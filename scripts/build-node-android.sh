#!/usr/bin/env bash
# build-node-android.sh — produce the arm64 Node runtime that Grom supervises.
#
# The build host here has an x86_64-only NDK on an aarch64 machine, so Node
# cannot be compiled locally (blocker B1). This script therefore prints the
# exact Docker invocation instead of pretending to build.
#
# Output (after a successful run elsewhere):
#   app/src/main/assets/node/libnode.so     64-bit arm64 executable
#   app/src/main/assets/node/grom-agent.js  the agent entry point
#
# Both are copied into app-private storage on first launch (D24).

set -euo pipefail
cd "$(dirname "$0")/.."

NODE_VERSION="${NODE_VERSION:-22.14.0}"
OUT_DIR="${OUT_DIR:-$PWD/node-out}"

cat <<EOF
Grom agent runtime build
========================
target : linux-arm64 (Android, API 29+, static)
node   : v${NODE_VERSION}
output : ${OUT_DIR}

The local NDK is $(ls "${ANDROID_NDK_HOME:-/root/android-sdk/ndk}" 2>/dev/null | head -1) and is
x86_64-only on this host, so this machine cannot link an arm64 binary.
Run the following on an x86_64 host with Docker:

  mkdir -p "${OUT_DIR}"
  docker run --rm \\
    -v "${OUT_DIR}:/out" \\
    -v "\$PWD/scripts/node-build:/build" \\
    node:22-bookworm \\
    bash -lc '
      set -e
      apt-get update && apt-get install -y --no-install-recommends \\
        curl xz-utils ca-certificates
      cd /build
      curl -fsSLO https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}.tar.xz
      tar -xf node-v${NODE_VERSION}.tar.xz
      cd node-v${NODE_VERSION}
      ./configure --dest-cpu=arm64 --dest-os=linux --fully-static \\
                  --without-npm --without-corepack --openssl-no-asm
      make -j"\$(nproc)"
      cp out/Release/node /out/libnode.so
    '

Then:

  cp "${OUT_DIR}/libnode.so"   app/src/main/assets/node/libnode.so
  cp agent/grom-agent.js       app/src/main/assets/node/grom-agent.js
  gradle :app:assembleServer29Debug

Q8 — still open
----------------
Before relying on the runtime, answer on a real device:

  adb shell run-as app.grom.server \\
    files/node/libnode.so -e "require('node:sqlite'); console.log('sqlite ok')"

If that prints "sqlite ok", Q8 is closed. If it throws, Grom must not depend on
node:sqlite and the agent storage layer needs a different backend.

This script intentionally does NOT download a prebuilt runtime: Grom never
initiates outbound traffic (S1-R003) and a runtime must be an explicit operator
action, not a silent download.
EOF
