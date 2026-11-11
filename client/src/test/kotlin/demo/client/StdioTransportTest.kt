package demo.client

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Starts the client and the server as two separate processes and connects them with two FIFOs,
 * the same way as a manual run.
 */
class StdioTransportTest {
    private val serverLauncher = launcher("server.launcher")
    private val clientLauncher = launcher("client.launcher")
    private val wiring = FifoWiring()

    @AfterTest
    fun cleanUp() = wiring.close()

    @Test
    fun `separately started client and server use gRPC over two FIFOs and open no sockets`() {
        val server = wiring.startServer(listOf(serverLauncher))
        val client = wiring.startClient(listOf(clientLauncher), env = mapOf("DEMO_HOLD_SECONDS" to "3"))

        // The client holds the connection open after the calls. Inspect both processes now.
        val hold = client.awaitLog(Regex("""HOLD client=(\d+) server=(\d+)"""))
        assertEquals(client.process.pid(), hold.groupValues[1].toLong(), "Client PID")
        assertEquals(server.process.pid(), hold.groupValues[2].toLong(), "The replies must come from the server process")
        for (peer in listOf(client, server)) {
            val fds = Lsof.fileDescriptors(peer.process.pid())
            println("${peer.name} fds:\n" + fds.joinToString("\n") { "  $it" })
            assertEquals(emptyList(), fds.filter { it.isSocket }, "${peer.name} must have no sockets")
        }
        assertFifo(server, fd = "0", wiring.clientToServer)
        assertFifo(server, fd = "1", wiring.serverToClient)
        assertFifo(client, fd = "0", wiring.serverToClient)
        assertFifo(client, fd = "1", wiring.clientToServer)

        assertEquals(0, client.awaitExit(), "Client exit code. Log:\n${client.log()}")
        assertEquals(0, server.awaitExit(), "Server exit code. Log:\n${server.log()}")
        val log = client.log()
        assertContains(log, "unary            -> Hello, stdio!")
        assertContains(log, "server streaming -> [5, 4, 3, 2, 1, 0]")
        assertContains(log, "client streaming -> 8388608 bytes, sha256 match=true")
        assertContains(log, "bidi streaming   -> 100 chunks, match=true")
        assertContains(server.log(), "client closed the connection")
    }

    @Test
    fun `a server that exits immediately fails the call with UNAVAILABLE`() {
        wiring.startServer(listOf("sh", "-c", "exit 3"))
        val client = wiring.startClient(listOf(clientLauncher))
        assertEquals(1, client.awaitExit(), client.log())
        assertContains(client.log(), "RPC failed: UNAVAILABLE")
    }

    @Test
    fun `a server that writes non-HTTP2 bytes fails the call with INTERNAL`() {
        wiring.startServer(listOf("sh", "-c", "echo 'not grpc'; sleep 5"))
        val client = wiring.startClient(listOf(clientLauncher))
        assertEquals(1, client.awaitExit(), client.log())
        assertContains(client.log(), "RPC failed: INTERNAL")
    }

    private fun assertFifo(peer: Peer, fd: String, fifo: File) {
        val entry = Lsof.fileDescriptors(peer.process.pid()).single { it.fd == fd }
        assertEquals("FIFO", entry.type, "${peer.name} fd $fd type")
        assertTrue(entry.name.endsWith(fifo.name), "${peer.name} fd $fd must be ${fifo.name}, but was ${entry.name}")
    }

    private fun launcher(property: String): String =
        System.getProperty(property) ?: error("Set -D$property to the start script (Gradle does it)")
}

class Peer(val name: String, val process: Process, private val logFile: File) {
    fun log(): String = logFile.readText()

    fun awaitLog(pattern: Regex, timeoutSeconds: Long = 60): MatchResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            pattern.find(log())?.let { return it }
            if (!process.isAlive) fail("$name exited before '$pattern'. Log:\n${log()}")
            Thread.sleep(100)
        }
        fail("$name didn't log '$pattern' in ${timeoutSeconds}s. Log:\n${log()}")
    }

    fun awaitExit(timeoutSeconds: Long = 60): Int {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) fail("$name didn't exit. Log:\n${log()}")
        return process.exitValue()
    }
}

/** Two FIFOs in a temporary directory, and the processes connected to them. */
class FifoWiring : AutoCloseable {
    private val dir = Files.createTempDirectory("stdio-grpc").toFile()
    val clientToServer = File(dir, "c2s")
    val serverToClient = File(dir, "s2c")
    private val peers = mutableListOf<Peer>()

    init {
        val mkfifo = ProcessBuilder("mkfifo", clientToServer.path, serverToClient.path).inheritIO().start()
        check(mkfifo.waitFor() == 0) { "mkfifo failed" }
    }

    // A shell opens the FIFOs, not this JVM, because the open of a FIFO blocks until its other end is open.
    // The server opens c2s first and the client opens c2s first too, so the two opens can't deadlock.
    fun startServer(command: List<String>) =
        start("server", command, """exec "$@" < "${'$'}C2S" > "${'$'}S2C"""", emptyMap())

    fun startClient(command: List<String>, env: Map<String, String> = emptyMap()) =
        start("client", command, """exec "$@" > "${'$'}C2S" < "${'$'}S2C"""", env)

    private fun start(name: String, command: List<String>, script: String, env: Map<String, String>): Peer {
        val logFile = File(dir, "$name.log")
        val builder = ProcessBuilder(listOf("sh", "-c", script, "sh") + command)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(logFile)
        builder.environment() += env + mapOf("C2S" to clientToServer.path, "S2C" to serverToClient.path)
        return Peer(name, builder.start(), logFile).also { peers += it }
    }

    override fun close() {
        peers.forEach { it.process.destroyForcibly() }
        dir.deleteRecursively()
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
