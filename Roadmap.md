# Xross Roadmap

What has been built, what is left, and in what order, measured against the product scope in
[`project_scope.md`](project_scope.md). Updated **2026-11-11**.

Related documents:

- [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md): how close Xross is to the real XMB, checked
  against the PS3 and PSP manuals in `assets/docs`
- [`docs/STATUS.md`](docs/STATUS.md): feature-by-feature test results and performance data
- [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md): why some scope items were changed or dropped
- [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md): how to test on a real handheld
- [`docs/BACKLOG_ASSESSMENT.md`](docs/BACKLOG_ASSESSMENT.md): the seven backlog features of
  2026-11-11 checked against the code, with the design decisions behind their rows below

**Legend:** ✅ done · ◐ partly done · ⬜ not started · 🧪 verified only on the Android emulator or
with stub emulators · 📱 verified on a real device

---

## 1. Where we are

| Milestone | Scope | State |
| --- | --- | --- |
| **Phase 1 — Alpha** | XMB navigation, gamepad layer, ROM library, emulator launching, wave background | ✅ Complete |
| **Phase 1b — Alpha hardening** | Hot-swap, USB-OTG, playtime, Options menu, per-game emulator, more emulators | ◐ Features done, including per-controller mappings, the axial deadzone and a 0% ("hardware only") deadzone; real-handheld testing and a Baseline Profile remain |
| **Phase 2 — Beta** | Metadata, media hub, theme packs, on-screen controller and keyboard, portable `Xross/` folder, library organization | ◐ Fingerprinting, media hub, gamepad search, DAT verification, scrapers, Details screen and video snaps done (ScreenScraper awaits developer credentials). The portable `Xross/` folder landed on 2026-11-11: JSON config, database backup and restore, `.nomedia`, and the `GameLauncher` interface. All Games, the media controls shortcut and the 30 s mini-player hide are done too. Library organization followed: hidden apps, app folders, the base ROMs folder with Create system folders, custom Photo/Video folders, and scraped art moved to `Xross/media`. Theme packs, the on-screen controller and wave settings are not started |
| **Phase 3 — Release candidate** | Dual screen, docking, RetroAchievements, Xross Portal, FTPS | ⬜ Not started (hot-swap and live resolution change, two prerequisites, are done) |
| **Phase 4 — v1.0** | Onboarding, tuning, theme/plugin format, release | ◐ Performance work started (app triage moved to Phase 2 as app folders) |
| **XMB fidelity** | Look and feel exactly like the XMB, plus Xross's extras | ◐ Home menu layout, motion and navigation match, and games now show their own box art; the typeface, sounds and several system behaviors don't match yet (§4.5) |

**Codebase at a glance:** Kotlin + Jetpack Compose, one `:app` module, Room database at schema v5,
22 platforms, 11 emulator launch recipes, 8 color themes (one follows the month), three metadata
services (Hasheous, SteamGridDB, ScreenScraper), settings in three JSON files in `Xross/config/`
(kotlinx.serialization), art in `Xross/media` in ES-DE's layout, 320 unit tests (318 run by default
plus 2 opt-in live-service tests), an
on-device smoke test (updated for the new rows, not yet re-run, §3), and test-only stand-ins for
all 11 emulators.

