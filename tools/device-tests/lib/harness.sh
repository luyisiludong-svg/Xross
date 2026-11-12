# shellcheck shell=bash
# Shared harness for the adb-driven device tests (sourced, not run). Each suite sources it, calls
# `suite_begin <name> <expected checks>`, sets up only the fixtures it needs, then `suite_end`.
#
# Results: ok / bad / skip count into PASS / FAIL / SKIP. When XROSS_RESULTS names a file (run-all.sh
# sets it), suite_end appends "<suite> <pass> <fail> <skip> <expected>" to it.
#
# Teardown runs on EXIT, INT and TERM, however the suite ends: `dumpsys battery reset`, the
# RetroArch stub stopped, host temp files removed and every test file the suites put on the device
# deleted (seeded ROMs, media, theme packs, .xtheme files, hero art). XROSS_KEEP_FIXTURES=1 keeps
# the device files for debugging a failure.

PKG=dev.xross.launcher
STUB=com.retroarch.aarch64
DEVICE_TESTS=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO_ROOT=$(cd "$DEVICE_TESTS/../.." && pwd)
PASS=0
FAIL=0
SKIP=0
SUITE=
EXPECTED=0
XT_TMP=
XT_STARTED=

# region Results

ok() { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
skip() { # skip <checks> <reason>: a block of checks that can't run here (missing host tool or stub)
  SKIP=$((SKIP + $1)); printf '  \033[33mSKIP\033[0m %s check(s): %s\n' "$1" "$2"
}
section() { echo "== $1"; }

# endregion
# region Screen reads and assertions

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause; }
dump() { adb shell uiautomator dump /sdcard/xross-ui.xml >/dev/null 2>&1; }
ui() {
  dump
  adb shell cat /sdcard/xross-ui.xml | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//'
}
# uiautomator occasionally returns an empty tree mid-animation, so read until it has text.
screen() {
  local s
  for _ in 1 2 3 4; do
    s=$(ui)
    if grep -q '[^[:space:]]' <<<"$s"; then printf '%s\n' "$s"; return; fi
    pause 0.5
  done
}
show() { grep -v '^[[:space:]]*$' <<<"$1" | sed 's/^/       | /' | head -"${2:-12}"; }
expect() { # expect <text> <description>
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qF -- "$1" <<<"$s"; then ok "$2"; return; fi
    pause 0.5
  done
  bad "$2 — '$1' not on screen"; show "$s"
}
expect_any() { # expect_any <text> <text> <description>: either text (e.g. linked or not)
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qF -e "$1" -e "$2" <<<"$s"; then ok "$3"; return; fi
    pause 0.5
  done
  bad "$3 — neither '$1' nor '$2' on screen"; show "$s"
}
# expect_line <text> <description>: <text> is a whole line on screen (not part of a longer one).
expect_line() {
  local s
  for _ in 1 2 3; do
    s=$(screen)
    if grep -qxF -- "$1" <<<"$s"; then ok "$2"; return; fi
    pause 0.5
  done
  bad "$2 — line '$1' not on screen"; show "$s"
}
expect_no_line() {
  local s; s=$(screen)
  if grep -qxF -- "$1" <<<"$s"; then bad "$2 — line '$1' unexpectedly on screen"; else ok "$2"; fi
}
# expect_desc <content-desc> <description>: an element described as <content-desc> (icons, glyphs).
expect_desc() {
  dump
  if adb shell cat /sdcard/xross-ui.xml | grep -qF "content-desc=\"$1\""; then ok "$2"; else bad "$2 — no element described '$1'"; fi
}
expect_not() {
  local s; s=$(screen)
  if grep -qF -- "$1" <<<"$s"; then bad "$2 — '$1' unexpectedly on screen"; else ok "$2"; fi
}
settings_has() { adb shell cat /sdcard/Xross/config/settings.json | grep -q -- "$1"; }
music_state() { adb shell dumpsys media_session | grep -o "state=PlaybackState {state=[A-Z]*" | head -1 | sed 's/.*state=//'; }
# Debug builds log every color change (XrossColors), palette extraction (XrossPalette) and wave
# loop change (WaveLoop): colors aren't in the accessibility tree.
colors() { adb logcat -d -s XrossColors:I; }

# endregion
# region Navigation

