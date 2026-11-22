# Grom fleet view

A small, read-only tool that shows the state of several Grom servers you run —
phones, a VPS, a laptop — in one table.

This is **Phase A** of the multi-device plan. It is deliberately the smallest
possible step:

* it does **not** change Grom, the engine, the app or the data plane;
* it does **not** introduce an account, a pairing flow or a shared identity;
* it only speaks endpoints that already exist and are already documented
  (`POST /v1/login`, `GET /v1/status`, `GET /v1/health`);
* during a normal run it never writes anything to a device and never listens on
  a socket (only `--self-test` starts a loopback server, for its own tests).

What it proves: your credentials, bind mode and tunnel/network setup work across
all your devices *before* any pairing or identity code is written.

What it deliberately does not do: aggregate state, store values, start tunnels,
control a device, or merge storage. Those are later phases, each with its own
decision (see `docs/GROM_IMPLEMENTATION_ROADMAP.md` and the fleet section of the
master plan).

---

## 1. Requirements

* **JDK 17** on the machine that runs the tool (it is a single-file Java
  program; there is no build step and no dependency).
* On every device: **Public access** enabled, the data plane running, and at
  least one credential configured.

Nothing is installed on the phones for this phase.

---

## 2. Preparing a device

Open the Grom app and expand **Public access**.

1. Turn the public API on and choose a **bind**:
   * `LOOPBACK` — only reachable through an SSH tunnel (`adb forward` or the
     reverse bridge); use this for a remote phone;
   * `LAN` — reachable from the same Wi-Fi network; this is the usual choice
     for a fleet on one network.
2. Choose **auth**:
   * **password** — Grom stores only a PBKDF2 hash, and the tool exchanges it
     for a short-lived session token;
   * **key** — register an Ed25519 **public** key; the tool signs each request
     and the private key never leaves the machine.
3. Note the **port** (default `47002`) and, for key auth, the **key id** the app
   shows next to the key.

### Generating a key with OpenSSL

```bash
openssl genpkey -algorithm ED25519 -out ~/.config/grom/keys/home-vps.pem
chmod 600 ~/.config/grom/keys/home-vps.pem
openssl pkey -in ~/.config/grom/keys/home-vps.pem -pubout
```

Paste the `-----BEGIN PUBLIC KEY-----` block into the app's *Add key* dialog.
The app derives the **key id** from the public key; copy it into the config, or
put the same public key under `"publicKey"` and let the tool derive it.

---

## 3. Configuration

Copy `tools/fleet/fleet.example.json` and edit it. The schema is:

```json
{
  "version": 1,
  "timeoutMs": 8000,
  "devices": [
    {
      "name": "pixel-7",
      "url": "http://192.168.1.20:47002",
      "auth": "password",
      "passwordEnv": "GROM_PIXEL7_PASSWORD"
    },
    {
      "name": "home-vps",
      "url": "http://100.64.0.5:47002",
      "auth": "key",
      "keyFile": "~/.config/grom/keys/home-vps.pem",
      "keyId": "0123456789abcdef"
    }
  ]
}
```

| field | meaning |
|---|---|
| `name` | unique label, at most 64 characters |
| `url` | `http://` or `https://`, origin only: no path, no query, no fragment, **no embedded credentials** |
| `auth` | `"password"` or `"key"` |
| `passwordEnv` | name of an environment variable holding the password |
| `passwordFile` | file holding the password (trailing newline stripped) |
| `keyFile` | PEM PKCS#8 Ed25519 private key (`BEGIN PRIVATE KEY`) |
| `seed` | alternative to `keyFile`: base64 of the raw 32-byte seed |
| `keyId` | the 16-hex id shown by the app |
| `publicKey` | alternative to `keyId`: the base64/SPKI public key; the id is derived |

Rules the tool enforces instead of trusting you:

* **a plaintext `password` field is refused** — the config is a file you might
  commit, so secrets go through an environment variable or a separate file;
* relative `keyFile` / `passwordFile` paths resolve against the **config file's
  directory**, never the shell's working directory;
* a key file readable by group or others produces a `chmod 600` warning;
* `url` with embedded `user:pass@` is refused, so credentials cannot leak into
  a shell history or a log through the URL.

---

## 4. Running

```bash
tools/grom-fleet --config fleet.json
tools/grom-fleet --config fleet.json --only pixel,home-vps
tools/grom-fleet --config fleet.json --json | jq .
tools/grom-fleet --config fleet.json --watch 30
```

