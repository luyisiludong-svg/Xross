# Phase 2b.5 Implementation Prompts (Slices 2b.5-prep, 2b.5a – 2b.5d)

Phase 2b.5 (audio and boot) of the Xross launcher (`dev.xross.launcher`), from Roadmap §4.3 and
the contract in Roadmap Appendix A.9. Each slice is self-contained, unit- and device-tested, and
leaves the launcher shippable. Branches: `refactor/2b5-prep` from `main` (after `01fee6c`) for the
prep slice, merged first; then `feat/theme-2b5` from `main` for 2b.5a–d.

**Order note.** The Roadmap's recommended order is 2b.7 (components) before 2b.5 and 2b.6. Nothing
in 2b.5 depends on 2b.7, and 2b.7 doesn't touch audio, so starting 2b.5 first is safe; only the
Settings ▸ Sound rows here and 2b.7's status-bar `VOLUME` slot touch neighbouring UI.

**Why a prep slice.** Of the refactors in [`ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md)
§8, three sit directly under 2b.5: the lint gate (#1; 2b.5c adds a Media3 player and the open lint
errors include Media3 opt-ins), `ThemeLookResolver` (#4; 2b.5c's per-category BGM and 2b.5d's
effects rows resolve through the cascade now in `MainActivity.setContent`, which is also why quick
profiles behave inconsistently, see the 2026-11-13 dev notes), and the audio part of the
`XmbViewModel` split (#6; volume and ducking decisions are spread over `MainActivity`, `SnapPlayer`
and `XmbViewModel`, and 2b.5b/c add more). The rest of §8 (package moves, the full ViewModel split,
singletons, state slices, modules) waits until after 2b.5: it doesn't block audio, and moving files
mid-phase only creates merge conflicts.

---

## What exists today (2026-11-13)

| Piece | Where | State |
|---|---|---|
| UI effects | `audio/UiSounds.kt` | `SoundPool` (6 streams, `USAGE_GAME`), one sample per file; `play()` passes the same volume left and right and rate `1f`; no ducking of effects anywhere |
| Default pack levels | `assets/themes/default/sounds/` | Quiet: `cursor.wav` (same sample as `assets/sounds/snd_option.wav`) is 40 ms peaking at **−20.5 dBFS**; `system_ng.wav` is 0.8 s, peak −23 dBFS, −36.6 LUFS. Game video snaps are typically mastered near 0 dBFS, hence the ~20 dB gap noted on the A55 |
| Theme cascade | `MainActivity.setContent` | `remember` blocks; the quick profile is applied in four places (layout, surface, `ColorSwitches`, `AdaptiveWave`) and never reaches wave settings, wave background, circadian curve or the background shader |
| Sound roles | `SoundRole` in `theme/pack/ThemeManifest.kt` | Nine: `cursor`, `confirm`, `back`, `option`, `ok`, `system_ng`, `trophy`, plus streamed `opening` and `gameboot` |
| Call sites | `state/XmbViewModel.kt` | ~40 `sounds.play(UiSound.…)`. `MOVE` covers both list steps and category switches; `ENTER` covers both confirm and drilling into a folder; `BACK` covers drilling out |
| Pack audio | `ThemeManifest.Audio` | `audio.sounds` (role → file) and `audio.soundGains` (role → 0–1). No aliases, no BGM |
| Volume | `settings.sound.effectsVolume` (`soundVolume`) | **One value** for effects, both cues **and video snaps** (`MainActivity` passes `soundVolume` as `snapVolume`); music follows system media volume |
| Music | `media/MusicPlayer.kt` | ExoPlayer with `handleAudioFocus = true`; `setDucked()` → 0.2 under a snap; `pauseForGame()` |
| Snaps | `media/SnapPlayer.kt` | ExoPlayer, `handleAudioFocus = false`, 0 → target volume ramp |
| Boot | `XmbViewModel` init | `opening` cue on a cold boot (`settings.sound.startupSound`) with the menu's fade-in |
| Launch | `XmbViewModel.launchGame` | `booting` bloom transition + `gameboot` cue (or `ok`), 1100 ms hand-off |

---

## Overview

| Slice | Title | Scope | Risk |
|---|---|---|:---:|
| **2b.5-prep** | Groundwork | Lint gate; `ThemeLookResolver` out of `MainActivity` with one quick-profile layer; Settings ▸ Theme regrouped; `AudioDirector` owning volume and ducking decisions (behaviour unchanged) | Medium |
| **2b.5a** | Sound roles, panning, jitter, levels | Three new roles with fallbacks, camelCase aliases, per-role gains through the cascade, stereo pan by crosshair X, cursor pitch jitter, Default pack sounds re-levelled | Low |
| **2b.5b** | Volume mixer | Independent effects, cues, BGM, music and snap volumes in `settings.json` and Settings ▸ Sound; music pauses under video snaps (or ducks, by setting) | Low |
| **2b.5c** | Theme BGM | `bgm.ogg` / `bgm.mp3` looping on a second ExoPlayer that never plays over the user's music, follows the snap setting, stops for games | High |
| **2b.5d** | Boot sequence and launch curtain | `boot.mp4` or a shader intro on a cold start (skippable), a themed launch curtain with `gameboot` | Medium |

Unit-test gates from Appendix A (2b.5): BGM never plays over music, ducks under snaps, stops on
launch; new sound roles fall back as in A.9; camelCase aliases resolve.

### A55 device pass (2026-11-14, working tree after 2b.5d)

Profiling build over the user's own data and settings (app data and `settings.json` backed up and
restored byte for byte afterwards), a temporary folder pack with a 6 s 60 fps `boot.mp4`, a 10 s
`bgm.ogg` and `launchCurtain: IRIS`, and the RetroArch stub installed for the launches (removed
afterwards). Snaps: the phone's two scraped PSP games (OutRun 2006, Tekken 6) with their real
snap videos, so no database edit was needed.

| Check | Result |
|---|---|
| `settings.json` migration (a real v1 file without the new keys) | Pass: `cueVolume` = `snapVolume` = `effectsVolume` (1.0), `musicVolume` 1.0, `bgmVolume` 0.6, `musicUnderSnaps` PAUSE |
| Roles and pan (2b.5a) | Pass: `cursor` pan −0.20, `category_switch` −0.05 / −0.35, `drill_in` (Default `confirm` → `cursor.wav`, 0.70), `drill_out` (`back.wav`, 0.60) |
| Levels against real snaps | OutRun / Tekken snaps: peak −5.5 dBFS, −17.6 / −18.4 LUFS. Cursor click as played ≈ −9 dBFS peak (−3 × 0.5), option ≈ −6 dBFS. By-ear comparison still open |
| Video snaps volume (2b.5b) | Pass: 30% → `XrossSnap audible target=0.30`; 100% → 1.00 |
| Theme music (2b.5c) | Pass: starts with the pack (`vol=0.60 ALLOWED`), `USAGE_GAME` / `CONTENT_TYPE_MUSIC`, audio focus stack empty; Pause → `PAUSED reason=SNAP` under the OutRun snap, Lower → `vol=0.12 SNAP_DUCK`, both back to 0.60 when the cursor leaves; the stub's tone service → `PAUSED OTHER_APP` and back; game launch → `STOPPED GAME`, back in Xross → `PLAYING` |
| Boot intro (2b.5d) | Pass: the video plays and ends by itself (`at=6.3–6.6 s`); a real touch skipped it (`skipped=true at=3099 ms`); Shader → `uTransition=1.00 at=2511 ms`, wave rising from black; E-Ink → `FADE` |
| Launch curtains | Pass: IRIS (screenshot: black with the glowing title), FLASH, FADE_BLACK, BLOOM, and BLACK under E-Ink |
| Cold start (`am start -W`, 3 runs each, warm caches) | Default pack 433–633 ms to the first frame, intro decided 0.17–0.21 s after process start. Folder pack without a boot video 573–644 ms, decided 0.88–1.12 s; with the boot video 400–411 ms, decided 0.86–0.93 s (first runs after pushing the pack: 818–1067 ms, 1.2–1.6 s) |
| **Boot video at 120 Hz** | **Judder, fixed the same day** (below). Through the `TextureView` the 60 fps file showed 56–58 distinct frames/s but held unevenly, about a third each for 1, 2 and 3 vsyncs (8 / 17 / 25 ms), although the decoder dropped none (`rendered=359 dropped=0`). Now 350 of 361 gaps are an even 16.7 ms |

Findings:
- **Boot video judder: fixed.** `BootVideo` drew through `SURFACE_TYPE_TEXTURE_VIEW`, so each video frame
  waited for one of Xross's UI frames. It now has its own `SurfaceView` above the window
  (`setZOrderOnTop`): nothing draws over the intro, an empty surface shows the black underneath
  until the first frame (no flash of the wave), and the 300 ms crossfade fades the surface through
  `View.alpha` (applied to the surface from API 29; on API 28 it cuts at the end). SurfaceFlinger
  timestats show the video layer presenting ≈ 355 frames at 16.7 ms with one or two late; a skip
  crossfades the video into the menu, and after the end only the wave's surface is left. Debug and
  profiling builds now log the decoder's counts when the video goes
  (`XrossBoot video rendered=360 dropped=0 maxRun=0 skipped=0`).
  How it was measured: `screenrecord --size 1170x540` plus ffmpeg `mpdecimate`, with Samsung Video
  playing the same file as the control (59.6 fps, even 16.6 ms). At full size the recorder itself
  caps near 30 fps here, because Xross's window keeps redrawing at 120 Hz under the video
  (≈ 366 window frames in 8 s); the first pass's "~29 fps" came from that.
- **Video snaps juddered the same way: fixed on API 34+.** The OutRun 2006 snap (30 fps) held
  only about half its frames for the even 4 vsyncs at 120 Hz, the rest for 3 or 5 (25 / 42 ms);
  the same file in Samsung Video held 232 of 238 for 4. `SnapVideo` now draws on a `SurfaceView`
  behind the window, above the wave's (`setZOrderMediaOverlay`), with the scrim and the menu over
  it: 231–233 of 237 frames for 4 vsyncs. Its alpha fades the surface and the hole it clears
  together, so the snap still crossfades in over the hero art (recorded frame by frame: no wave
  between them) and nothing shows before its first frame. That partial hole needs API 34, so
  older versions keep the `TextureView`. Checked on the A55: moving from OutRun's snap to Tekken's
  (hero art, then the snap fading in), Back out of the list (only the wave's surface left),
  Options (the snap stops as before) and Details over a playing snap.
  Measured with the same `screenrecord --size 1170x540` recording, cropped to a part of the
  snap with no menu, counting frames whose mean difference from the previous one is above 1.5
  (encoder noise stays under 1).
- **Xross's window redraws at 120 Hz during the boot video** although nothing on it changes (the
  menu and black are covered). Not 2b.5-specific; it belongs with the idle-CPU item in
  `PHASE_2B3_PENDING_TESTS.md`.
- **Folder packs show ≈ 0.3–0.7 s of black on a cold start.** Since 2b.5d the screen stays black
  until the intro is decided, and for a folder pack that waits for the pack to load (0.9–1.1 s
  after process start, against ≈ 0.2 s for the built-in Default pack). It happens with or without
  a boot video. The emulator showed ≈ 250 ms.

Not checked: Xross's music player under snaps and the game pause (the app has no
`READ_MEDIA_AUDIO` on this phone, so the Music category has no songs, and it wasn't granted for the
test); by ear: the BGM loop point, option click against a snap, `system_ng` recovery.

---

## Slice 2b.5-prep: Groundwork

**Status (2026-11-13, `refactor/2b5-prep`):** done in code, unit-tested (654 tests, lint 0
errors), device-checked on the A55 (profiling builds, the user's own library and settings, no
data seeded):
- Frame pacing, Games ▸ All Games ▸ Super Nintendo, 20 Down + 20 Up at 450 ms, 4 interleaved
  rounds: deadline jank main 0.17 / 0.03 / 0.11 / 0.07 %, prep 0.11 / 0.11 / 0.18 / 0.07 %. One
  pass of each build hit the known slow GPU mode (legacy 57 % / 75 %, p50 14–15 ms; see the
  particle note in STATUS); the others p50 7–10 ms on both. No difference.
- Quick profiles (NONE, OLED, Y2K, E-Ink): `XrossColors` shows each profile's palette (OLED
  `#000000` / `#E6E6FF`, Y2K `#1B1829` / `#FFB5C5`, E-Ink black / white) and the user's Emerald
  `#01261A` with none. Every Theme row reads as intended ("Set by …", "Off (set by Monochrome
  E-Ink)", "Still in Monochrome E-Ink"; wave background, shader and particles unlabelled).
  Confirm on Quick profile changed only `display.quickProfile` in `settings.json`.
- Snap ducking: **not verified on the device.** The phone's games have no scraped art, so the
  menu starts no snaps (same on main). `AudioDirector` logs `XrossMix music=… snap=…` in debug
  and profiling builds for when a seeded library is available; `AudioDirectorTest` pins the
  0.2 duck. Music pausing for a game launch wasn't run either (it launches a real emulator).
- Device suites, caught up after 2b.5d: `test-theme.sh` now drills into the Theme folders (Colors,
  Background, Effects, Menu and profile) instead of walking the old flat rows. Same 149 checks;
  the E-Ink color-layer check now expects the prep slice's "Off (set by Monochrome E-Ink)". It passes
  **149/149** on `emulator-5554` (debug build, 471 s). `test-media.sh` now reaches its Xross
  wallpaper check through Theme ▸ Background and passes **56/56** there (218 s). That includes the
  two XrossAudio checks 2b.5b listed as failing.

Differences from the prompt below:
- Lint: the 10 errors were already fixed in `5281d15`, and AGP's `check` already runs
  `lintDebug`, so requirement 1 needed no change.
- `ThemeLookResolver` is a pure `resolve(LookInputs): ResolvedLook` that `MainActivity` calls
  inside one `remember`, not a `StateFlow`; the settled system, battery and minute inputs still
  come from Compose. The palette layers stay in `ColorPipeline` (already pure), driven by
  `AdaptiveWave`.
- Quick profiles keep exactly what they applied before: the table (`QuickProfile.fields`) is
  derived from each profile and made visible ("Set by …" rows, the profile row lists what it
  sets). Extending a profile to wave background, wave settings or the shader is a product choice
  left open (see Decisions).
- Theme colours got an Options menu and five new colours (Teal, Violet, Coral, Slate, Copper); the
  options have no colour preview yet.

```markdown
### Task: Prepare for Phase 2b.5 — lint gate, ThemeLookResolver, AudioDirector

#### Requirements
1. **Lint gate (Assessment #1):** fix the 10 `lintDebug` errors (RestrictedApi on
   `dispatchKeyEvent`, Media3 opt-ins in `MediaScreens.kt` and `SnapLayer.kt`,
   `LifecycleCurrentStateInComposition`, `UnusedBoxWithConstraintsScope`, the BOM in
   `DatParser.kt`) and make `lintDebug` part of `check`.
2. **`ThemeLookResolver` (Assessment #4, `theme/`, pure Kotlin):** move the cascade out of
   `MainActivity.setContent` (pack → variant → category → system → battery and clock rules → user
   effects → quick profile → layout) into a resolver that exposes `StateFlow<ResolvedLook>`
   (look, layout, surface, `ColorSwitches`, still motion, wave palette inputs, shader).
   `MainActivity` only collects it and writes the draw-phase holders.
3. **Quick profile as one layer:** applied in one place, after the user's settings, with an
   explicit table of the fields each profile sets (theme colour, surface, layout, colour
   switches, motion, and, where the profile means it, wave background, wave settings, circadian
   curve and background shader). Fields a profile doesn't list keep the user's value. A Settings
   row whose value the active profile overrides says so in its subtitle ("Set by OLED True
   Black") instead of silently showing a value that doesn't apply.
4. **Settings ▸ Theme regrouped** (before 2b.5d adds Boot intro and Launch curtain): top level is
   Theme pack, Variant, Quick profile, Reset to theme defaults, then folders:
   **Colours** (Theme colours, wave tint and colours, circadian curve, battery tint, adaptive
   game palette), **Background** (wave background, wave settings, wallpaper, background shader),
   **Effects** (surface style, particles, filters, frame, cosmetics), and later **Intro and
   transitions** (2b.5d). Theme colours gains an Options menu (the Options button on the row
   opens the palette list with previews) and more presets.
5. **`AudioDirector` (first piece of Assessment #6, `audio/`):** a pure core
   (`AudioMix.resolve(inputs) → outputs`) plus a thin Android adapter. Inputs: settings volumes,
   music state (none / playing / paused), snap state, game running, full-screen overlay. Outputs:
   music player volume and pause/resume, snap target volume, cue volume, effects volume. It
   replaces `MainActivity`'s `snapVolume = soundVolume`, `SnapPlayer`'s direct
   `music.setDucked(…)` calls, and `XmbViewModel`'s `pauseForGame` and cue-volume reads.
   **Behaviour unchanged in this slice** (still ducks to 0.2 under a snap); 2b.5b and 2b.5c extend
   it, and `BgmPolicy` lives inside it.

#### Tests
- `ThemeLookResolverTest`: each layer in order; category, system, battery and minute changes;
  the quick-profile field table (every profile, every field: set or untouched); `NONE` is the
  identity.
- `AudioDirectorTest`: today's behaviour pinned (duck 0.2 under a snap and back, pause for a game,
  `resumeMusicAfterGames`, cue volume = effects volume).
- MenuTree: the Theme folders, row order, the "Set by …" subtitles.
- `./gradlew check` runs lint and passes.

#### Device checks (theme + media suites)
- `palette-perf.sh` before and after the resolver: janky frames unchanged within noise.
- Each quick profile on and off: wave background, wave settings, circadian curve and shader follow
  the table; turning the profile off restores the user's values.
- Snap duck and game pause log the same values as before the refactor.
```

---

## Slice 2b.5a: Sound roles, panning, jitter, levels

**Status (2026-11-13, `feat/theme-2b5`):** done in code, unit-tested (683 tests, lint 0 errors),
device-checked on the A55 (profiling build over the user's data; a temporary
`xross-2b5a-test` pack with only `cursor.wav` and `drill_in.wav`, removed afterwards and
`settings.json` restored):
- Fallbacks: `role=category_switch file=cursor.wav gain=0.50` (the pack's cursor),
  `role=drill_in file=drill_in.wav` (its own), `role=drill_out file=back.wav gain=0.60` (Default pack).
- Pan: base −0.20 at the Default crosshair (X 0.22 clamped to ≈0.21 of the width × 0.35); category
  switches −0.05 (right) and −0.35 (left).
- Hot reload of `audio.soundGains.cursor` 0.5 → 0.2 and `cursorJitter: true`: the next clicks log
  `gain=0.20` (category switch too, through the link) and rates 0.996–1.014, no restart.
- `system_ng` followed by six clicks within 300 ms: every click logs the same gain (0.50), so the
  code doesn't duck them. **By ear not done yet** (masking by the tail or the speaker limiter), nor
  the option click against a game snap: both need a listener on the A55.

Default pack levels (`tools/audio/relevel-default-sounds.sh`, originals kept in
`assets/sounds/original/`):

| File | Length | Before | After (peak / true peak / LUFS) |
|---|---|---|---|
| `cursor.wav` | 39 ms | −20.5 dBFS | −3.0 / −2.9 dBTP / short |
| `back.wav` | 39 ms | −27.0 dBFS | −3.0 / −2.4 dBTP / short |
| `ok.wav` | 301 ms | −31.3 dBFS | −3.0 / −3.0 dBTP / short |
| `system_ng.wav` | 800 ms | −23.0 dBFS, −36.6 LUFS | −2.4 / −2.4 dBTP / −16.0 LUFS |
| `trophy.wav` | 1000 ms | −5.9 dBFS, −20.9 LUFS | −1.0 / −1.0 dBTP / −16.0 LUFS |
| `01. opening.wav` | 4001 ms | −6.8 dBFS, −17.2 LUFS | −5.6 / −5.6 dBTP / −16.0 LUFS |
| `gameboot.wav` | 3040 ms | −17.7 dBFS, −29.7 LUFS | −4.1 / −4.0 dBTP / −16.0 LUFS |

`ok.wav` (300 ms) is peak-normalized like the clicks: EBU R128 can't read under 400 ms. Default
`soundGains`: cursor 0.5, category_switch 0.6, confirm 0.7, option 0.7, back 0.6, ok 0.6,
system_ng 0.6, trophy 0.8 (drill in/out follow confirm/back). At the same Effects volume the option
click is about 14 dB louder than before, the cursor about 17 dB.

Call-site audit (`XmbViewModel`):

| Call site | Before | After |
|---|---|---|
| `moveCategory` (Left/Right at a root, L1/R1, a tapped category, Left/Right from the avatar HUD) | `MOVE` | `CATEGORY`, pan leaning ±0.15 toward the move |
| `activate` → `OpenList` (Confirm or Right on a folder) | `ENTER` | `DRILL_IN` |
| `back` (Back, or Left inside a folder) | `BACK` | `DRILL_OUT` |
| `onCategoryTapped` on the open category inside a folder | `BACK` | `DRILL_OUT` |
| `activate` → `Run`, `openMedia`, search, name entry, options, details, info, overlays | `ENTER` / `MOVE` / `BACK` / … | unchanged |

Differences from the prompt below:
- Role keys ignore case, `_` and `-`, so every camelCase key of A.9 resolves without its own alias
  (only `cancel`, `optionOpen`, `gameLaunch`, `achievementUnlock` are real aliases).
- A role borrowing its fallback's file also takes its gain and follows it through the cascade
  (`LoadedTheme.soundLinks`), unless a layer names the role.
- Variants, overrides and rules accept `audio.soundGains` only; `sounds`, `panAmount` and
  `cursorJitter` there are reported. Unknown `audio` properties are reported like `effects` ones.
- Panning applies to every SoundPool effect; the streamed cues stay centred.

```markdown
### Task: Implement Phase 2b.5a — sound roles, panning, pitch jitter and Default pack levels

#### Requirements
1. **Roles (`SoundRole`, A.9):** add `CATEGORY_SWITCH("category_switch")`, `DRILL_IN("drill_in")`,
   `DRILL_OUT("drill_out")`, each with a fallback role (`cursor`, `confirm`, `back`). Resolve a
   pack's missing role to its fallback's file *in that pack* before falling back to the Default
   pack, so a pack with only `cursor.wav` uses it for category switches too.
2. **Aliases:** accept A.9's camelCase keys (`categorySwitch`, `drillIn`, `drillOut`, `cancel`,
   `optionOpen`, `systemNg`, `achievementUnlock`, `gameLaunch`) in `audio.sounds` and
   `audio.soundGains`; report unknown keys with the pack's problems (as `Effects.KEYS` does).
3. **Gains through the cascade:** `audio.soundGains` in variants, overrides and rules (last layer
   wins per role), clamped 0–1.
4. **Call sites (`XmbViewModel`):** `UiSound.CATEGORY` for Left/Right between categories,
   `DRILL_IN` / `DRILL_OUT` for entering and leaving a folder or sub-list (not for running an item
   or closing an overlay). Keep every other call as it is; audit with a table in the PR.
5. **Panning:** `UiSounds.play(sound, pan)` with pan in −1…1 → `SoundPool.play(id, l, r, …)` using
   an equal-power law (`l = cos((pan+1)π/4)`, `r = sin(…)`), scaled so centre equals today's level.
   Pan = crosshair X across the screen (`Crosshair.clampX` / width), times a pack `audio.panAmount`
   (0–1, default 0.35) so a centred XMB stays centred; a category switch adds ±0.15 toward the
   direction of travel. Off when the output is mono (`AudioManager` speaker on a phone held in
   portrait is still stereo on the A55; no special case).
6. **Pitch jitter:** `audio.cursorJitter` (bool, default false) → a random rate in 0.98–1.02 for
   `cursor` and `category_switch` only, from a seeded `Random` held by `UiSounds` (no allocation per
   play).
7. **Default pack levels:** re-level the Default pack's samples (and the `assets/sounds/` sources
   they are built from). Today `cursor.wav` / `snd_option.wav` peak at −20.5 dBFS and
   `system_ng.wav` at −23 dBFS, about 20 dB under a typical game snap. Short effects
   (< 200 ms): peak-normalize to −3 dBFS (LUFS isn't meaningful for a 40 ms click). Longer effects
   and cues (`system_ng`, `trophy`, `opening`, `gameboot`): about −16 LUFS integrated, true peak
   ≤ −1 dBTP. Set the balance between roles with the Default pack's `audio.soundGains` (which can
   only lower a sample), not by leaving files quiet. Trim or fade `system_ng`'s tail if the device
   check below shows the following clicks are masked.

#### Tests
- `SoundRolesTest`: fallback chain per role (pack file → fallback role in pack → Default pack),
  aliases in sounds and gains, unknown keys reported, gains through variant / override / rule.
- `UiSoundsTest` (Robolectric, fake `SoundPool` wrapper): pan law at −1, 0, 1 keeps centre level;
  jitter only on the two roles and inside 0.98–1.02; a `NONE` volume plays nothing.
- `MenuNavigationSoundTest` on `XmbViewModel`: Left/Right plays `CATEGORY`, drill in/out play their
  roles, running an item still plays `ENTER`.

#### Device checks (theme suite)
- A pack with only `cursor.wav` and `drill_in.wav`: category switch logs the cursor sample, drill in
  logs its own (debug log `XrossSound role=… file=… pan=… rate=…`).
- Hot reload of `audio.soundGains` changes the logged gain without a restart.
- Levels: `ffmpeg -af volumedetect,ebur128=peak=true` on every Default pack file meets the targets
  in requirement 7 (record the table in the PR). On the A55, at the default Effects volume, the
  option click and a game snap sound comparable by ear.
- `system_ng` recovery (dev notes 2026-11-13): trigger `system_ng`, then press Options repeatedly.
  If the logged gain is constant while the clicks still sound quieter for a moment, the cause is
  masking by the 0.8 s tail or the A55's speaker limiter, not the code: fix it in the sample
  (shorter tail, lower peak) and re-check.
```

---

## Slice 2b.5b: Volume mixer

**Status (2026-11-13, `feat/theme-2b5`):** done in code, unit-tested (707 tests, lint 0 errors),
device-checked on the emulator (`emulator-5554`, debug build) by the new "Music under video snaps"
section of `test-media.sh` (10/10): Chrono Trigger gets a generated 6 s snap with a 660 Hz tone
and is marked scraped in the database.
- Video snaps 75% → 30% with seven Confirms: `"snapVolume": 0.3` saved, `effectsVolume` still
  0.75, the snap logs `XrossSnap audible target=0.30`.
- `PAUSE`: the playing song fades out (213 ms) and pauses when the snap's first frame is up, and
  fades back in (211 ms) when the cursor moves to EarthBound. A song paused with
  `cmd media_session dispatch pause` stays paused through a snap.
- `DUCK` (Music during video snaps ▸ Lower) with Music at 50%: `XrossMix music=0.10` under the
  snap, `0.50` after it.
- **Not checked on the A55** (not connected): the same section runs there, but needs a debuggable
  build for the database edit (the profiling build skips it). The suite's other 5 failures on the
  emulator (Settings ▸ Theme wallpaper row ×3, XrossAudio ×2) fail the same on `b3c3b2e`: the
  wallpaper row moved into Theme ▸ Background in the prep slice and the suite still uses the old
  offsets.

Differences from the prompt below:
- A snap touches the music only once it is **heard**: its first frame is up (`SnapState.AUDIBLE`,
  where its fade-in starts) and Video snaps is above 0%. That applies to `DUCK` too (it used to
  duck while the snap was still loading), so a snap scrolled past or muted never dips the music.
- Effects volume moved from 25% to 10% steps like the others (75% → 80% on the next press), and
  0% reads "Muted". Only Effects volume previews its level when changed.
- `musicUnderSnaps` also reads `LOWER` (the row's label) as `DUCK`; any other value is `PAUSE`.
  NaN in `settings.json` isn't JSON, so the file is reported as broken like any syntax error.
- The song's "paused by a snap" mark lives in `MusicPlayer` (beside "paused for a game"), behind a
  `MusicOutput` interface so `AudioDirectorTest` drives the director without ExoPlayer. Anything
  the user does to playback clears it; a game launch while a snap holds the song hands it to the
  game's pause (`resumeMusicAfterGames`).
- `SnapPlayer` reads its level from `AudioDirector` when its fade-in starts and follows changes
  live (`snapVolumeListener`), so the `snapVolume` parameter through `MainActivity` /
  `XmbScreen` / `SnapDriver` is gone.
- Theme music shows "No theme music in this pack" for every pack until 2b.5c fills
  `ThemeSetup.packMusic`.

```markdown
### Task: Implement Phase 2b.5b — independent volumes

#### Requirements
1. **Settings (`ConfigFiles.Sound`, `XrossSettings`):** `effectsVolume` (UI effects, existing),
   `cueVolume` (`opening` and `gameboot`), `bgmVolume` (2b.5c; stored now, used later),
   `musicVolume` (Xross's music player, applied as `player.volume` × the duck factor, on top of
   system media volume), `snapVolume` (video snaps). All 0–1. **Migration:** a `settings.json`
   without the new keys takes `cueVolume = snapVolume = effectsVolume`, `musicVolume = 1`,
   `bgmVolume = 0.6`, so nobody's mix changes on upgrade.
2. **Wiring (through `AudioDirector` from the prep slice):** the snap's target volume is
   `snapVolume` (not `soundVolume`); both cues use `cueVolume`; the music player's volume is
   `musicVolume` × the duck factor instead of being replaced by it.
3. **Music under video snaps (`musicUnderSnaps`: `PAUSE` default, `DUCK`):** dev notes
   2026-11-13 — music kept playing under a game's video snap and only got quieter. `PAUSE`: when a
   snap starts its audio, the song fades out over 200 ms and pauses; when the snap ends or focus
   leaves the game, it resumes with a 200 ms fade-in, **only if Xross paused it** (a song the user
   paused stays paused; a snap that never started doesn't touch the music, so fast scrolling
   doesn't stutter it). `DUCK`: today's 0.2 × `musicVolume`. Migration: a file without the key
   takes `PAUSE`.
4. **Settings ▸ Sound:** after Effects volume: Startup and game cues, Theme music (with "No theme
   music in this pack" when the active pack has none), Music, Video snaps; each cycles 0–100% in
   10% steps like Effects volume, and Options offer Mute / 50% / 100%. Then "Music during video
   snaps: Pause / Lower". Changing Music or Video snaps applies live to a playing song or snap.
5. Reset to theme defaults doesn't touch volumes (they're the user's, not the pack's).

#### Tests
- `VolumeSettingsTest`: migration from an old file, round trip, clamping, NaN, `musicUnderSnaps`
  default and unknown values.
- `AudioDirectorTest`: `DUCK` gives `0.2 × musicVolume` and restores it after; `PAUSE` pauses on
  snap audio start and resumes on snap end, never resumes a user-paused song, ignores a snap that
  never started; a game launch during a paused-by-snap song follows `resumeMusicAfterGames`.
- MenuTree rows: order, labels, cycling, the no-BGM note, the snaps row.

#### Device checks (media suite)
- Video snaps at 30% → the snap player's target volume is 0.3 (log), effects unchanged.
- `PAUSE`: a song plays, focus a game with a snap → music pauses when the snap's audio starts;
  move off the game → it resumes where it was. Pause the song yourself first → it stays paused.
- `DUCK`: Music at 50% while a snap plays → 0.1; after the snap → 0.5.
```

---

## Slice 2b.5c: Theme BGM

**Status (2026-11-13, `feat/theme-2b5`):** done in code, unit-tested (737 tests, lint 0 errors),
device-checked on the emulator (`emulator-5554`, debug build) by the new "Theme music (2b.5c)"
section of `test-media.sh` (15/15), with a generated 10 s `bgm.ogg` (ffmpeg's built-in Vorbis
encoder, so no libvorbis is needed):
- Starts with the pack: `XrossBgm state=PLAYING file=bgm.ogg vol=0.60 reason=ALLOWED`; `dumpsys
  audio` shows a started `USAGE_GAME` / `CONTENT_TYPE_MUSIC` AudioTrack and no Xross entry in the
  focus stack.
- A song stops it (`reason=MUSIC`); Now Playing ▸ ↓ (new, see below) empties the queue and it
  starts again.
- The stub's new tone service (`USAGE_MEDIA`, in the background) pauses it (`reason=OTHER_APP`);
  stopping the tone brings it back.
- Launching Chrono Trigger in the stub stops it (`reason=GAME`); back in Xross it starts over.
- Under Chrono Trigger's snap: `vol=0.12 reason=SNAP_DUCK` with Lower, `state=PAUSED reason=SNAP`
  with Pause, back to 0.60 when the cursor moves on.
- The suite's other 5 failures are the ones 2b.5b recorded (wallpaper row offsets ×3, XrossAudio ×2).
- **Not checked on the A55** (not connected): the gap at the loop point by ear, and the same suite
  there (it needs a debuggable build for the snap fixture).

Differences from the prompt below:
- **Now Playing ▸ ↓ stops the music and empties the queue.** Nothing in the UI cleared the queue
  before, so with "a paused song blocks BGM" theme music could never come back after the first
  song until the process died.
- What stops (next start from 0) and what pauses: no loop, Theme music 0%, a game and a loaded
  song stop it; the launcher leaving the screen, the video player, the boot video, another app and
  a snap under Pause pause it. Fades: 600 ms in from a stop, 300 ms out to a stop, 200 ms for
  pauses, snaps and level changes, none on `onStop`.
- "Another app" counts active `USAGE_MEDIA` players and `USAGE_GAME` players with music or movie
  content (so Xross's own SoundPool clicks and cues, `USAGE_GAME` + sonification, don't count),
  minus Xross's music player, snap, video player and BGM players. Silence applies at once; playback
  must hold 500 ms, since the platform reports Xross's own players a moment late. The callback is
  registered only between `onStart` and `onStop`, with a snapshot read at `onStart`.
- `BgmPolicy` is a pure object next to `AudioMixer`; `AudioDirector` applies it through a
  `BgmOutput` interface (`media/ThemeBgm.kt`), so `BgmPolicyTest` drives the director without
  ExoPlayer. The crossfade runs on a second, short-lived player.
- `audio.bgmFile` takes `.ogg`, `.mp3`, `.m4a`, `.aac`, `.opus`, `.flac` or `.wav`; the root
  default names are only `bgm.ogg` / `bgm.mp3`. Built-in packs can't bring one (folder packs only,
  like the wallpaper). Variants, overrides and rules accept `bgmFile` beside `soundGains`.
- The boot-video rule is wired (`AudioDirector.onBootVideo`) for 2b.5d to call.
- On a cold boot the loop fades in under the `opening` cue; nothing holds it back until the cue ends.

```markdown
### Task: Implement Phase 2b.5c — ambient theme music

#### Requirements
1. **Pack:** `bgm.ogg` / `bgm.mp3` at the pack root or `audio.bgmFile` (alias `bgm`; `none` turns it
   off), through variants, overrides and rules like the wallpaper (a category override can give
   Games its own loop). `.xtheme` archives accept it at the root; size cap 20 MB.
2. **Player (`media/ThemeBgm.kt`):** a second ExoPlayer, `REPEAT_MODE_ONE`, gapless; `USAGE_GAME`,
   `CONTENT_TYPE_MUSIC`, `handleAudioFocus = false` (BGM must not steal focus from other apps or
   start a media session/notification). Created lazily on first need, released when the pack has
   no BGM or the activity is destroyed. Crossfades 600 ms on a track change (category override).
3. **Rules (`BgmPolicy`, pure):** plays only when *all* hold: the pack has a track, `bgmVolume > 0`,
   the activity is started, Xross's music player has **no song loaded** (paused counts as loaded),
   no other app is playing media, no game is running, no full-screen video. "Another app" comes
   from `AudioManager.registerAudioPlaybackCallback` (API 26+): active `USAGE_MEDIA` /
   `USAGE_GAME` configurations minus the ones Xross itself is playing (music, snap, BGM, whose
   states it knows). Not `isMusicActive()`, which counts Xross's own players and would silence BGM
   by its own playback. Under a video snap it follows `musicUnderSnaps` from 2b.5b (`PAUSE`:
   pauses and resumes where it was; `DUCK`: 20%), as part of `AudioDirector`;
   fades out 300 ms when music starts, and stops (not pauses) before an emulator launch; returns
   from position 0 after a game. Silent under the boot video until it ends.
4. **Lifecycle:** pause on `onStop`, resume on `onStart` if the policy still allows; a phone call or
   a notification sound ducking us is handled by the platform (no focus held).
5. Debug and profiling builds log `XrossBgm` (`state=PLAYING file=bgm.ogg vol=0.60 reason=…`).

#### Tests
- `BgmPolicyTest` (the table): every condition alone blocks playback; snap → paused (`PAUSE`) or
  0.2 (`DUCK`); music start →
  fade and stop; launch → stop; return → restart from 0; no track → player never created.
- Cascade: pack file, `audio.bgmFile`, `none`, a category override switching tracks.
- `ThemeArchiveTest`: `bgm.*` accepted at the root, oversize rejected.

#### Device checks (media + theme suites, a 10 s generated loop)
- BGM starts with the pack; starting a song stops it; stopping the song (cleared queue) restarts it.
- A snap pauses it under `PAUSE` and ducks it under `DUCK` (log `vol=0.12` for 0.6 × 0.2);
  launching the stub stops it; Back restarts it.
- Another app playing audio (`cmd media_session dispatch play` on a test player) keeps it silent.
- On the A55: no audible gap at the loop point (by ear), no `AudioFocus` change in
  `dumpsys audio` when BGM starts.
```

---

## Slice 2b.5d: Boot sequence and launch curtain

**Status (2026-11-13, `feat/theme-2b5`):** done in code, unit-tested (757 tests, lint 0 errors),
device-checked on the emulator (`emulator-5554`, debug build) by the new "Boot intro and launch
curtain (2b.5d)" section of `test-theme.sh` (15/15, run on its own over a fresh setup), with a
generated 3 s `boot.mp4` (test pattern and a 440 Hz tone):
- Cold start: the video plays as `USAGE_GAME` / `CONTENT_TYPE_MOVIE`; a key skips it
  (`mode=VIDEO file=boot.mp4 skipped=true at=597 ms`), that key doesn't move the menu and the next
  one switches category. Untouched it ends by itself (`skipped=false at=3339 ms`). A new activity in
  the same process (warm start) shows nothing.
- Boot intro ▸ Shader over the pack's video: `uTransition=1.00 at=2520 ms`, then
  `mode=SHADER skipped=false at=2505 ms`; screenshots show black, the Starfield emerging, the warp
  building, the menu fading in. E-Ink gives `mode=FADE`.
- Each curtain on a stub launch of Chrono Trigger: the pack's `IRIS`, then `FLASH`, `FADE_BLACK`,
  `BLOOM` from Settings, and `BLACK` under E-Ink. Screenshots of `IRIS` show the circle closing on
  the focused tile.
- `am start -W` on the emulator, over three runs: 640–750 ms to the first frame without a boot
  video, 630–850 ms with one; the intro is decided about 1 s after the process starts (≈ 250 ms of
  black after the first frame). Keys are taken from the first frame on.
- **The whole theme suite is stale:** 46 of 149 passed. The failures start at the first Down
  from Theme pack: the suite still walks the flat Settings ▸ Theme rows that the prep slice grouped
  into folders (`test-theme.sh` was last updated in 2b.4f). Not re-run on the build before 2b.5d.
  Since fixed: 149/149 (see the prep-slice status).
  Run in the full suite, the 2b.5d section also failed until it reset Boot intro and Launch curtain
  itself (the stale sections had changed them by pressing Confirm on the wrong rows).
- **Not checked on the A55** (not connected): cold-start times there, and that the video doesn't
  stutter at 120 Hz.

Differences from the prompt below:
- **A key or touch before the intro is decided skips it in advance.** The device check found that a
  key in the ≈ 250 ms between the first frame and the decision (the saved pack still loading)
  reached the menu and the video started after it. Now that key goes to the skip handler, and so
  does leaving Xross in that window; leaving during an intro ends it at once.
- A cold start stays black until the intro is decided (it was the wave with the menu fading in).
  The fade-in now lifts that black over 350 ms (the old reveal delay), then fades the menu in as
  before, so the wave comes up from black too instead of popping in.
- "Muted" means either Startup sound off or Startup and game cues at 0%. The shader intro is also
  off while Wave background is off (nothing to ramp).
- The user's Boot intro ▸ Shader plays even over a pack's video; only a pack's own `SHADER` loses to
  its video. Video without a video falls back to the fade-in.
- `audio.bootVideo`'s alias is `boot` (the prompt's alias is the key itself); `none` turns a
  root `boot.mp4` off. Only the pack's base reads it; variants, overrides and rules that set it are
  reported. Folder packs only, like the theme music. The 15 s limit cuts playback; the file's
  length isn't read at load.
- `uTransition` follows a smoothstep 0 → 1 over 2.5 s and then eases back like after a category
  switch; the black over the shader lifts over the first 1.5 s and the menu fades in from 1.2 s.
- The boot video plays without audio focus (`USAGE_GAME` / `CONTENT_TYPE_MOVIE`), counted as
  Xross's own player in the playback census, fitted on black rather than cropped, and the wave
  pauses under it. It draws on a `SurfaceView` above the window, not a `TextureView` (the A55 pass
  below found the `TextureView` judders at 120 Hz).
- Curtains close in 900 ms (inside the 1100 ms hand-off); `IRIS` closes on the crosshair, where the
  focused tile is, with the title glowing in once it is three-quarters shut. Each launch logs
  `XrossBoot curtain=IRIS title=…` (`BLACK` under E-Ink). Aliases: `FADE`/`BLACK` → `FADE_BLACK`,
  `CIRCLE` → `IRIS`, `WHITE_FLASH` → `FLASH`, `GLOW` → `BLOOM`; `FADE`/`NONE` → `OFF` for
  `bootIntro`.
- The two rows live in a new Settings ▸ Theme ▸ Intro and transitions folder (between Effects and
  Menu and profile); the Boot intro row says why it won't play (E-Ink, startup sound off, no video
  in the pack).

