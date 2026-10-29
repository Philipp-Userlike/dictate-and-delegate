#!/bin/sh
# Makes a compressed .dmg of a signed .app (with an Applications link to drag it onto), in the
# dd-dmg-tools container (scripts/dmg/Dockerfile). Usage: make-dmg.sh <App.app> <out.dmg> [volume name]
set -eu
app=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
out_dir=$(cd "$(dirname "$2")" && pwd)
out=$(basename "$2")
volume=${3:-"Dictate & Delegate"}
docker image inspect dd-dmg-tools >/dev/null 2>&1 || docker build -q -t dd-dmg-tools "$(dirname "$0")/dmg"
# The image: the app's size plus room for HFS+ overhead.
mb=$(( $(du -sm "$app" | cut -f1) * 2 + 50 ))
docker run --rm -v "$app:/in/$(basename "$app"):ro" -v "$out_dir:/out" dd-dmg-tools sh -c "
  set -e
  dd if=/dev/zero of=/tmp/stage.hfs bs=1M count=$mb status=none
  mkfs.hfsplus -v '$volume' /tmp/stage.hfs >/dev/null
  hfsplus --symlinks clone_link /tmp/stage.hfs addall /in >/dev/null
  hfsplus /tmp/stage.hfs symlink /Applications /Applications >/dev/null
  dmg build /tmp/stage.hfs '/out/$out' >/dev/null
  chown $(id -u):$(id -g) '/out/$out'
"
