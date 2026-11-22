# Grom — decision log

Status legend: **ACCEPTED** (normative), **REJECTED** (recorded so it is not
re-proposed), **DEFERRED** (out of F1, tracked for later).

Provenance: see `docs/RECOVERY.md`. This log is a re-derivation of the D1–D31
record named in the handoff, extended with D32–D48 as the agent runtime, the
operator surface, updates, release packaging and the public data plane were
built out. The numbering is kept so cross-references survive.

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

## D39 — The public data plane is separate from the loopback control plane
**ACCEPTED.** The server keeps two listeners with two independent policies. The
control plane (`/grom/v1/*`) is unchanged: loopback only, bearer-token
authenticated, and fatal to start when it cannot bind (D5). The data plane
(`/v1/*`) is the super server: separately enabled, separately bound, separately
authenticated, default port `47002`, and its refusal or failure is reported in
status without stopping the control plane or the agent. The two planes share the
process, the audit chain and the supervised agent, and nothing else. Data-plane
code lives under `superserver/`; the control-plane HTTP code does not import it,
and the only wiring is in `ServerRuntime` and the app's access card.

*Why not extend the existing listener:* one listener means one bind policy and
one credential, so enabling remote access would silently widen the control plane
and its token. Two planes keep the default safe and make the risky mode a
separate, explicit decision.

*Enforced by:* `SuperServerIntegrationTest`, `tools/test-policy` (6, 22),
`ServerRuntime`.

## D40 — Super-server configuration is atomic, validated, and secret-free
**ACCEPTED.** `<files>/grom/super-server.json` is written to a temporary file,
hardened to owner-only permissions, then renamed over the previous file, so a
crash never leaves half a policy. Loading is bounded at 64 KiB; an oversized,
unreadable or non-object file falls back to the safe defaults with a reported
`lastError`, never to a listener. The file holds a PBKDF2 hash and Ed25519
public keys, so it contains no usable credential — except the backend key, which
is sealed (D44). `SuperServerConfig.validate(controlPort)` runs before a socket
exists and refuses with a stable code; the server never "starts anyway" on a
configuration it could not honour.

*Enforced by:* `SuperServerConfigStore`, `SuperServerConfig`,
`SuperServerHost.startLocked`, `tools/test-policy` (18, 21).

## D41 — A non-loopback bind requires a credential; PUBLIC requires an acknowledgement
**ACCEPTED.** The data plane has three bind modes. `LOOPBACK` is `127.0.0.1` and
the default. `LAN` resolves the current site-local IPv4 address, preferring a
private address and never selecting loopback, wildcard or link-local; no usable
address is `NO_LAN_ADDRESS`, not a fallback. `PUBLIC` is `0.0.0.0` and is
refused unless `acknowledgePublicBind` is true. Any non-loopback mode is refused
without a password hash or an enabled key (`CREDENTIAL_REQUIRED`). The hostname
is never resolved and a bind failure is reported, never retried on another
address.

*Why the acknowledgement:* the wildcard means every interface, including any the
operator did not think about. A boolean in the file that the app sets only when
the operator cycles to *all interfaces* makes the decision deliberate and
auditable.

*Enforced by:* `SuperBindPolicy`, `SuperServerConfig.validate`,
`SuperServerHost`, `tools/test-policy` (6, 18).

## D42 — Password sessions and Ed25519 request signatures are the two credentials
**ACCEPTED.** Both mechanisms are supported and the mode selects which are live:
`PASSWORD`, `KEY`, or `BOTH` (the default). A password is verified against
`pbkdf2-sha256$iterations$salt$hash` (PBKDF2-HMAC-SHA256, 16-byte salt, 150000
iterations, 32-byte key) in constant time, then exchanged for a session token of
32 random bytes, base64url, stored only as its SHA-256 digest, 12 h TTL and at
most 16 live sessions. A signed request instead carries `X-Grom-Key`,
`X-Grom-Timestamp`, `X-Grom-Nonce` and `X-Grom-Signature` over the exact
six-line `grom-ed25519-v1` canonical string; timestamps are accepted within
±300 s and a nonce is remembered only after the signature verifies, so replays
are rejected without letting an unauthenticated caller fill the cache. Failures
are uniform: the wire answer is one `401 UNAUTHORIZED`.

*Why two:* a human-run client can type a password once and keep a short-lived
session; a machine client can avoid putting any reusable secret on the wire,
which matters when the traffic crosses a proxy the operator does not control.
Neither mechanism is enough on its own for both consumers.

*Enforced by:* `Ed25519Test`, `PasswordHashTest`, `RequestSignerTest`,
`SessionStoreTest`, `RateLimiterTest`, `tools/test-policy` (19, 20).

