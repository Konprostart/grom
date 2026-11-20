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

