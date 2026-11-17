# Phase 2 (Beta) — evidence

Fingerprinting, the media hub, the portable `Xross/` folder, library organization and the live-service runs. Overview: [Phase 2](README.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.4"></a>

## 3.4 Fingerprinting (Phase 2, step 1)

How each file type is identified (`storage/Fingerprinter.kt`):

| Input | Result |
| --- | --- |
| Plain ROM ≤ 256 MB | CRC32 + MD5 + SHA-1 in one pass; header-less set too for iNES/FDS/SNES copier headers |
| `.zip` / `.7z` | Picks the member with a platform ROM extension; CRC32 from the archive header, MD5/SHA-1 by decompressing members ≤ 256 MB (7z capped at 96 MB of LZMA memory) |
| Arcade `.zip` | Recorded as a set, identified by name |
| `.chd` | SHA-1 from the v4/v5 header (no decompression) |
| ISO/BIN over 256 MB | Skipped unless Settings ▸ Library ▸ Fingerprint large disc images is on |
| `.rvz` `.cso` `.gcz` `.wbfs` `.pbp` … | Skipped: compressed containers don't match DATs |
| `.cue` `.gdi` `.m3u` | Skipped until per-track hashing exists |
| Missing file (card removed) | Not recorded; retried next session |

The queue (`data/FingerprintQueue.kt`) runs on one background-priority thread **only while Xross
is in front**. It stops on pause and resumes on return, and it re-hashes a game only when its
size or modification time changes. Results live in the `game_hashes` table (DB v2, automatic
migration from v1).

Measured on the emulator (optimized build, upgraded in place from DB v1 without data loss):

| Check | Result |
| --- | --- |
| 3,011 small ROMs (mixed library) | 4.7 s; summary: 3,006 by content, 1 arcade set, 4 skipped, each correct |
| 2,000 more ROMs added + rescan | Scan 0.18 s, fingerprinting 3.8 s |
| Scrolling a 3,000-game list while hashing | 0 slow UI-thread frames, p99 18 ms |
| Launching a game mid-job (three 1 GB ISOs) | Queue stopped immediately, 0% CPU while the emulator ran; resumed and finished on return |
| 300 MB ISO with large-image hashing on | 0.95 s on emulator storage; expect several seconds per GB on SD cards |

Observed: a rescan right after bulk-copying files reported 22 files as "updated" (their
modification times settled after the first scan), so those were re-hashed once. Harmless, but
worth watching on real SD cards.

<a id="s3.5"></a>

## 3.5 Media hub (Phase 2)

| Section | What's there | Controls |
| --- | --- | --- |
| Photo | All photos + one entry per folder, newest first, with thumbnails | ←/→ photo · L1/R1 zoom · ↑↓←→ pan when zoomed · Confirm info · Start slideshow · Options set as wallpaper · Back |
| Music | Now playing, Shuffle all, All songs, Albums, Artists, with embedded album art | Confirm plays from that song · Now Playing: Confirm play/pause, ←/→ track, L1/R1 seek, Options shuffle, ↑ repeat |
| Video | All videos + folders, with thumbnails, duration and resolution | Confirm/Start play/pause · ←/→ 10 s · L1/R1 60 s · ↑/↓ previous/next · Options loop · Back |

Measured on the emulator (optimized build):

| Check | Result |
| --- | --- |
| Music service | Foreground `mediaPlayback` service with a media notification; MediaSession reports PLAYING/PAUSED correctly |
| Music during a game / video | Paused when the game or video starts, resumed when Xross is back in front |
| Scrolling 300 photo thumbnails | 0 slow UI-thread frames, 0 slow bitmap uploads, p99 17 ms |
| Memory | 58 MB PSS browsing photos; 60 MB with the music service while a game runs |
| Wave background | Hidden (GL loop stopped) under the full-screen photo viewer and video player |

Fixed during this pass: panning could expose the black border of pillarboxed photos (limits now
follow the fitted photo size and EXIF rotation); the XMB showed through Now Playing (the menu
now fades out under media screens); the Android status bar stayed visible after returning from
another app (immersive mode is re-applied on focus).

<a id="s3.9"></a>

## 3.9 Portable `Xross/` folder (Milestone 2)

**Layout and fallback.** `XrossEnvironment` resolves the root: `Xross/` on primary storage, or on
the drive chosen in Settings ▸ System ▸ Xross folder, once Xross can write shared storage (All
files access on Android 11+, `WRITE_EXTERNAL_STORAGE` on 9–10). Before that, and whenever the
chosen drive fails, it uses `files/Xross` in app storage. Every root is bootstrapped with
`.nomedia` in `media/` and `themes/` before the other folders.

**JSON configuration loader.** Measured with Robolectric on the development host (not yet on a
device):

| Item | Value |
| --- | --- |
| File sizes with defaults | `settings.json` 793 B, `controllers.json` 78 B, `systems.json` 11 KB (22 systems, including the reference `builtIn` blocks) |
| Load time | ~0.75 ms per full load of all three files on the JVM host; expect several times more on a Snapdragon 665-class handheld |
| When it runs | Once, synchronously, when the settings store is first used at startup; again off the main thread on each resume (`reloadIfChanged`, which only parses files whose text changed) |
| Memory | One `XrossSettings` object plus the last-seen text of each file (~12 KB) |
| Writes | Off the main thread on one background thread; repeated changes to a file coalesce to the newest content; `.tmp` + `fsync` + rename |
| Dependency | kotlinx-serialization-json 1.8.1 plus the Kotlin serialization compiler plugin (serialization-core was already on the classpath through other libraries) |

**Database backup (works on Android 9 and 10).** `VACUUM INTO` needs SQLite 3.27, which Android
only ships from API 30, so the backup is: `PRAGMA wal_checkpoint(TRUNCATE)` (retried while
readers block it), then `BEGIN IMMEDIATE` to hold writers off, a check that the WAL is empty
(otherwise repeat), a stream copy of the main file, its header switched from WAL to rollback
journal mode so it stands alone, a read-back check (`user_version` and game count), and a rename
over `Xross/data/xross.db`. It runs 4 s after Xross leaves the screen when a library table
changed, or from Settings ▸ System ▸ Back up library now. Restores are copied next to the live
database and applied on the next start, before Room opens it; the live database is never
replaced while open. Verified with Robolectric only.

<a id="s3.10"></a>

## 3.10 Library organization (Milestone 3)

| Feature | How it works |
| --- | --- |
| Hidden apps | Package names in `settings.json` `apps.hidden`; filtered from Apps and Games ▸ Android Games. "Hide system apps" uses `ApplicationInfo.FLAG_SYSTEM` (updated system apps included) |
| App folders | `apps.folders` maps package → folder. Presets need no typing; other names come from the gamepad keyboard (capitals only, tidied to title case except short acronyms) |
| Base ROMs folder | `library.baseRomsFolder`; empty means `roms/` in the Xross folder. Scanned first; auto-detected and added folders unchanged. Create system folders makes `<base>/<id>/` for the 22 systems and never touches existing files |
| Custom Photo/Video folders | `photo.customFolders` / `video.customFolders`. MediaStore queried with `_data LIKE '<folder>/%'`; a breadth-first walk (8 levels, 20,000 files, no symlinks) adds files MediaStore skips. Walked files get negative ids and FileProvider URIs; their thumbnails are decoded here (EXIF-rotated photos, a video frame) |
| Art in `Xross/media` | `<kind>/<system>/<rom stem>.<ext>`. Stems lose `\ / : * ? " < > \|` and control characters, trailing dots and spaces, reserved device names are prefixed, and length is capped at 200. On a clash within a system the oldest game keeps the plain name and the others get `[<first 8 of UUID>]`. Lookups try a few names × 4 extensions (a handful of `stat` calls). Prune deletes only art of games that left the library |
| Moving the old cache | After the library first loads, `noBackupFilesDir/assets/<uuid>/…` is copied file by file into the new layout in the background; the old copy is still found until moved; art already in place wins |

<a id="s3.11"></a>

## 3.11 Device run: Milestones 1–3 on the Galaxy A55 (2026-11-11)

Samsung Galaxy A55 (SM-A556B, Android 16, 120 Hz), internal storage only, no controller, no
emulators installed. The new build was installed **over the existing install** (12 arcade sets,
2 3DS images, scraped art, settings from the earlier pass), so the upgrade paths ran on real data.
Driven with adb key events (the same codes a controller sends) and checked through the
accessibility tree, the files on the device and screenshots. `smoke.sh` was **not** run: it
deletes `/sdcard/ROMs` and clears the app's data, and this phone holds the earlier test library.

| Area | Check | Result |
| --- | --- | --- |
| Upgrade | SharedPreferences → `config/settings.json`, `controllers.json`, `systems.json` | ✅ Non-default values carried over (Original theme, 45% deadzone, 100% effects volume, remembered `ROMs` folder); old preferences cleared after the files were written |
| Upgrade | Private art cache → `Xross/media` | ✅ All 41 files moved to `capsules/`, `heroes/`, `wheels/` under `arcade/` and `n3ds/`, named after the ROMs; old cache removed; box art, hero banner and logo still shown on rows and in Details |
| Xross folder | Layout and `.nomedia` | ✅ `config/ data/ dats/ media/ roms/ themes/`; `.nomedia` in `media/` and `themes/`; 0 MediaStore images under `Xross/` |
| Credentials | Not in config files | ✅ No account, password or key in any of the three files |
| Backup | On leaving Xross / Back up library now | ✅ `data/xross.db` appeared seconds after Home; rollback-journal header, `integrity_check` ok, schema v5, 14 games and 14 metadata rows, no `-wal`/`-shm` beside it; "Last backup" time updates |
| Restore | Settings ▸ System ▸ Restore library backup… | ✅ Confirm menu, then a new process; staged copy applied before Room opened it; library and favorite intact |
| Outside edits | `photo.customFolders` edited with adb, then Xross resumed | ✅ Picked up on resume |
| Custom Photo folder | A folder with `.nomedia` (unknown to MediaStore) | ✅ Photo showed only that folder (1 photo); the viewer opened it at full size through its FileProvider URI; "Show all photos again" restored all 2,138 photos |
| Base ROMs folder | Create system folders | ✅ 22 folders in `Xross/roms`; a second run reports them all present; a ROM dropped into `gba/` is picked up by Rescan (+1) and dropped when removed (−1) |
| Apps | Hide, Hidden apps, Hide system apps | ✅ Hidden app gone from Apps and stored in `apps.hidden`; shown again from Hidden apps; Hide system apps hides 22 preinstalled apps and is reflected in the file |
| Apps | Folders | ✅ Move to folder ▸ Tools puts a Tools folder at the top of Apps; New folder… opens the gamepad keyboard, "MY GAMES" is saved as "My Games"; Remove all app folders clears them |
| All Games | Platform folders setting | ✅ Classic layout lists Nintendo 3DS and Arcade at the top of Games; switching back restores All Games |
| Deadzone | Off — hardware only | ✅ 45 → 55 → Off (`stickDeadzone: 0.0`) → … → 45% |
| Media shortcut | Select, Guide | ✅ With nothing playing: "Nothing is playing" toast. With music: Select opens and closes Now Playing from Games; a held Guide (`--longpress KEYCODE_BUTTON_MODE`) opens it; a Guide tap does nothing |
| Mini-player | Paused line | ✅ Present ~20 s after pausing, gone at ~34 s, back with ♪ on resume |
| Launching | No emulator installed | ✅ "No emulator for 3DS — install Azahar"; Xross stays in front |
| Search | Typing "ZEL" | ✅ 1 game, The Legend of Zelda Ocarina of Time 3D |

Performance (`profiling` build, installed over the debug build with data kept):

| Measure | Result |
| --- | --- |
| Cold start (`am start -W`) | 1,050 ms on the first start after install, then 457 ms and 377 ms |
| Frames: Apps list (60 steps down and up), arcade list with art, category switches | 686 frames, 1.46% janky, p50 13 ms, p90/p95 18 ms, p99 23 ms, 0 missed vsync, 0 slow bitmap uploads |
| Memory in front | 211 MB PSS |
| Memory in the background (8 s after Home) | 122 MB PSS, 0% CPU (target ≤ 150 MB). Higher than the 83 MB measured earlier, likely because the music session and a 72-song queue had been loaded during this run |

Not covered: moving the Xross folder to an SD card or USB drive (no removable drive), a
controller, launching a real emulator, Android 9/10, the restore offer on a genuinely clean
install, video folders, and picking folders through the system folder picker (folders were set
by editing `settings.json`).

<a id="s3.12"></a>

## 3.12 Status bar profile and floating mini player (2026-11-11)

Unit-tested ([§3.1](../status/UNIT_TESTS.md#s3.1)), then run on the **Galaxy A55** (debug build installed over the existing data,
2026-11-11):

| Check | Result |
| --- | --- |
| Status bar avatar | ✅ Silhouette placeholder before the clock; Up from Search focuses it (white ring); Down returns |
| User Card (not linked) | ✅ Opens on Confirm: "Guest", "No RetroAchievements account linked", Link account… / Close, Enter/Esc hints |
| Link dialog | ✅ Opens from the card. In landscape the keyboard used to take the whole screen and hide the dialog; the fields now ask for no full-screen editor and the dialog scrolls, so the key field comes into view above the keyboard |
| Mini player card | ✅ After Shuffle all: album art, bold title, artist cut with "…", ▶ glyph, above the hint pill; stays when switching Music → Games |
| Long press | ✅ Pauses (glyph turns ❚❚) |
| 30 s hide | ✅ Still shown 25 s after pausing, gone at 36 s; back at once on resume (media play key) |
| Crashes | None in logcat |

Not covered on the device: linking a real account (no test account; the API host for images,
`media.retroachievements.org`, is therefore unconfirmed), the avatar download, the User Card with
profile data, tapping the card to open Now Playing, and a long title scrolling.

| Feature | How it works |
| --- | --- |
| Linking | Settings ▸ Accounts ▸ RetroAchievements account… opens one system dialog (user name, masked Web API key). The pair is checked with `API_GetUserProfile` first and saved only if it works, so a typo never replaces a working account. An empty key keeps the saved one for the same user; an empty name unlinks |
| Secrets | The key is stored only in `CredentialStore` (`xross_credentials`, app-private, excluded from cloud backup and device transfer), next to the scraper accounts. It is sent only as the `y` parameter to `retroachievements.org`, never logged, never put in an error message. Not encrypted at rest yet |
| Profile cache | `Xross/data/retroachievements.json`: user name, avatar path, points, RetroPoints, RetroRatio, rank, recent unlocks, fetch time. Read at startup, so the profile shows offline; refreshed in the background when older than 6 h (on start and on resume), and from the User Card or Settings |
| Rank and unlocks | Rank comes from `API_GetUserSummary` (`g=0&a=0`) and the last 30 days of unlocks from `API_GetUserRecentAchievements`; if either fails the profile still updates without it |
| Avatar | `Xross/media/avatars/<user>.png`, downloaded to a `.part` file and swapped in atomically, only from `*.retroachievements.org` over HTTPS. A failed download keeps the old picture; with none the silhouette vector shows. "Clear downloaded artwork" leaves avatars alone |
| Status bar | 32 dp round avatar before the clock and battery. Up from the first row of a category root moves the focus onto it (ring and breathing glow); Confirm or △ opens the User Card, Down or Back returns, Left/Right switch category. Tapping it opens the card too |
| User Card | Avatar, name, rank "#4,616 of 45,654", points, RetroPoints, RetroRatio, softcore points, motto, up to four recent unlocks, last update. Buttons: Refresh · Unlink (press twice) · Close, or Link account… · Close when nothing is linked |
| Mini player card | `MiniPlayerCard` at the root of `XmbScreen` (outside the menu fade), bottom-right, 16 dp from the edges and 8 dp above the measured button-hint row. Album art is the track's embedded cover through MediaStore (the same source Now Playing uses), with a music-note fallback. Hidden under full-screen media, Search, Details, Information, Options, the name keyboard, the User Card, remapping and the game start. `MiniPlayerVisibility` runs the 30 s pause timer |

<a id="s3.13"></a>

## 3.13 Live services, achievements, theme packs and wave settings (2026-11-11)

**Unit tests added (42; suite 349 → 391):**

| Suite | Tests | Covers |
| --- | --- | --- |
| `AchievementsTest` | 15 | `API_GetGameInfoAndUserProgress` and `API_GetGameList` parsing (map or empty-array achievements, display order, hardcore dates, completion, points, lower-cased hashes); the key only in `y` and only to retroachievements.org; matching per system (full MD5, header-less NES/SNES, arcade set-name MD5, exact title for disc systems, hacks and subsets excluded, an unlisted hash never falls back to the title); repository caches in `Xross/data/ra` with no key in any file, console list reuse, offline copy, NotLinked / Unsupported / NoMatch, re-match after the file changes, badges in `media/ra/badges` under `.nomedia`, clear on unlink; badge-grid navigation |
| `UnlockToastTest` | 3 | Only unlocks newer than the watermark are news (never an account's history), the watermark persists in `retroachievements.json` (no key) so a restart doesn't repeat toasts, one-at-a-time queue without duplicates |
| `ThemePackTest` | 11 | Default pack has every icon and sound; fixed audio roles (`cursor.wav` for D-pad moves, `01. opening.wav` at startup, `system_ng.wav` for errors; no `snd_error`/`coldboot` in the APK); folder pack discovery; partial packs inherit sounds, gains and icons; case-insensitive lookups; PNG icons downsampled; palettes; path traversal refused; runtime fallback to Default; `.nomedia` in `themes/`; SVG reader (viewBox, translate, black backdrops dropped, basic shapes, junk rejected) |
| `WaveSettingsTest` | 9 | Wave uniforms round-trip through `settings.json`, per-field clamping, editor rows and drift-free steps, blocked ends, tint cycling, reset to pack defaults, slider fractions, presets and custom gradient, Theme rows, backdrop timing (250 ms / 1.2 s / 300 ms ramp) |
| `XmbViewModelTest` | +2 | Wave settings panel driven by D-pad on the real view model, saved to `settings.json`; refused actions leave the menu unchanged |
| `RetroAchievementsLiveTest` | 2 (opt-in) | Account sign-in; with a Web API key, linking, avatar, an arcade game's achievements and badges, no key on disk |
| Others updated | — | `OptionsMenuTest` (Achievements row), `MenuSettingsTest` (Theme pack row first), `ConfigCodecTest` (speed clamp 0.5–2.0), `ScreenScraperLiveTest` (developer credentials alone, media cached through `AssetCache`) |

**Live services** (`-PliveScrape=true`, `.env` credentials):

| Service | Result |
| --- | --- |
| ScreenScraper | Developer credentials only (no user account in `.env`, so the account check is skipped). 5 of 6 samples identified (3 arcade sets by hash, 2 of 3 3DS games by name search; *Ocarina of Time 3D* not found by name); all 5 with descriptions; box art and video snaps for all 5 cached through `AssetCache` into `media/3dboxes/<system>/` and `media/snaps/<system>/` with `.nomedia` written first; quota block read (10 of 10,000 requests, 0 of 1,000 not-found), pacing held at 1.2 s; 26.8 s for the run |
| SteamGridDB | 8 of 8 samples got art |
| RetroAchievements | The `.env` account signs in (`dorequest.php?r=login2`, POST body; token never printed). Linking, profile, achievement lists and badges ran live later the same day once the Web API key was in `.env` ([§3.14](#s3.14)) |

**Galaxy A55 (Android 16, 120 Hz), debug build installed, `MANAGE_EXTERNAL_STORAGE` granted:**

| Check | Result |
| --- | --- |
| Icons from the Default pack's SVGs, avatar ring, wave | ✅ Rendered (screenshot check) |
| Smoke test | ◐ 75 of 86. The 11 failures are all in Photo and Music: they expect the seeded `XrossSmoke` media to be the only media, and this phone has its own photos and music library. Every library, Options, Achievements, Search, Settings, Theme pack, Wave settings, secrets, deadzone, remap, rescan, video and touch check passed |
| Theme pack from `Xross/themes` | ✅ (smoke) found after "Look for new packs", palette and wave defaults applied, back to Default |
| Wave settings panel | ✅ (smoke) sliders change the live wave; values in `settings.json` |
| Achievements screen | ✅ (smoke) opens from Options, asks to link when no account is linked |
| Secrets | ✅ (smoke) no account names, passwords or keys in `Xross/config/*.json` |
| Frame pacing, profiling build, 30 s of category switches and list scrolling | ✅ 0.63% janky (23 of 3,629 frames; target ≤ 1.5%); p50 13 ms, p99 22 ms; GL thread p50 8.3 ms, p95 ≤ 10.6 ms at 120 Hz |
| Memory | ✅ 82.9 MB PSS in the background (target ≤ 150 MB); 180–185 MB in front with the media hub loaded |
| Idle CPU in front | ◐ 100–130% of the 8-core total, the same as the previous commit measured the same way (A/B on the profiling build): the UI redraws about 85 times a second for the focus pulse and the wave renders continuously. The new GL pause only applies while hero art or a snap covers the screen; this library had no scraped art to show it |

**Bugs found and fixed on the device:**

1. **One Back popped two levels** for `KEYCODE_BACK` (many controllers' and remotes' back
   button): with predictive back (target SDK 36), the key reached both the gamepad handler and the
   back callback. The callback now ignores a back within 400 ms of a consumed `KEYCODE_BACK`.
   Gamepad B and the back gesture were not affected.
2. **`smoke.sh` was out of date:** Up at the top of a category root now focuses the status-bar
   avatar, so `top` needs a Down afterwards; settings in `Xross/config` survive `pm clear` and
   leaked between runs (now moved to `/sdcard/Xross-smoke-backup/` first); the Default mapping
   row's subtitle shows only when focused.

**Not verified on hardware:** an unlock toast after a real emulator session, achievement lists
from the live Web API, hero art and video snaps behind the menu on a scraped library (the smoke
library has no scraped art), the audio ramp, a custom font from a pack.

<a id="s3.14"></a>

## 3.14 Live run with the full test library: emulator and Galaxy A55 (2026-11-11)

Library: 370 arcade sets, 2 PSP ISOs (1.4 GB), 3DS images, and one album twice (60 FLAC + 60 MP3
tracks with identical tags). Emulator: a dedicated `xross_test` AVD (API 36, arm64, 16 GB data,
1080×2400 landscape). Phone: Galaxy A55 (arcade and PSP only; no test music on a personal phone).

**Live services** (`.env`: ScreenScraper developer + user account, RetroAchievements user, password
and Web API key):

| Check | Result |
| --- | --- |
| ScreenScraper account | ✅ Signed in; 20,000 requests/day; quota shown in Settings ▸ Metadata on both devices (sign-in moved to Settings ▸ Accounts in 0.2.0.1-beta) |
| `ScreenScraperLiveTest` | ✅ 7 of 8 samples (arcade by hash, 3DS and PSP by name; *Ocarina of Time 3D* not found by name); descriptions, box art and video snaps cached |
| RetroAchievements sign-in | ✅ `RetroAchievementsLiveTest`: login, then linking with the Web API key, avatar cached, no key in any file |
| RA game matching | ✅ 1941 and Street Fighter II by set name (28 and 32 achievements), Tekken 6 by title (65); OutRun 2006 is on RetroAchievements without a set and now shows "no achievements yet" (game lists include games without a set) |
| Debug builds link RA from `.env` | ✅ Avatar in the status bar on first start on both devices; never under Robolectric |

**On both devices:** library scrape (hero art, box art, logos, descriptions, video snaps; stopped
by request after about 100 games on each, at 5–6 s a game with three services and snaps), User
Card, Achievements screen (badge grid with D-pad browsing, detail bar, points, completion),
Details summary with "▶ Achievements", focused-game hero art then video snap. On the emulator the
GL wave rendered no frames while art or video covered the screen and resumed after. FLAC and MP3
copies play (`c2.android.flac.decoder` / `c2.android.mp3.decoder`; Now Playing advanced).

**Found and fixed:**

1. **Two copies of an album merged into one with every track twice.** MediaStore gives identical
   tags one album id. Albums are now split by folder and labelled by format ("… · FLAC",
   "… · MP3"; the folder name when formats match); songs in All songs say which copy they are.
2. **Descriptions showed `&quot;`** and other HTML references from ScreenScraper; decoded when
   scraped and when shown (already-scraped games are fixed too).
3. **Games without an achievement set looked "not recognized".** Game lists now include them
   (cache format v2; older caches and matches are fetched again).
4. **Badges could stay blank** when a grid asked for dozens at once during a scrape: at most 4
   downloads at a time with a retry, and a failed cell tries again.
5. **Badge grid showed 1⅓ rows on 20:9 screens:** cells are now sized so 2½ rows fit (10 columns).
6. **User Card:** "10 POINTS" ran into "10 RETROPOINTS" (wider spacing); the menu showed through
   the card (now fades like under Details).
7. **Unit tests would have linked the tester's real RA account** through the debug `BuildConfig`
   (caught by `ProfileCardTest`); the pre-fill skips Robolectric.
8. `.env`: the ScreenScraper password key was misspelled (`SREENSCRAPER_…`); renamed. The build and
   live tests also accept `SCREENSCRAPER_USER_PASSWORD` and `RETROACHIEVEMENT_USER_API`.

Not covered: an unlock toast (needs achievements earned in an emulator), 3DS has no
RetroAchievements, and the two largest 3DS images (6 GB) were not copied to the emulator.
