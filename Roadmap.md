# Xross Roadmap

What has been built, what is left, and in what order, measured against the product scope in
[`project_scope.md`](project_scope.md). Updated **2026-11-11**.

Related documents:

- [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md): how close Xross is to the real XMB, checked
  against the PS3 and PSP manuals in `assets/docs`
- [`docs/STATUS.md`](docs/STATUS.md): feature-by-feature test results and performance data
- [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md): why some scope items were changed or dropped
- [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md): how to test on a real handheld

**Legend:** ✅ done · ◐ partly done · ⬜ not started · 🧪 verified only on the Android emulator or
with stub emulators · 📱 verified on a real device

---

## 1. Where we are

| Milestone | Scope | State |
| --- | --- | --- |
| **Phase 1 — Alpha** | XMB navigation, gamepad layer, ROM library, emulator launching, wave background | ✅ Complete |
| **Phase 1b — Alpha hardening** | Hot-swap, USB-OTG, playtime, Options menu, per-game emulator, more emulators | ◐ Features done, including per-controller mappings and the axial deadzone; real-handheld testing and a Baseline Profile remain |
| **Phase 2 — Beta** | Metadata, media hub, theme packs, on-screen controller and keyboard | ◐ Fingerprinting, media hub, gamepad search, DAT verification, scrapers, Details screen and video snaps done; theme packs not started |
| **Phase 3 — Release candidate** | Dual screen, docking, RetroAchievements, Xross Portal, FTPS | ⬜ Not started (hot-swap and live resolution change, two prerequisites, are done) |
| **Phase 4 — v1.0** | Onboarding, app triage, tuning, theme/plugin format, release | ◐ App triage and performance work started |
| **XMB fidelity** | Look and feel exactly like the XMB, plus Xross's extras | ◐ Home menu layout, motion and navigation match; per-game art, typeface, sounds and several system behaviors don't yet (§4.5) |

**Codebase at a glance:** Kotlin + Jetpack Compose, one `:app` module, Room database at schema v5,
22 platforms, 11 emulator launch recipes, 8 color themes (one follows the month), 193 unit tests, a 57-check on-device
smoke test, and test-only stand-ins for all 11 emulators.

**Testing so far:** Almost all device testing used the Android emulator. One real device has
run Xross: a Samsung Galaxy A55 (Android 16, 120 Hz) with no controller, emulators or ROMs. No
gaming handheld, physical controller or real emulator install has been tested yet.

---

## 2. Implemented

### 2.1 Navigation and input

| Feature | State | Notes |
| --- | --- | --- |
| XMB layout: categories left/right, items up/down, drill-down | ✅ | Each category keeps its own drill-down stack and cursor; breadcrumb while drilled in |
| Categories | ✅ | Settings · Photo · Music · Video · Games · Apps |
| Spring-based motion | ✅ | Critically damped (~200 ms); held D-pad glides instead of restarting each step |
| Gamepad input (`InputDevice` keys, sticks, hat) | ✅ 🧪 | Stick motion is consumed so the D-pad never double-steps |
| Controller family detection | ✅ | PlayStation, Xbox, Nintendo, generic; picks matching button glyphs |
| Confirm/cancel swap | ✅ | Auto / South / East; Auto uses East on Nintendo pads |
| Button-remap wizard | ✅ | 9-step capture, saved per controller model (vendor/product ID); keyboards and unidentified devices use a global mapping |
| Radial and axial deadzones, anti-snapback, hold-to-repeat | ✅ 🧪 | Unit-tested; real stick motion can't be injected with adb |
| Options (△ / Y) context menu with submenus | ✅ | Long-press opens it on touch |
| Touch parity | ✅ | One category per horizontal swipe, continuous vertical drag, tap, long-press |
| First D-pad press after a touch isn't lost | ✅ | |
| Gamepad on-screen keyboard and search | ✅ | 10-column grid over the FTS4 title index; hardware keyboards type directly |

### 2.2 Library and storage

