# S4 — IPC and SDK contract

The Binder surface that other apps use, and the SDK that wraps it.

## 1. Naming

### S4-R001
AIDL package is `app.grom.sdk.ipc`. Interface name is `IGromServer`.

### S4-R002
SDK package is `app.grom.sdk`. The client class is `GromClient`.

### S4-R003
Service class is `app.grom.server.service.GromService`; action is
`app.grom.sdk.ipc.GROM_SERVER`; the client targets package `app.grom.server`.

## 2. Permission

### S4-R004
The server declares
`<permission android:name="app.grom.sdk.ipc.BIND_GROMSERVER"
android:protectionLevel="signature"/>`.

### S4-R005
The service is declared with
`android:permission="app.grom.sdk.ipc.BIND_GROMSERVER"` and
`android:exported="true"` (export is required for cross-app binding, the
permission is what gates it).

### S4-R006
A client that does not hold the permission MUST fail to bind. The platform
enforces this; Grom MUST NOT rely on it alone (see S4-R008).

## 3. Signature verification

### S4-R007
Every `IGromServer` transaction MUST verify the caller before touching any
server state.

### S4-R008
Verification compares signatures by UID, on the binder thread:
`PackageManager.checkSignatures(Process.myUid(), Binder.getCallingUid())` must
equal `SIGNATURE_MATCH`. On mismatch the call throws `SecurityException` with a
`GROM_E_FORBIDDEN` message and no state is read or written.

The check lives in the transaction rather than in `onBind` because `onBind` may
run outside a binder transaction, where `Binder.getCallingUid()` is not the
caller's identity (see S4-R009).

### S4-R009
Verification MUST NOT depend on resolving the caller's package name, so that
package-visibility restrictions on API 30+ cannot weaken the check. It MUST NOT
read `Binder.getCallingUid()` on a thread that is not handling a binder
transaction.

### S4-R010
A rejected transaction MUST be logged locally with the calling UID. The
`signature` protection on the service declaration (S4-R005) is the first gate,
so a foreign signature normally fails to bind at all and never reaches S4-R008.

## 4. Interface

### S4-R011
```aidl
int      apiVersion();
String   healthJson();
String   statusJson();
String   auditTail(int maxEntries);
void     restartAgent();
void     resetAgent();
```
Callers on a foreign signature never reach these; callers on the same
signature get all of them in F1. The interface also declares
`void rotateToken()` (the IPC counterpart of S2-R020). API 2 adds
`void reloadSuperServer()`; section 8 states the additive contract.

### S4-R012
`apiVersion()` returns `1` for F1. Clients MUST check it before assuming a
method exists. API 2 returns `2` (S4-R028). Each later API section states the
value it adds; the current contract is API 9, so the value is `9` (§14).

### S4-R013
`auditTail(int)` clamps `maxEntries` to 1…200, matching S2-R018.

### S4-R014
`restartAgent()` and `resetAgent()` MUST behave exactly like the corresponding
HTTP endpoints, including audit entries and quarantine rules.

### S4-R015
`healthJson()`/`statusJson()` return the same JSON documents as the HTTP
endpoints, byte-for-byte equal for the same instant.

### S4-R016
No method may throw a checked exception to the client; failures surface as
`IllegalStateException` with a stable message code prefix (`GROM_E_`).

## 5. Lifecycle

### S4-R017
`GromClient` binds with `BIND_AUTO_CREATE` and reports the connection through a
listener, never by polling `isBound` in a loop.

### S4-R018
`GromClient.unbind()` MUST be idempotent.

### S4-R019
A dead Binder (`DeadObjectException`) MUST be reported via the listener as a
disconnect, not crash the client.

A **null binding** MUST be reported as *the service refused to start*, never as
a signature failure. A foreign certificate is refused by the platform before
`onBind` runs and surfaces as `bindService` returning false (S4-R008/S4-R009),
so a null binding can only mean the service's `onBind` returned null — which the
server role does when the runtime cannot start, for example because another
server already holds the loopback control port. Naming the wrong cause sends an
integrator after a signature problem that does not exist.

### S4-R020
The SDK MUST NOT require any permission in the client manifest. Holding a
matching signature is sufficient.

