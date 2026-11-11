package demo.stdio

import io.grpc.ChannelCredentials
import io.grpc.ManagedChannelBuilder
import io.grpc.ManagedChannelProvider
import io.grpc.ManagedChannelRegistry
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.DefaultEventLoopGroup
import io.netty.util.concurrent.DefaultThreadFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The client side of a stdio connection: it uses the stdin and stdout of this process.
 * The streams can be connected only one time, so a reconnect fails with an [IOException].
 */
internal class StdioClientChannel(
    private val streams: AtomicReference<StdioStreams?>,
    private val connectionClosed: CompletableFuture<Unit>,
) : PipeChannel(null) {
    override fun doConnect(remoteAddress: SocketAddress) {
        remoteAddress.requireType<StdioAddress>()
        val streams = streams.getAndSet(null)
            ?: throw IOException("The stdio connection was closed. stdin and stdout can't be connected again.")
        attach(streams.input, streams.output)
        closeFuture().addListener { connectionClosed.complete(Unit) }
    }

    override fun localAddress0(): SocketAddress? = if (isActive) StdioAddress else null

    override fun remoteAddress0(): SocketAddress? = if (isActive) StdioAddress else null
}

/**
 * Client-side setup. Call [install] one time, before the `GrpcClient(...)` call:
 *
 * ```
 * val stdio = StdioClientTransport.install()
 * val client = GrpcClient(stdio.target) { credentials = plaintext() }
 * ```
 *
 * kotlinx-rpc gets its channel builder from `io.grpc.Grpc.newChannelBuilder(target, credentials)`.
 * That call gets the builder from the [ManagedChannelRegistry]. The provider that [install] registers has
 * a higher priority than Netty's own provider, so it gets [target] first. For all other targets, it gives
 * an error, and the registry falls back to the next provider.
 */
public class StdioClientTransport private constructor(streams: StdioStreams) {
    /** The gRPC target string of the peer on stdio. */
    public val target: String = TARGET

    /** Completes when the connection closes: the client shut down, or the peer closed its end. */
    public val connectionClosed: CompletableFuture<Unit> = CompletableFuture()

    private val streams = AtomicReference<StdioStreams?>(streams)
    private val eventLoopGroup = DefaultEventLoopGroup(1, DefaultThreadFactory("stdio-client", true))
    private val provider = StdioChannelProvider()

    /** Stops the event loop. Call it after the gRPC client stops. */
    public fun shutdown() {
        ManagedChannelRegistry.getDefaultRegistry().deregister(provider)
        eventLoopGroup.shutdownGracefully()
    }

    private fun builderFor(target: String, creds: ChannelCredentials?): NettyChannelBuilder? {
        if (target != TARGET) return null
        val builder = if (creds == null) {
            NettyChannelBuilder.forAddress(StdioAddress)
        } else {
            NettyChannelBuilder.forAddress(StdioAddress, creds)
        }
        return builder
            .channelFactory { StdioClientChannel(streams, connectionClosed) }
            .eventLoopGroup(eventLoopGroup)
            // stdio is one connection for the life of the process. Don't close it when the channel is idle.
            .idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS)
    }

    private inner class StdioChannelProvider : ManagedChannelProvider() {
        override fun isAvailable(): Boolean = true

        // Netty registers itself with priority 5.
        override fun priority(): Int = 10

        // Used by `ManagedChannelBuilder.forAddress/forTarget` (no credentials). Other targets go to Netty.
        override fun builderForAddress(name: String, port: Int): ManagedChannelBuilder<*> =
            NettyChannelBuilder.forAddress(name, port)

        override fun builderForTarget(target: String): ManagedChannelBuilder<*> =
            builderFor(target, null) ?: NettyChannelBuilder.forTarget(target)

        // Used by `Grpc.newChannelBuilder(target, credentials)`. kotlinx-rpc calls this function.
        override fun newChannelBuilder(target: String, creds: ChannelCredentials): NewChannelBuilderResult {
            val builder = builderFor(target, creds)
                ?: return NewChannelBuilderResult.error("Not the '$TARGET' target")
            return NewChannelBuilderResult.channelBuilder(builder)
        }

        // `stdio:` has no name resolver, so the registry expects the address type of the default (DNS) resolver.
        // The builder uses a direct address, so the name resolver is never used.
        override fun getSupportedSocketAddressTypes(): Collection<Class<out SocketAddress>> =
            listOf(InetSocketAddress::class.java, StdioAddress::class.java)
    }

    public companion object {
        private const val TARGET = "stdio:peer"

        /**
         * Takes over the stdin and stdout of this process for gRPC and registers the stdio channel provider.
         *
         * Sets [System.out] to [System.err]. A `println` then can't write into the HTTP/2 stream.
         */
        public fun install(): StdioClientTransport =
            StdioClientTransport(StdioStreams.takeOver()).also {
                ManagedChannelRegistry.getDefaultRegistry().register(it.provider)
            }
    }
}
