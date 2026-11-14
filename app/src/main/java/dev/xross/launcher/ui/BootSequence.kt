package dev.xross.launcher.ui

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.xross.launcher.theme.pack.BootIntro
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** What a cold start actually shows (2b.5d), after [BootSequence.resolve]. */
enum class BootMode {
    /** The pack's boot video, full-screen over the menu, with its own sound instead of the `opening` cue. */
    VIDEO,

    /** The background shader ramped up from black (`uTransition` 0 → 1) under the `opening` cue. */
    SHADER,

    /** The menu's fade-in under the `opening` cue, as before 2b.5d. */
    FADE,
}

/** Everything the cold-start intro depends on. */
data class BootInputs(
    /** The first screen after the process started (a warm start shows nothing). */
    val coldBoot: Boolean = true,
    /** The look's `effects.bootIntro` with the user's Settings ▸ Theme ▸ Boot intro over it (null: unset). */
    val intro: BootIntro? = null,
    /** [intro] is the user's choice, not the pack's: a chosen shader intro plays even when the pack has a video. */
    val userChoice: Boolean = false,
    /** The pack's boot video, if it has one ([dev.xross.launcher.theme.pack.LoadedTheme.bootVideo]). */
    val video: File? = null,
    /** A still-motion quick profile (Monochrome E-Ink) is on. */
    val stillMotion: Boolean = false,
    /** Settings ▸ Sound ▸ Startup sound. */
    val startupSound: Boolean = true,
    /** Settings ▸ Sound ▸ Startup and game cues, 0–1. */
    val cueVolume: Float = 0.75f,
    /** Settings ▸ Theme ▸ Background ▸ Wave background (the shader intro has nothing to ramp without it). */
    val waveEnabled: Boolean = true,
)

/** The intro to play: [mode], the [video] for [BootMode.VIDEO], and how long it may last. */
@Immutable
data class BootPlan(val mode: BootMode, val video: File? = null, val maxMs: Long = BootSequence.MAX_MS) {
    val fileName: String get() = video?.name ?: "none"
}

/**
 * The cold-start intro (2b.5d), pure. On a cold start only:
 *  - the pack's boot video if it has one (`boot.mp4` or `audio.bootVideo`), unless the look or
 *    the user turns the intro off or the user picks the shader intro (a pack's own shader intro
 *    loses to its video);
 *  - else `effects.bootIntro: SHADER`: the pack's shader ramped up from black over [SHADER_MS];
 *  - else the menu's fade-in ([BootMode.FADE]).
 *
 * The fade-in is all there is under a still-motion profile (E-Ink), when the startup sound is off
 * or the cue volume is 0 (a silent start stays plain), and for the shader without the wave. Any
 * key, touch, or [MAX_MS] ends an intro with a [CROSSFADE_MS] crossfade; the menu is composed
 * underneath all along.
 */
object BootSequence {
    /** An intro (and a boot video) is cut here. */
    const val MAX_MS = 15_000L

    /** The shader intro's length. */
    const val SHADER_MS = 2_500L

    /** The crossfade from an intro (ended or skipped) to the menu. */
    const val CROSSFADE_MS = 300L

    /** The shader intro: the black over the shader lifts over this, from its start. */
    const val SHADER_BLACK_MS = 1_500L

    /** The shader intro: the menu fades in from here to the end. */
    const val SHADER_MENU_AT_MS = 1_200L

    /** The fade-in: the black of a cold start lifts over this, then the menu fades in over [FADE_MENU_MS]. */
    const val FADE_BLACK_MS = 350L
    const val FADE_MENU_MS = 1_400L

