# "2 in 1": an app that needs a server, shipped with Grom

A host application can carry Grom inside its own APK. The operator installs
**one** package, taps **one** consent button, and gets the host's UI plus the
real Grom engine: supervisor, loopback control plane, public data plane, audit
chain, project registry, SSH bridge and Node agent — all in the host's process,
under the host's UID.

This guide is the operational companion to `EMBEDDING.md` (the API surface) and
`docs/CLUSTER.md` / `docs/HUB.md` (many devices). It answers the questions that
only appear when you actually ship the combination: which port, which lifecycle,
which permission, what happens when the operator already has Grom, and what the
APK weighs.

---

## 1. The three models, and why one of them is the recommendation

| | A — embedded (one APK) | B — companion (two APKs) | C — install side by side |
|---|---|---|---|
| Installations | **one** | two | two |
| Bind to the server | same UID, no IPC permission | **needs the same signing certificate** (S4-R007) | two servers, two UIDs |
| Server identity | the host's own | the standalone app's | one per package |
| Update channel | host's own | Grom's release | independent |
| APK cost | +~38 MB (arm64 runtime) | none | none |
| Trust | host compromise = server compromise | separated by signature | separated |
| Works today | **yes** | no (a third-party host cannot sign with Grom's key) | yes, with distinct ports (D85) |

**Model A is the supported "2 in 1".** Model B is not a code change — it is a new
trust relation: a host signed with a different certificate cannot bind to the
standalone service, and adding a second way in would weaken the invariant that
only the same signature reaches the Binder (S4-R007, S4-R008, D15). Do not ship
model B; if you ever need it, it is a decision (a user-granted permission or a
"this app may use this server" screen), not a build flag.

Model C is not "2 in 1" but it must still work, because operators do install the
standalone Grom and then a host app that embeds it. Section 6 covers it.

---

## 2. What the operator gets, and what they must be told

The engine draws no UI (D55). The host owns the screens, so the host owns the
honesty:

1. **Consent before start.** Show what a local server is and that nothing is
   exposed until the operator enables it. `GromEmbed.recordConsent` after the
   answer; `start` refuses with `CONSENT_REQUIRED` before that.
2. **A visible "server running" state**, with a way to stop it.
3. **The truth from `status()`, not from "we called start".** `started` means the
   platform accepted the foreground-service start; `state` in the status is the
   measurement. `MISSING_RUNTIME` and `QUARANTINED` are normal outcomes.
4. **The limits.** A phone in Doze, an aggressive vendor killer or a low-memory
   kill can stop the service; `force-stop` is not recoverable by any app. The
   Doctor section in the standalone app is the reference treatment of this.

---

## 3. Build it

### 3.1 Modules

```kotlin
// settings.gradle.kts
include(":sdk")
include(":engine")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":engine"))   // pulls :sdk in
}
```

The engine manifest merges automatically: `INTERNET`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, the two diagnostics
permissions (`ACCESS_NETWORK_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and a
**private** (`exported="false"`) `GromService`. You do not add an IPC permission —
caller and service are the same UID — and you do not copy permission lines by
hand.

If your app already declares permissions, check the merged manifest
(`app/build/intermediates/merged_manifests/<variant>/AndroidManifest.xml`). The
engine adds no storage, location or package-query permission; a host that adds
`QUERY_ALL_PACKAGES` or `PACKAGE_USAGE_STATS` is making its own decision, not
inheriting one from Grom (D82).

### 3.2 The Node runtime (the 38 MB)

The engine is pure Android library code; the Node agent is an executable that
must live in the APK's `nativeLibraryDir` (D34). Fetch the pinned arm64 runtime
into the host's `jniLibs`:

```bash
scripts/fetch-node-android.sh --all
scripts/build-node-android.sh --install-dir <host>/app/src/main/jniLibs
```

* **arm64 only.** x86_64 emulators do not run it. Without the runtime the server
  still starts and honestly reports `MISSING_RUNTIME`; `kv.*` answers
  `503 STORAGE_UNAVAILABLE`.
* **+~38 MB APK / ~91 MB installed.** If your host does not need the agent, do
  not ship it — a server without `MISSING_RUNTIME` is not a broken server, it is
  a server without local execution.
* Keep `jniLibs.useLegacyPackaging = true` in the host app: the runtime is
  executed from disk, so the libraries must be extracted at install time. The
  Grom app does this in `app/build.gradle.kts`.
* A host that ships *both* a peripheral build and a server build of itself can
  apply the same per-variant exclusion Grom uses (D87,
  `app/build.gradle.kts` → `androidComponents { onVariants(...) { variant ->
  variant.packaging.jniLibs.excludes } }`).

### 3.3 Choose the control port **before** the first start (D85)

The default is 47001, which is what a standalone Grom on the same phone uses.
Two servers cannot share it. Embedding hosts should take a port of their own —
47101, 47201, anything in `1..65535` that the phone does not use:

```kotlin
val options = GromEmbed.Options(
    projectId = "my-wallet",
    projectTitle = "My Wallet",
    controlPort = 47101            // chosen once, then persisted
)
val result = GromEmbed.start(context, options) { registered -> /* … */ }
when {
    result.requested -> { /* ask status() for the truth */ }
    result.reason == "PORT_IN_USE" -> show("Another server is using port 47101.")
    result.reason == "BAD_CONTROL_PORT" -> show("That port cannot be used.")
    result.reason == "PORT_CHANGE_RESTART_REQUIRED" ->
        show("Stop the server, then start it again to change the port.")
    result.reason == "CONSENT_REQUIRED" -> showConsentDialog()
    else -> show("Start refused: ${result.reason}")
}
```

`GromEmbed.setControlPort(context, port)` stores the choice without starting;
`GromEmbed.controlPort(context)` reads it back.

**Rules that matter:**

* the port is validated (`BAD_CONTROL_PORT`) and probed (`PORT_IN_USE`) before the
  service is asked to start, so the failure is a returned code;
* the app's **own** listener is never mistaken for a conflict — the service
  records the port it serves;
* changing the port while the service runs does not move the listener; the call
  returns `PORT_CHANGE_RESTART_REQUIRED` rather than pretending;
* **no silent fallback.** A server that moved itself would break every client
  that knows the old port — the hub's tunnel target, a paired device, a CLI.

A port conflict is a **fact**, not a bug: `PORT_IN_USE` also appears as
`lastError` in the status if a bind is refused for any other reason.

### 3.4 Start from a visible Activity

```kotlin
// In an Activity (or a user-initiated broadcast). NOT in Application.onCreate.
if (!GromEmbed.consentGiven(this)) { showConsentDialog(); return }
val result = GromEmbed.start(this, GromEmbed.Options(...))
```

Android 12+ refuses `startForegroundService` from the background
(`ForegroundServiceStartNotAllowedException`). `GromEmbed.start` catches it and
returns `requested = false` with a `START_REFUSED:` reason, but the call should
not be made from that position at all.

**Boot.** The engine does not start itself on boot — that is a visible decision
the host owns. A host that wants it declares `RECEIVE_BOOT_COMPLETED` and its own
receiver, checks the operator's opt-in setting, and calls `start`. On Android
12+ a boot-started foreground service needs an allowed exemption; the standalone
`server29` flavour documents its path in `docs/RUNTIME.md`, and the same
constraints apply to you.

**Stop.** `GromEmbed.stop(context)` stops the service and the heartbeat; state
stays on disk.

### 3.5 Configure the public data plane (D86, API 9)

The control plane is loopback-only and always on. The **public** data plane
(`/v1/*`, D39) is opt-in, and a host configures it through the Binder with the
same document the standalone screen writes:

```kotlin
val client = GromEmbed.newClient(context)
client.bind(object : GromClient.Listener {
    override fun onConnected(apiVersion: Int) {
        if (apiVersion < 9) return            // check before calling (S4-R028)
        val document = """
            {"enabled":true,"bind":"LAN","port":47002,"auth":"KEY",
             "keys":[{"keyId":"…","label":"laptop","publicKey":"…","enabled":true}],
             "acknowledgePublicBind":false}
        """.trimIndent()
        val status = client.call(null) { it.superSaveConfig(document) }
        // status contains "super": {enabled, running, bind, address, auth,
        //   passwordSet, keys:[{keyId,label}], error, …} — no secret.
    }
    override fun onDisconnected(reason: String) { /* show it */ }
})
```

* The document is the `SuperServerConfig.toJson()` shape. Build it from a data
  class in your own code, not by string concatenation.
* **Password auth:** put a `passwordHash` from
  `app.grom.server.superserver.auth.PasswordHash.hash(password)` in the document.
  A plaintext `password` field is simply ignored — Grom never stores one.
* A refusal is an `ApiException` carrying the screen's own code
  (`PORT_CONFLICT`, `CREDENTIAL_REQUIRED`, `PUBLIC_NOT_ACKNOWLEDGED`,
  `INSECURE_BACKEND_URL`, `BAD_KEY`, …) and **nothing is written**.
* For a listener off loopback you must supply at least one credential; `PUBLIC`
  also needs `acknowledgePublicBind: true`. These are the same rules the operator
  screen enforces — there is no "host bypass".
* Changing only `npub`/`relays`/`remoteBridge` applies without rebinding (D54);
  everything else restarts the data plane only, never the control plane.

If the data plane is off — the default — nothing listens outside loopback and no
client can reach the server. That includes `tools/grom-fleet` and a VPS hub.

### 3.6 Battery and Doctor

The engine already declares the whole diagnostics budget (D82): the merged
manifest gains `ACCESS_NETWORK_STATE` and
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and nothing else. If you want the
exemption UI in your own screens, show Android's dialog yourself:

```kotlin
// android.provider.Settings, android.net.Uri
startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    Uri.parse("package:$packageName")))
```

The standalone app asks once, explains why, and never asks again; do the same
rather than re-prompting on every launch.

* `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only lets you show Android's own
  one-tap exemption dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); the
  operator still decides. It does not exempt you automatically, and Play
  restricts it — Grom is installed by hand, which is why it is acceptable here.
