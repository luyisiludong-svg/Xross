# Xross Dotfile Ricing Engine & Web Studio Specification

This document details the architectural vision, specification, and implementation strategy to transform **Xross** into the first **unix dotfile-driven ricing launcher for Android**, alongside the design of the **Xross Web Theme Studio** and the **Kotlin Compose Multiplatform** preview targets.

---

## 1. Vision & Core Philosophy

### 1.1 The "Hyprland / Waybar of Android"
Traditional Android launchers (Nova, Lawnchair, Niagara, Daijisho) treat customization through rigid GUI settings dialogs, binary preference stores, and predetermined templates. On the other end, tools like KLWP (Kustom Live Wallpaper) provide visual freedom but suffer from heavy battery drain, high RAM footprints, touch latency, lack of gamepad navigation, and proprietary binary files.

In the Linux ricing community (`r/unixporn`), ricing is governed by clean, uncompromised tenets:
1. **Plain-Text Declarative Dotfiles:** Everything is declared in clean, human-readable JSON/CSS/TOML configuration files.
2. **Git-Native Distribution:** Configurations can be versioned, committed, diffed, and shared on GitHub.
3. **Instant Hot-Reloading:** Saving a file (via Termux or ADB) reloads the UI in `< 30 ms` with zero visual flicker and no launcher restarts.
4. **Composable Primitives over Rigid Enums:** Instead of choosing between 4 hardcoded styles, users compose shapes, multi-layer borders, sheen gradients, GLSL shaders, and spring physics.
5. **Ultra-Light Native Performance:** Retaining 60–120 FPS navigation and `< 10%` idle power draw via hardware-accelerated Jetpack Compose and OpenGL ES 3.0.

---

## 2. Directory Architecture (`~/.config/xross`)

The portable Xross root is located at `/sdcard/Xross/` (accessible without root via Android Storage Access Framework or `MANAGE_EXTERNAL_STORAGE`).

```
/sdcard/Xross/
├── .git/                      # User's dotfiles git repository
├── config/
│   ├── settings.json          # Input mappings, deadzones, emulator launch paths
│   └── overrides.json         # User private overrides (API keys, credentials - gitignored)
└── themes/
    └── my-custom-rice/        # Active theme directory
        ├── theme.json         # Master manifest: imports, layout geometry, typography
        ├── surfaces.json      # Declarative box-model definitions (cards, panels, bezels)
        ├── rules.json         # Contextual selectors (time-of-day, per-platform, battery)
        ├── shaders/
        │   ├── background.frag# GLSL ES 3.0 background shader (Layer 2)
        │   └── postprocess.frag# Fullscreen post-processing filter (Layer 7: CRT, bloom, scanlines)
        ├── fonts/
        │   ├── display.ttf    # Clock, category headers, section banners
        │   ├── body.ttf       # Game titles, descriptions, menus
        │   └── mono.ttf       # Tabular numerals: playtime, trophy counters, battery
        ├── sounds/            # 12 sound roles (.wav / .ogg)
        └── icons/             # Custom SVG glyphs, icon masks & masks
```

### 2.1 Developer & Ricer Workflow
* **On-Device via Termux:**
  ```bash
  pkg install git neovim tmux
  cd /sdcard/Xross/themes/my-custom-rice
  nvim surfaces.json   # Hit :w to save -> Android FileObserver hot-reloads UI instantly
  ```
* **Host PC / Mac via ADB:**
  ```bash
  # Live-sync changes during authoring:
  adb push my-custom-rice /sdcard/Xross/themes/
  ```
* **Git Sharing:**
  ```bash
  cd /sdcard/Xross
  git add themes/dracula-y2k/
  git commit -m "feat(rice): add neon chamfered tiles and ascii matrix shader"
  git push origin main
  # Another user installs with:
  # git clone https://github.com/user/xross-dracula themes/dracula-y2k
  ```

---

