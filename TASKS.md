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
  Gradle 8.13 (AGP 8.13.1's minimum), `app/proguard-rules.pro`, `local.properties.example`.
  *Verified:* `./gradlew projects` succeeds on-device and lists `:app`.
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
  `apksigner verify`). Not yet exercised — needs a push, which needs permission.

## Phase 2 — Standalone: no server, no gate

Design and rationale: [docs/standalone.md](docs/standalone.md).

- [x] **T-10** Remove backend dependencies: Firebase BoM + Database/Storage/Auth/Functions,
  Stripe, QRCodeScanner/zxing, `commons-net` (the NTP quota clock), Volley, and the
  `google-services` plugin. Drop the 14 secret `buildConfigField`s.
- [x] **T-11** Remove the paid surface: `AboutPaymentActivity.kt`, `EnterProCode.java`,
  `TransferLicense.java`, `User.java`, their layouts and menu, the five manifest activities, the
  `CAMERA` permission, and the `transfer_license` menu item.
- [ ] **T-04** Reimplement the minimum spine to a running app: `utils/Logger`,
  `utils/applyBottomInsetPadding`, `LauncherActivity`, `MainActivityNew`,
  `receivers/PackageInstallReceiver`; stub the four remaining activities as `finish()` shells.
  *Done when:* `./build-on-termux.sh debug` produces an APK that launches to a catalog screen.
- [ ] **T-15** Build the real catalog: `app/src/main/assets/catalog.json` per the schema in
  [standalone.md](docs/standalone.md#catalog-format). Every `source` URL must be established
  from the publisher's own release page — **do not invent URLs**. Also resolve the four
  unattributed mirroring package names before writing them in.
- [ ] **T-06** On-device APK patching / re-signing (BouncyCastle). This is the app's actual core,
  is unpublished upstream, and is not inferable from this tree. Without it an installed app will
  not appear in Android Auto.
  *Done when:* an app installed by this build is listed by Android Auto on the test device.
- [ ] **T-17** Self-update against this fork's own GitHub releases, using `utils/Version.java`.
  Replaces upstream's update check, which pointed at upstream's releases.

## Phase 3 — Android Auto app testing platform

Design: [docs/testing-harness.md](docs/testing-harness.md). Phase 2 is a hard prerequisite —
a matrix run is impossible against a quota-gated build, and that is now moot.

- [ ] **T-20** `harness/` skeleton (TypeScript, bun): device discovery, adb wrapper with the
  rotating-port rediscovery this box needs, structured run logging to JSONL.
- [ ] **T-21** Catalog-driven install matrix: for each catalog app × each connected device,
  install → launch → screenshot → record result.
- [ ] **T-22** Android Auto visibility probe — the assertion that actually matters. Choose
  between `dumpsys` against `gearhead`, the Desktop Head Unit, and on-device capture, and write
  down why. Until then, report visibility as `unknown` rather than implying success.
- [ ] **T-23** Screenshot pipeline honouring this device's constraints: no dimension ≥ 2000 px,
  file < 4 MB, auto-compress, per-run directory.
- [ ] **T-24** Regression baselines: per-app expected outcomes, diffed each run.
- [ ] **T-25** Instrumented/unit tests for what is worth pinning: `Version` comparator, catalog
  parsing, install-state detection.

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

- [ ] **T-12** Resolve the three open questions about upstream's 2.1 server-side authorization
  ([ARCHITECTURE.md § 7.3](ARCHITECTURE.md#73-v21--what-changed)): callable function name and
  payload, whether `lastdownload` survives as the storage key, and the unit of the `"date"`
  extra. Documentation completeness only — the fork has no gate. Fold into T-04's decompile pass
  if that happens anyway.
- [ ] **T-18** Prune the now-unreferenced PRO/payment strings across `res/values*`. Deliberately
  deferred: it churns 30 locale files for no functional gain.
- [ ] **T-19** Vendor BottomDialogs and drop the JitPack dependency. It has now broken the build
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
  Do this if T-04 keeps using BottomDialog; delete both files if it does not.
  *Done when:* the build resolves no JitPack artifact and `settings.gradle` drops the repo.

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
- **2026-08-20** — **aapt2 is the one real Termux trap.** AGP's Maven aapt2 is x86_64 and cannot
  run here. `$PREFIX/bin/aapt2` is *not* native — it is a `qemu-x86_64` wrapper. The native
  aarch64 binary on this device is `~/git/Embeddy/tools/aapt2-arm64/aapt2`, which
  `~/.gradle/gradle.properties` already sets device-globally (its "qemu-wrapped" comment is
  wrong). `build-on-termux.sh` probes for a native binary and passes an explicit `-P` override.