* The exemption is the single most effective measure. The foreground service and
  `START_STICKY` are already handled by the engine; **boot is the host's own
  decision** (§3.4). None of them makes the process immortal, and nothing should
  claim otherwise.
* The engine's Doctor (`GromEmbed.newClient(...).call { it.doctorJson() }`, API 8)
  works even while the server is stopped and reports measured facts with an
  explicit `unknown`. A host that wants a "why did it stop?" screen should render
  that report rather than inventing its own diagnosis.

---

## 4. What "2 in 1" does and does not change

| Concern | Reality |
|---|---|
| Storage | The engine's files live in the host's private directory. The host can read the token, audit chain and sealed secrets; that is the embedding trust model (D55). |
| Identity | The embedded server has its **own** `nodeId`, derived from a seed sealed by the **host's** Keystore entry. It is not the standalone app's identity. |
| Updates | The engine and the runtime ship with the host, so they update when the host updates. There is no separate Grom channel. |
| Uninstall | Uninstalling the host removes the embedded server and its data. The standalone Grom, if installed, is unaffected. |
| Size | +~38 MB APK with the runtime, ~2–3 MB without it; the host's own code dominates otherwise. |
| Permissions | The merged manifest is the union. Check it once; a permission your host adds is a capability the server process also holds. |
| Background | The same Doze/vendor-kill constraints as the standalone app. The host must expose the exemption and the boot opt-in. |

