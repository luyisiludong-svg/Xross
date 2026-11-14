#!/usr/bin/env bash
# Theme suite (149 checks): Settings ▸ Theme and its folders (theme color, wave and avatar panels,
# platform folders, surface style, the 2b.4f effect rows, Pick custom wallpaper, Reset to theme
# defaults), quick profiles (OLED, Y2K, E-Ink), color layers (adaptive colors,
# circadian curve, battery tint and charging pulse through `dumpsys battery`), the Sound section,
# v2 theme packs and hot reload (and a pack's wallpaper.png, which stops the wave and is pre-blurred
# for frosted surfaces; a pack's focus frame, icon mask and icon filter, with the Apps list's icons
# drawn once per style; a pack's foreground particles, resting after 10 s; a pack's background
# filter and custom postprocess.agsl, resting and failing safely), .xtheme import (zip-slip, variants, overrides, Keep both), and the
# game-art palette (hero PNGs, scraped flags patched into the database, extraction cache, debounce),
# and the cold-start intro and launch curtains (2b.5d: a generated boot.mp4, the shader ramp, E-Ink,
# each curtain on a stub launch).
#
#   tools/device-tests/test-theme.sh
#
# Needs python3 on the host; the game-art block also needs sqlite3 and a debuggable build (skipped
# otherwise). Colors are read from debug-build logs (XrossColors, XrossPalette, WaveLoop).
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
# The battery override is always reset at exit, even after Ctrl-C.
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin theme 149

setup_base_env
setup_theme_fixtures
FIX=$XT_TMP/theme
start_app
category 0

# Settings ▸ Theme (the fourth Settings folder) with the Default pack: Theme pack, Quick profile,
# then the folders Colors, Background, Effects, Intro and transitions, Menu and profile, then
# Reset to theme defaults and Import theme (.xtheme)…. A pack with variants adds Theme variant
# below Theme pack. Back out of a folder lands on that folder's row.
section "Settings"
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
expect "Theme pack" "Theme section lists theme packs"
expect "Default" "the built-in Default pack is active"
# Colors: Theme color, Adaptive colors, Circadian curve, Battery alert tint.
press DPAD_DOWN 2 KEYCODE_DPAD_CENTER
expect "Lavender" "Theme ▸ Colors shows the current theme color"
key KEYCODE_DPAD_CENTER
pause 0.5
expect "Crimson" "Theme color cycles"
# Background: Wave background, Wave settings, Xross wallpaper, Background shader, Background filter.
key KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_CENTER
expect "Off" "Wave background toggles off"
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Changes preview live behind this panel" "Wave settings panel opens"
expect "Ribbon lines" "Wave settings lists the shader uniforms"
key KEYCODE_DPAD_RIGHT
expect "1.1×" "Speed slider moves right"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT
expect "6" "Ribbon lines slider moves"
key KEYCODE_BACK
expect "1.1× · 6 lines" "Wave settings row summarizes the new values"
settings_has '"waveSpeed": 1.1' && ok "wave uniforms are saved to settings.json" || bad "waveSpeed not in settings.json"
# Menu and profile (three folders below Background): Profile customization, Button hints,
# Platform folders.
key KEYCODE_BACK
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
expect "glow on" "Profile customization row summarizes the avatar style"
# Panel rows: Border color, Custom color…, Border width, Glow, Glow speed, Glow intensity, Reset.
key KEYCODE_DPAD_CENTER
expect "Live preview · saved as you change it" "Profile customization panel opens"
key KEYCODE_DPAD_RIGHT
expect "Neon cyan" "Border color steps to the next preset"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Off" "Glow toggles off"
key KEYCODE_BACK
expect "Neon cyan ring" "the row summary shows the new color"
expect "glow off" "the row summary shows the glow is off"
settings_has '"#00E5FF"' && ok "avatar style is saved to settings.json" || bad "avatar border color not in settings.json"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
expect "Inside All Games" "Platform folders setting shows the All Games layout"
key KEYCODE_DPAD_CENTER
expect "At the top of Games (classic)" "Platform folders toggles to the classic layout"
key KEYCODE_DPAD_CENTER
expect "Inside All Games" "Platform folders toggles back"
# Effects (two folders above Menu and profile): Surface style, UI filter (experimental), Focus
# frame style, Ambient particles, App icon mask.
key KEYCODE_BACK
press DPAD_UP 2 KEYCODE_DPAD_CENTER
expect "Theme pack (Flat glass)" "Surface style follows the theme pack (Flat glass)"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Y2K glossy bezel" "Surface style cycles through the presets"
settings_has '"surfaceStyle": "Y2K_GLOSSY_BEZEL"' && ok "surface style is saved to settings.json" || bad "surfaceStyle not in settings.json"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Theme pack (Flat glass)" "Surface style cycles back to the pack's own"
key KEYCODE_BACK

