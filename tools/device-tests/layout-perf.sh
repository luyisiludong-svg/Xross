#!/usr/bin/env bash
# Frame pacing of the Layout panel (2b.8a, the Live Theme Mixer): Settings ▸ Theme ▸ Layout ▸ UI
# scale stepped as fast as presses can be injected (back to back, about one every 150–250 ms on
# the emulator) up to 150%, down to 50% and back, while the whole menu behind the panel springs to
# each new scale (it snaps), and the crosshair and spacing rows, which spring the menu there. Gate: 0–1.5% janky frames (Roadmap Appendix A.1, invariant 4), as in
# palette-perf.sh. The same 21 presses on the plain list are measured alongside, because the
# emulator's GPU timing drifts between passes and spawning `input` costs frames of its own.
#
#   ./gradlew :app:installProfiling      # frame timing needs the optimized build
#   tools/device-tests/layout-perf.sh [rounds]     # default 3
#
# A real held key (the OS repeat, ~20 steps a second) can't be injected on a non-root emulator:
# `input keyevent` can't hold a key and `sendevent` needs root. Judge by "Janky frames" (deadline
# missed). Boot the emulator with `-gpu host`. Clears display.layout before and after.
set -u
PKG=dev.xross.launcher
ROUNDS=${1:-3}
settings=/sdcard/Xross/config/settings.json

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause; }
times() { for _ in $(seq "$1"); do printf '%s ' "$2"; done; }
janky() { adb shell dumpsys gfxinfo $PKG | grep -m1 'Janky frames:' | grep -o '([0-9.]*%)' | tr -d '()'; }
steps() { # steps <keycode> <n>: n presses back to back from one shell, one `input` at a time
  adb shell "for i in \$(seq $2); do input keyevent $1; done"
  pause 0.3
}
clear_layout() {
  local f; f=$(mktemp)
  adb shell am force-stop $PKG
  adb shell cat $settings > "$f"
  python3 -I - "$f" <<'PY'
import json, sys
s = json.load(open(sys.argv[1])); s.setdefault("display", {})["layout"] = {}
json.dump(s, open(sys.argv[1], "w"), indent=2)
PY
  adb push "$f" $settings >/dev/null
  rm -f "$f"
}

clear_layout
adb shell am start -n $PKG/.MainActivity >/dev/null
pause 5
# Settings ▸ Theme ▸ Layout (the row after Components).
key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
key $(times 6 KEYCODE_BUTTON_L1)
key $(times 25 KEYCODE_DPAD_UP) KEYCODE_DPAD_DOWN
key $(times 3 KEYCODE_DPAD_DOWN) KEYCODE_DPAD_CENTER
key $(times 25 KEYCODE_DPAD_UP)

printf '%-6s %-14s %-14s %s\n' round list "UI scale" "crosshair+spacing"
for r in $(seq "$ROUNDS"); do
  # The list, the same 21 presses: Down to Import theme, Up to Theme pack, Down again.
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  steps 20 5; steps 19 10; steps 20 6
  pause 1
  list=$(janky)
  # The Layout row, its panel, the UI scale row on the pack's 100%.
  key $(times 25 KEYCODE_DPAD_UP) $(times 7 KEYCODE_DPAD_DOWN) KEYCODE_DPAD_CENTER KEYCODE_DPAD_DOWN
  pause 1
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  # Theme pack → 150%, then down through 50% to the pack (the left end), then to 150% again:
  # 5 + 11 + 5 steps, each one springing the whole menu to the new scale.
  steps 22 5; steps 21 11; steps 22 5
  pause 1
  scale=$(janky)
  # UI scale back to the pack's; the crosshair (3 presets and back) and spacing (Tight ↔ Spacious),
  # which spring the whole menu to the new anchor and pitch: 21 presses again.
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_UP
  pause 1
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  steps 22 3; steps 21 3; steps 22 3
  adb shell input keyevent 20 21 21
  steps 22 2; steps 21 2; steps 22 2
  pause 1
  springs=$(janky)
  # Spacing and crosshair back to the pack's, panel closed, cursor to the top for the next round.
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER KEYCODE_BACK $(times 25 KEYCODE_DPAD_UP)
  printf '%-6s %-14s %-14s %s\n' "$r" "${list:-?}" "${scale:-?}" "${springs:-?}"
done
clear_layout
adb shell am start -n $PKG/.MainActivity >/dev/null
