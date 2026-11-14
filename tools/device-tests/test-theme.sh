#!/usr/bin/env bash
# Theme suite (281 checks): Settings ▸ Theme and its folders (theme color, wave and avatar panels,
# platform folders, surface style, the 2b.4f effect rows, Pick custom wallpaper, Reset to theme
# defaults), quick profiles (OLED, Y2K, E-Ink), color layers (adaptive colors,
# circadian curve, battery tint and charging pulse through `dumpsys battery`), the Sound section,
# v2 theme packs and hot reload (and a pack's wallpaper.png, which stops the wave and is pre-blurred
# for frosted surfaces; a pack's focus frame, icon mask and icon filter, with the Apps list's icons
# drawn once per style; a pack's foreground particles, resting after 10 s; a pack's background
# filter and custom postprocess.agsl, resting and failing safely), .xtheme import (zip-slip, variants, overrides, Keep both), and the
# game-art palette (hero PNGs, scraped flags patched into the database, extraction cache, debounce),
# and the cold-start intro and launch curtains (2b.5d: a generated boot.mp4, the shader ramp, E-Ink,
# each curtain on a stub launch), and Settings ▸ Theme ▸ Components (2b.7a: battery modes on and off
# the charger, clock formats, three slot orders with the avatar HUD, network in airplane mode,
# the system volume, the avatar left out, a minute tick at rest; 2b.7b: the mini player as a card,
# a status-bar ticker and off, each button-glyph set in the hints, Search and the HUD, and title
# badges on a scraped game, an unscraped one and an app; 2b.7c: the social stack's collapse cases,
# its cached friends and badge offline, the panel by keys and touch; 2b.7d: the achievement badge
# off, compact and as the rich card, offline without its friend strip, online with it).
#
#   tools/device-tests/test-theme.sh
#
# Needs python3 on the host; the game-art block also needs sqlite3 and a debuggable build (skipped
# otherwise). Colors are read from debug-build logs (XrossColors, XrossPalette, WaveLoop).
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
# The battery override is always reset at exit, even after Ctrl-C.
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin theme 281

setup_base_env
setup_theme_fixtures
FIX=$XT_TMP/theme
start_app
category 0

