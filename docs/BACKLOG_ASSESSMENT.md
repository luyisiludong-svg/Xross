# Backlog assessment (2026-11-11)

Seven features proposed in `features_backlog_roadmap_directive.md`, checked against the code on
branch `metadata-pipeline` (Room schema v5, 206 unit tests passing) before any of them is built.
For each one: what exists today, what has to change, what could break, and the decision. Where
each lands in the plan is in [`Roadmap.md`](../Roadmap.md) (§4).

Summary:

| # | Feature | Fits the architecture? | Size | Milestone |
| --- | --- | --- | --- | --- |
| 1 | 0% analog deadzone | Yes; the stick code already allows it | S | Phase 1b |
| 2 | Custom Photo and Video folders | Yes, with a direct-scan fallback for folders MediaStore skips | M | Phase 2: Media hub |
| 3 | Media controls shortcut, auto-hide after 30 s paused | Yes; the Guide button can't be the only binding | S | Phase 2: Media hub |
| 4 | Hide apps, app folders | Yes; replaces Phase 4 "App triage UI" | M | Phase 2: Library |
| 5 | "All Games" folder, base ROMs folder | Yes; platform IDs already follow ES-DE | S | Phase 2: Library |
| 6 | Single portable `Xross/` folder | Partly: credentials and the live database need different treatment | L | Phase 2: Storage foundation |
| 7 | Background priority: video, then hero art, then wave | Yes; extends the existing snap layer | M | XMB fidelity (§4.5) |

---

## 1. 0% analog stick deadzone

**Today.** `StickTracker.direction()` rejects a sample when `hypot(x, y) < deadzone`, and the
zero vector separately. Settings ▸ Controls cycles the radial deadzone through
15/25/35/45/55% (`DEADZONES` in `XmbViewModel`). Before either deadzone runs,
`GamepadHandler.centered()` already zeroes any value inside the device's reported
`InputDevice.MotionRange.flat`.

**Change.** Add `0f` to `DEADZONES`, labelled "Off — hardware only". Because the hardware flat
zone is still applied, 0% means "trust the controller's own calibration", which is what Hall
effect sticks and users who calibrate in firmware want. True raw input (ignoring `flat` too) is
not offered: in a menu, any noise would move the cursor.

**Scope note.** The deadzone only affects Xross's own menus. Emulators read the stick themselves
and apply their own deadzones, so this setting never reaches a game.

**Risks.** A drifting stick at 0% walks the cursor by itself. The D-pad still works, so Settings
stays reachable; the row's detail text warns about it. Anti-snapback stays on.

**Follow-up (optional).** Store the deadzone per controller model alongside the existing
per-controller button mapping (`ControllerProfile`), so a Hall effect pad can use 0% while a worn
one keeps 35%. This also moves cleanly into `controllers.json` (feature 6).

**Breaking changes.** None. A stored value outside the list already falls back to the first entry
when cycled. Tests: `direction(0.01f, 0f, 0f) == RIGHT`, zero vector still null, axial with
radial 0.

---

## 2. Custom media folders for Photo and Video

**Today.** `MediaRepository` reads everything from MediaStore and groups by
`BUCKET_DISPLAY_NAME`. MediaStore leaves out folders containing `.nomedia`, hidden (dot) folders,
`Android/data`, and on some devices USB drives.

**Change.** Each of Photo and Video gets a folder list in Settings ▸ Media: empty means "all of
MediaStore" (today's behavior), otherwise only the listed folders are shown.

- Folders are picked with the same system picker and path conversion as **Add ROM folder…**
  (`StorageVolumes` document-ID mapping).
- Listed folders that MediaStore indexes are filtered by path prefix (`DATA LIKE ?`; `DATA` is
  readable with All files access and works on API 28).
- Listed folders MediaStore returns nothing for fall back to a direct file walk: thumbnails from
  `ThumbnailUtils.createImageThumbnail(File, …)` (API 29+; `BitmapFactory` with `inSampleSize`
  on API 28) and `MediaMetadataRetriever` for video frames and duration; EXIF from
  `ExifInterface`.
- Direct-walk folders are rescanned on resume and on hot-swap events (`VolumeMonitor`), not with
  `FileObserver`, which is unreliable on FUSE for large trees.
- Music should get the same option later for consistency; not part of this item.

