# XMB Fidelity Assessment

How close Xross is to the look and feel of the real XrossMediaBar, and what is still missing.
The goal from the scope is a launcher that feels **exactly like the XMB**, with extra features on
top. First assessed **2026-11-11** against the code at `1c1788b`; ratings updated the same day
for the code at `2c142d6` (Phase 2b.1). The remaining work is tracked in
[`Roadmap.md`](../Roadmap.md) §4.6.

## Sources

| Source | What it covers |
| --- | --- |
| PS3 instruction manual, CECH-2501A, system software 3.30 (`assets/docs/CECH-2501A-3.30_1.pdf`) | XMB basics (p. 11), PS button, options menu, on-screen keyboard (p. 34), USB content (p. 25), background color behavior (p. 44), settings layout |
| PSP E1000 instruction manual, system software 6.50 (`assets/docs/ENUK_PSP-E1002_E1003-6.50.pdf`) | XMB and categories (pp. 16–17), multi-tap keyboard (pp. 20–21), Theme Settings color behavior (p. 29), power saving |
| PSP Go instruction manual (`assets/docs/PSP-N1004_PSP Go_IM$en.pdf`) | XMB with busy icon (p. 24), categories (p. 25), Pause/Resume Game (p. 37), battery icon (p. 21) |
| PSP home-menu screenshots (`assets/examples/main.*.jpg`, `PSP-BloodRed-Theme-4.webp`) | Proportions, typography, status bar, list layout |
| Xross on a Galaxy A55 and the Android emulator (2026-11-10) | Current look and behavior |

The manuals are hardware guides, so they describe the XMB's rules rather than its exact visuals.
Visual comparisons come from the reference screenshots. They are Sony documents kept for reference
only and must not ship in a release.

**Legend:** ✅ matches the XMB · ◐ close or partial · ❌ missing · ➕ Xross extra (not in the XMB)

---

## Verdict

**Update (`2c142d6`): much closer.** Games now show their own art as XMB-style 16:9 tiles, and
resting on one crossfades the background to its hero art and then its video snap with its own
audio. The Original theme changes color each month, a busy icon shows while Xross reads data, the
Information screen and a hint-pill switch exist, and sounds have proper cursor and error roles.
What still breaks the feel: the **typeface** (Android default), **Resume/Quit Game**, the
**drill-down breadcrumb**, the **playback control panel**, the keyboard modes and the missing
Delete/sort options. The original assessment follows; the tables below carry the updated
ratings.

**Original verdict (`1c1788b`): not there yet, but the foundation is right.** Xross's home menu already reads as an XMB at a
glance: the horizontal category bar with one category visible to the left, the vertical item
column, rows scrolling up past the bar, the enlarged and glowing focused icon, the title with a
hairline and a focus-only subtitle, the date/time/battery status in the top right, the
right-hand options panel, and the flowing wave background all match the references.

The XMB feel breaks down in three places:

1. **Content art.** On the XMB every game has its own icon, and focusing it changes the
   background to the game's artwork, with its own audio. Xross shows the same disc or cartridge
   glyph for every game, so the Games category feels like a file list. This is the largest gap,
   and it depends on the scraper (Phase 2).
2. **Typography and sound.** The XMB uses a distinctive rounded system typeface. Xross uses the
   Android default font, which is the most visible difference in a side-by-side. Xross also has no
   dedicated cursor sound (moves reuse the options sound) and its error sound is silent.
3. **System behaviors.** Several things the manuals describe are missing: the background color
   that changes by month (PSP) or by time, day and month (PS3), resuming or quitting the running
   game from the home menu, the busy icon while reading data, an Information screen in the
   options menu, the playback control panel, and the XMB's way of showing an opened folder.

Xross also always shows a button-hint pill. That's a useful extra, but the XMB home menu has no
on-screen hints, so it should be possible to turn it off.

| Area | Rating | Summary |
| --- | --- | --- |
| Navigation model | ✅ | Categories ↔, items ↕, confirm, cancel, options |
| Home menu layout and motion | ✅ | Proportions and motion match the PSP references |
| Drill-down into folders | ◐ | Works, but shows a text breadcrumb instead of the XMB's folder-icon column |
| Content icons and backgrounds | ✅ | Per-game tiles; hero art then video snap (with audio) behind the focused game |
| Typography | ❌ | Android default font |
| Sound | ◐ | Boot, game-start, confirm, cancel, cursor and error roles; the Default pack's cursor sound is still the options tick |
| Background and themes | ◐ | Wave, day/night brightness, monthly color (Original), theme packs; no photo wallpaper behind the menu |
| Options menu | ◐ | Correct panel and behavior, with Information; no Delete or sort options |
| Media playback controls | ◐ | Direct button controls instead of the XMB control panel |
| Game session (PS button) | ❌ | No Resume Game or Quit Game from the home menu |
| Status and indicators | ✅ | Date, time and battery match; busy icon. Xross extra: the profile avatar at the far right |
| On-screen keyboard | ◐ | A grid keyboard; no input modes, lower case or multi-tap |

