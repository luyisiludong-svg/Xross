# Project Xross — Master Feature & Customization Specification

**Document Version:** 2.0.0  
**Target Releases:** Phase 2b (Active Sprint) & Phase 3 (Customization Engine)  
**Package:** `dev.xross.launcher`  
**Configuration Targets:** `Xross/config/settings.json`, `Xross/themes/<theme_id>/theme.json`

---

## 1. Executive Summary & Architecture Invariants

Project Xross delivers console-grade operating system fidelity with deep visual, spatial, and acoustic customizability. This document defines the engineering requirements, data models, and shader/rendering contracts for both **immediate UI and gameplay enhancements** and the **extended Theme 2.0 customization engine**.

### Core Architecture Invariants
1. **Strict Credential Isolation**: Sensitive tokens (RetroAchievements Web API keys, ScreenScraper passwords) remain strictly isolated in `CredentialStore` (`xross_credentials`, private app sandbox). They must **never** be written to `settings.json`, `theme.json`, or shared storage.
2. **Platform & File Compatibility**: Retain full backwards compatibility down to Android 9 (API 28). Preserve `.nomedia` flags in `Xross/media/` and `Xross/themes/`.
3. **Performance & Test Gates**: Maintain the locked 60/120 FPS render budget ($0\text{--}1.5\%$ jank target) and ensure zero regressions across existing unit and Robolectric tests (`./gradlew :app:testDebugUnitTest`).

---

## 2. Immediate Feature Implementations (Phase 2b Sprint)

### 2.1 Far-Right Status Bar Avatar with Expanding In-Place RA HUD
* **Layout & Placement (`XmbStatusBar.kt`)**:
  - Relocate the circular profile avatar badge ($32\text{ dp}$) to the **far right edge** of the status bar, placing the digital clock and battery capsule indicators to its left.
  - Apply custom border width, border color, and glow effects as defined in Section 2.4.
* **In-Place Mini-HUD Expansion Interaction**:
  - Deprecate opening a full-screen replacement route upon selecting the avatar.
  - Implement an in-place shared-container morph/expansion animation:
    - When tapped or focused via gamepad (`Select` or navigating `Up` from the top row of a category root), the avatar expands horizontally and vertically into a compact, frosted-glass capsule/mini-sheet anchored in the top-right corner.
    - During expansion, smoothly crossfade and **replace the battery gauge and clock indicators** behind the expanded HUD.
    - Contents of the mini-HUD: active RetroAchievements username, user avatar thumbnail (`Xross/media/avatars/<user>.png`), global rank, Hardcore and Softcore points, and a horizontal row of recent achievement badge thumbnails.
  - **Dismissal**:
    - Clicking/tapping outside the expanded bounding box, pressing `Back` (gamepad `B` / `East` / `KEYCODE_BACK`), or navigating `Down` collapses the mini-HUD smoothly back into the circular avatar, crossfading the battery status gauge and clock back into view.

### 2.2 Classic XMB Rectangular Game Tiles & Details Screen Game Paging
* **Rectangular Selection Tile (`GameItemView.kt` / `XmbScreen.kt`)**:
  - In the vertical ribbon of the `Games` category, replace the portrait 3D box-art thumbnail format with a horizontal rectangular card modeled after the original PlayStation 3 / PSP XMB game item tiles (approximate $16:9$ or $2:1$ aspect ratio).
  - Graphic priority within the rectangular tile:
    1. Centered clear logo (`wheel` art from `media/wheels/`).
    2. Fallback to wide hero/capsule art (`media/heroes/` or `media/capsules/`).
    3. Clean typographic title on a frosted translucent plate if no image art is available.
  - Ensure the bounding box scales and applies focus glow cleanly without clipping adjacent items in the ribbon.
* **Game Details View Traversal (`DetailsScreen.kt`)**:
  - When viewing a game's details screen (`Options ▸ Details` or `→` on a game), enable moving directly to adjacent games in the active list without backing out to the ribbon.
  - Bind previous/next navigation to controller shoulder bumpers (`L1` / `R1`) as well as horizontal D-pad directions (`Left` / `Right`).
  - Animate the transition between games using a smooth horizontal slide/crossfade.
  - Update all dynamic bindings reactively upon game change: metadata, developer/publisher, release date, hero backdrop, and game-specific achievement records (`AchievementsRepository`).