| Feature | State | Notes |
| --- | --- | --- |
| ROM scanning into Room with incremental rescan | ✅ | 5,000 ROMs: first scan 1.9 s, rescan ~1 s (emulator) |
| 22 platforms with folder-name aliases and extension filters | ✅ | NES through Saturn, Arcade, 3DS, Switch, ScummVM |
| `.cue` / `.gdi` / `.m3u` track de-duplication | ✅ | |
| Auto-detect `ROMs`, `Games`, `Emulation`, `Retro` folders on every drive | ✅ | Plus custom folders through the system picker |
| Multiple volumes: internal, microSD, USB-OTG | ✅ 🧪 | |
| Hot-swap (mount, unmount, eject, removal) | ✅ 🧪 | Debounced quiet rescan; deferred while a game runs; games on a removed drive stay listed as "Not connected" |
| Favorites, Recently Played | ✅ | |
| Playtime per game | ✅ | Survives process death; shown on rows and in the Options menu |
| Information screen (Options ▸ Information) | ✅ 🧪 | Platform, file, folder, size, dates, playtime, emulator, fingerprint source and CRC32/MD5/SHA-1 |
| Content fingerprints (CRC32, MD5, SHA-1) | ✅ | Inside `.zip` / `.7z`; CHD SHA-1 from the header; No-Intro header-less hashes; background job that stops while a game runs |
| Title search (FTS4) | ✅ | Word-prefix matching |

### 2.3 Launching games

| Feature | State | Notes |
| --- | --- | --- |
| RetroArch with a core per platform | ✅ 🧪 | |
| DuckStation, NetherSX2, Dolphin, PPSSPP | ✅ 🧪 | |
| Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM | ✅ 🧪 | Launch recipes from ES-DE's published Android rules |
| Emulator per platform (Settings ▸ Emulators) | ✅ | |
| Emulator per game (Options ▸ Emulator) | ✅ | Game override → platform choice → first installed |
| ROM access for emulators | ✅ | File path extra plus a `content://` URI with a read grant |
| Android games and apps | ✅ | Games (`CATEGORY_GAME`) go to Games ▸ Android Games; everything else to Apps |

All 11 recipes are verified only against the test stand-ins, not real emulator installs.

### 2.4 Media hub

| Feature | State | Notes |
| --- | --- | --- |
| Photo, Music and Video from MediaStore | ✅ | Folders, tags, EXIF, thumbnails, album art |
| Photo viewer | ✅ | Zoom up to 5×, pan, slideshow (3–20 s), set as wallpaper |
| Music player as a background service | ✅ | Media3 + MediaSession: notification, headset and lock-screen controls; albums, artists, shuffle, repeat |
| Background music pauses for games and videos | ✅ | Resumes when Xross is back in front |
| Video player | ✅ | Seek, previous/next, loop, auto-hiding controls |
| Clear empty state without storage access | ✅ 🧪 | Photo, Music and Video say "Storage access needed"; pressing the row opens the grant screen |

### 2.5 Look and sound

| Feature | State | Notes |
| --- | --- | --- |
| Wave background (GLSL ES 3.0) | ✅ | Speed setting (0.5×–2×); stops whenever Xross isn't in front |
| Day/night brightness shift | ✅ | |
| Color themes | ✅ | Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, and Original (a color per month, blended over the days around each month boundary) |
| Icons from the design SVGs | ✅ | 28 VectorDrawables generated by `tools/icons/svg_icons.py` |
| Focus glow, halos, drop shadows | ✅ | Glow no longer gets clipped into a box while categories switch (fixed 2026-11-10) |
| Cold-boot fade-in and startup sound | ✅ | Can be turned off |
| Busy indicator while scanning or fingerprinting | ✅ | Ring of dots in the bottom corner; appears after 0.4 s, steps 10×/s |
| Hide the button-hint pill | ✅ | Settings ▸ Theme ▸ Button hints |
| UI sounds, volume, game-start cue | ✅ | |

### 2.6 Devices and displays

| Feature | State | Notes |
| --- | --- | --- |
| Any aspect ratio and resolution | ✅ 🧪 | 12 configurations from 640×480 to 4K |
| Live resolution change without restart | ✅ 🧪 | Prerequisite for docked mode |
| Locked to landscape | ✅ 📱 | `sensorLandscape`: flips 180° with the device. Android 16 ignores the lock on screens 600 dp and wider |
| HOME launcher and Android TV leanback entry | ✅ | |

### 2.7 Quality and performance

