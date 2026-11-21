# Embedding Grom in another Android app (D55)

Grom is two artifacts:

| Artifact | What it is |
|---|---|
| `:engine` | the whole server as an Android library (`app.grom.engine`) |
| `:app` | the operator surface plus the two role flavours |

The shipped Grom app is just the reference host. Any application can depend on
`:engine` and get the same server — the same supervisor, loopback control plane,
public data plane, audit chain, project registry and SSH bridge — inside its own
package, without a second installation and without an IPC permission.

---

## 1. Add the module

`settings.gradle.kts` of the host project:

```kotlin
include(":sdk")
include(":engine")
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":engine"))
}
```

The engine's manifest is merged automatically. It declares:

* `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`;
* `GromService` with `android:exported="false"` and **no** IPC permission.

That default is deliberate. An embedded server is private to the host. If a host
wants to expose the Binder to another of *its own* apps, it must declare the
service again with `exported="true"` and a `signature` permission, exactly as the
standalone `server29` flavour does.

The Node runtime binaries are **not** part of the library: `execve` requires the
file to live in the hosting APK's `nativeLibraryDir` (D34). Add them to the host
app with `scripts/fetch-node-android.sh --install-dir <host>/app/src/main/jniLibs`.
Without them the server runs and honestly reports `MISSING_RUNTIME`; `kv.*`
returns `503 STORAGE_UNAVAILABLE`.

---

## 2. Ask the operator once

A server is a persistent process. The engine refuses to start without recorded
consent, so the host must show its own explanation first:

```kotlin
if (!GromEmbed.consentGiven(context)) {
    showDialog(
        title = "Run a local server?",
        message = "This app can run Grom, a local server, while it is open. " +
                  "Nothing is exposed to the internet unless you enable it."
    ) { granted ->
        GromEmbed.recordConsent(context, granted)
    }
}
```

The consent flag lives in the host app's private preferences. It is not a server
setting and it is not transmitted anywhere.

---

## 3. Start and register (one call)

```kotlin
val result = GromEmbed.start(
    context,
    options = GromEmbed.Options(
        projectId = "my-wallet",           // ^[a-z][a-z0-9-]{1,31}$
        projectTitle = "My Wallet",        // <= 64 chars
        projectVersion = "1.2.0",
        capabilities = setOf("kv", "lightning.ref"),
        heartbeat = true,                  // repeat the announcement (default)
        heartbeatIntervalMs = 30_000       // clamped to 5 s … 10 min
    )
) { registered ->                      // runs on a background thread
    if (!registered) Log.w("Grom", "project registration did not complete")
}

if (!result.requested) Log.e("Grom", "start refused: ${result.reason}")
```

`start` returns as soon as the platform accepts the foreground-service start.
`requested = true` does **not** mean the agent is ready: the runtime may be
missing. Ask the server for the truth:

```kotlin
val client = GromEmbed.newClient(context)   // Target.EMBEDDED
client.bind(object : GromClient.Listener {
    override fun onConnected(apiVersion: Int) { /* apiVersion == 5 */ }
    override fun onDisconnected(reason: String) { /* show the reason */ }
})
val status = client.call(null) { it.statusJson() }
```

**Android 12 and later.** `startForegroundService` from the background throws
`ForegroundServiceStartNotAllowedException`. `GromEmbed.start` catches it and
returns `requested = false` with a `START_REFUSED:` reason, but the call should
not be made from that position in the first place: call `start` from a visible
`Activity` (or from a user-initiated broadcast), never from
`Application.onCreate` or a background `WorkManager` job. A host that needs a
boot start must use its own `BOOT_COMPLETED` receiver and explain the
exemption, exactly as the standalone `server29` flavour does.

`GromEmbed.newClient` uses the host's own package; the signature gate passes by
construction because caller and service are the same UID.

A heartbeat is started for you: the announcement repeats every 30 seconds on a
daemon thread and stops with `GromEmbed.stop(context)` (or `stopHeartbeat()` if
the host wants to keep the server up). Tokens, projects and settings stay on
disk.

---

## 4. The IPC surface (API 4 and 5)