**Testing so far:** One real device has run Xross: a Samsung Galaxy A55 (Android 16, 120 Hz). On
2026-11-11 it ran the full Step 1 and Step 2 feature set with 12 arcade sets and 2 3DS images:
scanning, fingerprinting, live scraping (Hasheous and SteamGridDB), DAT verification, art, the
Details and Information screens, manual matching, and a release-like (R8) build's frame pacing
and memory. No controller, gaming handheld or real emulator install has been tested yet, and
ScreenScraper hasn't run live (it needs developer credentials).

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
| Media controls shortcut | ✅ 🧪 | **Select** toggles Now Playing from the XMB, an Options menu, Information or Details; holding **Guide** for 0.5 s does the same where the key reaches the app. Remappable; search, the photo viewer and the video player ignore it |
| Options (△ / Y) context menu with submenus | ✅ | Long-press opens it on touch |
| Touch parity | ✅ | One category per horizontal swipe, continuous vertical drag, tap, long-press |
| First D-pad press after a touch isn't lost | ✅ | |
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
| Base ROMs folder | ✅ | Settings ▸ Library ▸ Base ROMs folder… (default `Xross/roms`); always scanned, beside auto-detection and added folders. **Create system folders** makes the 22 system folders, named by ES-DE's ids |
| "All Games" folder | ✅ | Games ▸ All Games holds the platform folders; Search, Recently Played, Favorites and Android Games stay at the top. Settings ▸ Theme ▸ Platform folders brings back the flat list |
| Portable `Xross/` folder | ✅ 🧪 | `config/`, `data/`, `dats/`, `media/`, `roms/`, `themes/` on primary storage, or on an SD card or USB drive (Settings ▸ System ▸ Xross folder). App storage until shared storage can be written; config already in the folder is adopted. `.nomedia` in `media/` and `themes/` is created first |
| Settings as JSON | ✅ 🧪 | `settings.json`, `controllers.json`, `systems.json`; atomic writes (`.tmp`, sync, rename) off the main thread; one-time migration from SharedPreferences; unreadable files kept as `.bad`; edits made outside Xross picked up on resume. Credentials stay in the private `CredentialStore` |
| Library database backup | ✅ 🧪 | Live database stays private. A snapshot goes to `Xross/data/xross.db` 4 s after Xross leaves the screen (if anything changed) or from Settings ▸ System ▸ Back up library now. On a clean install a backup with games is offered from Games and Settings ▸ System; restore applies on restart |
| Playtime per game | ✅ | Survives process death; shown on rows and in the Options menu |
| Information screen (Options ▸ Information) | ✅ 🧪 | Platform, file, folder, size, dates, playtime, emulator, fingerprint source and CRC32/MD5/SHA-1 |
| Content fingerprints (CRC32, MD5, SHA-1) | ✅ | Inside `.zip` / `.7z`; CHD SHA-1 from the header; No-Intro header-less hashes; background job that stops while a game runs |
| Title search (FTS4) | ✅ | Word-prefix matching |

### 2.3 Launching games

| Feature | State | Notes |
| --- | --- | --- |
| RetroArch with a core per platform | ✅ 🧪 | |
| DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | |
| Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM | ✅ 🧪 | Launch recipes from ES-DE's published Android rules |
| Emulator per platform (Settings ▸ Emulators) | ✅ | |
| Emulator per game (Options ▸ Emulator) | ✅ | Game override → platform choice → first installed |
| ROM access for emulators | ✅ | File path extra plus a `content://` URI with a read grant |
| `GameLauncher` interface | ✅ | UI and repositories see launch plans only; Intents, `content://` grants and package queries live in `AndroidIntentGameLauncher`. A failed start no longer counts as playtime |
| Android games and apps | ✅ | Games (`CATEGORY_GAME`) go to Games ▸ Android Games; everything else to Apps |
| Hide apps | ✅ | Options ▸ Hide on any app in Apps or Games ▸ Android Games; Settings ▸ Apps ▸ Hidden apps shows them again (one by one or all); Hide system apps switch. Stored as `apps.hidden` in `settings.json` |
| App folders | ✅ | Options ▸ Move to folder: Emulators, Streaming, Tools, folders already in use, or New folder… typed on the gamepad keyboard. Known emulator packages are suggested for Emulators, and a "Group emulators" row moves them all at once. Folders sit at the top of Apps while they hold an app |

All 11 recipes are verified only against the test stand-ins, not real emulator installs.

### 2.4 Media hub

