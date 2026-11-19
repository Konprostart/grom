# Grom — decision log

Status legend: **ACCEPTED** (normative), **REJECTED** (recorded so it is not
re-proposed), **DEFERRED** (out of F1, tracked for later).

Provenance: see `docs/RECOVERY.md`. This log is a re-derivation of the D1–D31
record named in the handoff, extended with D32–D38 as the agent runtime, the operator
surface, updates and release packaging were built out. The numbering is kept so cross-references survive.

---

## D1 — Grom is an agent, never an authority for money
**ACCEPTED.** Grom may observe, relay, and execute whitelisted local operations.
It never holds balances, never signs payments, never decides that a payment is
valid. Any amount, account, or settlement concept in server code is a defect.

*Enforced by:* `tools/test-policy` (forbidden-symbol scan), review.

## D2 — One artifact, two roles
**ACCEPTED.** A single application ships two Gradle flavours:

| flavour | minSdk | role |
|---|---|---|
| `server29` | 29 | full server: binds loopback, supervises the agent |
| `peripheral26` | 26 | client/peripheral: uses the SDK, never binds a socket |

`BuildConfig.GROMSERVER` gates every server-only code path. A peripheral build
must never open a listening socket.

*Enforced by:* `tools/test-policy`, flavour matrix build in CI.

## D3 — Kotlin, zero third-party runtime dependencies
**ACCEPTED.** F1 uses only the Android framework, Kotlin stdlib and `org.json`
(part of the framework). No HTTP server library, no JSON library, no DI
framework. The APK stays small and the dependency surface stays auditable.

*Enforced by:* `tools/check-third-party`.

## D4 — Test-only keystores are committed
**ACCEPTED.** `keys/grom-test-same.jks` and `keys/grom-test-foreign.jks` are
committed so that the signature gate (C-008) is reproducible on any machine.

These keys are **public by design**. They must never sign a distributed build;
a release build must supply its own key material outside the repository.

*Enforced by:* README warning, `tools/verify-ipc-signing` refuses to run against
a release keystore path.

## D5 — Loopback bind only, fail closed
**ACCEPTED.** The listener may bind `127.0.0.1` or `::1` and nothing else.
`0.0.0.0`, `::`, any LAN address, and any hostname that resolves off-loopback
are rejected before the socket is created. Rejection is fatal to server start,
not a warning.

*Enforced by:* `BindPolicy`, `BindPolicyTest`, `tools/test-policy` (manifest has
no `INTERNET`-bound export surface).

## D6 — Bind `0.0.0.0` so desktop clients can connect directly
**REJECTED.** It turns every phone running Grom into a LAN service with one
bearer token as the only control. Conflicts with D5 and with the threat model in
S1. Remote access is a separate future design (pairing, per-device keys), not a
bind-address flag.

## D7 — HTTP/1.1 on a raw socket
**ACCEPTED.** The loopback API is HTTP/1.1 served by a small parser over
`ServerSocket`. Requests are bounded: max 8 concurrent connections, max 64 KiB
body, 5 s idle timeout. Anything larger is rejected with `413`/`431`.

## D8 — Bearer token, 256-bit, constant-time compare
**ACCEPTED.** The token is 32 random bytes, base64url without padding. It is
stored in app-private storage and compared in constant time. Tokens are never
logged; the audit log stores a short fingerprint (first 8 hex chars of
SHA-256) when it must refer to one.

## D9 — Health is unauthenticated; everything else is authenticated
**ACCEPTED.** `GET /grom/v1/health` answers on loopback without a token so a
supervisor or a local launcher can probe liveness. It exposes no secrets.
Every other endpoint requires `Authorization: Bearer <token>`.

## D10 — Append-only hash-chained audit log
**ACCEPTED.** Every authenticated mutating request appends an entry
`{seq, ts, actor, action, detail, prev, hash}` where
`hash = SHA-256(domain || seq || ts || actor || action || detail || prev)` and
`prev` is the previous entry's hash (`0…0` for genesis). Verification replays the
chain. Truncation or edit is detectable.