section "Quick profiles and color layers"
# Back on Theme ▸ Effects: Quick profile is three rows up; the color layers are in Colors, below it.
press DPAD_UP 3
expect "None · The theme's own look" "Quick profile is None by default"
adb logcat -c
key KEYCODE_DPAD_CENTER
expect "OLED True Black · Pure black, glowing outlines" "Quick profile cycles to OLED True Black"
settings_has '"quickProfile": "OLED_TRUE_BLACK"' && ok "the quick profile is saved to settings.json" || bad "quickProfile not in settings.json"
colors | grep -q "top=#000000 .*profile=OLED_TRUE_BLACK" && ok "OLED True Black paints a pure black canvas" || bad "OLED canvas not black"
key KEYCODE_DPAD_CENTER
expect "Y2K Cyber-Pastel · Lavender, lilac and strawberry" "Quick profile cycles to Y2K Cyber-Pastel"
colors | grep -q "top=#1B1829 glow=#FFB5C5 profile=Y2K_CYBER_PASTEL" && ok "Y2K uses the lavender canvas and strawberry glow" || bad "Y2K colors not applied"
adb logcat -c
key KEYCODE_DPAD_CENTER
expect "Monochrome E-Ink · Black and white, no motion" "Quick profile cycles to Monochrome E-Ink"
pause 1
adb logcat -d -s WaveLoop:I | tail -1 | grep -q "mode=SINGLE_FRAME" \
  && ok "E-Ink stops the wave's render loop (one still frame)" || bad "the wave loop still runs under E-Ink"
# Colors ▸ Adaptive colors.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "Off (set by Monochrome E-Ink)" "E-Ink holds the color layers off"
key KEYCODE_BACK KEYCODE_DPAD_UP KEYCODE_BUTTON_Y
expect "Y2K Cyber-Pastel" "Quick profile Options list every profile"
key KEYCODE_BACK
key KEYCODE_DPAD_CENTER
expect "None · The theme's own look" "Quick profile cycles back to None"
pause 1
adb logcat -d -s WaveLoop:I | tail -1 | grep -qE "mode=(CONTINUOUS|PACED)" \
  && ok "leaving E-Ink restarts the wave loop" || bad "the wave loop didn't restart"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "On (theme pack) · Colors follow the focused game's art" "Adaptive colors are on by default (theme pack)"
key KEYCODE_DPAD_CENTER
expect "Off · Colors follow the focused game's art" "Adaptive colors toggle off"
settings_has '"adaptiveGamePalette": false' && ok "adaptive colors are saved to settings.json" || bad "adaptiveGamePalette not in settings.json"
key KEYCODE_DPAD_CENTER
expect "On · Colors follow the focused game's art" "Adaptive colors toggle back on"
key KEYCODE_DPAD_DOWN
expect "Off (theme pack) · Colors shift with the time of day" "Circadian curve is off by default"
adb logcat -c
key KEYCODE_DPAD_CENTER
expect "On · Colors shift with the time of day" "Circadian curve turns on"
colors | grep -q "circadian=true" && ok "the circadian curve recolors the wave" || bad "no circadian color change"
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "Off (theme pack) · Amber to red below 20%" "Battery alert tint is off by default"
key KEYCODE_DPAD_CENTER
expect "On · Amber to red below 20%" "Battery alert tint turns on"
settings_has '"batteryTint": true' && ok "the battery tint is saved to settings.json" || bad "batteryTint not in settings.json"
adb logcat -c
adb shell dumpsys battery unplug
# A phone on USB keeps reporting "charging" (status 2) after unplug; Xross reads the status.
adb shell dumpsys battery set status 3
adb shell dumpsys battery set level 12
pause 1.5
colors | grep -q "batteryLow=true" && ok "a 12% battery tints the colors" || bad "no low-battery tint"
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set status 2
pause 1.5
colors | grep -q "charging=true" && ok "charging pulses the wave green" || bad "no charging pulse"
adb shell dumpsys battery reset
key KEYCODE_DPAD_CENTER
expect "Off · Amber to red below 20%" "Battery alert tint turns off"
# Out of Colors and Theme; Sound is the folder below Theme.
key KEYCODE_BACK KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Effects volume" "Sound section opens"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "100%" "Effects volume cycles"
key KEYCODE_BACK

section "Theme packs"
# A minimal v2 pack: name, palette, wave defaults, one sound; everything else inherits from Default.
adb shell rm -rf /sdcard/Xross/themes/smoke_pack
adb push "$FIX/smoke_pack" /sdcard/Xross/themes/ >/dev/null
adb shell ls -a /sdcard/Xross/themes | grep -q '^.nomedia$' && ok "Xross/themes has .nomedia" || bad "Xross/themes/.nomedia missing"
top
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
top_list
key KEYCODE_BUTTON_Y
expect "Look for new packs" "Theme pack Options offer a rescan"
press DPAD_DOWN 6 KEYCODE_DPAD_CENTER
pause 1
key KEYCODE_DPAD_CENTER
pause 1.5
expect "Smoke Pack" "a pack in Xross/themes is found and applied"
expect_line "Theme" "the inherited BREADCRUMB drill-down shows the path beside the open category"
# Theme ▸ Colors (its subtitle is the theme color), then Background ▸ Wave settings.
press DPAD_DOWN 2
expect "Theme pack · Smoke Pack" "the pack's palette becomes the theme color"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "8 lines" "the pack's wave defaults are applied"
# Hot reload: an edit to the active pack applies without leaving Xross; a broken save keeps it.
# The edit also switches the layout: the bar collapses while drilled in and a ◀ marks the way back.
good='{"schemaVersion": 2, "name": "Smoke Pack Live", "author": "smoke", "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"}, "layout": {"drillDownStyle": "CHEVRON_COLLAPSE", "focusDivider": "FULL_BLEED_LINE", "motionProfile": "PS3_CRISP"}}'
adb shell "echo '$good' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
# Only the focused row shows its subtitle: out of Background, then up three rows to Theme pack.
key KEYCODE_BACK
press DPAD_UP 3
expect "Smoke Pack Live" "an edited theme.json reloads while Xross is open"
expect_no_line "Theme" "CHEVRON_COLLAPSE drops the breadcrumb"
expect_desc "Back" "CHEVRON_COLLAPSE puts a back chevron beside the focused row"
adb shell "echo '{\"schemaVersion\": 2, \"name\": \"Smoke Pack Live\",,}' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 0.8
expect "Theme not reloaded" "a broken theme.json keeps the last good theme and says why"
adb shell "echo '$good' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
# 2b.4c: a wallpaper.png dropped into the active pack hot-reloads behind the menu. It is opaque,
# so the wave's GL loop stops; Flat glass (the Default surface, 14 dp) blurs a small copy of it.
adb logcat -c
adb push "$FIX/smoke_wallpaper.png" /sdcard/Xross/themes/smoke_pack/wallpaper.png >/dev/null
pause 2.5
adb logcat -d -s XrossWallpaper:I | tail -1 | grep -q "wallpaper=pack .* opaque=true" \
  && ok "a pack's wallpaper.png shows behind the menu" || bad "no pack wallpaper ($(adb logcat -d -s XrossWallpaper:I | tail -1))"
