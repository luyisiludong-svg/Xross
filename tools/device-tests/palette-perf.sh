#!/usr/bin/env bash
# Frame pacing of game navigation with the game-art palette on and off (Roadmap Appendix A.1,
# invariant 4: 0–1.5% janky frames on the profiling build).
#
#   ./gradlew :app:assembleDebug :app:assembleProfiling
#   tools/device-tests/palette-perf.sh [pairs]     # default 3 pairs of passes
#   PERF_EFFECTS='{"backgroundFilter": "CRT", "uiFilter": "BLOOM"}' tools/device-tests/palette-perf.sh
#                                                  # the same with Settings ▸ Theme effects on
#   PERF_COMPONENTS='{"badgePills": ["SYSTEM", "YEAR", "DEVELOPER", "PLAYTIME"]}' tools/device-tests/palette-perf.sh
#                                                  # the same with Settings ▸ Theme ▸ Components picks
#   PERF_LAYOUT='{"preset": "PSP_CENTER", "uiScale": 1.3, "itemSpacingDp": 20}' tools/device-tests/palette-perf.sh
#                                                  # the same with Settings ▸ Theme ▸ Layout values (2b.8a)
#   PERF_TELEMETRY=1 tools/device-tests/palette-perf.sh
#                                                  # passes alternate the rich telemetry card and the
#                                                  # compact pill (2b.7d) instead of adaptive on and off
#
# PERF_TELEMETRY also seeds saved RetroAchievements progress (matches.json, game-*.json and
# badges) for the 12 games, so each focus draws its card from the saved copy, and runs the passes
# in airplane mode (no fetch when the cursor rests). It needs a linked account: the debug build
# links the .env one.
#
# Installs the debug build to seed 12 scraped SNES games with distinct hero art (it must be
# debuggable to edit the database), then the profiling build over it (same signing, data kept).
# Each pass cold-starts Xross (empty palette cache), opens Games ▸ All Games ▸ Super Nintendo,
# resets gfxinfo, presses Down 11 × and Up 11 × 450 ms apart (past the 250 ms debounce, so each
# game is extracted and the colors morph) and reads gfxinfo. Passes alternate adaptive on and off,
# because the emulator's GPU timing drifts between passes.
#
# Judge by "Janky frames" (deadline missed), not the legacy count. On an emulator, boot it with
# `-gpu host` first: the default may pick software rendering and report ~100% jank.
#
# WARNING: replaces /sdcard/ROMs with the 12 test games and resets display settings (theme,
# pack, quick profile, color layers, effects, components, layout) in /sdcard/Xross/config/settings.json.
set -u
PKG=dev.xross.launcher
PAIRS=${1:-3}
APK=app/build/outputs/apk
OUT=$(mktemp -d)
command -v sqlite3 >/dev/null || { echo "needs sqlite3 on the host"; exit 1; }
[ -f "$APK/debug/app-debug.apk" ] && [ -f "$APK/profiling/app-profiling.apk" ] || { echo "build both APKs first"; exit 1; }

