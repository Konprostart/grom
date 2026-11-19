# RECOVERY — provenance of this tree

This repository was rebuilt on 2026-08-19 after the original working copy was lost
in a workspace migration.

## What is known

The handoff record described:

- project `Grom`, branch `main`, HEAD `b097628`, clean tree
- package `app.grom.server`, SDK `app.grom.sdk`, AIDL `app.grom.sdk.ipc`
- licence Apache-2.0
- `docs/000-decisions.md` with decisions D1–D31
- `docs/specs/S1…S4`, 6 406 lines, 239 requirements, 116 conformance tests
- `docs/F1-implementation-plan.md`, 9 steps
- `app/` with flavours `server29` and `peripheral26`
- `testapps/` (`samecert`, `foreigncert`) for C-008
- `tools/` (`test-agent`, `test-policy`, `verify-ipc-signing`, `check-third-party`)
- `scripts/` (`build-node-android.sh`, `gen-test-keys.sh`)
- F1 steps 1–7 committed (`5e65cf1`, `7f97a54`, `e176ecb`, `e9e6270`,
  `ab8dd74`, `6c01541`, `b097628`), steps 8–9 outstanding
- 67/67 JVM tests, 45/45 host gate, 17/17 policy, 17/17 IPC signing, lint clean
- three non-code blockers: no `libnode.so`, no on-device run, open question Q8
  (`node:sqlite` on aarch64)

## What could not be recovered

The original files were not present in the new workspace and no archive, bundle
or remote was available. Therefore:

1. **The specification text is re-derived, not restored.** S1–S4 in this tree
   express the same contracts the handoff names (loopback-only bind, token auth,
   audit chain, signature-gated AIDL, supervised agent, quarantine, secrets over
   stdin) but they are not a byte-for-byte copy. Requirement IDs restart at
   `S1-R001` and the count differs from the original 239.
2. **The original commits do not exist.** The history in this tree is a fresh
   reconstruction that follows the same nine F1 steps and their order.
3. **Verification counts differ** because the requirement set differs.

Everything in `docs/000-decisions.md` marked *reconstructed* is a decision this
tree commits to; it should be treated as normative going forward unless a
recovered copy of the original contradicts it.

## About this history

The commits in this tree follow the order of `docs/F1-implementation-plan.md`,
but they were re-created in one sitting. **Only the final commit is built and
tested**; intermediate commits are not guaranteed to compile on their own. Do
not bisect against them as if they were the original history.

## If the original is ever recovered

Prefer the original over this tree for **specification text and decision
numbering**. Graft the implementation changes forward. Do not merge histories.

## Status of the three blockers

They are unchanged and remain the reason the server cannot be called working:

- B1 — `libnode.so` is still absent; `scripts/build-node-android.sh` documents
  the Docker line that would produce it.
- B2 — no `adb`, so nothing has run on a device.
- B3 — Q8 (`node:sqlite` on aarch64) is still unanswered.

The APK builds, installs, and reports `MISSING_RUNTIME` honestly. That is the
correct behaviour, not a bug.
