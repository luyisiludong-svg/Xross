# Phase 2b.4 Implementation Prompts (Slices 2b.4a – 2b.4f)

This document contains the step-by-step prompts for implementing **Phase 2b.4 (Visual Effects, Shaders & Materials)** of the Xross Launcher (`dev.xross.launcher`).

Each slice is self-contained, test-verified, and structured to prevent regressions, GPU budget blowouts, and launcher crash loops.

---

## Overview & Order of Work

| Slice | Title | Target Scope | Risk |
|---|---|---|:---:|
| **2b.4a** | Shader Host & Watchdog | Multi-program GL host, public uniforms, ahead-of-time compilation, crash-loop marker, watchdog | High |
| **2b.4b** | Audio-Reactive Uniforms | `TeeAudioProcessor` in ExoPlayer, latency compensation ring buffer, `uAudioBass` & `uAudioBeat` | Medium |
| **2b.4c** | Backdrop & Pre-Blurred Slices | `wallpaper.png`, "Set as Xross wallpaper", decode-time pre-blurred slices for frosted surfaces | Low |
| **2b.4d** | Draw-Phase Cosmetics | Focus frames (`STATIC_NEON`, `HARD_SHADOW`, `GOLD_BEZEL`), icon masks, duotone `ColorMatrix` cache | Low |
| **2b.4e** | Foreground Ambient Particles | Layer 4 Compose particle canvas (≤48 particles), idle/snap/E-Ink resting states | Medium |
| **2b.4f** | Post-Processing & Settings | GL-side scanlines/CRT, experimental AGSL UI filter, Settings ▸ Theme rows, device tests | Medium |

---

## Slice 2b.4a: Shader Host & Watchdog

```markdown
### Task: Implement Phase 2b.4a — Shader Host & Watchdog

#### Context & Objectives
We are starting Phase 2b.4 (Visual Effects, Shaders & Materials) on branch `feat/theme-2b4` (branched from `feat/theme-2b3`).
In this first slice, turn `ui/WaveRenderer.kt` into a robust multi-program GL host that supports shader presets and user custom shaders with complete crash and budget protection.

#### Requirements
1. **Multi-Program GL Host (`ui/WaveRenderer.kt`):**
   - Keep the built-in `WAVE` shader permanently compiled and linked as the immutable fallback program.
   - Refactor `WAVE` to use the standard public uniform names:
     - `uniform float uTime;`
     - `uniform vec2 uResolution;`
     - `uniform vec4 uColorTop;`
     - `uniform vec4 uColorBottom;`
     - `uniform vec4 uAccent;`
     - `uniform float uBatteryLevel;` (0.0 to 1.0, bound from BatteryMonitor)
     - `uniform float uAudioBass;` (defaults to 0.0 for now)
     - `uniform float uAudioBeat;` (defaults to 0.0 for now)
   - Add built-in fragment shader presets:
     - `ASCII_MATRIX` (procedural character matrix rendered in fragment shader, tinted by palette)
     - `BOKEH` (drifting orbs with depth of field)
     - `STARFIELD` (warp dust reacting to category switches)
     - `CRT` (scanlines, aperture grille, phosphor bloom)
     - `CUSTOM` (loads `shader.frag` from theme pack directory or `effects.shaderPath`)

2. **Ahead-of-Time (AOT) GL Compilation:**
   - When a theme pack loads, collect every preset used by its variants, overrides, and `rules.json`.
   - Compile and link them ahead-of-time on the GL thread using `queueEvent`.
   - Keep rendering the active program until the new one is confirmed linked, ensuring category switches never hitch or drop frames.

3. **Error Handling & Hot Reload:**
   - Replace any `check()` or unhandled runtime exceptions with a state/flow (`ShaderStatus.Ready`, `ShaderStatus.Compiling`, `ShaderStatus.Failed(error)`).
   - On compile or link failure, fall back to `WAVE`, log the driver's exact error line, and post a user-facing toast.
   - Support live hot-reloading when an on-disk `shader.frag` is edited.

4. **Frame-Budget Watchdog:**
   - Measure frame duration during the first ~2 seconds after loading a custom shader:
     - Use `GL_EXT_disjoint_timer_query` where supported by the driver; otherwise fallback to sampled `glFinish`.
     - If the 90th percentile (p90) exceeds 60% of the active frame budget (e.g. >10 ms on a 60 Hz / 16.6 ms budget), trip the watchdog and fall back to `WAVE`.
   - Keep the budget evaluation logic in a pure, unit-testable class.

5. **Launcher Crash-Loop Prevention:**
   - A GPU-panicking shader could crash the process, causing Android to immediately restart the home launcher into the same crash loop.
   - Before the first draw of an untrusted custom shader, write a marker file (e.g. `/sdcard/Xross/cache/shader_canary.tmp`).
   - Clear the marker file after 120 consecutive successfully rendered frames.
   - At cold boot: if the marker file is still present, bypass the custom shader, reset to `WAVE`, and notify the user.

6. **Gradle & Unit Tests:**
   - Add a Gradle verification task running `glslangValidator` over the built-in GLSL presets.
   - Add unit tests for shader manifest decoding, watchdog decision logic, and fallback states.
   - Verify that all existing unit tests and `tools/device-tests/smoke.sh` pass.
```

