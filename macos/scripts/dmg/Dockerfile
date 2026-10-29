# Tools to make a macOS disk image on Linux, the way Mozilla builds Firefox's: mkfs.hfsplus
# (hfsprogs), then libdmg-hfsplus's `hfsplus` (fills the HFS+ image without mounting it) and
# `dmg` (compresses it into a UDIF .dmg).
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      hfsprogs cmake make gcc g++ git ca-certificates zlib1g-dev libbz2-dev libssl-dev liblzma-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 https://github.com/mozilla/libdmg-hfsplus /src/libdmg \
    && cmake -S /src/libdmg -B /src/build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build /src/build -j4 \
    && cp /src/build/dmg/dmg /src/build/hfs/hfsplus /usr/local/bin/
