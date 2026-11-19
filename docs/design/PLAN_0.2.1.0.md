# 0.2.1.0 — implementation plan

The candidate features from `dev_notes/19-11-2026.md`, checked against the code as of
`feat/armsx2` (2026-11-19): what each one needs, where it goes, how it is tested, and what could go
wrong. Nothing here is built yet except where marked ✅.

Sizes: **XS** under an hour · **S** half a day · **M** one to two days · **L** three days or more.

| # | Feature | Size | Status |
| --- | --- | --- | --- |
| 1 | Hide the file format in game rows | XS | Planned |
| 2 | Turn off the game tile glow | S | Planned |
| 3 | Console logos on system folders | S–M | Planned |
| 4a | ARMSX2 recipe (PS2) | S | ✅ `45d0ecc`, stub-verified only |
| 4b | ARMSX3 recipe and a PS3 platform | M | Planned |
| 5 | Custom launch recipes and custom systems | M–L | Planned (candidate for 0.2.2.0) |
| 6 | Sort Android apps into Video, Music and Photo | M | Planned |
| 7 | Choose a game's media from the scraper's offers | L | Planned (candidate for 0.2.2.0) |
| 8 | New theme packs to show off the above | — | Alongside 2 and 3 |

**Order:** 1–3 land together as one PR (small, settings and theme only). 4b next, then 6, then 5
and 7. If the release has to shrink, 7 moves to 0.2.2.0 before 5 does: custom recipes also answer
every future "please add emulator X" request.

---

## 1. Hide the file format in game rows (XS)

**Today.** `MenuTree.gameNode` ([MenuTree.kt](../../app/src/main/java/dev/xross/launcher/state/MenuTree.kt))
builds the subtitle from offline state, ★, platform, region, the upper-cased extension and
playtime. The extension is already skipped when it only repeats the platform ("GBA · GBA").

**Change.**
- `XrossSettings.showFileFormat: Boolean = true`, stored as `display.showFileFormat` in
  `settings.json` (`ConfigFiles.SettingsFile.Display` and `ConfigCodec`).
- In `gameNode`, add `ext` only when `s.settings.showFileFormat`.
- A switch in Settings ▸ Display: "File format in game rows".
- The Information screen keeps the full file name either way.

**Tests.** A `MenuTree` unit test: the subtitle with and without the switch; a `ConfigCodec`
round trip, and an old `settings.json` without the key reading as `true`.

## 2. Turn off the game tile glow (S)

**Today.** The focused game tile is framed by `drawPlateFrame`
([FocusHalo.kt](../../app/src/main/java/dev/xross/launcher/ui/FocusHalo.kt)) in the pack's
`FocusFrameStyle` (`BREATHING_HALO`, `STATIC_NEON`, `HARD_SHADOW`, `GOLD_BEZEL`;
[Cosmetics.kt](../../theme/engine/src/main/kotlin/dev/xross/launcher/theme/pack/Cosmetics.kt)).
There is no way to have no frame.

**Change.**
- Theme side: a `components.tileGlow: Boolean = true` in `theme.json`, rather than a `NONE` frame
  style, so a pack can keep the gold bezel on RetroAchievements badges but drop the glow on game
  tiles. Read in `ThemePackLoader`, exported by `LookExporter`, documented in
  `THEME_SPECIFICATION.md`.
- User side: an override in the user look (`UserLook` / Look settings): Follow theme · On · Off.
- `GameTile` skips `drawPlateFrame` when the resolved value is off. The focus is still shown by the
  tile's scale and the title plate, so there is no accessibility loss.

**Tests.** Loader test for the new key (present, missing, wrong type); `LookExporter` round trip;
a Compose screenshot or semantics check that the frame isn't drawn. Device check on the A55: no
change in frame time with the glow on (the default) and a small drop with it off.

## 3. Console logos on system folders (S–M)

**Today.** `platformFolders()` gives every system folder the same `GlyphKey.PLATFORM` icon.

