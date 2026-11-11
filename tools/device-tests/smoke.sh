#!/usr/bin/env bash
# End-to-end smoke test on a connected device/emulator (adb). Resets Xross's data, seeds test
# ROMs under /sdcard/ROMs, then drives the launcher with key events and checks what is on
# screen through the accessibility tree (uiautomator).
#
#   ./gradlew :app:installDebug
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: enables the launch check
#   tools/device-tests/smoke.sh
#
# WARNING: clears Xross's app data and adds files under /sdcard/ROMs on the device.
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
  bad "$2 — '$1' not on screen"; sed 's/^/       | /' <<<"$s" | head -12
}
expect_not() {
  local s; s=$(screen)
  if grep -qF -- "$1" <<<"$s"; then bad "$2 — '$1' unexpectedly on screen"; else ok "$2"; fi
}
top() { key $(for _ in $(seq 25); do printf 'KEYCODE_DPAD_UP '; done); }
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
# Games root rows below Search: [Recently Played] [Favorites] All Games [Android Games].
all_games() { # all_games <collections above it>: open All Games (the platform folders)
  home
  [ "${1:-0}" -gt 0 ] && key $(for _ in $(seq "$1"); do printf 'KEYCODE_DPAD_DOWN '; done)
  key KEYCODE_DPAD_CENTER
}

echo "== Setup"
adb shell pm clear "$PKG" >/dev/null
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
# Game Options menu rows: Play, Details, Add to favorites, Emulator, (Played), Metadata, Information.
all_games
key KEYCODE_DPAD_CENTER
expect "Options" "Options hint is shown for a game"
key KEYCODE_BUTTON_Y
expect "Add to favorites" "Options button opens the game's menu"
expect "Details" "Options menu offers Details"
expect "Metadata" "Options menu offers Metadata"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 0.6
key KEYCODE_BUTTON_Y
expect "Remove from favorites" "menu toggles favorite"

echo "== Per-game emulator"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
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
key $(for _ in $(seq 3); do printf 'KEYCODE_DPAD_DOWN '; done) KEYCODE_DPAD_CENTER
expect "Lavender" "Theme section shows the current theme"
key KEYCODE_DPAD_CENTER
pause 0.5
expect "Crimson" "Theme color cycles"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Off" "Wave background toggles off"
key KEYCODE_DPAD_CENTER
# Theme rows: Theme color, Wave background, Wave speed, Button hints, Platform folders.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
expect "Inside All Games" "Platform folders setting shows the All Games layout"
key KEYCODE_DPAD_CENTER
expect "At the top of Games (classic)" "Platform folders toggles to the classic layout"
key KEYCODE_DPAD_CENTER
expect "Inside All Games" "Platform folders toggles back"
key KEYCODE_BACK KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "Effects volume" "Sound section opens"
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
expect "100%" "Effects volume cycles"
key KEYCODE_BACK

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
expect "Buttons remapped" "wizard completes after ten buttons (last: media controls)"
key KEYCODE_DPAD_DOWN
expect "10 custom bindings" "bindings are saved"
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
