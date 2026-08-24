#!/data/data/com.termux/files/usr/bin/bash
#
# Builds a side-by-side, Android-Auto-visible clone of an installed app.
#
#   ./carify.sh <serial> <package> [suffix]     # clone an app installed on a device
#   ./carify.sh --apk <file.apk> [suffix]       # clone a downloaded APK, no device needed
#
# The clone is a DIFFERENT package (<package><suffix>), so it installs alongside the original and
# never touches it. That is the whole design: the original keeps its signature, its data, and its
# publisher updates, while the clone is the one that has been re-signed and declared to Android
# Auto. Re-signing is unavoidable — the manifest and resource table both change — and a re-signed
# app can never receive the publisher's updates, so it must not BE the user's copy.
#   CARIFY_PACKAGE=maps.example.android ./carify.sh ... # explicit replacement identity
#
#
# What the clone gains (see patch_manifest.py for the manifest surgery):
#   * an AndroidX car runtime payload when the APK does not already contain car code
#   * an AndroidX template descriptor, CarAppService, and runtime components
#   * distractionOptimized=true on the application and the launcher activity
#   * DEFAULT + CAR_LAUNCHER + NAVIGATION + APP_MAPS on the launcher intent-filter
#   * appCategory=game, the Android Auto parked-app route available to sideloaded Activities
#   * resizeableActivity=true and no screenOrientation lock, so a phone Activity has a chance of
#     rendering usefully on a landscape head unit
#
# Existing car implementations are preserved. Ordinary phone apps receive a real CarAppService,
# but Android Auto will not admit a shell-initiated Car App Library package as trusted merely
# because its installer label says Play. The parked-game route is the only honest, generally
# discoverable sideload route; Android Auto disables these copies while moving.
#
# Requires: java, APKEditor.jar, zipalign, apksigner, keytool, adb.
set -euo pipefail

# Two input modes. The APK-file mode exists because the transformation needs no device at all —
# only the install does — and a publisher's download is often the thing you want to fix before it
# has ever been installed.
APK_INPUT=""
if [ "${1:-}" = "--apk" ]; then
  APK_INPUT="${2:?usage: carify.sh --apk <file.apk> [suffix]}"
  SUFFIX="${3:-.aaad}"
  SERIAL=""
  [ -f "$APK_INPUT" ] || { echo "no such APK: $APK_INPUT"; exit 1; }
else
  SERIAL="${1:?usage: carify.sh <serial> <package> [suffix] | --apk <file.apk> [suffix]}"
  PACKAGE="${2:?usage: carify.sh <serial> <package> [suffix] | --apk <file.apk> [suffix]}"
  SUFFIX="${3:-.aaad}"
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APKEDITOR="${APKEDITOR:-$HOME/git/termux-tools/edge-fix/tools/APKEditor.jar}"
BAKSMALI="${BAKSMALI:-$HOME/git/termux-tools/edge-fix/tools/baksmali-3.0.9-fat.jar}"
BRIDGE_DIR="$ROOT/carify-bridge/build/outputs/apk/release"
KEYSTORE="${CARIFY_KEYSTORE:-$HOME/.aaad-carify.keystore}"
KEYSTORE_PASS="${CARIFY_KEYSTORE_PASS:-carify}"

[ -f "$APKEDITOR" ] || { echo "APKEditor.jar not found at $APKEDITOR (set APKEDITOR=)"; exit 1; }
[ -f "$BAKSMALI" ] || { echo "baksmali not found at $BAKSMALI (set BAKSMALI=)"; exit 1; }

# A caller-provided work directory is useful for inspecting a failed transformation, so never
# recursively delete it. Default runs use a unique directory that this script created itself.
if [ -n "${CARIFY_WORK:-}" ]; then
  WORK="$CARIFY_WORK"
  mkdir -p "$WORK"
  CLEAN_WORK=0
else
  WORK="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/carify.XXXXXX")"
  CLEAN_WORK=1
fi
DEVICE_TMP=""
INSTALL_SESSION=""

