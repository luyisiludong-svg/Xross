# Phase 2b.8 Implementation Prompts (Slices 2b.8-prep, 2b.8a – 2b.8c)

Phase 2b.8 (the Live Theme Mixer) of the Xross launcher (`dev.xross.launcher`), from Roadmap §4.3
(the 2b.8 row and the "Live Theme Mixer" architecture check) and the contracts in Roadmap
Appendix A.1, A.4, A.5 and A.13. Each slice is self-contained, unit- and device-tested, and leaves
the launcher shippable. Branches: `refactor/2b8-prep` from `main` (after `762d581`), merged first;
then `feat/theme-2b8` from `main` for 2b.8a–c.

**Order note.** 2b.8 needed "everything before it" (2b.2–2b.7). 2b.6 (haptics) is iced (Roadmap
§4.8), so 2b.8 waits on nothing: 2b.5 and 2b.7 are merged into `main`. Nothing here touches
input, audio playback or RetroAchievements. The 2b.7e A55 pass is still open (Carry-overs); it can
run while 2b.8-prep is in review, since prep doesn't change what 2b.7e draws.

**What the milestone is.** Two halves that share one idea: the user layer over a pack.
- **Mixer:** the layout values a pack sets (crosshair, UI scale, list spacing, focused scale) get
  Settings ▸ Theme rows that change the menu while you adjust them. They were moved out of 2b.2
  because 2b.8 owns the user layer.
- **Export:** Settings ▸ Theme ▸ Export theme… writes a new `Xross/themes/<id>/` in one of three
  scopes: the **whole pack** (every variant, override and rule, as the pack ships), **one
  variant** (that variant flattened into a single-look pack), or the **current look** (the pack,
  its variant and every user pick over it, which loads to what is on screen with no user
  overrides). 2b.8c adds sharing any pack as an `.xtheme`. Every pack can be exported and shared,
  the built-in Default pack included.

**Why the Default pack matters here.** The owner plans a full Default pack rework once Theme 2.0
(2b.8) is done, and today's Sony-like look moves out to a community pack (Roadmap §4.2). A
whole-pack export of the built-in Default pack, shared as an `.xtheme`, is how that community pack
is produced, so it has to be exact.

