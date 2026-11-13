#!/usr/bin/env bash
# Media suite (56 checks): photo folders and the viewer (paging, R1 zoom, Set as Xross wallpaper and
# back to the theme's), music from tags (albums,
# track order, playback through the media session), the music driving the shader's audio uniforms
# (and 0 when paused), Now Playing, the Select shortcut and the mini-player line, background
# music pausing for a video and for a game, and the volume mixer (2b.5b): the Video snaps volume,
# music pausing under a game's video snap (and staying paused when the user paused it), and
# lowered instead with "Music during video snaps: Lower"; theme music (2b.5c): a pack's generated
# bgm.ogg loop starting with the pack without audio focus, stopping for a song and for a game,
# silent while another app plays (the stub's tone service), paused or lowered under a video snap.
#
#   ./gradlew :emulator-stub:installRetroarchDebug   # optional: the game and other-app checks
#   tools/device-tests/test-media.sh
#
# Needs ffmpeg on the host (every check is skipped without it). Expects the seeded XrossSmoke media
# to be the only media on the device: on a phone with its own photos and music the Photo and Music
# checks fail by design.
# WARNING: clears Xross's app data, replaces /sdcard/ROMs with test files and puts XrossSmoke
# folders under Pictures, Music and Movies (all removed at exit; see lib/harness.sh).
set -u
. "$(dirname "$0")/lib/harness.sh"
suite_begin media 56

setup_base_env
if ! setup_media_fixtures; then
  skip 56 "the media suite needs ffmpeg on the host"
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
key KEYCODE_BACK
# 2b.4c: Options over the viewer; Set as Xross wallpaper draws the photo behind the menu
# (display.customWallpaper), and the opaque photo stops the wave's GL loop (debug-build logs).
adb logcat -c
key KEYCODE_BUTTON_Y
expect "Set as Xross wallpaper" "the viewer's Options offer Set as Xross wallpaper"
key KEYCODE_DPAD_CENTER
for _ in $(seq 10); do settings_has '"customWallpaper": "content://' && break; pause 0.5; done
settings_has '"customWallpaper": "content://' && ok "the photo is saved as display.customWallpaper" || bad "customWallpaper not in settings.json"
key KEYCODE_BACK
expect "Smoke Photo A" "Back un-zooms, then closes the viewer"
pause 1.5
adb logcat -d -s XrossWallpaper:I | tail -1 | grep -q "wallpaper=custom .* opaque=true" \
  && ok "the Xross wallpaper is decoded and shown" || bad "no custom wallpaper ($(adb logcat -d -s XrossWallpaper:I | tail -1))"
adb logcat -d -s WaveLoop:I | tail -1 | grep -q "mode=STOPPED" \
  && ok "an opaque wallpaper stops the wave's GL loop" || bad "the wave still runs under the wallpaper ($(adb logcat -d -s WaveLoop:I | tail -1))"
# Settings ▸ Theme ▸ Xross wallpaper (two rows above Import theme, the last row, below Reset to
# theme defaults) says where it comes from; Confirm goes back to the theme's.
category 0
press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
press DPAD_DOWN 30 KEYCODE_DPAD_UP KEYCODE_DPAD_UP
expect "A picture you chose" "Settings ▸ Theme shows the Xross wallpaper"
key KEYCODE_DPAD_CENTER
for _ in $(seq 10); do settings_has '"customWallpaper": ""' && break; pause 0.5; done
settings_has '"customWallpaper": ""' && ok "Confirm goes back to the theme's wallpaper" || bad "customWallpaper not cleared"
pause 1
adb logcat -d -s WaveLoop:I | tail -1 | grep -qE "mode=(CONTINUOUS|PACED)" \
  && ok "the wave runs again without the wallpaper" || bad "the wave didn't resume ($(adb logcat -d -s WaveLoop:I | tail -1))"
key KEYCODE_BACK

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
# 2b.4b: the fixture's 55 Hz kick at 120 BPM reaches uAudioBass / uAudioBeat (debug builds log
# them). Pausing drops both to 0 at once; then play again for the checks below.
pause 4.5
lv=$(audio_levels)
if [[ "$lv" =~ bass\ max=([0-9.]+)\ beats=([0-9]+) ]] && awk "BEGIN{exit !(${BASH_REMATCH[1]} >= 0.5)}" && [ "${BASH_REMATCH[2]}" -ge 2 ]; then
  ok "the music drives the shader's bass and beat ($lv)"
