# Project Wave — Implementation Status

Status of the codebase against [`project_scope.md`](../project_scope.md), as of **2026-11-09**. It covers what has been built, how it was verified, measured
performance across device classes, known limitations, and the remaining work split into phases.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

---

## 1. Summary

| Area | State |
| --- | --- |
| Phase 1 (Alpha): XMB navigation, gamepad layer, library, emulator dispatch, wave shader | ✅ complete, with a few small gaps listed in §6 Phase 1b |
| Visual front end from `assets/examples` (icons, themes, glow, status bar, sounds) | ✅ |
| Phase 2 (metadata, media hub, theme packs, on-screen controller and keyboard) | ◐ step 1 done: archive-aware fingerprinting (§2, §3.4) |
| Phase 3 (dual screen, hot-swap, RetroAchievements, Web Portal, FTPS) | ❌ (two prerequisites done; see §6) |
| Phase 4 (onboarding, triage, profiling, plugin API) | ◐ app triage and performance work started |

**Verification:** 53 JVM/Robolectric unit tests, a 31-check on-device smoke test,
end-to-end launch tests against stub builds of five emulators, and frame-pacing measurements
across 12 display configurations. Everything passes. All device testing used the Android
emulator (API 37, host GPU). No physical handheld or controller has been tested yet.

---

## 2. Feature status by scope section

### 3.1 Controller & navigation engine

| Feature | State | Notes |
| --- | --- | --- |
| XMB dual-axis navigation (categories ↔, items ↕, drill-down stacks) | ✅ | Each category keeps its own stack and cursor; breadcrumb shown when drilled in |
| Android `InputDevice` gamepad handling (keys, sticks, hat) | ✅ 🧪 | Joystick motion is always consumed, so the D-pad never double-steps |
| Vendor detection: PlayStation, Xbox, Nintendo, generic | ✅ | Picks the on-screen button glyphs (✕○△ / ABXY / Nintendo order) |
| Confirm/cancel swap (Auto / South / East) | ✅ | Auto uses East on Nintendo pads |
| Global button remapping (wizard) | ✅ | 9-step capture; verified on device |
| Per-game remapping | ❌ | |
| Mapping wizard per generic HID device (saved per controller) | ◐ | The wizard is global, not stored per device |
| Analog deadzone (radial) + anti-snapback + hold-to-repeat | ✅ 🧪 | Unit-tested; adb cannot inject real stick motion |
| Axial deadzone option | ❌ | |
| Touch parity (one category per horizontal swipe, continuous vertical drag, tap) | ✅ | |
| First D-pad press after a touch is not lost | ✅ | Fixed ViewRootImpl "leave touch mode" swallowing |
| On-screen controller overlay | ❌ | Phase 2 |
| Gamepad virtual keyboard | ❌ | Phase 2; needed before search and metadata editing |

### 3.2 Game libraries & storage