# Settings ▸ Theme (the fourth Settings folder) with the Default pack: Theme pack, Quick profile,
# then the folders Colors, Background, Effects, Intro and transitions, Components, Menu and profile, then
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
# Menu and profile (four folders below Background): Profile customization, Button hints,
# Platform folders.
key KEYCODE_BACK
press DPAD_DOWN 4 KEYCODE_DPAD_CENTER
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
# Effects (three folders above Menu and profile): Surface style, UI filter (experimental), Focus
# frame style, Ambient particles, App icon mask.
key KEYCODE_BACK
press DPAD_UP 3 KEYCODE_DPAD_CENTER
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
# rest_frames: f0 and f1 around 3 s at rest that don't cross a minute boundary, where the status
# bar's clock draws its one frame.
rest_frames() {
  local s
  s=$(adb shell date +%S | tr -d '\r'); s=${s#0}
  [ "$s" -ge 52 ] && adb shell sleep $((62 - s))
  f0=$(frames); pause 3; f1=$(frames)
}
rest_frames
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
  rest_frames
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
rest_frames
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
press DPAD_DOWN 8
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


section "Components (2b.7a)"
# Settings ▸ Theme ▸ Components (the folder after Intro and transitions): Status bar left and
# right (Confirm opens the slot list: Theme pack, each slot with its moves, then Add), Battery
# display and Clock format, saved as display.components. The bar's glyphs are described
# ("Battery gauge, 2 of 3 bars", "Wi-Fi, 3 of 3 bars", "Volume 2 of 3", "Offline"), and debug
# builds log one XrossStatus line per change. XROSS_SHOTS=<dir> keeps a screenshot of each state.
shot() { if [ -n "${XROSS_SHOTS:-}" ]; then mkdir -p "$XROSS_SHOTS"; adb exec-out screencap -p > "$XROSS_SHOTS/components-$1.png"; fi; }
flat_settings() { adb shell cat /sdcard/Xross/config/settings.json | tr -d ' \n\r'; }
await_flat() { # await_flat <text in settings.json without spaces>
  for _ in $(seq 10); do flat_settings | grep -qF -- "$1" && return 0; pause 0.5; done
  return 1
}
xml() { dump; adb shell cat /sdcard/xross-ui.xml; }
has_desc() { xml | grep -qF "content-desc=\"$1"; }
descs() { xml | grep -o 'content-desc="[^"]\{1,\}"' | sort; }
# The clock's and the gauge's bounds: the HUD's expansion must never move them.
bar_bounds() {
  xml | grep -oE '(text="[0-9]{1,2}/[0-9]{1,2}  [^"]*"|text="[0-9]{1,3}%"|content-desc="Battery gauge[^"]*")[^>]*bounds="[^"]*"' \
    | grep -o 'bounds="[^"]*"' | tr '\n' ' '
}
# Left edge of the first text matching <ERE>.
x_of() { xml | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+" | grep -oE '\[[0-9]+$' | tr -d '[' | head -1; }
clock_is() { screen | grep -qE "$1"; }
open_components() {
  category 0
  press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
  top_list
  press DPAD_DOWN 6 KEYCODE_DPAD_CENTER
}
# While the HUD is open the right cluster is fully faded, and Compose leaves transparent nodes out
# of the accessibility tree: an empty reading then is the fade, not a missing bar.
hud_cycle() { # hud_cycle <order>: the HUD fades the right cluster and hands it back in place
  category 0
  pause 1
  local before during after
  before=$(bar_bounds)
  key KEYCODE_DPAD_UP
  pause 0.8
  during=$(bar_bounds)
  shot "hud-$2"
  key KEYCODE_DPAD_DOWN
  pause 0.8
  after=$(bar_bounds)
  shot "bar-$2"
  if [ -n "$before" ] && [ -z "$during" ] && [ "$before" = "$after" ]; then
    ok "$1: the HUD fades the clock and battery and gives them back without moving them"
  else
    bad "$1: before | under the HUD | after: $before | $during | $after"
  fi
}
adb shell dumpsys battery unplug
adb shell dumpsys battery set level 64
adb shell dumpsys battery set status 3
open_components
expect "Status bar left" "Settings ▸ Theme ▸ Components lists the status-bar rows"
expect "Empty (theme pack) · Top-left" "the left cluster follows the Default pack (empty)"
# Only the focused row shows its subtitle.
key KEYCODE_DPAD_DOWN
expect "Clock · Battery · Avatar (theme pack)" "the right cluster follows the Default pack"
key KEYCODE_DPAD_DOWN
expect "Theme pack (Icon and percentage)" "Battery display follows the pack (BOTH)"
expect "64%" "the bar draws the battery percentage"
expect_desc "Battery gauge, 2 of 3 bars" "the bar draws the battery gauge beside it"
shot both
hud_cycle "Default order" default

# Battery display (third row): Theme pack → Icon → Percentage → Icon and percentage → Time to full.
open_components
press DPAD_DOWN 2 KEYCODE_DPAD_CENTER
expect "Icon · Time to full" "Battery display cycles to Icon"
await_flat '"batteryMode":"CAPSULE_ICON"' && ok "it is saved as display.components.batteryMode" || bad "batteryMode not in settings.json"
expect_not "64%" "Icon draws no percentage"
expect_desc "Battery gauge, 2 of 3 bars" "Icon draws the gauge"
shot icon
key KEYCODE_DPAD_CENTER
expect "64%" "Percentage draws the percentage"
has_desc "Battery gauge" && bad "Percentage still draws the gauge" || ok "Percentage draws no gauge"
shot percentage
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Time to full · " "Battery display cycles to Time to full"
expect "64%" "Time to full off the charger draws the percentage"
adb logcat -c
adb shell dumpsys battery set ac 1
adb shell dumpsys battery set status 2
pause 2
s=$(screen)
full=$(grep -m1 -E '^[0-9]+ [hm]( [0-9]+ m)? to full$' <<<"$s")
if [ -n "$full" ]; then
  ok "on the charger, Time to full draws '$full'"
elif grep -qxF "64%" <<<"$s" && adb logcat -d -s XrossStatus:I | grep -q "battery=64+ toFull=-"; then
  ok "on the charger with no estimate from Android (−1), Time to full draws the percentage"
else
  bad "Time to full on the charger ($(adb logcat -d -s XrossStatus:I | tail -1))"; show "$s"
fi
shot time-remaining
adb shell dumpsys battery set ac 0
adb shell dumpsys battery set status 3
key KEYCODE_DPAD_CENTER
await_flat '"batteryMode":null' && ok "Theme pack hands the battery back (null)" || bad "batteryMode not reset to null"

# Clock format (fourth row): Theme pack → 24 h → 12 h → Date and time → Time only.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "24 h · " "Clock format cycles to 24 h"
clock_is '^[0-9]{1,2}/[0-9]{1,2}  ([01][0-9]|2[0-3]):[0-5][0-9]$' && ok "24 h: date and a 24-hour time" || { bad "24 h clock"; show "$(screen)"; }
key KEYCODE_DPAD_CENTER
clock_is '^[0-9]{1,2}/[0-9]{1,2}  [0-9]{1,2}:[0-5][0-9][^0-9]*[AP]M$' && ok "12 h: date and a 12-hour time" || { bad "12 h clock"; show "$(screen)"; }
key KEYCODE_DPAD_CENTER
clock_is '^[A-Z][a-z]{2}, [A-Z][a-z]{2} [0-9]{1,2}  [0-9]{1,2}:[0-5][0-9]' && ok "Date and time: weekday, month and day, then the time" || { bad "Date and time clock"; show "$(screen)"; }
key KEYCODE_DPAD_CENTER
await_flat '"clockFormat":"m"' && ok "Time only is saved as the skeleton m" || bad "clockFormat m not in settings.json"
clock_is '^[0-9]{1,2}:[0-5][0-9]([^0-9]*[AP]M)?$' && ! clock_is '^[0-9]{1,2}/[0-9]{1,2}  ' \
  && ok "Time only: the time without a date" || { bad "Time only clock"; show "$(screen)"; }
key KEYCODE_DPAD_CENTER
clock_is '^[0-9]{1,2}/[0-9]{1,2}  [0-9]{1,2}:[0-5][0-9]' && ok "Theme pack: back to the default clock" || { bad "default clock"; show "$(screen)"; }

# Second order: Status bar right ▸ Clock ▸ Move right gives Battery, Clock, Avatar.
top_list
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
expect "Add" "Status bar right opens its slot list (Theme pack, the slots, Add)"
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_CENTER
await_flat '"statusBarRight":["BATTERY","CLOCK","AVATAR"]' && ok "Clock ▸ Move right saves Battery, Clock, Avatar" || bad "statusBarRight not saved ($(flat_settings | grep -o '"statusBarRight":[^]]*]'))"
expect "Move left" "the list reopens on the clock's moves"
key KEYCODE_BACK KEYCODE_BACK
bx=$(x_of '[0-9]{1,3}%'); cx=$(x_of '[0-9]{1,2}/[0-9]{1,2}  [^"]*')
[ -n "$bx" ] && [ -n "$cx" ] && [ "$bx" -lt "$cx" ] && ok "the battery now draws left of the clock" || bad "slot order on screen (battery x=$bx, clock x=$cx)"
hud_cycle "Battery first" battery-first

# Third order: Status bar left ▸ Add ▸ Network, then Add ▸ Volume.
open_components
key KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
key KEYCODE_BACK
await_flat '"statusBarLeft":["WIFI","VOLUME"]' && ok "Add ▸ Network and Add ▸ Volume save the left cluster" || bad "statusBarLeft not saved ($(flat_settings | grep -o '"statusBarLeft":[^]]*]'))"
expect "Network · Volume · Top-left" "Status bar left shows its slots"
if has_desc "Wi-Fi," || has_desc "Mobile data" || has_desc "Ethernet"; then
  ok "Network shows the default network ($(descs | grep -oE 'Wi-Fi[^"]*|Mobile data|Ethernet' | head -1))"
else
  bad "no network glyph ($(descs | tr '\n' ' '))"
fi
has_desc "Volume" && bad "Volume shows before the volume changed" || ok "Volume stays hidden until the volume changes"
# The volume keys, as a user would (`cmd media_session volume --set` doesn't move the emulator's
# speaker volume). A press while Android's volume panel is hidden only raises the panel, so the
# first press shows it and the next one steps. One step down usually keeps the same level: it
# still counts as a change.
music_volume() { adb shell cmd media_session volume --stream 3 --get 2>/dev/null | grep -o 'volume is [0-9]*' | grep -o '[0-9]*$'; }
# While Android's panel has the focus, uiautomator doesn't list the slot that just appeared, so
# its state is read from XrossStatus ("volume=<level>#<changes>"); the screenshots show the glyph.
vol=$(music_volume)
adb logcat -c
key KEYCODE_VOLUME_DOWN
key KEYCODE_VOLUME_DOWN
pause 0.3
step=$(adb logcat -d -s XrossStatus:I | grep -oE 'volume=[0-9]#[1-9][0-9]*' | tail -1)
[ -n "$step" ] && ok "a volume step shows Volume ($step)" || bad "Volume didn't show after a change ($(adb logcat -d -s XrossStatus:I | tail -1))"
shot volume
pause 4
has_desc "Volume" && bad "Volume still shown 4 s after the change" || ok "Volume hides again after 3 s"
key KEYCODE_VOLUME_DOWN
for _ in $(seq 16); do [ "$(music_volume)" = 0 ] && break; key KEYCODE_VOLUME_DOWN; done
pause 0.3
shot volume-muted
adb logcat -d -s XrossStatus:I | grep -qE 'volume=0#[1-9]' && ok "volume 0 shows the muted glyph" || bad "no muted volume ($(adb logcat -d -s XrossStatus:I | tail -1))"
key KEYCODE_VOLUME_UP
for _ in $(seq 16); do [ "$(music_volume)" -ge "${vol:-8}" ] && break; key KEYCODE_VOLUME_UP; done
pause 4
hud_cycle "With a left cluster" left-cluster

# Airplane mode: the network slot follows within 2 s, and comes back after.
category 0
pause 1
adb logcat -c
t0=$(adb shell date +%s.%N | tr -d '\r')
adb shell cmd connectivity airplane-mode enable
pause 3
t1=$(adb logcat -d -v epoch -s XrossStatus:I | grep -m1 "network=OFFLINE" | awk '{print $1}')
if [ -n "$t1" ] && python3 -c "import sys; sys.exit(0 if float('$t1') - float('$t0') <= 2.0 else 1)"; then
  ok "airplane mode on: the network slot goes offline in $(python3 -c "print(round(float('$t1') - float('$t0'), 2))") s"
else
  bad "airplane mode on: no offline within 2 s ($(adb logcat -d -s XrossStatus:I | tail -1))"
fi
expect_desc "Offline" "the offline glyph is drawn"
shot offline
adb logcat -c
adb shell cmd connectivity airplane-mode disable
back=
for _ in $(seq 20); do
  if adb logcat -d -s XrossStatus:I | grep -qE "network=(WIFI|CELLULAR|ETHERNET)"; then back=1; break; fi
  pause 1
done
[ -n "$back" ] && ok "airplane mode off: the network slot comes back ($(adb logcat -d -s XrossStatus:I | grep -oE 'network=[A-Z]+/[0-9]' | tail -1))" \
  || bad "airplane mode off: the network slot stayed offline"

# The avatar left out: no avatar, and Up at the top of a list stays in the list.
open_components
with=$(descs)
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_CENTER
expect "Avatar removed from the status bar" "Avatar ▸ Remove says where the User Card is"
key KEYCODE_BACK
await_flat '"statusBarRight":["BATTERY","CLOCK"]' && ok "the right cluster is saved without the avatar" || bad "statusBarRight still has the avatar"
without=$(descs)
[ -n "$(comm -23 <(echo "$with") <(echo "$without"))" ] && ok "the avatar is gone from the bar" || bad "the avatar is still drawn"
shot no-avatar
# category's own top() climbs onto the avatar, so go to the top by hand here.
key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
press BUTTON_L1 6
press DPAD_UP 25
key KEYCODE_DPAD_UP
pause 0.8
expect "Theme" "Up at the top of Settings stays in the list without an avatar"

# Reset to theme defaults hands the components back to the pack.
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
top_list
press DPAD_DOWN 8
expect "2 components" "Reset to theme defaults counts the components"
key KEYCODE_DPAD_CENTER
await_flat '"statusBarLeft":null,"statusBarRight":null' && ok "Reset clears display.components" || bad "components left in settings.json"
[ "$(descs)" != "$without" ] && ok "the avatar is back after Reset" || bad "no avatar after Reset"

# At rest the bar's minute tick draws a frame or two, nothing more.
category 0
pause 12
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
s=$(adb shell date +%S | tr -d '\r'); s=${s#0}
adb shell sleep $((60 - s + 3))
frames=$(adb shell dumpsys gfxinfo "$PKG" | grep -m1 'Total frames rendered' | grep -o '[0-9]*$')
[ -n "$frames" ] && [ "$frames" -le 3 ] && ok "a minute tick at rest draws $frames frame(s)" || bad "a minute at rest drew ${frames:-?} frames"
adb shell dumpsys battery reset

section "Components (2b.7b)"
# Mini player (Theme pack → Floating card → Status bar ticker → Off), Button glyphs (Theme pack →
# Controller's own → Shapes → Colored letters → Letters, A on the right → Minimal outlines) and
# Title badges (an Options list: Theme pack, None, then each badge, on in the order turned on).
# The suite's keys come from the virtual keyboard, so the controller's own glyphs are Enter, Esc, Tab.
comp_row() { open_components; [ "$1" -gt 0 ] && press DPAD_DOWN "$1"; }
playing() { for _ in $(seq 12); do [ "$(music_state)" = "PLAYING" ] && return 0; pause 0.5; done; return 1; }
ticker_bounds() { xml | grep -oE 'text="Smoke Song [12] — Smoke Artist"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"'; }
# The glyph drawn just before a hint's label ("✕" before "Select"); the hints come last on screen
# (Search's keyboard has a "Space" key of its own).
glyph_before() { screen | grep -B1 -xF "$1" | tail -2 | head -1; }

if setup_media_fixtures; then
  adb shell am force-stop "$PKG"
  start_app
  category 2
  expect "Shuffle all" "Music lists Shuffle all for the mini-player checks"
  key KEYCODE_DPAD_CENTER
  playing && ok "a song plays" || bad "no song playing (state $(music_state))"
  category 0
  pause 0.5
  s=$(screen)
  grep -qxF "Smoke Artist" <<<"$s" && grep -qxE "Smoke Song [12]" <<<"$s" \
    && ok "Floating card (the default) draws the title and the artist on their own lines" || { bad "no floating card"; show "$s"; }
  shot mini-card
  comp_row 4
  expect "Theme pack (Floating card)" "Mini player follows the pack (Floating card)"
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
  await_flat '"miniPlayerMode":"STATUS_TICKER"' && ok "Status bar ticker is saved as display.components.miniPlayerMode" || bad "miniPlayerMode not saved"
  pause 0.5
  s=$(screen)
  grep -qxE "Smoke Song [12] — Smoke Artist" <<<"$s" && ok "the ticker draws 'Title — Artist' in the status bar" || { bad "no ticker"; show "$s"; }
  grep -qxF "Smoke Artist" <<<"$s" && bad "the floating card is still drawn with the ticker" || ok "no floating card with the ticker"
  shot mini-ticker
  tx=$(x_of 'Smoke Song [12] — Smoke Artist'); bx=$(x_of '[0-9]{1,3}%')
  [ -n "$tx" ] && [ -n "$bx" ] && [ "$tx" -gt "$bx" ] && ok "the ticker sits after the battery, before the avatar" || bad "ticker x=$tx, battery x=$bx"
  category 0
  pause 1
  before=$(ticker_bounds); key KEYCODE_DPAD_UP; pause 0.8; during=$(ticker_bounds); shot mini-ticker-hud
  key KEYCODE_DPAD_DOWN; pause 0.8; after=$(ticker_bounds)
  [ -n "$before" ] && [ -z "$during" ] && [ "$before" = "$after" ] \
    && ok "the HUD fades the ticker and gives it back in place" || bad "ticker before | under the HUD | after: $before | $during | $after"
  adb shell cmd media_session dispatch pause
  pause 1
  has_desc "❚❚  Smoke Song" && ok "paused, the ticker stays with the pause glyph" || bad "no paused ticker ($(descs | grep -i smoke | tr '\n' ' '))"
  shot mini-ticker-paused
  adb shell cmd media_session dispatch play
  playing
  comp_row 4
  key KEYCODE_DPAD_CENTER
  await_flat '"miniPlayerMode":"OFF"' && ok "Off is saved" || bad "miniPlayerMode OFF not saved"
  expect "Off · Music still plays" "Mini player Off says the music carries on"
  category 0
  pause 0.5
  screen | grep -qE "^Smoke Song [12]" && bad "Off still draws a card or a ticker" || ok "Off draws neither the card nor the ticker"
  [ "$(music_state)" = "PLAYING" ] && ok "playback carries on with the mini player off" || bad "music state $(music_state) with the mini player off"
  shot mini-off
  comp_row 4
  key KEYCODE_DPAD_CENTER
  await_flat '"miniPlayerMode":null' && ok "Theme pack hands the mini player back (null)" || bad "miniPlayerMode not reset"
  adb shell cmd media_session dispatch pause
else
  skip 15 "the mini-player checks need ffmpeg on the host for the song fixtures"
fi

# Button glyphs: the labels change, the confirm side doesn't (DPAD_CENTER confirms throughout).
comp_row 5
expect "Theme pack (Controller's own) · Labels only" "Button glyphs follows the pack (the controller's own)"
[ "$(glyph_before Select)" = "Enter" ] && ok "the keyboard's own glyphs: Enter selects" || bad "Select is labelled '$(glyph_before Select)'"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
await_flat '"glyphOverride":"PLAYSTATION"' && ok "Shapes is saved as display.components.glyphOverride" || bad "glyphOverride not saved"
[ "$(glyph_before Select)" = "✕" ] && [ "$(glyph_before Back)" = "○" ] && [ "$(glyph_before Options)" = "△" ] \
  && ok "Shapes: ✕ selects, ○ goes back, △ opens Options" || bad "Shapes: $(glyph_before Select) $(glyph_before Back) $(glyph_before Options)"
shot glyphs-shapes
key KEYCODE_DPAD_CENTER
[ "$(glyph_before Select)" = "A" ] && [ "$(glyph_before Back)" = "B" ] && [ "$(glyph_before Options)" = "Y" ] \
  && ok "Colored letters: A selects, B goes back, Y opens Options" || bad "Colored letters: $(glyph_before Select) $(glyph_before Back) $(glyph_before Options)"
shot glyphs-xbox
key KEYCODE_DPAD_CENTER
[ "$(glyph_before Select)" = "B" ] && [ "$(glyph_before Back)" = "A" ] \
  && ok "Nintendo letters on a south-confirm controller: Select is the south button, labelled B" || bad "Nintendo: $(glyph_before Select) $(glyph_before Back)"
key KEYCODE_DPAD_CENTER
await_flat '"glyphOverride":"MONOCHROME_MINIMAL"' && ok "Minimal outlines is saved" || bad "MONOCHROME_MINIMAL not saved"
[ "$(glyph_before Select)" = "▽" ] && [ "$(glyph_before Back)" = "▷" ] && [ "$(glyph_before Options)" = "△" ] \
  && ok "Minimal outlines: ▽ selects, ▷ goes back, △ opens Options" || bad "Minimal: $(glyph_before Select) $(glyph_before Back) $(glyph_before Options)"
shot glyphs-minimal
home
key KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER
expect "Space" "Search opens"
[ "$(glyph_before Space)" = "△" ] && ok "Search's hints draw the minimal set (△ Space)" || bad "Search: '$(glyph_before Space)' before Space"
shot glyphs-search
key KEYCODE_BACK
category 0
key KEYCODE_DPAD_UP
pause 0.8
s=$(screen)
grep -qxF "▽" <<<"$s" && ok "the avatar HUD's hints draw the minimal set" || { bad "HUD hints"; show "$s"; }
shot glyphs-hud
key KEYCODE_DPAD_DOWN
comp_row 5
key KEYCODE_DPAD_CENTER
await_flat '"glyphOverride":null' && ok "Theme pack hands the glyphs back (null)" || bad "glyphOverride not reset"
[ "$(glyph_before Select)" = "Enter" ] && ok "the keyboard's own glyphs are back" || bad "Select is labelled '$(glyph_before Select)'"

# Title badges: System, Release year, Developer and Playtime, on in that order. Chrono Trigger gets
# a release date, a developer and 75 minutes of playtime in the library database (as in the
# game-art block); EarthBound has none of them, Super Metroid isn't scraped.
comp_row 6
expect "None (theme pack) · Beside the focused game's title" "Title badges follows the pack (none)"
key KEYCODE_DPAD_CENTER
expect "Achievements" "Title badges opens the list of badges"
press DPAD_DOWN 2 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
press DPAD_DOWN 1 KEYCODE_DPAD_CENTER
expect "On · 4 of 4" "the list stays open, the last badge 4th of 4"
key KEYCODE_BACK
await_flat '"badgePills":["SYSTEM","YEAR","DEVELOPER","PLAYTIME"]' && ok "the badges are saved in the order turned on" \
  || bad "badgePills not saved ($(flat_settings | grep -o '"badgePills":[^]]*]'))"
if command -v sqlite3 >/dev/null && adb shell run-as "$PKG" true 2>/dev/null; then
  db=$(tmpdir db-pills)
  adb shell am force-stop "$PKG"
  for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$db/$f" 2>/dev/null; done
  [ -s "$db/xross.db-wal" ] || rm -f "$db/xross.db-wal" "$db/xross.db-shm"
  sqlite3 "$db/xross.db" "INSERT OR IGNORE INTO game_metadata (gameId, locked) SELECT id, 0 FROM games WHERE fileName LIKE 'Chrono Trigger%';
    UPDATE game_metadata SET releaseDate = '1995-03-11', developer = 'Square' WHERE gameId IN (SELECT id FROM games WHERE fileName LIKE 'Chrono Trigger%');
    UPDATE games SET playTimeMs = 4500000 WHERE fileName LIKE 'Chrono Trigger%';
    PRAGMA journal_mode = DELETE;" >/dev/null
  adb push "$db/xross.db" /data/local/tmp/xross-smoke.db >/dev/null
  adb shell chmod 644 /data/local/tmp/xross-smoke.db
  adb shell run-as "$PKG" sh -c "'cp /data/local/tmp/xross-smoke.db databases/xross.db && rm -f databases/xross.db-wal databases/xross.db-shm'"
  adb shell rm -f /data/local/tmp/xross-smoke.db
  start_app
  home
  all_games "$(collections_above)"
  key KEYCODE_DPAD_CENTER
  pause 1
  s=$(screen)
  if grep -qxF "SNES" <<<"$s" && grep -qxF "1995" <<<"$s" && grep -qxF "Square" <<<"$s" && grep -qxF "1h 15m" <<<"$s"; then
    ok "a scraped game shows System, Release year, Developer and Playtime"
  else
    bad "Chrono Trigger's badges"; show "$s"
  fi
  grep -q "Playtime:" <<<"$s" && bad "the playtime is still in the subtitle" || ok "the Playtime badge takes the playtime out of the subtitle"
  shot pills-scraped
  key KEYCODE_DPAD_DOWN
  pause 0.8
  s=$(screen)
  ! grep -qxF "1995" <<<"$s" && ! grep -qxF "Square" <<<"$s" && grep -qxF "SNES" <<<"$s" \
    && ok "badges are drawn on the focused game only, and a game without the data skips them" || { bad "EarthBound's badges"; show "$s"; }
  key KEYCODE_DPAD_DOWN
  pause 0.8
  expect_line "SNES" "an unscraped game keeps its System badge"
  shot pills-unscraped
  category 5
  pause 0.8
  s=$(screen)
  grep -qxE "SNES|1995|Square" <<<"$s" && bad "an app shows title badges" || ok "apps get no title badges"
else
  skip 5 "the title-badge data needs sqlite3 on the host and a debuggable build"
fi
comp_row 6
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
await_flat '"badgePills":null' && ok "Theme pack hands the badges back (null)" || bad "badgePills not reset"

section "Components (2b.7c)"
# Top-left (the last Components row): Theme pack → Social stack → Empty. The stack is described
# "Friends: N" (", K with new activity" while the badge shows); its panel lists FOLLOWING. The
# linked account here is fake (no key reaches RetroAchievements: the checks run in airplane mode),
# its profile and friends come from caches written into the Xross folder. Debug builds log one
# XrossRa line per friends refresh.
topleft_row() { comp_row 7; }
stack_desc() { xml | grep -o 'content-desc="Friends: [^"]*"' | head -1 | sed 's/content-desc="//; s/"$//'; }
now_ms() { echo "$(adb shell date +%s | tr -d '\r')000"; }
ra_log() { adb logcat -d -s XrossRa:I | grep -o 'friends=.*' ; }
link_fake_account() { # credentials in the app's own preferences (debug build), plus a fresh profile cache
  local d; d=$(tmpdir ra-link)
  printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
    '<map><string name="retroachievements_user">SmokeUser</string><string name="retroachievements_web_api_key">NotARealKey000</string></map>' > "$d/creds.xml"
  adb push "$d/creds.xml" /data/local/tmp/xross-creds.xml >/dev/null
  adb shell chmod 644 /data/local/tmp/xross-creds.xml
  adb shell run-as "$PKG" sh -c "'mkdir -p shared_prefs && cp /data/local/tmp/xross-creds.xml shared_prefs/xross_credentials.xml'"
  adb shell rm -f /data/local/tmp/xross-creds.xml
  printf '{"version":1,"username":"SmokeUser","profile":{"username":"SmokeUser","totalPoints":100,"fetchedAtMs":%s}}' "$(now_ms)" > "$d/profile.json"
  adb push "$d/profile.json" /sdcard/Xross/data/retroachievements.json >/dev/null
}
# Debug builds link the developer's .env account at every start (AppContainer,
# DEBUG_RETROACHIEVEMENTS_*): then nothing on the device is ever unlinked.
seeded_account() { adb shell run-as "$PKG" cat shared_prefs/xross_credentials.xml 2>/dev/null | grep -qE 'name="retroachievements_user">[^<]+'; }
unlink_fake_account() { adb shell run-as "$PKG" rm -f shared_prefs/xross_credentials.xml; adb shell rm -f /sdcard/Xross/data/retroachievements.json; }
write_friends() { # write_friends <following JSON array> <activity JSON object> <seen JSON object>
  local d now; d=$(tmpdir ra-friends); now=$(now_ms)
  printf '{"version":1,"owner":"SmokeUser","followingFetchedAtMs":%s,"following":%s,"total":3,"activity":%s,"seen":%s,"stackOpenedAtMs":%s}' \
    "$((now - 3600000))" "$1" "$2" "$3" "$((now - 7200000))" > "$d/friends.json"
  adb shell mkdir -p /sdcard/Xross/data/ra
  adb push "$d/friends.json" /sdcard/Xross/data/ra/friends.json >/dev/null
}
# Three followed users, each fetched an hour ago (stale: opening the stack refreshes): Alice and
# Carol moved on since the stack was last opened, Bob didn't.
FOLLOWING='[{"user":"Alice","ulid":"01ALICE"},{"user":"Bob","ulid":"01BOB"},{"user":"Carol","ulid":"01CAROL"}]'
activity() { # activity <user> <ulid> <game id> <title> <date>
  printf '"%s":{"user":"%s","ulid":"%s","gameId":%s,"gameTitle":"%s","consoleName":"Dreamcast","lastPlayed":"%s","lastSeen":"%s","fetchedAtMs":%s}' \
    "$2" "$1" "$2" "$3" "$4" "$5" "$5" "$(( $(now_ms) - 3600000 ))"
}
ACTIVITY="{$(activity Alice 01ALICE 3750 'Elemental Gimmick Gear' '2026-11-14 10:00:00'),$(activity Bob 01BOB 12 'Twelve' '2026-11-13 09:00:00'),$(activity Carol 01CAROL 77 'Seventy-seven' '2026-11-12 08:00:00')}"
SEEN='{"01ALICE":"1@2026-11-01 10:00:00","01BOB":"12@2026-11-13 09:00:00","01CAROL":"5@2026-11-01 10:00:00"}'

if adb shell run-as "$PKG" true 2>/dev/null; then
  topleft_row
  expect "Theme pack (Empty) · Friends you follow on RetroAchievements" "Top-left follows the pack (Empty), last in Components"
  key KEYCODE_DPAD_CENTER
  await_flat '"topLeftCluster":"SOCIAL_STACK"' && ok "Social stack is saved as display.components.topLeftCluster" || bad "topLeftCluster not saved"
  if seeded_account; then
    # Linked to the .env account, online: turning the stack on refreshed it against the live API.
    expect "Social stack · Friends you follow" "linked (the debug build's .env account), the row says what it shows"
    for _ in $(seq 20); do [ -n "$(ra_log)" ] && break; pause 0.5; done
    ra_log | grep -qE '^friends=[0-9]+ activity=[0-9]+ calls=[0-9]+$' && ok "turning the stack on refreshed it live ($(ra_log | tail -1))" || bad "no live refresh: $(ra_log)"
  else
    expect "Social stack · Needs a RetroAchievements account" "without an account the row says what it needs"
    category 0
    pause 0.5
    [ -z "$(stack_desc)" ] && ok "unlinked: the stack collapses" || bad "unlinked, the stack shows: $(stack_desc)"
  fi
  shot social-turned-on

  # Linked, offline, with a cache: the cached friends, newest activity first, and the badge.
  adb shell am force-stop "$PKG"
  adb shell cmd connectivity airplane-mode enable
  link_fake_account
  write_friends "$FOLLOWING" "$ACTIVITY" "$SEEN"
  adb logcat -c
  start_app
  category 0
  pause 0.5
  d=$(stack_desc)
  [ "$d" = "Friends: 3, 2 with new activity" ] && ok "offline with a cache: the stack shows the cached friends and a 2 badge" || bad "stack: '$d'"
  [ -z "$(ra_log)" ] && ok "a fresh profile and a friends cache at startup: no friends refresh" || bad "refreshed at startup: $(ra_log)"
  sx=$(xml | grep -oE 'content-desc="Friends: [^"]*"[^>]*bounds="\[[0-9]+' | grep -oE '\[[0-9]+$' | tr -d '[')
  cx=$(x_of '[0-9]{1,2}/[0-9]{1,2}  .*')
  [ -n "$sx" ] && [ -n "$cx" ] && [ "$sx" -lt "$cx" ] && ok "the stack sits top-left, left of the clock" || bad "stack x=$sx, clock x=$cx"
  shot social-stack

  # Up opens the avatar HUD; Left crosses to the stack's panel, which refreshes the stale list and,
  # offline, keeps the saved one.
  key KEYCODE_DPAD_UP KEYCODE_DPAD_LEFT
  expect "FOLLOWING" "Left from the avatar HUD opens the stack's panel"
  s=$(screen)
  a=$(grep -nxF "Alice" <<<"$s" | cut -d: -f1); b=$(grep -nxF "Bob" <<<"$s" | cut -d: -f1); c=$(grep -nxF "Carol" <<<"$s" | cut -d: -f1)
  [ -n "$a" ] && [ -n "$b" ] && [ -n "$c" ] && [ "$a" -lt "$b" ] && [ "$b" -lt "$c" ] \
    && ok "the panel lists the friends, most recent activity first" || { bad "panel order: Alice=$a Bob=$b Carol=$c"; show "$s"; }
  expect "Elemental Gimmick Gear · Dreamcast" "each friend shows their latest game"
  for _ in $(seq 10); do ra_log | grep -q "failed" && break; pause 0.5; done
  ra_log | grep -qE '^friends=3 activity=0 calls=1 failed' && ok "opening the stale stack refreshed once and stopped at the first failure ($(ra_log | tail -1))" || bad "XrossRa: $(ra_log | tr '\n' ' ')"
  expect "Offline · saved list" "offline, the panel keeps the saved list"
  shot social-panel
  key KEYCODE_BACK
  pause 0.5
  screen | grep -qxF "FOLLOWING" && bad "Back left the panel open" || ok "Back closes the panel"
  d=$(stack_desc)
  [ "$d" = "Friends: 3" ] && ok "opening the stack cleared its badge" || bad "after opening: '$d'"
  adb shell am force-stop "$PKG"
  start_app
  category 0
  pause 0.5
  [ "$(stack_desc)" = "Friends: 3" ] && ok "the cleared badge is kept in friends.json" || bad "after a restart: '$(stack_desc)'"
  adb shell cat /sdcard/Xross/data/ra/friends.json | grep -q "NotARealKey000" && bad "the key is in friends.json" || ok "friends.json holds no key"

  # Touch: a tap on the stack opens the panel, a tap outside closes it.
  b=$(xml | grep -oE 'content-desc="Friends: [^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]$')
  if [ -n "$b" ]; then
    read -r x1 y1 x2 y2 <<<"$(tr '[],' '   ' <<<"$b")"
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); pause 0.8
    screen | grep -qxF "FOLLOWING" && ok "a tap on the stack opens the panel" || bad "tap on the stack: no panel"
    # Anywhere off the panel: three quarters across and down the screen as drawn now (the UI
    # dump's root bounds follow the rotation; `wm size` doesn't).
    read -r sw sh <<<"$(xml | grep -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '\[[0-9]+,[0-9]+\]"$' | tr -d '[]"' | tr ',' ' ')"
    adb shell input tap $(( ${sw:-1000} * 3 / 4 )) $(( ${sh:-1000} * 3 / 4 )); pause 0.8
    screen | grep -qxF "FOLLOWING" && bad "a tap outside left the panel open" || ok "a tap outside closes the panel"
  else
    bad "no stack bounds to tap"; bad "no stack to close"
  fi

  # Offline without a cache: collapsed (the refresh at startup fails).
  adb shell am force-stop "$PKG"
  adb shell rm -f /sdcard/Xross/data/ra/friends.json
  adb logcat -c
  start_app
  category 0
  pause 1
  [ -z "$(stack_desc)" ] && ok "offline without a cache: the stack collapses" || bad "no cache, the stack shows: $(stack_desc)"
  ra_log | grep -q failed && ok "with nothing cached, startup tried one refresh ($(ra_log | tail -1))" || bad "no startup refresh logged: $(ra_log)"

  # Following nobody: collapsed; at rest, no further calls.
  adb shell am force-stop "$PKG"
  write_friends '[]' '{}' '{}'
  adb shell "sed -i 's/\"followingFetchedAtMs\":[0-9]*/\"followingFetchedAtMs\":$(now_ms)/' /sdcard/Xross/data/ra/friends.json"
  adb logcat -c
  start_app
  category 0
  pause 0.5
  [ -z "$(stack_desc)" ] && ok "following nobody: the stack collapses" || bad "following nobody, the stack shows: $(stack_desc)"
  pause 15
  [ -z "$(ra_log)" ] && ok "at rest for 15 s: no friends refresh" || bad "refreshed at rest: $(ra_log)"

  # Unlinked with a cache on disk: collapsed. (A debug build with .env secrets links its own
  # account again at start: SmokeUser's cache then belongs to another account, also collapsed.)
  adb shell am force-stop "$PKG"
  write_friends "$FOLLOWING" "$ACTIVITY" "$SEEN"
  unlink_fake_account
  start_app
  category 0
  pause 0.5
  what=$(seeded_account && echo "another account's cache is ignored" || echo "unlinked, even with a cache")
  [ -z "$(stack_desc)" ] && ok "$what: the stack collapses" || bad "$what: $(stack_desc)"

  adb shell cmd connectivity airplane-mode disable
  adb shell rm -f /sdcard/Xross/data/ra/friends.json
  topleft_row
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
  await_flat '"topLeftCluster":null' && ok "Theme pack hands the top-left back (null)" || bad "topLeftCluster not reset"
else
  skip 24 "the social-stack checks need a debuggable build (credentials are written with run-as)"
fi

section "Components (2b.7d)"
# Achievement badge (the last Components row): Theme pack → Off → Compact → Rich card. The rich
# card is described "RetroAchievements: 2/8 · 10/40 pts" and its friend strip "Recently played by
# …". Chrono Trigger's progress comes from a saved copy (matches.json and game-228.json in
# Xross/data/ra, badges in Xross/media/ra/badges) read with the fake account in airplane mode, so
# its fetch fails: the card from the saved copy, without the strip. With the debug build's .env
# account online the fetch works, and the saved friends on that game make the strip.
telemetry_row() { comp_row 8; }
card_desc() { descs | grep -o 'content-desc="RetroAchievements: [^"]*"' | head -1 | sed 's/content-desc="//; s/"$//'; }
strip_desc() { descs | grep -o 'content-desc="Recently played by [^"]*"' | head -1 | sed 's/content-desc="//; s/"$//'; }
pill_shown() { screen | grep -qxF " unlocked"; }
await_card() { for _ in $(seq 12); do [ -n "$(card_desc)" ] && return 0; pause 0.5; done; return 1; }
ra_user() { adb shell run-as "$PKG" cat shared_prefs/xross_credentials.xml 2>/dev/null | grep -oE 'name="retroachievements_user">[^<]+' | sed 's/.*>//'; }
seed_progress() { # Chrono Trigger matched to RetroAchievements game 228: 8 achievements, 2 unlocked, badges on disk
  local db d row uuid size mod
  db=$(tmpdir db-telemetry); d=$(tmpdir ra-telemetry)
  for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$db/$f" 2>/dev/null; done
  [ -s "$db/xross.db-wal" ] || rm -f "$db/xross.db-wal" "$db/xross.db-shm"
  row=$(sqlite3 -separator ' ' "$db/xross.db" "SELECT uuid, sizeBytes, lastModified FROM games WHERE fileName LIKE 'Chrono Trigger%' LIMIT 1")
  read -r uuid size mod <<<"$row"
  [ -n "$uuid" ] || return 1
  printf '{"%s":{"raId":228,"kind":"HASH","size":%s,"modified":%s,"listVersion":2}}' "$uuid" "$size" "$mod" > "$d/matches.json"
  python3 - "$d" <<'PY'
import json, struct, sys, zlib, os
d = sys.argv[1]
ach = []
for i in range(1, 9):
    a = {"id": 9000 + i, "title": "Smoke %d" % i, "points": 5, "badgeName": "9%04d" % i, "displayOrder": i}
    if i <= 2: a["unlockedAt"] = "2026-11-0%d 10:00:00" % i
    ach.append(a)
json.dump({"gameId": 228, "title": "Chrono Trigger", "consoleName": "SNES", "achievements": ach}, open(os.path.join(d, "game-228.json"), "w"))
def png(path, rgb):  # a 64 × 64 solid square
    raw = b"".join(b"\0" + bytes(rgb) * 64 for _ in range(64))
    chunk = lambda t, b: struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
os.makedirs(os.path.join(d, "badges"), exist_ok=True)
for i in range(1, 9): png(os.path.join(d, "badges", "9%04d.png" % i), (30 * i, 200 - 20 * i, 120))
PY
  adb shell mkdir -p /sdcard/Xross/data/ra /sdcard/Xross/media/ra/badges
  adb push "$d/matches.json" /sdcard/Xross/data/ra/matches.json >/dev/null
  adb push "$d/game-228.json" /sdcard/Xross/data/ra/game-228.json >/dev/null
  for f in "$d"/badges/*.png; do adb push "$f" /sdcard/Xross/media/ra/badges/ >/dev/null; done
}
# Four followed users, fetched just now (nothing is stale): Alice, Bob, Carol and Dave last played
# game 228, Erin another one.
seed_card_friends() { # seed_card_friends <owner>
  local d now act=""; d=$(tmpdir ra-card-friends); now=$(now_ms)
  for f in "Alice 01ALICE 228 2026-11-14 10:00:00" "Bob 01BOB 228 2026-11-13 10:00:00" "Carol 01CAROL 228 2026-11-12 10:00:00" \
    "Dave 01DAVE 228 2026-11-11 10:00:00" "Erin 01ERIN 77 2026-11-14 12:00:00"; do
    read -r u id g day time <<<"$f"
    act="$act${act:+,}\"$id\":{\"user\":\"$u\",\"ulid\":\"$id\",\"gameId\":$g,\"gameTitle\":\"Game $g\",\"lastPlayed\":\"$day $time\",\"lastSeen\":\"$day $time\",\"fetchedAtMs\":$now}"
  done
  printf '{"version":1,"owner":"%s","followingFetchedAtMs":%s,"following":[%s],"total":5,"activity":{%s},"seen":{},"stackOpenedAtMs":%s}' \
    "$1" "$now" '{"user":"Alice","ulid":"01ALICE"},{"user":"Bob","ulid":"01BOB"},{"user":"Carol","ulid":"01CAROL"},{"user":"Dave","ulid":"01DAVE"},{"user":"Erin","ulid":"01ERIN"}' \
    "$act" "$now" > "$d/friends.json"
  adb shell mkdir -p /sdcard/Xross/data/ra
  adb push "$d/friends.json" /sdcard/Xross/data/ra/friends.json >/dev/null
}
chrono() { home; all_games "$(collections_above)"; key KEYCODE_DPAD_CENTER; pause 1; }

if command -v sqlite3 >/dev/null && adb shell run-as "$PKG" true 2>/dev/null; then
  telemetry_row
  expect "Theme pack (Compact) · The focused game's unlocks and points" "Achievement badge follows the pack (Compact), last in Components"
  key KEYCODE_DPAD_CENTER
  await_flat '"telemetryMode":"OFF"' && ok "Off is saved as display.components.telemetryMode" || bad "telemetryMode OFF not saved"
  expect "Off · No RetroAchievements badge on games" "Off says what it does"

  # Offline, the fake account, Chrono Trigger's saved progress and friends on it.
  adb shell am force-stop "$PKG"
  adb shell cmd connectivity airplane-mode enable
  link_fake_account
  seed_progress || bad "no Chrono Trigger in the library to seed"
  seed_card_friends SmokeUser
  adb logcat -c
  start_app
  chrono
  [ -z "$(card_desc)" ] && ! pill_shown && ok "Off: a recognized game shows neither the pill nor the card" || bad "Off draws: '$(card_desc)'"
  shot telemetry-off
  telemetry_row
  key KEYCODE_DPAD_CENTER
  await_flat '"telemetryMode":"COMPACT_PILL"' && ok "Compact is saved" || bad "telemetryMode COMPACT_PILL not saved"
  chrono
  for _ in $(seq 10); do pill_shown && break; pause 0.5; done
  pill_shown && [ -z "$(card_desc)" ] && ok "Compact: today's pill, from the saved progress" || { bad "no compact pill"; show "$(screen)"; }
  shot telemetry-compact
  telemetry_row
  key KEYCODE_DPAD_CENTER
  await_flat '"telemetryMode":"RICH_CARD"' && ok "Rich card is saved" || bad "telemetryMode RICH_CARD not saved"
  expect "Rich card · Newest unlocks and friends who played it" "Rich card says what it shows"
  chrono
  await_card
  [ "$(card_desc)" = "RetroAchievements: 2/8 · 10/40 pts" ] && ok "the rich card draws the saved progress" || { bad "card: '$(card_desc)'"; show "$(screen)"; }
  expect_line "SNES" "the card carries the platform pill"
  pause 1.5
  [ -z "$(strip_desc)" ] && ok "offline (the game's fetch failed): the card has no friend strip" || bad "offline strip: '$(strip_desc)'"
  [ -z "$(ra_log)" ] && ok "focusing the game fetched no friends" || bad "friends fetched on focus: $(ra_log)"
  shot telemetry-rich-offline
  key KEYCODE_DPAD_DOWN
  pause 2
  [ -z "$(card_desc)" ] && ok "an unrecognized game (EarthBound) lets the card go" || bad "EarthBound shows: '$(card_desc)'"
  key KEYCODE_DPAD_UP
  await_card && ok "back on Chrono Trigger, the card returns" || bad "no card back on Chrono Trigger"
  # Inside a folder Up doesn't reach the avatar: a tap on it opens the HUD, Back closes it.
  ab=$(xml | grep -oE 'content-desc="SmokeUser"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]$' | head -1)
  read -r x1 y1 x2 y2 <<<"$(tr '[],' '   ' <<<"${ab:-[0,0][0,0]}")"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )); pause 1
  [ -n "$ab" ] && [ -z "$(card_desc)" ] && ok "the avatar's HUD covers the card" || bad "card under the HUD (avatar at '$ab'): '$(card_desc)'"
  key KEYCODE_BACK
  await_card && ok "closing the HUD gives the card back" || bad "no card after the HUD closed"
  category 5
  pause 1.5
  [ -z "$(card_desc)" ] && ok "apps get no card" || bad "an app shows: '$(card_desc)'"

  # Online with the .env account (debug builds with secrets): the fetch works, the strip shows.
  adb shell am force-stop "$PKG"
  adb shell cmd connectivity airplane-mode disable
  unlink_fake_account
  start_app
  user=$(ra_user)
  if [ -n "$user" ]; then
    adb shell am force-stop "$PKG"
    seed_card_friends "$user"
    adb logcat -c
    start_app
    chrono
    await_card
    for _ in $(seq 12); do [ -n "$(strip_desc)" ] && break; pause 0.5; done
    [ "$(strip_desc)" = "Recently played by Alice, Bob, Carol and 1 more" ] \
      && ok "online: RECENTLY PLAYED lists the three most recent friends on this game, then +1" || { bad "strip: '$(strip_desc)'"; show "$(screen)"; }
    expect_line "+1" "the strip ends with +1"
    shot telemetry-rich-strip
    [ -z "$(ra_log | grep -v '^friends=5 activity=0 calls=0')" ] && ok "nothing fetched friends on focus ($(ra_log | tr '\n' ' '))" || bad "friends fetched: $(ra_log)"
    # Airplane mode: the next fetch fails, the card stays without its strip.
    adb shell am force-stop "$PKG"
    adb shell cmd connectivity airplane-mode enable
    start_app
    chrono
    await_card
    pause 1.5
    [ -n "$(card_desc)" ] && [ -z "$(strip_desc)" ] && ok "airplane mode: the card without the strip" || bad "airplane: card '$(card_desc)', strip '$(strip_desc)'"
    adb shell cmd connectivity airplane-mode disable
  else
    skip 4 "the friend strip needs the debug build's .env RetroAchievements account"
  fi
  adb shell rm -rf /sdcard/Xross/data/ra/friends.json /sdcard/Xross/data/ra/matches.json /sdcard/Xross/data/ra/game-228.json /sdcard/Xross/media/ra/badges
  telemetry_row
  key KEYCODE_DPAD_CENTER
  await_flat '"telemetryMode":null' && ok "Theme pack hands the badge back (null)" || bad "telemetryMode not reset"
else
  skip 22 "the telemetry checks need sqlite3 on the host and a debuggable build"
fi

suite_end