---

## 1. Navigation and layout

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| Left/right selects a category, up/down selects an item, confirm opens, cancel goes back | PS3 p. 11; PSP p. 16; PSP Go p. 24 | Same, plus L1/R1 and touch | ✅ |
| Only the focused category shows its label | References | Same | ✅ |
| One category stays visible to the left of the focused one | References | Same, on every aspect ratio | ✅ |
| Focused item is larger, glows, and has a hairline under its title; subtitle only on the focused item | References | Same, with a slow glow pulse | ✅ |
| Items scroll up past the category bar instead of disappearing | References | Same; rows above the bar fade near the top edge | ✅ |
| Short, smooth transitions | — | Critically damped springs (~200 ms) | ✅ |
| Opening a folder keeps the folder's icon to the left of its contents; no text breadcrumb | XMB behavior | The bar slides left leaving the category icon, and a text breadcrumb appears | ◐ |
| Category names | PSP p. 17: Game, Video, Music, Photo, Settings (plus Network, PlayStation Network) | Settings, Photo, Music, Video, Games, Apps. "Apps" stands in for Network/Extra; "Games" is plural | ◐ |
| Landscape | — | Locked to landscape, flips 180° | ✅ |
| No on-screen button hints on the home menu | References | Hint pill, which Settings ▸ Theme ▸ Button hints turns off | ✅ ➕ |

## 2. Content presentation

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| Each game has its own icon | References; an inserted game disc appears as its own icon (PS3 p. 24) | 16:9 tile with the game's logo or wide art; the glyph only for unscraped games | ✅ |
| Focusing a game fades the background to that game's artwork, sometimes with its own music | XMB behavior | Hero art after 250 ms, then the video snap with its sound after 1.2 s; music ducked | ✅ |
| Photo, Music and Video show thumbnails and album art | XMB behavior | Thumbnails and album art from MediaStore | ✅ |
| An inserted disc or connected USB device appears as an item in the matching category | PS3 pp. 24–25; PSP p. 19 | Rescans silently and shows a toast; no per-device item | ◐ |
| Restricted content is shown as such | PSP p. 23 | Not applicable (no parental controls) | — |

## 3. Look, typeface and themes

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| Flowing wave background | References | GLSL ribbon shader with glossy sheets and strands | ✅ |
| Background color changes automatically at the start of each month (PSP) | PSP p. 29; PSP Go p. 43 | The Original theme: a color per month, blended around month changes | ✅ |
| Default background changes with the time, day and month (PS3) | PS3 p. 44 | Brightness follows the time of day; color doesn't change | ◐ |
| Theme Settings: color, theme packs, wallpaper | PSP p. 29 | Palettes and theme packs (`Xross/themes`); no wallpaper | ◐ |
| "Set as wallpaper" puts a photo behind the XMB | XMB behavior | Sets the Android system wallpaper; the XMB always shows the wave | ◐ |
| Rounded system typeface with soft drop shadow | References | Android default font with a drop shadow; a theme pack can supply a font, but none ships by default | ❌ |
| Status: date, time, battery percentage and icon, top right | References; PSP Go p. 21 | Same format; the battery bar turns red at 15% | ✅ |
| Busy icon while reading data or using the network | PSP Go p. 24 | Ring of dots while scanning, fingerprinting, importing DATs or scraping | ✅ |

## 4. Sound

| XMB behavior | Xross | State |
| --- | --- | --- |
| Boot chime | Startup sound once per process start | ✅ |
| Game-start sound and animation | Game-start cue with a darken-and-bloom animation | ◐ |
| Confirm and cancel sounds | Yes | ✅ |
| Distinct cursor sound for moving | `cursor.wav` role for every move; the Default pack's file is the options tick at a lower gain | ◐ |
| Error sound | `system_ng.wav` for errors and refused actions | ✅ |