```markdown
### Task: Implement Phase 2b.5d — boot video or shader intro, and the launch curtain

#### Requirements
1. **Boot (`ui/BootSequence.kt`):** on a cold start only, if the pack has `boot.mp4` (or
   `audio.bootVideo`, alias `bootVideo`; ≤ 15 s, ≤ 30 MB) play it full-screen over the menu with the
   `opening` cue muted (the video carries its own sound at `cueVolume`); else if
   `effects.bootIntro: SHADER` play a 2.5 s intro that ramps the pack's shader from black with
   `uTransition` 0→1 (already a public uniform) under the `opening` cue; else today's fade-in. Any
   key, touch or 15 s ends it with a 300 ms crossfade. Off under E-Ink and when Startup and game cues
   volume is 0 and the user turned the startup sound off. Never delays input handling: the menu is
   composed underneath and keys go to the skip handler first.
2. **Launch curtain:** `effects.launchCurtain`: `BLOOM` (today's), `FADE_BLACK`, `IRIS`
   (a circle closing on the focused tile), `FLASH` (white flash then black), timed to the
   1100 ms hand-off; `gameboot` plays as now. Drawn in the existing `booting` overlay; E-Ink snaps
   to black.
3. Settings ▸ Theme rows: Boot intro (Theme pack / Video / Shader / Off), Launch curtain; saved in
   `display.effects` like the 2b.4f rows; Reset to theme defaults clears them.
4. Log `XrossBoot` (`mode=VIDEO file=boot.mp4 skipped=true at=1840 ms`).

#### Tests
- `BootSequenceTest`: mode resolution (video beats shader, E-Ink and muted turn it off, warm starts
  never play it), the 15 s cap, skip on input; archive limits.
- Curtain enum names, aliases and cascade; Settings rows and their reset.

#### Device checks (theme suite)
- Cold start with a generated 3 s `boot.mp4`: plays, a key skips it (`skipped=true`), input reaches
  the menu right after; a warm start doesn't play it.
- Shader intro logs `uTransition` reaching 1; each curtain logs its mode on a stub launch.
- On the A55: cold-start time to an interactive menu with and without a boot video
  (`am start -W`), and that the video doesn't stutter at 120 Hz.
```

