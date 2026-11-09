#!/usr/bin/env bash
# End-to-end smoke test on a connected device/emulator (adb). Resets Wave's data, seeds test
# ROMs under /sdcard/ROMs, then drives the launcher with key events and checks what is on
# screen through the accessibility tree (uiautomator).
#
#   ./gradlew :app:installDebug
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: enables the launch check
#   tools/device-tests/smoke.sh
#
# WARNING: clears Wave's app data and adds files under /sdcard/ROMs on the device.
set -u
PKG=dev.wave.launcher
PASS=0
FAIL=0

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause; }
ui() {
  adb shell uiautomator dump /sdcard/wave-ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/wave-ui.xml | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//'
}
ok() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
# uiautomator occasionally returns an empty tree mid-animation, so read until it has text.
screen() {
  local s
  for _ in 1 2 3 4; do
    s=$(ui)
    if grep -q '[^[:space:]]' <<<"$s"; then printf '%s\n' "$s"; return; fi
    pause 0.5
  done
}
expect() { # expect <text> <description>
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qF -- "$1" <<<"$s"; then ok "$2"; return; fi
    pause 0.5
  done
  bad "$2 — '$1' not on screen"; sed 's/^/       | /' <<<"$s" | head -12
}
expect_not() {
  local s; s=$(screen)
  if grep -qF -- "$1" <<<"$s"; then bad "$2 — '$1' unexpectedly on screen"; else ok "$2"; fi
}
top() { key $(for _ in $(seq 25); do printf 'KEYCODE_DPAD_UP '; done); }
home() { # Games category root, cursor on the first entry
  key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
  key KEYCODE_BUTTON_L1 KEYCODE_BUTTON_L1 KEYCODE_BUTTON_L1 KEYCODE_BUTTON_R1
  top
}

echo "== Setup"
adb shell pm clear "$PKG" >/dev/null
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell 'rm -rf /sdcard/ROMs; mkdir -p /sdcard/ROMs/snes /sdcard/ROMs/gba /sdcard/ROMs/psx
  for g in "Chrono Trigger (USA)" "EarthBound (USA)" "Super Metroid (USA, Europe)"; do echo x > "/sdcard/ROMs/snes/$g.sfc"; done
  echo x > "/sdcard/ROMs/gba/Golden Sun (USA).gba"
  printf "FILE \"Vagrant Story (USA).bin\" BINARY\n" > "/sdcard/ROMs/psx/Vagrant Story (USA).cue"
  echo x > "/sdcard/ROMs/psx/Vagrant Story (USA).bin"'
adb shell am start -n "$PKG/.MainActivity" >/dev/null
pause 4

echo "== Library and navigation"
expect "Super Nintendo" "auto-detected /sdcard/ROMs and listed platforms"
expect "Game Boy Advance" "second platform listed"
expect_not "Recently Played" "no Recently Played before anything is launched"
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "entering a platform lists its games (titles cleaned)"
expect "Super Metroid" "all games in the platform are listed"
key KEYCODE_BACK
key $(for _ in $(seq 2); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
expect "Vagrant Story" "cue sheet listed"
expect "CUE" "the .bin track is hidden behind its .cue (focused entry is the .cue)"
key KEYCODE_BACK

echo "== Favorites"
home
key KEYCODE_DPAD_CENTER
expect "Favorite" "Options hint offers Favorite"
key KEYCODE_BUTTON_Y
pause 0.6
expect "Unfavorite" "Options button toggles favorite"
key KEYCODE_BACK
top
expect "Favorites" "Favorites collection appears in Games"

echo "== Categories"
home
key KEYCODE_BUTTON_R1
expect_not "Super Nintendo" "R1 moves to the Apps category"
key KEYCODE_BUTTON_L1 KEYCODE_BUTTON_L1
expect "Library" "L1 moves to Settings"
key $(for _ in $(seq 4); do printf 'KEYCODE_DPAD_DOWN '; done)
expect "Sound" "Settings lists the Sound section"
top

echo "== Settings"
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
expect "Lavender" "Theme section shows the current theme"
key KEYCODE_DPAD_CENTER
pause 0.5
expect "Crimson" "Theme color cycles"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Off" "Wave background toggles off"
key KEYCODE_DPAD_CENTER
key KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Effects volume" "Sound section opens"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "100%" "Effects volume cycles"
key KEYCODE_BACK

echo "== Button remapping"
top
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Press the button for: Up" "remap wizard starts"
for k in DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT BUTTON_X BUTTON_B BUTTON_Y BUTTON_L1 BUTTON_R1; do
  adb shell input keyevent "KEYCODE_$k"
done
pause 0.6
expect "Buttons remapped" "wizard completes after nine buttons"
key KEYCODE_DPAD_DOWN
expect "9 custom bindings" "bindings are saved"
key KEYCODE_BUTTON_X
pause 0.5
expect "Using defaults" "remapped X confirms (it ran Reset button mapping)"

echo "== Rescan"
key KEYCODE_BACK
top
key KEYCODE_DPAD_CENTER
adb shell 'echo x > "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 1.2
expect "+1" "rescan picks up a new file"
adb shell 'rm "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
key KEYCODE_DPAD_CENTER
pause 1.2
expect "−1" "rescan drops a deleted file"

echo "== Touch"
home
adb shell input swipe 1400 600 700 600 200
pause 0.6
expect_not "Super Nintendo" "swipe left moves to the next category"
adb shell input swipe 700 600 1400 600 200
pause 0.6
expect "Super Nintendo" "swipe right comes back (one category per swipe)"
adb shell input swipe 1000 700 1000 300 250
pause 0.6
expect "Game Boy Advance" "vertical drag scrolls the list"

if adb shell pm list packages | grep -q com.retroarch.aarch64; then
  echo "== Launch (RetroArch stub)"
  home
  key KEYCODE_DPAD_CENTER
  adb logcat -c
  key KEYCODE_DPAD_CENTER
  pause 2.5
  log=$(adb logcat -d -s WaveStub:I)
  grep -q "extra.LIBRETRO=/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so" <<<"$log" \
    && ok "RetroArch receives the snes9x core" || bad "RetroArch core extra"
  grep -q "uriRead=ok" <<<"$log" && ok "emulator can read the ROM through the granted URI" || bad "URI read grant"
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  pause 1
  home
  expect "Recently Played" "launched game appears in Recently Played"
fi

echo
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
