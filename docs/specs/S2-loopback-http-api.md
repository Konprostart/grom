# S2 — Loopback HTTP API

Transport, authentication, endpoints and error semantics for `v1`.

## 1. Transport

### S2-R001
The listener MUST be created only after `BindPolicy.allows(address, port)`
returns true. Two addresses are permitted: `127.0.0.1` and `::1`.

### S2-R002
Attempting to bind any other address MUST raise before socket creation and MUST
leave the server in `STOPPED`, never in a degraded "bound somewhere" state.

### S2-R003
The default port is `47001`. A configured port change MUST NOT change the
address policy.

### S2-R004
Protocol is HTTP/1.1. A request with `HTTP/1.0` MUST be rejected with `505`.

### S2-R005
`Transfer-Encoding` MUST NOT be accepted; body length MUST come from
`Content-Length`. A chunked request is answered `400` and the connection closed.

### S2-R006
At most 8 connections may be in flight. Excess connections wait in the accept
backlog, not in application threads.

### S2-R007
A connection idle for 5 s MUST be closed. A request whose headers exceed 8 KiB
MUST be answered `431`.

### S2-R008
A request body larger than 64 KiB MUST be answered `413` and the connection
closed without reading the remainder.

### S2-R009
`Connection: close` is the only supported lifecycle for F1; keep-alive is not
required and the server MAY close after one response.

## 2. Authentication

### S2-R010
The header is `Authorization: Bearer <token>`. `X-Grom-Token` is accepted as an
alternative for local tooling.

### S2-R011
Comparison MUST be constant-time over the full token length.

### S2-R012
A missing, malformed, or wrong token yields `401` with code `UNAUTHORIZED` and
no distinction between the three cases in the response.

### S2-R013
Authentication MUST be checked before routing, so that an unauthenticated
request cannot distinguish existing from non-existing paths (uniform `401`).

### S2-R014
`GET /grom/v1/health` is the only unauthenticated endpoint.

### S2-R015
Failed authentication attempts MUST be counted; more than 20 failures in 60 s
MUST return `429` for the remainder of the window. The counter MUST reset on
success.

## 3. Endpoints

### S2-R016 — `GET /grom/v1/health`
Returns `200`:
```json
{"api":1,"state":"READY|STARTING|MISSING_RUNTIME|QUARANTINED|STOPPED",
 "runtime":"node|missing","pid":1234,"uptimeMs":12345,"version":"0.1.0"}
```
MUST contain no token, no audit content, no device identifiers.

### S2-R017 — `GET /grom/v1/status`
Authenticated. Extends health with supervisor counters: `restarts`,
`crashes`, `lastCrashMs`, `quarantineRemaining`, `port`, `enabled`.

### S2-R018 — `GET /grom/v1/audit?limit=N`
Authenticated. Returns the newest `N` entries (default 20, max 200) in
chronological order, each with `seq`, `ts`, `actor`, `action`, `detail`,
`prev`, `hash`.

### S2-R019 — `GET /grom/v1/audit/verify`
Authenticated. Replays the chain and returns
`{"ok":true,"entries":N,"head":"<hash>"}` or `{"ok":false,"brokenAt":seq}` with
`200` in both cases.

### S2-R020 — `POST /grom/v1/token/rotate`
Authenticated. Returns `{"token":"<new>"}` exactly once. The old token is
invalid from the moment the response is written. Rotation is audited, with the
new token never written to the audit log.

### S2-R021 — `POST /grom/v1/agent/start`
Authenticated. Starts the supervised agent if stopped. `409` if already
running, `409 QUARANTINED` if quarantined.

### S2-R022 — `POST /grom/v1/agent/stop`
Authenticated. Stops the agent without quarantining it.

### S2-R023 — `POST /grom/v1/agent/restart`
Authenticated. Equivalent to stop+start; counts as a deliberate restart, not a
crash.

### S2-R024 — `POST /grom/v1/agent/reset`
Authenticated. Clears quarantine and failed-attempt counters. Does not
auto-start the agent.

### S2-R025 — `POST /grom/v1/agent/exec`
Authenticated. Body `{"op":"<name>","args":{...}}`. Only explicitly whitelisted
op names are accepted; anything else is `400 UNKNOWN_OP`.

### S2-R026
When the runtime is missing, `S2-R021`–`S2-R025` MUST return `503` with code
`MISSING_RUNTIME`, and the error message MUST name the missing file.

### S2-R027
When the agent is quarantined, start/exec MUST return `409` with code
`QUARANTINED` and include the crash count.

### S2-R028
Unknown paths return `404 NOT_FOUND` once authenticated. A correct path with a
wrong method returns `405 METHOD_NOT_ALLOWED` and an `Allow` header.

## 4. Encoding and errors

### S2-R029
Request and response bodies are UTF-8 JSON with `Content-Type: application/json`.
A request with a body and a non-JSON content type is `415`.

### S2-R030
Errors use:
```json
{"error":{"code":"UNAUTHORIZED","message":"..."}}
```
`code` is a stable machine token; `message` is human text and MAY change.

### S2-R031
The server MUST NOT include a stack trace, file path, or exception class name in
a response body. Details go to the local log.

### S2-R032
Every response carries `X-Grom-Api: 1`.

## 5. Audit integration

### S2-R033
Every authenticated request that is not a `GET` appends one audit entry with
`action` equal to the logical operation (e.g. `agent.start`).

### S2-R034
An audit append failure MUST fail the request with `500` rather than mutating
state without a record.

### S2-R035
`GET` requests are audited only when they read audit content
(`/audit`, `/audit/verify`), to avoid the log auditing itself into noise.

## 6. Shutdown

### S2-R036
Stopping the server MUST close the listener first, then in-flight connections,
then the agent.

### S2-R037
A shutdown MUST append a `server.stop` audit entry if the audit store is
writable.

### S2-R038
No request handler may block the accept loop; a slow client must not stall the
server beyond the idle timeout.

## 7. Two servers on one device (D85)

### S2-R039
The loopback control port MUST be choosable by the host that starts the server,
and the default MUST stay 47001. When the port is held by another process, the
bind MUST fail with the stable code `PORT_IN_USE` carried in
`ApiException`/`lastError`; the server MUST NOT fall back to another port, bind a
non-loopback address or continue without a listener. The runtime MUST NOT start
its tick loop, the public data plane or the agent after a refused bind.
