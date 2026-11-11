# Xross — XMB launcher for Android handhelds

A controller-first launcher for Android handhelds in the style of the cross-media bar, built from
[`project_scope.md`](project_scope.md). Phase 1 (Alpha) is complete and Phase 2 (Beta) is under
way. The feasibility review and the decisions behind this build are in
[`docs/FEASIBILITY.md`](docs/FEASIBILITY.md).

Xross was called Wave until 2026-11-11. The application ID changed from `dev.wave.launcher` to
`dev.xross.launcher`, so an older Wave install is a separate app: uninstall it by hand, since its
settings and library don't carry over. Names in the code such as `WaveRenderer` or the
"Wave background" setting refer to the background effect, not the product.

## Build and run

```bash
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:installDebug           # build + install on a connected device/emulator
```

On first launch go to **Settings ▸ Library ▸ All files access** to grant storage access, or press
the "Storage access needed" row in Games, Photo, Music or Video. The same grant covers ROMs and
media. Folders named `ROMs`, `Games`, `Emulation` or `Retro` at the root of any drive are picked
up automatically; add others with **Add ROM folder…**. Put games in platform subfolders
(`snes/`, `psx/`, `gba/`, …). See `PlatformRegistry` in `storage/Platform.kt` for every folder
alias and extension.

### The `Xross/` folder

Everything you'd want to back up or carry to another device lives in one folder, by default
`/storage/emulated/0/Xross/`:

```
Xross/
  config/   settings.json, controllers.json, systems.json (hand-editable; picked up on resume)
  data/     xross.db, a backup snapshot of the library database
  dats/     No-Intro / Redump / MAME DATs to import
  media/    scraped art and videos (has a .nomedia, so it stays out of Photo and Video):
            covers/ 3dboxes/ heroes/ wheels/ screenshots/ capsules/ snaps/, each <system>/<rom name>.<ext>
  roms/     the default base ROMs folder, one folder per system
  themes/   theme packs (has a .nomedia)
```

* **Moving it to an SD card or USB drive:** **Settings ▸ System ▸ Xross folder** steps through the
  mounted drives. Config already in the target folder is used (so a card from another device
  brings its setup along); otherwise the current settings are written there and a database backup
  follows. If the drive is removed, Xross falls back to primary storage. Android 9 and 10 don't
  let apps write to removable cards, so there it stays on primary storage.
* **Before storage access is granted** the same layout lives in app storage, and moves to shared
  storage as soon as Xross may write there.
* **The live database never leaves app storage.** A consistent copy goes to `data/xross.db` a few
  seconds after you leave Xross (when something changed) or with **Settings ▸ System ▸ Back up
  library now**. On a fresh install with an existing backup, Games and Settings ▸ System offer
  **Restore library backup**; Xross restarts to apply it.
* **Accounts stay private.** ScreenScraper and SteamGridDB credentials are never written to
  `config/`; they stay in app storage.
* **Art is named like ES-DE's** (`media/covers/snes/Super Mario World (USA).png`), so it survives
  a library rebuild and ES-DE media copied into `media/` is used as is. Names are cleaned for
  FAT32/exFAT cards. Art from earlier versions is moved here once, in the background.
* **Base ROMs folder:** **Settings ▸ Library ▸ Base ROMs folder…** (default `Xross/roms`) is always
  scanned; **Create system folders** makes `snes/`, `psx/`, … for all 22 systems. Auto-detected
  `ROMs/` folders and folders you add keep working.
* **`systems.json`** sets the emulator per system and lets you add folder names and file
  extensions; each system's `builtIn` block is there for reference and is rewritten on save.
  Settings saved by earlier versions are moved into these files on first start.

Xross runs in landscape and turns 180° with the device. Android 16 ignores the orientation lock
on screens 600 dp and wider.

### Metadata, art and DATs

* **DATs (offline verification):** put No-Intro, Redump or MAME DATs (`.dat`, `.xml`, or zipped)
  in `Xross/dats`, then **Settings ▸ Library ▸ DAT files ▸ Import DATs**. Matched games get
  their exact title, region, languages, revision and serial.
* **Scraping:** **Settings ▸ Library ▸ Metadata & artwork**. Hasheous (identification by hash) needs
  no account. SteamGridDB (capsules, hero banners, logos) needs a free API key. ScreenScraper
  (descriptions, box art, video snaps) needs developer credentials issued to the app, plus
  optionally your own account. Scraping runs only while Xross is in front; automatic scraping is
  off until you turn it on, and Wi-Fi only by default.
