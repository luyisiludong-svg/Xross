#!/usr/bin/env bash
# End-to-end smoke test on a connected device/emulator (adb). Resets Xross's data, seeds test
# ROMs under /sdcard/ROMs, then drives the launcher with key events and checks what is on
# screen through the accessibility tree (uiautomator).
#
#   ./gradlew :app:installDebug
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: enables the launch check
#   tools/device-tests/smoke.sh
#
# WARNING: clears Xross's app data, moves /sdcard/Xross/config to /sdcard/Xross-smoke-backup/, and
# replaces /sdcard/ROMs with test files on the device.
set -u
PKG=dev.xross.launcher
PASS=0
FAIL=0

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause; }
ui() {
  adb shell uiautomator dump /sdcard/xross-ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/xross-ui.xml | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//'
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
  bad "$2 — '$1' not on screen"; grep -v '^[[:space:]]*$' <<<"$s" | sed 's/^/       | /' | head -12
}
expect_any() { # expect_any <text> <text> <description>: either text (e.g. linked or not)
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qF -e "$1" -e "$2" <<<"$s"; then ok "$3"; return; fi
    pause 0.5
  done
  bad "$3 — neither '$1' nor '$2' on screen"; grep -v '^[[:space:]]*$' <<<"$s" | sed 's/^/       | /' | head -12
}
# expect_line <text> <description>: <text> is a whole line on screen (not part of a longer one).
expect_line() {
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qxF -- "$1" <<<"$s"; then ok "$2"; return; fi
    pause 0.5
  done
  bad "$2 — line '$1' not on screen"; grep -v '^[[:space:]]*$' <<<"$s" | sed 's/^/       | /' | head -12
}
expect_no_line() {
  local s; s=$(screen)
  if grep -qxF -- "$1" <<<"$s"; then bad "$2 — line '$1' unexpectedly on screen"; else ok "$2"; fi
}
# expect_desc <content-desc> <description>: an element described as <content-desc> (icons, glyphs).
expect_desc() {
  adb shell uiautomator dump /sdcard/xross-ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/xross-ui.xml | grep -qF "content-desc=\"$1\""; then ok "$2"; else bad "$2 — no element described '$1'"; fi
}
expect_not() {
  local s; s=$(screen)
  if grep -qF -- "$1" <<<"$s"; then bad "$2 — '$1' unexpectedly on screen"; else ok "$2"; fi
}
# Up at the top of a category root climbs onto the status bar avatar; the Down after it only
# leaves the avatar, so the cursor ends on the root's first row.
top() { key $(for _ in $(seq 25); do printf 'KEYCODE_DPAD_UP '; done) KEYCODE_DPAD_DOWN; }
# Inside a list (drilled in) Up stops at the first row.
top_list() { key $(for _ in $(seq 25); do printf 'KEYCODE_DPAD_UP '; done); }
# Categories: Settings, Photo, Music, Video, Games, Apps.
category() { # category <index>: go to that category's root, cursor on the first entry
  key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
  key $(for _ in $(seq 6); do printf 'KEYCODE_BUTTON_L1 '; done)
  [ "$1" -gt 0 ] && key $(for _ in $(seq "$1"); do printf 'KEYCODE_BUTTON_R1 '; done)
  top
}
music_state() { adb shell dumpsys media_session | grep -o "state=PlaybackState {state=[A-Z]*" | head -1 | sed 's/.*state=//'; }
home() { # Games category root, cursor on the first collection (just below Search)
  category 4
  key KEYCODE_DPAD_DOWN
}
# Games root rows below Search: [Recently Played] [Favorites] All Games [Android Games]
# RetroAchievements.
all_games() { # all_games <collections above it>: open All Games (the platform folders)
  home
  [ "${1:-0}" -gt 0 ] && key $(for _ in $(seq "$1"); do printf 'KEYCODE_DPAD_DOWN '; done)
  key KEYCODE_DPAD_CENTER
}

