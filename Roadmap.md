# Xross Roadmap

The one planning document for Xross: what has been built, what is left, in what order, and the
specifications for upcoming work. It is measured against the product scope in
[`project_scope.md`](project_scope.md). Updated **2026-11-11**, after the Phase 2b.1 commit
(`2c142d6`).

**Legend:** ✅ done · ◐ partly done · ⬜ not started · 🧪 verified only on the Android emulator, with
stub emulators or in unit tests · 📱 verified on a real device

## Documentation map

Plans, backlogs and feature specifications live **here**. The other documents are reference
material and link back here rather than keeping their own to-do lists.

| Document | What it is for |
| --- | --- |
| [`Roadmap.md`](Roadmap.md) (this file) | Status, known issues, backlog, order of work, scope decisions, and the specs for upcoming features (Appendix A) |
| [`project_scope.md`](project_scope.md) | The original product scope. Where this roadmap disagrees, §5 records the decision |
| [`README.md`](README.md) | Building, running and using Xross; the `Xross/` folder; theme pack format |
| [`docs/STATUS.md`](docs/STATUS.md) | Evidence: test suites, device runs, measurements, bugs found and fixed |
| [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md) | Checklist for testing on a real handheld, and what to send back |
| [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md) | How close Xross is to the real XMB, checked against the PS3 and PSP manuals |
| [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) | The 2026-11-09 feasibility review that led to the scope changes in §5 |
| [`docs/SCREENSCRAPER_API.md`](docs/SCREENSCRAPER_API.md) | English guide to ScreenScraper's API rules |

Merged into this file on 2026-11-11 (the originals are in git history):

| Former document | Now in |
| --- | --- |
| `docs/xross_extended_customization_feature_specification.md` (Phase 2b / Theme 2.0 spec, v2.0.0) | §2.9 (2b.1, done), §4.3 (2b.2 onward), Appendix A (data contracts and test gates) |
| `features_backlog_roadmap_directive.md` (seven backlog features) | §2 (all seven are done), Appendix B (decisions), Appendix C (references) |
| `docs/BACKLOG_ASSESSMENT.md` (assessment of those seven) | Appendix B (decisions and follow-ups), §5 (scope changes) |

---

## 1. Where we are

| Milestone | Scope | State |
| --- | --- | --- |
| **Phase 1 — Alpha** | XMB navigation, gamepad layer, ROM library, emulator launching, wave background | ✅ Complete |
| **Phase 1b — Alpha hardening** | Hot-swap, USB-OTG, playtime, Options menu, per-game emulator, more emulators | ◐ Features done, including per-controller mappings, the axial deadzone and a 0% ("hardware only") deadzone. Real-handheld testing and a Baseline Profile remain |
| **Phase 2 — Beta** | Metadata, media hub, theme packs, on-screen controller and keyboard, portable `Xross/` folder, library organization | ◐ Fingerprinting, DAT verification, three scrapers (all live-verified), Details screen, video snaps, media hub, gamepad search, the portable `Xross/` folder, library organization, theme packs, wave settings and the focused-game background are done. Left: on-screen controller, keyboard modes, per-track disc hashing, a language priority list, volume sliders, an original Default pack |
| **Phase 2b — Customization engine (Theme 2.0)** | Profile HUD, XMB game tiles, RetroAchievements dashboard, then Theme 2.0: layout, palettes, shaders, audio, haptics | ◐ **2b.1 done** (2026-11-11, unit-tested, not yet on a device, §2.9). 2b.2–2b.7 not started (§4.3) |
| **Phase 3 — Release candidate** | Dual screen, docking, RetroAchievements, Xross Portal, FTPS | ◐ RetroAchievements is done apart from encrypting the key and seeing an unlock toast after a real session. Hot-swap and live resolution change (two prerequisites) are done. Dual screen, docking, Portal and FTPS are not started |
| **Phase 4 — v1.0** | Onboarding, tuning, theme/plugin format, release | ◐ Performance work started; app triage moved to Phase 2 as app folders |
| **XMB fidelity** | Look and feel exactly like the XMB, plus Xross's extras | ◐ Layout, motion, navigation, status bar, game art and backgrounds, the monthly color, the busy icon and the sound roles match. The typeface, Resume/Quit Game, the folder drill-down and the playback control panel don't yet (§4.6) |

**Codebase at a glance:** Kotlin + Jetpack Compose, one `:app` module (plus the test-only
`:emulator-stub`), minSdk 28 / targetSdk 36, version `0.1.0-alpha`. Room database at schema v5,
22 platforms, 11 emulator launch recipes, 8 color themes (one follows the month), three metadata
services (Hasheous, SteamGridDB, ScreenScraper) plus RetroAchievements, settings in three JSON files
in `Xross/config/` (kotlinx.serialization), art in `Xross/media` in ES-DE's layout, theme packs in
`Xross/themes/`. **415 unit tests** (411 run by default, 4 opt-in live-service tests), an
on-device smoke test (75 of 86 on the Galaxy A55, §3), and test-only stand-ins for all 11
emulators.

**Testing so far:** one real device, a Samsung Galaxy A55 (Android 16, 120 Hz), has run every
milestone up to the theme packs and the live RetroAchievements and scraping pass
(docs/STATUS.md §3.11–§3.14), including upgrades of a real install. Phase 2b.1 is unit-tested
only. No controller, gaming handheld, SD card, Android 9/10 device or real emulator install has
been tested yet.

---

## 2. Implemented

### 2.1 Navigation and input

| Feature | State | Notes |
| --- | --- | --- |
| XMB layout: categories left/right, items up/down, drill-down | ✅ | Each category keeps its own drill-down stack and cursor; breadcrumb while drilled in |
| Categories | ✅ | Settings · Photo · Music · Video · Games · Apps |
| Spring-based motion | ✅ | Critically damped (~200 ms); held D-pad glides instead of restarting each step |
| Gamepad input (`InputDevice` keys, sticks, hat) | ✅ 🧪 | Stick motion is consumed so the D-pad never double-steps |
| Controller family detection | ✅ | PlayStation, Xbox, Nintendo, generic; picks matching button glyphs |
| Confirm/cancel swap | ✅ | Auto / South / East; Auto uses East on Nintendo pads |
| Button-remap wizard | ✅ | 10-step capture (the last step is the media controls button), saved per controller model (vendor/product ID); keyboards and unidentified devices use a global mapping |
| Radial and axial deadzones, anti-snapback, hold-to-repeat | ✅ 🧪 | Unit-tested; real stick motion can't be injected with adb. The radial deadzone has an "Off — hardware only" step that leaves only the driver's flat zone |
| Media controls shortcut | ✅ 📱 | **Select** toggles Now Playing from the XMB, an Options menu, Information or Details; holding **Guide** for 0.5 s does the same where the key reaches the app. Remappable; search, the photo viewer and the video player ignore it |
| Options (△ / Y) context menu with submenus | ✅ | Long-press opens it on touch |
| Touch parity | ✅ | One category per horizontal swipe, continuous vertical drag, tap, long-press |
| First D-pad press after a touch isn't lost | ✅ | |
| One Back press pops one level | ✅ 📱 | `KEYCODE_BACK` no longer reaches both the gamepad handler and the predictive-back callback |
| Gamepad on-screen keyboard and search | ✅ | 10-column grid over the FTS4 title index; hardware keyboards type directly |

