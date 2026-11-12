# Project Xross — Implementation Status

Status of the codebase against [`project_scope.md`](../project_scope.md), as of **2026-11-11**
(commit `2c142d6`, Phase 2b.1). It is the evidence file: what has been built, how it was verified,
measured performance across device classes, and known limitations. Plans, the backlog and the
order of work are in [`Roadmap.md`](../Roadmap.md).

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

---

## 1. Summary

| Area | State |
| --- | --- |
| Phase 1 (Alpha): XMB navigation, gamepad layer, library, emulator dispatch, wave shader | ✅ complete |
| Phase 1b (hardening): hot-swap, USB-OTG, playtime, Options menu, per-game emulator, 6 more emulators, per-controller mappings, axial deadzone, 0% deadzone | ✅ done; real-handheld testing and a Baseline Profile remain (§6) |
| Visual front end from `assets/examples` (icons, themes, glow, status bar, sounds) | ✅ plus the Original monthly theme, busy indicator, optional button hints, Information screen |
| Phase 2 (metadata, media hub, theme packs, on-screen controller and keyboard) | ◐ fingerprinting, media hub, gamepad search, DAT verification, scrapers, art, Details screen, manual matching and video snaps done (§2, §3); portable `Xross/` folder with JSON config and database backup, All Games, media controls shortcut and mini-player hide done (§3.9); library organization (hidden apps, app folders, base ROMs folder, custom Photo/Video folders, art in `Xross/media`) done (§3.10); ScreenScraper verified live with developer credentials (§3.13); theme packs, wave shader settings and the focused-game background done (§3.13); on-screen controller not started |
| Phase 2b.1 (customization engine): avatar mini-HUD, 16:9 game tiles, Details paging, playtime subtitle, game RA telemetry, profile customization, RetroAchievements dashboard | ✅ 🧪 unit- and view-model-tested; no device run yet (§3.15) |
| Phase 2b.2 (Theme 2.0 foundation): v2 manifest, imports, surfaces, hot reload, layout block, motion, drill-down, variants, category and system overrides, `.xtheme` import | ✅ 🧪 on `feat/theme-2b2` (2026-11-12): unit tests, `smoke.sh` 139/139 and the 12-configuration display matrix on the API 37 emulator; not yet on a phone or handheld (§3.16–§3.18) |
| Phase 2b.3 (color intelligence): game-art palette, `rules` context engine, circadian curve, battery tint, quick profiles | ✅ 🧪 on `feat/theme-2b3` (2026-11-12): 495 unit tests; `smoke.sh` with the 2b.3 checks 164/171 on the API 37 emulator (every new check passes; the failures are timing in older sections, §3.19). **Frame pacing of game navigation not measured yet**: the emulator's system process stopped responding mid-run. Open items: [`PHASE_2B3_PENDING_TESTS.md`](PHASE_2B3_PENDING_TESTS.md) |
| Phase 3 (dual screen, hot-swap, RetroAchievements, Web Portal, FTPS) | ◐ RetroAchievements account linking, status bar avatar and User Card (§3.12), per-game achievement lists and unlock toasts (§3.13), live on the emulator and the Galaxy A55 (§3.14); hot-swap and live resolution change done; dual screen, docking, Portal and FTPS not started |
| Phase 4 (onboarding, triage, profiling, plugin API) | ◐ app triage and performance work started |

**Verification:** 495 JVM/Robolectric unit tests (491 run by default; 4 opt-in tests call the real
services), all passing on 2026-11-12 on `feat/theme-2b3` (§3.1, §3.19), end-to-end launch tests against stub builds of eleven emulators, a
hot-swap test with a real removable volume, frame-pacing measurements across 12 display
configurations on the emulator, live scraping runs, and feature passes on a **Samsung Galaxy
A55** (Android 16, 120 Hz) on 2026-11-11 (§3.11–§3.14). The updated on-device smoke test ran on the
Galaxy A55 on 2026-11-11: 75 of 86 checks pass, and the 11 failures are Photo/Music checks that
need a device without its own media (§3.13). On 2026-11-12 the smoke test, extended with the 2b.1
checks, passed 112 of 112 on the API 37 emulator (§3.15), and with the Theme 2.0 (2b.2) checks 139
of 139 (§3.18). ScreenScraper and RetroAchievements sign-in ran
live; frame pacing on the A55 is 0.63% janky with 82.9 MB PSS in the background (§3.13). No physical controller, gaming handheld or real emulator install has been tested yet,
and the portable folder and library organization passed a device run on the Galaxy A55 as an
upgrade of a real install (§3.11); SD card, Android 9/10 and handhelds are still untested.

---

## 2. Feature status by scope section

### 3.1 Controller & navigation engine

| Feature | State | Notes |
| --- | --- | --- |
| XMB dual-axis navigation (categories ↔, items ↕, drill-down stacks) | ✅ | Each category keeps its own stack and cursor; breadcrumb shown when drilled in |
| Android `InputDevice` gamepad handling (keys, sticks, hat) | ✅ 🧪 | Joystick motion is always consumed, so the D-pad never double-steps |
| Vendor detection: PlayStation, Xbox, Nintendo, generic | ✅ | Picks the on-screen button glyphs (✕○△ / ABXY / Nintendo order) |
| Confirm/cancel swap (Auto / South / East) | ✅ | Auto uses East on Nintendo pads |
| Button remapping (wizard) | ✅ | 10-step capture (the last is the media controls button, Select by default); saved per controller model (vendor/product ID). The first device to press a button owns the session; keyboards and devices without IDs use a global mapping. Each saved controller can be reset on its own |
| Options (△ / Y) context menu | ✅ | Slide-in panel with submenus; long-press opens it on touch |
| Per-game remapping | ❌ | |
| Mapping saved per generic HID device | ✅ 🧪 | Unit- and view-model-tested; needs two real controllers to verify |
| Analog deadzone (radial) + anti-snapback + hold-to-repeat | ✅ 🧪 | Unit-tested; adb cannot inject real stick motion. Steps: Off (hardware flat zone only) / 15 / 25 / 35 / 45 / 55% |
| Media controls shortcut | ✅ 🧪 | Select, or Guide held 0.5 s, toggles Now Playing; remappable |
| Axial deadzone option | ✅ 🧪 | Off / 10 / 20 / 30% per axis, applied before the radial check (Settings ▸ Controls) |
| Touch parity (one category per horizontal swipe, continuous vertical drag, tap) | ✅ | |
| First D-pad press after a touch is not lost | ✅ | Fixed ViewRootImpl "leave touch mode" swallowing |
| On-screen controller overlay | ❌ | Phase 2 |
| Gamepad virtual keyboard | ✅ | 10-column grid; Options = space, L1 = delete, R1 = results; hardware keyboards type directly |

### 3.2 Game libraries & storage

| Feature | State | Notes |
| --- | --- | --- |
| Platform categories (22 systems, folder-alias detection) | ✅ | NES … Saturn, Arcade, 3DS, Switch, ScummVM |
| Favorites, Recently Played (timestamp + play count) | ✅ | |
| Total playtime counter | ✅ | Session = launcher paused after a launch → launcher resumed; survives process death; shown on game rows and in the Options menu |
| Playlists / custom tags | ❌ | |
| Extension filtering, `.cue`/`.gdi`/`.m3u` track de-duplication | ✅ | |
| Look inside `.zip`/`.7z`; CRC32/MD5/SHA-1 fingerprints | ✅ | Background job; see §3.4 |
| Information screen (Options ▸ Information) | ✅ 📱 | Platform, file, folder, size, dates, playtime, emulator, DAT match, fingerprint source and hashes |
| No-Intro header-less hashes (iNES, FDS, SNES copier) | ✅ | Stored alongside the full-file hashes |
| CHD identification | ✅ | SHA-1 from the v4/v5 header |
| Per-track hashes for `.cue`/`.gdi` (Redump) | ❌ | Sheets are skipped for now |
| Redump/No-Intro/MAME DAT import and matching | ✅ 📱 | Logiqx XML and clrmamepro text, plain or zipped; SHA-1 > MD5 > CRC32 + size > arcade set name |
| Room library, incremental rescan, unmount-safe deletion | ✅ | 5,000 ROMs: first scan 1.9 s, rescan 0.9–1.1 s (emulator) |
| Title search on the FTS4 index | ✅ | Games ▸ Search: live word-prefix results, launch and Options from results |
| Multi-volume pooling (internal + microSD) with `ROMs`/`Games`/`Emulation` auto-detect | ✅ | |
| USB-OTG volumes | ✅ 🧪 | `StorageManager.getStorageVolumes()` merged with app-dir roots |
| Hot-swap (`MEDIA_MOUNTED`/`UNMOUNTED`/`EJECT`/`REMOVED`/`BAD_REMOVAL`) | ✅ 🧪 | Debounced quiet rescan in front, deferred while a game runs; games on a removed volume are kept and marked "Not connected" |
| Volume naming, drive icons, `autorun.inf` (parse-only) | ❌ | Phase 3 |
| Emulator intent matrix: RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | Verified end-to-end with stub apps (§3.3); not yet with real emulators |
| Per-platform emulator choice | ✅ | Settings ▸ Emulators |
| Per-game emulator override | ✅ | Options ▸ Emulator; precedence: game override → platform choice → first installed |
| Permission bridge (`content://` + `FLAG_GRANT_READ_URI_PERMISSION`) | ✅ | Every recipe, RetroArch included |
| Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM | ✅ 🧪 | Recipes from ES-DE's Android rules; new 3DS, Switch and ScummVM platforms |
| Sudachi, Vita3K, others | ❌ | Sudachi is no longer in ES-DE's rules; no verified launch contract |