echo "== Setup"
adb shell pm clear "$PKG" >/dev/null
# Config in the Xross folder outlives pm clear and would be adopted on start; move it aside so every
# run starts from defaults (kept under /sdcard/Xross-smoke-backup/, never deleted).
adb shell 'if [ -d /sdcard/Xross/config ]; then b=/sdcard/Xross-smoke-backup/$(date +%Y%m%d-%H%M%S); mkdir -p $b && mv /sdcard/Xross/config $b/; fi'
adb shell rm -rf /sdcard/Xross/themes/smoke_pack '/sdcard/Xross/themes/smoke-variants' '/sdcard/Xross/themes/smoke-variants-2'
adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell 'rm -rf /sdcard/ROMs; mkdir -p /sdcard/ROMs/snes /sdcard/ROMs/gba /sdcard/ROMs/psx
  for g in "Chrono Trigger (USA)" "EarthBound (USA)" "Super Metroid (USA, Europe)"; do echo x > "/sdcard/ROMs/snes/$g.sfc"; done
  echo x > "/sdcard/ROMs/gba/Golden Sun (USA).gba"
  printf "FILE \"Vagrant Story (USA).bin\" BINARY\n" > "/sdcard/ROMs/psx/Vagrant Story (USA).cue"
  echo x > "/sdcard/ROMs/psx/Vagrant Story (USA).bin"'
