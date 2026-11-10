# Wave — XMB launcher for Android handhelds

A controller-first launcher for Android handhelds in the style of the cross-media bar, built from
[`project_scope.md`](project_scope.md). Phase 1 (Alpha) is complete and Phase 2 (Beta) is under
way. The feasibility review and the decisions behind this build are in
[`docs/FEASIBILITY.md`](docs/FEASIBILITY.md).

## Build and run

```bash
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:installDebug           # build + install on a connected device/emulator
```

On first launch go to **Settings ▸ Library ▸ All files access** to grant storage access, or press
the "Storage access needed" row in Games, Photo, Music or Video. The same grant covers ROMs and
media. Folders named `ROMs`, `Games`, `Emulation` or `Retro` at the root of any drive are picked
up automatically; add others with **Add ROM folder…**. Put games in platform subfolders
(`snes/`, `psx/`, `gba/`, …). See `PlatformRegistry` in `storage/Platform.kt` for every folder
alias and extension.

Wave runs in landscape and turns 180° with the device. Android 16 ignores the orientation lock
on screens 600 dp and wider.

## Status

| Area | State |
| --- | --- |
| XMB navigation | Done. Settings, Photo, Music, Video, Games and Apps; per-category drill-down stacks, breadcrumb, spring-animated scroll, touch swipe, tap and long-press |
| Gamepad layer | Done. Vendor detection (Sony/Microsoft/Nintendo/generic) with matching glyphs, confirm/cancel swap (Auto/South/East), radial deadzone, anti-snapback, stick/hat auto-repeat, button-remap wizard, Options (△ / Y) menu |
| Library | Done. File-path scanner over internal storage, microSD and USB-OTG; Room DB with an FTS4 title index and gamepad search; incremental rescan; hot-swap that keeps games on removed drives; cue/gdi/m3u de-duplication; favorites, Recently Played and playtime |
| Fingerprinting | Done. CRC32/MD5/SHA-1 inside `.zip`/`.7z`, CHD header SHA-1 and No-Intro header-less hashes, in a background job that stops while a game runs |
| Emulator dispatch | Done. RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS and ScummVM, chosen per platform or per game. ROMs go out as a path extra plus a `content://` URI with a read grant. Verified with stand-ins only |
| Media hub | Done. Photo viewer (zoom, pan, slideshow, wallpaper), background music player with media-session controls, video player; music pauses for games and videos |
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette and adjustable speed. Pauses whenever the launcher isn't in front |
| Extras | Android apps (games sorted into Games ▸ Android Games), 7 color themes, can be set as the HOME launcher, Android TV leanback entry |
| Not started | Scrapers and DAT matching, theme packs, on-screen controller, dual screen, RetroAchievements, Wave Portal, FTPS |

[`Roadmap.md`](Roadmap.md) summarizes what is done, what is left, and the order of the remaining
work. Feature-by-feature test results and device/performance measurements are in
[`docs/STATUS.md`](docs/STATUS.md). Testing on a physical handheld is described in
[`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # 115 JVM + Robolectric tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # on-device smoke test (see warning below)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

> **`smoke.sh` is for a throwaway emulator.** It clears Wave's data and runs
> `rm -rf /sdcard/ROMs` (plus `WaveSmoke` folders under Pictures, Music and Movies) to seed its
> own test files. Never run it on a device that holds a real ROM collection.

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Look and sound

* **Icons** come from `assets/icons` (the design SVGs). `tools/icons/svg_icons.py` splits the
  icon sheets and converts the ones listed in `tools/icons/icon_map.txt` into
  `res/drawable/xmb_*.xml`. To change an icon, edit the map and run:

  ```bash
  python3 tools/icons/svg_icons.py list assets/icons /tmp/icons   # previews every icon id
  python3 tools/icons/svg_icons.py build assets/icons tools/icons/icon_map.txt app/src/main/res/drawable
  ```
* **Themes**: Lavender, Crimson, Sapphire, Emerald, Amber, Rose and Midnight (Settings ▸ Theme).
* **Sounds**: `app/src/main/assets/sounds`, mapped in `audio/UiSounds.kt`. Settings ▸ Sound
  toggles effects, volume, the startup cue and the game-start cue.
* **Media**: Settings ▸ Media sets the slideshow interval, whether music resumes after a game or
  stops, and video looping.

## Layout

```
app/src/main/java/dev/wave/launcher/
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily
  storage/  Platform (PlatformRegistry), RomScanner, Fingerprinter, StorageVolumes, VolumeMonitor
  data/     Room (GameEntity, GameHashEntity, DAOs, WaveDatabase), GameRepository, AppRepository,
            FingerprintQueue, PlaySessionTracker, SettingsStore
  launch/   EmulatorRegistry (intent matrix), GameLauncher
  media/    MediaRepository (MediaStore), MusicPlayer, PlaybackService (Media3 session)
  state/    MenuTree, MediaMenus, Navigation (NavState), OptionsMenu, Search, RemapSession,
            MediaOverlayController, XmbViewModel
  ui/       XmbScreen, MediaScreens, SearchOverlay, OptionsPanel, StatusBar,
            WaveBackground + WaveRenderer (GLSL), Glyphs
  theme/    WaveTheme palettes
  audio/    UiSounds (SoundPool effects + streamed boot cues)
app/schemas/         exported Room schemas (v1–v4) used by the migration tests
docs/                feasibility review, implementation status, hardware testing guide
tools/icons/         SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven smoke test
```
