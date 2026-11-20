# S1 — Product and security model

Normative requirements for Grom F1. IDs are stable; do not renumber.
`MUST`/`MUST NOT`/`SHOULD` carry their RFC 2119 meaning.

> **Amendments (D39–D48).** The public data plane specified in S5 extends the
> product beyond one loopback listener. `S1-R001` now describes the control
> plane; an operator may separately enable the data plane of S5 under D39 and
> D41. `S1-R002` is narrowed, not repealed: Grom still holds no balance and
> decides no payment, and the Lightning adapter (D43) only forwards to an
> operator-configured backend. `S1-R003` is narrowed by D48: the process may
> open a connection to a backend URL the operator supplied at runtime and must
> never compile one in. `S1-R010` applies unchanged to the control plane; for the
> data plane the bind is chosen by `SuperBindPolicy`, and a non-loopback choice
> requires a credential and, for the wildcard, an explicit acknowledgement
> (D41). No other S1 requirement changes.

## 1. Purpose

### S1-R001
Grom turns an Android device into a **local agent**: it exposes a small,
authenticated, loopback-only API through which an operator can query state and
trigger whitelisted local operations.

### S1-R002
Grom MUST NOT hold, compute, authorise, or transmit value. It has no ledger, no
balance, no account, no settlement, no payment credential.

### S1-R003
Grom MUST NOT initiate an outbound connection to the internet on its own. There
is no telemetry, analytics or crash reporting. The single exception is the
operator-initiated update check (S1-R033): one HTTPS GET to the public release
feed, only after the operator explicitly asks for it.

### S1-R004
Grom MUST be usable with no account, no sign-in, and no cloud component.

## 2. Roles

### S1-R005
Two roles are shipped from one source tree: `server29` (minSdk 29) and
`peripheral26` (minSdk 26).

### S1-R006
Only the `server29` role may bind a listening socket. A `peripheral26` build
MUST NOT contain a live code path that binds.

### S1-R007
Both roles must be installable side by side on the same device only if the
application IDs differ; by default they do not, so the roles are mutually
exclusive installs.

## 3. Threat model

### S1-R008
Primary adversaries: (a) another app on the same device, (b) a process running
as the same Linux UID after a compromise, (c) a human with the unlocked device.

### S1-R009
Explicitly out of scope for F1: a malicious platform/OS, physical extraction of
app-private storage, side-channel attacks, and network attackers — there is no
network exposure (see S1-R010).

### S1-R010
The listener MUST bind loopback only. Binding to `0.0.0.0`, `::`, a LAN address,
or a hostname that resolves off-loopback is a hard failure, not a warning.

### S1-R011
Every state-changing API call MUST require a bearer token. The only exception is
the health endpoint, which MUST expose no secret and no user data.

### S1-R012
The IPC surface MUST be reachable only by applications signed with the same
certificate as Grom.

### S1-R013
Secrets (token and any future signing material) MUST NOT appear in process argv,
in the child environment, or in logs.

## 4. Auditability

### S1-R014
Every authenticated state-changing request MUST append exactly one audit entry.
The append MUST happen whether the operation succeeded or failed.

### S1-R015
Audit entries MUST be hash-chained so that deletion, reordering or editing of
any entry is detectable by replay.

### S1-R016
Audit verification MUST be available without stopping the server.

## 5. Honesty of state

### S1-R017
When the agent runtime is absent or unhealthy, the server MUST report that
state. It MUST NOT report `ok` for a capability it cannot perform.

### S1-R018
The UI and the health payload MUST agree about the runtime state at all times
within one supervisor tick.

### S1-R019
The application MUST NOT crash when the runtime is missing; it starts, serves
health, and returns `503` for agent endpoints.

## 6. Failure handling

### S1-R020
A crash of the agent process MUST NOT crash the server process.

### S1-R021
Three agent crashes within a rolling window MUST quarantine the agent and stop
restarts.

### S1-R022
Quarantine MUST require an explicit human action to clear and MUST survive
process death and device reboot.

### S1-R023
Automatic start on boot MUST be opt-in, persisted, and MUST NOT start a
quarantined agent.

## 7. Data

### S1-R024
The token and the audit log live in app-private storage. No world-readable or
world-writable file may be created.

### S1-R025
Grom MUST NOT persist user content beyond the audit metadata needed for
S1-R014.

## 8. Dependency and licence discipline

### S1-R026
Every third-party component MUST be listed in `third_party/THIRD_PARTY.md` with
version, licence and purpose.

### S1-R027
The repository licence is Apache-2.0 and every source file carries an SPDX
header.

### S1-R028
No dependency may be added that contacts the network at runtime.

## 9. Compatibility

### S1-R029
The HTTP API is versioned (`v1`) and reports its version in health. Additive
changes keep the version; removals require `v2`.

### S1-R030
The AIDL interface is versioned by `apiVersion()`. Clients MUST check it before
using methods introduced later.

## 10. Conformance

### S1-R031
Each requirement in S1–S4 maps to at least one automated check or to a named
manual check in `docs/F1-implementation-plan.md`.

### S1-R032
A conformance run is: `gradle test`, `tools/check-third-party`,
`tools/test-policy`, `tools/verify-ipc-signing`, `tools/test-agent`, and
`gradle lint`.

## 11. Updates

### S1-R033
The app MAY check for updates only as an explicit operator action. The check is
one HTTPS GET to `https://api.github.com/repos/sveatoken/grom/releases/latest`
with no account, identifier or token; it never runs on a schedule, on boot, or
in the background. A failed check MUST be reported as a failure, never as "up to
date". The app MUST NOT download or install an APK itself: a newer release is
handed to the operator as a link (the release page or the matching APK asset).

### S1-R034
The operator surface MUST be usable in both light and dark mode, and the mode
MUST be an explicit operator choice (Auto / Light / Dark) rather than something
the app changes on its own (D36).

