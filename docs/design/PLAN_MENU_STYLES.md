# Menu styles — implementation plan

A proposal, not yet scheduled to a release. It lets a theme pack replace the XMB menu with
another built-in menu style. The first new style, `SLASH`, is a bold, slanted, high-contrast
list with shard-shaped highlights and staggered motion, inspired by the menus of Persona-era
JRPGs. Written against `main` at `14ed5aa` (2026-11-25). The companion plan is
`PLAN_LIVE_BACKGROUNDS.md`, which covers what is drawn behind the menu.

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.

| # | Slice | Size | Status |
| --- | --- | --- | --- |
| 1 | Renderer seam: `layout.menuStyle`, `MenuAnchors`, `ScreenSignal.Focus` | M | ⬜ |
| 2 | `SLASH` list: slanted rows, shard highlight, typography | M | ⬜ |
| 3 | `SLASH` motion: entrance stagger, category transition, focus pop | M | ⬜ |
| 4 | `SLASH` categories and drill-down | S–M | ⬜ |
| 5 | Rules (E-Ink, standby, `uiScale`), Layout panel, docs, sample pack, A55 timings | S–M | ⬜ |

**Total:** about L, 1.5–2 weeks.
**Order:** 1 to 5. Slice 1 ships alone with no visible change: XMB is drawn through the new seam.
Slice 1 should land before or with Live backgrounds § 1, because that section's `uFocus` comes
from the new `ScreenSignal.Focus`.
**Not planned:**
- A pack-scriptable menu (keyframes, a layout DSL). It would be XL, with a large surface to
  validate, and a fixed renderer with parameters gets most of the effect. See "Decisions".
- Shipping any game's fonts, art, logos or exact screen layouts. The sample pack uses original
  art and freely licensed fonts.

## Where things stand

- The menu is two composables in `XmbScreen.kt` (1,881 lines): `CategoryBar` (the horizontal
  icon bar) and `ItemColumn` (`ItemRow`, `XmbIcon`, `ArtIcon`, `ThumbIcon`, `FocusDivider`,
  `BackChevron` and friends).
- `XmbMetrics` holds the geometry. Its size values (`s`, `titleSize`, `statusSize`…) are used by
  many screens (details, options, search, RA hub, wizards). Its **anchor** values (`anchorX`,
  `barY`, `focusedY`, `aboveY`) appear only in `XmbScreen.kt`, 14 times. That's what keeps the
  seam an M and not an L. The anchor values are used in four places outside the menu itself:
  - the status bar's `leftMaxHeight` (the left cluster stops above the category bar)
  - `ScreenSignal.CrosshairX`, which `MainActivity` sends to `sounds.setCrosshair` for panning
  - the focus point `Offset(m.anchorX, m.focusedY)`
  - the drill-down overlays (folder column, breadcrumb, chevron)
- The `layout` block (`theme/engine/.../pack/Layout.kt`) only adjusts the XMB: crosshair, tile
  aspects, spacing, focus scale and alpha, divider, drill-down style, text overflow, motion.
- Navigation is already separate from drawing: `XmbUiState`, `XmbEvent`, `NavAction` and
  `MenuTree`. Left and right switch category, up and down move the focus, and confirm and back
  drill in and out. A new style uses the same state and input, so **the view model doesn't
  change**.
- Packs already ship fonts (`typography.display`, `typography.body`), palettes, focus frames
  and motion profiles. `SLASH` reuses all of them.

---

## 1. Renderer seam (M)

**Goal.** `XmbScreen` draws "the menu" without knowing which style it is. Other screens stay as
they are.

**Format.**

