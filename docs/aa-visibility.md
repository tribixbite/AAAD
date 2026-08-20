# How an app becomes visible to Android Auto

The core of what AAAD does, and the one thing upstream never published as source. Recovered by
decompiling the official **v2.1 release APK** — the build that matches the source drop in this
repo (released 2025-11-20, same day as the `Version 2.1 source code` commits).

Everything here is **[V] verified** from decompiled bytecode unless marked otherwise. Method and
class names survive because upstream ships `minifyEnabled false`.

## Method

```bash
gh release download v2.1 -R shmykelsa/AAAD          # AAAD-2.1-release.apk, 25 MB
unzip -q AAAD-2.1-release.apk -d x21                # 5 dex, assets/, lib/
java -jar baksmali-3.0.9-fat.jar d x21/classes3.dex -o smali3
# rebuild a filtered dex of just com.legs.appsforaa.* → 223 smali files, 405 KB
java -jar smali-3.0.9-fat.jar a mini -o mini.dex
jadx -d java --no-res --show-bad-code mini.dex      # 52 readable Java files
```

`classes3.dex` holds the app's own code. Tooling notes: `~/git/termux-tools/docs/APKTOOL_TERMUX.md`
and `.claude/skills/smali-dex-patching.md`. `jadx` is packaged for Termux (`pacman -S jadx`).

## The actual gate

Android Auto surfaces a third-party app only when **both** hold:

1. **The app declares AA support** — `<meta-data android:name="com.google.android.gms.car.application" .../>`
   in its manifest. Every app in the catalog already does; they are AA apps. AAAD's
   `AndroidAutoCompatChecker` reports *"Apps need `<meta-data android:name="com.google.android.gms.car.application"/>`
   in manifest"* when it doesn't.
2. **Android Auto trusts the installation** — either AA's developer setting *Unknown sources* is
   enabled, or the package looks like it came from the Play Store.

Point 2 is the whole trick. **AAAD does not patch or re-sign the app. It falsifies the install
attribution.** Everything else in the codebase that looks like patching is dead (see below).

## What actually runs: installer attribution

### Tier 1 — Shizuku session install (the working path)

`utils/ShizukuInstaller.installApkWithSession()` builds a `pm` session as the shell UID:

```sh
pm install-create [-r] -i com.android.vending \
    --originating-uri 'https://play.google.com/store' \
    --install-reason 0 \
    [--bypass-low-target-sdk-block]        # appended when Build.VERSION.SDK_INT >= 34
cat <apk> | pm install-write -S <size> <sessionId> <name> -
pm install-commit <sessionId>
# on any failure: pm install-abandon <sessionId>
```

Executed through `Shizuku.newProcess(String[], String[], String)`, reached by reflection because
it is not public API. `getPlayStoreUid()` resolves `com.android.vending`'s uid for the originating
package, falling back to a hardcoded `10299`.

`-i com.android.vending` is the payload: it sets the installer package, which is what AA reads.

### Tier 2 — repair an existing install

`utils/PlayStoreSpoofer.makeAppAppearAsPlayInstalled()`, for apps already on the device:

- `pm set-installer <pkg> com.android.vending` via Shizuku, or
- `PackageManager.setInstallerPackageName` by reflection, then
- `verifyInstallerSet()` confirms it took — the code explicitly handles
  *"pm set-installer command succeeded but verification failed"*.

`fixAllAAADInstalledApps()` batch-repairs every catalog app already installed.

### Tier 3 — `/data/system/packages.xml` (root, last resort)

`PlayStoreSpoofer.modifyPackagesXml()` backs up to `packages.xml.aaad_backup`, rewrites the
installer attribute with an `awk` script, restores `chown system:system` + `chmod 660`, and
reports *"REBOOT REQUIRED"*. Needs real root; irrelevant to the non-root use case.

### Without Shizuku

Falls back to the system installer. Modern Android does not honour `EXTRA_INSTALLER_PACKAGE_NAME`
from an ordinary app, so attribution fails and visibility depends on AA's *Unknown sources*
developer setting — which is exactly why upstream ships `AndroidAutoSetupActivity`, a guided
walkthrough for enabling it (`strings.xml:316-333`). `AndroidAutoCompatChecker` also notes:
*"On Android 14+, this flag cannot be changed after installation without modifying packages.xml."*

## What does not run: the repackaging chain

Four classes implement APK rewriting and re-signing. **None of them can execute on a stock
device.** This matters: it means the fork does not need to reproduce any of it.

| Class | Intent | Why it never runs |
| --- | --- | --- |
| `ApkStampInjector` | Inject `com.android.stamp.source` = `https://play.google.com/store` and `com.android.stamp.type` = `STAMP_TYPE_DISTRIBUTION_APK` into `<application>` | Gated on `Runtime.exec("which apktool")`. No stock Android has `apktool` on `$PATH`, so it logs *"apktool not available - skipping stamp injection"* and copies the APK unchanged |
| `ApkRepackager` | decompile → inject stamps → recompile → keystore → sign → align | Same apktool dependency, plus the signer below |
| `BundledApkSigner` | `dalvikvm -Xmx256m -cp <apksigner.jar> com.android.apksigner.ApkSignerTool sign --ks … --v2-signing-enabled true --v3-signing-enabled true` | **`assets/tools/apksigner.jar` is 1.1 MB / 663 entries of JVM `.class` files with no `classes.dex`.** ART cannot load it |
| `ApkSigner` | fall back to system `apksigner`, then `jarsigner` | Neither binary exists on Android |