---

## 5. What NOT to build

* **Do not call `start` in `Application.onCreate`.** It fails on Android 12+ and
  hides the failure from the operator.
* **Do not use the standalone port (47001) when the host ships its own server**
  unless you truly do not expect a standalone Grom on the same phone. If you do,
  the operator gets `PORT_IN_USE` — honest, but annoying.
* **Do not run two embedded servers in one process.** One engine, one process, one
  service; a second start replaces the project session, not the server.
* **Do not try to bind to the standalone app's service.** Different certificate
  means no Binder, by design.
* **Do not promise uptime.** A force-stop, a low-memory kill and a vendor
  "deep clean" can all stop the server. Doctor measures; it does not prevent.

---

## 6. Coexistence with a standalone Grom (model C)

An operator may have both. The rules follow from the code:

| Aspect | Behaviour |
|---|---|
| Package / service | Two packages, two services, two foreground notifications. |
| Data | Separate private directories. Nothing is shared. |
| Tokens | Separate. Rotating one does not affect the other. |
| Node identity | Separate `nodeId`s, separate peer stores. Pair them if you want them to see each other (`Devices`, D70–D75). |
| Control port | **Must differ** (D85). Use 47101+ for the embedded one. |
| Public data plane | Separate ports (47002 by default) and separate credentials. Only one may take a given port. |
| SSH tunnels | Separate saved targets; the forward-port uniqueness rule is per app (C0). Give each its own port on the VPS. |
| Uninstall | Removing one leaves the other running and intact. |