else
  bad "audio uniforms while playing ('${lv:-no XrossAudio line}')"
fi
adb shell cmd media_session dispatch pause
pause 4.5
lv=$(audio_levels)
[ "$lv" = "bass max=0.00 beats=0" ] && ok "paused music binds 0" || bad "audio uniforms after a pause ('${lv:-no XrossAudio line}')"
adb shell cmd media_session dispatch play
for _ in $(seq 9); do [ "$(music_state)" = "PLAYING" ] && break; pause 0.5; done
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

section "Music under video snaps (2b.5b)"
# Chrono Trigger gets a 6 s video snap with a 660 Hz tone and is marked scraped in the database
# (pulled, edited with the host's sqlite3, pushed back while Xross is stopped), so resting on it
# plays the snap after 1.2 s. Debug builds log the snap's level (XrossSnap) and the music
# player's (XrossMix).
snap_audible() { for _ in $(seq 12); do adb logcat -d -s XrossSnap:I | grep -q "audible" && return 0; pause 0.5; done; return 1; }
sound_row() { # sound_row <row>: Settings ▸ Sound, cursor on that row (0 = Sound effects)
  category 0
  press DPAD_DOWN 4 KEYCODE_DPAD_CENTER
  top_list
  [ "$1" -gt 0 ] && press DPAD_DOWN "$1"
}
chrono() { # the SNES list, cursor on Chrono Trigger (its snap starts 1.2 s later)
  home
  all_games "$(collections_above)"
  key KEYCODE_DPAD_CENTER
}
play_song() {
  category 2
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
  for _ in $(seq 8); do screen | grep -q "Smoke Artist · 2 songs" && break; key KEYCODE_DPAD_DOWN; done
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_CENTER
  for _ in $(seq 12); do [ "$(music_state)" = "PLAYING" ] && break; pause 0.5; done
}
if command -v sqlite3 >/dev/null && adb shell run-as "$PKG" true 2>/dev/null; then
  snapdir=$(tmpdir snap)
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=30:duration=6 -f lavfi -i "sine=frequency=660:sample_rate=44100:duration=6" \
    -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$snapdir/Chrono Trigger (USA).mp4"
  adb shell mkdir -p /sdcard/Xross/media/snaps/snes
  adb push "$snapdir/Chrono Trigger (USA).mp4" /sdcard/Xross/media/snaps/snes/ >/dev/null
  db=$(tmpdir db)
  adb shell am force-stop "$PKG"
  for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$db/$f" 2>/dev/null; done
  [ -s "$db/xross.db-wal" ] || rm -f "$db/xross.db-wal" "$db/xross.db-shm"
  sqlite3 "$db/xross.db" "INSERT OR REPLACE INTO game_metadata (gameId, locked, scrapeStatus, scrapedAt)
    SELECT id, 0, 'OK', 1700000000000 FROM games WHERE platformId = 'snes' AND fileName LIKE 'Chrono Trigger%';
    PRAGMA journal_mode = DELETE;" >/dev/null
  adb push "$db/xross.db" /data/local/tmp/xross-smoke.db >/dev/null
  adb shell chmod 644 /data/local/tmp/xross-smoke.db
  adb shell run-as "$PKG" sh -c "'cp /data/local/tmp/xross-smoke.db databases/xross.db && rm -f databases/xross.db-wal databases/xross.db-shm'"
  adb shell rm -f /data/local/tmp/xross-smoke.db
  start_app 4
  # Video snaps 75% → 30%: Confirm steps 80, 90, 100, 0 (Muted), 10, 20, 30.
  sound_row 5
  press DPAD_CENTER 7
  expect "Video snaps" "Settings ▸ Sound lists Video snaps"
  for _ in $(seq 10); do settings_has '"snapVolume": 0.3' && break; pause 0.5; done
  settings_has '"snapVolume": 0.3' && settings_has '"effectsVolume": 0.75' \
    && ok "Video snaps saves 30% as sound.snapVolume, Effects volume unchanged" || bad "snapVolume not 0.3 in settings.json"
  play_song
  adb logcat -c
  chrono
  if snap_audible; then
    adb logcat -d -s XrossSnap:I | grep -q "audible target=0.30" && ok "the snap plays at the Video snaps volume (0.30)" \
      || bad "snap level ($(adb logcat -d -s XrossSnap:I | tail -1))"
    pause 1
    [ "$(music_state)" = "PAUSED" ] && ok "music pauses when the snap's sound starts" || bad "music under the snap (state $(music_state))"
  else
    bad "no snap played for Chrono Trigger"; bad "music under the snap (no snap)"
  fi
  key KEYCODE_DPAD_DOWN
  pause 1.2
  [ "$(music_state)" = "PLAYING" ] && ok "music resumes when the cursor leaves the game" || bad "music after the snap (state $(music_state))"
  # A song the user paused stays paused through a snap.
  adb shell cmd media_session dispatch pause
  pause 0.5
  adb logcat -c
  key KEYCODE_DPAD_UP
  snap_audible || true
  pause 1
  key KEYCODE_DPAD_DOWN
  pause 1.2
  [ "$(music_state)" = "PAUSED" ] && ok "a song the user paused stays paused after a snap" || bad "user-paused song after a snap (state $(music_state))"
  # Lower: Music during video snaps (row 6) → Lower, Music (row 4) 100% → 50% (Muted, 10 … 50).
  sound_row 6
  key KEYCODE_DPAD_CENTER
  expect "Lower" "Music during video snaps switches to Lower"
  for _ in $(seq 10); do settings_has '"musicUnderSnaps": "DUCK"' && break; pause 0.5; done
  settings_has '"musicUnderSnaps": "DUCK"' && ok "saved as sound.musicUnderSnaps DUCK" || bad "musicUnderSnaps not DUCK in settings.json"
  press DPAD_UP 2
  press DPAD_CENTER 6
  for _ in $(seq 10); do settings_has '"musicVolume": 0.5' && break; pause 0.5; done
  adb shell cmd media_session dispatch play
  for _ in $(seq 12); do [ "$(music_state)" = "PLAYING" ] && break; pause 0.5; done
  adb logcat -c
  chrono
  snap_audible || true
  pause 1
  mix=$(adb logcat -d -s XrossMix:I | tail -1)
  [[ "$mix" == *"music=0.10"* ]] && [ "$(music_state)" = "PLAYING" ] && ok "Lower: Music 50% plays at 0.10 under the snap" || bad "ducked music ($mix, state $(music_state))"
  key KEYCODE_DPAD_DOWN
  pause 1.2
  mix=$(adb logcat -d -s XrossMix:I | tail -1)
  [[ "$mix" == *"music=0.50"* ]] && ok "back to 0.50 after the snap" || bad "music after the ducked snap ($mix)"
  adb shell cmd media_session dispatch pause
  adb shell rm -f "'/sdcard/Xross/media/snaps/snes/Chrono Trigger (USA).mp4'"
  snaps_ready=1
