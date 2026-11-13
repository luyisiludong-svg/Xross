# Project Xross — Implementation Status

Status of the codebase against [`project_scope.md`](../project_scope.md), as of **2026-11-13**
(commit `41f64dc`, Phase 2b.4). It is the evidence file: what has been built, how it was verified,
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
| Phase 2b.3 (color intelligence): game-art palette, `rules` context engine, circadian curve, battery tint, quick profiles | ✅ on `feat/theme-2b3` (2026-11-12): 499 unit tests; 171/171 on the API 37 emulator (§3.19, §3.20); on the **Galaxy A55 at 120 Hz** 160/171 (the 11 are seeded-media checks), game navigation 0.12–0.19% janky with game-art colors, GPU p50 6 ms; wheel-only art, Details as the source and a rule's layout patch checked on the device (§3.21). Idle CPU at rest is 16–21% (target < 10%) except under E-Ink (0%). Open items: [`PHASE_2B3_PENDING_TESTS.md`](PHASE_2B3_PENDING_TESTS.md) |
| Phase 2b.4a (shader host & watchdog): multi-program GL host, public uniforms, `ASCII_MATRIX` / `BOKEH` / `STARFIELD` / `CRT` / `CUSTOM` presets, ahead-of-time compile, frame-budget watchdog, crash-loop canary | ✅ on `feat/theme-2b4` (2026-11-12): 535 unit tests, `validateShaders` (glslangValidator); `smoke.sh` 12/12 and the theme suite 88/88 on the API 37 emulator; `smoke.sh` 12/12 on the **Galaxy A55** (profiling build, timer-query watchdog): presets 1.3–3.0 ms GPU p90, a custom shader, hot reload, a compile error, the watchdog and crash recovery checked by hand on both (§3.22) |
| Phase 2b.4b (audio-reactive uniforms): `uAudioBass` / `uAudioBeat` from Xross's own music player, no `RECORD_AUDIO`; a tap in ExoPlayer's audio sink, 150 Hz 4th-order low-pass, beat detector, latency-compensated ring buffer | ✅ on `feat/theme-2b4` (2026-11-12): 558 unit tests; media suite 24/24 (two new checks: the music drives both uniforms, a pause binds 0) and `smoke.sh` 12/12 on the API 37 emulator, where the analysis ran 475–507 ms ahead of the speaker and was compensated. **Galaxy A55** (2026-11-13): media checks pass; the user's own music drives both, 369–406 ms ahead of the speaker (§3.23, §3.28) |
| Phase 2b.4c (wallpapers and frosted surfaces): `wallpaper.png` / `effects.wallpaperPath`, Set as Xross wallpaper (`display.customWallpaper`), the shader resting under an opaque wallpaper, `backdropBlurDp` as a pre-blurred slice on every Android version | ✅ 🧪 on `feat/theme-2b4` (2026-11-12): 578 unit tests; theme suite 94/94, media suite 31/31 and `smoke.sh` 12/12 on the API 37 emulator, frosting checked by eye. **Galaxy A55** (2026-11-13): a 3220×4830 photo decodes to 1610×2415, blurred copy in 165–192 ms off the main thread (§3.24, §3.28) |
| Phase 2b.4d (focus frames and icon masks): `effects.focusFrameStyle` (`BREATHING_HALO`, `STATIC_NEON`, `HARD_SHADOW`, `GOLD_BEZEL` in `palette.focusGold`), `effects.iconMask` (`SQUIRCLE`, `CIRCLE`, `Y2K_CHROME_FRAME`) and `effects.iconFilter` (`DUOTONE`, `MONOCHROME`) through the cascade, icons cached by package, style and size | ✅ 🧪 on `feat/theme-2b4` (2026-11-12): 603 unit tests; theme suite 98/98 and library suite 41/41 on the API 37 emulator; Apps list 0.5% janky on the profiling build with the cache cold or warm. **Galaxy A55** (2026-11-13): real apps' icons 3–11 ms each (chrome), 2–4 ms (squircle), off the main thread (§3.25, §3.28) |
| Phase 2b.4e (foreground particles): `effects.foregroundParticles` (`SNOW`, `BOKEH`, `DUST_MOTES`) as layer 4, ≤48 particles in flat arrays drawn in one `graphicsLayer`; resting after 10 s without input, in the background, and hidden under a snap, full-screen media and E-Ink | ✅ 🧪 on `feat/theme-2b4` (2026-11-13): 622 unit tests; theme suite 102/102 on the API 37 emulator; profiling build: UI thread 0.3 ms p50 per frame with or without particles, 0.2–0.4% janky in most windows (as without). **Galaxy A55** (2026-11-13): 120 Hz, 0 frames at rest, hidden under a playing snap; during game navigation 7 of 16 passes doubled the render time (deadline jank unchanged, §3.28) |
| Phase 2b.4f (post-processing and Settings rows): `effects.backgroundFilter` (`SCANLINES`, `CRT`, `LCD_GRID`) as a GL pass snapped to render pixels, `effects.uiFilter` (`SCANLINES_UI`, `BLOOM`, custom `postprocess.agsl`) as an AGSL `RenderEffect` on Android 13+, Settings ▸ Theme rows for every 2b.4 effect (`display.effects`), Pick custom wallpaper, Reset to theme defaults, the `effects` schema | ✅ 🧪 on `feat/theme-2b4` (2026-11-13): 645 unit tests; 226/226 device checks on the API 37 emulator (theme 134/134); `palette-perf.sh` 0.30–0.45% janky, the same as the build before it, and 0.30–0.44% with CRT and Bloom on. **Galaxy A55** (2026-11-13): 645 unit tests, `lintDebug` 0 errors, **215/226** device checks (theme 134/134; the 11 are seeded-media checks), every slice checked by hand, `palette-perf.sh` 0.12–0.19% janky (0.06–0.19% with CRT and Bloom) (§3.28). **2b.4 is complete on the emulator and the A55** |
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
of 139 (§3.18). Since §3.20 those checks are five standalone suites (171, all passing on the
emulator) behind a 12-check quick `smoke.sh` and a `run-all.sh` runner. ScreenScraper and RetroAchievements sign-in ran
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

### 3.1 Unit tests — 464 tests, 0 failures (2026-11-12; 578 on `feat/theme-2b4`, §3.22–§3.24)
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
It checks screen content through the accessibility tree. (Since 2026-11-12 these checks live in
five suites run by `run-all.sh`, and `smoke.sh` is a 12-check quick test: §3.20.) On 2026-11-11 its key counts were
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

On branch `feat/theme-2b3` (from `feat/theme-2b2`). Spec: Roadmap §4.3,
Appendix A.4, A.7, A.13, docs/THEME_RICING_AND_WEB_STUDIO.md §3.4. Everything not yet verified is
listed in [`PHASE_2B3_PENDING_TESTS.md`](PHASE_2B3_PENDING_TESTS.md).