```
   DEVICE      STATE        BIND  ADDRESS          UPTIME  BRIDGE       PROJECTS  ALERTS         NOTE
   ----------  -----------  ----  ---------------  ------  -----------  --------  -------------  -----
●  pixel-ok    running      LAN   127.0.0.1:36903  1h 02m  1 connected  1 alive   ok 2m 38s ago
●  pixel-bad   refused      -     -                -       -            -         -              credentials refused (INVALID_CREDENTIALS)
●  pixel-gone  unreachable  -     -                -       -            -         -              connection refused
```

The lamp means exactly one thing:

| lamp | state | meaning |
|---|---|---|
| green `●` | `running` | the device answered **and** reported `running: true` |
| red `●` | `stopped` | the device answered and reported the data plane is not running |
| red `●` | `refused` | answered, but rejected the credential |
| red `●` | `unreachable` / `timeout` | no answer |
| grey `●` | `disabled` | the operator turned the public API off; a choice, not a fault |

`BRIDGE` is read from `bridge.tunnels`, `PROJECTS` counts `state == "alive"`
(90-second heartbeat window), and `ALERTS` is the **last measured delivery**
from the Telegram notifier. A configured-but-untested feature never shows green.

### Exit codes

| code | meaning |
|---|---|
| `0` | every selected device answered and reported a running server |
| `1` | at least one did not |
| `2` | usage or configuration error |

That makes it usable from cron or a health check:

```bash
tools/grom-fleet --config ~/.config/grom/fleet.json --json --only pixel-7 > /dev/null \
  || echo "pixel-7 is not serving"
```

---

## 5. Security notes

* **Read-only.** The tool has no code path that sends anything but a login and
  two GETs. There is no write endpoint in Grom's public plane to call.
* **Credentials are never printed.** The JSON report contains device names,
  URLs, states, error notes and the sanitised status document; it contains no
  password, no token and no key material. (There is a self-test that greps the
  output for the password used.)
* **The session token lives in memory only** for the duration of one run.
* **Cleartext on LAN.** Grom has no TLS (D47). Over plain HTTP on a local
  network the request body — including a password at login — is visible to
  anyone who can capture the network. Use:
  * `auth: key` (the password is never sent), and/or
  * a trusted LAN, an SSH tunnel (`adb forward`, or the reverse bridge), or a
    VPN/mesh for anything that leaves the house.
* **The private key never leaves the machine.** For key auth the tool signs
  locally; the device holds only the public key. A password, by contrast, is
  sent to the device at login — over the transport you chose, which is why the
  cleartext warning above matters.
* **Unreachable devices are red, not green.** An unanswered request is never
  reported as "fine"; the tool does not cache the last good answer.

---

## 6. Tests

```bash
tools/test-fleet
```

The self-test starts a real HTTP server on loopback and speaks the same two
endpoints a phone serves. It checks, among other things, that the canonical
signature string matches the vector pinned in the engine's
`RequestSignerTest`, that a wrong password is reported as `refused` rather than
`ok`, and that a PEM key file signs a request a strict verifier accepts.

The gate is part of `tools/run-gates` and CI, so the protocol cannot drift
silently.

---

## 7. What comes next

Phase A answers "does my network and credential setup work?".

**Phase B is now implemented in the app** (D70–D75): a per-device Ed25519
identity, mutual pairing with fingerprints, scoped access and the measured
Devices section. The CLI stays useful because it needs nothing installed on a
phone and works from a laptop. See [`CLUSTER.md`](CLUSTER.md) for the in-app flow
and the security model.

Setting a phone up (APK, battery exemption, its forward port) is done from the
computer with [`PROVISION.md`](PROVISION.md)'s `tools/grom-provision`; its
`emit --hub` writes the config that `grom-hub` reads below.

**Phase C is now implemented as `tools/grom-hub`** (D76–D78): it reaches every
phone through the reverse tunnel that already exists, so it works behind CGNAT,
and it serves one loopback roster. A phone is admitted with a one-way scoped
grant (`Devices → Trust a key`), each phone uses its own forward port, and the
roster carries no address or key. See [`HUB.md`](HUB.md).

Remaining phases each need a written decision first:

* **Phase D — storage catalogue and vault backup** — no merged disk; a
  catalogue plus one device with a real disk.
* **Phase E — Linux/PC node** — a worker, never a control plane.

None of those are started by this tool.
