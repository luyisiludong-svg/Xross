# Phase 2b.3 — what is still to be tested

Branch `feat/theme-2b3` (from `feat/theme-2b2`), 2026-11-12. The code for 2b.3 is complete and
unit-tested; this file lists everything that has **not** been verified yet, why, and exactly how
to verify it. What *was* verified is in [`STATUS.md` §3.19](STATUS.md).

Nothing on this branch is committed yet.

---

## 1. Frame pacing of game navigation (acceptance gate, not met yet)

**Target:** palette extraction while moving through games drops no frames: 0–1.5% janky frames on
the `profiling` build (Roadmap Appendix A.1, invariant 4).

**Status: not measured.** The one pass that reached the game list measured 96.7% janky (p50
117 ms), but the API 37 emulator was failing at the time: Android's own system process stopped
responding ("Process system isn't responding" dialog) and the next passes couldn't even
navigate. That number says nothing about Xross. The adaptive-off baseline for an A/B
comparison didn't run either. STATUS §3.18 already noted that this emulator's GPU timing was
unreliable on 2026-11-12.

**How to measure** (preferably on the Galaxy A55, else on a freshly cold-booted emulator):

1. `./gradlew :app:installDebug` (it must be debuggable to seed the database).
2. Seed 12 SNES games with distinct hero art, marked as scraped. The scratch script used for this
   is not in the repo; the steps are:
   - `adb shell 'mkdir -p /sdcard/ROMs/snes; for i in 01 02 03 04 05 06 07 08 09 10 11 12; do echo x > "/sdcard/ROMs/snes/Perf Game $i (USA).sfc"; done'`
   - push one PNG per game to `/sdcard/Xross/media/heroes/snes/Perf Game NN (USA).png` (any
     colorful 640×360 images; distinct hues make each focus change a real morph);
   - start Xross, then press Home and reopen it so the resume rescan picks the ROMs up;
   - `adb shell am force-stop dev.xross.launcher`, pull `databases/xross.db` (plus `-wal` and
     `-shm`) with `adb exec-out run-as dev.xross.launcher cat databases/<file>`, run
     `INSERT OR REPLACE INTO game_metadata (gameId, locked, scrapeStatus, scrapedAt) SELECT id, 0, 'OK', 1700000000000 FROM games WHERE fileName LIKE 'Perf Game%'; PRAGMA journal_mode = DELETE;`
     with the host's `sqlite3`, push the file to `/data/local/tmp`, `chmod 644` it, and
     `run-as … cp` it over `databases/xross.db`, deleting `-wal` and `-shm`. The smoke test's
     "Game-art palette" section does the same for two games.
3. `adb install -r app/build/outputs/apk/profiling/app-profiling.apk` (same debug signing, so the
   data stays).
4. Open Games ▸ All Games ▸ Super Nintendo. Then
   `adb shell dumpsys gfxinfo dev.xross.launcher reset`, 11 × Down and 11 × Up with 450 ms
   between presses (each stop rests past the 250 ms debounce, so each game is extracted and the
   colors morph), then `adb shell dumpsys gfxinfo dev.xross.launcher` for janky % and p50/p90/p99.
   Force-stop between passes so the palette cache starts empty. Run 3+ passes.
5. A/B: set `"adaptiveGamePalette": false` in `/sdcard/Xross/config/settings.json` (with Xross
   stopped) and repeat. The difference is the feature's cost.
6. `adb logcat -s XrossPalette` gives each extraction's time and thread.

**Gotchas found while scripting this:** All Games remembers its last cursor row, so move to the
top before counting rows. macOS `seq 0` prints `1 0` (two key presses, not none). Opening Recently
Played instead of All Games launches a game into the RetroArch stub, which then takes every key.

**If it fails:** the Compose side of a morph is a ~300 ms animation that recomposes each
`GlowBloom` (the bloom copy of glow texts, `XmbScreen.kt`) once per frame, since a text shadow
color can't be changed in the draw phase. Fix it by drawing the bloom tint in the draw phase, or
by snapping the halo instead of animating it. Everything else (wave, avatar halo, battery rim)
already reads its colors in the draw phase or on the GL thread.

## 2. On real hardware

Only the API 37 emulator has run this branch.

- **Galaxy A55 (120 Hz):** the full `smoke.sh`, the frame-pacing run above, and idle CPU at rest
  with each quick profile (E-Ink should be lower than today's 12–19%, since the wave draws no
  frames at all).
- **Watch the morphs by eye:** the wave crossfade to a game's colors (visible when a game has a
  wheel logo but no hero, or while the hero fades in), and the focus halo, avatar glow and battery
  outline morphing over ~300 ms without jitter.