| Feature | State | Notes |
| --- | --- | --- |
| Photo, Music and Video from MediaStore | ✅ | Folders, tags, EXIF, thumbnails, album art |
| Photo viewer | ✅ | Zoom up to 5×, pan, slideshow (3–20 s), set as wallpaper |
| Music player as a background service | ✅ | Media3 + MediaSession: notification, headset and lock-screen controls; albums, artists, shuffle, repeat |
| Background music pauses for games and videos | ✅ | Resumes when Xross is back in front |
| Video player | ✅ | Seek, previous/next, loop, auto-hiding controls |
| Clear empty state without storage access | ✅ 🧪 | Photo, Music and Video say "Storage access needed"; pressing the row opens the grant screen |
| Custom Photo and Video folders | ✅ 🧪 | Settings ▸ Media ▸ Photo folders / Video folders. Empty = all of MediaStore; otherwise MediaStore filtered by path plus a direct walk for files it skips (`.nomedia` and hidden folders, unindexed drives), with thumbnails made by Xross. Reloads on resume and when a drive comes or goes |
| Mini-player line hides after 30 s paused | ✅ | The corner "❚❚ Title — Artist" line fades out (0.9 s) and returns as soon as playback resumes |

### 2.5 Look and sound

| Feature | State | Notes |
| --- | --- | --- |
| Wave background (GLSL ES 3.0) | ✅ | Speed setting (0.5×–2×); stops whenever Xross isn't in front |
| Day/night brightness shift | ✅ | |
| Color themes | ✅ | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original (a color per month, blended over the days around each month boundary) |
| Icons from the design SVGs | ✅ | 28 VectorDrawables generated by `tools/icons/svg_icons.py` |
| Focus glow, halos, drop shadows | ✅ | Glow no longer gets clipped into a box while categories switch (fixed 2026-11-10) |
| Cold-boot fade-in and startup sound | ✅ | Can be turned off |
| Busy indicator while scanning or fingerprinting | ✅ | Ring of dots in the bottom corner; appears after 0.4 s, steps 10×/s |
| Hide the button-hint pill | ✅ | Settings ▸ Theme ▸ Button hints |
| UI sounds, volume, game-start cue | ✅ | |

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
| 120 Hz | ✅ 📱 | Galaxy A55 at 120 Hz, optimized build, scrolling art lists: 1.26% janky frames, median 6 ms, 99th percentile 18 ms, no missed vsync. Not yet measured on a 120 Hz handheld |
| Smooth list scrolling | ✅ 📱 | Galaxy A55, optimized build: 0–0.6% janky frames in a long Apps list; 1.26% in a list with box art |
| Fewer recompositions per cursor move | ✅ | Only rows whose focus changes are rebuilt (~75% less work per press) |
| Cold boot to interactive | ✅ 📱 | First frame 1.2 s on the Galaxy A55 (optimized build, cold process); ~200 ms on the emulator. The fade-in adds 1.75 s by design |
| Memory with another app in front | ✅ 📱 | 83 MB PSS on the Galaxy A55 with art loaded (target ≤ 150 MB); decoded art is released when the UI is hidden. 181–216 MB while in front |
| Test tooling | ✅ | Unit tests, opt-in live scraper tests, adb smoke test, emulator stand-ins, `profiling` build type |

### 2.8 Metadata and art

