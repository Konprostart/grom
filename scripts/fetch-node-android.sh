#!/usr/bin/env bash
# fetch-node-android.sh — assemble the arm64 Android Node runtime Grom runs.
#
# This is the reproducible, verified replacement for "no libnode.so exists".
# It downloads a *bionic* (Android) Node build, normalises the shared-library
# names so every dependency matches `lib*.so` (required by the Android Gradle
# Plugin's jniLibs packaging), and — when the host can execute Android binaries
# through /system/bin/linker64 — proves the result by running it.
#
# Why a vendor build and not a cross-build here: building V8 needs an x86_64
# NDK toolchain (this repo's host may be aarch64) plus several GB of RAM.
# Prebuilt bionic builds are the same chromium/V8 code; what matters for D34 is
# that the binary is aarch64, bionic-linked, and verified by execution.
#
# Modes:
#   --all        fetch, assemble and verify (default)
#   --fetch      download the pinned packages into node-out/debs
#   --assemble   extract and normalise the runtime into node-out/runtime
#   --verify     execute the runtime: --version, node:sqlite (Q8), agent suite
#   --q8         print the Q8 result only
#   --clean      remove node-out
#
# Output:
#   node-out/runtime/libnode.so        executes as the agent binary
#   node-out/runtime/lib*.so           its dependencies, same directory
#   node-out/runtime/SHA256SUMS        integrity of the assembled runtime
#   node-out/runtime/MANIFEST          provenance (package versions + URLs)
#
# Grom itself never downloads anything (S1-R003, S3-R037); this script is an
# explicit operator/CI action, and `--verify` is the reason to trust it.

set -euo pipefail
cd "$(dirname "$0")/.."

REPO="$PWD"
OUT_DIR="${OUT_DIR:-$REPO/node-out}"
DEB_DIR="$OUT_DIR/debs"
RUNTIME_DIR="$OUT_DIR/runtime"
BINARY_NAME="libnode.so"
AGENT_SCRIPT="agent/grom-agent.js"

# Pinned provenance. Every package is a bionic aarch64 build from the Termux
# main repository (MIT/OpenSSL/ICU/Unicode/SQLite/zlib/BSD licences — see
# docs/THIRD-PARTY.md). The checksums pin what --verify then executes.
PACKAGES=(
  "nodejs-lts_24.22.3_aarch64.deb|pool/main/n/nodejs-lts/nodejs-lts_24.22.3_aarch64.deb|80edcc73db025db0baa09be27e3d65a16e6f2a72ab925f1873a1b808ab857c28"
  "libc++_30_aarch64.deb|pool/main/libc/libc++/libc++_30_aarch64.deb|53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"
  "openssl_1:3.6.5_aarch64.deb|pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb|a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b"
  "c-ares_1.34.9_aarch64.deb|pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb|623d84855e176f3f2a415d24c0e1d2f529356f4b5439bb3313e6d7ff0da4585c"
  "libicu_79.1_aarch64.deb|pool/main/libi/libicu/libicu_79.1_aarch64.deb|83c980a010e957738e3567127801d66a1b9bd7e404a4fda98788048ee57d606c"
  "libsqlite_3.54.1-1_aarch64.deb|pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb|661fb995f7215845007b222a85266ae99d9443e87e0604f8677edfd738e6b81a"
  "zlib_1.4_aarch64.deb|pool/main/z/zlib/zlib_1.4_aarch64.deb|e3027565ae50bcd8ba5113c988b589754ae192c9cc3e6afbe3e4ed7c06b2598e"
)
REPO_URL="${REPO_URL:-https://packages.termux.dev/apt/termux-main}"
NODE_VERSION="24.22.3"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  FAIL  %s\n' "$*" >&2; }
step() { printf '\n== %s ==\n' "$*"; }

need() { command -v "$1" >/dev/null 2>&1 || { bad "$1 is required"; exit 1; }; }

# --------------------------------------------------------------- bionic probe