## 6. Conformance apps

### S4-R021
`testapps/samecert` is signed with the Grom test key. On a device where Grom is
installed, it MUST bind and its listener MUST fire `onConnected(apiVersion)` with
the contract version the server implements (`9` in API 9, §14).

### S4-R022
`testapps/foreigncert` is signed with a different key. `bindService` MUST return
`false` and its listener MUST never fire `onConnected`.

### S4-R023
Both apps MUST compile in the same Gradle build and be listed in
`docs/F1-implementation-plan.md` as the C-008 evidence.

### S4-R024
`tools/verify-ipc-signing` MUST verify, without a device:
1. both APKs exist;
2. the merged manifest of `app` declares the permission with
   `protectionLevel="signature"` and the service with that permission;
3. `samecert` and `app` certificates are identical (SHA-256);
4. `foreigncert` certificate differs from both.

### S4-R025
The tool exits non-zero if any of the four checks fails.

## 7. Limits

### S4-R026
The Binder interface is local-only. Grom MUST NOT expose it through any remote
transport.

### S4-R027
No AIDL method may accept a file descriptor or a custom Parcelable in F1.

## 8. API 2 — the public data plane

### S4-R028
`apiVersion()` returns `2`. API 2 is additive: every API 1 method is unchanged.
The interface also declares `rotateToken()` (the IPC counterpart of S2-R020) and
the one new transaction:

```aidl
void reloadSuperServer();
```

### S4-R029
`reloadSuperServer()` re-reads `<files>/grom/super-server.json` and rebuilds
only the public data plane described in S5. The control plane, the bearer token
and the supervised agent are untouched, so an operator can change exposure
without dropping the supervisor. It is the only way the app applies an access
change (D39).

### S4-R030
The call passes the same signature gate as every other transaction
(S4-R007/S4-R008). It never throws a checked exception (S4-R016): when the server
is not running it fails as `GROM_E_SERVER_STOPPED`, and in the peripheral role as
`GROM_E_PERIPHERAL_ROLE`. A refused data-plane configuration is not a Binder
failure; it is reported through `statusJson()` under `super.error` (S5-R003).

### S4-R031
`GromClient.API_VERSION` is `3` (it was `2` in the previous release). Clients
MUST compare the value returned by `apiVersion()` before calling a method
introduced in a later API (S4-R012). A client that only needs API 2 methods
keeps working against an API 3 server.

### S4-R032
A reload invalidates every password session of the data plane, because the
session store is rebuilt with the listener (S5-R014). Clients that held a session
token MUST sign in again; Ed25519 keys registered in the new configuration keep
working.

## 9. API 3 — the embeddable surface (D55)

### S4-R033
`apiVersion()` returns `3`. API 3 is additive: every API 1 and API 2 method is
unchanged in name, signature and behaviour.

### S4-R034
API 3 adds:

```aidl
String projectsJson();
String announceProject(String json);
void   removeProject(String id);
String configExportJson();
String agentExec(String op, String argsJson);
String sshStatusJson();
String sshGenerateKeyJson();
String sshSaveTarget(String draftJson);
void   sshDeleteTarget(String id);
String sshConnect(String id, String trustFingerprint);
void   sshDisconnect(String id);
void   sshClearPin(String id);
String lightningProbeJson();
```

Every method MUST call `requireSameSigner()` before touching server state, so a
client with a different certificate still receives `GROM_E_FORBIDDEN`.

### S4-R035
`projectsJson()` MUST return the stored records with `state` derived from the
heartbeat (`alive` for at most 90 seconds). `announceProject` MUST validate the
id, title, version, package and capabilities before writing, and MUST NOT accept
a caller-supplied `state`. Errors are `GROM_E_BAD_PROJECT`,
`GROM_E_TOO_MANY_PROJECTS` or `GROM_E_PROJECT_NOT_FOUND`.

### S4-R036
`configExportJson()` MUST return the same sanitised allow-list document as the
UI's *Copy config* action (D53). It MUST NOT contain a password hash, key
material, a sealed credential, a session token or an audit record.