### 3.3 Metadata & achievements — ✅ done and live-verified

| Feature | State | Notes |
| --- | --- | --- |
| Hasheous (hash identification, no account) | ✅ 📱 | `POST /api/v1/Lookup/ByHash`; arcade sets send their largest ROM chip and the set name picks between games that share it |
| SteamGridDB (capsules, hero banners, logos) | ✅ 📱 | Free per-user API key; autocomplete search by the best known title |
| ScreenScraper (descriptions, box art, logos, screenshots, video snaps) | ✅ | Follows the API page (English guide: `docs/SCREENSCRAPER_API.md`): file hashes plus size, name search for files too large to hash, and the mandatory quota handling. Live with developer credentials (§3.13), then with a user account too (§3.14) |
| Throttled batch scraper | ✅ 📱 | Per-service pacing, back-off on 429, daily-quota stop, retry after a day on failure; only while Xross is in front; opt-in and Wi-Fi only by default |
| Asset cache | ✅ 🧪 | `Xross/media/<kind>/<system>/<rom stem>.<ext>` since 2026-11-11 (§3.10); atomic writes; files accepted only if their bytes are PNG, JPEG, WebP or MP4; art of removed games pruned. The earlier private UUID cache (verified on the Galaxy A55) is moved over once |
| Game Details screen | ✅ 📱 | Background art (or the playing snap), box art, logo, release facts, rating, playtime, scrollable description |
| Manual match and override tool | ✅ 📱 | Options ▸ Metadata: Edit title, Find match (results from every service), Scrape again, Use automatic match |
| Language priority list | ◐ | One region and one language setting |
| RetroAchievements account and profile | ✅ 📱 | Settings ▸ Accounts link dialog, status bar avatar, User Card (§3.12); since 2b.1 the avatar expands into a mini-HUD (§3.15). Live on the emulator and the Galaxy A55 (§3.14) |
| RetroAchievements game achievements | ✅ 🧪 | Options ▸ Achievements and a Details summary: badge grid (locked greyed), titles, descriptions, points, hardcore/softcore and dates, completion bar. ROMs matched by RetroAchievements' hash per system, title only for disc systems or unfingerprinted ROMs. Live with a Web API key (§3.14) |
| RetroAchievements unlock toasts | ✅ 🧪 | After a game session (and any profile refresh) new unlocks queue as top-right toasts with the trophy sound; drawn only, never take input. Not yet seen after a real session |
| RetroAchievements dashboard and per-game telemetry | ✅ 🧪 | Games ▸ RetroAchievements and the badge above the button hints (Phase 2b.1, §3.15) |

Google Play and web-search scraping are dropped (see `FEASIBILITY.md`). Dusklight/TwilitRealm is
parked until an API is documented.

### 3.4 Media engine & customization

| Feature | State | Notes |
| --- | --- | --- |
| Photo, Music and Video categories in the XMB | ✅ | From MediaStore (tags, EXIF, folders); live thumbnails and album art |
| Photo viewer: pan/zoom, slideshow, Set as wallpaper | ✅ | Animated zoom up to 5×, pan clamped to the photo's edges, crossfade slideshow (3–20 s), pinch/swipe/tap |
| Music player as a background service | ✅ | Media3 ExoPlayer + MediaSession: notification, headset and lock-screen controls; albums, artists, shuffle, repeat |
| BGM mode: music pauses for games and resumes after | ✅ | Settings ▸ Media; also pauses for videos |
| Video player with controller playback and scrubbing | ✅ | Seek 10 s / 60 s, previous/next, loop toggle, auto-hiding on-screen display |
| Focused-game background | ✅ 🧪 | Hero art (or a screenshot) crossfades in after 250 ms; the video snap fades in over it after 1.2 s with a 300 ms audio ramp, music ducked to 20%; the wave's GL loop stops while either covers the screen; decoder released when focus moves |
| Wave background (GLSL ES 3.0) | ✅ | Glossy sheets plus strands, AA crests, dithering, vsync-snapped time, reduced-res target |
| Wave parameters: speed | ✅ | 0.5×–2× |
| Wave parameters: line count, turbulence, thickness, custom gradients | ✅ | `uLines`, `uTurb`, `uThick` uniforms plus 7 tint presets and a custom gradient; Settings ▸ Theme ▸ Wave settings with sliders over the live wave; `display.wave*` in `settings.json` |
| Color themes | ✅ 📱 | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original: a color per month, crossfaded over the three days either side of a month change |
| Busy indicator | ✅ 📱 | Ring of dots by the hint pill while scanning, fingerprinting, importing DATs or scraping |
| Hide the button-hint pill | ✅ 📱 | Settings ▸ Theme ▸ Button hints |
| Day/night color shift | ✅ | Brightness follows the time of day |
| Battery-level color blending | ✅ 🧪 | `palette.batteryTint` / Settings ▸ Theme ▸ Battery alert tint: amber to red below 20%, a green pulse while charging (2b.3, §3.19) |
| Coldboot: fade-in + startup sound | ✅ | Once per process start, `01. opening.wav` (was the coldboot cue); can be turned off |
| Custom boot animations | ❌ | |
| UI sound effects + volume, game-start cue | ✅ | SoundPool for short effects, MediaPlayer for long cues |
| BGM and video-snap volume sliders | ◐ | Music uses system media volume; separate sliders planned in Phase 2b.5 |
| Loadable sound packs / theme packs | ✅ | `Xross/themes/<id>/` (theme.json, fonts, icons, sounds); the bundled icons and sounds are the built-in Default pack in `assets/themes/default`. Variants, category and system overrides, and single-file `.xtheme` import (§3.18) |
| Icons from the design SVGs | ✅ | `tools/icons/svg_icons.py` → 29 SVGs in the Default pack, read by `SvgIcon` at runtime |
| Custom fonts | ✅ | One .ttf/.otf per theme pack, applied to all launcher text |
| UI scale slider (50–150%) | ❌ | Layout already scales automatically (§4) |
| Aspect-ratio overrides, status-bar mode options | ❌ | Currently immersive (system bars hidden); status-bar modes planned in Phase 2b.7 |
| Game tiles and avatar style | ✅ 🧪 | 16:9 XMB tiles in Games; `profile.avatarStyle` with Settings ▸ Theme ▸ Profile customization (Phase 2b.1, §3.15) |

### 3.5 Device integration & form factors

| Feature | State | Notes |
| --- | --- | --- |
| Any aspect ratio / resolution | ✅ 🧪 | 12 configurations from 640×480 to 4K (§4). Since 2026-11-10 Xross is locked to landscape (`sensorLandscape`); the portrait row in §4.1 predates the lock |
| Live resolution change without restart | ✅ 🧪 | Verified by switching display size while running (docked-mode prerequisite) |
| Dual-screen (Presentation API, per-screen layouts, swap hotkey) | ❌ | Phase 3 |
| HDMI/DisplayPort dock detection (`DisplayManager`) | ❌ | Phase 3 |
| App triage: games (`CATEGORY_GAME`) to Games ▸ Android Games, others to Apps | ✅ | |
| Hide apps, Hide system apps, Hidden apps screen | ✅ | Options ▸ Hide; Settings ▸ Apps |
| App folders | ✅ | Options ▸ Move to folder (Emulators, Streaming, Tools or a typed name); emulators suggested for Emulators; "Group emulators" row |
| Emulator detection among installed apps | ◐ | Installed check for known packages; not shown as a separate group |
| Can be the HOME launcher; Android TV leanback entry | ✅ | |

### 3.6 Network daemons — ❌ not started
HTTPS Xross Portal (Ktor CIO), FTPS (Apache FtpServer), auth, uploads, library editing.

---

## 3. Test results

### 3.1 Unit tests — 464 tests, 0 failures (2026-11-12)
`./gradlew :app:testDebugUnitTest` (JVM + Robolectric on SDK 35): 460 run by default, 4 opt-in live
tests skip unless `-PliveScrape=true`. The 415 from Phase 2b.1 passed unchanged; the idle rest
added `InactivityTrackerTest` (3 tests: timeout, input pushing it back, wave frame strides), and
2b.2 slice 1 added 8 to `ThemePackTest` (§3.16). Slice 2 added `ThemeLayoutTest` (13, §3.17); slice 3 added
`ThemeVariantsTest` (12) and `ThemeArchiveTest` (13) and a `themeVariant` field to the
`ConfigCodecTest` round trip (§3.18). The table below lists the original suites; later suites
are listed after it.

| Suite | Tests | Covers |
| --- | --- | --- |
| `KeyMapperTest` | 6 | South/East swap, remap precedence, vendor families |
| `StickTrackerTest` | 4 | Radial deadzone, single press while held, snapback rejection |
| `RomScannerTest` | 6 | Title cleaning, folder aliases, extension filter, asset-folder skip, cue/m3u/gdi |
| `LibraryDiffTest` | 4 | Insert/update/delete, unmounted volume kept, removed folder purged, prefix safety |
| `NavigationTest` | 6 | Stacks, menu tree, empty library, emulator resolution, remap session, SAF paths |
| `MenuSettingsTest` | 6 | Settings sections, sound/theme rows, disc vs cartridge icons, folders |
| `EmulatorIntentTest` | 4 | Every intent recipe: component, extras, data URI, read grant |
| `SettingsStoreTest` | 2 | Every setting survives a restart; defaults |
| `FingerprinterTest` | 12 | Known CRC/MD5/SHA-1 vectors; iNES and SNES header stripping; ZIP member choice and header-only mode; 7z; CHD v5; arcade sets; skip rules; missing files |
| `FingerprintQueueTest` | 3 | Staleness and paging queries, stats, cascade delete, real queue run (Room in memory) |
| `MediaMenusTest` | 8 | Photo folders and thumbnails, album track order, artists, Now Playing row, video subtitles, empty states, six categories, EXIF rotation, pan limits |
| `HotSwapTest` | 10 | Volume merging, USB-OTG via `StorageManager`, volume roots, folder planning (remember / forget / custom), broadcast mapping and a registered receiver, offline marking |
| `PlaytimeTest` | 9 | Session rules (arm window, failed launch, process death, clock change, cap), accumulation, **migration 2→3**, formatting, row subtitles |
| `OptionsMenuTest` | 9 | Menu state (move, select, submenu, back), game menu contents, override marking, launch precedence, DAO, **migration 2→4** |
| `SearchTest` | 13 | FTS query building and sanitising, keyboard cursor across wide keys, typing and shortcuts, results focus, stale results, FTS index sync on insert/update/delete |
| `XmbViewModelTest` | 4 | Constructs the real view model over the real container: startup with and without storage access, lifecycle and volume events, navigation |

