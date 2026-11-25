# Live backgrounds — implementation plan

A proposal, not yet scheduled to a release. It covers three steps toward Wallpaper Engine-style
backgrounds: shader image inputs, video backgrounds, and layered scenes. Written against `main`
at `1b5261f` (2026-11-22).

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.

| # | Feature | Size | Status |
| --- | --- | --- | --- |
| 1 | Image inputs for pack shaders (`uImage0..3`, `uFocus`) | M | ⬜ |
| 2 | Video backgrounds (`effects.videoPath`, `uVideo`, VIDEO preset) | L | ⬜ |
| 3 | Layered scenes (`scene.json`: images, sprites, video, effect shaders, GPU particles) | L × 5–6 | ⬜ |

**Order:** 1, then 2, then 3. Section 2 reuses § 1's texture units and upload path, and § 3 is
built from § 1 and § 2 layers. Each section ships on its own: § 1 alone already enables painted
scenes with ripples, haze and depth parallax.
**Not planned:** reading Wallpaper Engine `.pkg` / Workshop content. The format is proprietary,
its shaders are HLSL, and the content belongs to its authors.

## Where things stand

- `WaveRenderer` (`app/.../ui/WaveRenderer.kt`) is the only GL host: one full-screen triangle,
  one fragment program per frame, plus the optional `screen_filter.frag` pass. Programs come from
  `ShaderLibrary` (`theme/engine/.../shader/Shaders.kt`), which keys a CUSTOM program by its path
  plus a digest of its text. Every later section relies on that key; see "Keys" below.
- What a program can sample today is only its own output: `uPrev` (feedback, texture unit 0) and
  `uField` (the quarter-res pass, unit 1). It can't read any file the pack ships.
- The wallpaper is a Compose `Image` (`WallpaperBackdrop`, layer 3 in `XmbScreen`) drawn over
  the GL surface. When it is opaque, it reports `onCovering` and the wave pauses. Hero art, video
  snaps (`SnapPlayer`, ExoPlayer) and `AmbientParticles` are more Compose layers above that.
- The surface renders at `renderScale` = 1280 px on its long side (0.55 on the A55's 2340 ×
  1080). That's fine for soft math shaders, but too blurry for sharp art (see § 3, "Resolution").
- Protections every new path must keep: ahead-of-time compiling with `ShaderFallback`, the
  `FrameBudgetWatchdog` (A55: about 10 ms p90 at 450 MHz), the `ShaderCanary` crash guard, the
  Performance settings (standby motion, frame rate, slow shaders), still/E-Ink mode, and pausing
  whenever something opaque covers the screen.

### Keys (applies to every section)

A program's key has to change whenever anything it draws changes. Otherwise a hot reload
(`ThemeWatcher`) keeps showing the stale version, and a shader blocked after a crash is never
retried. From § 1 on, a CUSTOM key is
`custom:<path>:<digest(text + each input's path, size, mtime)>`. A scene's key is the digest of
`scene.json` plus every file it names.

---

## 1. Image inputs for pack shaders (M)

**Goal.** A pack's shader can sample up to four images from the pack, and knows where the menu's
focus is. This is the smallest change that ends the split between "static image" and "pure math".

**Format.** This lives next to the shader in every place a theme layer can set one: the base,
variants, category and system patches, and rules.

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

- A plain string means CLAMP / LINEAR / no mipmap. `wrap` is `CLAMP`, `REPEAT` or `MIRROR`;
  `filter` is `LINEAR` or `NEAREST`. Both `REPEAT` and `mipmap` are fine on ES 3.0 at any size.
- `shaderImages` is only read together with a CUSTOM shader. With a preset it is a problem, the
  same rule as for `shaderPath`.
- Paths go through the same checks as `shaderPath` (inside the pack, no `..`). Allowed types are
  png, jpg and webp.

**Prelude** (`prelude.glsl`, documented in `SHADER_GUIDE.md`):