## D11 — Secrets reach the agent over stdin, never argv or env
**ACCEPTED.** Anything sensitive (token, future signing material) is written to
the child's stdin as a single JSON line and then the pipe is closed. `ps` on
Android does not show it; `/proc/<pid>/environ` does not contain it.

*Enforced by:* `AgentConfigTest`, `tools/test-agent`.

## D12 — Supervised agent with a health handshake
**ACCEPTED.** The agent process must emit `GROM_READY <pid>` on stdout within
`startTimeoutMs` (default 10 000). Until then the agent is `STARTING`. A process
that dies before the handshake counts as a crash.

## D13 — Quarantine after three crashes, cleared only by a human
**ACCEPTED.** Three crashes inside a rolling 10-minute window move the agent to
`QUARANTINED`. The supervisor stops restarting it. Quarantine clears only on an
explicit user action (app UI or `POST /grom/v1/agent/reset` with a token), never
on a timer.

## D14 — Boot restart is opt-in and persistent
**ACCEPTED.** `RECEIVE_BOOT_COMPLETED` is honoured only when the user enabled
"start on boot". The flag lives in app preferences. Quarantine survives reboot:
a quarantined agent does not restart at boot.

## D15 — IPC is signature-gated twice
**ACCEPTED.** Layer 1: the service is protected by
`app.grom.sdk.ipc.BIND_GROMSERVER` with `protectionLevel="signature"`.
Layer 2: `onBind` compares the caller UID's signatures with Grom's own via
`PackageManager.checkSignatures` and returns `null` on mismatch. A foreign
signature gets no Binder at all.

## D16 — No outbound network, no telemetry
**ACCEPTED.** Grom does not initiate connections to the internet. No analytics,
no crash reporting, no update check. Permissions are limited to what the local
service needs.

## D17 — Every third-party component is documented and gated
**ACCEPTED.** `third_party/THIRD_PARTY.md` lists each component, version,
licence and why it is present. `tools/check-third-party` fails when the document
and the build disagree in either direction.

## D18 — Apache-2.0, SPDX headers
**ACCEPTED.** Repository licence is Apache-2.0. Each source file carries
`SPDX-License-Identifier: Apache-2.0`.

## D19 — Per-request capability tokens (scoped grants)
**DEFERRED.** F1 has one bearer token with one scope. Scoped grants, expiry and
rotation-with-overlap belong to F2; adding them now would widen the attack
surface without a consumer.

## D20 — Keystore-wrapped token storage
**DEFERRED.** F1 stores the token in app-private storage (`0600` semantics by
directory permissions). Wrapping it with the Android Keystore is worthwhile but
buys little while the same UID can read the process memory anyway. Revisit with
the F2 threat model.

## D21 — Foreground service type `specialUse`, server role only
**ACCEPTED.** The server role runs as a foreground service with
`foregroundServiceType="specialUse"` and the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
property. The peripheral role does not start it.

## D22 — Token rotation is explicit
**ACCEPTED.** The token persists across restarts so clients are not broken on
every launch. `POST /grom/v1/token/rotate` issues a new token and invalidates the
old one atomically. Rotation is itself audited.

## D23 — Fixed port, user-overridable
**ACCEPTED.** Default port `47001` on `127.0.0.1`. The user may change it in
settings; the bind policy is unchanged by a port change.

## D24 — Agent state lives in app-private storage
**ACCEPTED, revised by D34.** Expected layout:

```
<files>/node/libnode.so        the runtime (exec-only)   <- moved by D34
<files>/node/grom-agent.js     the agent entry point
```

When either file is missing the server starts in `MISSING_RUNTIME`, serves
health with `"runtime":"missing"`, and refuses agent endpoints with `503`.
That is a truthful state, not an error to hide.

**Revision (D34):** the *executable* cannot live here — Android denies
`execve()` of app-private files to apps targeting API 29+, and Grom targets 36.
The binary is now launched from `ApplicationInfo.nativeLibraryDir`; only the
script stays under `<files>/node`. The rest of D24 stands.