**Change.**
- Packs may ship `icons/systems/<platformId>.svg|png|webp` (`snes.svg`, `ps2.png`…). The loader
  already decodes pack icons; it gains a `systemIcons: Map<String, ThemeIcon>` with fallback to
  the Default pack, then to the generic glyph.
- A new `NodeIcon.System(platformId, fallback)` for system folders, drawn from the pack's icon
  when there is one and as the `GlyphKey.PLATFORM` glyph otherwise.
- `display.showSystemLogos: Boolean = true` in `settings.json`, with a switch next to the one in 1.
- **Artwork:** real console logos are trademarks, so Xross ships no official logos. The Default
  pack gets simple drawn silhouettes (or none, and keeps the glyph); third-party packs can bring
  real logos. Say so in `THEME_SPECIFICATION.md`.

**Tests.** Loader test (pack icon, Default fallback, missing); MenuTree test that the switch picks
the glyph. Device check that 22 SVGs don't slow the first open of Games.

## 4a. ARMSX2 (S) — ✅ done

Committed in `45d0ecc` on `feat/armsx2`. `ACTION_VIEW` on `com.armsx2.Main` with the granted
`content://` URI and a `path` extra, from ARMSX2's own `MainActivityRuntime.extractLaunchUri`.
Packages `com.armsx2` and `com.armsx2.nightly`, both in `<queries>`. A `.cue` is handed over as
its first track (`CueSheet`), because ARMSX2 can't reach a sheet's tracks through a single-file
URI.

**Still to do:** on the A55, boot an `.iso`, a `.chd` and a cue+bin game, once with ARMSX2 closed
and once with it already open (that path goes through `onNewIntent`). Then drop the 🧪 in the
Roadmap.

## 4b. ARMSX3 and a PS3 platform (M)

**Source.** ES-DE's Android rules: package `com.armsx3` or `com.armsx3.play`, activity
`com.armsx2.Main` (ARMSX3 is built from ARMSX2's code). Two launch forms:

| Form | Intent |
| --- | --- |
| Disc image | No action; extra `path` = the ROM's file path |
| Installed game | `ACTION_VIEW`; extra `path` = `<external data>/Android/data/<pkg>/files/config/dev_hdd0/game/<serial>`, where the serial is the contents of a `.ps3` file |

**Change.**
- `Platform("ps3", "PlayStation 3", "PS3", folders ps3/playstation3/sonyplaystation3, extensions
  chd/iso/ps3, isDiscBased = true)` in `Platform.kt`. Not scanned: folder-format game dumps.
- One `armsx3` recipe that switches on the file type: `.ps3` → read the serial (first non-blank
  line, as the ScummVM recipe reads its target id) and build the installed-game path; anything
  else → the disc-image form. Users don't have to choose between two entries.
- `<queries>` for both packages; a stub flavor; `HARDWARE_TESTING.md` row.
- RetroAchievements and the scrapers need the new platform id mapped (ScreenScraper `systemeid`,
  Hasheous platform); check `RaConsoles` has no PS3 entry and leave it out.
- **Storage:** `%ROM%` is a plain path, so ARMSX3 needs its own all-files access; the Xross read
  grant doesn't help. A failed launch should say "grant ARMSX3 storage access".
- aPS3e and EmuCoreC are in the same ES-DE entry; add them only after a device test.

**Tests.** Intent tests for both forms; `PlatformRegistry` test for the folder names and file
types. Device check needs a PS3-capable phone; the A55 may be too slow to play but is enough to
confirm the game starts.

## 5. Custom launch recipes and custom systems (M–L)

**Goal.** Let users add an emulator Xross doesn't know without a new release, and paste what ES-DE
already documents.

**File.** `Xross/config/emulators.json`, beside `settings.json`, `controllers.json` and
`systems.json`:

```json
{
  "version": 1,
  "emulators": {
    "citron": {
      "name": "Citron",
      "packages": ["org.citron.citron_emu", "org.citron.citron_emu.ea"],
      "activity": "org.citron.citron_emu.activities.EmulationActivity",
      "systems": ["switch"],
      "command": "%ACTION%=android.nfc.action.TECH_DISCOVERED %DATA%=%ROMPROVIDER%"
    }
  }
}
```

**The `command` string** is ES-DE's syntax, minus the `%EMULATOR_X%` prefix (that is `packages` +
`activity`):

