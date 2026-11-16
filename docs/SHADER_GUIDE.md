# Background shader guide

How Xross draws its background, how to write a shader for it (a built-in preset or a pack's
`shader.frag`), the two optional extra passes (feedback and the field pass), the frame budget, and
the `BLACK_HOLE` preset as a worked example with every value that shapes its look.

Related documents: the [theme specification](THEME_SPECIFICATION.md) (§6 the wave and its
settings, §8 `shaderPreset` / `shaderPath` and the rest of the `effects` keys),
[`DEVELOPMENT.md`](DEVELOPMENT.md) (builds and tests), [`STATUS.md`](STATUS.md) (measurements:
§3.23 audio, §3.39 the black hole on the Galaxy A55).

## Contents

1. [How the background is drawn](#1-how-the-background-is-drawn)
2. [Writing a shader](#2-writing-a-shader)
3. [Feedback: last frame's picture](#3-feedback-last-frames-picture)
4. [The field pass: work per block of pixels](#4-the-field-pass-work-per-block-of-pixels)
5. [The frame budget](#5-the-frame-budget)
6. [Testing a shader](#6-testing-a-shader)
7. [`BLACK_HOLE`, a worked example](#7-black_hole-a-worked-example)

## 1. How the background is drawn

The background is a `GLSurfaceView` under the Compose menu, drawn by
[`WaveRenderer`](../app/src/main/java/dev/xross/launcher/ui/WaveRenderer.kt). Each frame it draws
one full-screen triangle with the active fragment shader, then the background filter
(`effects.backgroundFilter`, §8 of the specification) multiplied over it.

* **Render size:** below the display's, keeping the long side near 1280 px
  (`WaveRenderer.renderScale`), then scaled up. `uResolution` is the render size, not the
  screen's. On the A55 that is 1280×576.
* **Frame rate:** 60 fps while in use (paced on 120 Hz panels), about 20 fps after 3 s without
  input, and nothing while the menu is covered or Xross is in the background.
* **Programs:** every preset a theme can show is compiled ahead of time, `WAVE` first. A program
  that fails to compile or link, or is too slow ([§5](#5-the-frame-budget)), is replaced by
  `WAVE` and the user gets a toast.

| Preset | File | Passes |
| --- | --- | --- |
| `WAVE` | `wave.frag` | one |
| `ASCII_MATRIX` | `ascii_matrix.frag` | one |
| `BOKEH` | `bokeh.frag` | one |
| `STARFIELD` | `starfield.frag` | one |
| `CRT` | `crt.frag` | one |
| `BLACK_HOLE` | `black_hole.frag` | field + full size, with feedback |

Built-in sources live in [`app/src/main/assets/shaders/`](../app/src/main/assets/shaders/).

## 2. Writing a shader

A shader is a GLSL ES 3.0 fragment shader. Xross puts
[`prelude.glsl`](../app/src/main/assets/shaders/prelude.glsl) in front of it (unless the file
starts with its own `#version`), so the file starts straight at its constants and functions and
writes `fragColor`. Compile errors are reported with line numbers of your file, not the prelude.

| Uniform | Type | What it holds |
| --- | --- | --- |
| `uTime` | `float` | seconds of animation, scaled by the wave speed; wraps at 10 000 |
| `uResolution` | `vec2` | render size in pixels |
| `uColorTop`, `uColorBottom` | `vec4` | the canvas gradient (theme palette) |
| `uAccent`, `uGlow` | `vec4` | wave / ribbon color and glow color |
| `uBatteryLevel` | `float` | 0 empty … 1 full (1 where unknown) |
| `uAudioBass`, `uAudioBeat` | `float` | bass energy of Xross's own music 0 … 1; 1 for ~100 ms on a beat |
| `uTransition` | `float` | 1 right after a category switch, easing to 0 in about a second; the cold-start `SHADER` intro ramps it 0 → 1 |
| `uPrev`, `uFrameDt`, `uFresh` | | feedback, [§3](#3-feedback-last-frames-picture) |
| `uField`, `uPass` | | the field pass, [§4](#4-the-field-pass-work-per-block-of-pixels) |

Conventions the built-ins follow:

* Work in `uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y`, so shapes keep their
  proportions on any aspect ratio. Check portrait too.
* Keep it dark behind the menu: the canvas gradient times about 0.1–0.6, a vignette, and the
  brightest content away from the left third where the categories sit.
* Add `(hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0` at the end; soft gradients band
  without it.
* Use `uTime` for motion, never a frame counter; frames are paced and drop to 20 fps at rest.

## 3. Feedback: last frame's picture

A shader that reads `uniform sampler2D uPrev` gets the frame it drew last time. It opts in by using
it: an unused uniform has no location, and the renderer only allocates the buffers for a program
that has one. Every other shader is unaffected.

How it works: two RGBA8 textures at the render size take turns
([`FeedbackTargets`](../app/src/main/java/dev/xross/launcher/ui/GlShaders.kt)). The shader draws
into one with the other bound as `uPrev`, then the result is blitted to the screen and the roles
swap. The background filter is drawn after the blit, so it never builds up in the history.

| Uniform | Meaning |
| --- | --- |
| `uPrev` | last frame's output, linear and clamped; sample at `gl_FragCoord.xy / uResolution` |
| `uFrameDt` | seconds since the last frame, at most 0.1 |
| `uFresh` | 1 when `uPrev` holds no usable history, else 0 |

Rules for using it:

* **Fade per second, not per frame:** `prev * pow(keep, uFrameDt * 30.0)` keeps the same trail
  at 60 and at 20 fps.
* **Ignore `uPrev` where `uFresh` is 1.** The history is dropped
  ([`FeedbackPolicy`](../app/src/main/java/dev/xross/launcher/ui/GlShaders.kt)) on the first frame,
  on a program switch, a resize, a lost GL context, after a gap over 0.5 s, and in still (E-Ink)
  mode, where every frame is drawn on its own.
* **Subtract a little when fading in RGBA8:** `prev * k - 1.5 / 255.0`. Without it, small values
  round back up and a trail never quite reaches black.
* **Keep any gain below 1.** A glow made from a blur of `uPrev` is drawn again into the next
  `uPrev`; with `col += blur(prev) * g` the picture settles at `1 / (1 - g)` of its own
  brightness. `g` near 0.25 spreads into a soft bloom over a few frames.

It costs two textures (about 6 MB at 1280×576) and one blit per frame. On the A55 the black hole
measured the same with and without it (§3.39).

## 4. The field pass: work per block of pixels

A shader that reads `uniform sampler2D uField` is drawn twice per frame:

1. **`uPass` = 0** into an RGBA8 texture of `ceil(uResolution / 4)` texels
   ([`FieldPolicy.size`](../app/src/main/java/dev/xross/launcher/ui/GlShaders.kt)).
   `gl_FragCoord` is then a texel. Write whatever the second pass needs, packed into 0 … 1.
2. **`uPass` = 1** at full size, with that texture on `uField`. `texelFetch` reads one texel
   exactly; `texture()` blends neighbours bilinearly, which gives cheap blurs.

`uResolution` is the full render size in both passes. A pass-0 fragment beyond the area it needs
should return early: the texture always covers at least one texel per 4×4 pixels, and usually
more than the shader uses.

Use it when many pixels compute the same expensive value. The black hole traces its light rays
once per 6×8-pixel character cell instead of once per pixel; that alone took it from 12 ms to
5.4 ms a frame on the A55.

`main()` then branches on the pass:

```glsl
void main() {
    if (uPass == 0) {
        fragColor = fieldPass(floor(gl_FragCoord.xy), uTime);   // one texel per cell
        return;
    }
    vec4 f = texelFetch(uField, ivec2(floor(gl_FragCoord.xy / cell)), 0);
    // ... draw this pixel from f
}
```

Still frames draw both passes. A shader may use the field pass, feedback, both or neither.

## 5. The frame budget

The renderer times a new non-`WAVE` program for its first ~2 s on screen
([`FrameBudgetWatchdog`](../theme/engine/src/main/kotlin/dev/xross/launcher/theme/shader/FrameBudgetWatchdog.kt)), with
GPU timer queries where the driver has them (the A55 does) and `glFinish` samples elsewhere. If the
90th percentile is over **60% of a frame**, 10 ms at 60 fps, the program is retired for the
session and `WAVE` draws instead. The time covers every pass and the feedback blit.

The limit leaves the other 40% for the menu itself (hero art, frosted panels, particles), which
draws on the same GPU in the same frame; past it, the menu stutters. It also guards against a
pack's shader making the launcher unusable. The check runs while the A55's GPU is still at its
lowest clock (450 MHz), so it is on the strict side.

Reference costs on the Galaxy A55, profiling build, watchdog p90 (§3.39):

| Program | GPU per frame |
| --- | --- |
| `STARFIELD` | 3.0 ms |
| `BLACK_HOLE` (field pass, 4 rays per cell, bloom) | 6.1–6.3 ms |
| `BLACK_HOLE` traced per pixel (before the field pass) | 12.0 ms, retired |

## 6. Testing a shader

* **Compile check:** `./gradlew :app:validateShaders` runs `glslangValidator` over the prelude plus
  every built-in (part of `./gradlew check`). For a pack's file:
  `cat app/src/main/assets/shaders/prelude.glsl shader.frag > /tmp/x.frag && glslangValidator -S frag /tmp/x.frag`.
* **Pick it:** Settings ▸ Theme ▸ Background shader, or `display.effects.shaderPreset` in
  `Xross/config/settings.json` (picked up on the next start).
* **Look:** the emulator must run with `-gpu host`; its default software GL renders shaders far too
  slowly to judge anything.
* **Cost:** only on a real device and the `profiling` build. It logs under `XrossShader`:

  ```
  watchdog passed BLACK_HOLE: p90 6.19 ms of 10.00 ms (timer query, 123 samples)
  watchdog tripped BLACK_HOLE: p90 12.1 ms > 10.0 ms (timer query, 110 samples)
  ```

  `WaveFrames` lines give the GL thread's frame pacing every 300 frames. Keep the menu active
  while measuring (Xross drops to 20 fps after 3 s without input): press up and down in a list,
  never Confirm. Run at least three rounds; single runs vary.
* **Unit tests:** `ShaderHostTest` checks that every preset ships a source and that the prelude
  declares the public uniforms; `FeedbackTest` covers the feedback and field-pass rules and which
  shaders use them.

## 7. `BLACK_HOLE`, a worked example

A Gargantua-style black hole (Interstellar) ray traced through curved space and drawn only with
letters and symbols. It is original work: the look comes from the physics and from the techniques
below, nothing is taken from another artwork. The physics follows James, von Tunzelmann, Franklin and
Thorne, *Gravitational lensing by spinning black holes in astrophysics, and in the movie
Interstellar* (Class. Quantum Grav. 32, 2015).

### 7.1 What it computes

Units G = c = M = 1: the horizon is at r = 2, the photon sphere at r = 3, and the shadow's edge at
impact parameter b = 3√3 ≈ 5.196.

1. **Light rays.** Four per character cell, traced backwards from the camera. Light in the
   Schwarzschild metric obeys `u'' + u = 3Mu²` (u = 1/r); in Cartesian form that is the force
   `a = -3 M h² x / r⁵` with `h = |x × v|` conserved, stepped with a symplectic Euler step of
   `STEP · r`.
2. **The disk.** Thin, in the y = 0 plane, from Gargantua's innermost stable orbit (Kerr spin
   a = 0.6 M: r = 3.83 M) out to `ROUT`. Gas on Keplerian orbits (Ω = r^-1.5), thin-disk flux
   F ∝ r⁻³ (1 − √(r_in/r)), temperature T ∝ F^¼. Its texture is value noise on (orbit phase, log r),
   carried round at each radius's own rate, so differential rotation shears it into streaks.
3. **Redshift.** Each hit is shifted by g = √(1 − 3/r) / (1 − Ω L), gravity and Doppler together
   (L is the photon's angular momentum about the disk axis): observed T' = gT, intensity g⁴I.
   The film removed the Doppler side-to-side difference; `BEAMING` keeps part of it.
4. **The sky.** Rays that escape read a starfield and a clumpy dust band in their final direction, so the
   sky is lensed too. Rays near b = 3√3 add a soft halo of glyphs round the shadow: the photon ring.
5. **Glyphs and colour.** Brightness indexes a 15-glyph ramp ordered by lit dots
   (`. , - ; : + x = * o % & 8 # @`). Colour is blackbody: each stop of `heatColor()` is a Planck
   spectrum at 1700–7500 K through the CIE 1931 observer.
6. **Hot spots.** Three blobs of plasma (the orbiting hot-spot model of Broderick & Loeb 2006, used
   for Sgr A* flares) flare up at random radii, orbit, shear into arcs and fade; lensing carries
   them over the shadow.

The light bending is Schwarzschild (no spin); only the disk's inner edge uses Gargantua's spin,
which changes little at a = 0.6.

### 7.2 Passes

| Pass | Size | Does |
| --- | --- | --- |
| field (`uPass` 0) | one texel per 6×8 cell | traces 4 rays, tone-maps, adds the ring; packs r = glyph level, g = heat / 1.6, b = flow / 3 (wrapped), a = star brightness × 0.9, or 1 for dust |
| full size (`uPass` 1) | render size | draws the cell's glyph dot, the wide bloom (5 bilinear field taps), the vignette, then the trail and tight glow from `uPrev`, and a soft highlight shoulder |

### 7.3 Values that shape the look

All in [`black_hole.frag`](../app/src/main/assets/shaders/black_hole.frag).

**Size and framing**

| Value | Now | Effect |
| --- | --- | --- |
| `FOCAL` | 1.75 | zoom: higher makes the hole and the disk bigger together |
| `ROUT` | 27.0 | outer edge of the disk, in M: lower makes the hole bigger relative to the disk |
| `CAM_R` | 60.0 | camera distance; mostly leave it and use `FOCAL` |
| `ROLL` | 0.27 | camera roll in radians (~15°): the disk's diagonal lean; 0 is level |
| `el` in `camera()` | 7.5° ± 3.5° | how far above the disk plane the camera sits, and its slow sway; higher opens the disk up |
| `az` in `camera()` | 0.011 rad/s | the slow drift round the hole (moves the lensed stars) |

**Glyphs**

| Value | Now | Effect |
| --- | --- | --- |
| `ROWS` | 72.0 | rows of characters; cells are never smaller than 6×8 render pixels |
| `G[]` | 15 glyphs | the 5×7 bitmaps, one 5-bit row each, sparsest first |
| ramp index in `main()` | `level^0.8 · 15`, ±1.1 dither | how brightness maps onto the ramp, and how much neighbouring cells vary |

**Brightness and colour**

| Value | Now | Effect |
| --- | --- | --- |
| `exposure` in `fieldPass()` | 2.9 | overall brightness of the disk before glyphs are picked |
| `BEAMING` | 0.45 | how much brighter and bluer the approaching side is (1 physical, 0 as in the film) |
| `heatColor()` `c0`…`c5` | 1700 K → 5200 K → 7500 K | the blackbody colour ramp |
| shoulder in `main()` | 0.65, peak 0.95 | where highlights start to roll off and their ceiling |
| `diskEmission()` `edge` | fades over 0.5 M inside, 9 M outside | how softly the disk starts and ends |

**Glow**

| Value | Now | Effect |
| --- | --- | --- |
| `BLOOM` | 0.5 | strength of the wide bloom from the field |
| `BLOOM_SPREAD` | 0.9 | its reach in cells (each tap blends 2×2 more) |
| `cellLight` hue in `wideBloom()` | `heat · 0.7` | how much deeper orange the bloom is than the letters |
| `GLOW` | 0.24 | tight glow from a blur of last frame; keep it below ~0.4 |
| `PERSIST` | 0.04 s | half-life of last frame's light; higher gives longer afterglow |

**Motion**

| Value | Now | Effect |
| --- | --- | --- |
| `SPIN` | 7.5 | orbital time per second: how fast the gas streaks turn |
| `FLOW_PERIOD` | 18.0 | how often the streak texture re-seeds so the shear never winds it up |
| `gasTexture()` | 7 / 16 / 2 cells round, 10 / 23 / 48 per unit log r | streak size: the last octave is the thin ringlets |
| `SPOT_LIFE` | 8 s | a hot spot's life from flare-up to fade (radius 8–20 M, picked at random each life) |
| `SPOT_RATE` | 2.4 | hot spots' orbital clock relative to `SPIN` (still Keplerian, so they shear) |
| `glint` | 0.9 rad/s | the light chasing round the photon ring |
| `pulse` | 6 · u(1 − u), width 0.09 in log r | the wave of gas a category switch sends spiralling in from the rim |
| `lit` in `fieldPass()` | 0.5 → 6.5 s | how fast the disk ignites from its inner edge outward after a cold start (the photon ring fades in over 0.5 → 3.5 s) |
| audio | `0.35 · uAudioBass`, `0.12 · uAudioBeat` | how much music brightens it |

**Accuracy and cost**

| Value | Now | Effect |
| --- | --- | --- |
| rays per cell | 4 | smoothness of edges; each halving saves about 1.5–2 ms on the A55 |
| `STEP` | 0.06 | step length per unit radius; smaller bends light more exactly and costs more |
| `STEPS` | 220 | the cap per ray; only rays skimming the photon sphere reach it |

Re-check the A55 timing ([§6](#6-testing-a-shader)) after raising rays, steps, `ROWS` or the bloom
taps; on 2026-11-16 the shader used 6.1–6.3 ms of the 10 ms budget.