pause() { adb shell sleep "${1:-0.4}"; }
key() { adb shell input keyevent "$@"; pause; }
ups() { for _ in $(seq "$1"); do printf 'KEYCODE_DPAD_UP '; done; }
screen() { adb shell uiautomator dump /sdcard/xross-ui.xml >/dev/null 2>&1; adb shell cat /sdcard/xross-ui.xml | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//'; }
root() { # root <index>: that category's root (Settings, Photo, Music, Video, Games, Apps), cursor on its first row
  key KEYCODE_BACK KEYCODE_BACK KEYCODE_BACK
  key $(for _ in 1 2 3 4 5 6; do printf 'KEYCODE_BUTTON_L1 '; done)
  [ "$1" -gt 0 ] && key $(for _ in $(seq "$1"); do printf 'KEYCODE_BUTTON_R1 '; done)
  key $(ups 25) KEYCODE_DPAD_DOWN
}
settings() { # settings <adaptive>: the shipped look, with adaptive colors on or off (Xross stopped)
  adb shell am force-stop "$PKG"
  adb shell cat /sdcard/Xross/config/settings.json > "$OUT/settings.json"
  python3 -I - "$OUT/settings.json" "$1" <<'PY'
import json, os, sys
p = sys.argv[1]; s = json.load(open(p)); d = s["display"]
# The pack's own effects unless PERF_EFFECTS names some (2b.4f, display.effects).
d["effects"] = json.loads(os.environ.get("PERF_EFFECTS") or "{}")
# The pack's own components unless PERF_COMPONENTS names some (2b.7, display.components).
d["components"] = json.loads(os.environ.get("PERF_COMPONENTS") or "{}")
# The pack's own layout unless PERF_LAYOUT names some (2b.8a, display.layout).
d["layout"] = json.loads(os.environ.get("PERF_LAYOUT") or "{}")
d.update(theme="LAVENDER", themePack="default", themeVariant="", surfaceStyle="", quickProfile="NONE",
         adaptiveGamePalette=sys.argv[2] == "true")
for k in ("circadianCurve", "batteryTint"): d.pop(k, None)
json.dump(s, open(p, "w"), indent=2)
PY
  adb push "$OUT/settings.json" /sdcard/Xross/config/settings.json >/dev/null
}

echo "== Seed (debug build)"
adb install -r "$APK/debug/app-debug.apk" >/dev/null
adb shell settings put secure immersive_mode_confirmations confirmed
python3 -I - "$OUT" <<'PY'
import colorsys, struct, sys, zlib
d = sys.argv[1]
def png(path, bg, fg, w=640, h=360):
    rows = [bytes([0]) + b"".join(bytes(fg if 220 <= x < 420 and 100 <= y < 260 else bg) for x in range(w)) for y in range(h)]
    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
rgb = lambda h, s, v: tuple(int(c * 255) for c in colorsys.hsv_to_rgb(h, s, v))
for i in range(12):  # distinct hues, so every focus change is a real morph
    png(f"{d}/Perf Game {i + 1:02d} (USA).png", rgb(i / 12, 0.8, 0.3), rgb((i / 12 + 0.5) % 1, 0.9, 1.0))
PY
adb shell 'rm -rf /sdcard/ROMs; mkdir -p /sdcard/ROMs/snes /sdcard/Xross/media/heroes/snes
  for i in 01 02 03 04 05 06 07 08 09 10 11 12; do echo x > "/sdcard/ROMs/snes/Perf Game $i (USA).sfc"; done'
adb push "$OUT"/Perf*.png /sdcard/Xross/media/heroes/snes/ >/dev/null
# An existing library isn't rescanned on start: Settings ▸ Library ▸ Rescan library (fifth row).
adb shell am force-stop "$PKG"; adb shell am start -n "$PKG/.MainActivity" >/dev/null; pause 4
root 0
key KEYCODE_DPAD_CENTER
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
pause 3
adb shell am force-stop "$PKG"
for f in xross.db xross.db-wal xross.db-shm; do adb exec-out run-as "$PKG" cat "databases/$f" > "$OUT/$f" 2>/dev/null; done
[ -s "$OUT/xross.db-wal" ] || rm -f "$OUT/xross.db-wal" "$OUT/xross.db-shm"
n=$(sqlite3 "$OUT/xross.db" "SELECT count(*) FROM games WHERE fileName LIKE 'Perf Game%';")
[ "$n" -eq 12 ] || { echo "the rescan found $n of 12 games"; exit 1; }
sqlite3 "$OUT/xross.db" "INSERT OR REPLACE INTO game_metadata (gameId, locked, scrapeStatus, scrapedAt)
  SELECT id, 0, 'OK', 1700000000000 FROM games WHERE fileName LIKE 'Perf Game%'; PRAGMA journal_mode = DELETE;" >/dev/null
adb push "$OUT/xross.db" /data/local/tmp/xross-perf.db >/dev/null
adb shell chmod 644 /data/local/tmp/xross-perf.db
adb shell run-as "$PKG" sh -c "'cp /data/local/tmp/xross-perf.db databases/xross.db && rm -f databases/xross.db-wal databases/xross.db-shm'"
adb shell rm -f /data/local/tmp/xross-perf.db
echo "  12 scraped games with hero art"
if [ -n "${PERF_TELEMETRY:-}" ]; then
  # Each game matched to a RetroAchievements id (7001–7012) with 24 achievements, 0–11 unlocked.
  sqlite3 -separator ' ' "$OUT/xross.db" "SELECT uuid, sizeBytes, lastModified FROM games WHERE fileName LIKE 'Perf Game%' ORDER BY fileName" > "$OUT/games.txt"
  python3 -I - "$OUT" <<'PY'
import json, os, struct, sys, zlib
d = sys.argv[1]
rows = [l.split() for l in open(os.path.join(d, "games.txt")) if l.strip()]
json.dump({u: {"raId": 7001 + i, "kind": "HASH", "size": int(s), "modified": int(m), "listVersion": 2} for i, (u, s, m) in enumerate(rows)},
          open(os.path.join(d, "matches.json"), "w"))
os.makedirs(os.path.join(d, "ra"), exist_ok=True)
for i in range(len(rows)):
    ach = [dict(id=70000 + i * 100 + k, title="A%d" % k, points=5, badgeName="7%04d" % (i * 24 + k), displayOrder=k,
                **({"unlockedAt": "2026-11-%02d 10:00:00" % (k + 1)} if k < i else {})) for k in range(24)]
    json.dump({"gameId": 7001 + i, "title": "Perf %d" % i, "consoleName": "SNES", "achievements": ach}, open(os.path.join(d, "ra", "game-%d.json" % (7001 + i)), "w"))
os.makedirs(os.path.join(d, "badges"), exist_ok=True)
def png(path, rgb):
    raw = b"".join(b"\0" + bytes(rgb) * 64 for _ in range(64))
    chunk = lambda t, b: struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
for n in range(12 * 24): png(os.path.join(d, "badges", "7%04d.png" % n), ((n * 37) % 256, (n * 91) % 256, (n * 53) % 256))
PY
  adb shell mkdir -p /sdcard/Xross/data/ra /sdcard/Xross/media/ra/badges
  adb push "$OUT/matches.json" /sdcard/Xross/data/ra/matches.json >/dev/null
  adb push "$OUT"/ra/. /sdcard/Xross/data/ra/ >/dev/null
  adb push "$OUT"/badges/. /sdcard/Xross/media/ra/badges/ >/dev/null
  echo "  saved RetroAchievements progress and badges for the 12 games"
  adb shell cmd connectivity airplane-mode enable
fi
adb install -r "$APK/profiling/app-profiling.apk" >/dev/null

pass() { # pass <label>
  adb shell am force-stop "$PKG"; adb shell am force-stop com.retroarch.aarch64
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" >/dev/null; pause 4
  root 4
  key KEYCODE_DPAD_DOWN   # below Search
  above=$(screen | grep -cxE 'Recently Played|Favorites')
  [ "$above" -gt 0 ] && key $(for _ in $(seq "$above"); do printf 'KEYCODE_DPAD_DOWN '; done)
  key KEYCODE_DPAD_CENTER; pause 1            # All Games
  key $(ups 25); key KEYCODE_DPAD_CENTER; pause 1.5   # Super Nintendo, the only platform
  key $(ups 25); pause 2                      # Perf Game 01
  screen | grep -q "Perf Game 01" || { echo "$1: not in the SNES list"; return; }
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  adb shell 'for i in 1 2 3 4 5 6 7 8 9 10 11; do input keyevent 20; sleep 0.45; done
    for i in 1 2 3 4 5 6 7 8 9 10 11; do input keyevent 19; sleep 0.45; done; sleep 1'
  local g; g=$(adb shell dumpsys gfxinfo "$PKG")
  v() { grep -m1 "^$1" <<<"$g" | sed "s/^$1[^:]*: *//"; }
  local log; log=$(adb logcat -d -s XrossPalette:I | grep "extract ")
  printf '%-6s %-7s %-14s %-9s %-6s %-6s %-6s %-6s %s\n' "$1" "$(v 'Total frames')" "$(v 'Janky frames:')" \
    "$(v 'Janky frames (legacy)' | grep -o '[0-9.]*%')" "$(v '50th percentile')" "$(v '90th percentile')" \
    "$(v '99th percentile')" "$(v '50th gpu')" "$(grep -c . <<<"$log") ($(grep -c ' on main' <<<"$log") on main)"
}

echo "== Passes (profiling build)"
printf '%-6s %-7s %-14s %-9s %-6s %-6s %-6s %-6s %s\n' pass frames janky legacy p50 p90 p99 gpu50 extractions
for i in $(seq "$PAIRS"); do
  if [ -n "${PERF_TELEMETRY:-}" ]; then
    PERF_COMPONENTS='{"telemetryMode": "RICH_CARD"}' settings true; pass "card$i"
    PERF_COMPONENTS='{"telemetryMode": "COMPACT_PILL"}' settings true; pass "pill$i"
  else
    settings true; pass "on$i"
    settings false; pass "off$i"
  fi
done
settings true
if [ -n "${PERF_TELEMETRY:-}" ]; then
  adb shell cmd connectivity airplane-mode disable
  adb shell rm -rf /sdcard/Xross/data/ra/matches.json "/sdcard/Xross/data/ra/game-70*.json" /sdcard/Xross/media/ra/badges
fi
rm -rf "$OUT"
