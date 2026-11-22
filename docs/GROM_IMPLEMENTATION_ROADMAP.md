# Grom — implementation roadmap

The delivery order for `docs/GROM_MASTER_PLAN.md`. This file is the one that
answers "what do I do next, and what has to be green before I may".
Task ids (`T-nn`) match §U of the master plan.

---

## 1. Current position

| phase | state | evidence in the tree |
|---|---|---|
| **0 — F1 walking skeleton** | **done** | `docs/F1-implementation-plan.md` §Status, 9/9 steps |
| **1A — reference configuration** | **done** | `npub` (NIP-19), `relays` (reference only), `remoteBridge` (label/description), exposed in authenticated `/v1/status` and the sanitized Copy-config export; D52–D54 |
| **1C — embeddable engine, dashboard, SSH bridge** | **done** | `engine/` library, `GromEmbed`, API 3, `projects/`, `ssh/`, `DashboardModel`, sections UI; D55–D60 |
| **1D — integration ergonomics** | **done** | `ProjectNamespace` + API 4 `projectExec` (D61), `GromProject`, real heartbeat in `GromEmbed`, "How Grom works" section (D62), external Lightning node reference (D63), unlimited saved SSH servers (D64) |
| **1B — core hardening** | **next** | `apps/AppManifest.kt`, `apps/PortAllocator.kt`, `apps/HealthGate.kt`, `apps/ReleaseStore.kt`, `proc/ChildProcess.kt`, `adapters/`, `auth/Scope.kt` — none exist yet |
| **2 — Application Manager MVP** | **blocked by 1B** | `apps/ApplicationManager.kt`, `apps/AppSupervisor.kt`, the gateway, `/v1/apps` |
| **3 — runtimes and adapters** | blocked by 2 | `apps/runtime/`, `adapters/`, LND |
| **4 — multi-device** | blocked by 3 | `cluster/` |
| **5 — backup, CLI, advanced** | blocked by 4 | `backup/`, `cli/` |
| **6 — production** | blocked by 5 | signing key, soak, review |

Current version: `0.7.0` (versionCode 7). The nine gates in `tools/run-gates` are
the baseline; every phase below adds tests, not gates that replace them.

**The next concrete action** is T-01 (`apps/AppManifest.kt`), because every later
task either consumes a validated manifest or is blocked on one existing.

---

## 2. Ordering rules

1. **No phase skipping.** A phase's entry criteria are the previous phase's exit
   gate. The only exception is a spike (`[RESEARCH]`), which is allowed to run
   early, produces a written result, and ships nothing.
2. **Code-complete ≠ closed.** A phase whose gate includes a `[DEVICE]` item is
   closed only when hardware has run it. Mark it `code-complete` and do not claim
   the phase. This applies to Phase 2 onward.
3. **Policy before transport.** A rule that decides something (validation, port
   choice, health arithmetic, scope) lands as pure Kotlin with a JVM test
   (D30) before any route or UI exposes it.
4. **One gate per decision.** Every new decision gets a test or a
   `tools/test-policy` invariant in the same change that introduces it, and the
   invariant is proven to fail on a seeded violation (T-09 is the template).
5. **No new dependency without a decision.** A dependency added in a phase is a
   `Dxx` plus a `third_party/THIRD_PARTY.md` row (D17, S1-R026), not a build-file
   edit.
6. **The baseline never goes red.** `tools/run-gates` must be green at every
   phase boundary; a phase may add tests, never weaken one.

---

## 3. Dependency edges

`A → B` means B cannot start until A is green.

