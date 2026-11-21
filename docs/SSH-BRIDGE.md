# The SSH bridge (D58)

Grom can open a **reverse SSH tunnel** to a machine the operator controls, so a
phone behind CGNAT can still serve its public data plane to a VPS. Grom is the
SSH *client*. It asks for exactly one thing and nothing else.

```
VPS (public)                          Phone (behind CGNAT)
────────────                          ────────────────────
127.0.0.1:47002  ──tunneled TCP──►   127.0.0.1:47002  (Grom data plane)
      ▲                                     ▲
      │ tcpip-forward                       │ reverse SSH session
      └──────────── SSH server ◄────────────┘  (initiated by the phone)
```

The tunnel is initiated from the device. No inbound port forward, no CGNAT
workaround and no third-party tunnel service is involved.

---

## 1. What the session may do

| Operation | Allowed |
|---|---|
| `tcpip-forward` (server listens, forwards to the phone) | yes, one port |
| Shell / `exec` / subsystem | **never** |
| Agent forwarding, X11 forwarding | **never** |
| Local or dynamic forwarding (`-L`, `-D`) | **never** |
| `direct-tcpip` channels | **never** |

This is enforced twice:

1. Grom never creates a shell, exec or subsystem channel, and it calls
   `startRemotePortForwarding` exactly once per connection.
2. The client installs `SshForwardingPolicy`, which accepts a `forwarded-tcpip`
   channel only when the destination is loopback on the local data-plane port.
   An accept-all filter would let a compromised server reach any port on the
   phone; this filter makes that impossible.

`tools/test-policy` check 29 fails if a shell/exec/subsystem call appears, and
`SshForwardingPolicyTest` proves the destination rule.

---

## 2. Host keys: pinned on first use, never "accept anyway"

1. A target with no pin is **refused** at connect. The attempt still learns the
   server key, and the UI shows `SHA256:…`.
2. The operator confirms; the pin is stored and the connection is retried.
3. Every later connection must present the same key. A different key is
   `HOST_KEY_MISMATCH` and the connection does not proceed.
4. A legitimate server key change is handled only by *Forget host key*, an
   explicit operator action, followed by a fresh confirmation.

A caller-supplied fingerprint can never override an existing pin.

Fingerprints use the OpenSSH `SHA256:…` form, the same string as
`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`.

---

## 3. Credentials

| Mode | On disk | On the wire |
|---|---|---|
| `KEY` (preferred) | private key text sealed by `AndroidKeystoreSecretBox` | public-key auth |
| `PASSWORD` | password sealed by `AndroidKeystoreSecretBox` | password auth |

* *Generate Ed25519 key* creates a pair with the CC0 EdDSA provider, stores the
  private half sealed and shows the `ssh-ed25519 AAAA…` line to copy into
  `~/.ssh/authorized_keys` on the VPS.
* *Paste existing private key* accepts OpenSSH/PEM text, validates that it can
  be parsed, and seals it.
* The plaintext never appears in `ssh-targets.json`, in status, in the config
  export, in an audit record or in logcat.
* Changing the target's host or port **drops the pin**: a different machine must
  not inherit the old trust.

---

## 4. Limits

| Limit | Value |
|---|---|
| Saved targets | none (bounded only by the 512 KiB store, D64) |
| Simultaneously active tunnels | 2 |
| Connect / auth timeout | 15 s each |
| Remote bind address | `127.0.0.1` on the VPS |
| Forward port | per target, default 47002 |

Saved servers are not capped: the count limit was removed because adding a
server costs no phone resource, and the store refuses an over-size write with
`SSH_STORE_FULL` instead of silently dropping records. Live tunnels are capped
at two, and the screen says so as soon as a third server is saved (the extra
servers stay idle).

The tunnel refuses to start when the public data plane is not running
(`DATA_PLANE_NOT_RUNNING`): pointing a public port at a closed one would be a
lie. It also refuses when the target is disabled, when no credential is stored,
or when the stored key cannot be parsed.

---

## 5. Setting it up

On the VPS:

```bash
# one-time, if you generate the key in the app
echo 'ssh-ed25519 AAAA… grom@android' >> ~/.ssh/authorized_keys

# the forward binds loopback on the VPS, so a reverse proxy there can reach it
```

In Grom: **External servers → Add server**, fill label/host/port/user, generate
or paste a key, confirm the host key, **Connect**.

On the VPS, verify:

```bash
curl -s http://127.0.0.1:47002/v1/health
```

That is the unauthenticated health endpoint. Everything else on the data plane
still requires the password or the Ed25519 API key configured in **Public
access**; the tunnel does not bypass authentication, it only carries bytes.

### TLS

Grom speaks plain HTTP. If the port is reachable from anything other than
loopback on the VPS, put Caddy/nginx in front of it for TLS, or keep the tunnel
loopback-only and reach it through the SSH session itself.

---

## 6. Failure codes

| Code | Meaning |
|---|---|
| `TARGET_NOT_FOUND` | id is not in the store |
| `TARGET_DISABLED` | operator disabled the target |
| `KEY_MISSING` / `PASSWORD_MISSING` | no usable credential |
| `KEY_INVALID` | stored private key cannot be parsed |
| `SSH_UNAVAILABLE` | this build has no Ed25519 provider |
| `DATA_PLANE_NOT_RUNNING` | nothing to forward to |
| `CONNECTING` | an attempt is already running |
| `ALREADY_CONNECTED` | tunnel is up |
| `TOO_MANY_TUNNELS` | two tunnels are already active |
| `HOST_KEY_UNVERIFIED` | first use; confirm the fingerprint |
| `HOST_KEY_MISMATCH` | pinned key changed; connection refused |
| `SSH_TIMEOUT` | connect or auth timed out |
| `SSH_UNAVAILABLE` | connection refused / DNS / network |
| `SSH_FAILED` | anything else; the message is never the driver's |

After a tunnel drops unexpectedly the state is `failed` with
`SSH_SESSION_CLOSED`; a deliberate disconnect is `disconnected`, not an error.

---

## 7. Licensing

`sshd-core` (Apache-2.0), `net.i2p.crypto:eddsa` (CC0-1.0) and SLF4J (MIT) are
listed in `third_party/THIRD_PARTY.md` and checked by
`tools/check-third-party`. Because Apache MINA SSHD tests one class against
`javax.management.ReflectionException`, which Android does not ship, the engine
provides a minimal stand-in and narrows lint's `InvalidPackage` suppression to
that exact reference (`engine/lint.xml`). Without it, an exception-unwrapping
path would throw `NoClassDefFoundError` on a device.

## 8. Notifications

If the operator has enabled alerts (`NOTIFICATIONS.md`, D65), the bridge emits a
message on these transitions only:

| audit action | message |
|---|---|
| `ssh.connect` | `Grom: external server connected.` |
| `ssh.closed` (unexpected) | `Grom: external server disconnected.` |
| `ssh.failed` | `Grom: external server connection failed (CODE).` |
| `ssh.hostkey.mismatch` | `Grom: external server key changed; refused.` |

`ssh.disconnect` (a deliberate tap), `ssh.hostkey.learned` and
`ssh.hostkey.unverified` do **not** notify: the first is the operator's own
action, and the other two are part of the first-use handshake the operator is
looking at right now.

The message carries the stable code (`SSH_UNAVAILABLE`, `SSH_TIMEOUT`,
`HOST_KEY_MISMATCH`, `SSH_SESSION_CLOSED`) and never the host, the port, the user
name or the fingerprint — the audit `detail` contains the target id and the
host, and it is precisely what must not leave the device.
