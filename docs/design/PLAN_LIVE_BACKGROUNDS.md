# Live backgrounds — implementation plan

A proposal, not yet scheduled to a release. It covers three steps toward Wallpaper Engine-style
backgrounds: shader image inputs, video backgrounds, and layered scenes. Written against `main`
at `1b5261f` (2026-11-22).

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.
Status: ✅ done · ◐ code done, device check open · ⬜ not started.

| # | Feature | Size | Status |
| --- | --- | --- | --- |
| 1 | Image inputs for pack shaders (`uImage0..3`, `uFocus`) | M | ◐ on `feat/live-backgrounds-image-inputs`; A55 timing open |
| 2 | Video backgrounds (`effects.videoPath`, `uVideo`, VIDEO preset) | L | ◐ on `feat/live-backgrounds-image-inputs`; emulator checked, A55 runs open; stretch not started |
| 3 | Layered scenes (`scene.json`: images, sprites, video, effect shaders, GPU particles) | L × 5–6 | ◐ 3a–3f on `feat/live-backgrounds-image-inputs` (3b–3f emulator checked); A55 timings, tilt tuning and the final overdraw cap open |

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

> **Progress (2026-11-25, `feat/live-backgrounds-image-inputs`).** Code, unit tests and docs are
> done; the A55 timing is not (no device was attached).
>
> - **Format and loading** as specified below. `ShaderImage` / `ShaderImageSource` / `BoundImage` /
>   `ShaderImagePlan` in `Shaders.kt`; `ShaderRef.images`, `ShaderSpec.images`, and
>   `ShaderLibrary.customKey` (a shader without images keeps its old key). `LoadedTheme.shaderImages`
>   finds the files (folder and bundled packs) without decoding them. `LookExporter` carries them.
> - **GL:** `ShaderImageLoader` (decoder thread: bounds, plan, `ImageDecoder` unpremultiplied,
>   rows flipped so images are upright at `gl_FragCoord.xy / uResolution`), `ImageTextures` in
>   `GlShaders.kt`, `GlProgram.drawState` (PENDING until uploaded). On a still screen the frame
>   that uploads is requested by `WaveRenderer.onImagesDecoded`.
> - **`uFocus`:** `ScreenSignal.CrosshairX` became `ScreenSignal.Focus(x, y)` here, the signal half
>   of `PLAN_MENU_STYLES.md` slice 1 (`MenuAnchors` and `layout.menuStyle` are still to come). In
>   the XMB it is the crosshair, so it only moves with the layout. It is eased in the renderer
>   (`WaveRenderer.easeFocus`, 0.12 s) and carried in `WaveParams.focusX/Y`.
> - **A deviation:** a slot whose entry is unusable stays empty instead of shifting the later
>   images down, so `uImageN` always means the N-th entry the pack wrote.
> - **Tests:** `ShaderImagesTest` (12): decoding, problems, the cascade, keys, the 2048 cap and 8 MP
>   budget, focus easing, the prelude. The full `:app:testDebugUnitTest` and `:theme:engine:test`
>   pass, and `validateShaders` is clean.
> - **Sample:** `tools/shader-preview/examples/painted/` (generated art, depth map, `scene.frag`),
>   checked in the preview, which now binds `shaderImages` and moves `uFocus` with the mouse. A
>   pack in `xross_themePacks` is still to make.
> - **Open:** the A55 run with a profiling build (the sample's watchdog p90 into
>   `docs/status/PERFORMANCE.md`), and a look at the upload hitch for two 2048² images with mipmaps.

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
`CrosshairX` (the signal itself landed with this section). Every menu style reports it, so a shader's parallax follows the focus in any of
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

