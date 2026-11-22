# The Grom hub — one roster for phones behind any network

Phase C of the fleet plan (D76–D78). A phone already exposes its public data
plane on `127.0.0.1` and forwards it to a VPS over the reverse SSH tunnel the
operator opens in **External servers**. The hub is a small program on that VPS
which polls those forwarded loopback ports and serves one roster. Because the
phone makes the outbound connection, it works behind CGNAT, on mobile data, and
on a network that forbids incoming connections — with no LAN bind and no public
port on the phone.

The hub is **not** part of Grom:

* it is not in the APK and Grom never calls it;
* if it is down, the phones keep working and only the roster is missing;
* it holds no account, no cloud credential and no secret of any phone.

## 1. Topology

```text
        phone A                        VPS (operator)
  data plane 127.0.0.1:47002
        │  outbound SSH (D58, operator taps Connect)
        ▼
                               sshd
   127.0.0.1:41001 ──────────▶ phone A 127.0.0.1:47002
   127.0.0.1:41002 ──────────▶ phone B 127.0.0.1:47002
   127.0.0.1:41003 ──────────▶ phone C 127.0.0.1:47002
        ▲
        │  signed GET /v1/status (grom-ed25519-v1)
   grom-hub (dedicated user)
        │  GET http://127.0.0.1:47100/roster.json
        ▼
   your laptop (through an SSH session or a proxy)
```

Each phone gets **its own** forward port. `tools/test-policy` #38 and the SSH
store enforce that: two targets on the same host may not share a port. The
conventional block is **41001–41099**, one per phone.

## 2. On each phone

1. **Public access**: enable it, bind **Loopback**, auth **Key** or **Both**.
   A password-only data plane cannot be used by the hub, because the hub signs
   its requests.
2. **External servers**: add the VPS, open the tunnel, and set
   **forward port** to this phone's own port (41001, 41002, …). A unique port
   per phone is required; the second phone with the same port is refused with
   `BAD_FORWARD_PORT_CONFLICT`, and a server that rejects the forward at connect
   time reports `SSH_FORWARD_REFUSED`.
3. **Devices → Trust a key**: paste the hub public key printed by
   `identity --init`, name it (for example `home-vps-hub`), and select
   **Read status**. `Read configuration` is optional and should stay off unless
   you need the sanitised export. This is a one-way grant: the phone never dials
   the hub and cannot be reached by it for anything except those reads.

Nothing else on the phone changes. The hub adds no outbound connection to Grom
(S1-R003).

## 3. On the VPS

Create a dedicated user and the hub key:

```bash
sudo useradd --system --create-home --shell /usr/sbin/nologin gromhub
sudo -u gromhub tools/grom-hub identity --init
```

That writes `hub-identity.json` with mode `0600` and prints the key id,
fingerprint and public key. Paste the **public key** into each phone's
**Trust a key** dialog.

Copy `tools/hub/hub.example.json` to `hub.json` and edit it:

```json
{
  "version": 1,
  "timeoutMs": 8000,
  "pollSeconds": 30,
  "listen": "127.0.0.1:47100",
  "rosterFile": "roster.json",
  "devices": [
    { "label": "pixel-7",  "url": "http://127.0.0.1:41001", "nodeId": "0123456789abcdef" },
    { "label": "pixel-4a", "url": "http://127.0.0.1:41002" }
  ]
}
```

* `url` is the forwarded loopback port on the VPS, never a phone's LAN address.
* `nodeId` is optional and recommended. When present, a device that reports a
  different id (a reset or a replaced phone) is reported as `IDENTITY_CHANGED`
  and is not silently treated as the old one.
* `listen` **must** be a loopback address. The hub refuses `0.0.0.0` and a LAN
  address outright.

One-shot check, useful in cron or by hand:

```bash
sudo -u gromhub tools/grom-hub check --config hub.json
# exit 0 = every device alive, 1 = at least one not, 2 = usage/config error
```

Long-running roster:

```bash
sudo -u gromhub tools/grom-hub run --config hub.json
# grom-hub listening on http://127.0.0.1:47100/roster.json
```

