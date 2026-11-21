# Third-party components

Every external component that touches the build or ships inside the APK is
listed here. `tools/check-third-party` fails when this file and the build
disagree, in either direction. See D17 and S1-R026.

The right-hand column marks whether a row must also appear in a Gradle build
file (`requirement`), or is external to the build (`toolchain`/`runtime`).

## Runtime (inside the APK)

| Component | Version | Licence | Purpose | Build |
|---|---|---|---|---|
| Kotlin standard library | 1.9.22 | Apache-2.0 | language runtime | requirement |
| org.json | platform | Android | JSON on the platform (not bundled) | platform |
| HttpURLConnection | platform | Android | the two compiled outbound hosts: the operator-initiated update check (D37) and the operator-enabled Telegram alert (D65), not bundled | platform |
| Telegram Bot API (external service) | n/a | Telegram terms | delivery of the operator's own alerts (D65); no code, no SDK, no account — one HTTPS POST to a host the feature is pinned to | external |
| Apache MINA SSHD (`sshd-core`) | 2.13.1 | Apache-2.0 | reverse SSH client for the operator's tunnel (D58); no server, no shell, no exec | requirement |
| EdDSA-Java (`net.i2p.crypto:eddsa`) | 0.3.0 | CC0-1.0 | Ed25519 provider that MINA SSHD uses for generated and imported keys | requirement |
| SLF4J API + no-op binding | 1.7.36 | MIT | MINA SSHD logging; the no-op binding guarantees no secret reaches logcat | requirement |

## Build and test (not shipped in the APK)

| Component | Version | Licence | Purpose | Build |
|---|---|---|---|---|
| Android Gradle Plugin | 8.11.0 | Apache-2.0 | `com.android.application`/`com.android.library` | requirement |
| Kotlin Gradle plugin | 1.9.22 | Apache-2.0 | `org.jetbrains.kotlin.android` | requirement |
| Gradle | 8.14.3 | Apache-2.0 | build system | toolchain |
| JUnit | 4.13.2 | EPL-1.0 | JVM unit tests | requirement |
| Hamcrest | 1.3 | BSD-3-Clause | transitive of JUnit | toolchain |
| Android SDK Platform | 36 | Android SDK terms | compile target | toolchain |
| `javax.management.ReflectionException` shim | 1 file | Apache-2.0 (this project) | Android has no `javax.management`; MINA SSHD's exception peeler tests against it (engine/lint.xml) | source |
| Android SDK Build Tools | 35.0.0 | Android SDK terms | aapt2, apksigner, zipalign | toolchain |

## Agent runtime (installed separately by the operator)

Not committed and never downloaded by Grom. Assembled by
`scripts/fetch-node-android.sh` from pinned bionic aarch64 packages (D35) and
packaged into `jniLibs` by `scripts/build-node-android.sh --install` (D34).

| Component | Version | Licence | Purpose | Build |
|---|---|---|---|---|
| Node.js (bionic arm64) | 24.22.3 LTS | MIT | the agent runtime itself | runtime |
| libc++ (LLVM runtime) | 30 | Apache-2.0 with LLVM exception | `libc++_shared.so`, required by Node | runtime |
| OpenSSL | 3.6.5 | Apache-2.0 | `libssl`/`libcrypto`, required by Node | runtime |
| c-ares | 1.34.9 | MIT | asynchronous DNS inside Node | runtime |
| ICU | 79.1 | Unicode-3.0 | `Intl` support in Node | runtime |
| SQLite | 3.54.1 | Public domain | backing store for `node:sqlite` | runtime |
| zlib | 1.4 | Zlib | compression inside Node | runtime |

Every row is verified by execution, not by vendoring: see
`scripts/fetch-node-android.sh --verify` and `docs/RUNTIME.md`.

The SSH client is the only outbound networking library. It is confined to
`engine/.../ssh/`, cannot be reached from a request handler, only connects to a
target the operator saved, and is gated by the client-side forwarding policy in
`SshForwardingPolicy` (one destination: the local data plane). See D58 and
`tools/test-policy` (27).

## Deliberately absent

No HTTP server library, no JSON library, no dependency-injection framework, no
analytics SDK, no crash reporter, no general-purpose networking client. The
update check uses the platform `HttpURLConnection`; the Lightning adapter uses
`HttpURLConnection`; the only added networking library is the SSH client above,
and only for the operator's own reverse tunnel. See D3, D37, D58 and
S1-R003/S1-R033.

If a row above is removed, delete it here in the same commit.