### 2.2 Library and storage

| Feature | State | Notes |
| --- | --- | --- |
| ROM scanning into Room with incremental rescan | ✅ | 5,000 ROMs: first scan 1.9 s, rescan ~1 s (emulator) |
| 22 platforms with folder-name aliases and extension filters | ✅ | NES through Saturn, Arcade, 3DS, Switch, ScummVM |
| `.cue` / `.gdi` / `.m3u` track de-duplication | ✅ | |
| Auto-detect `ROMs`, `Games`, `Emulation`, `Retro` folders on every drive | ✅ | Plus custom folders through the system picker |
| Multiple volumes: internal, microSD, USB-OTG | ✅ 🧪 | |
| Hot-swap (mount, unmount, eject, removal) | ✅ 🧪 | Debounced quiet rescan; deferred while a game runs; games on a removed drive stay listed as "Not connected" |
| Favorites, Recently Played | ✅ | |
| Base ROMs folder | ✅ 📱 | Settings ▸ Library ▸ Base ROMs folder… (default `Xross/roms`); always scanned, beside auto-detection and added folders. **Create system folders** makes the 22 system folders, named by ES-DE's ids |
| "All Games" folder | ✅ 📱 | Games ▸ All Games holds the platform folders; Search, Recently Played, Favorites, Android Games and RetroAchievements stay at the top. Settings ▸ Theme ▸ Platform folders brings back the flat list |
| Portable `Xross/` folder | ✅ 📱 | `config/`, `data/`, `dats/`, `media/`, `roms/`, `themes/` on primary storage, or on an SD card or USB drive (Settings ▸ System ▸ Xross folder). App storage until shared storage can be written; config already in the folder is adopted. `.nomedia` in `media/` and `themes/` is created first |
| Settings as JSON | ✅ 📱 | `settings.json`, `controllers.json`, `systems.json`; atomic writes (`.tmp`, sync, rename) off the main thread; one-time migration from SharedPreferences; unreadable files kept as `.bad`; edits made outside Xross picked up on resume. Credentials stay in the private `CredentialStore` |
| Library database backup | ✅ 📱 | Live database stays private. A snapshot goes to `Xross/data/xross.db` 4 s after Xross leaves the screen (if anything changed) or from Settings ▸ System ▸ Back up library now. On a clean install a backup with games is offered from Games and Settings ▸ System; restore applies on restart |
| Playtime per game | ✅ | Survives process death. Since 2b.1 it is the subtitle under a game's title ("Never played", "Playtime: 12 mins", "Playtime: 14h 25m") in place of the file size; also in the Options menu |
| Information screen (Options ▸ Information) | ✅ 📱 | Platform, file, folder, size, dates, playtime, emulator, DAT match, fingerprint source and CRC32/MD5/SHA-1 |
| Content fingerprints (CRC32, MD5, SHA-1) | ✅ | Inside `.zip` / `.7z`; CHD SHA-1 from the header; No-Intro header-less hashes; background job that stops while a game runs |
| Title search (FTS4) | ✅ | Word-prefix matching |

### 2.3 Launching games and apps

| Feature | State | Notes |
| --- | --- | --- |
| RetroArch with a core per platform | ✅ 🧪 | |
| DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | |
| Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM | ✅ 🧪 | Launch recipes from ES-DE's published Android rules |
| Emulator per platform (Settings ▸ Emulators) | ✅ | |
| Emulator per game (Options ▸ Emulator) | ✅ | Game override → platform choice → first installed |
| ROM access for emulators | ✅ | File path extra plus a `content://` URI with a read grant |
| `GameLauncher` interface | ✅ | UI and repositories see launch plans only; Intents, `content://` grants and package queries live in `AndroidIntentGameLauncher`. A failed start doesn't count as playtime |
| Android games and apps | ✅ | Games (`CATEGORY_GAME`) go to Games ▸ Android Games; everything else to Apps |
| Hide apps | ✅ 📱 | Options ▸ Hide on any app in Apps or Games ▸ Android Games; Settings ▸ Apps ▸ Hidden apps shows them again (one by one or all); Hide system apps switch. Stored as `apps.hidden` in `settings.json` |
| App folders | ✅ 📱 | Options ▸ Move to folder: Emulators, Streaming, Tools, folders already in use, or New folder… typed on the gamepad keyboard. Known emulator packages are suggested for Emulators, and a "Group emulators" row moves them all at once. Folders sit at the top of Apps while they hold an app |

All 11 recipes are verified only against the test stand-ins, not real emulator installs.

### 2.4 Media hub

| Feature | State | Notes |
| --- | --- | --- |
| Photo, Music and Video from MediaStore | ✅ | Folders, tags, EXIF, thumbnails, album art |
| Photo viewer | ✅ | Zoom up to 5×, pan, slideshow (3–20 s), set as wallpaper |
| Music player as a background service | ✅ | Media3 + MediaSession: notification, headset and lock-screen controls; albums, artists, shuffle, repeat |
| Albums split by copy | ✅ 🧪 | The same album in two folders (FLAC and MP3, say) shows once per copy, labelled by format or folder |
| Background music pauses for games and videos | ✅ | Resumes when Xross is back in front |
| Video player | ✅ | Seek, previous/next, loop, auto-hiding controls |
| Clear empty state without storage access | ✅ 🧪 | Photo, Music and Video say "Storage access needed"; pressing the row opens the grant screen |
| Custom Photo and Video folders | ✅ 📱 | Settings ▸ Media ▸ Photo folders / Video folders. Empty = all of MediaStore; otherwise MediaStore filtered by path plus a direct walk for files it skips (`.nomedia` and hidden folders, unindexed drives), with thumbnails made by Xross. Reloads on resume and when a drive comes or goes |
| Floating mini player | ✅ 📱 | A rounded card in the lower-right corner, just above the button hints: 40 dp album art, scrolling title, artist, play/pause glyph. Stays across every category and steps aside for full-screen media, panels and dialogs. Tap opens Now Playing; long press plays or pauses |
| Mini player hides after 30 s paused | ✅ 📱 | Fades out after 30 s paused; comes straight back when playback resumes or the track changes |

### 2.5 Look and sound

