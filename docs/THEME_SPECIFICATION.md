# Theme 2.0 specification

The reference for Xross's look and sound as implemented today (through Phase 2b.7a): the theme pack
format, surfaces, sounds, the background shader and Wave settings, wallpapers, focus frames, icon
masks, particles, filters, variants, overrides, context rules, color layers, quick profiles, the
single-file `.xtheme` archive and the `components` block (status-bar slots, §15).

Related documents: the [user and storage guide](USER_GUIDE.md) (where `Xross/themes/` lives and
the rest of the `Xross/` folder), [`Roadmap.md`](../Roadmap.md) §4.3 and Appendix A (the design
spec and milestones for upcoming Theme 2.0 work), [`STATUS.md`](STATUS.md) (how each feature was
verified, with frame timings) and
[`design/THEME_RICING_AND_WEB_STUDIO.md`](design/THEME_RICING_AND_WEB_STUDIO.md) (the plain-text ricing and web
studio proposal).

## Contents

1. [Where a look comes from](#1-where-a-look-comes-from)
2. [Pack layout](#2-pack-layout)
3. [`theme.json` v2](#3-themejson-v2)
4. [Surfaces](#4-surfaces)
5. [Sounds](#5-sounds)
6. [Wave background and Wave settings](#6-wave-background-and-wave-settings)
7. [`settings.json` display keys](#7-settingsjson-display-keys)
8. [Effects](#8-effects)
9. [Variants, overrides and context rules](#9-variants-overrides-and-context-rules)
10. [Color themes, color layers and quick profiles](#10-color-themes-color-layers-and-quick-profiles)
11. [Your own picks in Settings ▸ Theme](#11-your-own-picks-in-settings--theme)
12. [`.xtheme` archives](#12-xtheme-archives)
13. [Focused game background](#13-focused-game-background)
14. [The Default pack and icon tooling](#14-the-default-pack-and-icon-tooling)
15. [Components](#15-components)

## 1. Where a look comes from

Drop a folder into `Xross/themes/` and pick it with **Settings ▸ Theme ▸ Theme pack** (press
Confirm to step through packs, or Options ▸ Look for new packs after copying one in).

Anything a pack leaves out (a sound, an icon, the fonts) comes from the Default pack, so a pack
can be as small as one `theme.json` and a font.

Layers stack in this order, lowest to highest:

* **Pack structure** (palette, surfaces, effects): Default pack → pack → variant → category
  override → system override → context rules (§9) → your own Settings (a fixed theme color, a
  chosen surface style or any effect picked in Settings ▸ Theme wins over all of them, §11).
* **Colors:** Default pack → pack → variant → overrides → rules → your theme color → quick profile
  → circadian curve and battery tint → game art (§10).
* **Components** (the status bar and the other widgets): Default pack → pack → variant → your
  Settings ▸ Theme ▸ Components, nothing else (§15).

## 2. Pack layout

```
Xross/themes/<theme_id>/
├── theme.json      "schemaVersion": 2, name (required); palette, wave, typography, audio,
│                   icons, effects, surfaces (all optional)
├── surfaces.json   optional: any JSON file listed in "imports" is merged under theme.json
├── fonts/          .ttf/.otf; "typography" names one per role (display, body, mono)
├── icons/          <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
│   └── systems/    console logos for system folders: <platform id>.svg / .png / .webp (§14)
├── sounds/         cursor.wav, category_switch.wav, confirm.wav, drill_in.wav, drill_out.wav,
│                   back.wav, option.wav, ok.wav, system_ng.wav, trophy.wav, 01. opening.wav,
│                   gameboot.wav
├── shader.frag     optional: a CUSTOM background shader (or effects.shaderPath), and up to
│                   four PNG / JPEG / WebP images it samples (effects.shaderImages)
├── postprocess.agsl optional: a CUSTOM UI filter, Android 13+ (or effects.uiFilterPath)
├── wallpaper.png   optional: drawn behind the menu (or .jpg / .webp, or effects.wallpaperPath)
├── *.mp4 / *.webm  optional: a looping background video named by effects.videoPath, at most 30 MB
├── scene.json      optional: a layered scene (effects.scenePath), and the images, videos and
│                   shaders its layers name
├── bgm.ogg         optional: ambient music loop, at most 20 MB (or bgm.mp3, or audio.bgmFile)
└── boot.mp4        optional: cold-start video, at most 30 MB, cut at 15 s (or audio.bootVideo)
```

* File names are matched without regard to case, and a pack can't reach files outside its folder
  (imports included).
* A `rules.json` listed in `imports` holds context rules (§9).

## 3. `theme.json` v2

```json
{
  "schemaVersion": 2,
  "name": "Sakura",
  "author": "you",
  "version": "1.0",
  "imports": ["surfaces.json"],
  "palette": { "canvas": "#2E0A22", "accent": "#FFE6F4", "accentSecondary": "#FFC6EA" },
  "wave": { "speed": 0.8, "lineCount": 7, "turbulence": 1.2, "ribbonThickness": 1.4, "gradient": ["#2E0A22", "#B04C8E"] },
  "typography": { "display": "fonts/Display.ttf", "body": "fonts/Rounded.ttf", "mono": "fonts/Mono.ttf" },
  "audio": { "sounds": { "cursor": "tick.wav" }, "soundGains": { "cursor": 0.6 } },
  "icons": { "album": "disc.svg" },
  "effects": { "surfaceStyle": "petal" }
}
```

* **Only `schemaVersion: 2` packs are read.** A folder with an older `theme.json` is listed as
  skipped (Settings ▸ Theme ▸ Theme pack, and Options ▸ Look for new packs says why).
* **Live editing:** saving any file of the active pack (in Termux, or with `adb push`) reloads it
  within a moment, without leaving Xross. If the edit doesn't parse, Xross keeps the last good
  version and shows the file and line of the error.
* **Palette:** `canvas` is the top of the background, the last `wave.gradient` stop its bottom,
  `accentSecondary` the wave and `accent` the glow. A pack with a palette switches the color
  theme to it; its `wave` values become the wave settings. `palette.focusGold` colours the
  `GOLD_BEZEL` focus frame (§8); `palette.circadianCurve` and `palette.batteryTint` are color
  layers (§10).
* **Fonts:** `display` is used for the clock and category names, `mono` for the battery figure,
  `body` for everything else. Missing roles use `body`; with no `typography`, the first font in
  `fonts/` is used for all three.
* **Sounds:** see §5.
* **Icons:** icon ids are the lower-case glyph names: `settings`, `games`, `apps`, `photo`,
  `music`, `video`, `album`, `artist`, `shuffle`, `search`, `platform`, `game_cart`, `game_disc`,
  `favorites`, `recent`, `handheld`, `folder`, `storage`, `controls`, `emulators`, `display`,
  `sound`, `system`, `add`, `refresh`, `home`, `info`, `download`, `network`, `users`, `trophy`.
  Icons are drawn as white silhouettes with the XMB gloss, so color doesn't matter. SVGs may use
  paths, rects, circles, ellipses and polygons with translate/scale transforms; black fills are
  treated as a backdrop and dropped. PNG/WebP icons are scaled to 256 px.
* **Effects:** the `effects` block is described in §8; unknown keys are reported (§8,
  [`effects` reference](#effects-reference)).

## 4. Surfaces

`surfaces.json` (or a `surfaces` block in `theme.json`) defines how cards, panels and toasts are
drawn, layer by layer:

```json
{
  "surfaces": {
    "petal": {
      "shape": { "type": "CHAMFERED", "cutDp": [12, 0, 12, 0] },
      "fill": { "type": "LINEAR_GRADIENT", "angle": 180, "stops": [["#3A1030", 0.0, 0.85], ["#14060F", 1.0, 0.9]] },
      "sheen": { "heightPercent": 0.35, "gradient": [["#FFFFFF", 0.12], ["#FFFFFF", 0.0]] },
      "borders": [{ "type": "OUTER", "widthDp": 1.5, "brush": { "type": "SWEEP_GRADIENT", "colors": ["#FFC6EA", "#B04C8E", "#FFC6EA"] } }],
      "shadows": [{ "offsetDp": [0, 4], "blurDp": 12, "color": "#000000", "opacity": 0.4 }],
      "glow": { "color": "#FFC6EA", "radiusDp": 10, "intensity": 0.5 }
    }
  }
}
```

* **Shapes:** `ROUNDED` (no `radiusDp`: each card keeps its own corners), `CHAMFERED` (`cutDp`:
  top-left, top-right, bottom-right, bottom-left), `RECTANGLE`.
* **Fills and brushes:** `SOLID`, `LINEAR_GRADIENT` (CSS angles, stops `[color, position,
  opacity]`), `SWEEP_GRADIENT`.
* **Borders:** `INNER`, `OUTER` or a `side` (`TOP`, `LEFT`, `BOTTOM`, `RIGHT`).
* **Limits:** at most 3 borders and 2 shadows are drawn, glows up to 24 dp.
* **Built-in surfaces:** every pack also gets `FLAT_GLASS`, `Y2K_GLOSSY_BEZEL`, `OLED_TRUE_BLACK`
  and `EINK_HIGH_CONTRAST`, and **Settings ▸ Theme ▸ Surface style** picks any of them over the
  pack's choice (`display.surfaceStyle`).
* **Frosted glass (`backdropBlurDp`, 0–48):** frosts a surface. Over hero art or a wallpaper it
  shows a blurred slice of the image behind it under its fill; over the live shader it keeps its
  tint. The blur is made once per image, small and off the main thread, so it costs nothing per
  frame and works on every Android version. `FLAT_GLASS` uses 14.

## 5. Sounds

Sounds (`audio/UiSounds.kt`) come from the active theme pack:

| Event | Role | Pack file | If the pack has no file |
| --- | --- | --- | --- |
| A step up or down a list, or through an overlay | `cursor` | `cursor.wav` | Default pack |
| Left / Right between categories | `category_switch` | `category_switch.wav` | the pack's `cursor` |
| Running an item, confirming a choice | `confirm` | `confirm.wav` | Default pack (its `cursor.wav`) |
| Into a folder or sub-list | `drill_in` | `drill_in.wav` | the pack's `confirm` |
| Out of a folder or sub-list | `drill_out` | `drill_out.wav` | the pack's `back` |
| Back, closing an overlay | `back` | `back.wav` | Default pack |
| Options menu | `option` | `option.wav` | Default pack (its `cursor.wav`) |
| A setting saved, an action that worked | `ok` | `ok.wav` | Default pack |
| Errors and actions that aren't allowed (Options on a row without a menu, Confirm on an information row, a slider at its end) | `system_ng` | `system_ng.wav` | Default pack |
| Achievement unlocked | `trophy` | `trophy.wav` | Default pack |
| Startup, with the fade-in | `opening` | `01. opening.wav` | Default pack |
| Game start | `gameboot` | `gameboot.wav` | Default pack |

* In `audio.sounds`, a bare file name is looked up in `sounds/`, a path ("audio/tick.wav") from the
  pack root. `audio.soundGains` sets a role's level, 0–1 (it can only lower a sample, so ship
  files at full level: short clicks peaking near −3 dBFS, longer cues near −16 LUFS).
* A role with a fallback (`category_switch`, `drill_in`, `drill_out`) that the pack has no file for
  plays the fallback's file **from the same pack**, at the fallback's gain, before the Default
  pack's: a pack with only `cursor.wav` ticks with it on category switches too.
* Role keys ignore case, `_` and `-` (`system_ng`, `systemNg` and `SYSTEM_NG` are one key), and
  the Theme 2.0 names `cancel`, `optionOpen`, `gameLaunch` and `achievementUnlock` are accepted for
  `back`, `option`, `gameboot` and `trophy`. Unknown role keys, and unknown `audio` properties, are
  listed with the pack's problems.
* **Gains through the cascade:** variants, `categoryOverrides`, `systemOverrides` and `rules`
  (`apply`) may carry `"audio": { "soundGains": { … } }`; the last layer that names a role wins
  (§7 order), and so may `bgmFile`. Only gains and the music loop change there: sound files,
  panning and jitter are the pack's.
* `audio.panAmount` (0–1, default 0.35): effects pan with the crosshair's X position across the
  screen, times this amount, so a centred XMB stays centred; a category switch leans a little
  further toward the direction of travel. 0 keeps every effect centred.
* `audio.cursorJitter` (default false): the `cursor` and `category_switch` ticks play at a random
  rate between 0.98 and 1.02, so a long scroll sounds less mechanical.
* **Theme music** (`audio.bgmFile`, alias `bgm`): a loop played on repeat behind the menu, gapless.
  Unset, a `bgm.ogg` or `bgm.mp3` at the pack root; `"none"` turns it off. Any of `.ogg`, `.mp3`,
  `.m4a`, `.aac`, `.opus`, `.flac`, `.wav`, at most 20 MB, from the pack root. Variants, overrides
  and rules may name another loop or `none` (`"audio": { "bgmFile": "games.ogg" }` in
  `categoryOverrides.GAMES` gives Games its own); a change while it plays crossfades over 600 ms.
  It plays at Settings ▸ Sound ▸ Theme music (`sound.bgmVolume`, 60% by default) only while
  nothing else does: no song in Xross's music player (a paused song counts; stop it from Now
  Playing with ↓), no other app playing media, no game, no full-screen video. It takes no audio
  focus, so it never pauses another app. Under a game's video snap it pauses, or plays at 20% with
  Music during video snaps ▸ Lower; it stops for a game and starts over afterwards. Master loops
  near −16 LUFS like the cues, a little quieter if the pack is meant as a backdrop.
* **Several tracks** (`audio.bgmFiles`, `audio.bgmOrder`, 0.2.2.0): a list of audio files from the
  pack root, at most 50, each at most 20 MB, played one after another with a 1.5 s crossfade.
  `bgmOrder` is `SHUFFLE` (the default: no track repeats until all have played) or `IN_ORDER`.
  `bgmFile` wins when both are set; only the pack's base reads the list (variants, overrides and
  rules still name one loop or `none`). After a game the list goes on with the next track instead
  of starting over. Export theme and Share as `.xtheme` copy every listed file.
  `"audio": { "bgmFiles": ["music/day.ogg", "music/night.ogg"], "bgmOrder": "IN_ORDER" }`
* The user's choice in **Settings ▸ Theme ▸ Theme music** (`display.bgm`, §7) wins over all of this.
* **Boot video** (`audio.bootVideo`, alias `boot`): played full-screen on a cold start in place of
  the `opening` cue (§8, Boot intro). Unset, a `boot.mp4` at the pack root; `"none"` turns it
  off. `.mp4`, `.m4v`, `.webm`, `.mkv` or `.3gp`, at most 30 MB, from the pack root; it is cut at
  15 s. Its sound plays at Settings ▸ Sound ▸ Startup and game cues, without audio focus. Only the
  pack's base names one (variants, overrides and rules that do are reported). Folder packs only.

```json
"audio": {
  "sounds": { "cursor": "tick.wav", "categorySwitch": "swish.wav" },
  "soundGains": { "cursor": 0.5, "drillIn": 0.7 },
  "panAmount": 0.35,
  "cursorJitter": true,
  "bgmFile": "audio/ambient_loop.ogg"
}
```

* **Settings ▸ Sound** toggles effects, volume, the startup sound and the game-start sound.

## 6. Wave background and Wave settings

The default background is a GLSL ES 3.0 ribbon shader with a day/night palette. It pauses whenever
the launcher isn't in front, and stops rendering while game art, a video snap or an opaque
wallpaper covers it. Packs can switch to another preset or their own shader (§8, `shaderPreset`).

**Wave settings** (Settings ▸ Theme ▸ Wave settings): sliders for the shader uniforms, previewed
live behind the panel and saved in `settings.json` under `display`:

| Setting | `settings.json` key | Range | Default |
| --- | --- | --- | --- |
| Speed | `waveSpeed` | 0.5–2.0× | 1.0 |
| Ribbon lines | `waveLines` | 0–12 | 5 |
| Turbulence (amplitude) | `waveTurbulence` | 0–2× | 1.0 |
| Ribbon thickness | `waveThickness` | 0.5–3× | 1.0 |
| Colors | `waveTint` | `THEME`, `AURORA`, `SUNSET`, `OCEAN`, `SAKURA`, `PASTEL`, `MONO`, `CUSTOM` | `THEME` |
| Custom gradient | `waveColors` `{top, bottom, wave}` | `#RRGGBB` | Lavender |

**Reset to theme defaults** restores the active pack's `wave` values.

A pack's `shader.frag` can react to music played by Xross's own player through the `uAudioBass` and
`uAudioBeat` uniforms (no microphone permission); see STATUS [§3.23](phase-2b/2b.4/STATUS.md#s3.23) for how it
was measured. Writing a shader, the full uniform list, the optional feedback (`uPrev`) and field
(`uField`) passes and the frame budget are in the [background shader guide](SHADER_GUIDE.md).

## 7. `settings.json` display keys

The look-related keys in the `display` block of `Xross/config/settings.json` (from
[`ConfigFiles.kt`](../app/src/main/java/dev/xross/launcher/config/ConfigFiles.kt)). Each is set
from Settings ▸ Theme; editing the file by hand works too and is picked up on resume.

| Key | Values | Set from |
| --- | --- | --- |
| `theme` | a color theme name, or `PACK` for the pack's own palette | Settings ▸ Theme (§10) |
| `themePack` | a folder name in `Xross/themes/`, or `default` | Theme pack (§1) |
| `themeVariant` | a variant id; empty = the pack's base look | Theme variant (§9) |
| `surfaceStyle` | a built-in or pack surface id; empty = the pack's | Surface style (§4) |
| `customWallpaper` | a `content://` URI or a file path; empty = the pack's wallpaper | Xross wallpaper (§8) |
| `wallpaperRotation` | `{ "folder": path, "album": a Photo album's name, "interval": OFF / MIN_1 / MIN_5 / MIN_15 / MIN_60 / FRONT }`; `album` wins over `folder`; both empty turns it off; wins over `customWallpaper` | Xross wallpaper ▸ Pick a folder… / An album, or Photo ▸ an album ▸ Options (§8) |
| `bgm` | an audio file's path, a folder's path, `playlist:<name>` for a Music playlist, or `none`; empty = the pack's music | Theme music (§5), or Music ▸ Playlists ▸ Options |
| `bgmOrder` | `SHUFFLE` or `IN_ORDER`, for a folder | Theme music ▸ Order (§5) |
| `components` | `statusBarLeft`, `statusBarRight`, `batteryMode`, `clockFormat`, …; `null` = the pack's | Settings ▸ Theme ▸ Components (§15) |
| `effects` | `shaderPreset`, `backgroundFilter`, `uiFilter`, `focusFrameStyle`, `foregroundParticles`, `iconMask`, `bootIntro`, `launchCurtain`; empty = the pack's | Effect rows (§11) |
| `quickProfile` | `NONE`, `OLED_TRUE_BLACK`, `Y2K_CYBER_PASTEL`, `MONOCHROME_EINK` | Quick profile (§10) |
| `adaptiveGamePalette`, `circadianCurve`, `batteryTint` | `true` / `false`; `null` follows the pack | Adaptive colors / Circadian curve / Battery alert tint (§10) |
| `wave` | `true` / `false` | Wave background on or off |
| `waveSpeed`, `waveLines`, `waveTurbulence`, `waveThickness`, `waveTint`, `waveColors` | see §6 | Wave settings (§6) |
| `buttonHints` | `true` / `false` | Hides or shows the button-hint pill |
| `showFileFormat` | `true` / `false` | File format in game rows (Menu and profile) |
| `showSystemLogos` | `true` / `false` | Console logos on system folders (Menu and profile, §14) |
| `systemLogoStyle` | `PACK`, `WHITE`, `COLOR`, `MONO`, `OUTLINED`, `DARK_COLOR`, `DARK_MONO`, `BLACK` | Which logos: the pack's, or one of Xross's bundled sets (§14) |

Outside `display`, `profile.avatarStyle` holds the avatar's border and glow (Settings ▸ Theme ▸
Profile customization).

## 8. Effects

### Wallpaper

A `wallpaper.png` (or `.jpg` / `.webp`) at the pack root is drawn full-bleed behind the menu, over
the background shader and under the focused game's hero art; `effects.wallpaperPath` names another
file in the pack (`wallpaper` is accepted too), and `"none"` turns it off. Variants, overrides and
rules may set it like the shader. An opaque wallpaper stops the shader's GL loop while it covers
the screen.

**Photo ▸ Options ▸ Set as Xross wallpaper** (in a photo list or the viewer) puts a photo there
instead of the pack's, saved as `display.customWallpaper` (a `content://` URI or a file path) and
separate from the Android wallpaper; **Settings ▸ Theme ▸ Xross wallpaper** goes back to the
theme's.

**Xross wallpaper ▸ Pick a folder…** (0.2.2.0) lets the pictures of a folder (PNG, JPEG, WebP, in
file-name order) take turns, every 1, 5, 15 or 60 minutes or each time Xross comes to the front
(**Change**), saved as `display.wallpaperRotation`. A change never wakes a resting screen: when it
is due it waits for the next button press or touch, or for Xross coming back to the front. The
next picture is decoded and blurred for the frosted surfaces before it crossfades in over
600 ms; a deleted picture is skipped. Reset to theme defaults clears it. A Photo album works the
same way (**Xross wallpaper ▸ An album**, or **Photo ▸ an album ▸ Options ▸ Use as Xross
wallpaper**): its pictures, from every folder of that name, by file name; it follows the album as
photos are added or removed.

### Shader images

A pack's own shader (`CUSTOM`) can sample up to four images from the pack, listed in
`effects.shaderImages` next to `shaderPath`, in any layer that can set a shader:

```json
"effects": {
  "shaderPath": "scene.frag",
  "shaderImages": ["art/painting.webp", { "path": "art/depth.png", "wrap": "CLAMP" }]
}
```

They are bound as `uImage0` … `uImage3` in order, upright and with straight alpha, each capped at
2048 px on its long side, 8 megapixels per shader in all. `uFocus` gives the shader the focused
item's position for parallax. A path that leaves the pack or isn't PNG, JPEG or WebP, a bad `wrap`
or `filter`, images with a preset shader, and an image that's missing or over the budget are
listed with the pack's problems; the shader still draws, with that slot empty. Replacing an image
is picked up by hot reload like an edit to the shader. The details, and a worked example, are in
[Shader guide §8](SHADER_GUIDE.md#8-images-and-the-focus).

### Background video

`effects.videoPath` (alias `video`) names a looping, muted video in the pack: an MP4 (H.264 or
HEVC) or a WebM (VP9), at most 30 MB. `effects.videoFit` places it: `FILL` (the default; `COVER`
is accepted too), `FIT`, `STRETCH` or `CENTER`. `"none"` turns a video off.

```json
"effects": { "videoPath": "loops/rain.mp4", "videoFit": "FILL" }
```

* **Which shader shows it.** With the look's shader left at the wave, the built-in `VIDEO` preset
  draws the video (the pack needs no shader key). A `CUSTOM` shader reads it as `uVideo` instead,
  next to its images; the other presets ignore it. The background filter is drawn over it as over
  any shader. `VIDEO` isn't offered in Settings ▸ Theme ▸ Background shader: it needs the pack's
  video.
* **Cascade.** Variants, overrides and rules may set it like the wallpaper. A layer that sets only
  `videoFit` keeps the video and changes its placement.
* **It is the pack's art.** While the video shows, the pack's own wallpaper isn't drawn over it. A
  wallpaper you chose (Set as Xross wallpaper, a folder or an album) still wins: it covers the
  screen, and the video holds its frame behind it.
* **When it plays.** It pauses on its last frame while something opaque covers the background,
  while a video snap or the boot video plays, and when the screen rests: Settings ▸ System ▸
  Performance ▸ Standby motion, but never sooner than 10 s after the last button (with `Never`
  it keeps playing). Still (E-Ink) mode shows its first frame and never plays. When a game or app
  comes to the front, the player lets go of its decoder; back in Xross the loop resumes where it
  was.
* **Problems.** A path that leaves the pack or isn't MP4 / WebM, a missing or oversized file, a
  bad `videoFit`, and a video in the built-in pack are listed with the pack's problems. A file the
  device can't decode shows a notice and the look's wallpaper, else the wave; one larger than
  1920 px on its long side plays, with a notice.
* **Size.** An `.xtheme` is capped at 50 MB, so keep loops short: 720p, 10–20 s, about 2–4 Mbit/s.
  Shaders sample it at most 1280 px on its long side.

### Layered scenes

*In progress (Live backgrounds § 3).* The format below is read and checked today, and the scene
shows as its **poster**; the layers themselves are drawn from a later update on. A pack written
against this section keeps working as the renderer lands.

`effects.scenePath` (alias `scene`) names a `.json` file in the pack that describes a stack of
layers, drawn bottom to top. `"shaderPreset": "SCENE"` alone reads `scene.json`. A scene is one
background program: it cascades like a shader (variants, overrides and rules may each pick another
scene, or a shader instead), and it replaces the other shader keys of the same layer
(`shaderPreset`, `shaderPath` and `shaderImages` next to a `scenePath` are listed as problems).

```json
{
  "resolution": "sharp",
  "poster": "poster.webp",
  "layers": [
    { "type": "image",  "path": "sky.webp", "fit": "COVER", "parallax": 0.1, "scroll": [4, 0], "wrap": "REPEAT" },
    { "type": "video",  "path": "clouds.mp4", "blend": "SCREEN", "opacity": 0.6 },
    { "type": "image",  "path": "city.png", "anchor": "BOTTOM", "parallax": 0.4, "audio": { "scale": 0.02 } },
    { "type": "sprite", "path": "neon.png", "frames": [4, 2], "fps": 8, "rect": [0.62, 0.40, 0.12, 0.10] },
    { "type": "shader", "path": "rain_glass.frag", "images": ["drops.png"] },
    { "type": "particles", "path": "ember.png", "rate": 30, "life": [3, 6], "velocity": [[-5, 5], [-40, -20]],
      "size": [4, 10], "color": ["#ffb354", "#00ff8024"], "parallax": 0.7 },
    { "type": "image",  "path": "rain_fg.png", "parallax": 1.0, "scroll": [0, 300], "wrap": "REPEAT", "blend": "ADD" }
  ]
}
```

Every path is relative to the pack root, like the rest of `theme.json`.

* **`resolution`:** `soft` (the default, 1280 px on the long side) or `sharp` (1920 px).
* **`poster`:** an image that stands in for the scene while it loads, in Still mode and if the
  scene is retired as too slow. Without one, the first `image` layer is the poster.
* **Layer types:**

  | `type` | Its own keys |
  | --- | --- |
  | `image` | `path` (PNG, JPEG or WebP), `wrap` (`CLAMP`, `REPEAT`, `MIRROR`) |
  | `video` | `path` (MP4 or WebM, folder packs only, at most 30 MB), looped and muted |
  | `sprite` | `path`, `frames` `[columns, rows]` (1–64 each), `fps` (default 8), `count` (default every cell) |
  | `shader` | `path` (a `.frag`), `images` (up to four, as `effects.shaderImages`); it reads the layers below it |
  | `particles` | `path` (optional; a soft dot without one), `rate` per second (required), `life` `[min, max]` s, `velocity` `[[xMin, xMax], [yMin, yMax]]` px/s, `size` `[min, max]` px, `color` `[start, end]` |

* **Keys every layer has:** `opacity` (0–1), `blend` (`NORMAL`, `ADD`, `SCREEN`, `MULTIPLY`),
  `rect` `[x, y, width, height]` (fractions of the screen from its top-left corner) or `fit`
  (`COVER` / `FILL`, `FIT`, `STRETCH`, `CENTER`) with `anchor` (`CENTER`, `TOP`, `BOTTOM`, `LEFT`,
  `RIGHT`, `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`), `parallax` (0 stays still, 1
  moves the most with the focus), `mask` (an image whose alpha masks the layer).
* **Motion:** `scroll` `[x, y]` in texels per second; `sway` (an angle in degrees, or
  `{ "angle", "offset": [x, y], "period" }`); `pulse` (a scale amount, or
  `{ "scale", "opacity", "period" }`). `audio`, `transition` and `daylight` each take
  `{ "scale", "opacity", "offset", "angle" }`: what that input adds at its full strength (the
  music's bass, a category switch, noon).
* **Units:** pixels (`velocity`, `size`, `offset`) are at 1280 px on the screen's long side, y
  down. Colours are `#rrggbb`, or `#aarrggbb` with alpha, as everywhere in a theme.
* **Budget:** at most 16 layers, 2 video layers, 4 shader layers, 2,000 particles (`rate` × the
  longest `life`) and 8 screens of pixels per frame (each layer counts the part of the screen it
  covers, a shader layer twice). Layers are taken bottom to top while they fit; one that doesn't
  is left out with a problem, and a cheaper one above it may still fit.
* **Problems** are listed per layer, with the layer's place in the file:
  `scene.json: layers[3].frames: give [columns, rows], …`. An unknown key or a bad value falls back
  to its default; an unknown `type` or a bad or missing `path` leaves the layer out. A missing file
  is listed and its layer draws nothing. A file that isn't valid JSON, or keeps no usable layer,
  is no scene: the look shows the wave.
* **It is the pack's art.** While a scene shows, the pack's own wallpaper isn't drawn over it. A
  wallpaper you chose still wins, as over any shader.
* **Hot reload.** Editing `scene.json` or any file it names reloads the scene.
* A scene can't be picked in Settings ▸ Theme ▸ Background shader: it needs the pack's files.

### Focus frames and app icons

`effects.focusFrameStyle` (alias `focusFrame`) frames the focused item:

* `BREATHING_HALO`: the default soft, pulsing glow.
* `STATIC_NEON`: a crisp glow in the accent, or the wave color when the accent is white, that holds
  still.
* `HARD_SHADOW`: a solid shadow 2 dp down and right, no blur.
* `GOLD_BEZEL`: a metal frame in `palette.focusGold` around game tiles and RetroAchievements
  badges.

Only the breathing halo animates; the others draw no frames while the cursor rests.

`effects.iconMask` (`IconStyler`) cuts installed apps' icons to `SQUIRCLE`, `CIRCLE` or
`Y2K_CHROME_FRAME` (a squircle in a glossy chrome bezel), or leaves them as shipped (`NONE`):
adaptive icons are rebuilt from their layers, legacy icons with transparent corners are shrunk onto
a plate tinted from their own colors. `effects.iconFilter` recolors them: `DUOTONE` (shadows in the
canvas color, highlights in the accent) or `MONOCHROME` (black to the accent). Each icon is drawn
once per mask, filter, palette and size and then cached, so scrolling the Apps list costs nothing.

Names are matched ignoring case and dashes (`gold-bezel`).

### Foreground particles

`effects.foregroundParticles` (alias `particles`; `AmbientParticles`) drifts a light layer of
particles over the wallpaper and hero art, under the menu:

* `SNOW`: flakes falling and swaying.
* `BOKEH`: large faint discs in the accent rising slowly, nearer ones bigger and fainter.
* `DUST_MOTES`: specks drifting and twinkling, brightest in a shaft of light from the top left.
* `NONE` by default.

At most 48 particles. They ease to a stop after 10 s without input and draw nothing until the next
key or touch, stop in the background, and are hidden under a playing video snap, full-screen photos
and video, and the Monochrome E-Ink profile.

### Background filter

`effects.backgroundFilter` draws a full-screen filter over the background shader, inside its GL
pipeline (the shader is a separate surface under the menu, so this is the one place a filter
reaches it):

* `SCANLINES`: dark lines about 1.5 dp apart.
* `CRT`: scanlines, an RGB aperture grille and the tube's vignette.
* `LCD_GRID`: a handheld's pixel grid, cells about 3 dp across.
* `NONE` by default.

Lines and stripes are sized in dp and snapped to whole pixels of the shader's render target
(density × render scale), so they don't shimmer or beat into moiré. It only darkens the background;
the menu stays crisp. Skipped over the `CRT` preset, which draws its own (and now sizes them the
same way).

### UI filter (experimental, Android 13+)

`effects.uiFilter` (alias `postProcess`) runs an AGSL shader over the whole menu as a
`RenderEffect`:

* `SCANLINES_UI`.
* `BLOOM`: bright text and glows bleed a soft halo.
* `CUSTOM` with the pack's `postprocess.agsl` (or `effects.uiFilterPath`, alias `postProcessPath`;
  a path alone means `CUSTOM`).

A custom file must declare `uniform shader content;` (the menu) and may declare
`uniform float2 uResolution;`, `uniform float uTime;` (seconds) and `uniform float uPxPerDp;`;
Xross sets only those it declares. The built-in filters are static, so a resting menu draws no
frames; a custom one that reads `uTime` animates at up to 30 fps while the menu is in use and rests
with it (3 s without input, in the background, under E-Ink). A compile error turns the filter off
with a toast. Android 12 and below ignore it. Every pass costs a full-screen offscreen render, so
prefer the background filter on slow GPUs.

### Boot intro

`effects.bootIntro` (alias `intro`) is what a cold start shows (once per process start; a warm
start shows the menu straight away):

* `VIDEO`: the pack's boot video (§5), full-screen and fitted on black, with its own sound
  instead of the `opening` cue; theme music waits for it. The default when the pack has one, and
  a pack's video beats its own `SHADER`.
* `SHADER`: the background shader rises from black over 2.5 s while `uTransition` ramps from 0 to
  1 (a pack shader can build a warp or a reveal on it; it then eases back to 0 as after a category
  switch), under the `opening` cue, the menu fading in over the last 1.3 s.
* `OFF` (aliases `FADE`, `NONE`): the menu's fade-in under the `opening` cue.

Any button or touch ends an intro with a 300 ms crossfade, and the menu takes the next input (a
button pressed before the intro starts skips it). It is off under Monochrome E-Ink, while
Settings ▸ Sound ▸ Startup sound is off or Startup and game cues is at 0%, and (`SHADER`) while
Wave background is off. Debug builds log `XrossBoot` (`mode=VIDEO file=boot.mp4 skipped=true at=1840 ms`).

### Launch curtain

`effects.launchCurtain` (alias `curtain`) is drawn over the 1.1 s hand-off to the emulator while
`gameboot` plays, with the game's title glowing in over it:

* `BLOOM` (default, alias `GLOW`): the menu dims under a glow blooming from the centre.
* `FADE_BLACK` (aliases `FADE`, `BLACK`): the menu fades to black.
* `IRIS` (alias `CIRCLE`): a circle closes on the focused tile, black outside it.
* `FLASH` (alias `WHITE_FLASH`): a white flash that falls to black.

Under Monochrome E-Ink it is plain black at once. Variants, overrides and rules may set it (a
system override can give that system's games their own curtain).

### `effects` reference

Every key a pack's `effects` block (in the base, a variant, an override or a rule's `apply`)
recognizes. Any other key is listed with the pack's problems (Settings ▸ Theme ▸ Theme pack)
instead of being ignored silently, so a typo shows up.

| Key (aliases) | Values | Since |
| --- | --- | --- |
| `surfaceStyle` | a preset or a surface the pack defines | 2b.2 |
| `adaptiveGamePalette` | `true` / `false` | 2b.3 |
| `shaderPreset` (`shader`) | `WAVE`, `ASCII_MATRIX`, `BOKEH`, `STARFIELD`, `CRT`, `BLACK_HOLE`, `MECHA`, `VIDEO`, `SCENE`, `CUSTOM` | 2b.4a (`BLACK_HOLE` 2026-11-16, `MECHA` 2026-11-17, `VIDEO` Live backgrounds § 2, `SCENE` § 3) |
| `shaderPath` | a `.frag` in the pack (implies `CUSTOM`) | 2b.4a |
| `shaderImages` | up to four images in the pack for a `CUSTOM` shader (`uImage0..3`): paths, or `{ "path", "wrap": CLAMP / REPEAT / MIRROR, "filter": LINEAR / NEAREST, "mipmap" }` ([Shader guide §8](SHADER_GUIDE.md#8-images-and-the-focus)) | Live backgrounds § 1 |
| `scenePath` (`scene`) | a `.json` scene in the pack (implies `SCENE`; [Layered scenes](#layered-scenes)) | Live backgrounds § 3 |
| `wallpaperPath` (`wallpaper`) | a PNG, JPEG or WebP in the pack, or `none` | 2b.4c |
| `videoPath` (`video`) | an MP4 or WebM in the pack, or `none` ([Background video](#background-video)) | Live backgrounds § 2 |
| `videoFit` | `FILL` (`COVER`), `FIT`, `STRETCH`, `CENTER` | Live backgrounds § 2 |
| `focusFrameStyle` (`focusFrame`) | `BREATHING_HALO`, `STATIC_NEON`, `HARD_SHADOW`, `GOLD_BEZEL` | 2b.4d |
| `iconMask` | `NONE`, `SQUIRCLE`, `CIRCLE`, `Y2K_CHROME_FRAME` | 2b.4d |
| `iconFilter` | `NONE`, `DUOTONE`, `MONOCHROME` | 2b.4d |
| `foregroundParticles` (`particles`) | `NONE`, `SNOW`, `BOKEH`, `DUST_MOTES` | 2b.4e |
| `backgroundFilter` | `NONE`, `SCANLINES`, `CRT`, `LCD_GRID` | 2b.4f |
| `uiFilter` (`postProcess`) | `NONE`, `SCANLINES_UI`, `BLOOM`, `CUSTOM` (Android 13+) | 2b.4f |
| `uiFilterPath` (`postProcessPath`) | an `.agsl` in the pack (implies `CUSTOM`) | 2b.4f |
| `bootIntro` (`intro`) | `VIDEO`, `SHADER`, `OFF` | 2b.5d |
| `launchCurtain` (`curtain`) | `BLOOM`, `FADE_BLACK`, `IRIS`, `FLASH` | 2b.5d |

## 9. Variants, overrides and context rules

### Variants

`"variants": [{ "id": "bios-matrix", "name": "BIOS Matrix", "palette": {…}, "effects": {
"surfaceStyle": "OLED_TRUE_BLACK" } }]` adds colorways that share the pack's fonts, icons and
sounds. **Settings ▸ Theme ▸ Theme variant** (shown only for packs that have them) steps through
them; the choice is saved as `display.themeVariant` in `settings.json`.

### Category and system overrides

`"categoryOverrides": { "MUSIC": {…} }` (`GAMES`, `MUSIC`, `PHOTO`, `VIDEO`, `APPS`, `SETTINGS`)
applies while that category is open; `"systemOverrides": { "dreamcast": {…} }` (a platform id or a
folder alias such as `dc`) while one of that system's games is focused, after the cursor rests for
a quarter second.

Each may set `palette` roles, `effects.surfaceStyle`, `effects.shaderPreset` (with `shaderPath`,
`shaderImages` or `scenePath`), `effects.wallpaperPath`, `effects.videoPath`, `effects.videoFit`, `effects.focusFrameStyle`, `iconMask`, `iconFilter`,
`foregroundParticles`, `backgroundFilter`, `uiFilter`, `bootIntro`, `launchCurtain` and `cardAspectRatio` (a system's aspect
shapes that system's game tiles), `audio.soundGains` and `audio.bgmFile` (§5).

Precedence, lowest to highest: Default pack → pack → variant → category → system → your own
Settings (a fixed theme color, a chosen surface style or any effect picked in Settings ▸ Theme wins
over all of them). Overrides patch a pack palette; they don't add one to a pack without colors. The
wave crossfades between palettes.

### Context rules

`"rules": [{ "match": {…}, "apply": {…} }]` inline, or in a `rules.json` listed in `imports`
(imported rules come first, then the manifest's; they add up).

* `match` takes any of `category` (`MUSIC`), `system` (a platform id or folder alias), `battery`
  (`<= 20`, `< 15`, `>= 80`, `charging`, `discharging`, `full`) and `time` (`21:00..06:00`,
  wrapping past midnight; start included, end excluded); every selector given must hold.
* `apply` takes `palette` roles, any `effects` key (§8), `cardAspectRatio` and any `layout` key.
* Rules sit above the variant and the overrides, in file order, so the last matching rule wins.
* A rule whose selector can't be read is reported and dropped, never applied everywhere.
* Battery and time are re-checked when they change and once a minute, never per frame.

## 10. Color themes, color layers and quick profiles

### Color themes

Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, Original (a color per month), plus
"Theme pack" when the active pack has its own palette (Settings ▸ Theme, `display.theme`). The same
section hides the button-hint pill.

### Color layers

* `palette.circadianCurve`: an hourly curve, cool cyan 06–11, azure 11–18, amber and rose gold
  18–21, indigo 21–06, blended across each boundary.
* `palette.batteryTint`: accent and glow shift to amber, then red, below 20%; a soft green pulse
  while charging.
* `effects.adaptiveGamePalette`: the focused game's hero or wheel art drives the wave, focus halos,
  avatar glow and battery outline, 250 ms after the cursor rests.

A pack sets the defaults (off, off, on); **Settings ▸ Theme ▸ Adaptive colors / Circadian curve /
Battery alert tint** override them (`display.adaptiveGamePalette`, `display.circadianCurve`,
`display.batteryTint`; `null` follows the pack). Rules can switch them too.

### Quick profiles

**Settings ▸ Theme ▸ Quick profile** (`display.quickProfile`):

* *OLED True Black*: pure black canvas, no surface fills, glowing outlines; game art still drives
  the accent.
* *Y2K Cyber-Pastel*: lavender, lilac and strawberry, glossy bezels.
* *Monochrome E-Ink*: black and white, no translucency, instant motion, every pulse and glow
  frozen, the wave a single still frame, color layers off.

Precedence, lowest to highest: Default pack → pack → variant → overrides → rules → your theme color
→ quick profile → circadian curve and battery tint → game art.

## 11. Your own picks in Settings ▸ Theme

**Settings ▸ Theme** has a row for each of:

* *Background shader* (Wave, Matrix, Bokeh, Starfield, CRT, Black hole)
* *Background filter* (Off, Scanlines, CRT grille, LCD grid)
* *UI filter (experimental)* (Off, Scanlines, Bloom)
* *Focus frame style* (Breathing halo, Static neon, Hard shadow, Gold bezel)
* *Ambient particles* (Off, Snow, Bokeh, Dust motes)
* *App icon mask* (System, Squircle, Circle, Chrome)
* *Boot intro* (Video, Shader, Off) and *Launch curtain* (Bloom, Fade to black, Iris, Flash), in
  Settings ▸ Theme ▸ Intro and transitions; a chosen Shader plays even over a pack's video

Confirm steps through "Theme pack (…)" and then each choice; Options lists them all. They are saved
under `display.effects` in `settings.json` (`shaderPreset`, `backgroundFilter`, `uiFilter`,
`focusFrameStyle`, `foregroundParticles`, `iconMask`, `bootIntro`, `launchCurtain`; empty follows
the pack) and win in every
category and rule.

*Xross wallpaper* picks any picture through the system picker (Pick custom…; the read access is
kept across restarts) or goes back to the theme's (Reset to theme).

*Reset to theme defaults* clears every pick that overrides the pack's look (those eight, the surface
style, the three color layers, the Xross wallpaper and the Components picks, §15) and keeps the pack, its variant, the theme
color, the quick profile and the wave settings.

## 12. `.xtheme` archives

A pack zipped into one file (the folder itself or its contents) installs with **Settings ▸ Theme ▸
Import theme (.xtheme)…**. The archive is checked before anything is written:

* no `..` or absolute paths;
* only `.json` / `.md` / `.txt`, `wallpaper.png`, `bgm.ogg` / `bgm.mp3` (at most 20 MB),
  `boot.mp4` (at most 30 MB), `shader.frag` and `postprocess.agsl` at the top,
  and fonts, sounds (or `audio/`), icons, shaders and `wallpapers/` in their folders with their
  own file types;
* no programs;
* at most 50 MB unpacked;
* a valid `schemaVersion: 2` `theme.json`.

It unpacks into `Xross/themes/<id>/` (the id is made from the pack's name) and is switched to; if
that id is installed, Xross asks whether to replace it or keep both.

## 13. Focused game background

Resting on a scraped game crossfades its hero art (or a screenshot) in after 250 ms; its video snap
fades in over it after 1.2 s, with the snap's sound ramping up over 300 ms while music stays ducked.
The wave stops rendering while art or video covers it. Scraping itself is described in the
[user guide](USER_GUIDE.md#4-metadata-art-and-dats).

## 14. The Default pack and icon tooling

The Default pack, named **XMB**, lives in
[`app/src/main/assets/themes/default/`](../app/src/main/assets/themes/default) (`theme.json`,
`surfaces.json`, XMB-style `icons/*.svg`, `sounds/*.wav`), kept apart from the app code so it can be
swapped as a whole. It is what every other pack inherits from, and a working example of a v2 pack.
Settings that name its former id `xmb` load it.

The APK also bundles **Xross** in
[`app/src/main/assets/themes/xross/`](../app/src/main/assets/themes/xross): the same sounds and
surfaces with Phosphor icons (MIT, credited in [`CREDITS.md`](../CREDITS.md)), and **Gargantua** in
[`app/src/main/assets/themes/blackhole/`](../app/src/main/assets/themes/blackhole): an ASCII black
hole shader set in Redaction 50 (OFL, also credited there). Bundled packs are listed right
after Default, load from the APK, and their ids are reserved (`ThemePackLoader.BUNDLED_IDS`).

### Console logos on system folders

A pack may draw each system folder (Super Nintendo, PlayStation 2…) with its own logo instead of
the generic `platform` icon: `icons/systems/<name>.svg`, `.png` or `.webp`, where `<name>` is the
Xross platform id (`snes`, `ps2`, `psx`, `megadrive`…) or one of the folder names Xross scans for
that system (`ps1.png` is `psx`, `genesis.svg` is `megadrive`). An exact id beats an alias for the
same system. An SVG is drawn like any pack icon (white, tinted with the menu); a PNG or WebP keeps
its own colors. A name that isn't a system and a file that can't be read are reported; the rest
still load.

A pack without a logo for a system inherits the Default pack's, and a system with none in either
gets Xross's bundled White logo. *Settings ▸ Theme ▸ Menu and profile ▸ Console logos on system
folders* picks what folders show (`display.systemLogoStyle`), or turns logos off
(`display.showSystemLogos`):

| Style | Shows |
| --- | --- |
| Theme pack (`PACK`, the default) | The pack's `icons/systems/` logos, else bundled White |
| White, Color, Black & white | Bundled sets for dark backgrounds |
| Color, outlined | Color logos with a white outline, visible on any background |
| Color / Black & white for light backgrounds, Black | Bundled sets for light backgrounds |

Any style other than Theme pack ignores the pack's logos. White and the pack's logos get the
XMB's white-to-silver fill like any icon; the other bundled sets keep their colors. A logo wider
than about 1.25:1 keeps its shape: it is drawn up to 2.4 icon slots wide and 0.8 tall, and the
title moves right to make room, as it does beside a game tile.

The bundled logos are Dan Patrick's (see `CREDITS.md`), in `assets/system_logos/<style>/<id>.webp`,
made by `tools/system_logos/import_logos.py`. Console logos are their makers' trademarks; a pack
that brings its own is responsible for its right to use them.

The Phosphor icons are committed as pack SVGs; each names its source icon in a comment. To change
one, download it from [phosphoricons.com](https://phosphoricons.com) and replace the file.

## 15. Components

The `components` block (Phase 2b.7) says which widgets the menu draws and how. It is read from
the pack's base and its variants only: a category or system override or a rule can't change it
(a `components` key there is reported and ignored), so the status bar never rearranges itself per
category or battery level.

```json
"components": {
  "statusBarLeft": ["LOGO", "WIFI"],
  "statusBarRight": ["CLOCK", "BATTERY", "AVATAR"],
  "batteryMode": "TIME_REMAINING",
  "clockFormat": "EMMMdm",
  "logo": "art/logo.svg",
  "volumeAlways": false,
  "miniPlayerMode": "STATUS_TICKER",
  "glyphOverride": "AUTO",
  "badgePills": ["SYSTEM", "YEAR", "RA_PROGRESS"],
  "tileGlow": true
}
```

### Status bar

* `statusBarLeft` draws at the top-left, `statusBarRight` at the top-right, each in array order.
  Slots: `LOGO`, `WIFI`, `VOLUME`, `BATTERY`, `CLOCK`, `AVATAR`. A slot listed twice, or in both
  clusters, is drawn once (on the left); these are reported.
* `AVATAR` (the profile avatar and its mini-HUD) is always the rightmost slot of `statusBarRight`:
  listed anywhere else, it is moved there and reported. Left out, there is no avatar in the bar and
  Up at the top of a list stays in the list; the User Card is still in Settings ▸ Accounts.
* While the HUD is open it fades every slot of the right cluster; the left cluster never fades.
* The left cluster gets the width the right one leaves and is clipped before the category bar.
* `LOGO`: the pack's `logo`, an SVG, PNG or WebP from the pack root (the same checks as icons),
  drawn at the status text's cap height and its own aspect. An SVG is read like an icon (white,
  black fills dropped); a PNG or WebP keeps its colors. No logo: the slot is skipped.
* `WIFI`: Wi-Fi with its strength in 4 levels, mobile data, ethernet or offline. Xross reads the
  signal strength only (the `ACCESS_WIFI_STATE` permission), never the network's name.
* `VOLUME`: the system media volume in 4 levels, a muted glyph at 0, shown for 3 s after it
  changes; `"volumeAlways": true` keeps it shown.
* `BATTERY`: `batteryMode` is `CAPSULE_ICON` (the gauge), `PERCENTAGE`, `BOTH` (the default) or
  `TIME_REMAINING`: "1 h 20 m to full" while charging when Android has an estimate, else exactly
  like `PERCENTAGE`. There is no time-to-empty: Android doesn't estimate it.
* `CLOCK`: `clockFormat` is a date-format skeleton of the letters `y M d E H h m a`; other letters
  are dropped and reported. The date letters are written in the locale's order, then two spaces
  and the time: `H` for 24 h, `h` for 12 h, `m` alone for the phone's own 12/24 h setting. Empty is
  the default clock (`Md` and the phone's time). Examples: `MdHm` → "11/14  21:41", `EMMMdm` →
  "Sat, Nov 14  9:41 PM", `m` → "9:41 PM".

The Default pack: left `[]`, right `[CLOCK, BATTERY, AVATAR]`, `BOTH`, empty clock format.

### Game tile glow

* `tileGlow` (0.2.1.0): `true` (the default) frames the focused game tile in the pack's
  `effects.focusFrameStyle`; `false` draws no frame there. The focused tile still grows and shows
  its title plate. Only game tiles change: RetroAchievements badges and thumbnails keep their frame,
  so a pack can keep `GOLD_BEZEL` on badges and a flat look on the game row.
* A value that isn't `true` or `false` is reported and ignored (the layer below's value is kept).

### Mini player, glyphs and title badges

* `miniPlayerMode`: `FLOATING_CARD` (the default: art, title, artist and play/pause in the
  lower-right corner), `STATUS_TICKER` (one line in the status bar's right cluster, just before
  the avatar: the play/pause glyph and "Title — Artist" scrolling in a fixed width, faded with the
  other slots while the avatar's HUD is open) or `OFF` (neither; music and its notification carry
  on). Card and ticker share their rules: hidden while a screen or panel covers the menu, and 30 s
  after a pause. Tap opens the audio controls, long press plays or pauses.
* `glyphOverride`: the button labels in every hint (the menu, Search, Options, Achievements, the
  avatar HUD). `AUTO` (the default) draws the detected controller's own; `PLAYSTATION` ✕ ○ △ □;
  `XBOX` A B X Y, each in its color (A green, B red, X blue, Y yellow); `NINTENDO` A B X Y in
  Nintendo places (south reads B); `MONOCHROME_MINIMAL` an outlined triangle pointing at the
  button's place on the pad (▽ ▷ △ ◁), in outlined pills. The shapes (✕ ○ △ □ and the
  triangles) are drawn, not set in the font, so all four are the same size and centered. Labels
  only: which button confirms still follows the controller and Settings ▸ Controls.
* `badgePills`: pills after the focused game's title, on the line under it, in array order, each
  skipped when its data is missing: `SYSTEM` (the platform's short tag, e.g. DC, GC), `YEAR` (from
  the release date, full or partial), `DEVELOPER` (cut at 18 characters), `PLAYTIME` ("1h 15m";
  none before the first minute), `RA_PROGRESS` ("🏆 18/42", recognized games with achievements
  only). Listing `SYSTEM` or `PLAYTIME` takes that part out of the line under the title. Pills use
  the surface (`effects.surfaceStyle`) at a small radius, with figures in the `mono` font; games
  only, never apps or media. The Default pack lists none.

### Social stack

* `topLeftCluster`: `EMPTY` (the default: nothing) or `SOCIAL_STACK`: the RetroAchievements users
  the linked account follows, after the left slots in the same clipped row. Up to 4 overlapping
  round avatars, the most recently active on top, with an amber badge counting the friends whose
  latest game or last activity changed since the stack was last opened (`1`–`9`, then `9+`).
* Opening it shows a panel below it: FOLLOWING, then each friend with their latest game and
  console and how long ago they were active ("12 min", "3 h", "online"), newest first; friends new
  since the last opening carry a dot. It opens with Left from the avatar's HUD (or Up at the top of
  a list when the bar has no avatar), or a tap; Up and Down move, Right goes to the avatar, Back
  or a tap outside closes. Opening it clears the badge.
* It collapses (draws nothing, takes no room) when no account is linked, when offline with nothing
  saved, or when the account follows nobody. Offline with a saved list, it shows that list.
* Friend data is fetched only on a refresh (Games ▸ RetroAchievements' Refresh, the profile's own
  refresh, opening the stack when it is stale, turning it on), never on focus or a timer; see
  [reference/RETROACHIEVEMENTS_API.md](reference/RETROACHIEVEMENTS_API.md).

### Achievement badge

* `telemetryMode`: the focused game's RetroAchievements progress in the bottom corner, above the
  button hints and below the mini player's floating card (with the ticker or no mini player it
  has the corner). `OFF` draws nothing; `COMPACT_PILL` (the default) the trophy, unlocked/total
  and points in a pill; `RICH_CARD` a card on the surface (`effects.surfaceStyle`) with the game's
  box art cropped square, a platform pill (and ★ MASTERED at 100%), six badges (the newest
  unlocks first, then locked ones greyed in the game's order), a progress bar with
  "18/42 · 120/400 pts" (no points part for a set worth none) and a `RECENTLY PLAYED` strip: up to
  three followed users whose latest game this is, most recent first, then "+N".
* Recognized games with achievements only; nothing for other games, apps and media, unlinked, or
  under an overlay or the avatar's HUD. The card keeps its game for a moment (dimmed) while the
  focus moves, then crossfades to the next game's or fades out.
* Offline (the friend refresh or this game's own fetch failed), the card draws from the saved
  progress without the strip; no friend on the game, no strip. The card never fetches: it shows
  the progress the focus already loads (as the pill always did) and the saved friends. Turning
  the rich card on fetches the friends once when none are saved; the profile's refresh then
  keeps them current, as for the social stack.

### `components` reference

Names ignore case, `-` and spaces; a bad value is reported and falls back to the layer below,
leaving the rest of the block.

| Key (aliases) | Values (aliases) |
| --- | --- |
| `topLeftCluster` (`topLeft`) | `EMPTY` (`NONE`), `SOCIAL_STACK` (`SOCIAL`, `FRIENDS`) |
| `statusBarLeft`, `statusBarRight` | arrays of `LOGO`, `WIFI` (`WI-FI`, `NETWORK`), `VOLUME`, `BATTERY`, `CLOCK` (`TIME`), `AVATAR` (`PROFILE`) |
| `batteryMode` (`battery`, `batteryDisplay`) | `CAPSULE_ICON` (`ICON`, `CAPSULE`, `GAUGE`), `PERCENTAGE` (`PERCENT`), `BOTH`, `TIME_REMAINING` (`TIME`, `TIME_TO_FULL`) |
| `clockFormat` (`clock`) | a skeleton of `y M d E H h m a`; empty = the default clock |
| `telemetryMode` (`telemetry`) | `OFF` (`NONE`), `COMPACT_PILL` (`PILL`, `COMPACT`), `RICH_CARD` (`CARD`, `RICH`) |
| `miniPlayerMode` (`miniPlayer`) | `FLOATING_CARD` (`CARD`), `STATUS_TICKER` (`TICKER`), `OFF` (`NONE`) |
| `glyphOverride` (`glyphs`, `glyphStyle`) | `AUTO`, `PLAYSTATION` (`PS`), `XBOX`, `NINTENDO` (`SWITCH`), `MONOCHROME_MINIMAL` (`MONOCHROME`, `MINIMAL`) |
| `badgePills` | array of `SYSTEM` (`PLATFORM`), `YEAR` (`RELEASE_YEAR`), `DEVELOPER`, `PLAYTIME`, `RA_PROGRESS` (`RA`, `ACHIEVEMENTS`) |
| `logo` | an SVG, PNG or WebP in the pack, or `none` |
| `volumeAlways` | `true` / `false` |
| `tileGlow` (`glow`) | `true` / `false` |

### Settings ▸ Theme ▸ Components

* *Status bar left* and *Status bar right*: Confirm (or Options) opens the slot list: Theme pack
  (the pack's slots), each slot with Move left, Move right, Move to the other side and Remove, and
  Add. After a move the list reopens on that slot, so it can be moved again at once. The avatar
  can only be removed or added back, always last.
* *Battery display*: Theme pack, Icon, Percentage, Icon and percentage, Time to full.
* *Clock format*: Theme pack, 24 h (`MdHm`), 12 h (`Mdhma`), Date and time (`EMMMdm`), Time only
  (`m`).
* *Mini player*: Theme pack, Floating card, Status bar ticker, Off.
* *Button glyphs*: Theme pack, Controller's own, Shapes (✕ ○ △ □), Colored letters (A B X Y),
  Letters, A on the right, Minimal outlines.
* *Title badges*: Confirm (or Options) opens the list: Theme pack, None, then System, Release year,
  Developer, Playtime and Achievements; Confirm turns a badge on (at the end) or off, and the list
  stays open on it.
* *Top-left*: Theme pack, Social stack, Empty.
* *Achievement badge*: Theme pack, Off, Compact, Rich card.
* *Game tile glow* (last): Theme pack, On, Off.

Saved as `display.components` in `settings.json` (`null` follows the pack); Reset to theme
defaults clears it, quick profiles never touch it.
