# Lightning — integrator guide

Grom can expose a Lightning backend as the `lightning` plugin on its public data
plane. It is an **adapter**, never a wallet: Grom holds no keys, custodies no
funds, matches no orders and decides no settlement. The operator points Grom at
a backend they run; Grom forwards a request and relays the answer
(D43).

The normative API is in [`specs/S5-super-server.md`](specs/S5-super-server.md).
This page is for someone attaching a wallet or building an exchange on top.

## 1. Architecture

```
client ──/v1/plugins/lightning/rpc──> Grom (adapter) ──https──> operator's backend
                                             │
                                             └── Android Keystore: sealed API key
```

- The backend is operator-configured LNbits, reached over **https only**.
- Keys stay in the wallet run by the operator. Grom stores only the LNbits API
  key, sealed with the Android Keystore (`apiKeySealed`; D44).
- Grom never mints an invoice, never computes a balance and never invents a
  payment state. Every value in a result was produced by the backend.
- Amounts are always integer millisatoshis (`msat`). There is no floating-point
  money anywhere in the path.

## 2. Capability matrix

Capabilities are negotiated per backend, not assumed. A backend that does not
implement one omits it, and the plugin answers `501 NOT_SUPPORTED` naming it.

| capability | wire name | LNbits adapter | unsupported result |
|---|---|---|---|
| balance | `balance` | yes (`GET /api/v1/wallet`) | — |
| create invoice | `invoice.create` | yes (`POST /api/v1/payments`, `out=false`) | — |
| payment status | `invoice.status` | yes (`GET /api/v1/payments/{hash}`) | — |
| pay | `pay` | yes (`POST /api/v1/payments`, `out=true`) | `501` when `maxFeeMsat` is supplied |
| orderbook | `orderbook` | **no** | `501 NOT_SUPPORTED`, message names `orderbook` |
| escrow / hold invoice | `escrow` | **no** | `501 NOT_SUPPORTED`, message names `escrow` |

LNbits has no hold invoices and no fee-ceiling parameter. The adapter omits
`escrow` and `orderbook` rather than emulating them.

## 3. The plugin ops

`POST /v1/plugins/lightning/rpc` with `{"op":"<name>","args":{...}}`. Success is
`200 {"ok":true,"plugin":"lightning","op":"...","result":{...}}`; failure keeps
the plugin's HTTP status and carries
`{"ok":false,"plugin":"lightning","op":"...","error":{"code","message"}}`.

| op | args | result |
|---|---|---|
| `health` | none | `{"backend":"lnbits","reachable":true,"detail":null}` |
| `status` | none | `{"configured":bool,"backend":"lnbits","capabilities":[...],"error":null}` |
| `balance` | none | `{"msat":12345}` — measured, never defaulted |
| `invoice.create` | `amountMsat` required, > 0 and ≤ 1000000000000; `memo` optional, ≤ 128 chars; `expirySeconds` optional, 60–86400 | `{"paymentHash":"<hex>","bolt11":"lnbc...","expiresAtMs":null}` |
| `invoice.status` | `paymentHash` required, 64 hex chars | `{"paymentHash":"<hex>","status":"settled\|pending","paid":bool,"preimage":"<hex>\|null","amountMsat":12345\|null}` |
| `pay` | `bolt11` required, 20–20000 chars, prefix `lnbc`/`lntb`/`lnbcrt`/`lnsb`; `maxFeeMsat` optional | `{"paymentHash":"<hex>","status":"pending","paid":false,"preimage":null,"amountMsat":null}` — submission is not settlement |
| `orderbook` | none | `501 NOT_SUPPORTED` on LNbits |
| `escrow.hold` | `paymentHash`, `amountMsat`, `memo` optional | `501 NOT_SUPPORTED` on LNbits |
| `escrow.release` / `escrow.refund` | `escrowId` (1–128 chars) | `501 NOT_SUPPORTED` on LNbits |
| anything else | — | `400 UNKNOWN_OP` |

