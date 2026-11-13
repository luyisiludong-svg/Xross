# Architecture assessment

A review of Xross's structure at the end of Phase 2b.4 (2026-11-13, commit `2bd8730` plus the
documentation split): what holds up, where the structure is starting to strain, and a prioritized
list of refactorings. It is a proposal; decisions and scheduling belong in
[`Roadmap.md`](../Roadmap.md).

Related documents: the [developer guide](DEVELOPMENT.md) (source layout, build and tests),
[`STATUS.md`](STATUS.md) (frame timings and test evidence referred to below) and
[`THEME_RICING_AND_WEB_STUDIO.md`](THEME_RICING_AND_WEB_STUDIO.md) (the desktop and web targets
that "future preparation" refers to).

## Contents

1. [Snapshot](#1-snapshot)
2. [What already works well](#2-what-already-works-well)
3. [Monolithic module vs. modularization](#3-monolithic-module-vs-modularization)
4. [Package structure and file clutter](#4-package-structure-and-file-clutter)
5. [State management and unidirectional data flow](#5-state-management-and-unidirectional-data-flow)
6. [Separation of concerns across Android, GL, audio and caching](#6-separation-of-concerns-across-android-gl-audio-and-caching)
7. [Other findings](#7-other-findings)
8. [Action plan](#8-action-plan)

## 1. Snapshot

One Gradle application module, `:app`, plus the test-only `:emulator-stub`. Main sources:
**155 Kotlin files, about 31,000 lines**. Tests: **61 files, about 12,000 lines**, all in one
package; 645 JVM/Robolectric tests (4 opt-in live tests skip by default), run in about 26 s
incrementally.

| Package | Files | Lines | Role |
| --- | ---: | ---: | --- |
| `ui/` | 35 | 9,009 | Compose screens and components, GL background, AGSL filters, blur, particles |
| `state/` | 22 | 6,450 | `XmbViewModel`, `MenuTree`, navigation, per-panel presenters |
| `theme/pack/` | 13 | 3,326 | Pack loader, manifest, rules, surfaces, variants, archive, watcher |
| `data/` (+ `dat/`) | 20 | 2,909 | Room, repositories, settings, credentials, backups, icon styling |
| `scrape/` (+ `providers/`) | 12 | 1,823 | Orchestrator, providers, asset cache, HTTP |
| `profile/` | 5 | 1,201 | RetroAchievements client and repositories |
| `media/` (+ `energy/`) | 8 | 1,386 | Music, snaps, MediaStore, audio analysis |
| `storage/` | 10 | 1,124 | Platforms, scanner, fingerprints, volumes, `Xross/` paths |
| `theme/` (+ `shader/`) | 13 | 1,462 | Palettes, color intelligence, shader health |
| `config/`, `input/`, `launch/`, `audio/` | 14 | 1,493 | JSON config, gamepad, emulator intents, UI sounds |

The five largest files hold a fifth of the code: `XmbViewModel.kt` (2,685 lines),
`XmbScreen.kt` (1,573), `MenuTree.kt` (1,313), `WaveRenderer.kt` (684) and
`ThemePackLoader.kt` (650). `MainActivity.kt` is 602.

## 2. What already works well

These are worth keeping through any refactor:

* **`MenuTree` is pure Kotlin.** It imports nothing from Android or Compose (only
  `java.util.Locale`), builds each list lazily and memoizes it per key. The whole menu can be unit
  tested without a device, and it is.
* **The theme engine core is almost platform-free.** `ThemeManifest`, `ThemeRuntime`,
  `ThemeArchive`, `ContextRules`, `Variants`, `Surface`, `Cosmetics` and `ColorIntelligence` import
  nothing from Android; their only Compose dependency is the `@Immutable` annotation. Only
  `ThemePackLoader` (assets, `Typeface`), `SvgIcon`, `Layout` and `ArtSwatches` touch
  `android.*`. That is the hard part of a desktop or web theme studio already done.
* **The audio → GL boundary is clean.** `MusicEnergy` is a Media3 `AudioProcessor` inside
  ExoPlayer's sink; it fills a latency-compensated ring buffer, and `WaveBackground` receives only
  an `AudioLevels?` interface that the renderer samples once per frame. No `RECORD_AUDIO`, no GL
  code in `media/`, no ExoPlayer types in `ui/`.
* **Manual dependency injection is explicit.** `AppContainer` is 209 lines of `lazy` singletons
  with seams (`storageAccess`, `GameLauncher`, `HttpClient`) that tests use.
* **Performance is designed in and measured.** Progress flows are sampled at 500 ms, effects rest
  when input stops, an opaque wallpaper stops the GL loop, and `onTrimMemory` drops decoded art,
  pre-blurred copies, wallpapers and styled icons. Game navigation measures 0.30–0.45% janky
  frames on the profiling build ([`STATUS.md`](STATUS.md) §3.27).

None of the findings below is a measured performance problem today. They are about the cost of
the next phases: more features landing in the same few files, and targets beyond Android.

## 3. Monolithic module vs. modularization

### Where it stands

Everything compiles as one module. At 31,000 lines and a 26 s incremental test run, **build time
is not yet the bottleneck**, so modularizing for speed alone is premature. The stronger reasons
are enforced boundaries, JVM-only tests for the pure parts, and the desktop and Wasm targets in
[`THEME_RICING_AND_WEB_STUDIO.md`](THEME_RICING_AND_WEB_STUDIO.md).

### Blocking cycles

Gradle modules can't depend on each other in a cycle, and the packages already do. Counting
references to another top-level package:

| From → to | References | What crosses |
| --- | ---: | --- |
| `theme` → `ui` | 4 | `XmbMetrics`, `UiFilterSupport`, `ParticleField`, `BackdropTiming` |
| `theme` → `state` | 4 | `GlyphKey`, `Categories` |
| `state` → `ui` | 3 | `UiFilterSupport`, `MiniPlayerCard` |
| `media` → `ui` | 2 | `BackdropTiming` |
| `config` → `state` | 1 | `AppFolders` |
| `data` → `theme` | 19 | `SettingsStore` stores `WaveLimits`, `WaveTint`, `QuickProfile`, pack types |

Meanwhile `ui` → `theme` (95) and `state` → `theme` (70) are the expected direction. Each
reverse edge is a small type (a timing constant, an enum, a support check) that belongs in a
lower layer. **Cutting these six edges is the prerequisite for any module split** and is worth
doing on its own.

### Proposed module boundaries

```
:core:model        pure Kotlin: GlyphKey, Categories, PlatformRegistry, settings model,
                   WaveLimits/WaveTint/QuickProfile, NavAction, timing constants
:core:storage      Xross/ paths and environment, config JSON, AtomicFiles, volumes
:core:database     Room entities, DAOs, migrations, schemas
:theme:engine      pure Kotlin (KMP-ready): manifest, runtime cascade, rules, variants,
                   surfaces, cosmetics, color pipeline, archive validation
:theme:android     ThemePackLoader, SvgIcon, ArtSwatches, ThemeWatcher, IconStyler
:render:gl         WaveBackground, WaveRenderer, GlShaders, background filter pass,
                   shader health, watchdog, canary
:feature:library   scanner, fingerprints, DATs, scraping, emulator launch
:feature:profile   RetroAchievements
:feature:media     MusicPlayer, PlaybackService, SnapPlayer, energy analysis, MediaStore
:app               MainActivity, AppContainer, XmbViewModel, screens and components
```

Where each split pays off:

| Module | Build time | Test isolation | Future targets |
| --- | --- | --- | --- |
| `:theme:engine` | Medium: theme work no longer recompiles the screens | High: pure JVM tests, no Robolectric | **High**: compiles for desktop and Wasm, so the Web Theme Studio validates packs with the launcher's own parser and cascade |
| `:core:model` | Low | High | High: shared by every target |
| `:render:gl` | Medium: shader work stays out of Compose | Medium: shader validation (`validateShaders`) belongs here | Low: GL ES is Android-only; a desktop preview would reuse the GLSL sources, not the host |
| `:core:database` | Medium: KSP for Room runs only when entities change | High: migration tests run alone | Low |
| `:feature:*` | Medium | Medium | Low |

**Recommended order:** cut the cycles, then extract `:core:model` and `:theme:engine` (the KMP
candidates), then `:render:gl`. Leave the `:feature:*` split until `XmbViewModel` is broken up
(§5), since today every feature meets in that one class.

## 4. Package structure and file clutter

### `ui/`: 35 files, five kinds of code

`ui/` has 35 files (not the 60+ it can feel like), but they are five different kinds of code
side by side:

| Kind | Files |
| --- | --- |
| Screens and panels | `XmbScreen`, `DetailsScreen`, `AchievementsScreen`, `RaHubScreen`, `MediaScreens`, `SearchOverlay`, `OptionsPanel`, `InfoPanel`, `UserCard`, `AvatarStylePanel`, `WaveSettingsPanel` |
| Components | `GameTile`, `StatusBar`, `AvatarHud`, `ProfileAvatar`, `BusyIndicator`, `UnlockToast`, `MiniPlayerCard`, `Glyphs`, `XrossSurface`, `Frosted`, `ThemeAccent` |
| OpenGL | `WaveBackground`, `WaveRenderer`, `GlShaders` |
| Effects (Compose draw phase, AGSL, blur) | `AmbientParticles`, `FocusHalo`, `Breathing`, `FrostedBackdrop`, `PreBlur`, `ScreenFilter`, `AdaptiveWave` |
| Backdrops and loaders (with I/O) | `FocusBackdrop`, `SnapLayer`, `WallpaperBackdrop` (contains `WallpaperLoader`, which reads a `ContentResolver` and decodes) |

### Misplaced files elsewhere

* `data/IconStyler.kt` renders bitmaps (masks, plates, duotone). It is image processing, not
  data access.
* `state/BatteryMonitor.kt` is a `BroadcastReceiver` wrapper, and `state/MediaOverlayController.kt`
  holds Android media state. Both are platform adapters sitting next to the pure presenters.
* `theme/shader/` (watchdog, canary, health) and `ui/WaveRenderer` are two halves of one
  subsystem in two packages.
* Tests: all 61 files are in `dev.xross.launcher`, so package-private (`internal`) seams can't
  follow the code they test.

### Proposed layout

A move-only change (no behaviour change, mechanical with the IDE):

```
ui/
  screens/     XmbScreen, DetailsScreen, AchievementsScreen, RaHubScreen, MediaScreens,
               SearchOverlay, OptionsPanel, InfoPanel, UserCard, AvatarStylePanel, WaveSettingsPanel
  components/  GameTile, StatusBar, AvatarHud, ProfileAvatar, BusyIndicator, UnlockToast,
               MiniPlayerCard, Glyphs, XrossSurface, Frosted, ThemeAccent
  backdrop/    FocusBackdrop, SnapLayer, WallpaperBackdrop, FrostedBackdrop
  effects/     AmbientParticles, FocusHalo, Breathing, ScreenFilter (AGSL), AdaptiveWave
  gl/          WaveBackground, WaveRenderer, GlShaders, + theme/shader/* (health, watchdog, canary)
imaging/       PreBlur, IconStyler (+ the icon cache now in AppRepository), WallpaperLoader,
               theme.LruCache: decode, blur, style, cache
platform/      BatteryMonitor, VolumeMonitor, StorageAccess: Android adapters behind interfaces
```

Mirror the same packages under `app/src/test/`.

## 5. State management and unidirectional data flow

### What is unidirectional today

The core loop is sound: input becomes a `NavAction` → `XmbViewModel.onAction` → `nav` /
`transient` `MutableStateFlow`s → `combine` with the repositories' flows → one
`StateFlow<XmbUiState>` → `collectAsStateWithLifecycle()` in `MainActivity`. One-off events
(pickers, launches) go out through a `Channel<UiEffect>`. Fast inputs read `current()`
synchronously from the source flows, so a burst of D-pad presses never acts on a stale frame.

### Where it strains

1. **`XmbViewModel` is a god object.** At 2,685 lines and 146 functions it owns navigation,
   search, name entry, remapping, options menus, Details, RetroAchievements, the avatar and wave
   editors, wallpaper and theme import, ROM, DAT and media folder pickers, volume events, scanning
   and backups. Every feature in the roadmap lands here, which makes it the merge-conflict and
   review hotspot.
2. **One 36-field `XmbUiState` is read at the root.** `MainActivity.setContent` reads `state`, so
   any change (a toast, the busy flag, a Now Playing change) re-runs the root composable. Kotlin
   2.2's strong skipping lets unchanged children skip, but `XmbScreen` receives the whole `state`
   and so do `ButtonHints`, `OverlayHints` and `ItemColumn`. The measured jank shows it's
   affordable today; it gets more expensive with every field added.
3. **46 parameters on `XmbScreen`.** Besides `onAction(NavAction)` there are 31 more `on…`
   callbacks (`onWaveSlider`, `onAvatarRowTap`, `onProfileCardButtonTap`, …). Two event styles
   coexist, and every new panel touches `XmbScreen`'s signature and `MainActivity`'s wiring.
4. **Theme resolution happens in the Activity.** The cascade (pack → variant → category →
   system → rules for battery and clock → user effects → quick profile → layout) is computed with
   `remember` blocks inside `MainActivity.setContent`. It is the most rule-heavy logic in the app,
   and today it is only reachable through a composition.
5. **Global snapshot-state singletons.** `ThemeTypography`, `ThemeSurfaces`, `ThemeAccent`,
   `FrostedBackdrop` (wallpaper, hero, host coordinates) and `ParticleRest` are `object`s with
   `mutableStateOf` fields, written from `LaunchedEffect`s and read during drawing. This is a
   deliberate trick (draw-phase reads, so a surface switch redraws without recomposing), but it is
   state outside the UDF loop: tests and previews share it, and two compositions (a Settings
   preview beside the live menu, a future desktop window) would fight over it.
6. **A second state holder on the side.** `MediaOverlayController` is created by the ViewModel
   and then handed to the UI directly (`viewModel.media`), so the media overlays have their own
   event path.

### Recommendations

* **Split the ViewModel by feature, keep one facade.** Extract plain controllers with their own
  `StateFlow` and an `onEvent`, following the pure presenter `object`s that already exist
  (`WaveEditor`, `ProfileCard`, `AchievementsGrid`): `SearchController`, `ProfileController`
  (avatar HUD, User Card, RA hub, achievements), `EditorController` (wave and avatar editors),
  `FolderPickers`, `LibraryJobs` (scan, DATs, volumes, backup). `XmbViewModel` keeps navigation
  and combines their states.
* **Move theme resolution into a `ThemeLookResolver`** in `theme/` (pure, unit-testable) that
  produces a `StateFlow<ResolvedLook>` from the pack, settings, focused category and system,
  battery and minute. `MainActivity` then only collects it.
* **One event type.** A sealed `XmbEvent` (with `NavAction` as one case) and a single
  `onEvent: (XmbEvent) -> Unit` replaces the 32 callbacks.
* **Collect state per region.** Hand composables a slice (`state.map { it.statusBar }
  .distinctUntilChanged()`, or a small `@Immutable` sub-state per region), so a toast doesn't run
  the item column's code. Measure with the existing `palette-perf.sh` before and after.
* **Replace the singletons with holders provided through `CompositionLocal`**, created once in
  `MainActivity` (or the resolver). The draw-phase read trick still works, because a
  `mutableStateOf` inside a provided holder is read the same way.

## 6. Separation of concerns across Android, GL, audio and caching

| Boundary | Today | Assessment |
| --- | --- | --- |
| ExoPlayer audio → shader uniforms | `MusicEnergy` (Media3 `AudioProcessor`) → `BassAnalyzer` → `AudioEnergyRing` → `AudioLevels` → `WaveRenderer` reads once per frame | **Good.** Narrow interface, no shared threads beyond the ring buffer, tested on the JVM |
| GL host ↔ Compose | `WaveBackground` wraps a `GLSurfaceView` in `AndroidView`, with its own vsync pacer and render-mode switching; `WaveRenderer` hosts several programs, the background filter pass and the watchdog hooks | **Good boundary, crowded file.** Split `WaveRenderer` into a program host, a filter pass and uniform binding when it moves to `ui/gl/` |
| Shader safety | `theme/shader/` (watchdog, canary, health) vs `ui/WaveRenderer` | **Split across packages.** One `gl/` package (or `:render:gl`) |
| Compose draw-phase effects | `AmbientParticles` (flat arrays, one `graphicsLayer`, rests), `FocusHalo`, `Breathing` | **Good.** Self-contained, idle-aware |
| Frosted glass | Backdrop layers write `FrostedBackdrop.wallpaper/hero/host` (a global), and surfaces read it to cut their blurred slice | **Implicit coupling.** Provide a `FrostRegistry` through a `CompositionLocal` |
| Blur and icon caches | `PreBlur` (`object` in `ui/`, `theme.LruCache`), `IconStyler` (renderer in `data/`) with its cache as an `android.util.LruCache` in `AppRepository`, `WallpaperLoader` (in `ui/`), `ArtLoader` (in `scrape/`); each keeps its own cache sized independently, with two different LRU classes; `XrossApplication.onTrimMemory` calls four different trims | **Scattered.** One `imaging/` package with a shared memory budget and a single `trim(level)` entry point |
| Settings model ↔ theme | `SettingsStore` (`data/`) stores theme types (`WaveLimits`, `QuickProfile`, pack types) | **Inverted.** Move those value types to `:core:model`, so settings don't depend on the theme engine |

## 7. Other findings

* **Lint debt:** `lintDebug` fails on 10 errors that predate 2b.4f (RestrictedApi on
  `dispatchKeyEvent`, Media3 opt-ins in `MediaScreens.kt` and `SnapLayer.kt`,
  `LifecycleCurrentStateInComposition`, `UnusedBoxWithConstraintsScope`, a byte-order mark in
  `DatParser.kt`; [`STATUS.md`](STATUS.md) §3.27). While it fails, lint can't be a CI gate, so
  new findings go unnoticed.
* **Fully qualified names inline.** `AppContainer`, `XmbViewModel` and `MainActivity` refer to
  many types as `dev.xross.launcher.theme.pack.ThemeRuntime` and similar inside code rather than
  importing them. It hides the real dependency list (and made this review's import count harder).
* **No license.** The repository has no `LICENSE` file, so it is not yet open source in the legal
  sense, and the README badge says so.
* **Asset provenance.** Several files in `assets/sounds/` (`snd_cancel.wav`, `coldboot_stereo.wav`,
  `gameboot_stereo.wav`) are named like console system sounds, and the Default pack's sounds are
  built from that folder. Confirm where they came from and that they may be redistributed before
  choosing a license or publishing builds.
* **`AppContainer` notes "swap for Hilt once the module split happens".** Manual DI is fine at this
  size and keeps the build simple; reconsider only once there are several feature modules.

## 8. Action plan

### High impact, low effort

| # | Change | Why | Effort |
| --- | --- | --- | --- |
| 1 | Fix the 10 lint errors and make `lintDebug` part of `check` | Turns lint into a gate before the refactors below | ½ day |
| 2 | Cut the six reverse package edges (§3): move `BackdropTiming`, `UiFilterSupport`, `GlyphKey`, `Categories`, `AppFolders` constants and the settings value types down a layer | Unblocks every module split; no behaviour change | 1 day |
| 3 | Move-only package reorganization of `ui/` plus `imaging/` and `platform/` (§4), and mirror test packages | Findability and reviewable ownership; IDE-mechanical | 1 day |
| 4 | Extract `ThemeLookResolver` out of `MainActivity.setContent` (§5) with unit tests for the cascade | The most rule-heavy logic becomes testable without Compose | 1–2 days |
| 5 | Settle the license and the provenance of `assets/sounds/` (§7) | Needed before any public release | Owner decision |

### Structural refactoring

| # | Change | Why | Effort |
| --- | --- | --- | --- |
| 6 | Split `XmbViewModel` into feature controllers behind one facade (§5) | Stops every feature landing in one 2,700-line class | 1 week, one controller at a time |
| 7 | One `XmbEvent` sink in place of 32 callbacks on `XmbScreen` | Smaller signatures; new panels don't touch `MainActivity` | 2 days, after 6 |
| 8 | Replace the global snapshot singletons with `CompositionLocal` holders (§5, §6) | Previews, tests and a second composition stop sharing state | 2 days |
| 9 | Per-region state slices for the status bar, item column and overlays, verified with `palette-perf.sh` | Keeps recomposition flat as `XmbUiState` grows | 2–3 days |
| 10 | One `imaging/` cache policy with a shared memory budget and a single `trim(level)` | Predictable memory on 3–4 GB handhelds | 2 days |

### Future preparation

| # | Change | Why | Effort |
| --- | --- | --- | --- |
| 11 | Extract `:core:model` and `:theme:engine` as Kotlin Multiplatform modules (JVM + Android first, Wasm later) | The Web Theme Studio and desktop preview reuse the real parser, rules and cascade instead of a copy | 1 week, after 2 and 4 |
| 12 | Extract `:render:gl` with `validateShaders` moved into it | Shader work isolated, GLSL sources reusable by a desktop preview | 3 days |
| 13 | Extract `:core:database` and the `:feature:*` modules | Build-time and ownership gains once the app is larger | After 6; when incremental builds pass ~1 min |
| 14 | Revisit Hilt (or Koin, if KMP) only after 13 | Manual DI is adequate until then | — |
