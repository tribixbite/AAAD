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
- [x] **T-08** First-run setup. `OnboardingActivity` is **one scrolling screen of live status**,
  not upstream's multi-page pager: a pager makes the user read advice that may not apply to their
  device, whereas this shows each item's real state and only asks for what is missing.
  It closes a genuine gap — nothing in the app had ever asked for permission to install packages,
  so a user without Shizuku hit the system installer and got a refusal with no explanation.
  `canRequestPackageInstalls` is a Settings toggle rather than a runtime permission, so the screen
  links to it instead of pretending a dialog exists, with a fallback for OEM builds that lack the
  per-app screen. `LauncherActivity` routes first run here via `data/OnboardingStore`.
  Verified live: first run → onboarding (install permission showing GRANT, Shizuku showing
  "Ready to use" with no button), Continue → catalog, relaunch → straight to catalog.
  Also replaced upstream's welcome copy, which said "Swipe through…" and described a pager that
  no longer exists.
  *Not wired:* re-entry after dismissal. Everything on the screen is also in Diagnostics, and the
  two actionable items surface where they actually block something. Use `onboarding_welcome_summary`
  and add a button if that changes.
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
- [~] **T-15** Build the real catalog: `app/src/main/assets/catalog.json` per the schema in
  [standalone.md](docs/standalone.md#catalog-format). Upstream's own catalog (14 apps, with
  package names and categories) is recovered in
  [aa-visibility.md](docs/aa-visibility.md#also-recovered) — use it for structure and package
  names, which are now all resolved. **Do not copy its `download_url`s**: they point at upstream's
  Firebase Storage bucket with embedded access tokens. Establish each `source` from the
  publisher's own release page instead, and do not invent URLs to fill gaps.
  For the mirroring family: T-07 established that renaming adds no AA capability, so catalogue
  them from **publisher** sources under their real package names and mark AA visibility unverified.
  Do not copy upstream's renamed builds — they are signed with the public AOSP test key.
  **Progress 2026-08-22:** CarStream added from its actual publisher,
  `thekirankumar/carstream-android-auto`, package **`com.google.android.kk`** (read from the
  released APK, not guessed — the publisher already disguises it as a Google package, and upstream
  renames it again to `maps.jaoolonda.android`). Verified end to end through the harness:
  `NEW carstream — -> attributed/true`, `installer=com.android.vending`, and the scanner
  independently confirmed its Android Auto metadata (AA-capable count 9 -> 10). Catalog is now 7.
  **The remaining four have no publisher release to point at:** `slashmax/AAMirror` (542 stars) is
  source-only with no releases, `aahacks/Screen2Auto` has none, Screen2Auto proper ships from
  inceptive.ru, and AAStream / AA Mirror Plus are not on GitHub at all. They stay out of the
  catalog rather than being given an invented or upstream-bucket URL. Options if they are wanted:
  build from source, add a `manual` source type entry pointing at the publisher's page, or accept
  upstream's AOSP-test-key builds knowingly.
  **Progress 2026-08-23:** **Screen2Auto added** as a `manual` entry, verified from the
  publisher's own page rather than assumed: `inceptive.ru/projects/s2a` is the official project,
  the package is `ru.inceptive.screentwoauto`, and the download page offers versions with no
  direct APK link — which is precisely what the `manual` source type is for. Catalog is now 8.
  The app no longer routes a manual entry through the installer just to fail with "must be
  downloaded from its website"; it opens the publisher's page after saying that the resulting
  download installs through Android, not through AAAD, and so needs converting afterwards.
  Verified on device. The remaining three (AAMirror, AAStream, AA Mirror Plus) still have no
  publisher release anywhere and stay out.
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
- [x] **T-17** Self-update against this fork's own GitHub releases. Replaces upstream's update
  check, which pointed at *upstream's* releases — wrong for a fork, since it offers the user a
  different app. `data/SelfUpdateChecker` targets `BuildConfig.UPDATE_REPO`
  (default `tribixbite/AAAD`, blank disables the check entirely).

  Not built on `utils/Version.java` as originally planned: that comparator *throws* on any version
  it cannot parse as pure digits-and-dots, which is most real-world tags. `utils/VersionCompare`
  already returns null for versions it cannot confidently order, and an update prompt the user
  cannot verify is worse than no prompt, so unknown is treated as up to date. `Version.java` had no
  remaining references and is deleted.

  Two deliberate limits. **Stable releases only:** CI publishes a `dev-<sha>` prerelease on every
  push and offering those would fire several times a day; GitHub's `releases/latest` already
  excludes prereleases. **Only when asked:** no timer, no worker — an app that phones home on a
  schedule is what this fork removed.

  It reuses `InstallManager` via a synthetic `AppEntry` rather than growing a second
  download-and-install path, and it reports `alongside=true` when the published build carries a
  different applicationId than the running one, because a `.dev` build cannot be replaced by a
  release build — it installs beside it, and two AAAD icons is a bad way to learn that.

  Lives on the diagnostics screen, not the catalog: updating AAAD and updating the apps AAAD
  installs are different actions, and one screen offering both is how people update the wrong one.

  `ReleaseResolver` gained `NoReleaseException` so "nothing published yet" stops being reported as
  a failure. GitHub answers 404 for both "no non-prerelease release" and "no such repo", so a 404
  now costs one extra request to tell those apart — the difference between "not published yet" and
  "you typed the name wrong".

  *Verified on device* through `DEBUG_UPDATE_CHECK`: default → `UPDATE_NONE` (this fork has no
  tagged release yet); `--es version 0.1 --es repo AndreyPavlenko/Fermata` → `UPDATE_AVAILABLE
  version=2.0.2 alongside=true`; `--es version 99.0` → `UPDATE_CURRENT`; a nonexistent repo →
  `UPDATE_FAILED Repository ... not found`. The blank-repo branch is unreachable over adb (the
  device shell drops a whitespace-only argument) and is covered by `SelfUpdateCheckerTest`.

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
- [x] **T-25** Tests. `harness/src/baseline.test.ts` covers the comparator (8 tests, `bun test`) —
  it decides whether a run is called a regression, so a false green hides the exact breakage the
  harness exists to catch and a false red trains people to ignore it. It is pure logic, so there
  was no excuse. Cases include the two that matter most: an outcome change that *keeps* attribution
  must not read as broken, and a repeated failure must not masquerade as new breakage.
  App side: `VersionCompareTest` (7 tests, `./gradlew :app:testDebugUnitTest`) using the real
  version strings this catalog's publishers actually emit, not invented ones — including the two
  that must return null rather than a guess.

## Phase 4 — Agent dash

Design: [docs/agent-dash.md](docs/agent-dash.md).

- [x] **T-30** `dash/` — a bun HTTP server on `127.0.0.1:18980` (loopback only; no auth, no
  business being reachable from the network) with a dark, mobile-first single page, everything
  inlined so it works with no network at all. Live device inventory via the harness's own `adb.ts`
  rather than a second implementation.
- [x] **T-31** Run history: every `harness/runs/*/results.jsonl` rendered newest-first with its
  screenshots inline, plus recorded baselines. The headline figure per run is **Play-attributed
  count**, because that is the assertion the harness can actually make — AA visibility is shown as
  `unknown` rather than implied. A truncated final JSONL line (interrupted run) is skipped instead
  of failing the page.
  *Still to add:* per-app timeline across runs (T-33's catalog view would pair with it).
- [x] **T-32** Task queue view backed by this file. `harness/src/tasks.ts` parses `TASKS.md`
  into phases with a done/total rollup; the dash renders open items first within each phase, done
  ones dimmed rather than hidden, since the ratio is the useful part.

  Parsing the prose rather than keeping a machine-readable copy alongside it means the two can
  never disagree. Only the checkbox line is read — every task here carries paragraphs of rationale
  beneath it, and treating those as rows would bury the list — and inline markdown is stripped,
  because the dash renders text and would otherwise show backticks and link syntax verbatim.
- [x] **T-33** Catalog inspector: catalog vs. installed state per device, update deltas.
  `harness/src/inventory.ts` reads each device in **one** `adb shell` call rather than one per
  package (seven round trips over wifi is visible lag on a page that refreshes every 15 s), and
  `harness/src/releases.ts` resolves published versions with a one-hour disk cache — GitHub allows
  60 unauthenticated requests per hour and the catalog has seven entries, so an uncached panel
  would exhaust the limit in eight refreshes and then report failures unrelated to the devices.
  The `refresh` button forces a lookup; page loads never do.

  `updateAvailable` is deliberately tri-state. `null` means the versions cannot be ordered and the
  cell shows no badge — CarStream publishes `untagged-<hash>`, which cannot be honestly compared
  to `2.0.0`. This required mirroring the app's `VersionCompare` as `harness/src/version.ts`;
  `version.test.ts` holds both to the same examples so the dash and the app can never disagree
  about whether an app needs updating.

  *Verified live against the Saga:* 3/7 installed, 1 unattributed; Widgets 0.2.2 → 0.2.3 badged
  with its installer shown as `none` in red (the fixture left installed for exactly this);
  Nav2Contacts 1.0.3 = 1.0.3, attributed; CarStream installed and attributed but with **no** update
  badge because its published tag is an untagged hash. Layout re-checked at a true 412 px viewport
  through CDP (`Emulation.setDeviceMetricsOverride`, since `--window-size` only crops the
  screenshot): `scrollWidth === innerWidth`, no sideways body scroll — the table overflows only
  inside its own `.scroll-x` box, as intended.
- [x] **T-34** Decided: the dash **stays a separate service**, recorded with its reasoning in
  [docs/agent-dash.md](docs/agent-dash.md#relationship-to-operad-t-34). It turned out to be a
  reader with no shared state, the coupling would be one-directional, and the duplication a plugin
  would remove is a single device-listing function the harness needs anyway to run headless in CI.
  What is shared instead is data: every panel has a plain JSON endpoint operad can poll.

## Phase 5 — App changes worth making

- [x] **T-41** Shizuku-first install path with a clean fallback, so harness runs are unattended.
  `InstallManager` already preferred Shizuku and fell back; what was missing was the *unattended*
  half. The fallback opens the system installer, which needs a person to tap Confirm — during a
  harness run nobody does, so the run left a dialog sitting on the device and reported
  `SYSTEM_INSTALLER` for an install that never happened.

  `install(entry, allowSystemFallback)` now defaults to true for anything a person is watching and
  is **false** from `DebugAutomationReceiver`, which yields the new `Outcome.NeedsShizuku`.
  It is a distinct outcome rather than a `Failed`, because nothing is broken: the run cannot
  proceed until Shizuku is started, and that needs a different response from a download error.

  Also extracted `parseResultLine` in `harness/src/app.ts` and covered it with `app.test.ts`. That
  was not incidental — a `RESULT=` the receiver logs but the harness does not recognise matches no
  branch, so the poll spins to its five-minute timeout and looks like a hung install rather than
  an unhandled case. Adding `NEEDS_SHIZUKU` would have introduced exactly that bug.

  *Verified on device:* an unattended `DEBUG_INSTALL` with Shizuku ready still reports
  `RESULT=ATTRIBUTED`, and a full `cli.ts matrix --apps carstream` run through the refactored poll
  reports `attributed (installer=com.android.vending)`.
  *Not exercised on device:* the `NeedsShizuku` branch itself, which would mean stopping Shizuku on
  the test phone and leaving it stopped. It is covered by `app.test.ts` at the parser level and by
  the compiler's exhaustiveness check at the call sites.
- [x] **T-42** Local catalog override (a file on the device) so unlisted APKs can be tested
  without editing the bundled catalog. `CatalogRepository` reads
  `/sdcard/Android/data/<applicationId>/files/catalog.json`, which outranks both the bundled and
  the remote catalog — precedence weakest-first is bundled < remote < device override, each layer
  something a person chose more deliberately than the last.

  That directory is the point: `adb push` writes it with **no** storage permission, no
  `MANAGE_EXTERNAL_STORAGE` and no root. The one wrinkle is that adb cannot *create* a package's
  directory under `Android/data` (`secure_mkdirs failed: Operation not permitted`), so the app has
  to have run once — it creates the directory itself when it first loads a catalog. Documented in
  `docs/testing-harness.md`.

  It replaces rather than merges, because a run that asks for three apps should get three. A
  malformed override is logged and ignored rather than fatal — the fix is to push a corrected
  file, and an app that will not start is a poor way to report a typo. The catalog screen says
  "Using a catalog pushed to this device" so an unexpected list explains itself.

  *Verified on device:* pushing a two-app override took `catalogApps` from 7 to 2 and showed the
  banner; a deliberately malformed file logged
  `Override ... is unparseable or targets an unsupported schema; ignoring it` and fell back to 7;
  deleting the file restored the shipped catalog. Adding `Origin.DEVICE_OVERRIDE` also made the
  compiler flag the non-exhaustive `when` in `MainActivityNew`, which is the enum earning its keep.
- [x] **T-43** Structured logging to a file the harness can `adb pull`. `utils/LogFile` mirrors
  every `Logger` call to `aaad-log.jsonl` in the app's external files directory — the same place
  the catalog override lives, so `adb pull` reaches it with no permission at all.

  It exists because logcat is a **device-wide ring buffer**: a slow install or a chatty system
  service can evict this app's lines before anything reads them, and a run that lost its verdict
  that way is indistinguishable from a run that failed. JSONL because it is appended from many
  threads and read after the fact — a torn final line costs one record instead of the file.

  Writing is single-writer with a queue rather than a lock, so logging never becomes something the
  install path waits on, and lines logged before the sink is installed are kept and flushed. Both
  entry points install it: `LauncherActivity` for cold starts and `DebugAutomationReceiver` for
  harness runs, which have no Activity at all.

  `cli.ts logs [--level W]` pulls and prints it. *Verified on device* end to end.

- [x] **T-49** **Convert over adb, with no Shizuku.** `harness/src/attribute.ts` +
  `cli.ts convert` perform the same attributed session the app does — create declaring the Play
  Store, stream every APK the package owns, commit — straight from the host.

  This exists because Shizuku is **not dependable on the host device**. `shizuku_server` runs as a
  child of `adbd`, and this box's wireless-debugging port rotates (see `~/.claude/CLAUDE.md`), so
  every rotation restarts adbd and takes Shizuku with it. Observed repeatedly: server up, then gone
  minutes later with only read-only commands in between. Since Shizuku *is* the shell uid, adb can
  do everything it can, and this path has nothing to lose.

  ```bash
  bun run src/cli.ts convert --packages com.foo,com.bar   # any package
  bun run src/cli.ts convert --unattributed aa            # every unattributed catalog app
  ```

  Splits are handled — every APK is re-staged, since a session holding only the base of a split
  app either fails to commit or yields an app missing its resources — and `--bypass-low-target-sdk-block`
  is added from API 34.

  *Verified:* Calculator on the host, `com.sec.android.app.samsungapps` → `com.android.vending`;
  Aurora Store on the Saga, `com.google.android.packageinstaller` → `com.android.vending`; and
  `--unattributed aa` caught Nav2Contacts sitting at `sksa.aa.customapps.dev`, i.e. an app AAAD's
  own unattributed fallback had installed and Android Auto was never going to list.

- [x] **T-50** **Stop reporting "Shizuku is not running" when it is.** Without a binder the app
  cannot distinguish a stopped server from a running one that has not authorised AAAD — Shizuku
  only hands its binder to apps the user has granted, through its own prompt rather than the
  Android permission. Confirmed on device: `shizuku_server` running as shell, `pingBinder()` still
  false. Naming only the first sends people to restart a service that is already up, so the copy
  now names both possibilities and points at the one action that fixes either.

- [x] **T-48** **Convert any installed app, not just Android-Auto-capable ones.** The convert
  screen listed only apps declaring the AA metadata key — 29 of 774 on a real phone — so the
  hundreds of others could not be converted at all. `InstalledAppScanner.scan(scope = ScanScope.ALL)`
  now returns every app, with a scope toggle and a search box, because a 774-row list without a
  filter is not usable.

  Each row states what conversion will actually achieve. Converting an app with no AA metadata
  fixes its installer but will **not** put it in Android Auto, and saying so on the row is the
  difference between a fixed expectation and a bug report. The same caveat is repeated in the
  confirmation dialog, which also now warns that **the app is stopped while it reinstalls** — a
  detail that is harmless for most apps and destructive for a terminal, a keyboard or a launcher.

  Scanning 774 apps serially took several seconds, so the per-app installer lookup and label load
  now fan out across the IO pool in chunks: **2298ms** for 774 apps, logged on every scan.

  Also fixed two things this surfaced: `getInstalledPackages` replaces a per-app `getPackageInfo`
  call, and the descriptor read is guarded by the cheap metadata check so several hundred apps
  that declare no car support never have their resources opened. And the debug receiver's
  convert-by-name now scans at ALL scope — it used to answer "not installed" for any app without
  AA metadata.

  *Verified on device:* 774 apps listed in the All scope and 29 in the Android Auto scope on a
  real phone; search narrows to 2; and a non-AA app (HTTP Toolkit) converted end to end,
  `com.google.android.packageinstaller` → `com.android.vending`.

- [x] **T-46** **Detect the "can't use while driving" condition.** `data/AutomotiveDescriptor`
  reads an app's `com.google.android.gms.car.application` XML and reports its `<uses>` set, for
  installed packages and for a downloaded APK before it is committed.

  This exists because attribution and usability are **independent** and were being conflated.
  Auditing all seven catalog apps found two routes to a car surface, not one: `projection`
  (full screen, unofficial SDK — CarStream, Fermata, Performance Monitor, AA Torque, Widgets) and
  `template` (Car App Library — Nav2Contacts). **AABrowser declares `media` only** and is the sole
  catalog app with no surface at all. **Nothing on the phone can grant either** — they are
  statements the app makes about itself. AABrowser already sets `distractionOptimized=true`, which
  marks an Activity safe once a surface exists rather than granting one.

  The audit also caught a bug in this very code before it shipped: the first cut tested
  `!projects`, which wrongly flagged templated Nav2Contacts as blocked. It now tests `hasCarUi`.

  No AABrowser release has ever declared a surface — 2.2, 2.0 and 1.6 are `media` only and 1.3 and
  earlier have no descriptor — so there is no version to pin back to. Full evidence in
  [docs/aa-visibility.md](docs/aa-visibility.md#being-listed-is-not-the-same-as-being-usable-while-driving-v).

  Surfaced in diagnostics (marker `D`, plus each app's `uses=` set) and on the convert row.
  *Not device-verified* — no phone was reachable; the finding is from `aapt2 dump xmltree` on the
  publishers' own APKs, which is stronger evidence than a single device anyway.

- [x] **T-47** **Make the convert screen usable.** It was showing every Android-Auto-capable app
  including Google's own, so on a stock phone ten of eleven rows were preinstalled apps that are
  already attributed and can never need conversion — nine greyed rows burying the one actionable
  one, which reads as broken. `InstalledAppScanner.scan(includeSystemApps = false)` now excludes
  system apps for the convert screen and sorts convertible first; diagnostics and the debug
  receiver pass `true`, because there the complete picture is the point and a convert-by-name
  request should never answer "not found" for an app that exists.

- [x] **T-44** **AAAD's own Android Auto surface — built, and measured as not surfaced.**
  `car/AaadCarAppService` with four screens: a root menu, read-only status, convert and install.
  Every action confirms on its own screen (templates have no dialogs), both list screens ask
  `ConstraintManager` for the driving content limit, and car install refuses the system-installer
  fallback because that dialog appears on the phone where nobody in the driver's seat can answer it.

  **Android Auto does not list it.** Verified with `harness/tools/aa-launcher-list.sh`: the service
  resolves at system level (`cmd package query-services -a androidx.car.app.CarAppService`), the
  app is `installer=com.android.vending`, and it is still absent — under `IOT` and again under
  `POI`. Third-party templated apps are surfaced only in the categories Android Auto approves,
  which in practice are navigation, audio and messaging. An app manager is none of them and cannot
  honestly claim to be one, so this is a policy wall rather than a bug to fix.

  The code and the declaration stay: they cost nothing, they are correct if that policy changes,
  and the screens work on any head unit that does surface the app. Closed as done-and-measured
  rather than left open, because there is nothing further to try that would not be a lie about what
  the app is.

- [x] **T-45** **Carify: a side-by-side, Android-Auto-visible clone of any installed app.**
  `harness/tools/carify.sh` + `patch_manifest.py`, exposed as `cli.ts carify --packages …`.

  The clone is a **different package** (`<pkg>.aaad`), which is what makes re-signing acceptable:
  the original keeps its signature, its data and its publisher updates, and only the clone carries
  a local key. Re-signing is unavoidable — both the manifest and the resource table change — so the
  rewritten app must never *be* the user's copy.

  The clone gains a `com.google.android.gms.car.application` descriptor declaring
  `<uses name="projection"/>`, `distractionOptimized=true` on the application and the launcher
  activity, `android.intent.category.CAR_LAUNCHER`, `resizeableActivity=true`, and no
  `screenOrientation` lock — an orientation lock being the usual reason a phone Activity renders as
  a letterboxed sliver on a landscape head unit.

  **Class names are deliberately not renamed.** The DEX is untouched, so every component
  `android:name` still refers to the original package's classes; only *identifiers* move —
  the manifest package, declared permissions, provider authorities, task affinity — because those
  are exactly what collide with the original install. Confirmed by the running clone:
  `com.sec.android.app.popupcalculator.aaad/com.sec.android.app.popupcalculator.Calculator`.

  Built on **APKEditor**, not apktool: apktool's rebuild goes through `aapt2 compile`, which
  rejects the `$`-prefixed AnimatedVectorDrawable entries Samsung's Calculator contains
  (`resource 'drawable/$avd_show_password__2' has invalid entry name`). APKEditor edits the binary
  resource table directly. Its one requirement is that a new file needs a `<public>` entry or the
  build fails with "Local resource not defined" — the script derives a free id from the app's own
  `xml` type rather than hardcoding one.

  *Verified on two apps, end to end:* Samsung Calculator and Service Browser both installed
  alongside their untouched originals, both Play-attributed, both **launch without crashing**
  (the DEX round-trip was the risk), and AAAD's own scanner counted them —
  `aaCapableInstalled` 35 → 36 → 37.

  **Split apps are handled** by merging every APK into one with `APKEditor m` before patching,
  rather than re-staging N re-signed splits through a single session. Merging is the better trade:
  a cloned split set inherits the original's split layout for no benefit, and any config split it
  lacks — a density, an ABI, a language — becomes a missing-resource crash at runtime.
  *Verified:* Bambu Handy, 5 APKs and 250 MB, merged, cloned, installed alongside the original and
  launched clean.

  **Scope, measured (T-53):** the rewrite repairs an app that is car-capable but mis-declared —
  AABrowser ships car-app code and declared `media`; its clone declares `projection` and Android
  Auto lists it. It does **not** make an arbitrary phone app appear in the car; clones of apps with
  no car implementation are absent from Android Auto's list however they are declared. The script
  says so when nothing backs the declaration.

  Also takes a downloaded APK: `carify.sh --apk foo.apk` needs no device at all, since only the
  install does. The package name is read from the APK rather than passed in.

- [x] **T-51** **Not needed — an ordinary Activity projects fine.** Closed by a head-unit test
  rather than built. The reasoning that opened it was wrong in an instructive way: reading
  CarStream's APK established that it implements the unofficial SDK, and that was written up as
  though the SDK were *required*. Verifying what one app does is not verifying what Android Auto
  demands — an implementation shows a sufficient path, never a necessary one. The claim was tagged
  `[V]` in `docs/aa-visibility.md`, which is exactly the failure that convention exists to prevent;
  the section now records the correction.

- [x] **T-53** **Resolved: declaring `projection` is necessary but not sufficient.** And the way
  it was resolved is the more useful outcome — Android Auto's app list turns out to be readable
  **on the phone**, from gearhead's *Customize launcher* screen
  (`companion.settings.DefaultSettingsActivity` → Display → Customize launcher). Wrapped as
  `harness/tools/aa-launcher-list.sh`. Nothing else exposes it: `dumpsys` says nothing, and a full
  logcat capture during a package change produced **zero** gearhead lines, so the log-scraping tool
  written for this was deleted — its premise was wrong.

  Measured, three clones built identically:

  | clone | in Android Auto's list |
  | --- | --- |
  | **AABrowser (Car)** | **yes** |
  | Calculator (Car) | no |
  | Service Browser (Car) | no |

  All three resolve `MAIN` + `CAR_LAUNCHER`, are `installer=com.android.vending`, enabled, carry
  `distractionOptimized`, and have a resolving descriptor. Adding `androidx.car.app.minCarApiLevel`
  and `androidx.car.app.category.POI` + `APP_MAPS` to the Calculator clone changed nothing.

  The separator is **code**. AABrowser already ships the androidx car-app library and a car entry
  point; its APK merely declared `media`, so Android Auto listed it and refused to open it.
  Calculator and Service Browser have no car implementation, and no manifest edit creates one.
  So `carify` **repairs a mis-declared car app** — which is exactly the case that started this —
  and cannot make an arbitrary phone app appear in the car. The warning removed in
  `6135f1a` was right and is restored.

- [x] **T-52** **Fix AABrowser** — the complaint that started this whole thread. Its APK declares
  `<uses name="media"/>` and nothing else, so Android Auto lists it and refuses to open it while
  driving. `carify.sh --apk` rewrote it into `com.kododake.aabrowser.aaad` declaring `projection`,
  with `distractionOptimized` and `CAR_LAUNCHER`, installable alongside the publisher's build.
  No device was involved in the transformation.


- [x] **T-40** Update checker — the thing upstream's README has promised for years.
  `data/UpdateChecker` resolves the latest published version of each **installed** catalog app and
  `CatalogRepository` turns that into `InstallState.UpdateAvailable`, which the adapter already
  knew how to render but nothing had ever produced.
  Two deliberate limits: only installed apps are checked (resolving one the user does not have
  tells them nothing and still spends GitHub rate limit), and only on an explicit pull-to-refresh
  (a screen that fires a request per app on every draw is how a standalone app quietly becomes a
  chatty one).
  `utils/VersionCompare` **returns null rather than guessing** when either side cannot be ordered —
  this catalog alone contains `v1.0.3`, `beta1.1`, `0.88B` and `untagged-7666cf8b031e67be69d2`.
  A phantom update badge is worse than none, because it teaches people to ignore the badge.
  Verified live: installed Widgets 0.2.2 against published 0.2.3 → **"Update to 0.2.3" + UPDATE**;
  Nav2Contacts 1.0.3 against 1.0.3 → **"Installed: 1.0.3"**; CarStream's untagged release → no
  badge. `UpdateChecker: Resolved 3/3 latest versions` — only the installed three.

## Low priority

- [x] **T-12** Upstream's 2.1 server-side authorization, resolved by decompile: the callable is
  **`requestAuthorizedDownload`**, the client reads an **`authorized`** field, and `users` /
  `lastdownload` remain the RTDB keys. The `"date"` extra's unit is still unconfirmed and not
  worth pursuing — it is a cosmetic countdown and the fork has no gate.
- [x] **T-18** Pruned the unreferenced PRO/payment strings: **1326 entries across 34 locale
  files**, 39 per locale.

  The reason it was deferred — churn for no functional gain — was right, but it also hid a real
  trap that had to be handled to do it safely. 242 strings look unreferenced to a naive search, and
  deleting them would have broken the catalog: `CatalogRepository.descriptionResIdOf` resolves each
  entry's `descriptionRes` **by name** with `getIdentifier`, so every catalog description is
  referenced only from `catalog.json` and appears nowhere as `R.string.*`. The prune therefore
  excludes anything named in the catalog or ending `_description`, and matches payment terms on
  word boundaries — a substring match flags `downloading_progress` because it contains "pro".
  Build and unit tests green, and the app verified running on device afterwards.
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