else
  skip 10 "the snap checks need sqlite3 on the host and a debuggable build (run-as)"
fi

section "Theme music (2b.5c)"
# A pack with a generated 10 s loop (bgm.ogg), made active by editing settings.json while Xross
# is stopped (which also empties the music queue). Debug builds log every move (XrossBgm).
bgm_line() { adb logcat -d -s XrossBgm:I | grep "state=" | tail -1; }
bgm_wait() { # bgm_wait <text>: the latest XrossBgm state line contains it, within 6 s
  for _ in $(seq 12); do [[ "$(bgm_line)" == *"$1"* ]] && return 0; pause 0.5; done; return 1; }
bgm_ok() { # bgm_ok <text> <description>
  if bgm_wait "$1"; then ok "$2"; else bad "$2 ($(bgm_line))"; fi; }
bgmdir=$(tmpdir bgm)/smoke_bgm
mkdir -p "$bgmdir"
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=520:sample_rate=44100:duration=10" -ac 2 -c:a vorbis -strict -2 "$bgmdir/bgm.ogg" # the built-in encoder: no libvorbis needed
echo '{"schemaVersion": 2, "name": "Smoke Music"}' > "$bgmdir/theme.json"
adb shell rm -rf /sdcard/Xross/themes/smoke_bgm
adb push "$bgmdir" /sdcard/Xross/themes/ >/dev/null
adb shell am force-stop "$PKG"
adb shell "sed -i 's/\"themePack\": \"[^\"]*\"/\"themePack\": \"smoke_bgm\"/' /sdcard/Xross/config/settings.json"
adb logcat -c
start_app 5
bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "theme music starts with the pack (Theme music 60%)"
# Read whole, then matched: grep -q on the live pipe would cut dumpsys off (SIGPIPE under pipefail).
# Only Xross plays game music here (the stub's tone is USAGE_MEDIA), so no uid filter is needed.
bgm_player() { adb shell dumpsys audio | grep "AudioPlaybackConfiguration" | grep "usage=USAGE_GAME content=CONTENT_TYPE_MUSIC" | grep -o 'state:[a-z]*' | tr '\n' ' '; }
for _ in $(seq 10); do [[ "$(bgm_player)" == *"state:started"* ]] && break; pause 0.5; done
p=$(bgm_player)
[[ "$p" == *"state:started"* ]] && ok "the loop plays as USAGE_GAME / CONTENT_TYPE_MUSIC" || bad "no started USAGE_GAME music player (${p:-none}; $(adb shell dumpsys audio | grep -c AudioPlaybackConfiguration) configurations)"
focus=$(adb shell dumpsys audio | sed -n '/Audio Focus stack entries/,/^ *$/p')
[[ "$focus" == *"Audio Focus stack entries"* && "$focus" != *"$PKG"* ]] \
  && ok "theme music takes no audio focus" || bad "audio focus while only theme music plays: $(grep -c "$PKG" <<<"$focus") Xross entries"
