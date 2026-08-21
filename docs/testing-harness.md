# Android Auto app testing platform — design

Covers [TASKS.md](../TASKS.md) Phase 3. This is a design doc for work not yet started; nothing
here is implemented.

## The problem

AAAD's real function is: fetch a third-party APK, install it in a way that makes Android Auto
list it, on a device/Android-version combination where that still works. All three legs break
independently and silently:

- Publishers move or re-version their APKs, so a catalog entry rots.
- Android and Android Auto tighten install and visibility rules every release (the README's known-issues
  list is exactly this: OnePlus/Oppo/Realme, Pixel on Android 13, everything on Android 14+).
- Play Protect intervenes.

Upstream verifies this by hand, per release. The fork's goal is to make it a run: **catalog ×
device → install → launch → AA-visibility → screenshot → verdict**, repeatable and diffable.

## Shape

A host-side harness in `harness/`, TypeScript on bun, driving devices over adb. Not an
instrumentation test suite — the interesting assertions are about the *system* (does AA list the
app), not about AAAD's internals. Those get ordinary `androidTest` coverage instead (T-25).

```
harness/
  src/
    adb.ts          Device discovery + command wrapper (see "adb on this box")
    devices.ts      Inventory: serial, model, Android version, AA version, Shizuku state
    catalog.ts      Read the catalog the app would read; also accepts a local override
    run.ts          The matrix: for each (device, app) → install → launch → probe → capture
    probe.ts        Android Auto visibility probes
    capture.ts      Screenshots, with this device's size constraints enforced
    report.ts       JSONL → summary + regression diff
  runs/
    <ISO8601>/      One directory per run: results.jsonl, screenshots/, logcat/
  baselines/
    <device>.json   Expected per-app outcome; diffed each run (T-24)
```

Results as JSONL, one object per (device, app, step) — cheap to append, trivially diffable, and
directly consumable by the dash ([docs/agent-dash.md](agent-dash.md)).

## T-22 research: what upstream's head unit emulator actually buys

Read out of upstream v2.8.5's `com.legs.appsforaa.androidauto.**` (see
[upstream-2.8.5-diff.md](upstream-2.8.5-diff.md)). Three findings that change the plan:

**1. There is a supported way to induce a projection session locally.** `AndroidAutoLauncher`
starts Android Auto's wireless entry point with a host and port:

```
com.google.android.projection.gearhead/
  com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity
    PARAM_HOST_ADDRESS = 127.0.0.1
    PARAM_SERVICE_PORT = <local TCP port>
```

`AndroidAutoProxyService` then listens on `127.0.0.1` and speaks the AA protocol
(`HeadUnitEmulator`, ~90 protobuf types, `ssl/SSLEngineBuilder`). **Verified on the test device:
`WirelessStartupActivity` resolves.** So a phone can host its own head unit — no car, no DHU.

**2. But the app list is video, not data.** Android Auto discovers projection apps *locally* via
PackageManager and renders the launcher into the projected video stream. A head unit — real or
emulated — receives H.264 frames, not an app list. Emulating the protocol therefore does **not**
hand you an enumerable list; you would still be reading pixels.