`EmulatorIntentTest` grew to 10 tests (six new recipes, platform registry) and `NavigationTest` to 7.

Added on 2026-11-11 (Milestones 1 and 2, 79 tests):

| Suite | Tests | Covers |
| --- | --- | --- |
| `AllGamesFolderTest` | 7 | All Games grouping, registry order, classic flat layout, empty library, settings row |
| `MediaShortcutTest` | 15 | Select → media controls, remapping, Guide long press (repeats, release, taps), mini-player 30 s hide |
| `XrossEnvironmentTest` | 8 | App-storage fallback, shared root once writable, `.nomedia` first, chosen volume, unwritable volume fallback |
| `AtomicFilesTest` | 5 | Atomic replace, failed write keeps the old file, leftover `.tmp`, coalesced background writes |
| `ConfigCodecTest` | 10 | Round trip of every setting through the three files, key names, clamping, broken JSON, `systems.json` overrides |
| `PortableSettingsTest` | 11 | Files written, SharedPreferences migration, `.bad` quarantine, outside edits, moving to shared storage, adopting existing config, credentials never written |
| `DatabaseBackupManagerTest` | 9 | Snapshot in `Xross/data`, WAL contents included, backup only after changes, restore offer and staged restore, invalid files |
| `GameLauncherTest` | 6 | Plans, `content://` grant inside the launcher, foreign plans refused, test doubles |
| Others extended | 8 | `StickTrackerTest` (0% deadzone), `MenuSettingsTest` (deadzone label, System rows, restore row), `SettingsStoreTest`, `XmbViewModelTest` (deadzone cycle, media button) |

Added for Milestone 3 (35 tests):

| Suite | Tests | Covers |
| --- | --- | --- |
| `AppFoldersTest` | 13 | Hiding (Apps, Android Games, system apps), Hidden apps screen, folders and their order, emulator suggestion and Group emulators, Move to folder menu, folder name tidying, the name keyboard |
| `PortableMediaTest` | 10 | FAT-safe names, clashes per system, ES-DE folder layout, reusing ES-DE media, art following the Xross folder, the one-time move from the private cache |
| `LibraryOrganizationTest` | 11 | Create system folders (22, idempotent, failures), base folder in the scan plan and Library rows, media folder filter and walk (`.nomedia`, hidden, limits), synthetic ids, custom folders in the media library, Media settings rows |
| `PortableSettingsTest` | +1 | `apps.hidden`, app folders, base ROMs folder and `photo/video.customFolders` in `settings.json` |

`ScrapeOrchestratorTest`'s two asset-cache tests were rewritten for the new layout (same count).

Added for the status bar profile and floating mini player (29 tests, 2026-11-11):

| Suite | Tests | Covers |
| --- | --- | --- |
| `RetroAchievementsClientTest` | 7 | `API_GetUserProfile` / `GetUserSummary` / `GetUserRecentAchievements` parsing (canonical name, points, RetroPoints, RetroRatio, rank, newest-first badges, relative media paths), numbers sent as strings and nulls, key only in the `y` parameter, error mapping (401/403, empty answer, 404, 429, HTML, offline) with no key in messages, images fetched only from retroachievements.org over HTTPS |
| `ProfileRepositoryTest` | 8 | The key stays in `CredentialStore`: absent from `settings.json`, every file in the Xross folder and the old settings preferences; profile cache and `media/avatars/<user>.png` written; offline start served from disk with no request; a failed refresh or avatar download keeps the cache; no avatar → placeholder; refused key keeps the linked account; unlink removes key, cache and avatar; staleness; safe file names |
| `MiniPlayerCardTest` | 8 | Hidden with nothing loaded, shown while playing; paused: shown at 29.999 s, hidden at 30 s; resume and track change bring it back and restart the countdown; repeated identical updates don't extend it; covering hides it without resetting the timer; stop hides it at once |
| `ProfileCardTest` | 6 | User Card buttons (linked / not linked), movement, Close, two-press Unlink; Settings ▸ Accounts rows; Up from the top of a category focuses the avatar, Confirm opens the card, Back / tap closes it; Up inside a list still moves the cursor |

`MenuSettingsTest`'s list of Settings sections now includes Accounts (same count).

Added for Phase 2b.1 (20 tests, 2026-11-11; `HtmlTextTest`, 2 tests, came with the §3.14 fixes):

| Suite | Tests | Covers |
| --- | --- | --- |
| `Phase2bFeaturesTest` | 20 | `profile.avatarStyle` round trip, defaults when missing, clamping and a bad color falling back alone, no credentials in the block; avatar editor sliders, presets, glow toggle, reset and input mapping; Profile customization opening from Theme and saving at once; playtime subtitle boundaries and rows showing playtime instead of size; Details pager ends, single-item and foreign lists, L1/R1 and D-pad paging on the real view model; telemetry only for recognized games with achievements and following the focused row; library summary (each set once, mastered games); RetroAchievements entry among the Games collections; dashboard scrolling, closing and offline placeholder |
| Others updated | — | `AllGamesFolderTest`, `NavigationTest`, `MenuSettingsTest` (new list order and Theme rows), `PlaytimeTest` (subtitles), `ProfileCardTest` (avatar HUD flow), `ConfigCodecTest`, `WaveSettingsTest` |

### 3.2 Device smoke test — 75/86 on the Galaxy A55 (2026-11-11; the 11 failures are Photo/Music checks that need a device without its own media, §3.13)
`tools/device-tests/smoke.sh` resets the app, seeds ROMs, and drives the UI with key events.
It checks screen content through the accessibility tree. On 2026-11-11 its key counts were
updated (Options menu, Controls, All Games) and it gained checks for Details/Metadata, the
deadzone cycle, the Platform folders setting, the 10-button remap and the Select shortcut.

| Group | Checks |
| --- | --- |
| Library & navigation | Auto-detect `/sdcard/ROMs`; platforms listed; drill-down; cleaned titles; `.bin` hidden behind `.cue` |
| Favorites | Options hint, menu opens, favorite toggles through the menu, Favorites collection appears |
| Per-game emulator | Emulator submenu lists the platform default; an override is saved and shown as "this game only" |
| Search | Opens from the top of Games; typing finds a game by prefix; result count; R1 to results; Back returns to keys, then closes |
| Categories | L1/R1 movement, Settings sections (incl. Sound) |
| Settings | Theme cycles, wave toggle, Sound section, volume cycles |
| Remapping | Wizard start, 9-button capture, bindings saved, remapped button works, reset |
| Rescan | New file picked up (+1), deleted file dropped (−1) |
| Touch | Swipe left/right moves exactly one category; vertical drag scrolls |
| Fingerprints | Library settings report games identified by content after a rescan |
| Photo | Folders listed; viewer opens at the right photo; next photo; R1 zoom; Back un-zooms then closes |
| Music | Albums from tags; track order; playback through the media session; Now Playing row and screen; next track |
| Video | Player opens; music pauses during the video and resumes after |
| Launch | RetroArch stub receives the right core; ROM readable via granted URI; Recently Played updated; music pauses during the game and resumes after |

### 3.3 Emulator dispatch — 11/11 recipes verified with stubs
`tools/emulator-stub` builds a test-only APK per emulator, each installed under the real package
and activity name. Each stub logs the intent it receives and reads the ROM through the URI.

| Emulator | Component | ROM handoff | URI read |
| --- | --- | --- | --- |
| RetroArch | `com.retroarch.aarch64/…RetroActivityFuture` | `ROM`, `LIBRETRO=/data/data/<pkg>/cores/snes9x_libretro_android.so`, `CONFIGFILE` | ✅ |
| DuckStation | `…duckstation.EmulationActivity` | `bootPath` | ✅ |
| NetherSX2 | `xyz.aethersx2.android.EmulationActivity` | `bootPath` | ✅ |
| Dolphin | `…ui.main.MainActivity` | `AutoStartFile` | ✅ |
| PPSSPP | `…PpssppActivity` | `ACTION_VIEW` + data | ✅ |
| Azahar | `org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity` | `ACTION_VIEW` + data, clear task/top | ✅ |
| Eden | `dev.eden.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity` | `android.nfc.action.TECH_DISCOVERED` + data | ✅ |
| Flycast | `com.flycast.emulator/.MainActivity` | `ACTION_VIEW` + data | ✅ |
| M64Plus FZ | `org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity` | `ACTION_VIEW` + data | ✅ |
| melonDS | `me.magnum.melonds/.ui.emulator.EmulatorActivity` | `me.magnum.melonds.LAUNCH_ROM` + `uri` extra + data | ✅ |
| ScummVM | `org.scummvm.scummvm/.SplashActivity` | `ACTION_MAIN`, data = game id from the `.scummvm` file | n/a (game id, not a file) |