| Feature | State | Notes |
| --- | --- | --- |
| Wave background (GLSL ES 3.0) | ✅ | Stops whenever Xross isn't in front, and while focused-game art or a snap covers it |
| Wave shader uniforms | ✅ 📱 | Speed (0.5×–2×), ribbon lines (0–12), turbulence, thickness, color presets and a custom gradient, in `settings.json` and Settings ▸ Theme ▸ Wave settings with a live preview |
| Theme packs | ✅ 📱 | `Xross/themes/<id>/` (theme.json, font, SVG/PNG icons, sounds), case-insensitive and contained lookups; partial packs inherit from the built-in Default pack. Format in README "Theme packs" |
| Audio roles | ✅ | `cursor.wav` for every D-pad step, `01. opening.wav` at startup, `system_ng.wav` for errors and refused actions |
| Day/night brightness shift | ✅ | |
| Color themes | ✅ 📱 | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original (a color per month, blended over the days around each month boundary) |
| Icons from the design SVGs | ✅ | 29 SVGs in the Default theme pack, generated by `tools/icons/svg_icons.py` and drawn by the pack loader |
| Focus glow, halos, drop shadows | ✅ | Blurred on Android 12+, offset copies on older versions |
| Cold-boot fade-in and startup sound | ✅ | Can be turned off |
| Busy indicator while scanning, fingerprinting, importing or scraping | ✅ 📱 | Ring of dots in the bottom corner; appears after 0.4 s |
| Hide the button-hint pill | ✅ 📱 | Settings ▸ Theme ▸ Button hints |
| UI sounds, volume, game-start cue | ✅ | |
| Focused-game background: video, then hero art, then wave | ✅ 📱 | Hero art (or a screenshot) crossfades in after 250 ms; the snap fades in over it after 1.2 s with a 300 ms audio ramp, music ducked; the wave stops rendering while either covers the screen |

### 2.6 Devices and displays

| Feature | State | Notes |
| --- | --- | --- |
| Any aspect ratio and resolution | ✅ 🧪 | 12 configurations from 640×480 to 4K |
| Live resolution change without restart | ✅ 🧪 | Prerequisite for docked mode |
| Locked to landscape | ✅ 📱 | `sensorLandscape`: flips 180° with the device. Android 16 ignores the lock on screens 600 dp and wider |
| HOME launcher and Android TV leanback entry | ✅ | |

### 2.7 Quality and performance

| Item | State | Notes |
| --- | --- | --- |
| 60 fps menus | ✅ 🧪 | Across all 12 display configurations |
| 120 Hz | ✅ 📱 | Galaxy A55 at 120 Hz, profiling build: 0.63–1.46% janky frames, p50 13 ms, no missed vsync. Not yet measured on a 120 Hz handheld |
| Fewer recompositions per cursor move | ✅ | Only rows whose focus changes are rebuilt (~75% less work per press) |
| Cold boot to interactive | ✅ 📱 | 377–1,050 ms to first frame on the Galaxy A55 (profiling build); the fade-in adds 1.75 s by design |
| Memory with another app in front | ✅ 📱 | 83–122 MB PSS on the Galaxy A55 (target ≤ 150 MB); decoded art is released when the UI is hidden |
| Test tooling | ✅ | Unit tests, opt-in live service tests, adb smoke test, emulator stand-ins, `profiling` build type |

### 2.8 Metadata and art

| Feature | State | Notes |
| --- | --- | --- |
| DAT verification (No-Intro, Redump, MAME) | ✅ 📱 | Logiqx XML and clrmamepro text, plain or zipped, from `Xross/dats`. Matches by SHA-1, MD5, CRC32 + size, or arcade set name; sets title, region, languages, revision and serial |
| Hasheous identification | ✅ 📱 | By hash, no account. Arcade sets send their largest ROM chip and pick the set by name |
| SteamGridDB art | ✅ 📱 | Capsule (600×900), hero banner and logo, by title; needs a free API key |
| ScreenScraper | ✅ 📱 | Descriptions, box art, logos, screenshots and video snaps; quota handling as the API requires (`docs/SCREENSCRAPER_API.md`). Live with developer credentials and a user account (20,000 requests/day); HTML entities in descriptions decoded |
| Background scraping | ✅ 📱 | Off until turned on, Wi-Fi only by default, paced per service, stops while a game runs |
| Art in `Xross/media/<kind>/<system>/<rom stem>.<ext>` | ✅ 📱 | ES-DE-style names (`covers`, `3dboxes`, `heroes`, `wheels`, `screenshots`, `capsules`, `snaps`), FAT-safe and de-duplicated per system; ES-DE media copied in is used as is and never pruned |
| Game Details screen | ✅ 📱 | Options ▸ Details or → on a game: background art, box art, logo, release facts, rating, playtime, description, achievements summary. Paging between games added in 2b.1 (§2.9) |
| Manual match and title override | ✅ 📱 | Options ▸ Metadata: Edit title, Find match, Scrape again, Use automatic match |
| Service credentials | ✅ | Stored apart from settings and excluded from backup; `.env` pre-fills them in debug builds only (see `.env.example`) |

### 2.9 Profile, RetroAchievements and Phase 2b.1

The RetroAchievements rows were built for Phase 3; the rest is milestone 2b.1 of the
customization engine (§4.3), committed in `2c142d6`.

| Feature | State | Notes |
| --- | --- | --- |
| RetroAchievements account linking | ✅ 📱 | Settings ▸ Accounts: user name + Web API key, checked against the API before saving. The key lives only in `CredentialStore` (app-private, excluded from backups) |
| User Card | ✅ 📱 | Rank, points, RetroPoints, RetroRatio, recent unlocks; Refresh and Unlink. Opens with Options (△) on the expanded avatar |
| Per-game achievement lists | ✅ 📱 | Options ▸ Achievements and a Details summary: badge grid, points, completion bar, unlock dates. Games matched by RetroAchievements' own hash per system (title for disc systems); games without a set say "no achievements yet" |
| Unlock toasts | ✅ 🧪 | New unlocks after a game session queue as top-right toasts with the trophy sound; not yet seen after a real session |
| **2b.1** Far-right avatar with in-place mini-HUD | ✅ 🧪 | The 32 dp avatar sits at the far right of the status bar. Up from the first row of a category root (or a tap) expands it in place into a frosted capsule (user, rank, hardcore and softcore points, recent badges) that replaces the clock and battery. Down, Back or a tap collapses it; Confirm refreshes (or starts linking); Left/Right switch category. The morph is layout/draw-phase only, so neighbours don't recompose |
| **2b.1** Rectangular 16:9 XMB game tiles | ✅ 🧪 | Games rows use a wide tile: clear logo (`wheels`), then wide hero/capsule art, then the title on a frosted plate |
| **2b.1** Playtime subtitle | ✅ 🧪 | See §2.2 |
| **2b.1** Details paging | ✅ 🧪 | L1/R1 and Left/Right page through the list the Details screen was opened from, with a slide and crossfade; metadata, backdrop and achievements follow, and the list cursor moves with it. Stops at both ends |
| **2b.1** Game RetroAchievements telemetry | ✅ 🧪 | Lower right, above the button hints: trophy + "18/42", points, a mastery ribbon at 100%. Hidden for games RetroAchievements doesn't recognize or that have no set |
| **2b.1** Profile customization | ✅ 🧪 | `profile.avatarStyle` in `settings.json` (border color, width 1–6 dp, glow on/off, speed 0.5–2×, intensity 0.1–1); Settings ▸ Theme ▸ Profile customization with presets and a live preview. The glow stops animating when the screen is idle or covered |
| **2b.1** Games ▸ RetroAchievements dashboard | ✅ 🧪 | Header card (avatar, user, rank, member since, hardcore/softcore points, RetroRatio), library completion gauge, mastery showcase in gold frames, recent activity feed; built from cached progress, with offline and not-linked states |

---

## 3. Known issues to fix

Bugs or gaps in features that are already built, separate from the new work in §4.

