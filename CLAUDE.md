# AAAD (fork) — Agent Guide

Entry point for any agent working in this repo. Read this first, then the doc you need
from the index below.

## What this repo is

A personal, **standalone** fork of [`shmykelsa/AAAD`](https://github.com/shmykelsa/AAAD)
(Android Auto Apps Downloader) — an Android app that downloads third-party Android Auto APKs and
installs them so Android Auto will list them, without root.

**Fork point:** `0c33a2b` (upstream `main`, 2026-02-16). Upstream `versionName` 2.1 /
`versionCode` 18.

**Standalone means: no server, no accounts, no entitlement, no quota, no payment.** Upstream's
Firebase + Stripe backend has been removed from this tree, not flag-gated. The app's only network
traffic is fetching app metadata (optional) and downloading APKs from their publishers.
Rationale and the full diff: [docs/standalone.md](docs/standalone.md).

**Upstream publishes a partial source drop, not the app.** Nine manifest components and two
utility symbols still have no source here. See [Buildability](#buildability).

## Fork intent

Three goals, in priority order. Task breakdown in [TASKS.md](TASKS.md).

1. **A buildable standalone build.** Backend removed (done); missing classes reimplemented so the
   app compiles and runs from this tree. Personal use only.
2. **An Android Auto app testing platform.** The app plus a host-side (Termux) harness that
   installs, launches, screenshots, and regression-checks AA third-party apps across devices —
   what upstream does by hand, driven by `adb` instead.
3. **A Claude/agent dashboard.** The `../operad` equivalent, scoped to Android Auto work: a
   Termux-hosted web dash over the harness (device inventory, catalog state, install matrix,
   run history, task queue).

## Buildability

**The build works.** `./build-on-termux.sh debug --no-install` produces a 23 MB
`AAAD-2.1-debug.apk` on-device — verified 2026-08-24 (Gradle 8.13 + AGP 8.13.1, package
`sksa.aa.customapps.dev`, targetSdk 36, debug-signed). Building needs **no secrets**: no
`google-services.json`, no Stripe keys, and `local.properties` is optional.

**The app runs and does its job.** `LauncherActivity` → `MainActivityNew` shows the catalog,
resolves install state, and refreshes on package changes. Three flows work:

- **Install** — resolve a publisher's latest GitHub release, download, verify its car surface, and
  install it unattended through Shizuku or interactively through Android (T-06).
- **Convert** — register an app's existing car version, or create a side-by-side car-compatible
  copy for any installed app. Work is queued, cancellable, and reports stage progress (T-55).
- **Discover** — search GitHub or paste a repo URL to add apps, Obtainium-style (T-31a).

Shizuku is an optional unattended-install bridge. It does not confer trusted-store admission;
everything it cannot do falls back to Android's confirmation installer.

Still unimplemented: onboarding (T-08), support and the AA setup guide (T-09). Their manifest
entries are **omitted, not stubbed** — a declared component with no class is a crash waiting for
whoever first routes to it. Re-add each entry alongside its implementation.

The `res/` tree is complete, and upstream's **full class list and behaviour are now recovered**
from a decompile of the v2.1 release APK — see
[docs/aa-visibility.md](docs/aa-visibility.md#upstreams-full-class-list). Reimplementing them is
ordinary work, not archaeology.

To redo or extend the decompile:

```bash
gh release download v2.1 -R shmykelsa/AAAD        # v2.1 matches this repo's source drop
unzip -q AAAD-2.1-release.apk -d x21              # app code is in classes3.dex
java -jar ~/git/termux-tools/edge-fix/tools/baksmali-3.0.9-fat.jar d x21/classes3.dex -o smali3
# copy just com/legs/appsforaa/** into mini/, then:
java -jar ~/git/termux-tools/edge-fix/tools/smali-3.0.9-fat.jar a mini -o mini.dex
jadx -d java --no-res --show-bad-code mini.dex    # pacman -S jadx
```

Filtering to a mini dex first is what makes this fast — jadx on all five dex files is not worth
the wait. Notes: `~/git/termux-tools/docs/APKTOOL_TERMUX.md`,
`~/git/termux-tools/.claude/skills/smali-dex-patching.md`.

## Building

```bash
./build-on-termux.sh                    # debug; installs if a device is connected
./build-on-termux.sh debug --no-install
./build-on-termux.sh release            # debug-signed unless RELEASE_KEYSTORE is set
./build-on-termux.sh --clean --low-mem
```

**Do not call `./gradlew assembleDebug` directly on this device.** AGP resolves an `aapt2` from
Maven that is an x86_64 glibc binary and cannot execute on Android ARM64. The script passes
`-Pandroid.aapt2FromMavenOverride=<path>` after probing for a usable aapt2. Note that
`$PREFIX/bin/aapt2` is **not** native — it is a `qemu-x86_64` wrapper; the native aarch64 binary
on this device is `~/git/Embeddy/tools/aapt2-arm64/aapt2`. Details:
[docs/build-setup.md](docs/build-setup.md).

CI (`.github/workflows/`) runs on x86_64 Linux where the stock aapt2 works, so no override there.

## Repo map

```
build.gradle                 Plugin versions only (AGP 8.13.1, Kotlin 2.2.21)
settings.gradle              Google/Maven Central repositories; includes :app and :carify-bridge
gradle.properties            On-device memory/worker tuning + reproducibility flags
build-on-termux.sh           The supported local build path
local.properties.example     Every key optional; documents the few that exist
.github/workflows/           build-apk.yml (main push → latest dev release) · release.yml (tag → signed)
app/build.gradle             compileSdk 36, minSdk 24, appId sksa.aa.customapps (.dev on debug)
app/src/main/
  AndroidManifest.xml        2 activities, 13 permissions, <queries> = the catalog packages
  assets/catalog.json        The bundled catalog — 6 apps, publisher GitHub releases only
  java/com/legs/appsforaa/
    LauncherActivity.kt      MAIN/LAUNCHER routing seam; onboarding check belongs here (T-08)
    MainActivityNew.kt       The catalog screen
    ConvertActivity.kt       Fix installed apps AA ignores (reinstall with attribution)
    DiscoverActivity.kt      Find apps on GitHub, Obtainium-style
    AboutDialog.java         About / privacy dialog (upstream, not yet rewired)
    adapters/
      AppListAdapter.kt      ListAdapter + DiffUtil over AppListItem
      InstalledAppAdapter.kt Installed AA-capable apps + their conversion state
      RepoAdapter.kt         GitHub search results
    data/
      CatalogModels.kt       AppEntry / AppSource / Catalog / InstallState, org.json parsing
      CatalogRepository.kt   Bundled asset + optional CATALOG_URL + user entries; install state
      UserCatalogStore.kt    User-added entries, same JSON shape as the bundled catalog
      ReleaseResolver.kt     Catalog entry → concrete APK via GitHub releases
      GitHubSearch.kt        Repo search + "owner/repo or URL" parsing
      InstalledAppScanner.kt Installed AA-capable apps and whether AA will list them
    receivers/
      PackageInstallReceiver.kt  Runtime-registered ONLY — see its class doc
    utils/
      Logger.kt              Facade; all tags prefixed AAAD/ for `adb logcat -s AAAD:V`
      ViewExtensions.kt      applyTopInsetPadding / applyBottomInsetPadding
      InstallManager.kt      resolve → download → install, picks the path per attempt
      ShizukuInstaller.kt    The Play-attributed session install, and conversion
      SystemInstaller.kt     Fallback; explicitly NOT equivalent (no attribution)
      ApkDownloader.kt       Cancellable download with progress
      BottomDialog.java      Vendored fork of iGio90/BottomDialogs (unused; see T-19)
      UtilsLibrary.java      dp→px + button drawable helpers for BottomDialog
      Version.java           Dotted-version comparator for update checks
  res/                       Complete; 30 locales via Crowdin
```

Two commits are worth knowing about:

- **`b5198bb`** ("Source code version 1.3", 2021-05-12) still contains `MainActivity.java`
  (1641 lines), `Downloader.java`, `GitHubDownloader.java`. It is the only complete, readable
  implementation of the download gate anywhere in this repo and the primary evidence behind
  [ARCHITECTURE.md § 7](ARCHITECTURE.md#7-download-gating-via-pro-subscription).
  `git show b5198bb:app/src/main/java/com/legs/appsforaa/MainActivity.java`
- **`0c33a2b`** — the fork point. `git show 0c33a2b:app/src/main/java/com/legs/appsforaa/AboutPaymentActivity.kt`
  and its siblings recover the payment/license classes this fork deleted.

## Docs index

| Doc | What it covers |
| --- | --- |
| [docs/aa-visibility.md](docs/aa-visibility.md) | **How an app becomes visible to Android Auto** — the core mechanism, recovered by decompiling the v2.1 release APK. Read before touching install code |
| [docs/upstream-2.8.5-diff.md](docs/upstream-2.8.5-diff.md) | What upstream changed since the source drop: working in-process signing, on-device package renaming, an install strategy ladder, and an in-app AA head unit emulator |
| [docs/standalone.md](docs/standalone.md) | The no-server design: what was removed, catalog format, behavioural diff |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Upstream 2.1 architecture + the exact PRO/quota gating mechanism; §10 is this fork's divergence |
| [TASKS.md](TASKS.md) | Prioritized backlog + decision log. `go` = take the next unchecked task |
| [docs/build-setup.md](docs/build-setup.md) | Local Termux build, CI/CD, signing, recovering the missing classes |
| [docs/testing-harness.md](docs/testing-harness.md) | Android Auto app testing platform design |
| [docs/aa-visibility.md § projection](docs/aa-visibility.md#an-ordinary-activity-can-never-be-projected-v) | **Read before touching car code**: what makes an app openable in the car, and the corrected claim about it |
| [docs/agent-dash.md](docs/agent-dash.md) | Claude/agent dashboard design |

## Host-side tools (`harness/`)

Everything here runs on this box against a connected phone. Shizuku is **not** required for any of
it — Shizuku runs as the shell uid, which is exactly what `adb` already is.

```bash
cd harness
bun run src/cli.ts devices                          # what is connected
bun run src/cli.ts status                           # the app's own view of the device
bun run src/cli.ts matrix --apps n2c                # install + screenshot + record a run
bun run src/cli.ts convert --packages com.foo       # Play-attribute any installed package
bun run src/cli.ts convert --unattributed aa        # every unattributed catalog app
bun run src/cli.ts carify  --packages com.foo       # side-by-side Android-Auto-visible clone
bun run src/cli.ts logs --level W                   # the app's own JSONL log, pulled off device
tools/carify.sh --apk downloaded.apk                # same clone, from a file, no device needed
```

**`carify` creates a pure parked-game copy.** It adds `appCategory=game` and `CAR_LAUNCHER` to the
normal launcher Activity, and removes template/projection discovery from the copy. Do not combine
the parked category with a `CarAppService`: Gearhead then classifies it as a Car App Library app,
where Unknown sources does not apply. Split apps are merged first. Android Auto can run parked
Activities only when the phone is on Android 15 or newer; touch is delivered directly to the
Activity and needs neither Accessibility nor Shizuku.

The phone's **Convert installed apps** screen uses the same distinction. A native legacy
projection app may be re-staged unchanged, preserving its signature and data. Any app without an
admitted car surface—including Play-installed and built-in apps—gets a `<package>.aaad` side-by-side
Car copy. The original is never stopped or modified; the copy is re-signed and starts with fresh
data. System apps stay out of the default AA-only list but are
available under **All apps**. AAAD itself is listed too. Apps with a publisher-supplied car version
carry an explicit chip; full descriptions wrap; conversions show stage progress and run in a
cancellable FIFO queue.

Shizuku is optional for this phone UI. When ready it avoids Android's confirmation dialog; it
cannot make `com.android.shell` a trusted initiating store. Without it, AAAD uses Android's
standard confirmation installer. **Unknown sources** may expose supported parked/custom apps, but
is not a bypass for Car App Library driving categories.

Application category is measured, not guessed. A fresh S25U control proved that a sideloaded
`maps` template is rejected even when its displayed installer is Play: Android records shell as
the initiator. A `game` copy is discoverable because it follows the official parked-app route.
Never claim or try to remove its while-driving restriction; that restriction is imposed by
Android Auto and is the safety contract that makes the route available.

`tools/aa-launcher-list.sh <serial>` prints the apps Android Auto will actually show, read off the
phone. Use it instead of guessing — or driving.

The clone is re-signed with `~/.aaad-carify.keystore`. **Keep that file**: without it no clone can
ever be updated in place.

## Working agreements

- **Do not add a backend.** If something seems to need a server, it belongs in the harness or the
  dash, which run on your own machine. The app stays offline-capable.
- **Never commit secrets.** `local.properties`, keystores, and `google-services.json` are
  gitignored. Release signing comes from environment variables; CI uses repository secrets.
- **Verified vs inferred.** ARCHITECTURE.md tags every claim **[V]** / **[H]** / **[I]**. When you
  learn something new — from a decompile, a log, a runtime probe — move the claim and cite the
  evidence. Do not silently upgrade a guess.
- **Don't invent data.** Catalog URLs and package attributions must come from the publisher.
  A plausible-looking wrong URL is worse than a missing entry.
- **Personal use only.** `LICENSE.md` is MIT (since `b374904`, 2025-12-06); `README.md`'s License
  section still asserts the older restrictive EULA. The two conflict; the fork sidesteps it by
  staying private. Do not publish builds or argue about which text wins.
- **Commits:** conventional commits, signed with an em-dash + model version, no `Claude` mention,
  no co-authored-by trailer. Never push, tag, or release without explicit per-instance permission.
- **ADB rules for this device** (rotating wireless-debugging port, the banned `stop; start`,
  screenshot size limits, leave-no-trace) live in `~/.claude/CLAUDE.md`. They apply here — this
  repo drives a phone constantly. `build-on-termux.sh` never runs `adb uninstall`.

## Reference repos on this box

| Repo | What to take from it |
| --- | --- |
| `../swype/cleverkeys` | `build-on-termux.sh` shape, env-var signing, `build-apk.yml` / `release.yml` patterns for an Android app built on Termux |
| `../termux-tools` | `.claude/skills/android-termux-build.md`, `smali-dex-patching.md`, `docs/APKTOOL_TERMUX.md` |
| `../operad` | The dash this fork's Phase 4 is modelled on |
| `../x2d` | Termux/Android runtime spelunking (APK patching, signing helpers under `runtime/handy_extract/`) |

## Glossary

Terms below describe **upstream** and are here so ARCHITECTURE.md §1–§9 reads cleanly. None of
them exist in this fork.

| Term | Meaning |
| --- | --- |
| `deviceId` | The gate's identity key. `ANDROID_ID` in v1.3; a Firebase Anonymous Auth UID in 2.1 |
| PRO | Lifetime unlimited-download entitlement. One boolean at RTDB `users/<deviceId>` |
| Free quota | 1 download per ~30.44 days, tracked at RTDB `lastdownload/<deviceId>` |
| `pc/` | RTDB node of unredeemed promo codes; deleted on redemption |

Still current: **Catalog** — the list of installable AA apps, now bundled in
`app/src/main/assets/`. **Shizuku** — optional privileged bridge for silent installs on
Android 14+ without root.
