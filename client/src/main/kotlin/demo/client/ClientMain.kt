package demo.client

import demo.Chunk
import demo.CountRequest
import demo.Demo
import demo.HelloRequest
import demo.invoke
import demo.stdio.StdioClientTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.grpc.GrpcStatusException
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.grpc.description
import kotlinx.rpc.grpc.status
import kotlinx.rpc.grpc.statusCode
import kotlinx.rpc.withService
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val pid = ProcessHandle.current().pid()

/** Logs to stderr. In this process, stdout carries the gRPC stream. */
fun log(message: String) = System.err.println("[client pid=$pid] $message")

data class ScenarioResult(
    val hello: String,
    val serverPid: Long,
    val countdown: List<Int>,
    val uploadedBytes: Long,
    val uploadDigestMatches: Boolean,
    val echoedChunks: Int,
    val echoMatches: Boolean,
)

/** Runs one call of each gRPC call type. */
suspend fun runScenario(demo: Demo, uploadSize: Int = 8 * 1024 * 1024): ScenarioResult {
    // Unary.
    val hello = demo.sayHello(HelloRequest { name = "stdio" })

    // Server streaming.
    val countdown = demo.countdown(CountRequest { start = 5 }).toList().map { it.value }

    // Client streaming: more data than fits in the pipe buffer and the HTTP/2 flow-control window.
    val payload = Random(42).nextBytes(uploadSize)
    val digest = demo.upload(payload.chunked(64 * 1024))
    val expectedSha = MessageDigest.getInstance("SHA-256").digest(payload).toHexString()

    // Bidirectional streaming.
    val sent = (1..100).map { i -> ByteArray(i * 100) { i.toByte() } }
    val echoed = demo.echo(sent.asFlow().map { Chunk { data = ByteString(it) } }).toList()

    return ScenarioResult(
        hello = hello.message,
        serverPid = hello.serverPid,
        countdown = countdown,
        uploadedBytes = digest.totalBytes,
        uploadDigestMatches = digest.sha256 == expectedSha,
        echoedChunks = echoed.size,
        echoMatches = echoed.map { it.data.toByteArray().toList() } == sent.map { it.toList() },
    )
}

private fun ByteArray.chunked(size: Int): Flow<Chunk> = flow {
    for (offset in indices step size) {
        val end = minOf(offset + size, this@chunked.size)
        emit(Chunk { data = ByteString(this@chunked, offset, end) })
    }
}

/**
 * Connect stdin and stdout of this process to the server, for example with two FIFOs:
 * `client > c2s < s2c` and `server < c2s > s2c`.
 */
fun main() {
    val exitCode = runBlocking {
        val stdio = StdioClientTransport.install()
        val client = GrpcClient(stdio.target) {
            credentials = plaintext() // The pipes are private to the two processes.
        }
        try {
            val result = runScenario(client.withService<Demo>())
            log("server pid=${result.serverPid}")
            log("unary            -> ${result.hello}")
            log("server streaming -> ${result.countdown}")
            log("client streaming -> ${result.uploadedBytes} bytes, sha256 match=${result.uploadDigestMatches}")
            log("bidi streaming   -> ${result.echoedChunks} chunks, match=${result.echoMatches}")

            // Optional: keep the connection open. Tools such as `lsof` can then inspect both processes.
            System.getenv("DEMO_HOLD_SECONDS")?.toLong()?.let { seconds ->
                log("HOLD client=$pid server=${result.serverPid} for ${seconds}s")
                Thread.sleep(seconds * 1000)
            }
            0
        } catch (e: GrpcStatusException) {
            log("RPC failed: ${e.status.statusCode}: ${e.status.description}")
            1
        } finally {
            client.shutdown()
            client.awaitTermination(10.seconds)
            stdio.shutdown()
            log("disconnected")
        }
    }
    exitProcess(exitCode)
}