`GET /roster.json` returns:

```json
{
  "version": 1,
  "generatedAtMs": 1795368464744,
  "devices": [
    { "label": "pixel-7", "nodeId": "0123456789abcdef",
      "state": "alive", "lastSeenMs": 1795368461000, "lastError": null }
  ]
}
```

There is no port, address, key or credential in the roster. `/health` is the
unauthenticated liveness check for the hub itself.

### systemd example (optional)

```ini
[Unit]
Description=Grom hub roster
After=network-online.target

[Service]
User=gromhub
WorkingDirectory=/home/gromhub/grom-hub
ExecStart=/home/gromhub/grom-hub/tools/grom-hub run --config /home/gromhub/grom-hub/hub.json
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Do not expose port 47100 in the firewall. To read the roster from your laptop,
use an SSH local forward (`ssh -L 47100:127.0.0.1:47100 vps`) or put a TLS
terminator of your own in front of it; Grom does not provide TLS (D47).

## 4. State is measured, never assumed

| value | meaning | lamp |
|---|---|---|
| `unknown` | never polled successfully | grey |
| `alive` | a successful signed status within 90 s | green |
| `stale` | a success older than 90 s | grey |
| `unreachable` | the last poll failed (`PEER_UNAUTHORIZED`, `HUB_UNREACHABLE`, …) | red |

A stored tunnel, a saved target and a trusted key are never evidence that a
phone is reachable. The hub tests this explicitly: a fresh success is the only
thing that makes a row `alive`.

## 5. Failure behaviour

| What fails | What happens |
|---|---|
| VPS is down | Phones keep working locally; tunnels drop; no roster updates. |
| Phone sleeps or is killed | Its tunnel or forward closes; the next poll is `unreachable`, never `alive`. |
| Phone is reset (new identity) | With a pinned `nodeId`, the row shows `IDENTITY_CHANGED`; without one, the new id appears. |
| Grant revoked on the phone | The hub gets `401`; the row turns red with `PEER_UNAUTHORIZED`. |
| Two phones share a forward port | The second tunnel is refused at the phone with `SSH_FORWARD_REFUSED`; it never appears alive. |
| Hub is compromised | It can read status while tunnels are up. It holds no phone secret, no write route and no control plane. |
| Hub key stolen | Revoke the grant on every phone (Devices → the row → Remove) and mint a new hub key. |

There is no "dead man's switch": if the phone is off, its row simply ages into
`stale`/`unreachable`. The hub cannot notice a phone that never comes back
except by the absence of fresh polls, and it does not pretend otherwise.

## 6. Security notes

* **The hub key file is the credential.** `0600`, owned by a dedicated user,
  not in the repository, not in a backup you do not control. There is no Android
  Keystore on a VPS.
* **The tunnel encrypts; the phone does not add TLS.** The hub-to-phone leg is
  inside SSH. The hub-to-laptop leg is your problem: loopback, an SSH forward,
  or your own TLS terminator.
* **The grant is read-only.** `status.read` allows `GET /v1/status`;
  `config.read` allows the sanitised `GET /v1/config`. Everything else
  (`/v1/events`, plugins, `rpc`, `logout`) answers `403 SCOPE_REQUIRED`.
* **The roster names devices.** It does not reveal where they are, but it does
  reveal that they exist. Keep the listener on loopback.
* **No global revoke exists.** There is no account to disable; remove the grant
  on each phone. Losing a phone means removing its row from every peer you
  paired with, and revoking the hub's grant on that phone if it had one.

## 7. What this is not

* Not a cloud account, not a directory, not a backup and not a shared disk.
* Not a control path: the hub cannot start, stop, install or change anything on
  a phone.
* Not a relay for pairing: two phones still pair directly (D71).
* Not a substitute for the phone's own availability: a phone that is off is off.

For the mutual pairing that this grant extends, see [CLUSTER.md](CLUSTER.md).
For the read-only CLI that predates the hub, see [FLEET.md](FLEET.md).
