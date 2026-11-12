# Xross — XMB launcher for Android handhelds

A controller-first launcher for Android handhelds in the style of the cross-media bar, built from
[`project_scope.md`](project_scope.md). Phase 1 (Alpha) is complete, Phase 2 (Beta) is under
way, and the first milestone of the customization engine (Phase 2b.1) has landed.
[`Roadmap.md`](Roadmap.md) is the single planning document: status, backlog, order of work,
scope decisions and the specs for upcoming features.

Xross was called Wave until 2026-11-11. The application ID changed from `dev.wave.launcher` to
`dev.xross.launcher`, so an older Wave install is a separate app: uninstall it by hand, since its
settings and library don't carry over. Names in the code such as `WaveRenderer` or the
"Wave background" setting refer to the background effect, not the product.

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

### The `Xross/` folder

Everything you'd want to back up or carry to another device lives in one folder, by default
`/storage/emulated/0/Xross/`:

```
Xross/
  config/   settings.json, controllers.json, systems.json (hand-editable; picked up on resume)
  data/     xross.db, a backup snapshot of the library database;
            retroachievements.json and ra/ (public profile, game lists and progress caches)
  dats/     No-Intro / Redump / MAME DATs to import
  media/    scraped art and videos (has a .nomedia, so it stays out of Photo and Video):
            covers/ 3dboxes/ heroes/ wheels/ screenshots/ capsules/ snaps/, each <system>/<rom name>.<ext>;
            avatars/<user>.png and ra/badges/<badge>.png for RetroAchievements
  roms/     the default base ROMs folder, one folder per system
  themes/   theme packs, one folder each (has a .nomedia; see "Theme packs" below)
```

* **Moving it to an SD card or USB drive:** **Settings ▸ System ▸ Xross folder** steps through the
  mounted drives. Config already in the target folder is used (so a card from another device
  brings its setup along); otherwise the current settings are written there and a database backup
  follows. If the drive is removed, Xross falls back to primary storage. Android 9 and 10 don't
  let apps write to removable cards, so there it stays on primary storage.
* **Before storage access is granted** the same layout lives in app storage, and moves to shared
  storage as soon as Xross may write there.
* **The live database never leaves app storage.** A consistent copy goes to `data/xross.db` a few
  seconds after you leave Xross (when something changed) or with **Settings ▸ System ▸ Back up
  library now**. On a fresh install with an existing backup, Games and Settings ▸ System offer
  **Restore library backup**; Xross restarts to apply it.
* **Accounts stay private.** ScreenScraper and SteamGridDB credentials and the RetroAchievements
  Web API key are never written to `config/` or anywhere else in `Xross/`; they stay in app-private
  storage (`CredentialStore`), excluded from backups.
* **Art is named like ES-DE's** (`media/covers/snes/Super Mario World (USA).png`), so it survives
  a library rebuild and ES-DE media copied into `media/` is used as is. Names are cleaned for
  FAT32/exFAT cards. Art from earlier versions is moved here once, in the background.
* **Base ROMs folder:** **Settings ▸ Library ▸ Base ROMs folder…** (default `Xross/roms`) is always
  scanned; **Create system folders** makes `snes/`, `psx/`, … for all 22 systems. Auto-detected
  `ROMs/` folders and folders you add keep working.
* **`systems.json`** sets the emulator per system and lets you add folder names and file
  extensions; each system's `builtIn` block is there for reference and is rewritten on save.
  Settings saved by earlier versions are moved into these files on first start.

Xross runs in landscape and turns 180° with the device. Android 16 ignores the orientation lock
on screens 600 dp and wider.

### Metadata, art and DATs

* **DATs (offline verification):** put No-Intro, Redump or MAME DATs (`.dat`, `.xml`, or zipped)
  in `Xross/dats`, then **Settings ▸ Library ▸ DAT files ▸ Import DATs**. Matched games get
  their exact title, region, languages, revision and serial.
* **Scraping:** **Settings ▸ Library ▸ Metadata & artwork**. Hasheous (identification by hash) needs
  no account. SteamGridDB (capsules, hero banners, logos) needs a free API key. ScreenScraper
  (descriptions, box art, video snaps) needs developer credentials issued to the app, plus
  optionally your own account. Scraping runs only while Xross is in front; automatic scraping is
  off until you turn it on, and Wi-Fi only by default.