**Interactions.** Feature 6 puts scraped covers and video snaps under `Xross/media`. That folder
must contain a `.nomedia` file, or MediaStore will put every box art into Photo and every snap
into Video. The folder picker should refuse `Xross/media`.

**Breaking changes.** None for existing users (empty list = current behavior). New settings
fields `photoFolders` and `videoFolders`.

---

## 3. Media controls shortcut and 30-second auto-hide

**Today.** A text line ("♪ / ❚❚ Title — Artist") sits in the top-left corner whenever a song is
loaded, playing or paused (`XmbScreen`, the `AnimatedVisibility` on `state.nowPlaying.song`).
The full Now Playing screen opens from Music ▸ Now Playing (`Command.OpenNowPlaying`). The Select
button isn't mapped to anything (`KeyMapper`).

**Auto-hide.** Record when playback paused. If it stays paused for 30 seconds, hide the corner
line (fade out). It returns when playback resumes, from Now Playing, a media key or the
notification. Stopped playback hides it at once. Pure state logic, unit-testable with an injected
clock.

**Shortcut.** A new `NavAction.MEDIA_CONTROLS` toggles the Now Playing screen from anywhere in
Xross while something is loaded.

- Default binding: **Select** (free today), plus a long press of **Guide / PS / Home** where the
  key reaches the app.
- Guide can't be the only binding. Android or the handheld's firmware often takes it (as HOME,
  or for its own overlay on Retroid, AYN and Ayaneo devices), and as the HOME launcher Xross
  only sees a HOME press as a new intent, never as a long press.
- Add it as an optional step in the remap wizard so it can be bound per controller.
- The shortcut only works while Xross is in front. Catching keys over a running emulator would
  need an accessibility service, which is out of scope.
- Fits next to the XMB fidelity item "Control panel on △ in the video and music players".

**Breaking changes.** None. `smoke.sh` doesn't press Select.

---

## 4. Hide apps and app folders

**Today.** `AppRepository` lists every launchable activity except Xross; `MenuTree` shows the
non-games as a flat, alphabetical Apps list and games under Games ▸ Android Games. Apps aren't in
Room. Phase 4 has a planned "App triage UI" (Emulators, Games and Utilities groups);
`EmulatorRegistry` already knows the emulator package names.

**Change.**

- Data: an app layout of `hidden: Set<String>` and `folders: List<AppFolder(id, name, members)>`,
  keyed by `ComponentName.flattenToString()`. Stored with the settings (JSON, so it moves into
  `settings.json` with feature 6), not in Room: it's small and should travel with the config.
- Options (△) on an app: **Hide**, **Move to folder ▸** (existing folders, **New folder…**,
  **Out of folder**). Options on a folder: **Rename**, **Delete** (members return to the list).
- Hidden apps disappear from Apps and from Games ▸ Android Games. Settings ▸ Apps ▸ **Hidden apps**
  lists them to unhide; a **Hide system apps** switch uses `ApplicationInfo.FLAG_SYSTEM`.
- Folder names: offer presets (Emulators, Streaming, Tools, Games) so no typing is needed; a
  custom name uses the gamepad keyboard (which already exists for search).
- A suggested **Emulators** folder is filled from `EmulatorRegistry` packages on first use. This
  covers the Phase 4 triage item, which is removed from Phase 4.
- Members that are uninstalled are skipped in the list and pruned on the package-removed
  broadcast, so reinstalling an app during an update doesn't lose its folder.

**Breaking changes.** None (no layout = today's flat list). The Apps row order changes only once
folders exist; folders sort above apps.

---

## 5. "All Games" folder and a base ROMs folder

**Today.** Games ▸ shows Search, Recently Played, Favorites, then one folder per platform with
games, then Android Games. The scanner already uses the ES-DE layout: a root such as `ROMs/`
with platform subfolders. The 22 platform IDs already match ES-DE's system names (`nes`, `snes`,
`n64`, `gb`, `gbc`, `gba`, `nds`, `megadrive`, `mastersystem`, `gamegear`, `pcengine`, `psx`,
`ps2`, `psp`, `gc`, `wii`, `dreamcast`, `saturn`, `n3ds`, `switch`, `scummvm`, `arcade`), plus
aliases such as `genesis`, `3ds`, `mame` and `fbneo`. `ROMs`, `Games`, `Emulation` and `Retro`
at the root of every drive are found automatically.

**Change.**

- Games ▸ keeps Search, Recently Played, Favorites and Android Games at the top level, and
  gains **All Games ▸** holding the platform folders.