## D43 — Lightning is an adapter, never a custody layer
**ACCEPTED.** Grom holds no wallet keys, signs no payments, computes no balance
and matches no orders. The `lightning` plugin forwards an operator-configured
backend's answer, in integer millisatoshis, and every failure is explicit: an
unimplemented capability is `501 NOT_SUPPORTED` naming it, an unconfigured
backend is `503 LIGHTNING_UNCONFIGURED`, an unreachable one is `503
LIGHTNING_UNAVAILABLE`. A balance is either measured or absent; there is no
default, no cached zero and no optimistic pending. This narrows S1-R002 for the
data plane without repealing it: money vocabulary is confined to the adapter,
and `tools/test-policy` fails if it appears elsewhere in server code.

*Why an adapter:* custody is a different product with different liabilities.
An operator who already runs a wallet should be able to reuse it; an operator
who does not should not accidentally acquire one.

*Enforced by:* `LightningBackend`, `LightningPluginTest`, `LnbitsBackendTest`,
`tools/test-policy` (10).

## D44 — The backend credential is sealed by the Android Keystore
**ACCEPTED.** The LNbits API key is the only secret Grom stores that is valuable
to an attacker off the device: it grants spend on a service Grom does not own.
It is encrypted with AES-256-GCM
under a non-exportable Android Keystore key (alias `grom-super-secrets`) and
stored only as base64 of `version || ivLength || iv || ciphertext+tag`. The
plaintext exists in memory while a backend object lives and in the `X-Api-Key`
header; it never appears in the file, the status document, the audit log, a URL
or an exception message. A keystore key that was rotated or deleted makes the
sealed value unreadable, which is reported as an unconfigured backend, not a
crash and not a plaintext fallback.

*Why not the control token (D20):* the control token is checked by the same
process that could read it anyway, so wrapping it buys little. A remote
backend credential is different — it is useful to an attacker off the device.

*Enforced by:* `SecretBox`, `AndroidKeystoreSecretBox`,
`SuperServerHost.buildBackend`, `tools/test-policy` (21).

## D45 — The agent is exposed as a plugin, not as a second raw port
**ACCEPTED.** The supervised agent stays loopback-only and token-authenticated.
The data plane reaches it through the `agent` plugin, which relays a whitelisted
op to the control-plane `exec` path and returns the agent's own status and body
unchanged. An unknown op is `400 UNKNOWN_OP`; an agent failure passes its status
and code through; an unreachable agent is `502 AGENT_UNAVAILABLE`; a non-JSON
reply is `502 AGENT_PROTOCOL_ERROR`. The plugin never synthesises `ok` and never
widens the whitelist, which remains `ping`, `echo`, `status`, `kv.get`, `kv.put`.

*Why:* a second raw port would need its own authentication, its own bounds and
its own audit, and would expose the agent's protocol instead of a stable API.

*Enforced by:* `AgentPlugin`, `ServerRuntime.exec`, `SuperServerIntegrationTest`.

## D46 — Server events use SSE, not WebSocket
**ACCEPTED.** `GET /v1/events` is a server-sent-events stream over the existing
HTTP/1.1 parser and worker pool. It opens with `retry: 5000` and a `hello`
event, emits `: ping` when no event was written for 15 s, ends with `bye`, and
lives at most 30 minutes.
Each subscriber owns a 32-frame bounded queue: a slow reader loses events, and
that loss is counted, rather than blocking the publisher or growing the heap.

*Why not WebSocket:* the traffic is one-way, and SSE needs no upgrade handshake,
no framing library and no second authentication path. It also passes unchanged
through the TLS terminators and tunnels an operator is told to use (D47).

*Enforced by:* `EventBus`, `SuperRouter.eventStream`,
`SuperServerIntegrationTest`.

## D47 — TLS is terminated outside the process
**ACCEPTED.** Grom contains no TLS server, no certificate storage and no
certificate rotation. The data plane speaks plain HTTP on its bound address; an
operator exposing it off loopback must put a TLS terminator (Caddy, nginx) or an
encrypted tunnel (WireGuard, Tailscale, SSH) in front. The app warns when
`PUBLIC` is selected, and `docs/SUPER-SERVER.md` states what is visible on the
wire without one: password logins in clear text, request and response bodies in
clear text, and Ed25519 signatures that reveal no key and cannot be replayed.

*Why:* certificate lifecycle, renewal and hostname validation are a product of
their own, and doing them badly is worse than delegating them. It also keeps D3
true — no TLS library is added to the APK.

*Enforced by:* `SuperServerConfig` (no TLS fields), the access-card warning in
`GromActivity`, `docs/SUPER-SERVER.md`, `tools/test-policy` (16).

