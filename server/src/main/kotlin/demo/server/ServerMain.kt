package demo.server

import demo.Chunk
import demo.CountReply
import demo.CountRequest
import demo.Demo
import demo.Digest
import demo.HelloReply
import demo.HelloRequest
import demo.invoke
import demo.stdio.StdioServerTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.grpc.server.GrpcServer
import kotlinx.rpc.registerService
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

private val pid = ProcessHandle.current().pid()

/** Logs to stderr. In this process, stdout carries the gRPC stream. */
fun log(message: String) = System.err.println("[server pid=$pid] $message")

class DemoService : Demo {
    override suspend fun sayHello(message: HelloRequest): HelloReply = HelloReply {
        this.message = "Hello, ${message.name}!"
        serverPid = pid
    }

    override fun countdown(message: CountRequest): Flow<CountReply> = flow {
        for (value in message.start downTo 0) {
            emit(CountReply { this.value = value })
            delay(10)
        }
    }

    override suspend fun upload(message: Flow<Chunk>): Digest {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        message.collect { chunk ->
            val bytes = chunk.data.toByteArray()
            sha.update(bytes)
            total += bytes.size
        }
        return Digest {
            totalBytes = total
            sha256 = sha.digest().toHexString()
        }
    }

    override fun echo(message: Flow<Chunk>): Flow<Chunk> = message.map { it }
}

fun main(): Unit = runBlocking {
    val stdio = StdioServerTransport.install()

    // The stdio server provider ignores the port. It binds to stdin and stdout, not to a socket.
    val server = GrpcServer(port = 0) {
        services {
            registerService<Demo> { DemoService() }
        }
    }
    server.start()
    log("serving gRPC on stdio")

    stdio.connectionClosed.await()
    log("client closed the connection, shutting down")
    server.shutdown()
    server.awaitTermination(5.seconds)
    stdio.shutdown()
    log("bye")
}