| Feature | State | Notes |
| --- | --- | --- |
| DAT verification (No-Intro, Redump, MAME) | ✅ 📱 | Logiqx XML and clrmamepro text, plain or zipped, from `/sdcard/Xross/dats`. Matches by SHA-1, MD5, CRC32 + size, or arcade set name; sets title, region, languages, revision and serial |
| Hasheous identification | ✅ 📱 | By hash, no account. Arcade sets send their largest ROM chip and pick the set by name |
| SteamGridDB art | ✅ 📱 | Capsule (600×900), hero banner and logo, by title; needs a free API key |
| ScreenScraper | ✅ 🧪 | Descriptions, box art, logos, screenshots and video snaps; quota handling as the API requires. Built and unit-tested; not yet live (needs developer credentials) |
| Background scraping | ✅ 📱 | Off until turned on, Wi-Fi only by default, paced per service, stops while a game runs |
| Art in `Xross/media/<kind>/<system>/<rom stem>.<ext>` | ✅ 🧪 | ES-DE-style names (`covers`, `3dboxes`, `heroes`, `wheels`, `screenshots`, `capsules`, `snaps`), FAT-safe and de-duplicated per system; media copied in from ES-DE is used as is and never pruned. The old private UUID cache moves over once in the background. The UUID cache was verified on the Galaxy A55; the new layout is unit-tested only |
| Game Details screen | ✅ 📱 | Options ▸ Details or → on a game: background art, box art, logo, release facts, rating, playtime, description |
| Manual match and title override | ✅ 📱 | Options ▸ Metadata: Edit title, Find match, Scrape again, Use automatic match |
| Video snaps behind the menu | ✅ 🧪 | Plays after 1.2 s on a game, music ducked; untested with real snaps (ScreenScraper only) |
| Service credentials | ✅ | Stored apart from settings and excluded from backup; `.env` pre-fills them in debug builds only (see `.env.example`) |

---

## 3. Known issues to fix

These are bugs or gaps in features that are already built, separate from the new work in §4.

| Issue | Impact | Fix |
| --- | --- | --- |
| No real-hardware validation of controllers, sticks, emulators or audio | Biggest risk to Beta | Phase 1b hardware pass (§4.1) |
| `tools/device-tests/smoke.sh` hasn't been re-run | Counts were updated on 2026-11-11 for the Options menu (Details, Metadata), Controls (Axial deadzone), All Games, the 10-step remap wizard and the new Library rows, and it gained checks for the new features; no emulator run yet | Re-run on an emulator |
| Library organization untested on a device | App hiding and folders, the base ROMs folder, custom media folders and art in `Xross/media` are unit- and Robolectric-tested only | Include in the next device pass, with an SD card |
| Folder names are typed in capitals | The gamepad keyboard has no lower case; "MY TOOLS" is saved as "My Tools" and short words like "TV" stay capitals | Keyboard modes (§4.5) |
| Portable folder untested on real storage | Atomic replace on FAT/exFAT cards, the restore-and-restart flow and the backup on Android 9 are unit-tested (Robolectric) only | Check on a device with an SD card, and on an Android 9 or 10 device |
| Config writes are asynchronous | A crash within milliseconds of changing a setting can lose that one change | Acceptable; writes are flushed before restores and folder moves |
| ScreenScraper can't run yet | No descriptions, box art from ScreenScraper, or video snaps | Request developer credentials on the ScreenScraper forum; put them in `.env` |
| Per-controller mappings and the axial deadzone are untested on hardware | Unit- and view-model-tested only | Test with two different controllers in the hardware pass |
| Text dialogs (account, key, title, search) use the system keyboard | On the Galaxy A55 the keyboard once didn't open until the field was tapped; later dialogs opened it by themselves | Replace with the gamepad keyboard (Step 4) |
| About 41% CPU while idle in front at 120 Hz | The wave renders continuously; costs battery on handhelds | Drop to 60 Hz or pause the wave while nothing moves |
| Six newer emulator recipes may not accept a FileProvider URI | Launches could fail on real installs | Test each against a real install; fall back to the path extra |
| Multi-file disc images (`.cue` + `.bin`) | One `content://` URI doesn't cover the `.bin` tracks | Rely on the emulator's own storage access and the path extra |
| `snd_error.wav` is silent, and there's no dedicated cursor sound | Errors and moves reuse other sounds | Replace with the theme-pack sound set (§4.2) |
| Search matches word prefixes only, Latin letters and digits only | "rono" doesn't find "Chrono Trigger" | Add substring or trigram matching if users need it |
| Playtime counts all wall-clock time away from Xross after a launch | Sleep and other apps count (capped at 24 h) | Acceptable for now; revisit with usage stats |
| Search results at 80% opacity clip their text shadow slightly | Barely visible | Switch to `fadeAlpha` when that screen is next touched |

