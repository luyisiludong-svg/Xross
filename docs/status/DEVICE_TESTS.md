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