| Issue | Impact | Fix |
| --- | --- | --- |
| No real-hardware validation of controllers, sticks, emulators or audio | Biggest risk to Beta | Phase 1b hardware pass (§4.1) |
| Phase 2b.1 has not run on a device | The avatar HUD morph, the 16:9 tiles, L1/R1 paging on Details and the dashboard are unit- and view-model-tested only | Run the 2b.1 checks in `docs/HARDWARE_TESTING.md` §6c on the Galaxy A55, then on a handheld |
| Unit tests not re-run in a clean environment since 2b.1 | The commit added 20 tests (395 → 415); the count is from the source | Run `./gradlew :app:testDebugUnitTest` and record the result in docs/STATUS.md §3.1 |
| `smoke.sh` 75/86 on the Galaxy A55 | The 11 failures are Photo/Music checks that expect the seeded media to be the only media. Not re-run since 2b.1 added Settings ▸ Theme ▸ Profile customization and Games ▸ RetroAchievements, which may shift key counts | Re-run on a throwaway emulator; fix counts |
| Idle CPU in front | 100–130% of the 8-core total on the Galaxy A55: the UI redraws ~85×/s for the focus pulse and the wave renders continuously at 120 Hz. The 2b.1 avatar glow adds another infinite animation while it is on | Drop the wave to 60 Hz or pause it while nothing moves; let the focus pulse and avatar glow rest when idle |
| Library organization and the portable folder only tested on a phone | Passed on the Galaxy A55's internal storage. Moving the folder to an SD card or USB drive, atomic replace on FAT/exFAT, and Android 9/10 are unit-tested only | Check on a device with an SD card, and on an Android 9 or 10 device |
| Folder names are typed in capitals | The gamepad keyboard has no lower case; "MY TOOLS" is saved as "My Tools" | Keyboard modes (§4.6) |
| Config writes are asynchronous | A crash within milliseconds of changing a setting can lose that one change | Acceptable; writes are flushed before restores and folder moves |
| Per-controller mappings and the axial deadzone are untested on hardware | Unit- and view-model-tested only | Test with two different controllers in the hardware pass |
| Text dialogs (account, key, title) use the system keyboard | On the Galaxy A55 the keyboard once didn't open until the field was tapped | Replace with the gamepad keyboard |
| Six newer emulator recipes may not accept a FileProvider URI | Launches could fail on real installs | Test each against a real install; fall back to the path extra |
| Multi-file disc images (`.cue` + `.bin`) | One `content://` URI doesn't cover the `.bin` tracks | Rely on the emulator's own storage access and the path extra |
| The Default pack still ships PS3/PSP-like icons and sounds | Fine for testing, not for a public release | Make an original or open-licensed Default pack and offer the current one as an optional download |
| Unlock toasts unseen after a real session | Detection is unit-tested and the profile refresh runs live | Earn an achievement in RetroArch or PPSSPP with the same account, return to Xross |
| RetroAchievements Web API key not encrypted at rest | App-private and excluded from backups, but readable on a rooted device | Keystore encryption (§4.4) |
| Search matches word prefixes only, Latin letters and digits only | "rono" doesn't find "Chrono Trigger" | Substring or trigram matching if users need it |
| Playtime counts all wall-clock time away from Xross after a launch | Sleep and other apps count (capped at 24 h) | Acceptable for now; revisit with usage stats |
| *Ocarina of Time 3D* not found by ScreenScraper name search | One sample of eight unidentified (3DS images are too large to hash) | Try the serial or a cleaned title as a second search |
| Search results at 80% opacity clip their text shadow slightly | Barely visible | Switch to `fadeAlpha` when that screen is next touched |

---

## 4. Not yet implemented

### 4.1 Phase 1b — finish alpha hardening (next)

| Item | Notes |
| --- | --- |
| Test on 2–3 real handhelds | For example a Retroid Pocket, an AYN Odin and a 4:3 Anbernic: controllers, sticks, frame pacing, audio, and real RetroArch, DuckStation, Dolphin, PPSSPP and NetherSX2 launches. Follow `docs/HARDWARE_TESTING.md` |
| Confirm the six newer recipes on real installs | Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM |
| Baseline Profile | Faster startup and less jank before the JIT warms up |
| Deadzone per controller model (optional) | Next to the button mapping in `controllers.json`, so a Hall effect pad can use 0% while a worn one keeps 35% |

### 4.2 Phase 2 — Beta (remaining)

| Item | Depends on | Notes |
| --- | --- | --- |
| Per-track hashing for `.cue` / `.gdi` | | Redump matches tracks, not sheets |
| Language priority list | Scrapers ✅ | One region and one language today; a list such as `es-ES, es, en-US, en` |
| Game backgrounds setting | Focused-game background ✅ | Settings ▸ Theme ▸ Game backgrounds: Video and art / Art only / Off; plus an audio ramp down on leaving a game |
| Custom Music folders | Custom Photo/Video folders ✅ | The same folder list for Music, for consistency |
| An original or open-licensed Default pack | Theme packs ✅ | Required before a public release; offer today's look as an optional download |
| XMB-style rounded typeface | Theme packs ✅ | Bundle an open-licensed font as the default; the font roles of Theme 2.0 (§4.3, 2b.2) build on it |
| Battery-level color blending | | Battery level is already read for the status bar |
| Custom boot animations | Theme packs ✅ | |
| UI scale slider (50–150%) | | Layout already scales automatically |
| Aspect-ratio overrides (letterbox / pillarbox) | | |
| On-screen controller overlay | | For touch-only devices: opacity, haptics, size, position snapping |
| Per-game button remapping | | |
| Playlists and custom tags | | For example "Multiplayer" or "Completed" |
| Advanced: live database on internal shared storage | Database backup ✅ | Opt-in, `JournalMode.TRUNCATE`, never on a removable drive (Appendix B.6) |

Separate volume sliders and status-bar modes moved to the customization engine (2b.5 and 2b.7).

### 4.3 Phase 2b — Customization engine (Theme 2.0)

From the Phase 2b / Theme 2.0 specification (v2.0.0, merged here; data contracts in Appendix A).
2b.1 is done (§2.9). The rest builds on the theme pack loader and wave uniforms that Phase 2
already shipped, and **doesn't depend on the hardware pass**, so it can run in parallel with it.
The spec labelled the last two milestones "Phase 3.1" and "Phase 3.2"; they are renumbered 2b.5
and 2b.6 so they don't collide with Phase 3 (RC). 2b.7 collects spec items the spec didn't
schedule.

