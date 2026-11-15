package dev.xross.launcher

import java.io.File

/**
 * A folder pack that uses every kind of pack file in every place a pack can name one (Phase 2b.8):
 * `imports` (a surfaces file and a rules file), two variants, category and system overrides and
 * rules that pick wallpapers, music loops, shaders and logos, convention files (`boot.mp4`, the
 * `icons/` and `sounds/` names) and files nothing reads. 2b.8-prep checks [LoadedTheme.files]
 * against [USED]; 2b.8b and 2b.8c export and round-trip it.
 */
object StressPack {
    const val ID = "stress"

    /** Every file the manifest reads in some variant, override or rule (pack-relative). */
    val USED: Set<String> = setOf(
        "icons/games.svg", "icons/my-music.svg",
        "sounds/tick.wav", "sounds/back.wav", "sounds/01. opening.wav",
        "logo.svg", "logo-night.svg",
        "postprocess.agsl", "shader.frag",
        "art/base.png", "art/night.webp", "art/vinyl.png", "art/late.jpg",
        "audio/loop.ogg", "audio/night.ogg", "audio/music.ogg",
        "boot.mp4",
    )

    /** In the folder, but no context reads them. */
    val UNUSED: Set<String> = setOf(
        "wallpaper.png", "bgm.ogg", "sounds/cursor.wav", "icons/music.svg", "icons/not_a_glyph.svg",
        "art/orphan.png", "notes.txt", ".git/config", ".nomedia",
    )

    val THEME_JSON = """
        {
          "schemaVersion": 2,
          "name": "Stress",
          "author": "Xross tests",
          "version": "2.3.0",
          "description": "Every pack file in every place",
          "imports": ["surfaces.json", "rules.json"],
          "palette": {"canvas": "#101018", "accent": "#7A5CFF", "accentSecondary": "#3AE0C8"},
          "icons": {"music": "my-music.svg"},
          "audio": {"sounds": {"cursor": "tick.wav"}, "bgmFile": "audio/loop.ogg"},
          "effects": {"wallpaperPath": "art/base.png", "uiFilter": "CUSTOM", "surfaceStyle": "stress_glass"},
          "components": {"logo": "logo.svg", "statusBarLeft": ["LOGO", "WIFI"]},
          "layout": {"preset": "PSP_CENTER", "itemSpacingDp": 16},
          "variants": [
            {"id": "night", "name": "Night", "palette": {"canvas": "#000000"},
             "effects": {"wallpaperPath": "art/night.webp", "shaderPreset": "BOKEH"},
             "components": {"logo": "logo-night.svg"}, "audio": {"bgmFile": "audio/night.ogg"}},
            {"id": "day", "name": "Day", "palette": {"accent": "#FF8800"}, "effects": {"focusFrameStyle": "STATIC_NEON"}}
          ],
          "categoryOverrides": {
            "MUSIC": {"effects": {"wallpaperPath": "art/vinyl.png", "focusFrameStyle": "HARD_SHADOW"}, "audio": {"bgmFile": "audio/music.ogg"}},
            "GAMES": {"palette": {"accent": "#00FF00"}, "effects": {"surfaceStyle": "OLED_TRUE_BLACK", "focusFrameStyle": "GOLD_BEZEL"}}
          },
          "systemOverrides": {
            "dreamcast": {"effects": {"shaderPreset": "CUSTOM", "surfaceStyle": "EINK_HIGH_CONTRAST"}}
          }
        }
    """.trimIndent()

    val SURFACES_JSON = """
        {"surfaces": {"stress_glass": {"label": "Stress glass", "fill": {"type": "SOLID", "color": "#202030", "alpha": 0.8}}}}
    """.trimIndent()

    val RULES_JSON = """
        {"rules": [
          {"match": {"battery": "<= 20"}, "apply": {"palette": {"accent": "#FF3344"}, "effects": {"shaderPreset": "CRT", "focusFrameStyle": "HARD_SHADOW"}}},
          {"match": {"time": "21:00..06:00"}, "apply": {"effects": {"wallpaperPath": "art/late.jpg"}}}
        ]}
    """.trimIndent()

    private const val SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect x="2" y="2" width="20" height="20" fill="#FFFFFF"/></svg>"""
    private const val FRAG = "#version 300 es\nprecision mediump float;\nuniform vec2 iResolution;\nout vec4 fragColor;\nvoid main() { fragColor = vec4(gl_FragCoord.xy / iResolution, 0.5, 1.0); }\n"
    private const val AGSL = "uniform shader content;\nhalf4 main(float2 p) { return content.eval(p); }\n"

    /** Writes the pack into `<themes>/stress/` and returns that folder. */
    fun write(themes: File): File {
        val dir = File(themes, ID).apply { mkdirs() }
        fun put(path: String, text: String) = File(dir, path).apply { parentFile!!.mkdirs() }.writeText(text)
        fun bytes(path: String, n: Int) = File(dir, path).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(n) { (it % 251).toByte() })
        put("theme.json", THEME_JSON)
        put("surfaces.json", SURFACES_JSON)
        put("rules.json", RULES_JSON)
        for (icon in listOf("icons/games.svg", "icons/my-music.svg", "icons/music.svg", "icons/not_a_glyph.svg", "logo.svg", "logo-night.svg")) put(icon, SVG)
        for (sound in listOf("sounds/tick.wav", "sounds/back.wav", "sounds/01. opening.wav", "sounds/cursor.wav")) bytes(sound, 64)
        put("shader.frag", FRAG)
        put("postprocess.agsl", AGSL)
        for (art in listOf("art/base.png", "art/night.webp", "art/vinyl.png", "art/late.jpg", "art/orphan.png", "wallpaper.png")) bytes(art, 128)
        for (loop in listOf("audio/loop.ogg", "audio/night.ogg", "audio/music.ogg", "bgm.ogg")) bytes(loop, 256)
        bytes("boot.mp4", 512)
        put("notes.txt", "not part of the pack")
        put(".git/config", "[core]\n")
        put(".nomedia", "")
        return dir
    }
}