    /** The intro a start with [i] plays; null on a warm start (nothing at all: the menu is just there). */
    fun resolve(i: BootInputs): BootPlan? {
        if (!i.coldBoot) return null
        val fade = BootPlan(BootMode.FADE)
        val muted = !i.startupSound || !i.cueVolume.isFinite() || i.cueVolume <= 0f
        if (i.stillMotion || muted) return fade
        val video = i.video
        val shader = if (i.waveEnabled) BootPlan(BootMode.SHADER, maxMs = SHADER_MS) else fade
        return when (i.intro) {
            BootIntro.OFF -> fade
            BootIntro.SHADER -> if (video != null && !i.userChoice) BootPlan(BootMode.VIDEO, video) else shader
            BootIntro.VIDEO, null -> if (video != null) BootPlan(BootMode.VIDEO, video) else fade
        }
    }

    /** The `XrossBoot` log line for [plan] ending [atMs] after it started. */
    fun describe(plan: BootPlan, skipped: Boolean, atMs: Long): String =
        "mode=${plan.mode} file=${plan.fileName} skipped=$skipped at=$atMs ms"
}

/**
 * The intro on screen (in [dev.xross.launcher.state.XmbUiState]): [plan], when it started
 * (`SystemClock.elapsedRealtimeNanos`, the wave renderer's clock), and whether it is crossfading
 * to the menu ([ending], since [endedAtNanos]).
 */
@Immutable
data class BootState(
    val plan: BootPlan,
    val startedAtNanos: Long,
    val ending: Boolean = false,
    val endedAtNanos: Long = 0L,
)

/**
 * The two levels of a cold start: [menu], the menu's alpha, and [cover], the black (and the boot
 * video) over everything, the wave included.
 */
@Stable
class BootReveal(cold: Boolean) {
    val menuLevel = Animatable(if (cold) 0f else 1f)
    val coverLevel = Animatable(if (cold) 1f else 0f)
    val menu: Float get() = menuLevel.value
    val cover: Float get() = coverLevel.value
}

/**
 * Drives [BootReveal] from the ViewModel's boot state: black while the plan is [pending] (the
 * saved pack is loading), then the fade-in, the shader ramp, or the video's cover, and the
 * crossfade to the menu when the intro ends.
 */
@Composable
fun rememberBootReveal(coldBoot: Boolean, pending: Boolean, boot: BootState?): BootReveal {
    val reveal = remember { BootReveal(coldBoot) }
    val mode = boot?.plan?.mode
    val ending = boot?.ending == true
    // An intro played: once it is gone the menu is simply there (no fade-in after the crossfade).
    var hadIntro by remember { mutableStateOf(false) }
    if (mode != null && (mode != BootMode.FADE || ending)) hadIntro = true
    LaunchedEffect(pending, mode, ending) {
        if (pending || (!coldBoot && boot == null)) return@LaunchedEffect
        if (boot == null && hadIntro) {
            reveal.coverLevel.snapTo(0f)
            reveal.menuLevel.snapTo(1f)
            return@LaunchedEffect
        }
        coroutineScope {
            when {
                // An intro ending (or skipped): both levels reach the menu together.
                ending -> {
                    val t = tween<Float>(BootSequence.CROSSFADE_MS.toInt(), easing = LinearEasing)
                    launch { reveal.coverLevel.animateTo(0f, t) }
                    launch { reveal.menuLevel.animateTo(1f, t) }
                }
                mode == BootMode.VIDEO -> {
                    // The menu is ready underneath; the video covers it until it ends.
                    reveal.menuLevel.snapTo(1f)
                    reveal.coverLevel.snapTo(1f)
                }
                mode == BootMode.SHADER -> {
                    launch {
                        reveal.coverLevel.animateTo(0f, tween(BootSequence.SHADER_BLACK_MS.toInt(), easing = FastOutSlowInEasing))
                    }
                    launch {
                        val ms = (BootSequence.SHADER_MS - BootSequence.SHADER_MENU_AT_MS).toInt()
                        reveal.menuLevel.animateTo(1f, tween(ms, delayMillis = BootSequence.SHADER_MENU_AT_MS.toInt(), easing = LinearEasing))
                    }
                }
                // The fade-in, or the moment after an intro: whatever is left reaches the menu.
                else -> {
                    launch { reveal.coverLevel.animateTo(0f, tween(BootSequence.FADE_BLACK_MS.toInt(), easing = LinearEasing)) }
                    if (reveal.menuLevel.value < 1f) {
                        reveal.menuLevel.animateTo(
                            1f,
                            tween(BootSequence.FADE_MENU_MS.toInt(), delayMillis = BootSequence.FADE_BLACK_MS.toInt(), easing = LinearEasing),
                        )
                    }
                }
            }
        }
    }
    return reveal
}

