package dev.xross.launcher.ui

import android.content.res.AssetManager
import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.xross.launcher.BuildConfig
import dev.xross.launcher.theme.pack.UiFilter
import dev.xross.launcher.theme.pack.UiFilterRef
import kotlin.math.floor
import kotlin.math.max

/**
 * The experimental UI post-process (Theme 2.0, 2b.4f, `effects.uiFilter`): an AGSL
 * [RuntimeShader] applied as a [RenderEffect] to the menu's root layer. The GL background is a
 * separate surface under the window, so it is never filtered here (it has the GL-side
 * `effects.backgroundFilter`); this reaches the interface itself: text, icons, panels, art.
 *
 * - Android 13 (API 33) and later only ([UiFilterSupport]); off below, with no fallback.
 * - Built in: [UiFilter.SCANLINES_UI] (dark rows 1.5 dp apart, whole pixels) and [UiFilter.BLOOM]
 *   (a bright pass, blurred, added back). Both are static: the effect is built once per size, so
 *   the layer re-renders only when the interface under it changes and a resting screen draws no
 *   frames.
 * - [UiFilter.CUSTOM]: the pack's `postprocess.agsl` (or `effects.uiFilterPath`), which must
 *   declare `uniform shader content;`. Xross sets `uResolution` (float2, px), `uPxPerDp` and
 *   `uTime` (seconds) where the file declares them. Only a file that reads `uTime` animates, at
 *   most [ANIMATION_FPS], and only while the screen is in use ([UiFilterSupport.running]): it
 *   rests with the wave and the focus pulse.
 * - A compile error (or a missing `content`) turns the filter off with a toast, once per file.
 */
object UiFilterSupport {
    const val MIN_SDK = Build.VERSION_CODES.TIRAMISU

    /** Scanline pitch: 1.5 dp at native resolution, at least 2 px. */
    const val SCANLINE_DP = 1.5f

    /** Bloom: blur radius of the bright pass. */
    const val BLOOM_RADIUS_DP = 8f

    /** Bloom: luma where it starts, and how strong it is added back. */
    const val BLOOM_THRESHOLD = 0.62f
    const val BLOOM_GAIN = 0.55f

    /** A custom filter that reads `uTime` advances it at most this often. */
    const val ANIMATION_FPS = 30

    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= MIN_SDK

    /** The filter drawn for [ref] on [sdk]: [ref], or off where AGSL isn't available. */
    fun active(ref: UiFilterRef, sdk: Int = Build.VERSION.SDK_INT): UiFilterRef =
        if (ref.filter == UiFilter.NONE || !supported(sdk)) UiFilterRef.OFF else ref

    /** Whole pixels between scanlines at [density] (px per dp). */
    fun scanlinePitchPx(density: Float): Int = max(2, floor(SCANLINE_DP * density + 0.5f).toInt())

    private val COMMENTS = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")
    private val CONTENT = Regex("""\buniform\s+shader\s+content\s*;""")

    /** Which of Xross's optional uniforms an AGSL file declares, each with exactly the type Xross sets. */
    data class Uniforms(val resolution: Boolean, val time: Boolean, val pxPerDp: Boolean)

    /**
     * The optional uniforms [source] declares (comments ignored). Only an exact `uniform float2
     * uResolution;` (or `vec2` / `half2`) and `uniform float uTime;` / `uPxPerDp;` (or `half`)
     * count: setting a uniform a shader doesn't declare, or with the wrong size, aborts the process
     * on Android 13–17 instead of throwing, so Xross never guesses.
     */
    fun uniformsOf(source: String): Uniforms {
        val code = source.replace(COMMENTS, " ")
        fun has(types: String, name: String) = Regex("""\buniform\s+(?:$types)\s+$name\s*;""").containsMatchIn(code)
        return Uniforms(
            resolution = has("float2|vec2|half2", "uResolution"),
            time = has("float|half", "uTime"),
            pxPerDp = has("float|half", "uPxPerDp"),
        )
    }

