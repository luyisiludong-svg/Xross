# ScreenScraper API v2: English guide

A working summary of ScreenScraper's API page (`https://www.screenscraper.fr/webapi2.php`, French,
"v2 beta"), translated and narrowed to what Xross uses. The saved page itself
(`docs/SCREENSCRAPER_api_docs.pdf`) is git-ignored because it was saved while signed in and
contains account credentials. Xross's client is `scrape/providers/ScreenScraperProvider.kt`.

## Who may use it

- The API may only be built into **completely free, distributed applications**. Anything else
  needs ScreenScraper's prior authorization and conditions; breaking this can get accounts cut
  off (the page also mentions legal action).
- Developers get credentials by **presenting their software on the ScreenScraper forum**. Every
  call carries them.
- v2 is a beta: ScreenScraper may change it without notice.

## Credentials (every request)

| Parameter | Meaning | Required |
| --- | --- | --- |
| `devid`, `devpassword` | Developer ID and password issued to the application | Yes |
| `softname` | Name of the calling software (Xross sends `Xross`) | Yes |
| `ssid`, `sspassword` | The **user's** ScreenScraper login (user name, not email) and password | No; raises the user's limits |
| `output` | `xml` (default) or `json` (Xross uses `json`) | No |

Requests are plain `GET`s to `https://api.screenscraper.fr/api2/<endpoint>.php?…`.

## Limits ("threads" and quotas)

- **Threads** are simultaneous requests. Users earn more by contributing data or media, or by
  supporting hosting financially (Tipeee/Patreon). Xross always uses one.
- **Quotas** limit requests per minute and per day, per user, by level and contribution.
- **Quota handling in the client is mandatory.** Every answer includes the user's block
  (`ssuser`), which clients must respect:

| Field | Meaning |
| --- | --- |
| `id` | User name |
| `maxthreads` | Simultaneous requests allowed |
| `maxdownloadspeed` | Download speed allowed (KB/s) |
| `requeststoday` | Requests made today |
| `requestskotoday` | Requests today that found nothing |
| `maxrequestspermin` | Requests allowed per minute (spelled `maxrequestsperdmin` in one place) |
| `maxrequestsperday` | Requests allowed per day |
| `maxrequestskoperday` | Not-found requests allowed per day |
| `favregion` | User's favourite region (france, europe, usa, japon) |

Xross paces requests at 10% under `maxrequestspermin` (never faster than one every 1.2 s), and
stops on its own once `requeststoday` or `requestskotoday` reaches its daily maximum.

## Errors (HTTP status)

| Code | Meaning | Xross does |
| --- | --- | --- |
| 400 | Malformed call: missing required field, bad hash format, or a file name containing a path | Treats the file as not found |
| 401 | API closed to non-members or inactive members (server CPU above 60%) | Waits 5 minutes, retries tomorrow |
| 403 | Login error: "check your developer credentials" or "check the user credentials" | Says which one in Settings |
| 404 | Game, or ROM/ISO/folder, not found | Not found |
| 423 | API completely closed (serious server problems) | Waits, retries tomorrow |
| 426 | This scraping software is blacklisted (non-compliant or outdated version) | Stops; needs an update |
| 429 | Thread limit reached (several variants) | Backs off 30 s |
| 430 | Daily scrape quota exceeded | Stops ScreenScraper for the day |
| 431 | Too many unrecognized ROMs today: "sort out your ROM files and come back tomorrow" | Stops ScreenScraper for the day |

## `jeuInfos.php`: identify a game and get its data and media

| Parameter | Meaning |
| --- | --- |
| `crc`, `md5`, `sha1` | Hashes of the **local file as stored** (ROM, ISO or folder)* |
| `romtaille` | Size in bytes of the file or folder* |
| `systemeid` | Numeric system ID (see below) |
| `romtype` | `rom` (single ROM file), single ISO file, or folder |
| `romnom` | File name with extension, or folder name. **No path** (that is a 400) |
| `serialnum` | Force the lookup by the disc's serial number |
| `gameid` | Force the lookup by ScreenScraper's game ID (no ROM information is returned then) |

\* Unless ScreenScraper grants an exemption, send at least one hash (ideally all three) **and**
the size. Xross sends hashes of the stored file (for zipped ROMs, the ROM inside; for arcade sets,
the zip itself). Files too large to hash fall back to a name search and a `gameid` lookup, used
only when a result's name matches exactly.