| Milestone | Scope | Prerequisites | State |
| --- | --- | --- | --- |
| **2b.1** | Far-right avatar and mini-HUD, 16:9 game tiles, Details paging, playtime subtitle, game RA telemetry, avatar style settings, Games ▸ RetroAchievements dashboard | RetroAchievements ✅, Details ✅ | ✅ 🧪 (device pass pending) |
| **2b.2** | Theme 2.0 manifest (`theme.json` v2) with a v1 reader; font roles (`display`, `body`, `mono`); layout block (crosshair anchors, tile aspect per category, spacing, focused scale, unfocused alpha); text overflow mode | Theme pack loader ✅ | ⬜ |
| **2b.3** | Contextual palette from game art (Jetpack Palette) driving the wave, status-bar rings and focus glow; contrast profiles (OLED True Black, Cyber-Pastel, Monochrome E-Ink); optional hourly circadian curve | Art in `Xross/media` ✅, 2b.2 palette roles | ⬜ |
| **2b.4** | Custom GLSL fragment shaders from theme packs (`shader.frag`) with standard uniforms; built-in Starfield, Bokeh and CRT profiles; frosted surface and halo/glow parameters | Wave uniforms ✅ | ⬜ |
| **2b.5** | Ambient BGM loop from theme packs with ducking; independent volume sliders (UI effects, boot cues, music/BGM, video snaps); optional cursor pitch jitter | Media hub ✅, `SnapPlayer` ✅ | ⬜ |
| **2b.6** | Haptic profiles (Off, Subtle Click, Crisp Snap, Deep Thud) on category switch, list moves, errors and unlock toasts | Gamepad layer ✅ | ⬜ |
| **2b.7** | Modular components: status-bar cluster order and battery display modes; mini-player modes (card, status ticker, off); glyph style override; metadata badge pills beside the focused title | 2b.2 | ⬜ |

**Architecture assessment.** Each item checked against the code before it is built:

| Item | Fits? | Platform implications and decisions |
| --- | --- | --- |
| Theme 2.0 manifest (2b.2) | Yes | `ThemeManifest` reads v1 today (`palette` top/bottom/wave/glow, `font`, `sounds`, `soundGains`, `icons`, `wave`). v2 must stay a superset: a v1 pack keeps working, and the v1 `font` maps to all three roles. Unknown keys are ignored (kotlinx `ignoreUnknownKeys`), as in `settings.json`. The spec's `$schema` URL (`xross.launcher`) isn't a domain the project owns; leave `$schema` out until a schema is published with the docs (Phase 4 "public theme format") |
| Theme values vs user settings | Decision | A pack supplies **defaults**; the user's `settings.json` overrides them, exactly as wave settings do today (Reset to theme defaults restores the pack's values). This covers `avatarStyle`, `layout`, `effects` and `audio` |
| Layout block (2b.2) | Yes, with care | Today the focus column, spacing and row count are derived from the screen; the 12-configuration display matrix (docs/STATUS.md §4.1) found clipping on 4:3, 1:1 and 3:2. Clamp anchors so one whole neighbour category stays visible, and re-run the matrix after this lands. Per-category tile aspect reuses the 2b.1 `GameTile` |
| Font roles (2b.2) | Yes | One `FontFamily` per role loaded once per pack; missing roles fall back to `body`, then the default. Tabular figures (`mono`) for scores, clock, battery and playtime |
| Contextual palette (2b.3) | Yes | New dependency `androidx.palette`. Extract off the main thread from the already-decoded hero/wheel bitmap, cache per game, debounce 250 ms (the hero fade delay). The wave reads colors as uniforms, so the morph costs no shader recompile |
| Contrast profiles (2b.3) | Yes | E-Ink must turn off the wave, the glow pulse and all motion; OLED True Black draws no surface fills. Both are presets over existing settings, not new renderers |
| Circadian curve (2b.3) | Yes | Extends the Original theme's blending and the existing day/night brightness shift; one source of truth for time-of-day color |
| Custom shaders (2b.4) | Yes, guarded | The wave is a `GLSurfaceView`. User shaders are compiled on the GL thread; a compile or link failure, or a frame-time budget overrun (watchdog), falls back to the built-in wave with a toast. Only the fragment shader is replaceable, the standard uniforms are always bound, and the existing pause rules (not in front, art or video covering it) still apply |
| Frosted surfaces (2b.4) | Partly | Compose can't blur what is behind a view, and the GL wave is a separate surface, so `Frosted.kt` uses a layered tint. A real backdrop blur needs `RenderEffect` (Android 12+) on a layer that also holds the background, or a blur pass inside the shader. Decision: opacity and glint border on every version; blur radius only on Android 12+ over art backgrounds; Android 9–11 keep the tint |
| Halo profiles (2b.4) | Yes | Glow blur already runs on Android 12+ with offset copies below; profiles change parameters, not technique |
| BGM (2b.5) | Yes | BGM must never fight the user's music: it plays only while no song is loaded, pauses when music starts, ducks to 20% under a video snap and stops before an emulator launch. Use a second, lightweight ExoPlayer with audio focus `GAIN_TRANSIENT_MAY_DUCK` handling, not the music session (no notification for BGM) |
| Pitch jitter (2b.5) | Yes | `SoundPool.play` already takes a rate; ±2% is a random rate in 0.98–1.02 |
| Haptics (2b.6) | Yes | `VibratorManager` on Android 12+, `Vibrator` on 9–11; controller rumble through `InputDevice.getVibratorManager()` (Android 12+) where the pad supports it. Needs the normal `VIBRATE` permission (not in the manifest yet). Off by default; skipped while a game runs |
| Status bar, mini-player and glyph modes (2b.7) | Yes | `StatusBar` and `MiniPlayerCard` already own their layout and visibility rules; modes are settings over them, and the 2b.1 HUD's fade of the clock and battery must keep working in every cluster order. Glyph override decouples `ControllerFamily` (detection) from the glyph set (display) |

### 4.4 Phase 3 — Release candidate

| Item | Notes |
| --- | --- |
| Dual screen | Presentation API; second-screen layouts for details, box art, achievements, music and telemetry; screen-swap hotkey; per-screen orientation (must work with the landscape lock) |
| Dock detection | `DisplayManager`; live resolution switching already works |
| `autorun.inf` manifests | **Parse only**: label, icon, scan paths. Never execute anything from removable media |
| Volume labels and drive icons | |
| RetroAchievements: remaining | Encrypt the Web API key at rest (Keystore); see an unlock toast after a real session. An overlay over a running emulator stays optional, since RetroArch shows its own |
| HTTPS Xross Portal | Ktor CIO engine: PIN login, ROM and BIOS upload into the base ROMs folder, library editing, diagnostics. Self-signed TLS means browser warnings |
| FTPS server | Apache FtpServer with TLS; under 3% CPU when idle; only runs when enabled; uploads into the base ROMs folder |

### 4.5 Phase 4 — v1.0

| Item | Notes |
| --- | --- |
| First-run onboarding | Storage access, folder discovery, emulator detection, first scrape, RetroAchievements link |
| Performance and thermal tuning on target hardware | Snapdragon 665 / Unisoc T618 class |
| Input-latency measurement | Target ≤ 16.6 ms; needs high-speed camera capture |
| Public theme and plugin format, with documentation | Publishes the Theme 2.0 manifest (2b.2) and its JSON schema |
| Release signing and opt-in crash reporting | The release build type has no signing config yet |
| Play Store / F-Droid compliance review | `MANAGE_EXTERNAL_STORAGE` is a Play review risk; bundled assets must be replaced |
| Orientation setting | Optional: let users unlock rotation (for example portrait phones or vertical handhelds) |

### 4.6 XMB fidelity

From [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md). The home menu, game art and backgrounds now
read as an XMB; what remains, ordered by how much each item adds to the feel:

| Item | Depends on | Notes |
| --- | --- | --- |
| XMB-style rounded typeface | Theme packs ✅ | Also listed in §4.2; the Android default font is the most visible difference today |
| Resume Game / Quit Game while an emulator Xross launched is running | — | Equivalent of the PS button's Quit Game and the PSP Go's Pause/Resume Game |
| A distinct cursor sound in the Default pack | Original Default pack (§4.2) | The role exists (`cursor.wav`); today's file is the options tick at a lower gain |
| XMB-style drill-down (folder icon beside its contents, no text breadcrumb) | — | |
| Control panel on △ in the video and music players | — | Alongside the existing direct controls and the media controls shortcut |
| Photo or theme background behind the menu | Theme packs ✅ | "Set as wallpaper" currently sets the Android wallpaper |
| Connected drives as items in their categories | Hot-swap ✅ | |
| Keyboard input modes and an optional multi-tap keypad | — | Lower case, accented letters, symbols |
| Delete, sort and Display All in the options menu; Restore default settings | — | |
| Screensaver when docked or idle on a TV | Dock detection (§4.4) | |

Xross's sounds, icons and font must stay original or open-licensed by default, with Sony-like
assets loadable only by the user through theme packs.

---

## 5. Scope changes and decisions

These replace the original wording in `project_scope.md` or in the merged backlog and spec. The
first group comes from [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md).

| Original scope | Decision | Reason |
| --- | --- | --- |
| Compose **or** C++/SDL2 UI | Compose for UI; GL only for the wave | One UI stack |
| Room with FTS5 | FTS4 | Room generates FTS3/4 only; FTS5 isn't guaranteed on older Android |
| Native POSIX bridge for scoped storage | `java.io.File` with All files access | Same speed; DocumentFile fallback later if needed |
| Hashes without decompressing | Header CRC first, full hashes in a background job | MD5/SHA-1 need the content |
| `android:isGame` | `ApplicationInfo.category == CATEGORY_GAME` plus the legacy flag | `isGame` is deprecated since API 26 |
| Google Play and web-search scraping | Dropped | No public API; against the services' terms |
| Yuzu, Citra, AetherSX2 | Eden, Azahar, NetherSX2 | Original projects discontinued |
| Sudachi | Not added | No verified launch contract |
| Dusklight / TwilitRealm | Parked | No public API found |
| `autorun.inf` "execution" | Parse only | Executing from removable media is unsafe |
| Ktor/Netty with FTPS | Ktor CIO for the portal, Apache FtpServer for FTPS | Ktor has no FTP; Netty is heavy on Android |
| Real-time RA overlay over a running emulator | Progress and toasts in the launcher; overlay optional | RA has no push API; RetroArch shows its own toasts |
| Free rotation | Landscape only (`sensorLandscape`) | Handheld-first; large screens can still rotate |
| App triage UI (Phase 4) | App folders with a suggested Emulators folder (Phase 2) | One mechanism instead of two |
| Live database in the portable `Xross/data/` folder (backlog) | Private live database plus a backup copy in `Xross/data/` | FUSE slows writes; SQLite on exFAT/FAT32 can corrupt when a drive is pulled |
| All state in `Xross/config/settings.json` (backlog) | Everything except credentials and the running-game session | The folder is readable by any app with All files access |
| Guide / Home long-press for media controls (backlog) | Select by default, Guide where it reaches the app | The system or handheld firmware often takes Guide; HOME never arrives as a long press |
| `VACUUM INTO` for the database backup (backlog) | WAL checkpoint plus a copy under `BEGIN IMMEDIATE` | `VACUUM INTO` needs SQLite 3.27 (API 30); Xross supports Android 9 |
| Desktop / Compose Multiplatform readiness (backlog) | `GameLauncher` interface only; no desktop target planned | Room 2.7+ and kotlinx.serialization don't block it later |
| `Select` opens the avatar mini-HUD (spec 2.1) | Up from the top row, or a tap, opens it | Select is the media controls shortcut |
| Spec file names `XmbStatusBar.kt`, `GameItemView.kt`, `DetailsViewModel` | `StatusBar.kt`, `GameTile.kt`, `XmbViewModel` (Details state in `state/GameDetails.kt`) | The names in the code |
| Spec "Phase 3.1" (BGM) and "Phase 3.2" (haptics) | 2b.5 and 2b.6 | Avoids a clash with Phase 3 (RC) |
| Theme manifest `$schema: https://xross.launcher/...` | No `$schema` until a schema is published | The project doesn't own that domain |
| Frosted blur 0–48 dp on every surface (spec 3.2) | Blur on Android 12+ over art only; tint elsewhere | No backdrop blur in Compose before `RenderEffect`; the wave is a separate GL surface |

---

## 6. Order of work

```
Phase 1b                  Phase 2 (Beta)                         Phase 2b (customization)        Phase 3 (RC)            Phase 4 (v1.0)
────────                  ──────────────                         ────────────────────────        ────────────            ──────────────
Real-hardware pass ──┬──▶ DAT import ✅ ─▶ Scrapers ✅ ─▶ Details ✅ ─▶ 2b.1 ✅ (device pass) ───┬─▶ RetroAchievements ◐ ─▶ Onboarding
                     │                        └──▶ Video snaps ✅    Theme packs ✅ ─▶ 2b.2 ─┬─▶ 2b.3 palettes          │
                     │                                                                 ├─▶ 2b.7 components        └─▶ Xross Portal
                     │                                               Wave uniforms ✅ ─▶ 2b.4 shaders                  Public theme format
                     │                                               Media hub ✅ ─────▶ 2b.5 audio
                     │                                               Gamepad layer ✅ ─▶ 2b.6 haptics
                     ├──▶ Per-controller mappings, axial and 0% deadzones ✅
                     ├──▶ Baseline Profile                                                         Dual screen, docking
                     │                                                                             FTPS (independent)
                     └──▶ Settings JSON in Xross/ ✅ ─┬─▶ App hide and folders ✅
                                                      ├─▶ Custom Photo/Video folders ✅
                                                      ├─▶ Art in Xross/media ✅ ─▶ Contextual palette (2b.3)
                                                      ├─▶ Database backup ✅
                                                      └─▶ Base ROMs folder ✅ ─▶ Portal / FTPS upload target

Before any public release: original Default pack + open-licensed typeface (§4.2)
```

- **The hardware pass gates the Beta.** Controller handling, emulator launches and frame pacing
  are only proven on the emulator and one phone.
- **Phase 2b runs in parallel**: it touches the look, not the input or launch paths, so it doesn't
  wait for the hardware pass. 2b.2 comes first, because palettes and components read its manifest.
- **Every 2b milestone stays inside the frame budget** (0–1.5% janky frames) and must not raise
  idle CPU; new infinite animations rest when the screen is idle (§3, idle CPU).
- **FTPS, dual screen and docking don't depend on the metadata or customization work.**
- **The original Default pack comes before any public release**, because the bundled assets
  resemble Sony's.

### Suggested next steps

1. Device pass for 2b.1 on the Galaxy A55 (`docs/HARDWARE_TESTING.md` §6c) and a clean
   `./gradlew :app:testDebugUnitTest` run; re-run `smoke.sh` on an emulator and fix its counts.
