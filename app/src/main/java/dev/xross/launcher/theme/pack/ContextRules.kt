package dev.xross.launcher.theme.pack

import androidx.compose.runtime.Immutable
import dev.xross.launcher.state.Categories
import dev.xross.launcher.theme.BatteryLevel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

/**
 * One entry of `rules` (Roadmap §4.3 "Context rules", docs/THEME_RICING_AND_WEB_STUDIO.md §3.4):
 * while every selector in [match] holds, [apply] patches the look. A pack ships them inline in
 * `theme.json` or in a `rules.json` it lists in `imports`; imported rules come first, then the
 * manifest's own, and later rules win over earlier ones.
 *
 * ```json
 * { "rules": [
 *   { "match": { "category": "MUSIC" }, "apply": { "cardAspectRatio": "1:1" } },
 *   { "match": { "system": "dc" }, "apply": { "palette": { "accent": "#FF8C42" } } },
 *   { "match": { "battery": "<= 20" }, "apply": { "palette": { "accent": "#FF3344" } } },
 *   { "match": { "time": "21:00..06:00" }, "apply": { "palette": { "canvas": "#05050A" } } }
 * ] }
 * ```
 */
@Serializable
data class ContextRule(
    val match: RuleMatcher = RuleMatcher(),
    val apply: RuleApplication = RuleApplication(),
)

/** Selectors of a [ContextRule]; all that are set must hold. A rule with none always applies. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RuleMatcher(
    /** A category id: `GAMES`, `MUSIC`, `PHOTO`, `VIDEO`, `APPS`, `SETTINGS` (any case). */
    val category: String? = null,
    /** A Xross platform id (`dreamcast`) or one of its folder aliases (`dc`). */
    val system: String? = null,
    /** `<= 20`, `< 15`, `>= 80`, `== 100`, `charging`, `discharging` or `full`. */
    @JsonNames("battery.level", "batteryLevel") val battery: String? = null,
    /** `HH:MM..HH:MM` local time, start included, end excluded; wraps past midnight (`21:00..06:00`). */
    val time: String? = null,
)

/** What a [ContextRule] changes: documented keys only (Roadmap §4.3), never arbitrary JSON paths. */
@Serializable
data class RuleApplication(
    /** Palette roles, and the `circadianCurve` / `batteryTint` switches. */
    val palette: ThemeManifest.Palette? = null,
    /**
     * `surfaceStyle`, `adaptiveGamePalette`, `shaderPreset`, `shaderPath`, `wallpaperPath`,
     * `focusFrameStyle`, `iconMask`, `iconFilter` and `foregroundParticles`.
     */
    val effects: ThemeManifest.Effects? = null,
    /** Tile aspect while the rule holds ("1:1"); with a `system` selector, that system's game tiles. */
    val cardAspectRatio: String? = null,
    /** Any `layout` key ([LayoutSpec]) over the pack's layout. */
    val layout: LayoutSpec? = null,
    /** Only `soundGains` (2b.5a) and `bgmFile` (2b.5c): sound levels and music while the rule holds. */
    val audio: ThemeManifest.Audio? = null,
)

/** Where the launcher is right now, for [ResolvedRule.matches]. Unknown values never match. */
data class RuleContext(
    /** Open category id, lower case ("games"). */
    val category: String? = null,
    /** Platform id of the focused game. */
    val system: String? = null,
    val battery: BatteryLevel? = null,
    /** Local minutes since midnight, 0–1439. */
    val minuteOfDay: Int? = null,
)

/** A parsed `battery` selector. */
@Immutable
sealed interface BatteryCondition {
    fun holds(b: BatteryLevel): Boolean

    data class Level(val op: Op, val percent: Int) : BatteryCondition {
        override fun holds(b: BatteryLevel): Boolean = when (op) {
            Op.LE -> b.percent <= percent
            Op.LT -> b.percent < percent
            Op.GE -> b.percent >= percent
            Op.GT -> b.percent > percent
            Op.EQ -> b.percent == percent
        }
    }

    data object Charging : BatteryCondition {
        override fun holds(b: BatteryLevel) = b.charging
    }

    data object Discharging : BatteryCondition {
        override fun holds(b: BatteryLevel) = !b.charging
    }

    data object Full : BatteryCondition {
        override fun holds(b: BatteryLevel) = b.percent >= 100
    }

    enum class Op { LE, LT, GE, GT, EQ }

