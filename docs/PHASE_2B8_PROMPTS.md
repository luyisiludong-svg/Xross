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
| 2b.8-prep | ✅ on `refactor/2b8-prep` (2026-11-15): 872 unit tests (852 + 20), `lintDebug` 0 errors, `XmbViewModel` 2,647 → 2,331 lines; theme suite **311/312** on the API 37 emulator, the same as `main` (the one fail is 2b.4f's wallpaper toast missed by the screen dump; the wallpaper was saved, drawn and kept across a restart); `palette-perf.sh 3` 0.15–0.71% janky against 0.29–1.50% on `main` (inside the noise and the gate). As built: [2b.8-prep notes](#as-built-2b8-prep-2026-11-15) |
| 2b.8a | ✅ on `feat/theme-2b8` (2026-11-15): 886 unit tests (872 + 14), `lintDebug` 0 errors; theme suite **348/351** on the API 37 emulator, all 39 new checks passing (new 39-check "Layout mixer (2b.8a)" section); `display-matrix.sh` 12/12 with both extremes; `palette-perf.sh 3` 0.29–0.45% janky with a non-default layout against 0.44–0.75% without. **Open:** the A55 (a held D-pad on UI scale). As built: [2b.8a notes](#as-built-2b8a-2026-11-15) |
| 2b.8b | ✅ on `feat/theme-2b8` (2026-11-15): 912 unit tests (886 + 26), `lintDebug` 0 errors; theme suite **407/407** on the API 37 emulator (new 56-check "Export (2b.8b)" section: the built-in Default pack whole and as a current look, a pack with variants, overrides and rules in every scope, a picked wallpaper; each the same on screen after Switch to). **Open:** the A55 with a folder pack; the import allowlist vs. paths the loader reads (2b.8c). As built: [2b.8b notes](#as-built-2b8b-2026-11-15) |
| 2b.8c | ✅ on `feat/theme-2b8` (2026-11-15): 932 unit tests (912 + 20), `lintDebug` 0 errors; the A.13 gate (`ThemeRoundTripTest`) green for every scope of the Default and stress packs in 126 contexts; new 19-check "Share (2b.8c)" section of the theme suite 19/19 on the API 37 emulator (the whole suite, now 426 checks, not rerun). **Open:** the A55 (share to a cloud folder, re-import on the emulator); a full theme-suite run. As built: [2b.8c notes](#as-built-2b8c-2026-11-15) |

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

### As built (2b.8-prep, 2026-11-15)

| Piece | Where |
|---|---|
| `UserLook` (`of`, `writeTo`, `changes`, `NONE`, `isNone`) | `theme/pack/UserLook.kt` |
| `LookInputs.user` replaces `effects`, `surfaceStyle`, `circadian`, `batteryTint`, `adaptive`, `components` | `theme/ThemeLookResolver.kt`; built in `MainActivity` with `UserLook.of(settings)` |
| `ThemeDefaults.reset` = `UserLook.NONE.writeTo`, `isDefault` = `UserLook.of(s).isNone`; the Reset row's summary is `changes()` | `theme/pack/EffectOverrides.kt`, `MenuTree.resetThemeRow` |
| `ThemeController` (27 commands, `onWallpaperPicked`, `onThemeArchivePicked`, the pending import) with a `Host` (toast, fail, refuse, emit, showOptions, components, friendsWanted, photoUri) | `state/ThemeController.kt`; `XmbViewModel` keeps `theme` and forwards |
| `ThemePackInfo.sourceJson` (from `readManifest`), `LoadedTheme.sourceJson` / `files` | `theme/pack/ThemePackLoader.kt` |
| A shared stress pack (imports, 2 variants, category and system overrides, rules, every file kind, unused files) | `test/.../StressPack.kt` |

Device checks (2026-11-15, API 37 emulator with `-gpu host`):

| Check | `main` (`762d581`) | `refactor/2b8-prep` |
|---|---|---|
| Theme suite | 311/312 (at 2b.7e) | **311/312**: the same fail, "Xross wallpaper set" not on screen when the dump ran; the URI was saved, the picture drawn, kept after a restart and cleared by Reset |
| `palette-perf.sh 3`, janky (on / off pairs) | 1.49 / 1.50, 0.29 / 0.30, 0.44 / 1.19% | 0.60 / 0.71, 0.15 / 0.29, 0.15 / 0.29% |

Decisions made while building:

- **Forwarding keeps the `when` exhaustive:** `XmbViewModel.run` lists the 27 theme commands in one
  branch that calls `theme.onCommand(command)` under a `check`, rather than asking the controller
  first, so a new `Command` still has to be placed somewhere. `ThemeControllerTest` sends one of
  each and asserts the controller takes them all (and none of four others).
- **`sourceJson` lives on `ThemePackInfo`** (the loader builds it in `readManifest`, which both
  discovery and loading go through); `LoadedTheme.sourceJson` reads it from `info`. Discovered and
  loaded packs carry the same object. It decodes back to the pack's manifest.
- **`files` is what was read and is used**, pack-relative with `/`, against `info.source` (APK
  asset folder or pack folder): fonts, icons, logos, sounds, CUSTOM shaders and UI filters,
  wallpapers, loops, boot video, from every variant, override and rule. Not the JSON files
  (`sourceJson` has them), not what the Default pack lends a folder pack, not a missing or
  unusable file. A declared name replaces the convention one (`icons.music: my-music.svg` reads
  `icons/my-music.svg`, not `icons/music.svg`; `wallpaperPath` and `bgmFile` skip `wallpaper.*` and
  `bgm.*`). The built-in pack ships `icons/power.svg`, which no glyph reads, so it isn't listed:
  2b.8b's whole-pack export brings it through the `icons/` convention folder.
- **The `UserLookTest` table** fails when `UserLook` gains a field without a row. Kotlin can't
  enumerate a data class's fields without reflection, so it counts them with Java's
  `declaredFields` (no `kotlin-reflect`); the rows themselves are plain values.
- **Tests moved:** only two view-model tests sent theme commands (`ComponentsTest`'s slot edits,
  `ComponentsTwoBTest`'s title badges); both moved unchanged into `ThemeControllerTest`'s "Through
  the view model" region. Nine new tests drive the controller on its own with a fake host.
- **Left in `XmbViewModel`:** the `ThemeWatcher` (hot reload), the `components` flow the status bar
  reads, Wave settings and Profile customization. They aren't theme commands; 2b.8a's Layout panel
  will follow the Wave settings pattern, which stays there for now.

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

### As built (2b.8a, 2026-11-15)

| Piece | Where |
|---|---|
| `LayoutOverrides` (`preset`, `uiScale`, `itemSpacingDp`, `focusedScale`; `normalized`, `applyTo`, `count`) and `LenientLayoutOverrides` (drops a value of the wrong type before decoding) | `theme/pack/Layout.kt` |
| `display.layout` ↔ `XrossSettings.layoutOverrides` | `config/ConfigFiles.kt`, `data/SettingsStore.kt` |
| `UserLook.layout`: Reset, its summary ("Clears 2 layout values, 1 effect") and 2b.8b's export read it | `theme/pack/UserLook.kt` |
| Cascade: `profile.layout(user.layout.applyTo(look.layout ?: pack.layout))` | `theme/ThemeLookResolver.kt` |
| `LayoutEditor` (rows, steps, values, sliders, the row's summary), pure | `state/LayoutEditor.kt` |
| `Command.OpenLayoutSettings`, the panel's state (`layoutEditor`) and input in `ThemeController`; `XmbViewModel` forwards | `state/ThemeController.kt`, `state/XmbViewModel.kt` |
| Settings ▸ Theme ▸ **Layout** (after Components) | `state/MenuTree.kt` `layoutRow()` |
| `LayoutSettingsPanel` (100% UI scale, bottom-right, no fade); `animatedLayout` springs the crosshair, spacing and focused scale | `ui/LayoutSettingsPanel.kt`, `ui/XmbScreen.kt` |
| Device: "Layout mixer (2b.8a)" (39 checks) in the theme suite; `display-matrix.sh … layout-min` / `layout-max`; `PERF_LAYOUT` for `palette-perf.sh`; `layout-perf.sh` | `tools/device-tests/` |

Device checks (2026-11-15, API 37 emulator with `-gpu host`, 2400×1080):

| Check | Result |
|---|---|
| Theme suite | **348/351**: the new section 39/39 (the menu moved to each preset, grew at 110%, shrank at 50%, re-spaced for Spacious and Tight, stayed undimmed). The fails: 2b.4f's wallpaper toast missed by the dump (as on `main`), and two 2b.7a volume checks that ran with the emulator's music volume already at 0, so Volume Down changed nothing; by hand with the volume up, the step (`volume=2#2`) and the muted glyph (`volume=0#9`) both showed. The first run (342/351) found the tap-outside layer hiding the menu from the accessibility tree |
| `display-matrix.sh layout-min` (Modern widescreen, 50%) and `layout-max` (PSP centre, 150%) | **12/12 each**: 0 clipped texts, 0 status-bar collisions, a whole neighbour category on each side; janky 0–1.20% |
| `palette-perf.sh 3`, janky (on / off pairs) | pack's layout 0.75 / 0.44, 0.44 / 0.59, 0.59 / 0.44%; `PERF_LAYOUT` (PSP centre, 130%, 20 dp, 1.10×) 0.29 / 0.45, 0.30 / 0.44, 0.30 / 0.44% |
| `layout-perf.sh 3`: 21 presses each, janky | list 2.94, 2.27, 3.03%; UI scale 2.78, 1.17, 0.56%; crosshair + spacing 0.94, 2.44, 1.41% |

Decisions made while building:

- **The defaults above were kept:** the four rows, presets only (no custom crosshair), the panel
  at 100% UI scale.
- **"Theme pack" is each row's left end.** Right from it steps up from the pack's own value (100%
  → 110%), Left past the smallest value (50%, Tight, 1.00×, Classic PS3) goes back to it, and both
  ends hold, so a held key stops instead of wrapping. Confirm on a value row puts that row back to
  the pack; Confirm on Reset layout clears the panel. A hand-edited value off the grid (`1.23`)
  steps to the next one.
- **The UI scale snaps; the crosshair, spacing and focused scale spring** with the motion profile
  (a snap under INSTANT). Springing the UI scale too laid every text on screen out again at a new
  size each frame: 2.9–3.1% janky (13% on a cold first round) against 1.4–2.3% for the same presses
  on the list. Snapped, it is 0.6–2.8% against 2.2–3.1% over two runs. Any layout change springs this way,
  including a rule's (battery, clock) and a pack switch.
- **No tap-outside clickable layer.** A clickable node over the whole screen hides everything under
  it from the accessibility tree, the menu included; the panel closes on a plain tap gesture
  instead, and screen readers (and the suite) still read the menu behind it.
- **The menu's button hints hide while the panel is open** (it sits in their corner with its own),
  and so does the mini player. The panel reads the pack's layout for "Theme pack · …" from the
  pack's base, not the open category's rules.
- **A held key can't be measured on this emulator.** It isn't rooted (`sendevent` refused), the
  console's `event send` doesn't reach the virtio keyboard, and `input keyevent --duration` gives
  one or two repeats. `layout-perf.sh` sends presses back to back from one shell instead (one every
  ~150–250 ms) and measures the list with the same presses; both sit around the 1.5% gate because
  every `input` process costs frames, and the layout rows are no worse than the list. The real
  held-key gate is for the A55 with a gamepad.
- **Order of Reset's summary:** layout values come first ("Clears 2 layout values, 1 effect").
- **Branch:** `feat/theme-2b8` was cut from `refactor/2b8-prep` (not yet merged into `main`), so
  merging the prep branch first leaves only 2b.8a's commits on it.

Open: the A55 at 120 Hz (a held D-pad on UI scale through a gamepad, and Reset to theme defaults
clearing the layout).

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

### As built (2b.8b, 2026-11-15)

| Piece | Where |
|---|---|
| `ExportScope` (`WholePack`, `Variant(id)`, `CurrentLook`), `LookPicks`, `WaveValues`, `LookExport`, `ExportFile` / `ExportSource` | `theme/pack/LookExporter.kt` |
| `LookExporter.export` (pure): `sourceJson` → one `theme.json` and the files; `defaultName`, `LICENCE_NOTE` | `theme/pack/LookExporter.kt` |
| `ThemeExport.write`: `.export-<nanos>/` staging, rename to a free id, 50 MB cap, `ExportReader` for the bytes | `theme/pack/ThemeExport.kt` |
| `ThemePackLoader.packFiles` / `openPackFile` / `packFileSize`: a pack's files from its APK assets or folder | `theme/pack/ThemePackLoader.kt` |
| Flow: `Command.ExportTheme`, `ExportThemePack(id)`, `ChooseExportScope`, `ConfirmThemeExport`, `SwitchToExportedTheme`; `NamePurpose.ExportTheme`; the host's `openNameEntry` and `busy` | `state/ThemeController.kt`, `state/XmbModel.kt`, `state/NameEntry.kt`, `state/XmbViewModel.kt` |
| Settings ▸ Theme ▸ **Export theme…** (after Reset to theme defaults); the Theme pack row's Options ▸ **Export a pack…** (every installed pack) | `state/MenuTree.kt` |
| Tests: `LookExporterTest` (11), `ThemeExportTest` (9: writer, each scope loaded back and resolved equal in 84 contexts, A.1), `ThemeControllerTest` (+6) | `app/src/test` |
| Device: "Export (2b.8b)" (56 checks) in the theme suite | `tools/device-tests/test-theme.sh` |

Device checks (2026-11-15, API 37 emulator with `-gpu host`, 2400×1080):

| Check | Result |
|---|---|
| Theme suite | **407/407**, the new "Export (2b.8b)" section 56/56 (the first full run found the "Theme packs" section stepping onto the new last row of the Theme pack row's Options, and the emulator's low-battery heads-up at 20% and below; both fixed in the suite) |
| Whole pack of the built-in Default pack | written to `Xross/themes/default-pack` (7 sounds and 29 icons from the APK assets, `surfaces.json` merged, no staging folder left); after Switch to, each category's root, a SNES game focused and the battery rule: at most 1.08% of pixels differ (the breathing halo), the logged colours, effects and cosmetics equal |
| Current look of Default (Crimson, 3 effects, 2 components, 3 layout values, a surface) | Switch to sets the pack's colours and clears the user layer; 0% of pixels differ, logs equal |
| A pack with 2 variants, 2 category overrides, a SNES override and 2 rules | whole pack with Ember selected (keeps the variant, carries its wallpaper), Base look only, Ember only, Mono only, and the current look over Mono (Emerald, effects, layout, components, a surface; the user's frame taken out of the Settings override and the battery rule): at most 1.41% of pixels differ, logs equal |
| A picture picked as the Xross wallpaper | copied as `wallpaper.png`; Switch to lets go of the picker's grant; the same look (1.36%); with the picture deleted, the pack's copy still shows after a restart |

Decisions made while building:

- **The defaults above were kept:** no quick profile in an export, the custom wallpaper copied in,
  Switch to offered after every export, `author` empty for the current look, no user pick in a
  whole-pack export.
- **Flattening writes checked values, not raw JSON.** A variant's palette, effects, sounds and
  components go into the base from `ResolvedVariant` under the canonical key names (an alias such as
  `shader` is removed with its key, a bad colour never reaches the base). A pack whose base has no
  palette takes the variant's as the palette, not as a patch over the base's unused colours (the
  cascade does the same). User picks are written from `EffectOverrides.normalized()` and
  `LayoutOverrides.normalized()`, and the crosshair as `preset` (the base's `crosshairX/Y` removed).
- **Colours: what is on screen becomes the palette.** A colour theme other than the pack's (or a pack
  without colours) writes `canvas`, `accent` and `accentSecondary` and a two-stop `wave.gradient`, and
  takes those roles out of every patch; a pack without colours also loses its patches' other colour
  roles, which never showed. A wave tint is baked the same way (canvas, ribbon and gradient from the
  tint, the pack's accent kept, as `wavePalette` keeps the glow), and Switch to resets the tint to
  Theme colors. Original writes this month's colours ("Original's November colors" in the summary).
  One thing a pack can't say: under a tint, the icon filters' canvas colour is the tint's after the
  switch (it was the theme's).
- **Files by elimination.** A flattened export keeps `LoadedTheme.files` minus each file only a
  dropped layer named (another variant's wallpaper, a CUSTOM shader the user's shader replaced),
  found by resolving the shader, UI filter, wallpaper, music and logo references of the source and
  the output manifests. Fonts, icons, sounds and the boot video can't be patched, so they always
  stay. A whole pack adds every file the import allowlist knows (`icons/`, `sounds/`, `audio/`,
  `fonts/`, `shaders/`, `wallpapers/`, the root convention files, `.md` / `.txt` notes), never a
  dot-file, never the JSON (it is merged into the one `theme.json`).
- **`variants` is dropped** rather than written as `[]` in a flattened export (the same to the
  loader).
- **The confirm panel is an Options menu** ("Export as <name>": Export with the summary, then the
  licence note), like the import's Replace / Keep both; Back cancels. Names take 40 characters
  (`NameEntry.maxLength`; Apps folders keep 24).
- **Switch to doesn't go through the pack switch**, which resets the wave to the new pack's defaults
  and the variant to the base: a whole pack keeps the variant, the user layer and the wave values; a
  variant keeps the user layer; a current look clears it (and lets go of the photo's grant).
- **Device screenshots need the wave off and the battery at 28%:** with the wave on, two shots of an
  unchanged menu differ by 7–11% of their pixels (0–0.95% with it off, the breathing halo), and at
  20% and below the emulator's own "18% battery left" heads-up covers the menu, so the device pack's
  battery rule is `<= 30` and the shot is taken at 28%. Each comparison also checks the colours,
  effects and cosmetics the debug build logs.

Open for 2b.8c (done there: the allowlist was widened): the import allowlist (`ThemeArchive.disallowed`) refuses some paths the loader reads
and an export copies, such as a root `logo.svg` or an `art/` folder (the stress pack has both); the
share has to either widen the allowlist to what the loader reads or refuse those packs with a reason.
Open: the A55 with a folder pack and the user's own picks.

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

### As built (2b.8c, 2026-11-15)

| Piece | Where |
|---|---|
| `ThemeArchive.write(export, reader, out)` (a `LookExport` streamed into a zip: the Default pack from its APK assets, no folder first), `writeFolder(dir, out)` (a pack folder as it is, `imports` and all), `write(entries, out)` underneath; `ArchiveEntry`, `ArchiveWrite` | `theme/pack/ThemeArchive.kt` |
| The import allowlist widened to what the loader reads (`NAMED_TYPES`) | `ThemeArchive.disallowed` |
| Flow: `Command.ShareThemePack(id)`, `ShareExportedTheme`, `ChooseExportScope(…, share)`, `NamePurpose.ExportTheme(…, share)`, `UiEffect.CreateThemeArchive(fileName)`, `ThemeController.onThemeArchiveCreated` | `state/ThemeController.kt`, `state/XmbModel.kt`, `state/NameEntry.kt`, `state/XmbViewModel.kt`, `MainActivity.kt` |
| The Theme pack row's Options ▸ **Share as .xtheme…** (every installed pack); **Share as .xtheme…** in the "<name> saved" menu after an export | `state/MenuTree.kt`, `state/ThemeController.kt` |
| Tests: `ThemeArchiveWriteTest` (6), `ThemeRoundTripTest` (9: the A.13 gate, A.1 over the inflated entries), `ThemeControllerTest` (+4), `ThemeArchiveTest` (+1, one rewritten); `SameLook` shared with `ThemeExportTest` | `app/src/test` |
| Device: "Share (2b.8c)" (19 checks) in the theme suite; the "Theme packs" section steps past the new last row | `tools/device-tests/test-theme.sh` |

Device checks (2026-11-15, API 37 emulator with `-gpu host`, 2400×1080):

| Check | Result |
|---|---|
| "Share (2b.8c)" section run on its own | **19/19** |
| Whole pack of the built-in Default pack, shared to `Download/` from the Theme pack row | `default-pack.xtheme`, 37 entries (`theme.json` first, 7 sounds and 29 icons from the APK assets), no folder written; imported back as `default-pack`: at most 1.38% of pixels differ in the 8 places (the breathing halo), logs equal |
| Current look of Default (Crimson, 3 effects, 2 components, 3 layout values, a surface), exported, shared from the saved menu, its folder deleted, imported back | 0.06% of pixels differ, logs equal |

Decisions made while building:

- **The allowlist was widened, not the share refused.** The root and any folder of the pack's own
  (`art/`, `music/`, `parts/`) take every file type a manifest can name: images, audio, fonts,
  shaders (`.fs` too, which `shaders/` refused), `.agsl`, boot videos, `.json`, `.md` / `.txt`.
  The convention folders keep their own types, programs and hidden files are still refused, and the
  root `bgm.*` / `boot.mp4` caps stay. So `logo.svg`, `art/` and an imported `parts/rules.json` now
  import. Two side effects: a whole-pack export also copies data files nothing reads
  (`art/orphan.png`; the pack as it ships), and every `.json` is left out of it (merged).
- **Two writers:** the scope list from the Theme pack row exports straight into the zip; Share
  after an export zips the folder just written as it is. Both go through one entry check that uses
  the reader's own rules, so a written archive always stages. A file an import would refuse (a
  `.gif`, a `LICENSE`, a root music loop over 20 MB) is left out and named in the toast, not a
  refusal: the loader can't use any of them. Over 50 MB or 2,000 entries is refused, the largest
  files named; a refused document is deleted.
- **The document is typed `application/octet-stream`, not `application/zip`:** with the zip type
  the emulator's storage provider saved `default-pack.xtheme.zip`. The import picker already
  accepts octet-stream.
- **"Includes your wallpaper photo"** opens the Share row's detail on the confirm panel, and is the
  detail of Share as .xtheme… after a current-look export that copied one.
- **Archives are reproducible:** entries carry a fixed time, so the same pack zips to the same bytes.
- **Contexts of the gate:** each category and none × no system / `dreamcast` × battery 15%, 50%,
  charging × 08:00, 20:00 and 22:00 (inside the stress pack's 21:00..06:00 rule): 126. "Equal" is
  `ThemeLookResolver.resolve` and the wave palette (`paletteFor` + `wavePalette`); there is no class
  named `ColorPipeline`. A whole-pack export keeps the colour theme as a setting, so it is compared
  under the same colours on both sides.
- **The landscape keyboard can cover the picker's Save** on the emulator; the suite presses its
  Enter in that case.

Open: the A55 (share to a cloud folder, re-import on the emulator); a full theme-suite run with the
new section in place (only the section itself and the unit tests ran).

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