## D48 — Outbound connections only to an operator-configured plugin backend
**ACCEPTED.** Grom initiates no connection of its own except the update check
(D37). A Lightning backend is the second exception, and it is narrow: the URL is
supplied by the operator at runtime, never compiled in; it must start with
`https://` (`INSECURE_BACKEND_URL`); redirects are not followed; the transport
refuses every scheme except `http` and `https`, and the configuration accepts
only `https`; responses are capped at 64 KiB; timeouts are bounded;
and the API key travels only in the `X-Api-Key` header. `tools/test-policy`
permits exactly one absolute URL in server code — the update feed — and fails on
any other. This narrows S1-R003 and S1-R028 for the data plane.

*Why:* the alternative is either no Lightning integration or a compiled-in
endpoint, and a compiled endpoint cannot be audited by the operator who is
supposed to control where their money traffic goes.

*Enforced by:* `HttpJsonClient`, `LnbitsBackend`,
`SuperServerConfig.validate`, `tools/test-policy` (17).

## D49 — The download page is static, and release assets have stable names
**ACCEPTED.** Distribution gets one human address:
`https://sveatoken.github.io/grom/`. It is plain HTML, CSS and one small script
deployed by GitHub Pages from `web/`; there is no build step, no framework, no
third-party script, no cookie and no analytics. The page has exactly one
optional network call — the public releases API — used to show the current
version and to point the primary button at the exact asset. With JavaScript
off, rate-limited or offline, the button still works because it is an ordinary
link to `releases/latest/download/grom-server29.apk`.

For that link to survive version bumps, release assets are published under
**version-free names**, `grom-server29.apk` and `grom-peripheral26.apk`; the
version lives in the release tag, the release title and the app's own build.
The in-app update checker selects assets by the role substring, so the stable
names are compatible with it (D37).

*Why:* the previous names embedded the version (`grom-0.3.0-server29.apk`),
which makes any permanent download URL impossible. A stable name is the only
way to give a non-technical person one link that keeps working.

*Enforced by:* `.github/workflows/release.yml` (asset naming),
`.github/workflows/pages.yml` (deployment), `tools/check-site` (static,
dependency-free, stable links), `docs/DOWNLOAD.md`.

## D50 — Referral identity is local-only and never leaves the device
**ACCEPTED.** An invite code is a random local identifier, not an account. It
is drawn from a Crockford base32 alphabet (no `I`, `L`, `O` or `U`), generated
once with `SecureRandom`, stored in app-private preferences, and never
transmitted. Entering a friend's code stores it locally and is reported as
exactly that: Grom has no registry, so it cannot confirm that a code exists, and
the dialog says so. The copy/share counter counts actions started on this
device, not people reached.

Where the record is visible: the loopback control plane (`/grom/v1/status`,
`IGromServer.statusJson`) and the **authenticated** `GET /v1/status` of the
super server, under `referral`. The unauthenticated `GET /v1/health` never
carries it — that endpoint is reachable by anyone who can reach the bound
address, and D39 keeps device facts on the control plane.

The share link itself is a `res/values/strings.xml` template
(`invite_link`), not a compiled-in URL in Kotlin, so the "one absolute URL in
server code" rule of D48 and `tools/test-policy` (17) is unchanged.

*Why:* a referral program that reports installs would give Grom its first
device identifier and its first mandatory outbound call. Both contradict S1,
D37 and D48. A local code still lets people share a link and paste a code
without turning the app into a tracking client.

*Enforced by:* `InviteCode`, `ReferralStore`, `GromActivity` (Invite & Earn),
`SuperServerHost.status`, `SuperRouter` (`/v1/health` has no referral block),
`tools/test-policy` (17) and `tools/check-site`.

## D51 — Rewards are "coming soon", never implied and never simulated
**ACCEPTED.** The app shows a fixed badge, **"Crypto rewards — coming soon"**,
and one sentence stating that nothing is paid today. There is no balance, no
pending amount, no estimate, no leaderboard, no count of "people you invited"
(which Grom cannot know) and no placeholder transaction. The rewards state is
the literal string `coming_soon` in the status payload.

*Why:* the moment a UI renders an amount that no system owes, the app is lying.
This project's rule is that a status is either measured or absent (D31, D33).

*Enforced by:* `ReferralStore.REWARDS_COMING_SOON`, the Invite card in
`GromActivity`, the rewards block on the download page, `tools/check-site`.

