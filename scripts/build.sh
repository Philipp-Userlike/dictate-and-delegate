#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d "$PWD/.tools/jdk/Contents/Home" ]; then
    export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-user}"
./gradlew testDebugUnitTest lintDebug assembleDebug "$@"
