#!/bin/sh
# Signed release: scripts/release.sh 2.0.0 13
# Writes dist/Dictate-and-Delegate-<version>.apk (direct install) and .aab (Google Play upload).
set -eu
cd "$(dirname "$0")/.."
if [ $# -ne 2 ]; then
    echo 'Usage: scripts/release.sh <versionName> <versionCode>' >&2
    exit 1
fi
VERSION=$1
CODE=$2
if [ -z "${JAVA_HOME:-}" ] && [ -d "$PWD/.tools/jdk/Contents/Home" ]; then
    export JAVA_HOME="$PWD/.tools/jdk/Contents/Home"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-user}"
if [ ! -f .signing/release.jks ] || [ ! -f .signing/password ]; then
    echo 'The local release signing key and password file are required in .signing/.' >&2
    exit 1
fi
DRIFT_STORE_PASSWORD=$(cat .signing/password)
export DRIFT_STORE_PASSWORD
./gradlew -PversionName="$VERSION" -PversionCode="$CODE" \
    testDebugUnitTest lintRelease assembleRelease bundleRelease --console=plain
unset DRIFT_STORE_PASSWORD
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/Dictate-and-Delegate-$VERSION.apk"
cp app/build/outputs/bundle/release/app-release.aab "dist/Dictate-and-Delegate-$VERSION.aab"
(cd dist && shasum -a 256 "Dictate-and-Delegate-$VERSION.apk" "Dictate-and-Delegate-$VERSION.aab" > SHA256SUMS)
