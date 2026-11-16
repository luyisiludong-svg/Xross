package dev.xross.launcher.state

import dev.xross.launcher.data.XrossSettings
import dev.xross.launcher.data.of
import dev.xross.launcher.theme.BatteryLevel
import dev.xross.launcher.theme.LookInputs
import dev.xross.launcher.theme.QuickProfile
import dev.xross.launcher.theme.millisUntilNextMinute
import dev.xross.launcher.theme.minuteOfDay
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.UserLook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalTime

/** Where the menu is, for the look: the open category and the focused game's system. */
data class LookFocus(val category: String? = null, val system: String? = null)

/**
 * The inputs of the theme cascade ([LookInputs]) as a flow, settled the way the menu shows them
 * (Architecture Assessment §7.4): the focused system after its rest delay, and the battery and
 * the clock only when the pack has rules on them, passed on only when the set of rules that hold
 * changes (not on every percent or minute). Pure flows, so the whole path from settings and focus
 * to [dev.xross.launcher.theme.ResolvedLook] is tested without Compose; [ThemeController]
 * resolves it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object LookFlow {
    /** How long the cursor rests on a game before its system's theme override applies. */
    const val SYSTEM_LOOK_DELAY_MS = 250L

    /**
     * [focus] with a new system inside the same category held back [delayMs], so scrolling a list
     * that mixes systems doesn't flash between their overrides. A new category takes its system
     * at once, so the old system's override never shows in the new category.
     */
    fun settle(focus: Flow<LookFocus>, delayMs: Long = SYSTEM_LOOK_DELAY_MS): Flow<LookFocus> = channelFlow {
        var shown: LookFocus? = null
        focus.distinctUntilChanged().collectLatest { f ->
            val s = shown
            if (s != null && s.category == f.category && s.system != f.system) delay(delayMs)
            shown = f
            send(f)
        }
    }.distinctUntilChanged()

    /** The indices of [pack]'s rules that hold at battery [b]; empty for none or no reading. */
    fun batteryRulesHeld(pack: LoadedTheme, b: BatteryLevel?): List<Int> =
        if (b == null) emptyList() else pack.rules.filter { r -> r.battery?.holds(b) == true }.map { it.index }

    /** The indices of [pack]'s rules that hold at [minute]. */
    fun clockRulesHeld(pack: LoadedTheme, minute: Int): List<Int> =
        pack.rules.filter { r -> r.time?.contains(minute) == true }.map { it.index }

    /** [battery] for [pack]'s rules: null when it has none on the battery, and only when the rules that hold change. */
    fun ruleBattery(pack: LoadedTheme, battery: Flow<BatteryLevel?>): Flow<BatteryLevel?> =
        if (!pack.usesBattery) flowOf(null)
        else battery.distinctUntilChanged { a, b -> batteryRulesHeld(pack, a) == batteryRulesHeld(pack, b) }

    /**
     * The minute of the day for [pack]'s rules, checked once a minute with a plain delay: null when
     * it has no rule on the time, and a new value only when the rules that hold change.
     */
    fun ruleMinute(pack: LoadedTheme, clock: () -> LocalTime = LocalTime::now): Flow<Int?> =
        if (!pack.usesClock) flowOf(null)
        else flow {
            while (true) {
                val now = clock()
                emit(minuteOfDay(now))
                delay(millisUntilNextMinute(now))
            }
        }.distinctUntilChanged { a, b -> clockRulesHeld(pack, a) == clockRulesHeld(pack, b) }

    /** Everything the cascade reads, from the settings, the active pack, the menu's [focus] and the battery. */
    fun inputs(
        pack: Flow<LoadedTheme>,
        settings: Flow<XrossSettings>,
        focus: Flow<LookFocus>,
        battery: Flow<BatteryLevel?>,
        clock: () -> LocalTime = LocalTime::now,
    ): Flow<LookInputs> {
        // Battery and clock restart with each pack: whether they are read at all depends on its rules.
        val context = pack.flatMapLatest { p ->
            combine(ruleBattery(p, battery), ruleMinute(p, clock)) { b, m -> PackContext(p, b, m) }
        }
        return combine(context, settings, settle(focus)) { c, st, f -> of(c.pack, st, f, c.battery, c.minute) }
            .distinctUntilChanged()
    }

    /** [LookInputs] for one moment (also the first value, before any flow has run). */
    fun of(pack: LoadedTheme, st: XrossSettings, focus: LookFocus, battery: BatteryLevel?, minute: Int?): LookInputs = LookInputs(
        pack = pack,
        variant = st.themeVariant,
        category = focus.category,
        system = focus.system,
        battery = battery?.takeIf { pack.usesBattery },
        minuteOfDay = minute?.takeIf { pack.usesClock },
        user = UserLook.of(st),
        profile = QuickProfile.byName(st.quickProfile),
    )

    private data class PackContext(val pack: LoadedTheme, val battery: BatteryLevel?, val minute: Int?)
}
