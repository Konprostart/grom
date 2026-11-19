# Agent runtime — status, Q8, and how to close it

This page is the single truthful answer to "does the agent actually run?".
Everything below is checked by `scripts/build-node-android.sh --status`.

## What the runtime is

Grom supervises a Node process that lives entirely in app-private storage
(D24, S3-R001):

```
<files>/node/libnode.so        the Node executable, chmod 755
<files>/node/grom-agent.js     the agent entry point
```

`libnode.so` is an operator-supplied binary. Grom never downloads it
(S1-R003, S3-R037). The agent script is Grom's own code: its canonical source
is `agent/grom-agent.js` and Gradle packages it into the APK as
`assets/node/grom-agent.js`; `RuntimeInstaller` copies it to app storage on
first start. There is exactly one copy of the script in the tree.

Since the installer exists, "missing runtime" now means only `libnode.so` —
which is the honest remaining blocker, not a packaging gap.

## Current status

| Item | State | Evidence |
|---|---|---|
| `agent/grom-agent.js` | **implemented** | `node --test agent/test/` |
| Runtime installer (assets → files) | **implemented** | JVM tests, `RuntimeInstallerTest` |
| Exec proxy (server → agent) | **implemented** | JVM tests, `AgentClientTest` |
| **B1** arm64 `libnode.so` | **blocked** | no build path on this host (below) |
| **B2** device run | **blocked** | no `adb`, no attached device |
| **Q8** `node:sqlite` | **open on-device**, answered for Node 24 on the dev host | see below |

### B1 — why this host cannot produce the binary

`uname -m` is `aarch64`; the installed NDK
(`/root/android-sdk/ndk/28.2.13676358`) ships only
`toolchains/llvm/prebuilt/linux-x86_64`. Those executables cannot run on an
aarch64 host at all, so no cross-compile starts. Docker is not installed here
either. The script detects this by *executing* `clang --version` rather than
listing directories, because the directories exist and would otherwise look
like a working toolchain.

A prebuilt runtime is also not committed: the binary is large, and a runtime
must be an explicit operator action.

### B2 — nothing has run on a device

No `adb` on this host. Consequently the boot receiver, the vendor task-killer
behaviour, and the quarantine-across-reboot path are unverified on real
hardware, not merely untested.

### Q8 — `node:sqlite`

Measured on this host (Linux x86_64, **Node v24.19.0**):

```
$ node -e "const s=require('node:sqlite'); ..."
node:sqlite OK v24.19.0 DatabaseSync,StatementSync,Session,constants,backup
sqlite query OK
```

Measured on the same host with **Node v22.14.0** (arm64 Linux): the module is
also available **without a flag**, with an `ExperimentalWarning` on stderr, and
all ten agent tests pass there too. Earlier 22.x releases did require
`--experimental-sqlite`; v22.14.0 does not. The build script still pins
**Node 24.19.0**, where the module is no longer experimental, but a 22.14+
runtime is a supported fallback.

What is still open is whether an **Android/bionic arm64 build** of Node 24
exposes the module. The module is compiled into the binary, not loaded as a
shared library, so a fully static build should keep it — but "should" is not
"verified". The one-command probe is in `--q8`; a Termux `nodejs-lts` answers it
in a minute without any Grom build.

## Producing the binary

On an x86_64 host with Docker:

```bash
scripts/build-node-android.sh --docker     # prints the exact reproducible recipe
# ... run it ...
scripts/build-node-android.sh --install ~/node-out
```

`--install` refuses anything that is not `e_machine == AArch64` and refuses
glibc-linked binaries (they pass an architecture check but cannot run on
Android's bionic). It also chmods the file executable, which `AgentLauncher`
requires before it reports the runtime as present.

Fastest way to answer Q8 without building anything:

```bash
# on the phone, in Termux
pkg install nodejs-lts
node -e "const s=require('node:sqlite');new s.DatabaseSync(':memory:').exec('create table t(a)');console.log('Q8 OK',process.version)"
```

## Verifying on a device

```bash
gradle :app:assembleServer29Debug
adb install -r app/build/outputs/apk/server29/debug/app-server29-debug.apk
adb shell am start -n app.grom.server/.MainActivity
# copy the token shown by the app, then:
adb forward tcp:8080 tcp:8080
curl -s http://127.0.0.1:8080/grom/v1/health          # state: READY
curl -s -H "Authorization: Bearer $TOKEN" \
     -d '{"op":"status"}' http://127.0.0.1:8080/grom/v1/agent/exec
# expect "sqlite":{"available":true,"backend":"node:sqlite"}
```

If `libnode.so` is absent, `health` still returns `200` with
`"state":"MISSING_RUNTIME"` and the agent endpoints return `503` naming the
missing file (S3-R035, S2-R026). That is the designed behaviour.

## What is deliberately *not* done

- The APK does not embed a Node binary. Embedding ~100 MB behind a flavour is a
  distribution decision that has not been made.
- Nothing is downloaded at runtime or at build time.
- The dev host's `node:sqlite` result is not presented as a device result.
