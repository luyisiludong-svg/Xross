# Menu styles — implementation plan

A proposal, not yet scheduled to a release. It lets a theme pack replace the XMB menu with
another built-in menu style. The first new style, `SLASH`, is a bold, slanted, high-contrast
list with art-skinned rows, shard-shaped highlights and staggered motion, inspired by the menus
of Persona-era JRPGs. Written against `main` at `14ed5aa` (2026-11-25); revised 2026-11-27
against `feat/live-backgrounds-image-inputs` at `f824014` to reach the reference mockups (see
"Target looks"). The companion plan is `PLAN_LIVE_BACKGROUNDS.md`, which covers what is drawn
behind the menu.

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.
Status: ✅ done · ◐ partly done · ⬜ not started.

| # | Slice | Size | Status |
| --- | --- | --- | --- |
| 1 | Renderer seam: `layout.menuStyle`, `MenuAnchors`, `ScreenSignal.Focus`, menu image loading | M | ✅ on `feat/menu-styles`; the home screen is unchanged on the A55 |
| 2 | `SLASH` list: slanted rows, row skins, highlight, focused content, alignment, typography | M–L | ✅ on `feat/menu-styles`; emulator only |
| 3 | `SLASH` motion: entrance stagger, category transition, focus pop | M | ◐ built and unit tested; the wipe and shards not yet reviewed on screen |
| 4 | `SLASH` categories (icon bar with a focus decal, labels, glyphs), drill-down, framed art | M | ◐ built; hero art not yet seen with a scraped library |
| 5 | Clock and hint styles (`components`, every menu style) | S–M | ✅ on `feat/menu-styles`; emulator only |
| 6 | Rules (E-Ink, standby, `uiScale`), Layout panel, docs, sample pack, A55 timings | M | ◐ A55 timings, the skin viewer and screenshots for the docs remain |

**Total:** about 2.5–3 weeks (was 1.5–2 before the mockup revision: skins, focused logos, the
category decal, framed art and slice 5 add about a week).
**Order:** 1 to 6. Slice 1 ships alone with no visible change: XMB is drawn through the new seam.
Slice 5 depends only on slice 1's image loading and can land any time after it, also for XMB
packs.
**Not planned:**
- A pack-scriptable menu (keyframes, a layout DSL). It would be XL, with a large surface to
  validate, and a fixed renderer with parameters gets most of the effect. See "Decisions".
- Widgets with no data in Xross, such as the mockups' "current wallet" figure. A pack can paint
  one into its background art.
- Shipping any game's fonts, art, logos or exact screen layouts. The reference mockups use
  Persona characters and logos; they set the target feel only. The sample pack uses original art
  and freely licensed fonts.

## Target looks

Three mockups in `xross_themePacks/References/` (`P3inspo.jpg`, `P4inspo.jpg`, `P5inspo.jpg`)
are the bar this plan is written to reach. They share one layout:

- **Top left:** the horizontal category icon bar, the focused icon on a decal (P5 a star burst,
  P4 a glow with rays, P3 a paint splash) with its name underneath.
- **Left, below the bar:** a vertical list of slanted rows made of art: P5 torn beige paper, P4
  black slabs with houndstooth edges that bleed off the left edge with right-aligned text, P3
  black slabs with a silver border.
- **The focused row:** a bigger panel in a stronger skin. P5 shows the title on a pink and black
  torn slab; P4 and P3 show the game's logo on the panel, P3 with the title beside it.
- **Right:** character art. In P3 and P4 it's part of the background (a Live backgrounds scene);
  in P5 it's the focused game's hero art in a tilted, decorated frame.
- **Top right:** a big, slanted, two-line date and time, on a plate in P5 and P4.
- **Bottom right:** the button hints, as skewed plates in P5 and plain italic text in P3 and P4.
- **Everywhere:** busy, layered background art with particles (bubbles, stars, static), which
  Live backgrounds § 3 already draws.

What each element maps to:

| Element | Where it comes from |
| --- | --- |
| Background art, character art, particles | Live backgrounds § 3 (`scenePath`), built |
| Category bar and its decal | Slice 4: `slash.categories: ICONS`, `slash.categoryDecal` |
| Slanted, art-skinned rows | Slice 2: `skewDeg`, `rowTilt`, `rowSkin` |
| The focused panel, logo and title | Slice 2: `focusedSkin`, `focusedContent` |
| Right-aligned text, rows bleeding off the edge | Slice 2: `textAlign`, `bleed`, `listWidth` |
| Framed hero art (P5) | Slice 4: `art: HERO`, `artRect`, `artTilt`, `artFrame` |
| The big clock | Slice 5: `components.clockLayout`, `clockSize`, `clockTilt`, `clockPlate` |
| Hint plates | Slice 5: `components.hintStyle`, `hintPlate` |
| Fonts | `typography.display` and `typography.body`, existing |

A P3-like look, as a sketch of the finished format:

```json
"layout": {
  "menuStyle": "SLASH",
  "slash": {
    "skewDeg": -10, "rowTilt": [-2, 1], "jitter": 0.2, "seed": 3,
    "listX": 0.16, "listY": 0.40, "listWidth": 0.24,
    "rowSkin": { "path": "menu/row.webp", "slice": [48, 14, 48, 14] },
    "focusedSkin": { "path": "menu/panel.webp", "slice": [40, 40, 40, 40], "tint": "accent" },
    "focusedContent": "LOGO_TITLE",
    "categories": "ICONS",
    "categoryDecal": { "path": "menu/splash.webp", "size": 2.0 }
  }
},
"components": {
  "clockFormat": "MdEhma",
  "clockLayout": "STACKED", "clockSize": 2.2, "clockTilt": -6,
  "hintStyle": "PLAIN"
}
```

## Where things stand

- The menu is two composables in `XmbScreen.kt` (1,881 lines): `CategoryBar` (the horizontal
  icon bar) and `ItemColumn` (`ItemRow`, `XmbIcon`, `ArtIcon`, `ThumbIcon`, `FocusDivider`,
  `BackChevron` and friends).
- `XmbMetrics` holds the geometry. Its size values (`s`, `titleSize`, `statusSize`…) are used by
  many screens (details, options, search, RA hub, wizards). Its **anchor** values (`anchorX`,
  `barY`, `focusedY`, `aboveY`) appear only in `XmbScreen.kt`, 14 times. That's what keeps the
  seam an M and not an L. The anchor values are used in four places outside the menu itself:
  - the status bar's `leftMaxHeight` (the left cluster stops above the category bar)
  - `ScreenSignal.Focus` (was `CrosshairX`), which `MainActivity` sends to `sounds.setCrosshair`
    for panning and the renderer uses as `uFocus`
  - the focus point `Offset(m.anchorX, m.focusedY)`
  - the drill-down overlays (folder column, breadcrumb, chevron)
- The `layout` block (`theme/engine/.../pack/Layout.kt`) only adjusts the XMB: crosshair, tile
  aspects, spacing, focus scale and alpha, divider, drill-down style, text overflow, motion.
- Navigation is already separate from drawing: `XmbUiState`, `XmbEvent`, `NavAction` and
  `MenuTree`. Left and right switch category, up and down move the focus, and confirm and back
  drill in and out. A new style uses the same state and input, so **the view model doesn't
  change**.
- Packs already ship fonts (`typography.display`, `typography.body`), palettes, category icons,
  focus frames and motion profiles. `SLASH` reuses all of them. Focus frames
  (`effects.focusFrameStyle`) are built-in drawn styles, not images, so the mockups' decals and
  plates need the image skins below.
- Games carry scraped logos as `ArtKind.WHEEL` art and hero art as `ArtKind.HERO`.
- The status bar (`components`, spec § 15) already has a `CLOCK` slot with `clockFormat` and the
  display font, but a fixed size, one line and no tilt or plate. The hints are one pill
  (`buttonHints`, `glyphOverride`).

## Progress on slices 2–6 (2026-11-27, `feat/menu-styles`)

Everything below is uncommitted on `feat/menu-styles`, on top of slice 1. `:app:testDebugUnitTest`
and `:theme:engine:test` pass (1,247 tests together, 23 of them new in `SlashStyleTest`).

**Built.**
- **Format** (`theme/engine/.../pack/Slash.kt`): `SlashLayout` reads every `slash` key from
  slices 2–4 over the Default pack's, reporting and clamping as `resolveLayout` does.
  `LayoutSpec.slash` stays JSON so one bad key never costs the manifest. A rule's `slash` is
  reported and stripped like its `menuStyle`; a variant's is reported. `SlashGeometry` holds the
  pure parts: the per-row hash, tilt, nudge and skin pick, the highlight's corners, the scroll
  window (`nextTop`), the row box with `bleed`, the cascade's timing and the shard polygons.
