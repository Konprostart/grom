# Telegram notifications (D65–D67)

Grom can send a short message to the operator's own Telegram chat when something
changes: the server started or stopped, an external server (SSH bridge)
connected, failed or hit a host-key mismatch, an app registered, or the agent
crashed and was quarantined.

It is **opt-in**, **operator-configured**, **bounded** and **one-way**. This
document says exactly what is sent, what is not, and what cannot work.

---

## 1. Why this exists and what it is not

The dashboard is only visible when the operator is looking at the phone. A
notification is the difference between noticing a crash in a minute and
noticing it a week later.

What it is **not**:

- not telemetry: nothing is sent anywhere unless the operator entered a bot
  token and a chat ID and switched the feature on;
- not a monitoring service: there is no server-side account, no dashboard, no
  history beyond the last delivery result on the device;
- not a dead-man's switch: see §6 for the honest limits.

---

## 2. Setting it up

1. In Telegram, talk to **@BotFather**, send `/newbot`, choose a name. BotFather
   answers with a token shaped like `123456789:AAH…`.
2. Send your new bot any message (a bot cannot open a chat with you).
3. Find the chat ID: in the app, open **Telegram alerts → Chat ID**. Enter the
   number you are told to use. For a private chat this is your numeric user id
   (e.g. `123456789`); for a group it is a negative number (e.g. `-1001234567890`).
4. Open **Bot token**, paste the token, **Save**.
5. Switch **Alerts** on, then press **Send test message**.

The phone must have a working internet connection. Grom does not proxy the
message through the SSH bridge: the bridge is a *reverse* tunnel (the VPS
connects to the phone), so it cannot carry the phone's own outbound traffic.

---

## 3. What a message contains

A fixed English sentence plus, where it matters, one of two things:

- an **event code** from a fixed list (e.g. `HOST_KEY_MISMATCH`), and
- a **project id** for project events.

Examples:

```
Grom: server started.
Grom: public API refused (CREDENTIAL_REQUIRED).
Grom: external server connection failed (SSH_UNAVAILABLE).
Grom: app "my-wallet" registered.
Grom: agent quarantined after repeated crashes.
```

Nothing else. The message **never** contains:

| never sent | why |
|---|---|
| the bot token or any part of it | it is a credential for the bot |
| the chat ID | no reason to echo an identifier back |
| SSH host, port, user name, password, key, fingerprint | the address of the operator's infrastructure |
| the Lightning URL, API key, balance or any invoice | money and credentials are out of scope (D43) |
| `npub`, relays, invite code, device identifiers | they are device identity, kept off the network |
| free-form text from an exception or a log line | it can contain any of the above |

The event text is chosen by `NotifyRules` from a fixed table; audit `detail`
strings are parsed only for a stable code and a validated project id, never
copied.

Since the message goes directly from the phone to `api.telegram.org`,
**Telegram sees the phone's IP address** and the time of the message. An
operator who does not accept that should leave the feature off; there is no
relay option in this version.

---

## 4. Delivery limits (D66)

| limit | value | why |
|---|---|---|
| attempts per message | **2** (immediately, then once after 4 s) | one Wi-Fi hiccup should not lose the message, but there is no retry loop |
| timeout per attempt | 8 s | a dead network must not pin a thread |
| queue | 16 messages, oldest dropped and counted | a burst must not grow memory |
| coalescing | same event code within 10 s → one message | a crash loop must not send a hundred messages |
| rate | at most 20 messages per minute | a friendly bound for the operator's chat |
| scheduler | **none** | there is no periodic tick, no `Timer`, no `scheduleAtFixedRate` |
| disk queue | **none** | a failed delivery is recorded as failed, not replayed later |

A `Send test message` call is synchronous and bypasses the queue and the rate
limit, because the operator asked for it in that moment. It still requires a
real `{"ok":true}` answer from Telegram; a rejection is reported as a failure
with a code.

The last result is kept in memory and reported as
`notifications.lastDelivery = {ok, atMs}`. It is a measurement, not a promise:
a green lamp means "the last message was accepted by Telegram", not "the
channel is healthy".

---

## 5. Secrets at rest (D67)

The bot token is sealed with `AndroidKeystoreSecretBox` (AES-256-GCM under a
non-exportable Keystore key) before it is written to
`files/grom/notifications.json`. The plaintext exists only in memory, for the
duration of one request.

The file is owner-only, written to a temporary file and renamed, and capped at
64 KiB. It contains `enabled`, the numeric `chatId` and `tokenSealed`; the chat
ID is not a secret, but it is device-identifying, so it never appears in
`/v1/status`, in the *Copy config* export or in the audit log.

The token is read from the box at send time and never cached. Error messages
carry a code (`TELEGRAM_HTTP_403`, `TELEGRAM_TRANSPORT`, …), never the URL —
the URL contains the token.

---

## 6. What this cannot do

**If the phone is off, offline, or the process was killed by a vendor task
killer, no message is sent.** Grom is the sender; when it is gone, there is
nobody to send. This is not a limitation to be worked around with a timer — a
real "the server is down" alert needs an *external* observer (for example a
cron job on the VPS that polls the data plane and alerts when it stops
answering). That observer is not part of Grom.

A project that simply stops sending heartbeats is **not** an event: Grom has no
"absence of heartbeat" alarm, so it will not claim one. The dashboard shows the
project as `stale`.

Server stop is sent on a best-effort basis: `GromService.onDestroy` stops the
runtime and then waits up to 2.5 s for the queue to flush. If Android kills the
process first, that message is lost.

---

## 7. Verification

| check | what it proves |
|---|---|
| `NotifySettingsTest` | token and chat-ID formats, `NOTIFY_NOT_CONFIGURED`, `clearToken` |
| `NotifySettingsStoreTest` | no plaintext token on disk, atomic write, size cap, unreadable file |
| `NotifyRulesTest` | every mapped event, unknown actions ignored, `detail` never copied, project id validated |
| `TelegramNotifierTest` | disabled → no call, `ok:false` → failure, 5xx → code, coalescing, queue cap, rate limit, two attempts, no token in the error |
| `NotifyModelTest` | a lamp is green only after a measured `ok=true` |
| `ConfigExportTest` | the export carries the state, never the chat ID or the token |
| `tools/test-policy` | the Telegram host appears in exactly one file; no scheduler in the notify package; the store seals the token |
| `SuperServerIntegrationTest` | the authenticated `/v1/status` carries the block; `/v1/health` does not |

Nothing here has been exercised against the real Bot API from a phone: the
tests use a fake transport. The first real send is the operator's own
**Send test message**.