### S4-R037
`agentExec` MUST pass the op through the same whitelist as the loopback HTTP API
and return `{"status":…,"body":…}`. A failure returned by the agent is data in
that envelope; the Binder call itself fails only for local reasons (quarantine,
missing runtime, unknown op).

### S4-R038
`sshConnect` is blocking (bounded by the 15 s connect and 15 s auth timeouts).
It MUST return a JSON result with `ok`, `state` and either `fingerprint`/
`learned` or a stable `code`. `HOST_KEY_UNVERIFIED` includes the observed
fingerprint so the caller can ask the operator; `HOST_KEY_MISMATCH` includes the
pin and the observed value and MUST NOT connect.

### S4-R039
`lightningProbeJson()` runs the adapter's `health` op now and returns its
measured result. It MUST NOT report `reachable: true` without a successful
backend probe, and it MUST answer `DATA_PLANE_STOPPED` when the public plane is
not running.

### S4-R040 (API 4)
`projectExec(projectId, op, argsJson)` MUST be the only project-facing data
path. It MUST compute the storage key as `p:<projectId>:<key>`, MUST refuse a
key that already carries a foreign `p:` prefix (`PROJECT_KEY_INVALID`), MUST
refuse an unregistered project (`PROJECT_NOT_REGISTERED`), MUST accept only
`kv.get` and `kv.put`, and MUST return `{"status":…,"body":…}` so a
`503 STORAGE_UNAVAILABLE` from the agent is visible as such. It MUST apply the
same per-transaction signature gate as every other method.

### S4-R041 (API 4)
`GromProject` MUST map that envelope onto a typed result where a missing
`node:sqlite` is `StorageUnavailable` and never `Ok`. `GromEmbed.start` with a
project registration MUST repeat the announcement every 30 s by default (clamped
to 5 s … 10 min) until `GromEmbed.stop`, so the dashboard's `alive` state is
derived from an ongoing heartbeat rather than a single claim.

## 10. API 5 — operator notifications (D65–D67)

`apiVersion()` returns `5`. API 5 is additive: every API 1–4 method is
unchanged. It adds:

```aidl
String notifySettingsJson();
String notifySaveSettings(String json);
String notifyTestJson();
```

### S4-R042 (API 5)
`notifySettingsJson()` MUST return
`{"enabled":…,"chatId":…,"tokenSet":…,"lastDelivery":{…}}`. `chatId` is the
operator's own chat identifier and is returned only over the signature-gated
Binder; `tokenSet` is a boolean, and the token itself MUST NOT be returned by any
method.

### S4-R043 (API 5)
`notifySaveSettings(json)` MUST accept `{enabled, chatId, token?, clearToken?}`
and MUST seal the token through `SecretBox` before the write. A token that does
not match the bot-token shape MUST be refused with `BAD_BOT_TOKEN`; a chat ID
that is not numeric MUST be refused with `BAD_CHAT_ID`; `enabled` without a
usable token or chat ID MUST be refused with `NOTIFY_NOT_CONFIGURED`. A `null`
token MUST keep the stored one, so a caller can toggle `enabled` without
resending the secret.

### S4-R044 (API 5)
`notifyTestJson()` MUST send one message synchronously and MUST report
`{"ok":…,"code":…}` where `ok` is true only when the provider answered
`{"ok":true}`. It MUST bypass the queue and the rate limit (the operator asked
in that moment) and MUST NOT alter the event table. Every API 5 method MUST pass
the same per-transaction signature gate (S4-R007).

## 11. API 6 — device identity and the fleet (D70–D75)

`apiVersion()` returns `6`. API 6 is additive: every API 1–5 method is
unchanged. It adds:

```aidl
String nodeIdentityJson();
String nodeResetIdentityJson(String confirmation);
String peersJson();
String peerPairStartJson();
String peerPairPendingJson();
String peerPairCompleteJson(String decisionJson);
String peerJoinJson(String requestJson);
String peerConfirmJson(String confirmJson);
void   peerRemove(String nodeId);
String peerRefreshJson();
```

### S4-R045 (API 6)
`nodeIdentityJson()` MUST return `{nodeId, publicKey, label, fingerprint,
createdAtMs}` and MUST NOT return the private seed or any value from which it can
be reconstructed. `nodeResetIdentityJson(confirmation)` MUST refuse any
confirmation other than `RESET` with `RESET_CONFIRMATION_REQUIRED`, and on
success MUST forget every paired device.

