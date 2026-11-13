#!/usr/bin/env bash
# Theme suite (88 checks): Settings ▸ Theme rows (theme color, wave and avatar panels, platform
# folders, surface style), quick profiles (OLED, Y2K, E-Ink), color layers (adaptive colors,
# circadian curve, battery tint and charging pulse through `dumpsys battery`), the Sound section,
# v2 theme packs and hot reload (and a pack's wallpaper.png, which stops the wave and is pre-blurred
# for frosted surfaces; a pack's focus frame, icon mask and icon filter, with the Apps list's icons
# drawn once per style; a pack's foreground particles, resting after 10 s), .xtheme import (zip-slip, variants, overrides, Keep both), and the
# game-art palette (hero PNGs, scraped flags patched into the database, extraction cache, debounce).
#
#   tools/device-tests/test-theme.sh
#
# Needs python3 on the host; the game-art block also needs sqlite3 and a debuggable build (skipped
# otherwise). Colors are read from debug-build logs (XrossColors, XrossPalette, WaveLoop).
# WARNING: clears Xross's app data and replaces /sdcard/ROMs with test files (see lib/harness.sh).
# The battery override is always reset at exit, even after Ctrl-C.
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin theme 102

setup_base_env
setup_theme_fixtures
FIX=$XT_TMP/theme
start_app
category 0

section "Settings"
# Theme rows: Theme pack, [Theme variant, only for packs with variants], Theme color, Wave
# background, Wave settings, Profile customization, Button hints, Platform folders, Surface style,
# Quick profile, Adaptive colors, Circadian curve, Battery alert tint, Xross wallpaper, Import theme (.xtheme)….
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
expect "Theme pack" "Theme section lists theme packs"
expect "Default" "the built-in Default pack is active"
key KEYCODE_DPAD_DOWN
expect "Lavender" "Theme section shows the current theme"
key KEYCODE_DPAD_CENTER
pause 0.5
expect "Crimson" "Theme color cycles"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
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
key KEYCODE_DPAD_DOWN
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
key KEYCODE_DPAD_DOWN
expect "Theme pack (Flat glass)" "Surface style follows the theme pack (Flat glass)"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Y2K glossy bezel" "Surface style cycles through the presets"
settings_has '"surfaceStyle": "Y2K_GLOSSY_BEZEL"' && ok "surface style is saved to settings.json" || bad "surfaceStyle not in settings.json"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Theme pack (Flat glass)" "Surface style cycles back to the pack's own"

section "Quick profiles and color layers"
# Rows after Surface style: Quick profile, Adaptive colors, Circadian curve, Battery alert tint.
key KEYCODE_DPAD_DOWN
expect "None · The theme's own look" "Quick profile follows Surface style (None)"
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
key KEYCODE_DPAD_DOWN
expect "Off in Monochrome E-Ink" "E-Ink holds the color layers off"
key KEYCODE_DPAD_UP KEYCODE_BUTTON_Y
expect "Y2K Cyber-Pastel" "Quick profile Options list every profile"
key KEYCODE_BACK
key KEYCODE_DPAD_CENTER
expect "None · The theme's own look" "Quick profile cycles back to None"
pause 1
adb logcat -d -s WaveLoop:I | tail -1 | grep -qE "mode=(CONTINUOUS|PACED)" \
  && ok "leaving E-Ink restarts the wave loop" || bad "the wave loop didn't restart"
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
key KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
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
key KEYCODE_DPAD_DOWN
expect "Theme pack · Smoke Pack" "the pack's palette becomes the theme color"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
expect "8 lines" "the pack's wave defaults are applied"
# Hot reload: an edit to the active pack applies without leaving Xross; a broken save keeps it.
# The edit also switches the layout: the bar collapses while drilled in and a ◀ marks the way back.
good='{"schemaVersion": 2, "name": "Smoke Pack Live", "author": "smoke", "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"}, "layout": {"drillDownStyle": "CHEVRON_COLLAPSE", "focusDivider": "FULL_BLEED_LINE", "motionProfile": "PS3_CRISP"}}'
adb shell "echo '$good' > /sdcard/Xross/themes/smoke_pack/theme.json"
pause 1.5
# Only the focused row shows its subtitle: Wave settings → up three rows to Theme pack.
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP
expect "Smoke Pack Live" "an edited theme.json reloads while Xross is open"
expect_no_line "Theme" "CHEVRON_COLLAPSE drops the breadcrumb"
expect_desc "Back" "CHEVRON_COLLAPSE puts a back chevron beside the focused row"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
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
# Xross wallpaper sits just above Import theme (the last row); then back to Wave settings.
press DPAD_DOWN 20 KEYCODE_DPAD_UP
expect "From Smoke Pack Live" "Settings ▸ Theme ▸ Xross wallpaper names the pack"
press DPAD_UP 25 KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
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
    tap_attr "text=\"$1\"" && { found=1; break; }
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
press DPAD_DOWN 20 KEYCODE_DPAD_CENTER
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

suite_end
