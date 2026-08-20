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

Point 2 is the whole trick. **In v2.1, AAAD does not patch or re-sign the app on-device — it
falsifies the install attribution.** Everything in the v2.1 codebase that looks like patching is
dead (see below).

> **Scope warning.** That statement is true of **v2.1 only**. Upstream's current **v2.8.5** does
> rename packages and re-sign, in-process and working. The `pm install-create -i
> com.android.vending` trick below is unchanged in 2.8.5 and remains the core mechanism, but
> "no patching" is not a property of upstream in general. See
> [upstream-2.8.5-diff.md](upstream-2.8.5-diff.md).

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

### Tier 2 — repair an existing install — **does not work** [V]

`utils/PlayStoreSpoofer.makeAppAppearAsPlayInstalled()` tries, for apps already on the device:

- `pm set-installer <pkg> com.android.vending` via Shizuku, or
- `PackageManager.setInstallerPackageName` by reflection, then
- `verifyInstallerSet()` to confirm — the code explicitly handles
  *"pm set-installer command succeeded but verification failed"*.

**Tested on a real device (SM_S938U1, Android 16 / SDK 36) and it cannot succeed:**

```
$ pm set-installer sksa.aa.customapps.dev com.android.vending
java.lang.SecurityException: Caller does not have same cert as new installer package
                             com.android.vending
    at PackageManagerService$IPackageManagerImpl.setInstallerPackageName
```

`setInstallerPackageName` requires the **caller to be signed with the same certificate as the new
installer package**. Neither adb nor Shizuku qualifies — both run as the shell UID (2000), which
is not signed as the Play Store. So the entire tier-2 repair path, including
`fixAllAAADInstalledApps()`, is dead on modern Android.

The asymmetry is the important part: **the installer package can be *declared* when the session is
created, but never *changed* afterwards.** That is why tier 1 works and tier 2 does not — and why
upstream needed a root-only tier 3 at all.

Practical consequence: **attribution must be set at install time.** An app already installed
without it cannot be fixed in place; it has to be uninstalled and reinstalled through a session.

### Tier 3 — `/data/system/packages.xml` (root, last resort)

`PlayStoreSpoofer.modifyPackagesXml()` backs up to `packages.xml.aaad_backup`, rewrites the
installer attribute with an `awk` script, restores `chown system:system` + `chmod 660`, and
reports *"REBOOT REQUIRED"*. Needs real root; irrelevant to the non-root use case.

### Without Shizuku — adb is an exact substitute [V]

Shizuku's whole contribution is running `pm` as the **shell UID (2000)**. `adb shell` is that same
UID, so anything Shizuku can do here, adb can do — no Shizuku app, no permission prompt, no
on-device service. Verified end to end on SM_S938U1 / Android 16 / SDK 36:

```bash
adb push app.apk /data/local/tmp/x.apk
adb shell "pm install-create -r -i com.android.vending \
    --originating-uri 'https://play.google.com/store' --install-reason 0 \
    --bypass-low-target-sdk-block"          # → Success: created install session [644219766]
adb shell "pm install-write -S <bytes> <session> base /data/local/tmp/x.apk"
adb shell "pm install-commit <session>"     # → Success

adb shell "pm list packages -i" | grep <pkg>
#   package:<pkg>  installer=com.android.vending
adb shell "dumpsys package <pkg>" | grep -E 'installerPackageName|packageSource'
#   installerPackageName=com.android.vending
#   packageSource=1
```

Note shell does **not** hold the Play Store's certificate, yet declaring `-i com.android.vending`
at session creation is accepted — the restriction only applies to changing it later (tier 2).

**This makes the test harness Shizuku-free.** The harness runs on a host with adb, so it never
needs Shizuku on the device ([testing-harness.md](testing-harness.md)). Shizuku only matters for
the *app* doing its own installs on a phone with no host attached.

### Without Shizuku and without a host

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

**Verified empirically** — the exact argv `BundledApkSigner` builds:

```
$ dalvikvm -Xmx256m -cp <readonly>/apksigner.jar com.android.apksigner.ApkSignerTool --version
Unable to locate class 'com/android/apksigner/ApkSignerTool'
java.lang.ClassNotFoundException: com.android.apksigner.ApkSignerTool
exit=1
```

ART opens the jar, finds no dex in it, and therefore cannot resolve the entry class. That is a
property of the shipped artifact, so it fails identically inside the app.

> Correction: an earlier run of this test reported `SIGABRT / exit=134`, which was an artifact of
> the test environment, not the jar — the copy lived on a writable path and tripped ART's
> `SecurityException: Writable dex file … is not allowed` W^X check before it ever looked at the
> contents. Re-run against a read-only copy, the real failure is the `ClassNotFoundException`
> above. The conclusion is unchanged; the evidence for it is different. If you test this yourself,
> `chmod 444` the jar first or you will measure the wrong thing.

Not re-signing is also the better outcome: the installed app keeps its original developer
signature, so Play Protect behaves normally and publisher updates still apply cleanly.

## Consequences for this fork

- **T-06 does not need the v2.1 patching chain.** No apktool, no stamp injection.
- **No BouncyCastle** — dropped from `app/build.gradle`; upstream carried `bcpkix`/`bcprov` 1.82
  solely for the dead chain. If the fork ever does need to re-sign, v2.8.5 shows the working way:
  in-process `com.android.apksig.ApkSigner` or `net.fornwall.apksigner.ZipSigner`, neither of which
  needs BouncyCastle ([upstream-2.8.5-diff.md](upstream-2.8.5-diff.md)).
