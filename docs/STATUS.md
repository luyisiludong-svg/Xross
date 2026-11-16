# Project Xross — Implementation Status

The status dashboard: where each phase stands against [`project_scope.md`](../project_scope.md),
as of **2026-11-15** (`main` at `64b6f79`, after Phase 2b.8). The evidence behind it (test runs,
device passes, frame timings, bugs found and fixed) lives next to each phase, in the files listed
under [Where the evidence is](#where-the-evidence-is). Plans, the backlog and the order of work
are in [`Roadmap.md`](../Roadmap.md).

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧊 iced · 🧪 verified only on the emulator / with stubs · 📱 verified on a real device

---

<a id="s1"></a>

## 1. Summary

| Phase | State | Details |
| --- | --- | --- |
| Phase 1 — Alpha | ✅ complete | [Phase 1](phase-1/README.md) |
| Phase 1b — alpha hardening | ◐ features done; real-handheld testing and a Baseline Profile remain | [Phase 1](phase-1/README.md) |
| Phase 2 — Beta: metadata, media hub, portable folder, library organization | ◐ done apart from the iced on-screen controller; scrapers and RetroAchievements live-verified on the Galaxy A55 | [Phase 2](phase-2/README.md) |
| Phase 2b — customization engine (Theme 2.0) | ✅ Theme 2.0 complete: 2b.1–2b.5, 2b.7, 2b.8 in `main`; 2b.6 🧊; 2b.9 (Web Theme Studio) not started; A55 passes open for 2b.7e and 2b.8 | [Phase 2b](phase-2b/README.md) |
| Phase 3-prep — module boundaries | ✅ 🧪 steps 1–5 of the architecture assessment on `refactor/phase3-prep`: `:core:model`, `:theme:engine` (Kotlin/JVM), build-logic, the look resolved in `ThemeController` | [Phase 3-prep](phase-3-prep/STATUS.md) |
| Phase 3 — release candidate | ◐ RetroAchievements linking, User Card, achievement lists and unlock toasts done and live; hot-swap and live resolution change done; dual screen, docking, Portal and FTPS not started | [Phase 2 evidence §3.12–§3.14](phase-2/STATUS.md#s3.12) |
| Phase 4 — v1.0 | ◐ app triage and performance work started | [Performance](status/PERFORMANCE.md) |

**Verification (2026-11-15, Phase 3-prep):** 943 unit tests pass (862 JVM/Robolectric in `:app`,
81 plain JUnit in `:theme:engine`); the theme suite passes 426 of 426 on `refactor/phase3-prep`
([§3.38](phase-3-prep/STATUS.md#s3.38)). **After Phase 2b.8:** 932 JVM/Robolectric unit tests pass (4 opt-in tests that call the
real services skip by default), `lintDebug` 0 errors. On the API 37 emulator the theme suite
passes 425 of 426 (the one fail is 2b.4f's wallpaper toast missed by the screen dump), behind a
12-check quick `smoke.sh` and a `run-all.sh` runner over the five suites
([device tests](status/DEVICE_TESTS.md)). End-to-end launch tests ran against stub builds of
eleven emulators, frame pacing across 12 display configurations
([performance](status/PERFORMANCE.md)), and live scraping and RetroAchievements runs. One real
device, a **Samsung Galaxy A55** (Android 16, 120 Hz), has run every milestone through 2b.7d. No
physical controller, gaming handheld, SD card, Android 9/10 device or real emulator install has
been tested yet.

---

## Where the evidence is

Evidence sections keep their original `STATUS.md` numbers (§3.1–§3.37, §4.1–§4.3), so references in
commits and older notes still resolve here.

| § | What | File |
| --- | --- | --- |
| 3.1 | Unit-test catalogue (2026-11-12) | [`status/UNIT_TESTS.md`](status/UNIT_TESTS.md#s3.1) |
| 3.2, 3.20 | Device smoke test; device tests split into tiers | [`status/DEVICE_TESTS.md`](status/DEVICE_TESTS.md) |
| 3.3, 3.6 | Emulator dispatch; Phase 1b features on a device | [`phase-1/STATUS.md`](phase-1/STATUS.md) |
| 3.4, 3.5, 3.9–3.14 | Fingerprinting, media hub, `Xross/` folder, library organization, device and live-service runs | [`phase-2/STATUS.md`](phase-2/STATUS.md) |
| 3.15 | Phase 2b.1 | [`phase-2b/2b.1/STATUS.md`](phase-2b/2b.1/STATUS.md) |
| 3.16–3.18 | Phase 2b.2, slices 1–3 | [`phase-2b/2b.2/STATUS.md`](phase-2b/2b.2/STATUS.md) |
| 3.19, 3.21 | Phase 2b.3 on the emulator and the Galaxy A55 | [`phase-2b/2b.3/STATUS.md`](phase-2b/2b.3/STATUS.md) |
| 3.22–3.28 | Phase 2b.4a–f and the A55 pass | [`phase-2b/2b.4/STATUS.md`](phase-2b/2b.4/STATUS.md) |
| — | Phase 2b.5 (as-built notes) | [`phase-2b/2b.5/PROMPTS.md`](phase-2b/2b.5/PROMPTS.md) |
| 3.29–3.34 | Phase 2b.7a–e and the A55 pass | [`phase-2b/2b.7/STATUS.md`](phase-2b/2b.7/STATUS.md) |
| 3.35–3.37 | Phase 2b.8a–c | [`phase-2b/2b.8/STATUS.md`](phase-2b/2b.8/STATUS.md) |
| 3.38 | Phase 3-prep: layering, modules, the look in `ThemeController` | [`phase-3-prep/STATUS.md`](phase-3-prep/STATUS.md) |
| 3.39 | `BLACK_HOLE` background, feedback and field passes, on the A55 | [`phase-3-prep/STATUS.md`](phase-3-prep/STATUS.md#s3.39) |
| 4.1–4.3 | Display matrix, animation and stutter, scope targets | [`status/PERFORMANCE.md`](status/PERFORMANCE.md) |

New evidence goes into the phase's own `STATUS.md` (a new phase gets a new folder), numbered on
from §3.39; this dashboard only gets its summary row and index entry updated.

---

<a id="s2"></a>

## 2. Feature status by scope section

### Scope 3.1 — Controller & navigation engine

| Feature | State | Notes |
| --- | --- | --- |
| XMB dual-axis navigation (categories ↔, items ↕, drill-down stacks) | ✅ | Each category keeps its own stack and cursor; breadcrumb shown when drilled in |
| Android `InputDevice` gamepad handling (keys, sticks, hat) | ✅ 🧪 | Joystick motion is always consumed, so the D-pad never double-steps |
| Vendor detection: PlayStation, Xbox, Nintendo, generic | ✅ | Picks the on-screen button glyphs (✕○△ / ABXY / Nintendo order) |
| Confirm/cancel swap (Auto / South / East) | ✅ | Auto uses East on Nintendo pads |
| Button remapping (wizard) | ✅ | 10-step capture (the last is the media controls button, Select by default); saved per controller model (vendor/product ID). The first device to press a button owns the session; keyboards and devices without IDs use a global mapping. Each saved controller can be reset on its own |
| Options (△ / Y) context menu | ✅ | Slide-in panel with submenus; long-press opens it on touch |
| Per-game remapping | ❌ | |
| Mapping saved per generic HID device | ✅ 🧪 | Unit- and view-model-tested; needs two real controllers to verify |
| Analog deadzone (radial) + anti-snapback + hold-to-repeat | ✅ 🧪 | Unit-tested; adb cannot inject real stick motion. Steps: Off (hardware flat zone only) / 15 / 25 / 35 / 45 / 55% |
| Media controls shortcut | ✅ 🧪 | Select, or Guide held 0.5 s, toggles Now Playing; remappable |
| Axial deadzone option | ✅ 🧪 | Off / 10 / 20 / 30% per axis, applied before the radial check (Settings ▸ Controls) |
| Touch parity (one category per horizontal swipe, continuous vertical drag, tap) | ✅ | |
| First D-pad press after a touch is not lost | ✅ | Fixed ViewRootImpl "leave touch mode" swallowing |
| On-screen controller overlay | 🧊 | Iced (Roadmap §4.8) |
| Gamepad virtual keyboard | ✅ | 10-column grid; Options = space, L1 = delete, R1 = results; hardware keyboards type directly |

### Scope 3.2 — Game libraries & storage

| Feature | State | Notes |
| --- | --- | --- |
| Platform categories (22 systems, folder-alias detection) | ✅ | NES … Saturn, Arcade, 3DS, Switch, ScummVM |
| Favorites, Recently Played (timestamp + play count) | ✅ | |
| Total playtime counter | ✅ | Session = launcher paused after a launch → launcher resumed; survives process death; shown on game rows and in the Options menu |
| Playlists / custom tags | ❌ | |
| Extension filtering, `.cue`/`.gdi`/`.m3u` track de-duplication | ✅ | |
| Look inside `.zip`/`.7z`; CRC32/MD5/SHA-1 fingerprints | ✅ | Background job; see [§3.4](phase-2/STATUS.md#s3.4) |
| Information screen (Options ▸ Information) | ✅ 📱 | Platform, file, folder, size, dates, playtime, emulator, DAT match, fingerprint source and hashes |
| No-Intro header-less hashes (iNES, FDS, SNES copier) | ✅ | Stored alongside the full-file hashes |
| CHD identification | ✅ | SHA-1 from the v4/v5 header |
| Per-track hashes for `.cue`/`.gdi` (Redump) | ❌ | Sheets are skipped for now |
| Redump/No-Intro/MAME DAT import and matching | ✅ 📱 | Logiqx XML and clrmamepro text, plain or zipped; SHA-1 > MD5 > CRC32 + size > arcade set name |
| Room library, incremental rescan, unmount-safe deletion | ✅ | 5,000 ROMs: first scan 1.9 s, rescan 0.9–1.1 s (emulator) |
| Title search on the FTS4 index | ✅ | Games ▸ Search: live word-prefix results, launch and Options from results |
| Multi-volume pooling (internal + microSD) with `ROMs`/`Games`/`Emulation` auto-detect | ✅ | |
| USB-OTG volumes | ✅ 🧪 | `StorageManager.getStorageVolumes()` merged with app-dir roots |
| Hot-swap (`MEDIA_MOUNTED`/`UNMOUNTED`/`EJECT`/`REMOVED`/`BAD_REMOVAL`) | ✅ 🧪 | Debounced quiet rescan in front, deferred while a game runs; games on a removed volume are kept and marked "Not connected" |
| Volume naming, drive icons, `autorun.inf` (parse-only) | ❌ | Phase 3 |
| Emulator intent matrix: RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | Verified end-to-end with stub apps ([§3.3](phase-1/STATUS.md#s3.3)); not yet with real emulators |
| Per-platform emulator choice | ✅ | Settings ▸ Emulators |
| Per-game emulator override | ✅ | Options ▸ Emulator; precedence: game override → platform choice → first installed |
| Permission bridge (`content://` + `FLAG_GRANT_READ_URI_PERMISSION`) | ✅ | Every recipe, RetroArch included |
| Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM | ✅ 🧪 | Recipes from ES-DE's Android rules; new 3DS, Switch and ScummVM platforms |
| Sudachi, Vita3K, others | ❌ | Sudachi is no longer in ES-DE's rules; no verified launch contract |

### Scope 3.3 — Metadata & achievements — ✅ done and live-verified

| Feature | State | Notes |
| --- | --- | --- |
| Hasheous (hash identification, no account) | ✅ 📱 | `POST /api/v1/Lookup/ByHash`; arcade sets send their largest ROM chip and the set name picks between games that share it |
| SteamGridDB (capsules, hero banners, logos) | ✅ 📱 | Free per-user API key; autocomplete search by the best known title |
| ScreenScraper (descriptions, box art, logos, screenshots, video snaps) | ✅ | Follows the API page (English guide: `docs/reference/SCREENSCRAPER_API.md`): file hashes plus size, name search for files too large to hash, and the mandatory quota handling. Live with developer credentials ([§3.13](phase-2/STATUS.md#s3.13)), then with a user account too ([§3.14](phase-2/STATUS.md#s3.14)) |
| Throttled batch scraper | ✅ 📱 | Per-service pacing, back-off on 429, daily-quota stop, retry after a day on failure; only while Xross is in front; opt-in and Wi-Fi only by default |
| Asset cache | ✅ 🧪 | `Xross/media/<kind>/<system>/<rom stem>.<ext>` since 2026-11-11 ([§3.10](phase-2/STATUS.md#s3.10)); atomic writes; files accepted only if their bytes are PNG, JPEG, WebP or MP4; art of removed games pruned. The earlier private UUID cache (verified on the Galaxy A55) is moved over once |
| Game Details screen | ✅ 📱 | Background art (or the playing snap), box art, logo, release facts, rating, playtime, scrollable description |
| Manual match and override tool | ✅ 📱 | Options ▸ Metadata: Edit title, Find match (results from every service), Scrape again, Use automatic match |
| Language priority list | ◐ | One region and one language setting |
| RetroAchievements account and profile | ✅ 📱 | Settings ▸ Accounts link dialog, status bar avatar, User Card ([§3.12](phase-2/STATUS.md#s3.12)); since 2b.1 the avatar expands into a mini-HUD ([§3.15](phase-2b/2b.1/STATUS.md#s3.15)). Live on the emulator and the Galaxy A55 ([§3.14](phase-2/STATUS.md#s3.14)) |
| RetroAchievements game achievements | ✅ 🧪 | Options ▸ Achievements and a Details summary: badge grid (locked greyed), titles, descriptions, points, hardcore/softcore and dates, completion bar. ROMs matched by RetroAchievements' hash per system, title only for disc systems or unfingerprinted ROMs. Live with a Web API key ([§3.14](phase-2/STATUS.md#s3.14)) |
| RetroAchievements unlock toasts | ✅ 🧪 | After a game session (and any profile refresh) new unlocks queue as top-right toasts with the trophy sound; drawn only, never take input. Not yet seen after a real session |
| RetroAchievements dashboard and per-game telemetry | ✅ 🧪 | Games ▸ RetroAchievements and the badge above the button hints (Phase 2b.1, [§3.15](phase-2b/2b.1/STATUS.md#s3.15)) |

Google Play and web-search scraping are dropped (see `design/FEASIBILITY.md`). Dusklight/TwilitRealm is
parked until an API is documented.

### Scope 3.4 — Media engine & customization

| Feature | State | Notes |
| --- | --- | --- |
| Photo, Music and Video categories in the XMB | ✅ | From MediaStore (tags, EXIF, folders); live thumbnails and album art |
| Photo viewer: pan/zoom, slideshow, Set as wallpaper | ✅ | Animated zoom up to 5×, pan clamped to the photo's edges, crossfade slideshow (3–20 s), pinch/swipe/tap |
| Music player as a background service | ✅ | Media3 ExoPlayer + MediaSession: notification, headset and lock-screen controls; albums, artists, shuffle, repeat |
| BGM mode: music pauses for games and resumes after | ✅ | Settings ▸ Media; also pauses for videos |
| Video player with controller playback and scrubbing | ✅ | Seek 10 s / 60 s, previous/next, loop toggle, auto-hiding on-screen display |
| Focused-game background | ✅ 🧪 | Hero art (or a screenshot) crossfades in after 250 ms; the video snap fades in over it after 1.2 s with a 300 ms audio ramp, music ducked to 20%; the wave's GL loop stops while either covers the screen; decoder released when focus moves |
| Wave background (GLSL ES 3.0) | ✅ | Glossy sheets plus strands, AA crests, dithering, vsync-snapped time, reduced-res target |
| Wave parameters: speed | ✅ | 0.5×–2× |
| Wave parameters: line count, turbulence, thickness, custom gradients | ✅ | `uLines`, `uTurb`, `uThick` uniforms plus 7 tint presets and a custom gradient; Settings ▸ Theme ▸ Wave settings with sliders over the live wave; `display.wave*` in `settings.json` |
| Color themes | ✅ 📱 | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original: a color per month, crossfaded over the three days either side of a month change |
| Busy indicator | ✅ 📱 | Ring of dots by the hint pill while scanning, fingerprinting, importing DATs or scraping |
| Hide the button-hint pill | ✅ 📱 | Settings ▸ Theme ▸ Button hints |
| Day/night color shift | ✅ | Brightness follows the time of day |
| Battery-level color blending | ✅ 🧪 | `palette.batteryTint` / Settings ▸ Theme ▸ Battery alert tint: amber to red below 20%, a green pulse while charging (2b.3, [§3.19](phase-2b/2b.3/STATUS.md#s3.19)) |
| Coldboot: fade-in + startup sound | ✅ | Once per process start, `01. opening.wav` (was the coldboot cue); can be turned off |
| Custom boot animations | ✅ 🧪 | A pack's `boot.mp4` (or `audio.bootVideo`) or `effects.bootIntro: SHADER` (the shader rising from black, `uTransition` 0 → 1) on a cold start, skippable by any button or touch (2b.5d); launch curtains `BLOOM`, `FADE_BLACK`, `IRIS`, `FLASH` over the game hand-off; both also in Settings ▸ Theme ▸ Intro and transitions |
| UI sound effects + volume, game-start cue | ✅ | SoundPool for short effects, MediaPlayer for long cues |
| Volume mixer: effects, cues, theme music, music, video snaps | ✅ 🧪 | Settings ▸ Sound (2b.5b), each 0–100% in `settings.json`; music pauses under a video snap with sound (or lowers to 20%), resuming only what Xross paused |
| Theme music (pack BGM) | ✅ 🧪 | `bgm.ogg` / `bgm.mp3` or `audio.bgmFile` through variants, overrides and rules (2b.5c); a second ExoPlayer without audio focus, only while no song is loaded, no other app plays media, no game or full-screen video; paused or lowered under snaps, stopped for games |
| Loadable sound packs / theme packs | ✅ | `Xross/themes/<id>/` (theme.json, fonts, icons, sounds); the bundled icons and sounds are the built-in Default pack in `assets/themes/default`. Variants, category and system overrides, and single-file `.xtheme` import ([§3.18](phase-2b/2b.2/STATUS.md#s3.18)) |
| Icons from the design SVGs | ✅ | `tools/icons/svg_icons.py` → 29 SVGs in the Default pack, read by `SvgIcon` at runtime |
| Custom fonts | ✅ | One .ttf/.otf per theme pack, applied to all launcher text |
| UI scale slider (50–150%) | ✅ 🧪 | Settings ▸ Theme ▸ Layout (2b.8a), over the automatic scaling ([§4](status/PERFORMANCE.md#s4)); evidence in [§3.35](phase-2b/2b.8/STATUS.md#s3.35) |
| Aspect-ratio overrides | ❌ | Xross stays immersive (Android's system bars hidden); showing Android's status bar was dropped (2026-11-14). Xross's own status-bar slots are below |
| Status bar components | ✅ 🧪 | `components.statusBarLeft` / `statusBarRight` slots (logo, network, system volume, battery, clock, avatar), battery modes including time to full, clock skeletons; Settings ▸ Theme ▸ Components (2b.7a, [§3.29](phase-2b/2b.7/STATUS.md#s3.29)) |
| Mini player modes, button glyphs, title badges | ✅ 🧪 | `components.miniPlayerMode`, `glyphOverride`, `badgePills`; Settings ▸ Theme ▸ Components (2b.7b, [§3.30](phase-2b/2b.7/STATUS.md#s3.30)) |
| Social stack | ✅ 🧪 | `components.topLeftCluster: SOCIAL_STACK`: followed RetroAchievements users, an unread badge and a FOLLOWING panel; cached for offline (2b.7c, [§3.31](phase-2b/2b.7/STATUS.md#s3.31)) |
| Achievement badge tiers | ✅ 🧪 | `components.telemetryMode`: off, the compact pill, or a rich card with recent badges and the friends who last played the game (2b.7d, [§3.32](phase-2b/2b.7/STATUS.md#s3.32)) |
| Game tiles and avatar style | ✅ 🧪 | 16:9 XMB tiles in Games; `profile.avatarStyle` with Settings ▸ Theme ▸ Profile customization (Phase 2b.1, [§3.15](phase-2b/2b.1/STATUS.md#s3.15)) |

### Scope 3.5 — Device integration & form factors

| Feature | State | Notes |
| --- | --- | --- |
| Any aspect ratio / resolution | ✅ 🧪 | 12 configurations from 640×480 to 4K ([§4](status/PERFORMANCE.md#s4)). Since 2026-11-10 Xross is locked to landscape (`sensorLandscape`); the portrait row in [§4.1](status/PERFORMANCE.md#s4.1) predates the lock |
| Live resolution change without restart | ✅ 🧪 | Verified by switching display size while running (docked-mode prerequisite) |
| Dual-screen (Presentation API, per-screen layouts, swap hotkey) | ❌ | Phase 3 |
| HDMI/DisplayPort dock detection (`DisplayManager`) | ❌ | Phase 3 |
| App triage: games (`CATEGORY_GAME`) to Games ▸ Android Games, others to Apps | ✅ | |
| Hide apps, Hide system apps, Hidden apps screen | ✅ | Options ▸ Hide; Settings ▸ Apps |
| App folders | ✅ | Options ▸ Move to folder (Emulators, Streaming, Tools or a typed name); emulators suggested for Emulators; "Group emulators" row |
| Emulator detection among installed apps | ◐ | Installed check for known packages; not shown as a separate group |
| Can be the HOME launcher; Android TV leanback entry | ✅ | |

### Scope 3.6 — Network daemons — ❌ not started
HTTPS Xross Portal (Ktor CIO), FTPS (Apache FtpServer), auth, uploads, library editing.

The "Scope 3.x" headings follow the sections of [`project_scope.md`](../project_scope.md).

---

<a id="s5"></a>

## 5. Known limitations and risks

1. **Little hardware testing yet.** One phone (Galaxy A55) has run Xross. Physical controllers
   (DualSense, Switch Pro, built-in pads), analog sticks, real emulator installs, SD cards,
   Android 9/10 and low-end GPUs are all untested.
2. **Multi-file disc images over URIs.** A single `content://` URI for a `.cue` doesn't give
   access to its `.bin` tracks. Emulators handle these through their own storage permission
   and the path extra.
3. **`MANAGE_EXTERNAL_STORAGE`** works for sideload and F-Droid distribution but is a
   Google Play review risk.
4. **Bundled sounds and icons** look derived from Sony's PS3/PSP assets. That's fine for
   personal use, but they should move to a user-supplied theme pack before any public release.
5. **The Default pack's cursor sound is the options tick** at a lower gain. The silent
   `snd_error.wav` is gone: errors use `system_ng.wav` ([§3.13](phase-2/STATUS.md#s3.13)).
6. Robolectric tests run on SDK 35 because SDK 36 sandboxes need Java 21.
7. **Playtime is wall-clock time away from the launcher** after a launch, capped at 24 h per
   session. Time in another app reached from the emulator, or with the device asleep, counts.
8. **Search matches word prefixes only** ("tri" finds "Chrono Trigger", "rono" does not), and
   the on-screen keyboard is Latin letters and digits.
9. **The RetroAchievements Web API key is not encrypted at rest.** It is in app-private
   preferences excluded from backups, like the scraper passwords, but a rooted device can read it.
   `API_GetUserSummary`, used only for the rank, is documented as slow.
10. **Foreground particles can double frame time while navigating on the Galaxy A55.** In 7 of 16
   game-navigation passes with particles, render time rose from ~11 to ~16 ms and p50 from 12 to
   19 ms, with no extra missed deadlines; the cause isn't found yet ([§3.28](phase-2b/2b.4/STATUS.md#s3.28)).


---

<a id="s6"></a>

## 6. Remaining work

Tracked in one place: [`Roadmap.md`](../Roadmap.md) §3 (known issues) and §4 (not yet
implemented, by phase, including the Phase 2b customization engine).


---

<a id="s7"></a>

## 7. How to reproduce

```bash
./gradlew :app:testDebugUnitTest                         # unit tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                              # quick device smoke test, 12 checks (clears app data)
tools/device-tests/run-all.sh                            # full device regression: the five suites, 314 checks
./gradlew :app:assembleProfiling                         # optimized build for frame timing
adb shell dumpsys gfxinfo dev.xross.launcher              # UI frame stats
adb logcat -s WaveFrames XrossScan                        # GL pacing + scan timing (debug/profiling)
```
