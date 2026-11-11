package demo.stdio

import io.grpc.ChannelCredentials
import io.grpc.ManagedChannelBuilder
import io.grpc.ManagedChannelProvider
import io.grpc.ManagedChannelRegistry
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.DefaultEventLoopGroup
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The address of a server that runs as a child process.
 *
 * To "connect", the client starts [command] and uses the stdin and stdout of the process as the connection.
 * The command can be any program that serves gRPC on its stdio, for example
 * `java -jar server.jar` or `ssh host java -jar server.jar`.
 *
 * The stderr of the child goes to the stderr of this process.
 *
 * gRPC uses [toString] as the HTTP/2 `:authority`. For this reason, [name] must be a valid host name.
 */
public class ProcessAddress(
    public val name: String,
    public val command: List<String>,
    public val onStart: (Process) -> Unit = {},
) : SocketAddress() {
    init {
        require(name.matches(Regex("[A-Za-z0-9.-]+"))) { "Name must be a valid host name, but was '$name'" }
        require(command.isNotEmpty()) { "Command must not be empty" }
    }

    override fun toString(): String = name
}

/** The client side of a [PipeChannel]: it starts a process on connect. */
internal class ProcessPipeChannel : PipeChannel(null) {
    @Volatile
    private var address: ProcessAddress? = null

    @Volatile
    private var process: Process? = null

    override fun doConnect(remoteAddress: SocketAddress) {
        val address = remoteAddress.requireType<ProcessAddress>()
        val process = ProcessBuilder(address.command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        this.address = address
        this.process = process
        attach(process.inputStream, process.outputStream)
        address.onStart(process)
    }

    override fun doClose() {
        super.doClose() // Closes the stdin of the child: a well-behaved server exits on EOF.
        val process = process ?: return
        process.onExit()
            .completeOnTimeout(process, EXIT_GRACE_SECONDS, TimeUnit.SECONDS)
            .thenRun { if (process.isAlive) process.destroyForcibly() }
    }

    override fun localAddress0(): SocketAddress? = null

    override fun remoteAddress0(): SocketAddress? = address

    private companion object {
        const val EXIT_GRACE_SECONDS = 5L
    }
}

/**
 * Client-side registration of stdio targets.
 *
 * ```
 * val target = StdioClientTransport.register(ProcessAddress("demo", listOf("java", "-jar", "server.jar")))
 * val client = GrpcClient(target) { credentials = plaintext() } // kotlinx-rpc
 * ```
 *
 * kotlinx-rpc gets its channel builder from `io.grpc.Grpc.newChannelBuilder(target, credentials)`.
 * That call gets the builder from the [ManagedChannelRegistry]. [StdioChannelProvider] has a higher priority
 * than Netty's own provider, so it gets the `stdio:` targets first. For all other targets, it gives an error,
 * and the registry falls back to the next provider.
 */
public object StdioClientTransport {
    public const val SCHEME: String = "stdio"

    private val targets = ConcurrentHashMap<String, ProcessAddress>()

    private val eventLoopGroup by lazy {
        DefaultEventLoopGroup(1, DefaultThreadFactory("stdio-client", true))
    }

    private val provider by lazy {
        StdioChannelProvider().also { ManagedChannelRegistry.getDefaultRegistry().register(it) }
    }

    /** Registers [address] and returns the gRPC target string for it (`stdio:<name>`). */
    public fun register(address: ProcessAddress): String {
        provider
        targets[address.name] = address
        return "$SCHEME:${address.name}"
    }

    internal fun builderFor(target: String, creds: ChannelCredentials?): NettyChannelBuilder? {
        if (!target.startsWith("$SCHEME:")) return null
        val name = target.removePrefix("$SCHEME:").removePrefix("//")
        val address = targets[name] ?: throw IllegalArgumentException("Unknown stdio target '$target'")
        val builder = if (creds == null) {
            NettyChannelBuilder.forAddress(address)
        } else {
            NettyChannelBuilder.forAddress(address, creds)
        }
        return builder
            .channelFactory { ProcessPipeChannel() }
            .eventLoopGroup(eventLoopGroup)
            // One process is one connection. Don't close it when the channel is idle.
            .idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS)
    }

    private class StdioChannelProvider : ManagedChannelProvider() {
        override fun isAvailable(): Boolean = true

        // Netty registers itself with priority 5.
        override fun priority(): Int = 10

        // Used by `ManagedChannelBuilder.forAddress/forTarget` (no credentials). Non-stdio targets go to Netty.
        override fun builderForAddress(name: String, port: Int): ManagedChannelBuilder<*> =
            NettyChannelBuilder.forAddress(name, port)

        override fun builderForTarget(target: String): ManagedChannelBuilder<*> =
            builderFor(target, null) ?: NettyChannelBuilder.forTarget(target)

        // Used by `Grpc.newChannelBuilder(target, credentials)`. kotlinx-rpc calls this function.
        override fun newChannelBuilder(target: String, creds: ChannelCredentials): NewChannelBuilderResult {
            val builder = builderFor(target, creds)
                ?: return NewChannelBuilderResult.error("Not a '$SCHEME:' target")
            return NewChannelBuilderResult.channelBuilder(builder)
        }

        // `stdio:x` has no name resolver, so the registry expects the address type of the default (DNS) resolver.
        // The builder uses a direct address, so the name resolver is never used.
        override fun getSupportedSocketAddressTypes(): Collection<Class<out SocketAddress>> =
            listOf(InetSocketAddress::class.java, ProcessAddress::class.java)
    }
}