The six newer recipes come from ES-DE's published Android rules. ES-DE passes those emulators a
storage-access-framework URI; Xross passes its FileProvider URI with a read grant. The stubs
confirm the intents are well-formed and the URI is readable, **not** that each real emulator
accepts a FileProvider URI. That needs real installs.

Finding: no emulator could read the raw path without its own storage permission. Real
emulators are normally granted storage access; if one isn't, it must use the URI.

### 3.4 Fingerprinting (Phase 2, step 1)

How each file type is identified (`storage/Fingerprinter.kt`):

| Input | Result |
| --- | --- |
| Plain ROM ≤ 256 MB | CRC32 + MD5 + SHA-1 in one pass; header-less set too for iNES/FDS/SNES copier headers |
| `.zip` / `.7z` | Picks the member with a platform ROM extension; CRC32 from the archive header, MD5/SHA-1 by decompressing members ≤ 256 MB (7z capped at 96 MB of LZMA memory) |
| Arcade `.zip` | Recorded as a set, identified by name |
| `.chd` | SHA-1 from the v4/v5 header (no decompression) |
| ISO/BIN over 256 MB | Skipped unless Settings ▸ Library ▸ Fingerprint large disc images is on |
| `.rvz` `.cso` `.gcz` `.wbfs` `.pbp` … | Skipped: compressed containers don't match DATs |
| `.cue` `.gdi` `.m3u` | Skipped until per-track hashing exists |
| Missing file (card removed) | Not recorded; retried next session |

The queue (`data/FingerprintQueue.kt`) runs on one background-priority thread **only while Xross
is in front**. It stops on pause and resumes on return, and it re-hashes a game only when its
size or modification time changes. Results live in the `game_hashes` table (DB v2, automatic
migration from v1).

Measured on the emulator (optimized build, upgraded in place from DB v1 without data loss):

| Check | Result |
| --- | --- |
| 3,011 small ROMs (mixed library) | 4.7 s; summary: 3,006 by content, 1 arcade set, 4 skipped, each correct |
| 2,000 more ROMs added + rescan | Scan 0.18 s, fingerprinting 3.8 s |
| Scrolling a 3,000-game list while hashing | 0 slow UI-thread frames, p99 18 ms |
| Launching a game mid-job (three 1 GB ISOs) | Queue stopped immediately, 0% CPU while the emulator ran; resumed and finished on return |
| 300 MB ISO with large-image hashing on | 0.95 s on emulator storage; expect several seconds per GB on SD cards |

Observed: a rescan right after bulk-copying files reported 22 files as "updated" (their
modification times settled after the first scan), so those were re-hashed once. Harmless, but
worth watching on real SD cards.

### 3.5 Media hub (Phase 2)

| Section | What's there | Controls |
| --- | --- | --- |
| Photo | All photos + one entry per folder, newest first, with thumbnails | ←/→ photo · L1/R1 zoom · ↑↓←→ pan when zoomed · Confirm info · Start slideshow · Options set as wallpaper · Back |
| Music | Now playing, Shuffle all, All songs, Albums, Artists, with embedded album art | Confirm plays from that song · Now Playing: Confirm play/pause, ←/→ track, L1/R1 seek, Options shuffle, ↑ repeat |
| Video | All videos + folders, with thumbnails, duration and resolution | Confirm/Start play/pause · ←/→ 10 s · L1/R1 60 s · ↑/↓ previous/next · Options loop · Back |

Measured on the emulator (optimized build):

| Check | Result |
| --- | --- |
| Music service | Foreground `mediaPlayback` service with a media notification; MediaSession reports PLAYING/PAUSED correctly |
| Music during a game / video | Paused when the game or video starts, resumed when Xross is back in front |
| Scrolling 300 photo thumbnails | 0 slow UI-thread frames, 0 slow bitmap uploads, p99 17 ms |
| Memory | 58 MB PSS browsing photos; 60 MB with the music service while a game runs |
| Wave background | Hidden (GL loop stopped) under the full-screen photo viewer and video player |

Fixed during this pass: panning could expose the black border of pillarboxed photos (limits now
follow the fitted photo size and EXIF rotation); the XMB showed through Now Playing (the menu
now fades out under media screens); the Android status bar stayed visible after returning from
another app (immersive mode is re-applied on focus).

### 3.6 Phase 1b features verified on a device (emulator)

| Feature | What was done | Result |
| --- | --- | --- |
| Hot-swap | Created a real public volume (`sm partition … public`), put a ROM on it, then unmounted / mounted / unmounted with Xross in front | Toasts "Virtual SD card removed — its games are kept" and "… connected"; rescans reported +1 then −0; the open search stayed open; the game stayed listed as "Not connected"; launching it says "File not found — is the storage mounted?" |
| Playtime | Launched a game through the Flycast stub, stayed 66 s, returned | Row shows "Played 1 min"; Options menu shows "1 min · 2 times"; open search results refreshed by themselves |
| Options menu | Opened on a game in landscape and portrait | Panel, submenu with ✓ on the active emulator, breadcrumb and hints render correctly |
| Search | Typed with D-pad and hardware keys; moved to results | Word-prefix matches ("SU" → Golden **Su**n, **Su**per Metroid); correct hints per pane |
| Upgrade | Installed over a database from the previous schema (v2) | Opened and ran without error; that the data survives is asserted by the migration unit tests (2→3, 2→4), not checked by hand on the device |

Bugs found by this device pass and fixed:
- **Startup crash.** A property used during construction was declared below the `init` block,
  so it was still null (Kotlin initializes in textual order). `init` now sits at the end of
  the class, and `XmbViewModelTest` constructs the real view model so this can't regress
  unnoticed.
- Toasts were drawn under the search and Options overlays; they are now on top and opaque.
- "GBA · GBA · 1 KB": the extension repeated the platform name in cross-platform lists.
- A smoke-test check assumed the test photo folder was on screen without scrolling.

