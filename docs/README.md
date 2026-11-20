# Xross documentation

Planning lives in [`Roadmap.md`](../Roadmap.md); everything here is reference material or evidence
and links back to it.

## Guides

| Document | For |
| --- | --- |
| [`USER_GUIDE.md`](USER_GUIDE.md) | Using Xross: the `Xross/` folder, library, metadata, RetroAchievements, media, apps |
| [`THEME_SPECIFICATION.md`](THEME_SPECIFICATION.md) | The theme pack format as implemented (`theme.json` v2, rules, effects, `.xtheme`) |
| [`SHADER_GUIDE.md`](SHADER_GUIDE.md) | Writing background shaders: uniforms, feedback and field passes, the frame budget, the `BLACK_HOLE` preset and its tunables |
| [`SHADER_TUTORIAL.md`](SHADER_TUTORIAL.md) | Making your own shaders: a starter template, the preview-to-phone loop, which built-in to learn each technique from |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | Building, unit/live/device tests, emulator stand-ins, source layout |
| [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) | Checklist for a real handheld, and what to send back |
| [`XMB_FIDELITY.md`](XMB_FIDELITY.md) | How close Xross is to the real XMB, against the PS3 and PSP manuals |

## Status and evidence

[`STATUS.md`](STATUS.md) is the dashboard: one row per phase, the feature table by scope section,
known limitations, and an index from every evidence section number to its file.

```
docs/
  STATUS.md                 dashboard
  status/                   cross-cutting evidence
    UNIT_TESTS.md           unit-test catalogue (§3.1)
    DEVICE_TESTS.md         smoke test and the device-test tiers (§3.2, §3.20)
    PERFORMANCE.md          display matrix, frame pacing, scope targets (§4)
  phase-1/                  Alpha and 1b: README.md (overview), STATUS.md (evidence)
  phase-2/                  Beta: README.md, STATUS.md
  phase-2b/                 Theme 2.0: README.md (slice table and log)
    2b.N/                   one folder per milestone:
      PROMPTS.md            the implementation plan in slices, with as-built notes
      STATUS.md             evidence: tests, device runs, frame timing, what is not verified
      PENDING_TESTS.md      (2b.3 only) what is still to verify
  phase-3-prep/             module boundaries before Phase 3: STATUS.md (evidence, §3.38–3.40)
  design/                   reviews and proposals
  reference/                third-party API notes
```

A new phase gets its own folder (`phase-3/`, …) with the same `README.md` + `STATUS.md` shape; a
new 2b-style milestone gets a `2b.N/` folder. Evidence sections keep numbering on from §3.40.

## Design and proposals

| Document | What it is |
| --- | --- |
| [`design/ARCHITECTURE_ASSESSMENT.md`](design/ARCHITECTURE_ASSESSMENT.md) | Structure review after Theme 2.0: module boundaries, state flow, the refactoring plan |
| [`design/THEME_RICING_AND_WEB_STUDIO.md`](design/THEME_RICING_AND_WEB_STUDIO.md) | Proposal: plain-text themes, a browser Theme Studio, a desktop build |
| [`design/FEASIBILITY.md`](design/FEASIBILITY.md) | The 2026-11-09 review behind the original scope changes |

## Reference

| Document | What it is |
| --- | --- |
| [`reference/SCREENSCRAPER_API.md`](reference/SCREENSCRAPER_API.md) | English guide to ScreenScraper's API rules |
| [`reference/RETROACHIEVEMENTS_API.md`](reference/RETROACHIEVEMENTS_API.md) | The RetroAchievements Web API calls Xross uses, verified live |
