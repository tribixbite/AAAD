# Build setup — local (Termux) and CI

Covers [TASKS.md](../TASKS.md) Phase 1. Target design: [standalone.md](standalone.md).

## Status

**The build works on-device, end to end.** `./build-on-termux.sh debug --no-install` produces
`app/build/outputs/apk/debug/AAAD-2.1-debug.apk` (14 MB) in Termux — verified 2026-08-20:

```
package: name='sksa.aa.customapps.dev' versionCode='18' versionName='2.1'
         compileSdkVersion='36' targetSdkVersion='36'
application-label: 'AAAD (dev)'
launchable-activity: com.legs.appsforaa.LauncherActivity
Signer #1 certificate DN: C=US, O=Android, CN=Android Debug
```

First clean build: ~11 min (dependency downloads + `mergeExtDexDebug` dominate). Incremental
builds reuse the daemon and build cache.

**The APK does not run yet.** `LauncherActivity` is declared but has no source, so it crashes on
launch. That is T-04, not a build problem.

| Piece | State |
| --- | --- |
| `settings.gradle`, `gradle.properties`, wrapper (8.13), `app/proguard-rules.pro` | committed |
| `build-on-termux.sh` | committed |
| `.github/workflows/build-apk.yml`, `release.yml` | committed |
| `local.properties` | **optional** — `local.properties.example` documents the few keys |
| `google-services.json` | **no longer needed** — no Firebase |
| Missing source (12 components) | still missing; see [Recovering the missing classes](#recovering-the-missing-classes) |

An APK will not run until the missing classes exist: the manifest points `LauncherActivity` and
friends at classes with no source, so an installed build has no entry point.

## Local build on Termux

```bash
./build-on-termux.sh                    # debug, incremental, installs if a device is connected
./build-on-termux.sh debug --no-install
./build-on-termux.sh release            # debug-signed unless RELEASE_KEYSTORE is set
./build-on-termux.sh --clean --low-mem  # after a toolchain change, on a memory-tight device
```

### Why the wrapper script exists

`./gradlew assembleDebug` on its own **fails here**. AGP resolves an `aapt2` from Maven that is an
**x86_64 glibc binary** — it cannot execute on Android ARM64. The build must be pointed at an
aapt2 that runs on this device:

```
-Pandroid.aapt2FromMavenOverride=<path>
```

The script resolves that path itself, preferring a native binary:

1. `$AAPT2_BIN` if you export it.
2. A native **aarch64** ELF. On this device: `~/git/Embeddy/tools/aapt2-arm64/aapt2`
   (aapt2 2.19, statically linked aarch64).
3. `$PREFIX/bin/aapt2` — the Termux `aapt2` package. **This is not native**: it is a bash script
   that runs an x86_64 `aapt2.elf` under `qemu-x86_64` with libraries from
   `~/git/for-android/tools/x86_64-libs`. Correct, but much slower. Fine as a fallback.

There is also a **device-global** override in `~/.gradle/gradle.properties` pointing at the
Embeddy binary, which applies to every Gradle project on this box. Its inline comment calls that
binary "qemu-wrapped" — that comment is wrong; the binary is native aarch64. The script passes an
explicit `-P` override anyway so the build does not silently depend on machine-global state.

### Verified toolchain on this device

| Tool | Where | Notes |
| --- | --- | --- |
| JDK 21 | `$PREFIX/lib/jvm/java-21-openjdk` | `pacman -S openjdk-21` |
| Gradle 8.13 | wrapper (`./gradlew`) | pinned; matches AGP 8.13.1's minimum |
| Gradle 9.5.1 | `$PREFIX/bin/gradle` | system install; also configures this project successfully |
| Android SDK | `~/android-sdk` | platforms 19/30/34/35/**36**; build-tools 30.0.3, 34.0.0, 34.0.0-arm64, 35.0.0 |
| aapt2 | see above | the one real trap |
| `apksigner`, `zipalign` | `$PREFIX/bin` | Termux packages |

`compileSdk = 36` is satisfied by `platforms/android-36`, which is already installed.

Note the SDK's own `build-tools/*/aapt2` and `zipalign` are x86_64 — including inside the
`34.0.0-arm64` directory, which only contains an `aapt2-wrapper` shell script, not a native
aapt2. Use Termux's `$PREFIX/bin/zipalign` rather than the SDK copy.

### Memory

`gradle.properties` sets `-Xmx1536m` and `org.gradle.workers.max=2`, with a 15-minute daemon
idle timeout (the 3-hour default holds ~250 MB resident for nothing). `--low-mem` drops to
`-Xmx768m` and a single worker; `--slow` also disables the daemon and drops CPU/IO priority.

## CI/CD

Two workflows, both x86_64 Linux runners where the stock Maven aapt2 works — no override, no
per-environment branching.

**`.github/workflows/build-apk.yml`** — push to `main`, PRs, manual dispatch. Builds
`assembleDebug`, runs lint (non-fatal, report uploaded), uploads the APK as an artifact, and on
`main` publishes a `dev-<sha>` prerelease. **Requires no secrets at all** — a direct consequence
of the standalone design; nothing to configure for the build to go green.

**`.github/workflows/release.yml`** — `v*` tags. Refuses to run without `SIGNING_KEY`, verifies
the tag matches `versionName` in `app/build.gradle`, builds a signed `assembleRelease` with
reproducibility flags (`--no-daemon --no-parallel --no-build-cache`, commit-derived
`SOURCE_DATE_EPOCH`, `TZ=UTC`), confirms the signature with `apksigner verify --print-certs`,
shreds the keystore, and publishes the release.

Secrets for `release.yml` only:

| Secret | Contents |
| --- | --- |
| `SIGNING_KEY` | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Neither workflow runs until this repo is pushed, which needs explicit permission.

## Signing

Env-var driven, same contract as `../swype/cleverkeys` and the `android-termux-build` skill:

```
RELEASE_KEYSTORE  RELEASE_KEYSTORE_PASSWORD  RELEASE_KEY_ALIAS  RELEASE_KEY_PASSWORD
```

Environment beats `local.properties`. With `RELEASE_KEYSTORE` unset, release builds fall back to
the debug key so a local build always yields an installable APK — that APK is not distributable.

Setting a real `RELEASE_KEYSTORE` also flips `build-on-termux.sh` into distribution mode:
no daemon, no parallelism, no build cache, for byte-determinism.

Two consequences of using your own key, both intentional:

- A different signing key means a different `ANDROID_ID` for the app on Android 8+.
- Debug builds carry `applicationIdSuffix '.dev'`, so `sksa.aa.customapps.dev` installs
  **alongside** any official AAAD and can never overwrite its data. The test harness depends on
  this. `build-on-termux.sh` never runs `adb uninstall`.

## Recovering the missing classes

Removing the backend shrank the gap — `AuthManager` is no longer needed — but nine manifest
components and two utility symbols still have no source. Strategy, unchanged from the first pass:
**decompile the official APK for evidence, write your own code.**

`LICENSE.md` is MIT and this fork is personal-use only, so reading the shipped bytecode to
recover behaviour is fine; `minifyEnabled false` upstream means the release APK is unobfuscated.
Do not paste decompiled output into the tree — it will not merge and it drags the unpublished
patching logic in with it. `~/git/termux-tools/.claude/skills/smali-dex-patching.md` and
`~/git/termux-tools/docs/APKTOOL_TERMUX.md` cover doing this on-device.

Minimum spine to a running app (T-04), in dependency order:

1. `utils/Logger` — trivial wrapper; `BuildConfig.DEBUG`-gated.
2. `utils/applyBottomInsetPadding` — `View` extension applying `WindowInsets` bottom padding.
3. `LauncherActivity` — onboarding-vs-main routing; the completion flag belongs in DataStore.
4. `MainActivityNew` — the catalog screen. Reads `assets/catalog.json`
   ([standalone.md](standalone.md#catalog-format)), honours the
   `inceptive.ru/projects/s2a/download/` deep link.
5. `receivers/PackageInstallReceiver` — broadcast → refresh installed state.

Then stub `OnboardingActivity`, `OnboardingActivityNew`, `SupportActivity`, and
`AndroidAutoSetupActivity` as `finish()` shells so the manifest resolves, and fill them in later.

## Verification checklist

```bash
./gradlew projects                      # ✅ verified: lists :app
./build-on-termux.sh debug --no-install # ✅ verified: AAAD-2.1-debug.apk, 14 MB
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n sksa.aa.customapps.dev/com.legs.appsforaa.LauncherActivity
adb logcat -d -s AndroidRuntime:E       # will crash until T-04 lands — expected
```

Inspecting the result (use the native aapt2, not the qemu wrapper):

```bash
~/git/Embeddy/tools/aapt2-arm64/aapt2 dump badging app/build/outputs/apk/debug/*.apk
apksigner verify --print-certs app/build/outputs/apk/debug/*.apk
```