Not covered: a physical USB-OTG drive (the removable volume was the emulator's virtual disk),
and the "deferred while a game runs" hot-swap path (unit-level only).

### 3.9 Portable `Xross/` folder (Milestone 2)

**Layout and fallback.** `XrossEnvironment` resolves the root: `Xross/` on primary storage, or on
the drive chosen in Settings ▸ System ▸ Xross folder, once Xross can write shared storage (All
files access on Android 11+, `WRITE_EXTERNAL_STORAGE` on 9–10). Before that, and whenever the
chosen drive fails, it uses `files/Xross` in app storage. Every root is bootstrapped with
`.nomedia` in `media/` and `themes/` before the other folders.

**JSON configuration loader.** Measured with Robolectric on the development host (not yet on a
device):

| Item | Value |
| --- | --- |
| File sizes with defaults | `settings.json` 793 B, `controllers.json` 78 B, `systems.json` 11 KB (22 systems, including the reference `builtIn` blocks) |
| Load time | ~0.75 ms per full load of all three files on the JVM host; expect several times more on a Snapdragon 665-class handheld |
| When it runs | Once, synchronously, when the settings store is first used at startup; again off the main thread on each resume (`reloadIfChanged`, which only parses files whose text changed) |
| Memory | One `XrossSettings` object plus the last-seen text of each file (~12 KB) |
| Writes | Off the main thread on one background thread; repeated changes to a file coalesce to the newest content; `.tmp` + `fsync` + rename |
| Dependency | kotlinx-serialization-json 1.8.1 plus the Kotlin serialization compiler plugin (serialization-core was already on the classpath through other libraries) |

**Database backup (works on Android 9 and 10).** `VACUUM INTO` needs SQLite 3.27, which Android
only ships from API 30, so the backup is: `PRAGMA wal_checkpoint(TRUNCATE)` (retried while
readers block it), then `BEGIN IMMEDIATE` to hold writers off, a check that the WAL is empty
(otherwise repeat), a stream copy of the main file, its header switched from WAL to rollback
journal mode so it stands alone, a read-back check (`user_version` and game count), and a rename
over `Xross/data/xross.db`. It runs 4 s after Xross leaves the screen when a library table
changed, or from Settings ▸ System ▸ Back up library now. Restores are copied next to the live
database and applied on the next start, before Room opens it; the live database is never
replaced while open. Verified with Robolectric only.

### 3.10 Library organization (Milestone 3)

| Feature | How it works |
| --- | --- |
| Hidden apps | Package names in `settings.json` `apps.hidden`; filtered from Apps and Games ▸ Android Games. "Hide system apps" uses `ApplicationInfo.FLAG_SYSTEM` (updated system apps included) |
| App folders | `apps.folders` maps package → folder. Presets need no typing; other names come from the gamepad keyboard (capitals only, tidied to title case except short acronyms) |
| Base ROMs folder | `library.baseRomsFolder`; empty means `roms/` in the Xross folder. Scanned first; auto-detected and added folders unchanged. Create system folders makes `<base>/<id>/` for the 22 systems and never touches existing files |
| Custom Photo/Video folders | `photo.customFolders` / `video.customFolders`. MediaStore queried with `_data LIKE '<folder>/%'`; a breadth-first walk (8 levels, 20,000 files, no symlinks) adds files MediaStore skips. Walked files get negative ids and FileProvider URIs; their thumbnails are decoded here (EXIF-rotated photos, a video frame) |
| Art in `Xross/media` | `<kind>/<system>/<rom stem>.<ext>`. Stems lose `\ / : * ? " < > \|` and control characters, trailing dots and spaces, reserved device names are prefixed, and length is capped at 200. On a clash within a system the oldest game keeps the plain name and the others get `[<first 8 of UUID>]`. Lookups try a few names × 4 extensions (a handful of `stat` calls). Prune deletes only art of games that left the library |
| Moving the old cache | After the library first loads, `noBackupFilesDir/assets/<uuid>/…` is copied file by file into the new layout in the background; the old copy is still found until moved; art already in place wins |

### 3.11 Device run: Milestones 1–3 on the Galaxy A55 (2026-11-11)

Samsung Galaxy A55 (SM-A556B, Android 16, 120 Hz), internal storage only, no controller, no
emulators installed. The new build was installed **over the existing install** (12 arcade sets,
2 3DS images, scraped art, settings from the earlier pass), so the upgrade paths ran on real data.
Driven with adb key events (the same codes a controller sends) and checked through the
accessibility tree, the files on the device and screenshots. `smoke.sh` was **not** run: it
deletes `/sdcard/ROMs` and clears the app's data, and this phone holds the earlier test library.

| Area | Check | Result |
| --- | --- | --- |
| Upgrade | SharedPreferences → `config/settings.json`, `controllers.json`, `systems.json` | ✅ Non-default values carried over (Original theme, 45% deadzone, 100% effects volume, remembered `ROMs` folder); old preferences cleared after the files were written |
| Upgrade | Private art cache → `Xross/media` | ✅ All 41 files moved to `capsules/`, `heroes/`, `wheels/` under `arcade/` and `n3ds/`, named after the ROMs; old cache removed; box art, hero banner and logo still shown on rows and in Details |
| Xross folder | Layout and `.nomedia` | ✅ `config/ data/ dats/ media/ roms/ themes/`; `.nomedia` in `media/` and `themes/`; 0 MediaStore images under `Xross/` |
| Credentials | Not in config files | ✅ No account, password or key in any of the three files |
| Backup | On leaving Xross / Back up library now | ✅ `data/xross.db` appeared seconds after Home; rollback-journal header, `integrity_check` ok, schema v5, 14 games and 14 metadata rows, no `-wal`/`-shm` beside it; "Last backup" time updates |
| Restore | Settings ▸ System ▸ Restore library backup… | ✅ Confirm menu, then a new process; staged copy applied before Room opened it; library and favorite intact |
| Outside edits | `photo.customFolders` edited with adb, then Xross resumed | ✅ Picked up on resume |
| Custom Photo folder | A folder with `.nomedia` (unknown to MediaStore) | ✅ Photo showed only that folder (1 photo); the viewer opened it at full size through its FileProvider URI; "Show all photos again" restored all 2,138 photos |
| Base ROMs folder | Create system folders | ✅ 22 folders in `Xross/roms`; a second run reports them all present; a ROM dropped into `gba/` is picked up by Rescan (+1) and dropped when removed (−1) |
| Apps | Hide, Hidden apps, Hide system apps | ✅ Hidden app gone from Apps and stored in `apps.hidden`; shown again from Hidden apps; Hide system apps hides 22 preinstalled apps and is reflected in the file |
| Apps | Folders | ✅ Move to folder ▸ Tools puts a Tools folder at the top of Apps; New folder… opens the gamepad keyboard, "MY GAMES" is saved as "My Games"; Remove all app folders clears them |
| All Games | Platform folders setting | ✅ Classic layout lists Nintendo 3DS and Arcade at the top of Games; switching back restores All Games |
| Deadzone | Off — hardware only | ✅ 45 → 55 → Off (`stickDeadzone: 0.0`) → … → 45% |
| Media shortcut | Select, Guide | ✅ With nothing playing: "Nothing is playing" toast. With music: Select opens and closes Now Playing from Games; a held Guide (`--longpress KEYCODE_BUTTON_MODE`) opens it; a Guide tap does nothing |
| Mini-player | Paused line | ✅ Present ~20 s after pausing, gone at ~34 s, back with ♪ on resume |
| Launching | No emulator installed | ✅ "No emulator for 3DS — install Azahar"; Xross stays in front |
| Search | Typing "ZEL" | ✅ 1 game, The Legend of Zelda Ocarina of Time 3D |

Performance (`profiling` build, installed over the debug build with data kept):

| Measure | Result |
| --- | --- |
| Cold start (`am start -W`) | 1,050 ms on the first start after install, then 457 ms and 377 ms |
| Frames: Apps list (60 steps down and up), arcade list with art, category switches | 686 frames, 1.46% janky, p50 13 ms, p90/p95 18 ms, p99 23 ms, 0 missed vsync, 0 slow bitmap uploads |
| Memory in front | 211 MB PSS |
| Memory in the background (8 s after Home) | 122 MB PSS, 0% CPU (target ≤ 150 MB). Higher than the 83 MB measured earlier, likely because the music session and a 72-song queue had been loaded during this run |

Not covered: moving the Xross folder to an SD card or USB drive (no removable drive), a
controller, launching a real emulator, Android 9/10, the restore offer on a genuinely clean
install, video folders, and picking folders through the system folder picker (folders were set
by editing `settings.json`).

### 3.12 Status bar profile and floating mini player (2026-11-11)

Unit-tested (§3.1), then run on the **Galaxy A55** (debug build installed over the existing data,
2026-11-11):

| Check | Result |
| --- | --- |
| Status bar avatar | ✅ Silhouette placeholder before the clock; Up from Search focuses it (white ring); Down returns |
| User Card (not linked) | ✅ Opens on Confirm: "Guest", "No RetroAchievements account linked", Link account… / Close, Enter/Esc hints |
| Link dialog | ✅ Opens from the card. In landscape the keyboard used to take the whole screen and hide the dialog; the fields now ask for no full-screen editor and the dialog scrolls, so the key field comes into view above the keyboard |
| Mini player card | ✅ After Shuffle all: album art, bold title, artist cut with "…", ▶ glyph, above the hint pill; stays when switching Music → Games |
| Long press | ✅ Pauses (glyph turns ❚❚) |
| 30 s hide | ✅ Still shown 25 s after pausing, gone at 36 s; back at once on resume (media play key) |
| Crashes | None in logcat |

Not covered on the device: linking a real account (no test account; the API host for images,
`media.retroachievements.org`, is therefore unconfirmed), the avatar download, the User Card with
profile data, tapping the card to open Now Playing, and a long title scrolling.

| Feature | How it works |
| --- | --- |
| Linking | Settings ▸ Accounts ▸ RetroAchievements account… opens one system dialog (user name, masked Web API key). The pair is checked with `API_GetUserProfile` first and saved only if it works, so a typo never replaces a working account. An empty key keeps the saved one for the same user; an empty name unlinks |
| Secrets | The key is stored only in `CredentialStore` (`xross_credentials`, app-private, excluded from cloud backup and device transfer), next to the scraper accounts. It is sent only as the `y` parameter to `retroachievements.org`, never logged, never put in an error message. Not encrypted at rest yet |
| Profile cache | `Xross/data/retroachievements.json`: user name, avatar path, points, RetroPoints, RetroRatio, rank, recent unlocks, fetch time. Read at startup, so the profile shows offline; refreshed in the background when older than 6 h (on start and on resume), and from the User Card or Settings |
| Rank and unlocks | Rank comes from `API_GetUserSummary` (`g=0&a=0`) and the last 30 days of unlocks from `API_GetUserRecentAchievements`; if either fails the profile still updates without it |
| Avatar | `Xross/media/avatars/<user>.png`, downloaded to a `.part` file and swapped in atomically, only from `*.retroachievements.org` over HTTPS. A failed download keeps the old picture; with none the silhouette vector shows. "Clear downloaded artwork" leaves avatars alone |
| Status bar | 32 dp round avatar before the clock and battery. Up from the first row of a category root moves the focus onto it (ring and breathing glow); Confirm or △ opens the User Card, Down or Back returns, Left/Right switch category. Tapping it opens the card too |
| User Card | Avatar, name, rank "#4,616 of 45,654", points, RetroPoints, RetroRatio, softcore points, motto, up to four recent unlocks, last update. Buttons: Refresh · Unlink (press twice) · Close, or Link account… · Close when nothing is linked |
| Mini player card | `MiniPlayerCard` at the root of `XmbScreen` (outside the menu fade), bottom-right, 16 dp from the edges and 8 dp above the measured button-hint row. Album art is the track's embedded cover through MediaStore (the same source Now Playing uses), with a music-note fallback. Hidden under full-screen media, Search, Details, Information, Options, the name keyboard, the User Card, remapping and the game start. `MiniPlayerVisibility` runs the 30 s pause timer |

---

### 3.13 Live services, achievements, theme packs and wave settings (2026-11-11)

**Unit tests added (42; suite 349 → 391):**

| Suite | Tests | Covers |
| --- | --- | --- |
| `AchievementsTest` | 15 | `API_GetGameInfoAndUserProgress` and `API_GetGameList` parsing (map or empty-array achievements, display order, hardcore dates, completion, points, lower-cased hashes); the key only in `y` and only to retroachievements.org; matching per system (full MD5, header-less NES/SNES, arcade set-name MD5, exact title for disc systems, hacks and subsets excluded, an unlisted hash never falls back to the title); repository caches in `Xross/data/ra` with no key in any file, console list reuse, offline copy, NotLinked / Unsupported / NoMatch, re-match after the file changes, badges in `media/ra/badges` under `.nomedia`, clear on unlink; badge-grid navigation |
| `UnlockToastTest` | 3 | Only unlocks newer than the watermark are news (never an account's history), the watermark persists in `retroachievements.json` (no key) so a restart doesn't repeat toasts, one-at-a-time queue without duplicates |
| `ThemePackTest` | 11 | Default pack has every icon and sound; fixed audio roles (`cursor.wav` for D-pad moves, `01. opening.wav` at startup, `system_ng.wav` for errors; no `snd_error`/`coldboot` in the APK); folder pack discovery; partial packs inherit sounds, gains and icons; case-insensitive lookups; PNG icons downsampled; palettes; path traversal refused; runtime fallback to Default; `.nomedia` in `themes/`; SVG reader (viewBox, translate, black backdrops dropped, basic shapes, junk rejected) |
| `WaveSettingsTest` | 9 | Wave uniforms round-trip through `settings.json`, per-field clamping, editor rows and drift-free steps, blocked ends, tint cycling, reset to pack defaults, slider fractions, presets and custom gradient, Theme rows, backdrop timing (250 ms / 1.2 s / 300 ms ramp) |
| `XmbViewModelTest` | +2 | Wave settings panel driven by D-pad on the real view model, saved to `settings.json`; refused actions leave the menu unchanged |
| `RetroAchievementsLiveTest` | 2 (opt-in) | Account sign-in; with a Web API key, linking, avatar, an arcade game's achievements and badges, no key on disk |
| Others updated | — | `OptionsMenuTest` (Achievements row), `MenuSettingsTest` (Theme pack row first), `ConfigCodecTest` (speed clamp 0.5–2.0), `ScreenScraperLiveTest` (developer credentials alone, media cached through `AssetCache`) |

**Live services** (`-PliveScrape=true`, `.env` credentials):

| Service | Result |
| --- | --- |
| ScreenScraper | Developer credentials only (no user account in `.env`, so the account check is skipped). 5 of 6 samples identified (3 arcade sets by hash, 2 of 3 3DS games by name search; *Ocarina of Time 3D* not found by name); all 5 with descriptions; box art and video snaps for all 5 cached through `AssetCache` into `media/3dboxes/<system>/` and `media/snaps/<system>/` with `.nomedia` written first; quota block read (10 of 10,000 requests, 0 of 1,000 not-found), pacing held at 1.2 s; 26.8 s for the run |
| SteamGridDB | 8 of 8 samples got art |
| RetroAchievements | The `.env` account signs in (`dorequest.php?r=login2`, POST body; token never printed). Linking, profile, achievement lists and badges ran live later the same day once the Web API key was in `.env` (§3.14) |

**Galaxy A55 (Android 16, 120 Hz), debug build installed, `MANAGE_EXTERNAL_STORAGE` granted:**

| Check | Result |
| --- | --- |
| Icons from the Default pack's SVGs, avatar ring, wave | ✅ Rendered (screenshot check) |
| Smoke test | ◐ 75 of 86. The 11 failures are all in Photo and Music: they expect the seeded `XrossSmoke` media to be the only media, and this phone has its own photos and music library. Every library, Options, Achievements, Search, Settings, Theme pack, Wave settings, secrets, deadzone, remap, rescan, video and touch check passed |
| Theme pack from `Xross/themes` | ✅ (smoke) found after "Look for new packs", palette and wave defaults applied, back to Default |
| Wave settings panel | ✅ (smoke) sliders change the live wave; values in `settings.json` |
| Achievements screen | ✅ (smoke) opens from Options, asks to link when no account is linked |
| Secrets | ✅ (smoke) no account names, passwords or keys in `Xross/config/*.json` |
| Frame pacing, profiling build, 30 s of category switches and list scrolling | ✅ 0.63% janky (23 of 3,629 frames; target ≤ 1.5%); p50 13 ms, p99 22 ms; GL thread p50 8.3 ms, p95 ≤ 10.6 ms at 120 Hz |
| Memory | ✅ 82.9 MB PSS in the background (target ≤ 150 MB); 180–185 MB in front with the media hub loaded |
| Idle CPU in front | ◐ 100–130% of the 8-core total, the same as the previous commit measured the same way (A/B on the profiling build): the UI redraws about 85 times a second for the focus pulse and the wave renders continuously. The new GL pause only applies while hero art or a snap covers the screen; this library had no scraped art to show it |

**Bugs found and fixed on the device:**

1. **One Back popped two levels** for `KEYCODE_BACK` (many controllers' and remotes' back
   button): with predictive back (target SDK 36), the key reached both the gamepad handler and the
   back callback. The callback now ignores a back within 400 ms of a consumed `KEYCODE_BACK`.
   Gamepad B and the back gesture were not affected.
2. **`smoke.sh` was out of date:** Up at the top of a category root now focuses the status-bar
   avatar, so `top` needs a Down afterwards; settings in `Xross/config` survive `pm clear` and
   leaked between runs (now moved to `/sdcard/Xross-smoke-backup/` first); the Default mapping
   row's subtitle shows only when focused.

**Not verified on hardware:** an unlock toast after a real emulator session, achievement lists
from the live Web API, hero art and video snaps behind the menu on a scraped library (the smoke
library has no scraped art), the audio ramp, a custom font from a pack.

### 3.14 Live run with the full test library: emulator and Galaxy A55 (2026-11-11)

Library: 370 arcade sets, 2 PSP ISOs (1.4 GB), 3DS images, and one album twice (60 FLAC + 60 MP3
tracks with identical tags). Emulator: a dedicated `xross_test` AVD (API 36, arm64, 16 GB data,
1080×2400 landscape). Phone: Galaxy A55 (arcade and PSP only; no test music on a personal phone).

**Live services** (`.env`: ScreenScraper developer + user account, RetroAchievements user, password
and Web API key):

| Check | Result |
| --- | --- |
| ScreenScraper account | ✅ Signed in; 20,000 requests/day; quota shown in Settings ▸ Metadata on both devices |
| `ScreenScraperLiveTest` | ✅ 7 of 8 samples (arcade by hash, 3DS and PSP by name; *Ocarina of Time 3D* not found by name); descriptions, box art and video snaps cached |
| RetroAchievements sign-in | ✅ `RetroAchievementsLiveTest`: login, then linking with the Web API key, avatar cached, no key in any file |
| RA game matching | ✅ 1941 and Street Fighter II by set name (28 and 32 achievements), Tekken 6 by title (65); OutRun 2006 is on RetroAchievements without a set and now shows "no achievements yet" (game lists include games without a set) |
| Debug builds link RA from `.env` | ✅ Avatar in the status bar on first start on both devices; never under Robolectric |

**On both devices:** library scrape (hero art, box art, logos, descriptions, video snaps; stopped
by request after about 100 games on each, at 5–6 s a game with three services and snaps), User
Card, Achievements screen (badge grid with D-pad browsing, detail bar, points, completion),
Details summary with "▶ Achievements", focused-game hero art then video snap. On the emulator the
GL wave rendered no frames while art or video covered the screen and resumed after. FLAC and MP3
copies play (`c2.android.flac.decoder` / `c2.android.mp3.decoder`; Now Playing advanced).

**Found and fixed:**

1. **Two copies of an album merged into one with every track twice.** MediaStore gives identical
   tags one album id. Albums are now split by folder and labelled by format ("… · FLAC",
   "… · MP3"; the folder name when formats match); songs in All songs say which copy they are.
2. **Descriptions showed `&quot;`** and other HTML references from ScreenScraper; decoded when
   scraped and when shown (already-scraped games are fixed too).
3. **Games without an achievement set looked "not recognized".** Game lists now include them
   (cache format v2; older caches and matches are fetched again).
4. **Badges could stay blank** when a grid asked for dozens at once during a scrape: at most 4
   downloads at a time with a retry, and a failed cell tries again.
5. **Badge grid showed 1⅓ rows on 20:9 screens:** cells are now sized so 2½ rows fit (10 columns).
6. **User Card:** "10 POINTS" ran into "10 RETROPOINTS" (wider spacing); the menu showed through
   the card (now fades like under Details).
7. **Unit tests would have linked the tester's real RA account** through the debug `BuildConfig`
   (caught by `ProfileCardTest`); the pre-fill skips Robolectric.
8. `.env`: the ScreenScraper password key was misspelled (`SREENSCRAPER_…`); renamed. The build and
   live tests also accept `SCREENSCRAPER_USER_PASSWORD` and `RETROACHIEVEMENT_USER_API`.

Not covered: an unlock toast (needs achievements earned in an emulator), 3DS has no
RetroAchievements, and the two largest 3DS images (6 GB) were not copied to the emulator.

### 3.15 Phase 2b.1: profile HUD, game tiles, Details paging, RetroAchievements dashboard (2026-11-11)

Built in `2c142d6` from the Phase 2b specification (now Roadmap §4.3 and Appendix A). Verified by
unit and view-model tests only (§3.1); **no device run yet**.

| Feature | How it works |
| --- | --- |
| Avatar mini-HUD | `AvatarHud` at the far right of the status bar. Up from the first row of a category root, or a tap, sets `avatarFocused`; the container morphs in place into a frosted capsule (user, rank, hardcore/softcore points, recent badges) while the status bar fades its clock and battery with the same value. The expansion is read only in layout and draw, so nothing else re-measures or recomposes. Down, Back or a tap collapses it; Confirm refreshes the profile (or starts linking); Options opens the User Card; Left/Right switch category. A stale profile refreshes quietly on open |
| Game tiles | `GameTile`: 16:9 plate with the clear logo (`wheels`), else wide hero/capsule art, else the title on a frosted plate |
| Playtime subtitle | `MenuTree`: "Never played", "Playtime: N mins" under an hour, "Playtime: Xh Ym" above; a session under a minute counts as one |
| Details paging | `GameDetails` pager over the list Details was opened from; L1/R1 and Left/Right with a slide and crossfade; stops at both ends; the list cursor follows |
| Game telemetry | Lower right above the button hints: unlocked/total, points, mastery ribbon at 100%. Only for games matched to a RetroAchievements set with achievements; hidden under full-screen overlays, search, panels and while the avatar HUD is open |
| Profile customization | `profile.avatarStyle` (`AvatarStyle.kt`): border color presets or custom, width 1–6 dp, glow on/off, speed 0.5–2×, intensity 0.1–1, clamped on load. `AvatarStylePanel` previews live and saves at once. The glow's infinite transition stops when the screen is idle or covered |
| RetroAchievements dashboard | `RaHubScreen` from Games ▸ RetroAchievements: header card (avatar, user, rank, member since, points, RetroRatio), library completion gauge, mastery showcase, recent activity feed. Built from the cached progress in `Xross/data/ra`; placeholder states when offline or not linked |

**Emulator run (2026-11-12, API 37, 60 Hz):** `smoke.sh` 112/112, now covering the avatar HUD
(Up from the top row and back), the playtime subtitle, Details paging (Right, R1, L1, both ends,
cursor follows), the dashboard and Profile customization (saved to `settings.json`). The HUD and
the 16:9 title plates were also checked in screenshots.

**Idle rest (2026-11-12):** `InactivityTracker` marks the screen idle after 3 s without key, nav or
touch input, unless music or a video snap plays. While idle the focus pulse and avatar glow ease to
a resting value and stop (`rememberBreathing`), and the wave drops to ~20 fps; in use it is capped
near 60 fps on panels above 90 Hz. Both rates are paced on vsync by a Choreographer callback that
wakes once per wave frame. Resting CPU on the emulator: 21.9% before, 2.5–2.8% after.

**Galaxy A55 (2026-11-12, Android 17, 120 Hz, profiling build, real library):**

| Measure | Before (`cddf975`) | After |
| --- | --- | --- |
| Process CPU resting on the Games root | 114–124% | 20 fps standby: 13–19%, 12% after a few minutes. 12 fps standby (tried, reverted as sluggish): 9.9–11.6% over 20 s windows, 7.9% over 30 s (GL thread 3.7%, main 2.3%, binder 1.3%) |
| Process CPU while pressing keys | 79–114% | 105–108% |
| Wave in use | 120 fps | 60 fps: p50 16.7 ms, p95 18.2–18.8 ms, 1–3 missed per 300 |
| Wave at rest | 120 fps | 20 fps: p50 50.0 ms, p95 51.9 ms. 12 fps (tried): p50 89.4 ms, p95 93.3 ms against 83.3 ms, so about half the frames land one vsync late |
| UI while in use | | 0.27% janky, p50 7 ms, p95 9 ms; 0 UI frames per 5 s at rest |

Screenshots on the A55: the HUD expands with the clock and battery hidden; logo tiles with
"Never played"; Details paging Chrono Trigger → EarthBound with art and facts changing, and the
list cursor on EarthBound after Back; the dashboard with the live profile and library completion
(1 of 377 supported games checked so far). Two layout bugs found and fixed: the Details hints
wrapped "Scroll" mid-word at 2340 px (now a `FlowRow`, and hint labels never wrap), and the
dashboard's identity card cut off its "Updated …" line at 1080 px tall (now pinned at the bottom,
with the identity block above it scrolling if needed and slightly tighter spacing).

**Not verified:** whether the HUD morph and the Details crossfade animate without jitter (checked
by screenshot only, not by eye); L1/R1 on a physical controller; the < 10% idle-CPU target is not
met (12–19% with the 20 fps standby kept); the dashboard with many achievements.

### 3.16 Phase 2b.2, slice 1: Theme 2.0 manifest, surfaces, hot reload (2026-11-12)

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

### 3.17 Phase 2b.2, slice 2: layout, motion, focus divider, drill-down (2026-11-12)

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

### 3.18 Phase 2b.2, slice 3: variants, overrides, `.xtheme` import (2026-11-12)

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
and 4:3 only the left one fits, as designed in §3.17). The stress pass found one clamp bug, fixed
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

