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

<a id="s3.39"></a>

## 3.39 `BLACK_HOLE` background, feedback and field passes (2026-11-16)

On `refactor/phase3-prep`, `60264f1` and `22e25e1`; not part of the Phase 3-prep plan. What was
built and how to use it: the [background shader guide](../SHADER_GUIDE.md).

* **`BLACK_HOLE` preset:** a Gargantua-style black hole in ASCII glyphs (Schwarzschild light rays,
  a thin Keplerian disk from the a = 0.6 Kerr ISCO, gravitational and Doppler shift, a lensed
  sky). Settings ▸ Theme ▸ Background shader ▸ Black hole.
* **Feedback:** a program that reads `uPrev` gets last frame's picture (two RGBA8 targets taking
  turns, blitted to the screen), with `uFrameDt` and `uFresh`.
* **Field pass:** a program that reads `uField` first draws a quarter-size pass (`uPass` 0) that
  its full-size pass reads.

**Host:** 896 unit tests in `:app` pass, 0 failures; 7 new in `FeedbackTest` (opt-in by use, when
history is dropped, `uFrameDt` clamping, field size per axis, which shaders use each pass).
`PostProcessSettingsTest` follows the Background shader row's new last choice. `validateShaders`:
8 built-in shaders OK.

**Device (API 37 emulator, host GPU):** the preset links and passes the watchdog (p90 3.3–4.9 ms,
`glFinish` sampling). Checked by screenshot: the intro (ring, then disk), the category-switch pulse
and its return to normal brightness (crop mean 58 → 82 → 60), and that the field pass leaves the
picture unchanged (the glow between letters 22.4 before, 22.6 after).

**Galaxy A55 (profiling build, 120 Hz paced to 60, GPU at its 450 MHz floor):** watchdog p90 by
timer query, 24 s runs with the menu kept active, three rounds each.

| Build | `BLACK_HOLE` | Note |
| --- | --- | --- |
| traced per pixel, with feedback | 12.0, 12.1 ms | **retired** (> 10 ms); `WAVE` shown |
| traced per pixel, without feedback (a test pack) | 11.8, 13.6 ms | retired: feedback is not the cost |
| field pass, 4 rays per cell | 5.41, 5.42, 5.43 ms | passes; `STARFIELD` 3.00 ms in the same rounds |
| + 16-tap bloom | 10.1, 10.1, 10.5 ms | retired |
| + 5-tap bilinear bloom | 6.19, 6.14, 7.51 ms | passes |
| as committed (sparks out in the disk, warmer bloom) | 6.23, 6.27, 6.09 ms | passes |

GL-thread pacing with the committed build: p50 16.6–16.7 ms, 0–6 missed frames per 300, the same
range as `STARFIELD` (0–4). GPU busy 55–60%, against 36–37% for `STARFIELD`.

**Not verified:** a long run for heat and battery, idle (20 fps) pacing, portrait, other GPUs, and
the device test suites (nothing outside the background renderer changed).