cleanup() {
  if [ -n "$SERIAL" ] && [ -n "$INSTALL_SESSION" ]; then
    adb -s "$SERIAL" shell pm install-abandon "$INSTALL_SESSION" >/dev/null 2>&1 || true
  fi
  if [ -n "$SERIAL" ] && [ -n "$DEVICE_TMP" ]; then
    adb -s "$SERIAL" shell rm -f -- "$DEVICE_TMP" >/dev/null 2>&1 || true
  fi
  if [ "$CLEAN_WORK" -eq 1 ]; then
    rm -rf -- "$WORK"
  fi
}
trap cleanup EXIT

say() { printf '\n== %s\n' "$*"; }

if [ -n "$APK_INPUT" ]; then
  say "Using $APK_INPUT"
  cp "$APK_INPUT" "$WORK/original.apk"
else
  say "Pulling $PACKAGE from $SERIAL"
  mapfile -t PATHS < <(adb -s "$SERIAL" shell "pm path $PACKAGE" | sed 's/^package://' | tr -d '\r' | grep -v '^$')
  [ "${#PATHS[@]}" -gt 0 ] || { echo "not installed: $PACKAGE"; exit 1; }

  if [ "${#PATHS[@]}" -eq 1 ]; then
    adb -s "$SERIAL" pull "${PATHS[0]}" "$WORK/original.apk" >/dev/null
  else
  # A split app is MERGED into a single APK rather than cloned split-by-split. Re-staging N
  # re-signed splits through one install session would work, but the clone would then inherit the
  # original's split layout for no benefit — and every config split it lacks (a density, an ABI,
  # a language) becomes a missing-resource crash at runtime. Merging collapses that whole class of
  # failure: one APK, every resource present, installs like any other.
    echo "  ${#PATHS[@]} APKs (split app) — merging"
    mkdir -p "$WORK/splits"
    for path in "${PATHS[@]}"; do
      adb -s "$SERIAL" pull "$path" "$WORK/splits/$(basename "$path")" >/dev/null
    done
    java -jar "$APKEDITOR" m -i "$WORK/splits" -o "$WORK/original.apk" >/dev/null
  fi
fi

say "Decoding"
java -jar "$APKEDITOR" d -i "$WORK/original.apk" -o "$WORK/decoded" -t xml >/dev/null

# The package name is read from the APK rather than trusted from the caller, so --apk mode needs
# no second argument and device mode cannot be given a name that disagrees with the file.
PACKAGE="$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$WORK/decoded/AndroidManifest.xml" | head -1)"
NEW_PACKAGE="${CARIFY_PACKAGE:-${PACKAGE}${SUFFIX}}"
case "$NEW_PACKAGE" in
  ""|.*|*..*|*[^a-zA-Z0-9._]*)
    echo "invalid clone package: $NEW_PACKAGE"; exit 1 ;;
esac
[ "$NEW_PACKAGE" != "$PACKAGE" ] || {
  echo "clone package must differ from the publisher package"; exit 1;
}
echo "  $PACKAGE -> $NEW_PACKAGE"

say "Patching the manifest"
PATCH_OUT="$(python3 "$HERE/patch_manifest.py" "$WORK/decoded/AndroidManifest.xml" \
  "$PACKAGE" "$NEW_PACKAGE" " (Car)" "${CARIFY_DISCOVERY:-template}")"
echo "$PATCH_OUT" | grep -v '^CAR_' || true
CAR_USES="$(echo "$PATCH_OUT" | sed -n 's/^CAR_USES=//p')"
CAR_NEEDS_BRIDGE="$(echo "$PATCH_OUT" | sed -n 's/^CAR_NEEDS_BRIDGE=//p')"

say "Adding the car descriptor"
RES_DIR="$(dirname "$(find "$WORK/decoded/resources" -maxdepth 3 -name package.json | head -1)")/res"
mkdir -p "$RES_DIR/xml"
cat > "$RES_DIR/xml/automotive_app_desc.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="$CAR_USES"/>
</automotiveApp>
XML

