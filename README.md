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

### Not verified yet

* The intent recipes are taken from each emulator's published launch contract, but none have
  been tested against real installs yet (the dev emulator has none installed).
* Only tested with D-pad key events on an emulator so far, not with physical controllers
  (DualSense, Switch Pro, built-in handheld pads).

## Layout

```
app/src/main/java/dev/wave/launcher/
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily
  storage/  PlatformRegistry, RomScanner, StorageVolumes
  data/     Room (GameEntity, GameDao, WaveDatabase), GameRepository, AppRepository, SettingsStore
  launch/   EmulatorRegistry (intent matrix), GameLauncher
  state/    MenuTree, NavState, RemapSession, XmbViewModel
  ui/       XmbScreen, WaveBackground + WaveRenderer (GLSL), Glyphs
```
