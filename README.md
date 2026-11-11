# gRPC over stdio with kotlinx-rpc

A kotlinx-rpc gRPC client and server in two JVM processes. The gRPC HTTP/2 stream goes over the stdin and stdout of the server process. Neither process opens a socket.

## Run

Requirements: JDK 21 and network access for the first Gradle build.

```shell
./gradlew :client:run   # Builds both apps. The client starts the server and calls each RPC once.
./gradlew :client:test  # Integration tests.
./verify.sh             # Tests, then a run inside a macOS sandbox that denies all network access.
```

Example output of `:client:run`:

```text
[server pid=58690] serving gRPC on stdio
[client pid=58673] unary            -> Hello, stdio!
[client pid=58673] server streaming -> [5, 4, 3, 2, 1, 0]
[client pid=58673] client streaming -> 8388608 bytes, sha256 match=true
[client pid=58673] bidi streaming   -> 100 chunks, match=true
[server pid=58690] client closed the connection, shutting down
[client pid=58673] server exited with code 0
```

## Modules

| Module | Holds |
|---|---|
| `stdio-transport` | The Netty channels and the grpc-java providers. It depends on `grpc-netty` only, not on kotlinx-rpc. |
| `protocol` | `demo.proto` and the code that the kotlinx-rpc Gradle plugin generates from it. |
| `server` | `GrpcServer` with the `Demo` service. Serves on its own stdin and stdout. |
| `client` | `GrpcClient`. Starts the server as a child process. |

## Use

Server:

```kotlin
val stdio = StdioServerTransport.install() // Call before GrpcServer(...).
val server = GrpcServer(port = 0) {        // The port is ignored.
    services { registerService<Demo> { DemoService() } }
}
server.start()
stdio.connectionClosed.await()             // Completes on EOF on stdin.
server.shutdown()
```

Client:

```kotlin
val target = StdioClientTransport.register(ProcessAddress("demo-server", listOf("path/to/server")))
val client = GrpcClient(target) { credentials = plaintext() }
val demo = client.withService<Demo>()
```

Rules for the server process:

- Don't write to stdout. `install()` redirects `System.out` to stderr, but a native library or a raw `FileDescriptor.out` can still write to the gRPC stream.
- Log to stderr. The client copies the stderr of the server to its own stderr.
- One process serves one gRPC server and one client connection.

## How it works

kotlinx-rpc 0.11.0-grpc-190 has no public API for a custom channel or server builder. It gets both from the grpc-java provider registries:

| kotlinx-rpc call | grpc-java call | Provider in this project |
|---|---|---|
| `GrpcClient(target) { credentials = ... }` | `Grpc.newChannelBuilder(target, creds)` | `StdioChannelProvider`. It handles only `stdio:` targets, and other targets go to the Netty provider. |
| `GrpcServer(port)` | `ServerBuilder.forPort(port)` | The provider from `StdioServerTransport.install()`. It handles every port. |

Both providers have priority 10, so the registries try them before the Netty provider (priority 5). Each provider returns a normal `NettyChannelBuilder` or `NettyServerBuilder` with these settings:

| Setting | Client | Server |
|---|---|---|
| Channel | `ProcessPipeChannel`. `connect` starts the server process. | `StdioServerChannel`. `bind` accepts one `StdioChildChannel` on stdin and stdout. |
| Address | `ProcessAddress` | `StdioListenAddress` |
| Event loops | `DefaultEventLoopGroup` | `DefaultEventLoopGroup` |

`DefaultEventLoopGroup` runs tasks only. It doesn't open a selector or a socket.

`PipeChannel` is the base class of both connection channels:

- Reads: a daemon thread reads the input stream and gives each chunk to the event loop. A blocked write doesn't stop the reads of the same process, so the two processes can't deadlock.
- Writes: the event loop writes to the output stream. A full pipe blocks the event loop until the peer reads. HTTP/2 flow control limits the amount of data in flight.
- EOF: the channel closes. The client then gets `UNAVAILABLE`, and the server shuts down.

## Verification

| Check | Result |
|---|---|
| Unary, server-streaming, client-streaming (8 MiB, SHA-256 compared), and bidi-streaming calls | Pass |
| The reply comes from the child JVM (server PID in the reply = PID of the child process) | Pass |
| `lsof -i` and `lsof -U` on both JVMs while the connection is open | No sockets |
| `lsof` on fd 0 and fd 1 of the server | Two pipes, connected to two pipes in the client |
| Client and server inside `sandbox-exec` with `(deny network*)` | All calls pass |
| Control: `new ServerSocket(0)` inside the same sandbox | `SocketException: Operation not permitted` |
| The client closes the connection | The server exits with code 0 |
| The server command exits immediately | The call fails with `UNAVAILABLE` |
| The server command writes bytes that aren't HTTP/2 | The call fails with `INTERNAL` |

## Limits

- The client starts a new server process on each connection. If the connection fails, gRPC reconnects with backoff and starts a new process. The channel disables idle mode, so an idle channel keeps its process.
- Each server process serves one connection. A `ManagedChannel` uses one connection, so one process is enough for one channel.
- The command in `ProcessAddress` can be any program that serves gRPC on stdio. For a server on another host, use `ssh host path/to/server`. The server host then doesn't need an open gRPC port.
- grpc-netty logs `TcpMetrics ... Epoll available` at `INFO` at startup. The message is about native transport detection. grpc-netty doesn't open a socket for it.
- The Gradle build shows deprecation warnings about multi-string dependency notation. They come from the kotlinx-rpc Gradle plugin.

## Versions

| Component | Version |
|---|---|
| kotlinx-rpc (gRPC dev build) | 0.11.0-grpc-190 from `https://redirector.kotlinlang.org/maven/kxrpc-grpc` |
| Kotlin | 2.4.20 |
| grpc-java | 1.81.0 (the version that kotlinx-rpc depends on) |
| Netty | 4.1.132.Final (from grpc-netty) |
| Gradle | 9.9.0 |
| JDK | 21 |
