# AAAD (fork) — Agent Guide

Entry point for any agent working in this repo. Read this first, then the doc you need
from the index below.

## What this repo is

A personal fork of [`shmykelsa/AAAD`](https://github.com/shmykelsa/AAAD) (Android Auto Apps
Downloader) — an Android app that downloads third-party Android Auto APKs and installs them
so Android Auto will list them, without root.

**Fork point:** `0c33a2b` (upstream `main`, 2026-02-16). Every commit in history is upstream's;
nothing has been forked-off yet. Upstream `versionName` 2.1 / `versionCode` 18.

**Upstream publishes a *partial* source drop, not the app.** Of the 12 components declared in
`AndroidManifest.xml`, only 4 have source here. The whole "new" Material You UI, the catalog
layer, the auth manager, the installer, and the download-authorization client are absent, along
with all Gradle scaffolding. See [Buildability](#buildability-read-before-you-try-to-build).

## Fork intent

Three goals, in priority order. Detail and task breakdown in [TASKS.md](TASKS.md).

1. **A buildable, de-gated personal build.** Restore the missing scaffolding + classes so the
   app compiles from this tree, with the PRO/quota gate cut out and the Firebase/Stripe backend
   dependency made optional. Personal-use modification only.
2. **An Android Auto app testing platform.** Turn the app + a host-side (Termux) harness into a
   repeatable way to install, launch, screenshot, and regression-check AA third-party apps
   across devices — the thing the upstream app does by hand, driven by `adb` instead.
3. **A Claude/agent dashboard.** The `../operad` equivalent, scoped to Android Auto work:
   a Termux-hosted web dash over the test harness (device inventory, catalog state, install
   matrix, run history, agent task queue).

## Buildability (read before you try to build)

This tree **does not compile as checked out.** Do not report a build failure as a bug until the
gaps below are closed — they are the starting condition, not a regression.

Missing build scaffolding (all absent from git):
`settings.gradle` · `gradlew` + `gradle/wrapper/` · `gradle.properties` · `local.properties`
(required — `app/build.gradle:47` reads it unconditionally) · `app/google-services.json`
(required by the `com.google.gms.google-services` plugin) · `app/proguard-rules.pro`
(referenced at `app/build.gradle:110`).

Missing source — declared in `AndroidManifest.xml`, no file in tree:
`LauncherActivity` · `MainActivityNew` · `OnboardingActivity` · `OnboardingActivityNew` ·
`ProVersionActivity` · `LicenseTransferActivity` · `SupportActivity` ·
`AndroidAutoSetupActivity` · `receivers.PackageInstallReceiver`

Missing source — referenced by the published classes, no file in tree:
`managers.AuthManager` (`EnterProCode.java:20`, `TransferLicense.java:33`,
`AboutPaymentActivity.kt:75`) · `utils.Logger` (`AboutPaymentActivity.kt:19`) ·
`utils.applyBottomInsetPadding` (`AboutPaymentActivity.kt:20`).

The `res/` tree, by contrast, is **complete for the published classes** — every layout id and
string they reference exists. Resources for the missing activities are largely present too
(strings, styles, drawables), which is the best available specification of what those classes did.

Recovery options and the exact `local.properties` key list: [docs/build-setup.md](docs/build-setup.md).

## Repo map

```
build.gradle                 Root Gradle: AGP 8.13.1, Kotlin 2.2.21, google-services 4.4.3
app/build.gradle             Module: compileSdk 36, minSdk 24, appId sksa.aa.customapps,
                             15 buildConfigField secrets sourced from local.properties
app/src/main/
  AndroidManifest.xml        12 app components, 14 permissions, <queries> for the AA catalog
  java/com/legs/appsforaa/
    AboutPaymentActivity.kt  PRO purchase screen — Stripe PaymentSheet + Cloud Function
    EnterProCode.java        Promo-code redemption against RTDB `pc/`
    TransferLicense.java     Legacy QR license transfer (superseded by LicenseTransferActivity)
    AboutDialog.java         About / privacy dialog; leaks the device id into the UI
    User.java                Vestigial 12-line stub, unused
    utils/
      BottomDialog.java      Vendored fork of iGio90/BottomDialogs
      UtilsLibrary.java      dp→px + button drawable helpers for BottomDialog
      Version.java           Dotted-version comparator used by the update checker
  res/                       Complete for published classes; 30 locales via Crowdin
```

Historical source worth knowing about: **`b5198bb` ("Source code version 1.3", 2021-05-12) still
contains `MainActivity.java` (1641 lines), `Downloader.java`, `GitHubDownloader.java`, and
`ContactDialog.java`.** That commit is the only complete, readable implementation of the download
gate anywhere in this repo, and it is the primary evidence behind
[ARCHITECTURE.md § Download gating](ARCHITECTURE.md#7-download-gating-via-pro-subscription).
Read it with `git show b5198bb:app/src/main/java/com/legs/appsforaa/MainActivity.java`.

## Docs index

| Doc | What it covers |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Current architecture spec + the exact PRO/quota gating mechanism |
| [TASKS.md](TASKS.md) | Prioritized backlog. `go` = take the next unchecked task |
| [docs/build-setup.md](docs/build-setup.md) | Getting from this partial tree to a compiling APK |
| [docs/testing-harness.md](docs/testing-harness.md) | Android Auto app testing platform design |
| [docs/agent-dash.md](docs/agent-dash.md) | Claude/agent dashboard design |

## Working agreements

- **Never commit secrets.** `local.properties`, `google-services.json`, and any keystore are
  gitignored. The 15 `buildConfigField` values include the Stripe publishable key, price ids, and
  the Firebase instance/project/region. If you need them in a doc, use placeholders.
- **Never touch the upstream backend from a dev build.** Upstream's Firebase RTDB is a live
  production database holding other people's license state. Writes from a test build corrupt real
  users' entitlements. Dev builds must point at a stub/local backend or at nothing —
  see [ARCHITECTURE.md § Cut points](ARCHITECTURE.md#10-cut-points-for-a-de-gated-dev-build).
- **Verified vs inferred.** ARCHITECTURE.md marks every claim. When you learn something new about
  the missing classes (from a decompile, a log, or a runtime probe), move the claim from inferred
  to verified and cite the evidence. Do not silently upgrade a guess.
- **Personal use only.** `LICENSE.md` is MIT (since `b374904`, 2025-12-06). `README.md`'s License
  section still asserts the older restrictive EULA — no redistribution of modified builds. These
  two conflict; this fork stays private and personal, which is fine under either reading. Do not
  publish builds or reason about which text wins.
- **Commits:** conventional commits, signed with an em-dash + model version, no `Claude` mention,
  no co-authored-by trailer. Never push, tag, or release without explicit per-instance permission.
- **ADB rules for this device** (port rotation, the banned `stop; start`, screenshot size limits)
  live in `~/.claude/CLAUDE.md`. They apply here — this repo drives a phone constantly.

## Commands

None of these work until [TASKS.md](TASKS.md) Phase 1 lands (see
[docs/build-setup.md](docs/build-setup.md)). Recorded here so there is one place to update.

```bash
./gradlew :app:assembleDebug          # build
./gradlew :app:lint                   # lint (abortOnError is false — read the report)
adb install -r app/build/outputs/apk/debug/AAAD-2.1-debug.apk
adb shell am start -n sksa.aa.customapps/com.legs.appsforaa.LauncherActivity
adb logcat -s AAAD:V AboutPaymentActivity:V Stripe:V Firebase:V
```

## Glossary

| Term | Meaning |
| --- | --- |
| `deviceId` | The gate's identity key. `ANDROID_ID` in v1.3; a Firebase Anonymous Auth UID in 2.1 |
| PRO | Lifetime unlimited-download entitlement. One boolean at RTDB `users/<deviceId>` |
| Free quota | 1 download per ~30.44 days, tracked at RTDB `lastdownload/<deviceId>` |
| `pc/` | RTDB node of unredeemed promo codes; a code is deleted on redemption |
| Catalog | The remote list of installable AA apps, fetched from `APP_CATALOG_URL` |
| Shizuku | Optional privileged bridge enabling silent installs on Android 14+ without root |