# A host can execute Android binaries natively only when the Android linker is
# present. On the phone (Termux/proot) and on this repo's dev host it is; on a
# stock Linux CI box it is not, and --verify degrades to static checks.
linker64() {
  local c
  for c in /system/bin/linker64 /apex/com.android.runtime/bin/linker64; do
    [ -x "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

can_execute_android() { linker64 >/dev/null 2>&1; }

# ---------------------------------------------------------------- fetch phase

do_fetch() {
  step "fetch (pinned bionic aarch64 packages)"
  need curl
  mkdir -p "$DEB_DIR"
  local entry name path sum target
  for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name path sum <<<"$entry"
    target="$DEB_DIR/$name"
    if [ -f "$target" ] && echo "$sum  $target" | sha256sum -c --status 2>/dev/null; then
      ok "$name (cached, checksum matches)"
      continue
    fi
    say "  ..   $name"
    curl -fsSL --retry 3 -o "$target.part" "$REPO_URL/$path" || { bad "download failed: $name"; exit 1; }
    mv -f "$target.part" "$target"
    if ! echo "$sum  $target" | sha256sum -c --status; then
      bad "checksum mismatch for $name — refusing to use it"
      rm -f "$target"
      exit 1
    fi
    ok "$name"
  done
}

# ------------------------------------------------------------- assemble phase

do_assemble() {
  step "assemble (normalise to lib*.so and a single runtime dir)"
  need python3
  mkdir -p "$RUNTIME_DIR"
  python3 - "$DEB_DIR" "$RUNTIME_DIR" "$BINARY_NAME" <<'PY'
import io, os, shutil, sys, tarfile

deb_dir, out, binary_name = sys.argv[1:4]

# Android's packaging rules: a file becomes a native library only when its name
# is lib<name>.so. Termux SONAMEs carry version suffixes (libssl.so.3), so the
# copied files are renamed *and* their DT_NEEDED/DT_SONAME strings rewritten in
# place. Rewriting is same-length with NUL padding, which keeps every offset and
# string-table entry valid.

RENAME = {
    "libc++_shared.so": "libc++_shared.so",
    "libcares.so":      "libcares.so",
    "libsqlite3.so":    "libsqlite3.so",
    "libssl.so.3":      "libssl.so",
    "libcrypto.so.3":   "libcrypto.so",
    "libicuuc.so.79":   "libicuuc.so",
    "libicui18n.so.79": "libicui18n.so",
    "libicudata.so.79": "libicudata.so",
    "libz.so.1":        "libz.so",
}
SUBS = {
    b"libssl.so.3": b"libssl.so",
    b"libcrypto.so.3": b"libcrypto.so",
    b"libicuuc.so.79": b"libicuuc.so",
    b"libicui18n.so.79": b"libicui18n.so",
    b"libicudata.so.79": b"libicudata.so",
    b"libz.so.1": b"libz.so",
}

def read_ar(path):
    data = open(path, "rb").read()
    if data[:8] != b"!<arch>\n":
        raise SystemExit("not an ar archive: " + path)
    i, files = 8, {}
    while i + 60 <= len(data):
        hdr = data[i:i + 60]
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        files[name] = data[i + 60:i + 60 + size]
        i += 60 + size + (size % 2)
    return files

stage = os.path.join(out, ".stage")
shutil.rmtree(stage, ignore_errors=True)
os.makedirs(stage)

for name in sorted(os.listdir(deb_dir)):
    if not name.endswith(".deb"):
        continue
    members = read_ar(os.path.join(deb_dir, name))
    payload = next(m for m in members if m.startswith("data.tar"))
    tarfile.open(fileobj=io.BytesIO(members[payload])).extractall(stage)

prefix = os.path.join(stage, "data/data/com.termux/files/usr")
if not os.path.isdir(prefix):
    raise SystemExit("unexpected layout: no Termux prefix in payloads")

shutil.copy2(os.path.join(prefix, "bin/node"), os.path.join(out, binary_name))
os.chmod(os.path.join(out, binary_name), 0o755)

missing = []
for src, dst in RENAME.items():
    path = os.path.join(prefix, "lib", src)
    if not os.path.exists(path):
        missing.append(src)
        continue
    shutil.copy2(os.path.realpath(path), os.path.join(out, dst))
if missing:
    raise SystemExit("missing libraries: " + ", ".join(missing))

# Rewrite once, after every file is in place.
for name in os.listdir(out):
    path = os.path.join(out, name)
    if not os.path.isfile(path) or name == "MANIFEST":
        continue
    blob = open(path, "rb").read()
    patched = blob
    for old, new in SUBS.items():
        patched = patched.replace(old, new + b"\0" * (len(old) - len(new)))
    if patched != blob:
        open(path, "wb").write(patched)

shutil.rmtree(stage, ignore_errors=True)
print("  ok    %d files -> %s" % (len(os.listdir(out)), out))
PY
  # Provenance and integrity, written next to the runtime so an operator can
  # tell which build is installed on a phone.
  {
    echo "Grom agent runtime (bionic aarch64)"
    echo "node        : v$NODE_VERSION"
    echo "source      : $REPO_URL"
    echo "assembled   : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "packages    :"
    local entry name
    for entry in "${PACKAGES[@]}"; do
      IFS='|' read -r name _ _ <<<"$entry"
      echo "  $name"
    done
  } > "$RUNTIME_DIR/MANIFEST"
  ( cd "$RUNTIME_DIR" && sha256sum lib*.so > SHA256SUMS )
  ok "MANIFEST + SHA256SUMS written"
}

# --------------------------------------------------------------- verify phase

do_verify() {
  step "verify (static)"
  need python3
  [ -f "$RUNTIME_DIR/$BINARY_NAME" ] || { bad "no $RUNTIME_DIR/$BINARY_NAME — run --assemble"; exit 1; }
  python3 - "$RUNTIME_DIR" "$BINARY_NAME" <<'PY'
import os, struct, sys
runtime, binary = sys.argv[1:3]
path = os.path.join(runtime, binary)
head = open(path, "rb").read(20)
if head[:4] != b"\x7fELF":
    raise SystemExit("  FAIL  %s is not ELF" % binary)
machine = struct.unpack("<H", head[18:20])[0]
if machine != 183:
    raise SystemExit("  FAIL  e_machine=%d, expected 183 (aarch64)" % machine)
print("  ok    ELF aarch64")
blob = open(path, "rb").read(1 << 16)
if b"ld-linux" in blob:
    raise SystemExit("  FAIL  glibc-linked: cannot run on Android")
print("  ok    bionic (no ld-linux interpreter)")
names = sorted(n for n in os.listdir(runtime) if n.startswith("lib") and n.endswith(".so"))
if not names:
    raise SystemExit("  FAIL  no libraries beside the binary")
print("  ok    %d libraries co-located: %s" % (len(names), " ".join(names)))
PY
  if ( cd "$RUNTIME_DIR" && sha256sum -c --status SHA256SUMS 2>/dev/null ); then
    ok "SHA256SUMS match"
  else
    bad "SHA256SUMS do not match the assembled files"
    exit 1
  fi

  step "verify (execute)"
  if ! can_execute_android; then
    say "  skip  no Android linker on this host — static checks only"
    say "        run --verify on the phone (Termux/proot) to execute it"
    return 0
  fi
  local v
  v="$( LD_LIBRARY_PATH="$RUNTIME_DIR" HOME="${TMPDIR:-/tmp}" TMPDIR="${TMPDIR:-/tmp}" \
        timeout 120 "$RUNTIME_DIR/$BINARY_NAME" --version 2>&1 | grep -v 'WARNING: linker' | tail -1)"
  [ "$v" = "v$NODE_VERSION" ] || { bad "unexpected version: $v"; exit 1; }
  ok "executes through /system/bin/linker64: $v"

  local q8
  q8="$( LD_LIBRARY_PATH="$RUNTIME_DIR" HOME="${TMPDIR:-/tmp}" TMPDIR="${TMPDIR:-/tmp}" \
         timeout 120 "$RUNTIME_DIR/$BINARY_NAME" -e '
       const s = require("node:sqlite");
       const db = new s.DatabaseSync(":memory:");
       db.exec("create table t(a)");
       db.prepare("insert into t values (?)").run(7);
       console.log("Q8", process.version, "sqlite=" + db.prepare("select a from t").get().a);
       db.close();' 2>&1 | grep -v 'WARNING: linker' | tail -1)"
  case "$q8" in
    "Q8 v$NODE_VERSION sqlite=7") ok "Q8 closed: $q8" ;;
    *) bad "Q8 failed: ${q8:-no output}"; exit 1 ;;
  esac

  if [ -f "$AGENT_SCRIPT" ]; then
    step "verify (agent on the Android runtime)"
    if LD_LIBRARY_PATH="$RUNTIME_DIR" HOME="${TMPDIR:-/tmp}" TMPDIR="${TMPDIR:-/tmp}" \
       timeout 300 "$RUNTIME_DIR/$BINARY_NAME" --test agent/test/*.test.js >"$OUT_DIR/agent-test.log" 2>&1; then
      # The spec reporter prints "ℹ pass 10" / "ℹ fail 0"; keep both figures.
      ok "agent test suite: $(grep -aE '(pass|fail) [0-9]+$' "$OUT_DIR/agent-test.log" \
            | tail -2 | tr -d '\342\204\271' | tr -s ' ' | tr '\n' ' ' | sed 's/ $//')"
    else
      bad "agent suite failed on the Android runtime (see $OUT_DIR/agent-test.log)"
      exit 1
    fi
  fi
}

usage() {
  sed -n '9,26p' "$0" | sed 's/^# \{0,1\}//'
}

mode="${1:---all}"
case "$mode" in
  --fetch)    do_fetch ;;
  --assemble) do_assemble ;;
  --verify)   do_verify ;;
  --q8)
    can_execute_android || { bad "no Android linker on this host; run this on the phone"; exit 1; }
    LD_LIBRARY_PATH="$RUNTIME_DIR" HOME="${TMPDIR:-/tmp}" TMPDIR="${TMPDIR:-/tmp}" \
      "$RUNTIME_DIR/$BINARY_NAME" -e 'const s=require("node:sqlite");new s.DatabaseSync(":memory:").exec("create table t(a)");console.log("Q8 OK", process.version)' 2>&1 | grep -v 'WARNING: linker'
    ;;
  --clean)    rm -rf "$OUT_DIR"; say "removed $OUT_DIR" ;;
  --all|--build)
    do_fetch
    do_assemble
    do_verify
    say ""
    say "Next: scripts/build-node-android.sh --install $RUNTIME_DIR"
    ;;
  -h|--help)  usage ;;
  *)          bad "unknown option: $mode (try --help)"; exit 2 ;;
esac