| Feature | How it works |
| --- | --- |
| Game-art palette | `androidx.palette:palette-ktx` 1.0.0. `GamePaletteExtractor` (app-scoped in `AppContainer`): the focused game's art (or the open Details game's), keyed `uuid/artVersion`, debounced 250 ms (the hero fade delay) with `collectLatest`, decoded to ~192 px from `media/heroes`, then `wheels`, then `screenshots`, and analyzed on `Dispatchers.Default`. Results, including "no usable colors", sit in a 100-entry LRU cache. `GameColors.from` maps vibrant (or dominant) to `accent`, light-vibrant (or muted) to `accentSecondary`, dark-muted (or the dominant darkened to ≤ 35% value) to `canvas`. All-grey art or a glow under 3:1 contrast against the canvas falls back to the theme. Guarded by `effects.adaptiveGamePalette` (default on) and Settings ▸ Theme ▸ **Adaptive colors** |
| Morphing | The wave eases to the new colors on the GL thread (~95% in 300 ms, the 2b.2 crossfade). In Compose, `ThemeAccent` holds animated colors read in the draw phase: the avatar's focus halo and style glow (leaning 60% toward the art accent), and the status-bar battery outline. Glow-text bloom (`GlowBloom`) switches straight to the new color (`ThemeAccent.haloTarget`) instead of morphing: its color is a text shadow's, and animating it re-blurred the text every frame (see Benchmarks). Halos stay white unless a color layer is active, so plain themes look as before. `AdaptiveWave` collects art colors, battery and clock, so those updates recompose it and not the screen |
| Context rules | `rules: [{match, apply}]` (`ContextRule`) inline or from `rules.json` in `imports`; rules from every file add up (imports first). Selectors: `category`, `system` (platform id or folder alias), `battery` (`<= 20`, `<`, `>=`, `>`, `==`, `charging`, `discharging`, `full`; also read as `battery.level`), `time` (`HH:MM..HH:MM`, wraps midnight, end excluded). `apply`: palette roles and the `circadianCurve` / `batteryTint` switches, `effects.surfaceStyle` / `adaptiveGamePalette`, `cardAspectRatio`, any `layout` key. Unreadable selectors are reported and the rule dropped. Static tile-shape rules fold into the pack layout at load; dynamic ones produce a per-context `ThemeLayout` (`LocalThemeLayout`). At most 64 rules |
| Precedence | Default pack → pack → variant → category override → system override → rules in file order (`cascade`), then the user's theme color, the quick profile, the circadian curve and battery tint, then game art (`ColorPipeline`). OLED True Black keeps its black canvas under game art, and a low battery still shows over art. `LoadedTheme.look` is cached by the set of rules that hold, and battery and time reach it only when that set changes (`rememberMinuteOfDay` checks once a minute) |
| Circadian curve | `Circadian`: phases 06 cyan/sky (hue 192), 11 azure/cobalt (214), 18 amber/rose gold (22), 21 indigo/midnight (255), built with the Original theme's `SeasonalPalette.paletteOf`, smoothstep-blended ±45 min around each boundary, mixed 50% into the canvas, 35% into the wave and 20% into the glow. `palette.circadianCurve` / Settings ▸ Theme ▸ **Circadian curve** |
| Battery tint | `BatteryMonitor` (sticky `ACTION_BATTERY_CHANGED`) in `XmbViewModel.battery`. ≤ 20% unplugged: glow and wave shift toward amber, reaching red at 5%. Charging: a green lean plus a 2.4 s brightness pulse of the sheets (1.00–1.18×) on the GL thread, resting with the wave. `palette.batteryTint` / Settings ▸ Theme ▸ **Battery alert tint** |
| Quick profiles | Settings ▸ Theme ▸ **Quick profile** (`display.quickProfile`). `OLED_TRUE_BLACK`: `#000000` canvas and the `OLED_TRUE_BLACK` surface. `Y2K_CYBER_PASTEL`: `#1B1829` canvas, `#C8B6FF` lilac, `#FFB5C5` strawberry, `Y2K_GLOSSY_BEZEL`. `MONOCHROME_EINK`: black and white with `EINK_HIGH_CONTRAST`, motion `INSTANT`, marquee off, `LocalStillMotion` freezing `rememberBreathing` (focus pulse, avatar glow), the busy dots and the search caret, and the wave in `WaveLoop.SINGLE_FRAME` (`RENDERMODE_WHEN_DIRTY`, one frame per change, no time drift). All color layers are off under E-Ink. A profile's surface wins over Surface style |
| Settings | `display.quickProfile`, `display.adaptiveGamePalette`, `display.circadianCurve`, `display.batteryTint` (`null` follows the pack) in `settings.json`. Four rows after Surface style; each switch's Options offers On, Off and Theme pack default |

**Unit tests (499, 0 failures; 35 new):** `ContextRulesTest` (9): time ranges, wrapping and
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
native graphics), plus a wheel logo on a transparent background (the transparent pixels don't make
the dominant swatch black, an all-transparent image yields nothing) and `adaptiveArtKey` (art and
scraped tiles, unscraped tiles, the Details screen sharing its row's key or naming a paged-to
game). `ContextRulesTest` also covers the `battery.level` / `batteryLevel` aliases and a
`category` + `system` tile shape applied at run time in that category only. `StillMotionTest`
(2, Compose): the pulse breathes normally and holds exactly at rest under E-Ink.

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

**Rerun on a cold-booted emulator (2026-11-12, later): `smoke.sh` 171 of 171.** Three script
fixes: the imported-pack check waits up to 8 s for `settings.json`, the remap wizard passes on the
toast *or* the wizard closing (the saved bindings are checked right after), and the song check
waits up to 6 s for the player to leave BUFFERING.

**Benchmarks:** extracting one 320×180 hero took 3.3 ms on the emulator; 60 extractions of
640×360 heroes (decoded to 320×180) took a median 0.3 ms, max 5.8 ms, none on the main thread.
**Frame pacing of game navigation** (`tools/device-tests/palette-perf.sh`: 12 scraped SNES games
with distinct heroes, profiling build, cold start per pass, Down 11 × and Up 11 × 450 ms apart,
adaptive on and off interleaved): the emulator had picked software rendering (`swangle`,
Vulkan on `llvmpipe`), which explains the earlier 96.7% (100% janky, GPU p50 4950 ms). With
`-gpu host`, the first measurements met the deadline target (on 0.29–1.04% janky, off 0.15–0.44%)
but GPU time rose with the feature: GPU p50 17–18 ms instead of 14 ms and 13–82% legacy-janky
passes on 7 of 7 adaptive passes (1 of 6 off). The cause was the glow-text bloom: its shadow color
animated through each ~300 ms morph, so the text was blurred again on every frame. With the bloom
switching color at once, **on: 0.29–0.90% janky, legacy 0.15–1.05%, GPU p50 14 ms; off:
0.30%, legacy 0.30–0.89%, GPU p50 14 ms** (3 pairs), so the feature has no measurable frame cost
on the emulator. On the Galaxy A55 at 120 Hz: on 0.12–0.19%, off 0.19–0.38%, GPU p50 5–8 ms (§3.21).

**Checked by eye on the emulator (screenshots):** game art tints the battery outline and the title
bloom; OLED True Black (black canvas, dim waves, no pill fill), Y2K Cyber-Pastel (violet to
lavender, lilac waves, strawberry rim) and Monochrome E-Ink (black to `#1A1A1A`, white waves);
the circadian curve in each phase by time zone (no root needed: `cmd alarm set-timezone`): night
violet `#1E1A37`, morning `#1E253C`, day `#1B203D`, evening plum `#291F2F`, blending toward night
at 20:58; the battery tint at 20% (pale amber), 5% (red, warm waves) and charging (mint). Context
rules from a pack's imported `rules.json` switched live with `dumpsys battery` and a time-zone
change (within the minute), and editing `rules.json` while the battery rule held applied the new
color in 3 s. Found: under E-Ink the avatar ring keeps its color (the user's avatar style, or the
iridescent default), so the screen isn't fully black and white.

**Not verified (after the Galaxy A55 run in §3.21):** a handheld or E-Ink panel; the morphs
between games with very different accents by eye; a real battery crossing 20%; Android 9–13.

### 3.20 Device tests split into tiers (2026-11-12)

The 755-line `smoke.sh`, one sequential run of 171 checks, is now three tiers in
`tools/device-tests/` that share `lib/harness.sh`: the assertions, navigation, fixture setup
(`setup_base_env`, `setup_media_fixtures`, `setup_theme_fixtures`) and a teardown on EXIT, INT
and TERM. That teardown runs `dumpsys battery reset`, stops the RetroArch stub and deletes the
seeded ROMs, `XrossSmoke` media, theme packs, `.xtheme` files, hero art and the UI dump.