| Feature | State | Notes |
| --- | --- | --- |
| Platform categories (19 systems, folder-alias detection) | ✅ | NES … Saturn, Arcade |
| Favorites, Recently Played (timestamp + play count) | ✅ | |
| Total playtime counter | ❌ | Needs session tracking: launch time → return to launcher |
| Playlists / custom tags | ❌ | |
| Extension filtering, `.cue`/`.gdi`/`.m3u` track de-duplication | ✅ | |
| Look inside `.zip`/`.7z`; CRC32/MD5/SHA-1 fingerprints | ✅ | Background job; see §3.4 |
| No-Intro header-less hashes (iNES, FDS, SNES copier) | ✅ | Stored alongside the full-file hashes |
| CHD identification | ✅ | SHA-1 from the v4/v5 header |
| Per-track hashes for `.cue`/`.gdi` (Redump) | ❌ | Sheets are skipped for now |
| Redump/No-Intro DAT import and matching | ❌ | Next Phase 2 step; `findByCrc` lookup is ready |
| Room library, incremental rescan, unmount-safe deletion | ✅ | 5,000 ROMs: first scan 1.9 s, rescan 0.9–1.1 s (emulator) |
| FTS4 title index + search query | ◐ | DAO query exists; no search UI yet (needs the virtual keyboard) |
| Multi-volume pooling (internal + microSD) with `ROMs`/`Games`/`Emulation` auto-detect | ✅ | |
| USB-OTG volumes | ◐ | Only volumes exposed via `getExternalFilesDirs`; needs `StorageManager` |
| Hot-swap receivers (`MEDIA_MOUNTED`/`UNMOUNTED`) | ❌ | Library refreshes on resume; games on a removed card are kept |
| Volume naming, drive icons, `autorun.inf` (parse-only) | ❌ | Phase 3 |
| Emulator intent matrix: RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | Verified end-to-end with stub apps (§3.3); not yet with real emulators |
| Per-platform emulator choice | ✅ | Settings ▸ Emulators |
| Per-game emulator override | ❌ | |
| Permission bridge (`content://` + `FLAG_GRANT_READ_URI_PERMISSION`) | ✅ | Every recipe, RetroArch included |
| More emulators (Azahar, Eden/Sudachi, Flycast, M64Plus FZ, melonDS, ScummVM, Vita3K…) | ❌ | |

### 3.3 Metadata & achievements — ❌ not started
ScreenScraper, SteamGridDB, Hasheous, RetroAchievements, the language priority list, the batch
scraper, the review tool, and the asset cache. Google Play and web-search scraping are dropped
(see `FEASIBILITY.md`). Dusklight/TwilitRealm is parked until an API is documented.

### 3.4 Media engine & customization

| Feature | State | Notes |
| --- | --- | --- |
| Photo viewer, music player (background service), video snaps | ❌ | Phase 2 |
| Wave background (GLSL ES 3.0) | ✅ | Glossy sheets plus strands, AA crests, dithering, vsync-snapped time, reduced-res target |
| Wave parameters: speed | ✅ | 0.5×–2× |
| Wave parameters: line count, turbulence, thickness, custom gradients | ❌ | Shader is ready for uniforms; no UI yet |
| Color themes | ✅ | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight |
| Day/night color shift | ✅ | Brightness follows the time of day |
| Battery-level color blending | ❌ | Battery level is already read for the status bar |
| Coldboot: fade-in + startup sound | ✅ | Once per process start; can be turned off |
| Custom boot animations | ❌ | |
| UI sound effects + volume, game-start cue | ✅ | SoundPool for short effects, MediaPlayer for long cues |
| BGM and video-snap volume sliders | ❌ | Needs the media engine |
| Loadable sound packs / theme packs | ❌ | The sounds and icons are built in for now |
| Icons from the design SVGs | ✅ | `tools/icons/svg_icons.py` → 28 VectorDrawables |
| Custom fonts, UI scale slider (50–150%) | ❌ | Layout already scales automatically (§4) |
| Aspect-ratio overrides, status-bar mode options | ❌ | Currently immersive (system bars hidden) |

### 3.5 Device integration & form factors

| Feature | State | Notes |
| --- | --- | --- |
| Any aspect ratio / resolution | ✅ 🧪 | 12 configurations from 640×480 to 4K, plus portrait (§4) |
| Live resolution change without restart | ✅ 🧪 | Verified by switching display size while running (docked-mode prerequisite) |
| Dual-screen (Presentation API, per-screen layouts, swap hotkey) | ❌ | Phase 3 |
| HDMI/DisplayPort dock detection (`DisplayManager`) | ❌ | Phase 3 |
| App triage: games (`CATEGORY_GAME`) to Games ▸ Android Games, others to Apps | ✅ | |
| Emulator detection among installed apps | ◐ | Installed check for known packages; not shown as a separate group |
| Can be the HOME launcher; Android TV leanback entry | ✅ | |

