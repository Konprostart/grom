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
| `:app:testServer29DebugUnitTest` | 163/163 |
| `:app:testPeripheral26DebugUnitTest` | 163/163 |
| `tools/test-policy` | 17/17 |
| `tools/check-third-party` | 10/10 |
| `tools/verify-ipc-signing` | 10/10 |
| `tools/test-agent-runtime` | 35/35 |
| `:app:lint` | 0 errors |
| fresh clone of the pushed commit, built from scratch | pass |

These numbers replace the handoff's 67/67, 45/45, 17/17, 17/17 because the
requirement set was re-derived (`docs/RECOVERY.md`).

## What F1 still cannot claim

F1 is **not** a working server. Three conditions are outside the repository:

1. `libnode.so` for arm64 does not exist here. See `scripts/build-node-android.sh`.
2. Nothing has run on a device: no `adb` in the build environment. The boot
   cycle and the vendor task-killer behaviour are unverified.
3. Q8 is open **on a device**: `node:sqlite` works on Node 24.19.0 on the dev
   host, but whether an Android/bionic arm64 build exposes it has not been
   measured. One `node -e "require('node:sqlite')"` on the phone answers it;
   the agent degrades to `503 STORAGE_UNAVAILABLE` instead of faking success
   until then. See `docs/RUNTIME.md`.

Until (1) and (2) close, `POST /grom/v1/agent/*` returns `503 MISSING_RUNTIME`
and the UI says the same. See D31.
