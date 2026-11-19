# Grom

**A universal, lightweight Android server — the phone as an agent, never as an
authority for money.**

Grom exposes a small, authenticated, loopback-only HTTP API and a
signature-protected Binder surface on an Android device. It supervises a Node
agent, records every state change in a hash-chained audit log, and refuses to
believe anything about itself that is not true.

[![CI](https://github.com/sveatoken/grom/actions/workflows/ci.yml/badge.svg)](https://github.com/sveatoken/grom/actions/workflows/ci.yml)

```
grom/
├── app/                    Android app, two role flavours
│   ├── src/main/           shared: monochrome UI, manifests, resources
│   ├── src/server29/       the server role manifest (service, boot receiver)
│   ├── src/peripheral26/   the peripheral role (no socket, no service)
│   └── src/test/           JVM tests for all policy logic
├── agent/                  grom-agent.js, the supervised Node agent
├── sdk/                    app.grom.sdk + AIDL app.grom.sdk.ipc
├── testapps/               samecert / foreigncert — the C-008 evidence
├── docs/                   000-decisions, specs S1–S4, F1 plan, RELEASING
├── tools/                  policy, third-party, IPC-signing and agent gates
├── scripts/                test keys and the arm64 Node runtime pipeline
├── third_party/            the dependency ledger
└── keys/                   TEST-ONLY signing material (see D4)
```

## Status

F1, the walking skeleton, is **complete**, and the agent runtime is real:
`agent/grom-agent.js` implements the protocol, the app installs it from its own
APK, and `POST /grom/v1/agent/exec` is proxied to the agent instead of being
answered with a fabricated `ok`. Release APKs bundle the verified arm64 bionic
Node runtime (D34, D35, D38), so an installed release starts the agent without
any extra operator step.

| Item | State |
|---|---|
| `libnode.so` for arm64 | **closed** — produced by `scripts/fetch-node-android.sh`, verified by execution, bundled into release APKs |
| Q8: `node:sqlite` on arm64 | **closed** — works on bionic Node 24.22.3; `--verify` runs a real SQLite round trip |
| Nothing has run on a physical device | **open** — no `adb` in the build environment; boot receiver, vendor task-killers and battery policy are unverified on hardware |
| Production signing key | **open** — releases use the committed test key (D4) and say so |

When the runtime is genuinely absent, the app reports `MISSING_RUNTIME` and the
agent endpoints answer `503` naming the missing file. That is the designed
behaviour (D31), not a bug.

## Install

Download the APK for your role from the
[latest release](https://github.com/sveatoken/grom/releases/latest), then:

```bash
adb install -r grom-<version>-server29.apk          # full server role
# or
adb install -r grom-<version>-peripheral26.apk      # client role, never binds
sha256sum -c SHA256SUMS                              # optional integrity check
```

Both roles share one application ID and are mutually exclusive installs
(S1-R007). Releases are signed with the committed test key: installable and
self-updatable, but not proof of publisher identity.

## Updates

The app has an **Update** card with a single **Check now** action. It performs
one HTTPS GET to the public GitHub release feed only when tapped — no scheduler,
no background work, no account, no telemetry (S1-R003, S1-R033, D37). A newer
release shows its version and a **Download APK** button that opens the matching
flavour asset in the browser. A failed check is reported as a failure, never as
"up to date". Grom never downloads or installs an APK itself.

Tagging is the release process: `git tag v0.3.0 && git push origin v0.3.0`
runs `.github/workflows/release.yml`, which builds both roles with the runtime
inside, verifies the contents and signature, and publishes the APKs and
`SHA256SUMS`. Full procedure: [`docs/RELEASING.md`](docs/RELEASING.md).

## Build

Pinned toolchain: AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, JDK 17, Gradle 8.14.3.

```bash
gradle :app:assembleServer29Debug
gradle :app:assemblePeripheral26Debug
gradle :app:testServer29DebugUnitTest
```

The server role is `server29` (minSdk 29). The peripheral role is
`peripheral26` (minSdk 26) and never binds a socket.

Release builds with the runtime (what the release workflow does):

```bash
scripts/fetch-node-android.sh --all
scripts/build-node-android.sh --install node-out/runtime
gradle :app:assembleServer29Release :app:assemblePeripheral26Release
```

## Conformance

```bash
tools/run-gates                           # all of the below, in order
gradle :app:testServer29DebugUnitTest     # JVM policy/logic tests
gradle :app:lint                          # lint, 0 errors required
tools/check-third-party                   # dependency ledger matches the build
tools/test-policy                         # 17 static invariants
tools/test-agent                          # secrets go over stdin, not argv/env
tools/test-agent-runtime                  # grom-agent.js protocol + degradation
tools/verify-ipc-signing                  # C-008, needs the three APKs
```

`verify-ipc-signing` needs:

```bash
gradle :app:assembleServer29Debug \
       :testapps:samecert:assembleDebug \
       :testapps:foreigncert:assembleDebug
```

GitHub Actions runs the same set on every push and pull request
(`.github/workflows/ci.yml`), plus the agent suite on Node 22 and 24.

## Using it

The server listens on `127.0.0.1:47001` only. The token is created on first run
and lives in app-private storage.

```bash
TOKEN=$(adb shell run-as app.grom.server cat files/grom/grom-token | tr -d '\r\n')
adb forward tcp:47001 tcp:47001

curl -s http://127.0.0.1:47001/grom/v1/health
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:47001/grom/v1/status
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:47001/grom/v1/audit/verify
```

## The agent runtime

Grom does not download anything at runtime (D16). The agent script
(`agent/grom-agent.js`) is packaged into the APK and copied into app-private
storage on first start; the Node binary runs from the APK's native library dir,
because Android will not `execve()` a file in app-private storage (D34):

```
<app native lib dir>/libnode.so     the runtime, executable (packaged from jniLibs)
<app files>/grom/node/grom-agent.js the agent entry point (from the APK)
```

```bash
scripts/fetch-node-android.sh              # pinned bionic arm64 runtime + verify
scripts/fetch-node-android.sh --verify     # runs it: version, node:sqlite (Q8), agent suite
scripts/build-node-android.sh --install     # into app/src/main/jniLibs/arm64-v8a
scripts/build-node-android.sh --status      # is this host able to build/run?
scripts/build-node-android.sh --q8          # the Q8 probe commands
```

The agent binds its own ephemeral loopback port and announces it with
`GROM_AGENT_READY <pid> <port>` after `GROM_READY <pid>`. Ops are `ping`,
`echo`, `status`, `kv.get`, `kv.put`; if `node:sqlite` is missing, `kv.*`
answers `503 STORAGE_UNAVAILABLE` rather than pretending to persist.

The repository itself holds no binaries (D35); `jniLibs/` is a build input and
stays out of git. Details and measured evidence: `docs/RUNTIME.md`.

## Design

The operator surface is deliberately monochrome: white on black and black on
white, one neutral surface, hairline borders, and colour used only for state.
The mode is an explicit **Auto / Light / Dark** choice (D36). The status card is
the largest element on the screen because "is it running?" is the only question
the app has to answer at a glance.

## Security model, in one paragraph

There is no network exposure: the listener binds loopback and fails closed on
anything else. Every endpoint except health requires a 256-bit bearer token
compared in constant time. The Binder surface is gated by a `signature`
permission *and* a runtime signature comparison, so only apps signed with the
same certificate get a Binder at all. Secrets reach the agent over stdin, never
argv or environment. Every state change is appended to a SHA-256 hash chain that
can be replayed to detect tampering. Three agent crashes in ten minutes
quarantine it, and only a human can clear that.

Full rationale: `docs/000-decisions.md`. Normative contracts:
`docs/specs/S1`–`S4`.

## History

This tree is a post-migration rebuild; the original working copy was lost.
`docs/RECOVERY.md` states exactly what was recovered, what was re-derived, and
how to graft forward if the original ever resurfaces.

## Licence

Apache-2.0. See `LICENSE`. Test keystores under `keys/` are public by design
and must never sign a distributed build.