play_song
bgm_ok "state=STOPPED file=bgm.ogg vol=0.00 reason=MUSIC" "starting a song stops theme music"
key KEYCODE_BUTTON_SELECT
expect "Track 1 of 2" "Now Playing opens over the menu"
key KEYCODE_DPAD_DOWN
pause 1
bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "Now Playing ▸ ↓ stops the song and theme music starts again"
if has_stub; then
  adb shell am start-foreground-service -n "$STUB/dev.xross.stub.ToneService" >/dev/null
  bgm_ok "state=PAUSED file=bgm.ogg vol=0.00 reason=OTHER_APP" "another app playing media keeps theme music silent"
  adb shell am stopservice -n "$STUB/dev.xross.stub.ToneService" >/dev/null 2>&1
  bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "theme music continues when the other app stops"
  chrono
  key KEYCODE_DPAD_CENTER # launch Chrono Trigger in the stub
  bgm_ok "state=STOPPED file=bgm.ogg vol=0.00 reason=GAME" "launching a game stops theme music"
  pause 2
  start_app 1
  home
  bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "back from the game, theme music starts over"
else
  skip 4 "install the RetroArch stub (./gradlew :emulator-stub:installRetroarchDebug)"
fi
if [ -n "${snaps_ready:-}" ]; then
  # Still "Music during video snaps: Lower" from the section above.
  adb push "$snapdir/Chrono Trigger (USA).mp4" /sdcard/Xross/media/snaps/snes/ >/dev/null
  adb logcat -c
  chrono
  snap_audible || true
  bgm_ok "state=PLAYING file=bgm.ogg vol=0.12 reason=SNAP_DUCK" "Lower: theme music at 0.6 × 0.2 under the snap"
  key KEYCODE_DPAD_DOWN
  bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "back to 0.60 after the snap"
  sound_row 6
  key KEYCODE_DPAD_CENTER
  expect "Pause" "Music during video snaps back to Pause"
  adb logcat -c
  chrono
  snap_audible || true
  bgm_ok "state=PAUSED file=bgm.ogg vol=0.00 reason=SNAP" "Pause: theme music pauses under the snap"
  key KEYCODE_DPAD_DOWN
  bgm_ok "state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED" "and continues when the cursor leaves the game"
  adb shell rm -f "'/sdcard/Xross/media/snaps/snes/Chrono Trigger (USA).mp4'"
else
  skip 5 "the theme music snap checks need the snap fixture above"
fi

suite_end
