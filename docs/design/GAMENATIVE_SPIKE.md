# GameNative PC shortcuts — research spike (0.2.2.0 § 6)

Read from GameNative's source on `master` (github.com/utkarshdalal/GameNative, version
1.4.1-prerelease) on 2026-11-21. Nothing here was run against a real install.

## How a game can be started from outside

- Package `app.gamenative` (the "gold" build: `app.gamenative.gold`). `MainActivity` is exported,
  no permission.
- **Intent action** `app.gamenative.LAUNCH_GAME` (category DEFAULT) with extras `app_id` (Int, the
  store's game id, must be > 0), `game_source` (String: a `GameSource` name such as `STEAM` or
  `GOG`; missing means `STEAM`), and optionally `exe`, `exec_args` and `container_config` (JSON,
  in memory only).
- **URI** `gamenative://run?appid=…&gamesource=…` (VIEW, BROWSABLE), same fields minus
  `container_config`.
- GameNative's own home-screen shortcuts are pinned shortcuts (`ShortcutManager.requestPinShortcut`,
  id `game_<appId>`) that carry exactly that intent, with dynamic shortcuts (max 4) as the fallback.

## What it means for Xross

- **No launcher privileges needed.** Xross doesn't have to read shortcuts through
  `LauncherApps.getShortcuts` (which works only while it is the default home app): the intent is
  public. A launch is one `Intent("app.gamenative.LAUNCH_GAME")` with two extras.
- **The hard part is the game list.** GameNative writes no per-game files a frontend could scan
  (not confirmed for its container folders). Xross would need the ids from somewhere: the user
  adding games one by one (an "Add GameNative game" row asking for the store and id, or a
  `.gamenative` file per game in a `pc` folder holding `STEAM:620` and scanned like `.ps3` files),
  or, as home app, reading GameNative's pinned shortcuts with `LauncherApps` when they exist.
- This can't be expressed as an `emulators.json` recipe today: the id isn't a file path. A
  `%INJECT%`-style recipe could read it from a launcher file (`%EXTRAINTEGER_app_id%` needs the
  value as a literal), so it needs a small built-in recipe that reads `SOURCE:ID` from the file.

## Size and recommendation

**S (half a day)** for launcher files: a `pc`/`gamenative` system scanning `.gamenative` files,
a built-in recipe reading `SOURCE:ID`, and a user-guide section. **M** more to import the user's
pinned GameNative shortcuts automatically while Xross is home (and fall back to files when it
isn't). Not built in 0.2.2.0; proposed for 0.2.3.0, after checking the intent on a real install.