adb logcat -d -s WaveLoop:I | tail -1 | grep -q "mode=STOPPED" \
  && ok "an opaque wallpaper stops the wave's GL loop" || bad "the wave still runs ($(adb logcat -d -s WaveLoop:I | tail -1))"
adb logcat -d -s XrossFrost:I | grep -q "WALLPAPER radius=[1-9][0-9]* copy=192x108" \
  && ok "frosted surfaces get a 192 px pre-blurred copy of it" || bad "no pre-blurred copy ($(adb logcat -d -s XrossFrost:I | tail -1))"
# Xross wallpaper is the third row of Theme ▸ Background; then back to Theme pack.
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
press DPAD_DOWN 2
expect "From Smoke Pack Live" "Settings ▸ Theme ▸ Background ▸ Xross wallpaper names the pack"
key KEYCODE_BACK
top_list
adb logcat -c
adb shell rm /sdcard/Xross/themes/smoke_pack/wallpaper.png
pause 2.5
adb logcat -d -s XrossWallpaper:I | tail -1 | grep -q "wallpaper=none" \
  && ok "removing wallpaper.png hides it" || bad "the wallpaper stayed ($(adb logcat -d -s XrossWallpaper:I | tail -1))"
adb logcat -d -s WaveLoop:I | tail -1 | grep -qE "mode=(CONTINUOUS|PACED)" \
  && ok "the wave runs again" || bad "the wave didn't resume ($(adb logcat -d -s WaveLoop:I | tail -1))"
# 2b.4d: the pack's focus frame, icon mask and icon filter hot-reload; the Apps list draws each
# icon once in that style, so scrolling back over it draws none (the cache holds them).
adb logcat -c
cos='{"schemaVersion": 2, "name": "Smoke Pack Live", "author": "smoke", "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF", "focusGold": "#E0B040"}, "effects": {"focusFrameStyle": "GOLD_BEZEL", "iconMask": "Y2K_CHROME_FRAME", "iconFilter": "DUOTONE"}, "layout": {"drillDownStyle": "CHEVRON_COLLAPSE", "focusDivider": "FULL_BLEED_LINE", "motionProfile": "PS3_CRISP"}}'
adb shell "echo '$cos' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
adb logcat -d -s XrossCosmetics:I | tail -1 | grep -q "frame=GOLD_BEZEL mask=Y2K_CHROME_FRAME filter=DUOTONE gold=#E0B040" \
  && ok "effects.focusFrameStyle, iconMask, iconFilter and palette.focusGold reload with the pack" \
  || bad "cosmetics not applied ($(adb logcat -d -s XrossCosmetics:I | tail -1))"
category 5
press DPAD_DOWN 12
press DPAD_UP 12
pause 1
drew=$(adb logcat -d -s XrossIcons:I | grep -c "Y2K_CHROME_FRAME/DUOTONE")
[ "$drew" -gt 0 ] && ok "app icons are drawn in the chrome frame, duotoned ($drew icons)" \
  || bad "no icon drawn in the pack's style ($(adb logcat -d -s XrossIcons:I | tail -1))"
adb logcat -c
press DPAD_DOWN 12
press DPAD_UP 12
pause 1
again=$(adb logcat -d -s XrossIcons:I | grep -c "drew")
[ "$again" -eq 0 ] && ok "scrolling the Apps list again draws no icon (all from the cache)" \
  || bad "$again icons drawn again ($(adb logcat -d -s XrossIcons:I | tail -1))"
category 0
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
adb logcat -c
adb shell "echo '$good' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
adb logcat -d -s XrossCosmetics:I | tail -1 | grep -q "frame=BREATHING_HALO mask=NONE filter=NONE" \
  && ok "without them the breathing halo and plain icons are back" \
  || bad "cosmetics stayed ($(adb logcat -d -s XrossCosmetics:I | tail -1))"
# 2b.4e: the pack's foreground particles start on reload, rest after 10 s without input (easing to
# a stop, then no frames at all), and the next key wakes them.
adb logcat -c
parts='{"schemaVersion": 2, "name": "Smoke Pack Live", "author": "smoke", "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"}, "effects": {"foregroundParticles": "snow"}}'
adb shell "echo '$parts' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
adb logcat -d -s XrossParticles:I | grep -q "state=RUNNING preset=SNOW n=40" \
  && ok "effects.foregroundParticles reloads with the pack (40 snowflakes running)" \
  || bad "particles not running ($(adb logcat -d -s XrossParticles:I | tail -1))"