---

## 4. Not yet implemented

### 4.1 Phase 1b — finish alpha hardening (next)

| Item | Notes |
| --- | --- |
| Test on 2–3 real handhelds | For example a Retroid Pocket, an AYN Odin and a 4:3 Anbernic: controllers, sticks, frame pacing, audio, and real RetroArch, DuckStation, Dolphin, PPSSPP and NetherSX2 launches. Follow `docs/HARDWARE_TESTING.md` |
| Confirm the six newer recipes on real installs | Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM |
| Baseline Profile | Faster startup and less jank before the JIT warms up |
| 0% analog deadzone ✅ | Done 2026-11-11 (§2.1). Still optional: deadzones per controller model next to its button mapping |

### 4.2 Phase 2 — Beta

**Metadata (the largest remaining piece)**

| Item | Depends on | Notes |
| --- | --- | --- |
| DAT import (No-Intro and Redump Logiqx XML) ✅ | Fingerprints ✅ | Logiqx XML and clrmamepro text, also inside .zip; SHA-1 > MD5 > CRC32+size > set name; titles, regions, languages, revision, serial |
| Per-track hashing for `.cue` / `.gdi` | | Redump matches tracks, not sheets |
| ScreenScraper scraper ✅ 🧪 | Developer credentials | Built to the API page's rules (translated in `docs/SCREENSCRAPER_API.md`); waiting for developer credentials (`SCREENSCRAPER_DEV_ID` in `.env`). Canned responses only so far |
| SteamGridDB scraper ✅ 📱 | Per-user API key | Hero banners, 600×900 capsules, logos. Verified live on the Galaxy A55 |
| Hasheous matching ✅ 📱 | | Hash lookup, no account. Verified live on the Galaxy A55, including arcade sets |
| Language priority list | Scrapers | ◐ One region and one language setting today; a full priority list (for example `es-ES, es, en-US, en`) is still to do |
| Throttled background batch scraper ✅ | Scrapers | Per-service rate limits, quota handling, opt-in, Wi-Fi only by default; stops while a game runs |
| Disk asset cache ✅ | Scrapers | Atomic writes, content sniffing, pruning. Moved from a private UUID cache to `Xross/media` (Milestone 3) |
| Game detail view ✅ | Scrapers | Options ▸ Details or → on a game |
| Manual match and override tool ✅ | Scrapers | Options ▸ Metadata: edit title, find match, scrape again, reset |
| Video snap previews on focused games ✅ | Scraper snaps | Full-bleed behind the menu after 1.2 s; music ducked |

**Storage foundation: one portable `Xross/` folder**

All configuration, art and backups in `/storage/emulated/0/Xross/` (`config/`, `data/`, `dats/`,
`media/`, `roms/`, `themes/`), so a library can be backed up, inspected over USB and moved to
another device. Details and the reasons for each decision: `docs/BACKLOG_ASSESSMENT.md` §6.

| Item | Depends on | Notes |
| --- | --- | --- |
| Private bootstrap and settings mirror ✅ | — | `XrossEnvironment`: app storage until shared storage can be written; falls back to primary storage if the chosen drive is removed |
| `config/settings.json`, `controllers.json`, `systems.json` ✅ | Bootstrap | kotlinx.serialization 1.8.1, schema version 1, atomic writes; one-time migration from SharedPreferences. **Credentials never go here**: they stay in private `CredentialStore`, since any app with All files access can read the folder (a test checks this). `systems.json` takes the emulator choice and extra folder names and extensions per system; its `builtIn` block is reference only |
| `.nomedia` in `media/` and `themes/` ✅ | — | Created before anything else in those folders |
| Move scraped art to `media/<kind>/<system_id>/<rom stem>.<ext>` ✅ | Config | Done 2026-11-11 (§2.8). Names cleaned for FAT32/exFAT; clashes in one system (FAT ignores case; `Game.zip` vs `Game.7z`) get a UUID suffix; art follows the Xross folder when it moves |
| Database backup at `data/xross.db` ✅ | Config | The live database stays private. `PRAGMA wal_checkpoint(TRUNCATE)`, then the file is copied while writers are held off (`BEGIN IMMEDIATE`), so it works on Android 9 (`VACUUM INTO` needs API 30). The copy is switched to rollback-journal mode, verified, and renamed into place. Offered for restore on a fresh install; the restore is applied on the next start, before Room opens the database |
| `GameLauncher` behind an interface ✅ | — | `GameLauncher` interface plus `AndroidIntentGameLauncher`; desktop support isn't planned |

