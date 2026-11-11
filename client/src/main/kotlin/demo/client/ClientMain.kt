package demo.client

import demo.Chunk
import demo.CountRequest
import demo.Demo
import demo.HelloRequest
import demo.invoke
import demo.stdio.ProcessAddress
import demo.stdio.StdioClientTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** A kotlinx-rpc client for a server in a child process. The connection uses the stdio of the child. */
class StdioDemoClient(serverCommand: List<String>) : AutoCloseable {
    /** Completes with the server process when the transport starts it. */
    val serverProcess = CompletableFuture<Process>()

    private val target = StdioClientTransport.register(
        ProcessAddress("demo-server", serverCommand) { serverProcess.complete(it) }
    )

    val grpc = GrpcClient(target) {
        credentials = plaintext() // The pipe is private to the two processes.
    }

    val demo: Demo = grpc.withService<Demo>()

    override fun close() = runBlocking {
        grpc.shutdown()
        grpc.awaitTermination(10.seconds)
    }
}

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

fun main(args: Array<String>): Unit = runBlocking {
    require(args.isNotEmpty()) { "Usage: client <server command...>" }
    val myPid = ProcessHandle.current().pid()
    println("[client pid=$myPid] server command: ${args.joinToString(" ")}")

    val client = StdioDemoClient(args.toList())
    val result = client.use {
        val result = runScenario(it.demo)
        // Optional: keep the connection open. Tools such as `lsof` can then inspect both processes.
        System.getenv("DEMO_HOLD_SECONDS")?.toLong()?.let { seconds ->
            println("[client pid=$myPid] HOLD client=$myPid server=${it.serverProcess.get().pid()} for ${seconds}s")
            Thread.sleep(seconds * 1000)
        }
        result
    }
    val server = client.serverProcess.get()
    println("[client pid=$myPid] server pid=${server.pid()} (reported by server: ${result.serverPid})")
    println("[client pid=$myPid] unary            -> ${result.hello}")
    println("[client pid=$myPid] server streaming -> ${result.countdown}")
    println("[client pid=$myPid] client streaming -> ${result.uploadedBytes} bytes, sha256 match=${result.uploadDigestMatches}")
    println("[client pid=$myPid] bidi streaming   -> ${result.echoedChunks} chunks, match=${result.echoMatches}")

    val exitCode = server.onExit().get().exitValue()
    println("[client pid=$myPid] server exited with code $exitCode")
}
