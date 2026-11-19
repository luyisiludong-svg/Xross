package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

// region Enums (Roadmap Appendix A.11)

/** What the top-left of the menu shows (2b.7c draws [SOCIAL_STACK]). */
enum class TopLeftClusterMode { EMPTY, SOCIAL_STACK }

/** The focused game's RetroAchievements badge (2b.7d draws [RICH_CARD]). */
enum class TelemetryMode { OFF, COMPACT_PILL, RICH_CARD }

/** The mini player's form (2b.7b draws [STATUS_TICKER] and [OFF]). */
enum class MiniPlayerMode { FLOATING_CARD, STATUS_TICKER, OFF }

/** How the status bar's `BATTERY` slot draws the battery (2b.7a). */
enum class BatteryDisplayMode {
    /** The three-bar gauge alone. */
    CAPSULE_ICON,

    /** "87%" alone, in the mono font. */
    PERCENTAGE,

    /** "87%" then the gauge (the bar before 2b.7). */
    BOTH,

    /** "1 h 20 m to full" while charging, when Android knows; otherwise exactly [PERCENTAGE]. */
    TIME_REMAINING,
}

/** The button glyphs drawn, whatever controller is detected (2b.7b). */
enum class GlyphOverrideMode { AUTO, PLAYSTATION, XBOX, NINTENDO, MONOCHROME_MINIMAL }

/** One slot of the status bar's `statusBarLeft` / `statusBarRight` clusters (2b.7a). */
enum class StatusSlot {
    /** The pack's `components.logo`. */
    LOGO,

    /** Wi-Fi (with its strength), cellular, ethernet or offline. */
    WIFI,

    /** The system media volume, for 3 s after it changes (or always, `volumeAlways`). */
    VOLUME,
    BATTERY,
    CLOCK,

    /** The profile avatar and its mini-HUD: always the rightmost slot of `statusBarRight`. */
    AVATAR,
}

/** A pill beside the focused game's title (2b.7b, Appendix A.8). */
enum class BadgePill { SYSTEM, YEAR, DEVELOPER, PLAYTIME, RA_PROGRESS }

/** Which status-bar cluster. */
enum class StatusSide { LEFT, RIGHT }

// endregion

