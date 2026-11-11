package demo.stdio

import io.netty.buffer.ByteBuf
import io.netty.channel.AbstractChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelMetadata
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelOutboundBuffer
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelConfig
import io.netty.channel.DefaultEventLoop
import io.netty.channel.EventLoop
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketAddress
import java.nio.channels.AlreadyConnectedException
import java.nio.channels.ClosedChannelException
import java.nio.channels.UnsupportedAddressTypeException
import java.util.concurrent.RejectedExecutionException

/**
 * A Netty byte channel on a pair of blocking streams, for example the stdin and stdout of a process.
 *
 * - Reads: a daemon thread reads from [InputStream] and gives each chunk to the event loop.
 *   A blocked write on the event loop doesn't stop the reads, so two processes can't deadlock
 *   when both pipe buffers are full.
 * - Writes: [doWrite] writes to [OutputStream] on the event loop. A full pipe blocks the event loop
 *   until the peer reads. gRPC (HTTP/2) flow control keeps the amount of data in flight small.
 *
 * The channel uses [DefaultEventLoop]s only. It doesn't open a selector or a socket.
 */
public abstract class PipeChannel protected constructor(parent: Channel?) : AbstractChannel(parent) {
    private enum class State { OPEN, ACTIVE, CLOSED }

    private val config = LenientChannelConfig(this)

    @Volatile
    private var state = State.OPEN

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    private var readerStarted = false

    /** Connects the channel to its streams. After this call, the channel is active. */
    protected fun attach(input: InputStream, output: OutputStream) {
        this.input = input
        this.output = output
        state = State.ACTIVE
    }

    /** Called on the event loop by [Channel.connect]. Must call [attach] or throw. */
    protected abstract fun doConnect(remoteAddress: SocketAddress)

    override fun config(): ChannelConfig = config

    override fun metadata(): ChannelMetadata = METADATA

    override fun isOpen(): Boolean = state != State.CLOSED

    override fun isActive(): Boolean = state == State.ACTIVE

    override fun isCompatible(loop: EventLoop): Boolean = loop is DefaultEventLoop

    override fun newUnsafe(): AbstractUnsafe = PipeUnsafe()

    override fun doBind(localAddress: SocketAddress): Unit = throw UnsupportedOperationException("bind")

    override fun doDisconnect(): Unit = doClose()

    override fun doClose() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        // Closing the output sends EOF to the peer. The peer then closes its side.
        runCatching { output?.close() }
        runCatching { input?.close() }
    }

    override fun doBeginRead() {
        if (readerStarted || state != State.ACTIVE) return
        readerStarted = true
        startReader(checkNotNull(input))
    }

    override fun doWrite(outbound: ChannelOutboundBuffer) {
        val out = output ?: throw ClosedChannelException()
        while (true) {
            val buf = outbound.current() as ByteBuf? ?: break
            val length = buf.readableBytes()
            if (length > 0) buf.getBytes(buf.readerIndex(), out, length)
            outbound.remove()
        }
        out.flush()
    }

    override fun filterOutboundMessage(msg: Any): Any =
        msg as? ByteBuf ?: throw UnsupportedOperationException("Unsupported message type: ${msg.javaClass.name}")

    private fun startReader(input: InputStream) {
        val loop = eventLoop()
        val reader = Thread({
            val chunk = ByteArray(READ_CHUNK_SIZE)
            var failure: IOException? = null
            try {
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count == 0) continue
                    val buf = config.allocator.heapBuffer(count).writeBytes(chunk, 0, count)
                    if (!submit(loop) { deliver(buf) }) {
                        buf.release()
                        break
                    }
                }
            } catch (e: IOException) {
                failure = e
            }
            // EOF or a read error: the peer is gone, so close the channel.
            submit(loop) {
                if (isOpen) {
                    failure?.let { pipeline().fireExceptionCaught(it) }
                    unsafe().close(unsafe().voidPromise())
                }
            }
        }, "pipe-reader-${id().asShortText()}")
        reader.isDaemon = true
        reader.start()
    }

    private fun deliver(buf: ByteBuf) {
        if (!isOpen) {
            buf.release()
            return
        }
        pipeline().fireChannelRead(buf)
        pipeline().fireChannelReadComplete()
    }

    private inner class PipeUnsafe : AbstractUnsafe() {
        override fun connect(remoteAddress: SocketAddress, localAddress: SocketAddress?, promise: ChannelPromise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) return
            if (state == State.ACTIVE) {
                promise.setFailure(AlreadyConnectedException())
                return
            }
            try {
                doConnect(remoteAddress)
                promise.trySuccess()
                pipeline().fireChannelActive()
            } catch (t: Throwable) {
                promise.tryFailure(t)
                closeIfClosed()
            }
        }
    }

    internal companion object {
        private const val READ_CHUNK_SIZE = 64 * 1024
        private val METADATA = ChannelMetadata(false)

        fun submit(loop: EventLoop, task: () -> Unit): Boolean = try {
            loop.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }
}

/**
 * Accepts and ignores socket options (`SO_KEEPALIVE`, `TCP_NODELAY`, ...).
 * gRPC sets them on every channel, and they don't apply to pipes.
 */
internal class LenientChannelConfig(channel: Channel) : DefaultChannelConfig(channel) {
    override fun <T : Any?> setOption(option: ChannelOption<T>, value: T): Boolean {
        super.setOption(option, value)
        return true
    }
}

/** Checks that [address] is of the type that a channel expects. */
internal inline fun <reified T : SocketAddress> SocketAddress.requireType(): T =
    this as? T ?: throw UnsupportedAddressTypeException()
