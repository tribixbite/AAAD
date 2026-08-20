# Build setup — from partial source drop to a compiling APK

Covers [TASKS.md](../TASKS.md) Phase 1. Read
[CLAUDE.md § Buildability](../CLAUDE.md#buildability-read-before-you-try-to-build) first for the
full list of what is missing.

## Why it doesn't build

Two independent gaps:

1. **No Gradle scaffolding.** No `settings.gradle`, so `:app` is never included. No wrapper. No
   `local.properties`, which `app/build.gradle:47` reads unconditionally — its absence fails at
   configuration time, before any compilation error is reachable. No `google-services.json`, which
   the `com.google.gms.google-services` plugin requires. No `proguard-rules.pro`, referenced at
   `app/build.gradle:110`.
2. **No source for 12 of 16 components.** The manifest declares nine activities/receivers with no
   file in the tree, and the four published classes import three more absent symbols.

Gap 1 is mechanical. Gap 2 is the real work.

## Step 1 — Gradle scaffolding (T-01)

`settings.gradle`:

```groovy
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
rootProject.name = 'AAAD'
include ':app'
```

`gradle.properties` — at minimum `android.useAndroidX=true`, plus a JVM heap suited to this device
if building in Termux. Wrapper: match AGP 8.13.1 (Gradle 8.x; check AGP's compatibility table
rather than guessing). `app/proguard-rules.pro` can start empty — the release type sets
`minifyEnabled false`, so it is referenced but not used.

Commit a `local.properties.example` alongside, so the key list is discoverable without leaking
values:

```properties
# Signing — app/build.gradle:61-68 (keyAlias is hardcoded to 'key3')
STORELOCATION=/absolute/path/to/keystore.jks
STOREPASSWORD=
KEYPASSWORD=

# Stripe — app/build.gradle:89-91
STRIPE_PUBLISHABLE_KEY=pk_test_xxx
STRIPE_PRICE_ID=price_xxx
STRIPE_PRICE_ID_PROMO=price_xxx

# Firebase — used to build the Cloud Function URL at AboutPaymentActivity.kt:178
FIREBASE_INSTANCE=https://xxx.firebaseio.com
FIREBASE_PROJECT_ID=xxx
FIREBASE_REGION=us-central1

# Catalog + per-app APK links — app/build.gradle:95-103
APP_CATALOG_URL=
AAPASSENGER_LINK=
CARSTREAM205_LINK=
CARSTREAM204_LINK=
CARSTREAM202_LINK=
AAMP_LINK=
AAMIRROR_LINK=
AAWIDGETS_LINK=
AASTREAM_LINK=
```

`sdk.dir` also belongs in the real `local.properties` if the SDK location isn't set by env.

> `local.properties`, `google-services.json`, and `*.jks`/`*.keystore` are gitignored. Keep it
> that way — the Stripe key and Firebase coordinates are in there.

## Step 2 — Firebase project (T-02)

Do **not** reuse upstream's `google-services.json`, even if you can extract one from a released
APK. It points at a production database holding other people's license state, and this app writes
entitlement values from the client ([ARCHITECTURE.md § 6](../ARCHITECTURE.md#6-data-model--firebase-realtime-database)).

Create your own project with an app registered under `sksa.aa.customapps` (or your dev
`applicationId`), enable Anonymous Auth and Realtime Database, and use the emulator suite for
local work. Long term, the `dev` flavor (T-10/T-11) should not need Firebase at all.

## Step 3 — Recover the missing classes (T-03/T-04)

Two viable strategies. Pick one, write the choice into this file, and note it in
[TASKS.md § Notes](../TASKS.md#notes-and-decisions).

**A. Reimplement against the published contract.** The `res/` tree is a surprisingly complete
specification: `strings.xml` names every state, error, and onboarding step; the layouts define the
ids; the manifest defines the components, intents, and the package list the installed-state logic
tracks. Slower, but you end up owning code you understand, and it makes the de-gating in Phase 2
trivial rather than surgical.

**B. Decompile the official release APK for reference.** `LICENSE.md` is MIT and this fork is
personal-use only, so reading the shipped bytecode to recover behaviour is fine for your own build.
Fastest way to answer the [§ 7.3 open questions](../ARCHITECTURE.md#73-v21--what-changed) (T-12),
because the authorization call is right there. `minifyEnabled false` means the release APK is
unobfuscated — class and method names survive.

Recommended: **B for evidence, A for code.** Decompile to answer specific questions and to confirm
the gating contract, then write your own implementation. Do not paste decompiled output into the
tree — it's unreadable, it will not merge with upstream, and it drags the unpublished patching
logic in with it.

Minimum spine to a running app (T-04), in dependency order:

1. `utils/Logger` — trivial; `AboutPaymentActivity.kt` calls `Logger.d/e`.
2. `utils/applyBottomInsetPadding` — a `View` extension applying `WindowInsets` bottom padding
   (edge-to-edge is enabled at `AboutPaymentActivity.kt:45`).
3. `managers/AuthManager` — Kotlin `object` with `suspend fun ensureAuthenticated(): String` and
   `fun getCurrentUid(): String?`. Both call sites treat the UID as the entitlement key.
4. `LauncherActivity` — decides onboarding vs. main; the onboarding-completion flag's storage is
   unknown, DataStore is the reasonable choice given the dependency set.
5. `MainActivityNew` — the catalog. Must honour `refresh_pro_status` (see
   [ARCHITECTURE.md § 5](../ARCHITECTURE.md#5-navigation-graph)) and the
   `inceptive.ru/projects/s2a/download/` deep link.
6. `receivers/PackageInstallReceiver` — broadcast → refresh installed state.

Stub the remaining five activities (`OnboardingActivity`, `OnboardingActivityNew`,
`ProVersionActivity`, `LicenseTransferActivity`, `SupportActivity`, `AndroidAutoSetupActivity`) as
`finish()`-immediately shells so the manifest resolves, then fill them in as needed.

## Step 4 — Signing and coexistence (T-05)

`app/build.gradle` has exactly one signing config (`nuova`) and applies it to `defaultConfig`, so
every build type wants the upstream keystore. Add a debug config using a locally generated key.

Two consequences worth planning around:

- A different signing key changes `ANDROID_ID` for the app on Android 8+, so a dev build gets a
  different device identity than an installed official build.
- The dev build cannot upgrade over an installed official build. Give it an `applicationIdSuffix`
  (e.g. `.dev`) so both can be installed at once — which the test harness wants anyway.

## Building on this device

Termux specifics that matter: `bun`/`bunx` rather than npm for the harness and dash; `$PREFIX/tmp`
rather than `/tmp`; `rg` rather than `grep` (the shell wraps `grep` and injects `-G`). Whether the
full Android toolchain runs locally or the APK is built elsewhere and side-loaded is an open
question — resolve it during T-01 and record the answer here.

## Verification checklist

```bash
./gradlew projects                      # T-01: :app is listed
./gradlew :app:processDebugResources    # T-02: google-services + local.properties resolve
./gradlew :app:assembleDebug            # T-04: compiles
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n sksa.aa.customapps/com.legs.appsforaa.LauncherActivity
adb logcat -d -s AndroidRuntime:E       # no crash on launch
```
