# Changelog

## Unreleased

- **A `bios` folder in the Xross folder.** A single place to keep your own BIOS files next to
  your ROMs, so they travel with the rest of your setup. Xross only creates it; it never reads or
  changes what you put there. Point each emulator's BIOS setting at `Xross/bios`.
- **ARMSX2 for PlayStation 2.** Pick it for PS2 in Settings ▸ Emulators, or per game in Options ▸
  Emulator; NetherSX2 stays the default. The stable and nightly builds are both found. A cue+bin
  game is handed to ARMSX2 as its first track, because ARMSX2 can't boot a `.cue` it can't see
  the tracks of.
- **PlayStation 3 with ARMSX3.** A new PS3 system lists disc images (`.iso`, `.chd`) from a `ps3`
  folder, and games installed inside ARMSX3 through a `.ps3` file holding the game's serial.
  ARMSX3 opens games by path, so it needs its own storage access. PS3 games scrape from
  ScreenScraper; there are no RetroAchievements for PS3.
- **ES-DE hack and MSU folders.** `nesh`, `snesh`, `snes-msu1`, `gbh`, `gbch`, `gbah`, `genh` and
  `msu-md` folders now list their games under the base system (NES, SNES, Game Boy…), so a ROMs
  folder laid out for ES-DE scans as it is. Mega Drive also lists `.cue` and `.chd` files, which
  is how MSU-MD games come.
- **Media apps in Video, Music and Photo.** Streaming, music and gallery apps now sit in an Apps
  folder at the end of those categories instead of in Apps. Options moves an app back to Apps (or
  from Apps into any of the three); Settings ▸ Apps ▸ Sort media apps turns the sorting off.
- **Hide the file format in game rows.** Settings ▸ Theme ▸ Menu and profile ▸ File format in
  game rows drops "ISO", "CHD" and the like from under each title; Information still shows it.
- **Game tile glow can be turned off.** Settings ▸ Theme ▸ Components ▸ Game tile glow: follow the
  theme pack, on or off. Packs set it with `components.tileGlow`.
- **Console logos on system folders.** A theme pack can bring a logo for each system
  (`icons/systems/snes.svg`…), shown on the system's folder in Games. Turn them off in
  Settings ▸ Theme ▸ Menu and profile. Xross's own packs ship none.

## 0.2.0.4-beta — 2026-11-17

- **Music playlists.** Make playlists under Music ▸ Playlists and add songs from any song's
  Options ▸ Add to playlist; albums and artists can be added whole. Inside a playlist, songs move
  up, move down or come out; a playlist plays in order or shuffled. Playlists are plain `.m3u8`
  files in `Xross/playlists`, so they travel with the Xross folder, survive a rescan and open in
  other players.
- **Favorites.** A built-in playlist, always first under Playlists. Every song's Options has a
  one-press Add to Favorites (or Remove from Favorites).
- **Sort songs and albums.** All songs sorts by title (A–Z or Z–A), artist, album or recently
  added; Albums by title (A–Z or Z–A), artist or recently added. The choice is in their Options and
  shows under the row.
- **Wallpaper fit.** A picture set as the Xross wallpaper can fill the screen (as before), fit
  inside it with the theme's background around it, stretch to cover it, or sit centered at its own
  size. Pick it in a photo's Options or Settings ▸ Theme ▸ Xross wallpaper.
- **New app icon,** with a matching TV banner and a monochrome icon for themed launchers.

## 0.2.0.3-beta — 2026-11-17

- **New built-in theme pack: Gargantua.** An ASCII black hole, ray traced behind the menu, in
  warm accretion-disk orange over a near-black sky, set in Redaction 50. Pick it in Settings ▸
  Theme ▸ Theme pack. Built-in packs can now bring their own background shader.
- **Video snaps keep playing under the Options and Information panels.** Opening either one on a
  game used to stop its video and show the wallpaper.

## 0.2.0.2-beta — 2026-11-17

- **Surface styles work.** Flat glass, Y2K glossy bezel, OLED true black and E-Ink high contrast
  now change the Options panel, the Information sheet, toasts, the mini player and the button-hint
  bars. Before, only a few rarely seen elements used the chosen style, so the styles looked the same.

## 0.2.0.1-beta — 2026-11-17

First beta.

- **Theme packs:** browser preview for background shaders and packs; the classic look ships as the
  built-in Default (XMB) pack, Phosphor as "Xross"; MECHA shader; Phosphor icons and synthesized UI
  sounds.
- **ScreenScraper sign-in moved to Settings ▸ Accounts.** Enter your own screenscraper.fr user name
  and password to use your own daily quota; saving checks the login and shows today's quota, and a
  blank user name signs out. Settings ▸ Library ▸ Metadata & artwork links to it.
- **ScreenScraper works without setup:** release builds carry Xross's developer credentials, masked
  in the APK.
- **Release builds** are signed from `.env` and refuse to build without ScreenScraper developer
  credentials.