A useful operator pattern: the standalone Grom holds the fleet identity and the
VPS tunnel; the embedded server in the host app is a second, independent node
with its own scopes. Both can be paired into the same roster.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `CONSENT_REQUIRED` | `start` before `recordConsent` | Show your consent dialog first. |
| `PORT_IN_USE` | 47001 (or your chosen port) is held — usually the standalone Grom | Pick another port with `Options.controlPort`; do not reuse. |
| `BAD_CONTROL_PORT` | Outside `1..65535` | Use a real port. |
| `PORT_CHANGE_RESTART_REQUIRED` | Port changed while the server runs | `GromEmbed.stop`, then `start`. |
| `START_REFUSED: ForegroundServiceStartNotAllowedException` | Started from the background | Start from a visible Activity. |
| Binder never connects | Service could not start | Read `lastError`/Doctor; a bind conflict now says `PORT_IN_USE` instead of a generic refusal. |
| `MISSING_RUNTIME` in status | `libnode.so` not in the host's `jniLibs` | Run `scripts/build-node-android.sh --install-dir …` and rebuild. |
| `ApiException` 400 on `superSaveConfig` | The document would be refused by the screen too | Fix the document; the code names the field (`BAD_KEY`, `CREDENTIAL_REQUIRED`, …). |
| Server dies overnight | Doze / vendor killer / low memory | Request the battery exemption, check Doctor, keep the phone on power. |

---

## 8. Checklist

- [ ] `include(":engine")` (and `:sdk`) and `implementation(project(":engine"))`.
- [ ] `jniLibs` populated **or** a deliberate decision to run without the agent.
- [ ] `jniLibs.useLegacyPackaging = true` in the host app.
- [ ] `controlPort` chosen and persisted (`47101`, not `47001`).
- [ ] Consent screen → `recordConsent` → `start` from an Activity.
- [ ] UI reads `status()` and shows `state`, `lastError` and the Doctor report.
- [ ] Public data plane stays **off** until the operator turns it on; configured
      only through `superSaveConfig` (API 9, checked with `apiVersion() >= 9`).
- [ ] Battery exemption offered with Android's own dialog; boot opt-in explained.
- [ ] Merged manifest reviewed.
- [ ] The operator has been told: no uptime guarantee, no cloud account, no
      shared disk, and what "pair a device" means if you expose one.

---

## 9. Where this is enforced

| Rule | Enforced by |
|---|---|
| one API version, from the SDK | `tools/test-policy` #48, `GromClientContractTest` |
| control port is choosable, conflicts are named | `ControlPortTest`, `tools/test-policy` #49 |
| host config uses the screen's contract | `SuperServerConfigInputTest`, `tools/test-policy` #50 |
| the peripheral role carries no runtime | `tools/check-flavours`, `tools/test-policy` #51 |
| every IPC method passes the signature gate | `tools/test-policy` #15, `tools/verify-ipc-signing` |
| no address or secret in a status document | `tools/test-policy` #24, #47; `ConfigExportTest` |
