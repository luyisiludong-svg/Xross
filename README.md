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
  data/     xross.db, a backup snapshot of the library database;
            retroachievements.json and ra/ (public profile, game lists and progress caches)
  dats/     No-Intro / Redump / MAME DATs to import
  media/    scraped art and videos (has a .nomedia, so it stays out of Photo and Video):
            covers/ 3dboxes/ heroes/ wheels/ screenshots/ capsules/ snaps/, each <system>/<rom name>.<ext>;
            avatars/<user>.png and ra/badges/<badge>.png for RetroAchievements
  roms/     the default base ROMs folder, one folder per system
  themes/   theme packs, one folder each (has a .nomedia; see "Theme packs" below)
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
* **Accounts stay private.** ScreenScraper and SteamGridDB credentials and the RetroAchievements
  Web API key are never written to `config/` or anywhere else in `Xross/`; they stay in app-private
  storage (`CredentialStore`), excluded from backups.
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
* **RetroAchievements:** **Settings ▸ Accounts** links your account with your user name and Web
  API key (retroachievements.org ▸ Settings ▸ Keys). The status bar shows your avatar; the User
  Card (Up from the top of a category, or tap the avatar) shows rank, points and recent unlocks.
  **Options ▸ Achievements** on a game (or → on the Details screen) opens its badge grid with
  progress, points and unlock dates. Games are matched by ROM hash the way RetroAchievements
  hashes them (header-less for NES/SNES/PC Engine, set name for arcade); disc systems and games
  not fingerprinted yet are matched by exact title. Achievements unlocked in an emulator show as
  unlock toasts when you come back to Xross.
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
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette; speed, ribbon lines, turbulence, thickness and color presets as live uniforms (Settings ▸ Theme ▸ Wave settings). Pauses whenever the launcher isn't in front or game art covers it |
| Theme packs | Done. `Xross/themes/<id>/` with `theme.json`, fonts, SVG/PNG icons and sounds; the bundled icons and sounds are the built-in Default pack |
| RetroAchievements | Done. Account linking, status bar avatar, User Card, per-game achievement lists, unlock toasts. Live sign-in checked; Web API features tested against recorded answers until a Web API key is in `.env` |
| Library organization | Done. Hide apps (Options ▸ Hide, Settings ▸ Apps ▸ Hidden apps, Hide system apps), app folders (Emulators, Streaming, Tools or your own name, with emulators suggested), base ROMs folder with Create system folders, and custom Photo/Video folders that also find files MediaStore skips. Not yet tested on a device |
| Extras | Android apps (games sorted into Games ▸ Android Games), 8 color themes including Original (a color per month), busy indicator, optional button hints, Information screen, can be set as the HOME launcher, Android TV leanback entry |
| XMB fidelity | Closer. The home menu's layout, motion, navigation, status bar and options panel match the XMB, and games show their own box art; an XMB-style typeface and cursor sound, a still-image background per game, and Resume/Quit Game are missing |
| Not started | On-screen controller, dual screen, Xross Portal, FTPS |