Argument rules: amounts accept JSON numbers or decimal strings, must be finite
and integral, and must be greater than zero; fractions are rejected rather than
rounded. `paymentHash` must be 64 hexadecimal characters; `bolt11` must be a
string of the documented length. LNbits accepts whole satoshis only, so an
`amountMsat` that is not a multiple of 1000 is rejected with `400 INVALID_ARGS`
rather than truncated.

## 4. Honest-state table

| HTTP | code | when |
|---|---|---|
| `200` | — | The backend produced the value; it is reported unchanged. |
| `400` | `INVALID_ARGS` | A required argument is missing, malformed, negative, fractional or out of range, or the backend rejects the value. |
| `400` | `UNKNOWN_OP` | The op name is not in the table above. |
| `501` | `NOT_SUPPORTED` | The configured backend does not implement the required capability; the message names it (`orderbook`, `escrow`, `pay.fee_limit`). |
| `502` | `LIGHTNING_BACKEND_ERROR` | A backend error the adapter does not classify as unreachable. |
| `503` | `LIGHTNING_UNCONFIGURED` | No backend is configured. |
| `503` | `LIGHTNING_UNAVAILABLE` | The backend is configured but unreachable, returned a non-2xx, a non-object body, a body above 64 KiB, or a response missing a required field (including a wallet response with no numeric balance). |
| `500` | `INTERNAL_ERROR` | An unexpected failure in the adapter. The message is a constant; backend URLs and keys never reach the client. |

What "no balance" looks like: there is no zero, no `"pending"` and no cached
number. An unconfigured backend gives `503 LIGHTNING_UNCONFIGURED`; an
unreachable one gives `503 LIGHTNING_UNAVAILABLE`; a reachable backend whose
wallet document has no numeric `balance` gives `503 LIGHTNING_UNAVAILABLE`
("wallet balance is missing or not numeric"). The plugin reports `configured:
false` and an `error` string in `status` when the stored credential cannot be
opened.

## 5. Wiring a real LNbits wallet

1. Run LNbits behind **https** (a TLS terminator in front of it is fine). The
   base URL is a host and optional path, with no userinfo and no plain `http`.
2. Create a wallet. In LNbits' API info, create an **invoice/read** key for that
   wallet. If the wallet refuses to send payments with that key, use its admin
   key instead; it grants more than the adapter needs.
3. In the app's **Lightning** row, paste the https base URL and the key. The key
   is sealed with the Android Keystore before it is written to
   `super-server.json`; leave the field blank to keep an existing key.
4. Save. The app rebuilds the listener; `/v1/plugins/lightning` reports the
   capabilities the backend advertises.
5. Verify with `{"op":"balance","args":{}}`, then create a small invoice and
   check it with `{"op":"invoice.status","args":{"paymentHash":"<hash>"}}`.

The app's dialog always writes `kind: "lnbits"`. Writing the JSON by hand is
possible for `kind`, `baseUrl` and `timeoutMs`, but `apiKeySealed` can only be
produced with Grom's keystore key; do not put a plaintext key in the file, and
expect an unreadable sealed value to be reported as unconfigured, not guessed. A
hand-edited file is read at the next server start.

## 6. Adding another backend

LND and Core Lightning can be supported without changing the API: implement the
adapter interface and register it. The interface is
`app.grom.server.superserver.plugins.lightning.LightningBackend`:

```kotlin
interface LightningBackend {
    val name: String
    fun capabilities(): Set<LightningCapability>
    fun health(): LightningHealth
    fun balance(): LightningBalance
    fun createInvoice(amountMsat: Long, memo: String?, expirySeconds: Int?): CreatedInvoice
    fun paymentStatus(paymentHash: String): PaymentState
    fun pay(bolt11: String, maxFeeMsat: Long?): PaymentState
    fun orderbook(): OrderbookSnapshot
    fun escrowHold(paymentHash: String, amountMsat: Long, memo: String?): EscrowHandle
    fun escrowRelease(escrowId: String): EscrowHandle
    fun escrowRefund(escrowId: String): EscrowHandle
}
```

