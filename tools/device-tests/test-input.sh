#!/usr/bin/env bash
# Input suite (11 checks): the analog stick deadzone cycle, the ten-button remap wizard and the
# saved custom mapping (then Reset with the remapped Confirm), and touch: one category per
# horizontal swipe, vertical drag in a list.
#
#   tools/device-tests/test-input.sh
#
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin input 11

setup_base_env
start_app

section "Analog stick deadzone"
# Controls rows: Confirm button, Analog stick deadzone, Axial deadzone, Remap buttons…, Reset all.
category 0
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "35%" "deadzone starts at 35%"
# 35% → 45% → 55% → Off → 15% → 25% → 35%
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Off — hardware only" "deadzone can be turned off (hardware flat zone only)"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "35%" "deadzone cycles back to 35%"

section "Button remapping"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Press the button for: Up" "remap wizard starts"
for k in DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT BUTTON_X BUTTON_B BUTTON_Y BUTTON_L1 BUTTON_R1 BUTTON_SELECT; do
  adb shell input keyevent "KEYCODE_$k"
done
pause 0.6
# The "Buttons remapped" toast lasts 2.6 s and is often gone before the dump; the wizard closing
# (back on the Controls rows) is the same signal. "10 custom bindings" below checks what was saved.
s=$(screen)
if grep -qF "Buttons remapped" <<<"$s" || { ! grep -qF "Press the button for" <<<"$s" && grep -qF "Remap buttons…" <<<"$s"; }; then
  ok "wizard completes after ten buttons (last: media controls)"
else
  bad "wizard completes after ten buttons — still asking"; show "$s" 6
fi
key KEYCODE_DPAD_DOWN
expect "default mapping changed" "Reset all summarizes the changed mapping"
key KEYCODE_DPAD_DOWN
expect "10 custom bindings" "bindings are saved (Default mapping row)"
key KEYCODE_DPAD_UP
key KEYCODE_BUTTON_X
pause 0.5
expect "Using defaults" "remapped X confirms (it ran Reset button mapping)"
key KEYCODE_BACK

section "Touch"
home # Games root: Search, All Games, RetroAchievements
# Swipes are placed relative to the screen (root node bounds from the accessibility dump).
dump
read -r SW SH < <(adb shell cat /sdcard/xross-ui.xml | grep -o 'bounds="\[0,0\]\[[0-9]*,[0-9]*\]"' | head -1 | sed 's/.*\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2/')
SW=${SW:-2400}; SH=${SH:-1080}
adb shell input swipe $((SW * 58 / 100)) $((SH * 55 / 100)) $((SW * 30 / 100)) $((SH * 55 / 100)) 200
pause 0.6
expect_not "All Games" "swipe left moves to the next category"
adb shell input swipe $((SW * 30 / 100)) $((SH * 55 / 100)) $((SW * 58 / 100)) $((SH * 55 / 100)) 200
pause 0.6
expect "All Games" "swipe right comes back (one category per swipe)"
adb shell input swipe $((SW * 42 / 100)) $((SH * 65 / 100)) $((SW * 42 / 100)) $((SH * 28 / 100)) 250
pause 0.6
expect "All Games" "vertical drag scrolls the list"

suite_end
