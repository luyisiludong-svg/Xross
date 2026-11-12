# Phase 2b.3 — what is still to be tested

Branch `feat/theme-2b3` (from `feat/theme-2b2`), updated 2026-11-12 after an emulator pass. This
file lists what has **not** been verified yet, why, and how to verify it. What *was* verified,
including that pass, is in [`STATUS.md` §3.19](STATUS.md).

**Done on the emulator in that pass:** frame pacing of game navigation (target met, once a GPU cost
in the glow-text bloom was fixed), the full smoke test (171 of 171), the colors by eye, context
rules switching live and under hot reload, and unit tests for wheel-only art, `adaptiveArtKey`,
the `battery.level` aliases and `category` + `system` tile shapes (499 tests).

---

## 1. On real hardware

Only the API 37 emulator has run this branch.

- **Galaxy A55 (120 Hz):** the full `smoke.sh`, then `tools/device-tests/palette-perf.sh` (it
  installs the debug build to seed, then the profiling build; judge the deadline-based
  "Janky frames", target 0–1.5%, and compare on/off). On the emulator, with the bloom fix: on
  0.29–0.90%, off 0.30%, GPU p50 14 ms both. At 120 Hz the budget is 8.3 ms, so watch GPU p50.
  Also idle CPU at rest with each quick profile (E-Ink should be under today's 12–19%, since the
  wave draws no frames).
- **Watch the morphs by eye:** the wave crossfade to a game's colors (visible when a game has a
  wheel logo but no hero, or while the hero fades in), and the focus halo, avatar glow and battery
  outline morphing over ~300 ms without jitter. The title bloom now switches color at once (see
  §3); check that it doesn't read as a flash.
- **A real E-Ink panel** (or a 60 Hz panel with the profile on for a while): no ghosting, nothing
  animating (pulse, avatar glow, busy dots, caret, marquee), and the wave as one still frame.
- **Android 9–11:** the battery receiver and `androidx.palette` are plain APIs, but only API 37
  ran.
- **A real battery:** the tint was only driven by `dumpsys battery`. Watch it cross 20% unplugged,
  and plug in.

**Emulator gotchas found:** a plain cold boot may pick software rendering (`gles_mode_selected:
swangle` in the emulator log), which reads ~100% janky; boot with `-gpu host`. Even then the
legacy janky count and GPU percentiles swing between passes, so interleave A/B passes. An
existing library isn't rescanned on start or resume (only after a volume change); use Settings ▸
Library ▸ Rescan library, as `palette-perf.sh` does.

## 2. Not covered yet

- Wheel-only art on a device (a unit test now shows Palette ignores transparent pixels).
- The Details screen as the palette source, on a device (unit-tested through `adaptiveArtKey`).
- A rule's `layout` patch on screen (for example `motionProfile: INSTANT` below 15%). Its color
  half switched live on the emulator; the layout half is unit-tested only.
- Display matrix (`tools/device-tests/display-matrix.sh`) wasn't rerun. The 2b.3 rows add four
  lines to Settings ▸ Theme but no new layout code.

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
