# Changelog

## Unreleased

- **Images for theme shaders.** A pack's own background shader can sample up to four of its
  images (`effects.shaderImages`: PNG, JPEG or WebP, with `wrap`, `filter` and `mipmap`) as
  `uImage0`–`uImage3`, and gets the focused item's position as `uFocus`. Paintings can now ripple,
  drift in haze and shift in depth. Images are capped at 2048 px and 8 MP per shader; replacing one
  reloads like an edit to the shader. See the shader guide §8 and the tutorial's painted scene.
- The desktop shader preview binds a pack's shader images and moves `uFocus` with the mouse.
- **Video backgrounds.** A theme can bring a looping, muted video (`effects.videoPath`: MP4 with
  H.264 or HEVC, or WebM with VP9) placed by `effects.videoFit` (`FILL`, `FIT`, `STRETCH`,
  `CENTER`). Left at the wave it plays as is, through the new `VIDEO` background; a theme's own
  shader reads it as `uVideo` and can bend it (glass, haze, parallax), and the background filter
  still applies. It holds its frame while art covers it, while a snap or the boot video plays and
  when the screen rests (never sooner than 10 s after a button); a game in front frees its decoder,
  and it picks up where it left off on return. Still mode shows a poster. A video the device can't
  play shows the theme's wallpaper, else the wave. See the theme specification and shader guide §9.
- The shader preview plays a theme's video (`?video=`, `&fit=`) and has a video example.
- **Layered scenes, first part.** A theme can describe its background as a stack of layers in a
  `scene.json` (`effects.scenePath`): images, videos, sprite sheets, shader layers and particles,
  each with opacity, blend, placement, parallax, a mask and motion. Xross reads and checks the
  whole format now, lists any problem per layer, keeps a scene within its budget, and shows the
  scene's poster (or its first image) as the background; the layers are drawn in a later update.
  Editing the scene or any of its files reloads it, and exports carry them. See the theme
  specification's Layered scenes.
- **Layered scenes draw their images and sprites.** Image and sprite-sheet layers are now drawn,
  with placement, blend (normal, add, screen, multiply), opacity, masks, scrolling, sway, pulse,
  parallax that follows the focused item, and motion driven by the music, category switches and
  the time of day. `"resolution": "sharp"` renders the background at 1920 px instead of 1280.
  Video, shader and particle layers follow in later updates.

## 0.2.2.0-beta — 2026-11-21

- **Metadata & Media editor.** Options ▸ Metadata & Media now opens a full screen for the game
  instead of a submenu. Edit the title or the description, find a match, scrape again or reset,
  and see every art slot with a preview and where its art came from: Box art, 3D box, Capsule,
  Hero background, Logo, Screenshot, and Video snap when Scrape videos is on. L1 / R1 move to the
  previous or next game and keep your place, so you can fix the same slot down a whole list.
- **Choose a game's art.** Confirm on a slot shows everything ScreenScraper and SteamGridDB have
  for it (every region, every style), or use an image from the device, or remove it. Art you
  choose or remove stays that way: Scrape again leaves it alone until Reset media. Offers are
  only asked for when you open a slot, so browsing games costs no ScreenScraper quota. When there
  is nothing to choose from, the picker says why (no SteamGridDB key, quota used up, no match).
- **Your own description.** It shows on Details instead of the scraped one; an empty one brings
  the scraped description back. Reset metadata clears it with your title and match.
- **Your own emulators and systems.** `Xross/config/emulators.json` takes launch recipes in
  ES-DE's command syntax (`%ACTION%`, `%DATA%`, typed `%EXTRA…%` tags, flags, `%INJECT%`), and an
  id in `systems.json` that Xross doesn't ship, with a name and file types, is a system of its own.
  Comments are allowed. A bad entry is skipped and its problem shown in Settings ▸ Emulators; the
  rest still load. A recipe counts as installed only when its package and its activity exist.
  Commented samples are written beside the files.
- **Add emulator… and Add system…** in Settings ▸ Emulators, without editing JSON: pick an app,
  its activity, how it gets the game and its systems, **Try it** on a real game, then save; or
  pick a folder (unclaimed ones first), its name, file types, logo and emulator. Xross writes the
  files and only ever touches entries it made (`"source": "device"`); your hand-written text and
  comments stay as they are.
- ES-DE's PC Engine CD folders (`pcenginecd`, `tg-cd`, `turbografxcd`, `supercd`) scan as PC Engine.
- **Theme music, your way.** Settings ▸ Theme ▸ Theme music: the theme pack's, a file, a folder of
  up to 50 tracks (crossfading, shuffled or in order) or off. Theme packs can list several tracks
  too (`audio.bgmFiles`, `audio.bgmOrder`). After a game a list continues with its next track.
- **Missing art filter.** Options on a system folder ▸ Show ▸ Missing art lists only the games
  without box art, a hero or a logo, and Metadata & Media pages through just those.
- **Fix media…** goes through a whole system: a console strip, filters for what's missing, and a
  badge per art kind on every game; Confirm opens Metadata & Media, Back returns to the same game.
- **Albums and playlists as backgrounds.** Photo ▸ an album ▸ Options ▸ Use as Xross wallpaper
  lets the album's pictures take turns behind the menu, and Music ▸ Playlists ▸ a playlist ▸
  Options ▸ Use as theme music plays it as the menu's music (also under Settings ▸ Theme ▸ Xross
  wallpaper ▸ An album and Theme music ▸ A playlist).
- **Wallpaper rotation.** Xross wallpaper ▸ Pick a folder… lets its pictures take turns, every few
  minutes or each time Xross comes to the front, crossfading; a change waits for your next input,
  so it never wakes a resting screen.
- The library database moves to version 6 (three new columns; nothing existing changes). The
  first start after updating checks once which art each game has.

## 0.2.1.0-beta — 2026-11-19

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
- **Console logos on system folders.** Every system folder in Games now shows its console's
  logo instead of the same icon for all. Pick the look in Settings ▸ Theme ▸ Menu and profile ▸
  Console logos on system folders: Theme pack (the pack's own logos, else White), White, Color,
  Black & white, Color outlined, three styles for light backgrounds, or Off. Wide logos keep
  their shape instead of shrinking into the square icon slot. The logos are by
  [Dan Patrick](https://forums.launchbox-app.com/profile/85709-dan-patrick/), shared on the
  LaunchBox Community Forums. A theme pack can bring its own (`icons/systems/snes.svg`…).

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
