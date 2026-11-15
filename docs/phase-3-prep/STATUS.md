# Phase 3-prep — evidence

Module boundaries before any Phase 3 feature branch: steps 1–5 of the
[architecture assessment](../design/ARCHITECTURE_ASSESSMENT.md#9-action-plan). What was built and
why: [§10 there](../design/ARCHITECTURE_ASSESSMENT.md#10-as-built-phase-3-prep-steps-15).

Section numbers continue the original `docs/STATUS.md` numbering; the
[status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.38"></a>

## 3.38 Phase 3-prep, steps 1–5: layering, `:core:model`, `:theme:engine`, the look in `ThemeController` (2026-11-15)

On `refactor/phase3-prep`. Steps 1–2 are `fa3cb79`; steps 3–5 follow it.

**Host:** 943 unit tests pass, 0 failures (4 opt-in live tests skip): 862 in `:app`
(`testDebugUnitTest`, JVM + Robolectric) and 81 in `:theme:engine` (plain JUnit, about a second).
New: `LookFlowTest` (5: the settled system, battery and clock passed on only at rule boundaries,
settings, focus, battery and clock adding up to the resolved look, a pack without rules reading
neither), one `ThemeControllerTest` case (the view model's look following the quick profile and
surface style and matching the status bar's components), and `ArchitectureTest` over all three
modules (each module holds only its packages; the engine has no Android or Compose UI import but
`@Immutable`). Eight suites moved to the engine unchanged apart from their loader, which now reads
the Default pack through `FilePackPlatform`. `./gradlew check assembleDebug assembleProfiling`
passes: `lintDebug` 0 errors, `validateShaders` 7 shaders OK. The configuration cache is stored
without problems and reused on the next build.

**Device (API 37 emulator, host GPU):** `smoke.sh` 12/12 on two runs. The first run after the
install failed "MainActivity is in front" once, because the check ran before the first activity
resumed; the activity was in front a moment later and no crash was logged. **Theme suite 426/426**
(`main` had 425/426: 2b.4f's wallpaper toast passed this time).

**Frame timing (profiling build):** `palette-perf.sh 3`: janky 0.30–0.90% on five of six passes;
the first pass after the install was 2.12% (p99 97 ms). A second run, `palette-perf.sh 2`: 0.30–0.59%
on all four passes, the same as 2b.8 (0.44–0.75%). Resolving the look in `ThemeController` costs
nothing measurable.

**Not verified:** the Galaxy A55 (no real device was connected), and the other device suites
(library, media, input, integration). Those suites don't touch the theme code, but they do run
over the moved engine.
