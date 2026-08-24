# Android Auto app testing platform — design

Covers [TASKS.md](../TASKS.md) Phase 3. The harness, capture, baselines, dashboard feeds, and
phone-side launcher probe described here are implemented; the protocol investigation is retained
as a decision log.

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

> **Resolved 2026-08-23:** a head-unit session is not needed to enumerate the launcher.
> `tools/aa-launcher-list.sh` reads Android Auto's phone-side *Customize launcher* screen and
> closed T-22. The research below remains useful for the separate question “does this listed app
> open, render, and accept input?”, which is the final T-54 Carify bridge test. Statements below
> that call video decoding the only listing route are historical conclusions superseded by this.

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
([aa-visibility.md](aa-visibility.md#observability-the-launcher-list-lives-in-android-auto-settings-v)).
Inside a session that changes, and `dumpsys` against a live gearhead becomes worth trying.

### The cheap experiment, attempted [V]

Run on the Saga: a `toybox nc -l -p <port>` listener on `127.0.0.1`, then

```
su -c "am start -n com.google.android.projection.gearhead/\
com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity \
  --es PARAM_HOST_ADDRESS 127.0.0.1 --ei PARAM_SERVICE_PORT <port>"
```

Result: **the intent is accepted, but Android Auto never dials out.** Zero bytes reached the
listener, focus never moved to AA, and its own logs show the setup service starting and
immediately tearing down:

```
CAR.SERVICE.USBMON.LITE: Stopped USB monitor
CAR.SETUP.SERVICE.LITE:  quit handler thread
```

Note `am start` without `su` is rejected outright — the activity is not exported.

**The missing precondition is that Android Auto has never completed its own first-run setup on
this device.** It has no `com.google.android.projection.gearhead_preferences.xml` and no
`databases/` at all. A freshly-installed AA that has never been paired with a car appears to
decline to start a wireless session at all, which is consistent with it tearing down immediately
rather than erroring.

Repeated on the **paired** S25U (Android Auto 17.3, first-run setup complete, three gearhead
processes resident) and it fails differently — and more usefully:

```
java.lang.SecurityException: Permission Denial: starting Intent { ...WirelessStartupActivity }
  from null (pid=…, uid=2000) not exported from uid 10258
```

**`WirelessStartupActivity` is not exported.** Shell (uid 2000) cannot start it, and neither can
Shizuku, which *is* uid 2000. Only root can — which is why the same command was accepted on the
rooted Saga. Nor can a third-party app, so upstream's `AndroidAutoLauncher` cannot be doing this
on an ordinary device either.

That leaves the two test phones each holding exactly one half of the precondition:

| | Android Auto set up | root |
| --- | --- | --- |
| Saga | ✗ (no prefs, no `databases/`) | ✓ |
| S25U | ✓ paired | ✗ |

### The route that needs neither: the head unit server

Android Auto's developer settings include **"Start head unit server"**, which opens the Desktop
Head Unit port — conventionally **5277** — and waits for a client. That is the supported entry
point the DHU itself uses, it is reached by a *user-toggled setting* rather than a privileged
intent, and it therefore needs **no root and no exported activity**.

Checked on the paired S25U: nothing is listening on 5277 today, so the setting is off. Turning it
on is a manual step ([AndroidAutoSetupActivity](../app/src/main/java/com/legs/appsforaa/AndroidAutoSetupActivity.kt)
already walks a user into AA's developer settings), after which:

```bash
adb forward tcp:5277 tcp:5277     # then connect a client and see how far the handshake gets
```

**This is the T-22 route to pursue.** It removes the root requirement that blocked both devices,
and it is the same socket a real head unit uses — so whatever protocol work turns out to be
necessary is work against a documented target rather than a reverse-engineered one.

### Confirmed on hardware [V]

Enabling it on the paired S25U (AA 17.3) and watching `/proc/net/tcp`:

- **Port 5277 opens.** The assumed port was right, now observed rather than assumed.
- The component behind it is `.companion.DeveloperHeadUnitNetworkService`.
- Enabling it starts a **`com.google.android.projection.gearhead:projection`** process and binds
  `GearheadCarStartupService` — state that does not exist on an idle phone.
- **No root needed.** It is a menu item, reached as AA's own string says: *"Developer mode
  enabled. Access it using the overflow menu on the top right."* Strings #843–#848 are one
  contiguous overflow-menu block — `Start head unit server`, `Stop head unit server`,
  `Developer settings`, `Help and feedback`, `Quit developer mode` — so it sits *beside*
  "Developer settings", not inside it. That is the single most confusing part of this and it cost
  a round trip.

Connecting to it from Termux on the same device (`127.0.0.1:5277`):

```
RESULT=CONNECTED to 127.0.0.1:5277
BYTES=0
NOTE=server accepted the connection but sent nothing; it expects the client to speak first
```

A naive version-request frame (`00 03 00 06 | 00 01 00 01 00 01` — channel 0, first+last flags,
then message id 1 with major/minor) drew **no reply**, so the framing has to match the real
protocol exactly; guessing at it is not going to work.

### Historical head-unit-server verdict

**With the head unit server running and a client connected, `dumpsys` still does not expose the
app list.** The full services dump is 183 lines of gearhead's own services and nothing else — no
third-party packages. That correctly closed `dumpsys`; it did not prove the list existed only in
the projected video, because the same list is also rendered in phone-side Customize launcher.

For a live, automatable projection session the route and cost remain:

- **Route:** the head unit server on 5277, no root, one menu tap. Confirmed reachable.
- **Cost:** implementing enough of the AA protocol to complete the handshake and decode video —
  which is what upstream's ~100 classes are. There is no shortcut through `dumpsys`.
- **Therefore:** use Customize launcher for explicit listing checks. Attempt the protocol only
  when rendering or interaction is under test, and start from AASDK/openauto framing rather than
  guesswork.

**Turn the server off when finished** (same overflow menu, "Stop head unit server"). It is an
open listening port on the phone.

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

## The hard part now: proving a listed app works

Install success is easy to assert (`pm list packages`), and Customize launcher now answers
whether Android Auto lists it. Proving the app actually opens and behaves on the head unit still
has no cheap API. Options, none free:

1. **Dump AA's own state.** `dumpsys` against `com.google.android.projection.gearhead`, or its
   app-list cache on disk. Cheapest, fully on-device, no desktop — but undocumented, version-fragile,
   and possibly root-gated.
2. **Desktop Head Unit (DHU).** Google's official emulator; drives a real AA session over adb and
   renders the launcher. Authoritative, but it needs a desktop host — this box is the adb *host*,
   so DHU would need to run elsewhere or under a Termux X11 environment. Investigate before committing.
3. **On-device AA rendering + screencap + OCR/template match.** Works without a desktop, brittle
   against AA UI changes, and needs a car or a head-unit emulator to enter projection mode at all.

The matrix continues to record `androidAutoVisible: "unknown"`: an unattended run cannot assume
the device is onboarded, and Customize launcher exposes labels rather than unique package ids.
Run the explicit launcher probe when listing is under test; use a car/DHU/emulator when rendering
or interaction is under test.

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

## Testing an app that is not in the catalog (T-42)

Push a catalog to the app's own external files directory and it replaces the shipped one:

```bash
adb -s "$D" push my-catalog.json \
    /sdcard/Android/data/sksa.aa.customapps.dev/files/catalog.json
```

Same schema as `app/src/main/assets/catalog.json`. Three things worth knowing:

- **It replaces, it does not merge.** A run that asks for three specific apps should get exactly
  those three, not those three plus the seven that ship in the build.
- **The directory has to exist first.** `adb push` fails with
  `secure_mkdirs failed: Operation not permitted` if the app has never run on the device — adb
  cannot create a package's directory under `Android/data`, but the app creates it the first time
  it loads a catalog. Launch the app once, then push. That path is used precisely because `adb`
  can write it with no storage permission, no `MANAGE_EXTERNAL_STORAGE` and no root.
- **A malformed override is ignored, not fatal.** It is logged at warn level and the build falls
  back to the bundled catalog; an app that refuses to start is a poor way to report a typo.

The catalog screen says *"Using a catalog pushed to this device"* whenever an override is in play,
so an unexpected app list is self-explaining. Delete the file to go back to the shipped catalog.

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