## D52 — npub and relays are reference configuration; Grom dials no relay
**ACCEPTED.** `SuperServerConfig` gains `npub`, `relays` and `remoteBridge`.
They exist so an external VPS bridge (for example a Mostro fork) can read the
operator's Nostr public key and relay list from the authenticated
`GET /v1/status` (and from the app's *Copy config* action) while Grom stays the
local control point.

The boundary is absolute: none of these fields causes a network connection,
a signature or a tunnel. `npub` is validated as a NIP-19 bech32 key with a
32-byte payload (or a 64-character hex key normalised to bech32); an `nsec1…`
secret is rejected because Grom never holds a Nostr private key. `relays` is a
list of at most 8 `ws://`/`wss://` URLs; a plaintext `ws://` is accepted only
because it may be a local relay, and the UI says so. `remoteBridge` is a
label/description (80/240 chars) and cannot contain a key, password, API key or
bearer token.

Per Q1/verified operation: the tunnel is the operator's (SSH, WireGuard,
Tailscale) and is initiated from the device. Grom does not start it. Per Q4:
LNbits stays the only Lightning adapter inside Grom; LND/CLN/NWC stay on the
VPS. `peripheral26` is unchanged.

*Why:* the alternative — a relay client and a tunnel supervisor inside the app —
would add an outbound network surface and a second set of credentials to a
server whose whole value is that it does one thing honestly. A reference list
gives the bridge what it needs without any of that.

*Enforced by:* `Nip19`, `RelayUrl`, `RemoteBridgeHint`, `SuperServerConfig.
validate` (`BAD_NPUB`, `BAD_RELAY`, `TOO_MANY_RELAYS`, `BAD_REMOTE_BRIDGE`),
`tools/test-policy` (23).

## D53 — status and "Copy config" are allow-lists, never a serialised config
**ACCEPTED.** The reference block in status is built by `SuperStatusDocument`,
and the hand-off document by `ConfigExport`. Both name every field they emit;
neither serialises `SuperServerConfig`. The export carries `npub`, `relays`,
`remoteBridge`, the data-plane shape and the Lightning backend's location and
measured health. It never carries `passwordHash`, public key material, the
sealed backend key, session tokens or audit records.

`lightningHealth.reachable` is `null` until the adapter has actually been
probed. `status()` does not call the backend, so a poll cannot turn "configured"
into "healthy", and an unreachable backend cannot make the status call slow.

*Why:* a "copy my config" button that serialises the object is one field away
from pasting a password hash into a chat window. An allow-list makes a future
field safe by default.

*Enforced by:* `ConfigExportTest` (sentinel secrets), `SuperStatusDocumentTest`,
`LightningPluginTest`, `tools/test-policy` (24).

## D54 — a reference-only change does not restart the listener
**ACCEPTED.** `SuperServerHost.reload()` compares the loaded config with the
running one via `sameRuntimePolicy`, which nulls only `npub`, `relays` and
`remoteBridge`. When nothing else changed, it updates the in-memory config and
appends `super.reload.reference`; sessions and SSE streams survive. Any other
change rebuilds the whole plane, as before, and invalidates sessions.

The `agent` plugin's `status` reads the reference block through a callback at
call time, so it sees the new values without a registry rebuild.

*Why:* editing a relay URL should not log out a paired client or drop an event
stream. The distinction is safe because those three fields are read by no
listener, no authenticator and no rate limiter.

*Enforced by:* `SuperServerConfigTest.sameRuntimePolicyIgnoresOnlyReferenceFields`,
`AgentPluginTest`, `SuperServerHost.reload`.


## D55 — the server engine is an embeddable library; `app` is a host
**ACCEPTED.** Every server package moved from `app/` to a new `:engine` Android
library (`app.grom.engine`): supervisor, control plane, data plane, audit chain,
secret store, project registry and SSH bridge. `:sdk` (AIDL + `GromClient`) is
its `api` dependency. `app/` keeps only the operator surface, the two role
flavours and the Node runtime binaries.

The engine manifest declares `GromService` with `exported="false"` and no IPC
permission: an embedded server is private to its host. The standalone
`server29` flavour replaces that declaration (`tools:node="replace"`) with the
public, `signature`-gated one. The per-transaction signature check is unchanged
and applies in both cases.

A host uses `GromEmbed`:
`recordConsent` → `start(options)` → `newClient` (Target.EMBEDDED). The engine
never starts itself: a persistent server is a visible operator decision, and
`start` refuses without recorded consent. `start` reports that the launch was
*requested*, never that the agent is ready.

*Why:* "install my app and the phone becomes a server" cannot be built by adding
an SDK to a separate app. It requires the engine in the host's own UID, where
`requireSameSigner()` is satisfied by construction and no second installation
exists. Extracting the library is mechanical; the security boundary is not
weakened because the host is the server.

*Cost, stated plainly:* the host's UID can read every Grom file (token, audit,
sealed SSH credentials). Embedding is for hosts that are the same trust domain
as the server. Anything else must use the standalone app.

