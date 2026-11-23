# Provisioning several phones from one computer

`tools/grom-provision` is an operator tool (D79). It runs on the machine with
the USB cables, drives `adb`, and gets a phone from "just wiped" to "running the
Grom server with its own forward port" without tapping through the app by hand.

It lives outside the Android tree on purpose:

* it is **not** part of the APK, and the app does not depend on it;
* it adds nothing to the security model — it uses the same package, the same
  APK and the same public data plane;
* it needs only a **JDK 17** and **`adb`**, so it runs as a single Java file
  with no build step.

It is the first half of the plan agreed after reviewing similar projects
(phoneborg's provisioner was the reference for the approach). The second half —
the **Doctor** that explains why Android keeps stopping a server — is now in the
app (`docs/DOCTOR.md`) and in this tool as a fifth command, `doctor`.

## What it actually does

Five commands, in increasing order of consequence.

| command | effect |
|---|---|
| `list` (default) | read-only: what is connected, and what each phone reports |
| `plan` | read-only: the exact steps `apply` would take, and what is blocked |
| `apply` | install the APK, ask for a battery exemption, assign a forward port |
| `emit` | turn the recorded state into a `hub.json` for `tools/grom-hub` |
| `doctor` | read-only: the settings Android does not show an app (`--fix` changes two) |

Everything `list` and `plan` do is measurement: `adb devices`, `getprop`,
`dumpsys package`, `dumpsys deviceidle whitelist`. Neither writes to a phone or
to the state file, and `plan` is what you run when you want to see the decision
before it happens.

### `list`

```
$ tools/grom-provision list
serial              model                 android  grom                 battery   port   state
R58M12345           Pixel 7               14       0.12.0-server29      exempt    41001  ready
R58M22222           Pixel 4a              13       -                    optimised -      not-installed
9A0X1234            -                     -        -                    -         -      unauthorized
```

Every column is read from the device, never inferred from configuration:

* **grom** comes from `dumpsys package app.grom.server` (`versionName`), so a
  blank phone shows `-` instead of an optimistic guess;
* **battery** comes from the deviceidle whitelist, not from "we asked once";
* **port** comes from the provisioning state written by a previous `apply`;
* **state** distinguishes `ready`, `not-installed`, `unauthorized`, `offline`,
  `sdk-too-low` and `no-arm64`, so the fix is obvious from the row.

### `plan` and `apply`

```
$ tools/grom-provision plan --apk grom-server29.apk --flavour server29
R58M12345  pixel-7  [not-installed]
    + install grom-server29.apk
    + exempt from battery optimisation
    + forward port 41001
9A0X1234  phone  [unauthorized]
    x blocked: unauthorized: accept the USB debugging prompt on the phone
```

`apply` performs exactly those steps, then **reads the result back**:

* after `adb install`, it asks the device for the installed package again. An
  install that printed nothing useful, or that left nothing behind, is
  `VERIFY_FAILED` — never a success;
* `--flavour` is checked against the `versionName` the *device* reports. Both
  roles share one `applicationId` (S1-R007), so installing the wrong flavour
  silently replaces the other one; this is the guard for that;
* the battery exemption is requested and then re-read. Several vendors ignore
  the request until the user confirms it on screen, so a request that did not
  take effect is reported as a warning ("unlock the phone and run again")
  rather than claimed as done.

## Requirements on the phone

1. **USB debugging** on, and the RSA prompt accepted (`adb devices` shows
   `device`, not `unauthorized`).
2. **Android 8.0+ (API 26)** for the `peripheral26` role, **Android 10+
   (API 29)** for `server29`. The tool refuses a phone below the minimum *when
   you name the flavour*, and explains which minimum applied.
3. **arm64-v8a for the server role.** `server29` starts the packaged Node
   agent, which is arm64-only, so a 32-bit phone is refused with `no-arm64`
   instead of failing later at runtime. `peripheral26` never starts that agent,
   so the same phone is a valid peripheral — pass `--flavour peripheral26` for
   it, and the check follows the role.

## Quick start

```bash
# 1. See what is connected.
tools/grom-provision list

# 2. Look before you leap.
tools/grom-provision plan --apk grom-server29.apk --flavour server29

# 3. Do it. Re-running is safe: it skips phones already on this APK.
tools/grom-provision apply --apk grom-server29.apk --flavour server29

# 4. Turn the recorded ports into a hub config.
tools/grom-provision emit --hub hub.json
```

`apply` is idempotent by APK content, not by version string: it records the
SHA-256 of the APK it installed for each phone, so re-running with the same file
changes nothing, and re-running with a rebuilt file reinstalls. `--force`
reinstalls regardless.

## The state file and the forward ports

`provision.json` (override with `--state`) records, per serial:

```json
{"version": 1, "nextPort": 41003,
 "devices": [{"serial": "R58M12345", "label": "pixel-7", "forwardPort": 41001,
              "apkSha256": "…", "versionName": "0.12.0-server29",
              "lastAppliedMs": 1700000000000}]}
```

It is written atomically (temp file + rename), owner-only, and it holds no
secret: an APK hash, a version name, a label and a port.

Ports are assigned once and then never moved, so a hub config stays valid across
runs. The block is 41001–41099 (D78); when it runs out the tool refuses rather
than reusing a port that another phone's tunnel is holding. Labels are derived
from the model (`Pixel 7` → `pixel-7`) and de-duplicated in serial order, so the
same fleet always produces the same names.

## `emit`

```bash
tools/grom-provision emit --hub hub.json    # or omit --hub to print it
```

It writes the `hub.json` that `tools/grom-hub` reads: one device per
provisioned phone, each pointing at its own loopback port.

```json
{
  "version": 1,
  "timeoutMs": 8000,
  "pollSeconds": 30,
  "listen": "127.0.0.1:47100",
  "rosterFile": "roster.json",
  "devices": [
    {"label": "pixel-7", "url": "http://127.0.0.1:41001"}
  ]
}
```

There is deliberately **no `fleet.json` emitter**. A fleet config needs a
reachable address per phone, which is a LAN fact the computer does not know and
should not guess. The hub path needs only the loopback ports, which the tool
does know. Write `fleet.json` by hand if you want the direct LAN view
(`docs/FLEET.md`).

## `doctor`

The questions an ordinary app cannot ask (D81). It reads, for each phone:

| question | command |
|---|---|
| is Grom whitelisted from Doze? | `dumpsys deviceidle whitelist` |
| is background work allowed? | `cmd appops get app.grom.server RUN_ANY_IN_BACKGROUND` |
| what is the phantom-process limit? | `device_config get activity_manager max_phantom_processes` |
| is phantom monitoring on? | `settings get global settings_enable_monitor_phantom_procs` |
| does Wi-Fi sleep with the screen off? | `settings get global wifi_sleep_policy` |

```
$ tools/grom-provision doctor
pixel-7  (R58M11111)
  doze whitelist           yes
  appops background        app.grom.server: No operations.
  max phantom processes    null
  phantom monitor          null
  wifi sleep policy        2
  findings: none

pixel-4a  (R58M22222)
  doze whitelist           no
  appops background        RUN_ANY_IN_BACKGROUND: deny
  wifi sleep policy        0
  findings: DOCTOR_NOT_WHITELISTED, DOCTOR_APPOPS_DENIED, DOCTOR_WIFI_SLEEP_NOT_ALWAYS
```

Exit code `1` means "at least one phone needs attention", which makes it usable
from a cron job; `--json` is the same data for a script.

**A command that answers nothing is printed as `unknown`, never as `0`.** That is
the same rule the in-app screen follows: `no` means "measured, and it is not
exempt", while `unknown` means "this ROM did not answer", and the two are not
interchangeable.

### `--fix`

The only mutating path in this command, and it is limited to two changes:

| what | when |
|---|---|
| `dumpsys deviceidle whitelist +app.grom.server` | the phone answered `no` |
| `settings put global wifi_sleep_policy 2` | **only** on API 28 and older |

`wifi_sleep_policy` is a legacy setting: from Android 10 the platform manages
Wi-Fi power itself and the value is ignored, so the tool does not pretend to fix
something there. It never changes an app-op (that is the operator's decision, and
`cmd appops set` can grant far more than Wi-Fi), never removes a package, and
never restarts a phone.

Both changes are **re-read** afterwards, so the report says what the phone now
reports rather than what the tool asked for. If a vendor silently ignores the
whitelist request, the second read still says `no` and the exit code stays `1` —
the tool does not get to declare success on your behalf. The table therefore
prints `attempted: DOCTOR_FIX_…`, never `fixed:`; the re-read lines above it are
the only claim of state.

The suite enforces this structurally: every invocation the tool issues must match
a fixed allow-list (`ALLOWED_COMMAND_PREFIXES` in the source), so a command
nobody anticipated fails a test instead of passing quietly.

## What it refuses to do

This is a setup tool, not fleet management, and it is intentionally unable to
break a phone:

* it never removes a package, never wipes data, never reboots and never
  terminates a process — the source is scanned for those verbs by
  `tools/test-policy` #42;
* it never waits for or executes anything from the device as a shell string: it
  builds an argument list (`ProcessBuilder`), so a serial number cannot become a
  command;
* it does not start or stop the Grom server, change its settings, add SSH
  servers, pair devices or read project data. Those are app actions with
  their own confirmations;
* it does not "root", flash, unlock bootloaders or install Termux.

If `adb install` fails because the installed app was signed with another key,
the tool stops and tells you to remove it yourself. It will not do it for you:
that is the operator's decision, not a convenience.

## Exit codes

| code | meaning |
|---|---|
| `0` | everything asked for was done |
| `1` | at least one phone needs attention — read the line for that phone |
| `2` | a usage or configuration problem; no phone was changed |

`2` is reserved for problems detected before any device command (`--apk`
missing, unknown flavour, no `adb`, port outside the block, unreadable state
file), which makes it safe to run from a script.

## Relationship to the other tools

```
        grom-provision              grom-hub                 grom-fleet
        (this tool)                 (VPS roster)             (laptop view)
             │                          │                        │
   installs the APK,            reads 127.0.0.1:410NN      reads a LAN URL
   assigns the port  ────────▶  over the reverse tunnel  ──▶  directly
                                        │                        │
                                   roster.json              table with lamps
```

* `grom-provision` **prepares** a phone and decides its port;
* `grom-hub` **reads** those phones from a VPS, through the tunnel the operator
  opens in **External servers** — the path that works behind CGNAT;
* `grom-fleet` **reads** them directly on a LAN, which needs no tunnel but no
  remote network either.

The three read the same public data plane and none of them can change a phone's
configuration.

## Honest limits

* **No phone was tested by the maintainer.** The suite runs the real tool
  against a fake `adb`, and the paths were exercised against a real `adb` with
  no device attached. The first run against hardware is the operator's.
* **USB only.** It does not provision over Wi-Fi (no `adb tcpip`, no pairing
  code), because that would need the phone to be reachable first.
* **Battery exemption is not immortality.** It keeps Doze from freezing the
  process, but vendor task killers, the phantom-process limit and low memory
  can still stop it. `doctor` reports what those settings say; it cannot make a
  vendor ROM honour them, and `--fix` covers only the two changes above.
* **`doctor` is a snapshot.** It reads at the moment you run it; a phone that
  looks fine now can be throttled an hour later.
* **One cable, one phone at a time** in practice: several phones can be
  connected through a hub, and `--serial` selects one, but `adb install` is
  serialised.
* **`apply` does not configure the app.** After it finishes, `Public access`
  still has to be turned on, and an SSH target and `Devices → Trust a key` are
  still operator decisions in the UI. Automating those would mean writing app
  settings behind the UI's back.
