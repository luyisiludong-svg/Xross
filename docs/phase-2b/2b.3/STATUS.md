# Phase 2b.3 — evidence

Color intelligence: game-art palette, context rules, circadian curve, battery tint, quick profiles. What is still open: [`PENDING_TESTS.md`](PENDING_TESTS.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.19"></a>

## 3.19 Phase 2b.3: game-art palette, context rules, circadian curve, battery tint, quick profiles (2026-11-12)

On branch `feat/theme-2b3` (from `feat/theme-2b2`). Spec: Roadmap §4.3,
Appendix A.4, A.7, A.13, [`THEME_RICING_AND_WEB_STUDIO.md`](../../design/THEME_RICING_AND_WEB_STUDIO.md) §3.4. Everything not yet verified is
listed in [`PENDING_TESTS.md`](PENDING_TESTS.md).

| Feature | How it works |
| --- | --- |
| Game-art palette | `androidx.palette:palette-ktx` 1.0.0. `GamePaletteExtractor` (app-scoped in `AppContainer`): the focused game's art (or the open Details game's), keyed `uuid/artVersion`, debounced 250 ms (the hero fade delay) with `collectLatest`, decoded to ~192 px from `media/heroes`, then `wheels`, then `screenshots`, and analyzed on `Dispatchers.Default`. Results, including "no usable colors", sit in a 100-entry LRU cache. `GameColors.from` maps vibrant (or dominant) to `accent`, light-vibrant (or muted) to `accentSecondary`, dark-muted (or the dominant darkened to ≤ 35% value) to `canvas`. All-grey art or a glow under 3:1 contrast against the canvas falls back to the theme. Guarded by `effects.adaptiveGamePalette` (default on) and Settings ▸ Theme ▸ **Adaptive colors** |
| Morphing | The wave eases to the new colors on the GL thread (~95% in 300 ms, the 2b.2 crossfade). In Compose, `ThemeAccent` holds animated colors read in the draw phase: the avatar's focus halo and style glow (leaning 60% toward the art accent), and the status-bar battery outline. Glow-text bloom (`GlowBloom`) switches straight to the new color (`ThemeAccent.haloTarget`) instead of morphing: its color is a text shadow's, and animating it re-blurred the text every frame (see Benchmarks). Halos stay white unless a color layer is active, so plain themes look as before. `AdaptiveWave` collects art colors, battery and clock, so those updates recompose it and not the screen |
| Context rules | `rules: [{match, apply}]` (`ContextRule`) inline or from `rules.json` in `imports`; rules from every file add up (imports first). Selectors: `category`, `system` (platform id or folder alias), `battery` (`<= 20`, `<`, `>=`, `>`, `==`, `charging`, `discharging`, `full`; also read as `battery.level`), `time` (`HH:MM..HH:MM`, wraps midnight, end excluded). `apply`: palette roles and the `circadianCurve` / `batteryTint` switches, `effects.surfaceStyle` / `adaptiveGamePalette`, `cardAspectRatio`, any `layout` key. Unreadable selectors are reported and the rule dropped. Static tile-shape rules fold into the pack layout at load; dynamic ones produce a per-context `ThemeLayout` (`LocalThemeLayout`). At most 64 rules |
| Precedence | Default pack → pack → variant → category override → system override → rules in file order (`cascade`), then the user's theme color, the quick profile, the circadian curve and battery tint, then game art (`ColorPipeline`). OLED True Black keeps its black canvas under game art, and a low battery still shows over art. `LoadedTheme.look` is cached by the set of rules that hold, and battery and time reach it only when that set changes (`rememberMinuteOfDay` checks once a minute) |
| Circadian curve | `Circadian`: phases 06 cyan/sky (hue 192), 11 azure/cobalt (214), 18 amber/rose gold (22), 21 indigo/midnight (255), built with the Original theme's `SeasonalPalette.paletteOf`, smoothstep-blended ±45 min around each boundary, mixed 50% into the canvas, 35% into the wave and 20% into the glow. `palette.circadianCurve` / Settings ▸ Theme ▸ **Circadian curve** |
| Battery tint | `BatteryMonitor` (sticky `ACTION_BATTERY_CHANGED`) in `XmbViewModel.battery`. ≤ 20% unplugged: glow and wave shift toward amber, reaching red at 5%. Charging: a green lean plus a 2.4 s brightness pulse of the sheets (1.00–1.18×) on the GL thread, resting with the wave. `palette.batteryTint` / Settings ▸ Theme ▸ **Battery alert tint** |
| Quick profiles | Settings ▸ Theme ▸ **Quick profile** (`display.quickProfile`). `OLED_TRUE_BLACK`: `#000000` canvas and the `OLED_TRUE_BLACK` surface. `Y2K_CYBER_PASTEL`: `#1B1829` canvas, `#C8B6FF` lilac, `#FFB5C5` strawberry, `Y2K_GLOSSY_BEZEL`. `MONOCHROME_EINK`: black and white with `EINK_HIGH_CONTRAST`, motion `INSTANT`, marquee off, `LocalStillMotion` freezing `rememberBreathing` (focus pulse, avatar glow), the busy dots and the search caret, and the wave in `WaveLoop.SINGLE_FRAME` (`RENDERMODE_WHEN_DIRTY`, one frame per change, no time drift). All color layers are off under E-Ink. A profile's surface wins over Surface style |
| Settings | `display.quickProfile`, `display.adaptiveGamePalette`, `display.circadianCurve`, `display.batteryTint` (`null` follows the pack) in `settings.json`. Four rows after Surface style; each switch's Options offers On, Off and Theme pack default |

