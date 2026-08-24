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

## The current gate

Android Auto admission depends on the declared car surface, its category, and the **initiating**
install source. On Android 11+, `InstallSourceInfo` exposes installing and initiating packages
separately. A shell session may set `-i com.android.vending`, but the S25U still reports
`initiatingPackageName=com.android.shell`; current Android Auto 17.3 uses that distinction.

The developer *Unknown sources* setting is not a universal bypass. Google's testing documentation
limits it to supported media, messaging/notification, and parked-app paths and explicitly says it
does not apply to Car App Library apps. A maps/navigation template therefore requires a genuine
trusted-store install. AAAD, adb, and Shizuku all initiate as shell and cannot create that trust.

The supported general-purpose conversion target is consequently a parked game-category copy.
That copy can appear with Unknown sources enabled, but Android Auto intentionally disables it while
the vehicle is moving. There is no supported non-root way for AAAD to turn arbitrary phone UI into
an unrestricted driving app.

References: [Android Auto testing](https://developer.android.com/training/cars/testing),
[parked apps](https://developer.android.com/training/cars/parked/auto), and
[`InstallSourceInfo`](https://developer.android.com/reference/android/content/pm/InstallSourceInfo).

> **Scope warning.** That statement is true of **v2.1 only**. Upstream's current **v2.8.5** does
> rename packages and re-sign, in-process and working. The `pm install-create -i
> com.android.vending` trick below is unchanged in 2.8.5 and remains the core mechanism, but
> "no patching" is not a property of upstream in general. See
> [upstream-2.8.5-diff.md](upstream-2.8.5-diff.md).

## What actually runs: installer attribution

### Tier 1 — Shizuku session install (legacy installer-label path)

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

`-i com.android.vending` sets the installing package label. It does **not** make Play the
initiating package. This helped older Android Auto builds, but does not satisfy current
trusted-source admission for Car App Library driving categories.

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

The asymmetry is still useful historically: the installer label can be declared when the session
is created but cannot be changed afterwards. Neither operation changes the initiating package.

Practical consequence: re-staging can repair the legacy installer label, but cannot turn a shell
install into a genuine Play-initiated install.

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

## What did not run upstream: its repackaging chain

Four upstream classes implement APK rewriting and re-signing. **None of that upstream chain can
execute on a stock device.** This is historical evidence, not a description of this fork's
`CarifyRepackager`, which uses vendored APKEditor and an in-process signer.

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

The Convert screen creates a side-by-side Car copy for an APK without a usable car surface. This
applies even when the original came from Play, and to built-in apps. Rewriting invalidates the
publisher signature, so replacing the original is impossible and unsafe; the clone has a new
package, AAAD's persistent signing key, and fresh data.

A publisher APK that already has a usable `projection` or `template` surface is reported as such,
but AAAD does **not** offer to "register" it. Current Android Auto inspects the initiating package,
not only the mutable installer label. A local `PackageInstaller`, adb, or Shizuku reinstall cannot
become a genuine Play/trusted-store install, so presenting that operation as a repair was false.
Reinstalling AAAD over itself also kills the Activity that owns the operation.

The Car-copy operation decodes and merges the base plus every split APK, patches the
manifest/resources, injects the bridge, aligns, and signs on-device. Shizuku makes its installation
unattended when available; otherwise Android's confirmation UI is used. Neither route is described
as trusted-store provenance. The operation never uploads the APK and never stops or writes into the
original package.

Scale of the problem on one real device: 556 apps from Play, and ~136 sideloaded — 36 via
Obtainium, 26 F-Droid, 26 packageinstaller, 19 Chrome, 16 AppManager, 13 with no installer at all.

## Being listed is not the same as being usable while driving [V]

Attribution is necessary but **not sufficient**. It decides whether Android Auto puts an app in
the launcher. A second, independent declaration decides what the app is then allowed to do — and
an app can pass the first and fail the second, which presents as *"can't use while driving"* on a
real head unit after an install that AAAD correctly reported as attributed.

That declaration is the XML the app's `com.google.android.gms.car.application` meta-data points
at, conventionally `res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="projection"/>
</automotiveApp>
```

Evidence, read straight out of the shipped APKs of three catalog apps (`aapt2 dump xmltree`):

Every app in the bundled catalog, audited with `aapt2 dump xmltree` against its current release:

| app | `<uses>` declared | car surface |
| --- | --- | --- |
| CarStream | `service`, **`projection`**, `notification`, `media` | full screen |
| Fermata | `media`, `service`, **`projection`** | full screen |
| Performance Monitor | `notification`, **`projection`**, `service` | full screen |
| AA Torque | **`projection`**, `service` | full screen |
| Widgets For Android Auto | `notification`, **`projection`**, `service` | full screen |
| Nav2Contacts | **`template`** | templated (Car App Library) |
| **AABrowser 2.2** | **`media` only** | **none — listed, then "can't use while driving"** |

There are two routes to a usable car surface and an app needs one of them:

- **`projection`** — a full-screen Activity on the head unit, via the unofficial custom-apps SDK.
- **`template`** — a templated Car App Library app. Distraction-optimised by construction, which
  is exactly why it is allowed to run while driving.

An app declaring neither is a media source. Android Auto lists it and then refuses to open its UI.

AABrowser is the only catalog app in that state, and it has **always** been: releases 2.2, 2.0 and
1.6 all declare `media` only, and 1.3 and earlier ship no descriptor at all. There is no version to
pin back to. It also carries `androidx.car.app.category.NAVIGATION` and `CAR_LAUNCHER` on a plain
Activity while shipping **no** `CarAppService`, so it is neither a templated app nor a projected
one — it reads as an unfinished port.

Two corollaries matter for this fork:

- **No installer, permission, or phone-side setting can grant `projection`.** It is a statement the
  app makes about itself, read from its own APK. Converting such an app, reinstalling it, or
  enabling AA's *Unknown sources* changes nothing. The only fixes are the publisher shipping a
  corrected descriptor, or rewriting the APK and re-signing it (T-45).
- **`distractionOptimized` is not the missing piece.** AABrowser already sets
  `distractionOptimized=true` on both its application and its `MainActivity`, and is still blocked.
  That attribute marks an Activity as safe to show *once the app is allowed a surface at all*; it
  does not grant the surface.

`data/AutomotiveDescriptor` reads this for any installed package (and for a downloaded APK before
it is committed), so the condition is reported rather than discovered in traffic. Diagnostics marks
such apps `D`, and the convert screen says so on the row.

### Reading Android Auto's app list without a car [V]

Android Auto's **Customize launcher** screen is the list it builds for the head unit, and it lives
on the phone:

```bash
adb shell am start -n com.google.android.projection.gearhead/\
  com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity
# → Display → Customize launcher       (harness/tools/aa-launcher-list.sh does this)
```

This is the only window there is. `dumpsys` exposes nothing about the list, and a release gearhead
logs nothing useful — a full logcat capture during a package change produced **zero** gearhead
lines. Before this, "will Android Auto show my app?" could only be answered by driving.

### A manifest-only `projection` declaration is NOT sufficient [V]

Measured with the list above, on one device, all three manifest-only clones built the same way:

| clone | descriptor | in Android Auto's list |
| --- | --- | --- |
| **AABrowser (Car)** | `projection` | **yes** |
| Calculator (Car) | `projection` | no |
| Service Browser (Car) | `projection` | no |

All three resolve for `MAIN` + `CAR_LAUNCHER`, are `installer=com.android.vending`, are enabled,
carry `distractionOptimized` on the application and the launcher activity, and have a descriptor
that resolves. The manifests are indistinguishable. Adding `androidx.car.app.minCarApiLevel` and
`androidx.car.app.category.POI` + `APP_MAPS` to the Calculator clone changed nothing either.

What separates them is **code**. AABrowser already ships car-app structure — the `androidx.car.app`
library, a `CarAppPermissionActivity`, an `androidx.car.app.connection.provider`,
`ACCESS_SURFACE`/`MAP_TEMPLATES` permissions — and its APK declared `media` only, which is why it
was listed and then refused to open. Rewriting its descriptor to `projection` fixed the app it
always nearly was. Calculator and Service Browser have no car implementation at all, and no
manifest edit conjures one. Every app in that list — Spotify, Discord, Widgets for Auto, the Google
apps — has a real media, template, or projection service behind it.

The verified rule is narrower than the old documentation made it: **manifest surgery can repair a
mis-declared car app, but cannot turn a phone app into a car app. Executable car code is required.**
That measurement describes Carify before T-54; the current pipeline injects the missing code.

This section has been wrong twice, in both directions, and the reason is worth keeping. First it
asserted from CarStream's APK that an ordinary Activity can *never* be projected — reading one
implementation and mistaking a sufficient path for a necessary one. Then a single report of a clone
running full screen was taken to prove the opposite, and the caveat was removed from the tool. The
first was over-inference from code, the second under-verification of a result: neither established
*which* clone had worked. The list above is what an actual measurement looks like.

### Injecting a real car surface (T-54) [V/I]

Carify now handles an APK with no car implementation by injecting a minimized AndroidX runtime and
real `CarAppService`: a `template` descriptor, AndroidX permission Activity, notification
receiver and connection query, map/navigation permissions, Car API 7, and
`DEFAULT + CAR_LAUNCHER + NAVIGATION + APP_MAPS` on the launcher.

**[V] Discovery evidence:** the complete projection runtime/categories initially remained NOT
FOUND. Renaming a second clone to `maps.popupcalc.android` also remained NOT FOUND, ruling out
package identity. Adding AABrowser's `android:appCategory="game"` produced
`FOUND: Calculator (Car)`.

That result did not isolate descriptor type. The exact missing control was run next: with the
known-visible projection clone disabled, an otherwise identical shell-initiated
`template + appCategory=game` clone at `maps.templatecalc.android` independently returned FOUND.
The earlier excluded shell-initiated Calculator/Nav2Contacts templates lacked the game category,
so comparison with Play-initiated templates was confounded and did not establish a Play gate.
Carify therefore defaults to the public template service that actually backs its runtime bridge.
Both disposable clones were removed; the normal clone launches cleanly on the phone.

**Correction, 2026-08-24:** that discovery control was not a valid shipping category. Android Auto
defines games as parked-only, which explains the later “not available while driving” result on
every converted clone. Carify now uses `android:appCategory="maps"`, matching its
`androidx.car.app.category.NAVIGATION` service and `NavigationTemplate`. The historical game
experiment remains above because it established that application category participates in
discovery; it must not be copied back into generated APKs.

**[I] Head-unit outcome:** Customize launcher proves listing, not behavior. Calculator still must
be selected in a car/DHU/emulator to verify rendering and button input. Do not upgrade that outcome
to **[V]** until the projected test passes.

The bridge declares maps/navigation discovery signals for arbitrary phone apps, so it is explicitly
a local testing/personal-use artifact and not a truthful Play-distribution declaration.

### Two car routes, and one public bridge

The apps that open full screen are **projected** apps: an exported `<service>` filtering on
`com.google.android.gms.car.category.CATEGORY_PROJECTION`, drawing an Activity on the head unit
through the unofficial Android Auto custom-apps SDK. That SDK is not published to Maven, so this
fork cannot build against it.

The **templated** Car App Library (`androidx.car.app`) is public, on Maven, and distraction-optimised
by construction. Navigation apps additionally receive a drawing Surface for their map. T-54 uses
that public Surface as the bridge to the cloned Activity; the policy caveat above is separate from
the technical mechanism.

## Observability: the launcher list lives in Android Auto settings [V]

Gearhead does not expose its launcher list through `dumpsys`, logcat, a database, or a useful
preference. The earlier investigation stopped there and incorrectly concluded that the list had to
be decoded from a live head-unit video stream.

The list has a simpler authoritative rendering: Android Auto's phone-side *Display → Customize
launcher* screen. `harness/tools/aa-launcher-list.sh <serial> [label]` opens that screen through
the exported settings Activity, navigates by UI text and measured coordinates, collects labels
with UI Automator while scrolling, then removes its dump and restores Home. T-53 used it to
distinguish AABrowser from the two manifest-only phone-app clones.

The phone must have completed Android Auto onboarding. On the rooted Saga, where onboarding was
never completed, the screen omits known-good third-party apps such as Nav2Contacts; its negative
results are environment failures, not app evidence. The paired S25U is the acceptance phone.

Listing and functioning remain separate assertions. Customize launcher closes T-22 without a head
unit. Proving that a listed app actually opens, renders, and accepts input still needs a live
projection session — a car, DHU, or emulator — and remains the final T-54 gate for the injected
Calculator bridge.

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
