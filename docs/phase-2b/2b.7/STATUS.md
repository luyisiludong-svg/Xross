# Phase 2b.7 — evidence

Modular components: status-bar clusters, mini player modes, glyphs, the social stack, telemetry tiers and the RetroAchievements hub. Plan and slices: [`PROMPTS.md`](PROMPTS.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.29"></a>

## 3.29 Phase 2b.7a: components block and status-bar clusters (2026-11-14)

On `feat/theme-2b7`, API 37 emulator (`-gpu host`, 2400×1080).

**Host:** 793 unit tests pass (4 skipped): `ComponentsTest` (decoding with aliases and bad values,
unknown keys, the cascade, `components` in overrides and rules ignored and reported, `AVATAR` moved
last, duplicates, the logo, `display.components`, Reset, the Components rows, slot edits through
the view model and the avatar gating Up) and `StatusSlotsTest` (clock skeletons, battery text per
mode, time to full with −1 and discharging, Wi-Fi levels from RSSI, volume levels and mute, which
monitors run for which slots). `lintDebug` 0 errors, 61 warnings (as before; `ACCESS_WIFI_STATE`
comes with `uses-feature android.hardware.wifi required=false`, so Ethernet-only TVs still qualify).

**Theme suite: 197/197** (the 149 before plus a new 48-check "Components (2b.7a)" section): each
battery mode, on and off the charger; the four clock presets; three slot orders, the HUD fading the
right cluster and giving it back with the clock's and battery's bounds unchanged; airplane mode
on (network slot offline in **0.17–0.26 s**) and off; the volume keys showing `VOLUME` for 3 s and
the muted glyph at 0; the avatar removed (Up at the top stays in the list) and Reset; **a minute
tick at rest draws 1 frame**, and 3-second windows away from the minute draw 0.

**Display matrix:** 12/12 with the Default pack and with a stress pack that fills both clusters (a
5:1 logo, network, volume always shown, a "Sat, Nov 14, 2026  13:11" clock, battery, avatar): no
clipped texts, no left-cluster glyph past the right cluster; at 640×480 the left cluster clips its
last slot. **`palette-perf.sh`** (profiling, 3 interleaved pairs): 0.15–0.75% janky (deadline);
the build before read 0.30–0.45%; the 0.75% is the first, cold pass.

**Found and fixed on the device:** `VOLUME` counted level changes, not volume changes, so a step
inside one level (14 → 12 of 15) didn't show it; it now counts every change and also listens to
the volume-changed broadcast, since Android writes the volume to `Settings.System` up to a second
after the key (now 5 ms). SVG logos were squashed to 48 × 48 dp; `SvgIcon` now keeps the
`viewBox` aspect (square icons unchanged). The 2b.4f "at rest under Bloom" check failed once when
its 3-second window crossed a minute boundary (the clock's frame, already there before 2b.7); the
suite's rest windows now avoid the boundary.

**Not covered:** the Galaxy A55 (volume keys, Wi-Fi strength walking away from the router, 120 Hz),
an Android 9 image for the `WifiManager` RSSI path, a real charge-time estimate (the emulator's
`dumpsys battery` charger gives −1, so only the percentage fallback was seen on a device).

<a id="s3.30"></a>

## 3.30 Phase 2b.7b: mini player modes, button glyphs, title badges (2026-11-14)

On `feat/theme-2b7`, API 37 emulator (`-gpu host`, 2400×1080).

**Host:** 805 unit tests pass (4 skipped): the new `ComponentsTwoBTest` (every `glyphOverride`
over every detected family; the labels change and the confirm side doesn't, through the view
model; year parsing from full and partial dates; missing data skipping a badge; the developer cut
at 18 characters; `RA_PROGRESS` only with telemetry; game rows carrying their badges, with
`SYSTEM` and `PLAYTIME` leaving the subtitle; no badges on settings, music or apps; card, ticker
and off; the ticker following the card's visibility and pause timeout; the Mini player and Button
glyphs rows; Title badges toggled in order through the view model and cleared by Reset), and
`ComponentsTest` with the three new rows. `lintDebug` 0 errors, 61 warnings (as before).

**Theme suite: 235/235** (the 197 before plus a new 38-check "Components (2b.7b)" section): a
song from the media fixtures as the floating card, then as the ticker ("Smoke Song 2 — Smoke
Artist" after the battery, before the avatar), faded under the avatar HUD and back in place,
paused with its pause glyph, then Off with the music still playing; each glyph set in the menu's
hints (Enter, ✕ ○ △, A B Y in color, B on the south for Nintendo, ▽ ▷ △), in Search (△ Space) and
in the avatar HUD; Title badges turned on in order, Chrono Trigger (a release date, a developer
and 75 minutes patched into the database) showing SNES, 1995, Square and 1h 15m with the
playtime gone from its subtitle, badges only on the focused game, an unscraped game keeping SNES,
none on an app; each row back to Theme pack.

**`palette-perf.sh`** (profiling, 3 interleaved pairs, new `PERF_COMPONENTS`): the Default
components 0.29–0.74% janky (deadline), with System, Release year, Developer and Playtime badges
0.15–0.74%: within the gate and the noise. The perf seed's games carry no release date or
playtime, so that run drew the System badge alone.

**Found and fixed on the device:** nothing in the app; the suite's glyph reader first took the
Search keyboard's own Space key for the hint's.

**Not covered:** the A55 (a real pad's glyph sets, touch on the ticker, 120 Hz), the
`RA_PROGRESS` badge on a device (needs a linked account), the ticker's 30-second hide after a
pause on a device (unit-tested on virtual time), `display-matrix.sh` with badges and the ticker.

<a id="s3.31"></a>

## 3.31 Phase 2b.7c: friend data and the social stack (2026-11-14)

On `feat/theme-2b7`, API 37 emulator (`-gpu host`, 2400×1080).

**Verify step (live, the project's test account):** `API_GetUsersIFollow` (no user parameter, `c`
≤ 500) carries name, ULID, points and follow-back, no avatar or activity; a friend's latest game
and when come from `API_GetUserSummary` with `g=1&a=1`; the limit is a burst of about 7 calls
refilling at about 3 a second, answered with a bare 429. Details and the measurements in
[../../reference/RETROACHIEVEMENTS_API.md](../../reference/RETROACHIEVEMENTS_API.md). The account follows nobody.

**Host:** 825 unit tests pass (4 skipped): the new `FriendsTest` (both parsers with empty,
paginated and malformed answers; one call for up to 200, paging past a short page; activity by
ULID and a 429 flagged busy; cache freshness at 30 min and 6 h; the 20-friend cap with call starts
≥ 350 ms apart and the rotation through a longer list; no call from loading, reading state,
staleness checks or opening; new activity across refreshes and after opening, the `9+` cap and
across sessions; offline stopping at the first failure with the cache kept; one retry after a
429; following nobody; another account's cache ignored and unlink clearing the file and avatars;
no key in any file; the collapse rules; the panel's input; "how long ago"; the Top-left row; the
controller's refresh triggers, Left from the HUD and taps), and `ComponentsTest` with the new row.
`lintDebug` 0 errors, 61 warnings (as before).

**Theme suite: 259/259** (the 235 before plus a new 24-check "Components (2b.7c)" section): the
Top-left row saved as `display.components.topLeftCluster`; turning it on with the debug build's
`.env` account refreshed it **live** (`friends=0 activity=0 calls=1`); in airplane mode with a
seeded account, profile and `friends.json`: the cached friends and a 2 badge at the top-left with
no refresh at startup, Left from the HUD opening FOLLOWING in activity order with each latest
game, the stale stack's one failed refresh keeping the saved list ("Offline · saved list"), Back
and a tap outside closing it, a tap opening it, the badge cleared and kept across a restart, no
key in `friends.json`; collapsed offline without a cache (after one startup try), following
nobody (and no refresh in 15 s at rest), and with another account's cache.

**Found and fixed on the device:** turning the stack on didn't fetch (the view model read the
components before its flow caught up with the saved setting). Suite fixes: debug builds link the
`.env` account at every start, so "unlinked" can't be set up on them; the outside tap used
`wm size`, which doesn't follow the rotation.

**From the first look (2026-11-14):** the PlayStation shapes were uneven (the font draws ○ small,
△ and ✕ off center): every shape label (✕ ○ △ □ ▽ ▷ ◁) is now drawn as strokes of one size,
centered, still read as its character by accessibility and the suite. The `WIFI` fan's outer arc
reached past the top of its slot and was clipped: the arcs now fit the slot. Profile customization
▸ Glow (which already turned the breathing glow off) now also turns off the halo on a focused
avatar, the social stack's front avatar included.

**Not covered:** a stack with real followed users (the test account follows nobody), the A55,
`palette-perf.sh` with the stack (it adds no per-frame work; not measured), the 10-minute
silent-at-rest check (15 s in the suite).

<a id="s3.32"></a>

## 3.32 Phase 2b.7d: telemetry tiers (2026-11-14)

On `feat/theme-2b7`, API 37 emulator (`-gpu host`, 2400×1080).

**Host:** 836 unit tests pass (4 skipped): the new `TelemetryCardTest` (the badge row with fewer
than six unlocks, many unlocks, a mastered set of two worth no points, large counts; the platform
fallback; the strip matched by RetroAchievements game id, newest first, `+N`, kept while loading,
none for another game or an unknown id; collapse when unlinked, unrecognized, failed or without
achievements; offline from the friend refresh or the game's own fetch, and the saved copy before
the fetch not counted as offline; the Achievement badge row and its subtitles), a controller test
in `FriendsTest` (the rich card fetches friends once without a cache; focusing games, startup with
a cache and the compact pill fetch none; a stale list is fetched when asked), and `ComponentsTest`
with the new row. `lintDebug` 0 errors.

**Theme suite: 281/281** (the 259 before plus a new 22-check "Components (2b.7d)" section): the
row cycling Off, Compact and Rich card into `display.components.telemetryMode`; with a seeded
saved copy for Chrono Trigger (`matches.json`, `game-228.json`, badges) and the fake account in
airplane mode: nothing with Off, today's pill with Compact, the card "2/8 · 10/40 pts" with its
SNES pill and no strip (the fetch failed), no friend refresh on focus, gone on EarthBound
(unrecognized) and back on Chrono Trigger, covered by the avatar's HUD (opened by a tap) and back,
none on apps. With the debug build's `.env` account online, the live fetch (game 228, 89
achievements) and four saved friends on it drew "Recently played by Alice, Bob, Carol and 1
more" with `+1`, no friend call; in airplane mode the card again without the strip.

**Performance (`PERF_TELEMETRY=1 palette-perf.sh`, new):** the 12 test games get saved progress
and badges, the passes run in airplane mode and alternate the rich card and the pill. With the
first version (`AnimatedContent` with a size transform) the card was 0.59–0.71% janky against
0.29–0.30% in all three rounds; the shipped one is a plain `Crossfade` (every card has the same
size). Over 11 interleaved pairs: card median 0.45% (0.30–1.37%, one pass at 4.22% that didn't
recur), pill median 0.30% (0.15–1.16%), about one frame per pass; both went up together while the
emulator ran slow (p50 23 ms). No GC or Davey lines from Xross in the passes logged.

**Not covered:** the A55 (120 Hz, the gate with the interleaved rounds there), portrait (the
emulator ignored `wm size` in this run; `display-matrix.sh` doesn't focus a recognized game),
real followed users (the strip used seeded friends).

<a id="s3.33"></a>

## 3.33 Phase 2b.7a–d on the Galaxy A55 (2026-11-14)

All of 2b.7a–d on the user's Galaxy A55 (Android 17 / API 37, 1080×2340 at 120 Hz, 450 dpi), on
`feat/theme-2b7` at `7666a9b`. The phone's ROMs, app data, `Xross/config`, `Xross/data` and the
RetroAchievements badges and avatars were backed up first and restored after (checksums matched);
the RetroArch stub was installed for the suites and removed; the profiling build was put back.

**Host:** **836 unit tests** pass (4 skipped), `lintDebug` **0 errors** (61 warnings).

**Device suites (`run-all.sh`, debug build): 380/398.** Library 41/41, input 11/11, integration
9/9, theme **275/281**, media 44/56. Every check in Components 2b.7a (48/48), 2b.7c (24/24) and
2b.7d (22/22) passed; 2b.7b 33/38. The 18 failures:

- **12 media checks** and **5 of 2b.7b's mini-player checks** expect the seeded `XrossSmoke` songs
  and photos; on the phone "Shuffle all" plays the user's own library (the suite's "a song plays"
  passed, but with "Need Somebody — XUITCASECITY"). Re-done by hand with the user's song: the
  floating card draws art, title, artist and the play glyph; the ticker draws "Title — Artist"
  after the battery (x 1685 vs 1425) and before the avatar, fades under the HUD and comes back at
  the same bounds, and keeps the `❚❚` glyph when paused.
- **"Effects volume cycles"** (2b.5b) was a broken check: it looked for "100%" anywhere on screen,
  which on the emulator is the battery. The row steps 75% → 80% as designed; the check now reads
  `sound.effectsVolume` from `settings.json`. It had passed on the emulator by accident.

**On the phone, beyond the emulator:**

| Check | Result |
|---|---|
| `TIME_REMAINING` on a charger | A real `computeChargeTimeRemaining()` estimate: "25 m to full" (the emulator only ever gave −1) |
| `VOLUME` | The phone's volume keys: a step showed `volume=1#2`, hid again after 3 s, and 0 drew the muted glyph |
| `WIFI` | Wi-Fi 3 of 3 bars at the start; airplane mode on: offline in **0.9 s**; off: back within the check, as cellular (Wi-Fi reconnects later) |
| HUD in every order | Default, battery first and with a left cluster: the clock and battery fade and come back at the same bounds |
| Minute tick at rest | 1 frame |
| Social stack at rest | `SOCIAL_STACK` on with the `.env` account, then **10 min untouched: no `XrossRa` line** (no refresh); only the once-a-minute `XrossStatus` battery lines while charging. Xross's uid moved about 2 KB each way in that window with no request logged (likely an idle connection closing) |

**Frame pacing (profiling build, 120 Hz):**

- `PERF_TELEMETRY=1 palette-perf.sh 5` (rich card and pill interleaved, airplane mode): card
  **0.19–0.38% janky (median 0.31%)**, pill **0.19–0.43% (median 0.38%)**: inside the 0–1.5% gate
  and no worse than the pill. The first card pass ran in the A55's slow GPU mode (p50 19 ms, GPU
  p50 9 ms, legacy 91%) with no missed deadlines; the other nine p50 12–13 ms.
- Badge pills (`SYSTEM`, `YEAR`, `DEVELOPER`, `PLAYTIME`), `palette-perf.sh 3`: **0.12–0.13%** in
  all 6 passes, the same as 2b.4's 0.12–0.19%.

**Display matrix on the phone (`display-matrix.sh`, default and stress):** 24/24, **no clipped
texts and no left-cluster glyph past the right cluster** in any configuration; 0–0.93% janky,
p99 11–27 ms. With the stress pack at 640×480 the wide logo and Wi-Fi clip before the clock and
the always-on volume glyph is clipped away, as designed. The portrait row stays landscape on the
phone (the app is `sensorLandscape`). The phone's size and density were reset after.

**Not covered:** real followed users (the `.env` account follows nobody, so the stack and strip
used seeded friends), Wi-Fi bars walking away from the router, an Android 9 image for the
`WifiManager` path, a physical controller for the glyph sets.

<a id="s3.34"></a>

## 3.34 Phase 2b.7e: RetroAchievements hub upgrade (2026-11-15)

On `feat/theme-2b7`, API 37 emulator (`-gpu host`, 2400×1080).

**Host:** 852 unit tests pass (4 skipped). New `HubUpgradeTest`: grid columns from the width with
cells fitting exactly three rows (landscape, wide, portrait at the 4-column floor, the 16-column
cap); scrolling by row at the top, bottom and last rows, L1/R1 pages, a tap, new columns keeping
the focused badge in view; the banner's status; the per-system summary from fixture progress (All
equals today's summary, untracked games still counted as supported, two dumps of one set once, the
newest dump owning the set); L1/R1 clamped at All and the last system and never scrolling; activity
per system; the filter kept across a re-read; the sidebar's order with the user in place, its
collapse (unlinked, offline without a cache, following nobody), Right/Left between dashboard and
sidebar, Confirm; a friend's game by matched set and by title (not a covered system, not a title
matched to another set); `MenuTree.pathToGame` with and without the All Games folder. In
`RaControllerTest`: the sidebar collapsed while unlinked, the grid's columns carried to the next
game. `lintDebug` 0 errors.