## D25 — Readiness is a stdout line, not a port probe
**ACCEPTED.** The agent writes `GROM_READY <pid>` when it is able to serve.
Probing a port would race with the agent's own bind and cannot distinguish
"listening" from "ready".

## D26 — Logs redact secrets
**ACCEPTED.** The token and any `Authorization` header value are replaced by
`«redacted»` before logging or auditing. A unit test asserts this.

## D27 — Audit log bounds
**ACCEPTED.** The audit file rotates at 4 MiB into `audit.log.1`, keeping one
generation. Rotation records the last hash of the previous segment so the chain
stays verifiable across files.

## D28 — Genesis and chain identity
**ACCEPTED.** `prev` of the first entry is 64 zeros. The hash domain string is
`grom.audit.v1`. Changing either is a breaking format change.

## D29 — API version is an integer, independent of app version
**ACCEPTED.** `GET /grom/v1/health` returns `api: 1`. `IGromServer.apiVersion()`
returns `1`. App version is `versionName`; they move independently.

## D30 — Policy logic is JVM-testable, no Robolectric
**ACCEPTED.** Bind policy, token, audit chain, supervisor state machine, health
gate, redaction and agent config are plain Kotlin with no Android imports.
Android classes are confined to the service, the activity and the process
launcher, which are thin.

*Enforced by:* `app/src/test` compiles and runs on the JVM with no emulator.

## D31 — F1 ships only when the three blockers are closed
**ACCEPTED.** The walking skeleton is useful but not "working". F1 is complete
only when (a) `libnode.so` exists for arm64, (b) Grom has run on a real device
through a boot cycle and a crash cycle, and (c) Q8 (`node:sqlite` on aarch64) is
answered. Until then the README and the UI both say `MISSING_RUNTIME`.

## D32 — The agent announces a private control port on stdout
**ACCEPTED.** `GROM_READY <pid>` (S3-R011) is unchanged. Immediately after it,
the agent prints a second, additive line:

```
GROM_AGENT_READY <pid> <port>
```

The port is an ephemeral `127.0.0.1` listener owned by the agent; the `--port`
argument still belongs to the Kotlin HTTP facade and is never bound by the
agent. The `<pid>` must equal the child the supervisor spawned, exactly like
S3-R013, or the line is treated as a crash. An unknown line would otherwise be
discarded by `HealthGate`, so the extension cannot break older supervisors.

*Why not a second fixed port:* a fixed port collides with other apps and cannot
be inferred from the facade port. *Why not a unix socket:* it needs a path,
which would have to appear in argv or in the runtime directory, and the
ephemeral port needs neither.

*Enforced by:* `AgentChannelTest`, `tools/test-agent-runtime`.

## D33 — `exec` is proxied, never synthesized
**ACCEPTED.** `POST /grom/v1/agent/exec` forwards the request to the agent and
returns the agent's status and body unchanged. If the agent cannot be reached
the answer is `502 AGENT_UNAVAILABLE`; if it has not announced a control port
the answer is `503 AGENT_NOT_READY`. The server never returns `{"ok":true}` for
an op it did not execute, and a `503 STORAGE_UNAVAILABLE` from the agent reaches
the caller as `503`.

*Enforced by:* `RouterTest` (status passthrough), `AgentClientTest`,
`ServerRuntime.exec`.

## D34 — The executable lives in the APK native library dir, not in files/
**ACCEPTED.** The agent is launched as:

```
<ApplicationInfo.nativeLibraryDir>/libnode.so     executable, packaged in jniLibs
<files>/grom/node/grom-agent.js                   data, read by Node
```

with `jniLibs.useLegacyPackaging = true` in `build.gradle.kts` (AGP's
supported way to get `extractNativeLibs="true"`) and `LD_LIBRARY_PATH`
set to the same directory when the runtime is dynamically linked.

*Why:* Android denies `execve()` of files in app-private storage for apps
targeting API 29+; Grom targets 36. D24's `<files>/node/libnode.so` could
therefore never be executed on a supported device, however valid the ELF was.
JavaScript is data, so it stays in app storage. `AssetRuntime`/`RuntimeInstaller`
remain for the operator path that ships a binary under `assets/`, but the native
library dir wins whenever it holds one.