---

## Decisions to confirm

| Question | Recommendation |
|---|---|
| What does a quick profile override? (prep) | **Exactly the fields in its table, applied as one layer after the user's settings.** Rows it overrides say "Set by …"; turning the profile off restores the user's values. Today it covers some fields (theme colour, surface, layout) and not others (wave background, wave settings, circadian curve, shader), which reads as random |
| Music under a video snap (2b.5b) | **Pause by default**, with Lower (duck) as a setting. Resume only what Xross paused |
| Default pack levels (2b.5a) | **Re-master the files**, then balance with `soundGains`. Gains are capped at 1, so they can't fix samples that are too quiet |
| Does BGM take audio focus? | **No.** Holding focus would pause the user's podcast in another app every time the launcher is in front. Instead, stay silent while any other app plays media (playback callback, Xross's own players subtracted), and let the platform duck us |
| Does a *paused* song block BGM? | **Yes.** A paused song means the user is listening to music in Xross; BGM returns only when the queue is cleared |
| Is "Music" volume Xross's own or system media volume? | **Xross's own, on top of system media volume**, so the slider mixes music against BGM and snaps without changing other apps |
| Boot video sound | Plays at `cueVolume` and replaces the `opening` cue rather than layering on it |

---

## Dev notes triage (`dev_notes/2026-11-13.md`)