## 3. The 6 Declarative Ricing Primitives

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                            Xross Ricing Primitives                           │
├──────────────────────┬──────────────────────┬────────────────────────────────┤
│ 1. Surface Box-Model │ 2. Layout Geometry   │ 3. Component Slot Bar          │
│ Shapes, Fills, Bevels│ Anchors, Tile Ratios │ Modular Status & Widgets       │
├──────────────────────┼──────────────────────┼────────────────────────────────┤
│ 4. Rules & Selectors │ 5. GLSL Pipelines    │ 6. Physics & Haptics           │
│ Contextual Triggers  │ Backgrounds + Filters│ Damped Springs & Waveforms     │
└──────────────────────┴──────────────────────┴────────────────────────────────┘
```

### 3.1 Primitive 1: Declarative Surface Box-Model (`surfaces.json`)
Surfaces are built from atomic layers: **Shape**, **Fill**, **Backdrop Blur**, **Specular Sheen**, **Borders**, and **Shadows/Glows**.

```json
{
  "$schema": "https://xross.dev/schema/surfaces.v1.json",
  "surfaces": {
    "glassCard": {
      "shape": { "type": "ROUNDED", "radiusDp": 12 },
      "fill": {
        "type": "LINEAR_GRADIENT",
        "angle": 180,
        "stops": [
          ["#2A2A40", 0.0, 0.70],
          ["#16161F", 0.55, 0.80],
          ["#0A0A12", 1.0, 0.90]
        ]
      },
      "backdropBlurDp": 16,
      "sheen": {
        "heightPercent": 0.35,
        "gradient": [["#FFFFFF", 0.12], ["#FFFFFF", 0.00]]
      },
      "borders": [
        { "type": "INNER", "widthDp": 1.0, "color": "#FFFFFF", "opacity": 0.15 }
      ],
      "shadows": [
        { "type": "OUTER", "offsetDp": [0, 4], "blurDp": 12, "color": "#000000", "opacity": 0.40 }
      ]
    },

    "y2kGlossyBezel": {
      "shape": { "type": "ROUNDED", "radiusDp": 14 },
      "fill": {
        "type": "LINEAR_GRADIENT",
        "angle": 180,
        "stops": [["#E0E5EC", 0.0, 0.85], ["#9BA4B5", 1.0, 0.90]]
      },
      "sheen": {
        "heightPercent": 0.50,
        "gradient": [["#FFFFFF", 0.40], ["#FFFFFF", 0.05]]
      },
      "borders": [
        { "type": "OUTER", "widthDp": 2.0, "color": "#FFFFFF", "opacity": 0.95 },
        { "type": "INNER", "widthDp": 1.5, "color": "#4A5568", "opacity": 0.60 }
      ],
      "glow": { "color": "#FFB5C5", "radiusDp": 14, "intensity": 0.70 }
    },

    "cyberpunkChamfer": {
      "shape": { "type": "CHAMFERED", "cutDp": [12, 0, 12, 0] },
      "fill": { "type": "SOLID", "color": "#0D0B18", "opacity": 0.85 },
      "borders": [
        {
          "type": "OUTER",
          "widthDp": 1.5,
          "brush": { "type": "SWEEP_GRADIENT", "colors": ["#00F0FF", "#FF003C", "#00F0FF"] }
        }
      ],
      "glow": { "color": "#00F0FF", "radiusDp": 10, "intensity": 0.75 }
    },

    "retroWin95": {
      "shape": { "type": "RECTANGLE" },
      "fill": { "type": "SOLID", "color": "#C0C0C0" },
      "borders": [
        { "side": "TOP", "widthDp": 2, "color": "#FFFFFF" },
        { "side": "LEFT", "widthDp": 2, "color": "#FFFFFF" },
        { "side": "BOTTOM", "widthDp": 2, "color": "#404040" },
        { "side": "RIGHT", "widthDp": 2, "color": "#404040" }
      ]
    }
  }
}
```

### 3.2 Primitive 2: Layout & Geometry
Declared within `theme.json`:

```json
"layout": {
  "crosshair": {
    "anchorX": 0.30,
    "anchorY": 0.38,
    "clamping": "KEEP_NEIGHBOUR_VISIBLE"
  },
  "tile": {
    "aspectRatio": "16:9",
    "focusedScale": 1.18,
    "unfocusedAlpha": 0.45,
    "spacingDp": 12,
    "surface": "glassCard",
    "focusDivider": {
      "type": "FULL_BLEED_HAIRLINE",
      "color": "#FFFFFF",
      "opacity": 0.20,
      "offsetYDp": 6
    }
  },
  "drillDown": {
    "style": "XMB_FOLDER_COLUMN",
    "folderIconOffsetDp": -20
  }
}
```

### 3.3 Primitive 3: Modular Waybar-Style Component Slots
Status bar and widgets use modular array slots:

```json
"components": {
  "statusBar": {
    "left": [
      { "id": "LOGO", "style": "MONOCHROME", "tint": "#FFB5C5" },
      { "id": "WIFI", "showSignalStrength": true }
    ],
    "right": [
      { "id": "CLOCK", "format": "HH:mm", "font": "mono" },
      { "id": "BATTERY", "mode": "PERCENTAGE_AND_ICON", "warnBelow": 20 },
      { "id": "AVATAR", "sizeDp": 32, "morphOnFocus": true }
    ]
  },
  "miniPlayer": {
    "mode": "FLOATING_CARD",
    "surface": "glassCard",
    "showProgress": true
  },
  "telemetry": {
    "tier": "COMPACT_PILL",
    "surface": "glassCard",
    "elements": ["TROPHY_RATIO", "MASTERY_RIBBON", "POINTS_PILL"]
  }
}
```

### 3.4 Primitive 4: Contextual State Rules (`rules.json`)
CSS-like dynamic cascading based on active launcher context:

```json
{
  "rules": [
    {
      "match": { "category": "MUSIC" },
      "apply": {
        "layout.tile.aspectRatio": "1:1",
        "shaders.background": "shaders/ascii_matrix.frag",
        "layout.tile.surface": "cyberpunkChamfer"
      }
    },
    {
      "match": { "system": "dreamcast" },
      "apply": {
        "palette.accent": "#FF8C42",
        "surfaces.glassCard.glow.color": "#FF8C42"
      }
    },
    {
      "match": { "battery.level": "<= 20" },
      "apply": {
        "palette.accent": "#FF3344",
        "shaders.speedMultiplier": 0.3
      }
    },
    {
      "match": { "time": "21:00..06:00" },
      "apply": {
        "palette.canvas": "#05050A",
        "surfaces.glassCard.fill.stops[0][2]": 0.95
      }
    }
  ]
}
```

### 3.5 Primitive 5: Dual-Stage GLSL Shaders & Audio-Reactivity
Two programmable stages:
1. **Background Shader (`background.frag`)**: Renders on Layer 2 behind game art and UI cards.
2. **Fullscreen Post-Processing Shader (`postprocess.frag`)**: Renders on Layer 7 over the whole screen (authentic CRT barrel distortion, aperture grilles, bloom, Game Boy dot matrix LCD response).

Standard uniforms provided to fragment shaders:
```glsl
#version 300 es
precision highp float;

