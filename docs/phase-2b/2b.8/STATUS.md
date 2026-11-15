# Phase 2b.8 — evidence

The Live Theme Mixer: layout mixer, export and Share as `.xtheme`. Plan, slices and as-built notes: [`PROMPTS.md`](PROMPTS.md).

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.35"></a>

## 3.35 Phase 2b.8a: the layout mixer (2026-11-15)

On `feat/theme-2b8`, API 37 emulator (`-gpu host`, 2400×1080). Details in
[PROMPTS.md](PROMPTS.md#as-built-2b8a-2026-11-15).

**Host:** 886 unit tests pass (4 skipped). New `LayoutOverridesTest`: `display.layout` decoded with
values of the wrong type (each dropped alone, the rest of `settings.json` kept), clamping, the
preset by name and alias (`PSP_CENTRE`), the JSON round trip; the user's values winning over the
pack's base, a variant, a category, a category rule and a battery rule that patch the same fields,
the rest following the context; the quick profile still setting only motion; the panel's steps (the
pack as the left end, both ends held, an off-grid value), Confirm, Reset layout, sliders; the
Layout row after Components and Reset's "Clears 2 layout values, 1 effect". `UserLookTest`,
`ThemeControllerTest` and `WaveSettingsTest` extended. `lintDebug` 0 errors.

**Theme suite: 348/351**, with a new 39-check "Layout mixer (2b.8a)" section: the Layout row's
summary; the panel listing the Default pack's own values ("Theme pack · 22% × 25%", 100%, Standard
(12 dp), 1.32×); the menu still in the accessibility tree and as bright behind the panel (94 → 94);
Classic PS3, PSP centre and Modern widescreen each moving the menu while the panel is open, the last
preset holding, Confirm putting it back; UI scale 110% growing the text, 150% held, 50% shrinking it,
Left past 50% back to the pack; Spacious and Tight changing the row pitch (162 → 186 / 144 px);
focused scale 1.35× and 1.00×; the row's subtitle, a restart, Reset layout and Reset to theme
defaults. The fails: 2b.4f's wallpaper toast (as on `main`) and two 2b.7a volume checks that ran
with the emulator's music volume already at 0; checked again by hand with the volume up, both pass.

**Display matrix:** `display-matrix.sh layout-min` (Modern widescreen, 50%) and `layout-max` (PSP
centre, 150%), 12/12 each: 0 clipped texts, 0 status-bar collisions, a whole neighbour category
on each side, janky 0–1.20%.

**Frame timing (profiling build):** `palette-perf.sh 3` 0.44–0.75% janky with the pack's layout,
0.29–0.45% with `PERF_LAYOUT` (PSP centre, 130%, 20 dp, 1.10×). `layout-perf.sh 3` (21 presses
each, back to back): list 2.27–3.03%, UI scale 0.56–2.78%, crosshair and spacing 0.94–2.44%. Every
injected `input` costs frames, so these sit near the gate for the plain list too. A springing UI
scale measured 2.9–3.1% (13% cold) against a 1.4–2.3% list, so the UI scale now snaps.

**Not covered:** a real held key (the emulator isn't rooted, and `input` can't hold one); the A55
at 120 Hz with a gamepad.

<a id="s3.36"></a>

## 3.36 Phase 2b.8b: export a theme pack (2026-11-15)

On `feat/theme-2b8`, API 37 emulator (`-gpu host`, 2400×1080). Details in
[PROMPTS.md](PROMPTS.md#as-built-2b8b-2026-11-15).

**Host:** 912 unit tests pass (4 skipped). New `LookExporterTest` (11) and `ThemeExportTest` (9:
the writer, each scope loaded back and resolved equal in 84 contexts, no credential bytes);
`ThemeControllerTest` +6. `lintDebug` 0 errors.

**Theme suite: 407/407**, with a new 56-check "Export (2b.8b)" section: the built-in Default pack
whole (7 sounds and 29 icons from the APK assets, at most 1.08% of pixels differ after Switch to,
the breathing halo) and as a current look (0%); a pack with 2 variants, category and system
overrides and 2 rules, whole, per variant and as a current look (at most 1.41%); a picked wallpaper
copied in and still shown with the original deleted. Each compared in 8 places by screenshot and
by the colours, effects and cosmetics the debug build logs.

**Not covered:** the A55 with a folder pack and the user's own picks.

<a id="s3.37"></a>

## 3.37 Phase 2b.8c: share as .xtheme and the round-trip gate (2026-11-15)

On `feat/theme-2b8`, API 37 emulator (`-gpu host`, 2400×1080). Details in
[PROMPTS.md](PROMPTS.md#as-built-2b8c-2026-11-15).

**Host:** 932 unit tests pass (4 skipped). New `ThemeArchiveWriteTest` (6) and `ThemeRoundTripTest`
(9: the A.13 gate, every scope of the Default and stress packs through export → `.xtheme` → stage →
install → load, resolved equal in 126 contexts, no credential bytes in the archive);
`ThemeControllerTest` +4, `ThemeArchiveTest` +1. `lintDebug` 0 errors.

**Theme suite: 425/426**, with a new 19-check "Share (2b.8c)" section: Share as .xtheme… for every
installed pack, the built-in Default pack shared whole to `Download/default-pack.xtheme` with no
folder written, imported back and the same look (at most 1.18% of pixels differ); a current-look
export shared, its folder deleted, imported back (0%). The one fail is 2b.4f's "Xross wallpaper set"
toast missed by the screen dump, as on `main`.

**Not covered:** the A55 (share to a cloud folder, re-import on the emulator).