**Unit tests (499, 0 failures; 35 new):** `ContextRulesTest` (9): time ranges, wrapping and
bounds; battery comparisons and charger states; category, system alias, battery and time rules
holding only in context; later rules winning over the variant and the overrides; `rules.json`
imports adding up; bad rules dropped with reasons; tile shapes and layout patches; rule switches;
no rules in Default. `ColorIntelligenceTest` (14): the four circadian phases and hues, a
never-more-than-8/255-per-minute blend all day, battery amber → red → green, swatch mapping and
fallbacks, the precedence order pack → variant → rule → user → profile → game art, switch
resolution, the ambient-under-art order, OLED/Y2K/E-Ink definitions, E-Ink's `INSTANT` motion and
`SINGLE_FRAME` wave loop at every stride, the `settings.json` round trip, and the Settings rows.
`GamePaletteExtractorTest` (6): the debounce (four games scrolled at 100 ms extract only the
last), cache hits on revisits and for unusable art, LRU eviction at 100, extraction on the worker
dispatcher and on a real worker thread, and real `androidx.palette` on a bitmap (Robolectric
native graphics), plus a wheel logo on a transparent background (the transparent pixels don't make
the dominant swatch black, an all-transparent image yields nothing) and `adaptiveArtKey` (art and
scraped tiles, unscraped tiles, the Details screen sharing its row's key or naming a paged-to
game). `ContextRulesTest` also covers the `battery.level` / `batteryLevel` aliases and a
`category` + `system` tile shape applied at run time in that category only. `StillMotionTest`
(2, Compose): the pulse breathes normally and holds exactly at rest under E-Ink.

**Emulator (API 37, debug build, 2026-11-12): `smoke.sh` 164 of 171.** New checks, all passing:
the four rows and their defaults; profile cycling and saving; OLED's `#000000` canvas and Y2K's
`#1B1829` / `#FFB5C5` (from the debug-only `XrossColors` log); E-Ink switching the wave to
`SINGLE_FRAME` and back to a loop on leaving; color rows held off under E-Ink; each switch
toggling and saving; the circadian curve recoloring; `dumpsys battery` at 12% producing the tint
and `status 2` the charging pulse. In "Game-art palette", two SNES games got seeded hero PNGs and
were marked scraped in the database. Chrono Trigger's blue banner gave a `#060F29` canvas, and
EarthBound's green one replaced it with `#062912`. A game without art fell back to the theme.
Revisits made 2 extractions for 4 visits, on `DefaultDispatcher` workers. A game scrolled past in
under 250 ms was never extracted. The 7 failures are in older sections: Recently Played/import
checks hit by timing and a leftover stub (script fixed, not rerun), the "Buttons remapped" toast
(A/B against the base build: 1 of 4 on both, a pre-existing flake), and music still BUFFERING
after 1.5 s.

**Rerun on a cold-booted emulator (2026-11-12, later): `smoke.sh` 171 of 171.** Three script
fixes: the imported-pack check waits up to 8 s for `settings.json`, the remap wizard passes on the
toast *or* the wizard closing (the saved bindings are checked right after), and the song check
waits up to 6 s for the player to leave BUFFERING.

