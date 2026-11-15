# Phase 1 (Alpha) and 1b — evidence

Emulator dispatch and the Phase 1b features verified on a device. Overview: [Phase 1](README.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.3"></a>

## 3.3 Emulator dispatch — 11/11 recipes verified with stubs
`tools/emulator-stub` builds a test-only APK per emulator, each installed under the real package
and activity name. Each stub logs the intent it receives and reads the ROM through the URI.

| Emulator | Component | ROM handoff | URI read |
| --- | --- | --- | --- |
| RetroArch | `com.retroarch.aarch64/…RetroActivityFuture` | `ROM`, `LIBRETRO=/data/data/<pkg>/cores/snes9x_libretro_android.so`, `CONFIGFILE` | ✅ |
| DuckStation | `…duckstation.EmulationActivity` | `bootPath` | ✅ |
| NetherSX2 | `xyz.aethersx2.android.EmulationActivity` | `bootPath` | ✅ |
| Dolphin | `…ui.main.MainActivity` | `AutoStartFile` | ✅ |
| PPSSPP | `…PpssppActivity` | `ACTION_VIEW` + data | ✅ |
| Azahar | `org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity` | `ACTION_VIEW` + data, clear task/top | ✅ |
| Eden | `dev.eden.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity` | `android.nfc.action.TECH_DISCOVERED` + data | ✅ |
| Flycast | `com.flycast.emulator/.MainActivity` | `ACTION_VIEW` + data | ✅ |
| M64Plus FZ | `org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity` | `ACTION_VIEW` + data | ✅ |
| melonDS | `me.magnum.melonds/.ui.emulator.EmulatorActivity` | `me.magnum.melonds.LAUNCH_ROM` + `uri` extra + data | ✅ |
| ScummVM | `org.scummvm.scummvm/.SplashActivity` | `ACTION_MAIN`, data = game id from the `.scummvm` file | n/a (game id, not a file) |

The six newer recipes come from ES-DE's published Android rules. ES-DE passes those emulators a
storage-access-framework URI; Xross passes its FileProvider URI with a read grant. The stubs
confirm the intents are well-formed and the URI is readable, **not** that each real emulator
accepts a FileProvider URI. That needs real installs.

Finding: no emulator could read the raw path without its own storage permission. Real
emulators are normally granted storage access; if one isn't, it must use the URI.

<a id="s3.6"></a>

## 3.6 Phase 1b features verified on a device (emulator)

| Feature | What was done | Result |
| --- | --- | --- |
| Hot-swap | Created a real public volume (`sm partition … public`), put a ROM on it, then unmounted / mounted / unmounted with Xross in front | Toasts "Virtual SD card removed — its games are kept" and "… connected"; rescans reported +1 then −0; the open search stayed open; the game stayed listed as "Not connected"; launching it says "File not found — is the storage mounted?" |
| Playtime | Launched a game through the Flycast stub, stayed 66 s, returned | Row shows "Played 1 min"; Options menu shows "1 min · 2 times"; open search results refreshed by themselves |
| Options menu | Opened on a game in landscape and portrait | Panel, submenu with ✓ on the active emulator, breadcrumb and hints render correctly |
| Search | Typed with D-pad and hardware keys; moved to results | Word-prefix matches ("SU" → Golden **Su**n, **Su**per Metroid); correct hints per pane |
| Upgrade | Installed over a database from the previous schema (v2) | Opened and ran without error; that the data survives is asserted by the migration unit tests (2→3, 2→4), not checked by hand on the device |

Bugs found by this device pass and fixed:
- **Startup crash.** A property used during construction was declared below the `init` block,
  so it was still null (Kotlin initializes in textual order). `init` now sits at the end of
  the class, and `XmbViewModelTest` constructs the real view model so this can't regress
  unnoticed.
- Toasts were drawn under the search and Options overlays; they are now on top and opaque.
- "GBA · GBA · 1 KB": the extension repeated the platform name in cross-platform lists.
- A smoke-test check assumed the test photo folder was on screen without scrolling.

Not covered: a physical USB-OTG drive (the removable volume was the emulator's virtual disk),
and the "deferred while a game runs" hot-swap path (unit-level only).