A method either returns a value the backend produced or throws
`LightningUnavailable`, `LightningUnsupported(capability)` or
`LightningBadRequest`. There is no default implementation, no zero and no
optimistic state.

The rule: **a capability the backend cannot perform must be omitted from
`capabilities()`, and the corresponding method must throw
`LightningUnsupported(capability)` — never return a plausible value.** The
plugin checks `capabilities()` first, so an omitted capability becomes `501`
without calling the method; the throw is the second line of defence.

Registration touch points:

- `LightningKind` in `SuperServerConfig.kt` — add the wire name. It is currently
  `NONE("none")` and `LNBITS("lnbits")`; `fromWire` maps anything unknown to
  `NONE`, so the new name must be added before it can be configured.
- `LightningBackendFactory.create` — construct the adapter for that kind after
  the https and credential checks.
- `SuperServerConfig.validate` — the generic https/URL/timeout checks already
  apply to any non-`NONE` kind.
- The UI Lightning dialog currently writes `lnbits` only; a new backend needs
  either a UI change or hand-edited configuration.

For LND, the capability worth adding is `escrow` via hold invoices
(`routerrpc`/`invoicesrpc`), and `pay` can enforce a fee ceiling. An `orderbook`
capability would need a market-data source the adapter author supplies; LND
itself has no order book. Those are backend semantics, not Grom features, and
must be implemented and documented by the adapter author.

## 7. What a P2P exchange needs beyond this layer

This layer can report a balance, create an invoice, check it and pay one. A
peer-to-peer Lightning exchange needs considerably more, and **Grom provides none
of it**:

- **Hold invoices / HTLC escrow** (LND) so funds are held while both sides
  settle, and released or cancelled deterministically. LNbits cannot do this,
  which is why the capability is absent (S5-R022).
- **An order book and matching engine**: price/time priority, partial fills,
  self-trade rules, cancellation, persistent state across restarts.
- **Settlement and dispute policy**: who releases the hold, what happens on a
  timeout, what evidence counts, how a fee is apportioned.
- **Market data** and a fee-ceiling policy the backend can actually enforce.

Grom has no matching engine and no custody. Do not represent a Grom deployment
as an exchange by itself. A phone behind CGNAT also has no reachable inbound
address; the peer-facing side must use an outbound tunnel (WireGuard, Tailscale,
SSH) initiated from the device, or a backend hosted somewhere reachable.

## 8. Security notes

- The backend URL must start with `https://`; a plain-http URL is refused at
  configuration time with `INSECURE_BACKEND_URL`. The adapter constructor
  repeats the check, and the transport only opens `http`/`https` schemes.
- Redirects are **not** followed by `HttpJsonClient`: a `3xx` is returned to the
  caller. Silently following `Location` could send `X-Api-Key` to another host.
- Responses are capped at 64 KiB; a larger body fails as unavailable rather than
  buffering it.
- Timeouts: 8 s read timeout by default (configurable 1–30 s), with the connect
  timeout capped at 5 s.
- The API key travels only in the `X-Api-Key` header. It never appears in a URL,
  an exception message, `/v1/status`, `/v1/plugins`, the audit log or the process
  log. Transport failures carry a constant message.
- `maxFeeMsat` is rejected (`501 NOT_SUPPORTED`, the message names
  `pay.fee_limit`) rather than ignored, because LNbits has no fee ceiling and
  silently dropping an operator's safety limit would be worse than refusing the
  payment.
- The configuration file stores the key sealed by the Android Keystore. A
  deleted or rotated keystore key makes the stored value unreadable; the backend
  becomes unconfigured, never a crash and never a plaintext fallback.

## 9. Test status

The Lightning adapter has JVM unit tests only. `LightningPluginTest` uses a fake
backend and `LnbitsBackendTest` uses an injected transport, so neither opens a
backend socket; `HttpJsonClientTest` runs against a hand-written HTTP/1.1 server
on `127.0.0.1` (a raw `ServerSocket`, because the Android unit-test classpath
does not expose `jdk.httpserver`). No test in this repository has run
against a live wallet, and no device, benchmark or CI result is claimed for the
Lightning path.