    companion object {
        private val LEVEL = Regex("""^(<=|>=|==|=|<|>)?\s*(\d{1,3})\s*%?$""")

        /** `<= 20`, `<20`, `20%` (equal), `charging`, `discharging`, `full`; null when unreadable. */
        fun parse(text: String): BatteryCondition? {
            val t = text.trim().lowercase(Locale.ROOT)
            when (t) {
                "charging", "plugged" -> return Charging
                "discharging", "!charging", "unplugged", "on battery" -> return Discharging
                "full" -> return Full
            }
            val m = LEVEL.matchEntire(t) ?: return null
            val percent = m.groupValues[2].toInt().takeIf { it in 0..100 } ?: return null
            val op = when (m.groupValues[1]) {
                "<=" -> Op.LE
                "<" -> Op.LT
                ">=" -> Op.GE
                ">" -> Op.GT
                else -> Op.EQ
            }
            return Level(op, percent)
        }
    }
}

/**
 * A `time` selector: minutes since midnight, [start] included, [end] excluded. When [end] is not
 * after [start] the range wraps past midnight; equal ends mean the whole day.
 */
@Immutable
data class TimeRange(val start: Int, val end: Int) {
    fun contains(minuteOfDay: Int): Boolean {
        val m = ((minuteOfDay % DAY) + DAY) % DAY
        return when {
            start == end -> true
            start < end -> m in start until end
            else -> m >= start || m < end
        }
    }

    companion object {
        const val DAY = 24 * 60
        private val CLOCK = Regex("""^(\d{1,2})(?::(\d{2}))?$""")

        /** `21:00..06:00`, `6..11`, `06:30 .. 07:15`; null when unreadable. `24:00` is midnight. */
        fun parse(text: String): TimeRange? {
            val parts = text.split("..")
            if (parts.size != 2) return null
            val a = minute(parts[0]) ?: return null
            val b = minute(parts[1]) ?: return null
            return TimeRange(a, b)
        }

        private fun minute(text: String): Int? {
            val m = CLOCK.matchEntire(text.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (min > 59 || h > 24 || (h == 24 && min != 0)) return null
            return (h * 60 + min) % DAY
        }
    }
}

/** The `palette` switches and `effects.adaptiveGamePalette`, null where a layer leaves them alone. */
@Immutable
data class ColorFlags(
    val circadianCurve: Boolean? = null,
    val batteryTint: Boolean? = null,
    val adaptiveGamePalette: Boolean? = null,
) {
    /** These with every flag [o] sets replacing them. */
    fun over(o: ColorFlags?): ColorFlags = if (o == null) this else ColorFlags(
        circadianCurve = o.circadianCurve ?: circadianCurve,
        batteryTint = o.batteryTint ?: batteryTint,
        adaptiveGamePalette = o.adaptiveGamePalette ?: adaptiveGamePalette,
    )

    val empty: Boolean get() = this == NONE

    companion object {
        val NONE = ColorFlags()

        fun of(palette: ThemeManifest.Palette?, effects: ThemeManifest.Effects?) = ColorFlags(
            circadianCurve = palette?.circadianCurve,
            batteryTint = palette?.batteryTint,
            adaptiveGamePalette = effects?.adaptiveGamePalette,
        )
    }
}

/** A checked [ContextRule]: resolved selectors, a palette/surface patch and the layout parts. */
@Immutable
data class ResolvedRule(
    /** Position in the pack's rule list (imports first), for error messages and look caching. */
    val index: Int,
    val category: String? = null,
    val system: String? = null,
    val battery: BatteryCondition? = null,
    val time: TimeRange? = null,
    val patch: ThemePatch? = null,
    val aspect: Float? = null,
    val layout: LayoutSpec? = null,
    /** Its tile shape is part of the pack's layout already ([foldRuleAspects]). */
    val folded: Boolean = false,
) {
    /** Depends on the battery or the clock, so it is re-evaluated as they change. */
    val dynamic: Boolean get() = battery != null || time != null

    val changesLayout: Boolean get() = !folded && (aspect != null || layout != null)

    fun matches(ctx: RuleContext): Boolean =
        (system == null || system == ctx.system) && matchesExceptSystem(ctx)

    /**
     * Every selector but `system` holds. A tile shape from a rule with a `system` selector shapes
     * that system's tiles wherever they are listed, not only the focused one's.
     */
    fun matchesExceptSystem(ctx: RuleContext): Boolean =
        (category == null || category == ctx.category) &&
            (battery == null || (ctx.battery != null && battery.holds(ctx.battery))) &&
            (time == null || (ctx.minuteOfDay != null && time.contains(ctx.minuteOfDay)))
}

/** Category ids a rule may name. */
private val RULE_CATEGORIES: Set<String> by lazy { Categories.all.mapTo(HashSet()) { it.id } }

/** At most this many rules are read from a pack. */
const val MAX_RULES = 64

/**
 * [rules] checked into [ResolvedRule]s. Unknown categories and systems, unreadable battery and
 * time selectors, bad colors, unknown surfaces and bad layout values are reported in [problems];
 * a rule whose selector can't be read is dropped (it would otherwise apply everywhere).
 */
internal fun resolveRules(
    rules: List<ContextRule>,
    surfaceIds: Set<String>,
    baseLayout: ThemeLayout,
    problems: MutableList<String>,
    platformId: (String) -> String? = ::platformIdOf,
): List<ResolvedRule> {
    if (rules.size > MAX_RULES) problems += "rules: only the first $MAX_RULES rules are used"
    val out = ArrayList<ResolvedRule>()
    for ((i, rule) in rules.take(MAX_RULES).withIndex()) {
        val where = "rules[$i]"
        val m = rule.match
        val category = m.category?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (category != null && category !in RULE_CATEGORIES) {
            problems += "$where.match.category: no such category ${m.category}"
            continue
        }
        val system = m.system?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            platformId(s) ?: run {
                problems += "$where.match.system: no such system $s"
                return@let null
            }
        }
        if (m.system?.isNotBlank() == true && system == null) continue
        val battery = m.battery?.takeIf { it.isNotBlank() }?.let { b ->
            BatteryCondition.parse(b) ?: null.also { problems += "$where.match.battery: use <= 20, >= 80, charging or discharging (not $b)" }
        }
        if (m.battery?.isNotBlank() == true && battery == null) continue
        val time = m.time?.takeIf { it.isNotBlank() }?.let { t ->
            TimeRange.parse(t) ?: null.also { problems += "$where.match.time: use HH:MM..HH:MM (not $t)" }
        }
        if (m.time?.isNotBlank() == true && time == null) continue

        val a = rule.apply
        val patch = patchOf(where + ".apply", ThemeOverride(a.palette, a.effects, audio = a.audio), surfaceIds, problems)
        val aspect = a.cardAspectRatio?.let { text ->
            parseAspect(text)?.coerceIn(LayoutLimits.MIN_ASPECT, LayoutLimits.MAX_ASPECT)
                ?: null.also { problems += "$where.apply.cardAspectRatio: $text isn't an aspect ratio like 16:9" }
        }
        val layout = a.layout?.also { spec ->
            // Validate once at load; the same spec is applied over the pack's layout when it matches.
            val found = ArrayList<String>()
            resolveLayout(spec, emptyMap(), baseLayout, found)
            found.mapTo(problems) { "$where.apply.$it" }
        }
        if (patch == null && aspect == null && layout == null) {
            problems += "$where: \"apply\" changes nothing"
            continue
        }
        out += ResolvedRule(i, category, system, battery, time, patch, aspect, layout)
    }
    return out
}