> **Progress (2026-11-26, `feat/live-backgrounds-image-inputs`, on top of § 1).** Code, unit
> tests, docs and an emulator run are done. The A55 runs and the stretch are not.
>
> - **Format and cascade.** `VideoRef` / `VideoFit` / `VideoSource` in
>   `theme/engine/.../pack/VideoBackground.kt`. They follow the wallpaper's cascade through
>   `ResolvedVariant`, `ThemePatch`, `ThemeLook.video` and `cascade()`. `LoadedTheme.videos` /
>   `videoSource()` handle folder packs only, at most 30 MB. `LookExporter` carries the files and
>   flattens `videoPath` / `videoFit`.
> - **VIDEO preset.** `withVideoShader` turns a look's WAVE into VIDEO when the look has a video.
>   CUSTOM and the other presets keep their place. `shadersUsed` compiles VIDEO ahead of time when
>   the pack has a video file. `EffectOverrides` doesn't let the user pick VIDEO.
> - **GL.** `VideoTexture` in `ui/GlVideo.kt` (OES texture, `SurfaceTexture`, an ESSL 1.0
>   `samplerExternalOES` copy into RGBA8 capped at 1280). `WaveRenderer.syncVideo` creates it and
>   drops it again with `WaveParams.videoFit`. After a context loss it is made again and the player
>   gets the new surface. `video.frag` and the prelude's `uVideo`, `uVideoSize`, `uVideoRect` use
>   unit 6.
> - **Player.** `media/BackgroundVideo.kt` has `BackgroundVideoPolicy` (pure: RELEASE / HOLD /
>   PLAY, and the probe verdicts) and `BackgroundVideo` (ExoPlayer, `REPEAT_MODE_ONE`, audio track
>   disabled, volume 0, the position kept across a release, a `MediaExtractor` +
>   `MediaCodecList` probe off the main thread). `WaveBackground` runs it from the lifecycle: it is
>   released on ON_STOP. `MainActivity` hides the pack's wallpaper while a video shows. After a
>   failure it falls back to that wallpaper, else WAVE, keyed by the file, so a replaced file is
>   tried again.
> - **Deviations:**
>   - `videoFit` uses its own engine enum, `VideoFit` (FILL / FIT / STRETCH / CENTER, with
>     `COVER` read as FILL), because `WallpaperFit` lives in `:app`.
>   - A layer that sets only `videoFit` keeps the video and changes its fit.
>   - The prelude also gets `uVideoRect` (the `videoFit` placement), so a CUSTOM shader can
>     honour the pack's fit.
>   - **Standby** uses the particles' rest, not the 3 s screen rest: Standby motion's timeout,
>     but at least 10 s. With the 3 s default, a loop froze 3 s after every press on the emulator.
>     `Never` keeps it playing.
>   - The video also holds while the activity is paused (not resumed), since the GL loop is
>     stopped then too.
>   - "Media playback open" needs no rule of its own: full-screen photos and video already remove
>     the GL surface, which releases the player.
> - **Tests:** `BackgroundVideoTest` (15): decoding, fit-only layers, the cascade (VIDEO under the
>   wave, presets and CUSTOM kept, `none`, category overrides), missing files, the source key, the
>   exporter, placements, the copy size, the policy, the probe, and the prelude. The full
>   `:app:testDebugUnitTest` and `:theme:engine:test` pass, and `validateShaders` is clean (10
>   built-ins).
> - **Emulator** (API 37, `-gpu host`, debug build, the sample pack):
>   - VIDEO and `glass.frag` link, and frames are copied at 640×360.
>   - The watchdog passes `glass.frag` (p90 5.5–7.7 ms, glFinish; emulator timings mean little).
>   - The policy goes play → hold after 10 s without input → play on a key.
>   - Settings in front: hold on pause, release on stop, play from the saved position on return.
>   - That emulator image kept raising system ANR dialogs after a cold boot, so the snap handoff
>     (it has no games) and the loop seam weren't checked.
> - **Sample:** `tools/shader-preview/examples/video/` (a generated 6 s loop, 37 KB, `glass.frag`,
>   and a `plain` variant on VIDEO). The preview plays `videoPath` / `?video=` with `videoFit` /
>   `&fit=`.
> - **Open:**
>   - The A55 with a profiling build: jank while navigating, idle CPU, and battery over 30 min
>     against the WAVE baseline, into `docs/status/PERFORMANCE.md`.
>   - The snap handoff and the loop seam on a device with games.
>   - HEVC and VP9 files on the A55.
>   - The § 2 stretch (videos from the user's folders).

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

> **Progress (2026-11-26, `feat/live-backgrounds-image-inputs`, on top of § 2).** Slice 3a is
> done: code, unit tests and docs. 3b–3f are not started. No device was attached, so the A55 runs
> still open in § 1 and § 2 are still open.
>
> - **Model.** `theme/engine/.../shader/Scene.kt`: `Scene`, the `SceneLayer` types (`Image`,
>   `Video`, `Sprite`, `Shader`, `Particles`), `LayerLook` (the shared fields), `LayerMotion`
>   (`scroll`, `Sway`, `Pulse`, and a `Drive` each for `audio`, `transition` and `daylight`),
>   `SceneBudget` and `SceneSource`. Layer `fit` reuses § 2's `VideoFit`.
> - **Format and cascade.** `effects.scenePath` (alias `scene`) is a new preset, `SCENE`, read by
>   `ShaderRef.of`. A scene therefore cascades through the shader field with no new cascade code,
>   and is one `ShaderLibrary` entry. `"shaderPreset": "SCENE"` alone reads `scene.json`. A
>   `scenePath` next to `shaderPreset` / `shaderPath` / `shaderImages` wins, and the others are
>   problems. `EffectOverrides` doesn't let the user pick SCENE.
> - **Loader.** `LoadedTheme.scenes`: each scene is parsed, then its files are found the way § 1
>   and § 2 find theirs. Images (poster, layers, masks, shader layers' images) are found as shader
>   images, in both pack kinds. Videos are found as background videos, folder packs only. Shader
>   layers are read as shader text. Problems are per layer: `scene.json: layers[3].frames: …`.
> - **Key.** `scene:<path>:<digest(scene.json + each file's path and size/mtime, or a shader
>   layer's text digest)>`. An unreadable scene is `scene:<path>:missing` and fails as MISSING, so
>   the wave shows.
> - **Poster.** Until 3b, the SCENE preset is the built-in `scene_poster.frag`. It draws
>   `SceneSource.posterImage` (`poster`, else the first image layer) as § 1's `uImage0`, covering
>   the screen, over the theme gradient. All of § 1's upload, PENDING and context-loss handling
>   applies. `MainActivity` doesn't draw the pack's wallpaper while a scene shows (as for § 2's
>   video); a user wallpaper still wins.
> - **Exporter.** A scene and every file it names travel together. A flattened variant writes
>   `shaderPreset: SCENE` + `scenePath`, and a look that drops the scene drops its files.
> - **Deviations:**
>   - **Overdraw cap 8, not 6 screens.** The plan's own example measures 6.02 (three full images,
>     a full video, a shader layer ×2), so 6 would drop its top layer. The A55 timings in 3f
>     settle the final number.
>   - The overdraw estimate and the count limits (16 / 2 / 4 / 2,000) landed here, not in 3f:
>     they are loader problems. 3f keeps the poster fallback on a slow verdict and the canary.
>   - Particle `color` uses `#aarrggbb`, as everywhere in a theme, so the example's fading end
>     colour is now `#00ff8024`.
>   - Paths in `scene.json` are pack-relative, like every other path in `theme.json`. They are
>     not relative to the scene's folder.
>   - Units: `rect` and `anchor` are screen fractions from the top-left, y down. `velocity`,
>     `size` and `offset` are pixels at 1280 on the long side. `scroll` is texels of the layer's
>     own image per second.
>   - `untrusted` stays CUSTOM-only for now: only the built-in poster program runs. A scene with
>     shader layers becomes canary-guarded as one unit in 3c.
> - **Tests:** `SceneTest` (12): the plan's example, per-layer problems and defaults, unusable
>   files, the poster choice, the budget (screens, videos, shaders, layers, particles), the
>   `scenePath` rules, the loader and cascade, a missing scene, key changes for each kind of file,
>   the exporter, and the effect keys. The full `:app:testDebugUnitTest` and `:theme:engine:test`
>   pass, and `validateShaders` is clean (11 built-ins).
> - **Not checked:** the poster on a device or emulator (no device was attached). There is also
>   no sample scene pack or shader-preview support yet; both belong to 3f.
> - **Next:** 3b, the quad program with image and sprite layers and the resolution switch.
>
> **Progress (2026-11-26, 3b, same branch).** Code, unit tests, docs and an emulator run are done.
> 3c–3f are not started.
>
> - **Pose math.** `theme/engine/.../shader/ScenePose.kt`: `ScenePoser.pose` turns a layer, its
>   texture size, the screen and the frame's `SceneInputs` into a `LayerPose`. The pose is a
>   parallelogram in render pixels plus a texture window and an opacity. It is pure and
>   allocation-free: box (rect, fit, anchor), parallax with overscan, sway, pulse, the three
>   drivers, sprite cells and scroll. Time-based terms are computed in double precision, so
>   nothing drifts or jumps after days.
> - **GL.** `ui/GlScene.kt`:
>   - `SceneQuadProgram` is `scene_layer.vert` / `.frag`: a 4-vertex strip from `gl_VertexID`,
>     placed by an origin and two edges. It has one sampler object per wrap mode (trilinear) and
>     is compiled once per context, next to the filter pass.
>   - `GlScene` holds a scene's textures (every one gets mipmaps) and draws the theme gradient,
>     then each image and sprite layer with its blend, opacity and mask.
>   - Blending is premultiplied: NORMAL `ONE, 1−SA`; ADD `ONE, ONE`; SCREEN `ONE, 1−SC`;
>     MULTIPLY `DST_COLOR, 1−SA`. Destination alpha is kept.
> - **Renderer.** `WaveRenderer` fills `SceneInputs` from its own time, the eased focus, the
>   audio levels, `uTransition` and the daylight. `render()` and the 1×1 warm-up draw a layered
>   program through `GlScene`.
> - **Loading.** The scene's textures are decoded on IO by `ShaderImageLoader`, which can now
>   decode premultiplied with a budget passed in (`Scene.TEXTURE_BUDGET_PIXELS`, 16 MP), and are
>   uploaded through a queue of their own. Until they are on the GPU the program is PENDING, so
>   `ShaderFallback` keeps the last program on screen.
> - **Resolution.** `WaveRenderer.longSide` reads the wanted program's `resolution`, and
>   `SurfaceSizer` in `WaveBackground` calls `setFixedSize` only when the size really changes.
> - **Deviations:**
>   - **No separate `SceneProgram`.** A SCENE's `GlProgram` is still the poster program and gets
>     a `GlScene` component (`layered`, `scene`). So `select()`, `ShaderFallback`, the watchdog
>     (it times the whole scene), the warm-up, the canary and context loss all work unchanged,
>     and the poster program is already compiled for 3f's fallback.
>   - A scene with no image or sprite layer (only video, shader or particles for now), or a
>     context whose quad program didn't link, shows the poster.
>   - The `audio`, `transition` and `daylight` drivers landed here with the other motions, since
>     they are a few lines in the same function. 3e keeps tilt and the parallax tuning on a device.
>   - Parallax: up to 3 % of the screen at 1, away from the focus. COVER and STRETCH layers are
>     overscanned by that much.
>   - `scroll` is in texels of the uploaded texture, which is at most 2048 px on its long side.
>   - A mask is stretched over the layer's box and doesn't scroll. Sprites always clamp.
>   - The gradient is drawn under the layers (one screen of fill, not counted in the budget).
> - **Tests:**
>   - `ScenePoseTest` (7, `:theme:engine`): rects, every fit and anchor, parallax and overscan,
>     sway and pulse (including after days), drivers, sprite cells, scrolling.
>   - `SceneTest` (+1, now 13): the textures a drawn scene decodes (each once, layer order,
>     particles' left for 3d), when the poster stands in, and the sharp long side (0.82 on the
>     A55).
>   - The full `:app:testDebugUnitTest` and `:theme:engine:test` pass, and `validateShaders` is
>     clean (13 units, including `scene_layer.vert` / `.frag`).
> - **Emulator** (API 37, `-gpu host`, debug build, a generated 4-layer scene: a scrolling sky, a
>   FIT/BOTTOM city, an ADD sprite, a masked swaying image):
>   - All layers draw in order, and the sky scrolls and the sprite steps between two frames.
>   - The surface is 1920×864 for `sharp`.
>   - The watchdog passed (p90 3.26 ms, glFinish; emulator timings mean little).
>   - An edit to `scene.json` hot-reloaded under a new key.
> - **Not checked:** the A55 (no device), and SCREEN and MULTIPLY by eye (only by their blend
>   equations).
> - **Next:** 3c, video layers (§ 2's copy pass, per layer) and shader layers with `uBelow` and
>   ping-pong targets.
>
> **Progress (2026-11-26, 3c, same branch).** Code, unit tests, docs and an emulator run are done.
> 3d–3f are not started.
>
> - **Video layers.** They draw § 2's `VideoTexture` copy through the quad program, posed and
>   blended like an image, with a bilinear sampler of its own (the copy has no mipmaps).
>   `MainActivity` gives the scene's video file (`Scene.video`) to the same `BackgroundVideo`
>   player, so the policy (hold, release, the snap handoff) and the failure path are § 2's. A
>   failed file leaves the layer empty and the rest of the scene drawn.
> - **Shader layers.** Each is a `GlProgram` of its own (`GlScene.layerSpec`: CUSTOM, keyed
>   `<scene key>#<i>`, labelled `scene.json › layers[i] glass.frag`), compiled with the scene's
>   program and settled with it (`poll()` waits for both under parallel compile). Its `images` are
>   decoded straight, all shader layers sharing § 1's 8 MP, and the scene stays PENDING until they
>   are up. `drawCall` draws it, so every prelude uniform is bound, plus `uBelow` on unit 7.
> - **Ping-pong.** `SceneTargets` in `GlScene.kt`: two RGBA8 targets owned by the renderer, made
>   while a scene with a shader layer shows and freed when it leaves. Layers draw into the current
>   one; a shader layer flips and reads the old one as `uBelow`; the last one is blitted to the
>   screen, then the filter pass. A scene without shader layers still draws straight to the screen.
> - **Canary.** `ShaderSpec.untrusted` is now true for a SCENE with shader layers: armed while it
>   compiles (the layers with it) and until proven on screen, one unit as planned.
> - **Deviations:**
>   - **One video file per scene, not two video layers.** `SceneBudget.MAX_VIDEOS` is 1 distinct
>     file; any number of layers may show it. That keeps § 2's rule of one hardware decoder in use
>     and its one player.
>   - **A shader layer isn't posed or blended.** It draws over its `rect` (a scissor; else the
>     screen) and mixes in at its `opacity` (a constant-alpha blend after a copy across). `blend`,
>     `fit`, `anchor`, `parallax`, `mask` and the motions are reported as ignored on it.
>   - **No `uPrev` or `uField` in a layer** (`uFresh` = 1, `uPass` = 1, their units unbound).
>   - **A shader layer that fails to compile is left out** with a notice, not the whole scene.
> - **Tests:** `SceneTest` (+4, now 17): video and shader layers drawn, the layers' programs (keys,
>   labels, texts, images, a missing text), a shader layer's ignored keys, rects as scissor boxes,
>   `uBelow` in the prelude; the budget's one video file; the canary flag. The full
>   `:app:testDebugUnitTest` and `:theme:engine:test` pass, and `validateShaders` is clean (13).
> - **Emulator** (API 37, `-gpu host`, debug build, a generated 6-layer scene: a scrolling sky,
>   two layers of one video (one SCREEN with sway), a full-screen ripple shader layer, a
>   half-opacity tint shader layer over the bottom 40 % with its own image, and an image above):
>   - Everything draws in order; the ripple bends the videos and sky, the tint stays inside its
>     rect at half strength, and the image above it is sharp.
>   - The video frame is copied at 640×360 and the player plays and holds by the § 2 policy.
>   - The watchdog passed (p90 2.83 ms, glFinish). After a hot reload with a broken layer it
>     tripped once (11.7 ms, while the launcher drew icons); emulator timings mean little, and 3f
>     times it on the A55.
>   - A layer that doesn't compile is reported (`layers[4] tint.frag compile failed: line 1:
>     'nope' : undeclared identifier`) and left out; fixing the file reloads the scene under its
>     old key.
> - **Not checked:** the A55 (no device), and a crash in a shader layer tripping the canary.
> - **Next:** 3d, GPU particles, and turning off the Compose particles under a scene.
>
> **Progress (2026-11-26, 3d, same branch).** Code, unit tests, docs and an emulator run are done.
> 3e–3f are not started.
>
> - **Arithmetic.** `theme/engine/.../shader/SceneParticles.kt`, pure and tested, and the same
>   steps in `scene_particles.vert`:
>   - A layer has `count` = `ceil(rate × lifeMax)` slots. Slot `i` is born at `i / rate`, then
>     every `period` = `count / rate` (never shorter than the longest life).
>   - Each birth is a new particle: life, spawn point, velocity and size come from a 32-bit hash
>     of the slot, the cycle (mod 256) and the layer's index (its seed).
>   - The GPU gets `uPhase`: the time wrapped to 256 periods in double precision on the CPU. The
>     time → (cycle mod 256, age) mapping is periodic in exactly that span, so the wrap is
>     seamless, and float steps stay around 2 ms at the longest span (100 s × 256).
>   - The field is full at time 0 (slots not yet born are in their previous cycle), and a pause
>     costs nothing.
>   - A fade-in over min(0.25 s, 10 % of the life); colour from `color[0]` to `color[1]` over the
>     life, premultiplied.
> - **GL.** `SceneParticleProgram` in `GlScene.kt` (`scene_particles.vert` / `.frag`, compiled
>   once per context next to the quad program; the two now share `linkSceneProgram`). A layer is
>   one `glDrawArraysInstanced` of a 4-vertex strip × `count`, with no buffers. Dead slots collapse
>   to a point. The image (if any) is a premultiplied, mipmapped scene texture, tinted by the
>   colour; without one, a soft dot. Blend modes are the quads'.
> - **The emitter's box.** `ScenePoser.pose` poses the layer as an image the size of the screen:
>   its `rect`, else the screen, overscanned by a covering fit. Parallax, sway, pulse and the
>   drivers move the whole field. `LayerPose.scale` (new) carries the motion's scale, so the box
>   before scaling (where particles move, in its own pixels) and the particles' size follow it.
>   A `mask` is stretched over the box.
> - **Compose particles.** `Scene.hasParticles`. `MainActivity` passes `sceneParticles` to
>   `XmbScreen`, which doesn't draw `AmbientParticles` while such a scene is the background.
> - **Deviations:**
>   - `GlScene.drawn` is gone: every layer type is drawn, so a scene shows its poster only while
>     its textures load or where the quad program didn't link. A shader layer's key and label
>     now use its index in the file (`#5`, `layers[5]`), as particle layers are no longer
>     filtered out first.
>   - `scroll` on a particle layer is reported as ignored. The particles move by `velocity`.
>   - Particles aren't rotated, and they may leave the box (it's where they're born, not a clip).
>   - A particle program that doesn't link skips particle layers; the rest of the scene draws.
>   - Compose particles stay off even while a user wallpaper covers the scene.
> - **Tests:** `SceneParticlesTest` (8, `:theme:engine`): slots and period, births spread at the
>   rate, about rate × mean life alive from the first frame, rebirths and seeds differ, ranges,
>   the seamless wrap and precision after days, fade-in and progress, dead slots, the box's motion
>   scale. `SceneTest` (+2, now 19): particle layers drawn and their images decoded, a
>   particle-only scene drawn, the `scroll` notice, `hasParticles`, and the shader's constants
>   matching `SceneParticles`. The full `:app:testDebugUnitTest` (1,103) and `:theme:engine:test`
>   (106) pass, and `validateShaders` is clean (15 units).
> - **Emulator** (API 37, `-gpu host`, debug build, a generated 4-layer scene at `sharp`: a sky, a
>   soft-dot snow layer masked to the top, a city silhouette, and ADD embers with an image in the
>   bottom 45 % with parallax and a pulse; the foreground particles set to SNOW in settings):
>   - All four textures uploaded and both particle layers draw in order. The snow fades out under
>     its mask, the embers rise over the city, and the frames change from second to second.
>   - No ambient snow flakes over the scene.
>   - The watchdog passed (p90 5.17 ms, glFinish; emulator timings mean little).
> - **Not checked:** the A55 (no device), and the cost of 2,000 particles (the plan's three
>   interleaved passes belong to 3f).
> - **Next:** 3e, tilt parallax (the setting and the sensor) and tuning parallax on a device; the
>   drivers already landed in 3b.
>
> **Progress (2026-11-26, 3e and 3f, same branch).** Code, unit tests, docs, the sample scene, the
> preview and an emulator run are done. What needs the A55 is not: its timings, tuning tilt by
> hand, and the final overdraw cap.
>
> - **Tilt (3e).**
>   - `theme/engine/.../shader/SceneTilt.kt`, pure and tested. It reads the game rotation vector's
>     quaternion relative to a rest pose, in the device's own frame, so any way of holding it
>     works. It maps the turn onto the screen's axes for each display rotation: right edge away
>     = +x, top edge towards you = +y, like the focus moving right and down. 12° is full scale,
>     clamped. The rest pose follows the device with a 4 s time constant, so a held tilt drifts
>     back to the middle.
>   - `SceneInputs.tiltX/Y`. `ScenePoser.pull` adds them to the focus's pull and clamps the sum
>     to -1..1, so the overscan always holds.
>   - The setting: `XrossSettings.tiltParallax` (off by default), `settings.json`
>     `performance.tiltParallax`, and Settings ▸ System ▸ Performance ▸ Tilt parallax
>     (`Command.ToggleTiltParallax`).
>   - `ui/TiltSensor.kt` listens at 50 ms (20 Hz) and hands the reading to `WaveRenderer.tiltX/Y`.
>     The renderer eases it with the focus's 0.12 s. `WaveBackground` registers the sensor only
>     while the setting is on and the wanted program is a scene with parallax
>     (`Scene.hasParallax`, shader layers excluded), uncovered, not still, in a resumed activity.
>     Stopping it eases the layers back to the middle.
> - **Poster fallback (3f).**
>   - A layered program the watchdog trips is not failed. `WaveRenderer.retireToPoster` notes
>     why and posts a notice. It decodes the poster (`spec.images`, § 1's path) while the layers
>     keep drawing; once the poster is uploaded, `dropLayers` frees the scene's textures, layer
>     programs and targets. The program then draws as the built-in poster program, which isn't
>     measured again.
>   - A SCENE blocked by the canary starts poster-only, with a notice, instead of failing.
>     `GlProgram.guarded` keeps the canary off a poster-only program.
>   - Try retired shaders again drops poster-only programs, so they compile layered and are
>     measured again; one still blocked by the canary stays a poster.
>   - `SceneSource.canRetireToPoster`: only a poster whose file was found. Without one, the scene
>     is retired to the wave as before.
> - **Sample and preview (3f).**
>   - `tools/shader-preview/examples/scene/` ("Neon Rain", generated art, 0.5 MB) uses every
>     layer type: a scrolling sky with a daylight driver, the video over it in SCREEN, masked
>     snow, a city at FIT/BOTTOM with audio and transition drivers, an ADD neon sprite, ADD embers,
>     a rain shader layer (`rain.frag`, `uBelow` + a drop map) and ADD mist with sway. It is sharp
>     and has a poster.
>   - `tools/shader-preview/scene.js` ports `ScenePoser` and `GlScene.draw` onto the app's own
>     `scene_layer` / `scene_particles` programs. `index.html` opens a scene from `?scene=` or a
>     theme.json's `scenePath`, has tilt x/y and daylight sliders, and a 1920×864 size that a
>     sharp scene selects.
> - **Deviations:**
>   - Tilt drives scenes only; `uFocus` for CUSTOM shaders stays the focus alone.
>   - The sensor stays on while the screen rests (20 fps): tilting is a kind of input, and
>     turning it off would ease the layers back to the middle on every rest.
>   - A retired scene's video player keeps decoding (its layer is no longer drawn). Stopping it
>     needs the retirement to reach `MainActivity`; left for the A55 battery run to judge.
>   - The overdraw budget and the count limits landed in 3a, and the canary as one unit in 3c.
> - **Tests:**
>   - `SceneTiltTest` (4, `:theme:engine`): the first sample is the middle; the axes in all four
>     rotations; the clamp, a zero quaternion and q = −q; the recentring and reset.
>   - `ScenePoseTest` (+1, now 8): tilt adds to the focus and is clamped to the overscan.
>   - `SceneTest` (+3, now 22): which scenes read the sensor, which can retire to their poster,
>     and the sample pack loads with no problem, inside the budget.
>   - `PerformanceSettingsTest` (+checks): the row, its command and text, the default, and the
>     settings.json round trip.
>   - The full `:app:testDebugUnitTest` (1,106) and `:theme:engine:test` (111) pass, and
>     `validateShaders` is clean.
> - **Preview:** the sample draws all 8 layers at 1920×864 with no console errors. Tilt x = 1
>   moves the city layer (parallax 0.5) 29 px left, against 0.03 × 0.5 × 1920 = 28.8 expected.
> - **Emulator** (API 37, `-gpu host`, debug build, the sample as a folder pack, tilt on):
>   - The scene linked and uploaded six textures (1024×512 … 32×32). The video was copied at
>     640×360, and everything draws in order under the XMB.
>   - The watchdog passed (p90 9.01 ms of 10, glFinish; emulator timings mean little).
>   - `dumpsys sensorservice` shows the game rotation vector registered at 50,000 µs while the
>     scene shows. It was unregistered with Settings in front and registered again on return.
> - **Not checked:**
>   - The A55: the sample's watchdog p90 at soft and sharp, deadline jank while navigating, idle
>     CPU, and the poster fallback after a forced slow verdict (three interleaved passes).
>   - Tilt on a real device: its sign, its 12° range and its 4 s recentre, by hand.
>   - A crash in a shader layer tripping the canary.
>   - The sample as an `.xtheme` in `xross_themePacks` (videos are folder-pack only, so it would
>     drop its video layer).

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
      "size": [4, 10], "color": ["#ffb354", "#00ff8024"], "parallax": 0.7 },
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
  counted ×2. Above 8 screens (6 in the first draft; see the progress note), or with more than 16 layers, 1 video file (2 video layers in the first draft; see 3c), 4 shader layers or
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
| 3a ✅ | `scene.json` model, loader, problems, key, poster; unit tests | M |
| 3b ✅ | Quad program; image and sprite layers; fit, anchor, blend, opacity; motions; resolution switch | L |
| 3c ✅ | Video layer (§ 2's copy pass); shader layers with `uBelow` and ping-pong targets | M |
| 3d ✅ | GPU particles; turns off Compose particles under a scene | M–L |
| 3e ◐ | Parallax from `uFocus`; tilt setting; audio, transition and daylight drivers (tuning on the A55 open) | S–M |
| 3f ◐ | Overdraw budget, poster fallback, canary as one unit; A55 timings (open); spec, guide, a sample scene pack | M |

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
   some find it distracting). Built that way in 3e; easy to flip if the A55 says otherwise.
3. **Video from the user's own folders (§ 2 stretch).** In scope for § 2, or later?
4. **`schemaVersion`.** Proposed: stays 2. All the new fields are optional, and an older Xross
   ignores them and shows the wave or wallpaper. There's no hard break worth a version bump.