/**
 * The `components` block (Roadmap Appendix A.10, A.12): which widgets the menu draws and how.
 * Read from the pack's base and its variants only; category and system overrides and rules can't
 * patch it (a status bar that rearranges itself per category or battery level reads as a bug), so
 * a `components` key there is reported and ignored.
 *
 * ```json
 * "components": {
 *   "statusBarLeft": ["LOGO", "WIFI"], "statusBarRight": ["CLOCK", "BATTERY", "AVATAR"],
 *   "batteryMode": "TIME_REMAINING", "clockFormat": "EMMMdHm", "logo": "art/logo.svg",
 *   "volumeAlways": false
 * }
 * ```
 * Names are matched ignoring case, `-` and spaces, with the aliases in [ComponentNames]; a bad
 * value is reported and falls back on its own (the layer below's), leaving the rest of the block.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class Components(
    @kotlinx.serialization.json.JsonNames("topLeft") val topLeftCluster: String? = null,
    val statusBarLeft: List<String>? = null,
    val statusBarRight: List<String>? = null,
    @kotlinx.serialization.json.JsonNames("battery", "batteryDisplay") val batteryMode: String? = null,
    /** A `DateFormat` skeleton of `y M d E H h m a` ([ClockFormat]); empty is the default clock. */
    @kotlinx.serialization.json.JsonNames("clock") val clockFormat: String? = null,
    @kotlinx.serialization.json.JsonNames("telemetry") val telemetryMode: String? = null,
    @kotlinx.serialization.json.JsonNames("miniPlayer") val miniPlayerMode: String? = null,
    @kotlinx.serialization.json.JsonNames("glyphs", "glyphStyle") val glyphOverride: String? = null,
    val badgePills: List<String>? = null,
    /** An SVG, PNG or WebP from the pack root, for the `LOGO` slot. */
    val logo: String? = null,
    /** Show `VOLUME` all the time instead of for 3 s after a change. */
    val volumeAlways: Boolean? = null,
    /**
     * Frame the focused game tile with the pack's `focusFrameStyle` (default true). Off, the tile
     * still grows and shows its title plate; badges and thumbnails keep their frame either way.
     * Kept as JSON so a value that isn't `true` or `false` is reported and dropped on its own
     * instead of failing the whole manifest ([ComponentsPatch.of] reads it).
     */
    @kotlinx.serialization.json.JsonNames("glow") val tileGlow: kotlinx.serialization.json.JsonElement? = null,
) {
    companion object {
        /** Every property `components` recognizes, with its aliases. */
        val KEYS: Set<String> = setOf(
            "topLeftCluster", "topLeft", "statusBarLeft", "statusBarRight",
            "batteryMode", "battery", "batteryDisplay", "clockFormat", "clock",
            "telemetryMode", "telemetry", "miniPlayerMode", "miniPlayer",
            "glyphOverride", "glyphs", "glyphStyle", "badgePills", "logo", "volumeAlways",
            "tileGlow", "glow",
        )

        /**
         * Problems with `components` in a pack's merged JSON [root]: unknown keys in the base's and
         * each variant's block, and a block in a category or system override or a rule, which is ignored.
         */
        fun schemaProblems(root: JsonObject): List<String> {
            val out = ArrayList<String>()
            fun keys(where: String, e: kotlinx.serialization.json.JsonElement?) {
                val block = e as? JsonObject ?: return
                block.keys.forEach { key ->
                    if (key !in KEYS) out += "$where.components.$key isn't a components property (see docs/THEME_SPECIFICATION.md, components reference)"
                }
            }
            fun obj(e: kotlinx.serialization.json.JsonElement?) = e as? JsonObject
            keys("theme", root["components"])
            (root["variants"] as? JsonArray)?.forEachIndexed { i, v ->
                val id = (obj(v)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "$i"
                keys("variants.$id", obj(v)?.get("components"))
            }
            val ignored = "is ignored: components follow the pack and its variants only"
            for (overrides in listOf("categoryOverrides", "systemOverrides")) {
                obj(root[overrides])?.forEach { (k, v) -> if (obj(v)?.containsKey("components") == true) out += "$overrides.$k.components $ignored" }
            }
            (root["rules"] as? JsonArray)?.forEachIndexed { i, r ->
                val rule = obj(r)
                if (rule?.containsKey("components") == true || obj(rule?.get("apply"))?.containsKey("components") == true) {
                    out += "rules[$i].components $ignored"
                }
            }
            return out
        }
    }
}

/** Enum names in `components` and `display.components`: ignoring case, `-` and spaces, plus aliases. */
object ComponentNames {
    private fun norm(text: String): String = text.trim().replace('-', '_').replace(' ', '_').uppercase(Locale.ROOT)

    private val SLOT_ALIASES = mapOf("TIME" to StatusSlot.CLOCK, "WI_FI" to StatusSlot.WIFI, "NETWORK" to StatusSlot.WIFI, "PROFILE" to StatusSlot.AVATAR)
    private val BATTERY_ALIASES = mapOf(
        "ICON" to BatteryDisplayMode.CAPSULE_ICON, "CAPSULE" to BatteryDisplayMode.CAPSULE_ICON, "GAUGE" to BatteryDisplayMode.CAPSULE_ICON,
        "PERCENT" to BatteryDisplayMode.PERCENTAGE, "TIME" to BatteryDisplayMode.TIME_REMAINING, "TIME_TO_FULL" to BatteryDisplayMode.TIME_REMAINING,
    )
    private val TELEMETRY_ALIASES = mapOf(
        "NONE" to TelemetryMode.OFF, "PILL" to TelemetryMode.COMPACT_PILL, "COMPACT" to TelemetryMode.COMPACT_PILL,
        "CARD" to TelemetryMode.RICH_CARD, "RICH" to TelemetryMode.RICH_CARD,
    )
    private val MINI_ALIASES = mapOf("CARD" to MiniPlayerMode.FLOATING_CARD, "TICKER" to MiniPlayerMode.STATUS_TICKER, "NONE" to MiniPlayerMode.OFF)
    private val GLYPH_ALIASES = mapOf(
        "PS" to GlyphOverrideMode.PLAYSTATION, "SWITCH" to GlyphOverrideMode.NINTENDO,
        "MONOCHROME" to GlyphOverrideMode.MONOCHROME_MINIMAL, "MINIMAL" to GlyphOverrideMode.MONOCHROME_MINIMAL,
    )
    private val TOP_LEFT_ALIASES = mapOf("NONE" to TopLeftClusterMode.EMPTY, "SOCIAL" to TopLeftClusterMode.SOCIAL_STACK, "FRIENDS" to TopLeftClusterMode.SOCIAL_STACK)
    private val PILL_ALIASES = mapOf("PLATFORM" to BadgePill.SYSTEM, "RELEASE_YEAR" to BadgePill.YEAR, "RA" to BadgePill.RA_PROGRESS, "ACHIEVEMENTS" to BadgePill.RA_PROGRESS)

    private inline fun <reified E : Enum<E>> pick(text: String?, aliases: Map<String, E>): E? {
        val t = norm(text ?: return null)
        if (t.isEmpty()) return null
        return enumValues<E>().firstOrNull { it.name == t } ?: aliases[t]
    }

    fun slot(text: String?): StatusSlot? = pick(text, SLOT_ALIASES)
    fun battery(text: String?): BatteryDisplayMode? = pick(text, BATTERY_ALIASES)
    fun telemetry(text: String?): TelemetryMode? = pick(text, TELEMETRY_ALIASES)
    fun miniPlayer(text: String?): MiniPlayerMode? = pick(text, MINI_ALIASES)
    fun glyphs(text: String?): GlyphOverrideMode? = pick(text, GLYPH_ALIASES)
    fun topLeft(text: String?): TopLeftClusterMode? = pick(text, TOP_LEFT_ALIASES)
    fun pill(text: String?): BadgePill? = pick(text, PILL_ALIASES)
}

/**
 * The status bar's clock (2b.7a, `components.clockFormat`): a `DateFormat` skeleton limited to the
 * fields `y M d E H h m a`. Its date letters (`y M d E`) give the date in the locale's best
 * pattern, its time letters the time: `H` 24 h, `h` 12 h, and `m` alone the hour as the system's
 * 12/24 h setting has it. Date and time are drawn two spaces apart, like the clock before 2b.7.
 * Empty is that clock: the `Md` date and the system's time.
 */
object ClockFormat {
    const val FIELDS = "yMdEHhma"

    /** [skeleton] with every letter outside [FIELDS] (and anything that isn't a letter) dropped, and what was dropped. */
    fun filter(skeleton: String): Pair<String, String> {
        val kept = StringBuilder()
        val dropped = StringBuilder()
        for (c in skeleton.trim()) if (c in FIELDS) kept.append(c) else if (!c.isWhitespace()) dropped.append(c)
        return kept.toString() to dropped.toString()
    }

    /** The skeleton [filter] keeps, reporting what it drops at [where]. */
    fun check(where: String, skeleton: String, problems: MutableList<String>): String {
        val (kept, dropped) = filter(skeleton)
        if (dropped.isNotEmpty()) problems += "$where.components.clockFormat: dropped \"$dropped\" (use the letters y M d E H h m a)"
        return kept
    }

    /** The date letters of [skeleton] (`y M d E`). */
    fun dateFields(skeleton: String): String = skeleton.filter { it in "yMdE" }

    /** The time letters of [skeleton] (`H h m a`). */
    fun timeFields(skeleton: String): String = skeleton.filter { it in "Hhma" }

    /** Presets for Settings ▸ Theme ▸ Components ▸ Clock format: (skeleton, label); null is the pack's. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "MdHm" to "24 h",
        "Mdhma" to "12 h",
        "EMMMdm" to "Date and time",
        "m" to "Time only",
    )

    /** The label of [skeleton]: a preset's name, "Default" for empty, else the skeleton itself. */
    fun label(skeleton: String): String = PRESETS.firstOrNull { it.first == skeleton }?.second ?: if (skeleton.isEmpty()) "Default" else skeleton
}

/**
 * One layer of `components`, checked: each value null where the layer leaves it alone. Stacks
 * like the rest of the cascade ([over]); [resolve] fills what no layer set.
 */
@Immutable
data class ComponentsPatch(
    val topLeft: TopLeftClusterMode? = null,
    val left: List<StatusSlot>? = null,
    val right: List<StatusSlot>? = null,
    val battery: BatteryDisplayMode? = null,
    /** A filtered [ClockFormat] skeleton; empty is the default clock. */
    val clock: String? = null,
    val telemetry: TelemetryMode? = null,
    val miniPlayer: MiniPlayerMode? = null,
    val glyphs: GlyphOverrideMode? = null,
    val pills: List<BadgePill>? = null,
    /** Pack-relative path of the logo; empty: none. */
    val logo: String? = null,
    val volumeAlways: Boolean? = null,
    val tileGlow: Boolean? = null,
) {
    /** These values with every one [patch] sets replacing them. */
    fun over(patch: ComponentsPatch?): ComponentsPatch = if (patch == null) this else ComponentsPatch(
        topLeft = patch.topLeft ?: topLeft,
        left = patch.left ?: left,
        right = patch.right ?: right,
        battery = patch.battery ?: battery,
        clock = patch.clock ?: clock,
        telemetry = patch.telemetry ?: telemetry,
        miniPlayer = patch.miniPlayer ?: miniPlayer,
        glyphs = patch.glyphs ?: glyphs,
        pills = patch.pills ?: pills,
        logo = patch.logo ?: logo,
        volumeAlways = patch.volumeAlways ?: volumeAlways,
        tileGlow = patch.tileGlow ?: tileGlow,
    )

    val empty: Boolean get() = this == NONE

    /** Every value set, defaults for the rest, and the slots placed by [ResolvedComponents.place]. */
    fun resolve(): ResolvedComponents {
        val (l, r) = ResolvedComponents.place(left ?: emptyList(), right ?: ResolvedComponents.DEFAULT.right)
        return ResolvedComponents(
            topLeft = topLeft ?: ResolvedComponents.DEFAULT.topLeft,
            left = l,
            right = r,
            battery = battery ?: ResolvedComponents.DEFAULT.battery,
            clock = clock.orEmpty(),
            telemetry = telemetry ?: ResolvedComponents.DEFAULT.telemetry,
            miniPlayer = miniPlayer ?: ResolvedComponents.DEFAULT.miniPlayer,
            glyphs = glyphs ?: ResolvedComponents.DEFAULT.glyphs,
            pills = pills ?: emptyList(),
            logo = logo.orEmpty(),
            volumeAlways = volumeAlways ?: false,
            tileGlow = tileGlow ?: true,
        )
    }

    companion object {
        val NONE = ComponentsPatch()

        /** A pack's `components` at [where] (`theme`, `variants.<id>`), checked; null when it sets nothing usable. */
        fun of(where: String, c: Components?, problems: MutableList<String>): ComponentsPatch? {
            c ?: return null
            fun <E : Enum<E>> one(key: String, text: String?, parse: (String?) -> E?, values: Array<E>): E? {
                if (text.isNullOrBlank()) return null
                return parse(text) ?: run {
                    problems += "$where.components.$key: ${text.trim()} isn't one of ${values.joinToString { it.name }}"
                    null
                }
            }
            fun slots(key: String, list: List<String>?): List<StatusSlot>? {
                list ?: return null
                val out = ArrayList<StatusSlot>()
                for (t in list) {
                    val slot = ComponentNames.slot(t)
                    when {
                        slot == null -> problems += "$where.components.$key: $t isn't one of ${StatusSlot.entries.joinToString { it.name }}"
                        slot in out -> problems += "$where.components.$key: ${slot.name} is listed twice"
                        else -> out += slot
                    }
                }
                return out
            }
            val left = slots("statusBarLeft", c.statusBarLeft)
            val right = slots("statusBarRight", c.statusBarRight)
            if (left != null && right != null) {
                for (slot in left.filter { it in right && it != StatusSlot.AVATAR }) problems += "$where.components: ${slot.name} is in both clusters (kept on the left)"
            }
            if (left?.contains(StatusSlot.AVATAR) == true) problems += "$where.components.statusBarLeft: AVATAR is always the rightmost slot of statusBarRight (moved there)"
            if (right != null && StatusSlot.AVATAR in right && right.last() != StatusSlot.AVATAR) {
                problems += "$where.components.statusBarRight: AVATAR is always the rightmost slot (moved there)"
            }
            val pills = c.badgePills?.let { list ->
                val out = ArrayList<BadgePill>()
                for (t in list) {
                    val p = ComponentNames.pill(t)
                    when {
                        p == null -> problems += "$where.components.badgePills: $t isn't one of ${BadgePill.entries.joinToString { it.name }}"
                        p in out -> problems += "$where.components.badgePills: ${p.name} is listed twice"
                        else -> out += p
                    }
                }
                out
            }
            val logo = c.logo?.trim()?.replace('\\', '/')?.let { path ->
                val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                when {
                    path.isEmpty() || path.equals("none", ignoreCase = true) -> ""
                    ext !in LOGO_TYPES || path.startsWith("/") || path.split('/').any { it == ".." } -> {
                        problems += "$where.components.logo: use an SVG, PNG or WebP inside the pack (not $path)"
                        null
                    }
                    else -> path
                }
            }
            val patch = ComponentsPatch(
                topLeft = one("topLeftCluster", c.topLeftCluster, ComponentNames::topLeft, TopLeftClusterMode.entries.toTypedArray()),
                left = left,
                right = right,
                battery = one("batteryMode", c.batteryMode, ComponentNames::battery, BatteryDisplayMode.entries.toTypedArray()),
                clock = c.clockFormat?.let { ClockFormat.check(where, it, problems) },
                telemetry = one("telemetryMode", c.telemetryMode, ComponentNames::telemetry, TelemetryMode.entries.toTypedArray()),
                miniPlayer = one("miniPlayerMode", c.miniPlayerMode, ComponentNames::miniPlayer, MiniPlayerMode.entries.toTypedArray()),
                glyphs = one("glyphOverride", c.glyphOverride, ComponentNames::glyphs, GlyphOverrideMode.entries.toTypedArray()),
                pills = pills,
                logo = logo,
                volumeAlways = c.volumeAlways,
                tileGlow = c.tileGlow?.let { v ->
                    val p = v as? kotlinx.serialization.json.JsonPrimitive
                    val b = if (p == null || p.isString) null else p.content.toBooleanStrictOrNull()
                    if (b == null && v !is kotlinx.serialization.json.JsonNull) problems += "$where.components.tileGlow: $v isn't true or false"
                    b
                },
            )
            return patch.takeUnless { it.empty }
        }

        val LOGO_TYPES = setOf("svg", "png", "webp")
    }
}

/**
 * The components the menu draws, every value settled (Default pack, theme pack, variant, the
 * user's `display.components`). [left] and [right] hold each slot at most once, and `AVATAR`
 * only as the last of [right].
 */
@Immutable
data class ResolvedComponents(
    val topLeft: TopLeftClusterMode = TopLeftClusterMode.EMPTY,
    val left: List<StatusSlot> = emptyList(),
    val right: List<StatusSlot> = listOf(StatusSlot.CLOCK, StatusSlot.BATTERY, StatusSlot.AVATAR),
    val battery: BatteryDisplayMode = BatteryDisplayMode.BOTH,
    /** [ClockFormat] skeleton; empty is the default clock. */
    val clock: String = "",
    val telemetry: TelemetryMode = TelemetryMode.COMPACT_PILL,
    val miniPlayer: MiniPlayerMode = MiniPlayerMode.FLOATING_CARD,
    val glyphs: GlyphOverrideMode = GlyphOverrideMode.AUTO,
    val pills: List<BadgePill> = emptyList(),
    /** Pack-relative path of the logo ([LoadedTheme.logos]); empty: none. */
    val logo: String = "",
    val volumeAlways: Boolean = false,
    /** The focused game tile gets the focus frame. */
    val tileGlow: Boolean = true,
) {
    /** The avatar (and its mini-HUD) is in the bar. */
    val avatar: Boolean get() = StatusSlot.AVATAR in right

    /** Slots in either cluster. */
    fun shows(slot: StatusSlot): Boolean = slot in left || slot in right

    /** The right cluster without the avatar: what fades while the HUD expands. */
    val rightOfText: List<StatusSlot> get() = right.filter { it != StatusSlot.AVATAR }

    /** What the status bar's monitors have to watch for this bar. */
    val needs: StatusNeeds
        get() = StatusNeeds(
            clock = clock,
            network = shows(StatusSlot.WIFI),
            volume = shows(StatusSlot.VOLUME),
            chargeTime = shows(StatusSlot.BATTERY) && battery == BatteryDisplayMode.TIME_REMAINING,
        )

    companion object {
        /** Today's bar: right [CLOCK, BATTERY, AVATAR], battery BOTH. */
        val DEFAULT = ResolvedComponents()

        /**
         * [left] and [right] with each slot once (the left keeps a slot listed in both) and
         * `AVATAR` moved to the end of the right cluster wherever it was listed.
         */
        fun place(left: List<StatusSlot>, right: List<StatusSlot>): Pair<List<StatusSlot>, List<StatusSlot>> {
            val avatar = StatusSlot.AVATAR in left || StatusSlot.AVATAR in right
            val l = left.distinct().filter { it != StatusSlot.AVATAR }
            val r = right.distinct().filter { it != StatusSlot.AVATAR && it !in l }
            return l to (if (avatar) r + StatusSlot.AVATAR else r)
        }
    }
}

/** What [ResolvedComponents] asks of the status bar's sources: only what a slot shows is watched. */
@Immutable
data class StatusNeeds(
    val clock: String = "",
    val network: Boolean = false,
    val volume: Boolean = false,
    val chargeTime: Boolean = false,
)

/**
 * The user's Settings ▸ Theme ▸ Components picks (2b.7a), saved as `display.components` in
 * `settings.json`. Every field is null to follow the pack (its base and variant); a value set
 * here wins. Cleared by Reset to theme defaults; quick profiles leave it alone.
 *
 * ```json
 * "components": { "statusBarLeft": ["WIFI"], "statusBarRight": null, "batteryMode": "PERCENTAGE", "clockFormat": "MdHm" }
 * ```
 */
@Immutable
@Serializable
data class ComponentOverrides(
    val topLeftCluster: String? = null,
    val statusBarLeft: List<String>? = null,
    val statusBarRight: List<String>? = null,
    val batteryMode: String? = null,
    val clockFormat: String? = null,
    val telemetryMode: String? = null,
    val miniPlayerMode: String? = null,
    val glyphOverride: String? = null,
    val badgePills: List<String>? = null,
    val volumeAlways: Boolean? = null,
    val tileGlow: Boolean? = null,
) {
    /** The overrides as a cascade layer (values that don't parse are left to the pack). */
    val patch: ComponentsPatch
        get() = ComponentsPatch(
            topLeft = ComponentNames.topLeft(topLeftCluster),
            left = statusBarLeft?.mapNotNull(ComponentNames::slot)?.distinct(),
            right = statusBarRight?.mapNotNull(ComponentNames::slot)?.distinct(),
            battery = ComponentNames.battery(batteryMode),
            clock = clockFormat?.let { ClockFormat.filter(it).first },
            telemetry = ComponentNames.telemetry(telemetryMode),
            miniPlayer = ComponentNames.miniPlayer(miniPlayerMode),
            glyphs = ComponentNames.glyphs(glyphOverride),
            pills = badgePills?.mapNotNull(ComponentNames::pill)?.distinct(),
            volumeAlways = volumeAlways,
            tileGlow = tileGlow,
        )

    /** Canonical names, unknown values dropped (a hand-edited typo follows the pack), clock skeleton filtered. */
    fun normalized(): ComponentOverrides {
        val p = patch
        return ComponentOverrides(
            topLeftCluster = p.topLeft?.name,
            statusBarLeft = p.left?.map { it.name },
            statusBarRight = p.right?.map { it.name },
            batteryMode = p.battery?.name,
            clockFormat = p.clock,
            telemetryMode = p.telemetry?.name,
            miniPlayerMode = p.miniPlayer?.name,
            glyphOverride = p.glyphs?.name,
            badgePills = p.pills?.map { it.name },
            volumeAlways = volumeAlways,
            tileGlow = tileGlow,
        )
    }

    val empty: Boolean get() = this == NONE

    /** How many rows the user set (for Reset to theme defaults). */
    val count: Int
        get() = listOf(topLeftCluster, statusBarLeft, statusBarRight, batteryMode, clockFormat, telemetryMode, miniPlayerMode, glyphOverride, badgePills, volumeAlways, tileGlow)
            .count { it != null }

    /** A copy with [side]'s cluster set to [slots]; null follows the pack. */
    fun withSlots(side: StatusSide, slots: List<StatusSlot>?): ComponentOverrides {
        val names = slots?.distinct()?.map { it.name }
        return when (side) {
            StatusSide.LEFT -> copy(statusBarLeft = names)
            StatusSide.RIGHT -> copy(statusBarRight = names)
        }
    }

    /** The value of [row] (null: the pack's). */
    operator fun get(row: ComponentRow): String? = when (row) {
        ComponentRow.TOP_LEFT -> topLeftCluster
        ComponentRow.BATTERY -> batteryMode
        ComponentRow.CLOCK -> clockFormat
        ComponentRow.MINI_PLAYER -> miniPlayerMode
        ComponentRow.GLYPHS -> glyphOverride
        ComponentRow.TELEMETRY -> telemetryMode
        ComponentRow.TILE_GLOW -> tileGlow?.let(ComponentRow::onOff)
    }

    /** A copy with [row] set to [value] (normalized; null or unknown follows the pack). */
    fun with(row: ComponentRow, value: String?): ComponentOverrides = when (row) {
        ComponentRow.TOP_LEFT -> copy(topLeftCluster = ComponentNames.topLeft(value)?.name)
        ComponentRow.BATTERY -> copy(batteryMode = ComponentNames.battery(value)?.name)
        ComponentRow.CLOCK -> copy(clockFormat = value?.let { ClockFormat.filter(it).first })
        ComponentRow.MINI_PLAYER -> copy(miniPlayerMode = ComponentNames.miniPlayer(value)?.name)
        ComponentRow.GLYPHS -> copy(glyphOverride = ComponentNames.glyphs(value)?.name)
        ComponentRow.TELEMETRY -> copy(telemetryMode = ComponentNames.telemetry(value)?.name)
        ComponentRow.TILE_GLOW -> copy(tileGlow = ComponentRow.parseOnOff(value))
    }

    /** A copy with the title badges set to [pills] in that order (each once); null follows the pack, empty draws none. */
    fun withPills(pills: List<BadgePill>?): ComponentOverrides = copy(badgePills = pills?.distinct()?.map { it.name })

    companion object {
        val NONE = ComponentOverrides()
    }
}

/** A one-value Settings ▸ Theme ▸ Components row, with its choices in row order: (value, label). */
enum class ComponentRow(val title: String, val choices: List<Pair<String, String>>) {
    /** `topLeftCluster` (2b.7c). */
    TOP_LEFT(
        "Top-left",
        listOf(TopLeftClusterMode.SOCIAL_STACK.name to "Social stack", TopLeftClusterMode.EMPTY.name to "Empty"),
    ),
    BATTERY(
        "Battery display",
        listOf(
            BatteryDisplayMode.CAPSULE_ICON.name to "Icon", BatteryDisplayMode.PERCENTAGE.name to "Percentage",
            BatteryDisplayMode.BOTH.name to "Icon and percentage", BatteryDisplayMode.TIME_REMAINING.name to "Time to full",
        ),
    ),
    CLOCK("Clock format", ClockFormat.PRESETS),

    /** `miniPlayerMode` (2b.7b). */
    MINI_PLAYER(
        "Mini player",
        listOf(
            MiniPlayerMode.FLOATING_CARD.name to "Floating card", MiniPlayerMode.STATUS_TICKER.name to "Status bar ticker",
            MiniPlayerMode.OFF.name to "Off",
        ),
    ),

    /** `glyphOverride` (2b.7b). */
    GLYPHS(
        "Button glyphs",
        listOf(
            GlyphOverrideMode.AUTO.name to "Controller's own", GlyphOverrideMode.PLAYSTATION.name to "Shapes (✕ ○ △ □)",
            GlyphOverrideMode.XBOX.name to "Colored letters (A B X Y)", GlyphOverrideMode.NINTENDO.name to "Letters, A on the right",
            GlyphOverrideMode.MONOCHROME_MINIMAL.name to "Minimal outlines",
        ),
    ),

    /** `telemetryMode` (2b.7d). */
    TELEMETRY(
        "Achievement badge",
        listOf(TelemetryMode.OFF.name to "Off", TelemetryMode.COMPACT_PILL.name to "Compact", TelemetryMode.RICH_CARD.name to "Rich card"),
    ),

    /** `tileGlow` (0.2.1.0): the focus frame on game tiles. */
    TILE_GLOW("Game tile glow", listOf("ON" to "On", "OFF" to "Off")),
    ;

    /** The label of [value]; "Theme pack" for null. */
    fun label(value: String?): String = when {
        value == null -> "Theme pack"
        this == CLOCK -> ClockFormat.label(value)
        else -> choices.firstOrNull { it.first == value }?.second ?: value
    }

    /** What [c] (the pack's components) shows for this row, as a label. */
    fun packValue(c: ResolvedComponents): String = when (this) {
        TOP_LEFT -> c.topLeft.name
        BATTERY -> c.battery.name
        CLOCK -> c.clock
        MINI_PLAYER -> c.miniPlayer.name
        GLYPHS -> c.glyphs.name
        TELEMETRY -> c.telemetry.name
        TILE_GLOW -> onOff(c.tileGlow)
    }

    /** "Theme pack (Icon and percentage)". */
    fun packLabel(c: ResolvedComponents): String = "Theme pack (${label(packValue(c))})"

    /** The value after [current]: theme pack (null), then each choice in turn. */
    fun next(current: String?): String? {
        val ids: List<String?> = listOf<String?>(null) + choices.map { it.first }
        val at = ids.indexOf(current).coerceAtLeast(0)
        return ids[(at + 1) % ids.size]
    }

    companion object {
        fun onOff(on: Boolean): String = if (on) "ON" else "OFF"

        /** "ON" / "OFF" (also true/false), else null: the pack's. */
        fun parseOnOff(value: String?): Boolean? = when (value?.trim()?.uppercase(Locale.ROOT)) {
            "ON", "TRUE" -> true
            "OFF", "FALSE" -> false
            else -> null
        }
    }
}

/** Names of the title badges, for Settings. */
val BadgePill.label: String
    get() = when (this) {
        BadgePill.SYSTEM -> "System"
        BadgePill.YEAR -> "Release year"
        BadgePill.DEVELOPER -> "Developer"
        BadgePill.PLAYTIME -> "Playtime"
        BadgePill.RA_PROGRESS -> "Achievements"
    }

/** Short names of the slots, for Settings. */
val StatusSlot.label: String
    get() = when (this) {
        StatusSlot.LOGO -> "Logo"
        StatusSlot.WIFI -> "Network"
        StatusSlot.VOLUME -> "Volume"
        StatusSlot.BATTERY -> "Battery"
        StatusSlot.CLOCK -> "Clock"
        StatusSlot.AVATAR -> "Avatar"
    }
