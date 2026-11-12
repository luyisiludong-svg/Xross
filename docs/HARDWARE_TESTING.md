# Testing Xross on real Android hardware

How to install, exercise and measure Xross on a physical handheld, phone or TV box, and what to
send back when something is wrong. Feature status and emulator measurements are in
[`STATUS.md`](STATUS.md); open work is in [`Roadmap.md`](../Roadmap.md).

> **Don't run the scripts in `tools/device-tests/` on a device that holds your real ROM
> collection.** Every suite, the quick `smoke.sh` included, runs `rm -rf /sdcard/ROMs` to seed its
> own test files (and removes them at the end) and clears Xross's app data. They are meant for a
> throwaway emulator (§2a).

## 1. Connect the device

1. On the device: **Settings ▸ About ▸ tap "Build number" 7 times**, then
   **Developer options ▸ USB debugging** on.
2. Plug in over USB-C, accept the prompt on the device, and check it is listed:

   ```bash
   adb devices
   ```

Over Wi-Fi (Android 11+): turn on **Wireless debugging**, then pair and connect using the IP and
ports shown on that screen:

```bash
adb pair <ip>:<pairing-port>
```

```bash
adb connect <ip>:<port>
```

## 2. Install Xross

| Build | Use it for | Command |
| --- | --- | --- |
| `debug` | Functional testing | `./gradlew :app:installDebug` |
| `profiling` | Smoothness and timing (R8-optimized, same signing key, logs frame timing) | `./gradlew :app:installProfiling` |

Both builds share the same signing key, so you can switch between them without uninstalling.

Grant storage access in the app (**Settings ▸ Library ▸ All files access**) or with:

```bash
adb shell appops set --uid dev.xross.launcher MANAGE_EXTERNAL_STORAGE allow
```

## 2a. Automated device tests

`tools/device-tests/` drives the debug build over adb (key events, the accessibility tree,
debug-build logs) in three tiers. All share `lib/harness.sh`; each suite clears Xross's data and
seeds its own fixtures, so any of them runs on its own and in any order.

