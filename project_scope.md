# Project Scope & System Architecture: Project Wave (XMB Android Launcher)

## 1. Executive Summary & Product Vision

Project Wave is a dedicated front-end launcher and media interface for Android-based gaming handhelds, set-top boxes, and dual-screen devices. Built around the horizontal-category, vertical-item cross-media bar (XMB) paradigm, the launcher balances lightweight console aesthetics with the functional demands of high-capacity emulation libraries and native Android applications.

```
       [Settings]   [Photos]   [Music]   [Video]   [Games]   [Apps]   [Network]
                                                      |
                                                 [PlayStation 2]
                                                      |
                                                 [GameCube]
                                                      |
                                              > [SNES] < (Active Category)
                                                      |  |-- Chrono Trigger
                                                      |  |-- Super Mario World
                                                      |  |-- Super Metroid
                                                      |
                                                 [Game Boy Advance]

```

### Core Tenets

* **Controller-First, Touch-Complete**: Zero reliance on mouse pointers or standard touch controls, while maintaining 1:1 gesture parity for touchscreen hardware.
* **Deterministic Emulation Dispatch**: Robust intent and URI schema orchestration across RetroArch cores and standalone Android emulators without file-permission failures.
* **Zero-Friction Asset Management**: Frictionless on-device scraping paired with browser-accessible LAN administration (Wave Portal) and FTPS syncing.
* **Hardware Adaptability**: First-class handling of single-screen, dual-screen, docked/undocked, and unconventional aspect ratios (1:1, 4:3, 3:2, 16:9).

---

## 2. Technical Stack & Hardware Baseline

| Layer | Technology / Implementation | Rationale |
| --- | --- | --- |
| **Target OS** | Android 9.0 (API Level 28) through Android 15+ (API Level 35) | Captures legacy retro handhelds up to modern flagship devices |
| **Core Architecture** | Kotlin + Jetpack Compose (or C++ / SDL2 core for UI rendering) | High developer velocity, hardware-accelerated animations, reactive state |
| **Graphics & Shaders** | OpenGL ES 3.0+ / Vulkan with GLSL custom shaders | Powers dynamic, responsive mathematical wave animations at 60/120 Hz |
| **Local Database** | Room (SQLite) with FTS5 (Full-Text Search) | High-speed indexing for 50,000+ title collections and instant search |
| **File I/O Engine** | Scoped Storage Native POSIX bridge + DocumentFile fallback | Bypasses Java SAF performance bottlenecks when scanning SD cards |
| **Embedded Server** | Ktor / Netty embedded engine | Micro-footprint local daemon for FTPS and HTTPS Web Portal |
| **Networking** | OkHttp 4 + Retrofit + Coroutines | Non-blocking multi-threaded scraping and asset downloading |

---

## 3. Detailed Functional Specifications

### 3.1 Controller & Navigation Engine

#### Input Pipeline

* **Gamepad Abstraction Layer**: Support for standard Android `InputDevice` APIs with dynamic detection and vendor-specific key-code mapping:
* **PlayStation**: DualShock 4, DualSense (accurate glyph mapping for Cross, Circle, Square, Triangle).
* **Xbox**: Standard XInput layout (A, B, X, Y).
* **Nintendo**: Switch Pro / Joy-Con layouts (native support for inverted confirm/cancel semantics).
* **Generic HID / DirectInput**: Automatic mapping wizard for boutique retro handheld gamepads (Anbernic, Retroid, Ayn, Ayaneo).


* **Button Layout & Semantics**:
* Global toggle for Confirm/Cancel swapping (East/South layout inversion).
* Global and per-game physical button remapping.
* Analog stick deadzone calibration (radial and axial) with anti-snapback filtering.


* **Virtual Input & Accessibility**:
* **On-Screen Controller (OSC)**: Fully customizable virtual touch overlay for screen-only devices, featuring dynamic opacity, haptic feedback integration, sizing, and position snapping.
* **XMB Gamepad Virtual Keyboard**: D-pad/analog stick optimized text entry. Employs a phone-keypad radial layout or a scrolling linear grid to avoid clumsy standard Android virtual keyboard popups.



---

### 3.2 Game Libraries & Storage Subsystem

#### Taxonomy & Filtering

