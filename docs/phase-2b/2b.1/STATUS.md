# Phase 2b.1 — evidence

Profile HUD, game tiles, Details paging and the RetroAchievements dashboard.

Section numbers are the original `docs/STATUS.md` numbers, kept so references in commits and older notes still resolve; the [status dashboard](../../STATUS.md) maps every number to its file.

Legend: ✅ done and verified · ◐ partial · ❌ not started · 🧪 verified only on the emulator / with stubs

<a id="s3.15"></a>

## 3.15 Phase 2b.1: profile HUD, game tiles, Details paging, RetroAchievements dashboard (2026-11-11)

Built in `2c142d6` from the Phase 2b specification (now Roadmap §4.3 and Appendix A). Verified by
unit and view-model tests only ([§3.1](../../status/UNIT_TESTS.md#s3.1)); **no device run yet**.

| Feature | How it works |
| --- | --- |
| Avatar mini-HUD | `AvatarHud` at the far right of the status bar. Up from the first row of a category root, or a tap, sets `avatarFocused`; the container morphs in place into a frosted capsule (user, rank, hardcore/softcore points, recent badges) while the status bar fades its clock and battery with the same value. The expansion is read only in layout and draw, so nothing else re-measures or recomposes. Down, Back or a tap collapses it; Confirm refreshes the profile (or starts linking); Options opens the User Card; Left/Right switch category. A stale profile refreshes quietly on open |
| Game tiles | `GameTile`: 16:9 plate with the clear logo (`wheels`), else wide hero/capsule art, else the title on a frosted plate |
| Playtime subtitle | `MenuTree`: "Never played", "Playtime: N mins" under an hour, "Playtime: Xh Ym" above; a session under a minute counts as one |
| Details paging | `GameDetails` pager over the list Details was opened from; L1/R1 and Left/Right with a slide and crossfade; stops at both ends; the list cursor follows |
| Game telemetry | Lower right above the button hints: unlocked/total, points, mastery ribbon at 100%. Only for games matched to a RetroAchievements set with achievements; hidden under full-screen overlays, search, panels and while the avatar HUD is open |
| Profile customization | `profile.avatarStyle` (`AvatarStyle.kt`): border color presets or custom, width 1–6 dp, glow on/off, speed 0.5–2×, intensity 0.1–1, clamped on load. `AvatarStylePanel` previews live and saves at once. The glow's infinite transition stops when the screen is idle or covered |
| RetroAchievements dashboard | `RaHubScreen` from Games ▸ RetroAchievements: header card (avatar, user, rank, member since, points, RetroRatio), library completion gauge, mastery showcase, recent activity feed. Built from the cached progress in `Xross/data/ra`; placeholder states when offline or not linked |

**Emulator run (2026-11-12, API 37, 60 Hz):** `smoke.sh` 112/112, now covering the avatar HUD
(Up from the top row and back), the playtime subtitle, Details paging (Right, R1, L1, both ends,
cursor follows), the dashboard and Profile customization (saved to `settings.json`). The HUD and
the 16:9 title plates were also checked in screenshots.

**Idle rest (2026-11-12):** `InactivityTracker` marks the screen idle after 3 s without key, nav or
touch input, unless music or a video snap plays. While idle the focus pulse and avatar glow ease to
a resting value and stop (`rememberBreathing`), and the wave drops to ~20 fps; in use it is capped
near 60 fps on panels above 90 Hz. Both rates are paced on vsync by a Choreographer callback that
wakes once per wave frame. Resting CPU on the emulator: 21.9% before, 2.5–2.8% after.

**Galaxy A55 (2026-11-12, Android 17, 120 Hz, profiling build, real library):**

| Measure | Before (`cddf975`) | After |
| --- | --- | --- |
| Process CPU resting on the Games root | 114–124% | 20 fps standby: 13–19%, 12% after a few minutes. 12 fps standby (tried, reverted as sluggish): 9.9–11.6% over 20 s windows, 7.9% over 30 s (GL thread 3.7%, main 2.3%, binder 1.3%) |
| Process CPU while pressing keys | 79–114% | 105–108% |
| Wave in use | 120 fps | 60 fps: p50 16.7 ms, p95 18.2–18.8 ms, 1–3 missed per 300 |
| Wave at rest | 120 fps | 20 fps: p50 50.0 ms, p95 51.9 ms. 12 fps (tried): p50 89.4 ms, p95 93.3 ms against 83.3 ms, so about half the frames land one vsync late |
| UI while in use | | 0.27% janky, p50 7 ms, p95 9 ms; 0 UI frames per 5 s at rest |

Screenshots on the A55: the HUD expands with the clock and battery hidden; logo tiles with
"Never played"; Details paging Chrono Trigger → EarthBound with art and facts changing, and the
list cursor on EarthBound after Back; the dashboard with the live profile and library completion
(1 of 377 supported games checked so far). Two layout bugs found and fixed: the Details hints
wrapped "Scroll" mid-word at 2340 px (now a `FlowRow`, and hint labels never wrap), and the
dashboard's identity card cut off its "Updated …" line at 1080 px tall (now pinned at the bottom,
with the identity block above it scrolling if needed and slightly tighter spacing).

**Not verified:** whether the HUD morph and the Details crossfade animate without jitter (checked
by screenshot only, not by eye); L1/R1 on a physical controller; the < 10% idle-CPU target is not
met (12–19% with the 20 fps standby kept); the dashboard with many achievements.