### 3.19 Phase 2b.3: game-art palette, context rules, circadian curve, battery tint, quick profiles (2026-11-12)

On branch `feat/theme-2b3` (from `feat/theme-2b2`, not committed yet). Spec: Roadmap §4.3,
Appendix A.4, A.7, A.13, docs/THEME_RICING_AND_WEB_STUDIO.md §3.4. Everything not yet verified is
listed in [`PHASE_2B3_PENDING_TESTS.md`](PHASE_2B3_PENDING_TESTS.md).

| Feature | How it works |
| --- | --- |
| Game-art palette | `androidx.palette:palette-ktx` 1.0.0. `GamePaletteExtractor` (app-scoped in `AppContainer`): the focused game's art (or the open Details game's), keyed `uuid/artVersion`, debounced 250 ms (the hero fade delay) with `collectLatest`, decoded to ~192 px from `media/heroes`, then `wheels`, then `screenshots`, and analyzed on `Dispatchers.Default`. Results, including "no usable colors", sit in a 100-entry LRU cache. `GameColors.from` maps vibrant (or dominant) to `accent`, light-vibrant (or muted) to `accentSecondary`, dark-muted (or the dominant darkened to ≤ 35% value) to `canvas`. All-grey art or a glow under 3:1 contrast against the canvas falls back to the theme. Guarded by `effects.adaptiveGamePalette` (default on) and Settings ▸ Theme ▸ **Adaptive colors** |
| Morphing | The wave eases to the new colors on the GL thread (~95% in 300 ms, the 2b.2 crossfade). In Compose, `ThemeAccent` holds animated colors read in the draw phase: the avatar's focus halo and style glow (leaning 60% toward the art accent), and the status-bar battery outline. Glow-text bloom reads it in its own small `GlowBloom` composable. Halos stay white unless a color layer is active, so plain themes look as before. `AdaptiveWave` collects art colors, battery and clock, so those updates recompose it and not the screen |
| Context rules | `rules: [{match, apply}]` (`ContextRule`) inline or from `rules.json` in `imports`; rules from every file add up (imports first). Selectors: `category`, `system` (platform id or folder alias), `battery` (`<= 20`, `<`, `>=`, `>`, `==`, `charging`, `discharging`, `full`; also read as `battery.level`), `time` (`HH:MM..HH:MM`, wraps midnight, end excluded). `apply`: palette roles and the `circadianCurve` / `batteryTint` switches, `effects.surfaceStyle` / `adaptiveGamePalette`, `cardAspectRatio`, any `layout` key. Unreadable selectors are reported and the rule dropped. Static tile-shape rules fold into the pack layout at load; dynamic ones produce a per-context `ThemeLayout` (`LocalThemeLayout`). At most 64 rules |
| Precedence | Default pack → pack → variant → category override → system override → rules in file order (`cascade`), then the user's theme color, the quick profile, the circadian curve and battery tint, then game art (`ColorPipeline`). OLED True Black keeps its black canvas under game art, and a low battery still shows over art. `LoadedTheme.look` is cached by the set of rules that hold, and battery and time reach it only when that set changes (`rememberMinuteOfDay` checks once a minute) |
| Circadian curve | `Circadian`: phases 06 cyan/sky (hue 192), 11 azure/cobalt (214), 18 amber/rose gold (22), 21 indigo/midnight (255), built with the Original theme's `SeasonalPalette.paletteOf`, smoothstep-blended ±45 min around each boundary, mixed 50% into the canvas, 35% into the wave and 20% into the glow. `palette.circadianCurve` / Settings ▸ Theme ▸ **Circadian curve** |
| Battery tint | `BatteryMonitor` (sticky `ACTION_BATTERY_CHANGED`) in `XmbViewModel.battery`. ≤ 20% unplugged: glow and wave shift toward amber, reaching red at 5%. Charging: a green lean plus a 2.4 s brightness pulse of the sheets (1.00–1.18×) on the GL thread, resting with the wave. `palette.batteryTint` / Settings ▸ Theme ▸ **Battery alert tint** |
| Quick profiles | Settings ▸ Theme ▸ **Quick profile** (`display.quickProfile`). `OLED_TRUE_BLACK`: `#000000` canvas and the `OLED_TRUE_BLACK` surface. `Y2K_CYBER_PASTEL`: `#1B1829` canvas, `#C8B6FF` lilac, `#FFB5C5` strawberry, `Y2K_GLOSSY_BEZEL`. `MONOCHROME_EINK`: black and white with `EINK_HIGH_CONTRAST`, motion `INSTANT`, marquee off, `LocalStillMotion` freezing `rememberBreathing` (focus pulse, avatar glow), the busy dots and the search caret, and the wave in `WaveLoop.SINGLE_FRAME` (`RENDERMODE_WHEN_DIRTY`, one frame per change, no time drift). All color layers are off under E-Ink. A profile's surface wins over Surface style |
| Settings | `display.quickProfile`, `display.adaptiveGamePalette`, `display.circadianCurve`, `display.batteryTint` (`null` follows the pack) in `settings.json`. Four rows after Surface style; each switch's Options offers On, Off and Theme pack default |