pause 11
adb logcat -d -s XrossParticles:I | grep -q "rest preset=SNOW n=40 settleFrames=" \
  && ok "10 s without input: the particles ease to a stop" \
  || bad "the particles didn't rest ($(adb logcat -d -s XrossParticles:I | tail -1))"
frames() { adb shell dumpsys gfxinfo "$PKG" | grep -m1 "Total frames rendered" | grep -o "[0-9]*"; }
f0=$(frames); pause 3; f1=$(frames)
[ "$f0" = "$f1" ] && ok "resting particles draw no frames" || bad "$((f1 - f0)) frames drawn while resting"
adb logcat -c
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_UP
adb logcat -d -s XrossParticles:I | grep -q "state=RUNNING preset=SNOW" \
  && ok "the next key wakes the particles" || bad "the particles stayed at rest ($(adb logcat -d -s XrossParticles:I | tail -1))"
# 2b.4f: the pack's background filter (a GL pass under the menu) and a custom UI filter
# (postprocess.agsl, AGSL on Android 13+) hot-reload. One that reads uTime animates only while the
# screen is in use and rests after 3 s without input (no frames); a broken one is turned off with
# a toast.
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
cat > "$XT_TMP/postprocess.agsl" <<'AGSL'
// A slow brightness wave down the menu.
uniform shader content;
uniform float uTime;
uniform float2 uResolution;
half4 main(float2 p) {
    half4 c = content.eval(p);
    float k = 0.92 + 0.08 * sin(uTime * 2.0 + p.y / uResolution.y * 6.2831);
    return half4(c.rgb * half(k), c.a);
}
AGSL
adb push "$XT_TMP/postprocess.agsl" /sdcard/Xross/themes/smoke_pack/postprocess.agsl >/dev/null
adb logcat -c
fx='{"schemaVersion": 2, "name": "Smoke Pack Live", "author": "smoke", "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"}, "effects": {"backgroundFilter": "LCD_GRID", "uiFilter": "CUSTOM"}}'
adb shell "echo '$fx' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
key KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_UP
pause 0.5
adb logcat -d -s XrossFilter:I | tail -1 | grep -qE "background=LCD_GRID .*cellPx=[3-9]" \
  && ok "effects.backgroundFilter draws an LCD grid over the background, in whole render pixels" \
  || bad "no LCD grid ($(adb logcat -d -s XrossFilter:I | tail -1))"
if [ "$sdk" -ge 33 ]; then
  adb logcat -d -s XrossUiFilter:I | grep -q "filter=CUSTOM state=ANIMATING" \
    && ok "a pack's postprocess.agsl that reads uTime animates over the menu" \
    || bad "the custom UI filter isn't animating ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
  pause 4
  adb logcat -d -s XrossUiFilter:I | tail -1 | grep -q "filter=CUSTOM state=RESTING" \
    && ok "3 s without input: the UI filter rests" || bad "the UI filter didn't rest ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
  f0=$(frames); pause 3; f1=$(frames)
  [ "$f0" = "$f1" ] && ok "a resting UI filter draws no frames" || bad "$((f1 - f0)) frames drawn under a resting UI filter"
  adb logcat -c
  adb shell "echo 'uniform shader content; half4 main(float2 p) { return content.eval(p) * nope; }' > /sdcard/Xross/themes/smoke_pack/postprocess.agsl"
  adb shell "echo '$fx' > /sdcard/Xross/themes/smoke_pack/theme.json"
  pause 1
  expect "UI filter postprocess.agsl" "a broken postprocess.agsl is reported in a toast"
  adb logcat -d -s XrossUiFilter:I | grep -q "filter=CUSTOM state=FAILED" \
    && ok "an AGSL error turns the UI filter off (the menu is drawn without it)" \
    || bad "no FAILED state ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
else
  adb logcat -d -s XrossUiFilter:I | grep -q "filter=CUSTOM state=UNSUPPORTED" \
    && ok "below Android 13 the UI filter stays off" || bad "UI filter state ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
  skip 4 "AGSL UI filters need Android 13 (this device runs API $sdk)"
fi
adb shell rm -f /sdcard/Xross/themes/smoke_pack/postprocess.agsl
adb shell "echo '$good' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
top_list
key KEYCODE_DPAD_CENTER
pause 1.5
expect "Default" "cycling returns to the Default pack"
key KEYCODE_BACK