- **Images at load.** `LoadedTheme.menuImages` / `menuSheets` find every skin `slash` and
  `components` name (both pack kinds), add them to `files` for export, and report missing ones as
  `Menu image … not found`. `XmbScreen` decodes them with slice 1's loader and provides
  `LocalMenuImages`, plus a `MenuPaint` (`LocalMenuPaint`) that resolves `accent`, palette roles and
  hex colors for tints and highlight colors.
- **Slice 2** (`ui/SlashMenu.kt`): `SlashMetrics` (sizes from `XmbMetrics.s`, fractions of the
  screen), rows skewed by one reused `Matrix` in `drawWithContent` and tilted in `graphicsLayer`,
  row skins nine-sliced with patches cached per size, the `SHARD` highlight morphing between cached
  corner sets on the focus spring (one reused `Path`), `SLAB`, `focusedSkin`, the `TITLE` / `LOGO` /
  `LOGO_TITLE` panel (`WHEEL` art, the title shown until it arrives), outlined display-font text,
  `unfocusedAlpha`, `textOverflow`, rows fading at the list's edges, and only the rows on screen
  composed. The list scrolls only when the focus nears an edge.
- **Slice 3.** `SlashMotion`: the list records into one of two `GraphicsLayer`s; on a new list key
  the last one is frozen (it keeps the old list's picture) and drawn over the new list as it
  cascades in. The swap happens in a `SideEffect`, so the new list's first frame is never recorded
  into the old layer. One progress value drives every row's delay, `WIPE` (a skewed band, the old
  list clipped ahead of it), `SHARDS` (the frozen picture clipped into 3–5 polygons that fly out)
  and `FADE`. `pop` is an under-damped spring released with a push. The progress goes out as
  `ScreenSignal.MenuTransition`. `MainActivity` writes it to a `MenuTransitionLevel` the renderer
  reads every frame (no recomposition), which holds `uTransition` and scenes' `transition` driver
  up while it plays.