*Consequence for packaging:* dependencies must be co-located and named
`lib*.so` — Termux SONAMEs such as `libssl.so.3` are renamed and their
`DT_NEEDED`/`DT_SONAME` strings rewritten in place, which is why the runtime is
assembled by `scripts/fetch-node-android.sh` rather than copied by hand.

*Enforced by:* `AgentLauncherTest` (paths, `LD_LIBRARY_PATH`), lint,
`scripts/fetch-node-android.sh --verify`.

## D35 — The runtime is pinned, vendored and verified by execution
**ACCEPTED.** `scripts/fetch-node-android.sh` assembles the arm64 runtime from
pinned bionic packages (Node v24.22.3 LTS and its libraries), checks a SHA-256
for every archive, and treats the build as successful only after the binary
**runs** — version, a `node:sqlite` round trip (Q8), and the agent suite.

*Why not cross-compile here:* this host is aarch64 with an x86_64-only NDK and
no Docker; a full V8 build would also need RAM this environment does not have.
Not compiling is not the same as not knowing: the vendored binary is the same
bionic/arm64 target, and executing it through `/system/bin/linker64` answers the
only question that mattered — does Node, including `node:sqlite`, run where the
phone will run it.

*Why not commit it:* ~91 MB of binaries in an Apache-2.0 repository defeats
auditability, and a runtime must stay an explicit operator action (S1-R003).

*Enforced by:* `scripts/fetch-node-android.sh --verify`, `docs/RUNTIME.md`.

## D36 — The operator surface is monochrome, with an explicit light/dark choice
**ACCEPTED.** The app has two palettes: white ground / black ink and black
ground / white ink. There is no brand accent; colour is reserved for state
(green positive, red negative) so "running" and "runtime missing" are readable
without reading. Cards are near-flat surfaces with hairline borders, labels are
tracked uppercase, and the status is the largest type on the screen — the
zen.com-style minimalism the operator asked for, applied to a server console.

The mode is `Auto` (follow the system), `Light`, or `Dark`, stored with the other
non-secret preferences; the app never flips it on its own. The window, status
bar and navigation bar are painted from the same palette, and the app icon is
the same mark in both modes.

*Enforced by:* `GromTheme`, `:app:lint`, and the `values/` + `values-night/`
resource pair.

## D37 — The update check is operator-initiated and reads only the release feed
**ACCEPTED.** "Check now" performs exactly one HTTPS GET to
`https://api.github.com/repos/sveatoken/grom/releases/latest`. No account, no
identifier, no token, no background job, no analytics. The response is parsed by
Grom's own JSON reader (D3), the version is compared numerically against
`BuildConfig.VERSION_NAME` (flavour suffix stripped), and the result is one of:
newer release, current, or failure. A failure is shown as a failure.

A newer release is opened in the browser — either the flavour-matching APK asset
or the release page. Grom never downloads or installs an APK itself. This
amends S1-R003 and adds S1-R033; it is why `tools/test-policy` permits exactly
one absolute URL, in `UpdateChecker.kt`, and fails if any other source grows one.

*Enforced by:* `JsonParserTest`, `UpdateCheckerTest`, `tools/test-policy` (17).

## D38 — Release APKs bundle the runtime and are verified before publishing
**ACCEPTED.** `.github/workflows/release.yml` runs on a `v*` tag: it assembles
the pinned arm64 runtime, installs it into `jniLibs`, builds both role flavours,
and verifies each APK contains `lib/arm64-v8a/libnode.so` and
`assets/node/grom-agent.js` and passes `apksigner verify` before attaching it to
the GitHub release with a `SHA256SUMS` file. The release is the same document
the in-app update check reads, so tagging is the whole update mechanism.

Release builds use the committed test key (D4) until a production key exists;
the release notes and README say so. A public release signed with a test key is
installable and updatable, but it does not establish publisher identity.

*Enforced by:* `.github/workflows/release.yml`, `scripts/build-node-android.sh
--verify`.
