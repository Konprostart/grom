# Doctor — why Android may be stopping the server

Every phone that runs a server gets stopped eventually. The interesting question
is not "is it still running" (the dashboard answers that in one second) but *why
it stopped*, and whether anything can be done about it. The Doctor answers that
with measurements, and it is careful about the difference between what it knows
and what it is guessing.

Open it from the menu: **Doctor**, just below *Runtime & supervision*.

A host application that embeds the engine does not get this screen (D55 — the
library draws no UI), but it gets the same measurements through
`GromEmbed.health`, which projects them into a state, a headline code and at most
four actions. The codes are the ones in `docs/TWO-IN-ONE.md` §9, so a host cannot
paint a green lamp the Doctor would not paint.

## The rule the whole screen follows

Every row is exactly one of these, and the three are never blended:

| what you see | what it means |
|---|---|
| **green** | Android answered, and the answer is the healthy one |
| **red** | Android answered, and the answer explains why the server may stop |
| **grey with a sentence** | Android did not answer — the row says which question it could not ask |
| **grey with a word** ("notice") | a measured thing that is neither healthy nor a fault |

Two consequences that are enforced by tests rather than by good intentions:

* `enabled = true`, a saved SSH server, a paired device or an installed runtime
  can **never** make a row green. Configuration is not evidence (D80).
* The headline can only say *"All measured checks pass"* when **every** row is
  green. If one row is unknown, the headline says *"Partly unknown"* — a green
  header over an unmeasured line would be exactly the lie this screen exists to
  prevent.

## What it measures

### Checks (these have lamps)

| row | green when | red when |
|---|---|---|
| **Server process** | the agent is `READY` | stopped, quarantined, no runtime — or a previous run ended without a clean stop |
| **Last exit** | the platform recorded a clean end | `LOW_MEMORY`, `ANR`, `SIGNALED`, `CRASH`, `FREEZER`, excessive resource use |
| **Battery optimisation** | Grom is exempt | Android may still Put it to sleep |
| **Background restriction** | not restricted | background work is restricted |
| **App standby bucket** | exempt, active or working set | rare, restricted or never |
| **Notifications** | the running notification is visible | hidden — a foreground service nobody can see is easier to kill |
| **Agent stability** | no crashes this run | one crash is a notice, three is a fault |
| **Public API** | measured listening | switched on but not listening |
| **SSH bridge** | at least one tunnel is up | a server is saved and none is up |
| **Free space** | 500 MB or more | below 500 MB — the runtime has nowhere to grow |

### Right now (these have no lamps, on purpose)

Doze state at this instant, battery saver, thermal status, battery level and
charging, network transport and whether it is metered, free space, memory
pressure, Android API level, and how many unexpected stops have been detected.

These are context. A hot phone or a 3 % battery is not a diagnosis, and giving
them a red lamp would train the operator to ignore red lamps.

## What it cannot see, and says so

Android does not let an ordinary app read:

* the device's whole Doze whitelist (only whether *this* app is on it);
* `device_config max_phantom_processes` or `settings_enable_monitor_phantom_procs`;
* the app-ops applied to this package;
* any vendor "auto-start" or "protected apps" list — those have no public API and
  no public Intent, so no screen can be opened for them programmatically.

The Doctor shows the exact commands instead, with a **Copy adb commands** button.
It never runs them: a shell in the app would be a privilege-escalation surface in
a project whose whole promise is that it has none (D81). For the same questions
from a computer, `tools/grom-provision doctor` reads them and can fix two of them
(`docs/PROVISION.md`).

## Actions

| button | what it does |
|---|---|
| **Measure again** | reads everything again, now |
| **Copy diagnostics** | copies the sanitised JSON report — no token, no chat id, no SSH host, no address |
| **Ask to stop optimising** | opens Android's own exemption dialog for Grom |
| **Battery settings** | opens the optimiser list (works on every version) |
| **Notification settings** | opens the channel page for Grom |
| **App settings** | opens Grom's page in Settings |

The exemption request is the **only** system change this screen asks for, it is
Android's dialog rather than a silent write, and the confirmation explains what
it is about to do. If a ROM has no settings page for one of the buttons, the
screen says so instead of crashing.

## Where the numbers come from

| fact | source |
|---|---|
| exemption, Doze now, battery saver, thermal | `PowerManager` |
| background restriction, low-RAM device, memory | `ActivityManager` |
| standby bucket | `UsageStatsManager` |
| notifications visible | `NotificationManager` |
| battery level and charging | sticky `ACTION_BATTERY_CHANGED` |
| free space | `StatFs(filesDir)` |
| network and metered | `ConnectivityManager` |
| why the last process ended | `ApplicationExitInfo` (**API 30+ only**) |
| previous run ended cleanly | the run marker (`doctor-run.json`) |
| crashes, restarts, tunnel state | the engine's own status |

On Android 10 (API 29) there is no `ApplicationExitInfo`, so the *Last exit* row
is grey and says that this version does not record exit reasons. It does not
guess "Doze".

## The run marker

The one thing Grom remembers. `GromService.onCreate` writes
`{sessionStartedAtMs, cleanStop=false}` and `onDestroy` writes `cleanStop=true`.
If the next start reads `false`, the previous process ended without `onDestroy` —
that is a fact. Its *cause* is only reported when the platform names it (D83).

`lastUnexpectedStopMs` is the **detection** time, not a reconstructed death time:
the marker cannot know the exact moment, and inventing one would be a guess
wearing a measurement's clothes.

The file is 4 KiB-capped, owner-only, written atomically, and a corrupt file
means "no knowledge" — it is never counted as a crash and never deleted.

## What changes in status

The authenticated `/v1/status` carries exactly three Doctor fields (D84):

```json
"doctor": { "batteryExempt": false, "backgroundRestricted": true, "lastUnexpectedStopMs": 1700000000000 }
```

So a hub or a fleet tool can say *why* a phone is unreachable instead of only
that it is. `/v1/health` is untouched and stays public. Nothing else from this
screen leaves the device — no exit history, no memory, thermal or network detail,
no text.

## Honest limits

* **Nothing here makes a process immortal.** Force-stop still wins, and Android
  still may restart a stuck service on its own schedule. The screen raises the
  odds and explains the failure; it does not promise uptime.
* **A measurement is a moment.** "Doze: no" now says nothing about ten minutes
  from now. Re-measuring is a tap, and nothing polls in the background.
* **Vendor advice is advice.** The notes for MIUI, One UI, ColorOS and friends
  are documentation, marked as such, because those screens cannot be opened
  programmatically and their behaviour cannot be read.
* **`--fix` on a computer is limited** to the Doze whitelist and the legacy
  Wi-Fi policy; it is not the Doctor's way of changing anything else.
* **The Android adapter is not unit-tested.** `AndroidDoctorProbe` is the only
  file in `engine/doctor/` that touches the platform, it holds no decisions, and
  `tools/test-policy` #43 pins that shape. Everything that decides is JVM-tested.
* **No physical phone was used by the maintainer.** The first real measurement is
  the operator's (B2).