* **Per game:** **Options ▸ Details** (or → on a game; L1/R1 or ←/→ there pages to the next and
  previous game), **Options ▸ Metadata** to edit the title,
  find the right match by name, scrape again or go back to the automatic match, and
  **Options ▸ Information** for the file, hashes, DAT match and playtime.
* **RetroAchievements:** **Settings ▸ Accounts** links your account with your user name and Web
  API key (retroachievements.org ▸ Settings ▸ Keys). Your avatar sits at the far right of the
  status bar; Up from the top of a category (or a tap) expands it into a mini-HUD with your rank,
  points and latest badges, Confirm refreshes it, and Options opens the full User Card (Refresh,
  Unlink). **Games ▸ RetroAchievements** is a dashboard: profile, library completion, mastered
  games and recent unlocks. Resting on a game shows its progress ("18/42", points) above the
  button hints. **Options ▸ Achievements** on a game (or → on the Details screen) opens its badge
  grid with progress, points and unlock dates. Games are matched by ROM hash the way RetroAchievements
  hashes them (header-less for NES/SNES/PC Engine, set name for arcade); disc systems and games
  not fingerprinted yet are matched by exact title. Achievements unlocked in an emulator show as
  unlock toasts when you come back to Xross.
* **Credentials for builds:** copy [`.env.example`](.env.example) to `.env` (git-ignored). The build
  reads ScreenScraper developer credentials from it. Debug builds only also pre-fill your
  SteamGridDB key, ScreenScraper account and RetroAchievements user name and Web API key (never
  the password) on a fresh install. An English guide to ScreenScraper's API
  rules is in [`docs/SCREENSCRAPER_API.md`](docs/SCREENSCRAPER_API.md).

## Status

