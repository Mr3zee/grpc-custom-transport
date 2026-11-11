# gRPC over stdio with kotlinx-rpc

A kotlinx-rpc gRPC client and server in two separate JVM processes. Each process serves gRPC (HTTP/2) on its own stdin and stdout. You start both processes and connect them with two FIFOs (named pipes). Neither process opens a socket.

## Run

Requirements: JDK 21 and network access for the first Gradle build.

1. Build both apps:

    ```shell
    ./gradlew installDist
    ```

2. Make two FIFOs, one for each direction:

    ```shell
    mkfifo /tmp/c2s /tmp/s2c
    ```

3. In terminal 1, start the server:

    ```shell
    server/build/install/server/bin/server < /tmp/c2s > /tmp/s2c
    ```

4. In terminal 2, start the client:

    ```shell
    client/build/install/client/bin/client > /tmp/c2s < /tmp/s2c
    ```

You can start the two processes in any order. Each one waits until the other opens its end of the FIFOs. Keep the order of the redirections as shown, because the open of a FIFO blocks until its other end is open. Both processes open `c2s` first, so the two opens can't deadlock.

Both apps log to stderr, because stdout carries the gRPC stream. Example output:

```text
[server pid=63779] serving gRPC on stdio
[client pid=63788] server pid=63779
[client pid=63788] unary            -> Hello, stdio!
[client pid=63788] server streaming -> [5, 4, 3, 2, 1, 0]
[client pid=63788] client streaming -> 8388608 bytes, sha256 match=true
[client pid=63788] bidi streaming   -> 100 chunks, match=true
[client pid=63788] disconnected
[server pid=63779] client closed the connection, shutting down
```

The client exits with code 0 after the calls, or with code 1 if a call fails. The server exits with code 0 when the client closes the connection.

Other commands:

```shell
./gradlew :client:test  # Integration tests. They start both apps with FIFOs, as above.
./verify.sh             # Tests, then a run of both apps inside macOS sandboxes that deny all network access.
```

## Modules

| Module | Holds |
|---|---|
| `stdio-transport` | The Netty channels and the grpc-java providers. It depends on `grpc-netty` only, not on kotlinx-rpc. |
| `protocol` | `demo.proto` and the code that the kotlinx-rpc Gradle plugin generates from it. |
| `server` | `GrpcServer` with the `Demo` service. |
| `client` | `GrpcClient` that calls each RPC of `Demo` one time, and the integration tests. |

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
val stdio = StdioClientTransport.install() // Call before GrpcClient(...).
val client = GrpcClient(stdio.target) { credentials = plaintext() }
val demo = client.withService<Demo>()
```

Rules for both processes:

- Don't write to stdout. `install()` sets `System.out` to stderr, but a native library or a raw `FileDescriptor.out` can still write to the gRPC stream.
- Log to stderr.
- Each process has one connection. When the connection closes, a process can't connect again, because it can't open its stdin and stdout again.

## How it works

kotlinx-rpc 0.11.0-grpc-190 has no public API for a custom channel or server builder ([KXRPC-708](https://youtrack.jetbrains.com/issue/KXRPC-708)). It gets both from the grpc-java provider registries:

| kotlinx-rpc call | grpc-java call | Provider in this project |
|---|---|---|
| `GrpcClient(stdio.target) { credentials = ... }` | `Grpc.newChannelBuilder(target, creds)` | The provider from `StdioClientTransport.install()`. It handles only `stdio.target`, and other targets go to the Netty provider. |
| `GrpcServer(port)` | `ServerBuilder.forPort(port)` | The provider from `StdioServerTransport.install()`. It handles every port. |

Both providers have priority 10, so the registries try them before the Netty provider (priority 5). Each provider returns a normal `NettyChannelBuilder` or `NettyServerBuilder` with these settings:

| Setting | Client | Server |
|---|---|---|
| Channel | `StdioClientChannel`. `connect` attaches stdin and stdout. | `StdioServerChannel`. `bind` accepts one `StdioChildChannel` on stdin and stdout. |
| Address | `StdioAddress` | `StdioAddress` |
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
| The replies come from the other process (server PID in the reply = PID of the server process) | Pass |
| Start order: server first, or client first | Both pass |
| `lsof -i` and `lsof -U` on both JVMs while the connection is open | No sockets |
| `lsof` on fd 0 and fd 1 of both JVMs | The two FIFOs, cross-connected |
| Client and server, each inside `sandbox-exec` with `(deny network*)` | All calls pass |
| Control: `new ServerSocket(0)` inside the same sandbox | `SocketException: Operation not permitted` |
| The client closes the connection | Both processes exit with code 0 |
| The server command exits immediately | The call fails with `UNAVAILABLE`, and the client exits with code 1 |
| The server command writes bytes that aren't HTTP/2 | The call fails with `INTERNAL`, and the client exits with code 1 |

## Limits

- Any byte stream between the two processes works, not only FIFOs. For a server on another host, connect the FIFOs through `ssh host path/to/server`. The server host then doesn't need an open gRPC port.
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