## 5. Options menu, control panel and settings

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| △ on any icon opens the options menu as a panel from the right | PS3 p. 11; PSP p. 17 | Same, with submenus; long-press on touch | ✅ |
| Options include Information | PS3 p. 35; PSP p. 23 | Options ▸ Information: platform, file, size, dates, playtime, emulator, DAT match, hashes | ✅ |
| Options include Delete, Copy, Move, Display All, sort orders | PS3 pp. 25, 37 | Not available | ❌ |
| △ during playback opens a control panel (grid of playback controls) | PS3 p. 11; PSP p. 17; PSP Go p. 25 | Direct button controls with an auto-hiding hint row | ◐ ➕ |
| Settings grouped into System, Theme, Display, Sound, Power Save, Date and Time, Security, Network | PS3 pp. 33–44; PSP pp. 22–31 | Library, Controls, Emulators, Theme, Sound, Media, System. Display, date/time, power and network are left to Android | ◐ by design |
| Restore default settings | PS3 p. 42; PSP p. 31 | Not available | ❌ |

## 6. Game session and system

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| The PS button during a game returns to the home menu with Quit Game | PS3 p. 26; PSP p. 18 | Android's Home returns to Xross, but Xross doesn't know a game is running | ❌ |
| Pause Game, then Resume Game from the Game category | PSP Go p. 37 | No resume row; launching again relaunches through the emulator | ❌ |
| Backlight auto-off, auto sleep, screensaver on an external display | PSP p. 29; PSP Go p. 43 | Left to Android; no screensaver when docked | ◐ |
| Initial setup (language, time zone, date and time, user name) before the XMB | PS3 p. 15 | Android handles these; Xross's first-run onboarding is Phase 4 | ◐ by design |

## 7. On-screen keyboard

| XMB behavior | Source | Xross | State |
| --- | --- | --- | --- |
| PSP: phone-keypad multi-tap layout; SELECT cycles input modes (letters, accented letters, numbers, web shortcuts) | PSP pp. 20–21 | Not available | ❌ |
| PS3: full keyboard with predictive text and a mini-size layout | PS3 p. 34 | 10-column grid, upper case only, no modes or prediction | ◐ |
| Hardware keyboards type directly | PS3 p. 34 | Yes | ✅ |

## 8. Xross extras beyond the XMB

These are the "better features" and should stay, ideally without changing how the XMB looks by
default: gamepad search over the whole library, favorites and Recently Played, playtime,
per-game emulator choice, touch navigation, hot-swap with "Not connected" games, content
fingerprints, background music while browsing, color palettes, and the button-hint pill (which
should become a setting).

---

## 9. Work needed to close the gap

Ordered by how much each item adds to the XMB feel, as assessed at `1c1788b`. Items marked ✅
have been done since; the open ones are tracked in [`Roadmap.md`](../Roadmap.md) §4.6.

| # | Item | Depends on | Size |
| --- | --- | --- | --- |
| 1 ✅ | Per-game icons in the Games lists, with a background crossfade to the game's art on focus | Scraper and asset cache (Phase 2) | Large |
| 2 | XMB-style typeface: bundle an open-licensed rounded sans as the default, allow a user-supplied font through theme packs | Theme pack loader for user fonts | Small (default font), Medium (loader) |
| 3 ✅ | Monthly background color ("Original" theme that changes each month), with the existing day/night brightness | — | Small |
| 4 | Resume Game / Quit Game row at the top of Games while an emulator launched by Xross is still running | — | Medium |
| 5 ✅ | Busy icon (animated, bottom or top right) while scanning, fingerprinting or loading media | — | Small |
| 6 ✅ | Setting to hide the button-hint pill, off by default in an "XMB classic" preset | — | Small |
| 7 ◐ | Distinct cursor sound and a real error sound (user-supplied through sound packs) | Theme packs for distribution | Small |
| 8 | XMB-style drill-down: the opened folder's icon stays to the left of its contents instead of the text breadcrumb | — | Medium |
| 9 ✅ | Information screen in the options menu (title, platform, file, size, hashes, playtime) | — | Small, richer after the scraper |
| 10 | Playback control panel on △ in the video and music players, alongside the direct controls | — | Medium |
| 11 | XMB background options: photo wallpaper behind the menu, theme pack backgrounds | Theme packs | Medium |
| 12 | Connected drives as items in their categories while mounted | Hot-swap (done) | Medium |
| 13 | Keyboard input modes: lower case, accented letters, symbols; optional multi-tap keypad layout | — | Medium |
| 14 | Options: Delete (with confirmation), sort orders, Display All | — | Medium |
| 15 | Screensaver when docked or idle on a TV | Dock detection (Phase 3) | Small |

Item 2, the typeface, is now the most visible single difference on the home menu. Item 7 has
its sound roles; it needs an original cursor sound in the Default pack.

**Licensing note.** The XMB's typeface, icons, sounds and theme assets belong to Sony. Xross
should match the feel with original or open-licensed assets by default, and let users load their
own through theme packs. The bundled sounds and icons already need replacing before any public
release (see `Roadmap.md` §3 and §4.2).