| Item | State | Notes |
| --- | --- | --- |
| 60 fps menus | ✅ 🧪 | Across all 12 display configurations |
| 120 Hz | ◐ 📱 | Xross renders at ~120 fps on the Galaxy A55; not yet measured on a 120 Hz handheld |
| Smooth list scrolling | ✅ 📱 | Galaxy A55, optimized build: 0–0.6% janky frames in a long Apps list |
| Fewer recompositions per cursor move | ✅ | Only rows whose focus changes are rebuilt (~75% less work per press) |
| Cold boot to interactive | ✅ 🧪 | First frame ~200 ms; the fade-in adds 1.75 s by design |
| Memory with an emulator in front | ✅ 🧪 | 42 MB PSS (target ≤ 150 MB) |
| Test tooling | ✅ | Unit tests, adb smoke test, emulator stand-ins, `profiling` build type |

---

## 3. Known issues to fix

These are bugs or gaps in features that are already built, separate from the new work in §4.

| Issue | Impact | Fix |
| --- | --- | --- |
| No real-hardware validation of controllers, sticks, emulators or audio | Biggest risk to Beta | Phase 1b hardware pass (§4.1) |
| Six newer emulator recipes may not accept a FileProvider URI | Launches could fail on real installs | Test each against a real install; fall back to the path extra |
| Multi-file disc images (`.cue` + `.bin`) | One `content://` URI doesn't cover the `.bin` tracks | Rely on the emulator's own storage access and the path extra |
| `snd_error.wav` is silent, and there's no dedicated cursor sound | Errors and moves reuse other sounds | Replace with the theme-pack sound set (§4.2) |
| Search matches word prefixes only, Latin letters and digits only | "rono" doesn't find "Chrono Trigger" | Add substring or trigram matching if users need it |
| Playtime counts all wall-clock time away from Xross after a launch | Sleep and other apps count (capped at 24 h) | Acceptable for now; revisit with usage stats |
| Search results at 80% opacity clip their text shadow slightly | Barely visible | Switch to `fadeAlpha` when that screen is next touched |
| `STATUS.md` still describes portrait as a supported layout | Docs are out of date since the landscape lock | Update STATUS §4 |

---

## 4. Not yet implemented

### 4.1 Phase 1b — finish alpha hardening (next)

| Item | Notes |
| --- | --- |
| Test on 2–3 real handhelds | For example a Retroid Pocket, an AYN Odin and a 4:3 Anbernic: controllers, sticks, frame pacing, audio, and real RetroArch, DuckStation, Dolphin, PPSSPP and NetherSX2 launches. Follow `docs/HARDWARE_TESTING.md` |
| Confirm the six newer recipes on real installs | Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM |
| Baseline Profile | Faster startup and less jank before the JIT warms up |

### 4.2 Phase 2 — Beta

**Metadata (the largest remaining piece)**

| Item | Depends on | Notes |
| --- | --- | --- |
| DAT import (No-Intro and Redump Logiqx XML) ✅ | Fingerprints ✅ | Logiqx XML and clrmamepro text, also inside .zip; SHA-1 > MD5 > CRC32+size > set name; titles, regions, languages, revision, serial |
| Per-track hashing for `.cue` / `.gdi` | | Redump matches tracks, not sheets |
| ScreenScraper scraper ✅ 🧪 | Developer credentials | Built; needs `screenscraper.devId`/`devPassword` in local.properties. Tested only against canned responses |
| SteamGridDB scraper ✅ 🧪 | Per-user API key | Hero banners, 600×900 capsules, logos. Canned responses only |
| Hasheous matching ✅ 🧪 | | Hash lookup, no account. Canned responses only |
| Language priority list | Scrapers | For example `es-ES, es, en-US, en` |
| Throttled background batch scraper ✅ | Scrapers | Per-service rate limits, quota handling, opt-in, Wi-Fi only by default; stops while a game runs |
| Disk asset cache keyed by UUID ✅ | Scrapers | Atomic writes, content sniffing, pruning; outside backups |
| Game detail view ✅ | Scrapers | Options ▸ Details or → on a game |
| Manual match and override tool ✅ | Scrapers | Options ▸ Metadata: edit title, find match, scrape again, reset |
| Video snap previews on focused games ✅ | Scraper snaps | Full-bleed behind the menu after 1.2 s; music ducked |