*Enforced by:* merged manifests (`EmbeddedManifest` policy check 25/26),
`GromEmbed` consent gate, `tools/test-policy` 25–26, `docs/EMBEDDING.md`.

## D56 — the dashboard shows measured lamps; everything else is collapsible
**ACCEPTED.** The screen is a header, a hero status with the single Start/Stop
action, a "Now serving" card with four lamps (control plane, data plane, bridge,
project) and collapsible sections for runtime, external servers,
Nostr & Lightning, local projects, public access, update and invite.

The lamp rule is absolute:

| Lamp | Meaning |
|---|---|
| green | a measured positive value (`running`, `reachable`, `alive`, `connected`) |
| red | a measured negative value, or an explicit error |
| grey | not configured / never measured |

`enabled = true` is configuration and can never produce green. Every lamp is
paired with text, and the mapping is a pure function in `DashboardModel`, tested
on the JVM. Section open/closed state is presentation-only, stored in
`GromPrefs`.

> **Amended by D68 and D69:** the collapsible cards and the stored open/closed
> state are gone. Sections now open from a header menu, so there is nothing to
> remember; `GromPrefs.sectionOpen`/`setSectionOpen` were removed with the last
> two cards. The lamp rule above is unchanged and is what still matters here.

*Why:* a dashboard that turns green because a flag is set is worse than no
dashboard. The projection being pure means the rule has tests instead of
screenshots.

*Enforced by:* `DashboardModelTest` (every lamp mapping), `SshTunnelManagerTest`
(measured bridge state), `LightningPluginTest` (no green before a probe).

## D57 — projects register themselves and liveness is derived
**ACCEPTED.** An embedded host may register a project
(`id`, `title`, `version`, `package`, `capabilities`). The record lives in
`files/grom/projects.json` and is written atomically, owner-only, with a size
cap. A project is `alive` only while its last heartbeat is at most 90 seconds
old; afterwards it is `stale`.

The caller supplies no `state`: `state` is computed at read time and is not
persisted as a claim. Registration is not evidence that a process is running.
At most 8 projects are kept; ids follow the plugin-id shape, titles are at most
64 characters and capabilities at most 12 short names. The file contains no
secret.

*Why:* a registry that trusts a `"running": true` field would be trivial to
fake and would make the dashboard lie. A heartbeat has a cost — the host must
keep announcing — which is exactly the property we want to measure.

*Enforced by:* `ProjectRegistryTest`, `ProjectRegistry`, `tools/test-policy`.

## D58 — the SSH bridge is a client that can only forward one port
**ACCEPTED.** Grom opens a reverse SSH session to an operator-saved target and
requests one `tcpip-forward` from that host to the local data plane. It is the
only outbound TCP client in the engine besides the loopback agent client and
the Lightning adapter.

Hard rules:

* **TOFU pinning.** A target with no pin is refused; the attempt reports the
  observed `SHA256:` fingerprint. The operator confirms; the pin is stored. A
  changed key is `HOST_KEY_MISMATCH`, never "accept anyway". A caller-supplied
  trust value cannot override an existing pin. Changing host or port drops the
  pin.
* **No shell.** No shell, exec, subsystem, agent or X11 channel is ever opened.
  `SshForwardingPolicy` additionally accepts a `forwarded-tcpip` channel only
  for loopback on the data-plane port, so even a hostile server cannot reach
  another service on the phone.
* **Sealed secrets.** Password or private key is stored only as
  `AndroidKeystoreSecretBox` ciphertext. Plaintext exists only for the duration
  of a connect and never reaches a log, audit record or status document.
* **Measured state.** `connected` only after the server acknowledged the
  forward. A stopped data plane is `DATA_PLANE_NOT_RUNNING`. At most 2 active
  tunnels, 8 saved targets, 15 s connect/auth timeouts.
* **Explicit action only.** A tunnel is opened by an operator tap in the UI;
  there is no auto-connect and no background retry.

This amends S1-R003: outbound connections are still limited to the update feed,
the operator-configured Lightning backend and now the operator-saved SSH
targets.

*Why:* a phone behind CGNAT can serve a VPS only if it dials out. Doing that
with a real SSH implementation, a pinned host key and a one-destination filter
is narrower than any VPN or tunnel daemon, and it removes the need for a
second process.

*Enforced by:* `SshTunnelManagerTest` (real MINA server, real forward, bytes
through the tunnel, cap, mismatch), `SshForwardingPolicyTest`,
`SshTargetStoreTest`, `SshKeysTest`, `tools/test-policy` 27–29,
`docs/SSH-BRIDGE.md`.

