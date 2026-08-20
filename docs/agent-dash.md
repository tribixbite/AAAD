# Agent dash — design

Covers [TASKS.md](../TASKS.md) Phase 4. Design doc; nothing here is implemented.

## What it is

The `../operad` equivalent, scoped to Android Auto work: a Termux-hosted web dashboard over the
test harness. Operad orchestrates Claude sessions across projects; this dash orchestrates
*devices, catalog state, and harness runs* for one project, and gives an agent working in this repo
a place to look instead of re-deriving state from `adb` every session.

Scope boundary: the dash **observes and triggers**; it does not reimplement the harness. Everything
it shows comes from `harness/runs/*/results.jsonl` and live adb queries.

## Views

| View | Content | Source |
| --- | --- | --- |
| Devices | Serial, model, Android version, AA version, Shizuku state, connection health | live adb via `harness/src/devices.ts` |
| Matrix | catalog app × device grid, colour-coded: installed / stale / broken / unknown | latest run + baselines |
| Run | One harness run: per-step timeline, logcat excerpt, screenshot gallery | `runs/<ts>/results.jsonl` |
| Catalog | Upstream catalog vs. installed state per device; version deltas; dead links | `catalog.ts` + device snapshot |
| Tasks | [TASKS.md](../TASKS.md) rendered with phase/status rollup | parse `TASKS.md` |

The matrix is the point. Everything else supports it: one screen that answers "which AA apps
currently work, on which device, on which Android version".

## Stack

Match the constraints already in force on this box rather than inventing new ones:

- **bun** for runtime and package management — never npm/npx.
- **TypeScript** throughout, shared types with `harness/` (the harness owns the schema; the dash imports it).
- Dark, mobile-friendly UI that resizes to the full viewport and works on a touchscreen — this is
  read from a phone as often as from a desktop browser.
- Server-Sent Events for live device/run status; no polling loop in the client.
- No external CDN assets — this runs offline on a phone.

Do not commit a `package-lock.json` pinning device-only natives, and put any
`*-android-arm64` native in `optionalDependencies` so the repo still installs off-device.

## Triggering runs

The dash should be able to start a harness run and stream its output, with two hard guards
inherited from [docs/testing-harness.md](testing-harness.md):

1. Refuse to run against a build that still has the download gate enabled.
2. Never uninstall, reboot, or clear app data without an explicit per-run confirmation — and never
   `stop; start` the framework on the Saga test phone under any circumstance.

## Relationship to operad (T-34)

Two options, to be decided rather than drifted into:

- **Separate service.** Its own port and lifecycle, registered as an operad session so it boots
  with everything else. Simplest; no coupling to operad's internals; duplicates some device-status
  code operad already has (`android-engine.ts`).
- **Operad plugin/panel.** Reuse operad's device inventory, SSE plumbing, and dashboard shell, and
  add AAAD-specific views. Less duplication, but binds this repo's release cadence to operad's.

Recommendation: start as a **separate service** so Phase 4 isn't blocked on operad's internals, and
revisit consolidation once the matrix view has proven what it needs. Record the decision here when
made.

## Non-goals

- Not a replacement for AAAD's own UI. The app stays the app.
- Not a Claude-session manager — that is operad's job. If this dash needs to launch agent work, it
  hands off to operad rather than growing its own session layer.
- Not multi-user or authenticated. It binds to localhost on a personal device.
