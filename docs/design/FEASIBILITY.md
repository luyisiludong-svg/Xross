# Project Xross — Feasibility Assessment

Reviewed against `project_scope.md` on 2026-11-09.

## Verdict

**Sound and buildable, but very large.** The overall architecture (Kotlin + Compose
launcher, Room library, intent-based emulator dispatch, GL background) is the same
proven shape used by existing Android frontends (Daijishō, ES-DE, Beacon, etc.).
Nothing in Phase 1 is technically risky. Phases 2–4 together are a multi-person,
multi-month effort, and a handful of items need to be changed or descoped (below).

Recommendation: proceed with **Phase 1 (Alpha)** now, using the decisions in
"Decisions taken" below. Re-plan Phase 2+ after the alpha runs on real handhelds.

## Items that need correction

| Spec item | Problem | Resolution |
| --- | --- | --- |
| Compose *or* C++/SDL2 core | Two UI stacks is a fork in the road, not an option list | **Compose** for UI; GL only for the wave background |
| Room with **FTS5** | Room's annotations generate FTS3/FTS4 only, and the platform SQLite on older Android builds is not guaranteed to ship FTS5 | **FTS4** via `@Fts4` (fast enough for 50k titles); FTS5 only if we bundle SQLite later |
| "Scoped Storage Native POSIX bridge" | Raw path access to shared storage on API 30+ requires `MANAGE_EXTERNAL_STORAGE` ("All files access"). Fine for sideload/F-Droid, a review risk on Google Play | Use `java.io.File` + All-files access (already the fast path); DocumentFile fallback later |
| CRC32/MD5/SHA-1 "without uncompressing" | ZIP and 7z headers store CRC32, and CHD stores SHA-1, so those are instant. MD5/SHA-1 of the content always needs a full streaming decompress, which is slow for multi-GB ISOs | Header CRC first; full hashes in a throttled background job (Phase 2) |
| `android:isGame="true"` | Deprecated since API 26 | Use `ApplicationInfo.category == CATEGORY_GAME` plus the legacy flag |
| Google Play metadata / "Web Search Engine" scraper | No public API; scraping Play or search engines breaks their ToS | Use the app's own label/icon from `PackageManager`; drop the web search fallback or pick a licensed API |
| ScreenScraper / SteamGridDB | Need developer credentials (ScreenScraper) and a per-user API key (SteamGridDB) | Plan for registration; let users enter their own keys |
| Yuzu / Citra / AetherSX2 | Projects are discontinued | Target the maintained forks (Sudachi/Eden, Azahar, NetherSX2) |
| Dusklight / TwilitRealm | Couldn't verify these as public services with an API | Park until a spec or API docs exist |
| Real-time RA unlock overlay *while an emulator runs* | RA has no push API, so this needs polling plus `SYSTEM_ALERT_WINDOW`. RetroArch already shows its own unlock toasts | Show progress in the launcher; overlay is optional |
| `autorun.inf` "execution" | Executing anything from removable media is a security hazard | **Parse-only** manifest (label, icon, scan paths), never execute |
| Ktor/Netty + FTPS | Ktor doesn't do FTP; Netty is heavy on Android | Ktor **CIO** engine for the portal; Apache FtpServer (MINA) for FTPS; self-signed TLS means browser warnings |
| Idle RAM ≤150 MB | Doable only if the GL loop, ExoPlayer, and daemons are torn down when an emulator is in front | Stop the GL loop on pause (done in Phase 1); start daemons only when enabled |

## Phase 1 decisions taken

* Single `:app` module, packages that mirror the scope's architecture (`input`,
  `state`, `storage`, `data`, `launch`, `ui`). Split into Gradle modules once each layer settles.
* minSdk 28, compileSdk/targetSdk 36. AGP 9.2 with built-in Kotlin, KSP + Room 2.8.
* Manual DI (`AppContainer`): no Hilt in the alpha.
* Library folders are picked with the system folder picker and resolved to real paths;
  common `ROMs/` folders on every mounted volume are found automatically.
* Platform detection by folder name (`snes/`, `psx/`, `gba/`…) and file extension.
* Emulator registry ships with intent recipes for RetroArch, DuckStation, NetherSX2,
  Dolphin, PPSSPP and a generic `ACTION_VIEW` + `content://` fallback with
  `FLAG_GRANT_READ_URI_PERMISSION`. Recipes for other standalone emulators need
  testing on real installs before they're added.