**Unit tests (495, 0 failures; 31 new):** `ContextRulesTest` (9): time ranges, wrapping and
bounds; battery comparisons and charger states; category, system alias, battery and time rules
holding only in context; later rules winning over the variant and the overrides; `rules.json`
imports adding up; bad rules dropped with reasons; tile shapes and layout patches; rule switches;
no rules in Default. `ColorIntelligenceTest` (14): the four circadian phases and hues, a
never-more-than-8/255-per-minute blend all day, battery amber → red → green, swatch mapping and
fallbacks, the precedence order pack → variant → rule → user → profile → game art, switch
resolution, the ambient-under-art order, OLED/Y2K/E-Ink definitions, E-Ink's `INSTANT` motion and
`SINGLE_FRAME` wave loop at every stride, the `settings.json` round trip, and the Settings rows.
`GamePaletteExtractorTest` (6): the debounce (four games scrolled at 100 ms extract only the
last), cache hits on revisits and for unusable art, LRU eviction at 100, extraction on the worker
dispatcher and on a real worker thread, and real `androidx.palette` on a bitmap (Robolectric
native graphics). `StillMotionTest` (2, Compose): the pulse breathes normally and holds exactly at
rest under E-Ink.

**Emulator (API 37, debug build, 2026-11-12): `smoke.sh` 164 of 171.** New checks, all passing:
the four rows and their defaults; profile cycling and saving; OLED's `#000000` canvas and Y2K's
`#1B1829` / `#FFB5C5` (from the debug-only `XrossColors` log); E-Ink switching the wave to
`SINGLE_FRAME` and back to a loop on leaving; color rows held off under E-Ink; each switch
toggling and saving; the circadian curve recoloring; `dumpsys battery` at 12% producing the tint
and `status 2` the charging pulse. In "Game-art palette", two SNES games got seeded hero PNGs and
were marked scraped in the database. Chrono Trigger's blue banner gave a `#060F29` canvas, and
EarthBound's green one replaced it with `#062912`. A game without art fell back to the theme.
Revisits made 2 extractions for 4 visits, on `DefaultDispatcher` workers. A game scrolled past in
under 250 ms was never extracted. The 7 failures are in older sections: Recently Played/import
checks hit by timing and a leftover stub (script fixed, not rerun), the "Buttons remapped" toast
(A/B against the base build: 1 of 4 on both, a pre-existing flake), and music still BUFFERING
after 1.5 s.

