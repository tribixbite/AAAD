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
#
# What the clone gains (see patch_manifest.py for the manifest surgery):
#   * com.google.android.gms.car.application -> a descriptor declaring <uses name="projection"/>
#   * distractionOptimized=true on the application and the launcher activity
#   * android.intent.category.CAR_LAUNCHER on the launcher intent-filter
#   * resizeableActivity=true and no screenOrientation lock, so a phone Activity has a chance of
#     rendering usefully on a landscape head unit
#
# This WORKS for ordinary apps: confirmed on a real head unit, a clone of a plain phone app — no
# car SDK, no CATEGORY_PROJECTION service — launches full screen on the car display. Android Auto
# projects the app's existing Activity. The unofficial SDK that CarStream bundles is how an app
# builds a *custom* car UI, not how it gets onto the display.
#
# The one judgement call: an app that already ships an androidx CarAppService is declared
# `template` instead, so it presents its real templated surface rather than a projected window.
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
APKEDITOR="${APKEDITOR:-$HOME/git/termux-tools/edge-fix/tools/APKEditor.jar}"
KEYSTORE="${CARIFY_KEYSTORE:-$HOME/.aaad-carify.keystore}"
KEYSTORE_PASS="${CARIFY_KEYSTORE_PASS:-carify}"
WORK="${CARIFY_WORK:-${TMPDIR:-$PREFIX/tmp}/carify-$$}"

[ -f "$APKEDITOR" ] || { echo "APKEditor.jar not found at $APKEDITOR (set APKEDITOR=)"; exit 1; }
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

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
NEW_PACKAGE="${PACKAGE}${SUFFIX}"
echo "  $PACKAGE -> $NEW_PACKAGE"

say "Patching the manifest"
PATCH_OUT="$(python3 "$HERE/patch_manifest.py" "$WORK/decoded/AndroidManifest.xml" \
  "$PACKAGE" "$NEW_PACKAGE" " (Car)")"
echo "$PATCH_OUT" | grep -v '^CAR_' || true
CAR_USES="$(echo "$PATCH_OUT" | sed -n 's/^CAR_USES=//p')"
CAR_BACKED="$(echo "$PATCH_OUT" | sed -n 's/^CAR_BACKED=//p')"

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
adb -s "$SERIAL" push "$WORK/clone.apk" /data/local/tmp/carify.apk >/dev/null
SESSION="$(adb -s "$SERIAL" shell "pm install-create -r -i com.android.vending \
  --originating-uri 'https://play.google.com/store' --install-reason 0 $BYPASS" | grep -o '[0-9]\+' | tail -1)"
adb -s "$SERIAL" shell "pm install-write -S $SIZE $SESSION base.apk /data/local/tmp/carify.apk" >/dev/null
adb -s "$SERIAL" shell "pm install-commit $SESSION"
adb -s "$SERIAL" shell rm -f /data/local/tmp/carify.apk

say "Result"
adb -s "$SERIAL" shell "pm list packages -i $PACKAGE" | grep -E "package:($PACKAGE|$NEW_PACKAGE) " || true

echo
echo "  Declared <uses name=\"$CAR_USES\"/>. Connect the phone to the head unit and the clone"
echo "  should appear in Android Auto's launcher and open full screen."