**Library organization**

| Item | Depends on | Notes |
| --- | --- | --- |
| Hide apps ✅ | Settings JSON ✅ | Done 2026-11-11 (§2.3) |
| App folders ✅ | Settings JSON ✅ | Done 2026-11-11 (§2.3). Replaces Phase 4 "App triage UI" |
| "All Games" folder ✅ | — | Done 2026-11-11 (§2.2) |
| Base ROMs folder ✅ | Portable folder ✅ | Done 2026-11-11 (§2.2). Still to come: the upload target for the Portal and FTPS |

**Media hub**

| Item | Depends on | Notes |
| --- | --- | --- |
| Custom Photo and Video folders ✅ | `.nomedia` in `Xross/media` ✅ | Done 2026-11-11 (§2.4) |
| Media controls shortcut ✅ | — | Done 2026-11-11 (§2.1). Not available over a running emulator |
| Hide the paused mini player after 30 s ✅ | — | Done 2026-11-11 (§2.4) |

**Customization**

| Item | Notes |
| --- | --- |
| Theme pack format and loader | Icons, sounds (cursor, confirm, back, error, boot), fonts, color palettes; packs live in `Xross/themes/` |
| Move bundled assets into a default pack | Required before any public release (bundled sounds and icons resemble PS3/PSP assets) |
| Wave parameters: line count, turbulence, thickness, custom gradients | The shader is ready for the uniforms; needs settings UI |
| Battery-level color blending | Battery level is already read for the status bar |
| Custom boot animations | |
| Separate volume sliders for UI sounds, music and video snaps | Music currently uses the system media volume |
| UI scale slider (50–150%) | Layout already scales automatically |
| Custom fonts | Part of theme packs |
| Status-bar mode options (transparent, dark, hidden) | Immersive (hidden) today |
| Aspect-ratio overrides (letterbox / pillarbox) | |

**Input**

| Item | Notes |
| --- | --- |
| On-screen controller overlay | For touch-only devices: opacity, haptics, size, position snapping |
| Per-game button remapping | |
| Playlists and custom tags | For example "Multiplayer" or "Completed" |

### 4.3 Phase 3 — Release candidate

| Item | Notes |
| --- | --- |
| Dual screen | Presentation API; second-screen layouts for details, box art, achievements, music and telemetry; screen-swap hotkey; per-screen orientation (must work with the landscape lock) |
| Dock detection | `DisplayManager`; live resolution switching already works |
| `autorun.inf` manifests | **Parse only**: label, icon, scan paths. Never execute anything from removable media |
| Volume labels and drive icons | |
| RetroAchievements | Login with an encrypted token, progress in game details, unlock toasts in the launcher. An overlay over a running emulator is optional, since RetroArch shows its own |
| HTTPS Xross Portal | Ktor CIO engine: PIN login, ROM and BIOS upload, library editing, diagnostics. Self-signed TLS means browser warnings |
| FTPS server | Apache FtpServer with TLS; under 3% CPU when idle; only runs when enabled |

### 4.4 Phase 4 — v1.0