**Benchmarks:** extracting one 320×180 hero took 3.3 ms on the emulator; 10 extractions of
640×360 heroes averaged 6.2 ms (max 31.3 ms), all off the main thread. **Frame pacing of game
navigation (target 0–1.5% janky on the profiling build) was not measured.** The only pass that
reached the list read 96.7% janky with a 117 ms median while the emulator's own system process
was not responding ("Process system isn't responding"), so it is discarded. The adaptive-off
baseline didn't run. Procedure in the pending-tests file.

**Not verified:** frame pacing; anything on a phone, handheld or E-Ink panel; the colors by eye
(only through logs); a real battery crossing 20%; Android 9–13; wheel-only art; hot reload of
time and battery rules; the display matrix with the new rows.

## 4. Display matrix & performance

All numbers are from the Android emulator on an Apple M5 with the host GPU, using an
R8-optimized, AOT-compiled build (`profiling` build type). Treat them as relative, not as a
substitute for real low-end hardware.

### 4.1 Aspect ratios and resolutions — all pass

Each configuration scripted 4 down, 4 up, then L1/R1, running live without restart.

| Device class | Resolution @ dpi | UI janky frames | UI p90 / p99 | GL wave p95 / missed |
| --- | --- | --- | --- | --- |
| 4:3 retro handheld | 640×480 @160 | 0.5% | 19 / 24 ms | 18.5 ms / 0 |
| 4:3 | 1024×768 @240 | 0% | 19 / 23 ms | 18.9 ms / 2 |
| 5:3 | 800×480 @160 | 0% | 19 / 23 ms | 18.8 ms / 0 |
| 1:1 | 720×720 @240 | 0% | 19 / 21 ms | 19.1 ms / 1 |
| 3:2 | 1080×720 @240 | 0% | 19 / 22 ms | 18.9 ms / 1 |
| 16:9 small | 1334×750 @280 | 0% | 16 / 17 ms | 18.7 ms / 0 |
| 16:9 FHD | 1920×1080 @320 | 0.5% | 19 / 25 ms | 19.0 ms / 1 |
| 16:10 | 2560×1600 @320 | 0% | 19 / 24 ms | 19.0 ms / 2 |
| 20:9 phone | 2400×1080 @420 | 0.5% | 16 / 23 ms | 18.7 ms / 0 |
| 21:9 | 2560×1080 @400 | 0% | 19 / 24 ms | 18.9 ms / 1 |
| 4K TV | 3840×2160 @640 | 0% | 20 / 24 ms | 20.1 ms / 2 |
| Portrait phone | 1080×2400 @420 | 0% | 16 / 17 ms | 18.6 ms / 0 |

Re-run on 2026-11-12 for Theme 2.0 with `tools/device-tests/display-matrix.sh`, with the Default
pack and a stress pack; layout results and the day's frame-timing noise are in §3.18.

Layout fixes from this pass:
- On 4:3, 1:1 and 3:2 the left neighbour category was half off-screen. The focus column now
  always leaves room for one whole category.
- Portrait had tiny text and a large gap between the bar and the list. Vertical spacing now
  follows the short side, and the number of composed rows matches the screen.
- On 1:1, button hints overlapped long titles. The hint row now has a translucent backing.

### 4.2 Animation speed, lag and stutter

| Animation | Timing | Notes |
| --- | --- | --- |
| Cursor scroll, category slide, drill-down | Critically damped spring (stiffness 520), ~200 ms settle | Replaced tweens: a held D-pad now glides instead of restarting from zero velocity each step |
| Focus glow pulse | 1.7 s ease in/out, reversing | Only layer alpha animates, so no text re-layout |
| List enter | 280 ms slide + fade | |
| Cold boot fade-in | 1.4 s after 350 ms | Once per process start |
| Game start | 900 ms darken + bloom, hand-off at 1.1 s | Menu darkens before the bloom so titles don't overlap |
| Xross | Time advances in whole vsync periods | GL thread holds 60 fps in every configuration |

Held-scroll measurements in a 3,000-game list (one key every 60 ms, release build):

| Variant | UI janky (frame deadline) | Slow UI-thread frames | Legacy deadline missed |
| --- | --- | --- | --- |
| Tween (before) | 11–14% | 0 | 1–12 |
| Spring + narrower recomposition (after) | 9.4–11% | 0–1 | 0–3 |
| Spring, wave disabled (control) | 6.6–8.5% | 0 | 1–3 |

The app's own frame work stays within budget; almost all remaining "deadline missed" frames
are late presentation by the emulator's compositor, plus the `cmd input` process spawned for
each key. About 2–3 points come from compositing the GL surface. Idle and category switching
are at 0–0.5%. **This must be re-measured on real target hardware (Snapdragon 665 /
Unisoc T618) before calling it final.**

### 4.3 Scope non-functional targets

| Target (scope §5) | Result |
| --- | --- |
| ≥ 60 FPS menu navigation | ✅ 🧪 UI median 16 ms; GL p50 16.7 ms everywhere |
| 120 Hz on high-refresh panels | ✅ Galaxy A55 at 120 Hz: 0.63–1.46% janky, no missed vsync (§3.11, §3.13); no 120 Hz handheld yet |
| Input-to-screen ≤ 16.6 ms | ❓ Not measured (needs high-speed capture on hardware) |
| Cold boot to interactive < 2.5 s | ✅ First frame ~200 ms on the emulator, 377–1,050 ms on the Galaxy A55 (`am start -W`); the fade-in adds 1.75 s by design |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 42 MB PSS in the background on the emulator; 83–122 MB on the Galaxy A55 |
| Suspend GL on screen-off / app in background | ✅ No GL frames and 0% CPU while an emulator is in front |
| Daemon CPU < 3% idle | n/a (no daemons yet) |

---

## 5. Known limitations and risks

1. **Little hardware testing yet.** One phone (Galaxy A55) has run Xross. Physical controllers
   (DualSense, Switch Pro, built-in pads), analog sticks, real emulator installs, SD cards,
   Android 9/10 and low-end GPUs are all untested.
2. **Multi-file disc images over URIs.** A single `content://` URI for a `.cue` doesn't give
   access to its `.bin` tracks. Emulators handle these through their own storage permission
   and the path extra.
3. **`MANAGE_EXTERNAL_STORAGE`** works for sideload and F-Droid distribution but is a
   Google Play review risk.
4. **Bundled sounds and icons** look derived from Sony's PS3/PSP assets. That's fine for
   personal use, but they should move to a user-supplied theme pack before any public release.
5. **The Default pack's cursor sound is the options tick** at a lower gain. The silent
   `snd_error.wav` is gone: errors use `system_ng.wav` (§3.13).
6. Robolectric tests run on SDK 35 because SDK 36 sandboxes need Java 21.
7. **Playtime is wall-clock time away from the launcher** after a launch, capped at 24 h per
   session. Time in another app reached from the emulator, or with the device asleep, counts.
8. **Search matches word prefixes only** ("tri" finds "Chrono Trigger", "rono" does not), and
   the on-screen keyboard is Latin letters and digits.
9. **The RetroAchievements Web API key is not encrypted at rest.** It is in app-private
   preferences excluded from backups, like the scraper passwords, but a rooted device can read it.
   `API_GetUserSummary`, used only for the rank, is documented as slow.

---

## 6. Remaining work

Tracked in one place: [`Roadmap.md`](../Roadmap.md) §3 (known issues) and §4 (not yet
implemented, by phase, including the Phase 2b customization engine).

---

## 7. How to reproduce

```bash
./gradlew :app:testDebugUnitTest                         # unit tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                              # device smoke test (clears app data)
./gradlew :app:assembleProfiling                         # optimized build for frame timing
adb shell dumpsys gfxinfo dev.xross.launcher              # UI frame stats
adb logcat -s WaveFrames XrossScan                        # GL pacing + scan timing (debug/profiling)
```
