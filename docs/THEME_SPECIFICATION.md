# Theme 2.0 specification

The reference for Xross's look and sound as implemented today (through Phase 2b.4): the theme pack
format, surfaces, sounds, the background shader and Wave settings, wallpapers, focus frames, icon
masks, particles, filters, variants, overrides, context rules, color layers, quick profiles and the
single-file `.xtheme` archive.

Related documents: the [user and storage guide](USER_GUIDE.md) (where `Xross/themes/` lives and
the rest of the `Xross/` folder), [`Roadmap.md`](../Roadmap.md) §4.3 and Appendix A (the design
spec and milestones for upcoming Theme 2.0 work), [`STATUS.md`](STATUS.md) (how each feature was
verified, with frame timings) and
[`THEME_RICING_AND_WEB_STUDIO.md`](THEME_RICING_AND_WEB_STUDIO.md) (the plain-text ricing and web
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

## 2. Pack layout

```
Xross/themes/<theme_id>/
├── theme.json      "schemaVersion": 2, name (required); palette, wave, typography, audio,
│                   icons, effects, surfaces (all optional)
├── surfaces.json   optional: any JSON file listed in "imports" is merged under theme.json
├── fonts/          .ttf/.otf; "typography" names one per role (display, body, mono)
├── icons/          <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
├── sounds/         cursor.wav, category_switch.wav, confirm.wav, drill_in.wav, drill_out.wav,
│                   back.wav, option.wav, ok.wav, system_ng.wav, trophy.wav, 01. opening.wav,
│                   gameboot.wav
├── shader.frag     optional: a CUSTOM background shader (or effects.shaderPath)
├── postprocess.agsl optional: a CUSTOM UI filter, Android 13+ (or effects.uiFilterPath)
├── wallpaper.png   optional: drawn behind the menu (or .jpg / .webp, or effects.wallpaperPath)
└── bgm.ogg         optional: ambient music loop, at most 20 MB (or bgm.mp3, or audio.bgmFile)
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
`uAudioBeat` uniforms (no microphone permission); see [`STATUS.md`](STATUS.md) §3.23 for how it
was measured.

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
| `effects` | `shaderPreset`, `backgroundFilter`, `uiFilter`, `focusFrameStyle`, `foregroundParticles`, `iconMask`; empty = the pack's | Effect rows (§11) |
| `quickProfile` | `NONE`, `OLED_TRUE_BLACK`, `Y2K_CYBER_PASTEL`, `MONOCHROME_EINK` | Quick profile (§10) |
| `adaptiveGamePalette`, `circadianCurve`, `batteryTint` | `true` / `false`; `null` follows the pack | Adaptive colors / Circadian curve / Battery alert tint (§10) |
| `wave` | `true` / `false` | Wave background on or off |
| `waveSpeed`, `waveLines`, `waveTurbulence`, `waveThickness`, `waveTint`, `waveColors` | see §6 | Wave settings (§6) |
| `buttonHints` | `true` / `false` | Hides or shows the button-hint pill |

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

### `effects` reference

Every key a pack's `effects` block (in the base, a variant, an override or a rule's `apply`)
recognizes. Any other key is listed with the pack's problems (Settings ▸ Theme ▸ Theme pack)
instead of being ignored silently, so a typo shows up.

| Key (aliases) | Values | Since |
| --- | --- | --- |
| `surfaceStyle` | a preset or a surface the pack defines | 2b.2 |
| `adaptiveGamePalette` | `true` / `false` | 2b.3 |
| `shaderPreset` (`shader`) | `WAVE`, `ASCII_MATRIX`, `BOKEH`, `STARFIELD`, `CRT`, `CUSTOM` | 2b.4a |
| `shaderPath` | a `.frag` in the pack (implies `CUSTOM`) | 2b.4a |
| `wallpaperPath` (`wallpaper`) | a PNG, JPEG or WebP in the pack, or `none` | 2b.4c |
| `focusFrameStyle` (`focusFrame`) | `BREATHING_HALO`, `STATIC_NEON`, `HARD_SHADOW`, `GOLD_BEZEL` | 2b.4d |
| `iconMask` | `NONE`, `SQUIRCLE`, `CIRCLE`, `Y2K_CHROME_FRAME` | 2b.4d |
| `iconFilter` | `NONE`, `DUOTONE`, `MONOCHROME` | 2b.4d |
| `foregroundParticles` (`particles`) | `NONE`, `SNOW`, `BOKEH`, `DUST_MOTES` | 2b.4e |
| `backgroundFilter` | `NONE`, `SCANLINES`, `CRT`, `LCD_GRID` | 2b.4f |
| `uiFilter` (`postProcess`) | `NONE`, `SCANLINES_UI`, `BLOOM`, `CUSTOM` (Android 13+) | 2b.4f |
| `uiFilterPath` (`postProcessPath`) | an `.agsl` in the pack (implies `CUSTOM`) | 2b.4f |

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

Each may set `palette` roles, `effects.surfaceStyle`, `effects.shaderPreset`,
`effects.wallpaperPath`, `effects.focusFrameStyle`, `iconMask`, `iconFilter`,
`foregroundParticles`, `backgroundFilter`, `uiFilter` and `cardAspectRatio` (a system's aspect
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

* *Background shader* (Wave, Matrix, Bokeh, Starfield, CRT)
* *Background filter* (Off, Scanlines, CRT grille, LCD grid)
* *UI filter (experimental)* (Off, Scanlines, Bloom)
* *Focus frame style* (Breathing halo, Static neon, Hard shadow, Gold bezel)
* *Ambient particles* (Off, Snow, Bokeh, Dust motes)
* *App icon mask* (System, Squircle, Circle, Chrome)

Confirm steps through "Theme pack (…)" and then each choice; Options lists them all. They are saved
under `display.effects` in `settings.json` (`shaderPreset`, `backgroundFilter`, `uiFilter`,
`focusFrameStyle`, `foregroundParticles`, `iconMask`; empty follows the pack) and win in every
category and rule.

*Xross wallpaper* picks any picture through the system picker (Pick custom…; the read access is
kept across restarts) or goes back to the theme's (Reset to theme).

*Reset to theme defaults* clears every pick that overrides the pack's look (those six, the surface
style, the three color layers and the Xross wallpaper) and keeps the pack, its variant, the theme
color, the quick profile and the wave settings.

## 12. `.xtheme` archives

A pack zipped into one file (the folder itself or its contents) installs with **Settings ▸ Theme ▸
Import theme (.xtheme)…**. The archive is checked before anything is written:

* no `..` or absolute paths;
* only `.json` / `.md` / `.txt`, `wallpaper.png`, `bgm.ogg` / `bgm.mp3` (at most 20 MB),
  `shader.frag` and `postprocess.agsl` at the top,
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

The bundled icons and sounds live in
[`app/src/main/assets/themes/default/`](../app/src/main/assets/themes/default/) (`theme.json`,
`surfaces.json`, `icons/*.svg`, `sounds/*.wav`), kept apart from the app code so they can be
swapped as a whole. It is a working example of a v2 pack.

The icons come from `assets/icons` (the design SVGs): `tools/icons/svg_icons.py` splits the icon
sheets and writes the ones listed in `tools/icons/icon_map.txt` as pack SVGs. To change an icon,
edit the map and run:

```bash
python3 tools/icons/svg_icons.py list assets/icons /tmp/icons   # previews every icon id
python3 tools/icons/svg_icons.py build assets/icons tools/icons/icon_map.txt app/src/main/assets/themes/default/icons
```