/**
 * The black over a cold start and, for [BootMode.VIDEO], the boot video in it. While an intro
 * plays a touch anywhere skips it ([onSkip]); keys go through the ViewModel's skip handler first.
 * [onVideoEnded] when the video finishes or can't play; [onCovering] while the video's first frame
 * is up and nothing shows through, so the wave can rest underneath.
 */
@Composable
fun BootOverlay(
    reveal: BootReveal,
    boot: BootState?,
    /** The intro isn't decided yet: a touch now skips it in advance. */
    pending: Boolean,
    videoVolume: Float,
    onSkip: () -> Unit,
    onVideoEnded: () -> Unit,
    onCovering: (Boolean) -> Unit,
) {
    val skip by rememberUpdatedState(onSkip)
    val playing = pending || (boot != null && !boot.ending)
    val video = boot?.plan?.takeIf { it.mode == BootMode.VIDEO }?.video
    val cover = reveal.cover
    if (cover <= 0f && video == null) {
        LaunchedEffect(Unit) { onCovering(false) }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = reveal.cover }
            .background(Color.Black)
            .then(
                if (playing) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            skip()
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (video != null) BootVideo(video, videoVolume, alpha = { reveal.cover }, onVideoEnded, onCovering)
    }
}

/**
 * [file] played once, fitted on black, at [volume]: no audio focus, so another app's audio isn't
 * paused. Drawn on a `SurfaceView` above the window, so frames go to the compositor as the codec
 * releases them; through a `TextureView` every frame waited for one of Xross's UI frames, and a
 * 60 fps video showed about 30 at 120 Hz on the A55 (none dropped by the decoder). Nothing draws
 * over it, an empty surface shows the black underneath until the first frame, and the crossfade
 * fades the surface with [alpha] (applied to the surface from API 29; on 28 it cuts at the end).
 */
@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private fun BootVideo(file: File, volume: Float, alpha: () -> Float, onEnded: () -> Unit, onCovering: (Boolean) -> Unit) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val covering by rememberUpdatedState(onCovering)
    var videoSize by remember(file) { mutableStateOf(VideoSize.UNKNOWN) }
    val player = remember(file) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_GAME).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                false,
            )
            .build()
            .apply {
                this.volume = volume.coerceIn(0f, 1f)
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
    }
    LaunchedEffect(volume) { player.volume = volume.coerceIn(0f, 1f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onRenderedFirstFrame() { covering(true) }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended() }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("XrossBoot", "boot video ${file.name} can't play: ${error.errorCodeName}")
                ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (dev.xross.launcher.BuildConfig.PERF_LOG) {
                player.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    android.util.Log.i(
                        "XrossBoot",
                        "video rendered=${it.renderedOutputBufferCount} dropped=${it.droppedBufferCount} " +
                            "maxRun=${it.maxConsecutiveDroppedBufferCount} skipped=${it.skippedOutputBufferCount}",
                    )
                }
            }
            player.release()
            covering(false)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = if (videoSize.width > 0 && videoSize.height > 0) {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        } else {
            16f / 9f
        }
        // Fit: the whole frame shows, letterboxed on black (a logo at the edge isn't cut).
        val (w, h) = if (maxWidth / maxHeight > aspect) maxHeight * aspect to maxHeight else maxWidth to maxWidth / aspect
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setZOrderOnTop(true)
                    player.setVideoSurfaceView(this)
                }
            },
            // The player and the view leave together (released with the player above).
            update = { it.alpha = alpha().coerceIn(0f, 1f) },
            modifier = Modifier.requiredSize(w, h),
        )
    }
}