---

## Slice 2b.4b: Audio-Reactive Uniforms

```markdown
### Task: Implement Phase 2b.4b — Audio-Reactive Uniforms (Zero Permission)

#### Context & Objectives
We are implementing Slice 2b.4b on branch `feat/theme-2b4`.
We need to drive the background shaders with real-time music energy (`uAudioBass` and `uAudioBeat`) from Xross's built-in ExoPlayer music player without requesting Android's `RECORD_AUDIO` permission.

#### Requirements
1. **ExoPlayer AudioSink Tee Processor:**
   - Implement a custom `TeeAudioProcessor` (or `BaseAudioProcessor`) plugged into `MusicPlayer`'s ExoPlayer audio sink.
   - Process incoming 16-bit PCM stereo audio buffers directly off the audio playback thread without copying allocations.

2. **Energy Analysis (Zero-FFT):**
   - Apply a lightweight ~150 Hz low-pass biquad/IIR filter to isolate bass energy.
   - Compute normalized bass energy (`uAudioBass`: 0.0 to 1.0) with an exponential decay falloff (~200 ms).
   - Detect beat pulses (`uAudioBeat`: 0.0 or 1.0) by comparing instantaneous bass energy against a sliding window average.

3. **AudioTrack Latency Compensation (Crucial):**
   - Raw audio samples in the processor lead the physical speakers by 100–250 ms due to the Android `AudioTrack` mixer buffer.
   - Push computed `(timestampMs, bass, beat)` entries into a circular ring buffer.
   - When the GL thread binds uniforms during `onDrawFrame()`, query the ring buffer using `exoPlayer.currentPosition` rather than the newest sample. This guarantees the shader pulses in exact synchronization with what the user hears.
   - When no track is playing or the player is paused, bind `uAudioBass = 0.0f` and `uAudioBeat = 0.0f`.

4. **Verification:**
   - Add unit tests verifying the low-pass filter math, ring buffer interpolation, and silence/pause state transitions.
   - Verify that `tools/device-tests/test-media.sh` and `tools/device-tests/smoke.sh` continue to pass.
```

---

## Slice 2b.4c: Backdrop Image & Pre-Blurred Slices

