#!/data/data/com.termux/files/usr/bin/bash
#
# Prints the apps Android Auto will actually show in the car — read off the PHONE, no head unit.
#
#   ./aa-launcher-list.sh <serial> [text-to-find]
#
# With a second argument it stops at the first match and exits 0/1, which is what you want when
# iterating on one app rather than surveying the device.
#
# Android Auto's "Customize launcher" screen (gearhead's LauncherAppSettingsActivity) is the app
# list it builds for the car. Reading it answers, in seconds and on the desk, the question this
# project otherwise had to answer by driving: will Android Auto show this app?
#
# That matters because every other signal lies by omission. An app can resolve for
# MAIN + CAR_LAUNCHER, be installer=com.android.vending, be enabled, and declare a perfectly good
# automotive_app_desc with <uses name="projection"/> — and still not be here. `dumpsys` exposes
# nothing about this list, and release gearhead logs nothing useful, so this screen is the only
# window there is.
set -euo pipefail

SERIAL="${1:?usage: aa-launcher-list.sh <serial> [text-to-find]}"
FIND="${2:-}"
GEARHEAD=com.google.android.projection.gearhead
SETTINGS="$GEARHEAD/$GEARHEAD.companion.settings.DefaultSettingsActivity"
DUMP="${TMPDIR:-$PREFIX/tmp}/aa-ui-$$.xml"
REMOTE_DUMP="/sdcard/aaad-ui-$$.xml"
TOOK_FOCUS=0

# UI Automator can dump a secure lock screen successfully, so without this guard the script
# scrolls/taps the keyguard for half a minute and misleadingly reports the requested app absent.
POLICY="$(adb -s "$SERIAL" shell dumpsys window policy 2>/dev/null || true)"
if printf '%s\n' "$POLICY" | grep -Eq \
    '^[[:space:]]+(showing=true|mIsShowing=true|mInputRestricted=true)'; then
  echo "Device is locked — unlock it before reading Android Auto's launcher list."
  exit 2
fi

cleanup() {
  rm -f -- "$DUMP" "$DUMP.txt"
  adb -s "$SERIAL" shell rm -f -- "$REMOTE_DUMP" >/dev/null 2>&1 || true
  if [ "$TOOK_FOCUS" -eq 1 ]; then
    adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

adb -s "$SERIAL" shell "am start -n $SETTINGS" >/dev/null 2>&1
TOOK_FOCUS=1

# Wait for the settings screen before scrolling. Swiping before it renders is a no-op, which
# leaves "Customize launcher" at the bottom of the screen where a tap opens Recents instead.
for _ in $(seq 1 20); do
  sleep 0.5
  case "$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=.*')" in
    *SettingsActivity*) break;;
  esac
done

# Parsed on the host: an awk that exits early closes the pipe under it, and `tr` then dies with
# "write error: Broken pipe" before the coordinates are ever printed.
tap_node() {
  adb -s "$SERIAL" shell uiautomator dump "$REMOTE_DUMP" >/dev/null 2>&1
  adb -s "$SERIAL" shell cat "$REMOTE_DUMP" 2>/dev/null > "$DUMP"
  python3 - "$DUMP" "$1" <<'PYEOF'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
for node in re.findall(r'<node[^>]*>', xml):
    if f'text="{sys.argv[2]}"' in node:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x = (int(b.group(1)) + int(b.group(3))) // 2
            y = (int(b.group(2)) + int(b.group(4))) // 2
            print(x, y)
        break
PYEOF
}

# Poll rather than sleep a fixed amount: the settings screen takes anywhere from half a second to
# several depending on whether gearhead is warm, and a fixed wait is either too slow or too short.
# "Customize launcher" also sits near the bottom, and tapping it where it first renders lands in
# the gesture-navigation strip and opens Recents — so scroll it up before looking.
# A dump taken mid-scroll returns stale coordinates, so wait for each measured fling to finish.
COORDS=""
for _ in 1 2 3; do
  adb -s "$SERIAL" shell input swipe 540 1800 540 1100 250 >/dev/null 2>&1
  sleep 3
  COORDS="$(tap_node 'Customize launcher')"
  [ -n "$COORDS" ] && break
done
[ -n "$COORDS" ] || { echo "Could not find 'Customize launcher' — Android Auto's settings may have moved."; exit 1; }

adb -s "$SERIAL" shell input tap $COORDS

focus=""
for _ in $(seq 1 12); do
  sleep 0.5
  focus="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=.*' | head -1)"
  case "$focus" in *LauncherAppSettings*) break;; esac
done
case "$focus" in
  *LauncherAppSettings*) ;;
  *) echo "Did not reach the app list (focus: $focus)"; exit 1;;
esac

NOISE='^(Add a shortcut to the launcher|Call a contact.*|Launcher sorting|A-Z|Exit|Customize)$'
COLLECTED="${DUMP}.txt"; : > "$COLLECTED"

for _ in 1 2 3 4 5 6; do
  adb -s "$SERIAL" shell uiautomator dump "$REMOTE_DUMP" >/dev/null 2>&1
  adb -s "$SERIAL" shell cat "$REMOTE_DUMP" 2>/dev/null > "$DUMP"
  tr '>' '\n' < "$DUMP" | grep -o 'text="[^"]\+"' | sed 's/^text="//; s/"$//' >> "$COLLECTED"
  # Early exit: when you are iterating on one app, the answer arrives on the first screen that
  # contains it and the remaining scrolls are pure latency.
  if [ -n "$FIND" ] && grep -qiF -- "$FIND" "$COLLECTED"; then
    echo "FOUND: $(grep -iF -- "$FIND" "$COLLECTED" | sort -u | tr '\n' ' ')"
    exit 0
  fi
  adb -s "$SERIAL" shell input swipe 540 1800 540 900 120 >/dev/null 2>&1
  sleep 1
done

if [ -n "$FIND" ]; then
  echo "NOT FOUND: $FIND"
  exit 1
fi

echo "Apps Android Auto will show on $SERIAL:"
sort -u "$COLLECTED" | grep -vE "$NOISE" | sed 's/^/  /'