- **Slice 4.** `ICONS` (the bar scrolls only when it doesn't fit), `LABELS`, `GLYPHS`; the decal
  follows the category spring and plays its sprite sheet, resting on frame 0 when still or idle;
  drill-down slides the frozen parent out left (back: the child out right) under a slanted folder
  header; `art: HERO / ICON` in a tilted box with a frame skin.
- **Slice 5** (`ui/MenuChrome.kt`): `StyledClock` (`ClockFormat.lines` splits the clock text) and
  `StyledHints` (`PILL`, `PLATES`, `PLAIN`), with `skewX` and `plate` modifiers. At their defaults
  they take the old code path, which gives the identical clock and pill.
- **Slice 6.** The Layout panel's rows come from a `LayoutPanelContext`: under `SLASH` the
  crosshair and focused-scale rows hide, and a *Menu style* row (shown when the variants offer
  more than one style) switches to the variant that draws the other. `INSTANT` and still motion
  skip the cascade, transitions, pop, morph and decal frames; standby holds the decal.
- **Sample pack** `xross_themePacks/Slash_Sample`: generated original art (torn paper rows, a slab
  panel, a 4-frame star decal, a starred frame, clock and hint plates, a halftone wallpaper),
  Arsenal (OFL), `LOGO_TITLE`, `HERO` art, a stacked clock on a plate and plated hints. Variants:
  `cobalt` (palette) and `classic` (`XMB`). It loads with no problems.
- **Docs:** `THEME_SPECIFICATION.md` § 16 (menu styles, skins, the `slash` block) and § 15 (the
  clock and hint keys).

**Deviations.**
- `rowTextColor` and `focusedTextColor` are new keys. White text on light paper skins couldn't be
  read on the emulator, and black text on dark slabs would have the same problem.
- The art frame hides with the art when the focused item has none, instead of standing empty.
- The torn-edge skins in the sample stretch whole, with no `slice`: nine-slicing smears a torn edge
  along its side. The spec says when to use which.
- Several XMB-only `layout` keys are ignored under `SLASH`, now including `focusedScale`. `SLASH`
  has its own `slash.focusedScale`.
- No separate theme-guide chapter: § 16 of the spec is the guide for now.

**Checked on the emulator** (`xross_test`, `-gpu host`, debug build, the sample pack): the home
list, moving the focus, drilling into All Games (header, hints gaining Back and Options) and a
category switch starting. No crash in logcat.

**Not done yet.**
- A55 timings on a profiling build (§ 6 "Performance"), and `PERFORMANCE.md`.
- A look at `WIPE` and `SHARDS` mid-switch (a recording), `LABELS` / `GLYPHS`, `bleed`, and hero
  art and logos with a scraped library.
- The shader preview's skin viewer, and screenshots for the docs.
- Unit tests that need Compose (the transition's layer swap, `PILL` / `INLINE` pixel equality).

---

## 1. Renderer seam (M)

> **Progress (2026-11-27, `feat/menu-styles`, on top of `feat/live-backgrounds-image-inputs`).**
> Code, unit tests and the device check are done.
>
> - **Format.** `LayoutSpec.menuStyle` → `ThemeLayout.menuStyle` (`MenuStyle.XMB` / `SLASH`),
>   resolved in `resolveLayout`. Variants gained `layout`, of which only `menuStyle` is read
>   (`ResolvedVariant.menuStyle`, applied in `LoadedTheme.look` over the pack's and the rules'
>   layout). A rule's `menuStyle` is reported and stripped; a rule left with nothing is "changes
>   nothing". Category and system overrides have no `layout`, so nothing to block there. The
>   exporter writes a flattened variant's style into `layout`.
> - **Seam.** `CategoryBar`, `ItemColumn` and their helpers moved unchanged to `XmbMenu.kt`
>   (731 lines), behind one `XmbMenu` composable. `XmbScreen` picks the style; `SLASH` draws as
>   the XMB until slice 2.
> - **`MenuAnchors`** (`ui/MenuAnchors.kt`): `focus` and `statusClearance`, from
>   `MenuAnchors.of(style, metrics)`. They now feed the `Focus` signal, the status bar's
>   `leftMaxHeight` and the game-boot overlay; nothing outside `XmbMetrics` reads the anchor values.
> - **Skins and menu images.** `Skin` (path, `slice`, `tint`, `outset`; parsed from a string or an
>   object, a bad part dropped on its own), `NineSlice.patches` and `MenuImagePlan` (1024 / 2048 for
>   sheets, 6 MP, dropped in order) in `theme/engine/.../pack/MenuSkin.kt`. `MenuImageLoader`,
>   `LocalMenuImages`, `rememberMenuImages` and `DrawScope.drawSkin` in `ui/MenuImages.kt`. No pack
>   field names a skin yet (slice 2 adds the first), so nothing loads them.
> - **Deviations:**
>   - `MenuAnchors.panX` is dropped: sound panning keeps reading the focus's x from the `Focus`
>     signal, which is now the focused item's centre in any style.
>   - The Layout panel changes (hiding XMB-only rows, a "Menu style" row) move to slice 2. While
>     `SLASH` draws as the XMB, hiding the crosshair row would be wrong.
> - **Tests:** `MenuStylesTest` (8): style names and fallback, XMB fields under `SLASH`, base /
>   variant / rule precedence and problems, the export, the XMB's anchors equal to the old numbers
>   on four screens × three presets × three UI scales, skin parsing, nine-slice geometry, the image
>   budget. The full `:app:testDebugUnitTest` (1,115) and `:theme:engine:test` (111) pass.
> - **A55** (profiling builds of `0027271` and this branch, Memento_Mori, cold start): the home
>   screen, one row down and one category right differ in 0.02–0.03% of the pixels below the
>   status bar, all of them the pack's moving particles; none on the bar, the rows, the text or
>   the hints.

**Goal.** `XmbScreen` draws "the menu" without knowing which style it is. Other screens stay as
they are.

**Format.**

```json
"layout": {
  "menuStyle": "SLASH",
  "slash": { "...": "see § 2 and § 4" }
}
```

- `menuStyle` is `XMB` (the default) or `SLASH`. An unknown name is a problem and falls back to
  `XMB`.
- Read at the base and in variants only. In a category patch, system patch or rule it is a
  problem and ignored: switching the whole menu while navigating would be jarring. (*Decision
  to confirm*, see below.)
- The XMB-only fields (`crosshairX/Y`, `preset`, `drillDownStyle`, `focusDivider`, tile aspects)
  are ignored under `SLASH`, without a problem, so one pack can offer both styles as variants.

**Code.**
- `ThemeLayout` gains `menuStyle: MenuStyle` and `slash: SlashLayout` (resolved and clamped like
  the rest of `resolveLayout`).
- `CategoryBar`, `ItemColumn` and their helpers move from `XmbScreen.kt` to a new `XmbMenu.kt`
  unchanged. `SlashMenu.kt` holds the new style. `XmbScreen` picks one at the place where it
  calls `CategoryBar` and `ItemColumn` today.
- Each style returns a `MenuAnchors`, which replaces the four uses of the anchor values outside
  the menu:

```kotlin
@Immutable
data class MenuAnchors(
    /** The focused item's centre, in px. */
    val focus: Offset,
    /** How far down the status bar's left cluster may reach, in px. */
    val statusClearance: Float,
    /** Where the focused item sits across the screen, 0..1, for sound panning. */
    val panX: Float,
)
```

- `ScreenSignal.Focus(x, y)`, both 0..1, landed with Live backgrounds § 1 (2026-11-25): the XMB
  sends its crosshair, and the renderer does the easing. Here it switches to
  `MenuAnchors.focus`, the focused item's centre, so a pack shader's parallax follows the focus
  in either style. `MainActivity` keeps passing `x` to `sounds.setCrosshair`.
- **Menu images.** Slices 2, 4 and 5 add images a pack names for the UI: row and panel skins, the
  category decal, the art frame, the clock and hint plates. A `MenuImages` loader (next to
  `WallpaperLoader`) finds and decodes them once per look, off the main thread, into
  `ImageBitmap`s that the composables read from a `CompositionLocal`. Paths get the same checks
  as every pack path (inside the pack, no `..`; png, jpg, webp). The long side is capped at 1024
  (2048 for a sprite sheet), and the whole set at 6 megapixels. Over the budget, the loader
  reports the problem and drops the images from the end of the list; a style whose image is
  missing draws its flat fallback. A hot reload (`ThemeWatcher`) reloads them when a file
  changes. Slice 1 ships the loader and the `Skin` type (below) with no user yet.
- **Skins.** One shape for every stretchable image:

```json
{ "path": "menu/row.webp", "slice": [48, 14, 48, 14], "tint": "accent", "outset": [0, 0, 0, 0] }
```

  - `slice` is left, top, right, bottom in image pixels: the corners keep their size, the edges
    stretch one way, the middle both ways (a nine-slice). Without it the image stretches whole.
  - `tint`, optional: `accent`, a palette role name or a hex color, multiplied in. One grey skin
    then works for a red, a yellow and a blue variant.
  - `outset`, optional, in dp: how far the image reaches past the box it skins, so torn edges,
    stars or splashes can spill over.
  - A plain string is `{ "path": … }`.
  - Drawn with `drawImage(srcOffset, srcSize, dstOffset, dstSize)`, nine calls per skin, cached
    in `drawWithCache` by size. Nothing is allocated per frame.
- `LayoutSettingsPanel` hides the XMB-only rows when the active style is not `XMB`, and gains a
  "Menu style" row only when the pack offers more than one through variants.

**Tests.** `LayoutSpec` parsing (`menuStyle` names, a problem in a rule or patch, XMB fields
ignored under `SLASH`). `Skin` parsing (strings and objects, bad slices, slices larger than the
image, tint names). The image budget and the order images are dropped in. Nine-slice geometry as
a pure function (corners keep their size, a box smaller than the corners scales them down evenly).
`MenuAnchors` for XMB matching today's numbers at the three crosshair presets, so the move
changes nothing. Emulator (`-gpu host`): a screenshot diff of the home screen before and after
the move.

## 2. `SLASH` list (M–L)

**Goal.** The menu as a vertical list of slanted rows. Each row tilts a little differently and
can wear a skin; the focused row is a bigger panel that can show the game's logo.

**Format.** Every field is optional:

```json
"slash": {
  "skewDeg": -12,
  "rowTilt": [-3, 2],
  "jitter": 0.35,
  "seed": 7,
  "listX": 0.08,
  "listY": 0.30,
  "listWidth": 0.38,
  "bleed": false,
  "textAlign": "START",
  "rowSkin": [
    { "path": "menu/paper_a.webp", "slice": [40, 16, 40, 16] },
    { "path": "menu/paper_b.webp", "slice": [40, 16, 40, 16] }
  ],
  "highlight": "SHARD",
  "highlightColors": ["accent", "#000000"],
  "focusedSkin": { "path": "menu/slab.webp", "slice": [60, 24, 60, 24], "outset": [8, 6, 8, 6] },
  "focusedScale": 1.6,
  "focusedContent": "TITLE",
  "focusedHeight": 0.16,
  "titleCase": "UPPER",
  "outline": 2
}
```

- **Geometry.**
  - `skewDeg`: the horizontal skew of every row, −20 to 20. `rowTilt`: the range each row's
    rotation is drawn from, in degrees, at most ±6.
  - `jitter` 0..1, with `seed`: how much row offsets and the highlight's corners vary. The
    values come from `hash(seed, row index)`, so a row keeps its shape across frames and visits.
  - `listX`, `listY`: where the list's first row sits, as fractions of the screen. `listWidth`:
    the rows' width, a fraction of the screen (0.15–0.6).
  - `bleed`: rows reach past the left edge of the screen; `listX` then only places the text (P4).
  - `textAlign`: `START`, `CENTER` or `END`, inside the row.
- **Row skins.**
  - `rowSkin`: a skin, or a list of skins. With a list, each row takes
    `hash(seed, index) % n`, so torn paper doesn't repeat row after row.
  - A skin is drawn in the row's skewed, tilted box. Draw the art straight and let `skewDeg`
    slant it, or set `skewDeg` to 0 and draw the slant into the art.
  - Without a skin, rows are text only, as in the first draft.
- **The focused row.**
  - `focusedSkin`: the panel behind the focused row. When set, it replaces `highlight`.
  - `highlight`, used without a `focusedSkin`: `SHARD` (an irregular quad plus a smaller offset
    shard), `SLAB` (a skewed rectangle) or `NONE`. `highlightColors`: front and back shard, as
    `accent`, a palette role name or a hex color.
  - `focusedContent`: `TITLE` (the default), `LOGO` (the game's `WHEEL` art, the title when it
    has none) or `LOGO_TITLE` (the logo on the panel, the title beside it, P3). Items without a
    logo (folders, utilities, settings) always show their title.
  - `focusedScale` sizes the focused title. With a logo, the panel is `focusedHeight` tall
    (a fraction of the screen, 0.08–0.3) and the logo fits inside its padding.
- **Text.** `titleCase`: `AS_IS` or `UPPER`. `outline`: a stroke behind the text, in dp, 0–4.

**Drawing.**
- Rows are Compose `Text` in `graphicsLayer { rotationZ }`. Skew isn't a `graphicsLayer`
  property, so it's applied with a `Matrix` in `drawWithContent`, together with the skin.
  Nothing is allocated per frame.
- The highlight is a `Path` built from `(seed, index, size)` and cached per focused index. It
  morphs to its new shape with the focus spring. A `focusedSkin` doesn't morph: it moves and
  resizes with the spring.
- Logos load through the same image path as the XMB's game art. While a logo loads, the title
  shows; it doesn't flash in once per scroll step, because the panel keeps the last logo for the
  length of the focus spring.
- Only the rows that fit are composed, as `XmbMetrics` already does for the XMB.
- Unfocused rows use `layout.unfocusedAlpha`. Long titles use `layout.textOverflow`.
- Fonts come from the pack's `typography.display`. With none, the built-in bold weight.

**Tests.** Unit: the highlight geometry as a pure function (same input gives the same corners,
corners stay inside the row's padded box at any `jitter`); the skin chosen per row (stable, and
spread across the list); `focusedContent` fallbacks (no logo, a folder); row boxes for every
`textAlign` and with `bleed`; `slash` parsing and clamping. Emulator: each mockup's list rebuilt
with original placeholder art, as screenshots for review.

## 3. `SLASH` motion (M)

**Goal.** The motion that makes this style: items arrive in a cascade, categories switch with a
wipe, and the focused item lands with a small overshoot.

**Format.**

```json
"slash": {
  "staggerMs": 28,
  "transition": "WIPE",
  "pop": 0.08
}
```

- `staggerMs` 0–80: the delay between rows sliding in when a list opens or a category changes.
  It's capped so the whole cascade takes at most 300 ms, however long the list.
- `transition`: `WIPE` (a skewed band of the highlight color sweeps across and reveals the new
  list), `SHARDS` (the old list breaks into 3–5 precomputed polygons that fly out) or `FADE`.
- `pop` 0–0.2: the focused row's overshoot on arrival, on top of `layout.motion`.

**Code.**
- A small `SlashMotion` state, keyed on `(categoryIndex, listKey)`, holds the transition's
  progress (one `Animatable`). Each row reads its own delayed progress from it. That's one
  animation per transition, not one per row.
- The transition's progress also goes out as `ScreenSignal.MenuTransition(progress)`. That's
  the source for Live backgrounds § 3's `transition` driver, so a scene can move in step with
  the menu.
- The focus pop reuses the `nav()` spring from `MotionSpecs.kt` with a lower damping ratio.

**Tests.** Unit: stagger timing as a pure function (the cap, `INSTANT` gives zero), shard polygons
covering the old list's bounds. Emulator: a screen recording of a category switch for review.

## 4. `SLASH` categories, drill-down and framed art (M)

**Goal.** Categories and sub-lists work the way they do in the XMB, drawn in this style, and
the focused game's art can sit in a decorated frame.

**Format.**

```json
"slash": {
  "categories": "ICONS",
  "categoryDecal": { "path": "menu/star.webp", "size": 1.8, "frames": [4, 1], "fps": 6 },
  "categoryY": 0.22,
  "art": "HERO",
  "artRect": [0.56, 0.28, 0.38, 0.44],
  "artTilt": -3,
  "artFrame": { "path": "menu/frame.webp", "slice": [64, 64, 64, 64], "outset": [24, 24, 24, 24] }
}
```

- **Categories.** `categories` picks how they're drawn:
  - `ICONS`, the default: the horizontal icon row, as in the XMB and all three mockups, using the
    pack's category icons, with the focused category's name under its icon. It is placed at
    `categoryY` and starts at `listX`. Left and right still switch category.
  - `LABELS`: a slanted strip of names along the top, the current one in the highlight color,
    the others dimmed.
  - `GLYPHS`: the same strip with the category glyphs.
- **The focus decal.** `categoryDecal` is a skin drawn behind the focused category, `size` times
  the icon (1–3). With `frames` and `fps` it is a sprite sheet, read like a Live backgrounds
  sprite layer. It follows the focus with the nav spring and holds its frame at rest.
- **Framed art.** `art` says what the empty side of the screen shows for the focused item:
  `HERO` (the game's `HERO` art, else its logo), `ICON` or `NONE`. `artRect` places it (screen
  fractions, x, y, w, h); `artTilt` turns it (±10°); `artFrame` is a skin drawn over its edge,
  usually with an `outset` so decorations spill past it (P5). `art: NONE` suits packs whose
  character art is in the background scene (P3, P4).
- **Drill-down.** Confirm on a folder slides the parent list out to the left, skewed and dimmed,
  with the new list cascading in (§ 3). The opened folder's title stays as a large, slanted
  header above the new list, in the `focusedSkin`. Back reverses it. `layout.drillDownStyle`
  doesn't apply.
- **Hints and status bar:** placed by `MenuAnchors.statusClearance`; their look is slice 5.
- **Info panel, details, options and the other overlays:** unchanged. They only use the size
  values of `XmbMetrics`.

**Tests.** Unit: decal frame selection over time and at rest; the art box with tilt and outset;
`categories` parsing. Emulator: drill two levels into Games, back out, switch category during a
transition; hero art changing as the focus moves, with and without a frame. Device (A55): the
same with a real library of several hundred games.

## 5. Clock and hint styles (S–M)

**Goal.** The mockups' big slanted clock and plated hints. They live in `components` next to
the existing clock and hint settings, so an XMB pack can use them too.

**Format.**

```json
"components": {
  "clockFormat": "MdEhma",
  "clockLayout": "STACKED",
  "clockSize": 2.4,
  "clockTilt": -6,
  "clockPlate": { "path": "menu/clock_plate.webp", "slice": [32, 32, 32, 32] },
  "hintStyle": "PLATES",
  "hintSkew": -10,
  "hintPlate": { "path": "menu/hint.webp", "slice": [12, 8, 12, 8], "tint": "#000000" }
}
```

- `clockLayout`: `INLINE` (today) or `STACKED`, where the date letters of `clockFormat`
  (`y M d E`) form the first line and the time letters (`H h m a`) the second. No new format
  string.
- `clockSize` scales the clock (1–3), `clockTilt` turns it (±12°), and `clockPlate` is a skin
  behind it. The other status bar slots keep their size and stay level.
- `hintStyle`: `PILL` (today's pill), `PLATES` (each hint on its own skewed plate, P5) or `PLAIN`
  (text and glyphs only, P3, P4). `hintSkew` slants plates and text (±15°); `hintPlate` is the
  plate's skin, else a flat plate in the surface color.
- Settings ▸ Theme ▸ Components follows the pack for these; no new device rows.

**Tests.** Unit: splitting a skeleton into its date and time lines (date only, time only, both);
parsing and clamping; `PILL` and `INLINE` staying pixel-identical to today. Emulator: each style
at `uiScale` 0.8 and 1.3, and with every status bar slot on.

## 6. Rules, settings, docs and sample (M)

**Protections the new style must keep:**
- `MotionProfile.INSTANT` and still/E-Ink mode: no stagger, no transition, no pop, no morphing
  highlight, decals on their first frame. Every change happens at once. Skins still draw.
- Standby (`LocalUiIdle`): nothing animates while the screen rests. The highlight and the decal
  hold still.
- `uiScale` scales the whole list, the skins' fixed edges and the clock; `listX`, `listY`,
  `listWidth`, `categoryY` and `artRect` stay fractions of the screen.
- Pausing when an opaque layer covers the screen, as today.

**Performance.** The style is Compose only: no GL program, so the `FrameBudgetWatchdog` and
`ShaderCanary` aren't involved. A skinned row costs nine image draws, so a full screen of rows is
under a hundred; the logo panel and decal add a few more. It's measured on a profiling build
(`assembleProfiling`; debug builds show false stutter), on the A55, three rounds interleaved
with XMB, over the sample pack's scene (the cost that matters is the menu on top of a busy
background): deadline jank while scrolling a long list fast, and during category switches with
each `transition`. Results go in `docs/status/PERFORMANCE.md`. The target is no more deadline
jank than XMB on the same pack.

**Docs and sample.**
- `THEME_SPECIFICATION.md`: `layout.menuStyle`, the `slash` block, skins, and the new
  `components` keys.
- A "menu styles" chapter in the theme guide, with screenshots, and a short "drawing skins"
  section (slices, outsets, drawing for a tint).
- The shader preview (`tools/shader-preview`) gets a skin viewer: open an image, drag the slice
  lines, see it at several sizes. Small, and it saves pack authors guesswork.
- A sample pack in `xross_themePacks`, aiming at the mockups' level with original art and an
  OFL-licensed display font: a Live backgrounds scene behind, paper row skins, a slab panel with
  `LOGO_TITLE`, a category decal, a framed hero, a stacked clock on a plate and plated hints. It
  also ships an `XMB` variant, to show the Layout panel's style row, and two color variants that
  share grey skins through `tint`.

---

## Interplay with live backgrounds

- § 1's `uFocus` comes from `ScreenSignal.Focus`, which both styles produce (slice 1 here). This
  replaces "CrosshairX + the focused row's height" in that plan.
- § 3's `transition` driver reads `ScreenSignal.MenuTransition` from this plan's slice 3. Under
  XMB it's driven by the category bar's slide.
- § 3 scenes carry the mockups' background and character art; `slash.art: NONE` leaves them
  room.
- Nothing else depends on the other plan. Each slice here works over the wave, a wallpaper or a
  shader.

## Decisions to confirm

1. **Where `menuStyle` can be set.** Proposed: base and variants only. The alternative is to
   also allow category patches, for example `SLASH` for Games and `XMB` for Settings. It would
   need a transition between styles, which is about S more.
2. **A user setting.** Proposed: the style belongs to the pack, chosen through its variants. The
   alternative is a device setting in Settings ▸ Theme that forces a style over any pack. That
   needs every pack to look acceptable in every style, which most won't.
3. **Pack-scriptable motion.** Proposed: not planned. Packs tune the fixed parameters above. If
   later packs need more, add another built-in style before a scripting format.
4. **`schemaVersion`.** Proposed: stays 2. All the new fields are optional, and an older Xross
   ignores `menuStyle` and the new `components` keys and draws the XMB.
5. **How skins are sliced.** Proposed: `slice` insets in the JSON on a plain png, jpg or webp.
   The alternative is Android `.9.png` files. JSON insets work with webp, in the shader preview
   and in tools on any machine, and can be tuned without re-exporting the art.
6. **Clock and hint styles in `components`, not in `slash`.** Proposed: `components`, so XMB
   packs get them too and they sit with the existing clock and hint settings.