| Tier | Checks | Time (API 37 emulator) |
| --- | --- | --- |
| `smoke.sh` (quick): boot, MainActivity in front, database created, 3 systems scanned, category bar, first platform, L1/R1, same process, no crash | 12 (new) | 26–27 s |
| `test-library.sh` | 41 | 124 s |
| `test-theme.sh` | 88 | 243–249 s |
| `test-media.sh` | 22 | 83 s |
| `test-input.sh` | 11 | 43 s |
| `test-integration.sh` | 9 | 34–37 s |
| `run-all.sh` (= `smoke.sh --all`): the five suites, one passed / failed / skipped table, exit 1 on any failure | 171 | 8 m 58 s |

Each suite clears the app and seeds its own fixtures, so it runs alone and in any order. Checks
that need a missing host tool (ffmpeg, sqlite3) or the stub count as skipped, so the total
stays 171. Moved: the two music-during-a-game checks went from Launch to the media suite. The
Sound section stays in the theme suite, where it sits in the Settings flow. Stricter: the Secrets
check now waits for the three config files and fails if one is missing, instead of passing on an
error message.

**Verified:** the check descriptions in the old script and the new suites match one-for-one
(171 each). `shellcheck` 0.11 is clean (`.shellcheckrc` documents the suppressed house patterns).
`test-theme.sh` passed 88 of 88 alone right after an emulator reboot. `run-all.sh` passed **171 of
171** twice, and the quick smoke 12 of 12 both times. After each run the battery was back to its
own state and no test file was left in `/sdcard`. On the Galaxy A55: §3.21.

### 3.21 Hardware run: Phase 2b.3 on the Galaxy A55 at 120 Hz (2026-11-12)

Samsung Galaxy A55 (SM-A556B, Android 17 / API 37, 1080×2340, 120 Hz), on USB power, branch
`feat/theme-2b3` at `ade1203`. The phone's real `/sdcard/ROMs` (370 arcade sets, 2 PSP ISOs) was
moved aside for the suites and put back afterwards. The RetroArch stub was installed for the
integration suite and removed again.

**Device regression (`run-all.sh`, debug build): 160 of 171 after three script fixes.**

| Suite | First run | After the fixes | Notes |
| --- | --- | --- | --- |
| library | 41/41 | — | |
| theme | 76/88 (15 failures) | **88/88** | Fixes 1 and 2 |
| media | 11/22 | — | The 11 failures are the seeded-media checks; the phone has its own photos and music (as on 2026-11-11) |
| input | 10/11 | **11/11** | Fix 3 |
| integration | 9/9 | — | RetroArch stub launch, secrets, mini-HUD, dashboard |

The first run's 27 failures came from three script assumptions that hold on the emulator but not on
a phone. None was an app bug, and each was confirmed by hand before the fix:
1. **Document picker** (13 failures, one cause): the phone's picker opens on an empty *Recent*, so
   the pushed `.xtheme` files weren't listed. `pick()` in `test-theme.sh` now searches the picker
   for the file after two looks. Importing by hand from Downloads worked (`Xross/themes/hw-probe`
   created), and the rerun passed import, zip-slip rejection, variants and re-import.
2. **Battery tint** (2 failures): on a phone charging over USB, `dumpsys battery unplug` clears the
   power sources but leaves `status: 2` (charging). Xross reads the status, so it correctly stayed
   in the charging look, and the later "charging" step then changed nothing. The suite now sets
   `status 3` after the unplug.
3. **Vertical drag** (1 failure): the drag works and lands on the last row, but this phone's Games
   root has an Android Games row, so the row shown above the focus wasn't All Games. The check now
   asserts that Search scrolled away and RetroAchievements is focused.

**Frame pacing of game navigation (`palette-perf.sh`, profiling build, 3 interleaved pairs):**

| Pass | Frames | Janky (deadline) | Legacy | p50 / p90 / p99 | GPU p50 | Extractions |
| --- | --- | --- | --- | --- | --- | --- |
| on1 | 1,590 | 0.19% | 22.8% | 12 / 13 / 17 ms | 6 ms | 12 (0 on main) |
| off1 | 1,601 | 0.19% | 17.9% | 12 / 13 / 16 ms | 6 ms | — |
| on2 | 1,614 | 0.12% | 70.9% | 18 / 20 / 22 ms | 8 ms | 12 (0 on main) |
| off2 | 1,606 | 0.25% | 15.6% | 12 / 13 / 17 ms | 6 ms | — |
| on3 | 1,600 | 0.19% | 12.7% | 12 / 13 / 16 ms | 6 ms | 12 (0 on main) |
| off3 | 1,583 | 0.38% | 20.9% | 12 / 13 / 17 ms | 5 ms | — |

The target is met with room to spare: 0.12–0.19% janky with adaptive colors on, 0.19–0.38% with
them off, and GPU p50 5–8 ms against the 8.3 ms budget at 120 Hz. The game-art palette has no
measurable frame cost on this phone. The legacy column swings between passes, as on the emulator.
No other pass matches on2's higher p50, so it reads as a one-off rather than a feature cost.

**Other measurements (profiling build):**

| Measure | Result | Target |
| --- | --- | --- |
| Idle CPU, Games root, 10 s rest, 20 s sample (2 runs each) | No profile 18 / 21%, OLED True Black 17 / 16%, Y2K Cyber-Pastel 21 / 21%, **Monochrome E-Ink 0 / 0%** (no wave frames at all) | < 10% ❌ except E-Ink |
| Wave at rest (`WaveFrames`) | p50 50.0 ms, p95 51.4–51.8 ms, 0–3 missed per 300 (period 50 ms) | ✅ |
| Wave in use, 25 s of D-pad | p95 18.4–20.3 ms and 1–5 missed per 300 once warm; the first window after a cold start had 11 missed, max 74 ms | ✅ ≤ 20 ms / ≤ 5 after warm-up |
| UI: Apps list 40 down and up, 10 category switches | 2,610 frames, 0.46% janky, p50 7 ms, p99 15 ms, GPU p50 3 ms, 1 missed vsync | ✅ |
| Cold start (`am start -W`) | 445, 400, 433 ms | ✅ < 2,500 ms |
| PSS in front | 164 MB at rest; peaks of 315 MB right after a cold start and 454 MB right after scrolling Apps and Photo (2,138 photos), then back down | — |
| PSS in the background (8 s after Home) | 84 MB, 0% CPU | ✅ ≤ 150 MB |

**2b.3 items checked on the device by hand:**

| Item | Result |
| --- | --- |
| Wheel-only art | ✅ A game with only a transparent wheel logo (magenta on alpha) extracted at 240×90 to a `#290614` canvas and `#FC9EC2` glow; the transparent area didn't turn the canvas black |
| Details as the palette source | ✅ Opening Details keeps the row's colors with no new extraction (same art key); R1 paging extracts and recolors per game (04 → `#172906`, 05 → `#062906`); Back returns to the list on the last game shown with no further extraction |
| A rule's `layout` patch on screen | ✅ A pack rule `battery <= 15 → layout {motionProfile: INSTANT, focusedScale: 1.0}`: at 10% the focused tile is the size of its neighbours, at 50% it is back to 1.32×. In a screen recording the list moves 06 → 07 in one frame at 10% and glides over ~300 ms at 50%. Editing `rules.json` on the device hot-reloaded the patch. `focusedScale` is clamped to 1.35, so a rule asking for 1.4 moves the default 1.32 by only a few pixels |
| Real charger | ✅ With no `dumpsys` override (USB, status 2, 67%) the battery tint shows `charging=true` and the `#D8FFE8` mint glow |
| Morphs by eye | ◐ Recorded 05 → 06 at 120 Hz: the backdrop crossfades over ~4 frames, the battery outline and avatar ring show no jitter, and the title bloom shows no flash in the frames. Those two games' accents are close in hue, so a bigger accent jump still needs a look by eye |