**Why a prep slice.** Export has to know every user-over-pack value, and today that list is kept
in three places that already drift: `ThemeDefaults.reset` (`theme/pack/EffectOverrides.kt`), the
Reset row's summary (`MenuTree.resetThemeRow`) and the `LookInputs` that `ThemeLookResolver` reads.
2b.8a adds a layout layer and 2b.8b reads all of it, so the list becomes one type first. The other
prep item is the next piece of Assessment #6 (the `XmbViewModel` split): the 28 theme commands in
`XmbViewModel.onCommand` (2,647 lines) move into a `ThemeController`, the way 2b.7-prep moved the
RetroAchievements logic into `RaController`, before 2b.8 adds more. The rest of
[`ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md) §8 (package moves, the `XmbEvent` sink,
singletons, imaging, modules) still waits: none of it blocks 2b.8.

---

## Progress

| Slice | State |
|---|---|
| 2b.8-prep | ⬜ |
| 2b.8a | ⬜ |
| 2b.8b | ⬜ |
| 2b.8c | ⬜ |

---

## What exists today (2026-11-15)

| Piece | Where | State |
|---|---|---|
| Theme cascade | `theme/ThemeLookResolver.kt` | Pure. `LookInputs` (pack, variant, category, system, battery, minute, `effects`, `surfaceStyle`, `profile`, three colour-layer flags, `components`) → `ResolvedLook` (look, layout, surface, switches, profile, components). Layout is `profile.layout(look.layout ?: pack.layout)`: **no user layout layer** |
| Layout model | `theme/pack/Layout.kt` | `LayoutSpec` (pack JSON: `preset`, `crosshairX/Y`, `cardAspectRatio`, `tile`, `itemSpacingDp`, `focusedScale`, `unfocusedAlpha`, `uiScale`, `focusDivider`, `drillDownStyle`, `textOverflow`, `motionProfile`, `spring`), `resolveLayout` with `LayoutLimits` clamps (UI scale 0.5–1.5, focus 1.00–1.35, spacing ≤ 32 dp), `CrosshairPreset` (`CLASSIC_PS3`, `PSP_CENTER`, `MODERN_WIDESCREEN`). Rules can patch `layout` (`ContextRules.kt`); the screen clamps the crosshair per display (`Crosshair.clampX` / `clampY` in `XmbScreen`) |
| User layer | `config/ConfigFiles.kt` `Display`; `data/XrossSettings` | `display.effects` (`EffectOverrides`), `display.components` (`ComponentOverrides`), `surfaceStyle`, `themeVariant`, `customWallpaper`, `quickProfile`, `adaptiveGamePalette` / `circadianCurve` / `batteryTint` (null = the pack's), wave values (`waveSpeed`, `waveLines`, `waveTurbulence`, `waveThickness`, `waveTint`, `waveColors`; always set, Wave settings ▸ Reset restores the pack's), `theme` (one of the 8 colour themes, or `PACK`) |
| Reset | `ThemeDefaults.reset` / `isDefault` in `theme/pack/EffectOverrides.kt`; summary in `MenuTree.resetThemeRow`; `Command.ResetThemeDefaults` in `XmbViewModel` | Clears effects, components, surface, wallpaper, the three colour layers. Keeps pack, variant, colour theme, quick profile and wave values |
| Theme commands | `XmbViewModel.onCommand` (around line 2260–2410) | Pack, variant, colour theme, quick profile, colour switches, surface, effects, components, slots, pills, wallpaper, import, reset: all inline |
| Settings ▸ Theme | `state/MenuTree.kt` `displaySettings()` | Theme pack, Theme variant, Quick profile, folders Colors, Background, Effects, Intro and transitions, Components, Menu and profile, then Reset to theme defaults and Import theme (.xtheme)… |
| Slider panel | `state/WaveEditor.kt` (`WaveRow`, `WaveEditorState`, `WaveEditorOutcome`) | A row-and-slider panel over the menu with Left/Right to adjust and its own Reset row: the pattern for 2b.8a |
| Name entry | `state/NameEntry.kt` (`NamePurpose`, `NameEntry`) | The gamepad keyboard for a short name (today only `NewAppFolder`) |
| Pack loading | `theme/pack/ThemePackLoader.kt` | Built-in Default pack from APK assets (`assets/themes/default/`: `theme.json`, `surfaces.json`, `icons/`, `sounds/`), others from `Xross/themes/<id>/`. `readManifest` merges `imports` into one `JsonObject` (`ThemeManifest.merge`, `rules` concatenated) and **decodes it; the merged JSON isn't kept**. Folders starting with `.` are skipped by the scan and `ThemeWatcher` |
| Import | `theme/pack/ThemeArchive.kt` | `stage` (zip slip, known folders, file types, 50 MB, 2,000 entries, BGM and boot-video caps) → `install(replace)` / `asCopy`; staging under `.import-…`. There is **no writer** of `theme.json` or of an archive |
| Credentials | `CredentialStore` | A.1: never in `settings.json`, a pack or anything in `Xross/`; tests assert it for `settings.json` and `friends.json` |
| Tests | `app/src/test` | 852 unit tests at the end of 2b.7e |

---

## Overview

| Slice | Title | Scope | Risk |
|---|---|---|:---:|
| **2b.8-prep** | Groundwork | `UserLook`: every user-over-pack value in one type, read by the resolver, Reset and its summary; `ThemeController` out of `XmbViewModel` (behaviour unchanged); `LoadedTheme` keeps its merged manifest JSON and the list of files it read | Low |
| **2b.8a** | Layout mixer | `display.layout` (`LayoutOverrides`); Settings ▸ Theme ▸ Layout panel: crosshair, UI scale, list spacing, focused scale, live while adjusting; cascade slot above the pack and its rules, below the quick profile; Reset includes it | Medium |
| **2b.8b** | Export theme (whole pack, one variant, current look) | `LookExporter` (pure): pack JSON + scope (+ variant, + `UserLook`) → one `theme.json` v2 and a file list; writer to a staged folder, renamed into `Xross/themes/<id>/`; name entry; copies from APK assets or the pack folder; offer to switch to it | High |
| **2b.8c** | Share as `.xtheme` and the round-trip gate | Zip any pack (folder or the built-in Default pack's assets) to a SAF document; the A.13 gate for each scope: export → zip → `ThemeArchive.stage` → install → load resolves to the same look in every context; no credentials anywhere | Medium |

Order: prep → a → b → c. 2b.8a and 2b.8b can swap once prep is in (2b.8b exports whatever
`UserLook` holds, so landing 2b.8a first only adds a block to the export). 2b.8b is the critical
path; 2b.8c is mostly tests plus a small writer.

Unit-test gate from Appendix A.13 (2b.8): an exported pack re-imports to the same look and contains
no credentials. Device gate added here: the layout rows move the menu while adjusting without
clipping on the 12 display-matrix configurations, and `palette-perf.sh` is unchanged.

---

## Slice 2b.8-prep: Groundwork

```markdown
### Task: Prepare for Phase 2b.8 — UserLook, ThemeController, raw pack JSON

#### Requirements
1. **`UserLook` (`theme/pack/`):** one `@Immutable` value with every user pick that sits over the
   active pack: `effects`, `components`, `surfaceStyle`, `customWallpaper`, the three colour-layer
   flags, and (from 2b.8a) `layout`. Built from `XrossSettings` in one place
   (`UserLook.of(settings)`); `UserLook.NONE` is "the pack as it is".
   - `ThemeLookResolver` takes it (`LookInputs.user`) instead of the separate fields.
   - `ThemeDefaults.reset` / `isDefault` become `UserLook`-based: reset writes `UserLook.NONE`
     back; what it keeps (pack, variant, colour theme, quick profile, wave values) is unchanged.
   - The Reset row's summary counts from `UserLook` (`changes(): List<String>`), so a field added
     to `UserLook` shows up in Reset, its summary and 2b.8b's export together.
   **Behaviour unchanged.**
2. **`ThemeController` (next piece of Assessment #6, `state/`):** the theme commands move out of
   `XmbViewModel.onCommand` (pack, variant, colour theme, quick profile, colour switches, surface,
   effects, components, status slots, badge pills, wallpaper, `.xtheme` import and its pending
   replace/keep-both, reset) into a controller with the same `Host` pattern as `RaController`
   (toasts, sounds, effects through the host). `XmbViewModel` keeps one field and forwards.
   **Behaviour unchanged**; 2b.8a–b add their commands here.
3. **Raw pack JSON:** `LoadedTheme` keeps the merged manifest `JsonObject` it decoded (imports
   merged, `rules` concatenated: exactly what `readManifest` builds today) as `sourceJson`, and
   the pack-relative paths of every file it read (fonts, icons, logos, sounds, shader, post-process
   file, wallpapers, BGM, boot video) as `files: Set<String>`, with the source (APK asset or
   folder). Nothing reads them yet; 2b.8b does. The Default pack's are its asset paths.

#### Tests
- `UserLookTest`: `of(settings)` round trip; `NONE` equals a fresh `XrossSettings`; every
  `UserLook` field changes `isDefault` and appears in `changes()` (a reflection-free table test, so
  a new field without a row fails).
- `ThemeLookResolverTest` passes unchanged through `LookInputs.user`.
- `ThemeControllerTest`: the view-model theme tests move here unchanged and pass.
- `ThemePackLoaderTest`: `sourceJson` of a pack with `imports` equals the merge; `files` lists
  exactly what was read for the Default pack and a stress pack (no unreferenced files, nothing
  outside the pack).

#### Device checks (smoke + theme suites)
- Theme suite on the API 37 emulator: same result as `main` (311/312 at 2b.7e).
- `palette-perf.sh` before and after: janky frames unchanged within noise.
```

---

## Slice 2b.8a: Layout mixer

```markdown
### Task: Implement Phase 2b.8a — the user's layout overrides, live

#### Requirements
1. **`LayoutOverrides` (`theme/pack/Layout.kt`), saved as `display.layout`:** every field nullable
   = the pack's: `preset` (a `CrosshairPreset` name), `uiScale`, `itemSpacingDp`, `focusedScale`.
   Decoded leniently like `display.effects`: a bad value drops on its own, numbers clamp to
   `LayoutLimits`. Part of `UserLook` (prep), so Reset to theme defaults clears it and its summary
   counts it ("Clears 2 layout values, 1 effect").
2. **Cascade:** the user's values win over the pack's base, variant, category and system
   overrides **and** `rules.json` layout patches (a user pick wins in every context, as
   `display.effects` does); the quick profile still applies last, and touches only motion. In
   `ThemeLookResolver`: `profile.layout(user.layout.applyTo(look.layout ?: pack.layout))`.
   The crosshair preset replaces `crosshairX` / `crosshairY`; `Crosshair.clampX` / `clampY` per display still
   runs after, so a preset can never push a neighbour category off screen.
3. **Settings ▸ Theme ▸ Layout** (a new row after Components, opening a panel like Wave settings):
   - Crosshair: Theme pack / Classic PS3 / PSP centre / Modern widescreen.
   - UI scale: Theme pack, then 50–150% in 10% steps.
   - List spacing: Theme pack / Tight (6 dp) / Standard (12 dp) / Spacious (20 dp).
   - Focused scale: Theme pack, then 1.00×–1.35× in 0.05 steps.
   - Reset layout (this panel's values only).
   Left/Right change the focused row; each step is saved and **the menu behind the panel moves
   with it** (that is the "live" in Live Theme Mixer). "Theme pack" shows the pack's value in the
   summary ("Theme pack · 100%"). The row's subtitle lists what is overridden.
4. **The panel itself:** drawn at 100% UI scale and anchored bottom-right, so changing the scale
   or the crosshair never moves the row under the cursor; the menu behind it is not dimmed while
   the panel is open (it is the preview).
5. **Performance:** a step changes `ResolvedLook.layout` once; the item column animates to the new
   spacing and scale with the current motion profile (snap under `INSTANT`). Holding Left/Right
   repeats at the normal hold-to-repeat rate and must not drop frames.

#### Tests
- `LayoutOverridesTest`: decode with bad values; clamping; preset by name and alias (`PSP_CENTRE`);
  `applyTo` over a pack layout, a variant, a category override and a rule that patches the same
  field (the user wins in each); the quick profile still sets motion only.
- `UserLookTest` (extended): `layout` counts in `changes()` and Reset.
- MenuTree: the Layout row, its summary, the panel rows and steps at the ends (clamped, then
  "Theme pack" is reachable by stepping below the minimum, as Wave settings does with its tint).

#### Device checks (theme suite, new "Layout mixer (2b.8a)" section)
- Each crosshair preset, UI scale 50% and 150%, each spacing, focused scale 1.00 and 1.35:
  screenshots, and the menu moves while the panel is open.
- `display-matrix.sh` 12/12 with the extremes (50% + Modern widescreen, 150% + PSP centre): no
  clipped texts, one whole neighbour category visible.
- `palette-perf.sh` unchanged within noise with a non-default layout; a held Left on UI scale
  stays within the 0–1.5% janky gate (profiling build).
- On the A55 at 120 Hz: the same, plus Reset to theme defaults clears the layout.
```

---

## Slice 2b.8b: Export theme (whole pack, one variant, current look)

```markdown
### Task: Implement Phase 2b.8b — Settings ▸ Theme ▸ Export theme (whole pack, one variant, current look)

#### Requirements
1. **`ExportScope`:** `WHOLE_PACK`, `VARIANT(id)`, `CURRENT_LOOK`. Any pack can be exported in any
   scope, the built-in Default pack included (from its APK assets). `VARIANT` is offered only for
   packs with variants, once per variant ("Base look" included).
2. **`LookExporter` (`theme/pack/`, pure, no Android):** input = a `LoadedTheme` (`sourceJson`,
   `files`), the scope and, for `CURRENT_LOOK`, `UserLook`, the colour theme and the wave values;
   output = one `theme.json` `JsonObject` and the list of files to copy (pack-relative source path
   → destination path). In every scope `imports` are already merged (`sourceJson`), so the output
   is a single `theme.json` (no `surfaces.json` split), and `schemaVersion: 2`.

   **`WHOLE_PACK`** — the pack as it ships, so it re-imports to the same pack:
   - `sourceJson` as it is: every variant, `categoryOverrides`, `systemOverrides` and `rules`
     kept; no user pick, colour theme or wave value is written.
   - Files: every file the manifest references in any variant, override or rule, plus each
     folder the import allowlist knows (`icons/`, `sounds/`, `fonts/`, root `shader.frag`,
     `postprocess.agsl`, `wallpaper.*`, `bgm.*`, `boot.mp4`), so a pack that picks files by
     convention (the Default pack's `sounds/`) keeps them. Never dot-files or `.git`.
   - `name` defaults to the pack's; `author`, `version` and `description` are the pack's, with
     "Exported by Xross on <date>" appended to the description.

   **`VARIANT(id)`** — one colourway of the pack, without the user's picks:
   - The variant's `palette` / `effects` patch merges into the base (`ThemeManifest.merge`);
     `variants` is written empty. Overrides and rules are kept (they are the pack's, and apply to
     that variant today).
   - Files: what that variant uses in some context (the variant's look over `LoadedTheme.files`).
   - `name` defaults to "<Pack> · <Variant>".

   **`CURRENT_LOOK`** — what is on screen:
   - **Flatten:** as `VARIANT` for the selected variant (the base when none).
   - **User layer on top, in every context:** each value the user set is written into the base
     **and removed from every `categoryOverrides`, `systemOverrides` and `rules` patch that sets
     the same key**, because today the user's pick wins in every context and the exported pack
     must behave the same with no user layer. This covers `effects.*` keys, `effects.surfaceStyle`,
     the colour-layer flags (`palette.circadianCurve`, `palette.batteryTint`,
     `effects.adaptiveGamePalette`) and 2b.8a's `layout` keys. A patch left empty is dropped.
   - **Components:** the resolved `ResolvedComponents` written as the `components` block
     (overrides and rules never patch it, 2b.7a).
   - **Wave:** the user's wave values written as `wave` (`speed`, `lineCount`, `turbulence`,
     `ribbonThickness`, and the tint's gradient as `gradient`).
   - **Colour theme:** when `display.theme` isn't `PACK`, the colour theme's colours are written
     as the base `palette` (Original writes this month's colours, and the export summary says so).
     How the pack's category/system palette patches combine with a non-pack colour theme today is
     whatever `ColorPipeline` does: the exporter reproduces it, and 2b.8c's round-trip test is the
     judge, not this prompt.
   - **Wallpaper:** a custom Xross wallpaper (`customWallpaper`, a photo URI or path) is copied as
     `wallpaper.<ext>` and set as `effects.wallpaperPath`; the pack's own wallpaper otherwise.
   - **Files:** only what the resolved look uses in some context (`LoadedTheme.files`, plus the
     custom wallpaper); a file no context reads isn't copied. Paths keep their pack-relative names.
   - `name` defaults to "<Pack> (my look)".

   **Identity, every scope:** `name` from the user (prefilled as above), `version` the pack's for
   `WHOLE_PACK` and `"1.0.0"` otherwise, `description` "Exported from <pack> <version>
   (<scope>) by Xross on <date>" (`WHOLE_PACK` keeps the pack's text in front). `author` is the
   pack's for `WHOLE_PACK` and `VARIANT`, empty for `CURRENT_LOOK`; never the RetroAchievements
   user name or anything from `CredentialStore`.

   **Never exported, any scope:** the quick profile (it stays a setting and keeps applying over
   the new pack; the `CURRENT_LOOK` summary says so), sound volumes (the mixer is the device's,
   not the look's), `profile.avatarStyle` (the loader doesn't read it from packs yet), the
   runtime game-art palette (A.4). `WHOLE_PACK` and `VARIANT` carry no user pick at all.
3. **Writer (`theme/pack/ThemeExport.kt`):** writes into `Xross/themes/.export-<nanos>/`
   (dot-prefixed, so the scan and `ThemeWatcher` skip it), then renames to `<id>/`; on any failure
   the staging folder is removed and nothing is half-written. `<id>` from
   `ThemeArchive.idFor(name, …)`; an existing id is never overwritten (`-2`, `-3`, …). Total size
   capped at `ThemeArchive.MAX_BYTES` (50 MB) so the folder can be shared and re-imported; over it,
   refuse with the largest files named. Copies from APK assets for the Default pack, from the pack
   folder otherwise, from the content resolver for a photo wallpaper (through the existing
   persisted grant). Runs on `Dispatchers.IO`; the menu shows the busy icon meanwhile.
4. **Flow (`ThemeController`):** Settings ▸ Theme ▸ **Export theme…** (after Reset to theme
   defaults) opens an Options list for the active pack: **Whole pack** ("<Pack>, 3 variants"),
   one row per variant when the pack has them ("<Variant> only"), **Current look** ("with your
   picks: 3 effects, 2 layout values"). The Theme pack row's Options offer the same list for any
   installed pack, not only the active one (`Current look` only for the active one). Then → name
   entry (`NamePurpose.ExportTheme`, prefilled per scope) → a confirm panel listing what goes in
   ("Default, 3 variants, 41 files, 2.1 MB"; or "Default + 3 effects, 2 layout values, your
   wallpaper; quick profile not included") → write → toast "Saved as <name> in Xross/themes" and
   an Options choice **Switch to <name>**:
   - `WHOLE_PACK`: sets `themePack = <id>` and keeps the variant and the user layer, so the
     screen doesn't change.
   - `VARIANT`: sets `themePack = <id>`, `themeVariant = ""`, keeps the user layer.
   - `CURRENT_LOOK`: sets `themePack = <id>`, `themeVariant = ""`, `theme = PACK` and resets the
     user layer (`UserLook.NONE`), so the screen doesn't change. The wave values already equal the
     new pack's.
5. **Licence note:** the confirm panel and the written `description` say that copied fonts,
   sounds, icons and art keep their original licences (Roadmap §4.3 architecture row). The same
   note for the Default pack: no extra warning or block.

#### Tests
- `LookExporterTest` (fixtures, no files):
  - `WHOLE_PACK`: the output equals `sourceJson` apart from the description; variants, overrides
    and rules kept; files include convention folders (the Default pack's `sounds/`) and files only
    a non-selected variant or a rule uses; no user pick leaks in.
  - `VARIANT`: the variant flattened, `variants` empty, overrides and rules kept; "Base look" of
    a pack with variants; files only that variant uses.
  - `CURRENT_LOOK`: a user effect removed from a category override, a system override and a rule;
    an override left empty dropped; components and wave written; a non-`PACK` colour theme; the
    file list for the Default pack and a stress pack (only used files); `author` empty.
  - Every scope: no `imports`; the Default pack from its assets.
- `ThemeExportTest` (temp dirs): staging then rename; a failure mid-copy leaves no folder; id
  collision → `-2`; the 50 MB cap; the export loads with `ThemePackLoader.loadStrict` and no
  schema problems.
- **No credentials (A.1):** the RA key, RA user name and ScreenScraper password from a seeded fake
  `CredentialStore` appear in no exported file (bytes searched, the `friends.json` assertion
  extended).
- `ThemeControllerTest`: the scope list per pack (no variant rows without variants, Current look
  only for the active pack), the flow's states, cancel at each step, what Switch to keeps or
  resets per scope.

#### Device checks (theme suite, new "Export (2b.8b)" section)
- Whole pack of the built-in Default pack: the folder loads, and after Switch to the screenshots
  match the before shots (pixel diff with the wave paused) in every category.
- Current look of the Default pack with effects, components and a layout set: the same.
- The stress pack (variants, category overrides, rules): whole pack, each variant and the current
  look, each checked in two categories and with a system override's game focused.
- A photo wallpaper: copied; the export still loads with the photo's grant revoked.
- On the A55 with a folder pack and the user's own picks.
```

---

## Slice 2b.8c: Share as `.xtheme` and the round-trip gate

```markdown
### Task: Implement Phase 2b.8c — share any pack as .xtheme; prove each export scope re-imports to the same look

#### Requirements
1. **Writer (`ThemeArchive.write(source, out)`):** zips a pack into an `.xtheme` that
   `ThemeArchive.stage` accepts, from a pack folder or from the built-in Default pack's APK assets
   (the same `PackSource` the loader reads): the pack's files at the archive root, no `.git`,
   `.nomedia` or dot-files, the same size and entry caps as the reader. For the Default pack this
   is the 2b.8b `WHOLE_PACK` output streamed straight into the zip (no folder written first).
2. **Flow:** **Share as .xtheme…** after any export, and in the Theme pack row's Options for
   **every** installed pack, the built-in Default pack included, opens the SAF create-document
   picker (`application/zip`, name `<id>.xtheme`). Sharing a whole pack, a variant or the current
   look goes through the 2b.8b scope list first (export to the archive instead of a folder). When
   the archive holds a wallpaper copied from the user's photos, the confirm says so first
   ("Includes your wallpaper photo").
3. **The A.13 gate as a test (`ThemeRoundTripTest`, Robolectric or plain JVM with temp dirs):**
   for the built-in Default pack and the stress pack, each scope goes export → `ThemeArchive.write`
   → `stage` → `install` → `load`, then for a matrix of contexts (each category; no system, a
   system with an override; battery 15% / 50% / charging; 08:00 / 20:00):
   - `WHOLE_PACK`: for **each variant** (and the base), the original and the re-imported pack
     resolve equal with `UserLook.NONE`; the variant list, ids and names are the same.
   - `VARIANT(id)`: the re-imported pack's base resolves equal to the original with that variant
     and `UserLook.NONE`.
   - `CURRENT_LOOK`: the original with a user layer that sets every `UserLook` field resolves
     equal to the re-imported pack with `UserLook.NONE`.
   "Equal" is `ThemeLookResolver.resolve` and `ColorPipeline`'s palette. A mismatch names the
   scope, the context and the field.
4. No credential bytes in any archive (the 2b.8b assertion over the zip).

#### Tests
- `ThemeArchiveWriteTest`: round trip of a folder and of the Default pack's assets; dot-files left
  out; caps.
- `ThemeRoundTripTest` as above.

#### Device checks (theme suite)
- Share the built-in Default pack (whole pack) to `Download/`, Import theme (.xtheme)… it back as
  a copy: the copy's screenshots match the built-in pack's in every category and variant. This is
  the path to the community pack of today's look (Carry-overs).
- Share a current-look export, delete its folder, import it back: the same screenshots as before
  the export.
- On the A55: share to a cloud folder and re-import on the emulator (another device's paths).
```

---

## Decisions to confirm before 2b.8a starts

Defaults chosen in this doc. Change them here before the slice that uses them starts.

| Question | Default | Alternative |
|---|---|---|
| Which layout values get rows (2b.8a) | The four from the Roadmap: crosshair preset, UI scale, list spacing, focused scale | Also motion profile, unfocused opacity, text overflow, focus divider, drill-down style (all in `LayoutSpec` already) |
| Custom crosshair (2b.8a) | Presets only; `crosshairX/Y` stay pack-only | A "Custom" entry nudging X and Y in 5% steps |
| The Layout panel while scaling (2b.8a) | Drawn at 100% UI scale, so it never moves under the cursor | Scales with the menu |
| Quick profile in an export (2b.8b) | Not baked in; it stays a setting and keeps applying over the new pack | Bake the fields it lists (E-Ink's still motion can't be expressed in a pack, so this is lossy) |
| Custom wallpaper in a current-look export (2b.8b) | Copied into the pack; sharing warns first | Left out, so the pack falls back to its own wallpaper |
| After exporting (2b.8b) | Offer Switch to <name>; only a current-look export also clears the user layer | Stay on the original pack; switch manually |
| Exported `author` (2b.8b) | The pack's for whole-pack and variant exports; empty for the current look | A name typed in the flow (never taken from the RA account) |
| User picks in a whole-pack export (2b.8b) | None: the pack exactly as it ships | Add the current look as an extra variant (variants patch only `palette` and `effects`, so components and layout would be lost) |

Settled with the owner (2026-11-15):

| Question | Decision |
|---|---|
| Export scopes | **Whole pack, one variant, or the current look**, for any installed pack; not only a flattened current look |
| The built-in Default pack | **Exportable and shareable like any other pack**, with the same licence note and no extra block. The owner reworks the Default pack after Theme 2.0 and turns today's Sony-like look into a community pack; the whole-pack export is how that pack is made |

---

## Carry-overs (not part of 2b.8)

- 2b.7e on the A55 with the user's account (real systems, an actual followed user).
- 2b.7a on the A55: Wi-Fi bars walking away from the router; an Android 9 image for the
  `WifiManager` path.
- The particle slowdown on the A55 during navigation (STATUS §3.28), still unexplained.
- `profile.avatarStyle` from a pack (A.12 lists it; the loader doesn't read it). Once it does, it
  joins `UserLook` and the export.
- The Default pack rework (Roadmap §4.2), after Theme 2.0 is finished: an original Default pack
  and an open-licensed typeface, with today's Sony-like look moved to a community pack. Its first
  step is a 2b.8c whole-pack share of the built-in Default pack, kept outside the APK.
- 2b.6 (haptics) is iced (Roadmap §4.8).