| Tag | Xross meaning |
| --- | --- |
| `%ACTION%=x`, `%CATEGORY%=x` | Intent action, category |
| `%DATA%=v` | Intent data |
| `%EXTRA_k%=v`, `%EXTRABOOL_k%=v`, `%EXTRAINTEGER_k%=v`, `%EXTRAARRAY_k%=a,b` | Typed extras |
| `%ACTIVITY_CLEAR_TASK%`, `%ACTIVITY_CLEAR_TOP%` | Intent flags |
| `%ROM%` | Absolute path |
| `%ROMPROVIDER%`, `%ROMSAF%` | The Xross FileProvider URI with a read grant (Xross has no SAF tree, so both map here, as the built-in recipes already do) |
| `%BASENAME%` | File name without the extension |
| `%INJECT%=%BASENAME%.ext` | The contents of that file beside the ROM (serials, app ids) |
| `%EXTERNALDATA%`, `%ANDROIDPACKAGE%` | `/storage/emulated/0`, the installed package |

Anything else is an error for that entry, not for the file.

**Custom systems.** Most of ES-DE's long tail is for systems Xross doesn't have (Vita, Wii U,
Xbox 360, Windows, Steam). `systems.json` gains user-defined systems: `name`, `shortName`,
`folders`, `extensions`, `emulators`. They scan, launch and scrape by name like built-in ones but
get no RetroAchievements or DAT matching.

**Code.**
- `ConfigFiles`: `EmulatorsFile` and the new `SystemsFile.System` fields; `ConfigCodec` read and
  write, keeping the file as the user wrote it.
- `launch/EsdeCommand.kt`: a pure parser from the command string to a recipe; `EmulatorDef`
  instances built from it.
- `EmulatorRegistry` becomes built-ins plus a user list from config; a built-in id wins a clash.
- `PlatformRegistry` likewise for custom systems; `GameLauncher.resolveOption`, the Settings ▸
  Emulators picker and `AppFolders.emulatorPackages` see the merged lists.
- `<queries>` can't list packages chosen at run time. Add an intent-based query
  (`<intent><action android:name="android.intent.action.VIEW" /><data android:mimeType="*/*" /></intent>`
  and `MAIN`/`LAUNCHER`) so the emulators are visible; check this doesn't need
  `QUERY_ALL_PACKAGES`, which Play restricts.
- Config read problems are only logged today. Collect the problems for each entry (unknown tag,
  missing package, unknown system) and list them in Settings ▸ Emulators so a typo is visible on
  the device; a bad entry is skipped, the rest still load.
- A commented sample (`emulators.example.json`) with a few ES-DE entries, untested, clearly marked.

**Watch for.** Package spoofing: some ES-DE rules use other apps' package names
(`com.antutu.ABenchMark`, `com.miHoYo.Yuanshen`, `com.tencent.ig`) so the build gets the
performance boost phone makers give those apps. Match a custom recipe on package **and** activity
(check the activity exists with `PackageManager.getActivityInfo`), and never add such packages to
the Emulators folder suggestion.

**Tests.** `EsdeCommandTest` over every tag, bad input and the ES-DE lines quoted in this plan;
`ConfigCodec` round trip; registry merge and clash; a spoofed package without the activity is not
"installed". Device check with one real emulator added only through `emulators.json`.

## 6. Sort Android apps into Video, Music and Photo (M)

**Today.** Non-game apps all go to Apps; the user can move them into folders there (`AppFolders`).
`AppRepository` already reads `ApplicationInfo.category` to tell games apart.

**Change.**
- `AppEntry.category: Int` from `ApplicationInfo.category` (`CATEGORY_VIDEO`, `CATEGORY_AUDIO`,
  `CATEGORY_IMAGE`, …). Many apps don't declare one, so a small built-in package table fills gaps
  (YouTube, Netflix, Twitch, Spotify, YouTube Music, Google Photos…), kept in `:core:model` next
  to `FolderNames`.