### S4-R046 (API 6)
`peersJson()` is the only projection that may carry a peer address, because the
caller already passed the per-transaction signature gate. It MUST return the
measured `state` (`unknown`, `alive`, `stale`, `unreachable`) and MUST NOT return
a secret.

### S4-R047 (API 6)
Pairing is three operator steps and no shortcut:
`peerPairStartJson()` opens a session and returns `{code, expiresAtMs, nodeId,
fingerprint}`; `peerPairPendingJson()` returns the waiting device (or nothing);
`peerPairCompleteJson({accept, scopes})` approves a non-empty subset of the
requested scopes or rejects. A scope that was not requested MUST be refused with
`BAD_SCOPE`.

### S4-R048 (API 6)
`peerJoinJson({address, code})` MUST validate the address and code locally,
verify the other device's acceptance signature over
`grom-pair-accept-v1`, and return an **unconfirmed** offer; nothing is written
until `peerConfirmJson({nodeId})`. `peerRefreshJson()` performs at most one
measured probe per paired device (clamped to 16 per call) and MUST NOT schedule
any future probe. `peerRemove(nodeId)` takes effect on the next request. Every
API 6 method MUST pass the same per-transaction signature gate (S4-R007).

## 12. API 7 — inbound-only trust for a hub (D76–D78)

`apiVersion()` returns `7`. API 7 is additive: every API 1–6 method is
unchanged. It adds one method:

```aidl
String peerGrantJson(String json);
```

### S4-R049 (API 7)
`peerGrantJson({publicKey, label?, nodeId?, scopes})` MUST trust a reader that
only calls in, such as the hub on the operator's VPS. The `nodeId` MUST be
derived from the public key (`Ed25519.keyId`); a supplied `nodeId` that does not
match MUST be refused with `BAD_PUBLIC_KEY`, and this device's own key MUST be
refused with `PEER_SELF`. At least one known scope is required
(`SCOPE_REQUIRED` / `BAD_SCOPE`). The stored record MUST have an empty address,
MUST never be probed, and MUST resolve to a `Principal.Peer` with exactly those
scopes — never to an operator. Every API 7 method MUST pass the same
per-transaction signature gate (S4-R007).

## 13. API 8 — measured Doctor diagnostics (D80–D84)

`apiVersion()` returns `8`. API 8 is additive: every API 1–7 method is
unchanged. It adds one method:

```aidl
String doctorJson();
```

The value is a single integer that names the whole contract, so a client MUST
compare it before calling an API 8 method (S4-R028). The runtime reports the
constant `GromClient.API_VERSION` rather than a literal of its own: the number
drifted twice — `3` while the contract said 5, then `7` while it said 8 —
because two places were kept in step by hand. `tools/test-policy` now pins the
single-source rule.

### S4-R050 (API 8)
`doctorJson()` MUST return one measured Doctor report: a verdict, the ordered
checks with a stable level and code, and the facts behind them. It MUST be
read-only, MUST be callable while the server is stopped (the device can be
measured without it), MUST contain no credential, identifier, address or free
text, and MUST pass the same per-transaction signature gate as every other
method (S4-R007). A client MUST be able to tell `unknown` from a measured `false`
without parsing a sentence.

## 14. API 9 — host-configured data plane (D85, D86)

`apiVersion()` returns `9`. API 9 is additive: every API 1–8 method is
unchanged. It adds one method:

```aidl
String superSaveConfig(String json);
```

### S4-R051 (API 9)
`superSaveConfig(json)` MUST accept only a `SuperServerConfig.toJson()` document,
MUST validate it through the same contract the standalone screen uses (so a
refusal carries the screen's own stable code), MUST NOT write anything on
refusal, and MUST apply the document only when the data plane has been started.
It MUST return the sanitised status (`passwordSet`, key ids) and MUST NOT return
the stored password hash or any secret. It MUST pass the same per-transaction
signature gate as every other method (S4-R007), and `apiVersion()` MUST still
report `GromClient.API_VERSION` rather than a literal.