/**
 * Tile shapes of rules that only name a category or a system (no battery or time, no `layout`)
 * never change at run time, so they go into the pack's layout once, above its overrides, like a
 * `categoryOverrides` / `systemOverrides` `cardAspectRatio`. Those rules come back [ResolvedRule.folded].
 */
internal fun foldRuleAspects(layout: ThemeLayout, rules: List<ResolvedRule>): Pair<ThemeLayout, List<ResolvedRule>> {
    var l = layout
    val out = rules.map { r ->
        val a = r.aspect
        if (a == null || r.dynamic || r.layout != null || (r.category != null && r.system != null)) return@map r
        l = when {
            r.system != null -> l.copy(systemAspects = l.systemAspects + (r.system to a))
            r.category != null -> l.copy(categoryAspects = l.categoryAspects + (r.category to a))
            else -> l.copy(cardAspect = a, categoryAspects = emptyMap())
        }
        r.copy(folded = true)
    }
    return l to out
}

/**
 * [base] with the tile shapes and `layout` patches of [rules] that hold in [ctx], in order, or
 * null when none of them changes the layout (so callers keep the pack's own instance).
 */
fun applyRuleLayout(base: ThemeLayout, rules: List<ResolvedRule>, ctx: RuleContext): ThemeLayout? {
    var out: ThemeLayout? = null
    for (r in rules) {
        if (!r.changesLayout || !r.matchesExceptSystem(ctx)) continue
        if (r.system != null && r.layout != null && r.system != ctx.system) {
            // A layout patch keyed to a system follows the focused game; its tile shape doesn't.
            r.aspect?.let { a -> out = (out ?: base).let { it.copy(systemAspects = it.systemAspects + (r.system to a)) } }
            continue
        }
        var l = out ?: base
        r.layout?.let { l = resolveLayout(it, emptyMap(), l, ArrayList()) }
        r.aspect?.let { a ->
            l = when {
                r.system != null -> l.copy(systemAspects = l.systemAspects + (r.system to a))
                ctx.category != null -> l.copy(categoryAspects = l.categoryAspects + (ctx.category to a))
                else -> l.copy(cardAspect = a)
            }
        }
        out = l
    }
    return out
}
