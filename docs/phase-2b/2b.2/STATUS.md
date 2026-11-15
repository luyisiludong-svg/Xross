# Phase 2b.2 — evidence

Theme 2.0 foundation: manifest, surfaces, hot reload, layout, motion, variants and `.xtheme` import.

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.16"></a>

## 3.16 Phase 2b.2, slice 1: Theme 2.0 manifest, surfaces, hot reload (2026-11-12)

On branch `feat/theme-2b2`. Scope and what is left: Roadmap §4.3 (2b.2 row) and §4.7.

| Feature | How it works |
| --- | --- |
| Manifest v2 | `ThemeManifest` with `schemaVersion: 2` required; v1 folders are listed as skipped with the reason (`ThemePackLoader.scan`, Options ▸ Look for new packs, the Theme pack row). Palette roles (`canvas`, `accent`, `accentSecondary`, …) map onto today's background and wave colors; `wave.gradient`'s last stop is the canvas bottom |
| Imports | `imports` lists JSON files in the pack, deep-merged under `theme.json` (which wins); paths stay inside the pack, `.json` only, 256 KB each, at most 8, no nested imports. The Default pack imports its `surfaces.json` |
| Font roles | `typography.display` / `body` / `mono`; missing roles use `body`. `display` draws the clock and category names, `mono` the battery figure |
| Sounds | `audio.sounds` / `audio.soundGains`; a bare name is in `sounds/`, a path from the pack root; the spec names `cancel`, `optionOpen`, `gameLaunch`, `achievementUnlock` are aliases |
| Surfaces | `SurfaceSpec` (shape, fill, sheen, borders, shadows, glow) validated into `Surface` with caps (3 borders, 2 shadows, glow ≤ 24 dp); `Modifier.xrossSurface()` draws it in the draw phase only and replaces `frostedGlass` + `iridescentBorder` in all nine places. The four styles are presets in the Default pack's `surfaces.json`; `FLAT_GLASS` reproduces the old look. Settings ▸ Theme ▸ Surface style (`display.surfaceStyle`) overrides the pack's choice |
| Hot reload | `ThemeWatcher` (`FileObserver` on the active pack and its folders, 150 ms debounce) and a fingerprint check on resume. A load that fails keeps the last good theme and toasts the file and line; sounds reload even when file names didn't change (`LoadedTheme.revision`) |