```
Phase 0 (F1) ─▶ Phase 1A (done) ─▶ Phase 1C (done) ─▶ Phase 1B ─▶ Phase 2 ─▶ Phase 3 ─▶ Phase 4 ─▶ Phase 5 ─▶ Phase 6

Phase 1C is orthogonal to 1B: it changed *where* the server lives and how an
operator sees it, not the application-manager core. It is sequenced first only
because the engine extraction touches every file, and doing it after the
Application Manager would have made a large mechanical move collide with new
code.

Phase 1B   T-01 ─▶ T-02 ─▶ T-03 ─▶ T-04       (T-05, T-06, T-07, T-08 beside it; T-09 last)
Phase 2    T-10 ─▶ T-11 ─▶ {T-12, T-13, T-18, T-38}; T-05 ─▶ T-14 ─▶ T-15 ─▶ T-16 ─▶ T-17 ─▶ T-19
Phase 3    T-21, T-22, T-23 on T-14; T-24 on T-06; T-25 on T-21+T-24; T-26 on T-06+T-08
Phase 4    T-27 ─▶ T-28 ─▶ T-33; T-27 ─▶ T-29 ─▶ T-30 ─▶ T-32; T-17+T-29 ─▶ T-31
Phase 5    T-34 ─▶ T-35 ─▶ T-36; T-37 on T-10+T-17
Phase 6    T-39 ─▶ T-40 ─▶ T-41 ─▶ T-42
```

Edges that are easy to forget:

- **T-05 (child process) blocks T-14 (Node runtime)**, not the other way round.
  Extracting the launcher first is what makes the second runtime a file instead of
  a fork of `ServerRuntime`.
- **T-16 (gateway) blocks every "expose this app" story.** Without it the only way
  to reach an app is to bind a LAN port, which is exactly what the plan forbids.
- **T-07 (scopes) blocks T-11 (deploy)** for the `apps.deploy` check, and blocks
  T-28 (pairing) because a pairing without scopes is the thing scopes fix.
- **T-27 (identity) blocks T-28, T-29, T-30, T-31.** Nothing multi-device exists
  before there is a node identity.
- **T-33 (discovery) is a hint** and may ship after everything else in Phase 4; it
  blocks nothing.

---

## 4. Phases and hard gates

Each gate is a command or a named test. "Green" means the command exits 0 on a
clean clone with the pinned toolchain, or the named test passes.

### Phase 0 — F1 walking skeleton — **done**

Gate (historic): `tools/run-gates` 9/9, `docs/F1-implementation-plan.md` §Status.

### Phase 1A — reference configuration — **done**

Delivered:

| file | what it is |
|---|---|
| `superserver/nostr/Nip19.kt` | `npub` validation: bech32 (not bech32m), 32-byte payload, hex accepted and normalised to `npub1…`; every other HRP, bad checksum and mixed case refused |
| `superserver/nostr/RelayUrl.kt` | `ws://`/`wss://` only, host/port/path, no userinfo, query, fragment or control characters; at most 8, 512 characters each |
| `superserver/nostr/RemoteBridgeHint.kt` | label ≤ 80 and description ≤ 240 characters; secret-shaped input (`nsec1…`, private-key headers, `password=`, `bearer …`) refused outright |
| `superserver/SuperStatusDocument.kt` | the reference block of the authenticated `/v1/status`; `lightningHealth.reachable` is `null` until probed, never assumed |
| `superserver/ConfigExport.kt` | the sanitized Copy-config document, built from an allow-list rather than by serialising the config |

Decisions: **D52** — reference configuration is stored and validated, never
dialled (no relay connection, no tunnel); **D53** — status and export are
allow-lists and carry no credential field; **D54** — a reference-only change is
applied in place, without rebuilding the listener, so sessions and SSE streams
survive. Nothing here is a runtime capability claim; it is configuration with
validation.

Gate (historic and still binding):

