# Developer guide

Building, testing and finding your way around the Xross source.

Related documents: [`Roadmap.md`](../Roadmap.md) (the plan), [`STATUS.md`](STATUS.md) (test
suites, device runs and measurements), [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) (device
testing in depth) and [`design/ARCHITECTURE_ASSESSMENT.md`](design/ARCHITECTURE_ASSESSMENT.md) (structure,
tech debt and refactoring proposals).

## Build and run

```bash
./gradlew test                        # every module's JVM unit tests (:app, :theme:engine)
./gradlew :app:installDebug           # build + install on a connected device/emulator
./gradlew :app:assembleProfiling      # R8-optimized build for frame timing
./gradlew check                       # unit tests + lintDebug + validateShaders (glslangValidator)
```

The build runs modules in parallel and keeps a configuration cache (`gradle.properties`); a build
file change invalidates it on its own. Shared module setup lives in two convention plugins in
`build-logic/` (`xross.jvm.library`, `xross.android.library`), so a new module's build file is a
few lines.

**Credentials:** copy [`.env.example`](../.env.example) to `.env` (git-ignored). The build reads
ScreenScraper developer credentials from it. Debug builds only also pre-fill your SteamGridDB key,
ScreenScraper account and RetroAchievements user name and Web API key (never the password) on a
fresh install. See the [user guide](USER_GUIDE.md#credentials-for-builds).

**Release:** `./gradlew :app:assembleRelease` signs with the `XROSS_KEY*` entries in `.env` and
fails without ScreenScraper developer credentials (`-PallowNoScreenScraper=true` overrides). The
credentials are masked in the APK, not encrypted: anyone determined can recover them from a
release build, which is how every ScreenScraper client ships. They never go in the repository.

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # JVM + Robolectric tests (live tests skip; count in docs/STATUS.md)
./gradlew :theme:engine:test                      # the theme engine alone: plain JUnit, about a second
./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true   # real API calls, uses .env and localTestMedia/Roms/
./gradlew :app:testDebugUnitTest --tests '*RetroAchievementsLiveTest*' -PliveScrape=true
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # quick on-device smoke test, ~30 s (see warning below)
tools/device-tests/test-theme.sh                  # one suite: test-{library,theme,media,input,integration}.sh
tools/device-tests/run-all.sh                     # full device regression, all 314 checks (= smoke.sh --all)
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
3. `run-all.sh`, which runs them all (314 checks) with one passed / failed / skipped summary and
   exits non-zero on any failure.

Details, timings and optional host tools: [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) §2a. Frame
timing has its own scripts (`palette-perf.sh`, `display-matrix.sh`), which need the profiling
build.

### Emulator stand-ins

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Source layout

Three Gradle modules; the package names don't change between them.

```
core/model/   :core:model (Kotlin/JVM), package dev.xross.launcher.model: pure shared types
              (GlyphKey/GlyphSet, CategoryIds, Platform/PlatformRegistry, ArtKind, FolderNames)
theme/engine/ :theme:engine (Kotlin/JVM, depends on :core:model only): the theme engine
  src/main/kotlin/dev/xross/launcher/theme/
            XrossTheme palettes, SeasonalPalette, ColorIntelligence (colour pipeline, quick
            profiles), ThemeLookResolver, WaveStyle, AvatarStyle, Clock, GamePaletteExtractor
    pack/   ThemeManifest, ThemePackLoader (behind PackPlatform), ThemeRuntime, Layout, Surface,
            Components, Cosmetics, ContextRules, Variants, SoundRoles, UserLook, LookExporter,
            ThemeExport, ThemeArchive
    shader/ shader presets and library, ShaderCanary, FrameBudgetWatchdog
  src/testFixtures/  StressPack, FilePackPlatform + TestAssets (the Default pack from app/src/main/assets)
app/src/main/java/dev/xross/launcher/   :app
  platform/ Android SDK checks (AgslSupport)
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily, ControllerProfiles
  storage/  RomScanner, Fingerprinter, FileHashes, StorageVolumes,
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
            AvatarEditor, WaveEditor, MiniPlayerLine, LibraryFolders, XmbViewModel; the controllers
            beside it (ThemeController, RaController, EditorController, SearchController,
            FolderPickers, LibraryJobs, TransitionController); XmbEvent, the one input type
            (XmbViewModel.onEvent hands each group to its owner)
  ui/       XmbScreen, GameTile, MediaScreens, SearchOverlay, OptionsPanel, InfoPanel, DetailsScreen,
            SnapLayer, FocusBackdrop, BusyIndicator, StatusBar, AvatarHud, ProfileAvatar, UserCard,
            AchievementsScreen, RaHubScreen, UnlockToast, MiniPlayerCard, AvatarStylePanel,
            WaveSettingsPanel, Frosted, WaveBackground + WaveRenderer (GLSL), Glyphs; ScreenSignal
            (what XmbScreen reports to the Activity: backdrop cover, UI filter errors, crosshair X)
  state/LookFlow  the theme cascade's inputs as flows; ThemeController resolves them (StateFlow<ResolvedLook>)
  theme/    the engine's Android adapters: AndroidPackPlatform (assets, VectorIcon/BitmapIcon,
            TypefaceFont, the ThemePackLoader(assets, …) factory), SvgIcon, ThemeWatcher,
            ArtSwatches, RememberThemePalette, ComposeColors, MotionSpecs
  audio/    UiSounds (SoundPool effects + streamed boot cues)
app/schemas/         exported Room schemas (v1–v5) used by the migration tests
build-logic/         convention plugins (included build)
Roadmap.md           the plan (status, backlog, specs)
docs/                user guide, theme specification, implementation status, hardware testing guide,
                     XMB fidelity, feasibility review, ScreenScraper API guide (English)
.env.example         keys for local service credentials (.env itself is git-ignored)
assets/docs/         PS3 and PSP instruction manuals (local reference only, git-ignored)
assets/              Xross screenshots and the app icon source (Xross_icon.svg)
tools/icons/         SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven device tests: quick smoke, five suites, run-all.sh, frame timing
```

**Package layering.** Gradle keeps `:core:model` and `:theme:engine` from seeing `:app`.
Inside `:app`, `ArchitectureTest` (a plain JVM unit test, run by `testDebugUnitTest`) reads every
`dev.xross.launcher` reference in all three modules' main sources, imports and fully qualified
names alike, and fails on an edge outside the target graph of
[`design/ARCHITECTURE_ASSESSMENT.md`](design/ARCHITECTURE_ASSESSMENT.md) §5. In short: `model`
depends on nothing; the theme engine (`theme/`, and its adapters in `:app`) only on `model`;
`data` and `config` (one persistence layer) may use the engine's types but not the reverse;
feature packages (`audio`, `launch`, `scrape`, `profile`, `media`) never reach `state` or `ui`;
`state` never reaches `ui`. It also checks that each module holds only its own packages and that
`:theme:engine` imports nothing from Android or Compose UI except `@Immutable`. A new top-level
package has to be added to the test's table. Prefer imports to inline fully qualified names, which
hide a file's real dependencies.

**The theme engine on other platforms.** `ThemePackLoader` reads folder packs itself and gets the
bundled assets and the decoding of icons and fonts from a `PackPlatform`: `AndroidPackPlatform`
in the app, `FilePackPlatform` in the engine's tests. Icons (`ThemeIcon`) and fonts (`ThemeFont`)
are opaque to the engine. A desktop preview or the Web Studio would add its own platform.

The tree lists the main classes per package; 2b.4 added shader, effect and cache classes to
`ui/`, `theme/` and `audio/`; see
[`design/ARCHITECTURE_ASSESSMENT.md`](design/ARCHITECTURE_ASSESSMENT.md) for package sizes and a proposed
reorganization.

The Default theme pack and the icon tooling are described in the
[Theme 2.0 specification](THEME_SPECIFICATION.md#14-the-default-pack-and-icon-tooling).