uniform float     uTime;
uniform vec2      uResolution;
uniform vec4      uColorCanvas;
uniform vec4      uColorAccent;
uniform float     uBatteryLevel;
uniform float     uAudioBass;      // 20-250 Hz energy
uniform float     uAudioBeat;      // Transient beat spike [0.0..1.0]
uniform sampler2D uContentTexture; // Whole-screen framebuffer for Layer 7
out vec4          fragColor;
```

### 3.6 Primitive 6: Motion Physics & Sound Map
```json
"motion": {
  "navigationSpring": { "stiffness": 520, "damping": 1.0 },
  "drillDownSpring":  { "stiffness": 420, "damping": 0.85 },
  "focusGlowEasing":  "EASE_IN_OUT",
  "focusGlowDurationMs": 1700
},
"audio": {
  "pitchJitter": 0.02,
  "stereoPanning": true,
  "events": {
    "cursor": "sounds/tick.wav",
    "confirm": "sounds/confirm.wav",
    "back": "sounds/cancel.wav",
    "categorySwitch": "sounds/whoosh.wav",
    "gameboot": "sounds/launch.wav",
    "trophy": "sounds/trophy.wav"
  }
}
```

---

## 4. The Xross Web Theme Studio

A zero-install, browser-based playground allowing designers and developers to create, test, and export themes without booting an Android device or emulator.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│  XROSS THEME STUDIO                          [🎮 8BitDo Connected] [⬇ Export] │
├───────────────────────────────┬────────────────────────────────────────────────┤
│  CODE EDITOR (Monaco / JSON)  │  VIRTUAL HANDHELD PREVIEW                      │
│                               │  Device: [AYN Odin 2 (16:9 FHD) ▼]             │
│  1  {                         │ ┌────────────────────────────────────────────┐ │
│  2    "$schema": "xross.json",│ │ 12:45                   98% 🔋  (Avatar)   │ │
│  3    "name": "Y2K Cyber",    │ │                                            │ │
│  4    "surfaces": {           │ │ Settings  Music  [GAMES]  Apps             │ │
│  5      "glassCard": {        │ │                     │                      │ │
│  6        "shape": "CHAMFER", │ │                 [CHRONO TRIGGER]           │ │
│  7        "cutDp": 8,         │ │                     │                      │ │
│  8        "fill": "#0D0B18",  │ │                  TEKKEN 6                  │ │
│  9        "glow": "#00F0FF"   │ │                     │                      │ │
│ 10      }                     │ │ ◀ 16:9 Hero Plate   OUTRUN 2006            │ │
│ 11    }                       │ │                                            │ │
│ 12  }                         │ │ [Live WebGL 2.0 Shader Canvas in preview]  │ │
│                               │ └────────────────────────────────────────────┘ │
│                               │  [Tabs: surfaces.json | theme.json | shader]   │
└───────────────────────────────┴────────────────────────────────────────────────┘
```