* **Dynamic Collections**: System categories (e.g., PS1, GBA, Arcade) alongside smart groupings:
* *Favorites* (cross-platform toggle).
* *Recently Played* (MRU list with timestamps and total playtime counters).
* *Playlists & Custom Tags* (e.g., "Multiplayer", "Turn-Based RPGs", "Completed").


* **ROM Parsing Engine**:
* Multi-format archive scanning: `.zip`, `.7z`, `.tar`, `.iso`, `.chd`, `.cso`, `.bin/.cue`, `.rvz`, `.nsp`, `.3ds`.
* Hash extraction (CRC32, MD5, SHA-1) on-the-fly without uncompressing entire archives for instant matching against Redump and No-Intro DATs.



#### Storage Architecture & Hot-Swapping

* **Multi-Mount Directory Pooling**: Simultaneous aggregation across Internal Storage, primary microSD, secondary microSD (TF2), and external USB-OTG drives.
* **Hot-Swap Engine**:
* Real-time `ACTION_MEDIA_MOUNTED` and `ACTION_MEDIA_UNMOUNTED` broadcast receivers.
* Auto-refresh of game lists on mount/dismount without crashing or dropping active UI state.
* Custom volume naming and visual drive icons inside the file management view.


* **`autorun.inf` Integration**:
* Parsing of custom storage manifests placed in root directories of external media:


```ini
[WaveLauncher]
Label=SanDisk 512GB Retro
Icon=assets/storage_icon.png
AutoScan=true
DefaultPlatformPath=/roms/

```



#### Emulator Dispatch Layer

* **Intent Schema Matrix**: Per-platform and per-game emulator assignments. Resolves between RetroArch Libretro cores and standalone emulators via standard Android Intents, URI grants, and component flags:
* *RetroArch*: Custom intent dispatch passing core path and ROM URI (`org.retroarch` / `org.retroarch.aarch64`).
* *Standalone Emulators*: Specific intent implementations for DuckStation, AetherSX2/NetherSX2, Dolphin, PPSSPP, Citra/Mandarine, Yuzu/Sudachi, ScummVM, etc.


* **Permission Bridge**: Automatic application of `FLAG_GRANT_READ_URI_PERMISSION` to ensure target apps can access scoped storage paths directly.

---

### 3.3 Metadata Scraping & Achievement Infrastructure

#### Multi-Source Scraper Orchestration

A tiered metadata pipeline queries remote endpoints concurrently, falling back gracefully upon missing fields or rate limits:

```
[ROM Identified (Hash / Clean Name)]
         |
         +---> [SteamGridDB] --------> Hero Banners, Logos, Custom Grids
         +---> [ScreenScraper] ------> Descriptions, Boxart 3D, Wheel Art, Videos
         +---> [Hasheous] -----------> Exact ROM verification & Title matching
         +---> [RetroAchievements] --> Badges, Target Game IDs, Cheevos lists
         +---> [Google Play] --------> Native Android App Metadata & Icons
         +---> [Web Search Engine] --> Fallback synopsis & high-res posters

```

* **Multi-Language Resolution**: Priority array for metadata fetching (e.g., `["es-ES", "es", "en-US", "en"]`).
* **Scraper Engine Features**:
* Batch background scraper with throttling to respect service rate limits.
* Interactive scraper review tool: Manual hash bypass and title search for ambiguous or modified ROMs.
* Asset caching to disk with persistent UUID-keyed image paths to prevent database bloat.



#### Achievement Tracking

* **RetroAchievements (RA) API Integration**:
* User authentication with persistent, encrypted token storage.
* Synchronous rich presence polling and visual display of unlocked/locked achievements inside game sub-menus.
* Real-time achievement unlock overlay (custom XMB-styled non-intrusive toast notification).


* **Dusklight / TwilitRealm Integration**:
* API connector for tracking specialized homebrew community targets and modern emulation platform progress.



---

### 3.4 Media Engine & Customization

#### Integrated Media Hub

* **Photo Viewer**: Controller-guided image browser supporting hardware-accelerated pan/zoom, slideshow modes, and "Set as Wallpaper" capabilities.
* **Music Player**: Local audio playback (`.mp3`, `.flac`, `.ogg`, `.wav`, `.aac`) with ID3 tag parsing and album art rendering. Can run as a background service while navigating menus.
* **Video Player**: Video preview player for game snaps and standalone media with controller-mapped playback, scrubbing, and seamless looping.