| Command | Checks | Time (API 37 emulator) | What it covers |
| --- | --- | --- | --- |
| `tools/device-tests/smoke.sh` | 12 | ~30 s | Quick sanity: Xross starts, creates its database, scans the seeded ROMs, draws the category bar and Games root, opens the first platform, L1/R1, no crash. No media, theme, database or battery fixtures |
| `tools/device-tests/test-library.sh` | 41 | ~2 min | Platform detection, `.cue`/`.bin` pairing, title cleaning, favorites, per-game emulator, search, game tiles and Details paging, categories, rescan and fingerprinting |
| `tools/device-tests/test-theme.sh` | 94 | ~4 min | Settings ▸ Theme rows, quick profiles, color layers (circadian, `dumpsys battery` tint and charging pulse), Sound, v2 packs and hot reload (a pack's `wallpaper.png`: shown, stops the wave, pre-blurred), `.xtheme` import and variants, game-art palette |
| `tools/device-tests/test-media.sh` | 31 | ~2 min | Photo viewer and zoom, Set as Xross wallpaper and back to the theme's, music from tags, the music driving the shader's audio uniforms (0 when paused), Now Playing, Select shortcut and mini-player, music pausing for video and games |
| `tools/device-tests/test-input.sh` | 11 | ~45 s | Deadzone cycle, ten-button remap and its saved mapping, touch swipes and drag |
| `tools/device-tests/test-integration.sh` | 9 | ~35 s | Avatar mini-HUD, RetroAchievements dashboard, no secrets in `Xross/config`, RetroArch launch through the stub |
| `tools/device-tests/run-all.sh` (or `smoke.sh --all`) | 186 | ~10 min | Every suite in sequence with one summary; exit 0 only when nothing failed |

`smoke.sh <suite>` runs one suite (`smoke.sh theme`); `run-all.sh theme input` runs a chosen set.

- **Host tools:** `python3` (theme fixtures); `ffmpeg` (media suite) and `sqlite3` with a
  debuggable build (game-art block) are optional, and their checks are reported as skipped
  without them. The RetroArch stub (`./gradlew :emulator-stub:installRetroarchDebug`) enables the
  launch and music-during-a-game checks; never install it next to real RetroArch (§3).
- **Teardown:** however a suite ends (pass, fail, Ctrl-C), it runs `dumpsys battery reset`, stops
  the stub and deletes what it put on the device: `/sdcard/ROMs`, the `XrossSmoke` media folders,
  the smoke theme packs and `.xtheme` files, hero art and the UI dump. `XROSS_KEEP_FIXTURES=1`
  keeps them for debugging. Xross's previous `Xross/config` stays in `/sdcard/Xross-smoke-backup/`.
- **On a phone with its own media** the Photo and Music checks fail by design: they expect the
  seeded `XrossSmoke` files to be the only media.
- Frame timing has its own scripts (`palette-perf.sh`, `display-matrix.sh`, §5); they need the
  profiling build.

## 3. Games and real emulators

- **ROMs:** put them in platform folders such as `ROMs/snes`, `ROMs/psx` or `ROMs/gc`, on
  internal storage or the SD card. Folders named `ROMs`, `Games`, `Emulation` or `Retro` at the
  root of any drive are found automatically; others can be added in Settings ▸ Library.
- **RetroArch:** install it, open it, and download the cores Xross uses with
  **Online Updater ▸ Core Downloader**: `snes9x`, `mgba`, `fceumm`, `genesis_plus_gx`,
  `swanstation`, `mupen64plus_next_gles3`, `gambatte`, `melondsds`, `flycast`, `fbneo`.
  Grant RetroArch storage access too. Xross launches the core at
  `/data/data/<package>/cores/<core>_libretro_android.so`, which only exists once that core is
  downloaded.
- **Standalone emulators:** install DuckStation, Dolphin and PPSSPP from the Play Store;
  NetherSX2 has to be sideloaded. Open each once and grant it storage access.
- **Remove the test stand-ins** if they were ever installed. They use the real emulators'
  package names and would block or replace them:

  ```bash
  adb uninstall com.retroarch.aarch64
  ```

  (Likewise `com.github.stenzek.duckstation`, `xyz.aethersx2.android`,
  `org.dolphinemu.dolphinemu`, `org.ppsspp.ppsspp`, but only if `adb shell pm list packages`
  shows that package is Xross's stand-in rather than the real emulator.)

**Pass/fail for each emulator:** pick a game and press Confirm. The game must start directly. If
the emulator opens to its own menu instead, the launch format doesn't match that emulator
version; capture a log (§9).

| Emulator | Platforms to try |
| --- | --- |
| RetroArch | NES, SNES, GB/GBC/GBA, Genesis, N64, Arcade |
| DuckStation | PS1 (`.chd`, `.cue`, `.m3u`) |
| NetherSX2 | PS2 (`.iso`, `.chd`) |
| Dolphin | GameCube, Wii (`.rvz`, `.iso`) |
| PPSSPP | PSP (`.iso`, `.cso`) |

## 4. Controllers

Test the built-in pad, plus any of DualSense, Switch Pro and Xbox controllers you own:

- [ ] D-pad moves the cursor; holding it repeats smoothly
- [ ] Left stick moves the cursor; holding it repeats; letting go doesn't bounce one step back
- [ ] L1/R1 switch categories
- [ ] Confirm/cancel: with Settings ▸ Controls ▸ Confirm button on **Auto**, Nintendo pads
      confirm with the right-hand button, others with the bottom one
- [ ] Button symbols in the bottom-right hints match the controller (✕○△ / A B Y)
- [ ] Settings ▸ Controls ▸ Remap buttons works (10 steps, the last is the media controls
      button), and Reset all button mappings restores defaults
- [ ] Analog stick deadzone **Off — hardware only**: the stick still has no drift in menus
      (if it does, the controller's own flat zone is too small; pick 15%)
- [ ] **Select** opens Now Playing from any category while music plays and closes it again;
      holding **Guide** for half a second does the same, if the handheld lets the key through
- [ ] Touch: one horizontal swipe moves one category; vertical drag scrolls; tap selects

If a button does nothing, this shows the raw key codes the device sends (press the button while
it runs, Ctrl+C to stop):

```bash
adb shell getevent -lt
```

## 5. Smoothness, memory and startup

Install the `profiling` build. Reset the frame counters:

```bash
adb shell dumpsys gfxinfo dev.xross.launcher reset
```

Use the launcher normally for about 30 seconds: scroll a large list, hold the D-pad, switch
categories. Then read the interface frame stats:

```bash
adb shell dumpsys gfxinfo dev.xross.launcher
```

Wave background frame timing and library scan times:

```bash
adb logcat -s WaveFrames XrossScan
```

Cold start time (target under 2.5 s):

```bash
adb shell am force-stop dev.xross.launcher && adb shell am start -W -n dev.xross.launcher/.MainActivity
```

Memory while a game runs: launch a game, then check that `TOTAL PSS` is 150 MB or less:

```bash
adb shell dumpsys meminfo dev.xross.launcher
```

| Measure | Good | Investigate |
| --- | --- | --- |
| `gfxinfo` Janky frames | < 5% | > 10% |
| `gfxinfo` Number Slow UI thread | ~0 | rising while scrolling |
| `WaveFrames` p95 | ≤ 1.2 × period | missed > 5 per 300 frames |
| Cold start `TotalTime` | < 2500 ms | |
| `TOTAL PSS` with a game running | ≤ 150 MB | |

The wave is capped near 60 fps (every 2nd vsync at 120 Hz, full rate at 90 Hz and below), and
drops to ~20 fps after 3 s without input while no music or video snap plays. On a 120 Hz screen
the `WaveFrames` lines report `period 16.7ms` while in use and `period 50.0ms` at rest; check
`p95` against that period.

Check by eye and ear:
- [ ] The wave background moves smoothly with no visible banding
- [ ] The focused item's glow pulses gently
- [ ] Sound effects play at sensible volumes; the startup and game-start sounds play
      (Settings ▸ Sound can turn each off)

## 6. Library and fingerprints

- [ ] All your platforms appear under Games, with sensible titles
- [ ] **Settings ▸ Library ▸ Game fingerprints** counts up while Xross is open and stops when a
      game is running (it shows "Fingerprinting N of M · <title>" while working)
- [ ] With a large library on an SD card, browsing stays smooth while fingerprinting runs
- [ ] Optional: turn on **Fingerprint large disc images** and note how long one ISO takes; the
      timing appears in `adb logcat -s XrossScan` with the `profiling` or `debug` build

- [ ] **Hot-swap:** with Xross open, pull the SD card (or USB drive). A toast names the drive, its
      games stay listed as "Not connected", and re-inserting it brings them back without
      restarting. Favorites and playtime on those games must survive.
- [ ] **Playtime:** play a game for a couple of minutes, return, and check the line under its
      title reads "Playtime: N mins" (it said "Never played" before)
- [ ] **Options menu:** △ / Y (or long-press) on a game opens Play / Favorite / Emulator; setting
      an emulator there applies to that game only
- [ ] **Search:** Games ▸ Search; type with the D-pad (△ / Y = space, L1 = delete, R1 = results)
- [ ] **All Games:** platforms are inside Games ▸ All Games; Settings ▸ Theme ▸ Platform folders
      switches back to the flat list
- [ ] **Base ROMs folder:** Settings ▸ Library ▸ Create system folders makes `Xross/roms/snes`,
      `psx`, … (22 folders); a ROM copied into one of them shows up after Rescan library

## 6a. The Xross folder

- [ ] After the first start with All files access, `/sdcard/Xross/` has `config/`, `data/`,
      `dats/`, `media/`, `roms/`, `themes/`, with a `.nomedia` in `media/` and `themes/`
- [ ] `config/settings.json`, `controllers.json` and `systems.json` exist; no account name,
      password or API key appears in them
- [ ] Edit `config/settings.json` on a PC (for example `"wave": false`), copy it back, and
      return to Xross: the change applies
- [ ] Leave Xross for a few seconds: `data/xross.db` appears, and **Settings ▸ System ▸ Back up
      library now** updates its time
- [ ] Scraped art is in `media/covers/<system>/`, `media/heroes/<system>/`, … named after the
      ROM, and none of it shows up in Photo or Video
- [ ] With an SD card: **Settings ▸ System ▸ Xross folder** moves the folder to the card and
      back; settings and art come along
- [ ] After a reinstall (or on a second device with the same card), Games offers **Restore
      library backup**; restoring restarts Xross with the old library

## 6b. Apps

- [ ] Options ▸ Hide on an app removes it from Apps; Settings ▸ Apps ▸ Hidden apps brings it back
- [ ] Settings ▸ Apps ▸ Hide system apps hides preinstalled apps
- [ ] Options ▸ Move to folder ▸ Emulators (suggested for emulators) and ▸ New folder… (typed on
      the gamepad keyboard) create folders at the top of Apps

## 6c. Profile, game tiles and Details paging (Phase 2b.1)

Link a RetroAchievements account first (Settings ▸ Accounts), and scrape a few games so they have
logos and hero art.

- [ ] The avatar sits at the far right of the status bar, right of the clock and battery
- [ ] Up from the first row of a category expands it into a mini-HUD (user, rank, points, recent
      badges) in place, with the clock and battery fading out behind it; Down or Back collapses
      it and they come back. No jump or jitter in the rest of the status bar
- [ ] In the HUD, Confirm refreshes the profile, Options opens the User Card, Left/Right switch
      category; tapping outside it closes it
- [ ] Games show 16:9 tiles: the logo if there is one, else wide art, else the title on a plate;
      the focused tile's glow isn't clipped by its neighbours
- [ ] Details (Options ▸ Details or → on a game): L1/R1 and ←/→ page to the previous and next game
      with a slide; art, facts and achievements change; at the first and last game nothing
      happens; Back returns to the list with the cursor on the last game shown
- [ ] Resting on a game with achievements shows "unlocked/total" and points above the button
      hints; a game RetroAchievements doesn't know shows nothing
- [ ] **Games ▸ RetroAchievements** opens the dashboard: profile header, completion, mastered
      games, recent unlocks; it scrolls with the D-pad and closes with Back. With Wi-Fi off it
      still shows the cached data
- [ ] **Settings ▸ Theme ▸ Profile customization:** color, width and glow change the preview at
      once and are saved in `config/settings.json` under `profile.avatarStyle`
- [ ] Idle CPU: rest on the Games root for 10 s (no music), then sample 20 s of the process's
      CPU time (below). Target < 10%; note it with the avatar glow on and off. Before the idle
      rest it was 100–130% on the Galaxy A55
- [ ] The first press after a rest moves the cursor at once, and the wave and glows pick up
      without a jump

```bash
pid=$(adb shell pidof dev.xross.launcher); t0=$(adb shell cat /proc/$pid/stat | awk '{print $14+$15}'); sleep 20; t1=$(adb shell cat /proc/$pid/stat | awk '{print $14+$15}'); echo "CPU $(( (t1 - t0) * 100 / 20 / 100 ))%"
```

## 7. Photo, Music and Video

- [ ] Photo, Music and Video show your device's pictures, songs (with album art) and videos
- [ ] Settings ▸ Media ▸ Photo folders ▸ Add folder…: Photo then shows only that folder,
      including pictures in a folder with a `.nomedia` file; "Show all photos again" undoes it
- [ ] Photo viewer: L1/R1 zoom, D-pad pans without showing black edges, Start runs a
      slideshow, Options offers Set as Xross wallpaper (the photo shows behind the menu, and
      frosted cards over it show it blurred) and Set as Android wallpaper (the system screen);
      Settings ▸ Theme ▸ Xross wallpaper goes back to the theme's
- [ ] Music keeps playing while you browse and shows a media notification; headset buttons work
- [ ] While music plays, a mini player card sits in the lower-right corner above the button
      hints; pausing for 30 s fades it out, and resuming brings it back
- [ ] Starting a game pauses music; it resumes when you come back
      (Settings ▸ Media ▸ Music while playing games)
- [ ] Video: smooth playback at the file's aspect ratio; ←/→ and L1/R1 seek; music pauses during
      the video and resumes after

## 8. Home screen

**Settings ▸ System ▸ Set as home app** makes Xross the launcher. To undo it, use Android's
**Settings ▸ Apps ▸ Default apps ▸ Home app**.

## 9. What to send back

The device model and Android version, plus a log, a screenshot, and the `gfxinfo` output:

```bash
adb logcat -d > xross-log.txt
```

```bash
adb exec-out screencap -p > xross-shot.png
```

```bash
adb shell dumpsys gfxinfo dev.xross.launcher > xross-gfxinfo.txt
```

For a controller problem, also include the `getevent -lt` output for the button.
