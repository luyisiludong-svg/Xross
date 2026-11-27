# Credits

Xross itself is released under the [Apache License 2.0](LICENSE). This file lists the third-party
work it ships with or builds on, the projects that inspired it, and the services it talks to.

## Inspiration

Xross owes its ideas to a lot of other work. None of the projects below contributed code or
assets unless stated otherwise.

### Frontends

- **Sony's XMB** on the PS3 and PSP, the interface Xross is modelled on. Its layout and behaviour
  were studied from the consoles and Sony's official user manuals.
- **Wave Launcher.** Xross began as a plan to fork Wave Launcher. Wave is closed source, so Xross
  was written from scratch instead, with Wave's design as one of its visual references. Xross
  contains no code or assets from Wave Launcher.
- **[ES-DE](https://es-de.org).** Xross follows ES-DE's conventions so libraries move between the
  two: platform ids match ES-DE's system names, artwork uses ES-DE's media folder layout, and
  several emulator launch recipes come from ES-DE's published Android rules.
- **Daijishō and Beacon**, whose approach to Android emulation frontends showed the shape Xross's
  library and launching took.

### Theming and visuals

- **[Wallpaper Engine](https://www.wallpaperengine.io)** inspired the animated, shader-driven
  backgrounds.
- **Linux ricing and [r/unixporn](https://www.reddit.com/r/unixporn/)**, and setups built on tools
  like Hyprland and Waybar, inspired theme packs: plain files you can edit, share and swap.
- **JIN2 (@jin_22ee).** JIN2's black hole work inspired the Gargantua pack's shader. Xross's
  `black_hole.frag` is an independent implementation built from the published physics of light
  bending around a spinning black hole (see the
  [Gargantua pack](#gargantua-pack-appsrcmainassetsthemesblackhole) below) and uses none of JIN2's
  code.

Xross is an independent project. It is not affiliated with or endorsed by Sony Interactive
Entertainment or any of the projects and people above.

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

### MEMENTO//MORI pack (`app/src/main/assets/themes/memento_mori`)

Theme pack by Beluwhal.

- **Wallpaper:** `wallpapers/specimen.png`, the pack author's own fan illustration of Persona 3
  Reload. Its character, the S.E.E.S. armband and the P3R mark (also written by the shader) belong
  to ATLUS and SEGA; the pack is not affiliated with or endorsed by them. No game files are used.
- **Shader, icons and wordmark** were made for this pack.
- **Fonts:** [Arsenal](https://fonts.google.com/specimen/Arsenal), SIL Open Font License 1.1 (see
  [`fonts/Arsenal-OFL.txt`](app/src/main/assets/themes/memento_mori/fonts/Arsenal-OFL.txt)), and
  Redaction 50, Copyright 2019 The Redaction Project Authors, SIL Open Font License 1.1 (see
  [`fonts/OFL.txt`](app/src/main/assets/themes/memento_mori/fonts/OFL.txt)).

### UNIT//TERMINAL pack (`app/src/main/assets/themes/unit_terminal`)

Theme pack by Beluwhal. Original work: no game assets, fonts, logos, names or music are used. The
shaders, filters, icons, wordmark and sounds were made for this pack; the audio is synthesized and
contains no samples.

### BREAKCORE.ERR pack (`app/src/main/assets/themes/breakcore_err`)

Theme pack by Beluwhal. The shader, filter, icons, wordmark and sounds were made for this pack; the
audio is synthesized and contains no samples.

- **Font:** Redaction 50, Copyright 2019 The Redaction Project Authors, SIL Open Font License 1.1;
  see [`fonts/OFL.txt`](app/src/main/assets/themes/breakcore_err/fonts/OFL.txt).

## Console logos (`app/src/main/assets/system_logos`)

The console logos on system folders are by
**[Dan Patrick](https://forums.launchbox-app.com/profile/85709-dan-patrick/)**, from his platform
logo collection (v2.1, Recommended Versions) shared on the LaunchBox Community Forums. Dan Patrick
drew them by hand and gave permission to use them in other projects. Thank you!

- **Styles:** `color`, `mono`, `white`, `dark_color`, `dark_mono` and `black` are his Light -
  Color, Light - Black & White, Light - Just White, Dark - Color, Dark - Black & White and Dark -
  Just Black sets; `outline` is his thin white outline set.
- **Changed for Xross:** cropped, scaled down to fit 512 x 256 and saved as WebP, one per Xross
  system, by [`tools/system_logos/import_logos.py`](tools/system_logos/import_logos.py).
- The logos themselves are trademarks of their owners; neither Dan Patrick nor Xross claims them,
  and per the author's terms they are never to be sold.

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
