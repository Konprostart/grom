# Grom

**A universal, lightweight Android server — the phone as an agent, never as an
authority for money.**

Grom exposes a small, authenticated, loopback-only HTTP API and a
signature-protected Binder surface on an Android device. It supervises a Node
agent, records every state change in a hash-chained audit log, and refuses to
believe anything about itself that is not true.

```
grom/
├── app/                    Android app, two role flavours
│   ├── src/main/           shared: activity, manifests, resources
│   ├── src/server29/       the server role manifest (service, boot receiver)
│   ├── src/peripheral26/   the peripheral role (no socket, no service)
│   └── src/test/           JVM tests for all policy logic
├── sdk/                    app.grom.sdk + AIDL app.grom.sdk.ipc
├── testapps/               samecert / foreigncert — the C-008 evidence
├── docs/                   000-decisions, specs S1–S4, F1 plan, RECOVERY
├── tools/                  policy, third-party, IPC-signing and agent gates
├── scripts/                test keys and the arm64 Node build recipe
├── third_party/            the dependency ledger
└── keys/                   TEST-ONLY signing material (see D4)
```

## Status

F1, the walking skeleton, is **complete**: all nine steps of
`docs/F1-implementation-plan.md` pass their gates on this tree.

It is **not yet a working server**, and it says so:

| Blocker | Consequence |
|---|---|
| **B1** `libnode.so` for arm64 is absent | agent endpoints answer `503 MISSING_RUNTIME` |
| **B2** nothing has run on a device (no `adb` here) | boot cycle and vendor task-killers unverified |
| **B3** Q8: does arm64 Node expose `node:sqlite`? | storage backend undecided |

The app builds, installs, and reports `MISSING_RUNTIME` honestly. That is the
designed behaviour (D24, D31), not a bug.

## Build

Pinned toolchain: AGP 8.11.0, Kotlin 1.9.22, compileSdk 36, JDK 17, Gradle 8.14.3.

```bash
gradle :app:assembleServer29Debug
gradle :app:assemblePeripheral26Debug
gradle :app:testServer29DebugUnitTest
```

The server role is `server29` (minSdk 29). The peripheral role is
`peripheral26` (minSdk 26) and never binds a socket.

## Conformance

```bash
gradle :app:testServer29DebugUnitTest     # JVM policy/logic tests
gradle :app:lint                          # lint, warnings are not failures
tools/check-third-party                   # dependency ledger matches the build
tools/test-policy                         # 17 static invariants
tools/test-agent                          # secrets go over stdin, not argv/env
tools/verify-ipc-signing                  # C-008, needs the three APKs
```

`verify-ipc-signing` needs:

```bash
gradle :app:assembleServer29Debug \
       :testapps:samecert:assembleDebug \
       :testapps:foreigncert:assembleDebug
```

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

Grom does not download anything (D16). Install the runtime yourself:

```
<app files>/grom/node/libnode.so        the runtime, executable
<app files>/grom/node/grom-agent.js     the agent entry point
```

`scripts/build-node-android.sh` prints the Docker line that produces a static
arm64 Node binary, and the one-liner that answers Q8 on a real device.

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
