# F1 — walking skeleton, implementation plan

F1 is the smallest Grom that is still honest: it builds, it starts, it refuses
unsafe configuration, it authenticates, it records what it did, and it reports
`MISSING_RUNTIME` instead of pretending.

Nine steps, in order. A step is done only when its gate passes on a clean clone
with the pinned toolchain (AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, JDK 17).

---

## Step 1 — Gradle skeleton and the two flavour branches
**Gate:** `gradle :app:assembleServer29Debug :app:assemblePeripheral26Debug`
succeeds; both APKs exist; `BuildConfig.GROMSERVER` is `true`/`false`
respectively; `:sdk` publishes the AIDL package `app.grom.sdk.ipc`.

## Step 2 — Service, supervisor shell, status screen
**Gate:** the server role starts a foreground service; the activity shows role,
build version and runtime state; the peripheral flavour has no service declared
as exported and never starts one.

## Step 3 — Licence and the `third_party` gate
**Gate:** `LICENSE` is Apache-2.0; every source file carries an SPDX header;
`tools/check-third-party` passes and fails when the document is edited to
mention a component the build does not have (and vice versa).

## Step 4 — Node agent launch and the health gate
**Gate:** the supervisor locates the agent under app-private storage, spawns it
with a bounded start timeout, waits for `GROM_READY`, and reports
`MISSING_RUNTIME` when the files are absent. Health endpoint reflects the state.

## Step 5 — Refuse any non-loopback bind
**Gate:** `BindPolicy` accepts `127.0.0.1`/`::1` and rejects `0.0.0.0`, `::`,
LAN addresses and off-loopback hostnames. The server refuses to start rather
than falling back. `tools/test-policy` asserts no `0.0.0.0` literal in the tree.

## Step 6 — Token, authentication, audit chain
**Gate:** every non-health endpoint requires the bearer token; wrong or missing
token returns `401` and is audited (without the value); successful mutations
append hash-chained entries; `audit verify` replays the chain; redaction test
passes.

## Step 7 — AIDL with signature verification
**Gate:** `IGromServer` is bound only when the caller's signature matches;
`testapps/samecert` binds and reads `apiVersion() == 1`; `testapps/foreigncert`
gets `null` from `bindService`; `tools/verify-ipc-signing` builds both and
inspects the merged manifests and certificates.

## Step 8 — Secrets over stdin
**Gate:** the agent config (including the token) is delivered as one JSON line
on stdin and the pipe is closed; a launch never places it in argv or the
environment; `tools/test-agent` proves it by inspecting the constructed command
and environment, and by round-tripping a config through the writer/reader pair.

## Step 9 — Quarantine and boot restart
**Gate:** three crashes within the window produce `QUARANTINED` and no further
restarts; an explicit reset clears it; the boot receiver starts the service only
when the user enabled it, and never when the agent is quarantined.

---

## Status in this tree

| # | Step | State |
|---|---|---|
| 1 | Skeleton + flavours | done |
| 2 | Service + supervisor shell | done |
| 3 | Licence + third-party gate | done |
| 4 | Agent launch + health gate | done |
| 5 | Loopback-only bind | done |
| 6 | Token + auth + audit | done |
| 7 | AIDL + signature | done |
| 8 | Secrets over stdin | done |
| 9 | Quarantine + boot restart | done |

"Done" here means the gate passes on this tree. Steps 8 and 9 were finished
during the rebuild; they were the two open items at handoff.

## Conformance results for this tree

| Gate | Result |
|---|---|
| `:app:testServer29DebugUnitTest` | 181/181 |
| `:app:testPeripheral26DebugUnitTest` | 181/181 |
| `tools/test-policy` | 17/17 |
| `tools/check-third-party` | 10/10 |
| `tools/verify-ipc-signing` | 10/10 |
| `tools/test-agent-runtime` | 35/35 |
| `:app:lint` | 0 errors |
| fresh clone of the pushed commit, built from scratch | pass |

These numbers replace the handoff's 67/67, 45/45, 17/17, 17/17 because the
requirement set was re-derived (`docs/RECOVERY.md`).

## What F1 still cannot claim

F1 was **not** a working server. Three conditions sat outside the repository; one
has since been closed:

1. `libnode.so` for arm64 — **closed as a build problem.** The binary still is not
   committed (it is a 91 MB build input), but it is now produced and verified by
   one pinned command: `scripts/fetch-node-android.sh`. The assembled runtime
   executes through the real bionic linker on this Android-kernel host, and
   `scripts/build-node-android.sh --install` packages it into `jniLibs` (D34,
   D35). See `docs/RUNTIME.md`.
2. Nothing has run on a device: no `adb` in the build environment. The boot
   cycle and the vendor task-killer behaviour are unverified. **Still open.**
3. Q8 — **closed.** `node:sqlite` works on the pinned bionic arm64 runtime
   (Node v24.22.3): `--verify` prints `Q8 closed: … sqlite=7`, and all ten agent
   tests pass on that runtime. What remains is repeating the same probe on the
   phone for the record, which needs `adb`.

Until (2) closes, the on-device behaviour of the supervisor is unknown, but the
runtime is no longer the reason: install it and `POST /grom/v1/agent/*` returns
`503 MISSING_RUNTIME` only while the binary is genuinely absent. See D31, D34.

## Post-F1 — operator surface, updates and release packaging

These are the changes after F1 that make the tree installable and updatable from
a phone rather than only buildable:

| Item | Decision | Gate |
|---|---|---|
| Monochrome Auto/Light/Dark operator surface | D36 | review + `:app:lint` |
| Operator-initiated update check against the release feed | D37 | `JsonParserTest`, `UpdateCheckerTest`, `tools/test-policy` |
| Release workflow that bundles and verifies the arm64 runtime | D38 | `.github/workflows/release.yml` |

The version moves to `0.2.0` (versionCode 2). Tags of the form `v*` publish
APKs; the app reads the latest release. Procedure: `docs/RELEASING.md`.
