#!/usr/bin/env bash
# The 12-configuration display matrix (docs/STATUS.md §4.1) on a connected emulator: for each
# resolution and density it drives the home menu (an unmeasured warm-up pass, then 4 down, 4 up,
# L1, R1 measured, then into Settings ▸ Theme),
# records the UI frame stats, counts on-screen text cut off by a screen edge, and saves a
# screenshot of the Games root and of Settings ▸ Theme for review.
#
#   ./gradlew :app:installProfiling          # frame timing needs the optimized build
#   tools/device-tests/display-matrix.sh [out-dir] [stress]
#
# With "stress", a pack is installed and activated first that pushes the layout clamps the hardest:
# the PSP centre crosshair at 150% UI scale, 3:4 game tiles and 20 dp spacing. Restores the
# display size and density, and the previous theme pack, at the end. Doesn't clear app data.
set -u
PKG=dev.xross.launcher
OUT=${1:-display-matrix}
MODE=${2:-default}
mkdir -p "$OUT"

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause 0.3; }
times() { for _ in $(seq "$1"); do printf '%s ' "$2"; done; }

# Name, width × height (landscape, as the app is sensorLandscape), density.
CONFIGS="
4:3-retro 640x480 160
4:3 1024x768 240
5:3 800x480 160
1:1 720x720 240
3:2 1080x720 240
16:9-small 1334x750 280
16:9-FHD 1920x1080 320
16:10 2560x1600 320
20:9-phone 2400x1080 420
21:9 2560x1080 400
4K-TV 3840x2160 640
portrait-phone 1080x2400 420
"

settings=/sdcard/Xross/config/settings.json
prev_pack=$(adb shell cat $settings 2>/dev/null | grep -o '"themePack": "[^"]*"' | sed 's/.*: "//; s/"$//')
if [ "$MODE" = stress ]; then
  tmp=$(mktemp -d)
  mkdir -p "$tmp/matrix_stress"
  cat > "$tmp/matrix_stress/theme.json" <<'JSON'
{"schemaVersion": 2, "name": "Matrix Stress",
 "layout": {"preset": "PSP_CENTER", "uiScale": 1.5, "itemSpacingDp": 20, "focusedScale": 1.35, "focusDivider": "FULL_BLEED_LINE"},
 "categoryOverrides": {"GAMES": {"cardAspectRatio": "3:4"}}}
JSON
  adb shell rm -rf /sdcard/Xross/themes/matrix_stress
  adb push "$tmp/matrix_stress" /sdcard/Xross/themes/ >/dev/null
  rm -rf "$tmp"
  adb shell am force-stop $PKG
  adb shell "sed -i 's/\"themePack\": \"[^\"]*\"/\"themePack\": \"matrix_stress\"/' $settings"
fi

printf '%-15s %-11s %-11s %-9s %-7s %-7s %s\n' config asked app-gets janky p90 p99 clipped-texts
for line in $(echo "$CONFIGS" | tr ' ' ','); do
  [ -z "$line" ] && continue
  IFS=, read -r name size dpi <<<"$line"
  w=${size%x*}; h=${size#*x}
  adb shell wm size "${w}x${h}"
  adb shell wm density "$dpi"
  pause 2
  adb shell am start -n $PKG/.MainActivity >/dev/null
  pause 2.5
  app=$(adb shell dumpsys window displays | grep -o 'app=[0-9]*x[0-9]*' | head -1 | sed 's/app=//')
  aw=${app%x*}; ah=${app#*x}
  # Games root, first collection.
  key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
  key $(times 6 KEYCODE_BUTTON_L1) $(times 4 KEYCODE_BUTTON_R1)
  key $(times 25 KEYCODE_DPAD_UP) KEYCODE_DPAD_DOWN
  pause 1
  # One unmeasured pass first, so a cold process (the stress mode restarts it) isn't timed.
  key $(times 4 KEYCODE_DPAD_DOWN) $(times 4 KEYCODE_DPAD_UP)
  pause 1
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  key $(times 4 KEYCODE_DPAD_DOWN) $(times 4 KEYCODE_DPAD_UP) KEYCODE_BUTTON_L1 KEYCODE_BUTTON_R1
  pause 1
  gfx=$(adb shell dumpsys gfxinfo $PKG)
  janky=$(grep -m1 'Janky frames:' <<<"$gfx" | grep -o '([0-9.]*%)' | tr -d '()')
  p90=$(grep -m1 '90th percentile' <<<"$gfx" | grep -o '[0-9]*ms')
  p99=$(grep -m1 '99th percentile' <<<"$gfx" | grep -o '[0-9]*ms')
  adb exec-out screencap -p > "$OUT/$MODE-$name-games.png"
  # Text whose bounds run past the left or right edge (rows above the bar may leave at the top).
  adb shell uiautomator dump /sdcard/xross-matrix.xml >/dev/null 2>&1
  clipped=$(adb shell cat /sdcard/xross-matrix.xml | grep -o 'text="[^"][^"]*"[^>]*bounds="\[[-0-9]*,[-0-9]*\]\[[-0-9]*,[-0-9]*\]"' \
    | sed 's/.*bounds="\[\([-0-9]*\),[-0-9]*\]\[\([-0-9]*\),[-0-9]*\]"/\1 \2/' \
    | awk -v W="$aw" '$1 < 0 || $2 > W { n++ } END { print n + 0 }')
  # Settings ▸ Theme: the deepest common list, with the drill-down beside it.
  key $(times 4 KEYCODE_BUTTON_L1)
  key $(times 25 KEYCODE_DPAD_UP) KEYCODE_DPAD_DOWN $(times 3 KEYCODE_DPAD_DOWN) KEYCODE_DPAD_CENTER
  pause 1
  adb exec-out screencap -p > "$OUT/$MODE-$name-settings.png"
  printf '%-15s %-11s %-11s %-9s %-7s %-7s %s\n' "$name" "$size@$dpi" "$app" "${janky:-?}" "${p90:-?}" "${p99:-?}" "$clipped"
done

adb shell wm size reset
adb shell wm density reset
if [ "$MODE" = stress ]; then
  adb shell am force-stop $PKG
  adb shell "sed -i 's/\"themePack\": \"matrix_stress\"/\"themePack\": \"${prev_pack:-default}\"/' $settings"
  adb shell rm -rf /sdcard/Xross/themes/matrix_stress
  adb shell am start -n $PKG/.MainActivity >/dev/null
fi
echo "Screenshots in $OUT/"