```glsl
uniform sampler2D uImage0, uImage1, uImage2, uImage3; // effects.shaderImages, in order (texture units 2–5)
uniform vec4 uImageSize0, uImageSize1, uImageSize2, uImageSize3; // w, h, 1/w, 1/h; 0 for a missing slot
uniform vec2 uFocus;  // 0..1: the focused item's centre on screen (the screen signal Focus); eased
```

`uFocus` comes from `ScreenSignal.Focus`, which `PLAN_MENU_STYLES.md` slice 1 adds in place of
`CrosshairX`. Every menu style reports it, so a shader's parallax follows the focus in any of
them. That slice lands before or with this section.

The driver gives an unused sampler no location, so programs that don't read the images pay
nothing. That's the same reasoning `uPrev` already uses.

**Loading.**
- Images are decoded on IO by a new `ShaderImageLoader` next to `WallpaperLoader`:
  - `ImageDecoder` with `setUnpremultipliedRequired(true)`, so data kept in the alpha channel
    (masks, packed depth) survives. The guide states that alpha is straight, not premultiplied.
  - The long side is capped at `min(2048, GL_MAX_TEXTURE_SIZE)`.
  - The upload uses `copyPixelsToBuffer` plus `glTexImage2D(RGBA8)`, not `GLUtils`, which would
    premultiply.
- Budget: 8 megapixels across one program's images (32 MB, or about 43 MB with mipmaps). The
  loader reports the problem, and the program draws with the slots it could fit.
- On the GL thread, `sync()` treats a program as PENDING until its textures are uploaded. So
  `ShaderFallback` keeps the current program on screen, and nothing flashes black.
- On context loss, the textures are lost. They are decoded and uploaded again, with the program
  PENDING in the meantime. Decoded bitmaps are not kept in memory.

**Code.** `ShaderRef` / `ShaderSpec` gain `images: List<ShaderImage>`, and `ShaderLibrary.of`
folds them into the key. `GlProgram` looks up the new uniforms, and `draw()` binds units 2–5. A
new `ImageTextures` class next to `FeedbackTargets` in `GlShaders.kt` owns the textures.
`WaveParams` gains `focus`, eased like the colors.

**Tests.** Loader parsing (strings and objects, bad paths, wrap and filter names, a preset with
images), key changes when an image changes, the budget problem. `ShaderSources` gets a test that
the prelude declares the new uniforms. Device, on the A55 with a profiling build: a sample pack
showing a painting with ripple and depth parallax, with its watchdog p90 noted in
`docs/status/PERFORMANCE.md`.

**Docs and sample.** `THEME_SPECIFICATION.md` (effects), `SHADER_GUIDE.md` (the new uniforms,
straight alpha, the budget), and a "painted scene" chapter in `SHADER_TUTORIAL.md`. A sample
pack goes in `xross_themePacks`.

## 2. Video backgrounds (L)

**Goal.** A looping, muted video as the background. It can be drawn as is, or read by a pack's
shader as `uVideo` and still pass through the background filter.

**Format.** `effects.videoPath` (mp4: H.264 or HEVC; webm: VP9), cascading like `wallpaperPath`.
Also `effects.videoFit`, reusing `WallpaperFit` (`COVER` by default).
- With no shader set, the new `VIDEO` preset draws it.
- With a CUSTOM shader, the shader reads it:

```glsl
uniform sampler2D uVideo;   // the current video frame (texture unit 6), already RGB
uniform vec4 uVideoSize;    // w, h, 1/w, 1/h; 0 with no video
```

**Decoding into GL.**
- A new `BackgroundVideo` (in `media/`) owns its own ExoPlayer:
  - `REPEAT_MODE_ONE`, which loops gaplessly for a single item
  - audio track disabled through track selection, so there is no audio decoder and no focus
    request
  - output to a `SurfaceTexture` that the GL thread created
- Each frame, `onDrawFrame` calls `updateTexImage()` when a frame is waiting. It then copies the
  external texture into a regular RGBA8 texture at the video's size (capped at 1280 on the long
  side) with a tiny `samplerExternalOES` program.
- Why the copy:
  - The prelude stays portable: no `GL_OES_EGL_image_external_essl3` requirement in every
    program.
  - `uVideo` behaves like any `sampler2D` (filtering, `texelFetch`).
  - The cost is one small pass per decoded frame, about 30 per second, not per render frame.

