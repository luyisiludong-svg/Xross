# Phase 2b.7 Implementation Prompts (Slices 2b.7-prep, 2b.7a – 2b.7e)

Phase 2b.7 (modular components) of the Xross launcher (`dev.xross.launcher`), from Roadmap §4.3
(the 2b.7 row and its architecture checks) and the contracts in Roadmap Appendix A.8, A.10, A.11
and A.13. Each slice is self-contained, unit- and device-tested, and leaves the launcher
shippable. Branches: `refactor/2b7-prep` from `main` once 2b.5 is merged, merged first; then
`feat/theme-2b7` from `main` for 2b.7a–e.

**Order note.** The Roadmap recommended 2b.7 before 2b.5; 2b.5 went first (see
[`PHASE_2B5_PROMPTS.md`](PHASE_2B5_PROMPTS.md)). Nothing here touches audio. The one neighbour is
the status-bar `VOLUME` slot (2b.7a), which shows the system media volume (not the 2b.5b mixer,
see Decisions) and never writes it. Merge 2b.5
into `main` before cutting either 2b.7 branch: the prep slice moves code out of `XmbViewModel` and
`XmbScreen`, which 2b.5 also changed.

**Why a prep slice.** Of the refactors in [`ARCHITECTURE_ASSESSMENT.md`](ARCHITECTURE_ASSESSMENT.md)
§8 that 2b.5 left for later, three sit directly under 2b.7: the RetroAchievements part of the
`XmbViewModel` split (#6; 2b.7c adds friend data, 2b.7d and 2b.7e consume it, and the view model is
2,823 lines), a state slice for the status bar (#9; 2b.7a turns one text run into up to six slots
that update on their own clocks, and each must not recompose the screen), and `GlyphKey` moving
down a layer (part of #2; 2b.7b's glyph override is read by `ui/` and `state/` alike). The rest of
§8 (package moves, the remaining controllers, the `XmbEvent` sink, singletons, imaging) still waits:
it doesn't block 2b.7.

---

## Progress

| Slice | State |
|---|---|
| 2b.7-prep | ✅ merged into `main` (`f67f532`): `RaController`, `StatusBarState` / `StatusBarSource`, `model/GlyphKey` and `GlyphSet` |
| 2b.7a | ✅ on `feat/theme-2b7` (2026-11-14): 793 unit tests, `lintDebug` 0 errors, theme suite **197/197** on the API 37 emulator (new 48-check "Components (2b.7a)" section), `display-matrix.sh` 12/12 default and stress, `palette-perf.sh` 0.15–0.75% janky ([STATUS §3.29](STATUS.md)). **A55** (2026-11-14): 48/48, volume keys and mute, a real "25 m to full", display matrix 24/24 ([STATUS §3.33](STATUS.md)). **Open:** Wi-Fi bars walking away from the router, an Android 9 image. As built: [2b.7a notes](#as-built-2b7a-2026-11-14) |
| 2b.7b | ✅ on `feat/theme-2b7` (2026-11-14): 805 unit tests, `lintDebug` 0 errors, theme suite **235/235** on the API 37 emulator (new 38-check "Components (2b.7b)" section), `palette-perf.sh` 0.15–0.74% janky with badges ([STATUS §3.30](STATUS.md)). **A55** (2026-11-14): 33/38, the 5 mini-player fails are the suite expecting its seeded songs (re-done by hand with the user's music), badges 0.12–0.13% janky ([STATUS §3.33](STATUS.md)). **Open:** a real pad, touch on the ticker, `RA_PROGRESS` with an account, `display-matrix.sh` with the ticker and badges. As built: [2b.7b notes](#as-built-2b7b-2026-11-14) |
| 2b.7c | ✅ on `feat/theme-2b7` (2026-11-14): friend API verified live ([RETROACHIEVEMENTS_API.md](RETROACHIEVEMENTS_API.md)), 825 unit tests, `lintDebug` 0 errors, theme suite **259/259** on the API 37 emulator (new 24-check "Components (2b.7c)" section) ([STATUS §3.31](STATUS.md)). **A55** (2026-11-14): 24/24, no refresh in 10 minutes at rest ([STATUS §3.33](STATUS.md)). **Open:** real followed users (the test account follows nobody). As built: [2b.7c notes](#as-built-2b7c-2026-11-14) |
| 2b.7d | ✅ on `feat/theme-2b7` (2026-11-14): 836 unit tests, `lintDebug` 0 errors, theme suite **281/281** on the API 37 emulator (new 22-check "Components (2b.7d)" section, the friend strip live with the `.env` account), `palette-perf.sh` card median 0.45% vs pill 0.30% janky over 11 pairs ([STATUS §3.32](STATUS.md)). **A55** (2026-11-14): 22/22, card median 0.31% vs pill 0.38% janky over 5 interleaved pairs at 120 Hz ([STATUS §3.33](STATUS.md)). **Open:** portrait, real followed users. As built: [2b.7d notes](#as-built-2b7d-2026-11-14) |
| 2b.7e | ⬜ next |

---

## What exists today (2026-11-13)

The table below is the starting point before the prep slice; what 2b.7a changed is in its
[as-built notes](#as-built-2b7a-2026-11-14).

| Piece | Where | State |
|---|---|---|
| Status bar | `ui/StatusBar.kt` | One `Text` run (date `Md` pattern, `DateFormat.getTimeFormat`, battery `NN%` in the `mono` font) and a three-bar `BatteryGauge` whose outline follows `ThemeAccent.halo`. Anchored `TopEnd`; keeps `StatusAvatarBox + 10 dp` free at the far right for `AvatarHud`; `contentAlpha` fades it while the HUD expands (read in the draw phase). Has its own `ACTION_BATTERY_CHANGED` receiver, separate from `state/BatteryMonitor.kt` (2b.3). Clock ticks once a minute |
| Avatar HUD | `AvatarHud` in `XmbScreen` | Drawn over the status bar's trailing space, same `TopEnd` padding; expands over the clock and battery |
| Top-left of the menu | `XmbScreen` | Nothing permanent (the back chevron and breadcrumb live by the category bar) |
| Mini player | `ui/MiniPlayerCard.kt` | Today's `FLOATING_CARD`: art, scrolling title, artist, play/pause glyph; hides after a pause (`MiniPlayerVisibility`); `miniCovered` hides it under 12 overlays; stacked above the telemetry badge in a `BottomEnd` column |
| Telemetry | `GameTelemetryBadge` in `ui/GameTile.kt`, `GameTelemetry` in `state/RaHub.kt` | Today's `COMPACT_PILL`: unlocked/total and a mastery state; only for recognized games with a set; hidden under the mini player's overlays and while the avatar is focused |
| Glyphs | `input/ControllerFamily.kt` | `PLAYSTATION`, `XBOX`, `NINTENDO`, `GENERIC`, `KEYBOARD`, each with `south` / `east` / `north` / `west` strings; detection (vendor id) and display are the same enum. Read as `state.family.*` in `XmbScreen`, `SearchOverlay`, `AvatarHud`, `AchievementsScreen` |
| Focused title | `ItemRow` in `XmbScreen` | Title and subtitle (playtime since 2b.1); no pills |
| Game metadata | `data/MetadataEntities.kt` | `releaseDate`, `developer`, `publisher`, `genre`, `players`, `rating` per game |
| Achievements | `ui/AchievementsScreen.kt`, `state/AchievementsView.kt` | Already a frosted sheet with art, title, system pill, earned/total and an amber bar; a 10-column `LazyVerticalGrid` sized for 2½ rows; a focused-badge `DetailBar` (title, description, points, mode, date) |
| RetroAchievements hub | `ui/RaHubScreen.kt`, `state/RaHub.kt` | Identity card, library completion gauge, mastery showcase, recent activity; L1/R1 (`PREV_CATEGORY` / `NEXT_CATEGORY`) scroll by 4 |
| RetroAchievements data | `profile/` | `ProfileRepository` (profile and recent unlocks, stale after 6 h), `AchievementsRepository` (per-game progress fresh for 10 min, console lists 7 days, files in `Xross/data/ra/`), `RetroAchievementsClient` (`API_GetUserProfile`, `GetUserSummary`, `GetUserRecentAchievements`, `GetGameInfoAndUserProgress`, `GetGameList`). `RaLibrarySummary` is library-wide, **not per system**. No friend data |
| Settings | `config/ConfigFiles.kt` `Display` | `display.effects` (`EffectOverrides`) is the 2b.4f/2b.5d user layer for pack effects; no `components` |
| Permissions | `AndroidManifest.xml` | `INTERNET`, `ACCESS_NETWORK_STATE`; no `ACCESS_WIFI_STATE` (2b.7a adds it) |
| System bars | `MainActivity` | Immersive: Android's status and navigation bars hidden, re-applied on focus. Stays that way (Decisions) |
| Tests | `app/src/test` | 757 unit tests at the end of 2b.5d |

---

## Overview

| Slice | Title | Scope | Risk |
|---|---|---|:---:|
| **2b.7-prep** | Groundwork | `RaController` out of `XmbViewModel` (behaviour unchanged); a status-bar state slice; `GlyphKey` and the settings value types moved down a layer | Low |
| **2b.7a** | Components block and status-bar clusters | `components` in the manifest and `display.components`; `statusBarLeft` / `statusBarRight` slot arrays (`LOGO`, `WIFI`, `VOLUME`, `BATTERY`, `CLOCK`, `AVATAR`); battery modes; clock pattern; Settings ▸ Theme ▸ Components | Medium |
| **2b.7b** | Mini player, glyphs, badge pills | `miniPlayerMode`, `glyphOverride`, `badgePills` beside the focused title | Low |
| **2b.7c** | Friend data and the social stack | Followed users and their latest game, fetched on refresh only and cached; unlinked and offline states; `topLeftCluster: SOCIAL_STACK` with a `9+` badge | High |
| **2b.7d** | Telemetry tiers | `telemetryMode`: `OFF`, `COMPACT_PILL` (today), `RICH_CARD` with recent badges and a `RECENTLY PLAYED` friend strip | Medium |
| **2b.7e** | RetroAchievements hub upgrade | Achievements header and 3-row grid, glossy detail banner; Games ▸ RetroAchievements gains a `FOLLOWING` sidebar and L1/R1 platform filter pills | Medium |

Order: prep → a → b → c → d → e. 2b.7a and 2b.7b can swap; 2b.7d and 2b.7e both need 2b.7c's
friend data, so 2b.7c is the critical path. If 2b.7c finds the Web API can't supply friend
activity, 2b.7d ships `RICH_CARD` without the friend strip and 2b.7e without the sidebar; neither
blocks on it.

Unit-test gates from Appendix A.13 (2b.7): the social stack and `RICH_CARD` collapse cleanly when
unlinked or offline; friend data is fetched on refresh only. Device gate from A.13 for the whole
phase: the HUD expands and collapses without layout jitter and gives the clock and battery back,
in every cluster order.

---

## Slice 2b.7-prep: Groundwork

```markdown
### Task: Prepare for Phase 2b.7 — RaController, status-bar slice, glyph types

#### Requirements
1. **`RaController` (second piece of Assessment #6, `state/`):** move the RetroAchievements and
   profile logic out of `XmbViewModel` (link and unlink, profile refresh and staleness, per-game
   progress and `focusedTelemetry`, the Achievements view, the RetroAchievements hub, unlock
   toasts) into a controller with its own `StateFlow` slice. `XmbViewModel` keeps one field and
   forwards actions to it, as it does for `AudioDirector`. **Behaviour unchanged**; 2b.7c adds the
   friend data here.
2. **Status-bar state slice (Assessment #9, for the status bar only):** what the bar shows (clock,
   battery, and from 2b.7a the network and volume) lives in one `@Immutable` `StatusBarState` read
   by `StatusBar` alone, so a minute tick or a battery change recomposes the bar and nothing else.
   Fold `StatusBar`'s own `ACTION_BATTERY_CHANGED` receiver into `BatteryMonitor` (2b.3), so the
   battery is read once.
3. **Glyph types down a layer (part of Assessment #2):** `GlyphKey` out of `state/XmbModel.kt` into
   a package `ui/` and `theme/pack/` can both read without importing `state/`; split
   `ControllerFamily` into detection (`ControllerFamily.fromVendor`, confirm layout) and a
   `GlyphSet` (the four face strings) that 2b.7b can choose independently. Every caller of
   `state.family.south/east/north/west` reads `state.glyphs.*` instead; same strings as today.

#### Tests
- `RaControllerTest`: the view-model RetroAchievements tests move here unchanged and pass.
- `StatusBarStateTest`: minute tick, battery change and charging produce the same text as today.
- `GlyphSet` for each family equals today's `ControllerFamily` strings.

#### Device checks (smoke + theme suites)
- `palette-perf.sh` before and after: janky frames unchanged within noise.
- Recomposition counts (Layout Inspector or a debug counter): a minute tick recomposes only the
  status bar.
- Link, refresh, open Achievements and the hub on the A55 with the user's account: same as `main`.
```

---

## Slice 2b.7a: Components block and status-bar clusters

```markdown
### Task: Implement Phase 2b.7a — components block, status-bar slots, battery and clock modes

#### Requirements
1. **`ThemeManifest.Components` (`theme/pack/`):** the `components` block of Appendix A.12 with
   every A.11 enum (`TopLeftClusterMode`, `TelemetryMode`, `MiniPlayerMode`, `BatteryDisplayMode`,
   `GlyphOverrideMode`) and the slot enum `StatusSlot { LOGO, WIFI, VOLUME, BATTERY, CLOCK, AVATAR }`.
   Keys: `topLeftCluster`, `statusBarLeft`, `statusBarRight`, `batteryMode`, `clockFormat`,
   `telemetryMode`, `miniPlayerMode`, `glyphOverride`, `badgePills`, `logo`, `volumeAlways`. Unknown keys and bad
   values are reported like `effects` (`Components.KEYS`, `schemaProblems`); a bad value falls back
   on its own. Only this slice's keys are drawn; the rest are read and stored for 2b.7b–d.
2. **Cascade:** Default pack → theme pack base → variant → user `settings.json`, nothing else.
   Category and system overrides and `rules.json` don't patch `components` (a `components` key in
   one is ignored and reported). Resolved in `ThemeLookResolver` into `ResolvedComponents`.
3. **User layer:** `display.components` in `settings.json` (`ComponentOverrides`, every field
   nullable = the pack's), cleared by Reset to theme defaults. Quick profiles don't touch it.
4. **Slots:** `statusBarLeft` draws at the top-left, `statusBarRight` at the top-right, in array
   order, each slot its own composable reading `StatusBarState`:
   - `LOGO`: the pack's `components.logo` (an SVG or PNG in the pack, through the same path checks
     as icons), sized to the status text's cap height; absent → slot skipped.
   - `WIFI`: Wi-Fi, cellular, ethernet or offline glyph from `ConnectivityManager` network
     callbacks. Wi-Fi strength in 4 levels: `NetworkCapabilities.signalStrength` on API 29+;
     on API 28 `WifiManager.getConnectionInfo().rssi` through `WifiManager.calculateSignalLevel`,
     re-read on `RSSI_CHANGED_ACTION`. Adds `ACCESS_WIFI_STATE` to the manifest (a normal,
     install-time permission). Never the SSID; no location permission.
   - `VOLUME`: the **system media volume** (`AudioManager.STREAM_MUSIC`), not the 2b.5b mixer, in
     4 levels, with a muted glyph at 0; observed through a `ContentObserver` on `Settings.System`.
     Shown for 3 s after it changes, or always (`components.volumeAlways`; default off).
   - `BATTERY`: `CAPSULE_ICON` (today's gauge), `PERCENTAGE` (today's `NN%`), `BOTH` (today's
     default), `TIME_REMAINING`: while charging, `BatteryManager.computeChargeTimeRemaining()`
     (API 28+) as "1 h 20 m to full"; while discharging, or when it returns −1 (unknown), the
     percentage, exactly as `PERCENTAGE` draws it. No drain estimate.
   - `CLOCK`: date and time as today; `clockFormat` is a `DateFormat` skeleton limited to the
     fields `y M d E H h m a` (anything else is dropped and reported); empty = today's.
   - `AVATAR`: the 2b.1 HUD. **Always the rightmost slot of `statusBarRight`:** listed elsewhere
     it is moved there and reported; left out, the avatar isn't drawn and the HUD can't be opened
     from the bar (it still opens from Settings ▸ Accounts).
   Defaults (the Default pack): left `[]`, right `[CLOCK, BATTERY, AVATAR]`, `BOTH`, which draws
   exactly today's bar.
5. **HUD fade in every order:** `contentAlpha` fades every right-cluster slot left of the avatar;
   the left cluster never fades. The HUD's expansion stays draw-phase only.
6. **Layout:** the left cluster and 2b.7c's social stack share the top-left; slots clip before they
   overlap the category bar. Re-run `display-matrix.sh` (12 configurations) with the Default pack
   and a stress pack that fills both clusters.
7. **Settings ▸ Theme ▸ Components** (a new folder after Intro and transitions): Status bar left,
   Status bar right (an Options list to add, remove and reorder slots; `AVATAR` locked last),
   Battery display, Clock format (presets: Theme pack, 24 h, 12 h, date and time, time only).
   2b.7b–e add their rows here.

#### Tests
- `ComponentsTest`: decode with aliases and bad values; unknown keys reported; cascade order
  (Default pack, theme pack, variant, user); `components` in a category override, a system
  override or a rule ignored and reported; `AVATAR` moved to the end; duplicate slots dropped.
- `StatusSlotsTest`: clock skeleton filtering; battery text per mode; `TIME_REMAINING` charging,
  charging with −1, and discharging (equals `PERCENTAGE`); Wi-Fi level mapping from RSSI (API 28)
  and from `signalStrength` (API 29+); volume levels and mute at 0.
- MenuTree: the Components folder, slot reordering, Reset to theme defaults.

#### Device checks (theme suite, new "Components (2b.7a)" section)
- Each battery mode and three slot orders: screenshots, and the HUD expands and collapses with the
  clock and battery fading and returning (A.13).
- Airplane mode on and off: `WIFI` changes within 2 s. `TIME_REMAINING` on and off the charger
  (the emulator's `adb emu power ac on|off`).
- `display-matrix.sh` 12/12 with no clipped texts; `palette-perf.sh` unchanged within noise; a
  minute tick at rest draws one frame.
- On the A55 at 120 Hz: the same, plus volume keys update `VOLUME` (and mute at 0), and Wi-Fi
  bars follow walking away from the router. An Android 9 device or emulator image for the
  `WifiManager` path, if one is available.
```

### As built (2b.7a, 2026-11-14)

Where things live:

| Piece | Where |
|---|---|
| Enums, the `components` block, its schema, `ComponentsPatch` (one layer), `ResolvedComponents` (settled, with `needs`), `ComponentOverrides` (`display.components`), `ComponentRow` (one-value Settings rows), `ClockFormat` | `theme/pack/Components.kt` |
| Pack layers | `LoadedTheme.components` (Default pack over the pack's base), `ResolvedVariant.components`, `LoadedTheme.components(variant, user)`; logos in `LoadedTheme.logos` |
| Resolution | `ThemeLookResolver` → `ResolvedLook.components`, provided as `LocalComponents` (MainActivity); the same cascade as `XmbViewModel.components` for the bar's sources and the avatar's place in navigation |
| Bar state | `state/StatusBarState.kt` (`StatusBarState`, `StatusBarSource` with `StatusNeeds`, `StatusLevels`), `state/StatusMonitors.kt` (`NetworkMonitor`, `MediaVolumeMonitor`) |
| Drawing | `ui/StatusBar.kt`: one `Layout` with the left and right clusters, a composable per slot |
| Settings | `state/ComponentMenus.kt` (the slot list and its edits), `MenuTree` folder `DISPLAY_COMPONENTS`, commands `SetStatusSlots`, `EditStatusSlot`, `CycleComponent`, `SetComponent`, `ShowOptions` |

Decisions made while building (the spec above is unchanged otherwise):

- **Clock skeletons:** the date letters (`y M d E`) go through the locale's best pattern, then two
  spaces (the gap the bar always had), then the time: `H` 24 h, `h` 12 h, and `m` without either
  follows the phone's 12/24 h setting. Skeletons are formatted with ICU
  (`android.text.format.DateFormat` prints `y` as two digits). Presets: 24 h `MdHm`, 12 h `Mdhma`,
  Date and time `EMMMdm`, Time only `m`.
- **`VOLUME`** counts every change of the volume index, not only a change of its 4 levels (14 → 12
  of 15 is level 3 both times), and listens to `android.media.VOLUME_CHANGED_ACTION` as well as the
  `Settings.System` observer: Android persists the volume up to a second after the key, the
  broadcast arrives within milliseconds.
- **`WIFI`:** `RSSI_CHANGED_ACTION` is read on every API (its `EXTRA_NEW_RSSI` beats an older
  reading); on 29+ `signalStrength`, then the connection's RSSI, mapped with the AOSP
  `calculateSignalLevel` formula. `ACCESS_WIFI_STATE` comes with `uses-feature
  android.hardware.wifi required=false`, so Ethernet-only TVs aren't filtered out.
- **Aliases** (case, `-` and spaces ignored): see the `components` reference in
  [THEME_SPECIFICATION.md §15](THEME_SPECIFICATION.md#15-components).
- **Logos:** SVG logos are read like icons (white, black fills dropped) and now keep their
  `viewBox` aspect (`SvgIcon` used to make every vector 48 × 48 dp; the Default icons are all
  square, so they are unchanged). PNG and WebP logos keep their colors.
- **Slot editor:** Confirm on Status bar left / right opens the Options list (Theme pack, each
  slot with Move left / Move right / Move to the other side / Remove, Add). After an edit the list
  reopens on that slot, with its moves open after a left or right move. Edits write only the
  side(s) they change; the other keeps following the pack.
- **Avatar left out:** Up at the top of a list stays in the list (the view model checks
  `components.avatar`); a toast says the User Card is in Settings ▸ Accounts.
- **Accessibility and logs:** the glyph slots are described ("Wi-Fi, 3 of 3 bars", "Mobile data",
  "Offline", "Volume 2 of 3", "Volume muted", "Battery gauge, 2 of 3 bars", "Theme logo"); debug and
  profiling builds log one `XrossStatus` line per change of what the slots show (never per minute).

Device-test lessons (in `test-theme.sh` and `display-matrix.sh` now):

- Only the focused row shows its subtitle: move onto a row before expecting its summary.
- Compose leaves alpha-0 nodes out of the accessibility tree, so while the HUD is open the right
  cluster reads as empty; the check compares the clock's and battery's bounds before and after.
- With Android's volume panel up, uiautomator doesn't list a slot that just appeared, so the
  `VOLUME` checks read `XrossStatus`. On the emulator a volume key while the panel is hidden only
  raises the panel, keys sent milliseconds apart both count as that, and
  `cmd media_session volume --set` doesn't move the speaker volume: press the keys one at a time.
- A resting-frames window that crosses a minute boundary sees the clock's one frame; the suite's
  `rest_frames` waits past the boundary first.
- `harness.sh`'s `top()` climbs onto the avatar and back down: with the avatar left out it lands
  on the second row, so go to the top with `press DPAD_UP 25` instead.
- The emulator's `dumpsys battery set ac 1` charger gives no charge-time estimate (−1), so only
  the percentage fallback of `TIME_REMAINING` was seen on a device.

### Notes for 2b.7b–e (from 2b.7a)

- Every `components` key is already decoded, cascaded, saved under `display.components` and
  carried in `ResolvedComponents` (`miniPlayer`, `glyphs`, `pills`, `telemetry`, `topLeft`); a
  slice only has to draw it and add its Settings row. One-value rows extend `ComponentRow` and
  `ComponentOverrides.get` / `with`; `badgePills` (multi-select) needs its own Options list, like
  the slot editor.
- 2b.7b's `STATUS_TICKER` belongs in the right cluster before the avatar, inside the row that
  `contentAlpha` fades (`ResolvedComponents.rightOfText`).
- 2b.7c's social stack goes after the left slots in the same clipped row of `StatusBar`'s
  `Layout`; it gets the width the right cluster leaves, and `leftMaxHeight` keeps it above the
  category bar.
- A new watched source belongs in `StatusNeeds` so it only runs while something shows it.

---

## Slice 2b.7b: Mini player modes, glyph override, badge pills

```markdown
### Task: Implement Phase 2b.7b — mini player modes, glyph style, badge pills

#### Requirements
1. **`miniPlayerMode`:** `FLOATING_CARD` (today's, the default); `STATUS_TICKER` (one line in the
   status bar's right cluster, left of the avatar: play/pause glyph, "Title — Artist" scrolling in
   a fixed width, same hide-after-pause rule, faded with the HUD); `OFF` (no card or ticker;
   playback and the notification unchanged). Tap and long-press keep today's actions in the ticker.
2. **`glyphOverride`:** `AUTO` (the detected family, as today), `PLAYSTATION` (✕ ○ △ □), `XBOX`
   (A B X Y, each in its Xbox color), `NINTENDO` (A B X Y in Nintendo positions, so south is B),
   `MONOCHROME_MINIMAL` (outlined shapes, no letters). Display only: the confirm layout still
   follows the detected controller and the user's Confirm setting.
3. **`badgePills`** beside the focused title, in array order, each skipped when its data is
   missing: `SYSTEM` (the system's short tag, e.g. DC, GC), `YEAR` (from `releaseDate`),
   `DEVELOPER` (ellipsized at 18 characters), `PLAYTIME` (moves the 2b.1 subtitle into a pill when
   listed), `RA_PROGRESS` ("🏆 18/42", only for recognized games). Pills use the pack's surface at a
   small radius and the `mono` font for figures; they fade in with the title, only on the focused
   game, and never on apps or media. Default pack: `[]` (today's look).
4. Settings ▸ Theme ▸ Components rows: Mini player, Button glyphs, Title badges (multi-select).

#### Tests
- Glyph resolution: every override × every detected family; confirm layout unaffected.
- Pill selection: missing data skips a pill; year parsing from partial dates; `RA_PROGRESS` only
  with telemetry.
- Mini-player mode: ticker visibility follows `MiniPlayerVisibility`; `OFF` hides both.

#### Device checks (theme + media suites)
- Each mini-player mode with a song playing and paused; the ticker fades under the HUD.
- Each glyph override with the A55's touch input and a connected pad if available: hints,
  Search, Achievements and the HUD all show the chosen set.
- Pills on a scraped game, an unscraped one and an app; Games navigation janky frames unchanged
  (`palette-perf.sh`).
```

### As built (2b.7b, 2026-11-14)

| Piece | Where |
|---|---|
| Glyph sets and styles (`Face`, `GlyphStyle`, `XBOX_COLORED`, `MINIMAL`) | `model/GlyphSet.kt` |
| `glyphOverride` × detected family | `state/Glyphs.kt` (`GlyphOverrideMode.glyphsFor`); `XmbViewModel.state` combines the components' mode, so `state.glyphs` follows it |
| Drawing a styled label | `ui/XmbScreen.kt` `Hint` reads `LocalGlyphs` (provided in MainActivity): a face label of an Xbox set takes its color, of the minimal set an outlined pill. The 43 hint call sites are unchanged |
| Mini-player placement | `state/MiniPlayerLine.kt` (`spot`, `shows`, `tickerText`); the ticker is `MiniPlayerTicker` in `ui/MiniPlayerCard.kt`, drawn by `StatusBar` at the end of the faded right row |
| Badge pills | `state/BadgePills.kt` (`TitlePill`, year parsing, developer cut, playtime); `MenuTree.gameNode` fills `XmbNode.pills`; `ItemColumn` hands the focused row its pills with `RA_PROGRESS` filled from `focusedTelemetry`; `SubtitleLine` draws them |
| Settings | `ComponentRow.MINI_PLAYER` / `GLYPHS`; Title badges is an Options list (`ComponentMenus.pillOptions`, `togglePill`, `reopenPills`; commands `SetBadgePills`, `ToggleBadgePill`) |

Decisions made while building:

- **`MONOCHROME_MINIMAL`** is an outlined triangle pointing at the button's place on the pad
  (south ▽, east ▷, north △, west ◁) in an outlined pill: shapes, no letters, and plain text, so a
  glyph quoted in a sentence ("press ▽ to try again") reads the same.
- **`XBOX`** colors the letter (A green, B red, X blue, Y yellow) on the usual pill; `AUTO` with an
  Xbox pad stays plain, as before.
- **Ticker:** 10 status-text heights wide (about 20 characters), the text at 85% of the status
  size, after the right cluster's text slots and before the avatar. It brings its own gap, so the
  bar is unchanged while nothing plays; it hides under the same overlays as the card.
- **Pills** sit on the line under the title, before the subtitle, never taller than that line, so
  focusing a game with badges doesn't move its title. They are composed for the focused row only
  and fade in with it. Listing `SYSTEM` also takes the platform tag (and an extension that only
  repeats it) out of the subtitle, like `PLAYTIME` takes the playtime; `PLAYTIME` draws nothing
  before the first minute ("Never played" leaves with the subtitle part).
- **Title badges list:** Theme pack, None, then each badge; Confirm turns one on (at the end) or
  off and the list reopens on it, so the order is the order they were turned on.

---

## Slice 2b.7c: Friend data and the social stack

**Verify first (decided 2026-11-14).** Before writing UI, confirm against the live Web API with
the user's key, and record the answers in a new `docs/RETROACHIEVEMENTS_API.md`: the
`API_GetUsersIFollow.php` payload (paging, and what each entry carries: avatar, points, whether
it follows back), what gives a friend's latest game and when (`API_GetUserSummary` /
`API_GetUserProfile`: last game id, rich presence, last activity), and any documented or observed
rate limit. Adjust requirement 2's caps to what the limits allow. The unread badge is tracked
locally either way (requirement 3).

```markdown
### Task: Implement Phase 2b.7c — followed users, friend activity, social stack

#### Requirements
1. **Client (`profile/RetroAchievementsClient.kt`):** `fetchFollowing(user, key)` (all pages,
   capped at 200 users) and `fetchFriendActivity(user, key, friend)`; parsers for each, skipping
   malformed entries; the same size caps and media-host checks as the existing calls.
2. **`FriendsRepository` (`profile/`):** the following list cached in `Xross/data/ra/friends.json`
   for 6 h (the profile's staleness); activity for at most 20 friends (most recently active first
   from the last refresh), fetched sequentially with ≥ 250 ms between calls, cached 30 min each.
   **Fetched on refresh only:** the hub's Primary action, the profile's own refresh and opening
   the social stack when stale. Never on hover, focus or a timer. Avatars stored like the user's
   own (`ProfileRepository.avatarFile`). Nothing written outside `Xross/data/ra/` and the avatar
   folder; no credentials in any file (A.1).
3. **State (`RaController`):** `FriendsState` = `Unlinked`, `Offline` (no network or the last fetch
   failed, with the cached list if any), `Loading`, `Ready(friends, newActivity)`. `newActivity`
   counts friends whose latest game or last activity changed since the stack was last opened
   (stored as a map in `friends.json`, with the time the stack was last opened); opening the stack
   clears it.
4. **`topLeftCluster: SOCIAL_STACK`:** up to 4 overlapping round avatars (most recent activity
   first) after the left status slots, with a badge `1`–`9`, then `9+`; tap it, or focus it the
   same way the avatar is focused, to open a small panel listing friends
   with their latest game and how long ago. **Collapses** (draws nothing, takes no room) when
   unlinked, offline with no cache, or following nobody. `EMPTY` (default) draws nothing.
5. Settings ▸ Theme ▸ Components ▸ Top-left (Theme pack / Social stack / Empty).

#### Tests
- Parsers for both endpoints, including empty, paginated and malformed answers.
- `FriendsRepositoryTest`: cache freshness, the 20-friend cap, call spacing, and **no fetch** on
  focus, hover or composition (A.13), driven by a fake clock and a counting fake client.
- `newActivity` across refreshes and after opening the stack; the `9+` cap.
- Collapse: unlinked, offline without cache, offline with cache (shows cached), following nobody
  (A.13).
- No credential in `friends.json` (the A.1 assertion extended).

#### Device checks (on the A55 with the user's account; theme suite for the collapse cases)
- Social stack with the user's followed users; refresh logs `XrossRa friends=N activity=M calls=K`.
- Airplane mode: the stack shows the cache; with the cache deleted, it collapses. Unlink: collapses.
- At rest with the stack shown: no network calls for 10 min (`XrossRa` log silent).
```

### As built (2b.7c, 2026-11-14)

The verify step's answers are in [RETROACHIEVEMENTS_API.md](RETROACHIEVEMENTS_API.md): the
following list carries name, ULID, points and follow-back but **no avatar or activity**; a friend's
latest game and when come from `API_GetUserSummary` with `g=1&a=1` (`RecentlyPlayed[0].LastPlayed`
and `RichPresenceMsgDate`; `LastActivity` is dead); the limit is a burst of about 7 calls that
refills at about 3 a second (429, no `Retry-After`). The project's test account follows nobody.

| Piece | Where |
|---|---|
| `RaFollowed`, `RaFriendActivity`, `fetchFollowing`, `fetchFriendActivity`, parsers; `RaResult.Failed.busy` on a 429 | `profile/RetroAchievementsClient.kt` |
| `FriendsRepository` (`Xross/data/ra/friends.json`, avatars in `media/avatars/friends/`), `FriendsState`, `Friend` | `profile/FriendsRepository.kt` |
| `RaState.friends` / `socialPanel`, refresh triggers, the panel's input | `state/RaController.kt`; pure rules (`shows`, panel input, "how long ago") in `state/SocialStack.kt` |
| Drawing | `ui/SocialStack.kt` (`SocialStackSlot` in `StatusBar`'s left row, `SocialPanel` over the menu) |
| Settings | `ComponentRow.TOP_LEFT`, the **last** row of Components (so 2b.7a–b's rows keep their places) |

Decisions made while building:

- **`fetchFollowing(apiKey)`**, no user: the endpoint answers for the key's account. One call with
  `c=200`; it keeps paging past a short page while `Total` says there are more (up to 4 pages).
- **Caps adjusted to the measured limit:** 350 ms between call starts (not 250: the passing run was
  really ~330 ms apart); on a 429, one retry after 2 s, then the refresh ends. Any other failure
  ends it at once (offline, 20 failing calls would say nothing new).
- **Which 20:** the 15 most recently active, then the others least recently fetched first, so a list
  longer than 20 is covered over a few refreshes instead of never.
- **Refresh triggers** (A.13: never focus, hover, composition or a timer): the hub's Primary action
  (always), the profile's own refresh (only while something shows friends: `SOCIAL_STACK` now,
  `RICH_CARD` and the hub sidebar later), opening the stack when stale (list > 6 h or no activity
  in 30 min), turning Top-left to Social stack, and at startup only when nothing was ever fetched
  (else a stack with no cache would collapse and could never be opened to fetch).
- **`newActivity`:** per friend, game id and last activity at the time the stack was opened; a
  friend it never showed counts if they were active after that. The first refresh of an account
  records the current state (nothing is news), like unlock toasts.
- **States:** at startup the cache is `Ready` (no fetch failed yet); a failed refresh is `Offline`
  with the saved list; `Loading` keeps the list it had. The stack collapses when unlinked, or the
  list is empty (offline without a cache, following nobody).
- **Focus:** Left from the avatar's HUD crosses to the stack (Left from the HUD moved category
  before, and still does without a stack); Up at the top opens it only when the bar has no avatar.
  In the panel Up/Down move, Right goes back to the avatar, L1/R1 move category, Back or a tap
  outside closes. Opening clears the badge; the panel still dots who was new.
- **Logs:** debug and profiling builds log `XrossRa friends=N activity=M calls=K` (`failed` when it
  ended early) once per refresh.

Device-test lessons (in `test-theme.sh` now):

- Debug builds link the developer's `.env` account at every start (`AppContainer`,
  `DEBUG_RETROACHIEVEMENTS_*`), so a cleared app is never unlinked there. The suite seeds its own
  account (`SmokeUser`, a fake key, run in airplane mode) by writing the credentials file with
  `run-as`, and checks "unlinked" only on builds without those secrets; with them, it checks the
  live refresh instead.
- Settings writes are read back through `components`, a derived flow that lags the save by a
  moment: code acting on a just-saved component must settle it from `settingsStore.current`.
- `wm size` reports the natural (portrait) size; the UI dump's root bounds follow the rotation.
- Never edit a suite while it runs: bash reads the script as it goes.

### Notes for 2b.7d–e (from 2b.7c)

- `RaState.friends` / `XmbUiState.friends` carry everything: `Friend.activity.gameId` is the
  RetroAchievements game id for 2b.7d's `RECENTLY PLAYED` strip; `gameIconUrl` for the card.
- Add `RICH_CARD` (2b.7d) and the hub sidebar (2b.7e) to `RaController.friendsWanted()`, so the
  profile's refresh fetches friends for them too.
- Only the 20 fetched friends have activity (and avatars); the rest of a long list are names and
  points. The sidebar sorts by points, so it can list everyone; the latest game shows for those 20.

---

## Slice 2b.7d: Telemetry tiers

```markdown
### Task: Implement Phase 2b.7d — telemetry OFF, COMPACT_PILL, RICH_CARD

#### Requirements
1. **`telemetryMode`:** `OFF` (no badge); `COMPACT_PILL` (today's `GameTelemetryBadge`, the
   default); `RICH_CARD`: a card in the pack's surface with the game's square icon, a platform
   pill, the six most recent unlocks' badges for this game (locked slots greyed when fewer), a
   progress bar with "18/42 · 120/400 pts", and a `RECENTLY PLAYED` strip of up to 3 friend
   avatars who last played this game, then `+N`.
2. Same visibility rules as today (recognized games only, hidden under overlays and the avatar
   HUD), same place above the hints and below the mini player; the mini player's
   `STATUS_TICKER` / `OFF` modes give the card the corner.
3. **Collapse (A.13):** unlinked or no progress → nothing, as today. Offline → the card from cached
   progress without the friend strip. No friend on this game → no strip. Never fetches: it reads
   `AchievementsRepository.cached` / `peek` and `FriendsState`.
4. The card's art and badges load off the main thread through the existing loaders; switching games
   crossfades, and fast scrolling never queues more than one load.
5. Settings ▸ Theme ▸ Components ▸ Achievement badge (Theme pack / Off / Compact / Rich card).

#### Tests
- Card content from progress: fewer than six unlocks, mastered, zero points.
- Friend strip: matching by RetroAchievements game id, the `+N` count, empty.
- Collapse: unlinked, offline, no progress, no friends; no fetch on focus (counting fake).

#### Device checks (theme suite + the A55)
- Each mode on a recognized game, an unrecognized one and an app.
- Airplane mode: card without the strip.
- Games navigation with `RICH_CARD` on the profiling build: janky frames within the 0–1.5% gate and
  not worse than `COMPACT_PILL` by more than noise (`palette-perf.sh`, ≥ 3 interleaved rounds; see
  the A55 GPU note in STATUS).
```

### As built (2b.7d, 2026-11-14)

| Piece | Where |
|---|---|
| `GameTelemetry` gains `uuid`, `raGameId`, `platform`, `slots` and `offline` | `state/RaHub.kt` (`GameTelemetry.of(…, platform, peeked, uuid)`) |
| Card rules: the badge row, "18/42 · 120/400 pts", the friend strip, `shows` | `state/TelemetryCard.kt` (`TelemetryCard`, `TelemetrySlot`, `FriendStrip`) |
| Telemetry loading (platform tag, offline flag), `RICH_CARD` in `friendsWanted()` | `state/RaController.kt` |
| Drawing: `GameTelemetryCorner` (off / pill / card), the card, its badges and strip | `ui/TelemetryCard.kt`; the pill stays `GameTelemetryBadge` in `ui/GameTile.kt` |
| Settings | `ComponentRow.TELEMETRY` ("Achievement badge"), the **last** Components row |

Decisions made while building:

- **"Never fetches"** means the card adds no request: it shows the telemetry the focus already
  loads (the saved copy 250 ms after the cursor rests, a fresh one 450 ms later, as the pill
  always did) and `FriendsState` as it stands. Friends are never fetched on focus; the rich card
  joins the social stack in `friendsWanted()`, so turning it on fetches them once when none are
  saved, and the profile's refresh keeps them current.
- **Offline** is either the friend refresh failing (`FriendsState.Offline`) or this game's fetch
  failing (the progress falls back to the saved copy, `GameTelemetry.offline`). The saved copy
  read before the fetch isn't offline yet, so the strip doesn't blink while the fetch runs.
  `FriendsState.Loading` keeps the strip it had.
- **Badge row:** the six newest unlocks; with fewer, the next locked ones in the game's order fill
  the row, greyed (saturation 0, 38% alpha); a set with fewer than six leaves empty cells, so the
  card keeps its width.
- **Icon:** the library's box art (box, grid, 3D box, screenshot) cropped square, like the
  Achievements header; the trophy without art. The platform pill is the library's short tag
  ("SNES"), RetroAchievements' console name when there is none.
- **Strip:** friends whose `activity.gameId` is this game, most recently active first, three
  avatars then "+N". Only the 20 fetched friends have activity, so only they can appear.
- **Switching games:** the card keeps its game for 700 ms after the focus leaves it, dimmed to
  55%, then crossfades to the next game's (`AnimatedContent` keyed by game) or fades out. Art and
  badge loads are `produceState`s of the shown card, so leaving a game cancels them; telemetry
  itself only arrives once the cursor rests, so scrolling never queues loads.
- **Accessibility and the suite:** the card reads "RetroAchievements: 2/8 · 10/40 pts" (",
  mastered"), the strip "Recently played by Alice, Bob, Carol and 1 more".
- **Switching games, as measured:** the first version crossfaded with `AnimatedContent` and a
  size transform and cost 2–3 janky frames a pass over the pill; a plain `Crossfade` keyed by game
  (the last two games' telemetry kept for the outgoing card) brought it to about one.

Device-test lessons (in `test-theme.sh` and `palette-perf.sh` now):

- A recognized game needs no real ROM: `matches.json` (the game's uuid, size and modified time
  from the database, `listVersion` 2) and `game-<id>.json` make `peek` and an offline `load` use
  the saved copy; badges are read from `Xross/media/ra/badges/<name>.png`.
- Inside a folder Up stops at the first row and never reaches the avatar: open the HUD with a tap
  on the avatar (content-desc is the user name).
- For the strip the game's fetch must succeed, so it needs the debug build's `.env` account
  online; its `friends.json` must be owned by that user and fresh (`followingFetchedAtMs` and each
  activity's `fetchedAtMs` now), or the profile's refresh replaces it with the real list.
- `palette-perf.sh` with `PERF_TELEMETRY=1` seeds progress for its 12 games and alternates the
  card and the pill; keys 450 ms apart reach the saved copy (250 ms) but never the fetch (700 ms).

### Notes for 2b.7e (from 2b.7d)

- Add the hub's sidebar to `RaController.friendsWanted()` (the rich card is there now).
- `TelemetryCard.friendStrip` shows how to match friends to a game by `activity.gameId`; the
  sidebar's Confirm on a friend needs the reverse (a RetroAchievements id to a library game),
  which `matches.json` holds per uuid (not exposed by `AchievementsRepository` yet).

---

## Slice 2b.7e: RetroAchievements hub upgrade

```markdown
### Task: Implement Phase 2b.7e — achievements grid and banner, FOLLOWING sidebar, platform filter

#### Requirements
1. **Options ▸ Achievements:** a "39/151 🏆" header (unlocked/total, points beside it); the grid
   sized for exactly three rows of badges on every display-matrix configuration (columns from the
   width, not fixed at 10), scrolling by row; the `DetailBar` becomes a glossy banner in the pack's
   surface (title, description, points, hardcore or softcore, unlock date or "Locked").
2. **Games ▸ RetroAchievements ▸ `FOLLOWING`:** a sidebar on the right listing followed users by
   points (avatar, name, points, latest game), the user's own row highlighted in place. Right moves
   focus into it, Left back; Confirm on a friend opens their latest game in the library if the user
   owns it. Collapses (the dashboard takes the width) when `FriendsState` isn't `Ready` and has no
   cache.
3. **L1/R1 platform filter:** pills under the header for All and each system with tracked games;
   L1/R1 move between them, clamped at the ends, and the completion gauge, mastery showcase and
   activity show that system only. L1/R1 no longer scroll by 4; Up/Down still scroll. Needs a per-system
   `RaLibrarySummary` (`librarySummary` grouped by console), computed from cached progress only.
4. Settings: none new; both follow the pack's surface.

#### Tests
- `AchievementsView`: grid columns per width, three rows, focus and scroll at the edges.
- `RaHub`: L1/R1 move the filter, clamped at All and the last system, and never scroll; Right/Left move between the
  dashboard and the sidebar; Confirm on a friend.
- Per-system summary from fixture progress; All equals today's summary.
- Sidebar collapse when unlinked or offline without cache (A.13).

#### Device checks (theme suite + the A55)
- Achievements on a game with > 100 achievements and one with 6: three rows on the A55 portrait and
  landscape and in the display matrix.
- Hub with the user's account: filter each system, the sidebar's order, offline collapse.
```

---

## Decisions (confirmed 2026-11-14)

| Question | Decision |
|---|---|
| `TIME_REMAINING` battery mode (2b.7a) | **Time to full while charging only**, from `computeChargeTimeRemaining()` (API 28+); while discharging, or when it reports −1, the plain percentage. No drain estimate: Android has no discharge-time API |
| Wi-Fi strength on Android 9 (2b.7a) | **Add `ACCESS_WIFI_STATE`** (normal, install-time) and read `WifiManager` RSSI through `calculateSignalLevel` on API 28; `NetworkCapabilities.signalStrength` on API 29+. Both give 4 levels |
| What `VOLUME` shows (2b.7a) | **System media volume** (`AudioManager.STREAM_MUSIC`), with a muted glyph at 0; not the 2b.5b mixer, which stays in Settings ▸ Sound |
| L1/R1 in Games ▸ RetroAchievements (2b.7e) | **Platform filter pills, clamped at the ends** (like Details paging in 2b.1); scrolling stays on Up/Down |
| `components` cascade (2b.7a) | **Default pack, theme pack, variants and the user's `settings.json` only.** Category and system overrides and `rules.json` don't patch it; a `components` key there is ignored and reported. A status bar that rearranges itself per category or battery level reads as a bug |
| RetroAchievements friend data (2b.7c) | **Confirm `API_GetUsersIFollow`'s payload and the rate limits first**, then build. The unread badge is local: friends with new activity since the stack was last opened |
| Android system bars | **Dropped from 2b.7; Xross stays immersive.** A.10 covers Xross's own status bar only. STATUS and the Roadmap no longer list Android status-bar modes |

Defaults chosen in this doc (change before the slice starts if needed):

| Question | Default |
|---|---|
| `statusBarLeft` and `SOCIAL_STACK` both top-left (2b.7a, c) | Left slots first, then the stack, in one row; both clip before the category bar |
| Avatar left out of `statusBarRight` (2b.7a) | Allowed: no avatar in the bar; the HUD stays reachable from Settings ▸ Accounts |
| How many friends get activity calls (2b.7c) | 20 per refresh, most recently active first, ≥ 250 ms apart, adjusted to the rate limits found in the verify step |

---

## Carry-overs (not part of 2b.7)

- 2b.5d on the A55: cold-start time with and without a boot video, and the video at 120 Hz.
- 2b.6 (haptics) can run before or after 2b.7; it touches input, not components.
- The particle slowdown on the A55 during navigation (STATUS §3.28): 2b.7d's `RICH_CARD` adds
  per-focus work (about one janky frame a pass on the emulator). Measured on the A55 on
  2026-11-14 (5 interleaved pairs): card median 0.31% vs pill 0.38%; one card pass in the slow
  GPU mode, no missed deadlines (STATUS §3.33). The slow mode itself is still unexplained.
- `PHASE_2B3_PENDING_TESTS.md` items still open (idle CPU at rest 16–21% vs < 10%); the status
  bar's new slots must not add wakeups at rest beyond the minute tick and real changes. Measured
  in 2b.7a on the emulator: 3-second windows at rest draw 0 frames, a minute tick 1; idle CPU on
  the A55 not re-measured.
- 2b.7a on the A55: Wi-Fi bars walking away from the router and an Android 9 image for the
  `WifiManager` path (volume keys, mute and a real `TIME_REMAINING` passed on 2026-11-14, §3.33).
