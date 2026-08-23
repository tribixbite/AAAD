#!/data/data/com.termux/files/usr/bin/bash
#
# Prints the apps Android Auto will actually show in the car — read off the PHONE, no head unit.
#
#   ./aa-launcher-list.sh <serial>
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

SERIAL="${1:?usage: aa-launcher-list.sh <serial>}"
GEARHEAD=com.google.android.projection.gearhead
SETTINGS="$GEARHEAD/$GEARHEAD.companion.settings.DefaultSettingsActivity"
DUMP="${TMPDIR:-$PREFIX/tmp}/aa-ui-$$.xml"
trap 'rm -f "$DUMP"' EXIT

adb -s "$SERIAL" shell "am start -n $SETTINGS" >/dev/null 2>&1
sleep 4

# "Customize launcher" sits near the bottom; tapping it where it first renders lands in the
# gesture-navigation strip and opens Recents instead, so scroll it up first.
adb -s "$SERIAL" shell input swipe 540 1800 540 1100 250 >/dev/null 2>&1
sleep 2

# Parsed on the host: an awk that exits early closes the pipe under it, and `tr` then dies with
# "write error: Broken pipe" before the coordinates are ever printed.
tap_node() {
  adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/ui.xml 2>/dev/null > "$DUMP"
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

COORDS="$(tap_node 'Customize launcher')"
[ -n "$COORDS" ] || { echo "Could not find 'Customize launcher' — Android Auto's settings may have moved."; exit 1; }
adb -s "$SERIAL" shell input tap $COORDS
sleep 4

focus="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -o 'mCurrentFocus=.*' | head -1)"
case "$focus" in
  *LauncherAppSettings*) ;;
  *) echo "Did not reach the app list (focus: $focus)"; exit 1;;
esac

echo "Apps Android Auto will show on $SERIAL:"
for _ in 1 2 3 4 5 6; do
  adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/ui.xml 2>/dev/null > "$DUMP"
  tr '>' '\n' < "$DUMP" | grep -o 'text="[^"]\+"' | sed 's/^text="//; s/"$//'
  adb -s "$SERIAL" shell input swipe 540 1800 540 900 200 >/dev/null 2>&1
  sleep 2
done | sort -u | grep -vE '^(Add a shortcut to the launcher|Call a contact.*|Launcher sorting|A-Z|Exit|Customize)$' | sed 's/^/  /'

adb -s "$SERIAL" shell rm -f /sdcard/ui.xml >/dev/null 2>&1
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