`KeystoreManager` generates `aaad_signing.keystore` (`CN=AAAD Auto Installer, OU=Android Auto Apps,
O=AAAD, L=Internet, ST=Online, C=US`, store password `aaad2024secure`) and is consumed only by the
dead signer.

**Verified empirically on this device** — the exact argv `BundledApkSigner` builds:

```
$ dalvikvm -Xmx256m -cp assets/tools/apksigner.jar com.android.apksigner.ApkSignerTool --version
Aborted
exit=134            # SIGABRT, zero output
```

That failure is a property of the shipped artifact (no dex), not of the environment, so it fails
identically inside the app.

Not re-signing is also the better outcome: the installed app keeps its original developer
signature, so Play Protect behaves normally and publisher updates still apply cleanly.

## Consequences for this fork

- **T-06 does not need APK patching.** No apktool, no manifest rewriting, no stamp injection.
- **No on-device re-signing**, therefore **no BouncyCastle** — dropped from `app/build.gradle`.
  Upstream carried `bcpkix`/`bcprov` 1.82 solely for the dead chain.
- What T-06 *does* need: a Shizuku session install with `-i com.android.vending`, a
  `pm set-installer` repair path, an honest capability check, and a clear message when Shizuku is
  unavailable — because in that case AA visibility genuinely depends on the user enabling AA's
  *Unknown sources*, and no amount of app-side cleverness changes that.
- The catalog does not need per-app patching metadata; every app already declares the AA meta-data.

## Diagnostics worth keeping

`AndroidAutoCompatChecker` is a good model for the harness's AA-visibility probe
([testing-harness.md](testing-harness.md) T-22). Per installed package it reports: AA metadata
present, Play Store stamps present, installer source, unknown-source flag, and an app category
(Audio / Maps-Navigation / Social / Productivity / Other) with the note that category affects
visibility.

## Also recovered

**T-12, upstream's server-side download authorization** — resolves the last **[I]** claims in
[ARCHITECTURE.md § 7.3](../ARCHITECTURE.md#73-v21--what-changed):

- Callable: **`requestAuthorizedDownload`**; the client reads an **`authorized`** field from the response.
- RTDB keys `users` and `lastdownload` are **still in use** in 2.1 — the quota storage did not move.

**The real catalog** — `assets/app_catalog.json`, 14 apps, schema version 2.0. Fields:
`app_id`, `app_name`, `package_name`, `version`, `version_code`, `download_url`, `icon_url`,
`source_type` (`firebase_storage` | `github`), `category`, `description_key`. Download URLs point
at upstream's own Firebase Storage bucket (`appsforaa-1b443`) with embedded access tokens — this
fork must resolve publisher sources instead of leeching upstream's bucket ([TASKS.md](../TASKS.md) T-15).

Package names, which also settles three of the four unattributed entries in
[standalone.md](standalone.md#package-names):

| Package | App |
| --- | --- |
| `maps.kiao2client.android` | AAMirror |
| `maps.mobilejiohubclient.android` | AAStream |
| `maps.mobilejiohub.android` | AA Mirror Plus |
| `com.carstream` | CarStream 2.0.2 / 2.0.4 / 2.0.5 |
| `ru.inceptive.screentwoauto` | Screen2Auto |
| `com.aapassenger` | AA Passenger |
| `com.aawidgets` | Widgets For Android Auto |

`android.loandamaps.it` appears in the manifest `<queries>` but not in the 2.1 catalog — a legacy
package retained for installed-state detection, as is `maps.jaoloonda.android` (an older CarStream).

## Upstream's full class list

For reference when reimplementing ([TASKS.md](../TASKS.md) T-04). Recovered from `classes3.dex`:

```
LauncherActivity  MainActivityNew  OnboardingActivity{,New}  OnboardingDataStore
OnboardingPageFragment  OnboardingPagerAdapter  OnboardingPermissionsFragment
OnboardingWarningFragment  AndroidAutoSetupActivity  SupportActivity
AboutPaymentActivity  ProVersionActivity  EnterProCode  TransferLicense  LicenseTransferActivity
adapters/  AppListAdapter  AppViewHolder  AppDiffCallback  AppActionType
data/      AppDatabase  AppMetadata  AppMetadataDao  AppCategory  AppSourceType  Converters
           AppInstallationState  DownloadState  InstallationStatus  VersionInfo
           GitHubRelease  GitHubAsset
managers/  AuthManager  InstallationStatusManager
services/  AppMetadataService          workers/  MetadataUpdateWorker
receivers/ PackageInstallReceiver
utils/     ApkInstallManager  ShizukuInstaller  UnifiedPlayStoreInstaller  PlayStoreSpoofer
           ApkRepackager  ApkSigner  BundledApkSigner  ApkStampInjector  KeystoreManager
           AndroidAutoCompatChecker  AppUpdateChecker  IconExtractor  AppIcon  AppIconMapper
           NetworkUtils  Logger  LogCollector  DebugManager  ThemeHelper  ViewExtensionsKt
           WrapContentLinearLayoutManager  WrapContentGridLayoutManager
```

`UnifiedPlayStoreInstaller` exposes the install strategies: `installWithFullCompatibility`,
`installLightweight`, `installWithRepackaging`, `installAggressive`, `installMultipleApks`.
Given the repackaging chain is dead, only the non-repackaging strategies do anything.