Also: the display matrix (`display-matrix.sh`) was rerun on the API 37 emulator (host GPU) with
the 2b.3 rows: 12 of 12 configurations, 0 clipped texts, janky 0–1.16%. Its screenshots show the
top of Settings ▸ Theme, so the four new rows lower down weren't photographed.

**Not covered:** a real battery going below 20% unplugged, an E-Ink panel, Android 9–11, a physical
controller, and morphs between games with very different accents, judged by eye.

### 3.22 Phase 2b.4a: shader host and watchdog (2026-11-12)

`ui/WaveRenderer.kt` is now a multi-program host. Sources live in `assets/shaders/`: `prelude.glsl`
declares the public uniforms (`uTime`, `uResolution`, `uColorTop`, `uColorBottom`, `uAccent`,
`uGlow`, `uBatteryLevel`, `uAudioBass`, `uAudioBeat`, `uTransition`) and goes in front of every
preset and of a pack's `shader.frag` unless that file has its own `#version`. A pack picks a
program with `effects.shaderPreset` (alias `shader`) and `effects.shaderPath` in its base,
variants, overrides and `rules`; the last layer wins.

- **AOT compile:** every program a pack can show is compiled on the GL thread via `queueEvent`
  when it loads (`KHR_parallel_shader_compile` is polled per frame where present). The program on
  screen stays until the wanted one links, and a new program is first drawn into a 1×1 viewport.
  The EGL context is now preserved across an activity pause, so returning from a game doesn't
  recompile.
- **Failures:** no `check()`; a compile/link error is `ShaderStatus.Failed`, the wave is drawn,
  the driver's line is logged under `XrossShader` and a toast names the line in the user's file.
- **Watchdog** (`FrameBudgetWatchdog`, pure): non-WAVE programs are timed for ~2 s on screen
  (timer query, else `glFinish` every third frame); p90 over 60% of the active frame budget
  retires the program for the session. A program far over budget goes sooner: 3 samples all over
  4× the budget, or a median over 2× after 6.
- **Canary** (`ShaderCanary`): `Xross/cache/shader_canary.tmp` holds a pack shader's key while it
  compiles and until 120 frames in a row; at process start a leftover key goes to
  `shader_blocked.txt` and isn't compiled again until the file's text changes.

**Emulator (API 37, host GPU, no timer-query extension, so the `glFinish` path):**

