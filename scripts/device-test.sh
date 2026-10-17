#!/bin/sh
# Instrumented tests against the fake OpenRouter (scripts/fakes/fake_openrouter.py), the fake bb
# MCP server (scripts/fakes/fake_bb_mcp.py) and the fake ntfy (scripts/fakes/fake_ntfy.py): no
# provider calls, no money, no real bb or ntfy. Uses ANDROID_SERIAL / ANDROID_ADB_SERVER_PORT from
# the environment, ADB for the adb binary, and DRIFT_TEST_PORT for the OpenRouter fake's port
# (default 8797); the bb fake listens on the next one, the ntfy fake on the one after.
set -eu
cd "$(dirname "$0")/.."
PORT=${DRIFT_TEST_PORT:-8797}
BB_PORT=$((PORT + 1))
NTFY_PORT=$((PORT + 2))
for p in "$PORT" "$BB_PORT" "$NTFY_PORT"; do
    if lsof -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "Port $p is in use; set DRIFT_TEST_PORT." >&2
        exit 1
    fi
done
LOG=$(mktemp -t drift-fake-openrouter)
BB_LOG=$(mktemp -t drift-fake-bb)
NTFY_LOG=$(mktemp -t drift-fake-ntfy)
python3 scripts/fakes/fake_openrouter.py --port "$PORT" >"$LOG" 2>&1 &
FAKE=$!
python3 scripts/fakes/fake_bb_mcp.py --port "$BB_PORT" >"$BB_LOG" 2>&1 &
BB_FAKE=$!
python3 scripts/fakes/fake_ntfy.py --port "$NTFY_PORT" >"$NTFY_LOG" 2>&1 &
NTFY_FAKE=$!
trap 'kill $FAKE $BB_FAKE $NTFY_FAKE 2>/dev/null; rm -f "$LOG" "$BB_LOG" "$NTFY_LOG"' EXIT
sleep 1
ADB=${ADB:-adb}
"$ADB" reverse tcp:"$PORT" tcp:"$PORT"
"$ADB" reverse tcp:"$BB_PORT" tcp:"$BB_PORT"
"$ADB" reverse tcp:"$NTFY_PORT" tcp:"$NTFY_PORT"
if [ -z "${JAVA_HOME:-}" ]; then
    for jdk in "$PWD/.tools/jdk/Contents/Home" "$PWD/.tools/jdk-link/Contents/Home"; do
        if [ -d "$jdk" ]; then export JAVA_HOME="$jdk"; break; fi
    done
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-user}"
./gradlew -PopenrouterUrl="http://127.0.0.1:$PORT/api/v1" -PanthropicUrl="http://127.0.0.1:$PORT" connectedDebugAndroidTest --console=plain "$@"
