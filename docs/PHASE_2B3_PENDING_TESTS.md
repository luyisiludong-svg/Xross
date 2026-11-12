# Phase 2b.3 — what is still to be tested

Branch `feat/theme-2b3` (from `feat/theme-2b2`), updated 2026-11-12 after an emulator pass and a
hardware pass on the Galaxy A55. This file lists what has **not** been verified yet, why, and how
to verify it. What *was* verified is in [`STATUS.md` §3.19](STATUS.md) (emulator) and
[§3.21](STATUS.md) (Galaxy A55).

**Done on the emulator:** frame pacing of game navigation (target met, once a GPU cost in the
glow-text bloom was fixed), the full device regression (171 of 171), the colors by eye, context
rules switching live and under hot reload, the display matrix with the new rows (12 of 12, 0
clipped texts), and unit tests for wheel-only art, `adaptiveArtKey`, the `battery.level` aliases
and `category` + `system` tile shapes (499 tests).

**Done on the Galaxy A55 (120 Hz):** the device regression (160 of 171; the 11 are the
seeded-media checks, which can't pass on a phone with its own media), after three script fixes for
phone behavior (document picker, `dumpsys battery` status, the Android Games row). Frame pacing of
game navigation: on 0.12–0.19% janky, off 0.19–0.38%, GPU p50 5–8 ms of the 8.3 ms budget.
Idle CPU per quick profile. Wheel-only art, the Details screen as the palette source, a rule's
`layout` patch (`INSTANT` motion and `focusedScale`) on screen with hot reload, and the charging
tint from a real charger.

---

## 1. On real hardware

- **Idle CPU at rest is above target.** On the Galaxy A55 the Games root at rest uses 16–21% of a
  core with no profile, OLED True Black or Y2K Cyber-Pastel (target < 10%, HARDWARE_TESTING §6c);
  the wave itself is at its 20 fps rest rate. Monochrome E-Ink uses 0%. Profile what still runs at
  rest (for example with `simpleperf` or a Perfetto trace) before the next phase.
- **Watch the morphs by eye** between games whose art differs a lot in hue: the wave crossfade, and
  the focus halo, avatar glow and battery outline morphing over ~300 ms. A recording of two
  similar games showed no jitter and no bloom flash (§3.21), but that change was small.
- **A real E-Ink panel** (or a 60 Hz panel with the profile on for a while): no ghosting, nothing
  animating (pulse, avatar glow, busy dots, caret, marquee), and the wave as one still frame. On
  the A55 the E-Ink profile drew no wave frames at rest (0% CPU).
- **Android 9–11:** the battery receiver and `androidx.palette` are plain APIs, but only API 37
  ran.
- **A real battery going low:** the charging look was confirmed with a real charger; the amber and
  red look was only driven by `dumpsys battery`. Watch it cross 20% unplugged.

**Gotchas:** on the emulator, a plain cold boot may pick software rendering (`gles_mode_selected:
swangle`), which reads ~100% janky; boot with `-gpu host`. The legacy janky count and GPU
percentiles swing between passes on both the emulator and the A55, so interleave A/B passes and
judge by the deadline-based "Janky frames". An existing library isn't rescanned on start or resume
(only after a volume change); use Settings ▸ Library ▸ Rescan library, as `palette-perf.sh` does.
On a phone, the suites delete `/sdcard/ROMs` and clear Xross's data: move a real ROM folder aside
first (`mv /sdcard/ROMs /sdcard/ROMs-real-backup`, and back afterwards).

## 2. Not covered yet

- The display matrix screenshots stop at the top of Settings ▸ Theme, so the four new rows weren't
  photographed in each configuration (no clipped text was counted on the visible part).
- `focusedScale` is clamped to 1.00–1.35 and defaults to 1.32, so a rule can make the focus
  flatter but barely bigger. Decide whether that range is intended for rules.

## 3. Found on screen, to decide

- **E-Ink isn't fully black and white:** the avatar ring keeps its color, either the user's avatar
  style (`profile.avatarStyle.borderColorHex`) or the iridescent pastel default. The picture
  itself stays in color too. Options: draw the ring white under E-Ink, or leave the avatar as the
  user's own.
- **The battery tint is faint at its edges:** at 20% the glow is `#FFE1B5` and the outline reads
  almost white; the charging mint (`#D8FFE8`) is hard to tell from white in a still. 5% is a clear
  red.
- **The circadian curve barely touches the glow** (`#FAFCFF`–`#FFFCFB` across the day); the canvas
  carries it.
- **Y2K Cyber-Pastel is close to the default Lavender theme** on screen; mostly the strawberry
  battery outline tells them apart.
- **Title bloom no longer morphs:** to remove its per-frame cost it switches to the new color at
  once while the other halos still morph. The alternative is a draw-phase tint, which needs an
  offscreen layer and padding so the 18 px shadow blur isn't clipped.

## 4. Decisions to review

- **Quick profile wins over Settings ▸ Theme ▸ Surface style** (a profile is a whole look). The
  alternative is to let an explicit surface choice win.
- **Adaptive colors are on by default** (`effects.adaptiveGamePalette` defaults to true per the
  spec). So upgrading users get game-art colors in Games without opting in.
- **Halos stay white** unless a color layer is active (art, a profile, the circadian curve, or the
  battery tint while low or charging), so the plain themes look exactly as before.
- **The wave tint is replaced by a quick profile's palette**, and game art (when on) wins over the
  wave tint.
- **Game art replaces Y2K's palette entirely** (canvas included), as it does for the plain themes;
  only OLED True Black keeps its canvas and takes the accents from the art.
- **Hero → wheel → screenshot** is the art order for extraction (the spec names hero and wheel; the
  screenshot fallback matches what the backdrop shows when a game has no hero).