* **Per game:** **Options ▸ Details** (or → on a game), **Options ▸ Metadata** to edit the title,
  find the right match by name, scrape again or go back to the automatic match, and
  **Options ▸ Information** for the file, hashes, DAT match and playtime.
* **Credentials for builds:** copy [`.env.example`](.env.example) to `.env` (git-ignored). The build
  reads ScreenScraper developer credentials from it. Debug builds only also pre-fill your
  SteamGridDB key and ScreenScraper account in Settings. An English guide to ScreenScraper's API
  rules is in [`docs/SCREENSCRAPER_API.md`](docs/SCREENSCRAPER_API.md).

## Status

| Area | State |
| --- | --- |
| XMB navigation | Done. Settings, Photo, Music, Video, Games and Apps; per-category drill-down stacks, breadcrumb, spring-animated scroll, touch swipe, tap and long-press |
| Gamepad layer | Done. Vendor detection (Sony/Microsoft/Nintendo/generic) with matching glyphs, confirm/cancel swap (Auto/South/East), radial (including "Off — hardware only") and axial deadzones, anti-snapback, stick/hat auto-repeat, button-remap wizard saved per controller model, Options (△ / Y) menu, Select (or held Guide) for the media controls |
| Portable folder | Done. `Xross/` with JSON config (atomic writes, migration from earlier versions), a library database backup with restore, `.nomedia` for media and themes, and moving the folder to an SD card or USB drive. Not yet tested on a device |
| Library | Done. File-path scanner over internal storage, microSD and USB-OTG; Room DB with an FTS4 title index and gamepad search; incremental rescan; hot-swap that keeps games on removed drives; cue/gdi/m3u de-duplication; favorites, Recently Played and playtime |
| Fingerprinting | Done. CRC32/MD5/SHA-1 inside `.zip`/`.7z`, CHD header SHA-1 and No-Intro header-less hashes, in a background job that stops while a game runs |
| DAT verification | Done. No-Intro, Redump and MAME DATs (Logiqx XML or clrmamepro, plain or zipped) matched by SHA-1, MD5, CRC32 + size or arcade set name; Room schema v5 |
| Scraping and art | Done. Hasheous, SteamGridDB and ScreenScraper with per-service pacing and quotas; art cached on disk per game; box art on game rows; Details screen; manual match and title override; video snaps behind the menu. ScreenScraper awaits developer credentials |
| Emulator dispatch | Done. RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS and ScummVM, chosen per platform or per game. ROMs go out as a path extra plus a `content://` URI with a read grant. Verified with stand-ins only |
| Media hub | Done. Photo viewer (zoom, pan, slideshow, wallpaper), background music player with media-session controls, video player; music pauses for games and videos |
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette and adjustable speed. Pauses whenever the launcher isn't in front |
| Library organization | Done. Hide apps (Options ▸ Hide, Settings ▸ Apps ▸ Hidden apps, Hide system apps), app folders (Emulators, Streaming, Tools or your own name, with emulators suggested), base ROMs folder with Create system folders, and custom Photo/Video folders that also find files MediaStore skips. Not yet tested on a device |
| Extras | Android apps (games sorted into Games ▸ Android Games), 8 color themes including Original (a color per month), busy indicator, optional button hints, Information screen, can be set as the HOME launcher, Android TV leanback entry |
| XMB fidelity | Closer. The home menu's layout, motion, navigation, status bar and options panel match the XMB, and games show their own box art; an XMB-style typeface and cursor sound, a still-image background per game, and Resume/Quit Game are missing |
| Not started | Theme packs and shader settings, on-screen controller, dual screen, RetroAchievements, Xross Portal, FTPS |