- A setting (Settings ▸ Library ▸ **Platforms**: "In All Games" / "At the top") keeps the
  current flat layout available. The PS3 Game column lists items directly, so flat is the more
  XMB-like choice; "In All Games" is the default as requested.
- **Base ROMs folder** (Settings ▸ Library): one folder marked as the library's home. It is
  scanned like any other, is where the Portal and FTPS (Phase 3) put uploads, and has a
  **Create system folders** action that makes `<base>/<system_id>/` for all 22 platforms, like
  ES-DE's "create directories". Default: an existing auto-detected `ROMs/` if there is one,
  otherwise `Xross/roms` from feature 6. Auto-detection and extra folders keep working.

**Breaking changes.**

- Node IDs for platforms stay the same, only their parent changes. Drill-down stacks and cursors
  aren't persisted, so there is nothing to migrate.
- `tools/device-tests/smoke.sh` navigates by key counts and will press the wrong rows again. It
  is already out of date (Roadmap §3); fix both in the same pass.

---

## 6. Single portable `Xross/` folder

**Today.** State is spread across private and shared storage:

| What | Where today |
| --- | --- |
| Settings, including controller profiles and emulator choices | Private SharedPreferences `xross_settings` (`SettingsStore`, encoded with `org.json`) |
| Service credentials | Private SharedPreferences, excluded from backup (`CredentialStore`) |
| Running-game session | Private SharedPreferences `xross_session` (`PlaySessionTracker`) |
| Library database | Private `databases/xross.db` (`XrossDatabase.create`) |
| Scraped art and snaps | Private `noBackupFilesDir/assets/<uuid>/<kind>.<ext>` (`AssetCache`) |
| DATs | Shared `/storage/emulated/0/Xross/dats` (`XrossPaths`) |