**Theme suite: 311/312** (the 281 before plus a new 31-check "RetroAchievements hub (2b.7e)"
section), offline with the fake account, Chrono Trigger (SNES) with 120 saved achievements (30
unlocked), Golden Sun (GBA) 6 of 6, and three saved friends: the header "30 / 120" with "150/600
pts"; three full rows of 16 badges; the banner "Hub 228-27, Smoke achievement 27, 5 points,
HARDCORE, Nov 28, 2026"; no scroll within the three rows, one row when crossing the bottom; "Locked"
on a locked badge; Golden Sun's six badges in one row; **three rows, none clipped, on all 12
display-matrix configurations** (7 to 16 across). The hub: FOLLOWING listing Alice, Erin, SmokeUser
(the user), Bob by points; All "180 of 630 points", L1 clamped, R1 to SNES (150 of 600) and GBA (30
of 30, Golden Sun in the showcase), R1 clamped; Right into the sidebar; Confirm on Erin's game
"Sonic Spinball isn't in your library"; Left back; Confirm on Alice closed the hub on Chrono
Trigger in the SNES list; no friend call; with the cache deleted the sidebar and its hint left. The
one failure was in 2b.4f: "a picked picture becomes the Xross wallpaper" missed the "Xross wallpaper
set" toast, while the row under it already read "A picture you chose" (a timing miss, not this
slice). Integration 9/9 and library 41/41 (the dashboard and the Achievements screen unlinked).

**Not covered:** the A55 with the user's account (real systems and badges, a followed user: the
test account follows nobody); real portrait (Xross is `sensorLandscape`, so the matrix's portrait
size draws landscape).