**Lifecycle**, where most of the work is:
- **One hardware decoder in use at a time.** The background video holds its last frame and
  pauses its player whenever a snap plays (`SnapPlayer.player` is non-null), media playback is
  open, or the boot sequence's video runs.
- **Launching a game or app.** The player is released in `onStop`, which frees the decoder for
  the emulator. On return it is re-prepared and seeks to the saved position, and the last copied
  frame stays on screen until the first new frame arrives.
- **Standby.** Decoding costs the same at any GL frame rate. So when the Performance setting
  "standby motion" says the screen is resting, playback pauses and the copied frame is held.
  Playback resumes on input.
- **Still/E-Ink mode.** Shows the first frame as a poster and never plays.
- **Covered** (opaque hero art or wallpaper): paused, like the wave.

**Failures.** A video is data, not code, so it gets no canary. If the decoder fails, or the video
format isn't supported (checked with `MediaCodecList`, sizes over 1920 flagged by the loader), a
status goes to `ShaderHealth` and the background falls back to the look's wallpaper, else the
wave.

**Packs.** `.xtheme` is capped at 50 MB (`ThemeArchive.MAX_BYTES`), so video packs need short
loops. The guide recommends 720p, 10–20 s, about 2–4 Mbit/s. The cap stays as it is.

**Stretch (S–M).** Choose a video from the user's Video folders as the wallpaper in
Settings ▸ Theme, like Photo albums today. It is a device setting and never exported.

**Tests.** Loader (path, fit, cascade, format problems). A unit test of a `BackgroundVideoPolicy`
holding the play / pause / release rules, written without Android, like `ParticleRest`.
Emulator: loop seam, snap handoff, launch and return. A55 with a profiling build: jank while
navigating, idle CPU, and battery over 30 min (`dumpsys batterystats`) against the WAVE
baseline.

## 3. Layered scenes (L × 5–6, about 3–4 weeks)

**Goal.** A stack of layers as in Wallpaper Engine's scene type. Each layer can move, react to
focus, audio and time, and carry its own effect. Sections 1 and 2 are the building blocks.

**Format.** `effects.scenePath` names a `scene.json` (cascading like a shader). The whole scene
is one entry in `ShaderLibrary`, so variants, rules, fallback and the watchdog work as they do
today.

```json
{
  "resolution": "sharp",
  "poster": "poster.webp",
  "layers": [
    { "type": "image",  "path": "sky.webp",  "fit": "COVER", "parallax": 0.1, "scroll": [4, 0], "wrap": "REPEAT" },
    { "type": "video",  "path": "clouds.mp4", "blend": "SCREEN", "opacity": 0.6 },
    { "type": "image",  "path": "city.png",  "anchor": "BOTTOM", "parallax": 0.4, "audio": { "scale": 0.02 } },
    { "type": "sprite", "path": "neon.png",  "frames": [4, 2], "fps": 8, "rect": [0.62, 0.40, 0.12, 0.10] },
    { "type": "shader", "path": "rain_glass.frag", "images": ["drops.png"] },
    { "type": "particles", "path": "ember.png", "rate": 30, "life": [3, 6], "velocity": [[-5, 5], [-40, -20]],
      "size": [4, 10], "color": ["#ffb354", "#ff802400"], "parallax": 0.7 },
    { "type": "image",  "path": "rain_fg.png", "parallax": 1.0, "scroll": [0, 300], "wrap": "REPEAT", "blend": "ADD" }
  ]
}
```

Fields shared by every layer: `opacity`, `blend` (`NORMAL`, `ADD`, `SCREEN`, `MULTIPLY`),
`rect` or `fit` + `anchor`, `parallax` (0 stays still, 1 moves the most with `uFocus` and
tilt), `mask` (an image), and the motions `scroll`, `sway` (an angle or offset on a sine) and
`pulse`. Motions can be driven by `audio` (bass/beat), `transition` (category switch) or
`daylight`. Errors are reported per layer (`scene.layers[3].frames: …`).

