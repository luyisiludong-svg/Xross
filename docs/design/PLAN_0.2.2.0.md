# 0.2.2.0 — implementation plan

The two features moved from `PLAN_0.2.1.0.md` (items 5 and 7), with 5 widened into on-device
system and emulator setup and 7 widened into a Metadata & Media editor, plus a bulk library
browser and theme music choices. **Shipped as 0.2.2.0-beta on 2026-11-21.** Checked against
`main` at `02df328` (2026-11-21). Everything is
built (see each "As built" note) and unit-tested; § 1–§ 5b were checked on the API 37 emulator with
stub apps, and the upgrade from 0.2.1.0-beta, Fix media… and the Settings rows on the A55. Still
open: launches with real emulators, the § 2 picker checks, frame timing and idle CPU (release
checklist).

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.

| # | Feature | Size | Status |
| --- | --- | --- | --- |
| 1 | Custom launch recipes and custom systems | M–L | ✅ Built, unit-tested, emulator-checked with stubs |
| 1b | Add a system or emulator on the device; PC Engine CD folders; more emulators | M + XS | ✅ Wizards and PCE-CD built and emulator-checked; no new built-in emulators (none read from a real install) |
| 2 | Metadata & Media editor for one game | L + S | 🟡 Built and unit-tested; device check on the A55 still to do |
| 3 | Library browser: fix media across a whole system | M | ✅ Built, unit-tested, emulator-checked |
| 4 | "Missing art" filter on game lists | S | ✅ Built, unit-tested, emulator-checked |
| 5 | Theme music: choose it in Settings, shuffle several tracks | S–M | ✅ Built, unit-tested, emulator-checked (by log, not by ear) |
| 5b | Wallpaper rotation | S–M | ✅ Built, unit-tested, emulator-checked; idle CPU not measured |
| 6 | GameNative PC shortcuts | — | ✅ Spike done: [`GAMENATIVE_SPIKE.md`](GAMENATIVE_SPIKE.md); building it is 0.2.3.0 |

**Order:** 2 first (the biggest, and 3 and 4 build on its media status), then 1 and 1b, then 5,
then 4, then 3 and 5b. 6 can run any time as a half-day spike.
If the release has to shrink, drop in this order: 3, 5b, 4. 1, 1b and 2 are the release.

**Moved to 0.2.3.0:** flat or grouped lists in every category and a sort order per category (see
the end of this file).

---

## 1. Custom launch recipes and custom systems (M–L)

Unchanged from `PLAN_0.2.1.0.md` § 5: `Xross/config/emulators.json` in ES-DE's command syntax,
user-defined systems in `systems.json`, `EsdeCommand.kt` parser, merged `EmulatorRegistry` and
`PlatformRegistry`, per-entry problems listed in Settings ▸ Emulators, package **and** activity
match against spoofed packages. Read that section for the design, tags and tests.

**As built (2026-11-21).**
- `ConfigFiles`: `EmulatorsFile` / `CustomEmulator`; `SystemsFile.System` gains `name`,
  `shortName`, `folders`, `extensions`, `emulators`, `logo`, `source`. The JSON reader allows
  comments and trailing commas. `emulators.json` is read before `systems.json`, so a built-in
  system may choose a custom emulator.
- Hand-written text is kept by `config/JsonSplice.kt`: `emulators.json` is not rewritten while
  its entries are unchanged, and when only device entries change they are spliced in as text
  (checked by decoding the result; otherwise the file is written whole). `systems.json` is still
  regenerated on save, as before (its `builtIn` blocks always were).
- `launch/EsdeCommand.kt` (pure parser and resolver) and `launch/RecipeIntents.kt` (the Intent).
  Extra tags beyond the plan: `%ACTIVITY_NO_HISTORY%`, `%FILENAME%`, `%ROMPATH%`. A URI only in an
  extra is granted through clip data. A recipe that hands over a plain path is flagged as needing
  the emulator's own storage access.