# press <key> <times> [keycode...]: one keyevent batch, e.g. press DPAD_DOWN 3 KEYCODE_DPAD_CENTER
press() {
  local k=$1 n=$2 a=()
  shift 2
  while [ "$n" -gt 0 ]; do a+=("KEYCODE_$k"); n=$((n - 1)); done
  key ${a[@]+"${a[@]}"} "$@"
}
# Up at the top of a category root climbs onto the status bar avatar; the Down after it only
# leaves the avatar, so the cursor ends on the root's first row.
top() { press DPAD_UP 25 KEYCODE_DPAD_DOWN; }
# Inside a list (drilled in) Up stops at the first row.
top_list() { press DPAD_UP 25; }
# Categories: Settings, Photo, Music, Video, Games, Apps.
category() { # category <index>: go to that category's root, cursor on the first entry
  key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
  press BUTTON_L1 6
  [ "$1" -gt 0 ] && press BUTTON_R1 "$1"
  top
}
home() { # Games category root, cursor on the first collection (just below Search)
  category 4
  key KEYCODE_DPAD_DOWN
}
# Games root rows below Search: [Recently Played] [Favorites] All Games [Android Games]
# RetroAchievements.
all_games() { # all_games <collections above it>: open All Games (the platform folders)
  home
  [ "${1:-0}" -gt 0 ] && press DPAD_DOWN "$1"
  key KEYCODE_DPAD_CENTER
}
# Collections above All Games on the Games root (Recently Played, Favorites), read from the screen.
collections_above() { screen | grep -cxE 'Recently Played|Favorites'; }
start_app() { adb shell am start -n "$PKG/.MainActivity" >/dev/null; pause "${1:-4}"; }
has_stub() { adb shell pm list packages | grep -q "package:$STUB"; }

# endregion
# region Fixtures

tmpdir() { # tmpdir <name>: a fresh host directory under this suite's temp root
  rm -rf "${XT_TMP:?}/$1"; mkdir -p "$XT_TMP/$1"; printf '%s\n' "$XT_TMP/$1"
}

# The base every suite starts from: Xross's data cleared, its config moved aside, storage access
# granted and six test ROMs (3 SNES, 1 GBA, a PS1 .cue/.bin pair) in /sdcard/ROMs. Doesn't start
# Xross: a suite adds its fixtures first, then calls start_app.
setup_base_env() {
  echo "== Setup ($SUITE)"
  adb shell pm clear "$PKG" >/dev/null
  # Config in the Xross folder outlives pm clear and would be adopted on start; move it aside so
  # every run starts from defaults (kept under /sdcard/Xross-smoke-backup/, never deleted).
  adb shell 'if [ -d /sdcard/Xross/config ]; then b=/sdcard/Xross-smoke-backup/$(date +%Y%m%d-%H%M%S); mkdir -p $b && mv /sdcard/Xross/config $b/; fi'
  remove_theme_fixtures
  adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
  adb shell 'rm -rf /sdcard/ROMs; mkdir -p /sdcard/ROMs/snes /sdcard/ROMs/gba /sdcard/ROMs/psx
    for g in "Chrono Trigger (USA)" "EarthBound (USA)" "Super Metroid (USA, Europe)"; do echo x > "/sdcard/ROMs/snes/$g.sfc"; done
    echo x > "/sdcard/ROMs/gba/Golden Sun (USA).gba"
    printf "FILE \"Vagrant Story (USA).bin\" BINARY\n" > "/sdcard/ROMs/psx/Vagrant Story (USA).cue"
    echo x > "/sdcard/ROMs/psx/Vagrant Story (USA).bin"'
  adb logcat -c
}