**Customization**

| Item | Notes |
| --- | --- |
| Theme pack format and loader | Icons, sounds (cursor, confirm, back, error, boot), fonts, color palettes |
| Move bundled assets into a default pack | Required before any public release (bundled sounds and icons resemble PS3/PSP assets) |
| Wave parameters: line count, turbulence, thickness, custom gradients | The shader is ready for the uniforms; needs settings UI |
| Battery-level color blending | Battery level is already read for the status bar |
| Custom boot animations | |
| Separate volume sliders for UI sounds, music and video snaps | Music currently uses the system media volume |
| UI scale slider (50–150%) | Layout already scales automatically |
| Custom fonts | Part of theme packs |
| Status-bar mode options (transparent, dark, hidden) | Immersive (hidden) today |
| Aspect-ratio overrides (letterbox / pillarbox) | |

**Input**

| Item | Notes |
| --- | --- |
| On-screen controller overlay | For touch-only devices: opacity, haptics, size, position snapping |
| Per-game button remapping | |
| Playlists and custom tags | For example "Multiplayer" or "Completed" |

### 4.3 Phase 3 — Release candidate

| Item | Notes |
| --- | --- |
| Dual screen | Presentation API; second-screen layouts for details, box art, achievements, music and telemetry; screen-swap hotkey; per-screen orientation (must work with the landscape lock) |
| Dock detection | `DisplayManager`; live resolution switching already works |
| `autorun.inf` manifests | **Parse only**: label, icon, scan paths. Never execute anything from removable media |
| Volume labels and drive icons | |
| RetroAchievements | Login with an encrypted token, progress in game details, unlock toasts in the launcher. An overlay over a running emulator is optional, since RetroArch shows its own |
| HTTPS Xross Portal | Ktor CIO engine: PIN login, ROM and BIOS upload, library editing, diagnostics. Self-signed TLS means browser warnings |
| FTPS server | Apache FtpServer with TLS; under 3% CPU when idle; only runs when enabled |

### 4.4 Phase 4 — v1.0

| Item | Notes |
| --- | --- |
| First-run onboarding | Storage access, folder discovery, emulator detection, first scrape |
| App triage UI | Separate Emulators, Games and Utilities groups (detection of known emulators exists) |
| Performance and thermal tuning on target hardware | Snapdragon 665 / Unisoc T618 class |
| Input-latency measurement | Target ≤ 16.6 ms; needs high-speed camera capture |
| Public theme and plugin format, with documentation | Builds on the Phase 2 theme pack format |
| Release signing and opt-in crash reporting | The release build type has no signing config yet |
| Play Store / F-Droid compliance review | `MANAGE_EXTERNAL_STORAGE` is a Play review risk; bundled assets must be replaced |
| Orientation setting | Optional: let users unlock rotation (for example portrait phones or vertical handhelds) |

### 4.5 XMB fidelity

From the assessment in [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md). **Verdict: not there yet.**
The home menu already reads as an XMB at a glance, but the Games lists, the typeface, the sounds
and several system behaviors described in the PS3 and PSP manuals don't match yet. Ordered by how
much each item adds to the feel:

| Item | Depends on | Notes |
| --- | --- | --- |
| Per-game icons, with a background crossfade to the game's art on focus | Scraper and asset cache (§4.2) | The largest gap: every game currently shows the same disc or cartridge glyph |
| XMB-style rounded typeface | Theme pack loader for user fonts | Bundle an open-licensed font as the default; the Android default font is the most visible difference today |
| Resume Game / Quit Game while an emulator Xross launched is running | — | Equivalent of the PS button's Quit Game and the PSP Go's Pause/Resume Game |
| Distinct cursor sound and a working error sound | Sound packs (§4.2) | Moves reuse the options sound; `snd_error.wav` is silent |
| XMB-style drill-down (folder icon beside its contents, no text breadcrumb) | — | |
| Control panel on △ in the video and music players | — | Alongside the existing direct controls |
| Photo or theme background behind the menu | Theme packs (§4.2) | "Set as wallpaper" currently sets the Android wallpaper |
| Connected drives as items in their categories | Hot-swap ✅ | |
| Keyboard input modes and an optional multi-tap keypad | — | Lower case, accented letters, symbols |
| Delete, sort and Display All in the options menu | — | |
| Screensaver when docked or idle on a TV | Dock detection (§4.3) | |