- **A real E-Ink panel** (or at least a device with a 60 Hz panel and the profile on for a while):
  confirm no ghosting, nothing animating (pulse, avatar glow, busy dots, caret, marquee) and the
  wave is one still frame.
- **Android 9–11:** the battery receiver and `androidx.palette` are plain APIs, but only API 37
  ran.
- **A real battery:** the tint was only driven by `dumpsys battery set level 12` and `set status 2`.
  Watch it cross 20% unplugged, and plug in.

## 3. Only checked through logs, not by eye

The smoke test reads debug-only logcat lines (`XrossColors`, `WaveLoop`, `XrossPalette`,
written only when `BuildConfig.PERF_LOG`), because colors aren't in the accessibility tree. Nobody
has looked at these on screen yet:

- OLED True Black: black canvas, no surface fills, 1 dp glowing outlines; game art changes the
  accent only.
- Y2K Cyber-Pastel: lavender canvas, lilac waves, strawberry glow, glossy bezels.
- Monochrome E-Ink: black and white, no translucency.
- The circadian curve in each of its four phases and across a boundary (it blends over 45 minutes
  either side of 06:00, 11:00, 18:00 and 21:00). Change the device clock to check.
- Battery tint: amber at 20%, red by 5%, green and a slow wave pulse while charging.
- Context rules from a real pack (`rules.json` through `imports`), especially `battery` and `time`
  rules switching live, and a rule `layout` patch (for example `motionProfile: INSTANT` below 15%).

## 4. Smoke test: remaining failures and flakes

Last full run on the API 37 emulator: **164 passed, 7 failed.** Every new 2b.3 check passed (38 in
"Quick profiles and color layers", 7 in "Game-art palette"). The 7 failures:

| Check | Cause | Status |
| --- | --- | --- |
| "platforms are grouped in All Games" | The RetroArch stub from the run before was still in front at the start | Environment: the new section now force-stops the stub. Rerun to confirm |
| "smoke-variants not installed", "the imported pack isn't active" | The checks read the disk before the async install and settings write landed (the next checks showed the pack installed and active) | Script fixed: both now wait up to 3 s. **Not rerun yet** |
| "re-importing … asks first", "smoke-variants-2 not installed" | Knock-on from the same import timing | Rerun to confirm |
| "wizard completes after ten buttons" | The 2.6 s "Buttons remapped" toast is often gone before it's read | Pre-existing flake: an A/B loop found it 1 of 4 on the base `feat/theme-2b2` build and 1 of 4 on this branch. Not caused by 2b.3. The check should read the saved mapping instead of the toast |
| "song plays (state BUFFERING)" | The player is still buffering 1.5 s after Confirm on the emulator | Failed in both runs on this emulator. Not A/B-tested against the base build; check whether it also fails there |

**To do:** a full `tools/device-tests/smoke.sh` run on a freshly booted emulator (run
`adb shell settings put secure immersive_mode_confirmations confirmed` first, or Android's
"Viewing full screen" dialog swallows the first keys after `pm clear`), and on the Galaxy A55.

## 5. Not covered by tests

- Game art that is only a wheel logo (transparent PNG): Palette should ignore the transparent
  pixels. No test uses one.
- The Details screen as the palette source (`adaptiveArtKey` uses the Details game when it is
  open). Unit tests cover the key function only indirectly; no device check.
- A pack's `rules` changing under hot reload while battery or time rules hold.
- Rules with both `category` and `system` plus `cardAspectRatio` (applied at run time, not folded
  into the layout). Implemented but not tested.
- `@JsonNames("battery.level", "batteryLevel")` aliases for the `battery` selector, as written in
  docs/THEME_RICING_AND_WEB_STUDIO.md §3.4. Not tested.
- Display matrix (`tools/device-tests/display-matrix.sh`) wasn't rerun. The 2b.3 rows add four
  lines to Settings ▸ Theme but no new layout code.

## 6. Decisions to review

- **Quick profile wins over Settings ▸ Theme ▸ Surface style** (a profile is a whole look). The
  alternative is to let an explicit surface choice win.
- **Adaptive colors are on by default** (`effects.adaptiveGamePalette` defaults to true per the
  spec). So upgrading users get game-art colors in Games without opting in.
- **Halos stay white** unless a color layer is active (art, a profile, the circadian curve, or the
  battery tint while low or charging), so the plain themes look exactly as before.
- **The wave tint is replaced by a quick profile's palette**, and game art (when on) wins over the
  wave tint.
- **Hero → wheel → screenshot** is the art order for extraction (the spec names hero and wheel; the
  screenshot fallback matches what the backdrop shows when a game has no hero).