### 2.3 Playtime Subtitle & Contextual Game RetroAchievements Telemetry
* **Playtime Ribbon Subtitle**:
  - In the vertical game list, replace the file size / weight display (e.g., `"1.4 GB"`) beneath the focused game title with total tracked playtime from `PlaySessionTracker`.
  - Display rules:
    - If unplayed: `"Never played"`.
    - If playtime $< 1\text{ hr}$: `"Playtime: X mins"`.
    - If playtime $\ge 1\text{ hr}$: `"Playtime: Xh Ym"` (e.g., `"Playtime: 14h 25m"`).
* **Bottom-Right Game RetroAchievements HUD**:
  - In the lower-right corner of the primary XMB interface (elevated cleanly above the bottom gamepad prompt pill, coordinating layout with the floating `MiniPlayerCard`):
    - When hovering over a game that has verified RetroAchievements support, display a compact game-telemetry badge:
      - Trophy/Achievement icon + unlock ratio (e.g., `🏆 18/42 unlocked`).
      - Earned points vs. total points (e.g., `210 / 500 pts`).
      - Mastery ribbon badge if the game is $100\%$ completed.
    - If the game is not supported by RA or unhashed, gracefully hide this element.

### 2.4 Extended Profile Avatar Customization Engine
* **Settings Model & Serialization (`settings.json`)**:
  - Add a `profile.avatarStyle` configuration block to `SettingsStore` / `ConfigFiles.kt`:
    ```kotlin
    @Serializable
    data class AvatarStyleConfig(
        val borderColorHex: String = "#FFB5C5", // Default pastel accent
        val borderWidthDp: Float = 2.0f,        // 1.0f to 6.0f
        val glowEnabled: Boolean = true,
        val glowSpeed: Float = 1.0f,            // 0.5x to 2.0x animation pulse rate
        val glowIntensity: Float = 0.6f         // 0.1f to 1.0f alpha/blur radius
    )
    ```
* **UI Controls (`Settings ▸ Theme ▸ Profile Customization` or `Settings ▸ Accounts`)**:
  - Expose interactive sliders and toggles:
    - Border Color: palette presets (pastel pink, neon cyan, gold, white, custom hex).
    - Border Width: stepped slider ($1\text{ dp}$ to $6\text{ dp}$).
    - Glow Toggle: On/Off switch.
    - Glow Speed: float slider ($0.5\times$ to $2.0\times$).
    - Glow Intensity: float slider (blur radius / opacity).
  - Provide a live interactive preview of the avatar composable directly inside the settings panel.
* **Shader / Modifier Implementation**:
  - Apply the pulse glow using an infinite transition animating blur radius and alpha according to `glowSpeed` and `glowIntensity`.
  - Pause animations when the screen is idle or backgrounded to conserve CPU and battery.

### 2.5 Dedicated "RetroAchievements Profile" Section under Games
* **Navigation Placement**:
  - In the `Games` category ribbon, add a dedicated root entry titled **"RetroAchievements"** positioned alongside special collections (`All Games`, `Favorites`, `Recently Played`, `Android Games`).
* **Hub Screen Layout (Reference: `Screenshot 2026-11-11 at 19.15.40.png`)**:
  - When selected, transition into a console-grade RetroAchievements dashboard:
    - **Header Card**: Frosted glass surface displaying full player identity (avatar, username, global ranking, member since, Softcore/Hardcore point breakdown, and RetroRatio).
    - **Completion Matrix**: Visual progress gauge showing overall achievement unlock rate across all scanned library games.
    - **Mastery Showcase**: Horizontal carousel or grid of fully mastered titles with gold laurel frames.
    - **Recent Activity Feed**: Vertical list of recently unlocked badges, containing timestamp, badge thumbnail, achievement title, description, and point value.
  - Provide empty/offline placeholder states when no network connection or linked credentials exist.

---

## 3. System-Wide Customization & Theme Engine 2.0 (Phase 3 Engine)

