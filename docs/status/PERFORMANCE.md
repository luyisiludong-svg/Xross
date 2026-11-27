# Display matrix and performance

Aspect ratios, frame pacing and the scope's non-functional targets, measured across device classes. Per-slice frame timings are in each phase's evidence file.

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

All numbers are from the Android emulator on an Apple M5 with the host GPU, using an
R8-optimized, AOT-compiled build (`profiling` build type). Treat them as relative, not as a
substitute for real low-end hardware.

<a id="s4"></a>

<a id="s4.1"></a>

## 4.1 Aspect ratios and resolutions — all pass

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
pack and a stress pack; layout results and the day's frame-timing noise are in [§3.18](../phase-2b/2b.2/STATUS.md#s3.18).

Layout fixes from this pass:
- On 4:3, 1:1 and 3:2 the left neighbour category was half off-screen. The focus column now
  always leaves room for one whole category.
- Portrait had tiny text and a large gap between the bar and the list. Vertical spacing now
  follows the short side, and the number of composed rows matches the screen.
- On 1:1, button hints overlapped long titles. The hint row now has a translucent backing.

<a id="s4.2"></a>

## 4.2 Animation speed, lag and stutter

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

<a id="s4.3"></a>

## 4.3 Scope non-functional targets

| Target (scope §5) | Result |
| --- | --- |
| ≥ 60 FPS menu navigation | ✅ 🧪 UI median 16 ms; GL p50 16.7 ms everywhere |
| 120 Hz on high-refresh panels | ✅ Galaxy A55 at 120 Hz: 0.63–1.46% janky, no missed vsync ([§3.11](../phase-2/STATUS.md#s3.11), [§3.13](../phase-2/STATUS.md#s3.13)); game navigation with game-art colors 0.12–0.19%, GPU p50 6 ms of the 8.3 ms budget ([§3.21](../phase-2b/2b.3/STATUS.md#s3.21)); no 120 Hz handheld yet |
| Input-to-screen ≤ 16.6 ms | ❓ Not measured (needs high-speed capture on hardware) |
| Cold boot to interactive < 2.5 s | ✅ First frame ~200 ms on the emulator, 377–1,050 ms on the Galaxy A55 (`am start -W`); the fade-in adds 1.75 s by design |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 42 MB PSS in the background on the emulator; 83–122 MB on the Galaxy A55 (84 MB on 2026-11-12, [§3.21](../phase-2b/2b.3/STATUS.md#s3.21)) |
| Suspend GL on screen-off / app in background | ✅ No GL frames and 0% CPU while an emulator is in front |
| Daemon CPU < 3% idle | n/a (no daemons yet) |

<a id="s4.4"></a>

## 4.4 Live backgrounds on the Galaxy A55

2026-11-27, `feat/live-backgrounds-image-inputs` at `2a8d6d6`, `profiling` build (release-signed
with `-Pxross.profilingReleaseSigned=true` so it installed over the phone's release build). The
sample packs from `tools/shader-preview/examples/` as folder packs. Each run: cold start, then
DPAD up/down every 0.4 s for 14 s; three rounds interleaved. GPU at its 450 MHz floor
throughout. The watchdog's limit is 10 ms p90 (timer query).

| Pack | Watchdog p90 (3 rounds) | GL loop after start | UI janky (deadline) |
| --- | --- | --- | --- |
| Painted Valley (§ 1, `scene.frag` + 2 images) | 1.46–1.65 ms | 60 fps, 0 missed | 0–0.06% |
| Video sample (§ 2, H.264 640×360 + `glass.frag`) | 0.51–0.56 ms | 60 fps, 0–2 missed | 0% |
| Neon Rain scene (§ 3, 8 layers), `sharp` (1920 long side) | 5.51–5.56 ms | 60 fps, 0–1 missed | 0.05–0.11% |
| Neon Rain scene, `soft` (1280) | 2.61–2.66 ms | 60 fps, 0 missed | 0–0.06% |

- Legacy jank (gfxinfo's 16 ms threshold) swung between 1% and 98% in the same configuration,
  the bimodal frame cost known from particles; deadline jank is the figure that matters.
- **Codecs.** H.264, HEVC and VP9 versions of the loop decode on the hardware decoders
  (`c2.exynos.h264/hevc/vp9.decoder`) and copy at 640×360. Every codec misses 4–13 GL frames in
  the first 5 s (cold start, max 70–127 ms), then 0.
- **CPU and GPU** (% of one core, 2 rounds; Standby motion on the phone is `AFTER_30S`):

  | Pack | Navigating: Xross / codec / SurfaceFlinger / GPU busy | Video held (no input > 30 s): codec, before → after the park fix |
  | --- | --- | --- |
  | Default (WAVE) | 88–96% / 0% / 42–44% / 37–38% | 0% (no video) |
  | Video sample | 108–111% / 19–20% / 44–46% / 25% | 4.5–16% → 0% |
  | Neon Rain (sharp) | 109–112% / 18–20% / 40% / 61% | — |

  With Xross stopped the codec service uses ~0.15%. Xross itself and the GPU are idle while the
  video is held (0 ticks, GPU 1%).
- **Poster fallback.** A copy of the scene with a deliberately slow `rain.frag` measured p90
  12.1–13.1 ms. With Performance ▸ Slow shaders on Warn it stays on with a notice; on Retire it
  went to its poster (1280×576 uploaded, no flash), and the GL loop returned to 0 missed frames.
  Its video kept decoding afterwards (~17% of a core in the codec service); since the fix
  below it is released (0%).
- **Fix (same day).** A held player now parks: after 2 s it is released once one of its
  frames has been copied, and the copy stays on screen; the next input prepares it again at
  the saved position. A scene drawn as its poster releases its video. Checked on the A55:
  0 codec ticks in 20 s while parked, playback resumes on input, the parked frame stays.
- **Not measured:** battery over 30 min (needs the phone unplugged; USB charging makes
  batterystats meaningless), tilt by hand, the snap handoff and loop seam, a crash in a shader
  layer, the upload hitch for two 2048² images (the sample's images are small).