[`Roadmap.md`](Roadmap.md) summarizes what is done, what is left, and the order of the remaining
work. [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md) compares Xross with the real XMB, using the
PS3 and PSP instruction manuals in `assets/docs` as the reference. Feature-by-feature test results and device/performance measurements are in
[`docs/STATUS.md`](docs/STATUS.md). Testing on a physical handheld is described in
[`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # 320 JVM + Robolectric tests (2 live tests skip)
./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true   # real API calls, uses .env and localTestMedia/Roms/
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # on-device smoke test (see warning below)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

The live tests call Hasheous, SteamGridDB and ScreenScraper for a few files in the git-ignored
`localTestMedia/Roms/` folder (`ARCADE/` and `3DS/` subfolders). Each runs only when its
credentials are in `.env`. `localTestMedia/` (ROMs, music and video for local testing) is never
committed.

> **`smoke.sh` hasn't been re-run since its last update.** Its key counts now match the current
> Options menu, Controls rows and All Games folder, but it hasn't been run on an emulator since.

> **`smoke.sh` is for a throwaway emulator.** It clears Xross's data and runs
> `rm -rf /sdcard/ROMs` (plus `XrossSmoke` folders under Pictures, Music and Movies) to seed its
> own test files. Never run it on a device that holds a real ROM collection.

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Look and sound

* **Icons** come from `assets/icons` (the design SVGs). `tools/icons/svg_icons.py` splits the
  icon sheets and converts the ones listed in `tools/icons/icon_map.txt` into
  `res/drawable/xmb_*.xml`. To change an icon, edit the map and run:

  ```bash
  python3 tools/icons/svg_icons.py list assets/icons /tmp/icons   # previews every icon id
  python3 tools/icons/svg_icons.py build assets/icons tools/icons/icon_map.txt app/src/main/res/drawable
  ```
* **Themes**: Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original, which
  changes color with the month (Settings ▸ Theme). The same section hides the button-hint pill.
* **Sounds**: `app/src/main/assets/sounds`, mapped in `audio/UiSounds.kt`. Settings ▸ Sound
  toggles effects, volume, the startup cue and the game-start cue.
* **Media**: Settings ▸ Media sets the slideshow interval, whether music resumes after a game or
  stops, video looping, and which folders Photo and Video show (all of them by default).
* **Apps**: Options (△ / Y) on an app hides it or moves it into a folder; Settings ▸ Apps lists
  hidden apps and can hide every system app.

## Layout

```
app/src/main/java/dev/xross/launcher/
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily, ControllerProfiles
  storage/  Platform (PlatformRegistry), RomScanner, Fingerprinter, FileHashes, StorageVolumes,
            VolumeMonitor, XrossPaths, XrossEnvironment (the Xross/ folder), StorageAccess, SystemFolders
  config/   ConfigFiles (settings/controllers/systems JSON), AtomicFiles, ConfigWriter
  data/     Room (GameEntity, GameHashEntity, MetadataEntities, DAOs, XrossDatabase), GameRepository,
            AppRepository, DatRepository, FingerprintQueue, PlaySessionTracker, SettingsStore,
            CredentialStore, DatabaseBackupManager
  data/dat/ DatParser (Logiqx XML, clrmamepro), DatNames, DatMatcher
  scrape/   ScrapeOrchestrator, AssetCache + MediaNames (Xross/media layout), ArtLoader, RateLimiter, Http, Network,
            providers/ (Hasheous, ScreenScraper, SteamGridDB)
  launch/   EmulatorRegistry (intent matrix), GameLauncher (interface), AndroidIntentGameLauncher
  media/    MediaRepository (MediaStore + custom folders), MediaFolderScan, MusicPlayer, PlaybackService (Media3 session), SnapPlayer
  state/    MenuTree, MediaMenus, Navigation (NavState), OptionsMenu, Search, NameEntry, AppFolders, RemapSession,
            GameInfo, GameDetails, MediaOverlayController, XmbViewModel
  ui/       XmbScreen, MediaScreens, SearchOverlay, OptionsPanel, InfoPanel, DetailsScreen,
            SnapLayer, BusyIndicator, StatusBar, WaveBackground + WaveRenderer (GLSL), Glyphs
  theme/    XrossTheme palettes, SeasonalPalette (Original theme)
  audio/    UiSounds (SoundPool effects + streamed boot cues)
app/schemas/         exported Room schemas (v1–v5) used by the migration tests
docs/                feasibility review, implementation status, XMB fidelity, hardware testing guide,
                     ScreenScraper API guide (English)
.env.example         keys for local service credentials (.env itself is git-ignored)
assets/docs/         PS3 and PSP instruction manuals (local reference only, git-ignored)
assets/examples/     XMB reference screenshots and design mock-ups
tools/icons/         SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven smoke test
```