**Renderer.** A new `SceneProgram` sits beside `GlProgram`. `WaveRenderer.select()` treats both
the same way.
- *Image, sprite and video layers* share one textured-quad program: a transform, UV rect,
  opacity and blend per draw, drawn straight into the target. One draw call per layer, nothing
  allocated per frame.
- *Shader layers* are § 1 programs that also read `uniform sampler2D uBelow`, the layers
  composited so far. While the scene has a shader layer, it renders into a ping-pong pair of
  framebuffers (the pattern `FeedbackTargets` uses), and the last pass writes to the screen. A
  scene without one draws straight to the screen.
- *Particles* run on the GPU: one instanced draw. Each particle is computed from its index and
  `uTime` (birth = index / rate, wrapped by life), so there is no CPU state and nothing to
  simulate after a pause. They are separate from the Compose `AmbientParticles`; a scene with
  particle layers turns those off.
- The background filter pass stays the final step.

**Resolution.**
- `"resolution": "soft"` keeps today's 1280 cap.
- `"sharp"` raises it to 1920 on the long side (0.82 on the A55), about 2.25× the pixels.
- `WaveBackground.setFixedSize` follows the active entry, so switching between a sharp scene and
  a soft shader resizes the surface once (the resize already marks `uFresh`).

**Budget.**
- The loader estimates overdraw: the screen fractions covered by all layers, with shader layers
  counted ×2. Above 6 screens, or with more than 16 layers, 2 video layers, 4 shader layers or
  2,000 particles, it reports a problem and drops the topmost layers that don't fit.
- The watchdog measures the whole scene as one program. A slow or crashing scene is retired to
  its `poster` (else its first image layer, drawn alone), not to the wave. That's a better
  fallback for art-driven packs.
- Pack-written shader layers keep the canary. The scene is armed as one unit.

**Input.**
- `uFocus` from § 1.
- Tilt: an optional gyroscope setting in Settings ▸ System ▸ Performance, off by default. When
  on, it reads `TYPE_GAME_ROTATION_VECTOR` at a low rate only while a scene with parallax is on
  screen, eased and clamped.
- No touch or pointer input; the launcher is gamepad-first.

**Compose interplay.** While a scene is the background, the pack's own wallpaper is not drawn.
The scene is the art. A wallpaper the user chose (Settings ▸ Theme, or rotation) still wins over
the scene, the same way it wins over a pack's shader today: opaque, covering, wave paused.
*Decision to confirm*, see below.

**Slices.**

| Slice | Scope | Size |
| --- | --- | --- |
| 3a | `scene.json` model, loader, problems, key, poster; unit tests | M |
| 3b | Quad program; image and sprite layers; fit, anchor, blend, opacity; motions; resolution switch | L |
| 3c | Video layer (§ 2's copy pass); shader layers with `uBelow` and ping-pong targets | M |
| 3d | GPU particles; turns off Compose particles under a scene | M–L |
| 3e | Parallax from `uFocus`; tilt setting; audio, transition and daylight drivers | S–M |
| 3f | Overdraw budget, poster fallback, canary as one unit; A55 timings; spec, guide, a sample scene pack | M |

**Tests.**
- Unit: the loader (every layer type, the problems, cascade, key), motions as pure functions of
  time and inputs, the overdraw estimate, particle birth and life arithmetic.
- Device, on the A55 with a profiling build, three passes interleaved (particle cost varies
  between runs): the sample scene's watchdog p90 at soft and sharp, deadline jank while
  navigating, idle CPU, and the poster fallback after a forced slow verdict.

---

## Decisions to confirm

1. **The user's wallpaper over a pack's scene or video.** Proposed: the user's choice wins,
   consistent with today. The alternative is that the scene wins while it is the look's
   background.
2. **Tilt parallax.** Proposed: an opt-in setting, off by default (it costs sensor wake-ups and
   some find it distracting).
3. **Video from the user's own folders (§ 2 stretch).** In scope for § 2, or later?
4. **`schemaVersion`.** Proposed: stays 2. All the new fields are optional, and an older Xross
   ignores them and shows the wave or wallpaper. There's no hard break worth a version bump.
