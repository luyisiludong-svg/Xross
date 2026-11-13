package dev.xross.launcher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.theme.pack.LoadedTheme
import dev.xross.launcher.theme.pack.PackSounds
import dev.xross.launcher.theme.pack.SoundRole
import dev.xross.launcher.theme.pack.SoundSource
import dev.xross.launcher.theme.pack.ThemeLook
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Short UI effects and the [SoundRole] each one plays (Phase 2b.5a audit in docs/PHASE_2B5_PROMPTS.md):
 *  - [MOVE] is a step through a list or an overlay (`cursor`); [CATEGORY] is Left / Right
 *    between categories (`category_switch`, falling back to `cursor`).
 *  - [ENTER] runs an item or confirms a choice (`confirm`); [DRILL_IN] / [DRILL_OUT] enter and
 *    leave a folder or sub-list (falling back to `confirm` / `back`). Closing an overlay is [BACK].
 *  - [ERROR] is every error and every action that isn't allowed (`system_ng`).
 */
enum class UiSound(val role: SoundRole) {
    MOVE(SoundRole.CURSOR),
    CATEGORY(SoundRole.CATEGORY_SWITCH),
    ENTER(SoundRole.CONFIRM),
    DRILL_IN(SoundRole.DRILL_IN),
    DRILL_OUT(SoundRole.DRILL_OUT),
    BACK(SoundRole.BACK),
    OPTION(SoundRole.OPTION),
    OK(SoundRole.OK),
    ERROR(SoundRole.SYSTEM_NG),
    TROPHY(SoundRole.TROPHY),
}

/** Long cues, streamed with MediaPlayer instead of decoded into SoundPool memory. */
enum class UiCue(val role: SoundRole) {
    /** Startup: `01. opening.wav`, with the fade-in on a cold start. */
    OPENING(SoundRole.OPENING),
    GAMEBOOT(SoundRole.GAMEBOOT),
}

/**
 * Decoded samples played with per-channel volume and a playback rate: [SoundPool] on the device,
 * a fake in tests.
 */
interface SamplePool {
    /** Loads [source]; the id [play] takes, or null when it can't be read. */
    fun load(source: SoundSource): Int?
    fun unload(id: Int)
    fun play(id: Int, left: Float, right: Float, rate: Float)
}

/** [SamplePool] over a SoundPool of six streams. */
private class SoundPoolSamples(private val context: Context, attributes: AudioAttributes) : SamplePool {
    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attributes).build()

    override fun load(source: SoundSource): Int? = runCatching {
        when (source) {
            is SoundSource.Asset -> context.assets.openFd(source.path).use { fd -> pool.load(fd, 1) }
            is SoundSource.Disk -> pool.load(source.file.absolutePath, 1)
        }
    }.onFailure { Log.w("UiSounds", "Can't load UI sound $source") }.getOrNull()

    override fun unload(id: Int) {
        pool.unload(id)
    }

    override fun play(id: Int, left: Float, right: Float, rate: Float) {
        pool.play(id, left, right, 1, 0, rate)
    }
}

/**
 * Low-latency UI sound effects from the active theme pack ([useTheme]). Short effects are
 * preloaded into a [SamplePool]; the cues are streamed. A role the pack has no file for stays
 * silent rather than failing.
 *
 * Effects pan with the crosshair ([setCrosshair], [pan]) and, with the pack's
 * `audio.cursorJitter`, the cursor and category-switch ticks vary their rate slightly ([rateFor]).
 */