| Item | Notes |
| --- | --- |
| First-run onboarding | Storage access, folder discovery, emulator detection, first scrape |
| Performance and thermal tuning on target hardware | Snapdragon 665 / Unisoc T618 class |
| Input-latency measurement | Target ≤ 16.6 ms; needs high-speed camera capture |
| Public theme and plugin format, with documentation | Builds on the Phase 2 theme pack format |
| Release signing and opt-in crash reporting | The release build type has no signing config yet |
| Play Store / F-Droid compliance review | `MANAGE_EXTERNAL_STORAGE` is a Play review risk; bundled assets must be replaced |
| Orientation setting | Optional: let users unlock rotation (for example portrait phones or vertical handhelds) |

### 4.5 XMB fidelity

From the assessment in [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md). **Verdict: not there yet.**
The home menu already reads as an XMB at a glance, but the Games lists, the typeface, the sounds
and several system behaviors described in the PS3 and PSP manuals don't match yet. Ordered by how
much each item adds to the feel:

| Item | Depends on | Notes |
| --- | --- | --- |
| Background on a focused game: video, then hero art, then wave | Scraper art ✅ | ◐ Video snaps already play behind the menu after 1.2 s. Missing: the hero banner crossfading in first (after ~250 ms, so fast scrolling doesn't decode every banner), the snap fading in over it, its sound ramping in and out, and the wave paused while art covers it (also cuts idle CPU). Setting: Video and art / Art only / Off. No real snaps to test with yet (`localTestMedia/Video` is empty) |
| XMB-style rounded typeface | Theme pack loader for user fonts | Bundle an open-licensed font as the default; the Android default font is the most visible difference today |
| Resume Game / Quit Game while an emulator Xross launched is running | — | Equivalent of the PS button's Quit Game and the PSP Go's Pause/Resume Game |
| Distinct cursor sound and a working error sound | Sound packs (§4.2) | Moves reuse the options sound; `snd_error.wav` is silent |
| XMB-style drill-down (folder icon beside its contents, no text breadcrumb) | — | |
| Control panel on △ in the video and music players | — | Alongside the existing direct controls; pairs with the media controls shortcut (§4.2) |
| Photo or theme background behind the menu | Theme packs (§4.2) | "Set as wallpaper" currently sets the Android wallpaper |
| Connected drives as items in their categories | Hot-swap ✅ | |
| Keyboard input modes and an optional multi-tap keypad | — | Lower case, accented letters, symbols |
| Delete, sort and Display All in the options menu | — | |
| Screensaver when docked or idle on a TV | Dock detection (§4.3) | |

Xross's sounds, icons and font must stay original or open-licensed by default, with Sony-like
assets loadable only by the user through theme packs.

---

## 5. Scope changes

Decided in [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md). These replace the original wording in
`project_scope.md`.

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
| Free rotation | Landscape only (`sensorLandscape`) | Handheld-first; large screens can still rotate |
| Live database in the portable `Xross/data/` folder (backlog) | Private live database plus a backup copy in `Xross/data/` (decided 2026-11-11) | FUSE slows writes; SQLite on exFAT/FAT32 can corrupt when a drive is pulled |
| All state in `Xross/config/settings.json` (backlog) | Everything except credentials and the running-game session | The folder is readable by any app with All files access |
| Guide / Home long-press for media controls (backlog) | Select by default, Guide where it reaches the app | The system or handheld firmware often takes Guide; HOME never arrives as a long press |
| `VACUUM INTO` for the database backup (backlog) | WAL checkpoint plus a copy under `BEGIN IMMEDIATE` | `VACUUM INTO` needs SQLite 3.27 (API 30); Xross supports Android 9 |
| App triage UI (Phase 4) | App folders with a suggested Emulators folder (Phase 2) | One mechanism instead of two |

---

## 6. Order of work

```
Phase 1b                  Phase 2 (Beta)                                  Phase 3 (RC)            Phase 4 (v1.0)
────────                  ──────────────                                  ────────────            ──────────────
Real-hardware pass ──┬──▶ DAT import ✅ ─▶ Scrapers ✅ ─▶ Detail view ✅ ┬─▶ RetroAchievements ──▶ Onboarding
                     │                        │                     └──▶ Xross Portal (editing)
                     │                        └──▶ Video snaps ✅
                     ├──▶ Theme pack format ──▶ Sounds, fonts, wave params ─────────────────────▶ Public theme format
                     ├──▶ Per-controller mappings, axial deadzone ✅ ──▶ 0% deadzone ✅
                     ├──▶ Baseline Profile                                Dual screen, docking
                     │                                                    FTPS (independent)
                     └──▶ Settings JSON in Xross/ ✅ ─┬─▶ App hide and folders ✅
                                                      ├─▶ Custom Photo/Video folders ✅
                                                      ├─▶ Art moved to Xross/media ✅
                                                      ├─▶ Database backup ✅
                                                      ├─▶ Theme pack format (reads Xross/themes)
                                                      └─▶ Base ROMs folder ✅ ─▶ Portal / FTPS upload target

Independent, any time: All Games folder ✅ · media controls shortcut ✅ · 30 s mini-player hide ✅ ·
focused-game background (hero art, then video), which pairs with cutting idle CPU
```

- **The hardware pass gates the Beta.** Controller handling, emulator launches and frame pacing
  are only proven on the emulator.
- **DAT import comes before scrapers.** Exact offline titles make scraper lookups more accurate
  and cut API calls.
- **The theme pack format comes before any public release**, because the bundled assets have to
  move out of the app.
- **FTPS, dual screen and docking don't depend on the metadata work** and can be done in
  parallel with it.
- **Settings move to `Xross/config/` before new structured settings land.** App folders, media
  folder lists and per-controller deadzones are awkward in SharedPreferences and would otherwise
  be migrated twice.
- **`.nomedia` must exist before any art moves to shared storage**, or scraped art floods the
  Photo and Video columns.

### Suggested next steps

1. Run the real-hardware pass on a handheld with a controller and real emulators (also covers
   per-controller mappings and the axial deadzone).
2. Get ScreenScraper developer credentials and run `ScreenScraperLiveTest`, then check
   descriptions, box art and video snaps on a device.
3. ✅ Quick backlog wins (0% deadzone, 30 s mini-player hide, media controls shortcut, All Games)
   and the `smoke.sh` update. Still to do: re-run `smoke.sh` on an emulator.
4. ✅ The portable folder: bootstrap, `settings.json`, `controllers.json`, `systems.json`,
   `.nomedia`, the database backup and the `GameLauncher` interface. Still to do: check it on a
   device with an SD card and on Android 9 or 10.
5. ✅ On top of it: app hide and folders, custom Photo/Video folders, the base ROMs folder, and
   art in `Xross/media`. Still to do: a device pass with an SD card.
6. Step 3: theme pack format (in `Xross/themes`), with the bundled assets moved into a default
   pack, and shader settings. An XMB-style default font comes with it.
7. Focused-game background (hero art, then video), together with pausing the wave under it and
   a Baseline Profile.

---

## 7. Performance targets

From `project_scope.md` §5.

| Target | Result |
| --- | --- |
| ≥ 60 fps menu navigation | ✅ 🧪 All 12 display configurations |
| 120 fps on high-refresh panels | ✅ 📱 Galaxy A55 at 120 Hz: 1.26% janky frames, median 6 ms, no missed vsync; no 120 Hz handheld tested |
| Input-to-screen ≤ 16.6 ms | ⬜ Not measured |
| Cold boot to interactive < 2.5 s | ✅ 📱 1.2 s to first frame on the Galaxy A55 (optimized build); ~200 ms on the emulator |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 📱 83 MB PSS on the Galaxy A55 with art loaded (42 MB on the emulator before art existed) |
| GL loop suspended when Xross isn't in front | ✅ 📱 0% CPU in the background on the Galaxy A55 |
| Background daemons < 3% CPU when idle | ⬜ No daemons yet |