```json
"layout": {
  "menuStyle": "SLASH",
  "slash": { "...": "see § 2" }
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

- `ScreenSignal.CrosshairX` becomes `ScreenSignal.Focus(x, y)`, both 0..1, eased. `MainActivity`
  passes `x` to `sounds.setCrosshair` as today. It also becomes the source of Live backgrounds
  § 1's `uFocus`, so a pack shader's parallax follows the focus in either style.
- `LayoutSettingsPanel` hides the XMB-only rows when the active style is not `XMB`, and gains a
  "Menu style" row only when the pack offers more than one through variants.

**Tests.** `LayoutSpec` parsing (`menuStyle` names, a problem in a rule or patch, XMB fields
ignored under `SLASH`). `MenuAnchors` for XMB matching today's numbers at the three crosshair
presets, so the move changes nothing. Emulator (`-gpu host`): a screenshot diff of the home
screen before and after the move.

## 2. `SLASH` list (M)

**Goal.** The menu as a vertical list of slanted slabs. Each row tilts a little differently, and
the focused row is large, high-contrast and backed by a jagged highlight.

**Format.** Every field is optional:

```json
"slash": {
  "skewDeg": -12,
  "rowTilt": [-3, 2],
  "jitter": 0.35,
  "seed": 7,
  "highlight": "SHARD",
  "highlightColors": ["accent", "#000000"],
  "focusedScale": 1.6,
  "titleCase": "UPPER",
  "outline": 2,
  "listX": 0.08,
  "listY": 0.30,
  "art": "HERO"
}
```

- `skewDeg`: the horizontal skew of every row, −20 to 20. `rowTilt`: the range each row's
  rotation is drawn from, in degrees, at most ±6.
- `jitter` 0..1, with `seed`: how much row offsets and the highlight's corners vary. The values
  come from `hash(seed, row index)`, so a row keeps its shape across frames and visits.
- `highlight`: `SHARD` (an irregular quad plus a smaller offset shard), `SLAB` (a skewed
  rectangle) or `NONE`. `highlightColors`: front and back shard, as `accent`, a palette role name
  or a hex color.
- `titleCase`: `AS_IS` or `UPPER`. `outline`: a stroke behind the text, in dp, 0–4.
- `listX` and `listY`: where the list's first row sits, as fractions of the screen.
- `art`: what the empty side of the screen shows for the focused item: `HERO` (the game's hero
  art, else its logo), `ICON` or `NONE`.

**Drawing.**
- Rows are Compose `Text` in `graphicsLayer { rotationZ; transform skew }`. Skew isn't a
  `graphicsLayer` property, so it's applied with a `Matrix` in `drawWithContent`. Nothing is
  allocated per frame.
- The highlight is a `Path` built from `(seed, index, size)` and cached per focused index. It
  morphs to its new shape with the focus spring.
- Only the rows that fit are composed, as `XmbMetrics` already does for the XMB.
- Unfocused rows use `layout.unfocusedAlpha`. Long titles use `layout.textOverflow`.
- Fonts come from the pack's `typography.display`. With none, the built-in bold weight.

**Tests.** Unit: the highlight geometry as a pure function (same input gives the same corners,
corners stay inside the row's padded box at any `jitter`), `slash` parsing and clamping.

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

## 4. `SLASH` categories and drill-down (S–M)

**Goal.** Categories and sub-lists work the way they do in the XMB, drawn in this style.

- **Categories:** a slanted strip of labels (or the category glyphs, by `slash.categories`:
  `LABELS` or `GLYPHS`) along the top. The current one is in the highlight color, the others
  dimmed. Left and right still switch category.
- **Drill-down:** confirm on a folder slides the parent list out to the left, skewed and dimmed,
  with the new list cascading in (§ 3). The opened folder's title stays as a large, slanted
  header above the new list. Back reverses it. `layout.drillDownStyle` doesn't apply.
- **Hints and status bar:** unchanged components, placed by `MenuAnchors.statusClearance`.
- **Info panel, details, options and the other overlays:** unchanged. They only use the size
  values of `XmbMetrics`.

**Tests.** Emulator: drill two levels into Games, back out, switch category during a
transition. Device (A55): the same with a real library of several hundred games.

## 5. Rules, settings, docs and sample (S–M)

**Protections the new style must keep:**
- `MotionProfile.INSTANT` and still/E-Ink mode: no stagger, no transition, no pop, no morphing
  highlight. Every change happens at once.
- Standby (`LocalUiIdle`): nothing animates while the screen rests. The highlight holds still.
- `uiScale` scales the whole list; `listX` and `listY` stay fractions of the screen.
- Pausing when an opaque layer covers the screen, as today.

**Performance.** The style is Compose only: no GL program, so the `FrameBudgetWatchdog` and
`ShaderCanary` aren't involved. It's measured on a profiling build (`assembleProfiling`; debug
builds show false stutter), on the A55, three rounds interleaved with XMB:
deadline jank while scrolling a long list fast, and during category switches with each
`transition`. Results go in `docs/status/PERFORMANCE.md`. The target is no more deadline jank
than XMB on the same pack.

**Docs and sample.**
- `THEME_SPECIFICATION.md`: `layout.menuStyle` and the `slash` block.
- A "menu styles" chapter in the theme guide, with screenshots.
- A sample pack in `xross_themePacks`: `SLASH` over a Live backgrounds § 1 shader with three
  painted layers moving with `uFocus`. Original art and an OFL-licensed display font. It also
  ships an `XMB` variant, to show the Layout panel's style row.

---

## Interplay with live backgrounds

- § 1's `uFocus` comes from `ScreenSignal.Focus`, which both styles produce (slice 1 here). This
  replaces "CrosshairX + the focused row's height" in that plan.
- § 3's `transition` driver reads `ScreenSignal.MenuTransition` from this plan's slice 3. Under
  XMB it's driven by the category bar's slide.
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
   ignores `menuStyle` and draws the XMB.