section "Theme import and variants"
# Two .xtheme files in Download: one with a zip-slip entry, one valid pack (wrapped in a folder)
# with two variants, a Settings override and a SNES override under its "sfc" folder alias.
adb shell rm -f /sdcard/Download/smoke_variants.xtheme /sdcard/Download/smoke_slip.xtheme
adb push "$FIX/smoke_variants.xtheme" "$FIX/smoke_slip.xtheme" /sdcard/Download/ >/dev/null
media_scan
# tap_attr <attr="value">: tap the first on-screen node with that attribute (needs a fresh dump).
tap_attr() {
  local b
  b=$(adb shell cat /sdcard/xross-ui.xml | grep -o "$1[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | head -1)
  [ -n "$b" ] || return 1
  read -r x1 y1 x2 y2 <<<"$(sed 's/bounds="//; s/"//; s/\]\[/ /; s/[][]//g; s/,/ /g' <<<"$b")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}
# pick <file name> [wait]: tap that file in the system document picker, then wait (2 s default).
# The emulator's picker lists the pushed file under Recent; a phone's may open on an empty Recent
# (Galaxy A55) or a busy Downloads, so after two looks it searches for the file by name.
pick() {
  local found=
  for i in 1 2 3 4 5 6; do
    dump
    # A list shows the name as text; the image picker's grid only as a thumbnail's description.
    { tap_attr "text=\"$1\"" || tap_attr "content-desc=\"$1,"; } && { found=1; break; }
    if [ "$i" -eq 2 ] && tap_attr 'content-desc="Search"'; then
      pause 1; adb shell input text "${1%.*}"; adb shell input keyevent KEYCODE_ENTER; pause 1.5
    else
      pause 0.8
    fi
  done
  if [ -z "$found" ]; then bad "the document picker lists $1"; key KEYCODE_BACK; return; fi
  pause "${2:-2}"
}
top
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
press DPAD_DOWN 20
expect "Import theme (.xtheme)" "Settings ▸ Theme ends with Import theme"
key KEYCODE_DPAD_CENTER
pause 2
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q documentsui \
  && ok "Import theme opens the system document picker" || bad "the document picker didn't open"
# The rejection is a 2.6 s toast: read it straight away.
pick smoke_slip.xtheme 0.3
expect "Theme not imported" "a .xtheme with a ../ entry is rejected"
adb shell ls /sdcard/Xross/themes | grep -qiE 'slip|^\.import' && bad "the rejected archive left files" || ok "a rejected archive writes nothing"
adb shell ls /sdcard/evil.json /sdcard/Xross/evil.json >/dev/null 2>&1 && bad "zip slip wrote evil.json" || ok "no file escaped Xross/themes"
key KEYCODE_DPAD_CENTER
pause 2
pick smoke_variants.xtheme
for _ in 1 2 3 4 5 6; do adb shell ls /sdcard/Xross/themes/smoke-variants/theme.json >/dev/null 2>&1 && break; pause 0.5; done
adb shell ls /sdcard/Xross/themes/smoke-variants/theme.json >/dev/null 2>&1 \
  && ok "a valid .xtheme unpacks into Xross/themes/smoke-variants" || bad "smoke-variants not installed"
# settings.json is written off the main thread: give it a moment.
for _ in $(seq 16); do settings_has '"themePack": "smoke-variants"' && break; pause 0.5; done
settings_has '"themePack": "smoke-variants"' \
  && ok "the imported pack is applied" \
  || bad "the imported pack isn't active (settings.json has $(adb shell cat /sdcard/Xross/config/settings.json | grep -o '"themePack": "[^"]*"'))"
top_list
expect "Smoke Variants by smoke · 3 packs" "the imported pack is listed and loads without problems"
key KEYCODE_DPAD_DOWN
expect "Standard · 3 looks in Smoke Variants" "a pack with variants shows Theme variant"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "BIOS Matrix · 3 looks" "Theme variant cycles to the next variant"
settings_has '"themeVariant": "matrix"' && ok "the variant is saved to settings.json" || bad "themeVariant not in settings.json"
key KEYCODE_BUTTON_Y
expect "Cyber Lilac" "Theme variant Options list every variant"
key KEYCODE_BACK
# The same file again: ask before replacing; Keep both installs a copy.
press DPAD_DOWN 30 KEYCODE_DPAD_CENTER
pause 2
pick smoke_variants.xtheme
expect "is already installed" "re-importing an installed pack asks first"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 2
adb shell ls /sdcard/Xross/themes/smoke-variants-2/theme.json >/dev/null 2>&1 \
  && ok "Keep both installs the copy as smoke-variants-2" || bad "smoke-variants-2 not installed"
# The SNES override's 3:4 tiles: a portrait game tile in Super Nintendo (colors aren't in the tree).
home
all_games "$(collections_above)"
expect "Super Nintendo" "All Games lists the SNES folder"
key KEYCODE_DPAD_CENTER
pause 1
expect "Chrono Trigger" "a system override doesn't break the game list"
# Back to Default for the rest of the run.
category 0
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
top_list
key KEYCODE_BUTTON_Y
press DPAD_UP 8 KEYCODE_DPAD_CENTER
pause 1.5
expect "Default" "Theme pack Options switch back to Default"
expect_not "Theme variant" "Default has no Theme variant row"
key KEYCODE_BACK
adb shell rm -rf /sdcard/Xross/themes/smoke-variants /sdcard/Xross/themes/smoke-variants-2 /sdcard/Download/smoke_variants.xtheme /sdcard/Download/smoke_slip.xtheme

section "Effects in Settings ▸ Theme (2b.4f)"
# Theme ▸ Background ends with Background shader and Background filter; Theme ▸ Effects has UI
# filter (experimental), Focus frame style, Ambient particles and App icon mask below Surface style.
# Each cycles "Theme pack (…)" first, then its choices; all are saved as display.effects.
category 0
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
top_list
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
press DPAD_DOWN 3
expect "Theme pack (Wave) · Behind the menu" "Background shader follows the pack (Wave)"
adb logcat -c
press DPAD_CENTER 3
expect "Bokeh · Behind the menu" "Background shader cycles Wave, Matrix, Bokeh"
settings_has '"shaderPreset": "BOKEH"' && ok "the shader is saved as display.effects.shaderPreset" || bad "shaderPreset not in settings.json"
adb logcat -d -s XrossEffects:I | tail -1 | grep -q "shader=BOKEH .*user=true" \
  && ok "the user's shader replaces the pack's" || bad "shader not applied ($(adb logcat -d -s XrossEffects:I | tail -1))"
key KEYCODE_DPAD_DOWN
press DPAD_CENTER 2
expect "Scanlines · Over the background only" "Background filter cycles to Scanlines"
pause 0.5
adb logcat -d -s XrossFilter:I | tail -1 | grep -qE "background=SCANLINES .*linePx=[2-9]" \
  && ok "scanlines are drawn on the GL side, at least 2 render pixels apart" || bad "no scanline pass ($(adb logcat -d -s XrossFilter:I | tail -1))"
key KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
press DPAD_CENTER 3
expect_any "Bloom · Over the whole menu" "Bloom · Needs Android 13" "UI filter cycles to Bloom"
if [ "$sdk" -ge 33 ]; then
  adb logcat -d -s XrossUiFilter:I | tail -1 | grep -q "filter=BLOOM state=STATIC" \
    && ok "Bloom is built once (static)" || bad "Bloom state ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
else
  adb logcat -d -s XrossUiFilter:I | tail -1 | grep -q "filter=BLOOM state=UNSUPPORTED" \
    && ok "Bloom stays off below Android 13" || bad "Bloom state ($(adb logcat -d -s XrossUiFilter:I | tail -1))"
fi
pause 4
f0=$(frames); pause 3; f1=$(frames)
[ "$f0" = "$f1" ] && ok "with Bloom on, a resting menu draws no frames" || bad "$((f1 - f0)) frames drawn at rest under Bloom"
key KEYCODE_DPAD_DOWN
press DPAD_CENTER 2
expect "Static neon · Around the focused item" "Focus frame style cycles to Static neon"
adb logcat -d -s XrossCosmetics:I | tail -1 | grep -q "frame=STATIC_NEON" \
  && ok "the user's focus frame is drawn" || bad "frame not applied ($(adb logcat -d -s XrossCosmetics:I | tail -1))"
key KEYCODE_DPAD_DOWN
press DPAD_CENTER 2
expect "Snow · Over the art" "Ambient particles cycle to Snow"
adb logcat -d -s XrossParticles:I | grep -q "preset=SNOW" \
  && ok "the user's particles run" || bad "no particles ($(adb logcat -d -s XrossParticles:I | tail -1))"
key KEYCODE_DPAD_DOWN
press DPAD_CENTER 3
expect "Circle · Installed apps' icons" "App icon mask cycles System, Squircle, Circle"
adb logcat -d -s XrossCosmetics:I | tail -1 | grep -q "mask=CIRCLE" \
  && ok "the user's icon mask is used" || bad "mask not applied ($(adb logcat -d -s XrossCosmetics:I | tail -1))"
# Back to Theme ▸ Background ▸ Xross wallpaper, its third row.
key KEYCODE_BACK KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER
press DPAD_DOWN 2
expect "None (the background shader shows) · Confirm to pick one" "Xross wallpaper offers to pick a picture"
adb push "$FIX/smoke_wallpaper.png" /sdcard/Download/smoke_pick.png >/dev/null
media_scan
key KEYCODE_DPAD_CENTER
pause 2
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q documentsui \
  && ok "Pick custom opens the system picker" || bad "the picker didn't open"
adb logcat -c
pick smoke_pick.png 0.3
expect "Xross wallpaper set" "a picked picture becomes the Xross wallpaper"
for _ in $(seq 10); do settings_has '"customWallpaper": "content://' && break; pause 0.5; done
settings_has '"customWallpaper": "content://' && ok "its URI is saved as display.customWallpaper" || bad "customWallpaper not saved"
pause 1.5
adb logcat -d -s XrossWallpaper:I | tail -1 | grep -q "wallpaper=custom .*opaque=true" \
  && ok "the picked picture shows behind the menu" || bad "no custom wallpaper ($(adb logcat -d -s XrossWallpaper:I | tail -1))"
# The picker's grant is kept: after a restart the picture still loads.
adb shell am force-stop "$PKG"
adb logcat -c
start_app
adb logcat -d -s XrossWallpaper:I | grep -q "wallpaper=custom .*opaque=true" \
  && ok "after a restart the picked picture still loads (persisted read grant)" \
  || bad "the picked picture didn't load after a restart ($(adb logcat -d -s XrossWallpaper:I | tail -1))"
category 0
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
top_list
press DPAD_DOWN 7
expect "Clears 6 effects" "Reset to theme defaults says what it would clear"
adb logcat -c
key KEYCODE_DPAD_CENTER
expect "Default's own look" "Reset to theme defaults confirms in a toast"
for _ in $(seq 10); do settings_has '"customWallpaper": ""' && break; pause 0.5; done
settings_has '"shaderPreset": ""' && settings_has '"customWallpaper": ""' \
  && ok "every override is cleared from settings.json" || bad "overrides left in settings.json"
adb logcat -d -s XrossEffects:I | tail -1 | grep -q "shader=WAVE bgFilter=NONE uiFilter=NONE frame=BREATHING_HALO particles=NONE mask=NONE user=false" \
  && ok "the pack's own effects are back" || bad "effects not reset ($(adb logcat -d -s XrossEffects:I | tail -1))"
expect "Nothing overrides Default" "the Reset row shows nothing is overridden"
key KEYCODE_BACK
adb shell rm -f /sdcard/Download/smoke_pick.png

section "Game-art palette"
# Two SNES games get hero art (a blue banner with an orange logo, a green one with a magenta logo)
# and are marked scraped in the library database (pulled, edited with the host's sqlite3, pushed
# back while Xross is stopped). Debug builds log each extraction (XrossPalette) and color change.
if command -v sqlite3 >/dev/null && adb shell run-as "$PKG" true 2>/dev/null; then
  db=$(tmpdir db)
  adb shell mkdir -p /sdcard/Xross/media/heroes/snes
  adb push "$FIX/heroes/Chrono Trigger (USA).png" "$FIX/heroes/EarthBound (USA).png" /sdcard/Xross/media/heroes/snes/ >/dev/null
  adb shell am force-stop "$PKG"
  for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$db/$f" 2>/dev/null; done
  [ -s "$db/xross.db-wal" ] || rm -f "$db/xross.db-wal" "$db/xross.db-shm"
  sqlite3 "$db/xross.db" "INSERT OR REPLACE INTO game_metadata (gameId, locked, scrapeStatus, scrapedAt)
    SELECT id, 0, 'OK', 1700000000000 FROM games WHERE platformId = 'snes' AND (fileName LIKE 'Chrono Trigger%' OR fileName LIKE 'EarthBound%');
    PRAGMA journal_mode = DELETE;" >/dev/null
  adb push "$db/xross.db" /data/local/tmp/xross-smoke.db >/dev/null
  adb shell chmod 644 /data/local/tmp/xross-smoke.db
  adb shell run-as "$PKG" sh -c "'cp /data/local/tmp/xross-smoke.db databases/xross.db && rm -f databases/xross.db-wal databases/xross.db-shm'"
  adb shell rm -f /data/local/tmp/xross-smoke.db
  adb logcat -c
  start_app 3
  home
  all_games "$(collections_above)"
  key KEYCODE_DPAD_CENTER
  pause 1.5
  expect "Chrono Trigger" "the SNES list opens with the scraped games"
  colors | grep "art=true" | grep -q "top=" && ok "a focused game's hero art recolors the theme" || bad "no game-art colors for Chrono Trigger"
  first=$(colors | grep "art=true" | tail -1 | grep -o "top=#[0-9A-F]*")
  key KEYCODE_DPAD_DOWN
  pause 1.5
  second=$(colors | grep "art=true" | tail -1 | grep -o "top=#[0-9A-F]*")
  [ -n "$second" ] && [ "$first" != "$second" ] && ok "the next game's art brings its own colors ($first → $second)" \
    || bad "EarthBound's colors didn't replace Chrono Trigger's ($first, $second)"
  key KEYCODE_DPAD_DOWN
  pause 1.5
  colors | tail -1 | grep -q "art=false" && ok "a game without art falls back to the theme's colors" || bad "colors stayed on the last game's art"
  key KEYCODE_DPAD_UP KEYCODE_DPAD_UP
  pause 1.5
  n=$(adb logcat -d -s XrossPalette:I | grep -c "extract ")
  [ "$n" -eq 2 ] && ok "revisiting a game reuses its cached colors (2 extractions for 4 visits)" || bad "$n extractions for 2 games"
  adb logcat -d -s XrossPalette:I | grep "extract " | grep -qv " on main" && ok "extraction runs off the main thread" || bad "extraction ran on the main thread"
  # Scrolling past a game faster than the 250 ms debounce extracts nothing for it: after a fresh
  # start, resting on Chrono Trigger extracts it, and EarthBound, skipped past, is never read.
  adb shell am force-stop "$PKG"; adb logcat -c
  start_app 3
  home
  all_games "$(collections_above)"
  key KEYCODE_DPAD_CENTER
  pause 0.8
  adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
  pause 1.5
  n=$(adb logcat -d -s XrossPalette:I | grep -c "extract ")
  [ "$n" -eq 1 ] && ok "a game scrolled past in under 250 ms is never extracted" || bad "$n extractions (expected 1: only the game the cursor rested on)"
  adb shell "rm -f '/sdcard/Xross/media/heroes/snes/Chrono Trigger (USA).png' '/sdcard/Xross/media/heroes/snes/EarthBound (USA).png'"
  adb shell am force-stop "$STUB"
  category 0
else
  skip 7 "the game-art palette needs sqlite3 on the host and a debuggable build"
fi

section "Boot intro and launch curtain (2b.5d)"
# A pack with a generated 3 s boot.mp4 (a test pattern and a tone) and "launchCurtain": "IRIS",
# made active by editing settings.json while Xross is stopped. Debug builds log XrossBoot when an
# intro is decided ("start mode=VIDEO … after=988 ms") and ends ("mode=VIDEO file=boot.mp4
# skipped=true at=1840 ms"), when the shader ramp ends
# ("uTransition=1.00 at=2508 ms") and the curtain of every launch ("curtain=IRIS title=…").
boot_line() { adb logcat -d -s XrossBoot:I | grep -- "$1" | tail -1; }
boot_wait() { # boot_wait <text> [half-seconds]: an XrossBoot line contains it in time
  for _ in $(seq "${2:-12}"); do [ -n "$(boot_line "$1")" ] && return 0; pause 0.5; done; return 1; }
boot_ok() { # boot_ok <text> <description>
  if boot_wait "$1"; then ok "$2 ($(boot_line "$1" | sed 's/.*XrossBoot: //'))"; else bad "$2 ($(adb logcat -d -s XrossBoot:I | tail -1))"; fi; }
cold_start() { # cold_start: a new process; prints am start -W's TotalTime (ms)
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -W -n "$PKG/.MainActivity" | grep -o "TotalTime: [0-9]*" | grep -o "[0-9]*"
}
set_json() { # set_json <key> <value>: a string value in settings.json (Xross stopped); adds display.effects keys it lacks
  adb shell am force-stop "$PKG"
  if adb shell cat /sdcard/Xross/config/settings.json | grep -q "\"$1\":"; then
    adb shell "sed -i 's/\"$1\": \"[^\"]*\"/\"$1\": \"$2\"/' /sdcard/Xross/config/settings.json"
  else
    adb shell "sed -i 's/\"iconMask\": \"\\([^\"]*\\)\"/\"iconMask\": \"\\1\", \"$1\": \"$2\"/' /sdcard/Xross/config/settings.json"
  fi
}
chrono() { # the SNES list, cursor on Chrono Trigger
  home
  all_games "$(collections_above)"
  key KEYCODE_DPAD_CENTER
}
movie_player() { adb shell dumpsys audio | grep "AudioPlaybackConfiguration" | grep "usage=USAGE_GAME content=CONTENT_TYPE_MOVIE" | grep -o 'state:[a-z]*' | tr '\n' ' '; }
bootdir=$(tmpdir boot)/smoke_boot
mkdir -p "$bootdir"
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=30:duration=3 -f lavfi -i "sine=frequency=440:duration=3" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$bootdir/boot.mp4"
echo '{"schemaVersion": 2, "name": "Smoke Boot", "effects": {"launchCurtain": "IRIS"}}' > "$bootdir/theme.json"
adb shell rm -rf /sdcard/Xross/themes/smoke_boot
adb push "$bootdir" /sdcard/Xross/themes/ >/dev/null
# From the pack's and Xross's defaults, whatever the sections above left in Settings ▸ Theme.
set_json bootIntro ""
set_json launchCurtain ""
set_json quickProfile NONE
set_json themePack default
t_plain=$(cold_start)
pause 3
set_json themePack smoke_boot
t_video=$(cold_start)
pause 1
p=$(movie_player)
[[ "$p" == *"state:started"* && -n "$(boot_line "start mode=VIDEO")" && -z "$(boot_line skipped=)" ]] \
  && ok "a cold start plays the pack's boot.mp4 (USAGE_GAME / CONTENT_TYPE_MOVIE)" || bad "no boot video playing (${p:-no player}; $(adb logcat -d -s XrossBoot:I | tail -1))"
key KEYCODE_DPAD_RIGHT
boot_ok "mode=VIDEO file=boot.mp4 skipped=true" "a key skips the boot video"
[ -z "$(adb logcat -d -s XrossSound:I | grep role=)" ] && ok "the skipping key doesn't reach the menu" || bad "the skip also moved the menu ($(adb logcat -d -s XrossSound:I | tail -1))"
key KEYCODE_DPAD_RIGHT
adb logcat -d -s XrossSound:I | grep -q "role=category_switch" && ok "the next key reaches the menu" || bad "no category switch after the skip"
cold_start >/dev/null
pause 3
boot_ok "mode=VIDEO file=boot.mp4 skipped=false" "untouched, the video ends by itself"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" -f 0x10008000 >/dev/null # a new activity in the same process
pause 4
[ -z "$(boot_line mode=)" ] && ok "a warm start shows no intro" || bad "warm start played $(boot_line mode=)"
if [[ "$t_plain" =~ ^[0-9]+$ && "$t_video" =~ ^[0-9]+$ ]]; then
  ok "am start -W: ${t_plain} ms to the first frame without a boot video, ${t_video} ms with one"
else
  bad "am start -W gave no TotalTime ($t_plain / $t_video)"
fi
set_json bootIntro SHADER
cold_start >/dev/null
boot_ok "uTransition=1.00" "Boot intro ▸ Shader ramps uTransition to 1 (over the pack's video)"
boot_ok "mode=SHADER file=none skipped=false" "the shader intro ends by itself"
set_json quickProfile MONOCHROME_EINK
cold_start >/dev/null
boot_ok "mode=FADE" "E-Ink turns the intro off (the fade-in)"
set_json quickProfile NONE
set_json bootIntro OFF
if has_stub; then
  curtain_launch() { # curtain_launch <logged curtain> <description>: Chrono Trigger in the stub
    adb logcat -c
    start_app 4
    chrono
    key KEYCODE_DPAD_CENTER
    boot_ok "curtain=$1 title=Chrono Trigger" "$2"
    pause 2
    adb shell am force-stop "$STUB"
  }
  curtain_launch IRIS "the pack's IRIS curtain on a stub launch"
  for c in FLASH FADE_BLACK BLOOM; do
    set_json launchCurtain "$c"
    curtain_launch "$c" "Launch curtain ▸ $c"
  done
  set_json quickProfile MONOCHROME_EINK
  curtain_launch BLACK "E-Ink snaps the curtain to black"
  set_json quickProfile NONE
else
  skip 5 "install the RetroArch stub (./gradlew :emulator-stub:installRetroarchDebug)"
fi
set_json launchCurtain ""
set_json bootIntro ""
set_json themePack default
adb shell rm -rf /sdcard/Xross/themes/smoke_boot
start_app

suite_end