| Area | State |
| --- | --- |
| XMB navigation | Done. Settings, Photo, Music, Video, Games and Apps; per-category drill-down stacks, breadcrumb, spring-animated scroll, touch swipe, tap and long-press |
| Gamepad layer | Done. Vendor detection (Sony/Microsoft/Nintendo/generic) with matching glyphs, confirm/cancel swap (Auto/South/East), radial (including "Off — hardware only") and axial deadzones, anti-snapback, stick/hat auto-repeat, button-remap wizard saved per controller model, Options (△ / Y) menu, Select (or held Guide) for the media controls |
| Portable folder | Done. `Xross/` with JSON config (atomic writes, migration from earlier versions), a library database backup with restore, `.nomedia` for media and themes, and moving the folder to an SD card or USB drive. Tested on a phone's internal storage; SD cards and Android 9/10 not yet |
| Library | Done. File-path scanner over internal storage, microSD and USB-OTG; Room DB with an FTS4 title index and gamepad search; incremental rescan; hot-swap that keeps games on removed drives; cue/gdi/m3u de-duplication; favorites, Recently Played and playtime (shown under each game's title) |
| Fingerprinting | Done. CRC32/MD5/SHA-1 inside `.zip`/`.7z`, CHD header SHA-1 and No-Intro header-less hashes, in a background job that stops while a game runs |
| DAT verification | Done. No-Intro, Redump and MAME DATs (Logiqx XML or clrmamepro, plain or zipped) matched by SHA-1, MD5, CRC32 + size or arcade set name; Room schema v5 |
| Scraping and art | Done. Hasheous, SteamGridDB and ScreenScraper with per-service pacing and quotas; art cached on disk per game; box art on game rows; Details screen with paging between games; manual match and title override; hero art and video snaps behind the focused game. All three services verified live |
| Emulator dispatch | Done. RetroArch (per-platform core), DuckStation, NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS and ScummVM, chosen per platform or per game. ROMs go out as a path extra plus a `content://` URI with a read grant. Verified with stand-ins only |
| Media hub | Done. Photo viewer (zoom, pan, slideshow, wallpaper), background music player with media-session controls, video player; music pauses for games and videos |
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette; speed, ribbon lines, turbulence, thickness and color presets as live uniforms (Settings ▸ Theme ▸ Wave settings). Pauses whenever the launcher isn't in front or game art covers it |
| Theme packs | Done. `Xross/themes/<id>/` with a v2 `theme.json` (imports, palette roles, font roles, surfaces), fonts, SVG/PNG icons and sounds, reloaded live when edited; the bundled icons and sounds are the built-in Default pack |
| RetroAchievements | Done. Account linking, status bar avatar with an expanding mini-HUD, User Card, Games ▸ RetroAchievements dashboard, per-game telemetry and achievement lists, unlock toasts. Linking and lists verified live on an emulator and the Galaxy A55; the 2b.1 HUD and dashboard are unit-tested only; unlock toasts not yet seen after a real session |
| Library organization | Done. Hide apps (Options ▸ Hide, Settings ▸ Apps ▸ Hidden apps, Hide system apps), app folders (Emulators, Streaming, Tools or your own name, with emulators suggested), base ROMs folder with Create system folders, and custom Photo/Video folders that also find files MediaStore skips. Tested on the Galaxy A55 |
| Customization | 16:9 XMB game tiles (logo, wide art or a title plate); Settings ▸ Theme ▸ Profile customization for the avatar's border and glow (`profile.avatarStyle` in `settings.json`). Theme 2.0 packs with variants, overrides and context rules, palettes from game art, an hourly circadian curve, a battery tint and quick profiles (OLED True Black, Y2K Cyber-Pastel, Monochrome E-Ink); custom shaders, BGM and haptics are planned, see Roadmap §4.3 |
| Extras | Android apps (games sorted into Games ▸ Android Games), 8 color themes including Original (a color per month), busy indicator, optional button hints, Information screen, can be set as the HOME launcher, Android TV leanback entry |
| XMB fidelity | Closer. The home menu's layout, motion, navigation, status bar and options panel match the XMB; games show their own tiles, and focusing one brings up its art and video. An XMB-style typeface, Resume/Quit Game and the folder drill-down are missing |
| Not started | On-screen controller, dual screen, Xross Portal, FTPS |

### Documentation

| Document | Contents |
| --- | --- |
| [`Roadmap.md`](Roadmap.md) | **The plan**: what is done, known issues, what is left by phase (including the Phase 2b customization engine and its Theme 2.0 spec), order of work, scope decisions |
| [`docs/STATUS.md`](docs/STATUS.md) | Test suites, device runs, performance measurements, bugs found and fixed |
| [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md) | Checklist for testing on a physical handheld |
| [`docs/XMB_FIDELITY.md`](docs/XMB_FIDELITY.md) | Comparison with the real XMB, using the PS3 and PSP manuals in `assets/docs` |
| [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) | The original feasibility review behind the scope changes |
| [`docs/SCREENSCRAPER_API.md`](docs/SCREENSCRAPER_API.md) | English guide to ScreenScraper's API rules |

## Testing

```bash
./gradlew :app:testDebugUnitTest                  # JVM + Robolectric tests (live tests skip; count in docs/STATUS.md)
./gradlew :app:testDebugUnitTest --tests '*ScreenScraperLiveTest*' -PliveScrape=true   # real API calls, uses .env and localTestMedia/Roms/
./gradlew :app:testDebugUnitTest --tests '*RetroAchievementsLiveTest*' -PliveScrape=true
./gradlew :app:installDebug :emulator-stub:installRetroarchDebug
tools/device-tests/smoke.sh                       # quick on-device smoke test, ~30 s (see warning below)
tools/device-tests/test-theme.sh                  # one suite: test-{library,theme,media,input,integration}.sh
tools/device-tests/run-all.sh                     # full device regression, all 173 checks (= smoke.sh --all)
./gradlew :app:assembleProfiling                  # R8-optimized build for frame timing
```

The live tests call Hasheous, SteamGridDB and ScreenScraper for a few files in the git-ignored
`localTestMedia/Roms/` folder (`ARCADE/` and `3DS/` subfolders), and RetroAchievements. Each runs
only when its credentials are in `.env`: ScreenScraper works with the developer credentials alone
(a user account raises the quota); RetroAchievements checks `RETROACHIEVEMENT_USER` /
`RETROACHIEVEMENT_USER_PASSWORD` sign in, and with `RETROACHIEVEMENTS_WEB_API_KEY` also links the
profile and loads a game's achievements. `localTestMedia/` (ROMs, music and video for local testing) is never
committed.

> **The device tests are for a throwaway emulator.** Each one clears Xross's data and runs
> `rm -rf /sdcard/ROMs` (the media suite also uses `XrossSmoke` folders under Pictures, Music and
> Movies) to seed its own test files, and deletes them at the end. Never run them on a device that
> holds a real ROM collection.

The device tests come in three tiers that share `tools/device-tests/lib/harness.sh`: `smoke.sh`
(12 checks: boot, database, library scan, category bar, no crash) for everyday work; five suites
that each start from a reset and run on their own (`test-library.sh` 41, `test-theme.sh` 88,
`test-media.sh` 22, `test-input.sh` 11, `test-integration.sh` 9); and `run-all.sh`, which runs them
all with one passed / failed / skipped summary and exits non-zero on any failure. Details and
optional host tools: [`docs/HARDWARE_TESTING.md`](docs/HARDWARE_TESTING.md) §2a.

Measure smoothness only on the `profiling` build. The debug build is debuggable, and Compose runs
several times slower there, so it shows stutter that the optimized build doesn't have.

`tools/emulator-stub` builds test-only stand-ins for eleven emulators (RetroArch, DuckStation,
NetherSX2, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS, ScummVM) that log the
launch intent they receive. Never install them alongside real emulators.

## Look and sound

* **Default theme pack**: the bundled icons and sounds live in `app/src/main/assets/themes/default/`
  (`theme.json`, `icons/*.svg`, `sounds/*.wav`), kept apart from the app code so they can be
  swapped as a whole. The icons come from `assets/icons` (the design SVGs):
  `tools/icons/svg_icons.py` splits the icon sheets and writes the ones listed in
  `tools/icons/icon_map.txt` as pack SVGs. To change an icon, edit the map and run:

  ```bash
  python3 tools/icons/svg_icons.py list assets/icons /tmp/icons   # previews every icon id
  python3 tools/icons/svg_icons.py build assets/icons tools/icons/icon_map.txt app/src/main/assets/themes/default/icons
  ```
* **Color themes**: Lavender, Crimson, Sapphire, Emerald, Amber, Rose, Midnight, Original (a color
  per month), plus "Theme pack" when the active pack has its own palette (Settings ▸ Theme). The
  same section hides the button-hint pill.
* **Sounds** (`audio/UiSounds.kt`), from the active theme pack:

  | Event | Pack file |
  | --- | --- |
  | Every D-pad step, horizontal and vertical (cursor and category moves) | `cursor.wav` |
  | Opening a list or running an item | `confirm.wav` (Default: `cursor.wav`) |
  | Back | `back.wav` |
  | Options menu | `option.wav` (Default: `cursor.wav`) |
  | A setting saved, an action that worked | `ok.wav` |
  | Errors and actions that aren't allowed (Options on a row without a menu, Confirm on an information row, a slider at its end) | `system_ng.wav` |
  | Achievement unlocked | `trophy.wav` |
  | Startup, with the fade-in | `01. opening.wav` |
  | Game start | `gameboot.wav` |

  Settings ▸ Sound toggles effects, volume, the startup sound and the game-start sound.
* **Wave settings** (Settings ▸ Theme ▸ Wave settings): sliders for the shader uniforms, previewed
  live behind the panel and saved in `settings.json` under `display`:

  | Setting | `settings.json` key | Range | Default |
  | --- | --- | --- | --- |
  | Speed | `waveSpeed` | 0.5–2.0× | 1.0 |
  | Ribbon lines | `waveLines` | 0–12 | 5 |
  | Turbulence (amplitude) | `waveTurbulence` | 0–2× | 1.0 |
  | Ribbon thickness | `waveThickness` | 0.5–3× | 1.0 |
  | Colors | `waveTint` | `THEME`, `AURORA`, `SUNSET`, `OCEAN`, `SAKURA`, `PASTEL`, `MONO`, `CUSTOM` | `THEME` |
  | Custom gradient | `waveColors` `{top, bottom, wave}` | `#RRGGBB` | Lavender |

  **Reset to theme defaults** restores the active pack's `wave` values.
* **Focused game background**: resting on a scraped game crossfades its hero art (or a screenshot)
  in after 250 ms; its video snap fades in over it after 1.2 s, with the snap's sound ramping up
  over 300 ms while music stays ducked. The wave stops rendering while art or video covers it.

### Theme packs

Drop a folder into `Xross/themes/` and pick it with **Settings ▸ Theme ▸ Theme pack** (press
Confirm to step through packs, or Options ▸ Look for new packs after copying one in):

```
Xross/themes/<theme_id>/
├── theme.json      "schemaVersion": 2, name (required); palette, wave, typography, audio,
│                   icons, effects, surfaces (all optional)
├── surfaces.json   optional: any JSON file listed in "imports" is merged under theme.json
├── fonts/          .ttf/.otf; "typography" names one per role (display, body, mono)
├── icons/          <icon id>.svg / .png / .webp, e.g. settings.svg, games.png
└── sounds/         cursor.wav, confirm.wav, back.wav, option.wav, ok.wav, system_ng.wav,
                    trophy.wav, 01. opening.wav, gameboot.wav
```

```json
{
  "schemaVersion": 2,
  "name": "Sakura",
  "author": "you",
  "version": "1.0",
  "imports": ["surfaces.json"],
  "palette": { "canvas": "#2E0A22", "accent": "#FFE6F4", "accentSecondary": "#FFC6EA" },
  "wave": { "speed": 0.8, "lineCount": 7, "turbulence": 1.2, "ribbonThickness": 1.4, "gradient": ["#2E0A22", "#B04C8E"] },
  "typography": { "display": "fonts/Display.ttf", "body": "fonts/Rounded.ttf", "mono": "fonts/Mono.ttf" },
  "audio": { "sounds": { "cursor": "tick.wav" }, "soundGains": { "cursor": 0.6 } },
  "icons": { "album": "disc.svg" },
  "effects": { "surfaceStyle": "petal" }
}
```

`surfaces.json` (or a `surfaces` block in `theme.json`) defines how cards, panels and toasts are
drawn, layer by layer:

```json
{
  "surfaces": {
    "petal": {
      "shape": { "type": "CHAMFERED", "cutDp": [12, 0, 12, 0] },
      "fill": { "type": "LINEAR_GRADIENT", "angle": 180, "stops": [["#3A1030", 0.0, 0.85], ["#14060F", 1.0, 0.9]] },
      "sheen": { "heightPercent": 0.35, "gradient": [["#FFFFFF", 0.12], ["#FFFFFF", 0.0]] },
      "borders": [{ "type": "OUTER", "widthDp": 1.5, "brush": { "type": "SWEEP_GRADIENT", "colors": ["#FFC6EA", "#B04C8E", "#FFC6EA"] } }],
      "shadows": [{ "offsetDp": [0, 4], "blurDp": 12, "color": "#000000", "opacity": 0.4 }],
      "glow": { "color": "#FFC6EA", "radiusDp": 10, "intensity": 0.5 }
    }
  }
}
```

* **Only `schemaVersion: 2` packs are read.** A folder with an older `theme.json` is listed as
  skipped (Settings ▸ Theme ▸ Theme pack, and Options ▸ Look for new packs says why).
* **Live editing:** saving any file of the active pack (in Termux, or with `adb push`) reloads it
  within a moment, without leaving Xross. If the edit doesn't parse, Xross keeps the last good
  version and shows the file and line of the error.
* **Palette:** `canvas` is the top of the background, the last `wave.gradient` stop its bottom,
  `accentSecondary` the wave and `accent` the glow. A pack with a palette switches the color
  theme to it; its `wave` values become the wave settings.
* **Fonts:** `display` is used for the clock and category names, `mono` for the battery figure,
  `body` for everything else. Missing roles use `body`; with no `typography`, the first font in
  `fonts/` is used for all three.
* **Sounds:** a bare file name is looked up in `sounds/`, a path ("audio/tick.wav") from the pack
  root. The Theme 2.0 names `cancel`, `optionOpen`, `gameLaunch` and `achievementUnlock` are
  accepted for `back`, `option`, `gameboot` and `trophy`.
* **Surfaces:** shapes `ROUNDED` (no `radiusDp`: each card keeps its own corners), `CHAMFERED`
  (`cutDp`: top-left, top-right, bottom-right, bottom-left), `RECTANGLE`; fills and brushes
  `SOLID`, `LINEAR_GRADIENT` (CSS angles, stops `[color, position, opacity]`), `SWEEP_GRADIENT`;
  borders `INNER`, `OUTER` or a `side` (`TOP`, `LEFT`, `BOTTOM`, `RIGHT`). At most 3 borders and
  2 shadows are drawn, glows up to 24 dp. Every pack also gets the built-in `FLAT_GLASS`,
  `Y2K_GLOSSY_BEZEL`, `OLED_TRUE_BLACK` and `EINK_HIGH_CONTRAST`, and **Settings ▸ Theme ▸ Surface
  style** picks any of them over the pack's choice. `backdropBlurDp` is accepted but not drawn yet.
* **Variants:** `"variants": [{ "id": "bios-matrix", "name": "BIOS Matrix", "palette": {…},
  "effects": { "surfaceStyle": "OLED_TRUE_BLACK" } }]` adds colorways that share the pack's fonts,
  icons and sounds. **Settings ▸ Theme ▸ Theme variant** (shown only for packs that have them)
  steps through them; the choice is saved as `display.themeVariant` in `settings.json`.
* **Overrides:** `"categoryOverrides": { "MUSIC": {…} }` (`GAMES`, `MUSIC`, `PHOTO`, `VIDEO`,
  `APPS`, `SETTINGS`) applies while that category is open; `"systemOverrides": { "dreamcast": {…} }`
  (a platform id or a folder alias such as `dc`) while one of that system's games is focused, after
  the cursor rests for a quarter second. Each may set `palette` roles, `effects.surfaceStyle` and
  `cardAspectRatio` (a system's aspect shapes that system's game tiles). Precedence, lowest to
  highest: Default pack → pack → variant → category → system → your own Settings (a fixed theme
  color or a chosen surface style wins over all of them). Overrides patch a pack palette; they
  don't add one to a pack without colors. The wave crossfades between palettes.
* **Context rules:** `"rules": [{ "match": {…}, "apply": {…} }]` inline, or in a `rules.json`
  listed in `imports` (imported rules come first, then the manifest's; they add up). `match` takes
  any of `category` (`MUSIC`), `system` (a platform id or folder alias), `battery` (`<= 20`, `< 15`,
  `>= 80`, `charging`, `discharging`, `full`) and `time` (`21:00..06:00`, wrapping past midnight;
  start included, end excluded); every selector given must hold. `apply` takes `palette` roles,
  `effects.surfaceStyle`, `cardAspectRatio` and any `layout` key. Rules sit above the variant and
  the overrides, in file order, so the last matching rule wins. A rule whose selector can't be read
  is reported and dropped, never applied everywhere. Battery and time are re-checked when they
  change and once a minute, never per frame.
* **Color layers:** `palette.circadianCurve` (an hourly curve: cool cyan 06–11, azure 11–18, amber
  and rose gold 18–21, indigo 21–06, blended across each boundary), `palette.batteryTint` (accent and
  glow shift to amber, then red, below 20%; a soft green pulse while charging) and
  `effects.adaptiveGamePalette` (the focused game's hero or wheel art drives the wave, focus halos,
  avatar glow and battery outline, 250 ms after the cursor rests). A pack sets the defaults (off,
  off, on); **Settings ▸ Theme ▸ Adaptive colors / Circadian curve / Battery alert tint** override
  them (`display.adaptiveGamePalette`, `display.circadianCurve`, `display.batteryTint`; `null`
  follows the pack). Rules can switch them too.
* **Quick profiles:** **Settings ▸ Theme ▸ Quick profile** (`display.quickProfile`): *OLED True
  Black* (pure black canvas, no surface fills, glowing outlines; game art still drives the accent),
  *Y2K Cyber-Pastel* (lavender, lilac and strawberry, glossy bezels) and *Monochrome E-Ink* (black
  and white, no translucency, instant motion, every pulse and glow frozen, the wave a single still
  frame, color layers off). Precedence, lowest to highest: Default pack → pack → variant →
  overrides → rules → your theme color → quick profile → circadian curve and battery tint → game art.
* **`.xtheme` files:** a pack zipped into one file (the folder itself or its contents) installs with
  **Settings ▸ Theme ▸ Import theme (.xtheme)…**. The archive is checked before anything is
  written: no `..` or absolute paths, only `.json` / `.md` / `.txt` at the top and fonts, sounds
  (or `audio/`), icons and shaders in their folders with their own file types, no programs, at most
  50 MB unpacked, and a valid `schemaVersion: 2` `theme.json`. It unpacks into
  `Xross/themes/<id>/` (the id is made from the pack's name) and is switched to; if that id is
  installed, Xross asks whether to replace it or keep both.
* Anything a pack leaves out (a sound, an icon, the fonts) comes from the Default pack, so a pack
  can be as small as one `theme.json` and a font.
* Icon ids are the lower-case glyph names: `settings`, `games`, `apps`, `photo`, `music`, `video`,
  `album`, `artist`, `shuffle`, `search`, `platform`, `game_cart`, `game_disc`, `favorites`,
  `recent`, `handheld`, `folder`, `storage`, `controls`, `emulators`, `display`, `sound`, `system`,
  `add`, `refresh`, `home`, `info`, `download`, `network`, `users`, `trophy`. Icons are drawn as
  white silhouettes with the XMB gloss, so color doesn't matter. SVGs may use paths, rects,
  circles, ellipses and polygons with translate/scale transforms; black fills are treated as a
  backdrop and dropped. PNG/WebP icons are scaled to 256 px.
* File names are matched without regard to case, and a pack can't reach files outside its
  folder (imports included).
* **Albums**: an album found in more than one folder (the same rip as FLAC and MP3, say) shows
  once per folder, labelled by format ("… · FLAC") or, when the formats match, by folder name.
* **Media**: Settings ▸ Media sets the slideshow interval, whether music resumes after a game or
  stops, video looping, and which folders Photo and Video show (all of them by default).
* **Apps**: Options (△ / Y) on an app hides it or moves it into a folder; Settings ▸ Apps lists
  hidden apps and can hide every system app.

## Layout

```
app/src/main/java/dev/xross/launcher/
  input/    GamepadHandler, KeyMapper, StickTracker, ControllerFamily, ControllerProfiles
  storage/  Platform (PlatformRegistry), RomScanner, Fingerprinter, FileHashes, StorageVolumes,
            VolumeMonitor, XrossPaths, XrossEnvironment (the Xross/ folder), StorageAccess, SystemFolders
  config/   ConfigFiles (settings/controllers/systems JSON), AtomicFiles, ConfigWriter
  data/     Room (GameEntity, GameHashEntity, MetadataEntities, DAOs, XrossDatabase), GameRepository,
            AppRepository, DatRepository, FingerprintQueue, PlaySessionTracker, SettingsStore,
            CredentialStore, DatabaseBackupManager
  data/dat/ DatParser (Logiqx XML, clrmamepro), DatNames, DatMatcher
  scrape/   ScrapeOrchestrator, AssetCache + MediaNames (Xross/media layout), ArtLoader, RateLimiter, Http, Network,
            providers/ (Hasheous, ScreenScraper, SteamGridDB)
  launch/   EmulatorRegistry (intent matrix), GameLauncher (interface), AndroidIntentGameLauncher
  media/    MediaRepository (MediaStore + custom folders), MediaFolderScan, MusicPlayer, PlaybackService (Media3 session), SnapPlayer
  profile/  RetroAchievementsClient, ProfileRepository, AchievementsRepository, RaGames, BadgeImages
  state/    MenuTree, MediaMenus, Navigation (NavState), OptionsMenu, Search, NameEntry, AppFolders, RemapSession,
            GameInfo, GameDetails, MediaOverlayController, AchievementsView, ProfileCard, RaHub,
            AvatarEditor, WaveEditor, MiniPlayerLine, LibraryFolders, XmbViewModel
  ui/       XmbScreen, GameTile, MediaScreens, SearchOverlay, OptionsPanel, InfoPanel, DetailsScreen,
            SnapLayer, FocusBackdrop, BusyIndicator, StatusBar, AvatarHud, ProfileAvatar, UserCard,
            AchievementsScreen, RaHubScreen, UnlockToast, MiniPlayerCard, AvatarStylePanel,
            WaveSettingsPanel, Frosted, WaveBackground + WaveRenderer (GLSL), Glyphs
  theme/    XrossTheme palettes, SeasonalPalette (Original theme), WaveStyle, AvatarStyle,
            pack/ (ThemePackLoader, ThemeManifest, ThemeRuntime, SvgIcon)
  audio/    UiSounds (SoundPool effects + streamed boot cues)
app/schemas/         exported Room schemas (v1–v5) used by the migration tests
Roadmap.md           the plan (status, backlog, specs); see "Documentation" above
docs/                implementation status, hardware testing guide, XMB fidelity, feasibility review,
                     ScreenScraper API guide (English)
.env.example         keys for local service credentials (.env itself is git-ignored)
assets/docs/         PS3 and PSP instruction manuals (local reference only, git-ignored)
assets/examples/     XMB reference screenshots and design mock-ups
tools/icons/         SVG → VectorDrawable converter and icon role map
tools/emulator-stub/ test-only emulator stand-ins (Gradle module :emulator-stub)
tools/device-tests/  adb-driven device tests: quick smoke, five suites, run-all.sh, frame timing
```