[`Roadmap.md`](Roadmap.md) summarizes what is done, what is left, and the order of the remaining
work. [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md) compares Xross with the real XMB, using the
PS3 and PSP instruction manuals in `assets/docs` as the reference. Feature-by-feature test results and device/performance measurements are in
[`docs/STATUS.md`](docs/STATUS.md). Testing on a physical handheld is described in
[`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # JVM + Robolectric tests (live tests skip; count in docs/STATUS.md)
./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true   # real API calls, uses .env and localTestMedia/Roms/
./gradlew :app:testDebugUnitTest --tests '*RetroAchievementsLiveTest*' -PliveScrape=true
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # on-device smoke test (see warning below)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

The live tests call Hasheous, SteamGridDB and ScreenScraper for a few files in the git-ignored
`localTestMedia/Roms/` folder (`ARCADE/` and `3DS/` subfolders), and RetroAchievements. Each runs
only when its credentials are in `.env`: ScreenScraper works with the developer credentials alone
(a user account raises the quota); RetroAchievements checks `RETROACHIEVEMENT_USER` /
`RETROACHIEVEMENT_USER_PASSWORD` sign in, and with `RETROACHIEVEMENTS_WEB_API_KEY` also links the
profile and loads a game's achievements. `localTestMedia/` (ROMs, music and video for local testing) is never
committed.

> **`smoke.sh` is for a throwaway emulator.** It clears Xross's data and runs
> `rm -rf /sdcard/ROMs` (plus `XrossSmoke` folders under Pictures, Music and Movies) to seed its
> own test files. Never run it on a device that holds a real ROM collection.

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Look and sound

* **Default theme pack**: the bundled icons and sounds live in `app/src/main/assets/themes/default/`
  (`theme.json`, `icons/*.svg`, `sounds/*.wav`), kept apart from the app code so they can be
  swapped as a whole. The icons come from `assets/icons` (the design SVGs):
  `tools/icons/svg_icons.py` splits the icon sheets and writes the ones listed in
  `tools/icons/icon_map.txt` as pack SVGs. To change an icon, edit the map and run:

  ```bash
  python3 tools/icons/svg_icons.py list assets/icons /tmp/icons   # previews every icon id
  python3 tools/icons/svg_icons.py build assets/icons tools/icons/icon_map.txt app/src/main/assets/themes/default/icons
  ```
* **Color themes**: Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, Original (a color
  per month), plus "Theme pack" when the active pack has its own palette (Settings ▸ Theme). The
  same section hides the button-hint pill.
* **Sounds** (`audio/UiSounds.kt`), from the active theme pack:

  | Event | Pack file |
  | --- | --- |
  | Every D-pad step, horizontal and vertical (cursor and category moves) | `cursor.wav` |
  | Opening a list or running an item | `confirm.wav` (Default: `cursor.wav`) |
  | Back | `back.wav` |
  | Options menu | `option.wav` (Default: `cursor.wav`) |
  | A setting saved, an action that worked | `ok.wav` |
  | Errors and actions that aren't allowed (Options on a row without a menu, Confirm on an information row, a slider at its end) | `system_ng.wav` |
  | Achievement unlocked | `trophy.wav` |
  | Startup, with the fade-in | `01. opening.wav` |
  | Game start | `gameboot.wav` |

  Settings ▸ Sound toggles effects, volume, the startup sound and the game-start sound.
* **Wave settings** (Settings ▸ Theme ▸ Wave settings): sliders for the shader uniforms, previewed
  live behind the panel and saved in `settings.json` under `display`:

  | Setting | `settings.json` key | Range | Default |
  | --- | --- | --- | --- |
  | Speed | `waveSpeed` | 0.5–2.0× | 1.0 |
  | Ribbon lines | `waveLines` | 0–12 | 5 |
  | Turbulence (amplitude) | `waveTurbulence` | 0–2× | 1.0 |
  | Ribbon thickness | `waveThickness` | 0.5–3× | 1.0 |
  | Colors | `waveTint` | `THEME`, `AURORA`, `SUNSET`, `OCEAN`, `SAKURA`, `PASTEL`, `MONO`, `CUSTOM` | `THEME` |
  | Custom gradient | `waveColors` `{top, bottom, wave}` | `#RRGGBB` | Lavender |

  **Reset to theme defaults** restores the active pack's `wave` values.
* **Focused game background**: resting on a scraped game crossfades its hero art (or a screenshot)
  in after 250 ms; its video snap fades in over it after 1.2 s, with the snap's sound ramping up
  over 300 ms while music stays ducked. The wave stops rendering while art or video covers it.

### Theme packs

Drop a folder into `Xross/themes/` and pick it with **Settings ▸ Theme ▸ Theme pack** (press
Confirm to step through packs, or Options ▸ Look for new packs after copying one in):

```
Xross/themes/<theme_id>/
├── theme.json      name, author, version, palette, wave defaults (all optional except name)
├── fonts/          one .ttf/.otf used for all launcher text
├── icons/          <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
└── sounds/         cursor.wav, confirm.wav, back.wav, option.wav, ok.wav, system_ng.wav,
                    trophy.wav, 01. opening.wav, gameboot.wav
```

```json
{
  "name": "Sakura",
  "author": "you",
  "version": "1.0",
  "palette": { "top": "#2E0A22", "bottom": "#B04C8E", "wave": "#FFC6EA", "glow": "#FFE6F4" },
  "wave": { "speed": 0.8, "lineCount": 7, "turbulence": 1.2, "ribbonThickness": 1.4 },
  "font": "fonts/Rounded.ttf",
  "sounds": { "cursor": "tick.wav" },
  "soundGains": { "cursor": 0.6 },
  "icons": { "album": "disc.svg" }
}
```

* Anything a pack leaves out (a sound, an icon, the font) comes from the Default pack, so a pack
  can be as small as one `theme.json` and a font.
* Icon ids are the lower-case glyph names: `settings`, `games`, `apps`, `photo`, `music`, `video`,
  `album`, `artist`, `shuffle`, `search`, `platform`, `game_cart`, `game_disc`, `favorites`,
  `recent`, `handheld`, `folder`, `storage`, `controls`, `emulators`, `display`, `sound`, `system`,
  `add`, `refresh`, `home`, `info`, `download`, `network`, `users`, `trophy`. Icons are drawn as
  white silhouettes with the XMB gloss, so color doesn't matter. SVGs may use paths, rects,
  circles, ellipses and polygons with translate/scale transforms; black fills are treated as a
  backdrop and dropped. PNG/WebP icons are scaled to 256 px.
* File names are matched without regard to case, and a pack can't reach files outside its
  folder. A pack with its own `palette` switches the color theme to it; its `wave` values become
  the wave settings.
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
