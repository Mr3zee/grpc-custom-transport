package demo.client

import demo.HelloRequest
import demo.invoke
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.grpc.GrpcStatusCode
import kotlinx.rpc.grpc.GrpcStatusException
import kotlinx.rpc.grpc.status
import kotlinx.rpc.grpc.statusCode
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StdioTransportTest {
    private val serverLauncher = System.getProperty("server.launcher")
        ?: error("Set -Dserver.launcher to the server start script (Gradle does it)")

    @Test
    fun `all call types work over stdio, and the server opens no sockets`() = runBlocking {
        val client = StdioDemoClient(listOf(serverLauncher))
        val result = client.use {
            val result = runScenario(it.demo)
            val server = it.serverProcess.get(10, TimeUnit.SECONDS)

            // The connection is still open here. Inspect the server's file descriptors.
            // The test JVM isn't checked, because Gradle connects to its test workers over a local socket.
            val fds = Lsof.fileDescriptors(server.pid())
            println("server fds:\n" + fds.joinToString("\n") { "  $it" })
            assertEquals(emptyList(), fds.filter { it.isSocket }, "Server must have no sockets")
            assertEquals("PIPE", fds.single { it.fd == "0" }.type, "Server stdin must be a pipe")
            assertEquals("PIPE", fds.single { it.fd == "1" }.type, "Server stdout must be a pipe")
            result
        }

        assertEquals("Hello, stdio!", result.hello)
        assertEquals(client.serverProcess.get().pid(), result.serverPid, "The reply must come from the child JVM")
        assertEquals(listOf(5, 4, 3, 2, 1, 0), result.countdown)
        assertEquals(8L * 1024 * 1024, result.uploadedBytes)
        assertTrue(result.uploadDigestMatches)
        assertEquals(100, result.echoedChunks)
        assertTrue(result.echoMatches)

        // The client closed the pipe, so the server must shut down by itself.
        val server = client.serverProcess.get()
        assertTrue(server.waitFor(10, TimeUnit.SECONDS), "Server must exit after the client disconnects")
        assertEquals(0, server.exitValue())
    }

    @Test
    fun `a server that exits immediately fails the call with UNAVAILABLE`() =
        assertCallFails(listOf("sh", "-c", "exit 3"), GrpcStatusCode.UNAVAILABLE)

    @Test
    fun `a server that writes non-HTTP2 bytes fails the call with INTERNAL`() =
        assertCallFails(listOf("sh", "-c", "echo 'not grpc'; sleep 5"), GrpcStatusCode.INTERNAL)

    /** The call must fail fast with [expected], and must not hang. */
    private fun assertCallFails(command: List<String>, expected: GrpcStatusCode) = runBlocking {
        StdioDemoClient(command).use { client ->
            val error = withTimeout(10.seconds) {
                assertFailsWith<GrpcStatusException> { client.demo.sayHello(HelloRequest { name = "x" }) }
            }
            assertEquals(expected, error.status.statusCode, error.status.description)
        }
    }
}

/** A small `lsof` wrapper (macOS and Linux). */
object Lsof {
    data class Fd(val fd: String, val type: String, val name: String) {
        val isSocket: Boolean get() = type in SOCKET_TYPES
    }

    // lsof file types of network and IPC sockets (macOS and Linux).
    private val SOCKET_TYPES = setOf("IPv4", "IPv6", "unix", "sock", "systm", "ndrv", "netlink")

    fun fileDescriptors(pid: Long): List<Fd> {
        val process = ProcessBuilder("lsof", "-n", "-P", "-p", pid.toString(), "-F", "ftn")
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        check(process.exitValue() == 0) { "lsof failed:\n" + lines.joinToString("\n") }
        // Output has one field on each line: f<fd>, t<type>, n<name>. A new 'f' line starts a new fd.
        val result = mutableListOf<Fd>()
        var current: Fd? = null
        for (line in lines) {
            val value = line.drop(1)
            when (line.firstOrNull()) {
                'f' -> { current?.let(result::add); current = Fd(value, "", "") }
                't' -> current = current?.copy(type = value)
                'n' -> current = current?.copy(name = value)
            }
        }
        current?.let(result::add)
        return result
    }
}