# Media fixtures (needs ffmpeg on the host): 2 photos, 2 tagged songs, 1 video.
MEDIA=0
if command -v ffmpeg >/dev/null; then
  tmp=$(mktemp -d)
  ffmpeg -loglevel error -y -f lavfi -i color=c=teal:s=1600x1200 -frames:v 1 "$tmp/Smoke Photo A.png"
  ffmpeg -loglevel error -y -f lavfi -i color=c=orange:s=1600x1200 -frames:v 1 "$tmp/Smoke Photo B.png"
  for n in 1 2; do
    ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$((300 + n * 60)):duration=30" -c:a libmp3lame \
      -metadata title="Smoke Song $n" -metadata artist="Smoke Artist" -metadata album="Smoke Album" -metadata track=$n "$tmp/Smoke Song $n.mp3"
  done
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=30:duration=8 -c:v libx264 -pix_fmt yuv420p "$tmp/Smoke Clip.mp4"
  adb shell 'rm -rf /sdcard/Pictures/XrossSmoke /sdcard/Music/XrossSmoke /sdcard/Movies/XrossSmoke; mkdir -p /sdcard/Pictures/XrossSmoke /sdcard/Music/XrossSmoke /sdcard/Movies/XrossSmoke'
  adb push "$tmp"/*.png /sdcard/Pictures/XrossSmoke/ >/dev/null
  adb push "$tmp"/*.mp3 /sdcard/Music/XrossSmoke/ >/dev/null
  adb push "$tmp"/*.mp4 /sdcard/Movies/XrossSmoke/ >/dev/null
  adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1
  rm -rf "$tmp"
  MEDIA=1
fi
adb shell am start -n "$PKG/.MainActivity" >/dev/null
pause 4

echo "== Library and navigation"
expect "All Games" "platforms are grouped in All Games"
expect "3 systems" "All Games counts the detected platforms"
expect_not "Recently Played" "no Recently Played before anything is launched"
key KEYCODE_DPAD_CENTER
expect "Super Nintendo" "auto-detected /sdcard/ROMs and listed platforms"
expect "Game Boy Advance" "second platform listed"
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "entering a platform lists its games (titles cleaned)"
expect "Super Metroid" "all games in the platform are listed"
key KEYCODE_BACK
key $(for _ in $(seq 2); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
expect "Vagrant Story" "cue sheet listed"
expect "CUE" "the .bin track is hidden behind its .cue (focused entry is the .cue)"
key KEYCODE_BACK KEYCODE_BACK

echo "== Favorites"
# Game Options menu rows: Play, Details, Achievements (systems with RetroAchievements), Add to
# favorites, Emulator, (Played), Metadata, Information.
all_games
key KEYCODE_DPAD_CENTER
expect "Options" "Options hint is shown for a game"
key KEYCODE_BUTTON_Y
expect "Add to favorites" "Options button opens the game's menu"
expect "Details" "Options menu offers Details"
expect "Achievements" "Options menu offers Achievements (SNES has RetroAchievements)"
expect "Metadata" "Options menu offers Metadata"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
# Unlinked it asks to link; linked (an account can outlive pm clear), the test ROM is unknown.
expect_any "Link your RetroAchievements account" "doesn't recognize this file" "Achievements screen opens"
key KEYCODE_BACK
expect_not "check the game's page on retroachievements.org" "Back closes the Achievements screen"
expect_not "Link your RetroAchievements account" "Back closes the Achievements screen (unlinked text gone)"
key KEYCODE_BUTTON_Y
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 0.6
key KEYCODE_BUTTON_Y
expect "Remove from favorites" "menu toggles favorite"

echo "== Per-game emulator"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Platform default" "Emulator submenu lists the platform default"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 0.6
key KEYCODE_BUTTON_Y
expect "this game only" "per-game emulator override is saved and shown"
key KEYCODE_BACK      # close the menu
key KEYCODE_BACK      # platform list → All Games
key KEYCODE_BACK      # All Games → Games root
top
expect "Favorites" "Favorites collection appears in Games"

echo "== Search"
key KEYCODE_DPAD_CENTER
expect "Type to search your library" "Search opens from the top of Games"
adb shell input keyevent KEYCODE_M KEYCODE_E KEYCODE_T
pause 1
expect "Super Metroid" "typing finds games by title prefix"
expect "1 game" "result count is shown"
key KEYCODE_BUTTON_R1
expect "Keyboard" "R1 moves focus to the results"
key KEYCODE_BACK KEYCODE_BACK
expect "Find a game by name" "Back returns to the keys, then closes search"

echo "== Status-bar avatar"
# Up from the first row of a category root focuses the avatar (it expands into the mini-HUD);
# Down hands focus back to that first row.
home
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP
expect_any "No RetroAchievements account linked" "SOFTCORE" "Up from the top row expands the avatar HUD"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Type to search your library" "Down leaves the avatar for the first row (Search)"
key KEYCODE_BACK KEYCODE_BACK

echo "== Game tiles and Details paging"
all_games 1 # Favorites sits above All Games now
key KEYCODE_DPAD_CENTER
expect "Chrono Trigger" "Super Nintendo lists its games"
expect "Never played" "game rows show playtime instead of the file size"
key KEYCODE_DPAD_RIGHT
expect "Game 1 of 3" "Right opens Details with its position in the list"
key KEYCODE_BUTTON_R1
expect "Game 2 of 3" "R1 pages to the next game"
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
expect "Game 3 of 3" "Right pages too and stops at the last game"
key KEYCODE_BUTTON_L1
expect "Game 2 of 3" "L1 pages back"
key KEYCODE_BACK
expect_not "Game 2 of 3" "Back closes Details"
key KEYCODE_DPAD_RIGHT
expect "Game 2 of 3" "the list cursor followed the paging"
key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK

echo "== RetroAchievements dashboard"
home
# RetroAchievements is the last row (Android Games may sit above it on a real device).
key $(for _ in $(seq 6); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
expect_any "Link your account with your user name" "HARDCORE POINTS" "Games ▸ RetroAchievements opens the dashboard"
key KEYCODE_BACK
expect_not "HARDCORE POINTS" "Back closes the dashboard"
expect_not "Link your account with your user name" "Back closes the dashboard (unlinked text gone)"

echo "== Categories"
home
key KEYCODE_BUTTON_R1
expect_not "All Games" "R1 moves to the Apps category"
key $(for _ in $(seq 5); do printf 'KEYCODE_BUTTON_L1 '; done)
expect "Library" "L1 moves to Settings"
key $(for _ in $(seq 4); do printf 'KEYCODE_DPAD_DOWN '; done)
expect "Sound" "Settings lists the Sound section"
top

echo "== Settings"
# Theme rows: Theme pack, [Theme variant, only for packs with variants], Theme color, Wave
# background, Wave settings, Profile customization, Button hints, Platform folders, Surface style,
# Quick profile, Adaptive colors, Circadian curve, Battery alert tint, Import theme (.xtheme)….
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
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
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"waveSpeed": 1.1' \
  && ok "wave uniforms are saved to settings.json" || bad "waveSpeed not in settings.json"
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
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"#00E5FF"' \
  && ok "avatar style is saved to settings.json" || bad "avatar border color not in settings.json"
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
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"surfaceStyle": "Y2K_GLOSSY_BEZEL"' \
  && ok "surface style is saved to settings.json" || bad "surfaceStyle not in settings.json"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Theme pack (Flat glass)" "Surface style cycles back to the pack's own"

echo "== Quick profiles and color layers"
# Rows after Surface style: Quick profile, Adaptive colors, Circadian curve, Battery alert tint.
# Colors aren't in the accessibility tree: debug builds log every color change (XrossColors) and
# every wave loop change (WaveLoop).
colors() { adb logcat -d -s XrossColors:I; }
key KEYCODE_DPAD_DOWN
expect "None · The theme's own look" "Quick profile follows Surface style (None)"
adb logcat -c
key KEYCODE_DPAD_CENTER
expect "OLED True Black · Pure black, glowing outlines" "Quick profile cycles to OLED True Black"
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"quickProfile": "OLED_TRUE_BLACK"' \
  && ok "the quick profile is saved to settings.json" || bad "quickProfile not in settings.json"
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
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"adaptiveGamePalette": false' \
  && ok "adaptive colors are saved to settings.json" || bad "adaptiveGamePalette not in settings.json"
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
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"batteryTint": true' \
  && ok "the battery tint is saved to settings.json" || bad "batteryTint not in settings.json"
adb logcat -c
adb shell dumpsys battery unplug
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

echo "== Theme packs"
# A minimal v2 pack: name, palette, wave defaults, one sound; everything else inherits from Default.
pack=$(mktemp -d)
mkdir -p "$pack/smoke_pack/sounds"
cat > "$pack/smoke_pack/theme.json" <<'JSON'
{"schemaVersion": 2, "name": "Smoke Pack", "author": "smoke",
 "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"},
 "wave": {"lineCount": 8, "turbulence": 1.4, "gradient": ["#101830", "#3060A0"]}}
JSON
cp "$(dirname "$0")/../../app/src/main/assets/themes/default/sounds/cursor.wav" "$pack/smoke_pack/sounds/CURSOR.WAV"
adb shell rm -rf /sdcard/Xross/themes/smoke_pack
adb push "$pack/smoke_pack" /sdcard/Xross/themes/ >/dev/null
rm -rf "$pack"
adb shell ls -a /sdcard/Xross/themes | grep -q '^.nomedia$' && ok "Xross/themes has .nomedia" || bad "Xross/themes/.nomedia missing"
top
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
top_list
key KEYCODE_BUTTON_Y
expect "Look for new packs" "Theme pack Options offer a rescan"
key $(for _ in $(seq 6); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
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
top_list
key KEYCODE_DPAD_CENTER
pause 1.5
expect "Default" "cycling returns to the Default pack"
key KEYCODE_BACK

echo "== Theme import and variants"
# Two .xtheme files in Download: one with a zip-slip entry, one valid pack (wrapped in a folder)
# with two variants, a Settings override and a SNES override under its "sfc" folder alias.
xt=$(mktemp -d)
python3 -I - "$xt" <<'PY'
import sys, zipfile
d = sys.argv[1]
m = """{"schemaVersion": 2, "name": "Smoke Variants", "author": "smoke",
 "palette": {"canvas": "#101830", "accent": "#FFFFFF", "accentSecondary": "#C0E0FF"},
 "variants": [
   {"id": "lilac", "name": "Cyber Lilac", "palette": {"canvas": "#1B1829", "accentSecondary": "#C8B6FF"}},
   {"id": "matrix", "name": "BIOS Matrix", "palette": {"canvas": "#0A140A", "accentSecondary": "#40FF60"}, "effects": {"surfaceStyle": "OLED_TRUE_BLACK"}}],
 "categoryOverrides": {"SETTINGS": {"effects": {"surfaceStyle": "Y2K_GLOSSY_BEZEL"}}},
 "systemOverrides": {"sfc": {"palette": {"canvas": "#300A0A", "accentSecondary": "#FF8C42"}, "cardAspectRatio": "3:4"}}}"""
with zipfile.ZipFile(d + "/smoke_variants.xtheme", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("Smoke Variants/theme.json", m)
    z.writestr("Smoke Variants/README.md", "smoke")
with zipfile.ZipFile(d + "/smoke_slip.xtheme", "w") as z:
    z.writestr("theme.json", '{"schemaVersion": 2, "name": "Slip"}')
    z.writestr("../../evil.json", "{}")
PY
adb shell rm -f /sdcard/Download/smoke_variants.xtheme /sdcard/Download/smoke_slip.xtheme
adb push "$xt/smoke_variants.xtheme" "$xt/smoke_slip.xtheme" /sdcard/Download/ >/dev/null
rm -rf "$xt"
adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1
# pick <file name> [wait]: tap that file in the system document picker, then wait (2 s default).
pick() {
  local b
  for _ in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/xross-ui.xml >/dev/null 2>&1
    b=$(adb shell cat /sdcard/xross-ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | head -1)
    [ -n "$b" ] && break
    pause 0.8
  done
  if [ -z "$b" ]; then bad "the document picker lists $1"; key KEYCODE_BACK; return; fi
  read -r x1 y1 x2 y2 <<<"$(sed 's/bounds="//; s/"//; s/\]\[/ /; s/[][]//g; s/,/ /g' <<<"$b")"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
  pause "${2:-2}"
}
top
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
key $(for _ in $(seq 20); do printf 'KEYCODE_DPAD_DOWN '; done)
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
for _ in $(seq 16); do adb shell cat /sdcard/Xross/config/settings.json | grep -q '"themePack": "smoke-variants"' && break; pause 0.5; done
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"themePack": "smoke-variants"' \
  && ok "the imported pack is applied" \
  || bad "the imported pack isn't active (settings.json has $(adb shell cat /sdcard/Xross/config/settings.json | grep -o '"themePack": "[^"]*"'))"
top_list
expect "Smoke Variants by smoke · 3 packs" "the imported pack is listed and loads without problems"
key KEYCODE_DPAD_DOWN
expect "Standard · 3 looks in Smoke Variants" "a pack with variants shows Theme variant"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "BIOS Matrix · 3 looks" "Theme variant cycles to the next variant"
adb shell cat /sdcard/Xross/config/settings.json | grep -q '"themeVariant": "matrix"' \
  && ok "the variant is saved to settings.json" || bad "themeVariant not in settings.json"
key KEYCODE_BUTTON_Y
expect "Cyber Lilac" "Theme variant Options list every variant"
key KEYCODE_BACK
# The same file again: ask before replacing; Keep both installs a copy.
key $(for _ in $(seq 20); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
pause 2
pick smoke_variants.xtheme
expect "is already installed" "re-importing an installed pack asks first"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 2
adb shell ls /sdcard/Xross/themes/smoke-variants-2/theme.json >/dev/null 2>&1 \
  && ok "Keep both installs the copy as smoke-variants-2" || bad "smoke-variants-2 not installed"
# The SNES override's 3:4 tiles: a portrait game tile in Super Nintendo (colors aren't in the tree).
home
all_games "$(screen | grep -cxE 'Recently Played|Favorites')"
expect "Super Nintendo" "All Games lists the SNES folder"
key KEYCODE_DPAD_CENTER
pause 1
expect "Chrono Trigger" "a system override doesn't break the game list"
# Back to Default for the rest of the run.
category 0
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
top_list
key KEYCODE_BUTTON_Y
key $(for _ in $(seq 8); do printf 'KEYCODE_DPAD_UP '; done) KEYCODE_DPAD_CENTER
pause 1.5
expect "Default" "Theme pack Options switch back to Default"
expect_not "Theme variant" "Default has no Theme variant row"
key KEYCODE_BACK
adb shell rm -rf /sdcard/Xross/themes/smoke-variants /sdcard/Xross/themes/smoke-variants-2 /sdcard/Download/smoke_variants.xtheme /sdcard/Download/smoke_slip.xtheme

echo "== Game-art palette"
# Two SNES games get hero art (a blue banner with an orange logo, a green one with a magenta logo)
# and are marked scraped in the library database (pulled, edited with the host's sqlite3, pushed
# back while Xross is stopped). Debug builds log each extraction (XrossPalette) and color change.
if command -v sqlite3 >/dev/null && adb shell run-as "$PKG" true 2>/dev/null; then
  art=$(mktemp -d)
  python3 -I - "$art" <<'PY'
import struct, sys, zlib
d = sys.argv[1]
def png(path, bg, fg, w=320, h=180):
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            row += bytes(fg if 110 <= x < 210 and 50 <= y < 130 else bg)
        rows.append(bytes(row))
    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
png(d + "/Chrono Trigger (USA).png", (11, 30, 74), (255, 122, 16))
png(d + "/EarthBound (USA).png", (12, 60, 24), (230, 40, 200))
PY
  adb shell mkdir -p /sdcard/Xross/media/heroes/snes
  adb push "$art/Chrono Trigger (USA).png" "$art/EarthBound (USA).png" /sdcard/Xross/media/heroes/snes/ >/dev/null
  adb shell am force-stop "$PKG"
  for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$art/$f" 2>/dev/null; done
  [ -s "$art/xross.db-wal" ] || rm -f "$art/xross.db-wal" "$art/xross.db-shm"
  sqlite3 "$art/xross.db" "INSERT OR REPLACE INTO game_metadata (gameId, locked, scrapeStatus, scrapedAt)
    SELECT id, 0, 'OK', 1700000000000 FROM games WHERE platformId = 'snes' AND (fileName LIKE 'Chrono Trigger%' OR fileName LIKE 'EarthBound%');
    PRAGMA journal_mode = DELETE;" >/dev/null
  adb push "$art/xross.db" /data/local/tmp/xross-smoke.db >/dev/null
  adb shell chmod 644 /data/local/tmp/xross-smoke.db
  adb shell run-as "$PKG" sh -c "'cp /data/local/tmp/xross-smoke.db databases/xross.db && rm -f databases/xross.db-wal databases/xross.db-shm'"
  adb shell rm -f /data/local/tmp/xross-smoke.db
  rm -rf "$art"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  pause 3
  home
  all_games "$(screen | grep -cxE 'Recently Played|Favorites')"
  key KEYCODE_DPAD_CENTER
  pause 1.5
  expect "Chrono Trigger" "the SNES list opens with the scraped games"
  colors() { adb logcat -d -s XrossColors:I; }
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
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  pause 3
  home
  all_games "$(screen | grep -cxE 'Recently Played|Favorites')"
  key KEYCODE_DPAD_CENTER
  pause 0.8
  adb shell input keyevent KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
  pause 1.5
  n=$(adb logcat -d -s XrossPalette:I | grep -c "extract ")
  [ "$n" -eq 1 ] && ok "a game scrolled past in under 250 ms is never extracted" || bad "$n extractions (expected 1: only the game the cursor rested on)"
  adb shell "rm -f '/sdcard/Xross/media/heroes/snes/Chrono Trigger (USA).png' '/sdcard/Xross/media/heroes/snes/EarthBound (USA).png'"
  adb shell am force-stop com.retroarch.aarch64
  category 0
else
  echo "  (skipped: needs sqlite3 on the host and a debuggable build)"
fi

echo "== Secrets"
cfg=$(adb shell cat /sdcard/Xross/config/settings.json /sdcard/Xross/config/controllers.json /sdcard/Xross/config/systems.json)
grep -qiE 'password|apikey|api_key|web_api|"key"' <<<"$cfg" && bad "account fields found in Xross/config" || ok "no account names, passwords or keys in Xross/config"

echo "== Analog stick deadzone"
# Controls rows: Confirm button, Analog stick deadzone, Axial deadzone, Remap buttons…, Reset all.
top
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN
expect "35%" "deadzone starts at 35%"
# 35% → 45% → 55% → Off → 15% → 25% → 35%
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "Off — hardware only" "deadzone can be turned off (hardware flat zone only)"
key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
expect "35%" "deadzone cycles back to 35%"

echo "== Button remapping"
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
  bad "wizard completes after ten buttons — still asking"; grep -v '^[[:space:]]*$' <<<"$s" | sed 's/^/       | /' | head -6
fi
key KEYCODE_DPAD_DOWN
expect "default mapping changed" "Reset all summarizes the changed mapping"
key KEYCODE_DPAD_DOWN
expect "10 custom bindings" "bindings are saved (Default mapping row)"
key KEYCODE_DPAD_UP
key KEYCODE_BUTTON_X
pause 0.5
expect "Using defaults" "remapped X confirms (it ran Reset button mapping)"

echo "== Rescan"
# Library rows: All files access, Add ROM folder…, Base ROMs folder…, Create system folders, Rescan library, …
key KEYCODE_BACK
top
key KEYCODE_DPAD_CENTER
expect "Base ROMs folder" "Library shows the base ROMs folder"
adb shell 'echo x > "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
key $(for _ in $(seq 4); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
pause 1.2
expect "+1" "rescan picks up a new file"
adb shell 'rm "/sdcard/ROMs/gba/Metroid Fusion (USA).gba"'
key KEYCODE_DPAD_CENTER
pause 1.2
expect "−1" "rescan drops a deleted file"
key KEYCODE_DPAD_DOWN
pause 3
expect "identified by content" "background fingerprinting reports identified games"

if [ "$MEDIA" -eq 1 ]; then
  echo "== Photo"
  category 1
  # Folders are sorted by name and the device may hold others; step down until XrossSmoke is
  # focused (its "2 photos" subtitle only shows on the focused row).
  for _ in $(seq 12); do screen | grep -q "XrossSmoke" && screen | grep -q "2 photos" && break; key KEYCODE_DPAD_DOWN; done
  expect "XrossSmoke" "photo folders listed"
  expect "2 photos" "the folder shows its photo count"
  key KEYCODE_DPAD_CENTER
  key KEYCODE_DPAD_CENTER
  pause 1
  expect "1 / 2" "photo viewer opens at the first photo"
  key KEYCODE_DPAD_RIGHT
  expect "2 / 2" "right shows the next photo"
  key KEYCODE_BUTTON_R1
  expect "1.5×" "R1 zooms in"
  key KEYCODE_BACK KEYCODE_BACK
  expect "Smoke Photo A" "Back un-zooms, then closes the viewer"

  echo "== Music"
  category 2
  expect "Shuffle all" "music root lists Shuffle all"
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
  expect "Smoke Album" "albums listed from tags"
  for _ in $(seq 8); do screen | grep -q "Smoke Artist · 2 songs" && break; key KEYCODE_DPAD_DOWN; done
  key KEYCODE_DPAD_CENTER
  expect "Smoke Song 1" "album lists its songs in track order"
  key KEYCODE_DPAD_CENTER
  pause 1.5
  # The emulator's player can sit in BUFFERING for a few seconds; a song that never plays still fails.
  for _ in $(seq 9); do [ "$(music_state)" = "PLAYING" ] && break; pause 0.5; done
  [ "$(music_state)" = "PLAYING" ] && ok "song plays through the media session" || bad "song plays (state $(music_state))"
  category 2
  expect "Now playing" "Now playing row appears"
  key KEYCODE_DPAD_CENTER
  expect "Track 1 of 2" "Now Playing screen opens"
  key KEYCODE_DPAD_RIGHT
  expect "Smoke Song 2" "right skips to the next track"
  key KEYCODE_BACK
  expect_not "Track 2 of 2" "Back closes Now Playing"

  echo "== Media controls shortcut"
  category 4
  key KEYCODE_BUTTON_SELECT
  expect "Track 2 of 2" "Select opens Now Playing from another category"
  key KEYCODE_BUTTON_SELECT
  expect_not "Track 2 of 2" "Select closes Now Playing again"
  expect "Smoke Song 2" "mini-player line shows while music plays"

  echo "== Video"
  category 3
  key KEYCODE_DPAD_CENTER
  for _ in $(seq 8); do screen | grep -q "Smoke Clip" && break; key KEYCODE_DPAD_DOWN; done
  key KEYCODE_DPAD_CENTER
  pause 1.5
  expect "Smoke Clip" "video player opens with its title"
  [ "$(music_state)" = "PAUSED" ] && ok "music pauses while a video plays" || bad "music during video (state $(music_state))"
  key KEYCODE_BACK
  pause 1
  [ "$(music_state)" = "PLAYING" ] && ok "music resumes after the video" || bad "music after video (state $(music_state))"
fi

echo "== Touch"
home # Games root: Search, Favorites, All Games
# Swipes are placed relative to the screen (root node bounds from the accessibility dump).
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

if adb shell pm list packages | grep -q com.retroarch.aarch64; then
  echo "== Launch (RetroArch stub)"
  home
  key KEYCODE_DPAD_CENTER
  adb logcat -c
  key KEYCODE_DPAD_CENTER
  pause 2.5
  log=$(adb logcat -d -s XrossStub:I)
  grep -q "extra.LIBRETRO=/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so" <<<"$log" \
    && ok "RetroArch receives the snes9x core" || bad "RetroArch core extra"
  grep -q "uriRead=ok" <<<"$log" && ok "emulator can read the ROM through the granted URI" || bad "URI read grant"
  if [ "$MEDIA" -eq 1 ]; then
    [ "$(music_state)" = "PAUSED" ] && ok "music pauses while a game runs" || bad "music during game (state $(music_state))"
  fi
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  pause 1
  home
  expect "Recently Played" "launched game appears in Recently Played"
  if [ "$MEDIA" -eq 1 ]; then
    [ "$(music_state)" = "PLAYING" ] && ok "music resumes after the game" || bad "music after game (state $(music_state))"
  fi
fi

echo
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
