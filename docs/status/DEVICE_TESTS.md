# Device tests

The on-device smoke test and how it was split into tiers. How to run them on a real handheld is in [`HARDWARE_TESTING.md`](../HARDWARE_TESTING.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.2"></a>

## 3.2 Device smoke test — 75/86 on the Galaxy A55 (2026-11-11; the 11 failures are Photo/Music checks that need a device without its own media, [§3.13](../phase-2/STATUS.md#s3.13))
`tools/device-tests/smoke.sh` resets the app, seeds ROMs, and drives the UI with key events.
It checks screen content through the accessibility tree. (Since 2026-11-12 these checks live in
five suites run by `run-all.sh`, and `smoke.sh` is a 12-check quick test: [§3.20](#s3.20).) On 2026-11-11 its key counts were
updated (Options menu, Controls, All Games) and it gained checks for Details/Metadata, the
deadzone cycle, the Platform folders setting, the 10-button remap and the Select shortcut.

| Group | Checks |
| --- | --- |
| Library & navigation | Auto-detect `/sdcard/ROMs`; platforms listed; drill-down; cleaned titles; `.bin` hidden behind `.cue` |
| Favorites | Options hint, menu opens, favorite toggles through the menu, Favorites collection appears |
| Per-game emulator | Emulator submenu lists the platform default; an override is saved and shown as "this game only" |
| Search | Opens from the top of Games; typing finds a game by prefix; result count; R1 to results; Back returns to keys, then closes |
| Categories | L1/R1 movement, Settings sections (incl. Sound) |
| Settings | Theme cycles, wave toggle, Sound section, volume cycles |
| Remapping | Wizard start, 9-button capture, bindings saved, remapped button works, reset |
| Rescan | New file picked up (+1), deleted file dropped (−1) |
| Touch | Swipe left/right moves exactly one category; vertical drag scrolls |
| Fingerprints | Library settings report games identified by content after a rescan |
| Photo | Folders listed; viewer opens at the right photo; next photo; R1 zoom; Back un-zooms then closes |
| Music | Albums from tags; track order; playback through the media session; Now Playing row and screen; next track |
| Video | Player opens; music pauses during the video and resumes after |
| Launch | RetroArch stub receives the right core; ROM readable via granted URI; Recently Played updated; music pauses during the game and resumes after |

<a id="s3.20"></a>

## 3.20 Device tests split into tiers (2026-11-12)

The 755-line `smoke.sh`, one sequential run of 171 checks, is now three tiers in
`tools/device-tests/` that share `lib/harness.sh`: the assertions, navigation, fixture setup
(`setup_base_env`, `setup_media_fixtures`, `setup_theme_fixtures`) and a teardown on EXIT, INT
and TERM. That teardown runs `dumpsys battery reset`, stops the RetroArch stub and deletes the
seeded ROMs, `XrossSmoke` media, theme packs, `.xtheme` files, hero art and the UI dump.

| Tier | Checks | Time (API 37 emulator) |
| --- | --- | --- |
| `smoke.sh` (quick): boot, MainActivity in front, database created, 3 systems scanned, category bar, first platform, L1/R1, same process, no crash | 12 (new) | 26–27 s |
| `test-library.sh` | 41 | 124 s |
| `test-theme.sh` | 88 | 243–249 s |
| `test-media.sh` | 22 | 83 s |
| `test-input.sh` | 11 | 43 s |
| `test-integration.sh` | 9 | 34–37 s |
| `run-all.sh` (= `smoke.sh --all`): the five suites, one passed / failed / skipped table, exit 1 on any failure | 171 | 8 m 58 s |

Each suite clears the app and seeds its own fixtures, so it runs alone and in any order. Checks
that need a missing host tool (ffmpeg, sqlite3) or the stub count as skipped, so the total
stays 171. Moved: the two music-during-a-game checks went from Launch to the media suite. The
Sound section stays in the theme suite, where it sits in the Settings flow. Stricter: the Secrets
check now waits for the three config files and fails if one is missing, instead of passing on an
error message.

**Verified:** the check descriptions in the old script and the new suites match one-for-one
(171 each). `shellcheck` 0.11 is clean (`.shellcheckrc` documents the suppressed house patterns).
`test-theme.sh` passed 88 of 88 alone right after an emulator reboot. `run-all.sh` passed **171 of
171** twice, and the quick smoke 12 of 12 both times. After each run the battery was back to its
own state and no test file was left in `/sdcard`. On the Galaxy A55: [§3.21](../phase-2b/2b.3/STATUS.md#s3.21).

## Fast screen reads with the UI reader (2026-11-16)

The full regression had grown to about 50 minutes. Timing the adb calls on the API 37 emulator
showed a `uiautomator dump` at ~2.0–2.3 s (a new app_process, then a 1 s idle wait) against
0.03–0.13 s for key events, logcat reads and screencaps. About one dump in three also came back
empty. `tools/ui-reader` (Gradle `:ui-reader`, test-only) is an instrumentation started with
`am instrument -w` in the background on the device. It keeps one UiAutomation session and
answers `GET /dump` on the abstract socket `xross-uireader` (`adb forward tcp:0`). The XML is
`uiautomator dump`'s, ported from AOSP's AccessibilityNodeInfoDumper (`NAF` included, its
child-label check looking at invisible children too): on the Settings root and the Games root it
matched byte for byte.

`lib/harness.sh` starts the reader in `suite_begin` and stops it in teardown. `dump()` keeps the
XML on the host (`ui_xml`), and the suites' three direct `cat /sdcard/xross-ui.xml` reads now use
it. Because reads are ~25× faster, `expect`, `expect_any`, `expect_line` and `expect_desc` poll to
a 7 s deadline instead of three tries, and `expect_not` / `expect_no_line` poll up to 3 s for
the text to go. The display-matrix check in the hub section read the grid once 2.5 s after each
`wm size` change, so it caught a relayout (4 rows on 20:9) that the slow dump used to hide; it now
waits up to ~6 s for three rows before measuring.

| Suite | Before (earlier notes) | With the reader (2026-11-16) |
| --- | --- | --- |
| `smoke.sh` | 26–27 s | 11 s, 12/12 |
| library | 124 s | 44 s, 41/41 |
| theme | ~30 min | 1,798 s, 425/426 (the display-matrix relayout, fixed above); alone after the fix, 1,815 s, 425/426 (the known "no live refresh" rerun fail: `Xross/data/ra/friends.json` outlives `pm clear`) |
| media | — | 158 s, 56/56 |
| input | 43 s | 17 s, 11/11 |
| integration | 34–37 s | 23 s, 9/9 |
| `run-all.sh` | ~50 min | 34 m 9 s, 542/543 |

Where the theme suite's 30 minutes go (timestamped run): Export (2b.8b) 774 s and Share (2b.8c)
216 s, the RetroAchievements hub 143 s, boot intro 93 s, the four Components sections 78–92 s
each, Theme packs 76 s, the rest under 50 s each. Each Export/Share look comparison (`xshots`) is
~42 s: an app restart, then 8 places at ~2.4 s of navigation and a 1.5 s settle each; the pixel
diff itself is 0.2 s. `run-all.sh` and `smoke.sh` now declare the real total, 543 checks.

**Overlap trimmed from the theme suite (2026-11-16): 426 → 416 checks, 1,815 s → 1,190 s,
416/416.** Each removed check repeated a unit test, at a device-side cost:

| Removed | Kept on the device | Covered by |
| --- | --- | --- |
| 7 of the 10 Export/Share look comparisons (Default's current look, the stress pack whole with Ember, its three variant-only exports, both Share re-imports): 14 of 18 eight-screen captures, ~42 s each | The built-in Default pack whole (APK assets), the stress pack's current look (overrides and rules flattened), the picked-wallpaper export (a real read grant); every export still runs and its written files are checked | `ThemeExportTest`, `ThemeRoundTripTest` (every scope, and archives), `LookExporterTest` |
| 9 of the hub's 12 display-matrix sizes | 4:3 retro 640×480, 20:9 phone, portrait | `HubUpgradeTest.gridColumnsFollowTheWidthAndCellsFitExactlyThreeRows`; `display-matrix.sh` walks all 12 |
| 2 of 5 launch curtains (FADE_BLACK, BLOOM) | The pack's IRIS, the user's FLASH over it, E-Ink's black | `BootSequenceTest` (names, shapes and timing, the cascade) |
| The avatar HUD cycle for the battery-first order | The default order and the one with a left cluster | The same `hud_cycle`; the battery-first order's positions are still checked |

Export now takes 388 s (was 774). `run-all.sh` declares 533 checks; estimated at ~24 min
(the other suites unchanged from the run above).