## D59 — status gains the bridge and projects, but not the operator's addresses
**ACCEPTED.** The authenticated `/v1/status` and the `agent` plugin's `status`
op gain:

* `projects`: id, title, version, capabilities and derived state;
* `bridge`: `configured`, `connected`, `active` and per-tunnel
  `{id, label, state, sinceMs, lastError}`.

`/v1/health` is unchanged and still carries none of this (D39). The SSH host,
username, port and fingerprint are **not** in the server-facing status: they are
configuration of the phone, not information a remote bridge needs. The on-screen
`statusForUi()` includes them because the operator is looking at their own
device.

`ConfigExport` gains `bridge` and `projects` using the same allow-list
discipline as D53. The Lightning lamp can only become green after a real
`health` op; `lightningProbeJson()` is that explicit probe, and it audits the
result.

*Why:* the VPS should know whether the bridge is up and which projects are
alive, not where the phone dials from. Splitting the two views keeps the
outbound credential surface on the device.

*Enforced by:* `ConfigExportTest` (address sentinel), `SshTunnelManagerTest`
(status has no host/user), `SuperStatusDocumentTest`, `DashboardModelTest`.

## D60 — SSH key material is generated on the device and sealed
**ACCEPTED.** Key authentication prefers an Ed25519 pair generated inside the
app. The engine uses the CC0 EdDSA provider, writes the private half as an
unencrypted OpenSSH key blob (`PROTOCOL.key`, cipher `none`) only long enough to
hand it to `SecretBox`, and exposes the public line for
`~/.ssh/authorized_keys`. An operator may instead paste an existing
OpenSSH/PEM key; it is validated by parsing it and then sealed the same way.

Both generated and imported keys travel through one loader
(`SshKeys.keyPairFromPrivateText`), so there is one failure mode and one test
surface. The OpenSSH blob is never written to disk unsealed and never appears in
a status or export document. Because MINA SSHD's exception peeler references
`javax.management.ReflectionException`, which Android does not ship, the engine
provides a minimal stand-in and narrows lint's `InvalidPackage` suppression to
that reference.

*Why:* requiring a laptop to generate a key is the difference between a feature
an operator uses and one they skip. Generating on-device and sealing immediately
keeps the private key out of every file, backup and bug report.

