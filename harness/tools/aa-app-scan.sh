#!/data/data/com.termux/files/usr/bin/bash
#
# Captures what Android Auto says about a package while it enumerates apps.
#
#   ./aa-app-scan.sh <serial> [package-fragment]
#
# Run this, THEN connect the phone to the head unit (or start Android Auto's own head unit server
# from its overflow menu). Android Auto rebuilds its app list on connection, and gearhead is
# noisy about what it accepts and rejects — that log is the only window into a decision the
# platform otherwise keeps entirely private.
#
# Why this exists: a carified clone can satisfy every phone-side precondition and still not appear
# in the car. All of these were checked on a clone that did not show up, and all were identical to
# one that did:
#   * resolves for MAIN + android.intent.category.CAR_LAUNCHER
#   * installer=com.android.vending
#   * enabled, not stopped, not suspended
#   * automotive_app_desc resolving and declaring <uses name="projection"/>
#   * distractionOptimized on the application and the launcher activity
# So the difference lives inside Android Auto, and this is how to see it.
set -euo pipefail

SERIAL="${1:?usage: aa-app-scan.sh <serial> [package-fragment]}"
FRAGMENT="${2:-aaad}"

echo "Clearing logcat on $SERIAL"
adb -s "$SERIAL" logcat -c

cat <<EOF

Now connect the phone to the head unit (or start Android Auto's head unit server).
Watching for gearhead lines mentioning "$FRAGMENT", plus app-list activity.
Press Ctrl-C when the car screen has finished loading.

EOF

# gearhead logs under many tags, so filter by content rather than guessing tag names. The second
# pattern catches rejections that name no package at all, which is the interesting case when a
# clone is silently dropped.
adb -s "$SERIAL" logcat -v time \
  | grep -Ei -- "$FRAGMENT|GH\.|gearhead|CarAppMan|projection|allowlist|whitelist|package.*(reject|filter|skip|unsupported)" \
  | grep -v "SharedNotificationListener"