### 3.1 Spatial Layout & Axis Geometry
* **Crosshair Anchor Point**:
  - Expose configurable vertical and horizontal screen percentage anchors in `settings.json` (`layout.crosshairX`, `layout.crosshairY`):
    - **Classic PS3 Profile**: Horizontal axis at $35\%$ from viewport top, vertical active column at $30\%$ from viewport left.
    - **PSP True Center**: Symmetric center intersection ($50\% / 50\%$).
    - **Modern Widescreen ($16:9$ / $20:9$ / $21:9$)**: Category bar anchored high at $20\%$, dedicating $80\%$ of viewport area to game media, hero banners, and the vertical game ribbon.
* **Card & Tile Geometry Overrides**:
  - Allow users to select tile aspect ratios independently per category:
    - **$16:9$ / $2:1$ Rectangular Tile**: PS3/PSP XMB-style game item plate showcasing horizontal clear logos (`wheels`) or capsule banners.
    - **$3:4$ / $2:3$ Portrait Box Art**: Authentic physical cartridge boxes and jewel cases.
    - **$4:1$ Compact Pill**: High-density textual pill for large collections.
* **Focus Scaling & Ribbon Dynamics**:
  - Unfocused Item Opacity slider: $10\%$ to $80\%$ alpha.
  - Focused Item Scale factor: $1.00\times$ (flat) to $1.35\times$ (dramatic bloom).
  - Vertical List Spacing: `Tight` ($6\text{ dp}$ gap), `Standard` ($12\text{ dp}$ gap), `Spacious` ($20\text{ dp}$ gap).

### 3.2 Materials, Glassmorphism & Custom Shaders
* **Custom GLSL Fragment Shader Loader**:
  - Extend `WaveBackground.kt` and `WaveRenderer.kt` to load arbitrary GLSL ES 3.0 fragment shaders from theme folders (`Xross/themes/<theme_id>/shader.frag`).
  - Standard uniforms provided to all shaders:
    ```glsl
    uniform float uTime;
    uniform vec2 uResolution;
    uniform vec4 uColorTop;
    uniform vec4 uColorBottom;
    uniform vec4 uAccent;
    ```
  - Built-in alternative shader profiles:
    - *Starfield / Warp Dust*: Flowing 3D star streaks.
    - *Floating Bokeh / Orbs*: Soft drifting light spheres.
    - *CRT Scanlines & Phosphor Bloom*: Subtly modulated scanline overlay with soft chromatic aberration.
* **Frosted Glass Surface Engine**:
  - Surface rendering parameters exposed in theme JSON and runtime settings:
    - Surface opacity ($0\%$ transparent to $85\%$ milky acrylic).
    - Background blur radius ($0\text{ dp}$ to $48\text{ dp}$).
    - Glint Border: $1\text{ px}$ inner hairline border supporting gradient or iridescent glints.
* **Halo & Focus Glow Engine**:
  - Aura profiles: *Breathing Pulse* (sinusoidal alpha/spread modulation), *Static Neon Bloom*, or *Hard Shadow* ($2\text{ dp}$ offset, zero blur).
  - Independent controls for pulse speed ($0.5\times$ to $2.0\times$), blur radius ($2\text{ dp}$ to $24\text{ dp}$), and peak opacity ($0.1$ to $1.0$).

### 3.3 Reactive Color & Dynamic Lighting
* **Contextual Palette Extraction (Material You for ROMs)**:
  - Integrate Android Jetpack Palette API to sample scraped game media:
    - Extract dominant, vibrant, and muted accents from `Xross/media/heroes/` or `Xross/media/wheels/`.
    - When hovering over a game, smoothly morph the GLSL wave colors, status bar accent rings, and focus glow to match the active game in real time.
    - Debounce threshold ($250\text{ ms}$) matching the hero art fade to prevent color thrashing during rapid D-pad navigation.
* **Specialized Contrast & OLED Profiles**:
  - *OLED True Black*: Pure `#000000` canvas with zero surface fills and glowing wireframe borders to maximize battery efficiency on OLED panels.
  - *Cyber-Pastel / Y2K*: Twilight lavender backdrop with lilac, mint, and strawberry-pink accents.
  - *Monochrome E-Ink*: High-contrast 1-bit silhouette layout optimized for reflective displays.
