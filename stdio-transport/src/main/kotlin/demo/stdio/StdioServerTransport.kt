package demo.stdio

import io.grpc.ServerBuilder
import io.grpc.ServerCredentials
import io.grpc.ServerProvider
import io.grpc.ServerRegistry
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.AbstractServerChannel
import io.netty.channel.ChannelConfig
import io.netty.channel.DefaultEventLoop
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.EventLoop
import io.netty.util.concurrent.DefaultThreadFactory
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** The "listen" address of a stdio server. It isn't a network address. */
public object StdioListenAddress : SocketAddress() {
    private fun readResolve(): Any = StdioListenAddress
    override fun toString(): String = "stdio"
}

/**
 * A Netty server channel that "accepts" exactly one connection: the stdin and stdout of this process.
 * gRPC's `NettyServer` binds it like a server socket.
 * Netty's `ServerBootstrap` then registers the accepted [StdioChildChannel] on the worker event loop.
 */
internal class StdioServerChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val connectionClosed: CompletableFuture<Unit>,
) : AbstractServerChannel() {
    private val config = LenientChannelConfig(this)

    @Volatile
    private var bound = false

    @Volatile
    private var closed = false

    private var accepted = false

    override fun config(): ChannelConfig = config

    override fun isOpen(): Boolean = !closed

    override fun isActive(): Boolean = bound && !closed

    override fun isCompatible(loop: EventLoop): Boolean = loop is DefaultEventLoop

    override fun localAddress0(): SocketAddress? = if (bound) StdioListenAddress else null

    override fun doBind(localAddress: SocketAddress) {
        localAddress.requireType<StdioListenAddress>()
        bound = true
    }

    override fun doClose() {
        closed = true
    }

    override fun doBeginRead() {
        if (accepted) return
        accepted = true
        val child = StdioChildChannel(this, input, output)
        child.closeFuture().addListener { connectionClosed.complete(Unit) }
        pipeline().fireChannelRead(child)
        pipeline().fireChannelReadComplete()
    }
}

/** The single connection of a [StdioServerChannel]. It is active from the start. */
internal class StdioChildChannel(
    parent: StdioServerChannel,
    input: InputStream,
    output: OutputStream,
) : PipeChannel(parent) {
    init {
        attach(input, output)
    }

    override fun doConnect(remoteAddress: SocketAddress): Unit = throw UnsupportedOperationException("connect")

    override fun localAddress0(): SocketAddress = StdioListenAddress

    override fun remoteAddress0(): SocketAddress = StdioListenAddress
}

/**
 * Server-side setup. Call [install] one time, before the `GrpcServer(...)` call:
 *
 * ```
 * val stdio = StdioServerTransport.install()
 * val server = GrpcServer(port = 0) { services { ... } } // the port is ignored
 * server.start()
 * stdio.connectionClosed.await()
 * ```
 *
 * kotlinx-rpc gets its server builder from `io.grpc.ServerBuilder.forPort(port)`.
 * That call uses the [ServerProvider] with the highest priority. [install] registers a provider with a higher priority
 * than Netty's. The provider ignores the port and gives a Netty server that serves on stdio.
 * As a result, nothing in this process can open a server port through grpc-java.
 */
public class StdioServerTransport private constructor(
    input: InputStream,
    output: OutputStream,
) {
    /** Completes when the client closes the connection (EOF on stdin). */
    public val connectionClosed: CompletableFuture<Unit> = CompletableFuture()

    private val bossGroup = DefaultEventLoopGroup(1, DefaultThreadFactory("stdio-boss", true))
    private val workerGroup = DefaultEventLoopGroup(1, DefaultThreadFactory("stdio-worker", true))
    private val used = AtomicBoolean(false)

    private val provider = object : ServerProvider() {
        override fun isAvailable(): Boolean = true

        // Netty registers itself with priority 5.
        override fun priority(): Int = 10

        override fun builderForPort(port: Int): ServerBuilder<*> =
            configure(NettyServerBuilder.forAddress(StdioListenAddress))

        override fun newServerBuilderForPort(port: Int, creds: ServerCredentials): NewServerBuilderResult =
            NewServerBuilderResult.serverBuilder(configure(NettyServerBuilder.forAddress(StdioListenAddress, creds)))

        private fun configure(builder: NettyServerBuilder): NettyServerBuilder {
            check(used.compareAndSet(false, true)) { "stdio can serve only one gRPC server per process" }
            return builder
                .channelFactory { StdioServerChannel(input, output, connectionClosed) }
                .bossEventLoopGroup(bossGroup)
                .workerEventLoopGroup(workerGroup)
        }
    }

    /** Stops the event loops. Call it after the gRPC server stops. */
    public fun shutdown() {
        ServerRegistry.getDefaultRegistry().deregister(provider)
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }

    public companion object {
        /**
         * Takes over the stdin and stdout of this process for gRPC and registers the stdio server provider.
         *
         * Sets [System.out] to [System.err]. A `println` then can't write into the HTTP/2 stream.
         */
        public fun install(): StdioServerTransport {
            val input = FileInputStream(FileDescriptor.`in`)
            val output = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 64 * 1024)
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
            System.setIn(InputStream.nullInputStream())
            return StdioServerTransport(input, output).also {
                ServerRegistry.getDefaultRegistry().register(it.provider)
            }
        }
    }
}
