# Agent runtime — status, Q8, and how to close it

This page is the single truthful answer to "does the agent actually run?".
Everything below is checked by `scripts/build-node-android.sh --status` and
`scripts/fetch-node-android.sh --verify`.

## What the runtime is

Grom supervises a Node process. Two files are involved, and they deliberately
live in different places (D24, D34, S3-R001):

```
<app native lib dir>/libnode.so   the Node executable, chmod 755   (packaged in jniLibs)
<files>/grom/node/grom-agent.js   the agent entry point            (data file)
```

**Why they are split.** Android refuses `execve()` of files in app-private
storage for apps targeting API 29+ — Grom targets 36. A runtime copied into
`files/` therefore *can never start*, no matter how valid the ELF is. Binaries
must be executed from `ApplicationInfo.nativeLibraryDir`, which only exists if
they were packaged as APK native libraries (`jniLibs/arm64-v8a/`) and extracted
at install time (AGP `jniLibs.useLegacyPackaging = true`, which produces an
`extractNativeLibs="true"` APK). JavaScript, by contrast,
is just data, so the agent script correctly stays in app storage.

`libnode.so` is operator-supplied and never committed: it is ~46 MB, its
dependencies another ~45 MB. Grom never downloads it at runtime (S1-R003,
S3-R037). Only the **server** role packages it: `peripheral26` never executes
the agent, so its APK carries no native libraries at all (D87,
`tools/check-flavours`). The agent script is Grom's own code — `agent/grom-agent.js` is the one
canonical copy, packaged into the APK as `assets/node/grom-agent.js`.

## Current status

| Item | State | Evidence |
|---|---|---|
| `agent/grom-agent.js` | **implemented** | 10/10 on the bionic arm64 runtime **and** on host Node |
| Runtime installer (assets → files, legacy) | **implemented** | JVM tests, `RuntimeInstallerTest` |
| Exec proxy (server → agent) | **implemented** | JVM tests, `AgentClientTest` |
| **B1** arm64 `libnode.so` | **buildable and verified — one command** | `scripts/fetch-node-android.sh --verify` |
| **B2** device run | **still blocked** | no `adb`, no attached device |
| **Q8** `node:sqlite` | **closed on bionic arm64** | `--verify` prints `Q8 closed: … sqlite=7` |

### B1 — no longer "no path exists"

The blocker used to be: this host is `aarch64`, the NDK ships only
`linux-x86_64` prebuilts, and Docker is absent, so nothing could be compiled.
That is still true — a V8 build also needs several GB of RAM — but it is no
longer the obstacle, because a **bionic arm64 build does not have to be
compiled here**:

```bash
scripts/fetch-node-android.sh            # fetch → assemble → verify
scripts/build-node-android.sh --install   # into app/src/main/jniLibs/arm64-v8a
gradle :app:assembleServer29Debug         # APK now carries the runtime
```

`fetch-node-android.sh` is pinned (package versions and SHA-256 for every
archive), normalises the SONAMEs to `lib*.so` (Android packages nothing else as
a native library), and — the important part — **proves the result by running
it**. It is not a download that "should work": it executes the binary and the
agent suite before it will tell you it succeeded.

Note that installing the runtime grows the APK by ~91 MB (uncompressed
`libnode.so` + `libicu` data); the compressed release APK is about 40 MB. The
repository itself stays binary-free. Published releases already contain the
runtime (D38), so an installed release needs no local step:

```bash
adb install -r grom-server29.apk
```

### B2 — nothing has run on a device

No `adb` on this host. The runtime is verified on the *Android kernel host*
through the real bionic linker, but the APK itself has not been installed on a
phone. Boot receiver, vendor task-killers and quarantine-across-reboot remain
unverified on hardware.

### Q8 — `node:sqlite`, closed

Measured on the assembled runtime (`Node v24.22.3`, bionic aarch64), executed
through `/system/bin/linker64`:

```
$ scripts/fetch-node-android.sh --verify
  ok    executes through /system/bin/linker64: v24.22.3
  ok    Q8 closed: Q8 v24.22.3 sqlite=7
  ok    agent test suite: # pass 10
```

Earlier evidence on host builds: Node v24.19.0 and v22.14.0 both expose the
module without a flag (22 emits an `ExperimentalWarning`; some older 22.x need
`--experimental-sqlite`, which Grom's argv does not pass — S3-R005). Those runs
were glibc builds and are *not* what a phone executes; the bionic run above is.

A runtime without the module is still handled truthfully: `kv.*` answers
`503 STORAGE_UNAVAILABLE` rather than pretending to persist.

## Verifying on a device

```bash
gradle :app:assembleServer29Debug
adb install -r app/build/outputs/apk/server29/debug/app-server29-debug.apk
adb shell am start -n app.grom.server/.GromActivity
# copy the token shown by the app, then:
adb shell run-as app.grom.server \
  /data/app/*/app.grom.server*/lib/arm64/libnode.so -e \
  "const s=require('node:sqlite');new s.DatabaseSync(':memory:').exec('create table t(a)');console.log('Q8 OK',process.version)"
curl -s http://127.0.0.1:47001/grom/v1/health           # state: READY
curl -s -H "Authorization: Bearer $TOKEN" \
     -d '{"op":"status"}' http://127.0.0.1:47001/grom/v1/agent/exec
# expect "sqlite":{"available":true,"backend":"node:sqlite"}
```

If no runtime is installed, `health` still returns `200` with
`"state":"MISSING_RUNTIME"` and agent endpoints return `503` naming the missing
file (S3-R035, S2-R026). That is the designed behaviour.

## What is deliberately *not* done

- The APK does not embed a Node binary **in git**. The runtime is a build input,
  ignored by `.gitignore`, produced by one pinned script.
- Nothing is downloaded by Grom at runtime.
- A host `node:sqlite` result is never presented as a device result; the bionic
  execution above is the one that counts.