**3. What it does buy is a running gearhead.** The reason nothing is observable today is that AA
runs no services and caches nothing while idle
([aa-visibility.md](aa-visibility.md#observability-aas-app-list-needs-a-live-projection-session-v)).
Inside a session that changes, and `dumpsys` against a live gearhead becomes worth trying.

**So the cheap experiment is worth doing before the expensive one.** Ranked:

| Approach | Cost | What it proves |
| --- | --- | --- |
| Induce a session, then `dumpsys` gearhead | low — if a minimal handshake is enough to keep AA connected | possibly the full list, as data |
| Induce a session, screenshot the projected launcher, OCR | medium | the list, as pixels — brittle across AA redesigns |
| Desktop Head Unit | medium, needs a desktop host | ground truth, not automatable from this box |
| Full protocol emulator (upstream's ~100 classes) | high | a session, and nothing more than the two rows above |

The last row is the point: **reimplementing the emulator is not itself the answer to T-22**, it is
only a means of reaching rows 1–2. Do not start it before checking whether a minimal handshake
gets AA far enough to populate `dumpsys`.

Also worth knowing, from `AndroidAutoDeveloperModeManager`: upstream flips AA's *Unknown sources*
by shell-editing `com.google.android.projection.gearhead_preferences.xml` (backup, `sed`, restore)
under the key `unknown_sources_enabled`. That needs root and writes into Google's private data.
The fork does not do this — [AndroidAutoSetupActivity](../app/src/main/java/com/legs/appsforaa/AndroidAutoSetupActivity.kt)
walks the user through four taps instead. **Reading** that key on a rooted device is, however, a
legitimate harness signal.

**Do not hardcode Android Auto activity names.** Upstream's constants
(`gearhead.vanmoof.VanmoofSettingsActivity`, `setupwizard.DeveloperSettingsActivity`) **do not
resolve** on the test device, which uses `gearhead.vanagon.VnDrivingModeLauncherActivity` and
`.frx.SetupActivity`. Resolve intents instead — a hardcoded class is a silently dead button.

## The hard part: proving Android Auto sees the app

Install success is easy to assert (`pm list packages`). "Android Auto lists it" is the assertion
that actually matters and the one with no obvious API. Options, none free:

1. **Dump AA's own state.** `dumpsys` against `com.google.android.projection.gearhead`, or its
   app-list cache on disk. Cheapest, fully on-device, no desktop — but undocumented, version-fragile,
   and possibly root-gated.
2. **Desktop Head Unit (DHU).** Google's official emulator; drives a real AA session over adb and
   renders the launcher. Authoritative, but it needs a desktop host — this box is the adb *host*,
   so DHU would need to run elsewhere or under a Termux X11 environment. Investigate before committing.
3. **On-device AA rendering + screencap + OCR/template match.** Works without a desktop, brittle
   against AA UI changes, and needs a car or a head-unit emulator to enter projection mode at all.

**Decide during T-22 and write the rationale down.** Recommended order to evaluate: (1) for a fast
signal in every run, (2) as the periodic authoritative check. Until one is chosen, the harness
should record install + launch + package presence and report AA visibility as `unknown` rather
than silently claiming success.

## adb on this box

Constraints from `~/.claude/CLAUDE.md` that the harness must encode, not work around by hand:

- **The wireless-debugging port rotates.** `adb connect` to a remembered port fails and looks like
  a dead device. Rediscovery: `nmap -sT -p 30000-65535 --open -n 127.0.0.1`, then probe each open
  port with `adb connect`. `persist.adb.tcp.port=5555` pins only the post-reboot port.
  This belongs in `adb.ts` as automatic reconnect-with-rediscovery.
- **Never `stop; start`** (framework restart) on the Saga test phone — it is banned and has
  required physical recovery twice. No `setprop ctl.restart zygote` either. Never reboot or clear
  app data without explicit per-instance permission.
- **Screenshots:** no dimension ≥ 2000 px and file size < 4 MB, compressed/converted if either is
  exceeded. `capture.ts` enforces this before anything reads an image.
- **Leave no trace.** After changing UI focus or a system setting for a test, restore the previous
  state — original foreground app, original input method, original setting values.
- **Grep logcat before clearing**, and check timestamps so you aren't reading the previous run.

## Run lifecycle

1. **Discover** devices; refuse to run against an unknown serial unless explicitly allowed.
2. **Snapshot** pre-state per device: installed catalog packages + versions, AA version, Android
   version, Shizuku availability.
3. **Per (device, app):** uninstall if present (only with permission) → trigger install through
   AAAD → wait for `PackageInstallReceiver`-visible state or poll `pm` → launch → probe AA →
   screenshot → pull the relevant logcat window.
4. **Restore**: return the device to its pre-run foreground app and settings.
5. **Report**: write `results.jsonl`, diff against the device baseline, print newly-broken apps.

Install path matters: the Shizuku silent path (T-41) is what makes step 3 unattended. The system
installer path needs UI automation to tap through, and Play Protect can inject an extra dialog —
that is a per-device, per-Android-version variable the harness should record rather than hide.

## Interaction with the standalone build

A matrix run is only possible because the fork is standalone: upstream's one-install-per-30.44-days
quota would make it a non-starter, and its mandatory Firebase auth would make offline runs
impossible ([docs/standalone.md](standalone.md)). Two consequences the harness should encode:

- **Target `sksa.aa.customapps.dev`, not `sksa.aa.customapps`.** Debug builds carry the `.dev`
  suffix so they coexist with any official install. A harness that drives the official package id
  is touching an app it did not build.
- **Refuse to run against a build that is not from this tree.** Check the installed signer or
  the `.dev` suffix before doing anything destructive, so a stray run cannot uninstall or
  overwrite a real install.

Phase 2 remains a hard prerequisite for a different reason now: until T-04 and T-06 land, the app
has no entry point and cannot make an installed app visible to Android Auto — there is nothing
for the matrix to assert.