* **Circadian Day/Night Time Cycles**:
  - Expand the monthly "Original" theme into an optional hourly circadian curve:
    - Morning ($06:00$--$11:00$): Crisp cool cyan / sky blue.
    - Afternoon ($11:00$--$18:00$): Vibrant azure / cobalt.
    - Sunset ($18:00$--$21:00$): Warm amber / rose gold.
    - Night ($21:00$--$06:00$): Deep indigo / midnight purple.

### 3.4 Modular Typography & Telemetry Density
* **Modular Multi-Typeface Mapping**:
  - Themes may define distinct fonts for specialized roles rather than a single global font:
    - `fontDisplay`: Heavy rounded typography for clocks, status titles, and category labels.
    - `fontBody`: Highly legible sans-serif for game descriptions, release metadata, and system dialogs.
    - `fontMono`: Tabular monospaced figures for RetroAchievements scores, timestamps, battery percentages, and playtime counters.
* **Contextual Game Metadata Badges**:
  - User-selectable badge pills displayed alongside the focused game title:
    - Playtime counter (`Playtime: 14h 25m` vs. `Never played`).
    - RetroAchievements progress pill (`🏆 18/42`).
    - System tag with authentic hardware vector glyph (`SNES`, `PSX`, `GBA`).
    - Release year + Developer / Publisher pill.
* **Text Overflow Handling**:
  - User choice between smooth marquee auto-scrolling, soft alpha gradient fade at the bounding edge, or standard ellipsis truncation (`…`).

### 3.5 Acoustic Ambiance & Haptic Engine
* **Background Music (BGM) Ambience**:
  - Theme packs may supply a looping ambient track (`bgm.ogg` / `bgm.mp3`).
  - Automatic audio ducking:
    - Lower BGM to $20\%$ volume when hovering over games playing video snaps.
    - Mute cleanly when launching external emulator intents.
* **Fine-Grained Volume & Audio Modulation**:
  - Independent volume sliders for UI navigation sound effects, system boot/shutdown cues, and media/video snap playback.
  - *Acoustic Pitch Jitter*: Optional toggle adding randomized $\pm 2\%$ pitch modulation to `cursor.wav` to simulate authentic mechanical relay switching.
* **Tactile Haptic Feedback (Vibration Motor Integration)**:
  - Haptic pulse profiles for handhelds with rumble motors (`Off`, `Subtle Click`, `Crisp Snap`, `Deep Thud`).
  - Triggers: Category switch, list navigation, boundary error (`system_ng`), and RetroAchievements toast appearance.

### 3.6 Modular Component Layout
* **Status Bar Layout Switcher**:
  - Reorderable component clusters:
    - Left Cluster: [Logo / Title, Wi-Fi indicator, Volume pill].
    - Right Cluster: [Battery percentage / pill, Clock, User Avatar / RA badge].
  - Battery presentation modes: Horizontal battery capsule, raw percentage (`84%`), or estimated time remaining.
* **Floating Mini-Player Modes**:
  - *Bottom-Right Floating Card*: Default rounded card elevated above button hints.
  - *Top Status Ticker*: Single-line scrolling marquee integrated directly into the status bar.
  - *Off / Background Only*: Headless media playback without on-screen cards.
* **Gamepad Glyph Overrides**:
  - Independent glyph style selector decoupling glyph visuals from controller vendor detection:
    - PlayStation (✕, ○, △, □)
    - Xbox (A, B, X, Y colored)
    - Nintendo (A, B, X, Y reversed positions)
    - Minimalist Monochrome (flat circular outlines)

---

## 4. Theme Specification (`theme-v2.json`)

To enable seamless exporting and sharing of community themes, the theme manifest format is extended as follows:

