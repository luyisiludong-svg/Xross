#!/usr/bin/env bash
# Media suite (22 checks): photo folders and the viewer (paging, R1 zoom), music from tags (albums,
# track order, playback through the media session), Now Playing, the Select shortcut and the
# mini-player line, and background music pausing for a video and for a game.
#
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: the two music-during-a-game checks
#   tools/device-tests/test-media.sh
#
# Needs ffmpeg on the host (every check is skipped without it). Expects the seeded XrossSmoke media
# to be the only media on the device: on a phone with its own photos and music the Photo and Music
# checks fail by design.
# WARNING: clears Xross's app data, replaces /sdcard/ROMs with test files and puts XrossSmoke
# folders under Pictures, Music and Movies (all removed at exit; see lib/harness.sh).
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin media 22

setup_base_env
if ! setup_media_fixtures; then
  skip 22 "the media suite needs ffmpeg on the host"
  suite_end
  exit
fi
start_app

section "Photo"
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

section "Music"
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

section "Media controls shortcut"
category 4
key KEYCODE_BUTTON_SELECT
expect "Track 2 of 2" "Select opens Now Playing from another category"
key KEYCODE_BUTTON_SELECT
expect_not "Track 2 of 2" "Select closes Now Playing again"
expect "Smoke Song 2" "mini-player line shows while music plays"

section "Video"
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

section "Music during a game (RetroArch stub)"
if has_stub; then
  all_games
  key KEYCODE_DPAD_CENTER # Super Nintendo, cursor on Chrono Trigger
  key KEYCODE_DPAD_CENTER # launch
  pause 2.5
  [ "$(music_state)" = "PAUSED" ] && ok "music pauses while a game runs" || bad "music during game (state $(music_state))"
  start_app 1
  home
  pause 1
  [ "$(music_state)" = "PLAYING" ] && ok "music resumes after the game" || bad "music after game (state $(music_state))"
else
  skip 2 "install the RetroArch stub (./gradlew :emulator-stub:installRetroarchDebug)"
fi

suite_end