### Response (`jeu` item)

| Field | Meaning |
| --- | --- |
| `id`, `romid` | Game ID, ROM ID |
| `notgame` | `true` when the file is a non-game (demo, application); names then start with `ZZZ(notgame):` |
| `noms` | Names by region: `nom_ss` (ScreenScraper's own) and `nom_xx` per region |
| `regionshortnames`, `cloneof` | ROM regions, clone-of ID |
| `systeme` | `id`, `nom`, `parentid` |
| `editeur`, `developpeur` | Publisher, developer |
| `joueurs` | Number of players |
| `note` | Rating **out of 20** (Xross stores it as 0–1) |
| `topstaff`, `rotation`, `resolution` | Staff pick; screen rotation and resolution (arcade only) |
| `synopsis` | Description per language: `synopsis_xx` |
| `dates` | Release date per region |
| `genres`, `modes`, `familles`, `numeros`, `themes`, `styles` | IDs plus localized names (`genres_xx`, …). `familles` is the series ("Sonic the Hedgehog") |
| `medias` | Download URLs (below) |

Region codes come from `regionsListe.php` (e.g. `wor` world, `us`, `eu`, `jp`, `fr`), language
codes from `languesListe.php` (e.g. `en`, `fr`, `de`). In JSON, Xross also accepts the common
`[{"region": "us", "text": "…"}]` form for names, dates and synopses.

### Media

| Key | Content | Xross uses it as |
| --- | --- | --- |
| `media_boitiers_3d` → `media_boitier_3d_xx` | 3D box, per region | Game icon, Details box art |
| `media_boitiers_2d` → `media_boitier_2d_xx` | 2D box front | Fallback box art |
| `media_wheels` → `media_wheel_xx` | Colour logo (also carbon and steel variants) | Details logo |
| `media_screenshot` | Screenshot | Details background fallback |
| `media_fanart` | Fan art / wallpaper | Details background |
| `media_video` | Gameplay video | Video snap |
| `media_marquee`, `media_screenmarquee`, `media_flyer_xx`, `media_manuel_xx`, `media_supports_*`, `media_boitiers_texture` | Marquee, flyers, PDF manual, disc/cart images, box textures | Not used yet |

Multi-disc and multi-page media append a number (`media_support_2d_eu2`, `media_flyer_wor1`).
In the media-type list, the matching short names are `box-2D`, `wheel`, `wheel-hd`, `ss`,
`fanart` and `video`. Separate endpoints (`mediaJeu.php`, `mediaVideoJeu.php`,
`mediaManuelJeu.php`) download a single medium by `jeuid` + `media` type.

## `jeuRecherche.php`: search by name

Parameters: the credentials, optional `systemeid`, and `recherche` (the name). Returns up to 30
games ranked by likelihood (`jeux`), with the same fields as `jeu` minus ROM information. Xross
uses it for Options ▸ Metadata ▸ Find match, and for files too large to hash.

## Other endpoints

`ssinfraInfos.php` (server load), `ssuserInfos.php` (the user's level and quotas; Xross's
"Check ScreenScraper account"), list endpoints (`systemesListe.php`, `regionsListe.php`,
`languesListe.php`, `genresListe.php`, `mediasJeuListe.php`, …), and contribution endpoints
(`botNote.php` for ratings, `botProposition.php` for proposing data or media).

## System IDs Xross uses

Read from the platform media paths on the API page; confirm with `systemesListe.php` once
developer credentials are available.

| Xross platform | `systemeid` | Xross platform | `systemeid` |
| --- | --- | --- | --- |
| Mega Drive / Genesis | 1 | Saturn | 22 |
| Master System | 2 | Dreamcast | 23 |
| NES | 3 | PC Engine | 31 |
| SNES | 4 | PlayStation | 57 |
| Game Boy | 9 | PlayStation 2 | 58 |
| Game Boy Color | 10 | PSP | 61 |
| Game Boy Advance | 12 | Arcade (MAME) | 75 |
| GameCube | 13 | ScummVM | 123 |
| Nintendo 64 | 14 | Switch | 225 |
| Nintendo DS | 15 | Game Gear | 21 |
| Wii | 16 | Nintendo 3DS | 17 |