    /** Why a custom [source] can't be a UI filter before it is even compiled; null when it may be. */
    fun precheck(source: String?): String? = when {
        source == null -> "the file can't be read"
        !CONTENT.containsMatchIn(source.replace(COMMENTS, " ")) -> "it needs \"uniform shader content;\" (the interface it filters)"
        else -> null
    }

    /** True when [filter] changes over time: only a custom one that declares `uTime`. */
    fun animates(filter: UiFilter, source: String?): Boolean = filter == UiFilter.CUSTOM && source != null && uniformsOf(source).time

    /** Advance time now: an animated filter, while the activity is started and the screen in use. */
    fun running(animates: Boolean, started: Boolean, idle: Boolean, stillMotion: Boolean): Boolean =
        animates && started && !idle && !stillMotion

    /** How the filter stands, for the debug log and the device tests. */
    enum class State { OFF, UNSUPPORTED, FAILED, STATIC, ANIMATING, RESTING }

    fun state(ref: UiFilterRef, sdk: Int, failed: Boolean, animates: Boolean, running: Boolean): State = when {
        ref.filter == UiFilter.NONE -> State.OFF
        !supported(sdk) -> State.UNSUPPORTED
        failed -> State.FAILED
        !animates -> State.STATIC
        running -> State.ANIMATING
        else -> State.RESTING
    }
}

/**
 * This modifier with the [UiFilterSupport] filter for [ref] as a layer, for the menu's root;
 * unchanged when it is off, unsupported or failed. [customSource] is the pack's AGSL for CUSTOM. [onError]
 * gets (key, toast text) once a filter can't be built.
 */
@Composable
fun Modifier.uiFilter(
    ref: UiFilterRef,
    customSource: String?,
    started: Boolean,
    idle: Boolean,
    stillMotion: Boolean,
    onError: (String, String) -> Unit,
): Modifier {
    val sdk = Build.VERSION.SDK_INT
    val active = UiFilterSupport.active(ref, sdk)
    // Scans the AGSL source (regex): once per filter, not on every recomposition of the menu.
    val animates = remember(active.filter, customSource) { UiFilterSupport.animates(active.filter, customSource) }
    val running = UiFilterSupport.running(animates, started, idle, stillMotion)
    val built: UiFilterBuild? = if (active.filter == UiFilter.NONE) {
        null
    } else {
        val assets = LocalContext.current.assets
        val density = LocalDensity.current.density
        remember(active, customSource, density) { buildUiFilter(active, customSource, assets, density) }
    }
    val failed = built is UiFilterBuild.Failed
    if (built is UiFilterBuild.Failed) {
        LaunchedEffect(built) { onError("uifilter:${active.filter}:${active.path}:${customSource?.hashCode()}", built.message) }
    }
    if (BuildConfig.PERF_LOG) {
        val state = UiFilterSupport.state(ref, sdk, failed, animates, running)
        LaunchedEffect(ref, state) {
            Log.i("XrossUiFilter", "filter=${ref.filter} state=$state sdk=$sdk" + (if (ref.path.isNotEmpty()) " path=${ref.path}" else ""))
        }
    }
    val effect = (built as? UiFilterBuild.Ready)?.effect ?: return this
    // Seconds of filter time; it only moves while running, so a resting screen keeps one effect.
    val time = remember(effect) { mutableFloatStateOf(0f) }
    if (running) {
        LaunchedEffect(effect) {
            val stepNs = 1_000_000_000L / UiFilterSupport.ANIMATION_FPS
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last == 0L) last = now
                    if (now - last >= stepNs) {
                        time.floatValue = (time.floatValue + (now - last) / 1e9f) % 3600f
                        last = now
                    }
                }
            }
        }
    }
    return this.graphicsLayer {
        // Read in the layer block only: a new time or size updates the layer, never recomposes.
        if (Build.VERSION.SDK_INT >= UiFilterSupport.MIN_SDK) renderEffect = effect.at(size.width, size.height, time.floatValue)
    }
}

