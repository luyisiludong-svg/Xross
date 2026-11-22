# Xross user and storage guide

How to set up and use Xross day to day: storage access, the portable `Xross/` folder, your
library, metadata and artwork, RetroAchievements, and the Photo, Music, Video and Apps
categories.

Related documents: the [Theme 2.0 specification](THEME_SPECIFICATION.md) (theme packs, sounds,
background and effects), the [developer guide](DEVELOPMENT.md) (building, testing, source
layout), [`Roadmap.md`](../Roadmap.md) (what is planned) and [`STATUS.md`](STATUS.md) (what has
been verified, and where).

## Contents

1. [Before you start](#1-before-you-start)
2. [The `Xross/` folder](#2-the-xross-folder)
3. [Library and storage](#3-library-and-storage)
4. [Metadata, art and DATs](#4-metadata-art-and-dats)
5. [RetroAchievements](#5-retroachievements)
6. [Media hub](#6-media-hub)
7. [Apps](#7-apps)
8. [Feature status](#8-feature-status)

## 1. Before you start

* **Requirements:** Android 9 (API 28) or newer. Xross runs in landscape and turns 180° with the
  device. Android 16 ignores the orientation lock on screens 600 dp and wider.
* **Storage access:** on first launch go to **Settings ▸ Library ▸ All files access** to grant
  storage access, or press the "Storage access needed" row in Games, Photo, Music or Video. The
  same grant covers ROMs and media.
* **Home launcher:** Xross can be set as the HOME launcher, and has an Android TV leanback entry.
* **Upgrading from Wave:** Xross was called Wave until 2026-11-11. The application ID changed from
  `dev.wave.launcher` to `dev.xross.launcher`, so an older Wave install is a separate app:
  uninstall it by hand, since its settings and library don't carry over. Names in the code such as
  `WaveRenderer` or the "Wave background" setting refer to the background effect, not the product.

## 2. The `Xross/` folder

Everything you'd want to back up or carry to another device lives in one folder, by default
`/storage/emulated/0/Xross/`:

```
Xross/
  bios/     your own BIOS files, one place to keep them with your ROMs (Xross never reads or changes them)
  config/   settings.json, controllers.json, systems.json (hand-editable; picked up on resume)
  data/     xross.db, a backup snapshot of the library database;
            retroachievements.json and ra/ (public profile, game lists and progress caches)
  dats/     No-Intro / Redump / MAME DATs to import
  media/    scraped art and videos (has a .nomedia, so it stays out of Photo and Video):
            covers/ 3dboxes/ heroes/ wheels/ screenshots/ capsules/ snaps/, each <system>/<rom name>.<ext>;
            avatars/<user>.png and ra/badges/<badge>.png for RetroAchievements
  roms/     the default base ROMs folder, one folder per system
  themes/   theme packs, one folder each (has a .nomedia; see the Theme 2.0 specification)
```

`bios/` is only a place to keep the BIOS files you dumped from your own consoles next to your
ROMs, so the Xross folder carries both. Xross creates it and leaves it alone: it doesn't read,
check, rename or move anything in it, and it doesn't supply BIOS files. Each emulator still needs
to find its BIOS, so point the emulator's own BIOS or system folder setting at `Xross/bios`, or
import the files from there if the emulator copies them into its own storage.

Theme packs in `themes/` are described in the [Theme 2.0 specification](THEME_SPECIFICATION.md).

### SD cards and USB drives

**Settings ▸ System ▸ Xross folder** steps through the mounted drives. Config already in the
target folder is used (so a card from another device brings its setup along); otherwise the
current settings are written there and a database backup follows. If the drive is removed, Xross
falls back to primary storage. Android 9 and 10 don't let apps write to removable cards, so there
it stays on primary storage.

**Before storage access is granted** the same layout lives in app storage, and moves to shared
storage as soon as Xross may write there.

### Database snapshots and backups

**The live database never leaves app storage.** A consistent copy goes to `data/xross.db` a few
seconds after you leave Xross (when something changed) or with **Settings ▸ System ▸ Back up
library now**. On a fresh install with an existing backup, Games and Settings ▸ System offer
**Restore library backup**; Xross restarts to apply it.

### Accounts stay private

ScreenScraper and SteamGridDB credentials and the RetroAchievements Web API key are never written
to `config/` or anywhere else in `Xross/`; they stay in app-private storage (`CredentialStore`),
excluded from backups.

### Config files

* **`systems.json`** sets the emulator per system and lets you add folder names and file
  extensions; each system's `builtIn` block is there for reference and is rewritten on save. An
  id Xross doesn't ship, with a `name` and `extensions`, is a **custom system** (see
  [Emulators](#emulators)).
* **`emulators.json`** holds launch recipes for emulators Xross doesn't know, in ES-DE's command
  syntax. Comments are allowed, and Xross never rewrites what you wrote by hand: it only adds,
  edits and deletes the entries it made itself (`"source": "device"`).
* `emulators.example.json` and `systems.example.json` beside them are commented samples, never
  read; copy entries from them.
* Settings saved by earlier versions are moved into these files on first start.
* The `display` block of `settings.json` holds the look-related keys, documented in the
  [Theme 2.0 specification](THEME_SPECIFICATION.md#7-settingsjson-display-keys).

## 3. Library and storage

### ROM folders

Folders named `ROMs`, `Games`, `Emulation` or `Retro` at the root of any drive (internal storage,
microSD or USB-OTG) are picked up automatically; add others with **Add ROM folder…**. Put games
in platform subfolders (`snes/`, `psx/`, `gba/`, …). See `PlatformRegistry` in
[`storage/Platform.kt`](../app/src/main/java/dev/xross/launcher/storage/Platform.kt) for every
folder alias and extension.

**Base ROMs folder:** **Settings ▸ Library ▸ Base ROMs folder…** (default `Xross/roms`) is always
scanned; **Create system folders** makes `snes/`, `psx/`, … for all 23 systems. Auto-detected
`ROMs/` folders and folders you add keep working.

Removed drives keep their games in the library (hot-swap), and `.cue`/`.gdi`/`.m3u` sets are
de-duplicated. Favorites, Recently Played and playtime (shown under each game's title) are kept
per game.

Each system's folder shows its console logo. **Settings ▸ Theme ▸ Menu and profile ▸ Console
logos on system folders** picks the style (the theme pack's own, White, Color, Black & white,
Color outlined, or a set for light backgrounds) or turns them off; Options on the row lists every
style.

### Emulators

Each system launches in an emulator chosen per platform (in `systems.json` or Settings) or per
game: RetroArch (per-platform core), DuckStation, NetherSX2, ARMSX2, ARMSX3, Dolphin, PPSSPP, Azahar,
Eden, Flycast, M64Plus FZ, melonDS and ScummVM. ROMs go out as a path extra plus a `content://` URI
with a read grant.

**Settings ▸ Emulators** lists every system with its emulator (Confirm cycles, Options lists all
of them), then your own emulators and systems with anything wrong with them, so a typo in a file
shows on the device. It also has:

* **Add emulator…**: pick an installed app (known emulators first), its activity (the launcher
  one by default), how it gets the game (file URI with a read grant; file path as data; file path
  as an extra whose name you type; or an ES-DE command line), and which systems it runs.
  **Try it** launches the first game of the first system with the recipe before it is saved;
  **Save** writes it to `emulators.json`. Confirm on an emulator you made reopens it to edit or
  delete.
* **Add system…**: pick a folder (folders in your ROM folders that no system claims come first,
  or any folder on the device), check the name and the file types (filled in from what it
  holds), optionally pick an image as its console logo, and pick the emulator: one Xross has, an
  installed app (it gets a recipe that hands over the file URI), or Android's "Open with". Saving
  writes `systems.json` and rescans.

Custom systems scan, launch and scrape by name like built-in ones, but get no RetroAchievements or
DAT matching. A custom emulator counts as installed only when its package **and** its activity are
there (some ES-DE rules borrow other apps' package names). ES-DE's PC Engine CD folders
(`pcenginecd`, `tg-cd`, `turbografxcd`, `supercd`) scan as PC Engine.

PlayStation 3 games go to ARMSX3: put disc images (`.iso`, `.chd`) in a `ps3` folder. A game
installed inside ARMSX3 is listed through a `.ps3` file whose first line is its serial
(`BLUS30443`); the file name is the title shown. ARMSX3 reads games by path, so give it storage
access (all files) in Android's settings.

### App folders and hidden apps

Options (△ / Y) on an app hides it or moves it into a folder (Emulators, Streaming, Tools or your
own name, with emulators suggested); Settings ▸ Apps lists hidden apps and can hide every system
app. See [Apps](#7-apps).

## 4. Metadata, art and DATs

### DATs (offline verification)

Put No-Intro, Redump or MAME DATs (`.dat`, `.xml`, or zipped) in `Xross/dats`, then **Settings ▸
Library ▸ DAT files ▸ Import DATs**. Matched games get their exact title, region, languages,
revision and serial.

### Scraping

**Settings ▸ Library ▸ Metadata & artwork**.

* **Hasheous** (identification by hash) needs no account.
* **SteamGridDB** (capsules, hero banners, logos) needs a free API key.
* **ScreenScraper** (descriptions, box art, video snaps) works out of the box: release builds
  carry Xross's developer credentials. Signing in with your own free screenscraper.fr account in
  **Settings ▸ Accounts ▸ ScreenScraper account…** gives you your own daily quota instead of the
  shared one; use your user name, not your email. Saving checks the login and shows today's quota;
  a blank user name signs out. An English guide to ScreenScraper's API rules is in
  [`reference/SCREENSCRAPER_API.md`](reference/SCREENSCRAPER_API.md).

Scraping runs only while Xross is in front; automatic scraping is off until you turn it on, and
Wi-Fi only by default.

**Art is named like ES-DE's** (`media/covers/snes/Super Mario World (USA).png`), so it survives a
library rebuild and ES-DE media copied into `media/` is used as is. Names are cleaned for
FAT32/exFAT cards. Art from earlier versions is moved here once, in the background.

### Per game: Details, matching and information

* **Options ▸ Details** (or → on a game; L1/R1 or ←/→ there pages to the next and previous game).
* **Options ▸ Metadata & Media** for the game's title, description, match and art, on one screen:
  * Edit title…, Edit description…, Find match…, Scrape again, and Reset metadata (which forgets
    your title, description and match).
  * One row per art slot (Box art, 3D box, Capsule, Hero background, Logo, Screenshot, and Video
    snap when Settings ▸ Scrape videos is on), each with a preview and where it came from:
    *Scraped*, *Chosen* (by you), *From your folder* (media copied in, ES-DE's for example),
    *Removed* or *None*.
  * Confirm on a slot opens the picker: everything ScreenScraper and SteamGridDB have for that
    slot, then **Use a file…** (an image on the device; an MP4 for the video snap) and
    **Remove**. Art you choose or remove is kept: Scrape again doesn't replace it until
    **Reset media**. The services are asked only when you open a slot, so paging through games
    uses no quota.
  * L1 / R1 move to the previous or next game of the list and keep the cursor on the same row.
    Back returns to the game's Options.
* **Options ▸ Information** for the file, hashes, DAT match and playtime.
* **Options on a system folder ▸ Show ▸ Missing art** lists only the games without box art, a hero
  or a logo; Metadata & Media opened from there pages through just those.
* **Fix media…** (Settings ▸ Library ▸ Metadata & artwork, or Options on a system folder) goes
  through a whole system: a strip of consoles at the top (L1 / R1 or ← / →), filters (All,
  Missing anything, Missing box art, Missing hero, Missing logo; ↑ from the first game reaches
  them), and every game with a badge per art kind: filled when it has the file, hollow when it
  doesn't, underlined when you chose it. Confirm opens Metadata & Media on that game, paging
  within the system; Back returns to the same game.

Resting on a scraped game brings up its hero art and video snap behind the menu; timing and
audio behaviour are described under
[Focused game background](THEME_SPECIFICATION.md#13-focused-game-background).

### Credentials for builds

Copy [`.env.example`](../.env.example) to `.env` (git-ignored). The build reads ScreenScraper
developer credentials from it and masks them into the APK (users never need their own); a release
build refuses to run without them. Debug builds only also pre-fill your SteamGridDB key, ScreenScraper
account and RetroAchievements user name and Web API key (never the password) on a fresh install.
In the app itself, credentials you type go to app-private storage (see
[Accounts stay private](#accounts-stay-private)).

## 5. RetroAchievements

* **Account linking:** **Settings ▸ Accounts** links your account with your user name and Web API
  key (retroachievements.org ▸ Settings ▸ Keys).
* **Status bar avatar and mini-HUD:** your avatar sits at the far right of the status bar; Up from
  the top of a category (or a tap) expands it into a mini-HUD with your rank, points and latest
  badges, Confirm refreshes it. A theme (or Settings ▸ Theme ▸ Components) can leave the avatar out
  of the bar; the User Card is then in Settings ▸ Accounts.
* **User Card:** Options on the avatar opens the full User Card (Refresh, Unlink).
* **Dashboard:** **Games ▸ RetroAchievements** shows your profile, library completion, mastered
  games and recent unlocks.
* **Per game:** resting on a game shows its progress ("18/42", points) above the button hints.
  **Options ▸ Achievements** on a game (or → on the Details screen) opens its badge grid with
  progress, points and unlock dates.
* **Matching:** games are matched by ROM hash the way RetroAchievements hashes them (header-less
  for NES/SNES/PC Engine, set name for arcade); disc systems and games not fingerprinted yet are
  matched by exact title.
* **Unlock toasts:** achievements unlocked in an emulator show as unlock toasts when you come back
  to Xross.

The profile caches live in `Xross/data/retroachievements.json` and `Xross/data/ra/`; avatars and
badges in `Xross/media/avatars/` and `Xross/media/ra/badges/`.

## 6. Media hub

* **Photo:** viewer with zoom, pan and slideshow. **Photo ▸ Options ▸ Set as Xross wallpaper** (in
  a photo list or the viewer) puts a photo behind the menu instead of the theme's (details in the
  [Theme 2.0 specification](THEME_SPECIFICATION.md#wallpaper)); it can also be set as the Android
  wallpaper. **Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a folder…** lets a folder's pictures take
  turns (**Change**: every 1, 5, 15 or 60 minutes, or each time Xross comes to the front); a change
  waits for your next button press, so a resting screen stays still. **Photo ▸ an album ▸ Options ▸
  Use as Xross wallpaper** (or Xross wallpaper ▸ **An album**) does the same with a Photo album.
* **Music:** background music player with media-session controls. Select (or a held Guide button)
  opens the media controls. Music pauses for games and videos, and for a game's video snap once
  the snap's sound starts (it fades back in when the cursor moves on; a song you paused stays
  paused). **Settings ▸ Sound ▸ Music during video snaps ▸ Lower** keeps it playing at 20% instead.
  In Now Playing, ↓ stops the music and empties the queue.
* **Theme music:** a theme pack can bring an ambient loop (`bgm.ogg`) or a list of tracks, or you
  choose in **Settings ▸ Theme ▸ Theme music**: the theme pack's, **Pick a file…** (one track,
  looped), **Pick a folder…** (up to 50 tracks, crossfading, shuffled or in order), **A playlist**
  (one of your Music playlists, in its order or shuffled; also **Music ▸ Playlists ▸ Options ▸ Use
  as theme music**, and it follows edits to the playlist) or **Off**.
  After a game a list goes on with its next track. It plays only while nothing
  else does: it stops when you start a song (a paused song counts; stop it from Now Playing to
  hear the theme's music again), stays silent while another app plays media, pauses under a game's
  video snap and stops for a game. It never takes audio focus, so it doesn't pause other apps.
* **Boot intro and launch curtain:** a theme pack can open a cold start with its own video
  (`boot.mp4`) or by raising its background shader from black; any button or touch skips it.
  Settings ▸ Theme ▸ Intro and transitions picks Video, Shader or Off, and the curtain drawn while
  a game starts (Bloom, Fade to black, Iris, Flash). The intro stays off under Monochrome E-Ink and
  while the startup sound is off.
* **Status bar:** **Settings ▸ Theme ▸ Components** arranges it. *Status bar left* and *Status bar
  right* list their slots (logo, network, volume, battery, clock, avatar): add, remove and move
  them; the avatar always stays last on the right. *Battery display* picks the gauge, the
  percentage, both, or the time to a full charge while charging; *Clock format* picks 24 h, 12 h,
  date and time, or the time only. The volume slot shows the phone's media volume for 3 s after it
  changes. *Mini player* shows what plays as the floating card, as one line in the status bar
  before the avatar (the ticker), or not at all (the music and its notification carry on).
  *Button glyphs* draws every hint with the controller's own labels, PlayStation shapes, colored
  Xbox letters, Nintendo letters or minimal outlines; which button confirms doesn't change.
  *Title badges* adds pills after the focused game's title: system, release year, developer,
  playtime and achievements, in the order you turn them on. *Top-left* set to *Social stack* shows
  the RetroAchievements users you follow at the top-left, with a badge counting who started
  something new since you last looked; Left from the avatar's HUD (or a tap) opens the list with
  each friend's latest game and how long ago. It needs a linked account, refreshes with your
  profile (and when you open it after half an hour), and keeps the last list for offline use.
  Saved as `display.components` in `settings.json`; Reset to theme defaults hands them back to
  the theme.
* **Settings ▸ Sound** has separate volumes for effects, startup and game cues, theme music
  (the pack's loop), Xross's music player (on top of the phone's media volume) and video snaps:
  Confirm steps each 10%, Options offer Mute, 50% and 100%; Theme music at 0% turns the pack's
  loop off. They are `sound.effectsVolume`,
  `cueVolume`, `bgmVolume`, `musicVolume` and `snapVolume` (0–1) in `settings.json`, with
  `sound.musicUnderSnaps` (`PAUSE` or `DUCK`). Reset to theme defaults leaves them alone.
* **Video:** video player.
* **Albums:** an album found in more than one folder (the same rip as FLAC and MP3, say) shows
  once per folder, labelled by format ("… · FLAC") or, when the formats match, by folder name.
* **Settings ▸ Media** sets the slideshow interval, whether music resumes after a game or stops,
  video looping, and which folders Photo, Video and Music show (all of them by default). Custom
  folders also find files MediaStore skips, so a game's soundtrack folder elsewhere stays out of
  Music unless you add it.
* **Video snaps** of the focused game: see
  [Focused game background](THEME_SPECIFICATION.md#13-focused-game-background).

## 7. Apps

Android apps are listed under Apps, and games among them are sorted into **Games ▸ Android
Games**. Options (△ / Y) on an app hides it or moves it into a folder; Settings ▸ Apps lists
hidden apps and can hide every system app. App icons can be masked and recoloured by the theme
(see [Focus frames and app icons](THEME_SPECIFICATION.md#focus-frames-and-app-icons)).

Video, music and photo apps (YouTube, Netflix, Spotify, Google Photos…) are sorted into an
**Apps** folder at the end of Video, Music and Photo. Xross goes by the category an app declares,
and by a built-in list of well-known apps for those that declare none. Options on an app there
offers **Move to Apps**; in Apps, **Move to Video, Music or Photo** puts any app in one of them,
and **Sort automatically** forgets that choice. An app you put in an Apps folder stays in Apps.
Settings ▸ Apps ▸ Sort media apps turns the sorting off (your own moves still apply).

## 8. Feature status

What works today, by area. [`Roadmap.md`](../Roadmap.md) tracks what is left and
[`STATUS.md`](STATUS.md) records how each item was verified.

| Area | State |
| --- | --- |
| XMB navigation | Done. Settings, Photo, Music, Video, Games and Apps; per-category drill-down stacks, breadcrumb, spring-animated scroll, touch swipe, tap and long-press |
| Gamepad layer | Done. Vendor detection (Sony/Microsoft/Nintendo/generic) with matching glyphs, confirm/cancel swap (Auto/South/East), radial (including "Off — hardware only") and axial deadzones, anti-snapback, stick/hat auto-repeat, button-remap wizard saved per controller model, Options (△ / Y) menu, Select (or held Guide) for the media controls |
| Portable folder | Done. `Xross/` with JSON config (atomic writes, migration from earlier versions), a library database backup with restore, `.nomedia` for media and themes, and moving the folder to an SD card or USB drive. Tested on a phone's internal storage; SD cards and Android 9/10 not yet |
| Library | Done. File-path scanner over internal storage, microSD and USB-OTG; Room DB with an FTS4 title index and gamepad search; incremental rescan; hot-swap that keeps games on removed drives; cue/gdi/m3u de-duplication; favorites, Recently Played and playtime (shown under each game's title) |
| Fingerprinting | Done. CRC32/MD5/SHA-1 inside `.zip`/`.7z`, CHD header SHA-1 and No-Intro header-less hashes, in a background job that stops while a game runs |
| DAT verification | Done. No-Intro, Redump and MAME DATs (Logiqx XML or clrmamepro, plain or zipped) matched by SHA-1, MD5, CRC32 + size or arcade set name; Room schema v5 |
| Scraping and art | Done. Hasheous, SteamGridDB and ScreenScraper with per-service pacing and quotas; art cached on disk per game; box art on game rows; Details screen with paging between games; manual match and title override; hero art and video snaps behind the focused game. All three services verified live |
| Emulator dispatch | Done. RetroArch (per-platform core), DuckStation, NetherSX2, ARMSX2, ARMSX3, Dolphin, PPSSPP, Azahar, Eden, Flycast, M64Plus FZ, melonDS and ScummVM, chosen per platform or per game. ROMs go out as a path extra plus a `content://` URI with a read grant. Verified with stand-ins only |
| Media hub | Done. Photo viewer (zoom, pan, slideshow, Set as Xross or Android wallpaper), background music player with media-session controls, video player; music pauses for games and videos |
| Wave background | Done. GLSL ES 3.0 ribbon shader with day/night palette; speed, ribbon lines, turbulence, thickness and color presets as live uniforms (Settings ▸ Theme ▸ Wave settings). Pauses whenever the launcher isn't in front or game art covers it |
| Theme packs | Done. `Xross/themes/<id>/` with a v2 `theme.json` (imports, palette roles, font roles, surfaces), fonts, SVG/PNG icons and sounds, reloaded live when edited; the bundled icons and sounds are the built-in Default pack |
| RetroAchievements | Done. Account linking, status bar avatar with an expanding mini-HUD, User Card, Games ▸ RetroAchievements dashboard, per-game telemetry and achievement lists, unlock toasts. Linking and lists verified live on an emulator and the Galaxy A55; the 2b.1 HUD and dashboard are unit-tested only; unlock toasts not yet seen after a real session |
| Library organization | Done. Hide apps (Options ▸ Hide, Settings ▸ Apps ▸ Hidden apps, Hide system apps), app folders (Emulators, Streaming, Tools or your own name, with emulators suggested), base ROMs folder with Create system folders, and custom Photo/Video/Music folders that also find files MediaStore skips. Tested on the Galaxy A55 |
| Customization | 16:9 XMB game tiles (logo, wide art or a title plate); console logos on system folders in eight styles; Settings ▸ Theme ▸ Menu and profile ▸ Profile customization for the avatar's border and glow (Glow off also drops the halo on a focused avatar, friends' included; `profile.avatarStyle` in `settings.json`). Theme 2.0 packs with variants, overrides and context rules, palettes from game art, an hourly circadian curve, a battery tint and quick profiles (OLED True Black, Y2K Cyber-Pastel, Monochrome E-Ink); background shader presets and a pack's own `shader.frag` (music-reactive through Xross's player), a pack wallpaper or a picture set as Xross wallpaper, frosted surfaces over art and wallpapers, focus frames, icon masks, ambient particles, a GL background filter (scanlines, CRT, LCD grid) and an experimental AGSL filter over the menu on Android 13+, each also pickable in Settings ▸ Theme; a pack's ambient music loop (only while nothing else plays), boot video or shader intro, and launch curtain; a status bar arranged from Settings ▸ Theme ▸ Components (logo, network, volume, battery with time to full, clock formats, the avatar, the friends you follow on RetroAchievements), with the mini player as a card, a status-bar ticker or off, a button-glyph style and badges beside the focused title. Haptics are on hold (Roadmap §4.8, Icebox) |
| Extras | Android apps (games sorted into Games ▸ Android Games, media apps into Video, Music and Photo), 8 color themes including Original (a color per month), busy indicator, optional button hints, Information screen, can be set as the HOME launcher, Android TV leanback entry |
| XMB fidelity | Closer. The home menu's layout, motion, navigation, status bar and options panel match the XMB; games show their own tiles, and focusing one brings up its art and video. An XMB-style typeface, Resume/Quit Game and the folder drill-down are missing. Details in [`XMB_FIDELITY.md`](XMB_FIDELITY.md) |
| Not started | Dual screen, Xross Portal, FTPS. On hold: haptics and the on-screen controller (Roadmap §4.8, Icebox) |
