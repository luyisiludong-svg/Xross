# Making your own shaders

A starting point for writing a background shader (or a UI filter) for a theme pack: what Xross
gives a shader, a template to copy, the loop from first edit to a measured shader on the phone, and
which built-in to read for each technique. The full reference (every uniform, the feedback and
field passes in detail, the frame budget, the `BLACK_HOLE` worked example) is the
[background shader guide](SHADER_GUIDE.md); where a pack declares its shader is §8 of the
[theme specification](THEME_SPECIFICATION.md).

## Contents

1. [Two kinds of shader](#1-two-kinds-of-shader)
2. [How a frame is drawn](#2-how-a-frame-is-drawn)
3. [What a shader is given](#3-what-a-shader-is-given)
4. [The starter shader](#4-the-starter-shader)
5. [From first edit to the phone](#5-from-first-edit-to-the-phone)
6. [What protects the launcher](#6-what-protects-the-launcher)
7. [Techniques and where to read them](#7-techniques-and-where-to-read-them)
8. [Rules of thumb](#8-rules-of-thumb)
9. [UI filters (AGSL)](#9-ui-filters-agsl)
10. [Where to go next](#10-where-to-go-next)
11. [A painted scene](#11-a-painted-scene)

## 1. Two kinds of shader

| | Background shader | UI filter |
| --- | --- | --- |
| Language | GLSL ES 3.0 fragment shader (`.frag`) | AGSL (`.agsl`), Android 13+ |
| Draws | the animated backdrop under the menu | over the menu itself, as a `RenderEffect` |
| In a pack | `shader.frag`, `"shaderPreset": "CUSTOM"` (or `shaderPath`) | `postprocess.agsl`, `"uiFilter": "CUSTOM"` (or `uiFilterPath`) |
| Built-ins | [`assets/shaders/*.frag`](../app/src/main/assets/shaders/) | [`assets/shaders/ui/*.agsl`](../app/src/main/assets/shaders/ui/) |

Almost all the creative work is in background shaders; most of this document is about them.

## 2. How a frame is drawn

```
fullscreen.vert            one triangle over the whole screen, no vertex buffers
prelude.glsl + your .frag  run once per pixel, writes fragColor
  optional field pass      uPass 0 at a quarter of the size, read back as uField
  optional feedback        last frame's picture bound as uPrev
screen_filter.frag         effects.backgroundFilter (scanlines, CRT, LCD grid) multiplied over it
Compose menu               drawn on top, optionally through a UI filter
```

* [`WaveRenderer`](../app/src/main/java/dev/xross/launcher/ui/WaveRenderer.kt) draws it;
  [`GlShaders.kt`](../app/src/main/java/dev/xross/launcher/ui/GlShaders.kt) compiles programs and
  holds the feedback and field targets.
* [`Shaders.kt`](../theme/engine/src/main/kotlin/dev/xross/launcher/theme/shader/Shaders.kt) turns
  `effects.shaderPreset` / `shaderPath` into a program. A path must be a `.frag`, `.glsl` or `.fs`
  file inside the pack.
* [`prelude.glsl`](../app/src/main/assets/shaders/prelude.glsl) is put in front of your file,
  unless the file starts with its own `#version` (it then declares everything itself). Compile
  errors are reported with your file's line numbers.
* The render size keeps its long side near 1280 px (1280×576 on the Galaxy A55) and is scaled up
  to the screen. `uResolution` is the render size.
* 60 fps while the menu is in use, about 20 fps after 3 s without input, nothing while the menu is
  covered.

## 3. What a shader is given

Declared by the prelude:

| Uniform | Holds | Typical use |
| --- | --- | --- |
| `uTime` | seconds, scaled by the wave speed; wraps at 10 000 | all motion |
| `uResolution` | render size in pixels | coordinates |
| `uColorTop`, `uColorBottom` | the theme's canvas gradient | the base |
| `uAccent`, `uGlow` | accent and glow colours | the "ink" |
| `uTransition` | 1 on a category switch, easing to 0 in about 1 s; ramps 0 → 1 during a `SHADER` boot intro | warps, pulses, reveals |
| `uAudioBass`, `uAudioBeat` | bass 0 … 1 of Xross's own music; 1 for ~100 ms on a beat | music reactivity |
| `uBatteryLevel` | 0 empty … 1 full (1 where unknown) | a low-battery mood |
| `uPrev`, `uFrameDt`, `uFresh` | feedback: last frame, seconds since it, 1 when there is none | trails, afterglow |
| `uField`, `uPass` | the quarter-size field pass | expensive work shared by blocks of pixels |
| `uFocus` | the focused item's centre, 0 … 1, y up, eased | parallax toward what the user looks at |
| `uImage0` … `uImage3`, `uImageSize0` … `uImageSize3` | the pack's `effects.shaderImages` and their sizes | painted scenes, masks, depth maps, noise textures |

The renderer looks up its uniforms by name on every program, so a pack's shader may also declare
these, which the prelude leaves out:

| Uniform | Holds | Used by |
| --- | --- | --- |
| `uniform float uPxPerDp;` | render pixels per dp | `crt.frag`: lines and stripes that land on whole pixels |
| `uniform float uTravel;` | distance gained on category switches, integrated so it never jumps | `starfield.frag` |
| `uBright`, `uLines` (`int`), `uTurb`, `uThick` | daylight brightness and the Settings ▸ Theme ▸ Wave settings sliders | `wave.frag` |

Feedback, the field pass and images are opt-in by use: a shader that never reads `uPrev`, `uField`
or `uImage0` gets no extra buffers, no extra pass and no textures.

## 4. The starter shader

[`tools/shader-preview/examples/starter.frag`](../tools/shader-preview/examples/starter.frag) is a
complete shader in the shape every built-in follows:

1. **Base:** the theme gradient, darkened so the menu reads over it.
2. **Content:** here, four soft blobs drifting in the accent and glow colours, a little brighter
   with the bass.
3. **Reactions:** a flash of glow on `uTransition`.
4. **Finish:** a vignette, then a dither of ±½ of an 8-bit step so long gradients don't band.

It works in `uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y`, centred on the screen
with y from -0.5 to 0.5, so shapes keep their proportions on any aspect ratio.

## 5. From first edit to the phone

1. **Look at it on the desktop.** [`tools/shader-preview/`](../tools/shader-preview/index.html)
   runs a shader the way `WaveRenderer` does: the prelude, both extra passes, a time slider, a
   category-switch button, bass and beat, portrait, and the 20 fps rest rate. Serve a folder above
   the repo and your packs:

   ```bash
   cd .. && python3 -m http.server
   ```

   then open
   `http://localhost:8000/waving/tools/shader-preview/?frag=examples/starter.frag`, adding
   `&theme=<path to a theme.json>` for a pack's palette. Paths are relative to the page.
   `renderAt(seconds)` in the console returns a PNG of that moment. The preview shows the look
   only; its speed says nothing about a phone.
2. **Compile check** with the same validator the build uses:

   ```bash
   cat app/src/main/assets/shaders/prelude.glsl my.frag > /tmp/x.frag && glslangValidator -S frag /tmp/x.frag
   ```

   Built-ins are checked by `./gradlew :app:validateShaders`, part of `./gradlew check`.
3. **Put it in a pack:** `Xross/themes/<id>/shader.frag` with
   `"effects": { "shaderPreset": "CUSTOM" }`, or any file with `"shaderPath": "my.frag"` (a path
   alone means `CUSTOM`). Packs reload when edited, and a shader's identity includes a digest of
   its text, so every saved change compiles fresh.
4. **Measure on a real device with the `profiling` build,** never the debug build or the emulator.
   The `XrossShader` log says `watchdog passed … p90 6.19 ms of 10.00 ms`. Keep the menu active
   while it measures (up and down in a list, never Confirm), and run at least three rounds.

## 6. What protects the launcher

* **A compile or link error** shows a toast with the first error (`Shader my.frag: line 12: …
  · showing the wave`), and `WAVE` draws instead.
* **Too slow:** [`FrameBudgetWatchdog`](../theme/engine/src/main/kotlin/dev/xross/launcher/theme/shader/FrameBudgetWatchdog.kt)
  times a new program over its first ~2 s on screen. Over **10 ms** at the 90th percentile (60% of
  a 60 fps frame), it is retired for the session. The A55's GPU is still at its lowest clock then,
  so aim for 6–7 ms: `STARFIELD` costs 3.0 ms, `BLACK_HOLE` about 6.2 ms.
* **A driver crash:** [`ShaderCanary`](../theme/engine/src/main/kotlin/dev/xross/launcher/theme/shader/ShaderCanary.kt)
  marks a pack's shader before it compiles and before it first draws, and clears the mark after
  120 good frames. A shader still marked when Xross died is blocked until its text changes.

## 7. Techniques and where to read them

| To learn | Read |
| --- | --- |
| Layered sine curves; anti-aliased lines with `fwidth` | [`wave.frag`](../app/src/main/assets/shaders/wave.frag) |
| Particles with depth: a grid of cells, a hash per cell, layers from far to near | [`starfield.frag`](../app/src/main/assets/shaders/starfield.frag) |
| Many objects, skipping the ones far from the pixel early | [`bokeh.frag`](../app/src/main/assets/shaders/bokeh.frag) |
| Pixel-exact scanlines and aperture grille, barrel curvature | [`crt.frag`](../app/src/main/assets/shaders/crt.frag) |
| A character grid with procedural 5×7 glyphs | [`ascii_matrix.frag`](../app/src/main/assets/shaders/ascii_matrix.frag) |
| Ray tracing in the field pass, bloom from `uField`, glow from `uPrev` | [`black_hole.frag`](../app/src/main/assets/shaders/black_hole.frag), [guide §7](SHADER_GUIDE.md#7-black_hole-a-worked-example) |
| A heavy ASCII scene in the field pass | [`mecha.frag`](../app/src/main/assets/shaders/mecha.frag) |

A good order: change the starter's blobs, then replace them with `starfield.frag`'s cell-and-hash
pattern, then add a `uTransition` effect, and only then reach for feedback or the field pass.

## 8. Rules of thumb

1. **The menu is on top.** Keep the picture at about 0.1–0.6 of the gradient, with a vignette, and
   the brightest content away from the left third, where the categories sit.
2. **Move with `uTime`, never a frame count.** Frames are paced and drop to 20 fps at rest.
3. **Feedback:** fade per second, `prev * pow(keep, uFrameDt * 30.0) - 1.5 / 255.0`; ignore `uPrev`
   where `uFresh` is 1; keep any gain from `uPrev` below 1 or the picture brightens without end.
4. **Move shared work to the field pass.** When many pixels compute the same value, compute it once
   per texel in `uPass == 0` and read it with `texelFetch`. That took the black hole from 12 ms to
   5.4 ms.
5. **Bound every loop by a constant** and `break` early (the strand loop in `wave.frag`). Look only
   at the neighbouring cells of a grid, never at every object for every pixel.
6. **Mind the wrap:** `uTime` returns to 0 after 10 000 s. Motion that would jump visibly there
   should use slow rates or `fract`-based cycles.
7. **Check portrait** as well as landscape.

## 9. UI filters (AGSL)

A pack's `postprocess.agsl` with `"uiFilter": "CUSTOM"` must declare `uniform shader content;` (the
menu, premultiplied) and may declare `uniform float2 uResolution;`, `uniform float uTime;` and
`uniform float uPxPerDp;`. Xross sets only the ones the file declares. A filter that reads `uTime`
animates at up to 30 fps while the menu is in use. A compile error turns the filter off with a toast,
and Android 12 and older ignore it.
[`scanlines_ui.agsl`](../app/src/main/assets/shaders/ui/scanlines_ui.agsl) is a 15-line start.
Every pass is a full-screen offscreen render, so on a slow GPU prefer `effects.backgroundFilter`.

## 10. Where to go next

* The [background shader guide](SHADER_GUIDE.md): the reference for everything above.
* §8 of the [theme specification](THEME_SPECIFICATION.md): `shaderPreset`, `shaderPath`,
  `backgroundFilter`, `uiFilter` and `bootIntro`, and how context rules can change the shader per
  category or system.

## 11. A painted scene

Images (`effects.shaderImages`) let a shader start from art instead of from nothing. The worked
example is [`tools/shader-preview/examples/painted/`](../tools/shader-preview/examples/painted/):

```json
"effects": {
  "shaderPath": "scene.frag",
  "shaderImages": ["painting.png", { "path": "depth.png", "wrap": "CLAMP", "filter": "LINEAR" }]
}
```

1. **Paint, then mark.** `painting.png` is the picture. Its alpha is not transparency: it is 1
   everywhere except the lake, where it is 0.5. Xross keeps alpha straight, so a shader can use it
   as a mask. `depth.png` is a greyscale depth map, black far away and white near. Paint it by
   hand, or make it from the layers you painted.
2. **Fit it.** Images are bound upright in `gl_FragCoord.xy / uResolution` space but keep their own
   aspect. `cover()` in `scene.frag` maps screen uv to image uv like a wallpaper's `COVER`, then
   shrinks it a few percent so the parallax never shows an edge.
3. **Parallax.** Read the depth, then offset the lookup by `(uFocus - 0.5) * strength * depth`:
   near things lean away from the focus, the far sky stays put. A slow sine sway keeps it alive
   while the focus rests.
4. **Water.** Where the mask says water, nudge the lookup by two scrolling noises, stretched along
   x, and let `uAudioBass` and `uTransition` raise the swell.
5. **Air.** A low haze tinted with `uGlow`, stronger with distance (`1 - depth`), ties the
   painting to the theme's colours.
6. **Then the usual finish:** dim it for the menu, a vignette, a dither.

Try it in the preview:
`?frag=examples/painted/scene.frag&theme=examples/painted/theme.json`. The preview binds the
images from the `theme.json` and moves `uFocus` with the mouse. The rules for images (slots,
`wrap` / `filter` / `mipmap`, the 2048 px cap and the 8 MP budget) are in
[guide §8](SHADER_GUIDE.md#8-images-and-the-focus).

Keep an eye on cost: every `texture()` is a fetch, and a dependent read (the depth decides where
the painting is read) is slower than a plain one. Measure on the phone as in §5.