2. The real-handheld pass with a controller and real emulators (also covers per-controller
   mappings, the deadzones and the six newer recipes).
3. Cut idle CPU in front: a 60 Hz or paused wave while nothing moves, resting pulses.
4. 2b.2: Theme 2.0 manifest with a v1 reader, font roles, and an open-licensed rounded typeface
   as the default `display` font.
5. An original Default pack (icons, sounds), then the Game backgrounds setting.
6. Baseline Profile.
7. 2b.3–2b.7 in order, or interleaved with Phase 3 (Keystore for the RA key, Portal, FTPS).

---

## 7. Performance targets

From `project_scope.md` §5.

| Target | Result |
| --- | --- |
| ≥ 60 fps menu navigation | ✅ 🧪 All 12 display configurations |
| 120 fps on high-refresh panels | ✅ 📱 Galaxy A55 at 120 Hz: 0.63–1.46% janky, p50 13 ms, no missed vsync; no 120 Hz handheld tested |
| Input-to-screen ≤ 16.6 ms | ⬜ Not measured |
| Cold boot to interactive < 2.5 s | ✅ 📱 377–1,050 ms to first frame on the Galaxy A55 (profiling build); ~200 ms on the emulator |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 📱 83–122 MB PSS on the Galaxy A55 |
| GL loop suspended when Xross isn't in front | ✅ 📱 0% CPU in the background on the Galaxy A55 |
| Idle CPU in front | ◐ Not a scope target, but 100–130% of the 8-core total on the Galaxy A55 (§3) |
| Background daemons < 3% CPU when idle | ⬜ No daemons yet |

---

## Appendix A — Theme 2.0 specification (data contracts)

Merged from the Phase 2b / Theme 2.0 specification v2.0.0. Milestones and decisions are in §4.3
and §5; this appendix keeps the contracts implementers need.

### A.1 Invariants

1. **Credentials stay isolated.** RetroAchievements Web API keys and ScreenScraper passwords stay
   in `CredentialStore` (`xross_credentials`, app-private). They are never written to
   `settings.json`, `theme.json` or anything in the `Xross/` folder; tests assert it.
2. **Android 9 (API 28) compatibility.** Anything that needs a newer API (blur, `VibratorManager`,
   controller rumble) has a fallback or is hidden.
3. **`.nomedia`** stays in `Xross/media/` and `Xross/themes/`.
4. **Performance and test gates.** 60/120 fps with 0–1.5% janky frames; no regressions in
   `./gradlew :app:testDebugUnitTest`.

### A.2 Avatar style (done in 2b.1)

`settings.json` → `profile.avatarStyle`. Out-of-range values are clamped, and a bad color falls
back on its own without resetting the rest.

```kotlin
@Serializable
data class AvatarStyleConfig(
    val borderColorHex: String = "#FFB5C5", // presets: pastel pink, neon cyan, gold, white, custom
    val borderWidthDp: Float = 2.0f,        // 1.0–6.0
    val glowEnabled: Boolean = true,
    val glowSpeed: Float = 1.0f,            // 0.5–2.0× pulse rate
    val glowIntensity: Float = 0.6f,        // 0.1–1.0 alpha / reach
)
```

### A.3 Layout (2b.2)

| Setting | Values |
| --- | --- |
| Crosshair anchor `layout.crosshairX` / `crosshairY` | Presets: **Classic PS3** (bar at 35% from the top, active column at 30% from the left), **PSP centre** (50% / 50%), **Modern widescreen** (bar at 20%, 80% of the screen for media and the list). Clamped so one whole neighbour category stays visible |
| Tile aspect, per category | `16:9` / `2:1` rectangular plate (logo or capsule), `3:4` / `2:3` portrait box art, `4:1` compact text pill |
| Unfocused item opacity | 10–80% |
| Focused item scale | 1.00× (flat) to 1.35× |
| List spacing | Tight (6 dp), Standard (12 dp), Spacious (20 dp) |
| Text overflow | Marquee, edge fade, or ellipsis |

### A.4 Materials, shaders and glow (2b.4)

