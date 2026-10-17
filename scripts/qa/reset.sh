#!/bin/sh
# QA runs against the fake OpenRouter (scripted transcripts and replies, no provider calls) and the
# fake bb MCP server on the next port (custom address http://127.0.0.1:$((QA_PORT + 1))/mcp).
#   scripts/qa/reset.sh build   start the fake, build and install the app and the test pad
#   scripts/qa/reset.sh fresh   wipe Talk Router on the emulator: a first start, like a new user
# Uses ADB (default .tools/adb.sh, else adb) and QA_PORT (default 8798).
set -eu
cd "$(dirname "$0")/../.."
PORT=${QA_PORT:-8798}
BB_PORT=$((PORT + 1))
if [ -z "${ADB:-}" ]; then
    if [ -x .tools/adb.sh ]; then ADB=$PWD/.tools/adb.sh; else ADB=adb; fi
fi
OUT=$PWD/qa-out
mkdir -p "$OUT"

fake() {
    if ! lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        nohup python3 scripts/fakes/fake_openrouter.py --port "$PORT" >"$OUT/fake.log" 2>&1 &
        sleep 1
    fi
    $ADB reverse tcp:"$PORT" tcp:"$PORT" >/dev/null
    if ! lsof -iTCP:"$BB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        nohup python3 scripts/fakes/fake_bb_mcp.py --port "$BB_PORT" >"$OUT/fake-bb.log" 2>&1 &
        sleep 1
    fi
    $ADB reverse tcp:"$BB_PORT" tcp:"$BB_PORT" >/dev/null
}

case "${1:-fresh}" in
build)
    fake
    if [ -z "${JAVA_HOME:-}" ] && [ -d .tools/jdk-link/Contents/Home ]; then
        export JAVA_HOME=$PWD/.tools/jdk-link/Contents/Home
    fi
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-user}"
    ./gradlew -q --offline -PopenrouterUrl="http://127.0.0.1:$PORT/api/v1" assembleDebug assembleDebugAndroidTest
    $ADB install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
    $ADB install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
    echo "Built and installed for the fake OpenRouter on port $PORT."
    ;;
fresh)
    fake
    curl -s -X POST "http://127.0.0.1:$PORT/test/reset" >/dev/null
    curl -s -X POST "http://127.0.0.1:$BB_PORT/test/reset" >/dev/null
    $ADB shell am force-stop app.driftdictation
    $ADB shell pm clear app.driftdictation >/dev/null
    $ADB shell pm clear app.driftdictation.test >/dev/null 2>&1 || true
    $ADB shell pm revoke app.driftdictation android.permission.RECORD_AUDIO 2>/dev/null || true
    $ADB shell appops set app.driftdictation SYSTEM_ALERT_WINDOW default
    $ADB shell cmd statusbar remove-tile app.driftdictation/.DictationTile >/dev/null 2>&1 || true
    $ADB shell cmd locale set-app-locales app.driftdictation --locales en-US
    $ADB shell cmd statusbar collapse
    $ADB shell input keyevent KEYCODE_WAKEUP
    $ADB shell input keyevent KEYCODE_HOME
    echo "Talk Router is wiped: the next launch is a first start."
    ;;
*)
    echo "Usage: $0 build|fresh" >&2
    exit 1
    ;;
esac
echo "export ADB='$ADB' QA_SERVER=http://127.0.0.1:$PORT QA_BB=http://127.0.0.1:$BB_PORT/mcp"
