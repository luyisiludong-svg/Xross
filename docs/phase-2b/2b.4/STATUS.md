# Phase 2b.4 — evidence

Shaders, audio-reactive uniforms, wallpapers and frosted surfaces, focus frames, particles and post-processing. Plan and slices: [`PROMPTS.md`](PROMPTS.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.22"></a>

## 3.22 Phase 2b.4a: shader host and watchdog (2026-11-12)

`ui/WaveRenderer.kt` is now a multi-program host. Sources live in `assets/shaders/`: `prelude.glsl`
declares the public uniforms (`uTime`, `uResolution`, `uColorTop`, `uColorBottom`, `uAccent`,
`uGlow`, `uBatteryLevel`, `uAudioBass`, `uAudioBeat`, `uTransition`) and goes in front of every
preset and of a pack's `shader.frag` unless that file has its own `#version`. A pack picks a
program with `effects.shaderPreset` (alias `shader`) and `effects.shaderPath` in its base,
variants, overrides and `rules`; the last layer wins.

- **AOT compile:** every program a pack can show is compiled on the GL thread via `queueEvent`
  when it loads (`KHR_parallel_shader_compile` is polled per frame where present). The program on
  screen stays until the wanted one links, and a new program is first drawn into a 1×1 viewport.
  The EGL context is now preserved across an activity pause, so returning from a game doesn't
  recompile.
- **Failures:** no `check()`; a compile/link error is `ShaderStatus.Failed`, the wave is drawn,
  the driver's line is logged under `XrossShader` and a toast names the line in the user's file.
- **Watchdog** (`FrameBudgetWatchdog`, pure): non-WAVE programs are timed for ~2 s on screen
  (timer query, else `glFinish` every third frame); p90 over 60% of the active frame budget
  retires the program for the session. A program far over budget goes sooner: 3 samples all over
  4× the budget, or a median over 2× after 6.
- **Canary** (`ShaderCanary`): `Xross/cache/shader_canary.tmp` holds a pack shader's key while it
  compiles and until 120 frames in a row; at process start a leftover key goes to
  `shader_blocked.txt` and isn't compiled again until the file's text changes.

**Emulator (API 37, host GPU, no timer-query extension, so the `glFinish` path):**

| Check | Result |
|---|---|
| Presets per category (Games BOKEH, Video CRT, Music STARFIELD, Photo ASCII_MATRIX) | ✅ all linked ahead of time; switching categories every 0.3 s reported each `Ready` at once; watchdog p90 2.8–6.0 ms of 10 ms (BOKEH with 26 orbs, before the change below) |
| Pack `shader.frag` (Apps) | ✅ drawn; marker present for its first frames, gone after 120 |
| Hot reload of `shader.frag` | ✅ new key compiled and shown without leaving Xross |
| Compile error (`fx/broken.frag`) | ✅ wave shown; toast "Shader fx/broken.frag: line 3: 'glowColour' : undeclared identifier · showing the wave" |
| Watchdog (`fx/slow.frag`, 6000-iteration loop) | ✅ tripped early at p90 56.5 ms; toast; wave shown; no marker left |
| Crash recovery (marker planted, cold start) | ✅ toast "A theme shader stopped Xross last time"; key in `shader_blocked.txt`; never compiled; Apps shows the wave |
| `smoke.sh` / theme suite | ✅ 12/12 (three runs; one earlier run had a single failure that was not captured and didn't recur) / 88/88 |

**Galaxy A55 (Xclipse 530, 120 Hz panel paced to 60, profiling build; `GL_EXT_disjoint_timer_query`
present, so the watchdog used GPU timer queries):** `smoke.sh` 12/12 (the phone's ROMs, app data
and config were backed up first and restored after).

| Check | Result |
|---|---|
| Presets, ahead of time | ✅ all linked at pack load; GPU p90 CRT 1.31 ms, ASCII_MATRIX 1.33 ms, STARFIELD 2.96 ms, BOKEH 2.90 ms, custom 0.46–0.55 ms (limit 10 ms). The GPU stayed at its 450 MHz floor throughout |
| BOKEH cost | 26 orbs read 7.7–8.0 ms (~70% GPU busy at 450 MHz, vs ~45% for WAVE); 20 orbs read 2.9 ms, a non-linear drop (the longer loop evidently crossed a compiler threshold). Now 20 |
| Pack shader, canary | ✅ drawn; marker present at first, gone after 120 frames |
| Hot reload, compile error | ✅ edited `shader.frag` relinked live; `fx/broken.frag` toast names line 3 |
| Watchdog | ✅ the 6000-iteration shader drew at 344 ms a frame; tripped after 3 samples, 1.5 s after its first frame (2.9 s before the 3-sample rule) |
| Crash recovery | ✅ planted marker → toast at start, key blocked, never compiled |

**Not covered:** Adreno and Mali GPUs (handhelds), a real driver crash or GPU reset, and E-Ink
with a pack shader. The watchdog measures at whatever clock the GPU governor has chosen, so a
reading near the limit at the lowest clock could pass at a higher one.

<a id="s3.23"></a>

## 3.23 Phase 2b.4b: audio-reactive uniforms (2026-11-12)

`uAudioBass` (0–1) and `uAudioBeat` (0 or 1) now come from Xross's own music player. There's no
`Visualizer`, so no `RECORD_AUDIO`: other apps' audio doesn't drive the shader. Code in
`media/energy/`:

- **Tap:** `MusicPlayer` builds its ExoPlayer with a renderers factory whose `DefaultAudioSink`
  has `BassTapProcessor` in front of the default processors. It's a pass-through
  `BaseAudioProcessor`: it reads each 16-bit or float buffer in place on the playback thread (any
  channel count, averaged to mono) and copies it to an output buffer it reuses, so nothing is
  allocated per buffer.
- **Analysis (`BassAnalyzer`, no FFT):** a 4th-order Butterworth low-pass at 150 Hz (two RBJ
  biquads, 24 dB/octave, so a voice's 360 Hz is 30 dB down; a single biquad let a pure 360 Hz
  fixture read 0.75), then a 20 ms running mean square read every 10 ms. Bass is RMS over a
  loudness reference that decays over 4 s (floor ≈ -28 dBFS) and is held with a 200 ms
  exponential release. A beat is a hop over 1.6× the last second's mean energy, with an energy
  floor and at least 0.35 of the track's own level. It holds for 100 ms and re-arms after 300 ms.
- **Latency compensation:** Media3 1.10's sink flushes its processors without a stream position,
  so the processor can't date a buffer itself. `EnergyAudioSink` (a `ForwardingAudioSink`)
  anchors the analyzer to each new buffer's presentation time and passes every
  `getCurrentPositionUs` reading (the sink's playout clock, which ExoPlayer turns into
  `currentPosition`) to `PlaybackClock`. Both are on the sink's own timeline. The GL thread can't
  call `player.currentPosition` (ExoPlayer is single-thread), so each frame it extrapolates the
  last reading (≤ 250 ms old) and reads `AudioEnergyRing` at that time: bass interpolated, beat
  from the entry at or before it. Paused, stopped, buffering or flushed (seek, new queue): 0 and 0.
- **Shaders:** `BOKEH` and `ASCII_MATRIX` already scaled with `uAudioBass`. `STARFIELD`'s dust now
  brightens with the bass, and its vanishing-point glow pulses on a beat. `WAVE` doesn't react
  yet. Debug and profiling builds log the uniforms every ~2 s under `XrossAudio`
  (`bass max=1.00 beats=4 lead=485 ms`).

**Unit tests:** `AudioEnergyTest` (23): filter response (one biquad: -3 dB at 150 Hz, -36 dB at
1.2 kHz; the cascade: -24 dB at 300 Hz, < -70 dB at 1.2 kHz, unity DC), hop timestamps, silence,
a steady and a quiet bass line reading full, a voice barely registering, the 200 ms decay
(e^-1 after one time constant), kicks at 120 BPM giving one 100 ms beat each, the refractory
period, the ring's interpolation, edges, wrap and rewind, the playout clock, and the whole tap
behind a fake sink. In that test the decoder runs 1 s ahead of the speaker and the shader still
follows what is heard; it also covers pause, resume, seek, stop, a refused buffer and
non-PCM pass-through. 558 unit tests in all.

**Emulator (API 37, debug build):** the media fixtures now carry a 55 Hz kick at 120 BPM under
their tones. Media suite 24/24, including the new checks "the music drives the shader's bass and
beat" (`bass max=1.00 beats=4` per 2 s) and "paused music binds 0" (`bass max=0.00 beats=0`).
`smoke.sh` 12/12. The analysis ran **475–507 ms** ahead of the speaker (decoder plus the
AudioTrack buffer), about twice the 100–250 ms the plan assumed. Reading the newest entry would
put every pulse half a second early.

**Not covered:** the Galaxy A55 and handhelds (their output latency, Bluetooth headphones with
their extra delay, which the sink's clock should include), sync judged by eye against real music,
and the frame cost (one biquad cascade per sample on the playback thread, a locked array read per
frame on the GL thread; neither profiled).

<a id="s3.24"></a>

## 3.24 Phase 2b.4c: wallpapers and frosted surfaces (2026-11-12)

Layer 3 of the Theme 2.0 stack, and the `backdropBlurDp` that 2b.2 read but didn't draw.

- **Wallpaper:** a pack's `wallpaper.png` / `.jpg` / `.webp` at its root, or `effects.wallpaperPath`
  (alias `wallpaper`; `none` turns it off), through variants, overrides and rules like the shader
  (`WallpaperRef` in the cascade; files checked at load, `LoadedTheme.wallpapers`). Photo ▸ Options
  ▸ **Set as Xross wallpaper** (in a photo list, and now as an Options menu over the viewer, which
  also offers Set as Android wallpaper and, while one is set, Use the theme's wallpaper) saves the
  photo's URI as `display.customWallpaper`; it beats the pack's, and the pack's shows again if the
  photo can't be read. **Settings ▸ Theme ▸ Xross wallpaper** (above Import theme) says where the
  wallpaper comes from and goes back to the theme's. `WallpaperLoader` decodes with `ImageDecoder`
  (EXIF rotation, software pixels, ≤ 2560 px) on IO and keeps the last two. `WallpaperBackdrop`
  draws it full-bleed under the hero art with a lighter scrim, hides it while the wave editor is
  open, and reports `backdropCovers` when it is opaque and fully in, so the GL loop stops.
- **Frosted surfaces (`PreBlur`, `FrostedBackdrop`):** when hero art or a wallpaper is shown and
  the active surface has `backdropBlurDp`, a 192 px wide copy is blurred once on
  `Dispatchers.Default` (three box passes ≈ a Gaussian of that sigma, premultiplied, edges
  clamped) and cached by image and radius (`PreBlurCache`, 8 copies, LRU). `Modifier.frostedSlice`,
  inside `xrossSurface`'s clip and under its fill, draws each layer's aligned slice (same
  `ContentScale.Crop` rect, measured from the home screen's root) with its scrim and fade, reads
  everything in the draw phase and redraws when the surface moves. No `RenderEffect`, so Android
  9–11 get it too (§5 of the Roadmap revised). Over the live shader, a video snap or a media screen
  the tint alone. The Default pack's `FLAT_GLASS` now blurs 14 dp.
- **`.xtheme`:** archives may now hold `wallpaper.*` and `shader.frag` at their root (the loader's
  default names; `shader.frag` there was rejected since 2b.4a) and a `wallpapers/` folder.
- Debug and profiling builds log `XrossWallpaper` (`wallpaper=pack 320x180 opaque=true`) and
  `XrossFrost` (`WALLPAPER radius=3 copy=192x108 in 24.5 ms`).

**Unit tests:** `BackdropTest` (20): wallpaper paths (none, escapes, types), the pack default in
any case, the cascade through variants, a category override and a rule, a missing file, the
user's wallpaper over the pack's, `display.customWallpaper` (round trip, trim, length cap), the
photo Options and the Settings row; the copy's size, the radius mapping, the crop rect, opacity,
the blur (uniform stays uniform, a point spreads symmetrically and keeps its energy, transparent
neighbors don't darken, radius 0 copies) and the cache (LRU eviction, radii, evict, no cached
failures). `ThemeArchiveTest`, `MediaMenusTest`, `ConfigCodecTest` and `WaveSettingsTest`
updated. 578 unit tests in all.

**Emulator (API 37, debug build):** theme suite **94/94**, with six new checks: a `wallpaper.png`
dropped into the active pack hot-reloads behind the menu, stops the wave's loop (`mode=STOPPED`)
and gets a 192×108 pre-blurred copy; Settings ▸ Theme ▸ Xross wallpaper names the pack; removing
the file hides it and the wave runs again. Media suite **31/31**, with seven new checks: the photo
viewer's Options offer Set as Xross wallpaper, the URI lands in `settings.json`, the photo is
decoded opaque and stops the wave, the Settings row shows it, and Confirm clears it and the wave
resumes. `smoke.sh` 12/12. By eye: a checkerboard wallpaper shows full-bleed with its scrim, and
the avatar mini-HUD (Flat glass) shows it blurred under its tint, aligned with the squares around
it. A blur took 24.5 ms for hero art on a warm process, and 428 ms for the first one after a cold
start (JIT and the default dispatcher warming up); either way off the main thread.

**Not covered:** the Galaxy A55 and handhelds (the blur's look at 120 Hz, decode time of a 4K
photo, memory with a 2560 px wallpaper held), frame pacing on the profiling build, a surface
sliding with `graphicsLayer` (its slice follows positioning callbacks), Android 9–11 on hardware,
and the `.xtheme` change on a device (unit-tested only). The Options panel and the button-hint
pill aren't themed surfaces, so they don't frost.

<a id="s3.25"></a>

## 3.25 Phase 2b.4d: focus frames and icon masks (2026-11-12)

Draw-phase cosmetics: how the focused item is framed, and how installed apps' icons are cut and
colored.

- **Manifest and cascade:** `effects.focusFrameStyle` (alias `focusFrame`), `effects.iconMask`
  and `effects.iconFilter`, read ignoring case, `-` and spaces; unknown names are reported and
  left unset. They form a `Cosmetics` bundle carried by the base, variants, category and system
  overrides and rules like the shader (the last layer naming a value wins), resolved into
  `ThemeLook.cosmetics`; `ThemeLook.focusGold` is the cascaded `palette.focusGold`.
- **Focus frames (`ui/FocusHalo.kt`):** `LocalFocusFrame` (style, neon and gold colors) from
  MainActivity. `rememberFocusPulse` runs the breathing animation only for `BREATHING_HALO`;
  every other style returns a constant pulse that isn't snapshot state, so a resting focused item
  draws no frames. `drawPlateFrame` draws the four styles around game tiles, thumbnails, art icons
  (fitted to the art) and RetroAchievements badges: the old white rings; a crisp neon core with a
  short falloff (accent, else the wave color when the accent is white, else the game-art tint);
  a solid shadow 2 dp down-right clipped to outside the plate (translucent tiles aren't darkened);
  a 3 dp metal bezel in `palette.focusGold` (the mastery gold by default) with a beveled edge.
  Glyph icons get the halo tinted neon or gold, or a solid offset copy under `HARD_SHADOW`.
- **App icons (`data/IconStyler.kt`):** masks are a superellipse (n = 4), a circle, or a squircle
  in an 8.5% chrome bezel with a gloss lens; the cut is anti-aliased (mask filled, content drawn
  `SRC_IN`). Adaptive icons are rebuilt from their layers drawn 1.5× (a quarter past each edge);
  legacy icons with opaque corners are cut whole, others shrunk to 70% onto a plate of their
  average color lifted 82% toward white. Filters are one `ColorMatrix` mapping Rec. 709 luma onto
  canvas → accent (`DUOTONE`) or black → accent (`MONOCHROME`), applied to the artwork, not the
  bezel. `AppRepository.icon` keys a 12 MB `LruCache` by package, `IconStyle` (mask, filter and
  the two colors, zeroed when the filter doesn't use them) and size; the drawable loads on IO and
  renders on `Dispatchers.Default`. MainActivity remembers the loader per style, so rows reload only
  when the style changes.
- Debug and profiling builds log `XrossCosmetics` (`frame=GOLD_BEZEL mask=Y2K_CHROME_FRAME
  filter=DUOTONE gold=#E0B040`) and `XrossIcons` (`drew com.android.camera2 Y2K_CHROME_FRAME/DUOTONE
  144px in 0.7 ms`).

**Unit tests:** `FocusFrameIconMaskTest` (25): names and problems, defaults, each layer
overriding only what it names, an override with only cosmetics; the breathing pulse moves, the
three steady ones hold at 1 and aren't snapshot state; neon and gold colors; the filter matrices
(endpoints, alpha, luma) and a rendered duotone; masks pixel by pixel (corners, edges, circle vs
squircle, the whole shape), adaptive layer bounds, a padded legacy icon's plate, the chrome ring,
bounds restored; the cache (one load and render per key, every key part distinct, misses not
cached). 603 unit tests in all.

**Emulator (API 37, host GPU):** theme suite **98/98** on the debug build, with four new checks:
the three keys and `focusGold` hot-reload with the pack; 16 Apps icons drawn in the chrome frame,
duotoned; scrolling the list again draws none; removing the keys brings the halo and plain icons
back. Library suite **41/41**. By eye: the chrome bezel and navy-to-cyan duotone on the Apps list,
gold halos on glyphs and the gold bezel on a focused game tile. Profiling build, Apps list, 20 rows
down and back: **0.51% janky** with every icon drawn on the way (18 icons, 0.7 ms average, 1.0 ms
max, off the main thread) and **0.52%** from the cache (none drawn); p90 16–19 ms, as the wave
alone gives on this emulator.

**Not covered:** the Galaxy A55 and handhelds (120 Hz, a real app drawer, OEM legacy icons),
`STATIC_NEON` and `HARD_SHADOW` checked only in unit tests, the RA badge frames by eye, and a
Settings ▸ Theme row for these keys (2b.4f). App icons have no focus frame (as before).

<a id="s3.26"></a>

## 3.26 Phase 2b.4e: foreground particles (2026-11-13)

Layer 4 of the Theme 2.0 stack: a light particle layer over the wallpaper, hero art and snap and
under the menu's surfaces.

- **Manifest and cascade:** `effects.foregroundParticles` (alias `particles`): `NONE` (default),
  `SNOW`, `BOKEH`, `DUST_MOTES`, read ignoring case, `-` and spaces. It is a fourth field of the
  2b.4d `Cosmetics` bundle (`ParticlePreset`), so the base, variants, category and system
  overrides and rules carry it like the focus frame; `ThemeLook.cosmetics.particleField`.
- **Field (`ui/AmbientParticles.kt`, `ParticleField`):** flat `FloatArray`s for position, speed,
  size, alpha, drift, sway, frequency and phase; positions are fractions of the layer and speeds
  layer heights per second, horizontal motion divided by the aspect ratio. 40 flakes, 14 discs or
  28 motes, hard-capped at 48 whatever is asked. Snow falls 0.035–0.09 heights/s and sways on its
  own sine (nearer flakes bigger, brighter, faster); bokeh rises 0.006–0.02 heights/s with
  14–46 dp discs (nearer ones bigger, fainter and faster), drawn as a skirt, a flat disc and a
  brighter 1 dp rim in the accent; dust drifts every way, twinkles, and is brightest in a
  diagonal shaft from the top left. A particle that passes the margin (2% of the height, 16% for
  bokeh) re-enters at the opposite edge at a random spot. Steps are clamped to 50 ms.
- **Drawing:** a `withFrameNanos` loop steps the field once per display frame and bumps one
  `mutableLongStateOf` read only in `drawBehind`, inside a `graphicsLayer` with
  `CompositingStrategy.ModulateAlpha` (no offscreen buffer): each frame re-records that layer and
  never recomposes. A hide or show fades the layer 400 ms (instant under E-Ink); hidden, it
  leaves the composition.
- **Resting (`ParticleRest`):** hidden for `NONE`, a still-motion profile (`MONOCHROME_EINK`), a
  playing video snap, full-screen photos or video and the wave editor; resting when the activity
  is stopped or after 10 s without input (`ParticleRest.IDLE_AFTER_MS`, a second
  `InactivityTracker` fed by the same key, stick and touch events as the 3 s one; music doesn't
  keep particles going). Resting eases the field to a stop over 0.6 s (a smoothstep "gait") and
  then ends the loop: no frames until the next input, which eases them back over 0.6 s.
- Debug and profiling builds log `XrossParticles` (`state=RUNNING preset=SNOW n=40`, and `rest
  preset=SNOW n=40 settleFrames=34` when the loop ends).

**Unit tests:** `AmbientParticlesTest` (19): names, aliases and problems; the cascade through a
variant, a category override and a rule, leaving other cosmetics alone; the default pack has
none; counts and the 48 cap; start spread over the layer; same seed, same field; each preset's
direction and bokeh's depth scale; the sway's bounds; dust alpha under its peak; wrapping at the
bottom, top and sides; two minutes in portrait staying inside the margins; a 30 s gap stepping
50 ms; the hidden / resting / running table; resting easing to a stop then wanting no frames and
freezing positions and clock; resuming from rest with monotonically rising steps up to full
speed; the 10 s tracker. 622 unit tests in all.

**Emulator (API 37, host GPU, profiling build):** a `SNOW` pack (40 flakes), `BOKEH` and
`DUST_MOTES` checked by eye in landscape 2400×1080. Rest after 10 s without input, settled in 34
frames, then **0 frames** rendered over 5 s; the next key logs `RUNNING`; another app in front
logs `RESTING` and coming back `RUNNING`; under Monochrome E-Ink the layer isn't composed and the
app drew 1 frame in 4 s. Frame timing, interleaved passes of `NONE`, `SNOW`, `BOKEH` and
`DUST_MOTES` (8 s with only the particles moving, then 36 D-pad presses across categories and
lists): **0.15–0.42% janky** (deadline) in 22 of 24 windows, `NONE` 0.18–1.30%; two consecutive
windows in one round (dust 19.6%, snow 14.1%) dropped frames together and the next rounds were
clean. Per-frame `framestats` over 120 frames each, four interleaved pairs: UI thread (animation
to sync) **0.29–0.37 ms p50, ≤0.83 ms p90** with snow vs 0.23–0.56 / ≤0.79 ms without; recording
the draw 0.09 vs 0.08 ms; the render thread (~15 ms, vsync-bound on the emulator) and late frame
starts (up to 26–38 ms) the same with and without particles, so the burst came from the host.
Theme suite **102/102** on the debug build, with four new checks: the preset hot-reloads with
the pack (40 flakes running), they rest after 10 s, draw no frames while resting, and the next
key wakes them.

**Not covered:** the Galaxy A55 and handhelds (120 Hz pacing, Mali and Adreno), a playing video
snap hiding the layer (no scraped games on the emulator; unit-tested), and a Settings ▸ Theme row
for the preset (2b.4f).

<a id="s3.27"></a>

## 3.27 Phase 2b.4f: post-processing, Settings rows (2026-11-13)

The last slice of 2b.4: full-screen filters on both sides of the GL / Compose split, the Settings ▸
Theme rows for every 2b.4 effect, and the `effects` schema. 2b.4 is complete on the emulator.

- **Background filter (GL side):** `effects.backgroundFilter`: `NONE` (default), `SCANLINES`,
  `CRT` (scanlines, an RGB aperture grille, the tube's vignette), `LCD_GRID`. The wave's
  `GLSurfaceView` is its own surface under the window, so a Compose effect can't reach it; the
  filter is `assets/shaders/screen_filter.frag`, drawn after the background program as a second
  full-screen triangle with `glBlendFunc(GL_DST_COLOR, GL_ZERO)` (it can only darken). Compiled
  with the wave when the context is created (`ScreenFilterPass`), skipped over the `CRT` preset.
  Pitches are given in dp and snapped to whole render pixels through `uPxPerDp` = display density
  × the surface's render scale (set by `WaveBackground` on layout): lines 1.5 dp (≥ 2 px), stripes
  0.5 dp (≥ 1 px), LCD cells 3 dp (≥ 3 px), sampled at pixel centers so a 2 px pitch is one lit
  row and one dark. On the 2400×1080 emulator (2.625 dp, rendered at 1280×576) that is 1.40 px/dp:
  2 px lines, 1 px stripes, 4 px cells. The `CRT` preset now sizes its scanlines and grille the
  same way instead of a fixed 270 lines and 1 px stripes, which beat into moiré once upscaled.
- **UI filter (AGSL, Android 13+):** `effects.uiFilter` (alias `postProcess`): `SCANLINES_UI`,
  `BLOOM`, or `CUSTOM` from `postprocess.agsl` / `effects.uiFilterPath`, read at load like a
  custom shader (folder packs, ≤ 64 KB; `.xtheme` archives accept it at the root). Applied by
  `Modifier.uiFilter` (`ui/ScreenFilter.kt`) as a `RenderEffect` on the menu's root layer:
  scanlines are one `RuntimeShader`; bloom is a bright pass, a blur (8 dp) and a `PLUS` blend over
  the content. Below API 33 it is off (`UiFilterSupport.active`), with a note on the row. A
  `RenderEffect` snapshots its shader's uniforms, so the effect is rebuilt only for a new size or
  time; built-in filters have no time and are built once, so the layer re-renders only when the
  menu under it changes. A custom file that declares `uniform float uTime;` advances it at ≤ 30 fps
  only while the menu is in use (started, not idle, not E-Ink): it rests with the focus pulse.
- **Uniforms are never probed:** on the API 37 emulator, `RuntimeShader.setFloatUniform` with a
  name the shader doesn't declare aborts the process (JNI: the "unable to find uniform named"
  message is built from a freed string) instead of throwing. Xross reads which of `uResolution`
  (`float2`/`vec2`/`half2`), `uTime` and `uPxPerDp` (`float`/`half`) a file declares from its text
  with comments removed, and sets only those; `uniform shader content;` is required. AGSL compile
  errors still throw (`IllegalArgumentException`): the filter is off and `ShaderHealth.notice`
  toasts the compiler's first line once per file version.
- **Schema:** `ThemeManifest.Effects` gains `backgroundFilter`, `uiFilter`, `uiFilterPath` (alias
  `postProcessPath`); `Effects.KEYS` lists every property and alias, and `unknownKeys` reports any
  other key under the base, a variant, an override or a rule's `apply` with the pack's problems
  (a typo such as `shaderPrest` used to be dropped silently). Both filters ride the `Cosmetics`
  bundle, so variants, overrides and rules carry them.
- **Settings ▸ Theme rows:** after Battery alert tint: Background shader (Wave, Matrix, Bokeh,
  Starfield, CRT), Background filter (Off, Scanlines, CRT grille, LCD grid), UI filter
  (experimental: Off, Scanlines, Bloom), Focus frame style, Ambient particles, App icon mask
  (System, Squircle, Circle, Chrome). Confirm cycles "Theme pack (…)" (the pack's value for the
  chosen variant) then each choice; Options list them. Saved as `display.effects` in
  `settings.json` (`EffectOverrides`, names normalized, a typo or `CUSTOM` follows the pack) and
  applied over the pack's look in every context (`EffectOverrides.applyTo`); a user shader the
  pack never names joins its ahead-of-time library (`LoadedTheme.shaderLibrary(extra)`).
  Xross wallpaper: Confirm opens the system picker when none is set (Options: Pick custom…, Reset
  to theme); the picked document's read grant is persisted and released when replaced or reset.
  Reset to theme defaults (`ThemeDefaults.reset`) clears the six effects, the surface style, the
  three color layers and the Xross wallpaper, and keeps the pack, variant, theme color, quick
  profile and wave settings; its row says what it would clear.
- Debug and profiling builds log `XrossFilter` (`background=SCANLINES program=BOKEH pxPerDp=1.40
  linePx=2 …`), `XrossUiFilter` (`filter=BLOOM state=STATIC sdk=37`; states OFF, UNSUPPORTED,
  FAILED, STATIC, ANIMATING, RESTING) and `XrossEffects` (the resolved shader, filters, frame,
  particles and mask, and whether the user overrides any).

**Unit tests:** `PostProcessSettingsTest` (23): filter names and aliases, `uiFilterPath` implying
CUSTOM and staying in the pack, unknown names reported; the cascade through a variant, a category
override and a rule; `postprocess.agsl` read at load; unknown `effects` keys reported in the base,
a variant, a system override and a rule (aliases accepted), and every `Effects` field in `KEYS`;
pitch snapping at three densities; the filter and CRT sources; API 33 gating; static presets
never animating; a custom filter animating only while in use; uniform detection ignoring comments
and wrong types; the `content` check; the shipped AGSL; overrides winning in every category
(System over a pack's mask); the user shader in the library; normalization; the settings.json
round trip; row cycling and labels; the Settings ▸ Theme rows, their order and summaries; Reset to
theme defaults. Two older tests updated for the new rows and the wallpaper row's action. 645 unit
tests in all.

**Emulator (API 37, host GPU):** device totals 194 → 226. `run-all.sh`: library 41/41, theme
**134/134** (32 new checks), media 30/31, input 11/11, integration 9/9; the media miss was its
wallpaper-row check still expecting the old row position and wording, fixed and rerun **31/31**.
New theme checks: a pack's `LCD_GRID` hot-reloads (4 px cells); a `postprocess.agsl` reading
`uTime` animates, rests after 3 s, draws **0 frames** at rest, and a broken one toasts and turns
off; each Settings row cycles, saves to `display.effects` and changes the log; scanlines are 2 px
apart; Bloom is static and a resting menu under it draws **0 frames**; Pick custom opens the
picker, the picture is saved as a document URI, shows, and **still loads after a restart**; Reset
to theme defaults clears everything and the pack's own effects come back. Checked by eye: CRT
grille plus Bloom over the menu (screenshot).

**Frame pacing (`palette-perf.sh`, profiling build, game navigation, 3 pairs):** the first run
read 0.89–33% janky in both arms, worsening pass by pass; a rerun was 0.30–0.44% in 5 of 6 passes.
An A/B against the `HEAD` build before this slice on the same emulator gave 0.30–0.45% (p50 16
ms, GPU p50 13–14 ms) and a third run of this branch **0.30–0.45% in all six** (p50 16 ms, GPU
p50 14 ms): with no effects set, unchanged, within the 0–1.5% target. With
`backgroundFilter: CRT` and `uiFilter: BLOOM` (`PERF_EFFECTS`, 2 pairs): **0.30–0.44% janky**
(deadline), but p90 19–21 ms and legacy 7–30% while game-art colors morph, as Bloom re-blurs the
whole menu on each frame of the morph; flat (p90 17 ms) with adaptive colors off.
`palette-perf.sh` now resets `display.effects` unless `PERF_EFFECTS` names some.

**`./gradlew check`:** unit tests and `validateShaders` (7 shaders, now with
`screen_filter.frag`) pass; `lintDebug` fails on 10 errors that predate this slice (RestrictedApi
on `dispatchKeyEvent`, Media3 opt-ins in `MediaScreens.kt` and `SnapLayer.kt`,
`LifecycleCurrentStateInComposition`, `UnusedBoxWithConstraintsScope`, a byte-order mark in
`DatParser.kt`); no new lint findings in this slice's code.

**Not covered:** the Galaxy A55 and handhelds (Adreno, Mali, 120 Hz) for both filters; AGSL on a
real Android 13–16 device (only API 37 here) and the picker on a phone; Bloom's GPU time on a slow
GPU (each change renders the menu offscreen and blurs it). The A55 is covered in [§3.28](#s3.28).

<a id="s3.28"></a>

## 3.28 Phase 2b.4 on the Galaxy A55 (2026-11-13)

All of 2b.4 on the user's Galaxy A55 (Android 17 / API 37, Xclipse 530, 1080×2340 at 120 Hz,
450 dpi), on `main` at `41f64dc`. The phone's ROMs, app data and `Xross/config` were backed up
first and restored after; the RetroArch stub was installed for the launch checks and removed.

**Host:** `./gradlew check`: **645 unit tests** pass (4 skipped), `validateShaders` passes,
`lintDebug` **0 errors** (61 warnings; the 10 older errors were fixed in `5281d15`).

**Device suites (`run-all.sh`, debug build): 215/226.** Library 41/41, theme **134/134**, input
11/11, integration 9/9, media 20/31. The 11 media failures are the Photo and Music listing checks,
which expect the seeded `XrossSmoke` files to be the only media (the phone has its own photos and
music). Every 2b.4 check passed, including the AGSL UI filters (a custom `postprocess.agsl`
animating, resting with 0 frames and failing safely), Pick custom wallpaper through the phone's
picker, Set as Xross wallpaper from a photo, and the music driving `uAudioBass` / `uAudioBeat`.

**By hand, on the user's library (370 arcade games with scraped art and 260 video snaps):**

| Slice | Result |
|---|---|
| 2b.4a presets | All linked at pack load; watchdog GPU p90 (timer query, GPU at its 450 MHz floor) BOKEH 2.90 ms, STARFIELD 3.00, ASCII_MATRIX 1.32, CRT 1.25 (limit 10 ms), unchanged by a background filter |
| 2b.4b audio | The user's songs read bass 0.96–1.00 and 1–3 beats per 2 s; the analysis ran **369–406 ms** ahead of the phone's speaker (475–507 ms on the emulator) and was compensated. A quiet intro logs nothing until the bass moves, as designed. Sync not judged by eye; Bluetooth not tried |
| 2b.4c wallpaper | A 3220×4830 (15.5 MP) photo set from the viewer decodes to 1610×2415, opaque, full-bleed; pre-blurred copy (192×288) in 165 ms warm, 192 ms after a cold start, off the main thread; the GL loop stops. Graphics memory +46 MB with it set after a cold start (83 → 128 MB); the viewer's full-size image is released on leaving Photo |
| 2b.4d icons | Real third-party icons with `Y2K_CHROME_FRAME` drew in 3–11 ms each (one 24 ms outlier; 68 ms on the first run after install), `SQUIRCLE` 2–4 ms, `CIRCLE` 4 ms, all on `Dispatchers.Default`. Chrome bezel, `GOLD_BEZEL` on the focused glyph and `STATIC_NEON` (game-art tint on a game tile) checked by eye |
| 2b.4e particles | `SNOW`, `BOKEH` and `DUST_MOTES` run at 120 Hz (~620 frames in 5 s, 0–0.16% janky even on the debug build), settle in 74 frames (0.6 s), draw **0 frames** at rest and wake on the next key. A playing video snap hides them (`state=HIDDEN`), which had only been unit-tested |
| 2b.4f filters | `pxPerDp` = 1.54 on this panel: 2 px scanlines, 1 px stripes, 5 px LCD cells; the CRT grille is even with no moiré; the `CRT` preset skips the filter pass. `SCANLINES_UI` and `BLOOM` (AGSL) compile and run on real API 37 hardware, are built once (`STATIC`), and a resting menu under them draws 0 frames |

**Frame pacing (`palette-perf.sh`, profiling build, 12 seeded SNES games, game-art colors on/off):**
no effects **0.12–0.19% janky** (deadline) in all 6 passes, p50 12 ms, GPU p50 6–7 ms;
`backgroundFilter: CRT` + `uiFilter: BLOOM` **0.06–0.19%** in 4 passes (p50 11–14 ms; legacy jank
12–52% while colors morph, as Bloom re-blurs the menu each morph frame).

**Particles during game navigation (open finding):** with `foregroundParticles: SNOW` (alone or
with `BOKEH`), 7 of 16 passes ran in a slow mode: render thread ~16 ms per frame instead of ~11,
`dequeueBuffer` waits ~3.6–4.7 ms, gfxinfo GPU p50 9 ms instead of 6, p50 18–19 ms, legacy jank
80–99%. The other 9 particle passes and all 16 passes without particles (none, BOKEH alone, CRT +
Bloom) were normal. Deadline jank stayed 0–0.19% in every pass, so no frames visibly drop; the cost
is latency and power. A time-boxed look found no app-side cause: the UI thread is 2.5–3.4 ms in
both modes (no recomposition: one `mutableLongStateOf` read in `drawBehind`), the draw is ≤40
circles with no per-frame allocation for snow (Bokeh allocates one `Stroke` per frame), and the
mode is per launch, not per preset. It is not thermal (AP flat at 40 °C) or the compositor: in a
slow and a normal pass SurfaceFlinger composited both Xross's window and the wave's `SurfaceView`
as `DEVICE` layers, and `gpu_busy` read 39–46% in both. The leading guess is the phase between the
120 Hz UI frames and the wave's 60 Hz GL frames (`PACED stride=2`) on the shared GPU queue, which
a Perfetto GPU-queue trace would confirm.

**Not covered:** sync of the audio uniforms judged by eye, Bluetooth output latency, E-Ink with a
pack shader, `HARD_SHADOW` and the RA badge frames by eye, handhelds (Adreno, Mali).