#### Visual Theming & Shaders

* **Wave Engine**:
* Real-time customizable dynamic wave background, mimicking PS3/PSP ribbons.
* Configurable parameters: Line count, turbulence, speed, wave thickness, and color gradients.
* Day/Night color-shifting based on system clock; battery-level color blending (green to yellow to red).


* **Coldboot & Audio Customization**:
* Custom boot animations and coldboot audio chimes.
* Discrete volume sliders: UI sound effects, background music (BGM), and media/video snaps.
* Soundpack support: Loadable sound packs (cursor move, confirm, back, error, system boot).


* **Display Configuration**:
* Custom TTF/OTF font loading.
* Interface scaling (slider from 50% to 150%) for dense high-DPI displays or small handheld screens.
* Custom aspect ratio overrides (letterboxing vs pillarboxing) and system status bar integration (transparent, dark, or hidden).



---

### 3.5 Device Integration & Form Factor Support

#### Dual-Screen Architecture

Designed to natively support dual-screen and folding hardware (e.g., Ayaneo Flip DS, Nintendo 3DS-style mods, Surface Duo, LG Dual Screen):

```
+---------------------------------------+
|            PRIMARY DISPLAY            |
| - Horizontal XMB Categories           |
| - Primary Navigation Flow             |
| - Video Snap Previews                 |
+---------------------------------------+
|           SECONDARY DISPLAY           |
| - Extended Game Details & Synopses    |
| - 3D Boxart / Spine Visualizer        |
| - RetroAchievements Live Progress     |
| - Audio Visualizer / Music Playback   |
| - Real-time Hardware Telemetry        |
+---------------------------------------+

```

* **Dual-Screen Mechanics**:
* Per-screen orientation lock (e.g., Landscape top, Portrait bottom).
* Global hotkey for instant screen swapping.
* Extended secondary screen modes: Game manual/PDF reader, dynamic second-screen touch controller, or hardware telemetry display (CPU/GPU load, temps, battery).


* **Docked Mode Detection**:
* Automatic detection of HDMI/DisplayPort connections via `DisplayManager`.
* Dynamic layout switching: Seamless resolution shift from low-res handheld panel (e.g., 720p) to external display (1080p/4K) without process restart.



#### Android Application Triage

* Automated categorization of installed Android APKs:
* **Emulators**: Direct detection of known emulator package IDs with automated configuration routing.
* **Native Games**: Categorized via Android Manifest `android:isGame="true"` flags or manual user overrides.
* **Applications / Utilities**: Streaming apps, browsers, and system tools separated from game libraries.



---

### 3.6 Network Daemons & Remote Management

```
  +-----------------------------------------------------------+
  |                   WAVE RUNTIME DAEMON                     |
  +-----------------------------+-----------------------------+
                                |
             +------------------+------------------+
             |                                     |
   [HTTPS Wave Portal]                     [Encrypted FTPS]
   (Port 8443 / Web UI)                    (Port 2121 / TLS)
             |                                     |
    Browser Access:                       Direct Client Sync:
  - Drag-and-drop ROM upload            - FileZilla / WinSCP
  - Library metadata editing            - Fast binary ROM transfers
  - Boxart replacement                  - BIOS file updates
  - System telemetry review             - Save file backups

```

#### HTTPS Wave Portal

* Local embedded web application accessible via LAN browser (`https://<device-ip>:8443`).
* Built-in authentication (PIN or password set on device).
* Functionality:
* Drag-and-drop ROM and BIOS uploading directly to configured directories.
* Interactive library manager: Edit titles, developer/publisher data, change box art, and inject manual metadata.
* System diagnostics: View storage distribution, temperatures, battery health, and logs.



#### FTPS Server Subsystem

* Embedded TLS-encrypted File Transfer Protocol server (`ftps://<device-ip>:2121`).
* Low-overhead background transfer engine engineered to prevent Android thread-starvation or out-of-memory exceptions during large ISO transfers.

---

## 4. Technical Architecture & Module Structure

