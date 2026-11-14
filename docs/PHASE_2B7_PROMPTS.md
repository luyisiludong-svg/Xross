# Phase 2b.7 Implementation Prompts (Slices 2b.7-prep, 2b.7a – 2b.7e)

Phase 2b.7 (modular components) of the Xross launcher (`dev.xross.launcher`), from Roadmap §4.3
(the 2b.7 row and its architecture checks) and the contracts in Roadmap Appendix A.8, A.10, A.11
and A.13. Each slice is self-contained, unit- and device-tested, and leaves the launcher
shippable. Branches: `refactor/2b7-prep` from `main` once 2b.5 is merged, merged first; then
`feat/theme-2b7` from `main` for 2b.7a–e.

**Order note.** The Roadmap recommended 2b.7 before 2b.5; 2b.5 went first (see
[`PHASE_2B5_PROMPTS.md`](PHASE_2B5_PROMPTS.md)). Nothing here touches audio. The one neighbour is
the status-bar `VOLUME` slot (2b.7a), which reads the 2b.5b mixer but never writes it. Merge 2b.5
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

## What exists today (2026-11-13)

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
  per-focus work, so measure it with the same interleaved rounds.
- `PHASE_2B3_PENDING_TESTS.md` items still open (idle CPU at rest 16–21% vs < 10%); the status
  bar's new slots must not add wakeups at rest beyond the minute tick and real changes.