| Method | Purpose |
|---|---|
| `projectsJson()` | registered projects with their **derived** liveness |
| `announceProject(json)` | register or heartbeat; returns the stored record |
| `removeProject(id)` | unregister |
| `configExportJson()` | sanitised hand-off document (D53) |
| `agentExec(op, args)` | whitelisted agent op, returned verbatim (operator surface) |
| `projectExec(projectId, op, args)` | **API 4**: `kv.get`/`kv.put` inside the caller's namespace |
| `sshStatusJson()` … `sshClearPin(id)` | the operator's SSH bridge (D58) |
| `lightningProbeJson()` | run the Lightning `health` op now |
| `notifySettingsJson()` … `notifyTestJson()` | **API 5**: the operator's Telegram alerts (D65) |

Every method runs the same per-transaction signature check as API 1 and 2. A
foreign certificate still never reaches the server state.

### Notifications are operator-level, not a host feature

API 5 exists so the **operator** can receive state changes on their own phone.
The engine does not ship a screen for it (D55: the library draws no UI), and it
does not send a message on the host's behalf to any chat other than the one the
operator configured, on the device the operator is holding.

A host that wants its own alert surface must draw its own screen and call
`notifySaveSettings`; there is no per-project chat, no per-project token and no
way for one project to redirect another project's alerts. This is deliberate: a
host that could pick the destination could exfiltrate state to a third party
through the operator's bot.

### Project liveness

A project is `alive` only while it keeps announcing: the record is refreshed by
`announceProject` and the state is `alive` for 90 seconds after the last
heartbeat, then `stale`. Nothing in the document can set `alive` directly, and
registration is not evidence that a process is running.

`GromEmbed.start` implements that heartbeat (30 s, three misses to go stale). A
host that stops — process death, user swipe-away — becomes `stale` on its own.
Use `GromProject.startHeartbeat` instead if you bind the client yourself.

### Local data (API 4)

Use the typed handle rather than hand-built JSON:

```kotlin
val client = GromEmbed.newClient(context)
client.bind(listener)                              // wait for onConnected
val project = GromEmbed.newProject(client, "my-wallet")

when (val write = project.put("theme", "dark")) {
    is GromProject.LocalWrite.Ok -> { /* stored: ${write.found} */ }
    GromProject.LocalWrite.StorageUnavailable -> show("local storage is not available")
    is GromProject.LocalWrite.Rejected -> show("${write.code}: ${write.message}")
}

val read = project.get("theme")                    // same sealed result type
```

The engine computes the storage key as `p:<projectId>:<key>` and accepts only
`kv.get`/`kv.put` on this path (`projectExec`). A key that already carries a
foreign `p:` prefix is refused with `PROJECT_KEY_INVALID`, an unregistered
project with `PROJECT_NOT_REGISTERED`, and a value over 32 KiB with
`VALUE_TOO_LARGE`. Without `node:sqlite` the call answers
`503 STORAGE_UNAVAILABLE`, which the SDK surfaces as `StorageUnavailable` — it
never fakes a successful write.

**Scope of the guarantee.** The namespace is an ownership gate on the API, not a
sandbox. Applications in the *same UID* are one trust domain: one that chose to
lie could announce its neighbour's id. Real isolation needs separate UIDs or
signatures, i.e. the standalone + `signature` model. Do not describe the
namespace as a security boundary between co-installed apps.

---

## 5. Distributing the library

The engine and the SDK are published as source modules in this repository
(`include(":engine")`, `include(":sdk")`). There is no AAR on a registry yet:
until the API stops moving, a versioned AAR would be a second artifact to keep
in sync, and `include` gives the host the same code the tests build. A host
that wants a single artifact can run `./gradlew :engine:assembleRelease` and
consume the local AAR; see `RELEASING.md`.

## 6. What changes at the trust boundary

Embedding means the host's UID owns the server. That is the point — and it is
also the cost:

* the host process can read every Grom file (token, audit chain, SSH targets,
  sealed credentials) because they are in its own data directory;
* a host compromise is a server compromise;
* the signature gate protects *other* apps, not the host itself.

There is no way to embed the engine and keep its storage out of the host's
reach. Hosts that need that separation must use the standalone app and the
Binder contract instead, which requires the same signing certificate.

---

## 6. What the engine does not do

* It does not draw any UI. Grom's screens in `:app` are a reference, not part of
  the library.
* It does not start itself on boot. `BootReceiver` belongs to the standalone
  `server29` flavour; an embedding host decides its own lifecycle.
* It does not download or install anything.
* It does not open a network listener unless the operator enables the public
  data plane in the configuration (D39).