**Proposed layout** (the directive's tree, plus `dats/`, which already exists):

```
Xross/
├── config/
│   ├── settings.json       # XrossSettings, minus controllers and systems
│   ├── systems.json        # emulator per platform; later, user launch recipes
│   └── controllers.json    # per-controller mappings (and deadzones) keyed by VID:PID
├── data/
│   └── xross.db            # see the database decision below
├── dats/                   # unchanged
├── media/                  # .nomedia inside
│   ├── covers/ heroes/ wheels/ screenshots/ videos/
├── roms/                   # default base ROMs folder when the user has none (feature 5)
└── themes/                 # read by the Phase 2 theme pack loader; .nomedia inside
```

### Decisions

**Credentials stay private.** Any app with All files access, and any computer the device is
plugged into, can read `Xross/`. ScreenScraper and SteamGridDB credentials (and the future
RetroAchievements token) therefore never go into `settings.json`; they stay in `CredentialStore`.
The session tracker's state is transient and stays private too.

**Xross must start without storage access.** The folder is unreadable until All files access is
granted. A small private bootstrap (root location and migration state) plus a private mirror of
`settings.json` (written alongside it) let Xross start, and keep working if the root's drive is
removed. On first grant, existing private settings are written out to `Xross/config/` once.

**Settings format.** `@Serializable` data classes with kotlinx.serialization (a new Gradle plugin
and dependency; today's code uses `org.json`), a `"schema": 1` field, `ignoreUnknownKeys` and
explicit defaults so older and newer files both load. Writes are debounced and atomic (temp file,
then rename). A file that fails to parse is kept as `settings.json.bad` and the mirror is used,
rather than silently resetting.

**Live database: stays private; `data/xross.db` is a snapshot (decided 2026-11-11).** The
directive put the live database on shared storage. That carries real risks:

- Shared internal storage goes through Android's FUSE layer (Android 11+), which slows the scan
  and fingerprint write bursts and FTS queries.
- On exFAT or FAT32 (SD cards, USB drives) SQLite has no WAL support worth trusting, weaker
  `fsync` guarantees, and pulling the card mid-write can corrupt the library. The hot-swap
  engine's job is to keep working when a drive disappears; that's impossible if the database
  was on it.

Decision: keep the live database in private storage with WAL as today, and write a
consistent copy to `Xross/data/xross.db` when Xross goes to the background
(at most every 10 minutes while something changed). `VACUUM INTO` needs SQLite 3.27, which
Android ships from Android 11; on Android 9 and 10, checkpoint the WAL and copy the file inside
a read transaction (or switch Room to the bundled SQLite driver) and on demand (Settings ▸ Library ▸ **Back up
now**). On a fresh install, if `Xross/data/xross.db` exists, offer to restore it, running Room
migrations on the copy before use. This gives backup and USB inspection without the corruption
risk. An "advanced" option to run the live database from `/storage/emulated/0/Xross/data` with
`JournalMode.TRUNCATE` can come later; a removable drive is never allowed.

**Media layout: by system and ROM name, not UUID (recommended).** `games.uuid` is random per
install (migration 4→5), so UUID-named files only make sense next to the same database. Naming
files `media/<kind>/<system_id>/<rom file stem>.<ext>`, as ES-DE does, makes art survive a
library rebuild and lets users copy in media an ES-DE install already downloaded. Costs:

- Renaming a ROM orphans its art (same as ES-DE).
- Stems come from user file names, so `AssetCache` must keep rejecting `..` and path separators,
  and must encode characters FAT/exFAT forbid (`: * ? " < > |`), since the ROM may be on ext4
  while the media is on an SD card.
- `AssetCache` keeps its `(uuid, kind)` API and resolves the file through the game row, so
  `ArtLoader` and the UI don't change.

A one-time migration moves `noBackupFilesDir/assets/<uuid>/` into the new layout and deletes the
old copies.

**Launcher behind an interface.** `GameLauncher` becomes an interface with an
`AndroidIntentLauncher` implementation. `LaunchPlan.Ready` currently carries an Android `Intent`;
that payload moves into the Android implementation. Do this when the launcher is next touched;
Compose Multiplatform / desktop is not in scope, so it isn't a goal on its own. (Room 2.7+ and
kotlinx.serialization are both multiplatform, so the choices above don't block it.)

**Play Store.** No new permission. All files access is already a Play review risk (Roadmap §4.4).

### Order

1. Bootstrap, `settings.json` / `controllers.json` / `systems.json`, mirror, `.nomedia`.
2. Media move to `Xross/media` (after 1; can follow feature 7).
3. Database snapshot and restore.

**Breaking changes.** The migration from SharedPreferences and from the old asset cache runs once
and keeps the originals until the new files are written and read back. Room schema stays at v5.

---

## 7. Background priority on a focused game: video, hero art, wave

**Today.** Game rows show box art. `SnapDriver` plays a game's video snap full-bleed behind the
menu after the cursor rests on it for 1.2 s; `SnapPlayer` ducks background music while it plays.
`DetailsScreen` already uses hero art, then a screenshot, as its background. The XMB fidelity
item "Background crossfade to the game's art on focus" is still open.

**Change.** One background controller for the focused game, in this order:

1. **Video snap** if the game has one: fades in after 1.2 s, over the hero if there is one.
2. **Hero banner** if it has one: crossfades in after a short settle (~250 ms) so fast scrolling
   doesn't decode every banner.
3. **Wave** otherwise (and always underneath).

- Audio: keep ducking the music, and ramp the snap's own volume in and out (~300 ms) so it
  doesn't start or stop with a pop.
- A screenshot as an extra fallback between hero and wave would match `DetailsScreen`; worth
  deciding during implementation.
- A scrim keeps the menu text readable (`SnapVideo` already has one).
- While a hero or video fully covers the screen, pause the wave renderer. This also helps the
  known issue of about 41% CPU while idle at 120 Hz (Roadmap §3).
- One hero decoded at a time (plus the outgoing one during a crossfade), sized to the screen;
  released when Xross goes to the background, as box art is now.
- Setting: Settings ▸ Theme ▸ **Game backgrounds**: Video and art / Art only / Off.

**Testing.** `localTestMedia/Video` is empty and ScreenScraper (the only snap source) awaits
developer credentials. To test before then, place sample `.mp4` files in the asset cache by hand
or add a debug-only "use this file as the snap" action.

**Breaking changes.** None. It supersedes the §4.5 crossfade item.

---

## Local test media

Test fixtures moved from `Roms/` to `localTestMedia/` (`Roms/ARCADE`, `Roms/3DS`, `Music/`,
`Video/`). Updated: `ScreenScraperLiveTest` (reads `localTestMedia/Roms/<dir>`), `.gitignore`
(`localTestMedia/` replaces `Roms/`), README. `tools/device-tests/smoke.sh` doesn't read local
media: it generates its own files and pushes them to the device, and its `/sdcard/ROMs` paths are
on-device folders, so it needed no change.
