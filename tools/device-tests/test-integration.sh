#!/usr/bin/env bash
# Integration suite (9 checks): the status-bar avatar mini-HUD, the RetroAchievements dashboard,
# no account fields in Xross/config, and launching a game in RetroArch (the stub logs the core and
# reads the ROM through the granted URI; the game then shows in Recently Played).
#
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: the three launch checks
#   tools/device-tests/test-integration.sh
#
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin integration 9

setup_base_env
start_app

section "Status-bar avatar"
# Up from the first row of a category root focuses the avatar (it expands into the mini-HUD);
# Down hands focus back to that first row.
home
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP
expect_any "No RetroAchievements account linked" "SOFTCORE" "Up from the top row expands the avatar HUD"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Type to search your library" "Down leaves the avatar for the first row (Search)"
key KEYCODE_BACK KEYCODE_BACK

section "RetroAchievements dashboard"
home
# RetroAchievements is the last row (Android Games may sit above it on a real device).
press DPAD_DOWN 6 KEYCODE_DPAD_CENTER
expect_any "Link your account with your user name" "HARDCORE POINTS" "Games ▸ RetroAchievements opens the dashboard"
key KEYCODE_BACK
expect_not "HARDCORE POINTS" "Back closes the dashboard"
expect_not "Link your account with your user name" "Back closes the dashboard (unlinked text gone)"

section "Secrets"
# The three files are written off the main thread after start; wait for them so the check reads
# real config rather than an error message.
for _ in $(seq 10); do adb shell ls /sdcard/Xross/config/settings.json /sdcard/Xross/config/controllers.json /sdcard/Xross/config/systems.json >/dev/null 2>&1 && break; pause 0.5; done
cfg=$(adb shell cat /sdcard/Xross/config/settings.json /sdcard/Xross/config/controllers.json /sdcard/Xross/config/systems.json 2>&1)
if grep -q 'No such file' <<<"$cfg"; then bad "Xross/config is incomplete: $(grep 'No such file' <<<"$cfg" | head -1)"
elif grep -qiE 'password|apikey|api_key|web_api|"key"' <<<"$cfg"; then bad "account fields found in Xross/config"
else ok "no account names, passwords or keys in Xross/config"; fi

section "Launch (RetroArch stub)"
if has_stub; then
  all_games
  key KEYCODE_DPAD_CENTER # Super Nintendo, cursor on Chrono Trigger
  adb logcat -c
  key KEYCODE_DPAD_CENTER
  pause 2.5
  log=$(adb logcat -d -s XrossStub:I)
  grep -q "extra.LIBRETRO=/data/data/$STUB/cores/snes9x_libretro_android.so" <<<"$log" \
    && ok "RetroArch receives the snes9x core" || bad "RetroArch core extra"
  grep -q "uriRead=ok" <<<"$log" && ok "emulator can read the ROM through the granted URI" || bad "URI read grant"
  start_app 1
  home
  expect "Recently Played" "launched game appears in Recently Played"
else
  skip 3 "install the RetroArch stub (./gradlew :emulator-stub:installRetroarchDebug)"
fi

suite_end