### 3.6 Network daemons — ❌ not started
HTTPS Wave Portal (Ktor CIO), FTPS (Apache FtpServer), auth, uploads, library editing.

---

## 3. Test results

### 3.1 Unit tests — 53/53 pass
`./gradlew :app:testDebugUnitTest` (JVM + Robolectric on SDK 35)

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

### 3.2 Device smoke test — 31/31 pass
`tools/device-tests/smoke.sh` resets the app, seeds ROMs, and drives the UI with key events.
It checks screen content through the accessibility tree.

| Group | Checks |
| --- | --- |
| Library & navigation | Auto-detect `/sdcard/ROMs`; platforms listed; drill-down; cleaned titles; `.bin` hidden behind `.cue` |
| Favorites | Options hint, toggle, Favorites collection appears |
| Categories | L1/R1 movement, Settings sections (incl. Sound) |
| Settings | Theme cycles, wave toggle, Sound section, volume cycles |
| Remapping | Wizard start, 9-button capture, bindings saved, remapped button works, reset |
| Rescan | New file picked up (+1), deleted file dropped (−1) |
| Touch | Swipe left/right moves exactly one category; vertical drag scrolls |
| Fingerprints | Library settings report games identified by content after a rescan |
| Launch | RetroArch stub receives the right core; ROM readable via granted URI; Recently Played updated |

### 3.3 Emulator dispatch — 5/5 recipes verified with stubs
`tools/emulator-stub` builds a test-only APK per emulator, each installed under the real package
and activity name. Each stub logs the intent it receives and reads the ROM through the URI.

| Emulator | Component | ROM handoff | URI read |
| --- | --- | --- | --- |
| RetroArch | `com.retroarch.aarch64/…RetroActivityFuture` | `ROM`, `LIBRETRO=/data/data/<pkg>/cores/snes9x_libretro_android.so`, `CONFIGFILE` | ✅ |
| DuckStation | `…duckstation.EmulationActivity` | `bootPath` | ✅ |
| NetherSX2 | `xyz.aethersx2.android.EmulationActivity` | `bootPath` | ✅ |
| Dolphin | `…ui.main.MainActivity` | `AutoStartFile` | ✅ |
| PPSSPP | `…PpssppActivity` | `ACTION_VIEW` + data | ✅ |

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

The queue (`data/FingerprintQueue.kt`) runs on one background-priority thread **only while Wave
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

---

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
| Wave | Time advances in whole vsync periods | GL thread holds 60 fps in every configuration |

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
| 120 Hz on high-refresh panels | ◐ The renderer reads the display refresh rate; untested on a 120 Hz panel |
| Input-to-screen ≤ 16.6 ms | ❓ Not measured (needs high-speed capture on hardware) |
| Cold boot to interactive < 2.5 s | ✅ 🧪 First frame ~200 ms (`am start -W`); the fade-in adds 1.75 s by design |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 🧪 42 MB PSS in background (88 MB in foreground) |
| Suspend GL on screen-off / app in background | ✅ No GL frames and 0% CPU while an emulator is in front |
| Daemon CPU < 3% idle | n/a (no daemons yet) |

---

## 5. Known limitations and risks

1. **No hardware testing yet.** Physical controllers (DualSense, Switch Pro, built-in pads),
   analog sticks, real emulator installs, audio output and low-end GPUs are all untested.
2. **Multi-file disc images over URIs.** A single `content://` URI for a `.cue` doesn't give
   access to its `.bin` tracks. Emulators handle these through their own storage permission
   and the path extra.
3. **`MANAGE_EXTERNAL_STORAGE`** works for sideload and F-Droid distribution but is a
   Google Play review risk.
4. **Bundled sounds and icons** look derived from Sony's PS3/PSP assets. That's fine for
   personal use, but they should move to a user-supplied theme pack before any public release.
5. **`snd_error.wav` is silent** (all zero samples). Errors use `snd_system_ng`. There is no
   dedicated cursor sound; moves reuse `snd_option`.
