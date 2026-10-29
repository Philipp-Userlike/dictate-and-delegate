#!/bin/bash
# Runs an end-to-end test in the real app, headless (its own Xvfb), with a WAV as the microphone.
# Usage: scripts/e2e.sh <name>   (test/e2e/<name>.e2e.js)
set -eu -o pipefail
cd "$(dirname "$0")/.."
name=${1:-dictation}
tmp=$(mktemp -d)
python3 scripts/make-test-wav.py "$tmp/mic.wav" "${DD_WAV_SECONDS:-6}" "${DD_WAV_PATTERN:-dictation}"
exec 3>"$tmp/display"
Xvfb -displayfd 3 -screen 0 1440x900x24 -nolisten tcp >/dev/null 2>&1 &
xvfb=$!
trap 'kill $xvfb 2>/dev/null; rm -rf "$tmp"' EXIT
for _ in $(seq 50); do [ -s "$tmp/display" ] && break; sleep 0.1; done
export DISPLAY=":$(cat "$tmp/display")"
# Output to a file, not a pipe: Chromium's helper processes can outlive the app and hold a pipe open.
status=0
DD_E2E="$PWD/test/e2e/$name.e2e.js" DD_USER_DATA="$tmp/data" DD_FAKE_MIC="$tmp/mic.wav" \
  setsid node_modules/.bin/electron . --no-sandbox >"$tmp/out.log" 2>&1 || status=$?
pkill -KILL -f -- "--user-data-dir=$tmp/data" 2>/dev/null || true
grep -v -E "dbus|Fontconfig|libva|viz_main|gpu_init|Exiting GPU|sandbox_linux|command_buffer|GLES|angle|atom_cache|isEncryptionAvailable" "$tmp/out.log" || true
grep -q "^E2E .*: OK" "$tmp/out.log" && [ "$status" -eq 0 ]
