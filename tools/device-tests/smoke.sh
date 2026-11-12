#!/usr/bin/env bash
# Quick smoke test (~30–45 s) on a connected device/emulator (adb): Xross is installed, starts
# from cleared data, scans the seeded /sdcard/ROMs into its database, draws the category bar and
# the Games root, opens the first platform, moves between categories, and doesn't crash. No media,
# theme, database or battery fixtures: those live in the suites.
#
#   ./gradlew :app:installDebug
#   tools/device-tests/smoke.sh                 # this quick check
#   tools/device-tests/smoke.sh theme           # one suite: theme, media, library, input, integration
#   tools/device-tests/smoke.sh --all           # full regression (run-all.sh: every suite, 171 checks)
#
# WARNING: clears Xross's app data, moves /sdcard/Xross/config to /sdcard/Xross-smoke-backup/, and
# replaces /sdcard/ROMs with test files (removed again at the end).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  "") ;;
  --all | all) shift; exec "$HERE/run-all.sh" "$@" ;;
  theme | media | library | input | integration) exec "$HERE/test-$1.sh" ;;
  -h | --help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown suite '$1' (theme, media, library, input, integration or --all)"; exit 2 ;;
esac
. "$HERE/lib/harness.sh"
suite_begin smoke 12

setup_base_env
start_app

section "Boot"
pid=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] && ok "Xross starts (pid $pid)" || bad "Xross isn't running after start"
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$PKG/.MainActivity" \
  && ok "MainActivity is in front" || bad "MainActivity isn't the resumed activity"
if adb shell run-as "$PKG" true 2>/dev/null; then
  adb shell run-as "$PKG" ls databases/xross.db >/dev/null 2>&1 && ok "the library database is created" || bad "no databases/xross.db"
else
  skip 1 "the database check needs a debuggable build"
fi

section "Library and categories"
expect_line "Games" "the category bar opens on Games"
expect "All Games" "the Games root lists All Games"
expect "3 systems" "the seeded ROMs are scanned (3 systems)"
key KEYCODE_DPAD_CENTER
expect "Super Nintendo" "All Games lists the first platform"
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "the platform lists its games (titles cleaned)"
key KEYCODE_BACK KEYCODE_BACK
key KEYCODE_BUTTON_R1
expect_not "All Games" "R1 moves to the next category"
key KEYCODE_BUTTON_L1
expect "All Games" "L1 comes back to Games"

section "Stability"
now=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$now" ] && [ "$now" = "$pid" ] && ok "the same process is still running" || bad "Xross restarted or died (pid $pid → ${now:-none})"
crash=$(adb logcat -d -b crash 2>/dev/null | grep -A3 "Process: $PKG")
[ -z "$crash" ] && ok "no crash in the log" || { bad "a crash was logged"; show "$crash" 8; }

suite_end