| check | command | result required |
|---|---|---|
| baseline gates | `tools/run-gates` | 9/9 |
| NIP-19 validation | `Nip19Test` (`:app:testServer29DebugUnitTest`) | bad checksum, `nsec1…`, mixed case, 31-byte payload all refused |
| relay validation | `RelayUrlTest` | `wss`/`ws` accepted; userinfo, query, fragment refused |
| bridge hint | `RemoteBridgeHintTest` | secret-shaped input refused |
| config refusal codes | `SuperServerConfigTest` | `BAD_NPUB`, `BAD_RELAY`, `TOO_MANY_RELAYS`, `BAD_REMOTE_BRIDGE` all exercised |
| reference-only reload | `SuperServerConfigTest.sameRuntimePolicyIgnoresOnlyReferenceFields` | sessions and SSE survive; `super.reload.reference` is appended (S5-R034) |
| status / export shape | `SuperStatusDocumentTest`, `ConfigExportTest` | no password hash, no sealed key, no `nsec`, no audit record |
| no relay connection | `tools/test-policy` (#23) | no `Socket`, `URL`, `WebSocket` or `openConnection` under `superserver/nostr` |
| no credential leak | `tools/test-policy` (#24) | `passwordHash`, `apiKeySealed`, `nsec1`, `PRIVATE KEY` absent from the status and export sources |
| no new outbound path | `tools/test-policy` (#17) | the "one hardcoded URL" invariant still passes |

The static count is a claim: it is 24 in this tree (17 in the F1 plan). When a
phase adds an invariant, update the header comment in `tools/test-policy` and the
count in `tools/run-gates` in the same change.

The records are **D52** (reference configuration, never dialled), **D53**
(status and export are allow-lists), **D54** (a reference-only change does not
restart the listener), with the normative text in S5-R032–R034. If the log, the
spec and the code ever disagree, that is a defect in 1A and the phase reopens.

### Phase 1C — embeddable engine, dashboard, SSH bridge — **done**

Delivered:

| file | what it is |
|---|---|
| `engine/` | the server as an Android library (`app.grom.engine`); every server package moved here, `app/` is a host |
| `engine/.../embed/GromEmbed.kt` | consent, start, embedded client, heartbeat, bounded project registration |
| `engine/.../embed/GromProject.kt` | typed registration, heartbeat, namespaced `put`/`get` (D61) |
| `engine/.../projects/ProjectNamespace.kt` | `p:<projectId>:` prefix, ownership gate |
| `engine/.../projects/ProjectRegistry.kt` | project records with heartbeat-derived `alive`/`stale`, atomic file, 8 projects |
| `engine/.../ssh/SshTarget.kt` | target model, validators, draft, limits (8 targets, 2 active) |
| `engine/.../ssh/SshTargetStore.kt` | sealed secrets, TOFU pin storage, no plaintext on disk |
| `engine/.../ssh/SshKeys.kt` | Ed25519 generation, OpenSSH `PROTOCOL.key` writer, one loader for generated and imported keys |
| `engine/.../ssh/SshTunnelManager.kt` | MINA SSHD client, TOFU verify, one `tcpip-forward`, `SshForwardingPolicy` |
| `sdk/.../IGromServer.aidl` | API 3: projects, export, agentExec, SSH, lightning probe |
| `app/.../ui/DashboardModel.kt` | pure lamp projection (green only for measured values) |
| `app/.../ui/MenuModel.kt` | menu order (`ssh` … `update`, `invite`, `help`), role split, section titles |
| `app/.../GromActivity.kt` | hero + "Now serving" only, header Menu; invite and help are menu entries (D69) |
| `docs/EMBEDDING.md`, `docs/SSH-BRIDGE.md` | host guide and bridge guide |

Gate: `tools/run-gates` 9/9, policy **29/29**, `SshTunnelManagerTest` proving a
real reverse forward against an in-process SSH server, `DashboardModelTest`,
both flavours green, lint 0/0.

Not closed by this phase: **nothing here has run on a phone.** The SSH tunnel is
proven against a real SSH implementation on the JVM, not on device hardware.

### Phase 1B — core hardening — **next**

Entry: Phase 1A green. Deliverables: T-01 … T-09.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 |
| manifest | `AppManifestTest` | every refusal code in §G.3 exercised; one accept |
| ports | `PortAllocatorTest` | allocate, release, collision, exhaustion, stable reuse |
| releases | `ReleaseStoreTest` | pointer written last; retention never prunes `previous` |
| health arithmetic | `HealthGateTest` | pass, flake, timeout, never-up |
| child process | `ChildProcessTest` | env from scratch, stdin line, 4 KiB line cap, group kill |
| adapter registry | `AdapterRegistryTest` | duplicate id, API mismatch, permission refusal |
| scopes | `ScopeTest` | owner/operator/viewer matrix; absent scope denies |
| status | `StatusDocumentTest` | additive fields only; `api` unchanged |
| new invariant | `tools/test-policy` (#25) | fails on a seeded adapter reference outside `adapters/` |

Exit: all of the above green. No device item, so 1B can actually close.

### Phase 2 — Application Manager MVP

Entry: 1B closed. Deliverables: T-10 … T-20, T-38.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 |
| deploy transaction | `DeployIntegrationTest` | real sockets; failed gate does not move the pointer; a successful one moves it once |
| local + HTTPS sources | `LocalSourceTest`, `HttpSourceTest` | traversal refused; redirects refused; `http://` refused |
| Node runtime | `NodeRuntimeTest` | plan equals the current launch contract; agent suite unchanged |
| static host | `StaticHostTest` | traversal refused; MIME allow-list |
| gateway | `GatewayTest` | no route to `/grom/v1/*`; no `Upgrade`; no `X-Grom-*` passthrough |
| app routes | `SuperServerIntegrationTest` (extended) | 401/403/404/405/413/429 unchanged in shape |
| secrets | `SecretSetTest` | sealed at rest; delivered on stdin; absent from status and logs |
| retention | `ReleaseStoreTest` | bytes accounted; `previous` preserved |
| device checklist | §V.5 of the master plan | **cannot be signed without hardware** |

Exit: code-complete when the tests above are green. **Closed** only when the
device checklist has been run and recorded in `docs/DEVICES.md`. Until then the
release notes say `unverified on hardware`.

### Phase 3 — runtimes and adapters

Entry: Phase 2 code-complete. Deliverables: T-21 … T-26.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 |
| binary runtime | `BinaryRuntimeTest` + device smoke | the mechanism works on hardware, or the adapter is withdrawn |
| python spike | written result in `docs/RUNTIME.md` | either a runnable bionic CPython with `sqlite3` and `ssl`, or `[RESEARCH]` stays and nothing ships |
| go/rust spike | written result | DNS and TLS roots resolved, or `[RESEARCH]` stays |
| second adapter | `LndBackendTest` | proves the contract needs no `ServerRuntime` change |
| ledger rule | `tools/check-third-party` | a copyleft row without a `constraint:` note fails |
| status | `StatusDocumentTest` | runtimes and their probes are visible, `MISSING` names the file |

Exit: all green, with the explicit possibility that a runtime is **dropped**
rather than shipped. A failed spike that is recorded is a passing phase.

### Phase 4 — multi-device

Entry: Phase 3 closed. Deliverables: T-27 … T-33.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 |
| identity | `NodeIdentityTest` | deterministic `nodeId`; seed never leaves sealed storage |
| pairing | `PairingTest` | no auto-approve; revocation effective on the next request; code single-use and expiring |
| registry | `NodeRegistryTest` | stale after 45 s, unreachable after 120 s, never shown as live |
| heartbeat | `HeartbeatTest` | stale timestamp and bad signature both `401` |
| cluster | `ClusterIntegrationTest` | two `ServerRuntime` instances over loopback; local work survives control loss |
| placement | `PlacementTest` | `NO_ELIGIBLE_NODE` instead of pending forever |
| CLI | `tools/test-cli` | every command against a live loopback server |
| discovery | `DiscoveryTest` | announce only; no trust decision |

Exit: green, plus a two-device manual checklist `[DEVICE]`.

### Phase 5 — backup, CLI, advanced

Entry: Phase 4 closed. Deliverables: T-34 … T-38 (T-31 may land here if Phase 4
ran long).

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 |
| format | `BackupFormatTest` | AEAD, authenticated manifest, path escape refused |
| round trip | `BackupRoundTripTest` | export → wipe → import → every entry verified |
| restore report | `RestoreVerificationTest` | a failing app keeps its old directory and is reported |
| exclusion | `tools/test-policy` (new) | no token, identity or pairing keys in an archive |
| events | `EventBusTest` | bounded queue, drop counting |

Exit: green.

### Phase 6 — production

Entry: Phase 5 closed. Deliverables: T-39 … T-42.

| gate item | command | pass condition |
|---|---|---|
| baseline | `tools/run-gates` | 9/9 on the tagged commit |
| release | `.github/workflows/release.yml` | both APKs, runtime inside, `apksigner verify` passes |
| soak | `tools/soak` | 72 h with a written result (memory, restarts, chain integrity) |
| device matrix | `docs/DEVICES.md` | §K rows replaced with measured ones |
| review | `docs/SECURITY-REVIEW.md` | every finding fixed or recorded as accepted |

Exit: a release a stranger can install, run and recover.

---

## 5. Task table

Task | Depends on | Gate
---|---|---
T-01 manifest | Phase 1A | `AppManifestTest`
T-02 port allocator | T-01 | `PortAllocatorTest`
T-03 release store | T-01, T-02 | `ReleaseStoreTest`
T-04 health gate | T-03 | `HealthGateTest`
T-05 child process | Phase 1A | `ChildProcessTest`, `tools/test-agent`
T-06 adapter registry | T-05 | `AdapterRegistryTest`
T-07 scopes | Phase 1A | `ScopeTest`, static invariant
T-08 status fields | T-01 | `StatusDocumentTest`
T-09 static invariant | T-06, T-07 | `tools/test-policy` (#25), seeded failure
T-10 app supervisor | T-04, T-05 | `AppSupervisorTest`
T-11 application manager | T-03, T-10 | `DeployIntegrationTest`
T-12 local source | T-11 | `LocalSourceTest`
T-13 HTTPS source | T-11 | `HttpSourceTest`
T-14 Node runtime | T-05, T-06 | `NodeRuntimeTest`
T-15 static host | T-14 | `StaticHostTest`
T-16 gateway | T-15 | `GatewayTest`
T-17 app routes | T-11, T-16 | `SuperServerIntegrationTest`
T-18 secret sets | T-05, T-11 | `SecretSetTest`
T-19 UI apps card | T-17 | lint, pure UI-state test
T-20 fixture app | T-10 | used by T-11…T-17
T-21 binary runtime | T-14 | `BinaryRuntimeTest`, device smoke
T-22 Python spike | T-14 | written result in `docs/RUNTIME.md`
T-23 Go/Rust spike | T-14 | written result
T-24 LND backend | T-06 | `LndBackendTest`
T-25 ledger rule | T-21, T-24 | `tools/check-third-party` seeded failure
T-26 runtime status | T-06, T-08 | `StatusDocumentTest`
T-27 node identity | Phase 2 | `NodeIdentityTest`
T-28 pairing | T-07, T-27 | `PairingTest`
T-29 registry | T-27 | `NodeRegistryTest`
T-30 heartbeat | T-27, T-29 | `HeartbeatTest`
T-31 CLI | T-17, T-29 | `tools/test-cli`
T-32 placement | T-29, T-30 | `PlacementTest`
T-33 discovery | T-28 | `DiscoveryTest`, no trust decision
T-34 backup format | Phase 3 | `BackupFormatTest`
T-35 export/import | T-34 | `BackupRoundTripTest`
T-36 restore report | T-35 | `RestoreVerificationTest`
T-37 SSE app events | T-10, T-17 | `EventBusTest`
T-38 retention | T-03 | `ReleaseStoreTest`
T-39 production signing | Phase 5 | tag build, `apksigner verify`
T-40 soak | T-39 | 72 h written result
T-41 device matrix | T-40 | `docs/DEVICES.md` measured rows
T-42 security review | T-41 | `docs/SECURITY-REVIEW.md`

---

## 6. Critical path and parallel tracks

**Critical path:** T-01 → T-03 → T-04 → T-10 → T-11 → T-16 → T-17 → T-20 →
Phase 2 gate → T-27 → T-29 → T-30 → Phase 4 gate → T-35 → Phase 5 gate → T-39 →
Phase 6 gate.

Parallel work that blocks nothing on the critical path:

- **T-05 / T-06** (child process, adapter registry) beside T-01…T-04.
- **T-07 / T-08 / T-09** (scopes, status, invariant) beside T-10…T-13.
- **T-21…T-26** (runtime and adapter work, spikes) can start once T-14 lands; a
  spike is allowed to run earlier and ship nothing.
- **T-31 (CLI)** can start as soon as app routes exist; it is a client, not a
  foundation.
- **T-41 (device matrix)** can start the moment hardware is available, in any
  phase; every measurement unblocks a `[DEVICE]` line in `docs/GROM_MASTER_PLAN.md`
  §K.

---

## 7. What not to start early

| tempting work | why it waits |
|---|---|
| a scheduler or placement heuristic | Phase 4, and only the fixed-order rule in §M.6 |
| a web dashboard | never (master plan §E.3) |
| dynamic plugin loading | never (S5-R020) |
| a bundled tunnel or relay client | never (D52–D54; tunnels stay the operator's) |
| multi-tenant isolation | never on Android (§P.7); a Linux node is the answer |
| a second runtime before `RuntimeAdapter` exists | Phase 3; T-14 then T-21 |
| backup before the release layout is stable | Phase 5; a backup format over a moving layout is a migration cost |
| production signing before the feature set freezes | Phase 6 (D4 still governs until then) |

---

## 8. Definition of done for any phase

Run, in this order, and paste the result into the phase's entry in this file:

```bash
tools/run-gates                                  # 9 baseline gates
gradle :app:testServer29DebugUnitTest            # + the phase's new tests
gradle :app:testPeripheral26DebugUnitTest
tools/test-policy                                # count = the claim in its header
tools/check-third-party                          # ledger matches the build
tools/verify-ipc-signing                         # C-008, needs the three APKs
tools/test-agent                                 # secrets over stdin, not argv
tools/test-agent-runtime                         # agent protocol on the real Node
tools/check-site                                 # the download page stays static
gradle :app:lintServer29Debug :app:lintPeripheral26Debug
```

Plus, for a phase with a `[DEVICE]` item: the checklist in master plan §V.5,
recorded in `docs/DEVICES.md` with the device model, Android version and the date.
A phase without that record is `code-complete`, never `closed`.

### Phase 1D — operator alerts, project ergonomics — **done**

Delivered:

| file | what it is |
|---|---|
| `engine/.../notify/NotifySettings.kt` | enabled / chat ID / sealed token, stable validation codes |
| `engine/.../notify/NotifySettingsStore.kt` | owner-only atomic JSON, 64 KiB cap, token only as `SecretBox` ciphertext |
| `engine/.../notify/NotifyEvent.kt` | the fixed event table (17 codes: 16 state events plus the test message), each with one English sentence |
| `engine/.../notify/NotifyRules.kt` | pure `classify(action, detail)`: unknown actions ignored, detail parsed for a code only |
| `engine/.../notify/TelegramNotifier.kt` | one daemon worker, 16-message queue, 10 s coalescing, 20/min, 2 attempts, 8 s timeout |
| `engine/.../notify/NotifyStatus.kt` | the sanitised `{enabled, configured, lastDelivery}` block |
| `app/.../ui/NotifyModel.kt` | pure lamp projection: green only after a measured `ok=true` |
| `app/.../ui/HelpModel.kt` | role-aware help: three blocks on `peripheral26`, four on `server29` |
| `sdk/.../IGromServer.aidl` | API 5: `notifySettingsJson`, `notifySaveSettings`, `notifyTestJson` |

Gate: `tools/run-gates` 9/9, policy **33/33**, both flavours green, lint 0/0,
`TelegramNotifierTest` proving the queue, coalescing, the two attempts, the rate
limit and that a token never appears in an error.

Not closed by this phase: **no message has been sent to the real Bot API from a
device.** The tests use a fake transport; the host half is code-reviewed only.

Exit criteria for anything that depends on the VPS side: the operator's `Send
test message` must return `ok` on a physical phone before an alert is trusted —
recorded as a B2 item, not a JVM item.
