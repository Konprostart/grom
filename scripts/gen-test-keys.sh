#!/usr/bin/env bash
# gen-test-keys.sh — regenerate the TEST-ONLY keystores used by the signature gate.
#
# These keys are committed on purpose (D4) so that C-008 is reproducible.
# They are PUBLIC BY DESIGN and must never sign a distributed build.
#
#   keys/grom-test-same.jks     signs app/, sdk consumers and testapps/samecert
#   keys/grom-test-foreign.jks  signs testapps/foreigncert only
#
# Usage: scripts/gen-test-keys.sh [--force]

set -euo pipefail
cd "$(dirname "$0")/.."

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

mkdir -p keys

gen() {
  local out="$1" cn="$2"
  if [ -f "$out" ] && [ "$FORCE" -ne 1 ]; then
    echo "keep: $out (use --force to regenerate)"
    return
  fi
  rm -f "$out"
  keytool -genkeypair \
    -keystore "$out" \
    -storetype PKCS12 \
    -alias grom \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass gromtest -keypass gromtest \
    -dname "CN=$cn, OU=Grom, O=Grom Test, L=NA, ST=NA, C=RS" >/dev/null
  echo "wrote: $out"
}

gen keys/grom-test-same.jks    "Grom Test Same"
gen keys/grom-test-foreign.jks "Grom Test Foreign"

echo
echo "Fingerprints (SHA-256):"
for f in keys/grom-test-same.jks keys/grom-test-foreign.jks; do
  printf '  %-30s ' "$f"
  keytool -list -keystore "$f" -storepass gromtest -alias grom 2>/dev/null \
    | grep -i 'SHA256:' | sed 's/^[[:space:]]*//'
done

echo
echo "WARNING: these keys are public. Never sign a release with them."