class UiSounds(
    private val context: Context,
    pool: SamplePool? = null,
    /** Seeded once; read on every jittered play without allocating. */
    private val random: Random = Random(0x5EED),
) {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool: SamplePool = pool ?: SoundPoolSamples(context, attributes)

    /** Loaded sample id per source, so roles sharing a file share one sample. */
    private val ids = HashMap<SoundSource, Int>()
    @Volatile private var sources: Map<SoundRole, SoundSource> = emptyMap()
    @Volatile private var gains: Map<SoundRole, Float> = emptyMap()
    /** The look's gains ([useLook]) and the pack load they belong to. */
    @Volatile private var lookGains: Map<SoundRole, Float> = emptyMap()
    @Volatile private var lookOwner: Pair<String, Long>? = null
    @Volatile private var panAmount: Float = PackSounds.DEFAULT_PAN_AMOUNT
    @Volatile private var cursorJitter: Boolean = false
    /** The crosshair's X as a fraction of the screen width; 0.5 until the menu reports it. */
    @Volatile private var crosshair: Float = 0.5f
    private var cuePlayer: MediaPlayer? = null
    private val lock = Any()

    @Volatile var volume: Float = 0.8f
    @Volatile var enabled: Boolean = true

    /** The pack whose sounds play now; null before the first theme is applied. */
    @Volatile var themeId: String? = null
        private set
    private var revision = -1L

    /** Swaps every sound to [theme]'s (missing roles fall back to the Default pack's, see the loader). */
    fun useTheme(theme: LoadedTheme) = synchronized(lock) {
        panAmount = theme.panAmount
        cursorJitter = theme.cursorJitter
        if (themeId == theme.id && sources == theme.sounds && gains == theme.gains && revision == theme.revision) return
        val wanted = theme.sounds.filterKeys { !it.stream }.values.toSet()
        // A reload of the same pack (hot reload) may have rewritten files under the same names.
        val reloaded = themeId == theme.id && revision != theme.revision
        // Unload samples the new theme doesn't use (or all disk samples on a reload); load the rest.
        for ((source, id) in ids.toMap()) if (source !in wanted || (reloaded && source is SoundSource.Disk)) {
            pool.unload(id)
            ids.remove(source)
        }
        for (source in wanted) if (source !in ids) pool.load(source)?.let { ids[source] = it }
        sources = theme.sounds
        gains = theme.gains
        themeId = theme.id
        revision = theme.revision
    }

    /**
     * The gains of [theme]'s look where the menu is now ([ThemeLook.gains]: its variant, the open
     * category, the focused system, the rules that hold). Ignored once another pack, or a reload
     * of this one, is active, until the look for that one arrives.
     */
    fun useLook(theme: LoadedTheme, look: ThemeLook) {
        lookGains = look.gains
        lookOwner = theme.id to theme.revision
    }

    /** The menu's crosshair X over the screen width, 0–1 ([dev.xross.launcher.theme.pack.Crosshair.clampX]). */
    fun setCrosshair(fraction: Float) {
        if (fraction.isFinite()) crosshair = fraction.coerceIn(0f, 1f)
    }

    /** The source [sound] plays, for tests and Settings. */
    fun sourceOf(sound: UiSound): SoundSource? = sources[sound.role]

    /** [role]'s gain now: the look's, else the pack's base gain. */
    fun gainOf(role: SoundRole): Float {
        val owner = lookOwner
        val fromLook = if (owner != null && owner.first == themeId && owner.second == revision) lookGains[role] else null
        return fromLook ?: gains[role] ?: 1f
    }

    /**
     * Stereo position −1 (left) … 1 (right) for an effect now: the crosshair's X across the
     * screen times the pack's `audio.panAmount`, plus [CATEGORY_NUDGE] toward [travel] (−1 left,
     * 1 right, 0 none) for a category switch. 0 when the pack turns panning off.
     */
    fun pan(travel: Int = 0): Float {
        val amount = panAmount
        if (amount <= 0f) return 0f
        return ((crosshair * 2f - 1f) * amount + travel.sign * CATEGORY_NUDGE).coerceIn(-1f, 1f)
    }

    /** Playback rate for [sound]: 0.98–1.02 for the cursor and category ticks with `cursorJitter`, else 1. */
    internal fun rateFor(sound: UiSound): Float =
        if (cursorJitter && (sound.role == SoundRole.CURSOR || sound.role == SoundRole.CATEGORY_SWITCH)) {
            1f - JITTER + random.nextFloat() * 2f * JITTER
        } else {
            1f
        }

    fun play(sound: UiSound) = play(sound, pan())

    /** Tests: every effect asked for, with its pan, before volume or a missing file can drop it. */
    @androidx.annotation.VisibleForTesting
    internal var requested: ((UiSound, Float) -> Unit)? = null

    /** [sound] at stereo position [pan] (−1…1, see [pan], [panLeft] and [panRight]). */
    fun play(sound: UiSound, pan: Float) {
        requested?.invoke(sound, pan)
        if (!enabled || volume <= 0f) return
        val source = sources[sound.role] ?: return
        val id = ids[source] ?: return
        val gain = gainOf(sound.role)
        val v = (volume * gain).coerceIn(0f, 1f)
        if (v <= 0f) return
        val rate = rateFor(sound)
        val p = if (pan.isFinite()) pan.coerceIn(-1f, 1f) else 0f
        val left = (v * panLeft(p)).coerceAtMost(1f)
        val right = (v * panRight(p)).coerceAtMost(1f)
        pool.play(id, left, right, rate)
        if (BuildConfig.PERF_LOG) {
            Log.i(LOG_TAG, "role=${sound.role.key} file=${source.fileName} gain=%.2f vol=%.2f pan=%.2f rate=%.3f".format(Locale.ROOT, gain, v, p, rate))
        }
    }

    fun playCue(cue: UiCue, cueVolume: Float = volume) {
        stopCue()
        if (cueVolume <= 0f) return
        val source = sources[cue.role] ?: return
        val v = (cueVolume * gainOf(cue.role)).coerceIn(0f, 1f)
        cuePlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                when (source) {
                    is SoundSource.Asset -> context.assets.openFd(source.path).use { fd ->
                        setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    is SoundSource.Disk -> setDataSource(source.file.absolutePath)
                }
                setVolume(v, v)
                setOnCompletionListener { player -> player.release(); if (cuePlayer === player) cuePlayer = null }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Can't play cue ${cue.name}", it) }.getOrNull()
    }

    fun stopCue() {
        cuePlayer?.runCatching { stop(); release() }
        cuePlayer = null
    }

    companion object {
        private const val TAG = "UiSounds"
        private const val LOG_TAG = "XrossSound"

        /** How far a category switch leans toward its direction of travel. */
        const val CATEGORY_NUDGE = 0.15f

        /** Half the jitter range: rates 0.98–1.02. */
        const val JITTER = 0.02f

        /**
         * Equal-power pan law, scaled so the centre plays both channels at full level (today's
         * level): `l = √2·cos((pan+1)π/4)`, `r = √2·sin((pan+1)π/4)`. Off-centre, the near side
         * rises above 1 and is capped there by [play], so a hard pan is never louder than full scale.
         */
        fun panLeft(pan: Float): Float = SQRT2 * cos((pan + 1f) * PI_4)
        fun panRight(pan: Float): Float = SQRT2 * sin((pan + 1f) * PI_4)

        private val SQRT2 = sqrt(2f)
        private const val PI_4 = (Math.PI / 4).toFloat()
    }
}