- `EmulatorRegistry.builtIn` plus installed custom defs (`EmulatorRegistry.install`, called by
  `SettingsStore` through a hook so `data` doesn't depend on `launch`); `data/CustomSystems.kt`
  builds the merged platform list (`PlatformRegistry.install`). An unknown system in an emulator's
  `systems` is a warning, not a reason to skip it. `AppFolders.emulatorPackages` uses built-ins
  only. `<queries>` already had the intent-based entries; no `QUERY_ALL_PACKAGES`.
- Commented `emulators.example.json` and `systems.example.json` are written to `Xross/config/`
  when missing (from `assets/config/`).

## 1b. Add a system or emulator on the device (M + XS)

**Goal.** Everything § 1 allows, without editing JSON: point Xross at a folder, say what is in
it, and pick the app that runs it. Xross writes the result to `systems.json` and
`emulators.json`, so the files stay the single source and can still be edited by hand.

**Add a system** (Settings ▸ Games ▸ Systems ▸ Add system…, and Options on an unknown folder in
the ROM root ▸ Make a system from this folder):
1. Folder: a folder under the ROM root, or any folder through the same picker the custom Photo and
   Video folders use.
2. Name and short name, typed on the gamepad keyboard; file extensions, prefilled from what the
   folder holds.
3. Emulator: an installed app from the list below, or an existing recipe.
4. Optional: a console logo (an image file) for the system folder.

**Add an emulator** (Settings ▸ Emulators ▸ Add emulator…):
1. Pick an installed app. The list shows launchable apps, known emulator packages first.
2. Pick its activity: the launcher activity by default, or any exported activity that accepts
   `VIEW` (from `PackageManager.queryIntentActivities`).
3. How to hand over the game: a short list of presets that write the `command` string for you
   (file URI with a read grant; file path as data; file path as an extra with a name you type), or
   type an ES-DE `command` line.
4. Which systems it runs.
5. **Try it** launches the first game of the first system with the recipe before it is saved.

Entries made here are marked `"source": "device"` in the JSON. Editing or deleting one from
Settings never touches hand-written entries.

**PC Engine CD folders (XS).** `pcengine` already scans `.cue` and `.chd`, but ES-DE's
`pcenginecd` and `tg-cd` folder names aren't among its folder names. Add them, plus
`turbografxcd` and `supercd`, to the `pcengine` folder set in `Platform.kt`. A test in the
existing folder-alias suite covers them.

**More emulators.** Each one becomes built-in only after its package **and** activity are read
from a real install (`adb shell dumpsys package <pkg> | grep -A1 MAIN`). None of these package
names are guessed in code. Until then, users add them through § 1 or this screen, and the
commented `emulators.example.json` shows how.

| Emulator | System | Xross today | Plan |
| --- | --- | --- | --- |
| Eden Nightly | Switch | Stock Eden (`dev.eden.eden_emulator`, `dev.legacy.eden_emulator`) only | Built-in after checking the nightly's package |
| Dolphin MMJR2 | GameCube, Wii | Stock Dolphin only | Built-in after checking |
| PokéDaisy | GBA | RetroArch cores only | Built-in after checking |
| hakuX | Xbox | No Xbox system | Example custom system in `systems.example.json`; built-in system later |
| XenDroid | Xbox 360 | No Xbox 360 system | Same as hakuX |
| A PC-98 emulator | PC-98 | No PC-98 system | Example custom system |

Forks often keep the upstream activity name under a new package. Check that the launch intent
of the fork matches the upstream recipe before reusing it.

**Code.** `state/SystemWizard.kt` and `state/EmulatorWizard.kt` (pure step state, tested),
`ui/WizardScreens.kt`, `ConfigCodec` writes that keep hand-written entries and comments as they
are. The package visibility question from § 1 applies here too: the app list needs the
intent-based `<queries>`, not `QUERY_ALL_PACKAGES`.

**Tests.** Wizard state (each step, Back, cancel leaves the files untouched); the presets produce
the right `command` strings and parse back through `EsdeCommand`; a device entry edits and
deletes without touching hand-written ones. Device check: add a system and an emulator on the
A55 with one real emulator, launch a game, then check the JSON by hand.

**Watch for.** Users picking a non-emulator app (allowed, but "Try it" should show the launch
error clearly); exported activities that crash without extras.

**As built (2026-11-21).**
- Both wizards live in **Settings ▸ Emulators** (Add emulator…, Add system…, and "Add emulator
  for <system>…" in a system row's Options); there is no Settings ▸ Games ▸ Systems. Options on an
  unknown folder isn't built: unknown folders aren't in the menu, so the folder step lists the
  unclaimed folders of the ROM folders first instead.
- Text steps use the system text dialog (`TextPrompt.WizardText`), not the gamepad keyboard.
- The system wizard's emulator step lists Xross's recipes (installed first), then installed apps
  without one (they get a device recipe with the file-URI preset and the launcher activity when
  the system is saved), then Android's chooser. A picked logo is copied to `Xross/config/logos/`
  and shown on the folder when there is no bundled logo.
- A device emulator or system opens again from its row to edit or delete. Saving a system adds an
  outside folder to the ROM folders and rescans.
- Code: `state/Wizard.kt` (shared rows), `state/EmulatorWizard.kt`, `state/SystemWizard.kt`,
  `state/WizardController.kt`, `storage/FolderSurvey.kt`, `ui/WizardScreens.kt`;
  `GameLauncher.planWith` for Try it.
- More emulators: none added; none was read from a real install. The commented samples cover the
  plan's examples with placeholder packages where unknown.

---

## 2. Metadata & Media editor for one game (L + S)

**Today.** Options (`△ / Y`) on a game ▸ Metadata is a submenu with Edit title…, Find match…,
Scrape again and Use automatic match (`MenuTree.metadataMenu`). There is no way to edit the
description or choose art: `ScreenScraperProvider.parseMedia` keeps one item per `ArtKind`,
`SteamGridDbProvider.image` keeps the first, and `ScrapeOrchestrator.downloadArt` overwrites on a
replace rescrape. Details already pages between games with `L1 / R1`
(`GameDetailsState.position` / `count`, `GameDetails.position(ids, current)`).

**Change.** Options ▸ **Metadata & Media** opens a full screen on the focused game, not a submenu.

Layout, top to bottom:

- **Header:** game title, platform, match status (the current `metadataDetail` text), and
  `L1 R1 · Game n of m` when the list has more than one game.
- **Text & match rows:** Edit title…, Edit description…, Find match…, Scrape again, Reset metadata.
  These reuse the existing commands; Edit description is new.
- **Media rows**, one per `ArtKind`, each with a small preview of the current art and a status:
  `Scraped · ScreenScraper`, `Chosen` (pinned), `From your folder` (ES-DE file adopted by
  `AssetCache`), or `None`.

  | Row | `ArtKind` | Folder |
  | --- | --- | --- |
  | Box art | `BOX_2D` | `covers` |
  | 3D box | `BOX_3D` | `3dboxes` |
  | Capsule | `GRID` | `capsules` |
  | Hero background | `HERO` | `heroes` |
  | Logo | `WHEEL` | `wheels` |
  | Screenshot | `SCREENSHOT` | `screenshots` |
  | Video snap | `VIDEO` | `snaps` (only when Scrape videos is on) |

- **Reset media** at the end clears every pin for this game.

**Controls.** Up/Down moves between rows, Confirm opens the row, Back leaves the editor (to the
game's Options or the library browser, whichever opened it). `L1 / R1` pages to the previous or
next game in the list the editor was opened from, as on Details. The editor keeps the focused
row when paging, so you can go down a list fixing the same slot on each game. `L1 / R1` has only
this job here. The library browser opens from Settings or a system's Options (§ 3), not from a
shoulder button in the editor.

**Media picker** (Confirm on a media row), as planned in 0.2.1.0 § 7:

- `ScrapeProvider.mediaOffers(request, kind): List<MediaOffer>` (url, thumbnail url, kind,
  provider, region, width × height, label such as "Box 3D · EU"). ScreenScraper reuses the
  `jeuInfos` response and stops dropping items; SteamGridDB lists
  `/grids|heroes|logos/game/{id}` in full.
- **Offers are fetched only when a picker opens**, never on paging or when the editor opens, and
  are cached in memory per game for the session. Paging fast through a system with `L1 / R1`
  therefore costs no quota.
- A gamepad grid of thumbnails (SteamGridDB thumbnail URLs, full URL only on pick) through
  `ArtLoader` at small size into a temporary cache. Rows at the end: "Use a file…" (an image on
  the device) and "Remove".
- Picking downloads into the existing `AssetCache` slot with `adopt` (ES-DE names and the
  `artVersion` refresh keep working), pins the kind, and the tile, Details and the palette update
  at once.
- **Empty picker says why:** "No SteamGridDB key. Add one in Settings ▸ Metadata to get heroes,
  logos and capsules", "ScreenScraper quota used up for today", "No match yet. Use Find match…".

**Data.** Room schema v5 → v6, one migration, exported schema:

- `game_metadata.pinnedArt` — comma-separated `ArtKind` names. `downloadArt` skips pinned kinds
  even with replace; Scrape again keeps pins; Reset media clears them.
- `game_metadata.synopsisOverride` — the user's description. Shown instead of `synopsis`
  everywhere; Reset metadata clears it along with `titleOverride` and `locked`.
- `game_metadata.mediaStatus` — a small bitmask of which `ArtKind`s have a file, written by
  `AssetCache` on store, adopt and remove, and by a one-off backfill in the migration's first
  scan. § 3 and § 4 read it instead of touching the file system per game.

**Code.**
- `state/MetadataEditor.kt`: pure state (game id, list ids, position, focused row, open picker)
  with the paging logic shared with `GameDetails.position`.
- `ui/MetadataEditorScreen.kt` and `ui/MediaPicker.kt`.
- `TextPrompt.GameDescription` beside `TextPrompt.GameTitle`; `Command.EditDescription`,
  `Command.OpenMetadataEditor`, `Command.PickMedia`, `Command.RemoveMedia`, `Command.ResetMedia`.
- `MenuTree.metadataMenu` becomes a single "Metadata & Media" option; the old rows move into the
  editor.

**Tests.** Parser tests with recorded ScreenScraper and SteamGridDB responses (every offer kept,
labels right); migration test v5 → v6 including the `mediaStatus` backfill; orchestrator test that
pinned kinds survive a replace scrape; editor state tests (paging keeps the row, ends of the
list, Back target); picker state tests (navigation, pick, remove, empty reasons). A test that
paging does not call `mediaOffers`.

**As built (2026-11-21).** Differences from the above:
- The `mediaStatus` backfill can't run inside the migration (it has no view of the media folders),
  so v6 adds the column as NULL and `GameMediaEditor.backfillStatus` fills it on the first library
  load after the upgrade, then for new games only.
- Removing a slot pins it empty (shown as `Removed`), so Scrape again doesn't bring the art back.
- Pick, Remove and Reset media are handled inside `MetadataEditorController`, not as `Command`
  cases; only `Command.OpenMetadataEditor` is new. The picker lives in `MetadataEditorScreen.kt`.
- ScreenScraper thumbnails use the media URL with `maxwidth` / `maxheight` added. Check on the
  device that the media server honours them; if it doesn't, thumbnails are full-size downloads.
- Not built yet: entry from the library browser (§ 3), so Back always returns to the game's Options.

**Device check (A55).** Quota counter moves by one per provider per picker opened and not at all
when paging; the thumbnail grid scrolls at 60 fps on the profiling build with 30+ thumbnails;
a picked hero shows on Details at once.

**Watch for.** ScreenScraper's daily quota and thread limit (`RateLimiter`); large SteamGridDB
images; a long description in the on-screen keyboard prompt (it may need a multi-line prompt).

---

## 3. Library browser: fix media across a whole system (M, stretch)

**Goal.** Go through a whole system and fix its media in minutes.

**Entry.** Settings ▸ Metadata ▸ Fix media…, and Options on a system folder ▸ Fix media (opens on
that system).

**Layout.**
- **Console strip** at the top: All, then every scanned platform with its console logo (reuses
  `systemLogo` from `SystemLogos.kt`). `L1 / R1` or Left/Right moves along it.
- **Game list** below: every game on that platform with badges read from `mediaStatus` and
  `pinnedArt`: one per `ArtKind`, filled when present, hollow when missing, and a pin mark when
  chosen. Badges use theme glyphs (`GlyphKey`), not emoji, so theme packs can restyle them.
- **Filter row:** All · Missing anything · Missing box art · Missing hero · Missing logo.

**Rapid edit loop.** Confirm on a game opens the § 2 editor with that platform's list, so
`L1 / R1` there pages within the platform. Back returns to the browser on the same game and
scroll position, with that game's badges refreshed.

**Code.** `state/LibraryBrowser.kt` (platform, filter, focus, scroll, kept across the editor
round trip) and `ui/LibraryBrowserScreen.kt`. One query per platform from `GameDao` joined with
`game_metadata`; no file access while scrolling.

**Tests.** State tests for strip navigation, filters, and return position after the editor.
Device check: a 1,000-game All list scrolls at 60 fps on the profiling build.

**As built (2026-11-21).** Settings entry is Settings ▸ Library ▸ Metadata & artwork ▸ Fix
media… (the metadata settings live there). Badges are text pills (BOX, 3D, CAP, HERO, LOGO, SHOT),
not theme glyphs: `GlyphKey` has no per-art-kind icons yet, and adding them means new drawables
and theme-spec entries; a 0.2.3.0 item. ↑ from the first game reaches the filter row; ←/→ there
change the filter. `state/MediaFilter.kt` is shared with § 4. Not measured: the 1,000-game scroll.

---

## 4. "Missing art" filter on game lists (S)

If § 3 slips, this keeps most of its value: Options on a system folder ▸ Show ▸ Missing art
limits that folder to games whose `mediaStatus` lacks box art, hero or logo. The § 2 editor
opened from there pages through only the filtered games.

**Tests.** Filter over `mediaStatus` combinations; editor paging over the filtered list.

**As built (2026-11-21).** The filter is per session (not saved). A folder with nothing missing
shows one row that turns the filter off. The same Options menu has **Fix media…** (§ 3).

---

## 5. Theme music: choose it in Settings, shuffle several tracks (S–M)

**Today.** Theme music comes only from the pack: `audio.bgmFile` (alias `bgm`), else `bgm.ogg` /
`bgm.mp3` at the pack root, through variants, overrides and rules. It plays as one gapless loop.
Settings ▸ Sound has only its volume. The wallpaper already has a user override
(`display.customWallpaper`, Settings ▸ Theme ▸ Xross wallpaper).

**Change.**
- **Settings ▸ Theme ▸ Theme music**, beside Xross wallpaper and built the same way: Theme pack
  (…) · Pick a file… · Pick a folder… · Off. The choice is saved as `display.bgm` (a file, a
  folder, or `none`) and wins over the pack's cascade, as `display.effects` does. "Reset to theme
  defaults" clears it.
- **Several tracks.** A folder (the user's, or `audio.bgmFiles` in a pack: a list of files) plays
  as a list. `display.bgmOrder`: `SHUFFLE` (default for lists) or `IN_ORDER`. One file keeps
  today's gapless loop. A list crossfades over 1.5 s between tracks; no track repeats until all
  have played.
- **Same rules as 2b.5:** silent while a song is loaded in Xross's music player or another app
  holds audio focus, ducked under a snap, stopped before an emulator launch. The next track
  starts from where the list left off when theme music comes back, not from the first.
- Each file still has the 20 MB limit; a folder is capped at 50 tracks.

**Theme spec.** `audio.bgmFiles` (list) and `audio.bgmOrder` added to `THEME_SPECIFICATION.md`
§ 5; `bgmFile` wins when both are set. Export theme and Share as `.xtheme` copy the files.

**Code.** `BgmPolicy` stays the gate; a small `BgmQueue` (pure: order, shuffle bag, position)
feeds the BGM player. `ThemeLookResolver` resolves `bgmFiles`; `ConfigCodec` reads and writes
`display.bgm` and `display.bgmOrder`.

**Tests.** `BgmQueue` (shuffle bag, no repeat, resume position); config round trip; the cascade
with `bgmFile` and `bgmFiles` together; `ThemeRoundTripTest` with a list. By-ear check on the
A55: crossfade, a pause under a song and the resume.

**As built (2026-11-21).** The choice is resolved by `data/ThemeMusic.kt`; Settings keeps it as
`themeMusic` / `themeMusicOrder` (`display.bgm` / `display.bgmOrder` in the file). A picked file on
shared storage is used where it is; one only reachable through its provider is copied to
`Xross/media/bgm/`. `BgmTrack` carries the list; `ThemeBgm` crossfades on a second player and
logs each track change (`XrossBgm: next track n/m`). The list round trip is in
`LookExporterTest` rather than `ThemeRoundTripTest`. Reset to theme defaults clears the choice.

## 5b. Wallpaper rotation (S–M, stretch)

- Xross wallpaper ▸ Pick a folder… plus an interval (Off, 1, 5, 15, 60 minutes, or each time
  Xross comes to the front). Saved as `display.wallpaperRotation`.
- The next picture is decoded and pre-blurred (`PreBlur`) off the main thread before it
  crossfades in over 600 ms, so the frosted surfaces change with it.
- **Idle cost.** Rotation must not wake a resting screen: a change waits until the next input or
  the next time Xross comes to the front. Idle CPU is already above its target (Roadmap), so
  measure it on the A55 with rotation on.
- **Tests.** Rotation state (interval, order, a missing file skipped); idle check on the
  profiling build.
- **As built (2026-11-21).** `state/WallpaperRotator.kt` (no timer: `onInput` from the Activity's
  input hook, `onFront` from `onStart`); interval names `OFF`, `MIN_1`, `MIN_5`, `MIN_15`, `MIN_60`,
  `FRONT`, saved as `display.wallpaperRotation` `{folder, interval}`. Picking a folder sets 15
  minutes if the interval was Off. The crossfade is in `WallpaperBackdrop`; the frosted layer
  switches to the new picture as it starts fading in. Idle CPU not yet measured.
- **Added after the plan:** a Photo album can be the rotating wallpaper (`wallpaperRotation.album`,
  Photo ▸ album ▸ Options ▸ Use as Xross wallpaper), and a Music playlist can be the theme music
  (`display.bgm` = `playlist:<name>`, Playlists ▸ Options ▸ Use as theme music). Both follow the
  library and the playlist file as they change. `MediaAsBackgroundTest` (7).

---

## 6. GameNative PC shortcuts (research spike)

GameNative games live inside the app, not as files in a folder, so § 1's folders and recipes
don't reach them. Before promising anything, find out in half a day:

- How GameNative exposes a game: pinned shortcuts (`ShortcutManager`), dynamic shortcuts, an
  exported activity with a game id extra, or files it writes somewhere.
- If shortcuts: `LauncherApps.getShortcuts` and `startShortcut` work only while Xross is the
  default home app. Check what happens when it isn't.
- Whether the same approach also works for other "games inside an app" launchers.

**Outcome:** a short note in `docs/design/` with the answer and a size. Building it is a 0.2.3.0
item unless the spike shows it is under half a day.

**Result (2026-11-21):** [`GAMENATIVE_SPIKE.md`](GAMENATIVE_SPIKE.md). GameNative exposes a public
`app.gamenative.LAUNCH_GAME` intent (app id + store), so no shortcut access is needed to launch;
the missing piece is the game list. S for launcher files, M with shortcut import; 0.2.3.0.

---

## Release checklist

- `./gradlew check assembleDebug assembleProfiling` passes; unit test count recorded in
  `docs/status/UNIT_TESTS.md`.
- Room schema v6 exported; migration tested from a real v5 database on the A55 (install over
  0.2.1.0-beta, art and titles intact).
- Device pass on the A55 from `docs/HARDWARE_TESTING.md`: one real emulator added only through
  `emulators.json` and one through the Add emulator screen; media picker on ScreenScraper and
  SteamGridDB; quota counter checked; theme music list by ear.
- Frame timing on the profiling build for the thumbnail grid and, if built, the library browser;
  idle CPU with wallpaper rotation on, if built (see `docs/status/PERFORMANCE.md`).
- `CHANGELOG.md`, `Roadmap.md`, `docs/USER_GUIDE.md` and `THEME_SPECIFICATION.md` (new badge
  glyphs, `audio.bgmFiles`, `audio.bgmOrder`) updated; version `0.2.2.0`.

**State at release (0.2.2.0-beta, 2026-11-21):** done: the check and builds (1,040 + 91 tests;
the count is in `docs/STATUS.md`), schema v6 and the real v5 → v6 upgrade on the A55, the docs,
version `0.2.2.0-beta` (code 220). Shipped as a beta without: real emulators added on the A55,
the media picker and quota checks, theme music by ear, frame timing and idle CPU. Badge glyphs
stayed text pills. These carry over to the next release.

---

## Moved to 0.2.3.0: flat or grouped lists, and a sort order per category

Kept here so the reasoning isn't lost.

- **Today.** Only Games has a grouped/flat switch (`allGamesFolder`, Settings ▸ Theme ▸ Platform
  folders), plus app folders in Apps. Only songs and albums have a sort order.
- **Idea.** A grouped/flat switch for every category, including the Apps folders in Video, Music
  and Photo, and a sort order for each list (name, recently played, play time, date added,
  platform) changed in one press.
- **Why it waits.** It's M–L of navigation work that builds on nothing else in 0.2.2.0. And the
  proposed Square / X quick toggle conflicts with today's controls: X and Y both open Options
  (`KeyMapper.kt`). Giving X its own job means a new `NavAction`, remapping support, new button
  hints, and a changed habit for every user.
- **Starting point for 0.2.3.0.** Options ▸ Sort by on every list first, with no new button. A
  Square shortcut only as an opt-in setting in Settings ▸ Controls.