Custom fragment shaders: `Xross/themes/<id>/shader.frag` (or the manifest's `effects.shaderPath`),
GLSL ES 3.0. Xross always binds:

```glsl
uniform float uTime;
uniform vec2  uResolution;
uniform vec4  uColorTop;
uniform vec4  uColorBottom;
uniform vec4  uAccent;
```

Built-in profiles besides the wave: *Starfield / warp dust*, *Floating bokeh*, *CRT scanlines
with phosphor bloom*. On a compile error or a blown frame budget Xross falls back to the wave.

| Parameter | Range |
| --- | --- |
| Surface opacity | 0% (clear) to 85% (milky) |
| Background blur radius | 0–48 dp (Android 12+, over art backgrounds; §5) |
| Glint border | 1 px inner hairline, solid or gradient |
| Halo profile | Breathing pulse, static neon bloom, hard shadow (2 dp offset, no blur) |
| Halo pulse speed / blur / peak opacity | 0.5–2.0× / 2–24 dp / 0.1–1.0 |

### A.5 Color (2b.3)

- **Contextual palette:** dominant, vibrant and muted colors from `media/heroes/` or
  `media/wheels/`, morphing the wave, status-bar rings and focus glow on hover; 250 ms debounce.
- **Profiles:** *OLED True Black* (`#000000`, no surface fills, glowing outlines), *Cyber-Pastel /
  Y2K* (lavender with lilac, mint and strawberry accents), *Monochrome E-Ink* (high-contrast,
  no motion).
- **Circadian curve (optional):** 06–11 cool cyan / sky blue · 11–18 azure / cobalt · 18–21 amber /
  rose gold · 21–06 indigo / midnight purple.

### A.6 Typography and badges (2b.2, 2b.7)

- Font roles: `display` (clock, status titles, category labels), `body` (descriptions, metadata,
  dialogs), `mono` (tabular figures: scores, timestamps, battery, playtime).
- Badge pills beside the focused title, each optional: playtime, RetroAchievements progress
  ("🏆 18/42"), system tag with its glyph, release year and developer/publisher.

### A.7 Audio and haptics (2b.5, 2b.6)

- BGM: `bgm.ogg` / `bgm.mp3` from the pack; 20% under a video snap; stops before an emulator
  launch; never plays over the user's music (§4.3).
- Volume sliders: UI effects, boot/shutdown cues, music and BGM, video snaps.
- Pitch jitter: random ±2% rate on `cursor.wav`.
- Haptic profiles: Off, Subtle Click, Crisp Snap, Deep Thud. Triggers: category switch, list move,
  boundary error (`system_ng`), unlock toast.

### A.8 Components (2b.7)

- Status bar clusters, reorderable. Left: logo/title, Wi-Fi, volume. Right: battery, clock,
  avatar. Battery as a capsule, a percentage, or time remaining.
- Mini player: bottom-right card (default), status-bar ticker, or off (playback only).
- Glyph style, independent of the detected controller: PlayStation (✕ ○ △ □), Xbox (colored
  A B X Y), Nintendo (A B X Y, swapped positions), minimalist monochrome outlines.

### A.9 Manifest example (`theme.json` v2)

Every block is optional; v1 keys keep working (§4.3). Values are defaults the user's settings
override.

```json
{
  "name": "Y2K Cyber Lilac",
  "author": "XrossCommunity",
  "version": "2.0.0",
  "layout": {
    "crosshairX": 0.32,
    "crosshairY": 0.40,
    "cardAspectRatio": "16:9",
    "itemSpacingDp": 12,
    "focusedScale": 1.18,
    "unfocusedAlpha": 0.45,
    "statusBarMode": "EXPANDED_RIGHT_AVATAR"
  },
  "palette": {
    "canvas": "#1B1829",
    "surface": "#FFFFFF25",
    "surfaceBorder": "#FFFFFF60",
    "accent": "#FFB5C5",
    "accentSecondary": "#C8B6FF",
    "textPrimary": "#F4F4FB",
    "textSecondary": "#A39DB8"
  },
  "avatarStyle": {
    "borderColorHex": "#FFB5C5",
    "borderWidthDp": 2.5,
    "glowEnabled": true,
    "glowSpeed": 1.2,
    "glowIntensity": 0.7
  },
  "effects": {
    "shaderPath": "shaders/custom_wave.frag",
    "blurRadiusDp": 24.0,
    "glowEnabled": true,
    "glowColor": "#FFB5C5",
    "glowRadiusDp": 14.0,
    "glowSpeed": 1.2,
    "adaptiveGamePalette": true
  },
  "wave": {
    "speed": 0.85,
    "lineCount": 6,
    "turbulence": 1.1,
    "ribbonThickness": 1.3,
    "gradient": ["#C8B6FF", "#BBD0FF", "#B8F2E6"]
  },
  "typography": {
    "display": "fonts/RoundedBold.ttf",
    "body": "fonts/SansRegular.ttf",
    "mono": "fonts/SpaceMono.ttf"
  },
  "audio": {
    "bgmFile": "audio/ambient_loop.ogg",
    "bgmVolume": 0.35,
    "effectsVolume": 0.80,
    "pitchJitter": true
  }
}
```

The v2 `palette` has more roles than v1's `top` / `bottom` / `wave` / `glow`. Proposed mapping
for the v1 reader (to confirm in 2b.2): `top` → `canvas`, `bottom` → the wave gradient's last stop, `wave` → `accentSecondary`,
`glow` → `accent`; text colors keep their defaults.

### A.10 Test gates

Each milestone adds unit or Robolectric tests and keeps the full suite green:

- 2b.1 (done, `Phase2bFeaturesTest`, 20 tests): `avatarStyle` round trip, defaults and clamping;
  playtime formatting at 0 s, under a minute, 61 min and 120 h; Details paging at the first and
  last item and in a one-game list; telemetry only for recognized games; dashboard states.
- 2b.2: v1 packs load unchanged through the v2 reader; role fallbacks; layout clamping on the 12
  display configurations.
- 2b.3: palette extraction off the main thread, debounce, cache.
- 2b.4: shader compile failure and budget overrun fall back to the wave; uniforms bound.
- 2b.5: BGM never plays over music, ducks under snaps, stops on launch.
- 2b.6: haptics off by default and silent while a game runs; API 28 fallback.

On a device: L1/R1 paging on Details without popping a menu; the HUD expands and collapses
without layout jitter and gives the clock and battery back; the avatar glow doesn't recompose
anything outside the avatar.

---

## Appendix B — Design notes for the 2026-11-11 backlog

The seven backlog features were assessed against the code (Room schema v5, 206 tests at the time)
before any of them was built. All seven are done (§2). The decisions and follow-ups that still
matter:

**B.1 0% analog deadzone.** "Off — hardware only" still applies the device's reported
`InputDevice.MotionRange.flat`; true raw input isn't offered, because any noise would move the
cursor. The deadzone only affects Xross's menus; emulators apply their own. A drifting stick at 0%
can walk the cursor, but the D-pad keeps Settings reachable. Follow-up: per-controller deadzones
(§4.1).

**B.2 Custom Photo and Video folders.** An empty list means all of MediaStore. Listed folders are
filtered by path (`_data LIKE`, readable with All files access on API 28), and folders MediaStore
skips are walked directly, rescanned on resume and on hot-swap rather than with `FileObserver`
(unreliable on FUSE). `Xross/media` must keep its `.nomedia`, or scraped art floods Photo and
Video. Follow-up: Music folders (§4.2).

**B.3 Media controls shortcut and 30 s hide.** Select by default, plus a held Guide where the key
reaches the app: firmware on Retroid, AYN and Ayaneo devices often takes Guide, and as the HOME
launcher Xross only sees HOME as a new intent. The shortcut only works while Xross is in front;
catching keys over an emulator would need an accessibility service, which is out of scope.

**B.4 Hide apps and app folders.** Stored in `settings.json` (`apps.hidden`, `apps.folders`), not
in Room, so they travel with the config. Preset folder names avoid typing; uninstalled members are
skipped. Replaced the Phase 4 "App triage UI".

**B.5 All Games and the base ROMs folder.** Platform ids already match ES-DE's system names.
Platform node ids kept, only their parent changed. The base folder is also the future upload
target for the Portal and FTPS.

**B.6 Portable `Xross/` folder.**

- Credentials and the running-game session stay private (any app with All files access, or a
  computer over USB, can read the folder).
- Xross must start without storage access: a private bootstrap and settings mirror let it run
  from app storage until shared storage is writable, and when the chosen drive is removed.
- kotlinx.serialization with `"schema": 1`, `ignoreUnknownKeys` and explicit defaults; atomic
  writes; a file that fails to parse is kept as `.bad`.
- The live database stays private (FUSE write cost; SQLite on exFAT/FAT32 can corrupt when a card
  is pulled, which would defeat hot-swap). `Xross/data/xross.db` is a consistent snapshot. An
  opt-in live database on internal shared storage with `JournalMode.TRUNCATE` may come later,
  never on a removable drive.
- Media named by system and ROM stem, ES-DE style, because `games.uuid` is random per install;
  renaming a ROM orphans its art, as in ES-DE.

**B.7 Focused-game background priority.** Video snap, then hero art (or a screenshot, as Details
does), then the wave, with one hero decoded at a time and the wave paused while covered.
Follow-up: the Game backgrounds setting and the ramp down on leaving (§4.2).

**Local test media.** Fixtures live in the git-ignored `localTestMedia/` (`Roms/ARCADE`,
`Roms/3DS`, `Music/`, `Video/`); live tests read from it. `smoke.sh` generates its own files and
doesn't use it. Never commit anything from that folder.

---

## Appendix C — References

- Gameplay and UX reference video: <https://www.youtube.com/watch?v=014gbBBHZ4s>
- Design reference screenshots and mock-ups: `assets/examples/` (the RetroAchievements dashboard
  follows `Screenshot 2026-11-11 at 19.15.40.png`)
- PS3 and PSP manuals: `assets/docs/` (local only, git-ignored; see docs/XMB_FIDELITY.md)
- Repository: <https://github.com/luyisiludong-svg/Xross>