- What T-06 *does* need: a Shizuku session install with `-i com.android.vending`, a
  `pm set-installer` repair path, an honest capability check, and a clear message when Shizuku is
  unavailable — because in that case AA visibility genuinely depends on the user enabling AA's
  *Unknown sources*, and no amount of app-side cleverness changes that.
- The catalog does not need per-app patching metadata; every app already declares the AA meta-data.

## Package renaming: what it actually does (T-07, partial) [V]

Upstream distributes some apps under package names that are not their publishers'
(`maps.jaoolonda.android`, `android.loandamaps.it`, `maps.kiao2client.android`, …). Examined by
pulling upstream's renamed **AAMirror** build (`mirror-debug.apk`, 2.0 MB) and reading its manifest.

What the rename changes: **only the package identifier.**

- Internal classes are untouched — the manifest still points at
  `com.github.slashmax.aamirror.CarService`, `.ForegroundService`, `.CarApplication`, and so on.
  Consistent with `ARSCPackageRenamer` rewriting the manifest's package attribute and
  `resources.arsc`, not the code.
- **AA capability is not added by the rename.** The original app already declares everything AA
  needs: the `com.google.android.gms.car.application` meta-data plus the
  `com.google.android.gms.car.category.CATEGORY_PROJECTION` and `CATEGORY_PROJECTION_OEM`
  intent categories.

Which apps get renamed is the informative part:

| Renamed | Left alone |
| --- | --- |
| Screen2Auto, AAMirror, AAStream, AA Mirror Plus, CarStream | AA Passenger, Nav2Contacts, AATorque, Fermata, AA Browser, Performance Monitor, Widgets For Android Auto |

Exactly the mirroring/streaming family — the category Google actively blocks — is renamed. Ordinary
AA utilities keep their publishers' package names, even the ones upstream hosts itself
(AA Passenger ships from upstream's bucket as `com.github.martoreto.aaremote`, unrenamed).

**So the rename is about identity, not capability.** The obvious reading is evading a
blocklist — Play Protect's, Android Auto's, or both — but nothing in the code or the artifacts
states a reason, and it is **[I]**. Do not assert it.

Also worth knowing: that build is signed with the **public AOSP test key**
(`EMAILADDRESS=android@android.com, CN=Android, O=Android`, SHA-256
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`) and is named `-debug`. The
private half of that key is public, so anyone can produce a signed update for those package ids.

**What this means for the fork.** Since renaming adds no AA capability, shipping the *original*
publisher builds is a viable starting point — the apps are already AA-capable as published. Whether
Android Auto or Play Protect rejects them under their real identity is the open half of T-07, and
it needs the observation channel below. This unblocks T-15 more than expected: the mirroring family
can be catalogued from publisher sources and flagged as AA-visibility-unverified.

## Converting an app that is already installed

The corollary of "attribution can only be declared at session creation": an AA-capable app that
was sideloaded from anywhere else — F-Droid, Obtainium, a browser download, another installer, or
this app's own fallback path — is invisible in the car and **cannot be repaired in place**.

The fix is to reinstall the app's *own* APKs through an attributed session. Nothing is
re-downloaded, patched, or re-signed:

1. Enumerate installed apps declaring `com.google.android.gms.car.application`
   (`data/InstalledAppScanner`, needs `QUERY_ALL_PACKAGES`).
2. Read each one's installer with `PackageManager.getInstallSourceInfo`. Anything other than
   `com.android.vending` is convertible.
3. Collect `applicationInfo.sourceDir` **and `splitSourceDirs`**. Split apps are the trap here:
   a session containing only the base of a split app fails to commit, or commits an app missing
   its resources.
4. Stage all of them into one attributed session and commit
   (`ShizukuInstaller.convertInstalled`). The shell uid can already read `/data/app`, so the paths
   are handed to `pm install-write` directly rather than streamed — and with a path, `install-write`
   sizes the file itself, so no `-S` is needed.

Because the APKs and therefore the signature are unchanged, this is an update over the top:
**app data and settings survive**.

Conversion has no fallback, and should not grow one. The entire point is the attribution, and the
platform `PackageInstaller` cannot provide it — an app may only attribute an install to itself.
Offering a fallback here would produce exactly the invisible install the user is trying to fix.

Scale of the problem on one real device: 556 apps from Play, and ~136 sideloaded — 36 via
Obtainium, 26 F-Droid, 26 packageinstaller, 19 Chrome, 16 AppManager, 13 with no installer at all.

## Observability: AA's app list needs a live projection session [V]

Checked on the test device with Android Auto installed but not projecting:

```bash
adb shell "dumpsys activity service com.google.android.projection.gearhead"
#   No services match: com.google.android.projection.gearhead
adb shell "settings get global car_developer_settings_enabled"   # null
```

Gearhead runs no services until a head unit connects, so **there is no way to read AA's app list
on an idle phone.** The question "does Android Auto list this app" is only answerable inside a
projection session — a real car, the desktop Desktop Head Unit, or an emulated head unit.

That is the concrete blocker behind [testing-harness.md](testing-harness.md) T-22, and it is very
likely why upstream v2.8.5 built its own head unit emulator
([upstream-2.8.5-diff.md](upstream-2.8.5-diff.md#the-other-headline-an-in-app-android-auto-head-unit)).
Everything upstream of that assertion — download, attribution, install state — is observable from
adb today; only the final "AA sees it" step is not.

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
