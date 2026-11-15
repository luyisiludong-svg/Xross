# Architecture assessment

A review of Xross's structure now that Theme 2.0 is complete (2026-11-15, `main` at `64b6f79`,
after Phase 2b.8): what changed since the first review, whether it is time to refactor, and the
best way to modularize. It replaces the 2026-11-13 review written after Phase 2b.4 (in git
history). It is a proposal; decisions and scheduling belong in [`Roadmap.md`](../../Roadmap.md).

**Progress (2026-11-15, `refactor/phase3-prep`):** steps 1–5 of the [action plan](#9-action-plan),
the "Phase 3-prep" slot, are done: the layering test, the nine edges cut, convention plugins and
the configuration cache, `:core:model` and `:theme:engine` as Kotlin/JVM modules, and the look
inputs in `ThemeController`. How it was built, where it differs from this proposal and what it did
not verify: [§10](#10-as-built-phase-3-prep-steps-15). Next is step 6, the ViewModel split.

Related documents: the [developer guide](../DEVELOPMENT.md) (source layout, build and tests), the
[status dashboard](../STATUS.md) (test and frame-timing evidence) and
[`THEME_RICING_AND_WEB_STUDIO.md`](THEME_RICING_AND_WEB_STUDIO.md) (the browser and desktop
targets that a shared theme engine would serve).

## Contents

1. [Verdict](#1-verdict)
2. [What changed since 2b.4](#2-what-changed-since-2b4)
3. [What still holds up](#3-what-still-holds-up)
4. [Package dependencies today](#4-package-dependencies-today)
5. [The target module graph](#5-the-target-module-graph)
6. [How to get there](#6-how-to-get-there)
7. [State management](#7-state-management)
8. [Other findings](#8-other-findings)
9. [Action plan](#9-action-plan)
10. [As built: Phase 3-prep, steps 1–5](#10-as-built-phase-3-prep-steps-15)

## 1. Verdict

**Yes, refactor now, but boundaries first and modules second, in small shippable steps rather
than one big move.** Four reasons:

1. **The theme engine is stable.** The pack format is frozen behind the A.13 round-trip gate
   (`ThemeRoundTripTest`: every scope of the Default and stress packs through export, `.xtheme`,
   import and load, resolved equal in 126 contexts). An API that stopped moving is the right
   thing to put behind a module boundary. During 2b.2–2b.8 it would have been churn.
2. **Every strain the first review measured got worse** (§2): `state/` grew by 45%, the
   `XmbScreen` signature by ten parameters, `XmbUiState` by six fields, and three new package
   cycles appeared.
3. **What comes next is new code in new areas.** Phase 3 (dual screen, docking, Xross Portal,
   FTPS) and 2b.9 (Web Theme Studio) have no code yet. Giving them a module before they are
   written costs almost nothing; extracting them afterwards costs what extracting the theme engine
   costs now.
4. **The branch is quiet.** Every 2b feature branch is merged, so moving files now creates no
   merge conflicts. That stops being true once Phase 3 work starts.

**Build time is still not the reason.** A full Kotlin compile of `:app` (`compileDebugKotlin
--rerun`) takes about 15 s with a warm Gradle daemon on the development Mac, and day-to-day changes compile incrementally.
Modules will help builds, but the payoff that matters is enforced boundaries, pure-JVM tests for
the engine and a home for Phase 3 and 2b.9.

**What not to do:** a big-bang split into ten modules, Kotlin Multiplatform before 2b.9 starts,
or Hilt. Each is discussed below.

## 2. What changed since 2b.4

| Measure | 2026-11-13 (after 2b.4) | 2026-11-15 (after 2b.8) | Trend |
| --- | ---: | ---: | --- |
| Main sources | 155 files, ~31,000 lines | 189 files, ~40,400 lines | +30% |
| Tests | 61 files, ~12,000 lines, 645 tests | 90 files, ~18,800 lines, 932 tests | +45% tests; still one package, 64 of 90 files on Robolectric |
| `state/` | 22 files, 6,450 lines | 33 files, 9,346 lines | +45% |
| `ui/` | 35 files, 9,009 lines | 40 files, 10,942 lines | +21% |
| `theme/` (incl. `pack/`, `shader/`) | 26 files, 4,788 lines | 34 files, 7,231 lines | +51% |
| `XmbViewModel.kt` | 2,685 lines | 2,359 lines | Smaller, but only because `ThemeController` (927), `RaController` (681) and `AudioDirector` (377) were extracted next to it |
| `XmbUiState` fields | 36 | 42 | Worse |
| `XmbScreen` parameters / `on…` callbacks | 46 / 31 | 56 / 41 | Worse |
| `MainActivity.kt` | 602 lines | 655 lines | Worse |
| Global snapshot-state `object`s | 6 | 6 (`ThemeTypography`, `ThemeSurfaces`, `ThemeAccent`, `FrostedBackdrop`, `ParticleRest`, `PreBlur`) | Unchanged |
| Real reverse package edges | 6 | 9 (§4) | Worse |

What the first review's action plan delivered:

| # | 2026-11-13 recommendation | State |
| --- | --- | --- |
| 1 | Lint as a gate | ✅ `lintDebug` 0 errors, run by `check` |
| 2 | Cut the six reverse package edges | ◐ `GlyphKey` and `GlyphSet` moved to `model/`; `theme` → `ui` is down to KDoc links. `theme` → `state` (`Categories`), `media` → `ui` and `config` → `state` remain, `state` → `ui` grew with the 2b.5d boot sequence, and new cycles appeared (§4) |
| 3 | Move-only package reorganization | ❌ `ui/` is still flat (40 files) |
| 4 | `ThemeLookResolver` | ◐ The pure resolver exists (2b.5-prep) and quick profiles are one layer. Its inputs (settled system, battery and minute rules, user look) are still assembled with `remember` in `MainActivity.setContent` |
| 5 | License and sound provenance | ◐ Apache 2.0 `LICENSE` added. `assets/sounds/` still has console-named files (`snd_cancel.wav`, `coldboot_stereo.wav`, `gameboot_stereo.wav`). The original Default pack (Roadmap §4.2) is still due before any public release |
| 6 | Split `XmbViewModel` into controllers | ◐ `ThemeController`, `RaController`, `AudioDirector` and `StatusBarSource` exist, each with a `Host` interface and tests. Search, the editors, the folder pickers and the library jobs are still in the ViewModel |
| 7 | One `XmbEvent` sink | ❌ The callbacks grew from 31 to 41 |
| 8 | Replace the global singletons | ❌ |
| 9 | Per-region state slices | ◐ `StatusBarState` is its own `StateFlow` (2b.7-prep); nothing else |
| 10 | One imaging cache policy | ❌ `onTrimMemory` still calls four different trims |

The pattern is clear: every refactoring that sat directly under a feature slice (lint, resolver,
the controllers) got done as a prep slice, and everything else did not. That worked during
Theme 2.0. It also means the remaining items will not get done as a side effect of feature work.
They need their own slot.

## 3. What still holds up

These are worth keeping through any refactor:

* **`MenuTree` is pure Kotlin** (1,634 lines). It builds each list lazily and memoizes per key, and
  it is fully unit-tested without a device.
* **The theme engine is almost platform-free.** Of 34 files in `theme/`, only `ThemePackLoader`
  (assets, `Typeface`, bitmaps), `SvgIcon`, `ThemeWatcher` (`FileObserver`), `ArtSwatches`
  (`Palette`), `RememberThemePalette` (composables), `XrossTheme` (`Color`) and `Layout` (Compose
  `AnimationSpec`) touch Android or Compose UI. Seventeen files import only the `@Immutable`
  annotation.
* **The controller pattern works.** `ThemeController`, `RaController` and `AudioDirector` each own
  their state and talk to the ViewModel through a narrow `Host` interface. That is the template
  for the rest of the split, and for feature modules.
* **The audio boundaries are clean.** `MusicEnergy` → `AudioLevels` → `WaveRenderer` for the
  uniforms, and `AudioDirector` decides volumes and ducking through the `MusicOutput` and
  `BgmOutput` interfaces, so `audio/` never sees ExoPlayer and `media/` never makes a mixing
  decision.
* **Manual dependency injection is still adequate.** `AppContainer` is 230 lines of `lazy`
  singletons with test seams.
* **Performance is designed in and measured.** Idle rest, opaque-wallpaper GL stop, `onTrimMemory`
  and the `palette-perf.sh` / `layout-perf.sh` gates (0.3–0.75% janky on the profiling build in
  2b.8).

## 4. Package dependencies today

> **Done (step 2, `fa3cb79`):** all nine edges below are cut, and `ArchitectureTest` keeps them
> cut. The table is kept as the record of what crossed and how it was fixed.

Counting real references between top-level packages (imports and fully qualified names in code,
KDoc links excluded), these edges go the wrong way or form a cycle:

| Edge | Refs | What crosses | Fix |
| --- | ---: | --- | --- |
| `state` → `ui` | 16 | `BootSequence.resolve`, `BootPlan`, `BootState`, `BootMode` (2b.5d); `UiFilterSupport` | Split `ui/BootSequence.kt`: the pure plan and state go to `state/` (or the theme engine, beside `BootIntro`), the composable stays. `UiFilterSupport` (an SDK check) goes to `platform/` |
| `theme` → `data` | 5 | `XrossSettings` in `UserLook.of`, `LookExporter`, `EffectOverrides.reset` | **New.** The engine must not read the settings model. Make `UserLook.of(settings)` an extension in `data/`, and have `LookExporter` and `reset` take a `UserLook` |
| `theme` → `state` | 2 | `Categories` (category ids in `Variants`, `ContextRules`) | Category ids to `model/` |
| `theme` → `storage` | 1 | `PlatformRegistry` | `Platform` and `PlatformRegistry` are pure; move them to `model/` |
| `theme` → `scrape` | 1 | `ArtKind` | `ArtKind` to `model/` |
| `media` → `ui` | 2 | `BackdropTiming.AUDIO_RAMP_MS`, `rampVolume` | Timing constants to `model/` (or `audio/`, which owns the ramp) |
| `config` → `state` | 1 | `AppFolders.normalizeName` | Move the name rule to `config/` or `model/` |
| `data` ↔ `config` | 4 / 1 | `AtomicFiles`, `ConfigCodec`, `ConfigWriter` / `XrossSettings` | **New cycle.** They are one persistence layer; keep both packages but put them in the same module (`:core:data`) |
| `data`, `config` ↔ `audio` | 3 / 2 | `MusicUnderSnaps` (a setting) / `XrossSettings` | **New cycle.** `MusicUnderSnaps` is a settings value: move it to `data/` |

The direction that the first review called "inverted", `data` → `theme` (the settings model
storing `WaveLimits`, `ComponentOverrides`, `LayoutOverrides`, `EffectOverrides`), is better
accepted than fought: those are the pack schema's own override types, so settings may depend on
the engine. The rule becomes **the engine depends on nothing but `model`**, which is a smaller
change than moving every override type into a model module.

## 5. The target module graph

> **Built so far:** `:core:model` and `:theme:engine` (step 4). The engine took the pack loader
> with it behind a `PackPlatform` interface, so `:theme:android` is down to seven small adapter
> files that stay in `:app` until a second module needs them (§10).

```
                         :app  (MainActivity, AppContainer, XmbViewModel, screens)
              ┌────────────┼───────────────┬──────────────────┬─────────────────┐
              ▼            ▼               ▼                  ▼                 ▼
      :feature:ra   :feature:library  :feature:media   :feature:portal*  :feature:ftps*
      (profile/,    (storage/ scan,   (media/, audio/)  (*Phase 3: born as modules)
      RaController, scrape/, launch/,
      RA screens)   data/dat)
              │            │               │
              └──────┬─────┴───────┬───────┘
                     ▼             ▼
               :core:data     :render:gl            :theme:android
               (Room, DAOs,   (WaveBackground,      (ThemePackLoader, SvgIcon,
               SettingsStore, WaveRenderer,         ThemeWatcher, ArtSwatches,
               config/ JSON,  GlShaders, filter     Compose adapters for Layout
               CredentialStore) pass, theme/shader/) motion and colors)
                     │             │                     │
                     └──────┬──────┴─────────────────────┘
                            ▼
                      :theme:engine   (Kotlin/JVM: manifest, runtime cascade, rules, variants,
                            │          surfaces, components, cosmetics, colour pipeline,
                            │          ThemeLookResolver, UserLook, LookExporter, ThemeArchive)
                            ▼
                      :core:model     (Kotlin/JVM: GlyphKey/GlyphSet, category ids, Platform/
                                       PlatformRegistry, ArtKind, NavAction, timing constants)
```

Where each module pays off:

| Module | Pays off through | When |
| --- | --- | --- |
| `:core:model` + `:theme:engine` | **High.** The engine's tests run as plain JUnit with no Robolectric; theme work stops recompiling screens; 2b.9 and a desktop preview reuse the real parser and cascade | Now |
| `:core:data` | Medium. KSP for Room runs only when entities change; migration tests stand alone; ends the `data` ↔ `config` cycle | After the engine |
| `:render:gl` | Medium. Shader work and `validateShaders` stay out of Compose; GLSL sources reusable by a desktop preview | After the engine |
| `:feature:portal`, `:feature:ftps` | High for little cost: new code starts behind a boundary | When Phase 3 starts |
| `:feature:ra` | Medium. The first vertical feature; `RaController` already has the `Host` seam | After the ViewModel split (§7) |
| `:feature:library`, `:feature:media` | Medium | Last; only once the ViewModel no longer reaches into them directly |

**Kotlin/JVM now, Kotlin Multiplatform later.** The engine imports `java.io.File` (9 times),
`java.time` (4) and `java.util.Locale` (14), and `ThemeArchive` uses `java.util.zip`. A
`kotlin("jvm")` library takes them as they are, and an Android app depends on it directly. Going
to `commonMain` would mean porting those to `kotlinx-io` (or Okio) and `kotlinx-datetime`. Do that
only when 2b.9 starts, and only if the Web Studio must run the engine in the browser. A cheaper
alternative to weigh then: generate a JSON Schema from the engine at build time and have the
Studio validate against it, which keeps the engine JVM-only. A JVM module now keeps both options
open.

**The `@Immutable` annotation** in 17 engine files: depend on
`androidx.compose.runtime:runtime-annotation` (annotation only, multiplatform), or drop the
annotations and list the engine's packages in a Compose compiler stability configuration file in
`:app`. Either way the engine does not pull in Compose.

**Keep `Layout.kt`'s data in the engine and its Compose mapping out.** Today it builds
`spring()` / `snap()` specs. The engine keeps the motion profile as numbers (damping, stiffness,
"instant"), and `:theme:android` maps them to `AnimationSpec`.

## 6. How to get there

Each step is shippable on its own and verified the same way as a feature slice: unit tests,
`lintDebug`, `smoke.sh` and the theme suite on the emulator, `palette-perf.sh` on the profiling
build.

**Step 1: enforce the layering before moving anything (1 day).** ✅ Done (`fa3cb79`). Add a JVM unit test that reads
the `import` lines under `app/src/main` and fails on any package edge outside the target graph in
§5. It needs no library (a few dozen lines of Kotlin, or Konsist if a library is acceptable) and
it keeps the rules while everything still lives in `:app`. Then cut the nine edges in §4 until it
passes (1–2 days, move-only apart from the `UserLook` and `BootSequence` splits).

**Step 2: extract `:core:model` and `:theme:engine` (3–4 days).** ✅ Done (§10). Add a `build-logic/` included
build with two convention plugins (`xross.jvm.library`, `xross.android.library`), so every later
module is a five-line build file. Move the engine's tests with it as plain JUnit, and move the
engine-specific Robolectric tests over as they lose their Android dependency. Turn on the Gradle
configuration cache and parallel execution (`gradle.properties` sets neither today), which only
start to matter once there is more than one module.

**Step 3: finish the ViewModel split (1–1.5 weeks, one controller at a time, §7).** This is the
precondition for any feature module, because today every feature meets in `XmbViewModel`.

**Step 4: `:core:data` and `:render:gl` (about 3 days each).** Room's KSP and schema export move
with `:core:data`; `validateShaders` moves into `:render:gl`.

**Step 5: feature modules, newest first.** Phase 3's Portal and FTPS start as `:feature:*`
modules. `:feature:ra` is the first extraction, since `profile/` plus `RaController` plus the
three RA screens are already a vertical slice. `:feature:library` and `:feature:media` come last.

**Dependency injection:** stay manual. `AppContainer` becomes the one place that wires modules
together, and each feature module exposes a small factory. Hilt adds annotation processing to
every module and does not work in a multiplatform engine; reconsider (Koin, if KMP) only after
step 5.

## 7. State management

The core loop is still sound: `NavAction` → `XmbViewModel.onAction` → `MutableStateFlow`s →
`combine` → one `StateFlow<XmbUiState>`, with one-off events through a `Channel<UiEffect>`. What
strains it:

1. **The ViewModel is smaller but the state layer is not.** Extracting `ThemeController` and
   `RaController` moved 1,600 lines next to the ViewModel without shrinking what it coordinates.
   The remaining extractions, each following the `Host` pattern: `SearchController` (search and
   name entry), `EditorController` (wave, avatar and layout editors), `FolderPickers` (ROM, DAT,
   media folders, wallpaper and theme import), `LibraryJobs` (scan, DATs, volume events, backup),
   and boot and launch transitions.
2. **One event type.** A sealed `XmbEvent` (with `NavAction` as one case) and a single
   `onEvent: (XmbEvent) -> Unit` replace the 41 callbacks on `XmbScreen`. Each controller handles
   its own subset, so a new panel adds a case, not a parameter on `XmbScreen` and a lambda in
   `MainActivity`. Do it controller by controller, after each extraction.
3. **Per-region state.** `StatusBarState` showed the way in 2b.7-prep. Do the same for the item
   column and the overlays, so a toast or a Now Playing change doesn't run the item column's code.
   Measure with `palette-perf.sh` before and after.
4. ✅ **Move the look inputs out of `setContent`** (done, §10). `ThemeLookResolver.resolve` is pure, but the
   settled system, the battery and minute rule filters and `UserLook.of(settings)` are still
   assembled with `remember` in `MainActivity`. Moving them into `ThemeController` as a
   `StateFlow<ResolvedLook>` makes the whole cascade testable without Compose and leaves the
   Activity to collect one value.
5. **Replace the six snapshot-state singletons with holders provided through `CompositionLocal`**,
   created once in `MainActivity`. The draw-phase read trick still works, because a
   `mutableStateOf` inside a provided holder is read the same way. This matters as soon as there
   are two compositions (a Settings preview beside the live menu, or a desktop preview window).
6. **`MediaOverlayController`** is still handed to the UI directly (`viewModel.media`); fold it
   into the event sink when the media screens are touched next.

## 8. Other findings

* **Fully qualified names inline** are still common: 44 lines in `AppContainer`, 40 in
  `MainActivity` and 67 in `XmbViewModel` spell out `dev.xross.launcher.….Type` instead of
  importing it. That hides the real dependency list, and the step 1 test will read imports, so
  replace them first.
* **Tests are all in one package** (`dev.xross.launcher`, 90 files). Mirror the source packages
  when they move, so `internal` seams can follow the code they test.
* **`ui/` is flat** (40 files of five kinds: screens, components, GL, draw-phase effects,
  backdrops). Split it into `screens/`, `components/`, `backdrop/` and `effects/` as files move
  for the modules. The GL files go to `:render:gl` and `PreBlur` with `IconStyler` (now in
  `data/`) and `WallpaperLoader` to an `imaging/` package with one memory budget and one
  `trim(level)`.
* **Asset provenance** remains the one release blocker in this area: the console-named sounds in
  `assets/sounds/` and the Default pack built from them (Roadmap §4.2).

## 9. Action plan

| # | Change | Why | Effort | Depends on | State |
| --- | --- | --- | --- | --- | --- |
| 1 | Import-based layering test; replace inline fully qualified names | Locks the target graph in before files move | 1 day | — | ✅ `fa3cb79` |
| 2 | Cut the nine edges in §4 (`UserLook` and `BootSequence` splits, ids and constants to `model/`, `MusicUnderSnaps` to `data/`) | Prerequisite for every module | 1–2 days | 1 | ✅ `fa3cb79` |
| 3 | `build-logic/` convention plugins, configuration cache, parallel builds | Cheap modules from here on | ½ day | — | ✅ §10.1 |
| 4 | Extract `:core:model` and `:theme:engine` (Kotlin/JVM), with their tests as plain JUnit | Stable engine behind a boundary; faster theme tests; ready for 2b.9 | 3–4 days | 2, 3 | ✅ §10.2 |
| 5 | Look inputs into `ThemeController` as a `StateFlow<ResolvedLook>` | The cascade testable end to end without Compose | 1 day | 4 | ✅ §10.3 |
| 6 | Finish the ViewModel split (search, editors, folder pickers, library jobs, boot) | Stops every feature landing in one class; precondition for feature modules | 1–1.5 weeks | — | Next |
| 7 | `XmbEvent` sink, controller by controller | Smaller signatures; new panels don't touch `MainActivity` | 2 days | 6 | ❌ |
| 8 | `CompositionLocal` holders in place of the six singletons | Previews, tests and a second composition stop sharing state | 2 days | — | ❌ |
| 9 | Per-region state slices (item column, overlays), checked with `palette-perf.sh` | Flat recomposition as `XmbUiState` grows | 2–3 days | 6 | ❌ |
| 10 | `:core:data` and `:render:gl` | KSP isolation, migration tests alone; shader work out of Compose | 3 days each | 4 | ❌ (`xross.android.library` is ready for them) |
| 11 | Phase 3 features as `:feature:*` modules from the start; `:feature:ra` as the first extraction | New code born modular | With Phase 3 | 6, 10 | ❌ |
| 12 | `ui/` subpackages, `imaging/` with one cache budget, mirrored test packages | Findability; predictable memory on 3–4 GB handhelds | 2 days | Alongside 4–10 | ◐ the engine's tests mirror its packages |
| 13 | Decide KMP for `:theme:engine` (or a generated JSON Schema) | Only needed if the Web Studio runs the engine in the browser | 1 week if KMP | When 2b.9 starts | ❌ |

**Suggested slot:** steps 1–5 as a "2b.10-prep" or "Phase 3-prep" slice (about 1.5 weeks)
before any Phase 3 feature branch, then 6–9 interleaved one controller at a time with the
hardware pass, which doesn't touch the same code. Steps 10–13 follow the work that needs them.

## 10. As built: Phase 3-prep, steps 1–5

On `refactor/phase3-prep`, 2026-11-15. Steps 1 and 2 are `fa3cb79` (the layering test, the nine
edges, 421 inline qualified names replaced); this section covers steps 3–5 and where they differ
from the proposal above.

### 10.1 Build logic (step 3)

* `build-logic/` is an included build with two convention plugins: `xross.jvm.library` (Kotlin/JVM,
  Java 17 bytecode, JUnit) and `xross.android.library` (`com.android.library`, `:app`'s SDK
  levels, Java 17, AGP's built-in Kotlin). They compile against AGP and KGP `compileOnly`, so the
  root build's own plugin versions (the `apply false` aliases) are the only ones on the classpath.
  `xross.android.library` has no user yet; it is there for `:core:data` and `:render:gl`.
* `gradle.properties` turns on `org.gradle.parallel` and `org.gradle.configuration-cache`. Every
  task in `check`, `assembleDebug` and `assembleProfiling` is compatible, and a repeated build
  reuses the cached configuration.

### 10.2 `:core:model` and `:theme:engine` (step 4)

| Module | Holds | Size |
| --- | --- | --- |
| `:core:model` (`core/model`) | `model/`: `GlyphKey`, `GlyphSet`, `CategoryIds`, `FolderNames`, `ArtKind`, `Platform` | 6 files, 364 lines |
| `:theme:engine` (`theme/engine`) | `theme/`, `theme/pack/`, `theme/shader/`: everything in §5's box, **plus the pack loader** | 32 files, 6,765 lines |
| `:app` | the rest, and the engine's Android adapters in `theme/` | 165 files, ~33,800 lines |

Both modules keep their package names, so the move needed no import changes outside the files that
were split. Gradle now enforces what `ArchitectureTest` enforced by reading imports; the test
still guards the packages inside `:app`, and checks that each module holds only its own packages
and that the engine imports nothing from Android or Compose UI apart from `@Immutable`.

**Where it differs from §5:**

1. **`ThemePackLoader` is in the engine, not in `:theme:android`.** Its 800 lines are almost all
   pack resolution (manifest, imports, cascade inputs, the files a pack reads), which a desktop
   preview or the Web Studio would need as much as the cascade itself. What was Android in it
   (`AssetManager`, `BitmapFactory`, `Typeface`, `SvgIcon`) is behind a seven-method
   `PackPlatform` interface. `:app` implements it in `AndroidPackPlatform`, and a top-level
   `ThemePackLoader(assets, themesDir)` factory keeps every existing call site unchanged.
2. **Icons and fonts are opaque handles.** `ThemeIcon` and `ThemeFont` are plain interfaces in the
   engine; `:app`'s `VectorIcon`, `BitmapIcon` and `TypefaceFont` implement them, and
   `ThemeFont?.typeface` reads the Android typeface. A `when` over an icon now needs an `else`.
3. **`:theme:android` is not a module yet.** What is left is seven files in `:app`'s `theme/`
   (`AndroidPackPlatform`, `SvgIcon`, `ThemeWatcher`, `ArtSwatches`, `RememberThemePalette`, and
   two small adapters: `ComposeColors` for `ThemePalette.topColor`/`bottomColor`/`glowColor`,
   `MotionSpecs` for `Motion.nav()`/`tween()`). A module for them pays off only when a second
   Android module needs them.
4. **`theme/shader/` went into the engine.** It is the shader *sources* and their bookkeeping
   (presets, the library, the canary, the frame-budget watchdog), all pure, and `LoadedTheme`
   refers to it. The GL renderer stays in `:app`'s `ui/` until `:render:gl`.
5. **`@Immutable` comes from `androidx.compose.runtime:runtime-annotation`** (the annotation-only
   artifact, through the Compose BOM), the first option in §5.
6. **Three `internal` declarations became public** because `:app` uses them: `LruCache` (the
   backdrop caches), `withOpacity` (surfaces) and `ThemeArchive.idFor` (import). The clock helpers
   (`minuteOfDay`, `millisUntilNextMinute`, `millisUntilNextDay`) moved from
   `RememberThemePalette.kt` to the engine's `Clock.kt`.

**Tests.** Eight suites moved to `theme/engine/src/test` as plain JUnit, in the engine's own
packages: `ContextRulesTest`, `LookExporterTest`, `SoundRolesTest`, `ThemeArchiveTest`,
`ThemeArchiveWriteTest`, `ThemeMusicPackTest`, `ThemePackLoaderTest` (all Robolectric before) and
`SeasonalPaletteTest`. Their 81 tests run in about a second. They load the real Default pack from
`app/src/main/assets` through `FilePackPlatform`, a file-backed `PackPlatform` in the engine's
`testFixtures` (with `StressPack` and `TestAssets`), which `:app`'s tests share
(`testFixtures(project(":theme:engine"))`). The platform's fake decoders fail the way real ones do
(an SVG without `<svg`, a PNG or WebP without its signature, an empty font). Tests that check real
Android decoding (`ThemePackTest`, `ComponentsTest`) stay in `:app` on Robolectric. `:app` now has
83 test files, 57 on Robolectric (90 and 64 before).

### 10.3 The look inputs in `ThemeController` (step 5)

`MainActivity.setContent` no longer assembles the cascade. `LookFlow` (`state/`, pure flows)
builds the `LookInputs` from the active pack, the settings, the menu's focus and the battery:

* `settle` holds a new system inside the same category back 250 ms and lets a new category through
  at once (it replaces the `rememberSettledSystem` composable);
* `ruleBattery` and `ruleMinute` read the battery and a once-a-minute clock only when the pack has
  rules on them, and pass a value on only when the set of rules that hold changes;
* `inputs` combines them and drops equal moments.

`ThemeController.resolvedLook(focus, battery)` maps that through `ThemeLookResolver` into a
`StateFlow<ResolvedLook>`; `XmbViewModel.look` starts it once, and the Activity collects that one
value. The whole path from settings and focus to the resolved look is tested without Compose
(`LookFlowTest`, 5 tests on virtual time, plain JUnit) and through the real view model
(`ThemeControllerTest`, one test). `MainActivity` went from 691 to 654 lines.

Two behaviour notes. The look now reaches the screen through its own `StateFlow`, so after a
category switch it can trail the category index by a frame; category moves animate over ~200 ms,
so this is not visible. And the battery and clock filters run while the view model lives (the clock
only for packs with time rules, one wake-up a minute), where the composables stopped when the
Activity did.

### 10.4 Verification

943 unit tests (862 in `:app`, 81 in `:theme:engine`), `lintDebug` 0 errors, debug and profiling
builds; on the API 37 emulator `smoke.sh` 12/12 and the theme suite 426/426; `palette-perf.sh`
0.30–0.59% janky on the second run, which matches 2b.8. Not run: the A55 and the other four device
suites. Details: [Phase 3-prep evidence §3.38](../phase-3-prep/STATUS.md#s3.38).