# Media fixtures (ffmpeg on the host): 2 photos, 2 tagged songs, 1 video in XrossSmoke folders
# under Pictures, Music and Movies. Returns 1 without ffmpeg. Call before start_app, so the media
# scan has indexed them by the time Xross queries.
setup_media_fixtures() {
  command -v ffmpeg >/dev/null || return 1
  local d; d=$(tmpdir media)
  ffmpeg -loglevel error -y -f lavfi -i color=c=teal:s=1600x1200 -frames:v 1 "$d/Smoke Photo A.png"
  ffmpeg -loglevel error -y -f lavfi -i color=c=orange:s=1600x1200 -frames:v 1 "$d/Smoke Photo B.png"
  for n in 1 2; do
    ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=$((300 + n * 60)):duration=30" -c:a libmp3lame \
      -metadata title="Smoke Song $n" -metadata artist="Smoke Artist" -metadata album="Smoke Album" -metadata track=$n "$d/Smoke Song $n.mp3"
  done
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=30:duration=8 -c:v libx264 -pix_fmt yuv420p "$d/Smoke Clip.mp4"
  adb shell 'rm -rf /sdcard/Pictures/XrossSmoke /sdcard/Music/XrossSmoke /sdcard/Movies/XrossSmoke; mkdir -p /sdcard/Pictures/XrossSmoke /sdcard/Music/XrossSmoke /sdcard/Movies/XrossSmoke'
  adb push "$d"/*.png /sdcard/Pictures/XrossSmoke/ >/dev/null
  adb push "$d"/*.mp3 /sdcard/Music/XrossSmoke/ >/dev/null
  adb push "$d"/*.mp4 /sdcard/Movies/XrossSmoke/ >/dev/null
  media_scan
  rm -rf "$d"
}
media_scan() { adb shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null 2>&1; }

# Theme fixtures, built on the host only (the suite pushes each one at the step that tests it):
#   $XT_TMP/theme/smoke_pack/      a minimal v2 pack (palette, wave defaults, one sound)
#   $XT_TMP/theme/smoke_variants.xtheme   a valid pack in a folder: two variants, a Settings
#                                  override and a SNES override under its "sfc" folder alias
#   $XT_TMP/theme/smoke_slip.xtheme       a pack with a ../ (zip-slip) entry
#   $XT_TMP/theme/heroes/          hero art for two SNES games (blue/orange, green/magenta)
setup_theme_fixtures() {
  local d; d=$(tmpdir theme)
  mkdir -p "$d/smoke_pack/sounds" "$d/heroes"
  cat > "$d/smoke_pack/theme.json" <<'JSON'
{"schemaVersion": 2, "name": "Smoke Pack", "author": "smoke",
 "palette": {"canvas": "#101830", "accentSecondary": "#C0E0FF"},
 "wave": {"lineCount": 8, "turbulence": 1.4, "gradient": ["#101830", "#3060A0"]}}
JSON
  cp "$REPO_ROOT/app/src/main/assets/themes/default/sounds/cursor.wav" "$d/smoke_pack/sounds/CURSOR.WAV"
  python3 -I - "$d" <<'PY'
import struct, sys, zipfile, zlib
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
png(d + "/heroes/Chrono Trigger (USA).png", (11, 30, 74), (255, 122, 16))
png(d + "/heroes/EarthBound (USA).png", (12, 60, 24), (230, 40, 200))
PY
}
remove_theme_fixtures() {
  adb shell rm -rf /sdcard/Xross/themes/smoke_pack /sdcard/Xross/themes/smoke-variants /sdcard/Xross/themes/smoke-variants-2 \
    /sdcard/Download/smoke_variants.xtheme /sdcard/Download/smoke_slip.xtheme \
    "'/sdcard/Xross/media/heroes/snes/Chrono Trigger (USA).png'" "'/sdcard/Xross/media/heroes/snes/EarthBound (USA).png'" \
    /data/local/tmp/xross-smoke.db
}

# endregion
# region Suite lifecycle

teardown() {
  local rc=$?
  trap - EXIT INT TERM
  [ -n "$XT_STARTED" ] || exit "$rc"
  adb shell dumpsys battery reset >/dev/null 2>&1
  adb shell am force-stop "$STUB" >/dev/null 2>&1
  [ -n "$XT_TMP" ] && rm -rf "$XT_TMP"
  if [ "${XROSS_KEEP_FIXTURES:-0}" != 1 ]; then
    remove_theme_fixtures >/dev/null 2>&1
    adb shell rm -rf /sdcard/ROMs /sdcard/Pictures/XrossSmoke /sdcard/Music/XrossSmoke /sdcard/Movies/XrossSmoke \
      /sdcard/xross-ui.xml /sdcard/evil.json /sdcard/Xross/evil.json >/dev/null 2>&1
    media_scan
  fi
  exit "$rc"
}
on_signal() { echo; echo "Interrupted ($SUITE)"; exit 130; }

suite_begin() { # suite_begin <name> <checks in this suite>
  SUITE=$1
  EXPECTED=$2
  command -v adb >/dev/null || { echo "adb not on PATH"; exit 2; }
  [ "$(adb get-state 2>/dev/null)" = device ] || { echo "no device: connect one or start an emulator (adb devices)"; exit 2; }
  adb shell pm path "$PKG" >/dev/null 2>&1 || { echo "$PKG isn't installed: ./gradlew :app:installDebug"; exit 2; }
  XT_TMP=$(mktemp -d "${TMPDIR:-/tmp}/xross-$SUITE.XXXXXX")
  XT_STARTED=1
  trap teardown EXIT
  trap on_signal INT TERM
  SECONDS=0
}

suite_end() {
  local ran=$((PASS + FAIL + SKIP))
  echo
  printf '%s: Passed: %d  Failed: %d  Skipped: %d  (%ds)\n' "$SUITE" "$PASS" "$FAIL" "$SKIP" "$SECONDS"
  [ "$EXPECTED" -gt 0 ] && [ "$ran" -ne "$EXPECTED" ] && echo "  note: $ran results for $EXPECTED checks"
  [ -n "${XROSS_RESULTS:-}" ] && echo "$SUITE $PASS $FAIL $SKIP $EXPECTED" >> "$XROSS_RESULTS"
  [ "$FAIL" -eq 0 ]
}

# endregion