### 4.1 Technical Architecture
* **Stack:** TypeScript, Vite, WebGL 2.0, Monaco Editor, Tailwind CSS, JSZip.
* **WebGL 2.0 = OpenGL ES 3.0:** WebGL 2.0 matches Android's GLSL ES 3.0 shader pipeline nearly 1:1. The exact same `shader.frag` code compiles and executes directly inside a browser `<canvas>` element.
* **HTML5 Gamepad API (`navigator.getGamepads()`):** Users plug in a DualSense, Xbox, or retro controller via USB/Bluetooth and navigate the virtual XMB UI directly using physical D-pad buttons.
* **Device Frame Emulation:** One-click toggles across target handheld aspect ratios:
  * **16:9 Widescreen:** AYN Odin 2, Retroid Pocket 4 Pro (1920×1080 / 1334×750).
  * **4:3 Retro:** Retroid Pocket 2S, Anbernic RG405M (640×480).
  * **1:1 Square:** Anbernic RG Cube (720×720).
  * **20:9 Modern Phone:** Samsung Galaxy A55 (2340×1080).
* **Instant Sharing & Export:**
  * **URL Hash Sharing:** Encodes the JSON/shader payload into a compressed URL hash (`https://xross.dev/studio#data=...`) for one-click sharing on Reddit/Discord.
  * **`.xtheme` Packager:** Uses JSZip in-browser to bundle `theme.json`, `surfaces.json`, `shaders/`, and fonts into a ready-to-import `.xtheme` file.

---

## 5. Kotlin Multiplatform / Desktop Target

In addition to a web studio, Xross can run its real Kotlin Compose UI on desktop computers (macOS, Linux, Windows) using **Compose Multiplatform (CMP)**.

```
                  ┌─────────────────────────────────────────┐
                  │    Shared Kotlin Compose UI & State     │
                  │  (XmbScreen, GameTile, Modifier.xross)  │
                  └────────────────────┬────────────────────┘
                                       │
                ┌──────────────────────┴──────────────────────┐
                ▼                                             ▼
     [Android Native App]                         [Desktop / Wasm CMP]
   - Uses Android Context                       - Uses Skiko / WebGL Canvas
   - Launches real emulators                    - Uses Mock ROM Library
   - Scans /sdcard/Xross/                       - Reads local project folder
```

### 5.1 Architecture
1. **Decoupled Engine Interfaces:**
   * Abstract Android `Context`, `StorageManager`, and `Intent` launches behind common interfaces (the project already started this with `GameLauncher`).
   * Introduce `ThemeRepository` and `ROMRepository` interfaces with mock desktop implementations.
2. **Desktop Launcher (`:desktop` module):**
   * Configured via `org.jetbrains.compose` desktop plugin.
   * Run directly via `./gradlew :desktop:run`.
   * Hot-reloads with Compose Desktop tooling, enabling rapid theme design and testing with native keyboard and controller input on Mac/PC.
3. **WebAssembly Target (Compose Wasm):**
   * Enables the compiled Kotlin app to run directly in the browser via WebAssembly (Wasm) + Skiko for 100% pixel-perfect fidelity with the Android build.

---

## 6. Implementation Feasibility & Phased Roadmap

### Phase 1: Foundation (Theme 2.0 / Milestone 2b.2)
* Replace closed `SurfaceStyle` enum with open `SurfaceSpec` data class.
* Implement Compose `Modifier.xrossSurface(spec: SurfaceSpec)` supporting rounded, chamfered paths, linear/sweep gradients, and inner/outer borders.
* Convert existing default styles (`FLAT_GLASS`, `Y2K_GLOSSY_BEZEL`, `OLED_TRUE_BLACK`, `EINK_HIGH_CONTRAST`) into built-in JSON presets.

### Phase 2: Live Hot-Reloading & Rules (Milestones 2b.3–2b.4)
* Implement Android `ThemeFileWatcher` via `FileObserver` for sub-second UI updates upon file modifications in `/sdcard/Xross/themes/`.
* Implement `rules.json` contextual selector evaluation in `XmbViewModel`.
* Bind audio-reactive and battery uniforms to `WaveRenderer`.

### Phase 3: The Web Theme Studio
* Deploy the standalone Web Theme Studio (Vite + WebGL 2.0 + Monaco) on GitHub Pages.
* Implement the HTML5 Gamepad API bridge and one-click `.xtheme` export.

### Phase 4: Desktop CMP & Community Rice Hub
* Introduce the `:desktop` target for local JVM testing.
* Launch the community dotfiles registry where users can publish and download rices directly into Xross.