*Enforced by:* `SshKeysTest` (round-trip through MINA's loader), `SshTargetStoreTest`
(no plaintext on disk), `tools/test-policy` 28.

## D61 — Project key/value access is namespaced by the engine, per project
**ACCEPTED.** Every key written on behalf of a project is stored as
`p:<projectId>:<key>`. The prefix is computed in `ProjectNamespace`, never taken
from the request, and a key that already carries a foreign `p:` prefix is
refused with `PROJECT_KEY_INVALID`. The new API 4 method
`projectExec(projectId, op, argsJson)` is the only project-facing data path: it
accepts `kv.get`/`kv.put` only, requires the project to be registered
(`PROJECT_NOT_REGISTERED`), caps a value at 32 KiB (`VALUE_TOO_LARGE`) and
routes the call through the normal agent whitelist.

Scope, stated plainly in `docs/EMBEDDING.md`: this is an ownership gate on the
API surface, not a sandbox. Applications inside the **same UID** are one trust
domain; a deliberately malicious one could announce another project's id.
Isolation between applications needs separate UIDs or signatures, which is what
the standalone + `signature` model provides (D15, D55).

The SDK was also given what it was missing: `GromProject` (typed registration,
heartbeat, `put`/`get`, `configExport`, `probeLightning`) and a real heartbeat
in `GromEmbed.start`. Before this decision the documentation recommended a
30-second heartbeat that no code implemented.

*Why:* a documented boundary that only a convention does not protect anything,
and hand-built JSON in every host app is both a bug farm and a way to silently
bypass the namespace.

*Enforced by:* `ProjectNamespaceTest`, `GromProjectTest`, `tools/test-policy` 30,
`DashboardModelTest`.

## D62 — "How Grom works" is static, last, and closed by default
**ACCEPTED.** A fourth section at the bottom of the screen explains, in plain
language: what Grom does, why most people need no rented server, and when an
external SSH server helps. The content lives in `HelpModel` + `strings.xml`, is
fixed at three blocks, and reports no state.

> **Amended by D68:** the section has seven blocks. **Amended by D69:** it is
> no longer a card on the page — it opens from the header menu as the last
> entry. The static-content rule and the test below are unchanged.

The test asserts the block count and order, that every string exists, and that
the text contains no URL, no currency, no `balance`, no `payout`, and no uptime
promise. The only honesty claim it is allowed to make is that a down link is
shown as down.

*Why:* an explanation that reads state would be one more place to lie. Keeping
it static lets the gate prove what it promises.

*Enforced by:* `HelpModelTest`.

## D63 — An external Lightning node is a reference, never a client
**ACCEPTED.** `LightningSettings.externalNode` describes a node on the
operator's own server: `kind` (`lnd`/`cln`/`nwc`), a label, and an endpoint hint
of the form `host` or `host:port`. Grom opens no gRPC channel, no NWC
connection and no socket to it; LND, CLN and NWC stay on the VPS (the operator's
Q4 answer).

Validation refuses a kind-less record (`BAD_EXTERNAL_NODE`), a URL-shaped
endpoint, oversized fields, and anything that looks like a secret — the same
anti-secret rule as the bridge hint (`nsec1`, PEM, `password=`, `token=`,
`api_key=`, `secret=`).

`endpointHint` never leaves the device: `/v1/status` and `ConfigExport` carry
`kind`, `label` and `configured` only. Changing the reference does not restart
the listener (`sameRuntimePolicy` ignores it, extending D54).

*Why:* the address of the operator's infrastructure is exactly the kind of
detail D59 already keeps off the data plane. Storing a description is useful;
dialling it from a phone would be neither safe nor honest.

*Enforced by:* `ExternalNodeRefTest`, `SuperServerConfigTest`,
`SuperStatusDocumentTest`, `ConfigExportTest` (endpoint sentinel),
`tools/test-policy` 31.

## D64 — Saved SSH servers are unlimited; only live tunnels are capped
**ACCEPTED.** The saved-target count cap was removed. The only bound is the
store's 512 KiB file limit, enforced **at write time** with `SSH_STORE_FULL`:
an over-size write would otherwise make every record unreadable on the next
start, which is silent data loss. `MAX_ACTIVE = 2` stays, because each live
tunnel costs a thread, a socket, an SSH session and a MINA transport.

The UI and `sshStatusJson()` show an advisory once more than two servers are
saved (`ssh_advisory`, `EXTRA_SERVERS_IDLE`): extra servers stay idle until the
operator connects them, and the screen says so rather than pretending the limit
is storage.

*Why:* the operator asked to stop being told "no" for saving a server, while the
phone still must not be over-committed. Two different limits deserve two
different messages.

*Enforced by:* `SshTargetStoreTest` (no count cap; full store refuses the write
and stays readable), `tools/test-policy`.

## D65 — Telegram alerts are the fourth, and last, compiled host
**ACCEPTED.** Grom may post a short state message to the operator's own chat
through the Telegram Bot API. This amends `S1-R003` with a fourth explicit
outbound case (S1-R038).

The host `https://api.telegram.org` is compiled in because it cannot be
otherwise: the Bot API is one host, not a URL the operator supplies. Every other
property that made D48 acceptable is preserved: the feature is off unless the
operator enables it, the bot token is entered at runtime and never compiled in,
the message body is a fixed string from a table, and delivery is bounded.

`tools/test-policy` check 17 is rewritten to allow exactly two compiled hosts —
the update feed (`UpdateChecker.kt`) and the Bot API (`notify/TelegramNotifier.kt`)
— and to fail if either host appears anywhere else.

*Why not route it through the SSH bridge:* the bridge is a **reverse** tunnel
(`tcpip-forward`); it lets the VPS reach the phone. It does not proxy the
phone's outbound traffic. Using it for alerts would mean adding a SOCKS/proxy
client plus a relay on the VPS — more moving parts, a weaker audit trail, and a
new failure mode. A direct HTTPS POST with a fixed body is smaller and easier to
prove.

*Enforced by:* `tools/test-policy` 17/32, `TelegramNotifierTest`.

## D66 — Delivery is bounded; there is no scheduler and no disk queue
**ACCEPTED.** One message = at most **two** attempts (immediately, then once
after 4 s), an 8 s timeout, a 16-message in-memory queue that drops the oldest,
coalescing of the same event within 10 s, and a hard ceiling of 20 messages per
minute. There is no `Timer`, no `ScheduledExecutorService`, no
`scheduleAtFixedRate`, and no persisted queue to replay after a restart.

A failed delivery is recorded as failed with a stable code and reported as such.
`Send test message` is synchronous and bypasses the queue and the rate limit,
because the operator asked at that moment; it still requires `{"ok":true}`.

*Why:* an alert system that silently retries forever becomes an unbounded
outbound client — exactly what `S1-R003` exists to prevent. Two attempts absorb
a single Wi-Fi hiccup; anything more is a monitoring service, which Grom is not.

*Enforced by:* `TelegramNotifierTest`, `tools/test-policy` 33 (no scheduler
symbol in the notify package).

## D67 — Notification state is measured, sanitised, and never leaves the device whole
**ACCEPTED.** The bot token is stored only as `SecretBox` ciphertext. The chat ID
is not a secret but it identifies the device's owner, so it stays on the phone:
`/v1/status`, the sanitised export and the audit log carry
`{enabled, configured, lastDelivery:{ok, atMs}}` and nothing else.

The lamp follows the same rule as every other lamp (D56): it is green only after
a delivery the provider accepted, red after a rejection, grey before the first
attempt. `configured=true` never turns it green.

The message contains the event text plus an optional stable code and project id.
It never contains the token, the chat ID, an SSH host or user, a fingerprint, a
Lightning URL, an `npub`, a relay, a balance or free-form exception text.

*Why:* one leaking alert would undo the care taken everywhere else — the chat
ID is the identifier an attacker needs to spam the operator, and the SSH host is
the address of their infrastructure.

*Enforced by:* `NotifySettingsStoreTest` (no plaintext on disk),
`NotifyRulesTest` (no `detail` passthrough), `TelegramNotifierTest` (no token in
an error), `ConfigExportTest` (chat-ID sentinel), `tools/test-policy` 24/33.

## D68 — The dashboard keeps only state, Start/Stop and the serving lamps; everything else opens from a header menu
**ACCEPTED.** The page is now: header (wordmark, **Menu**, theme switch), the
status hero with Start/Stop, the "Now serving" card with its four measured
lamps, then — at the very bottom — the invite card and, immediately after it,
the "How Grom works" explanation. Runtime, external servers, Nostr & Lightning,
local projects, Telegram alerts, public access and updates are no longer cards
on the page: they open from a menu in the top-right corner, in that order,
because that is the order in which ignoring them breaks something.

The menu order is data, not layout: `MenuModel.entries(serverRole)` returns the
list and `MenuModelTest` pins it. A build without a server (`peripheral26`)
gets the update entry only, because it has no tunnel, no projects and no
alerts, and listing them would open empty dialogs.

Every section panel is still built once, exactly as before, and kept detached;
opening one reparents it into a dialog. That keeps every `render*` pointing at a
live view, so an opened section is never stale, no card is rebuilt twice, and
the periodic refresh keeps updating an open panel.

The explanation was extended from three blocks to seven (what Grom does, no
rented server, the external SSH booster, Nostr/Lightning, local projects,
Telegram alerts, and the honest limits), and it is open by default on first run
because it now sits below everything else and cannot push the dashboard down.
D62's static-content rule is unchanged: no block reads state.

*Why:* the operator opens the app to see whether the server is up and to start
or stop it. Six collapsed cards between the hero and the invite code made the
things that matter compete with the things that are configured once. Nothing is
removed — only moved behind one tap — and no lamp, secret or gate changes.

*Enforced by:* `MenuModelTest` (order, uniqueness, role split, strings exist),
`HelpModelTest` (block count and order, no address/price/uptime claim),
`tools/run-gates` (lint stays at 0/0, all three JVM suites).

> **Amended by D69:** the invite card and the explanation also moved into the
> menu. The page is now the header, the hero and "Now serving" — nothing else.

## D69 — the page holds nothing but the server; the invite card and the explanation are menu entries
**ACCEPTED.** The invite card and the "How Grom works" explanation are no longer
cards on the page. They are the last two entries of the header menu, in that
order: `MenuModel.KEY_INVITE` then `MenuModel.KEY_HELP`. The page is the header
(wordmark, Menu, theme switch), the status hero with Start/Stop, the "Now
serving" card and the footer version line.

`peripheral26` gets update, invite and help — the invite card and the text exist
in that build, so listing them is not an empty promise.

The collapsible card helper (`sectionCard`/`toggleSection`), the chevron header
(`Views.sectionHeader`) and their presentation preference
(`GromPrefs.sectionOpen`/`setSectionOpen`) are deleted rather than left unused:
every section is now a menu dialog, so "is this card open" has no meaning.

*Why:* the previous increment already moved the configuration into the menu, but
left the invite card and a scroll-length explanation underneath the dashboard.
An operator opening the app still had to scroll past two informational cards to
confirm that the footer — and therefore the screen — had ended. The instruction
was explicit: the main page shows only what matters, and the explanation belongs
in the menu like everything else.

*Cost, stated plainly:* the import from D62's "open on first run" is gone.
Nothing is lost — the explanation is one tap away and is the last entry of the
menu — but a first-time user no longer sees it without opening the menu. The
section cards were also the only place that remembered UI state; that state is
now simply absent.

*Enforced by:* `MenuModelTest` (order ends with `invite`, `help`; both roles;
titles and subtitles exist), `HelpModelTest` (unchanged content rules),
`tools/run-gates` (9/9, lint 0/0).