private sealed interface UiFilterBuild {
    class Ready(val effect: UiFilterEffect) : UiFilterBuild
    class Failed(val message: String) : UiFilterBuild
}

private fun buildUiFilter(ref: UiFilterRef, customSource: String?, assets: AssetManager, density: Float): UiFilterBuild {
    if (Build.VERSION.SDK_INT < UiFilterSupport.MIN_SDK) return UiFilterBuild.Failed("UI filters need Android 13")
    val name = when (ref.filter) {
        UiFilter.CUSTOM -> ref.path
        else -> ref.filter.name
    }
    if (ref.filter == UiFilter.CUSTOM) {
        UiFilterSupport.precheck(customSource)?.let { return UiFilterBuild.Failed("UI filter $name: $it · filter off") }
    }
    return try {
        UiFilterBuild.Ready(UiFilterEffect(ref.filter, customSource, assets, density))
    } catch (e: RuntimeException) {
        // RuntimeShader reports AGSL compile errors as IllegalArgumentException with the compiler's text.
        val why = e.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(140) ?: "it doesn't compile"
        Log.e("XrossUiFilter", "UI filter $name failed: ${e.message}")
        UiFilterBuild.Failed("UI filter $name: $why · filter off")
    }
}

/**
 * One built filter: its shader(s) and the last [RenderEffect], rebuilt only when the size or
 * time it was built for changes (a [RenderEffect] snapshots its shader's uniforms).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class UiFilterEffect(
    private val filter: UiFilter,
    customSource: String?,
    assets: AssetManager,
    private val density: Float,
) {
    private val shader: RuntimeShader = RuntimeShader(
        when (filter) {
            UiFilter.SCANLINES_UI -> asset(assets, "scanlines_ui.agsl")
            UiFilter.BLOOM -> asset(assets, "bloom_bright.agsl")
            UiFilter.CUSTOM -> customSource ?: error("no source")
            UiFilter.NONE -> error("no filter")
        },
    )

    // A custom file may leave any of these out; only declared ones are ever set (see uniformsOf).
    private val declared = if (filter == UiFilter.CUSTOM) UiFilterSupport.uniformsOf(customSource.orEmpty()) else UiFilterSupport.Uniforms(false, false, false)
    private val hasResolution = declared.resolution
    private val hasTime = declared.time

    init {
        if (declared.pxPerDp) shader.setFloatUniform("uPxPerDp", density)
        when (filter) {
            UiFilter.SCANLINES_UI -> {
                shader.setFloatUniform("uPitch", UiFilterSupport.scanlinePitchPx(density).toFloat())
                shader.setFloatUniform("uStrength", 1f)
            }
            UiFilter.BLOOM -> {
                shader.setFloatUniform("uThreshold", UiFilterSupport.BLOOM_THRESHOLD)
                shader.setFloatUniform("uGain", UiFilterSupport.BLOOM_GAIN)
            }
            else -> Unit
        }
        // Built once here, so a broken custom file fails now (toast) rather than in the draw.
        build()
    }

    private var w = -1f
    private var h = -1f
    private var t = -1f
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun at(width: Float, height: Float, time: Float): androidx.compose.ui.graphics.RenderEffect {
        val c = cached
        if (c != null && width == w && height == h && (time == t || !hasTime)) return c
        w = width
        h = height
        t = time
        if (hasResolution) shader.setFloatUniform("uResolution", width, height)
        if (hasTime) shader.setFloatUniform("uTime", time)
        return build().asComposeRenderEffect().also { cached = it }
    }

    private fun build(): RenderEffect {
        val pass = RenderEffect.createRuntimeShaderEffect(shader, "content")
        if (filter != UiFilter.BLOOM) return pass
        val radius = UiFilterSupport.BLOOM_RADIUS_DP * density
        val glow = RenderEffect.createChainEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL), pass)
        return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 0f), glow, BlendMode.PLUS)
    }

    companion object {
        fun asset(assets: AssetManager, name: String): String =
            assets.open("${WaveRenderer.ASSET_DIR}/ui/$name").bufferedReader().use { it.readText() }
    }
}
