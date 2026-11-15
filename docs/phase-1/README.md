# Phase 1 (Alpha) and 1b (alpha hardening)

| Milestone | Scope | State |
| --- | --- | --- |
| Phase 1 — Alpha | XMB navigation, gamepad layer, ROM library, emulator dispatch, wave background | ✅ complete |
| Phase 1b — hardening | Hot-swap, USB-OTG, playtime, Options menu, per-game emulator, six more emulators, per-controller mappings, axial and 0% deadzones | ◐ features done; a real-handheld pass and a Baseline Profile remain |
| Visual front end | Icons, themes, glow, status bar and sounds from `assets/examples`, plus the Original monthly theme, busy indicator, optional button hints and the Information screen | ✅ |

Evidence: [`STATUS.md`](STATUS.md) (emulator dispatch with stub builds, the Phase 1b features on a
device). The unit-test catalogue and the first device smoke test are cross-cutting and live in
[`../status/`](../status/). What is left is tracked in
[Roadmap §4.1](../../Roadmap.md#41-phase-1b--finish-alpha-hardening-next).