| Note | Where it goes |
|---|---|
| `snd_option` far quieter than game video snaps | 2b.5a requirement 7 (levels) and 2b.5b (separate snap volume) |
| `snd_option` slow to recover after `snd_system_ng` | 2b.5a device check; likely the sample's tail or the speaker limiter, since nothing ducks effects in code |
| Quick profiles override only some theme settings | Prep requirements 2–3 |
| Theme navigation confusing; Theme colours has no Options menu and few colours | Prep requirement 4 |
| Music keeps playing (ducked) under a game's video snap | 2b.5b requirement 3, 2b.5c BGM rule |
| A frame of background shows when switching games in Details | Not 2b.5: a separate bug fix in `DetailsScreen`'s transition, any time |
| Auto-sort apps into categories (YouTube → Video); local Videos / Photos / Music folders | Not 2b.5: a library feature (`MenuTree`, scanner); schedule after the `XmbViewModel` split so it doesn't land in the god object |

---

## Carry-overs from 2b.4 (not part of 2b.5)

- The particle slowdown on the A55 during navigation (STATUS §3.28, Known limitation 10): profile
  with a Perfetto GPU-queue trace before 2b.6 adds more per-frame work.
- `PHASE_2B3_PENDING_TESTS.md` items still open (idle CPU at rest 16–21% vs < 10%).