- A pure `AppSorting.home(app, settings): Home` → `VIDEO`, `MUSIC`, `PHOTO` or `APPS`.
  Precedence: a folder the user picked in Apps → hidden → the sorting switch off → category or
  table → Apps.
- Video, Music and Photo each get an "Apps" folder row at the end of their root while it holds an
  app. Options on an app there offer "Move to Apps", which records a manual choice.
- `apps.autoSort: Boolean = true` in `settings.json`, with a switch in Settings ▸ Apps.
- The 2026-11-13 idea of a "Local media" folder per category is separate and not part of this.

**Tests.** `AppSortingTest` for the precedence; `MenuTree` tests that the folder appears only when
it holds an app and that hidden apps stay hidden. Device check on the A55, which has real
streaming apps installed.

## 7. Choose a game's media from the scraper's offers (L)

**Today.** `ScreenScraperProvider.parseMedia` keeps the best item per art kind (by type order, then
region), and `SteamGridDbProvider.image` keeps the first grid, hero and logo. `ScrapeOrchestrator.downloadArt`
downloads the first that works into `AssetCache`. Every other offer is thrown away, and a rescrape
with replace overwrites what is there.

**Change.**
- **Providers list everything.** `ScrapeProvider.mediaOffers(request, kind): List<MediaOffer>`
  (url, kind, provider, region, width × height, a label such as "Box 3D · EU"). ScreenScraper reuses
  the `jeuInfos` response and stops dropping items; SteamGridDB lists `/grids|heroes|logos/game/{id}`
  in full. Offers are fetched when the picker opens, not stored, so there is no new table for them.
  The cost is quota: one request per provider per opening, cached in memory for the session.
- **Pinned media.** `game_metadata.pinnedArt` (a comma-separated list of `ArtKind` names), Room
  schema v5 → v6 with a migration and exported schema. `downloadArt` skips pinned kinds even with
  replace. Scrape ▸ Rescrape keeps pins; a new "Reset media" clears them.
- **Picker UI.** Options ▸ Edit media on a game → a list of art kinds with the current one shown
  → a gamepad grid of thumbnails (Left/Right/Up/Down, Confirm picks, Back leaves). Thumbnails load
  through `ArtLoader` at small size from the offer URL into a temporary cache. Rows at the end:
  "Use a file…" (an image already on the device) and "Remove".
- **Picking** downloads into the existing `AssetCache` slot with `adopt` (so ES-DE file names and
  the `artVersion` refresh keep working), pins the kind, and the tile, Details and the palette
  update at once.
- Video snaps are listed only when Scrape videos is on, and play muted in the picker preview.

**Tests.** Parser tests with recorded ScreenScraper and SteamGridDB responses (every offer kept,
labels right); migration test v5 → v6; orchestrator test that pinned kinds survive a replace
scrape; picker state tests (navigation, pick, remove). Device check: quota counter moves by the
expected amount, the grid scrolls at 60 fps on the A55 with 30+ thumbnails.

**Watch for.** ScreenScraper's daily quota and thread limit, already handled by `RateLimiter`;
large SteamGridDB images (use their thumbnail URLs for the grid, the full URL only on pick).

## 8. Theme packs

Build the next showcase packs while 2 and 3 are fresh, so each new option has a pack that uses it:
one with no tile glow and a flat look, one with system logos. Ideas are in
`dev_notes/themePackIdeas.md`.

---

## Release checklist

- `./gradlew check assembleDebug assembleProfiling` passes; unit test count recorded in
  `docs/status/UNIT_TESTS.md`.
- Device pass on the A55 from `docs/HARDWARE_TESTING.md`, including every new launch recipe with
  the real emulator.
- Frame timing on the profiling build for the game row with and without the glow and with system
  logos (see `docs/status/PERFORMANCE.md`).
- `CHANGELOG.md`, `Roadmap.md`, `docs/USER_GUIDE.md` and `THEME_SPECIFICATION.md` updated; version
  `0.2.1.0`.
