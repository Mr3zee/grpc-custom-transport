#!/usr/bin/env bash
# Shows that the client and server JVMs use gRPC over stdio and don't open sockets.
#
# 1. Runs the integration tests.
# 2. Control: shows that the sandbox blocks a plain `new ServerSocket(0)` (macOS only).
# 3. Starts the server and the client as two processes, each in a sandbox that denies ALL network
#    access (macOS only), and connects them with two FIFOs.
# 4. While the connection is open, lists the sockets and FIFOs of both JVMs with lsof.
set -euo pipefail
cd "$(dirname "$0")"

SANDBOX='(version 1)(allow default)(deny network*)'
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

run_sandboxed() {
  if [[ "$(uname)" == "Darwin" ]]; then sandbox-exec -p "$SANDBOX" "$@"; else "$@"; fi
}

echo "== 1. Build and run the integration tests"
./gradlew -q installDist :client:test

if [[ "$(uname)" == "Darwin" ]]; then
  echo "== 2. Control: the sandbox blocks a plain ServerSocket"
  cat > "$WORK/OpenPort.java" <<'EOF'
public class OpenPort {
    public static void main(String[] a) throws Exception {
        try (var s = new java.net.ServerSocket(0)) { System.out.println("opened port " + s.getLocalPort()); }
    }
}
EOF
  if run_sandboxed java "$WORK/OpenPort.java" >/dev/null 2>&1; then
    echo "FAIL: the sandbox did not block the socket"; exit 1
  fi
  echo "OK: ServerSocket(0) fails inside the sandbox"
else
  echo "== 2. Skipped: sandbox-exec is available on macOS only (lsof check still runs)"
fi

echo "== 3. Start server and client separately, connected by two FIFOs"
mkfifo "$WORK/c2s" "$WORK/s2c"
# Redirection order matters: both processes open c2s first, so the blocking FIFO opens can't deadlock.
run_sandboxed server/build/install/server/bin/server <"$WORK/c2s" >"$WORK/s2c" 2>"$WORK/server.log" &
SERVER_JOB=$!
DEMO_HOLD_SECONDS=3 run_sandboxed client/build/install/client/bin/client >"$WORK/c2s" <"$WORK/s2c" 2>"$WORK/client.log" &
CLIENT_JOB=$!

for _ in $(seq 1 120); do grep -q ' HOLD ' "$WORK/client.log" && break; sleep 0.25; done
if ! grep -q ' HOLD ' "$WORK/client.log"; then
  echo "FAIL: the client did not reach the hold point"; cat "$WORK/server.log" "$WORK/client.log"; exit 1
fi
read -r CLIENT SERVER < <(sed -nE 's/.*HOLD client=([0-9]+) server=([0-9]+).*/\1 \2/p' "$WORK/client.log")

echo "== 4. File descriptors while the connection is open (client=$CLIENT, server=$SERVER)"
SOCKETS="$( (lsof -n -P -a -p "$CLIENT,$SERVER" -i; lsof -n -P -a -p "$CLIENT,$SERVER" -U) || true)"
lsof -n -P -p "$CLIENT,$SERVER" | awk 'NR == 1 || $5 == "FIFO" || $5 == "PIPE"'

CLIENT_EXIT=0; wait "$CLIENT_JOB" || CLIENT_EXIT=$?
SERVER_EXIT=0; wait "$SERVER_JOB" || SERVER_EXIT=$?
echo "== Logs"
grep -h -v -e TcpMetrics -e 'Epoll available' "$WORK/server.log" "$WORK/client.log"

if [[ -n "$SOCKETS" ]]; then
  echo "FAIL: sockets found:"; echo "$SOCKETS"; exit 1
fi
[[ "$CLIENT_EXIT" == 0 && "$SERVER_EXIT" == 0 ]] || { echo "FAIL: exit codes client=$CLIENT_EXIT server=$SERVER_EXIT"; exit 1; }
[[ "$(grep -c 'match=true' "$WORK/client.log")" == 2 ]] || { echo "FAIL: data mismatch"; exit 1; }
echo "PASS: all RPCs succeeded, no sockets in either JVM, both processes exited with code 0"
