#!/data/data/com.termux/files/usr/bin/bash
#
# Build AAAD on Termux ARM64.
#
# Usage:
#   ./build-on-termux.sh [debug|release] [flags...]
#
# Flags:
#   --clean        Force a clean build (default: incremental)
#   --low-mem      Constrain the JVM further (-Xmx768m, single worker)
#   --slow         No daemon, single worker, lowest CPU/IO priority
#   --no-install   Build only; skip the ADB install step
#   --help, -h     Show this message
#
# Env overrides:
#   AAPT2_BIN         Path to an aapt2 that runs on this device (auto-detected otherwise)
#   ANDROID_HOME      Default: $HOME/android-sdk
#   JAVA_HOME         Default: $PREFIX/lib/jvm/java-21-openjdk
#   RELEASE_KEYSTORE  + _PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
#                     When set, release builds are signed with your key and treated as
#                     distribution builds (deterministic flags, slower).
#
# Why this script exists: `./gradlew assembleDebug` on its own fails here. AGP downloads an
# aapt2 from Maven that is an x86_64 glibc binary, which cannot execute on Android ARM64.
# The build must be pointed at an aapt2 that runs natively (or under qemu). See DEVICE NOTES
# at the bottom of docs/build-setup.md.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

say()  { printf '%s\n' "$*"; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
show_help() { sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \?//'; exit 0; }

# --- Arguments ----------------------------------------------------------------
BUILD_TYPE=""
CLEAN=0; LOW_MEM=0; SLOW=0; NO_INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --clean)      CLEAN=1 ;;
        --low-mem)    LOW_MEM=1 ;;
        --slow)       SLOW=1 ;;
        --no-install) NO_INSTALL=1 ;;
        --help|-h)    show_help ;;
        debug|release) BUILD_TYPE="$arg" ;;
        *) fail "unknown argument '$arg'. Run '$0 --help'." ;;
    esac
done
BUILD_TYPE="${BUILD_TYPE:-debug}"

# --- Environment --------------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Deterministic output: fixed locale/timezone and a commit-derived timestamp.
export TZ=UTC LANG=C.UTF-8 LC_ALL=C.UTF-8
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$SCRIPT_DIR" log -1 --format=%ct 2>/dev/null || printf '0')}"

command -v java >/dev/null 2>&1 || fail "java not found. Install with: pacman -S openjdk-21"
[ -d "$ANDROID_HOME/platforms/android-36" ] \
    || fail "platforms/android-36 missing under $ANDROID_HOME (compileSdk = 36)."

# --- aapt2 selection ----------------------------------------------------------
# Preference order:
#   1. $AAPT2_BIN                                  — explicit override
#   2. a native aarch64 aapt2 found on this device — fastest
#   3. $PREFIX/bin/aapt2                           — Termux package; a qemu-x86_64 wrapper,
#                                                    correct but markedly slower
resolve_aapt2() {
    local candidate
    if [ -n "${AAPT2_BIN:-}" ]; then
        [ -x "$AAPT2_BIN" ] || fail "AAPT2_BIN=$AAPT2_BIN is not executable"
        printf '%s' "$AAPT2_BIN"; return 0
    fi
    # Known native aarch64 builds on this device, plus the SDK's own arm64 drop-in if present.
    for candidate in \
        "$HOME/git/Embeddy/tools/aapt2-arm64/aapt2" \
        "$ANDROID_HOME/build-tools/34.0.0-arm64/aapt2" \
        "$PREFIX/bin/aapt2"
    do
        [ -x "$candidate" ] || continue
        # Prefer a real aarch64 ELF; accept a wrapper script as the fallback.
        # `case` rather than grep: this device's login profile wraps grep with an injected -G.
        case "$(file -b "$candidate" 2>/dev/null)" in
            *"ARM aarch64"*) printf '%s' "$candidate"; return 0 ;;
        esac
        AAPT2_FALLBACK="${AAPT2_FALLBACK:-$candidate}"
    done
    [ -n "${AAPT2_FALLBACK:-}" ] && { printf '%s' "$AAPT2_FALLBACK"; return 0; }
    return 1
}
AAPT2="$(resolve_aapt2)" || fail "no usable aapt2 found. Install with: pacman -S aapt2"
say "aapt2: $AAPT2"

# --- Gradle tuning ------------------------------------------------------------
JVM_MEM="-Xmx1536m -XX:MaxMetaspaceSize=384m"
WORKERS=2
EXTRA_FLAGS=(--parallel --build-cache)
DAEMON_FLAG=""
NICE_PREFIX=()

[ "$LOW_MEM" -eq 1 ] && { JVM_MEM="-Xmx768m -XX:MaxMetaspaceSize=256m"; WORKERS=1; }
[ "$SLOW" -eq 1 ] && { DAEMON_FLAG="--no-daemon"; WORKERS=1; NICE_PREFIX=(nice -n 19 ionice -c 3); }