| Check | Result |
|---|---|
| Presets per category (Games BOKEH, Video CRT, Music STARFIELD, Photo ASCII_MATRIX) | ✅ all linked ahead of time; switching categories every 0.3 s reported each `Ready` at once; watchdog p90 2.8–6.0 ms of 10 ms (BOKEH with 26 orbs, before the change below) |
| Pack `shader.frag` (Apps) | ✅ drawn; marker present for its first frames, gone after 120 |
| Hot reload of `shader.frag` | ✅ new key compiled and shown without leaving Xross |
| Compile error (`fx/broken.frag`) | ✅ wave shown; toast "Shader fx/broken.frag: line 3: 'glowColour' : undeclared identifier · showing the wave" |
| Watchdog (`fx/slow.frag`, 6000-iteration loop) | ✅ tripped early at p90 56.5 ms; toast; wave shown; no marker left |
| Crash recovery (marker planted, cold start) | ✅ toast "A theme shader stopped Xross last time"; key in `shader_blocked.txt`; never compiled; Apps shows the wave |
| `smoke.sh` / theme suite | ✅ 12/12 (three runs; one earlier run had a single failure that was not captured and didn't recur) / 88/88 |

**Galaxy A55 (Xclipse 530, 120 Hz panel paced to 60, profiling build; `GL_EXT_disjoint_timer_query`
present, so the watchdog used GPU timer queries):** `smoke.sh` 12/12 (the phone's ROMs, app data
and config were backed up first and restored after).

| Check | Result |
|---|---|
| Presets, ahead of time | ✅ all linked at pack load; GPU p90 CRT 1.31 ms, ASCII_MATRIX 1.33 ms, STARFIELD 2.96 ms, BOKEH 2.90 ms, custom 0.46–0.55 ms (limit 10 ms). The GPU stayed at its 450 MHz floor throughout |
| BOKEH cost | 26 orbs read 7.7–8.0 ms (~70% GPU busy at 450 MHz, vs ~45% for WAVE); 20 orbs read 2.9 ms, a non-linear drop (the longer loop evidently crossed a compiler threshold). Now 20 |
| Pack shader, canary | ✅ drawn; marker present at first, gone after 120 frames |
| Hot reload, compile error | ✅ edited `shader.frag` relinked live; `fx/broken.frag` toast names line 3 |
| Watchdog | ✅ the 6000-iteration shader drew at 344 ms a frame; tripped after 3 samples, 1.5 s after its first frame (2.9 s before the 3-sample rule) |
| Crash recovery | ✅ planted marker → toast at start, key blocked, never compiled |

**Not covered:** Adreno and Mali GPUs (handhelds), a real driver crash or GPU reset, and E-Ink
with a pack shader. The watchdog measures at whatever clock the GPU governor has chosen, so a
reading near the limit at the lowest clock could pass at a higher one.

### 3.23 Phase 2b.4b: audio-reactive uniforms (2026-11-12)

`uAudioBass` (0–1) and `uAudioBeat` (0 or 1) now come from Xross's own music player. There's no
`Visualizer`, so no `RECORD_AUDIO`: other apps' audio doesn't drive the shader. Code in
`media/energy/`:

- **Tap:** `MusicPlayer` builds its ExoPlayer with a renderers factory whose `DefaultAudioSink`
  has `BassTapProcessor` in front of the default processors. It's a pass-through
  `BaseAudioProcessor`: it reads each 16-bit or float buffer in place on the playback thread (any
  channel count, averaged to mono) and copies it to an output buffer it reuses, so nothing is
  allocated per buffer.
- **Analysis (`BassAnalyzer`, no FFT):** a 4th-order Butterworth low-pass at 150 Hz (two RBJ
  biquads, 24 dB/octave, so a voice's 360 Hz is 30 dB down; a single biquad let a pure 360 Hz
  fixture read 0.75), then a 20 ms running mean square read every 10 ms. Bass is RMS over a
  loudness reference that decays over 4 s (floor ≈ -28 dBFS) and is held with a 200 ms
  exponential release. A beat is a hop over 1.6× the last second's mean energy, with an energy
  floor and at least 0.35 of the track's own level. It holds for 100 ms and re-arms after 300 ms.
- **Latency compensation:** Media3 1.10's sink flushes its processors without a stream position,
  so the processor can't date a buffer itself. `EnergyAudioSink` (a `ForwardingAudioSink`)
  anchors the analyzer to each new buffer's presentation time and passes every
  `getCurrentPositionUs` reading (the sink's playout clock, which ExoPlayer turns into
  `currentPosition`) to `PlaybackClock`. Both are on the sink's own timeline. The GL thread can't
  call `player.currentPosition` (ExoPlayer is single-thread), so each frame it extrapolates the
  last reading (≤ 250 ms old) and reads `AudioEnergyRing` at that time: bass interpolated, beat
  from the entry at or before it. Paused, stopped, buffering or flushed (seek, new queue): 0 and 0.
- **Shaders:** `BOKEH` and `ASCII_MATRIX` already scaled with `uAudioBass`. `STARFIELD`'s dust now
  brightens with the bass, and its vanishing-point glow pulses on a beat. `WAVE` doesn't react
  yet. Debug and profiling builds log the uniforms every ~2 s under `XrossAudio`
  (`bass max=1.00 beats=4 lead=485 ms`).

**Unit tests:** `AudioEnergyTest` (23): filter response (one biquad: -3 dB at 150 Hz, -36 dB at
1.2 kHz; the cascade: -24 dB at 300 Hz, < -70 dB at 1.2 kHz, unity DC), hop timestamps, silence,
a steady and a quiet bass line reading full, a voice barely registering, the 200 ms decay
(e^-1 after one time constant), kicks at 120 BPM giving one 100 ms beat each, the refractory
period, the ring's interpolation, edges, wrap and rewind, the playout clock, and the whole tap
behind a fake sink. In that test the decoder runs 1 s ahead of the speaker and the shader still
follows what is heard; it also covers pause, resume, seek, stop, a refused buffer and
non-PCM pass-through. 558 unit tests in all.

**Emulator (API 37, debug build):** the media fixtures now carry a 55 Hz kick at 120 BPM under
their tones. Media suite 24/24, including the new checks "the music drives the shader's bass and
beat" (`bass max=1.00 beats=4` per 2 s) and "paused music binds 0" (`bass max=0.00 beats=0`).
`smoke.sh` 12/12. The analysis ran **475–507 ms** ahead of the speaker (decoder plus the
AudioTrack buffer), about twice the 100–250 ms the plan assumed. Reading the newest entry would
put every pulse half a second early.

**Not covered:** the Galaxy A55 and handhelds (their output latency, Bluetooth headphones with
their extra delay, which the sink's clock should include), sync judged by eye against real music,
and the frame cost (one biquad cascade per sample on the playback thread, a locked array read per
frame on the GL thread; neither profiled).

### 3.24 Phase 2b.4c: wallpapers and frosted surfaces (2026-11-12)

Layer 3 of the Theme 2.0 stack, and the `backdropBlurDp` that 2b.2 read but didn't draw.

- **Wallpaper:** a pack's `wallpaper.png` / `.jpg` / `.webp` at its root, or `effects.wallpaperPath`
  (alias `wallpaper`; `none` turns it off), through variants, overrides and rules like the shader
  (`WallpaperRef` in the cascade; files checked at load, `LoadedTheme.wallpapers`). Photo ▸ Options
  ▸ **Set as Xross wallpaper** (in a photo list, and now as an Options menu over the viewer, which
  also offers Set as Android wallpaper and, while one is set, Use the theme's wallpaper) saves the
  photo's URI as `display.customWallpaper`; it beats the pack's, and the pack's shows again if the
  photo can't be read. **Settings ▸ Theme ▸ Xross wallpaper** (above Import theme) says where the
  wallpaper comes from and goes back to the theme's. `WallpaperLoader` decodes with `ImageDecoder`
  (EXIF rotation, software pixels, ≤ 2560 px) on IO and keeps the last two. `WallpaperBackdrop`
  draws it full-bleed under the hero art with a lighter scrim, hides it while the wave editor is
  open, and reports `backdropCovers` when it is opaque and fully in, so the GL loop stops.
- **Frosted surfaces (`PreBlur`, `FrostedBackdrop`):** when hero art or a wallpaper is shown and
  the active surface has `backdropBlurDp`, a 192 px wide copy is blurred once on
  `Dispatchers.Default` (three box passes ≈ a Gaussian of that sigma, premultiplied, edges
  clamped) and cached by image and radius (`PreBlurCache`, 8 copies, LRU). `Modifier.frostedSlice`,
  inside `xrossSurface`'s clip and under its fill, draws each layer's aligned slice (same
  `ContentScale.Crop` rect, measured from the home screen's root) with its scrim and fade, reads
  everything in the draw phase and redraws when the surface moves. No `RenderEffect`, so Android
  9–11 get it too (§5 of the Roadmap revised). Over the live shader, a video snap or a media screen
  the tint alone. The Default pack's `FLAT_GLASS` now blurs 14 dp.
- **`.xtheme`:** archives may now hold `wallpaper.*` and `shader.frag` at their root (the loader's
  default names; `shader.frag` there was rejected since 2b.4a) and a `wallpapers/` folder.
- Debug and profiling builds log `XrossWallpaper` (`wallpaper=pack 320x180 opaque=true`) and
  `XrossFrost` (`WALLPAPER radius=3 copy=192x108 in 24.5 ms`).

**Unit tests:** `BackdropTest` (20): wallpaper paths (none, escapes, types), the pack default in
any case, the cascade through variants, a category override and a rule, a missing file, the
user's wallpaper over the pack's, `display.customWallpaper` (round trip, trim, length cap), the
photo Options and the Settings row; the copy's size, the radius mapping, the crop rect, opacity,
the blur (uniform stays uniform, a point spreads symmetrically and keeps its energy, transparent
neighbors don't darken, radius 0 copies) and the cache (LRU eviction, radii, evict, no cached
failures). `ThemeArchiveTest`, `MediaMenusTest`, `ConfigCodecTest` and `WaveSettingsTest`
updated. 578 unit tests in all.

**Emulator (API 37, debug build):** theme suite **94/94**, with six new checks: a `wallpaper.png`
dropped into the active pack hot-reloads behind the menu, stops the wave's loop (`mode=STOPPED`)
and gets a 192×108 pre-blurred copy; Settings ▸ Theme ▸ Xross wallpaper names the pack; removing
the file hides it and the wave runs again. Media suite **31/31**, with seven new checks: the photo
viewer's Options offer Set as Xross wallpaper, the URI lands in `settings.json`, the photo is
decoded opaque and stops the wave, the Settings row shows it, and Confirm clears it and the wave
resumes. `smoke.sh` 12/12. By eye: a checkerboard wallpaper shows full-bleed with its scrim, and
the avatar mini-HUD (Flat glass) shows it blurred under its tint, aligned with the squares around
it. A blur took 24.5 ms for hero art on a warm process, and 428 ms for the first one after a cold
start (JIT and the default dispatcher warming up); either way off the main thread.

**Not covered:** the Galaxy A55 and handhelds (the blur's look at 120 Hz, decode time of a 4K
photo, memory with a 2560 px wallpaper held), frame pacing on the profiling build, a surface
sliding with `graphicsLayer` (its slice follows positioning callbacks), Android 9–11 on hardware,
and the `.xtheme` change on a device (unit-tested only). The Options panel and the button-hint
pill aren't themed surfaces, so they don't frost.

### 3.25 Phase 2b.4d: focus frames and icon masks (2026-11-12)

Draw-phase cosmetics: how the focused item is framed, and how installed apps' icons are cut and
colored.

- **Manifest and cascade:** `effects.focusFrameStyle` (alias `focusFrame`), `effects.iconMask`
  and `effects.iconFilter`, read ignoring case, `-` and spaces; unknown names are reported and
  left unset. They form a `Cosmetics` bundle carried by the base, variants, category and system
  overrides and rules like the shader (the last layer naming a value wins), resolved into
  `ThemeLook.cosmetics`; `ThemeLook.focusGold` is the cascaded `palette.focusGold`.
- **Focus frames (`ui/FocusHalo.kt`):** `LocalFocusFrame` (style, neon and gold colors) from
  MainActivity. `rememberFocusPulse` runs the breathing animation only for `BREATHING_HALO`;
  every other style returns a constant pulse that isn't snapshot state, so a resting focused item
  draws no frames. `drawPlateFrame` draws the four styles around game tiles, thumbnails, art icons
  (fitted to the art) and RetroAchievements badges: the old white rings; a crisp neon core with a
  short falloff (accent, else the wave color when the accent is white, else the game-art tint);
  a solid shadow 2 dp down-right clipped to outside the plate (translucent tiles aren't darkened);
  a 3 dp metal bezel in `palette.focusGold` (the mastery gold by default) with a beveled edge.
  Glyph icons get the halo tinted neon or gold, or a solid offset copy under `HARD_SHADOW`.
- **App icons (`data/IconStyler.kt`):** masks are a superellipse (n = 4), a circle, or a squircle
  in an 8.5% chrome bezel with a gloss lens; the cut is anti-aliased (mask filled, content drawn
  `SRC_IN`). Adaptive icons are rebuilt from their layers drawn 1.5× (a quarter past each edge);
  legacy icons with opaque corners are cut whole, others shrunk to 70% onto a plate of their
  average color lifted 82% toward white. Filters are one `ColorMatrix` mapping Rec. 709 luma onto
  canvas → accent (`DUOTONE`) or black → accent (`MONOCHROME`), applied to the artwork, not the
  bezel. `AppRepository.icon` keys a 12 MB `LruCache` by package, `IconStyle` (mask, filter and
  the two colors, zeroed when the filter doesn't use them) and size; the drawable loads on IO and
  renders on `Dispatchers.Default`. MainActivity remembers the loader per style, so rows reload only
  when the style changes.
- Debug and profiling builds log `XrossCosmetics` (`frame=GOLD_BEZEL mask=Y2K_CHROME_FRAME
  filter=DUOTONE gold=#E0B040`) and `XrossIcons` (`drew com.android.camera2 Y2K_CHROME_FRAME/DUOTONE
  144px in 0.7 ms`).

**Unit tests:** `FocusFrameIconMaskTest` (25): names and problems, defaults, each layer
overriding only what it names, an override with only cosmetics; the breathing pulse moves, the
three steady ones hold at 1 and aren't snapshot state; neon and gold colors; the filter matrices
(endpoints, alpha, luma) and a rendered duotone; masks pixel by pixel (corners, edges, circle vs
squircle, the whole shape), adaptive layer bounds, a padded legacy icon's plate, the chrome ring,
bounds restored; the cache (one load and render per key, every key part distinct, misses not
cached). 603 unit tests in all.

**Emulator (API 37, host GPU):** theme suite **98/98** on the debug build, with four new checks:
the three keys and `focusGold` hot-reload with the pack; 16 Apps icons drawn in the chrome frame,
duotoned; scrolling the list again draws none; removing the keys brings the halo and plain icons
back. Library suite **41/41**. By eye: the chrome bezel and navy-to-cyan duotone on the Apps list,
gold halos on glyphs and the gold bezel on a focused game tile. Profiling build, Apps list, 20 rows
down and back: **0.51% janky** with every icon drawn on the way (18 icons, 0.7 ms average, 1.0 ms
max, off the main thread) and **0.52%** from the cache (none drawn); p90 16–19 ms, as the wave
alone gives on this emulator.

**Not covered:** the Galaxy A55 and handhelds (120 Hz, a real app drawer, OEM legacy icons),
`STATIC_NEON` and `HARD_SHADOW` checked only in unit tests, the RA badge frames by eye, and a
Settings ▸ Theme row for these keys (2b.4f). App icons have no focus frame (as before).

### 3.26 Phase 2b.4e: foreground particles (2026-11-13)

Layer 4 of the Theme 2.0 stack: a light particle layer over the wallpaper, hero art and snap and
under the menu's surfaces.

- **Manifest and cascade:** `effects.foregroundParticles` (alias `particles`): `NONE` (default),
  `SNOW`, `BOKEH`, `DUST_MOTES`, read ignoring case, `-` and spaces. It is a fourth field of the
  2b.4d `Cosmetics` bundle (`ParticlePreset`), so the base, variants, category and system
  overrides and rules carry it like the focus frame; `ThemeLook.cosmetics.particleField`.
- **Field (`ui/AmbientParticles.kt`, `ParticleField`):** flat `FloatArray`s for position, speed,
  size, alpha, drift, sway, frequency and phase; positions are fractions of the layer and speeds
  layer heights per second, horizontal motion divided by the aspect ratio. 40 flakes, 14 discs or
  28 motes, hard-capped at 48 whatever is asked. Snow falls 0.035–0.09 heights/s and sways on its
  own sine (nearer flakes bigger, brighter, faster); bokeh rises 0.006–0.02 heights/s with
  14–46 dp discs (nearer ones bigger, fainter and faster), drawn as a skirt, a flat disc and a
  brighter 1 dp rim in the accent; dust drifts every way, twinkles, and is brightest in a
  diagonal shaft from the top left. A particle that passes the margin (2% of the height, 16% for
  bokeh) re-enters at the opposite edge at a random spot. Steps are clamped to 50 ms.
- **Drawing:** a `withFrameNanos` loop steps the field once per display frame and bumps one
  `mutableLongStateOf` read only in `drawBehind`, inside a `graphicsLayer` with
  `CompositingStrategy.ModulateAlpha` (no offscreen buffer): each frame re-records that layer and
  never recomposes. A hide or show fades the layer 400 ms (instant under E-Ink); hidden, it
  leaves the composition.
- **Resting (`ParticleRest`):** hidden for `NONE`, a still-motion profile (`MONOCHROME_EINK`), a
  playing video snap, full-screen photos or video and the wave editor; resting when the activity
  is stopped or after 10 s without input (`ParticleRest.IDLE_AFTER_MS`, a second
  `InactivityTracker` fed by the same key, stick and touch events as the 3 s one; music doesn't
  keep particles going). Resting eases the field to a stop over 0.6 s (a smoothstep "gait") and
  then ends the loop: no frames until the next input, which eases them back over 0.6 s.
- Debug and profiling builds log `XrossParticles` (`state=RUNNING preset=SNOW n=40`, and `rest
  preset=SNOW n=40 settleFrames=34` when the loop ends).

**Unit tests:** `AmbientParticlesTest` (19): names, aliases and problems; the cascade through a
variant, a category override and a rule, leaving other cosmetics alone; the default pack has
none; counts and the 48 cap; start spread over the layer; same seed, same field; each preset's
direction and bokeh's depth scale; the sway's bounds; dust alpha under its peak; wrapping at the
bottom, top and sides; two minutes in portrait staying inside the margins; a 30 s gap stepping
50 ms; the hidden / resting / running table; resting easing to a stop then wanting no frames and
freezing positions and clock; resuming from rest with monotonically rising steps up to full
speed; the 10 s tracker. 622 unit tests in all.

**Emulator (API 37, host GPU, profiling build):** a `SNOW` pack (40 flakes), `BOKEH` and
`DUST_MOTES` checked by eye in landscape 2400×1080. Rest after 10 s without input, settled in 34
frames, then **0 frames** rendered over 5 s; the next key logs `RUNNING`; another app in front
logs `RESTING` and coming back `RUNNING`; under Monochrome E-Ink the layer isn't composed and the
app drew 1 frame in 4 s. Frame timing, interleaved passes of `NONE`, `SNOW`, `BOKEH` and
`DUST_MOTES` (8 s with only the particles moving, then 36 D-pad presses across categories and
lists): **0.15–0.42% janky** (deadline) in 22 of 24 windows, `NONE` 0.18–1.30%; two consecutive
windows in one round (dust 19.6%, snow 14.1%) dropped frames together and the next rounds were
clean. Per-frame `framestats` over 120 frames each, four interleaved pairs: UI thread (animation
to sync) **0.29–0.37 ms p50, ≤0.83 ms p90** with snow vs 0.23–0.56 / ≤0.79 ms without; recording
the draw 0.09 vs 0.08 ms; the render thread (~15 ms, vsync-bound on the emulator) and late frame
starts (up to 26–38 ms) the same with and without particles, so the burst came from the host.
Theme suite **102/102** on the debug build, with four new checks: the preset hot-reloads with
the pack (40 flakes running), they rest after 10 s, draw no frames while resting, and the next
key wakes them.

**Not covered:** the Galaxy A55 and handhelds (120 Hz pacing, Mali and Adreno), a playing video
snap hiding the layer (no scraped games on the emulator; unit-tested), and a Settings ▸ Theme row
for the preset (2b.4f).

### 3.27 Phase 2b.4f: post-processing, Settings rows (2026-11-13)

The last slice of 2b.4: full-screen filters on both sides of the GL / Compose split, the Settings ▸
Theme rows for every 2b.4 effect, and the `effects` schema. 2b.4 is complete on the emulator.

- **Background filter (GL side):** `effects.backgroundFilter`: `NONE` (default), `SCANLINES`,
  `CRT` (scanlines, an RGB aperture grille, the tube's vignette), `LCD_GRID`. The wave's
  `GLSurfaceView` is its own surface under the window, so a Compose effect can't reach it; the
  filter is `assets/shaders/screen_filter.frag`, drawn after the background program as a second
  full-screen triangle with `glBlendFunc(GL_DST_COLOR, GL_ZERO)` (it can only darken). Compiled
  with the wave when the context is created (`ScreenFilterPass`), skipped over the `CRT` preset.
  Pitches are given in dp and snapped to whole render pixels through `uPxPerDp` = display density
  × the surface's render scale (set by `WaveBackground` on layout): lines 1.5 dp (≥ 2 px), stripes
  0.5 dp (≥ 1 px), LCD cells 3 dp (≥ 3 px), sampled at pixel centers so a 2 px pitch is one lit
  row and one dark. On the 2400×1080 emulator (2.625 dp, rendered at 1280×576) that is 1.40 px/dp:
  2 px lines, 1 px stripes, 4 px cells. The `CRT` preset now sizes its scanlines and grille the
  same way instead of a fixed 270 lines and 1 px stripes, which beat into moiré once upscaled.
- **UI filter (AGSL, Android 13+):** `effects.uiFilter` (alias `postProcess`): `SCANLINES_UI`,
  `BLOOM`, or `CUSTOM` from `postprocess.agsl` / `effects.uiFilterPath`, read at load like a
  custom shader (folder packs, ≤ 64 KB; `.xtheme` archives accept it at the root). Applied by
  `Modifier.uiFilter` (`ui/ScreenFilter.kt`) as a `RenderEffect` on the menu's root layer:
  scanlines are one `RuntimeShader`; bloom is a bright pass, a blur (8 dp) and a `PLUS` blend over
  the content. Below API 33 it is off (`UiFilterSupport.active`), with a note on the row. A
  `RenderEffect` snapshots its shader's uniforms, so the effect is rebuilt only for a new size or
  time; built-in filters have no time and are built once, so the layer re-renders only when the
  menu under it changes. A custom file that declares `uniform float uTime;` advances it at ≤ 30 fps
  only while the menu is in use (started, not idle, not E-Ink): it rests with the focus pulse.
- **Uniforms are never probed:** on the API 37 emulator, `RuntimeShader.setFloatUniform` with a
  name the shader doesn't declare aborts the process (JNI: the "unable to find uniform named"
  message is built from a freed string) instead of throwing. Xross reads which of `uResolution`
  (`float2`/`vec2`/`half2`), `uTime` and `uPxPerDp` (`float`/`half`) a file declares from its text
  with comments removed, and sets only those; `uniform shader content;` is required. AGSL compile
  errors still throw (`IllegalArgumentException`): the filter is off and `ShaderHealth.notice`
  toasts the compiler's first line once per file version.
- **Schema:** `ThemeManifest.Effects` gains `backgroundFilter`, `uiFilter`, `uiFilterPath` (alias
  `postProcessPath`); `Effects.KEYS` lists every property and alias, and `unknownKeys` reports any
  other key under the base, a variant, an override or a rule's `apply` with the pack's problems
  (a typo such as `shaderPrest` used to be dropped silently). Both filters ride the `Cosmetics`
  bundle, so variants, overrides and rules carry them.
- **Settings ▸ Theme rows:** after Battery alert tint: Background shader (Wave, Matrix, Bokeh,
  Starfield, CRT), Background filter (Off, Scanlines, CRT grille, LCD grid), UI filter
  (experimental: Off, Scanlines, Bloom), Focus frame style, Ambient particles, App icon mask
  (System, Squircle, Circle, Chrome). Confirm cycles "Theme pack (…)" (the pack's value for the
  chosen variant) then each choice; Options list them. Saved as `display.effects` in
  `settings.json` (`EffectOverrides`, names normalized, a typo or `CUSTOM` follows the pack) and
  applied over the pack's look in every context (`EffectOverrides.applyTo`); a user shader the
  pack never names joins its ahead-of-time library (`LoadedTheme.shaderLibrary(extra)`).
  Xross wallpaper: Confirm opens the system picker when none is set (Options: Pick custom…, Reset
  to theme); the picked document's read grant is persisted and released when replaced or reset.
  Reset to theme defaults (`ThemeDefaults.reset`) clears the six effects, the surface style, the
  three color layers and the Xross wallpaper, and keeps the pack, variant, theme color, quick
  profile and wave settings; its row says what it would clear.
- Debug and profiling builds log `XrossFilter` (`background=SCANLINES program=BOKEH pxPerDp=1.40
  linePx=2 …`), `XrossUiFilter` (`filter=BLOOM state=STATIC sdk=37`; states OFF, UNSUPPORTED,
  FAILED, STATIC, ANIMATING, RESTING) and `XrossEffects` (the resolved shader, filters, frame,
  particles and mask, and whether the user overrides any).

**Unit tests:** `PostProcessSettingsTest` (23): filter names and aliases, `uiFilterPath` implying
CUSTOM and staying in the pack, unknown names reported; the cascade through a variant, a category
override and a rule; `postprocess.agsl` read at load; unknown `effects` keys reported in the base,
a variant, a system override and a rule (aliases accepted), and every `Effects` field in `KEYS`;
pitch snapping at three densities; the filter and CRT sources; API 33 gating; static presets
never animating; a custom filter animating only while in use; uniform detection ignoring comments
and wrong types; the `content` check; the shipped AGSL; overrides winning in every category
(System over a pack's mask); the user shader in the library; normalization; the settings.json
round trip; row cycling and labels; the Settings ▸ Theme rows, their order and summaries; Reset to
theme defaults. Two older tests updated for the new rows and the wallpaper row's action. 645 unit
tests in all.

**Emulator (API 37, host GPU):** device totals 194 → 226. `run-all.sh`: library 41/41, theme
**134/134** (32 new checks), media 30/31, input 11/11, integration 9/9; the media miss was its
wallpaper-row check still expecting the old row position and wording, fixed and rerun **31/31**.
New theme checks: a pack's `LCD_GRID` hot-reloads (4 px cells); a `postprocess.agsl` reading
`uTime` animates, rests after 3 s, draws **0 frames** at rest, and a broken one toasts and turns
off; each Settings row cycles, saves to `display.effects` and changes the log; scanlines are 2 px
apart; Bloom is static and a resting menu under it draws **0 frames**; Pick custom opens the
picker, the picture is saved as a document URI, shows, and **still loads after a restart**; Reset
to theme defaults clears everything and the pack's own effects come back. Checked by eye: CRT
grille plus Bloom over the menu (screenshot).

**Frame pacing (`palette-perf.sh`, profiling build, game navigation, 3 pairs):** the first run
read 0.89–33% janky in both arms, worsening pass by pass; a rerun was 0.30–0.44% in 5 of 6 passes.
An A/B against the `HEAD` build before this slice on the same emulator gave 0.30–0.45% (p50 16
ms, GPU p50 13–14 ms) and a third run of this branch **0.30–0.45% in all six** (p50 16 ms, GPU
p50 14 ms): with no effects set, unchanged, within the 0–1.5% target. With
`backgroundFilter: CRT` and `uiFilter: BLOOM` (`PERF_EFFECTS`, 2 pairs): **0.30–0.44% janky**
(deadline), but p90 19–21 ms and legacy 7–30% while game-art colors morph, as Bloom re-blurs the
whole menu on each frame of the morph; flat (p90 17 ms) with adaptive colors off.
`palette-perf.sh` now resets `display.effects` unless `PERF_EFFECTS` names some.

**`./gradlew check`:** unit tests and `validateShaders` (7 shaders, now with
`screen_filter.frag`) pass; `lintDebug` fails on 10 errors that predate this slice (RestrictedApi
on `dispatchKeyEvent`, Media3 opt-ins in `MediaScreens.kt` and `SnapLayer.kt`,
`LifecycleCurrentStateInComposition`, `UnusedBoxWithConstraintsScope`, a byte-order mark in
`DatParser.kt`); no new lint findings in this slice's code.

**Not covered:** the Galaxy A55 and handhelds (Adreno, Mali, 120 Hz) for both filters; AGSL on a
real Android 13–16 device (only API 37 here) and the picker on a phone; Bloom's GPU time on a slow
GPU (each change renders the menu offscreen and blurs it). The A55 is covered in §3.28.

### 3.28 Phase 2b.4 on the Galaxy A55 (2026-11-13)

All of 2b.4 on the user's Galaxy A55 (Android 17 / API 37, Xclipse 530, 1080×2340 at 120 Hz,
450 dpi), on `main` at `41f64dc`. The phone's ROMs, app data and `Xross/config` were backed up
first and restored after; the RetroArch stub was installed for the launch checks and removed.

**Host:** `./gradlew check`: **645 unit tests** pass (4 skipped), `validateShaders` passes,
`lintDebug` **0 errors** (61 warnings; the 10 older errors were fixed in `5281d15`).

**Device suites (`run-all.sh`, debug build): 215/226.** Library 41/41, theme **134/134**, input
11/11, integration 9/9, media 20/31. The 11 media failures are the Photo and Music listing checks,
which expect the seeded `XrossSmoke` files to be the only media (the phone has its own photos and
music). Every 2b.4 check passed, including the AGSL UI filters (a custom `postprocess.agsl`
animating, resting with 0 frames and failing safely), Pick custom wallpaper through the phone's
picker, Set as Xross wallpaper from a photo, and the music driving `uAudioBass` / `uAudioBeat`.

**By hand, on the user's library (370 arcade games with scraped art and 260 video snaps):**

| Slice | Result |
|---|---|
| 2b.4a presets | All linked at pack load; watchdog GPU p90 (timer query, GPU at its 450 MHz floor) BOKEH 2.90 ms, STARFIELD 3.00, ASCII_MATRIX 1.32, CRT 1.25 (limit 10 ms), unchanged by a background filter |
| 2b.4b audio | The user's songs read bass 0.96–1.00 and 1–3 beats per 2 s; the analysis ran **369–406 ms** ahead of the phone's speaker (475–507 ms on the emulator) and was compensated. A quiet intro logs nothing until the bass moves, as designed. Sync not judged by eye; Bluetooth not tried |
| 2b.4c wallpaper | A 3220×4830 (15.5 MP) photo set from the viewer decodes to 1610×2415, opaque, full-bleed; pre-blurred copy (192×288) in 165 ms warm, 192 ms after a cold start, off the main thread; the GL loop stops. Graphics memory +46 MB with it set after a cold start (83 → 128 MB); the viewer's full-size image is released on leaving Photo |
| 2b.4d icons | Real third-party icons with `Y2K_CHROME_FRAME` drew in 3–11 ms each (one 24 ms outlier; 68 ms on the first run after install), `SQUIRCLE` 2–4 ms, `CIRCLE` 4 ms, all on `Dispatchers.Default`. Chrome bezel, `GOLD_BEZEL` on the focused glyph and `STATIC_NEON` (game-art tint on a game tile) checked by eye |
| 2b.4e particles | `SNOW`, `BOKEH` and `DUST_MOTES` run at 120 Hz (~620 frames in 5 s, 0–0.16% janky even on the debug build), settle in 74 frames (0.6 s), draw **0 frames** at rest and wake on the next key. A playing video snap hides them (`state=HIDDEN`), which had only been unit-tested |
| 2b.4f filters | `pxPerDp` = 1.54 on this panel: 2 px scanlines, 1 px stripes, 5 px LCD cells; the CRT grille is even with no moiré; the `CRT` preset skips the filter pass. `SCANLINES_UI` and `BLOOM` (AGSL) compile and run on real API 37 hardware, are built once (`STATIC`), and a resting menu under them draws 0 frames |

**Frame pacing (`palette-perf.sh`, profiling build, 12 seeded SNES games, game-art colors on/off):**
no effects **0.12–0.19% janky** (deadline) in all 6 passes, p50 12 ms, GPU p50 6–7 ms;
`backgroundFilter: CRT` + `uiFilter: BLOOM` **0.06–0.19%** in 4 passes (p50 11–14 ms; legacy jank
12–52% while colors morph, as Bloom re-blurs the menu each morph frame).

**Particles during game navigation (open finding):** with `foregroundParticles: SNOW` (alone or
with `BOKEH`), 7 of 16 passes ran in a slow mode: render thread ~16 ms per frame instead of ~11,
`dequeueBuffer` waits ~3.6–4.7 ms, gfxinfo GPU p50 9 ms instead of 6, p50 18–19 ms, legacy jank
80–99%. The other 9 particle passes and all 16 passes without particles (none, BOKEH alone, CRT +
Bloom) were normal. Deadline jank stayed 0–0.19% in every pass, so no frames visibly drop; the cost
is latency and power. A time-boxed look found no app-side cause: the UI thread is 2.5–3.4 ms in
both modes (no recomposition: one `mutableLongStateOf` read in `drawBehind`), the draw is ≤40
circles with no per-frame allocation for snow (Bokeh allocates one `Stroke` per frame), and the
mode is per launch, not per preset. It is not thermal (AP flat at 40 °C) or the compositor: in a
slow and a normal pass SurfaceFlinger composited both Xross's window and the wave's `SurfaceView`
as `DEVICE` layers, and `gpu_busy` read 39–46% in both. The leading guess is the phase between the
120 Hz UI frames and the wave's 60 Hz GL frames (`PACED stride=2`) on the shared GPU queue, which
a Perfetto GPU-queue trace would confirm.

**Not covered:** sync of the audio uniforms judged by eye, Bluetooth output latency, E-Ink with a
pack shader, `HARD_SHADOW` and the RA badge frames by eye, handhelds (Adreno, Mali).

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
| 120 Hz on high-refresh panels | ✅ Galaxy A55 at 120 Hz: 0.63–1.46% janky, no missed vsync (§3.11, §3.13); game navigation with game-art colors 0.12–0.19%, GPU p50 6 ms of the 8.3 ms budget (§3.21); no 120 Hz handheld yet |
| Input-to-screen ≤ 16.6 ms | ❓ Not measured (needs high-speed capture on hardware) |
| Cold boot to interactive < 2.5 s | ✅ First frame ~200 ms on the emulator, 377–1,050 ms on the Galaxy A55 (`am start -W`); the fade-in adds 1.75 s by design |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 42 MB PSS in the background on the emulator; 83–122 MB on the Galaxy A55 (84 MB on 2026-11-12, §3.21) |
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
10. **Foreground particles can double frame time while navigating on the Galaxy A55.** In 7 of 16
   game-navigation passes with particles, render time rose from ~11 to ~16 ms and p50 from 12 to
   19 ms, with no extra missed deadlines; the cause isn't found yet (§3.28).

---

## 6. Remaining work

Tracked in one place: [`Roadmap.md`](../Roadmap.md) §3 (known issues) and §4 (not yet
implemented, by phase, including the Phase 2b customization engine).

---

## 7. How to reproduce

```bash
./gradlew :app:testDebugUnitTest                         # unit tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                              # quick device smoke test, 12 checks (clears app data)
tools/device-tests/run-all.sh                            # full device regression: the five suites, 226 checks
./gradlew :app:assembleProfiling                         # optimized build for frame timing
adb shell dumpsys gfxinfo dev.xross.launcher              # UI frame stats
adb logcat -s WaveFrames XrossScan                        # GL pacing + scan timing (debug/profiling)
```
