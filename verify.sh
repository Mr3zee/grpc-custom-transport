#!/usr/bin/env bash
# Shows that the client and server JVMs use gRPC over stdio and don't open sockets.
#
# 1. Runs the integration tests.
# 2. Control: shows that the sandbox blocks a plain `new ServerSocket(0)` (macOS only).
# 3. Runs client + server inside a sandbox that denies ALL network access (macOS only).
# 4. While the connection is open, lists the sockets and pipes of both JVMs with lsof.
set -euo pipefail
cd "$(dirname "$0")"

SANDBOX='(version 1)(allow default)(deny network*)'
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

run_sandboxed() {
  if [[ "$(uname)" == "Darwin" ]]; then sandbox-exec -p "$SANDBOX" "$@"; else "$@"; fi
}

echo "== 1. Build and run the integration tests"
./gradlew -q :server:installDist :client:installDist :client:test

if [[ "$(uname)" == "Darwin" ]]; then
  echo "== 2. Control: the sandbox blocks a plain ServerSocket"
  CONTROL_DIR="$(mktemp -d)"
  cat > "$CONTROL_DIR/OpenPort.java" <<'EOF'
public class OpenPort {
    public static void main(String[] a) throws Exception {
        try (var s = new java.net.ServerSocket(0)) { System.out.println("opened port " + s.getLocalPort()); }
    }
}
EOF
  if run_sandboxed java "$CONTROL_DIR/OpenPort.java" >/dev/null 2>&1; then
    echo "FAIL: the sandbox did not block the socket"; exit 1
  fi
  echo "OK: ServerSocket(0) fails inside the sandbox"
  rm -rf "$CONTROL_DIR"
else
  echo "== 2. Skipped: sandbox-exec is available on macOS only (lsof check still runs)"
fi

echo "== 3. Run client + server inside the sandbox"
DEMO_HOLD_SECONDS=3 run_sandboxed \
  client/build/install/client/bin/client "$PWD/server/build/install/server/bin/server" \
  </dev/null >"$LOG" 2>&1 &
DEMO_PID=$!

for _ in $(seq 1 120); do grep -q ' HOLD ' "$LOG" && break; sleep 0.25; done
if ! grep -q ' HOLD ' "$LOG"; then
  echo "FAIL: the client did not reach the hold point"; cat "$LOG"; exit 1
fi
read -r CLIENT SERVER < <(sed -nE 's/.*HOLD client=([0-9]+) server=([0-9]+).*/\1 \2/p' "$LOG")

echo "== 4. File descriptors while the connection is open (client=$CLIENT, server=$SERVER)"
SOCKETS="$( (lsof -n -P -a -p "$CLIENT,$SERVER" -i; lsof -n -P -a -p "$CLIENT,$SERVER" -U) || true)"
lsof -n -P -p "$CLIENT,$SERVER" | awk 'NR == 1 || $5 == "PIPE"'

wait "$DEMO_PID"
echo "== Client output"
grep -v -e TcpMetrics -e 'Epoll available' "$LOG"

if [[ -n "$SOCKETS" ]]; then
  echo "FAIL: sockets found:"; echo "$SOCKETS"; exit 1
fi
grep -q 'server exited with code 0' "$LOG" || { echo "FAIL: the server did not exit cleanly"; exit 1; }
[[ "$(grep -c 'match=true' "$LOG")" == 2 ]] || { echo "FAIL: data mismatch"; exit 1; }
echo "PASS: all RPCs succeeded, no sockets in either JVM, the server exited with code 0"
