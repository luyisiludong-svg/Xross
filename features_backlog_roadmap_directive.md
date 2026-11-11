# Agent Directive: Features Assessment & Roadmap Integration

> **ATTENTION AI AGENT / ASSISTANT:**
>
> **DO NOT start implementing any of these features immediately.**
>
> Before writing any production code:
>
> 1. **Assess Current Project Status:** Inspect the working tree, module layouts (`app/src/main/java/dev/xross/launcher/...`), Room schemas, and build configurations.
>
> 2. **Run Verification Tests:** Execute `./gradlew :app:testDebugUnitTest` (or platform equivalent) to verify that current tests pass and the baseline is stable.
>
> 3. **Read Core Documentation:** Review `README.md`, `project_scope.md`, and `Roadmap.md` to understand architectural constraints (FTS4, Direct I/O via `MANAGE_EXTERNAL_STORAGE`, Jetpack Compose + OpenGL ES 3.0 waves, Ktor/FTP daemons).
>
> 4. **Assess & Integrate:** Evaluate each feature listed below against the existing architecture, determine platform implications, resolve potential breaking changes, and merge them into the official `Roadmap.md` under the appropriate milestones. Only proceed with implementation once the roadmap and architecture specifications have been updated.

### Notice: Local Test Media Path Update
* **Relocation**: All local test fixtures previously stored in the `roms/` folder (including test ROMs, audio, and video snaps) have been moved into the root **`localTestMedia/`** directory.
* **Testing & ADB Configuration**: Update all test runners, Robolectric fixtures, and ADB push scripts to reference `localTestMedia/` instead of the legacy `roms/` path.
* **Git Hygiene**: `localTestMedia/` is excluded via `.gitignore`. Do not attempt to track, stage, or commit binary files from this directory.

# Features Backlog

Candidate features and architectural refinements proposed for upcoming development phases:

* \[ \] **0% Analog Stick Deadzone Option**

  Provide an explicit setting in the controller configuration to drop the analog stick deadzone to `0.0f` (raw input) for controllers with Hall Effect sticks or users who calibrate deadzones at the hardware level.

* \[ \] **Custom Media Directory Selectors (Photos & Videos)**

  Allow users to select custom external directory paths for the Photo and Video XMB columns rather than relying strictly on default Android MediaStore queries. Allow users to select which paths to show up in each tab.

* \[ \] **Dedicated Media Player Controller Shortcut & 30-Second Inactivity Auto-Hide**

  * Bind a global controller shortcut (e.g., Guide/Home button long-press or custom combination) to immediately focus/summon playback controls.

  * When media playback has been paused for longer than 30 seconds, automatically dismiss/hide the mini-player overlay from the top-left corner instead of leaving a persistent paused widget.

* \[ \] **App Library Management: Hide & Folder Organization**

  * Allow users to hide unwanted or system applications from the Apps category.

  * Enable custom folder creation / grouping (e.g., "Emulators", "Streaming", "Tools") inside the Apps ribbon.

* \[ \] **Consolidated "All Games" Platform Folder (ES-DE Hierarchy Paradigm)**

  * Consolidate all game platforms inside a unified "All Games" category/folder on the main ribbon.

  * Entering "All Games" presents the list of detected gaming systems (SNES, PSX, GBA, etc.).

  * Standardize ROM scanning under a user-defined base `ROMs/` folder path (e.g., `<root>/roms/<system_id>/`), matching standard emulation frontend directory schemas.

* \[ \] **Self-Contained "Single Portable Root" Folder Architecture**

  * Structure all application state, configurations, and assets inside a single portable root directory (e.g., `/storage/emulated/0/Xross/` or MicroSD root):

    ```
    Xross/
    ├── config/
    │   ├── settings.json         # Replaces private SharedPreferences
    │   ├── systems.json          # Custom emulator launch configurations
    │   └── controllers.json      # Controller mappings keyed by VID/PID
    ├── data/
    │   └── xross.db              # Live Room/SQLite database with FTS4 index
    ├── media/
    │   ├── covers/               # Box art / covers
    │   ├── heroes/               # Scraped hero banners / background art
    │   ├── wheels/               # Logos / wheels
    │   └── videos/               # Gameplay preview clips (.mp4)
    ├── roms/                     # Default ROM library directory
    └── themes/                   # Custom UI icons, sounds, and shader packs
    
    ```

  * Allows full backup, USB file inspection, and prepares the codebase for seamless cross-platform desktop expansion (Compose Multiplatform / KMP) in the future.

* \[ \] **Dynamic Media Hover Priority Pipeline**

  When focusing or hovering over a game in the list, background media rendering must strictly follow this fallback hierarchy:

  1. **Video Preview:** If a video snap exists (`.mp4`), play it seamlessly in the background with smooth audio ducking (mirroring authentic XMB behavior).

  2. **Hero Art Banner:** If no video is present, display the scraped wide "Hero" banner (e.g., SteamGridDB / ScreenScraper hero assets).

  3. **Default / Theme Wave:** If neither asset exists, maintain the live GLSL ES 3.0 wave shader background.

## Technical Notes & Architectural Considerations for the Agent

1. **Room SQLite Path Override:**

   Moving `xross.db` to the external portable root requires initializing `Room.databaseBuilder` with `File(xrossEnv.dataDir, "xross.db").absolutePath`. Be mindful of SQLite `-wal` and `-shm` behavior on external exFAT/FAT32 filesystems (may require `RoomDatabase.JournalMode.TRUNCATE`).

2. **Settings Serialization:**

   Replace Android DataStore / private XML settings with a clean Kotlin `@Serializable data class SettingsData(...)` targeting `Xross/config/settings.json`.

3. **Intent Permissions vs. Desktop Portability:**

   Keep `GameLauncher` behind an interface so Android's `Intent` + `content://` URI grants remain cleanly separated from any future CLI `ProcessBuilder` execution.

## References

* **Gameplay & UX Reference Video:** https://www.youtube.com/watch?v=014gbBBHZ4s

* **Repository:** https://github.com/luyisiludong-svg/Xross
