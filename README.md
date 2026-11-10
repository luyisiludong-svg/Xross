# Wave — XMB launcher for Android handhelds

Phase 1 (Alpha) of [`project_scope.md`](project_scope.md). The feasibility review and the
decisions behind this build are in [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md).

## Build and run

```bash
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:installDebug           # build + install on a connected device/emulator
```

On first launch go to **Settings ▸ Library ▸ All files access** to grant storage access.
Folders named `ROMs`, `Games`, `Emulation` or `Retro` at the root of any drive are picked up
automatically; add others with **Add ROM folder…**. Put games in platform subfolders
(`snes/`, `psx/`, `gba/`, …). See `PlatformRegistry` for every folder alias and extension.

## Phase 1 status

| Scope item | State |
| --- | --- |
| XMB category/item dual-axis navigation | Done. Per-category drill-down stacks, breadcrumb, animated scroll, touch swipe and tap |
| Gamepad layer | Done. Vendor detection (Sony/Microsoft/Nintendo/generic) with matching glyphs, confirm/cancel swap (Auto/South/East), radial deadzone, anti-snapback, stick/hat auto-repeat, button-remap wizard |
| Storage engine | Done. File-path scanner, Room DB with FTS4 title index, incremental rescan that keeps games on unmounted cards, cue/gdi/m3u track de-duplication |
| Emulator dispatch | Done. RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP, with a per-platform choice in Settings ▸ Emulators. ROMs go out as a path extra plus a `content://` URI with a read grant |
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette and adjustable speed. Pauses whenever the launcher isn't in front |
| Extras | Favorites, Recently Played, Android apps (games sorted into Games), can be set as the HOME launcher, Android TV leanback entry |

Full feature-by-feature status, test results, device/performance measurements and the
phased roadmap are in [`docs/STATUS.md`](docs/STATUS.md).

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # 113 JVM + Robolectric tests
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # on-device smoke test (clears Wave's data)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

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

## Layout

```
app/src/main/java/dev/wave/launcher/
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily
  storage/  PlatformRegistry, RomScanner, StorageVolumes
  data/     Room (GameEntity, GameDao, WaveDatabase), GameRepository, AppRepository, SettingsStore
  launch/   EmulatorRegistry (intent matrix), GameLauncher
  state/    MenuTree, NavState, RemapSession, XmbViewModel
  ui/       XmbScreen, StatusBar, WaveBackground + WaveRenderer (GLSL), Glyphs
  theme/    WaveTheme palettes
  audio/    UiSounds (SoundPool effects + streamed boot cues)
tools/icons/        SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven smoke test
```