```json
{
  "$schema": "https://xross.launcher/schemas/theme-v2.json",
  "name": "Y2K Cyber Lilac",
  "author": "XrossCommunity",
  "version": "2.0.0",
  "layout": {
    "crosshairX": 0.32,
    "crosshairY": 0.40,
    "cardAspectRatio": "16:9",
    "itemSpacingDp": 12,
    "focusedScale": 1.18,
    "unfocusedAlpha": 0.45,
    "statusBarMode": "EXPANDED_RIGHT_AVATAR"
  },
  "palette": {
    "canvas": "#1B1829",
    "surface": "#FFFFFF25",
    "surfaceBorder": "#FFFFFF60",
    "accent": "#FFB5C5",
    "accentSecondary": "#C8B6FF",
    "textPrimary": "#F4F4FB",
    "textSecondary": "#A39DB8"
  },
  "avatarStyle": {
    "borderColorHex": "#FFB5C5",
    "borderWidthDp": 2.5,
    "glowEnabled": true,
    "glowSpeed": 1.2,
    "glowIntensity": 0.7
  },
  "effects": {
    "shaderPath": "shaders/custom_wave.frag",
    "blurRadiusDp": 24.0,
    "glowEnabled": true,
    "glowColor": "#FFB5C5",
    "glowRadiusDp": 14.0,
    "glowSpeed": 1.2,
    "adaptiveGamePalette": true
  },
  "wave": {
    "speed": 0.85,
    "lineCount": 6,
    "turbulence": 1.1,
    "ribbonThickness": 1.3,
    "gradient": ["#C8B6FF", "#BBD0FF", "#B8F2E6"]
  },
  "typography": {
    "display": "fonts/RoundedBold.ttf",
    "body": "fonts/SansRegular.ttf",
    "mono": "fonts/SpaceMono.ttf"
  },
  "audio": {
    "bgmFile": "audio/ambient_loop.ogg",
    "bgmVolume": 0.35,
    "effectsVolume": 0.80,
    "pitchJitter": true
  }
}
```

---

## 5. Implementation Milestones & Roadmap

| Milestone | Target Scope | Prerequisites |
| :--- | :--- | :--- |
| **Phase 2b.1 (Current)** | Far-right status bar avatar with expanding mini-HUD, $16:9$ rectangular game selection tile, Details screen game paging (`L1`/`R1`, `Left`/`Right`), playtime ribbon subtitles, lower-right game RA telemetry, avatar style settings (`settings.json`), and dedicated Games RA profile screen. | RetroAchievements & Details Screen (Phase 2) |
| **Phase 2b.2** | Theme 2.0 JSON deserializer, modular font roles (`fontDisplay`, `fontBody`, `fontMono`), and card aspect ratio selector ($16:9$ vs. $3:4$). | Theme Pack Loader (Phase 2) |
| **Phase 2b.3** | Contextual Palette Engine (Material You via Jetpack Palette API) with GLSL uniform and accent morphing. | Scraper Art Cache (`Xross/media/`) |
| **Phase 2b.4** | Custom external GLSL fragment shader loader (`shader.frag`) with standard uniform bindings. | Wave Uniform Engine |
| **Phase 3.1** | Ambient BGM loop manager with automatic ducking on video snap hover. | Media Hub & SnapPlayer |
| **Phase 3.2** | Tactile Haptic engine binding `Vibrator` / `VibratorManager` to D-pad navigation and errors. | Gamepad input layer |

---

## 6. Verification & Test Gates

1. **Unit & Robolectric Tests (`:app:testDebugUnitTest`)**:
   - Verify `AvatarStyleConfig` serialization/deserialization within `settings.json`.
   - Verify playtime string formatting helpers across boundary cases ($0\text{ s}$, $45\text{ s}$, $61\text{ m}$, $120\text{ h}$).
   - Test game pagination boundaries in `DetailsViewModel` (first item, last item, single-item lists).
   - Ensure all 395 baseline tests pass with zero failures.
2. **Device Smoke & Hardware Verification**:
   - Confirm D-pad and shoulder button (`L1`/`R1`) paging on the Details screen without menu pop bugs.
   - Verify that expanding and collapsing the top-right avatar mini-HUD produces zero layout jitter and cleanly restores the battery status bar.
   - Confirm that the avatar glow animation does not trigger recomposition loops outside of the avatar modifier.