```markdown
### Task: Implement Phase 2b.4c — Backdrop Images & Pre-Blurred Slices

#### Context & Objectives
We are implementing Slice 2b.4c on branch `feat/theme-2b4`.
We need to support static wallpapers, provide a "Set as Xross wallpaper" feature, and implement frosted glass blur without incurring real-time GPU penalty.

#### Requirements
1. **Wallpaper Support (Layer 3):**
   - Support `wallpaper.png` inside theme packs (and custom paths via `effects.wallpaperPath`).
   - In Photo Viewer options menu: add **"Set as Xross wallpaper"** (saving the path to `settings.json` under `display.customWallpaper`, separate from Android's system wallpaper).
   - If an opaque wallpaper is active and covers the viewport, trigger `backdropCovers` to suspend the GL shader loop, saving battery.

2. **Pre-Blurred Backdrop Slice for Frosted Glass (`backdropBlurDp`):**
   - Do NOT run expensive live per-frame `RenderEffect` blurs across composited layers.
   - When hero art or a wallpaper is decoded, generate a downscaled pre-blurred copy (~192 px width with a fast Gaussian/box blur) in the background.
   - In `Modifier.xrossSurface()`, when `SurfaceSpec.backdropBlurDp > 0`:
     - If rendered over art or wallpaper, sample and draw the aligned, clipped slice of the pre-blurred bitmap with the surface's tint and border.
     - This delivers frosted glass at zero per-frame cost and works seamlessly across Android 9 through Android 15+.
   - If rendered over the live GL wave/shader, retain the existing layered translucent tint.

3. **Verification:**
   - Add unit tests for wallpaper settings serialization and pre-blurred bitmap cache eviction.
   - Verify with `tools/device-tests/test-theme.sh` that theme pack loading and wallpaper switching work smoothly.
```

---

## Slice 2b.4d: Draw-Phase Cosmetics (Focus Frames & Icon Masks)

```markdown
### Task: Implement Phase 2b.4d — Focus Frames & App Icon Masks

#### Context & Objectives
We are implementing Slice 2b.4d on branch `feat/theme-2b4`.
We need to implement focus frame styles for focused tiles and badges, and add app icon masking with duotone filtering for the Apps category.

#### Requirements
1. **Focus Frame Styles (`effects.focusFrameStyle`):**
   - Add enum and implementations in `FocusHalo.kt` / `GameTile.kt`:
     - `BREATHING_HALO` (default: pulsing glow)
     - `STATIC_NEON` (crisp, high-intensity static glow, 0% pulse animation)
     - `HARD_SHADOW` (solid 2 dp offset shadow, 0 dp blur radius)
     - `GOLD_BEZEL` (metallic frame around focused game tiles and RetroAchievements badges using `palette.focusGold`)
   - Ensure all styles except `BREATHING_HALO` produce zero draw-phase animations when resting.

2. **App Icon Masks (`effects.iconMask`):**
   - In `AppRepository`: support masking installed application icons:
     - `NONE` (original icon)
     - `SQUIRCLE` (continuous curvature rounded rect)
     - `CIRCLE`
     - `Y2K_CHROME_FRAME` (glossy metallic border plate)
   - Extract `AdaptiveIconDrawable` foreground/background layers on API 26+; pad legacy icons onto a matching plate.

3. **Icon Duotone / Monochrome Filters (`effects.iconFilter`):**
   - Support `NONE`, `DUOTONE`, `MONOCHROME`.
   - Apply via a fast `ColorMatrix` mapped to `palette.accent` / `palette.canvas`.
   - **Performance Invariant:** Cache generated bitmaps by `(packageName, iconMask, iconFilter, sizePx)`. Navigating the Apps list must remain at 60/120 fps without frame drops.

4. **Verification:**
   - Add unit tests for `AppRepository` icon cache and mask clipping.
   - Verify with `tools/device-tests/test-library.sh` and `test-theme.sh`.
```

---

## Slice 2b.4e: Foreground Ambient Particles