**Benchmarks:** extracting one 320×180 hero took 3.3 ms on the emulator; 60 extractions of
640×360 heroes (decoded to 320×180) took a median 0.3 ms, max 5.8 ms, none on the main thread.
**Frame pacing of game navigation** (`tools/device-tests/palette-perf.sh`: 12 scraped SNES games
with distinct heroes, profiling build, cold start per pass, Down 11 × and Up 11 × 450 ms apart,
adaptive on and off interleaved): the emulator had picked software rendering (`swangle`,
Vulkan on `llvmpipe`), which explains the earlier 96.7% (100% janky, GPU p50 4950 ms). With
`-gpu host`, the first measurements met the deadline target (on 0.29–1.04% janky, off 0.15–0.44%)
but GPU time rose with the feature: GPU p50 17–18 ms instead of 14 ms and 13–82% legacy-janky
passes on 7 of 7 adaptive passes (1 of 6 off). The cause was the glow-text bloom: its shadow color
animated through each ~300 ms morph, so the text was blurred again on every frame. With the bloom
switching color at once, **on: 0.29–0.90% janky, legacy 0.15–1.05%, GPU p50 14 ms; off:
0.30%, legacy 0.30–0.89%, GPU p50 14 ms** (3 pairs), so the feature has no measurable frame cost
on the emulator. On the Galaxy A55 at 120 Hz: on 0.12–0.19%, off 0.19–0.38%, GPU p50 5–8 ms ([§3.21](#s3.21)).

**Checked by eye on the emulator (screenshots):** game art tints the battery outline and the title
bloom; OLED True Black (black canvas, dim waves, no pill fill), Y2K Cyber-Pastel (violet to
lavender, lilac waves, strawberry rim) and Monochrome E-Ink (black to `#1A1A1A`, white waves);
the circadian curve in each phase by time zone (no root needed: `cmd alarm set-timezone`): night
violet `#1E1A37`, morning `#1E253C`, day `#1B203D`, evening plum `#291F2F`, blending toward night
at 20:58; the battery tint at 20% (pale amber), 5% (red, warm waves) and charging (mint). Context
rules from a pack's imported `rules.json` switched live with `dumpsys battery` and a time-zone
change (within the minute), and editing `rules.json` while the battery rule held applied the new
color in 3 s. Found: under E-Ink the avatar ring keeps its color (the user's avatar style, or the
iridescent default), so the screen isn't fully black and white.

**Not verified (after the Galaxy A55 run in [§3.21](#s3.21)):** a handheld or E-Ink panel; the morphs
between games with very different accents by eye; a real battery crossing 20%; Android 9–13.

<a id="s3.21"></a>

## 3.21 Hardware run: Phase 2b.3 on the Galaxy A55 at 120 Hz (2026-11-12)

Samsung Galaxy A55 (SM-A556B, Android 17 / API 37, 1080×2340, 120 Hz), on USB power, branch
`feat/theme-2b3` at `ade1203`. The phone's real `/sdcard/ROMs` (370 arcade sets, 2 PSP ISOs) was
moved aside for the suites and put back afterwards. The RetroArch stub was installed for the
integration suite and removed again.

**Device regression (`run-all.sh`, debug build): 160 of 171 after three script fixes.**

| Suite | First run | After the fixes | Notes |
| --- | --- | --- | --- |
| library | 41/41 | — | |
| theme | 76/88 (15 failures) | **88/88** | Fixes 1 and 2 |
| media | 11/22 | — | The 11 failures are the seeded-media checks; the phone has its own photos and music (as on 2026-11-11) |
| input | 10/11 | **11/11** | Fix 3 |
| integration | 9/9 | — | RetroArch stub launch, secrets, mini-HUD, dashboard |

The first run's 27 failures came from three script assumptions that hold on the emulator but not on
a phone. None was an app bug, and each was confirmed by hand before the fix:
1. **Document picker** (13 failures, one cause): the phone's picker opens on an empty *Recent*, so
   the pushed `.xtheme` files weren't listed. `pick()` in `test-theme.sh` now searches the picker
   for the file after two looks. Importing by hand from Downloads worked (`Xross/themes/hw-probe`
   created), and the rerun passed import, zip-slip rejection, variants and re-import.
2. **Battery tint** (2 failures): on a phone charging over USB, `dumpsys battery unplug` clears the
   power sources but leaves `status: 2` (charging). Xross reads the status, so it correctly stayed
   in the charging look, and the later "charging" step then changed nothing. The suite now sets
   `status 3` after the unplug.
3. **Vertical drag** (1 failure): the drag works and lands on the last row, but this phone's Games
   root has an Android Games row, so the row shown above the focus wasn't All Games. The check now
   asserts that Search scrolled away and RetroAchievements is focused.

**Frame pacing of game navigation (`palette-perf.sh`, profiling build, 3 interleaved pairs):**

| Pass | Frames | Janky (deadline) | Legacy | p50 / p90 / p99 | GPU p50 | Extractions |
| --- | --- | --- | --- | --- | --- | --- |
| on1 | 1,590 | 0.19% | 22.8% | 12 / 13 / 17 ms | 6 ms | 12 (0 on main) |
| off1 | 1,601 | 0.19% | 17.9% | 12 / 13 / 16 ms | 6 ms | — |
| on2 | 1,614 | 0.12% | 70.9% | 18 / 20 / 22 ms | 8 ms | 12 (0 on main) |
| off2 | 1,606 | 0.25% | 15.6% | 12 / 13 / 17 ms | 6 ms | — |
| on3 | 1,600 | 0.19% | 12.7% | 12 / 13 / 16 ms | 6 ms | 12 (0 on main) |
| off3 | 1,583 | 0.38% | 20.9% | 12 / 13 / 17 ms | 5 ms | — |

The target is met with room to spare: 0.12–0.19% janky with adaptive colors on, 0.19–0.38% with
them off, and GPU p50 5–8 ms against the 8.3 ms budget at 120 Hz. The game-art palette has no
measurable frame cost on this phone. The legacy column swings between passes, as on the emulator.
No other pass matches on2's higher p50, so it reads as a one-off rather than a feature cost.

**Other measurements (profiling build):**

| Measure | Result | Target |
| --- | --- | --- |
| Idle CPU, Games root, 10 s rest, 20 s sample (2 runs each) | No profile 18 / 21%, OLED True Black 17 / 16%, Y2K Cyber-Pastel 21 / 21%, **Monochrome E-Ink 0 / 0%** (no wave frames at all) | < 10% ❌ except E-Ink |
| Wave at rest (`WaveFrames`) | p50 50.0 ms, p95 51.4–51.8 ms, 0–3 missed per 300 (period 50 ms) | ✅ |
| Wave in use, 25 s of D-pad | p95 18.4–20.3 ms and 1–5 missed per 300 once warm; the first window after a cold start had 11 missed, max 74 ms | ✅ ≤ 20 ms / ≤ 5 after warm-up |
| UI: Apps list 40 down and up, 10 category switches | 2,610 frames, 0.46% janky, p50 7 ms, p99 15 ms, GPU p50 3 ms, 1 missed vsync | ✅ |
| Cold start (`am start -W`) | 445, 400, 433 ms | ✅ < 2,500 ms |
| PSS in front | 164 MB at rest; peaks of 315 MB right after a cold start and 454 MB right after scrolling Apps and Photo (2,138 photos), then back down | — |
| PSS in the background (8 s after Home) | 84 MB, 0% CPU | ✅ ≤ 150 MB |

**2b.3 items checked on the device by hand:**

| Item | Result |
| --- | --- |
| Wheel-only art | ✅ A game with only a transparent wheel logo (magenta on alpha) extracted at 240×90 to a `#290614` canvas and `#FC9EC2` glow; the transparent area didn't turn the canvas black |
| Details as the palette source | ✅ Opening Details keeps the row's colors with no new extraction (same art key); R1 paging extracts and recolors per game (04 → `#172906`, 05 → `#062906`); Back returns to the list on the last game shown with no further extraction |
| A rule's `layout` patch on screen | ✅ A pack rule `battery <= 15 → layout {motionProfile: INSTANT, focusedScale: 1.0}`: at 10% the focused tile is the size of its neighbours, at 50% it is back to 1.32×. In a screen recording the list moves 06 → 07 in one frame at 10% and glides over ~300 ms at 50%. Editing `rules.json` on the device hot-reloaded the patch. `focusedScale` is clamped to 1.35, so a rule asking for 1.4 moves the default 1.32 by only a few pixels |
| Real charger | ✅ With no `dumpsys` override (USB, status 2, 67%) the battery tint shows `charging=true` and the `#D8FFE8` mint glow |
| Morphs by eye | ◐ Recorded 05 → 06 at 120 Hz: the backdrop crossfades over ~4 frames, the battery outline and avatar ring show no jitter, and the title bloom shows no flash in the frames. Those two games' accents are close in hue, so a bigger accent jump still needs a look by eye |

Also: the display matrix (`display-matrix.sh`) was rerun on the API 37 emulator (host GPU) with
the 2b.3 rows: 12 of 12 configurations, 0 clipped texts, janky 0–1.16%. Its screenshots show the
top of Settings ▸ Theme, so the four new rows lower down weren't photographed.

**Not covered:** a real battery going below 20% unplugged, an E-Ink panel, Android 9–11, a physical
controller, and morphs between games with very different accents, judged by eye.