Xross's sounds, icons and font must stay original or open-licensed by default, with Sony-like
assets loadable only by the user through theme packs.

---

## 5. Scope changes

Decided in [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md). These replace the original wording in
`project_scope.md`.

| Original scope | Decision | Reason |
| --- | --- | --- |
| Compose **or** C++/SDL2 UI | Compose for UI; GL only for the wave | One UI stack |
| Room with FTS5 | FTS4 | Room generates FTS3/4 only; FTS5 isn't guaranteed on older Android |
| Native POSIX bridge for scoped storage | `java.io.File` with All files access | Same speed; DocumentFile fallback later if needed |
| Hashes without decompressing | Header CRC first, full hashes in a background job | MD5/SHA-1 need the content |
| `android:isGame` | `ApplicationInfo.category == CATEGORY_GAME` plus the legacy flag | `isGame` is deprecated since API 26 |
| Google Play and web-search scraping | Dropped | No public API; against the services' terms |
| Yuzu, Citra, AetherSX2 | Eden, Azahar, NetherSX2 | Original projects discontinued |
| Sudachi | Not added | No verified launch contract |
| Dusklight / TwilitRealm | Parked | No public API found |
| `autorun.inf` "execution" | Parse only | Executing from removable media is unsafe |
| Ktor/Netty with FTPS | Ktor CIO for the portal, Apache FtpServer for FTPS | Ktor has no FTP; Netty is heavy on Android |
| Free rotation | Landscape only (`sensorLandscape`) | Handheld-first; large screens can still rotate |

---

## 6. Order of work

```
Phase 1b                  Phase 2 (Beta)                                  Phase 3 (RC)            Phase 4 (v1.0)
────────                  ──────────────                                  ────────────            ──────────────
Real-hardware pass ──┬──▶ DAT import ──▶ Scrapers ──▶ Detail view ──┬──▶ RetroAchievements ──▶ Onboarding
                     │                        │                     └──▶ Xross Portal (editing)
                     │                        └──▶ Video snaps
                     ├──▶ Theme pack format ──▶ Sounds, fonts, wave params ─────────────────────▶ Public theme format
                     ├──▶ Per-controller mappings, axial deadzone
                     └──▶ Baseline Profile                                Dual screen, docking
                                                                          FTPS (independent)
```

- **The hardware pass gates the Beta.** Controller handling, emulator launches and frame pacing
  are only proven on the emulator.
- **DAT import comes before scrapers.** Exact offline titles make scraper lookups more accurate
  and cut API calls.
- **The theme pack format comes before any public release**, because the bundled assets have to
  move out of the app.
- **FTPS, dual screen and docking don't depend on the metadata work** and can be done in
  parallel with it.

### Suggested next steps

1. Run the real-hardware pass on at least one handheld with a controller and real emulators.
2. An XMB-style default font (the other quick fidelity wins from §4.5 are done).
3. Update `docs/STATUS.md` for the landscape lock, the Galaxy A55 results, the rendering fixes
   and the media access message.
4. Add a Baseline Profile.
5. Start Phase 2 metadata with DAT import. Per-game art from the scrapers is the biggest single
   step toward the XMB feel.

---

## 7. Performance targets

From `project_scope.md` §5.

| Target | Result |
| --- | --- |
| ≥ 60 fps menu navigation | ✅ 🧪 All 12 display configurations |
| 120 fps on high-refresh panels | ◐ 📱 ~120 fps on the Galaxy A55; no 120 Hz handheld tested |
| Input-to-screen ≤ 16.6 ms | ⬜ Not measured |
| Cold boot to interactive < 2.5 s | ✅ 🧪 ~200 ms to first frame |
| Idle RAM ≤ 150 MB with an emulator in front | ✅ 🧪 42 MB PSS |
| GL loop suspended when Xross isn't in front | ✅ 0% CPU while an emulator runs |
| Background daemons < 3% CPU when idle | ⬜ No daemons yet |
