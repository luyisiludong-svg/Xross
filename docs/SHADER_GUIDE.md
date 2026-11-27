# Background shader guide

How Xross draws its background, how to write a shader for it (a built-in preset or a pack's
`shader.frag`), the two optional extra passes (feedback and the field pass), the frame budget, and
the `BLACK_HOLE` preset as a worked example with every value that shapes its look.

New to shaders? Start with the [shader tutorial](SHADER_TUTORIAL.md) and its starter template.

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
8. [Images and the focus](#8-images-and-the-focus)
9. [Video](#9-video)
10. [Scene shader layers](#10-scene-shader-layers)

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
| `MECHA` | `mecha.frag` | field + full size |

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
| `uFocus` | `vec2` | the focused item's centre, 0…1 in `gl_FragCoord.xy / uResolution` space (y up), eased; [§8](#8-images-and-the-focus) |
| `uImage0`…`uImage3`, `uImageSize0`…`uImageSize3` | | the pack's images, `effects.shaderImages`; [§8](#8-images-and-the-focus) |
| `uVideo`, `uVideoSize`, `uVideoRect` | | the look's background video, `effects.videoPath`; [§9](#9-video) |
| `uBelow` | `sampler2D` | in a scene's shader layer, the layers below it; [§10](#10-scene-shader-layers) |

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
* **Look on the desktop:** [`tools/shader-preview/`](../tools/shader-preview/index.html) runs a
  shader in the browser the way `WaveRenderer` does (prelude, field pass, feedback, the uniforms,
  `effects.shaderImages`, `effects.videoPath`; a time slider, a category-switch button, bass, portrait, and `uFocus`
  following the mouse). Serve a folder above both this repo and
  the pack, e.g. `python3 -m http.server` in the parent folder, and open
  `tools/shader-preview/?frag=<path to the .frag>&theme=<path to theme.json>`. Paths are relative
  to the page. `renderAt(seconds)` in the console draws one moment and returns a PNG blob. It shows
  the look only; its speed says nothing about a phone.
* **Look on the emulator:** it must run with `-gpu host`; its default software GL renders shaders
  far too slowly to judge anything.
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
  shaders use them; `ShaderImagesTest` covers `effects.shaderImages`, the program key, the
  upload sizes and budget, and `uFocus`; `BackgroundVideoTest` covers `effects.videoPath` and
  `videoFit`, the VIDEO preset's place in the cascade, the placements, the copy size and when the
  player plays, holds or lets go; `SceneTest` covers `scene.json` (every layer type, the problems,
  the budget), `effects.scenePath` in the cascade, the scene's key, its poster, the exporter, which images a
  drawn scene decodes, the sharp resolution, shader layers' programs (keys, images, rects), which
  scenes read the tilt sensor, which can retire to their poster, and the sample scene pack;
  `ScenePoseTest` (`:theme:engine`) covers a layer's box, parallax (focus and tilt), sway, pulse,
  the input drivers, sprite cells and scrolling; `SceneTiltTest` covers the tilt reading (relative
  to how the device is held, the screen's axes per rotation, the clamp and the recentring);
  `SceneParticlesTest` covers particle births and lives.

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

## 8. Images and the focus

A CUSTOM shader can sample up to four images from its pack, so a painting, a photo or a hand-made
texture can be brought to life by math (ripples, haze, depth parallax) instead of being either a
still wallpaper or pure math. List them next to the shader, in any layer that can set one (base,
variants, category and system overrides, rules):

```json
"effects": {
  "shaderPath": "scene.frag",
  "shaderImages": [
    "art/painting.webp",
    { "path": "art/depth.png", "wrap": "CLAMP", "filter": "LINEAR", "mipmap": false },
    { "path": "art/noise.png", "wrap": "REPEAT" }
  ]
}
```

* **Slots:** the first is `uImage0` (texture unit 2), the next `uImage1`, up to `uImage3`. An entry
  that can't be used is reported and leaves its slot empty, so the ones after it keep their
  numbers. More than four are reported and ignored.
* **Settings:** a plain string means `CLAMP`, `LINEAR`, no mipmaps. `wrap` is `CLAMP`, `REPEAT` or
  `MIRROR`; `filter` is `LINEAR` or `NEAREST` (pixel art); `mipmap: true` for an image drawn much
  smaller than its size. All of them work at any size on ES 3.0.
* **Files:** PNG, JPEG or WebP inside the pack (no `..`), at most 24 MB each. Images are only read
  with a CUSTOM shader; with a preset they are a problem in Settings ▸ Theme ▸ Theme pack.
* **Orientation:** upright when sampled at `gl_FragCoord.xy / uResolution`, the same space as
  `uPrev`. Fitting the image to the screen is the shader's job; `scene.frag` in the example below
  has a `cover()` that keeps the aspect.
* **Alpha is straight, not premultiplied.** What a pack stores in alpha (a mask, a water marker,
  packed depth) comes through exactly. Multiply by alpha yourself where you blend.
* **Size:** each image's long side is capped at 2048 px (less if the GPU's `GL_MAX_TEXTURE_SIZE`
  is smaller), keeping its aspect. One program's images share a budget of 8 megapixels (32 MB as
  RGBA8, about 43 MB with mipmaps): two full 2048² images use all of it. Slots that don't fit are
  left empty with a toast, in order, and a smaller one after them may still fit.
* **`uImageSizeN`** is `(w, h, 1/w, 1/h)` in texels as uploaded, and all zeros for an empty slot. An
  empty slot samples black, so `if (uImageSize1.x > 0.0)` tells whether an optional image is there.
* **Cost:** a program that doesn't read the images pays nothing (an unused sampler has no
  location). Reading them costs a texture fetch each; the watchdog times it like any other work.

### Loading

The images are found when the pack loads, then decoded off the GL thread
([`ShaderImageLoader`](../app/src/main/java/dev/xross/launcher/ui/ShaderImageLoader.kt)) while the
program compiles, and uploaded on the GL thread
([`ImageTextures`](../app/src/main/java/dev/xross/launcher/ui/GlShaders.kt)). Until they are
uploaded the program counts as compiling, so the background on screen stays and nothing flashes
black. The decoded pixels aren't kept: after a lost GL context they are decoded again.

A program's key folds in each image's settings, size and modification time, so replacing an image
in a folder pack (a hot reload) is a new program, and a shader blocked after a crash is tried
again once one of its images changes.

### `uFocus`

`uFocus` is the centre of the focused item on screen, in the same 0…1 space as
`gl_FragCoord.xy / uResolution` (y up), eased over about 0.1 s so it never snaps. Use it to make
near layers lean away from where the user is looking:

```glsl
float d = texture(uImage1, uv).r;            // depth: 0 far .. 1 near
vec2 p = uv - (uFocus - 0.5) * 0.03 * d;     // near things move most
vec3 col = texture(uImage0, p).rgb;
```

In the XMB the focused item always sits at the crosshair, so `uFocus` changes when the layout does
(another pack, rotation), not on every press. Menu styles that move the focus around the screen
(`docs/design/PLAN_MENU_STYLES.md`) report it the same way.

### Example

[`tools/shader-preview/examples/painted/`](../tools/shader-preview/examples/painted/) is a small
pack: a generated dusk valley (`painting.png`, whose alpha marks the lake), a depth map
(`depth.png`) and `scene.frag`, which shifts the layers by depth toward `uFocus`, ripples the
water with the bass and `uTransition`, and drifts a haze tinted by `uGlow`. Open it in the preview
with `?frag=examples/painted/scene.frag&theme=examples/painted/theme.json`: the preview binds the
`theme.json`'s `shaderImages` (or `&images=a.png,b.png`), and `uFocus` follows the mouse.

## 9. Video

A look can have a looping, muted background video (`effects.videoPath`, with `effects.videoFit`;
see the [theme specification](THEME_SPECIFICATION.md#background-video)). With the look's shader
left at the wave, the built-in `VIDEO` preset draws it. A pack's own shader reads it instead:

```glsl
uniform sampler2D uVideo;    // the current frame (texture unit 6), already RGB, alpha 1
uniform vec4 uVideoSize;     // w, h, 1/w, 1/h of the frame in texels; 0 with no video
uniform vec4 uVideoRect;     // where effects.videoFit places it: left, bottom, width, height in render pixels
```

* **A plain texture.** The decoder's frames arrive in an external (OES) texture; each new one is
  copied into a regular RGBA8 texture, at most 1280 px on its long side, and that copy is
  `uVideo`. Filtering and `texelFetch` work as on any `sampler2D`, and the prelude needs no
  extension. The copy costs one small pass per decoded frame (about 30 a second), not per frame
  drawn.
* **Upright,** like the images: sample at `gl_FragCoord.xy / uResolution` for a stretched frame, or
  through `uVideoRect` for the pack's fit:

  ```glsl
  vec2 t = (gl_FragCoord.xy - uVideoRect.xy) / uVideoRect.zw; // 0..1 across the placed frame
  vec3 col = texture(uVideo, clamp(t, 0.0, 1.0)).rgb;
  ```

  `uVideoRect` is in full-size render pixels; in the field pass (`uPass` = 0) scale it by
  `FieldPolicy`'s 1/4.
* **Before the first frame,** and with no video, `uVideo` samples black and `uVideoSize` is 0, so
  `if (uVideoSize.x > 0.0)` tells whether there is a picture yet. When the player pauses (covered,
  resting, a snap plays) or lets go (a game is in front), the last frame stays.
* **Cost.** Decoding happens in the hardware decoder whatever the frame rate, so the video pauses
  when the screen rests. The shader is measured by the watchdog as any other; a 720p H.264 loop is
  the safe choice on a mid-range phone.

### Example

[`tools/shader-preview/examples/video/`](../tools/shader-preview/examples/video/) is a small pack:
a generated 6 s gradient loop (`loop.mp4`, 640×360, 37 KB) and `glass.frag`, which shows it
through rippled glass that follows the bass, shifted a little toward `uFocus`, with a vignette. Its
`plain` variant shows the same video through `VIDEO`. In the preview:
`?frag=examples/video/glass.frag&theme=examples/video/theme.json`, or
`?frag=../../app/src/main/assets/shaders/video.frag&video=<a .mp4>&fit=FIT` for the preset alone.

## 10. Scene shader layers

A `shader` layer in a [layered scene](THEME_SPECIFICATION.md#layered-scenes) is a full-screen
shader like the ones above, drawn between the scene's other layers. It gets the whole prelude
(time, colours, music, `uFocus`, its own `images` as `uImage0..3`, the scene's video as `uVideo`)
plus one more input:

```glsl
uniform sampler2D uBelow;    // the layers below, composited so far (texture unit 7)
```

* **Sample it at `gl_FragCoord.xy / uResolution`.** It is RGBA8 at the render size, linear and
  clamped, so small offsets (ripples, refraction, a blur of a few taps) are cheap.
* **What you write replaces what is below** inside the layer's `rect` (the whole screen without
  one), mixed in at its `opacity`. Write alpha 1; to leave a pixel as it was, return
  `texture(uBelow, uv)`.
* **Not available in a layer:** `uPrev` and `uField` (`uFresh` is 1 and `uPass` is 1). Outside a
  scene `uBelow` samples black.
* **Cost.** Each shader layer is a full pass and the scene draws through two render targets while
  it has one, so the budget counts a shader layer as two screens. The watchdog times the whole
  scene, not each layer; a scene over budget is retired to its poster, not to the wave.
* **Errors.** A layer that doesn't compile is left out (its error in the notice, with its place in
  `scene.json`) and the rest of the scene still draws. The scene is crash-guarded as one unit;
  once blocked after a crash it shows its poster (the built-in poster program runs no pack code).
* **Preview.** `tools/shader-preview/?theme=examples/scene/theme.json` draws a whole scene with the
  app's own layer and particle programs, your shader layers over them; the mouse is the focus and
  the tilt sliders stand in for the device. `examples/scene/rain.frag` is a shader layer to start
  from.

```glsl
// rain_glass.frag: the scene below, seen through running water (uImage0: a drop normal map).
void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec2 n = texture(uImage0, uv * vec2(2.0, 1.0) + vec2(0.0, uTime * 0.05)).xy * 2.0 - 1.0;
    vec3 col = texture(uBelow, uv + n * 0.01).rgb;
    fragColor = vec4(col * (0.9 + 0.1 * n.y), 1.0);
}
```
