# Changelog

## Unreleased

- **ARMSX2 for PlayStation 2.** Pick it for PS2 in Settings ▸ Emulators, or per game in Options ▸
  Emulator; NetherSX2 stays the default. The stable and nightly builds are both found. A cue+bin
  game is handed to ARMSX2 as its first track, because ARMSX2 can't boot a `.cue` it can't see
  the tracks of.

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
