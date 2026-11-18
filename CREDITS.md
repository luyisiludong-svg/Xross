# Credits

Xross itself is released under the [Apache License 2.0](LICENSE). This file lists the third-party
work it ships with or builds on, and the services it talks to.

## Bundled theme packs

### Xross pack (`app/src/main/assets/themes/xross`)

**Icons** come from [Phosphor Icons](https://phosphoricons.com), MIT License, Copyright (c) 2023
Phosphor Icons. Each file in `icons/` names its source in a comment. The MIT notice ships with the
pack as [`LICENSE.txt`](app/src/main/assets/themes/xross/LICENSE.txt), so it goes along with every
exported copy.

- **Used as-is** (cropped and renamed to the icon key): the fill weight of `file-plus`, `cube`,
  `download-simple`, `list-star`, `folder-simple`, `disc`, `game-controller`, `house-line`, `info`,
  `music-notes-simple`, `globe`, `camera`, `power`, `list-numbers`, `arrows-clockwise`,
  `magnifying-glass`, `toolbox`, `hard-drive`, `trophy`, `users` and `film-reel`.
- **Combined for Xross:**
  - The settings icons `controls`, `display`, `emulators`, `sound` and `system` are `game-controller`,
    `image`, `joystick`, `speaker-high` and `hard-drives`, each with `wrench-duotone` as a badge in
    the top-left corner.
  - `game_cart` is `hard-drive` with a square slot instead of the round light.
  - `platform` is `folder-simple` with `game-controller` cut out of it.
- **Drawn for Xross:** `handheld`, in Phosphor's style.

### Gargantua pack (`app/src/main/assets/themes/blackhole`)

Theme pack by Beluwhal.

- **Shader:** `black_hole.frag`. Its physics follow James, von Tunzelmann, Franklin & Thorne,
  *Gravitational lensing by spinning black holes in astrophysics, and in the movie Interstellar*
  (Class. Quantum Grav. 32, 2015).
- **Font:** [Redaction 50](https://github.com/jeremymickel/Redaction) (Regular and Bold), Copyright
  2019 The Redaction Project Authors, SIL Open Font License 1.1; see
  [`fonts/OFL.txt`](app/src/main/assets/themes/blackhole/fonts/OFL.txt).

## Libraries

| Library | License |
| --- | --- |
| [Jetpack Compose](https://developer.android.com/jetpack/compose), AndroidX Core, Activity, Lifecycle, Palette | Apache 2.0 |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache 2.0 |
| [Media3 / ExoPlayer](https://github.com/androidx/media) | Apache 2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache 2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache 2.0 |
| [XZ for Java](https://tukaani.org/xz/java.html) | 0BSD |

Tests also use [JUnit 4](https://junit.org/junit4/) (EPL 1.0) and
[Robolectric](https://robolectric.org) (MIT).

## Services

Xross uses these services with your own accounts or keys. It isn't affiliated with any of them.

- [ScreenScraper](https://www.screenscraper.fr) and [SteamGridDB](https://www.steamgriddb.com) for
  game metadata and artwork.
- [RetroAchievements](https://retroachievements.org) for achievements and profiles.
- ROM verification reads DAT files you import from projects such as
  [No-Intro](https://no-intro.org) and [Redump](http://redump.org); none are bundled.