**Emulator (API 37, 2026-11-12):** `smoke.sh` 118/118, including the new Surface style and hot
reload checks (a first run failed one check that read a row that wasn't focused; fixed). Checked by hand: an `echo` into
`theme.json` through `adb shell` renamed the active pack within ~1.5 s without leaving Xross, and
a broken save showed "Theme not reloaded". The Wave settings panel was screenshotted with all
four presets and a custom chamfered surface with a cyan glow pushed while the app was running.

**Not verified:** hot reload on the Galaxy A55 (FUSE delivery differs between devices; the
resume check is the fallback); shadow and glow cost on a low-end handheld; the `AvatarHud`
capsule still draws its own frosted glass and doesn't follow the surface style yet.

<a id="s3.17"></a>

## 3.17 Phase 2b.2, slice 2: layout, motion, focus divider, drill-down (2026-11-12)

On branch `feat/theme-2b2`. Spec: Roadmap Appendix A.5 and A.11.

| Feature | How it works |
| --- | --- |
| `layout` block | `LayoutSpec` resolved into `ThemeLayout` over the Default pack's (`resolveLayout`); bad names and out-of-range numbers are reported on the pack and clamped. The Default pack declares the measured look (22% / 25%, 16:9 games, 1:1 music, 1.3 photo and video), so it looks as before |
| Crosshair | `crosshairX` / `crosshairY`, or `preset` `CLASSIC_PS3` (0.30 / 0.35), `PSP_CENTER` (0.50 / 0.50), `MODERN_WIDESCREEN` (0.18 / 0.20). `Crosshair.clampX` keeps a whole neighbour category on each side (left only when UI scale leaves no room for both), never past the middle; `clampY` keeps the rows above the bar, the focused row and the one under it on screen |
| Tiles | `cardAspectRatio`, `layout.tile` per category id, and `categoryOverrides.<CAT>.cardAspectRatio` (wins); `16:9`, `2:1`, `1:1`, `3:4`, `2:3`, `4:1` or a number, clamped 1:3–4:1. Game tiles and photo / video / album thumbnails follow it; square and portrait game tiles show box art first |
| Scale and spacing | `uiScale` 50–150% (scales the short side every size follows), `itemSpacingDp` (12 = the measured pitch), `focusedScale` 1.00–1.35, `unfocusedAlpha` 10–80% (unset: the built-in mix) |
| Focus divider | `NONE`, `FULL_BLEED_LINE` (from the focused icon's right edge to the screen edge, between title and subtitle), `TITLE_UNDERLINE` (the built-in hairline; added to the spec's enum as the default) |
| Text overflow | `ELLIPSIS`; `EDGE_FADE` (fades the last stretch only when the title doesn't fit); `MARQUEE` (the focused title scrolls three times and stops, so a resting screen draws nothing) |
| Motion | `motionProfile` (or `motion`) `CRISP` 520 / 1.0, `SNAPPY` 800 / 1.0, `BOUNCY` 450 / 0.75, `INSTANT` (snap, also for the list slide-in, menu fade and HUD morph); aliases `PS3_CRISP`, `PSP_SNAPPY`, `Y2K_BOUNCY`, `EINK_INSTANT`; optional `spring.stiffness` / `dampingRatio` |
| Drill-down | `BREADCRUMB` (as before); `XMB_FOLDER_COLUMN` (no text; the opened folder's icon parks left of the list, level with the focus; `XmbUiState.folderGlyph`); `CHEVRON_COLLAPSE` (the bar fades and rises away; a ◀, described "Back", sits left of the focused icon) |
| Avatar HUD | Drawn with `xrossSurface` (lambda overload: shape, strength, shadow and clip read while drawing). Rounded surfaces morph from the avatar circle, chamfer cuts grow in, and fill, borders and glow fade with the expansion; no clip while collapsed |

**Emulator (API 37, 1080×2400 landscape, 2026-11-12):** `smoke.sh` 121/121, with three new checks
(the inherited breadcrumb, then a hot-reloaded `CHEVRON_COLLAPSE` dropping it and showing the ◀).
Screenshotted by hand: `CHEVRON_COLLAPSE` + `FULL_BLEED_LINE` on Settings and on a game list, and
`PSP_CENTER` + `XMB_FOLDER_COLUMN` + 3:4 game tiles.

**Not verified:** the 12-configuration display matrix wasn't re-run on the emulator (the unit
tests check the clamping on all 12); `MARQUEE` and `EDGE_FADE` were not looked at on screen;
`BOUNCY` feel and frame cost on a handheld; the HUD on the chamfered and E-Ink surfaces.

<a id="s3.18"></a>

## 3.18 Phase 2b.2, slice 3: variants, overrides, `.xtheme` import (2026-11-12)

On branch `feat/theme-2b2`; completes 2b.2. Spec: Roadmap §4.3 and Appendix A.4, A.12, A.13.

| Feature | How it works |
| --- | --- |
| Variants | `variants: [{ id, name, palette?, effects? }]` (`ThemeVariant`), checked into `ResolvedVariant` (ids `[A-Za-z0-9_.-]`, unique; bad colors and unknown surfaces dropped one by one and reported). Settings ▸ Theme ▸ **Theme variant** appears right under Theme pack only when the pack has variants: Confirm steps Standard → each variant, Options lists them. Saved as `display.themeVariant` in `settings.json`; an id the pack doesn't have means the base look, and switching packs resets it. A variant with colors switches Theme color to the pack's, as choosing a pack does |
| Category and system overrides | `categoryOverrides` (`GAMES`, `MUSIC`, `PHOTO`, `VIDEO`, `APPS`, `SETTINGS`) and `systemOverrides` (platform id or any folder alias, `dc` → `dreamcast`, through `PlatformRegistry`; the exact id wins over an alias) carry `palette`, `effects.surfaceStyle` and `cardAspectRatio` (`ThemeOverride`). A system's aspect shapes that system's game tiles wherever they are listed (`ThemeLayout.systemAspects`), so tiles don't change shape as the cursor moves |
| Precedence | `cascade()`: Default pack → pack → variant → category → system, then the user's `settings.json` (a fixed Theme color or a chosen Surface style wins). A variant may bring a pack's only palette; overrides only patch an existing one, so a pack without colors never repaints the user's theme. `LoadedTheme.look(variant, category, system)` is cached per triple |
| Runtime | `MainActivity` computes the look from the open category and the focused game's platform (`XmbUiState.focusedSystem`), the system after the cursor rests 250 ms (`rememberSettledSystem`; a category switch applies at once). Surfaces go through `ThemeSurfaces.active` (draw phase only); the wave eases its three colors on the GL thread (`WaveRenderer.easeColor`, ~95% in 300 ms at any frame rate), so a switch costs no recomposition beyond the one the focus move already makes |
| `.xtheme` import | Settings ▸ Theme ▸ **Import theme (.xtheme)…** (last row) opens the SAF picker (`application/zip`, `application/x-zip-compressed`, `application/octet-stream`). The file is copied to the cache (capped at 51 MB), then `ThemeArchive` checks every entry before writing: `..`, leading `/` or `\`, drive letters and NUL reject the archive (Android 14+'s own `ZipFile` guard is reported the same way); only `.json` / `.md` / `.txt` at the root and `fonts/`, `sounds/`, `audio/`, `icons/`, `shaders/` with their own file types, at most 4 levels; programs (`.apk`, `.dex`, `.so`, `.sh`, `.jar`, …) named as such; ≤ 50 MB unpacked by declared size and again by bytes actually inflated; ≤ 2000 files; no duplicate paths. `__MACOSX/`, `.DS_Store` and `._*` are skipped and a single wrapping folder is unwrapped. It unpacks into a hidden `Xross/themes/.import-*` folder, must pass the loader's manifest check (`schemaVersion: 2`, a name), and is renamed to `Xross/themes/<slug of the name>/`, rescanned and applied. If that id exists, an Options prompt offers **Replace** or **Keep both** (`<id>-2`); nothing is overwritten otherwise, and leftover staging folders are swept on the next import |
| Display matrix | `tools/device-tests/display-matrix.sh [out] [stress]` sets each of the 12 sizes with `wm size` / `wm density`, warms up, measures 4 down, 4 up, L1, R1 with `gfxinfo`, counts text cut by a screen edge and saves screenshots of the Games root and Settings ▸ Theme. `stress` installs a pack with `PSP_CENTER`, 150% UI scale, 20 dp spacing, 1.35× focus and 3:4 game tiles |

**Unit tests (464, 0 failures):** `ThemeVariantsTest` — variants patch only the roles they set,
unknown or blank ids fall back to the base, a variant may bring the only palette, bad colors and
surfaces drop on their own, the full precedence order including the user layer, overrides never
add a palette, folder aliases and exact-id priority, system and category aspects, the saved
variant in `settings.json` (and its absence in older files), the row shown only with variants, and
the wave crossfade being frame-rate independent. `ThemeArchiveTest` — five traversal forms, four
absolute forms, six program types, unknown folders, root files, wrong types per folder and depth,
51 MB (≈ 50 KB compressed) rejected and 49 MB accepted, a summed-size overrun, no `theme.json`,
v1 and broken manifests, not-a-zip, empty and duplicate entries, unwrapping a zipped folder, ids
from name / folder / file name, install and discovery, replace only when asked, Keep both, and no
files left behind after a rejection or a cancelled prompt.

**Emulator (API 37, 2026-11-12):** `smoke.sh` 139/139 (121 before, 18 new): the Import row opens
DocumentsUI; a `../../evil.json` archive is refused with the reason and writes nothing; a valid
archive wrapped in a folder installs as `smoke-variants` and is applied without problems; Theme
variant shows, cycles to BIOS Matrix, is saved, and its Options list every variant; re-importing
asks first and Keep both installs `smoke-variants-2`; the SNES list still works with an `sfc`
override; Default has no variant row. A first run failed one check that read a 2.6 s toast too
late; the picker helper now reads it straight away. By hand: BIOS Matrix turned the wave green, a
focused SNES game turned it red-orange through the `sfc` override with 3:4 tiles, crossfading
both ways.

**Display matrix re-run (profiling build, 2026-11-12).** Layout: no on-screen text runs past an
edge on any of the 12 configurations with the Default pack or the stress pack, and the
screenshots show one whole neighbour category on each side with the Default pack (at 150% on 1:1
and 4:3 only the left one fits, as designed in [§3.17](#s3.17)). The stress pass found one clamp bug, fixed
here: at 150% UI scale on 1:1 the focused row's text had a minimum width wider than the room left,
so it ran off the edge instead of ending in "…"; the text width is now capped at the screen edge.
At that extreme the button-hint pill still covers the row below the focus (as before, it has a
translucent backing). Frame timing was unreliable on this emulator today: every configuration had
at least one clean run (0–1.2% janky, p99 ≤ 19 ms; the stress pack all 12 clean in one pass), but
runs of 20–35% janky frames came and went across configurations between passes, also after a
reboot. They are "slow issue draw commands" frames (the render thread waiting on the host GPU),
not UI-thread work, and an interleaved A/B on 1920×1080 measured the slice 2 build just as janky
at the same time (base 16–27%, this branch 14–30% over 12 passes each), so they don't come from
this slice. Re-measure on a phone or handheld before quoting numbers.

**Not verified:** the import on Android 9–13 (the copy and checks are plain `java.util.zip`, but
only API 37 ran) and from a removable volume or a cloud provider in the picker; the crossfade and
override switches on a 120 Hz phone; a pack with many large fonts near the 50 MB cap.
