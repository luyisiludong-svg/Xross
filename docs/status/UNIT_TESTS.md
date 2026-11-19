# Unit tests

The JVM/Robolectric suite as it was catalogued on 2026-11-12 (464 tests). Later counts are in each slice's evidence; the current total is in the [status dashboard](../STATUS.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.1"></a>

## 3.1 Unit tests — 464 tests, 0 failures (2026-11-12; 578 on `feat/theme-2b4`, [§3.22](../phase-2b/2b.4/STATUS.md#s3.22)–[§3.24](../phase-2b/2b.4/STATUS.md#s3.24))
`./gradlew :app:testDebugUnitTest` (JVM + Robolectric on SDK 35): 460 run by default, 4 opt-in live
tests skip unless `-PliveScrape=true`. The 415 from Phase 2b.1 passed unchanged; the idle rest
added `InactivityTrackerTest` (3 tests: timeout, input pushing it back, wave frame strides), and
2b.2 slice 1 added 8 to `ThemePackTest` ([§3.16](../phase-2b/2b.2/STATUS.md#s3.16)). Slice 2 added `ThemeLayoutTest` (13, [§3.17](../phase-2b/2b.2/STATUS.md#s3.17)); slice 3 added
`ThemeVariantsTest` (12) and `ThemeArchiveTest` (13) and a `themeVariant` field to the
`ConfigCodecTest` round trip ([§3.18](../phase-2b/2b.2/STATUS.md#s3.18)). The table below lists the original suites; later suites
are listed after it.

| Suite | Tests | Covers |
| --- | --- | --- |
| `KeyMapperTest` | 6 | South/East swap, remap precedence, vendor families |
| `StickTrackerTest` | 4 | Radial deadzone, single press while held, snapback rejection |
| `RomScannerTest` | 6 | Title cleaning, folder aliases, extension filter, asset-folder skip, cue/m3u/gdi |
| `LibraryDiffTest` | 4 | Insert/update/delete, unmounted volume kept, removed folder purged, prefix safety |
| `NavigationTest` | 6 | Stacks, menu tree, empty library, emulator resolution, remap session, SAF paths |
| `MenuSettingsTest` | 6 | Settings sections, sound/theme rows, disc vs cartridge icons, folders |
| `EmulatorIntentTest` | 4 | Every intent recipe: component, extras, data URI, read grant |
| `SettingsStoreTest` | 2 | Every setting survives a restart; defaults |
| `FingerprinterTest` | 12 | Known CRC/MD5/SHA-1 vectors; iNES and SNES header stripping; ZIP member choice and header-only mode; 7z; CHD v5; arcade sets; skip rules; missing files |
| `FingerprintQueueTest` | 3 | Staleness and paging queries, stats, cascade delete, real queue run (Room in memory) |
| `MediaMenusTest` | 8 | Photo folders and thumbnails, album track order, artists, Now Playing row, video subtitles, empty states, six categories, EXIF rotation, pan limits |
| `HotSwapTest` | 10 | Volume merging, USB-OTG via `StorageManager`, volume roots, folder planning (remember / forget / custom), broadcast mapping and a registered receiver, offline marking |
| `PlaytimeTest` | 9 | Session rules (arm window, failed launch, process death, clock change, cap), accumulation, **migration 2→3**, formatting, row subtitles |
| `OptionsMenuTest` | 9 | Menu state (move, select, submenu, back), game menu contents, override marking, launch precedence, DAO, **migration 2→4** |
| `SearchTest` | 13 | FTS query building and sanitising, keyboard cursor across wide keys, typing and shortcuts, results focus, stale results, FTS index sync on insert/update/delete |
| `XmbViewModelTest` | 4 | Constructs the real view model over the real container: startup with and without storage access, lifecycle and volume events, navigation |

`EmulatorIntentTest` grew to 10 tests (six new recipes, platform registry) and `NavigationTest` to 7.

Added on 2026-11-11 (Milestones 1 and 2, 79 tests):

| Suite | Tests | Covers |
| --- | --- | --- |
| `AllGamesFolderTest` | 7 | All Games grouping, registry order, classic flat layout, empty library, settings row |
| `MediaShortcutTest` | 15 | Select → media controls, remapping, Guide long press (repeats, release, taps), mini-player 30 s hide |
| `XrossEnvironmentTest` | 8 | App-storage fallback, shared root once writable, `.nomedia` first, chosen volume, unwritable volume fallback |
| `AtomicFilesTest` | 5 | Atomic replace, failed write keeps the old file, leftover `.tmp`, coalesced background writes |
| `ConfigCodecTest` | 10 | Round trip of every setting through the three files, key names, clamping, broken JSON, `systems.json` overrides |
| `PortableSettingsTest` | 11 | Files written, SharedPreferences migration, `.bad` quarantine, outside edits, moving to shared storage, adopting existing config, credentials never written |
| `DatabaseBackupManagerTest` | 9 | Snapshot in `Xross/data`, WAL contents included, backup only after changes, restore offer and staged restore, invalid files |
| `GameLauncherTest` | 6 | Plans, `content://` grant inside the launcher, foreign plans refused, test doubles |
| Others extended | 8 | `StickTrackerTest` (0% deadzone), `MenuSettingsTest` (deadzone label, System rows, restore row), `SettingsStoreTest`, `XmbViewModelTest` (deadzone cycle, media button) |

Added for Milestone 3 (35 tests):

| Suite | Tests | Covers |
| --- | --- | --- |
| `AppFoldersTest` | 13 | Hiding (Apps, Android Games, system apps), Hidden apps screen, folders and their order, emulator suggestion and Group emulators, Move to folder menu, folder name tidying, the name keyboard |
| `PortableMediaTest` | 10 | FAT-safe names, clashes per system, ES-DE folder layout, reusing ES-DE media, art following the Xross folder, the one-time move from the private cache |
| `LibraryOrganizationTest` | 11 | Create system folders (22, idempotent, failures), base folder in the scan plan and Library rows, media folder filter and walk (`.nomedia`, hidden, limits), synthetic ids, custom folders in the media library, Media settings rows |
| `PortableSettingsTest` | +1 | `apps.hidden`, app folders, base ROMs folder and `photo/video.customFolders` in `settings.json` |

`ScrapeOrchestratorTest`'s two asset-cache tests were rewritten for the new layout (same count).

Added for the status bar profile and floating mini player (29 tests, 2026-11-11):

| Suite | Tests | Covers |
| --- | --- | --- |
| `RetroAchievementsClientTest` | 7 | `API_GetUserProfile` / `GetUserSummary` / `GetUserRecentAchievements` parsing (canonical name, points, RetroPoints, RetroRatio, rank, newest-first badges, relative media paths), numbers sent as strings and nulls, key only in the `y` parameter, error mapping (401/403, empty answer, 404, 429, HTML, offline) with no key in messages, images fetched only from retroachievements.org over HTTPS |
| `ProfileRepositoryTest` | 8 | The key stays in `CredentialStore`: absent from `settings.json`, every file in the Xross folder and the old settings preferences; profile cache and `media/avatars/<user>.png` written; offline start served from disk with no request; a failed refresh or avatar download keeps the cache; no avatar → placeholder; refused key keeps the linked account; unlink removes key, cache and avatar; staleness; safe file names |
| `MiniPlayerCardTest` | 8 | Hidden with nothing loaded, shown while playing; paused: shown at 29.999 s, hidden at 30 s; resume and track change bring it back and restart the countdown; repeated identical updates don't extend it; covering hides it without resetting the timer; stop hides it at once |
| `ProfileCardTest` | 6 | User Card buttons (linked / not linked), movement, Close, two-press Unlink; Settings ▸ Accounts rows; Up from the top of a category focuses the avatar, Confirm opens the card, Back / tap closes it; Up inside a list still moves the cursor |

`MenuSettingsTest`'s list of Settings sections now includes Accounts (same count).

Added for Phase 2b.1 (20 tests, 2026-11-11; `HtmlTextTest`, 2 tests, came with the [§3.14](../phase-2/STATUS.md#s3.14) fixes):

| Suite | Tests | Covers |
| --- | --- | --- |
| `Phase2bFeaturesTest` | 20 | `profile.avatarStyle` round trip, defaults when missing, clamping and a bad color falling back alone, no credentials in the block; avatar editor sliders, presets, glow toggle, reset and input mapping; Profile customization opening from Theme and saving at once; playtime subtitle boundaries and rows showing playtime instead of size; Details pager ends, single-item and foreign lists, L1/R1 and D-pad paging on the real view model; telemetry only for recognized games with achievements and following the focused row; library summary (each set once, mastered games); RetroAchievements entry among the Games collections; dashboard scrolling, closing and offline placeholder |
| Others updated | — | `AllGamesFolderTest`, `NavigationTest`, `MenuSettingsTest` (new list order and Theme rows), `PlaytimeTest` (subtitles), `ProfileCardTest` (avatar HUD flow), `ConfigCodecTest`, `WaveSettingsTest` |

Added for 0.2.1.0 (31 tests, 2026-11-19, `feat/0.2.1.0`; 950 in the full suite before the last fixes, all passing after them):

| Suite | Tests | Covers |
| --- | --- | --- |
| `FileFormatSwitchTest` | 5 | The extension in game rows by default and gone with `display.showFileFormat` off (rest of the subtitle unchanged, search rows too); the Menu and profile row; an old `settings.json` without the key reads as shown |
| `TileGlowTest` | 7 | `components.tileGlow` (and `glow`) from a pack and a variant; a non-boolean value reported and ignored without failing the pack; the user's override over the pack; the Components row cycling Theme pack · On · Off; `display.components` round trip |
| `SystemLogosTest` | 6 | `icons/systems/` by platform id and folder alias (exact id wins), listed in the pack's files; unknown names, wrong types and unreadable files reported; Default pack fallback; system folders ask for `NodeIcon.System` unless `display.showSystemLogos` is off |
| `Armsx3Test` | 6 | Disc-image form (no action, `path` extra, no URI) for both packages; `.ps3` installed-game form with the serial's `dev_hdd0` path; a `.ps3` without a serial refused; the PS3 platform's folders, file types and place after PS2; ScreenScraper 59, PS3 DATs, no RetroAchievements console |
| `AppSortingTest` | 7 | Declared category, then the known-app table; folder → own move → switch precedence; Video/Music/Photo end with an Apps folder only while it holds an app; Apps keeps the rest; hidden apps stay hidden; Move to Apps / Move to Video, Music or Photo / Sort automatically; `apps.autoSort` and `apps.homes` round trip |
| Others updated | — | `ConfigCodecTest` (new fields), `ComponentsTest` and `TelemetryCardTest` (the Game tile glow row), `ThemeExportTest` (a current-look export with the glow off), `WaveSettingsTest` (Menu and profile rows), `LibraryOrganizationTest` (23 systems), `DatParserTest` (PS3 DATs), `AppFoldersTest` (app options) |
