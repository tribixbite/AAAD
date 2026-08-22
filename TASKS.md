# AAAD fork — Task Backlog

Working file. `go` = pick the next unchecked task in the lowest incomplete phase, do it fully,
commit, and tick it here. Reorder if priority genuinely changed; add tasks as they surface.

Task ids are stable — cite them in commits (`feat(catalog): resolve publisher URLs (T-15)`).
Ids are never reused; an obsolete task is struck through with the reason, not deleted.

Status: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked (say why inline)

---

## Phase 0 — Repo navigation (done)

- [x] **T-00** Agent-facing docs: `CLAUDE.md`, `ARCHITECTURE.md` (incl. the PRO gating spec),
  `TASKS.md`, `docs/build-setup.md`, `docs/testing-harness.md`, `docs/agent-dash.md`, `.gitignore`.

## Phase 1 — Build scaffolding (done)

Detail: [docs/build-setup.md](docs/build-setup.md).

- [x] **T-01** Gradle scaffolding: `settings.gradle`, `gradle.properties`, wrapper pinned to
  Gradle 8.13 (AGP 8.13.1's minimum), `app/proguard-rules.pro`, `local.properties.example`,
  `build-on-termux.sh`.
  *Verified 2026-08-20:* `./build-on-termux.sh debug --no-install` produces a 14 MB
  `AAAD-2.1-debug.apk` on-device — package `sksa.aa.customapps.dev`, targetSdk 36, debug-signed.
  It crashes on launch because `LauncherActivity` has no source; that is T-04.
- [x] ~~**T-02** Create a Firebase project and add `google-services.json`.~~
  **Obsolete** — the fork has no backend, so there is nothing to configure.
  See [docs/standalone.md](docs/standalone.md).
- [x] **T-03** Recovery strategy for the missing classes, recorded in
  [docs/build-setup.md](docs/build-setup.md#recovering-the-missing-classes):
  decompile the official APK **for evidence**, write original code from it.
- [x] **T-05** Env-var-driven signing (`RELEASE_KEYSTORE` + friends) with a debug fallback, and
  `applicationIdSuffix '.dev'` so dev builds coexist with an official install.
- [x] **T-16** GitHub Actions: `build-apk.yml` (debug APK, artifact, `dev-<sha>` prerelease,
  **zero secrets required**) and `release.yml` (tag-triggered signed release with
  `apksigner verify`).
  **Exercised 2026-08-21.** First run failed at `dataBindingMergeDependencyArtifactsDebug` —
  JitPack again (T-19); removing that dependency turned it green. Second run published
  `dev-34f4ae7`, and the published APK was downloaded and checked: 11 MB,
  `sksa.aa.customapps.dev`, versionCode 18, compileSdk/targetSdk 36, label "AAAD (dev)".
  `release.yml` remains unexercised — it needs a `v*` tag and four signing secrets.

## Phase 2 — Standalone: no server, no gate

Design and rationale: [docs/standalone.md](docs/standalone.md).

- [x] **T-10** Remove backend dependencies: Firebase BoM + Database/Storage/Auth/Functions,
  Stripe, QRCodeScanner/zxing, `commons-net` (the NTP quota clock), Volley, and the
  `google-services` plugin. Drop the 14 secret `buildConfigField`s.
- [x] **T-11** Remove the paid surface: `AboutPaymentActivity.kt`, `EnterProCode.java`,
  `TransferLicense.java`, `User.java`, their layouts and menu, the five manifest activities, the
  `CAMERA` permission, and the `transfer_license` menu item.
- [x] **T-04** Minimum spine to a running app: `utils/Logger`, `utils/ViewExtensions`
  (`applyBottomInsetPadding` / `applyTopInsetPadding`), `data/CatalogModels` +
  `data/CatalogRepository`, `adapters/AppListAdapter`, `LauncherActivity`, `MainActivityNew`,
  `receivers/PackageInstallReceiver`, and the three layouts the source drop never published.
  *Verified live on SM_S938U1 / Android 16:* the app launches to the catalog, loads 6 apps from
  the bundled asset, renders in dark mode with correct edge-to-edge insets, and — after
  Nav2Contacts was installed out-of-band — the receiver logged `PACKAGE_ADDED` and the card
  flipped from `Not Installed / INSTALL` to `Installed: 1.0.3 / OPEN` with no manual refresh.
  The unimplemented activities were **omitted from the manifest rather than stubbed**: a declared
  component with no class is a crash waiting for whoever first routes to it.
- [ ] **T-08** Onboarding (`OnboardingActivityNew` + the first-run routing seam already present in
  `LauncherActivity`): permissions, Play Protect warning, Shizuku setup. All strings already
  exist in `res/values/strings.xml`. Re-add the manifest entry with the implementation.
- [x] **T-09** `AndroidAutoSetupActivity` **done**: the developer-settings walkthrough, with live
  status for Shizuku and the installed Android Auto version. It leads with whether the user needs
  the steps at all — if Shizuku is ready it says so rather than sending them through a fiddly
  manual procedure for nothing — and its entry point on the catalog screen only appears when
  Shizuku *cannot* provide attribution. Verified on the Saga: *"Shizuku is ready — you do not need
  these steps. Android Auto 17.3.662874-release is installed"*.
  Uses `utils/AndroidAutoLauncher`, which **resolves intents instead of naming activities**:
  upstream's hardcoded `gearhead.vanmoof.VanmoofSettingsActivity` and
  `setupwizard.DeveloperSettingsActivity` do **not** resolve on the test device (it has
  `gearhead.vanagon.VnDrivingModeLauncherActivity` and `.frx.SetupActivity`), so a hardcoded class
  is a silently dead button.
  It does **not** copy upstream's approach of shell-editing gearhead's `shared_prefs` to flip
  `unknown_sources_enabled` — that needs root and writes into Google's private app data.
  `SupportActivity` **done**, but as a **diagnostics** screen rather than upstream's "email
  help.aaad@gmail.com": there is no support team behind a personal build, and sending upstream
  reports about a modified app would waste their time. `utils/Diagnostics` collects device, app,
  Shizuku, Android Auto, catalog and per-app installer attribution as plain text, with copy and
  share. Every field earned its place by having been the answer to a real question during
  development. Verified on the Saga.
- [ ] **T-15** Build the real catalog: `app/src/main/assets/catalog.json` per the schema in
  [standalone.md](docs/standalone.md#catalog-format). Upstream's own catalog (14 apps, with
  package names and categories) is recovered in
  [aa-visibility.md](docs/aa-visibility.md#also-recovered) — use it for structure and package
  names, which are now all resolved. **Do not copy its `download_url`s**: they point at upstream's
  Firebase Storage bucket with embedded access tokens. Establish each `source` from the
  publisher's own release page instead, and do not invent URLs to fill gaps.
  For the mirroring family: T-07 established that renaming adds no AA capability, so catalogue
  them from **publisher** sources under their real package names and mark AA visibility unverified.
  Do not copy upstream's renamed builds — they are signed with the public AOSP test key.
- [x] **T-06** Android Auto visibility: **installer attribution, not APK patching.**
  **Implemented**: `data/ReleaseResolver` (GitHub releases → concrete APK), `utils/ApkDownloader`
  (cancellable, progress), `utils/ShizukuInstaller` (`pm install-create -r -i com.android.vending
  --originating-uri … --install-reason 0` + `--bypass-low-target-sdk-block` on SDK ≥ 34, APK
  streamed over stdin, session abandoned on failure), `utils/SystemInstaller` (fallback, states
  plainly that its result is *not* Play-attributed), `utils/InstallManager` (picks per attempt).
  No `pm set-installer` repair path exists, by design — verified impossible on Android 16
  (`SecurityException: Caller does not have same cert as new installer package`), so attribution is
  declared at session creation or not at all.
  **Verified on device:** resolve → download end to end (correct asset, version, byte count); the
  fallback path; and correct availability reporting.
  **Verified end to end on the Saga (2026-08-21):** resolve → download (4469526 bytes, progress
  to 1.0) → Shizuku attributed session → `RESULT=ATTRIBUTED version=1.0.3` and
  `installer=com.android.vending`. The remaining half of the original acceptance — that Android
  Auto then *lists* it — still needs T-22's observation channel.
- [~] **T-07** Determine whether the mirroring family needs **package renaming** to be visible to
  Android Auto. **Half answered** — see
  [aa-visibility.md](docs/aa-visibility.md#package-renaming-what-it-actually-does-t-07-partial-v).
  Verified by examining upstream's renamed AAMirror build: the rename changes **only the package
  identifier** (classes stay `com.github.slashmax.aamirror.*`) and **adds no AA capability** — the
  original already declares `com.google.android.gms.car.application` plus the `CATEGORY_PROJECTION`
  categories. Only the mirroring/streaming family is renamed; utilities upstream hosts itself keep
  their publishers' names. So the rename is about identity, not capability — plausibly blocklist
  evasion, but that is **inferred, not established**.
  Remaining half needs T-22's observation channel: does AA (or Play Protect) actually reject the
  original identity? Until then, catalogue the mirroring family from **publisher** sources and mark
  AA visibility unverified rather than copying upstream's renamed builds. Upstream distributes CarStream as `maps.jaoolonda.android`, Screen2Auto as
  `android.loandamaps.it`, and the AAMirror/AAStream/AAMirrorPlus family as `maps.*` — v2.1 shipped
  pre-renamed APKs, v2.8.5 renames on-device (`PackageRenamer` + `ARSCPackageRenamer` over
  `AndroidManifest.xml` and `resources.arsc`). The *reason* is unverified. This gates T-15: if the
  fork downloads from the original publishers it gets the publishers' package names.
  *Done when:* an unrenamed publisher build is installed with correct attribution and we know
  whether Android Auto lists it. If renaming is required, in-process signing is the proven route —
  `com.android.apksig.ApkSigner` or `net.fornwall.apksigner.ZipSigner`, no BouncyCastle.
- [ ] **T-17** Self-update against this fork's own GitHub releases, using `utils/Version.java`.
  Replaces upstream's update check, which pointed at upstream's releases.

- [x] **T-30a** **Convert installed apps.** `data/InstalledAppScanner` finds installed apps
  declaring `com.google.android.gms.car.application`, reads each one's installer via
  `getInstallSourceInfo`, and marks the unattributed ones convertible.
  `ShizukuInstaller.convertInstalled` re-stages the app's **own** APKs — base plus every split —
  through an attributed session; same signature, so it is an update over the top and app data
  survives. `ConvertActivity` confirms first and says so. Conversion has **no fallback** by design:
  the system installer cannot set attribution, so it says that rather than failing vaguely.
- [x] **T-31a** **Discover apps on GitHub**, modelled on Obtainium's GitHub source
  (`~/git/obtainium/lib/app_sources/github.dart`): `/search/repositories`, star floor, archived
  repos flagged rather than hidden — for AA apps the archived project is often the only working
  build. `DiscoverActivity` also accepts a pasted repo URL, which is added directly instead of
  burning one of GitHub's ~10 unauthenticated searches per minute. Added entries live in
  `UserCatalogStore` and merge into the catalog.
  A discovered entry has **no package name** — a repo does not advertise one — so `MainActivityNew`
  learns it from the `PACKAGE_ADDED` broadcast after the first install, which is the only place
  that fact exists. AA capability is confirmed afterwards by the scanner reading the real manifest,
  never inferred from a repo description.

## Phase 3 — Android Auto app testing platform

Design: [docs/testing-harness.md](docs/testing-harness.md). Phase 2 is a hard prerequisite —
a matrix run is impossible against a quota-gated build, and that is now moot.

- [x] **T-20** `harness/` (TypeScript, bun): `adb.ts` (device resolution + rotated-port rescan;
  **refuses to guess** when several devices are online — it silently ran against the wrong phone
  once when a second handset rejoined the network, so `--serial` is required in that case),
  `app.ts` (drives the debug automation hook, parses `RESULT=` verdicts), `catalog.ts`,
  `cli.ts` (`devices` | `status` | `matrix`). Results as JSONL, one object per (device, app).
  Verified against the Saga.
- [x] **T-21** Catalog-driven install matrix: `bun run src/cli.ts matrix [--apps id,id]` installs
  each entry and records the outcome. It cross-checks two independent sources — the app's own
  reported outcome and `pm list packages -i` — because they can disagree, and the disagreement is
  the interesting part. Refuses to run when Shizuku is not `Ready`, since every install would
  silently fall back to an unattributed one and the matrix would measure nothing.
  Verified: `n2c … attributed (installer=com.android.vending)`, `playAttributed: true`.
  *Still to add:* launch + screenshot per app (T-23 covers the capture rules).
- [~] **T-22** Android Auto visibility probe — the assertion that actually matters. Upstream's
  `AndroidAutoCompatChecker` is a working model: per package it checks AA meta-data, Play Store
  stamps, **installer source**, and the unknown-source flag
  ([aa-visibility.md](docs/aa-visibility.md#diagnostics-worth-keeping)). Installer source is the
  cheap high-signal check — `pm` reports it directly. Three ground-truth options now: `dumpsys`
  against gearhead, the desktop Desktop Head Unit, or an **on-device head unit emulator** —
  upstream v2.8.5 ships one (`androidauto/HeadUnitEmulator` + ~90 protobuf message types + a
  `127.0.0.1` proxy), with `forceParkingBrake` / `forceUnrestricted` that would let a harness run
  unattended without a vehicle
  ([upstream-2.8.5-diff.md](docs/upstream-2.8.5-diff.md#the-other-headline-an-in-app-android-auto-head-unit)).
  **Route identified — the head unit server, not the emulator.** Two probes narrowed it:
  `WirelessStartupActivity` is **not exported**, so shell (uid 2000) cannot start it and neither
  can Shizuku, which is the same uid — only root can, which is why the rooted Saga accepted the
  intent and the unrooted S25U refused it. The two phones hold one half each of the precondition:
  the Saga has root but Android Auto was never set up (it tears the session down immediately);
  the S25U is paired but unrooted.
  Android Auto's **"Start head unit server"** developer setting sidesteps both — it opens the
  Desktop Head Unit port (conventionally 5277) via a user-toggled setting, needing no root and no
  exported activity, and it is the same socket a real head unit uses. Nothing listens on 5277 on
  the S25U today, so the setting is off.
  **Confirmed on hardware (2026-08-22).** Enabling it on the paired S25U opens **port 5277**
  (observed in `/proc/net/tcp`, not assumed), starts a `gearhead:projection` process, and binds
  `GearheadCarStartupService`; the component is `.companion.DeveloperHeadUnitNetworkService`. It
  needs **no root** — it is an overflow-menu item, sitting *beside* "Developer settings" rather
  than inside it, which is what made it hard to find.
  **But `dumpsys` still does not expose the app list** even with the server up and a client
  connected: 183 lines of gearhead's own services and no third-party packages. The cheap probe is
  therefore closed — the app list really is only rendered into the video stream.
  T-22 now has a known route and a known cost instead of an unknown: reaching a session is one
  menu tap, and getting the list from it means implementing the protocol (upstream's ~100 classes)
  and decoding video. The server ignores a guessed version frame, so any attempt must start from
  AASDK/openauto framing. **Do it only if visibility itself becomes the thing under test**; until
  then the harness reports `androidAutoVisible: "unknown"` and asserts installer attribution.
  **Investigated and confirmed blocked**: on a rooted device, gearhead has no `databases/` at all
  and `shared_prefs/carservice.xml` is 424 bytes of unrelated tuning constants — no app list, no
  `unknown_sources` flag. AA caches nothing until it has projected, which rules out any idle-phone
  probe. Real options remain a car, the desktop DHU, or an emulated head unit (upstream 2.8.5
  ships one). The harness reports `androidAutoVisible: "unknown"` rather than guessing.
- [x] **T-23** Screenshot pipeline: `harness/src/capture.ts` downscales to a 1400 px long edge
  before anything lands on disk (a native 1080x2400 capture already exceeds the 2000 px read
  limit), writes to `runs/<ts>/screenshots/`, and records the path in each JSONL row.
  Two refusals, both from things that actually happened: it verifies the app **took focus** before
  capturing — the first run screenshotted the launcher, which is misleading evidence rather than
  missing evidence — and it treats a 0-byte capture (screen off or locked) as "no screenshot"
  rather than failing a run whose real assertions do not need pixels.
  Launches `LauncherActivity`, not `MainActivityNew`: `am start` rejects the latter, and routing
  through the real entry point is what a user does anyway.
- [x] **T-24** Regression baselines: `harness/src/baseline.ts`, one per **device model** (serials
  rotate, models do not), holding only what should stay stable — outcome and Play attribution, not
  timings or paths. `matrix` diffs every run against it, `--accept true` records one, and a
  `broken` change sets a non-zero exit so a shell notices and not just a reader.
  `broken` is kept deliberately narrow — *was* Play-attributed, now is not — so the regression this
  project exists to catch does not get lost among version bumps.
  Verified on the Saga: first run recorded `baselines/Saga.json`, second reported "No change".
- [~] **T-25** Tests. `harness/src/baseline.test.ts` covers the comparator (8 tests, `bun test`) —
  it decides whether a run is called a regression, so a false green hides the exact breakage the
  harness exists to catch and a false red trains people to ignore it. It is pure logic, so there
  was no excuse. Cases include the two that matter most: an outcome change that *keeps* attribution
  must not read as broken, and a repeated failure must not masquerade as new breakage.
  *Remaining:* app-side tests for the `Version` comparator and catalog parsing.

## Phase 4 — Agent dash

Design: [docs/agent-dash.md](docs/agent-dash.md).

- [ ] **T-30** Dash skeleton served from Termux: device inventory + live adb status.
- [ ] **T-31** Harness run history: matrix view, per-app timeline, screenshot gallery.
- [ ] **T-32** Task queue view backed by this file.
- [ ] **T-33** Catalog inspector: catalog vs. installed state per device, update deltas.
- [ ] **T-34** Decide integration depth with `../operad` and record it in `docs/agent-dash.md`.

## Phase 5 — App changes worth making

- [ ] **T-41** Shizuku-first install path with a clean fallback, so harness runs are unattended.
- [ ] **T-42** Local catalog override (a file on the device) so unlisted APKs can be tested
  without editing the bundled catalog.
- [ ] **T-43** Structured logging to a file the harness can `adb pull`.
- [ ] **T-40** Update checker for installed AA apps — README calls it out as never-implemented;
  `Version.java` plus the catalog makes it straightforward.

## Low priority

- [x] **T-12** Upstream's 2.1 server-side authorization, resolved by decompile: the callable is
  **`requestAuthorizedDownload`**, the client reads an **`authorized`** field, and `users` /
  `lastdownload` remain the RTDB keys. The `"date"` extra's unit is still unconfirmed and not
  worth pursuing — it is a cosmetic countdown and the fork has no gate.
- [ ] **T-18** Prune the now-unreferenced PRO/payment strings across `res/values*`. Deliberately
  deferred: it churns 30 locale files for no functional gain.
- [x] **T-19** Vendor BottomDialogs and drop the JitPack dependency. It has now broken the build
  twice:
  1. `master-SNAPSHOT` is an unpinnable moving snapshot — JitPack read timeout under load. Pinning
     to the commit coordinate `95b945247b` is worse: JitPack builds it on demand, >2 min and
     unpredictable.
  2. The AAR predates AndroidX and pulls `com.android.support:support-compat:27.0.2`, whose
     `android.support.v4.*` classes collide with `androidx.core:core:1.17.0`
     (`checkDebugDuplicateClasses`). Worked around with `exclude group: 'com.android.support'`.

  The Java is **already vendored** as `utils/BottomDialog.java`; only resources are still pulled
  from the AAR.
  Vendor these (Apache-2.0, Javier Santos — keep the attribution):
  `layout/library_bottom_dialog.xml`; `drawable/shadow.xml`, `btn_flat_*`; `anim/sheet_show.xml`,
  `sheet_hide.xml`; styles `BottomDialogs`, `BottomDialogsAnimation`, `Button(.Base)`,
  `Button.Flat(.Base)`; colors `colorPrimary`, `colorPrimaryDark`, `flat_pressed`; the `btn_*`
  dimens. Then repoint `BottomDialog.java` / `UtilsLibrary.java` at `com.legs.appsforaa.R`.
  **Resolved by deletion, not vendoring.** `utils/BottomDialog` and `utils/UtilsLibrary`
  referenced only each other — nothing in the app used them — so both were deleted along with the
  dependency and the JitPack repository. Upstream v2.8.5 dropped BottomDialogs too.
  Trigger was a third failure: after the read timeout and the support-compat collision, it broke
  **CI** outright (`Repository maven is disabled ... Could not GET ... jitpack`).

---

## Notes and decisions

Append dated entries as decisions are made — this is the fork's decision log.

- **2026-08-20** — Fork initialized for documentation. Established: docs mark claims
  **[V]**/**[H]**/**[I]**; personal use only, no redistribution.
- **2026-08-20** — **Standalone decided.** No server, no accounts, no entitlement, no quota. The
  backend is removed rather than flag-gated: product flavors were considered and rejected as
  carrying dead code and a live-backend footgun for no benefit in a personal fork.
- **2026-08-20** — Build toolchain established from `../swype/cleverkeys` (`build-on-termux.sh`,
  env-var signing, CI shape) and `~/git/termux-tools/.claude/skills/android-termux-build.md`.
  Verified on-device: Gradle 8.13 + AGP 8.13.1 configure `:app` successfully.
- **2026-08-20** — **T-04 done; the app runs.** Catalog screen live on device, install-state
  detection verified end to end against a real publisher build. Two decisions worth keeping:
  unimplemented activities are **omitted from the manifest, not stubbed**, and
  `PackageInstallReceiver` is **runtime-registered only** — since Android 8.0 a manifest-declared
  receiver never gets `PACKAGE_ADDED`/`_REMOVED`/`_REPLACED` for other packages, so upstream's
  manifest entry looked right and silently never fired.
  The bundled catalog ships **6 apps, all from their publishers' own GitHub releases** (verified to
  have APK assets). The mirroring family and CarStream are deliberately absent pending T-07.
- **2026-08-20** — **Correction to the 2.1 signer evidence.** The earlier `dalvikvm` SIGABRT was an
  artifact of running from a writable path (ART's `Writable dex file … is not allowed` W^X check),
  not proof about the jar. Re-tested against a read-only copy: `ClassNotFoundException:
  com.android.apksigner.ApkSignerTool` — no dex in the jar. Conclusion unchanged, evidence fixed.
- **2026-08-21** — **T-06 and conversion closed on hardware.** With Shizuku authorized on the Saga,
  both flows verified from inside the app via the debug hook:
  *Install* — `Downloaded … (4469526 bytes)` → `Shizuku availability: Ready` →
  `Installed … via session 1364344564` → `RESULT=ATTRIBUTED version=1.0.3` →
  `installer=com.android.vending`.
  *Convert* — `installer=null` → `CONVERT state=CONVERTIBLE apks=1` →
  `Converted … to Play attribution` → `RESULT=CONVERTED` → `installer=com.android.vending`, and the
  scanner then reports `ALREADY_ATTRIBUTED`, `convertible=0`. The state machine closes both ways.
  Note Shizuku's authorization is **not** the Android permission: `pm grant` of
  `moe.shizuku.manager.permission.API_V23` reports `granted=true` and changes nothing, and editing
  `flags` in `/data/user_de/0/com.android.shell/shizuku.json` as root has no effect either. It has
  to be granted through Shizuku itself.
- **2026-08-21** — **Verified on the Saga test device** (ingot, Android 13 / SDK 33, rooted).
  The attributed session install works on **Android 13** exactly as on 16 —
  `pm install-create -r -i com.android.vending …` → `installer=com.android.vending`, so the
  mechanism spans both. `InstalledAppScanner` found **9** AA-capable apps and classified every one
  correctly; installing Nav2Contacts plainly (`installer=null`) flipped it to `CONVERTIBLE`, and
  installing it through an attributed session flipped it back to `ALREADY_ATTRIBUTED`.
  Added a **debug-only adb automation hook** (`src/debug`, `DebugAutomationReceiver`) so all of
  this runs headlessly — the harness needs it anyway (T-20/T-21) and it does not depend on an
  unlocked screen or stable tap coordinates.
  Still unverified: conversion and attributed install **from inside the app**, because Shizuku's
  authorization is server-side and needs one tap on its dialog, and the device is locked. Editing
  `flags` to `FLAG_ALLOWED` (2) in `/data/user_de/0/com.android.shell/shizuku.json` as root did
  **not** take effect even after restarting the server — Shizuku evidently does not treat that
  file as the sole source of truth. Backup left at `shizuku.json.aaad-backup`.
- **2026-08-20** — **Mechanism validated on real hardware** (SM_S938U1, Android 16 / SDK 36).
  Three results: (1) **adb is an exact substitute for Shizuku** — a Play-attributed session install
  over plain adb yields `installer=com.android.vending`, `packageSource=1`, so the harness never
  needs Shizuku; (2) **`pm set-installer` is impossible** —
  `SecurityException: Caller does not have same cert as new installer package`, which kills
  upstream's entire tier-2 repair path for adb *and* Shizuku alike (both are shell UID), so
  attribution must be declared at session creation; (3) **AA's app list is unobservable on an idle
  phone** — gearhead runs no services until a head unit connects, which is the real T-22 blocker.
  Device restored afterwards: test app uninstalled, pushed APK deleted.
- **2026-08-20** — **Diffed upstream v2.8.5 against v2.1** ([docs/upstream-2.8.5-diff.md](docs/upstream-2.8.5-diff.md)).
  93 → 241 classes. The `pm install-create -i com.android.vending` trick is **unchanged** — the
  v2.1 spec still describes the current mechanism. What changed: signing now works in-process
  (`com.android.apksig` / `net.fornwall ZipSigner`) while the dead `dalvikvm` jar is still shipped
  byte-identical; package renaming moved on-device (`PackageRenamer`/`ARSCPackageRenamer`);
  `SmartInstaller` formalized the strategy ladder; and upstream added a full **in-app Android Auto
  head unit emulator** (~100 classes, AA protobuf + TLS), which is a candidate answer for T-22.
  Also found a real bug our source drop inherited: the `<queries>` CarStream package was
  `maps.jaoloonda.android` (typo) — corrected to `maps.jaoolonda.android`. Opened T-07.
- **2026-08-20** — **The Android Auto mechanism is recovered** by decompiling the v2.1 release APK
  (`gh release download v2.1` → baksmali → filtered dex → jadx). It is **installer attribution**,
  not APK patching: a Shizuku `pm install-create -i com.android.vending` session. Upstream's
  repackaging/re-signing chain is dead code — stamp injection needs `apktool` on `$PATH`, and the
  bundled `apksigner.jar` has no `classes.dex` (verified: `dalvikvm -cp` → SIGABRT). Consequences:
  BouncyCastle dropped, T-06 is now ordinary work, T-12 closed, and upstream's real catalog gives
  T-15 its package names. Full spec: [docs/aa-visibility.md](docs/aa-visibility.md).
  Note upstream is at v2.8.5 while the published source is v2.1 — seven versions of unexamined drift.
- **2026-08-20** — **First APK built on-device.** 11 min clean, `mergeExtDexDebug` and dependency
  downloads dominate. Two failures on the way, both from
  `com.github.iGio90:BottomDialogs:master-SNAPSHOT` (T-19): a JitPack read timeout, then
  `com.android.support:support-compat:27.0.2` colliding with `androidx.core` in
  `checkDebugDuplicateClasses`. Also fixed a bug in `build-on-termux.sh`: `RELEASE_KEYSTORE` is
  exported device-wide for another project, which was pushing *debug* builds onto the slow
  distribution path (no daemon, no cache). That path is now release-only.
- **2026-08-20** — **aapt2 is the one real Termux trap.** AGP's Maven aapt2 is x86_64 and cannot
  run here. `$PREFIX/bin/aapt2` is *not* native — it is a `qemu-x86_64` wrapper. The native
  aarch64 binary on this device is `~/git/Embeddy/tools/aapt2-arm64/aapt2`, which
  `~/.gradle/gradle.properties` already sets device-globally (its "qemu-wrapped" comment is
  wrong). `build-on-termux.sh` probes for a native binary and passes an explicit `-P` override.
