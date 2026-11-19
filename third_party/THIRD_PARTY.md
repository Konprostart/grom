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

## Build and test (not shipped in the APK)

| Component | Version | Licence | Purpose | Build |
|---|---|---|---|---|
| Android Gradle Plugin | 8.11.0 | Apache-2.0 | `com.android.application`/`com.android.library` | requirement |
| Kotlin Gradle plugin | 1.9.22 | Apache-2.0 | `org.jetbrains.kotlin.android` | requirement |
| Gradle | 8.14.3 | Apache-2.0 | build system | toolchain |
| JUnit | 4.13.2 | EPL-1.0 | JVM unit tests | requirement |
| Hamcrest | 1.3 | BSD-3-Clause | transitive of JUnit | toolchain |
| Android SDK Platform | 36 | Android SDK terms | compile target | toolchain |
| Android SDK Build Tools | 35.0.0 | Android SDK terms | aapt2, apksigner, zipalign | toolchain |

## Agent runtime (installed separately by the operator)

| Component | Version | Licence | Purpose | Build |
|---|---|---|---|---|
| Node.js (arm64 static build) | 22.x LTS | MIT | the agent runtime itself | runtime |

## Deliberately absent

No HTTP server library, no JSON library, no dependency-injection framework, no
analytics SDK, no crash reporter, no networking client. See D3 and S1-R003.

If a row above is removed, delete it here in the same commit.
