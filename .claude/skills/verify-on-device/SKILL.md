---
name: verify-on-device
description: Use when changing AAAD app code and needing to confirm it works on a real phone — building, installing, driving install/convert/status, capturing screenshots, and checking installer attribution. Covers the adb, Shizuku and screenshot traps specific to these devices.
---

# Verifying AAAD on a real device

Nothing in this project counts as working until a phone says so. Three separate bugs shipped
looking correct and were only caught here: an install that silently fell back to an unattributed
one, a search box whose action never fired, and inset padding that was being discarded.

## 1. Build

```bash
./build-on-termux.sh debug --no-install
```

Never `./gradlew assembleDebug` directly — AGP's Maven `aapt2` is an x86_64 binary and cannot
execute on Android ARM64. The script resolves a usable one. See `docs/build-setup.md`.

## 2. Reach the device

```bash
adb connect 192.168.1.243:5555      # Saga: fixed port, rooted, Shizuku works here
```

If a phone shows `offline` or vanishes, its wireless-debugging port rotated. Rediscover:

```bash
nmap -sT -p 30000-65535 --open -n <ip>     # then adb connect each open port
```

`harness/src/adb.ts` does this automatically; prefer it over doing it by hand.

## 3. Drive the app — by broadcast, not by tapping

```bash
adb -s $D shell am broadcast -f 0x00000020 -p sksa.aa.customapps.dev \
    -a com.legs.appsforaa.DEBUG_STATUS
... -a com.legs.appsforaa.DEBUG_INSTALL --es id n2c
... -a com.legs.appsforaa.DEBUG_CONVERT --es package nl.frankkie.nav2contacts

adb -s $D logcat -d -s AAAD/DebugAutomation:V | grep RESULT=
```

**`-f 0x00000020` is mandatory.** It is `FLAG_INCLUDE_STOPPED_PACKAGES`; after `am force-stop` the
app receives no manifest broadcasts without it, and `am` still prints `result=0`, so the failure is
completely silent.

Simulated taps are a last resort: they need an unlocked screen, a known scroll offset and stable
coordinates. If you must, get bounds from `uiautomator dump` rather than guessing pixels — and note
that button labels are uppercased by the theme, so grep for `text="SEARCH"` not `"Search"`.

## 4. Check the thing that actually matters

The app's own reported outcome and the system's record can disagree — that disagreement is the
interesting part, so always check both:

```bash
adb -s $D shell 'pm list packages -i' | grep <package>
#   installer=com.android.vending   -> Android Auto will list it
#   installer=<anything else>       -> it will not.  See docs/aa-visibility.md
```

## 5. Screenshots

```bash
adb -s $D shell screencap -p /sdcard/s.png && adb -s $D pull /sdcard/s.png
adb -s $D shell rm -f /sdcard/s.png
```

- A **0-byte** file means the screen is off or a secure lock screen is blanking the capture. Wake
  first; on a rooted device `su -c screencap` sometimes succeeds where the normal one does not.
- Downscale before reading: **no dimension ≥ 2000 px and under 4 MB.**

## Shizuku

Required for any attributed install or conversion. `availability=Ready` is the only state that
works; anything else silently falls back to an unattributed install.

- Its authorization is **not** the Android permission. `pm grant … API_V23` reports `granted=true`
  and changes nothing; so does editing `flags` in
  `/data/user_de/0/com.android.shell/shizuku.json`. It must be granted through Shizuku's own
  prompt, once per app.
- Start it so it outlives the adb session (Saga, rooted):
  ```bash
  su -c "setsid $(ls /data/app/*/moe.shizuku.privileged.api*/lib/*/libshizuku.so | head -1) </dev/null >/dev/null 2>&1 &"
  ```
  Without `setsid` the server dies with the shell that spawned it.
- Its binder arrives **asynchronously** after process start, so a synchronous `pingBinder()` right
  after launch reports a healthy Shizuku as "not running".

## Non-exported activities

`ConvertActivity`, `DiscoverActivity`, `SupportActivity` and `AndroidAutoSetupActivity` are
`exported="false"`, so `adb shell am start` cannot launch them. On the rooted Saga use
`su -c "am start -n …"`. This is correct behaviour, not a bug to fix.

## Leave no trace

Uninstall anything installed only for a test, delete pushed files from `/data/local/tmp` and
`/sdcard`, and restore the foreground app. **Never** `stop; start` or reboot the Saga — see
`~/.claude/CLAUDE.md`.

## Or just use the harness

```bash
cd harness && bun run src/cli.ts status
bun run src/cli.ts matrix --apps n2c
```

It encodes all of the above and writes JSONL results. `docs/testing-harness.md`.
