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
`GromService.onBind` MUST verify the caller before returning a Binder.

### S4-R008
Verification compares signatures by UID:
`PackageManager.checkSignatures(Process.myUid(), Binder.getCallingUid())` must
equal `SIGNATURE_MATCH`. On mismatch, `onBind` returns `null`.

### S4-R009
Verification MUST NOT depend on resolving the caller's package name, so that
package-visibility restrictions on API 30+ cannot weaken the check.

### S4-R010
A rejected bind MUST be logged locally with the calling UID.

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
signature get all of them in F1.

### S4-R012
`apiVersion()` returns `1` for F1. Clients MUST check it before assuming a
method exists.

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

### S4-R020
The SDK MUST NOT require any permission in the client manifest. Holding a
matching signature is sufficient.

## 6. Conformance apps

### S4-R021
`testapps/samecert` is signed with the Grom test key. On a device where Grom is
installed, it MUST bind and read `apiVersion() == 1`.

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