# APKEditor builds from the resource TABLE, not from files on disk: a file with no <public> entry
# is "Local resource not defined". The id has to be a free one in the app's own xml type, so it is
# derived from the highest existing entry rather than hardcoded.
python3 - "$RES_DIR/values/public.xml" <<'PY'
import re, sys
path = sys.argv[1]
s = open(path, encoding='utf-8').read()
if 'name="automotive_app_desc"' in s:
    print("  descriptor already declared"); raise SystemExit
xml_ids = [int(m, 16) for m in re.findall(r'id="0x([0-9a-f]{8})" type="xml"', s)]
if xml_ids:
    new_id = max(xml_ids) + 1
    anchor = [l for l in s.splitlines() if f'0x{max(xml_ids):08x}' in l][0]
else:
    # No xml type at all: take a fresh type id above every type the app already uses.
    types = {int(m, 16) >> 16 for m in re.findall(r'id="0x([0-9a-f]{8})"', s)}
    new_id = ((max(types) + 1) << 16)
    anchor = [l for l in s.splitlines() if '<public ' in l][-1]
entry = f'  <public id="0x{new_id:08x}" type="xml" name="automotive_app_desc" />'
open(path, 'w', encoding='utf-8').write(s.replace(anchor, anchor + "\n" + entry))
print(f"  declared automotive_app_desc as 0x{new_id:08x}")
PY

say "Relabelling so the clone is distinguishable"
python3 - "$RES_DIR" <<'PY'
import glob, re, sys

n = 0
for f in glob.glob(f"{sys.argv[1]}/values*/strings.xml"):
    s = open(f, encoding='utf-8').read()
    new, c = re.subn(r'(<string name="app_name">)([^<]*?)( \(Car\))?(</string>)', r'\1\2 (Car)\4', s)
    if c:
        open(f, 'w', encoding='utf-8').write(new); n += c
print(f"  labels patched: {n}")
PY

if [ "$CAR_NEEDS_BRIDGE" = "yes" ]; then
  # Do not place a second Car App runtime behind the same class names. This can happen when an APK
  # bundles the library but never declares its service; Android's first-definition-wins class
  # loading would then silently mix versions. Existing declared car services took the no-bridge
  # branch above, so reaching this check means the APK is incomplete and needs a deliberate merge.
  EXISTING_CAR_RUNTIME="$(find "$WORK/decoded/smali" -path '*/androidx/car/app/CarAppService.smali' -print -quit)"
  [ -z "$EXISTING_CAR_RUNTIME" ] || {
    echo "APK contains an undeclared Car App runtime; refusing to inject a duplicate"; exit 1;
  }

  say "Building the car bridge"
  BRIDGE_APK="$(find "$BRIDGE_DIR" -maxdepth 1 -name '*.apk' -type f 2>/dev/null | head -1)"
  STALE=""
  if [ -n "$BRIDGE_APK" ]; then
    STALE="$(find "$ROOT/carify-bridge/src" "$ROOT/carify-bridge/build.gradle" \
      -type f -newer "$BRIDGE_APK" -print -quit)"
  fi
  if [ -z "$BRIDGE_APK" ] || [ -n "$STALE" ]; then
    "$ROOT/build-on-termux.sh" bridge --no-install
    BRIDGE_APK="$(find "$BRIDGE_DIR" -maxdepth 1 -name '*.apk' -type f | head -1)"
  fi
  [ -f "$BRIDGE_APK" ] || { echo "Carify bridge build produced no APK"; exit 1; }
  echo "  payload: $BRIDGE_APK"

  say "Injecting the car bridge"
  mkdir -p "$WORK/bridge-dex"
  unzip -q "$BRIDGE_APK" 'classes*.dex' -d "$WORK/bridge-dex"

  NEXT_DEX=1
  for smali_dir in "$WORK/decoded/smali"/classes*; do
    [ -d "$smali_dir" ] || continue
    name="$(basename "$smali_dir")"
    number="${name#classes}"
    [ -n "$number" ] || number=1
    [ "$number" -ge "$NEXT_DEX" ] && NEXT_DEX=$((number + 1))
  done
  for dex in "$WORK/bridge-dex"/classes*.dex; do
    destination="$WORK/decoded/smali/classes$NEXT_DEX"
    java -jar "$BAKSMALI" d "$dex" -o "$destination"
    echo "  $(basename "$dex") -> smali/$(basename "$destination")"
    NEXT_DEX=$((NEXT_DEX + 1))
  done

  # Car App Library's handshake reads its version from the payload APK's resource table. Carify
  # injects DEX, not that unrelated table, so the compiled 0x7f... id would point at an arbitrary
  # resource in the cloned app. Replace the one lookup with the version this payload is pinned to.
  APP_INFO="$(find "$WORK/decoded/smali" -path '*/androidx/car/app/AppInfo.smali' | tail -1)"
  [ -f "$APP_INFO" ] || { echo "Injected bridge is missing androidx.car.app.AppInfo"; exit 1; }
  python3 - "$APP_INFO" <<'PY'
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().splitlines()
start = next(
    (i for i, line in enumerate(lines)
     if "Context;->getResources()Landroid/content/res/Resources;" in line),
    None,
)
if start is None:
    raise SystemExit("Car App AppInfo resource lookup changed; refusing an unsafe payload")
