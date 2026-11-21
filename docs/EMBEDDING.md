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
        capabilities = setOf("kv", "lightning.ref")
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
    override fun onConnected(apiVersion: Int) { /* apiVersion == 3 */ }
    override fun onDisconnected(reason: String) { /* show the reason */ }
})
val status = client.call(null) { it.statusJson() }
```

`GromEmbed.newClient` uses the host's own package; the signature gate passes by
construction because caller and service are the same UID.

`GromEmbed.stop(context)` stops the service. Tokens, projects and settings stay
on disk.

---

## 4. The API 3 surface

| Method | Purpose |
|---|---|
| `projectsJson()` | registered projects with their **derived** liveness |
| `announceProject(json)` | register or heartbeat; returns the stored record |
| `removeProject(id)` | unregister |
| `configExportJson()` | sanitised hand-off document (D53) |
| `agentExec(op, args)` | whitelisted agent op, returned verbatim |
| `sshStatusJson()` … `sshClearPin(id)` | the operator's SSH bridge (D58) |
| `lightningProbeJson()` | run the Lightning `health` op now |

Every method runs the same per-transaction signature check as API 1 and 2. A
foreign certificate still never reaches the server state.

### Project liveness

A project is `alive` only while it keeps announcing: the record is refreshed by
`announceProject` and the state is `alive` for 90 seconds after the last
heartbeat, then `stale`. Nothing in the document can set `alive` directly, and
registration is not evidence that a process is running.

Recommended heartbeat: every 30 seconds while the host is in the foreground. A
host that stops (process death, user swipe-away) becomes `stale` on its own.

### Local data

The agent's `kv.*` ops are available through `agentExec`. Namespace keys per
project (`p:<projectId>:<key>`) so two embedded projects cannot read each
other's values. Without `node:sqlite` the call answers
`503 STORAGE_UNAVAILABLE`; it never fakes a successful write.

---

## 5. What changes at the trust boundary

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
