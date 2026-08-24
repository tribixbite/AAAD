# Upstream drift: v2.1 → v2.8.5

[aa-visibility.md](aa-visibility.md) documents **v2.1** (2025-11-20), the release matching this
repo's source drop. Upstream's current release is **v2.8.5** (2026-06-17). This is what changed on
the install path, recovered the same way (`gh release download v2.8.5` → baksmali `classes3.dex`
→ filtered mini-dex → jadx).

Scale of the drift: **93 → 241 classes** in `com.legs.appsforaa`. APK 25 MB → 59 MB.

## The headline: 2.1's dead code became real

[aa-visibility.md](aa-visibility.md#what-does-not-run-the-repackaging-chain) establishes that in
v2.1 the entire repackage-and-re-sign chain could not execute. In 2.8.5 **it works** — via a
different implementation, while the broken one is still shipped.

### Signing now happens in-process **[V]**

| | v2.1 | v2.8.5 |
| --- | --- | --- |
| Path | `BundledApkSigner` → `dalvikvm -cp assets/tools/apksigner.jar` | `JavaApkSigner` → **`com.android.apksig.ApkSigner`**, linked as a library |
| Alternate | `apksigner` / `jarsigner` binaries (absent on Android) | `FornwallApkSigner` → **`net.fornwall.apksigner.ZipSigner`**, pure-Java, Android-safe |
| Works? | **No** — jar has no `classes.dex`, `dalvikvm` aborts | **Yes** — no subprocess, no external binary |

`assets/tools/apksigner.jar` is **still shipped and byte-identical** (same md5
`dcb3734997fda0169f1d6d66b6ad7cb9`, 663 `.class` entries, still no `classes.dex`), and
`BundledApkSigner` still exists. Seven releases on, the dead path is dead but retained — it is
simply no longer load-bearing.

`JavaApkSigner` carries the note `"(disabled for ARSCLib compatibility)"` on part of its config,
tying it to the resource rewriting below.

### Package renaming moved on-device **[V]**

New classes: `utils/PackageRenamer`, `utils/ARSCPackageRenamer` (rewrites `resources.arsc`, hence
ARSCLib), `utils/PackageNameHelper`, `utils/RenamedPackageTracker`, plus Room storage
`data/PackageNameMapping` + `PackageNameMappingDao`. `PackageRenamer` counts and rewrites
`" occurrences of package name"` across `AndroidManifest.xml` and the resource table.

The evidence that this is a genuine move rather than an addition is in the catalogs. Both versions
ship 14 apps with identical schema and identical `last_updated`, but **`package_name` was
corrected in 2.8.5 for exactly the apps whose distributed builds are renamed**:

| app | v2.1 `package_name` | v2.8.5 `package_name` |
| --- | --- | --- |
| carstream 202/204/205 | `com.carstream` | `maps.jaoolonda.android` |
| s2a (Screen2Auto) | `ru.inceptive.screentwoauto` | `android.loandamaps.it` |
| aap | `com.aapassenger` | `com.github.martoreto.aaremote` |
| aawidgets | `com.aawidgets` | `de.nsvb.android.auto.w4a` |

In v2.1 the catalog listed the *publisher's* package name while the Firebase-hosted APK installed
under a different one — so installed-state detection was simply wrong for those apps. 2.8.5 lists
the name the app actually ends up with.

`PackageNameHelper` now checks both, logging `"App installed with original package: "` vs
`"App renamed package check (DB): "` / `"(Prefs): "`.

**Why rename** is **[I]**: the three mirroring apps all land on a `maps.*` prefix and Screen2Auto
on `android.loandamaps.it`, which reads as disguising them as navigation apps — plausible given
Android Auto's category policy, but the code does not state a reason. Do not assert it as fact.

### Install is now a strategy ladder **[V]**

`utils/SmartInstaller` replaces the ad-hoc calls, exposing:

```
install()                → runs the ladder
tryShizukuPlayStore()    → the v2.1 mechanism: pm install-create -i com.android.vending
tryShizukuDirect()       → Shizuku install without Play attribution
tryPackageInstaller()    → plain PackageInstaller, no attribution
getAvailableMethods()    → capability report
getRecommendedMethod()   → picks per device
```

The Shizuku command itself is **unchanged**: `pm install-create`, `-i com.android.vending`,
`--originating-uri`, `--install-reason 0`, `--bypass-low-target-sdk-block` on SDK ≥ 34. **The
core AA-visibility trick documented for v2.1 is still exactly the trick in v2.8.5.**

Supporting additions: `utils/InstallationPrecheck`, `utils/ShizukuHealthMonitor`,
`utils/DeltaPatcher`, `managers/InstallationHistoryManager` with Room-backed
`data/InstallationRecord` / `InstallationHistoryDao`.

## The other headline: an in-app Android Auto head unit

`com.legs.appsforaa.androidauto.**` is ~100 new classes implementing **the Android Auto protocol
itself**:

- `HeadUnitEmulator` — configurable `make`, `model`, `dpi`, `framerate`, and sensor toggles
  (`locationEnabled`, `compassEnabled`, `gyroscopeEnabled`, `accelerometerEnabled`,
  `fuelEnabled`, `nightModeEnabled`, `drivingStatusEnabled`) plus **`forceGearPark`,
  `forceParkingBrake`, `forceUnrestricted`**.
- `AndroidAutoProxyService` — proxies the protocol over `127.0.0.1`.
- `ProtobufBuilder` + `androidauto/proto/**` — ~90 generated message types
  (`ServiceDiscoveryRequest/Response`, `VideoConfiguration`, `AudioConfiguration`, `SensorBatch`,
  `DrivingStatusData`, `GearData`, `SpeedData`, `UiConfig`, `WirelessTcpConfiguration`, …).
- `androidauto/ssl/**` — `SSLEngineBuilder`, `SSLTransport`, `NioSSLPeer` for the AA TLS channel.
- `carapp/AAADCarAppService` + `AAADMainScreen` + `AAProxyService` — an `androidx.car.app` host.
- `AndroidAutoInterceptActivity`, `receivers/AndroidAutoConnectionReceiver`,
  `utils/AndroidAutoConnectionDetector`, `AndroidAutoDeveloperModeManager`,
  `AndroidAutoQuickLauncher`, `AndroidAutoCacheManager`, `data/AndroidAutoPreferences`.

**This informed [testing-harness.md](testing-harness.md)'s T-22 research.** T-22 was later closed
more cheaply by reading Android Auto's phone-side Customize launcher screen. The emulator remains
relevant to the stronger question of whether a listed app opens, renders, and accepts input
without a car. Reimplementing it is still substantial protocol work, but `forceParkingBrake` /
`forceUnrestricted` could make that stronger test unattended.

## Everything else that moved

**Added**
- Monetization is now a **subscription**: `utils/SubscriptionManager`, `SubscriptionFlowHelper`,
  `ProStatusHelper`. `TermsActivity` is new.
- Anti-tamper: `utils/SecurityChecker`, `IntegrityChecker`, `StringObfuscator`.
- Catalog/updates: `services/GitHubReleasesService`, `FirebaseGitHubCache`,
  `utils/GitHubCircuitBreaker`, `workers/UpdateCheckWorker`, `utils/AppUpdatesNotificationManager`,
  `UpdateNotificationManager`.
- `AAADApplication`, `QRScannerActivity` (ML Kit — `assets/mlkit_barcode_models` replaces the
  blikoon/zxing scanner), `utils/GoogleBackupManager`, `utils/RetryHelper`,
  `utils/ErrorCategorizer` / `ErrorMessageHelper` / `AppError` / `RecoveryAction`,
  `services/AAADMessagingService`, `utils/DebugLogger`, `utils/AppConstants`, `MemoryConstants`.
- Manifest `<queries>` gains `com.android.chrome` and `com.google.android.apps.maps`.

**Removed**
- `TransferLicense` (the legacy QR transfer), `User`, `OnboardingActivity` + `OnboardingPagerAdapter`
  (old onboarding), `utils/BottomDialog` and its `HeaderBinding` / `AppButtonBinding` /
  `DialogLayoutBinding` layouts.

That last one is worth noting: upstream also dropped BottomDialogs, the JitPack dependency that
broke this fork's build twice ([TASKS.md](../TASKS.md) T-19).

## Bug in our tree, fixed upstream

v2.1's manifest `<queries>` lists **`maps.jaoloonda.android`**. v2.8.5 lists
**`maps.jaoolonda.android`** — `jaoolonda`, not `jaoloonda`. The catalog confirms the latter is
the real CarStream package, so v2.1 (and therefore this repo's source drop) queries a package that
does not exist and never detects CarStream as installed. Fixed in
`app/src/main/AndroidManifest.xml`.

## What this changes for the fork

1. **T-06 gains a working reference.** In-process signing is viable and proven:
   `com.android.apksig.ApkSigner` or `net.fornwall.apksigner.ZipSigner`. No `dalvikvm`, no
   external binary, no BouncyCastle.
2. **Package renaming may be required for the mirroring family.** If the fork downloads from the
   original publishers ([standalone.md](standalone.md) T-15), it gets the publishers' package
   names — not the `maps.*` names those apps install under today. Whether AA visibility depends on
   the rename is unverified and should be tested before designing around it.
3. **Adopt `SmartInstaller`'s ladder shape** for T-06 — Shizuku+Play attribution, Shizuku direct,
   plain PackageInstaller, with an honest capability report.
4. **Head-unit behavior has a third test option** — an on-device head unit emulator.
5. The standalone fork deliberately does **not** follow 2.8.5's subscription, anti-tamper, or
   Firebase-cache additions.