seen_get_string = False
end = None
for i in range(start, len(lines)):
    if "Resources;->getString(I)Ljava/lang/String;" in lines[i]:
        seen_get_string = True
    elif seen_get_string and "move-result-object p0" in lines[i]:
        end = i
        break
if end is None:
    raise SystemExit("Car App AppInfo version lookup changed; refusing an unsafe payload")
lines[start:end + 1] = ['    const-string p0, "1.7.0"']
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("  made Car App library version package-independent")
PY
fi

say "Building"
java -jar "$APKEDITOR" b -i "$WORK/decoded" -o "$WORK/unsigned.apk" >/dev/null

say "Signing"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" \
    -keypass "$KEYSTORE_PASS" -alias carify -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AAAD Carify, OU=Personal, O=AAAD, L=NA, S=NA, C=NA" >/dev/null 2>&1
  echo "  generated $KEYSTORE — keep it; without it the clone can never be updated in place"
fi
zipalign -p -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" \
  --out "$WORK/clone.apk" "$WORK/aligned.apk"

if [ -z "$SERIAL" ]; then
  OUT="${CARIFY_OUT:-$(dirname "$APK_INPUT")/$(basename "${APK_INPUT%.apk}")-car.apk}"
  cp "$WORK/clone.apk" "$OUT"
  say "Wrote $OUT"
  echo "  install it with Play Store attribution, or Android Auto will not list it:"
  echo "    bun run src/cli.ts convert --packages $NEW_PACKAGE   # after installing"
  echo
  echo "  Declared <uses name=\"$CAR_USES\"/>."
  exit 0
fi

say "Installing $NEW_PACKAGE with Play Store attribution"
SDK="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
BYPASS=""; [ "$SDK" -ge 34 ] && BYPASS="--bypass-low-target-sdk-block"
SIZE="$(stat -c%s "$WORK/clone.apk")"
DEVICE_TMP="/data/local/tmp/aaad-carify-$$.apk"
adb -s "$SERIAL" push "$WORK/clone.apk" "$DEVICE_TMP" >/dev/null
SESSION="$(adb -s "$SERIAL" shell "pm install-create -r -i com.android.vending \
  --originating-uri 'https://play.google.com/store' --install-reason 0 $BYPASS" | grep -o '[0-9]\+' | tail -1)"
INSTALL_SESSION="$SESSION"
adb -s "$SERIAL" shell "pm install-write -S $SIZE $SESSION base.apk $DEVICE_TMP" >/dev/null
adb -s "$SERIAL" shell "pm install-commit $SESSION"
INSTALL_SESSION=""
adb -s "$SERIAL" shell rm -f -- "$DEVICE_TMP"
DEVICE_TMP=""

say "Result"
adb -s "$SERIAL" shell "pm list packages -i" | grep -E "package:($PACKAGE|$NEW_PACKAGE) " || true

echo
echo "  Declared <uses name=\"$CAR_USES\"/>."
echo "  Check the clone's visible label is listed:  tools/aa-launcher-list.sh $SERIAL"