```markdown
### Task: Implement Phase 2b.4e — Foreground Ambient Particles (Layer 4)

#### Context & Objectives
We are implementing Slice 2b.4e on branch `feat/theme-2b4`.
We need to implement a lightweight ambient particle layer rendered above hero art and wallpapers, while strictly obeying idle resting and battery conservation rules.

#### Requirements
1. **Particle System Canvas (Layer 4):**
   - Add `AmbientParticles.kt` composable positioned between backdrop art (Layer 3) and surface cards (Layer 5).
   - Presets via `effects.foregroundParticles`:
     - `NONE` (disabled)
     - `SNOW` (gentle downward drift with subtle horizontal sine wobble)
     - `BOKEH` (slow floating out-of-focus translucent circles with depth scale)
     - `DUST_MOTES` (subtle floating ambient particles caught in light)
   - **Hard Cap:** Maximum 48 particles.

2. **High-Efficiency Draw-Phase Implementation:**
   - Store particle state in contiguous flat primitive arrays (`FloatArray` for x, y, speed, size, alpha).
   - Update state inside a `withFrameNanos` loop paced to match the display refresh rate.
   - Execute drawing strictly in `Modifier.drawBehind` / `Canvas` inside an isolated `graphicsLayer` to eliminate Compose recompositions.

3. **Lifecycle & Resting Rules (Critical):**
   - Automatically pause the particle loop when:
     - The launcher is in the background (activity stopped).
     - The launcher enters an idle state (>10 seconds without controller input).
     - A video snap is actively playing.
     - The active quick profile is `MONOCHROME_EINK`.
   - Resume smoothly upon the first controller/touch event.

4. **Verification:**
   - Measure frame pacing on the profiling build to ensure 0% jank contribution.
   - Add unit tests for particle state initialization, bounds wrapping, and idle pausing.
```

---

## Slice 2b.4f: Post-Processing, Settings UI & Full Suite Verification

```markdown
### Task: Implement Phase 2b.4f — Post-Processing, Settings UI & Final Integration

#### Context & Objectives
We are concluding Phase 2b.4 on branch `feat/theme-2b4`.
We need to finalize full-screen post-processing effects, expose new controls in Settings ▸ Theme, update manifest schema definitions, and pass the complete regression suite.

#### Requirements
1. **GL-Side Scanlines & CRT Simulation:**
   - Because `GLSurfaceView` is rendered on a separate hardware surface below the Compose UI, implement full-screen scanlines and CRT aperture grille directly inside the GL background fragment pipeline.
   - Ensure the shader accounts for screen resolution and display density (`renderScale`).

2. **Experimental AGSL UI Filter (Android 13+ / API 33+):**
   - For UI-level effects, provide an optional root `RenderEffect.createRuntimeShaderEffect`:
     - Presets: `SCANLINES_UI`, `BLOOM`, or custom `postprocess.agsl`.
     - Disable automatically on Android 12 and below.
     - Ensure the filter rests when UI is static.

3. **Settings ▸ Theme UI Additions:**
   - Expose the new controls under **Settings ▸ Theme**:
     - *Background shader:* Wave / Matrix / Bokeh / Starfield / CRT
     - *Focus frame style:* Breathing Halo / Static Neon / Hard Shadow / Gold Bezel
     - *Ambient particles:* Off / Snow / Bokeh / Dust Motes
     - *App icon mask:* System / Squircle / Circle / Chrome
     - *Wallpaper:* Reset to theme / Pick custom
   - Connect options to `settings.json` user overrides and ensure "Reset to theme defaults" works.

4. **Documentation & Theme Schema:**
   - Update `ThemeManifest.kt` to officially recognize all `effects` properties.
   - Update `Roadmap.md` marking 2b.4 as completed.
   - Document new properties in `README.md` (since moved to `docs/THEME_SPECIFICATION.md`).

5. **Full Regression Verification:**
   - Run `./gradlew check` and all unit tests.
   - Run `tools/device-tests/run-all.sh` (or `smoke.sh --all`) on the emulator to verify all device tests pass.
   - Run `tools/device-tests/palette-perf.sh` to confirm frame pacing remains within target.
```