```
                         +-----------------------------------+
                         |         WaveApplication           |
                         +-----------------------------------+
                                           |
         +---------------------------------+---------------------------------+
         |                                 |                                 |
+-------------------+             +-------------------+             +-------------------+
|   InputEngine     |             |    StateEngine    |             |   StorageManager  |
| - GamepadHandler  |             | - ActiveCategory  |             | - VolumeMonitor   |
| - VirtualPadView  |             | - FocusManager    |             | - SAFBridge       |
| - HotkeyRouter    |             | - NavigationStack |             | - AutorunParser   |
+-------------------+             +-------------------+             +-------------------+
         |                                 |                                 |
         +---------------------------------+---------------------------------+
                                           |
+---------------------------------------------------------------------------------------+
|                                      DATA CORE                                        |
|  +--------------------+    +--------------------+    +-----------------------------+  |
|  |   GameRepository   |    | MetadataOrchestrator|   |   MediaPlaybackService      |  |
|  | - Room Database    |    | - SteamGridDB      |    | - ExoPlayer Audio/Video     |  |
|  | - FTS5 Search      |    | - ScreenScraper    |    | - Custom SoundPool (SFX)    |  |
|  | - Cache Layer      |    | - RetroAchievements|    | - Wave Shader (GLSurface)   |  |
|  +--------------------+    +--------------------+    +-----------------------------+  |
+---------------------------------------------------------------------------------------+
                                           |
         +---------------------------------+---------------------------------+
         |                                 |                                 |
+-------------------+             +-------------------+             +-------------------+
|  LauncherBridge   |             |   NetworkDaemon   |             |  DualScreenManager|
| - IntentFactory   |             | - Ktor Web Portal |             | - PresentationAPI |
| - EmulatorRegistry|             | - FTPS Server     |             | - DisplayListener |
| - URI GrantEngine |             | - AuthManager     |             | - LayoutOrchestrtr|
+-------------------+             +-------------------+             +-------------------+

```

---

## 5. Non-Functional Requirements & Performance Targets

### 5.1 Latency & Frame Pacing

* **Menu Navigation**: Rock-solid 60 FPS minimum; native 120 FPS support on devices equipped with high-refresh panels (e.g., AYN Odin 2 Portal, high-end smartphones).
* **Input-to-Screen Latency**: $\le 16.6\text{ ms}$ processing time from hardware controller input event to frame buffer presentation.
* **Cold Boot Performance**: Cold launch to interactive XMB grid in $< 2.5\text{ seconds}$ on mid-range hardware (Snapdragon 665 / Unisoc T618 or better).

### 5.2 Memory Footprint & Resource Management

* **Idle RAM Usage**: $\le 150\text{ MB}$ footprint in background state to avoid triggering Android low-memory killer (LMK) during execution of high-overhead emulators (e.g., Switch/PS2/Wii).
* **Active Background Daemon Limits**: Under 3% CPU utilization when FTPS / Wave Portal servers are idle.
* **Low-Power Screen Standby**: Instant suspension of GL wave rendering loops on device screen-off/lock.

---

## 6. Implementation Roadmap & Release Milestones

### Phase 1: Core Engine & Basic Navigation (Alpha)

* Implement native XMB category/item dual-axis navigation engine.
* Gamepad input layer with automatic controller detection, physical button mapping, and AB/XY swapping.
* Storage engine: Fast local directory scanner with Room DB persistence.
* RetroArch and standard standalone emulator dispatch via Android Intents.
* Initial dynamic wave background shader.

### Phase 2: Metadata, Customization & Media (Beta)

* Scraping pipeline: ScreenScraper, SteamGridDB, and Hasheous API integrations.
* Multi-language scraping and manual asset override interface.
* Media Hub: Audio playback engine with BGM mode, video snap previewing, and photo viewer.
* Custom theme pack loader: Fonts, coldboot animations, sound fonts, and custom color palettes.
* Touchscreen on-screen keyboard and virtual overlay controller.

### Phase 3: Hardware Integration & Network Services (RC)

* Multi-screen / Dual-screen Presentation API integration (split rendering, screen swapping).
* Hot-swappable storage handling with dynamic external volume monitoring and `autorun.inf` execution.
* Integrated RetroAchievements and Dusklight profile sync, progress tracking, and toast notifications.
* Embedded HTTPS Wave Portal for local network management.
* Embedded FTPS file transfer service.

### Phase 4: Production Launch & Ecosystem (v1.0)

* First-run onboarding wizard (storage path discovery, emulator detection, initial scraping).
* App discovery categorization engine (Emulators vs. Games vs. Utilities).
* Performance profiling, thermal mitigation tuning, and public API documentation for community plugins/themes.