6. Robolectric tests run on SDK 35 because SDK 36 sandboxes need Java 21.

---

## 6. Remaining work by phase

### Phase 1b — Alpha hardening (next)
- [ ] Test on 2–3 real handhelds (e.g. Retroid Pocket, AYN Odin, a 4:3 Anbernic): controllers,
      sticks, frame pacing, audio, and real RetroArch/DuckStation/Dolphin/PPSSPP/NetherSX2 launches
- [ ] Hot-swap: `ACTION_MEDIA_MOUNTED`/`UNMOUNTED` receivers → automatic rescan, keep UI state
- [ ] USB-OTG volumes via `StorageManager.getStorageVolumes()`
- [ ] Playtime tracking (session start on launch, end on return) and display
- [ ] Per-game emulator override (Options menu on a game)
- [ ] Options (△/Y) context menu instead of a single action
- [ ] Per-controller saved mappings; axial deadzone option
- [ ] Baseline Profile for faster startup and less early-scroll JIT jank
- [ ] Add maintained emulators: Azahar (3DS), Eden/Sudachi (Switch), Flycast, M64Plus FZ,
      melonDS, ScummVM, verified with the stub harness

### Phase 2 — Metadata, customization & media (Beta)
- [x] Archive-aware fingerprinting (zip/7z/CHD headers) and CRC32/MD5/SHA-1 hashing in a
      background job that pauses while a game runs
- [ ] DAT import (No-Intro / Redump Logiqx XML) → exact titles, regions and revisions offline
- [ ] Per-track hashing for `.cue`/`.gdi` (Redump matches tracks, not sheets)
- [ ] Scrapers: ScreenScraper (dev credentials), SteamGridDB (user key), Hasheous; language
      priority list; throttled batch scraper; disk asset cache keyed by UUID
- [ ] Game detail view (box art, description, release info) and manual match/override tool
- [ ] Gamepad virtual keyboard, then search UI on the existing FTS4 index
- [ ] Media hub: photo viewer, music player as a background service with BGM mode, video snaps
- [ ] Theme packs: loadable icons, sounds (cursor/confirm/back/error/boot), fonts, palettes and
      wave parameters (line count, turbulence, thickness); move bundled assets into a pack
- [ ] UI scale slider, custom fonts, status-bar mode options
- [ ] On-screen controller overlay for screen-only devices

### Phase 3 — Hardware integration & network services (RC)
- [ ] Dual-screen: Presentation API, secondary-screen layouts (details, box art, achievements,
      music, telemetry), screen-swap hotkey, per-screen orientation
- [ ] Dock detection via `DisplayManager` (resolution switching already works live)
- [ ] `autorun.inf` manifest parsing (parse-only), volume labels and drive icons
- [ ] RetroAchievements: login with encrypted token, progress in game details, unlock toasts
- [ ] HTTPS Wave Portal (Ktor CIO): PIN auth, ROM/BIOS upload, library editing, diagnostics
- [ ] FTPS server (Apache FtpServer) with TLS; idle CPU < 3%

### Phase 4 — Production launch & ecosystem (v1.0)
- [ ] First-run onboarding: storage access, folder discovery, emulator detection, first scrape
- [ ] App triage UI: Emulators vs Games vs Utilities groups
- [ ] Performance and thermal tuning on target hardware; input-latency measurement
- [ ] Public theme/plugin format and documentation
- [ ] Release signing, crash reporting (opt-in), Play/F-Droid compliance review
      (`MANAGE_EXTERNAL_STORAGE`, bundled assets)

---

## 7. How to reproduce

```bash
./gradlew :app:testDebugUnitTest                         # unit tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                              # device smoke test (clears app data)
./gradlew :app:assembleProfiling                         # optimized build for frame timing
adb shell dumpsys gfxinfo dev.wave.launcher              # UI frame stats
adb logcat -s WaveFrames WaveScan                        # GL pacing + scan timing (debug/profiling)
```
