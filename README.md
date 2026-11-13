<div align="center">

# Xross

**A controller-first cross-media bar launcher for Android handhelds and TVs.**

![Android API 28+](https://img.shields.io/badge/Android-API%2028%2B-3DDC84?logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![OpenGL ES 3.0](https://img.shields.io/badge/OpenGL%20ES-3.0-5586A4?logo=opengl&logoColor=white)
![Unit tests](https://img.shields.io/badge/unit%20tests-645%20passing-brightgreen)
![Device checks](https://img.shields.io/badge/device%20checks-226%2F226-brightgreen)
![Architecture](https://img.shields.io/badge/architecture-MVVM%20%C2%B7%20single%20module-blueviolet)
![License](https://img.shields.io/badge/license-not%20yet%20chosen-lightgrey)

</div>

Xross turns a retro-gaming handheld or Android TV box into a console-style home screen. Browse
games, music, photos, video and apps on a horizontal bar of categories with vertical lists, the
way a 2000s console menu worked, and drive all of it with a D-pad. Behind the menu runs a live
OpenGL ribbon shader; in front of it sits a library that knows your ROMs by hash, matches them
against No-Intro and Redump, pulls in artwork and tracks your RetroAchievements. Theme 2.0 lets you
restyle every surface, sound, shader and particle from plain JSON files that reload as you save
them.

> **Status:** Phase 2b.4 (visual effects, shaders and materials) is complete on the emulator.
> See [`Roadmap.md`](Roadmap.md) for what is next and [`docs/STATUS.md`](docs/STATUS.md) for what
> has been verified, and on which hardware.

## Screenshots

*Captures of the current build are still to come.* Until then,
[`assets/examples/`](assets/examples/) holds the reference screenshots and design mock-ups the
look is built from, and [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md) compares Xross with the
original menu screen by screen.

## Features at a glance

### 🎮 Navigation and gamepad

- Real D-pad ergonomics: per-category drill-down stacks, breadcrumbs, spring-animated scrolling,
  plus touch swipe, tap and long-press.
- Controller vendor detection (Sony, Microsoft, Nintendo, generic) with matching button glyphs and
  a confirm/cancel swap.
- Radial and axial deadzones, anti-snapback, and stick/hat auto-repeat.
- A button-remap wizard saved per controller model, an Options (△ / Y) menu on every row, and media
  controls on Select.

### 📚 Library and metadata

- 22 platforms detected from folder names, scanned across internal storage, microSD and USB-OTG,
  with an FTS4 search you can type with a gamepad.
- Hash fingerprinting inside `.zip`, `.7z` and CHD files, including No-Intro header-less hashes.
- Offline DAT verification against No-Intro, Redump and MAME.
- Artwork, descriptions and video snaps from Hasheous, SteamGridDB and ScreenScraper, cached
  ES-DE style.
- RetroAchievements: a status-bar avatar with a mini-HUD, a dashboard, per-game badge grids and
  unlock toasts.
- One-press launch into RetroArch, DuckStation, NetherSX2, Dolphin, PPSSPP and six more emulators,
  chosen per system or per game.

### 🎨 Theme 2.0 engine

- Theme packs in plain JSON (palettes, font roles, surfaces, icons, sounds) that hot-reload while
  you edit them, or install from one `.xtheme` file.
- Background shader presets, your own `shader.frag`, and audio-reactive uniforms driven by Xross's
  music player.
- Frosted-glass surfaces, wallpapers, focus frames, app icon masks and ambient particles.
- Scanline, CRT and LCD-grid filters in GL, and AGSL UI filters on Android 13+.
- Context rules (category, system, battery, time of day), a circadian color curve, palettes taken
  from game art, and quick profiles: OLED True Black, Y2K Cyber-Pastel and Monochrome E-Ink.
- Built to stay smooth: effects rest when you stop pressing buttons, and frame pacing is measured
  on 60 Hz and 120 Hz panels.

### 💾 Portable by design

- One `Xross/` folder holds your config, DATs, art, ROMs and themes. Move it to an SD card or USB
  drive and take it to another device.
- Drives can be hot-swapped without losing their games from the library.
- The library database is backed up to the folder automatically and can be restored on a fresh
  install. Account credentials never leave app-private storage.

## Quick start

**You need** an Android 9+ device (API 28) with a landscape screen, and the Android SDK to build.

```bash
./gradlew :app:testDebugUnitTest     # fast JVM unit tests
./gradlew :app:installDebug          # build and install on a device or emulator
```

Then on the device:

1. **Grant storage access:** Settings ▸ Library ▸ All files access.
2. **Add games:** put ROMs in platform folders under `Xross/roms/` (`snes/`, `psx/`, `gba/`, …).
   Settings ▸ Library ▸ Base ROMs folder ▸ Create system folders makes them all for you.
3. **Optional:** drop DATs in `Xross/dats/` and import them, turn on scraping in Settings ▸
   Library ▸ Metadata & artwork, or copy a theme pack into `Xross/themes/`.

Upgrading from **Wave**? The project was renamed on 2026-11-11 and the app ID changed to
`dev.xross.launcher`, so uninstall the old app by hand
([details](docs/USER_GUIDE.md#1-before-you-start)).

## Documentation

| Guide | What's in it |
| --- | --- |
| [User and storage guide](docs/USER_GUIDE.md) | The `Xross/` folder, SD/USB, backups, ROM folders, DATs, scraping, RetroAchievements, media and apps, feature status |
| [Theme 2.0 specification](docs/THEME_SPECIFICATION.md) | Pack layout, `theme.json` v2, surfaces, sounds, Wave settings, effects, rules, quick profiles, `.xtheme` |
| [Developer guide](docs/DEVELOPMENT.md) | Building, unit/live/device tests, emulator stand-ins, source layout |
| [Roadmap and backlog](Roadmap.md) | The plan: what is done, known issues, order of work, scope decisions and upcoming specs |
| [Implementation status and benchmarks](docs/STATUS.md) | Test suites, device runs, frame-timing measurements, bugs found and fixed |
| [Hardware testing checklist](docs/HARDWARE_TESTING.md) | Testing on a physical handheld, and what to send back |
| [Architecture assessment](docs/ARCHITECTURE_ASSESSMENT.md) | Structure, tech debt and a prioritized refactoring plan |
| [XMB fidelity analysis](docs/XMB_FIDELITY.md) | How close Xross is to the original menu, checked against the console manuals |
| [ScreenScraper API guide](docs/SCREENSCRAPER_API.md) | English summary of ScreenScraper's API rules |
| [Ricing and Web Studio proposal](docs/THEME_RICING_AND_WEB_STUDIO.md) | Plain-text themes, a browser theme studio and a desktop build (proposal) |
| [Feasibility review](docs/FEASIBILITY.md) | The review behind the original scope changes |
| [Product scope](project_scope.md) | The original product scope |

## Testing

Xross is tested on three levels:

- **Unit tests** (645, JVM and Robolectric): `./gradlew :app:testDebugUnitTest`. Opt-in live tests
  call the real scraping and RetroAchievements APIs with credentials from `.env`.
- **Device tests** over adb, in three tiers: `smoke.sh` (12 checks, ~30 s), five focused suites
  (library, theme, media, input, integration), and `run-all.sh` for the full 226-check regression.
- **Frame timing** on the R8-optimized `profiling` build (`palette-perf.sh`, `display-matrix.sh`).

> ⚠️ **Run device tests on a throwaway emulator or test device only.** They wipe Xross's data and
> delete `/sdcard/ROMs` to seed their own fixtures.

Commands and details are in the [developer guide](docs/DEVELOPMENT.md#testing) and
[`HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md).

## License and community

Xross has no license file yet, so all rights are reserved by default until one is chosen. Bug
reports and ideas are welcome as issues; check [`Roadmap.md`](Roadmap.md) first to see whether
something is already planned.

Xross is an independent fan project. It isn't affiliated with or endorsed by Sony Interactive
Entertainment.
