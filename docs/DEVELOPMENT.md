# Developer guide

Building, testing and finding your way around the Xross source.

Related documents: [`Roadmap.md`](../Roadmap.md) (the plan), [`STATUS.md`](STATUS.md) (test
suites, device runs and measurements), [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) (device
testing in depth).

## Build and run

```bash
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:installDebug           # build + install on a connected device/emulator
./gradlew :app:assembleProfiling      # R8-optimized build for frame timing
./gradlew check                       # unit tests + validateShaders (glslangValidator)
```

**Credentials:** copy [`.env.example`](../.env.example) to `.env` (git-ignored). The build reads
ScreenScraper developer credentials from it. Debug builds only also pre-fill your SteamGridDB key,
ScreenScraper account and RetroAchievements user name and Web API key (never the password) on a
fresh install. See the [user guide](USER_GUIDE.md#credentials-for-builds).

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # JVM + Robolectric tests (live tests skip; count in docs/STATUS.md)
./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true   # real API calls, uses .env and localTestMedia/Roms/
./gradlew :app:testDebugUnitTest --tests '*RetroAchievementsLiveTest*' -PliveScrape=true
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # quick on-device smoke test, ~30 s (see warning below)
tools/device-tests/test-theme.sh                  # one suite: test-{library,theme,media,input,integration}.sh
tools/device-tests/run-all.sh                     # full device regression, all 226 checks (= smoke.sh --all)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

### Live tests

The live tests call Hasheous, SteamGridDB and ScreenScraper for a few files in the git-ignored
`localTestMedia/Roms/` folder (`ARCADE/` and `3DS/` subfolders), and RetroAchievements. Each runs
only when its credentials are in `.env`: ScreenScraper works with the developer credentials alone
(a user account raises the quota); RetroAchievements checks that `RETROACHIEVEMENTS_USER` /
`RETROACHIEVEMENTS_PASSWORD` sign in (the older names `RETROACHIEVEMENT_USER` /
`RETROACHIEVEMENT_USER_PASSWORD` are accepted too), and with `RETROACHIEVEMENTS_WEB_API_KEY` also
links the profile and loads a game's achievements. `localTestMedia/` (ROMs, music and video for
local testing) is never committed.

### Device tests

> **The device tests are for a throwaway emulator.** Each one clears Xross's data and runs
> `rm -rf /sdcard/ROMs` (the media suite also uses `XrossSmoke` folders under Pictures, Music and
> Movies) to seed its own test files, and deletes them at the end. Never run them on a device that
> holds a real ROM collection.

The device tests come in three tiers that share `tools/device-tests/lib/harness.sh`:

1. `smoke.sh` (12 checks: boot, database, library scan, category bar, no crash) for everyday work;
2. five suites that each start from a reset and run on their own (`test-library.sh` 41,
   `test-theme.sh` 134, `test-media.sh` 31, `test-input.sh` 11, `test-integration.sh` 9);
3. `run-all.sh`, which runs them all (226 checks) with one passed / failed / skipped summary and
   exits non-zero on any failure.

Details, timings and optional host tools: [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) §2a. Frame
timing has its own scripts (`palette-perf.sh`, `display-matrix.sh`), which need the profiling
build.

### Emulator stand-ins

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Source layout

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
  profile/  RetroAchievementsClient, ProfileRepository, AchievementsRepository, RaGames, BadgeImages
  state/    MenuTree, MediaMenus, Navigation (NavState), OptionsMenu, Search, NameEntry, AppFolders, RemapSession,
            GameInfo, GameDetails, MediaOverlayController, AchievementsView, ProfileCard, RaHub,
            AvatarEditor, WaveEditor, MiniPlayerLine, LibraryFolders, XmbViewModel
  ui/       XmbScreen, GameTile, MediaScreens, SearchOverlay, OptionsPanel, InfoPanel, DetailsScreen,
            SnapLayer, FocusBackdrop, BusyIndicator, StatusBar, AvatarHud, ProfileAvatar, UserCard,
            AchievementsScreen, RaHubScreen, UnlockToast, MiniPlayerCard, AvatarStylePanel,
            WaveSettingsPanel, Frosted, WaveBackground + WaveRenderer (GLSL), Glyphs
  theme/    XrossTheme palettes, SeasonalPalette (Original theme), WaveStyle, AvatarStyle,
            pack/ (ThemePackLoader, ThemeManifest, ThemeRuntime, SvgIcon)
  audio/    UiSounds (SoundPool effects + streamed boot cues)
app/schemas/         exported Room schemas (v1–v5) used by the migration tests
Roadmap.md           the plan (status, backlog, specs)
docs/                user guide, theme specification, implementation status, hardware testing guide,
                     XMB fidelity, feasibility review, ScreenScraper API guide (English)
.env.example         keys for local service credentials (.env itself is git-ignored)
assets/docs/         PS3 and PSP instruction manuals (local reference only, git-ignored)
assets/examples/     XMB reference screenshots and design mock-ups
tools/icons/         SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven device tests: quick smoke, five suites, run-all.sh, frame timing
```

The tree lists the main classes per package; 2b.4 added shader, effect and cache classes to
`ui/`, `theme/` and `audio/`.

The Default theme pack and the icon tooling are described in the
[Theme 2.0 specification](THEME_SPECIFICATION.md#14-the-default-pack-and-icon-tooling).
