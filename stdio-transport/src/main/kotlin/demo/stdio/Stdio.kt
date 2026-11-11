package demo.stdio

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The address of the peer at the other end of this process's stdin and stdout. It isn't a network address.
 *
 * gRPC uses [toString] as the HTTP/2 `:authority`.
 */
public object StdioAddress : SocketAddress() {
    private fun readResolve(): Any = StdioAddress
    override fun toString(): String = "stdio"
}

/** The stdin and stdout of this process, as raw streams for gRPC. */
internal class StdioStreams private constructor(val input: InputStream, val output: OutputStream) {
    companion object {
        private val taken = AtomicBoolean(false)

        /**
         * Takes over stdin and stdout. Sets [System.out] to stderr and [System.in] to an empty stream.
         * A `println` or a `readLine` then can't touch the HTTP/2 stream.
         */
        fun takeOver(): StdioStreams {
            check(taken.compareAndSet(false, true)) { "stdin and stdout are already in use for gRPC" }
            val input = FileInputStream(FileDescriptor.`in`)
            val output = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 64 * 1024)
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
            System.setIn(InputStream.nullInputStream())
            return StdioStreams(input, output)
        }
    }
}