# A release build with a real keystore is a distribution build: trade speed for
# byte-determinism. Debug builds never take this path — RELEASE_KEYSTORE is often exported
# device-wide for another project, and letting that silently disable the daemon and the build
# cache makes every debug build needlessly slow.
if [ "$BUILD_TYPE" = "release" ]; then
    if [ -n "${RELEASE_KEYSTORE:-}" ] && [ -f "${RELEASE_KEYSTORE}" ]; then
        say "Distribution build: reproducibility flags on (no daemon, no parallel, no cache)"
        say "  keystore: $RELEASE_KEYSTORE"
        DAEMON_FLAG="--no-daemon"
        EXTRA_FLAGS=(--no-parallel --no-build-cache)
    else
        say "WARNING: no usable RELEASE_KEYSTORE in env — release will be signed with the debug key."
        say "         That APK is for local use only; do not distribute it."
    fi
fi

if [ "$CLEAN" -eq 1 ]; then
    say "Cleaning..."
    ./gradlew --stop >/dev/null 2>&1 || true
    ./gradlew clean --console=plain || say "  clean failed, continuing"
fi

# --- Build --------------------------------------------------------------------
case "$BUILD_TYPE" in
    release) TASK="assembleRelease"; APK_DIR="app/build/outputs/apk/release" ;;
    debug)   TASK="assembleDebug";   APK_DIR="app/build/outputs/apk/debug" ;;
esac

LOG_FILE="build-${BUILD_TYPE}-$(date +%Y%m%d-%H%M%S).log"
say "Building $TASK (log: $LOG_FILE)"

"${NICE_PREFIX[@]}" ./gradlew "$TASK" \
    -Dorg.gradle.jvmargs="$JVM_MEM" \
    -Dorg.gradle.workers.max="$WORKERS" \
    -Pandroid.aapt2FromMavenOverride="$AAPT2" \
    ${DAEMON_FLAG} \
    "${EXTRA_FLAGS[@]}" \
    --warning-mode=none --console=plain 2>&1 | tee "$LOG_FILE"
RC=${PIPESTATUS[0]}

if [ "$RC" -ne 0 ]; then
    say ""
    say "=== BUILD FAILED (gradle exit $RC) ==="
    say "Log: $LOG_FILE"
    say "Common causes on this device:"
    say "  1. aapt2 mismatch — try AAPT2_BIN=<path> $0 $BUILD_TYPE"
    say "  2. OOM            — re-run with --low-mem or --slow"
    say "  3. Missing SDK    — check \$ANDROID_HOME/platforms/android-36"
    exit "$RC"
fi

APK_PATH=$(find "$APK_DIR" -name '*.apk' 2>/dev/null | head -1)
[ -n "$APK_PATH" ] || fail "gradle reported success but no APK in $APK_DIR"

say ""
say "=== BUILD SUCCESSFUL ==="
ls -lh "$APK_DIR"/*.apk

[ "$NO_INSTALL" -eq 1 ] && { say "Skipping install (--no-install)."; exit 0; }

# --- Install ------------------------------------------------------------------
# Debug builds carry applicationIdSuffix '.dev', so they install alongside any official
# AAAD and cannot touch its data. This script NEVER uninstalls anything.
command -v adb >/dev/null 2>&1 || { say "adb not found; APK is at $APK_PATH"; exit 0; }

if ! adb devices | awk 'NR>1 && $2=="device" {f=1} END {exit !f}'; then
    say "No connected device. This box's wireless-debugging port rotates — rediscover with:"
    say "  nmap -sT -p 30000-65535 --open -n 127.0.0.1"
    say "then 'adb connect 127.0.0.1:<port>' for each open port."
    say "APK is at $APK_PATH"
    exit 0
fi

say "Installing $APK_PATH ..."
INSTALL_OUT=$(adb install -r "$APK_PATH" 2>&1); INSTALL_RC=$?
if [ "$INSTALL_RC" -eq 0 ]; then
    say "=== INSTALLED ==="
    [ "$BUILD_TYPE" = "debug" ] && say "Installed as sksa.aa.customapps.dev (coexists with any official build)."
    exit 0
fi

say "Install failed:"
printf '%s\n' "$INSTALL_OUT" | sed 's/^/    /'
case "$INSTALL_OUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
        say ""
        say "DIAGNOSIS: signing key mismatch — an APK with this package id is installed under a"
        say "different key. Existing app data is SAFE; Android rejected the replace, nothing was"
        say "written. Do NOT 'adb uninstall' to work around it without deciding that data loss is"
        say "acceptable." ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
        say ""
        say "DIAGNOSIS: the installed APK has a higher versionCode. Data is SAFE; Android"
        say "refused the downgrade." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
        say ""
        say "DIAGNOSIS: device storage full." ;;
esac
exit 1
