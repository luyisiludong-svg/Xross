package dev.xross.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import dev.xross.launcher.theme.pack.FocusFrameStyle
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import dev.xross.launcher.theme.pack.LaunchCurtain
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.currentStateAsState
import dev.xross.launcher.input.ConfirmLayout
import dev.xross.launcher.media.MusicPlayer
import dev.xross.launcher.media.ThumbKind
import dev.xross.launcher.state.MediaOverlay
import dev.xross.launcher.state.MediaOverlayController
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.model.Face
import dev.xross.launcher.model.GlyphKey
import dev.xross.launcher.model.GlyphStyle
import dev.xross.launcher.theme.pack.MiniPlayerMode
import dev.xross.launcher.state.BadgePills
import dev.xross.launcher.state.TitlePill
import dev.xross.launcher.state.NodeAction
import dev.xross.launcher.state.NodeIcon
import dev.xross.launcher.state.RemapSession
import dev.xross.launcher.state.XmbCategory
import dev.xross.launcher.state.XmbNode
import dev.xross.launcher.state.XmbEvent
import dev.xross.launcher.state.XmbUiState
import dev.xross.launcher.media.SnapPlayer
import dev.xross.launcher.model.ArtKind
import dev.xross.launcher.theme.ThemePalette
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rememberActivePalette
import dev.xross.launcher.theme.pack.Crosshair
import dev.xross.launcher.theme.pack.DrillDownStyle
import dev.xross.launcher.theme.pack.FocusDividerStyle
import dev.xross.launcher.theme.pack.Motion
import dev.xross.launcher.theme.pack.TextOverflowMode
import dev.xross.launcher.theme.pack.ThemeLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import dev.xross.launcher.state.SocialStack
import dev.xross.launcher.state.StatusBarState
import dev.xross.launcher.theme.WallpaperSource
import dev.xross.launcher.theme.pack.Cosmetics
import dev.xross.launcher.theme.pack.ParticlePreset
import dev.xross.launcher.theme.pack.UiFilter
import dev.xross.launcher.theme.pack.UiFilterRef
import dev.xross.launcher.theme.pack.nav
import dev.xross.launcher.theme.pack.tween
import dev.xross.launcher.theme.glowColor

internal val TextBright = Color(0xFFFFFFFF)
internal val TextDim = Color(0xD9FFFFFF)
private val DropShadow = Color(0x99000000)
private val IconTop = Color(0xFFFFFFFF)
private val IconBottom = Color(0xFFC9C9D3)
internal val Pill = Color(0x59000000)

/**
 * Layout measured from the reference screenshots (16:9): focused category column at ~22% of the
 * width, category bar at ~25% of the height, focused item at ~52% (the Default pack's `layout`).
 * A pack moves the crosshair, spacing, focus scale and overall scale with its `layout` block
 * ([ThemeLayout]).
 *
 * Everything else scales from [s], the usable short side (times the UI scale), so 4:3 / 1:1 / 3:2
 * handhelds, 21:9 phones, portrait and 4K TVs keep the same proportions:
 *  - the focus column always leaves room for one whole category on each side ([Crosshair.clampX]);
 *  - vertical positions follow [s], not the raw height, so tall portrait screens don't open a
 *    gap between the bar and the list ([Crosshair.clampY]).
 */
internal class XmbMetrics(val width: Float, val height: Float, val layout: ThemeLayout = ThemeLayout.DEFAULT) {
    val s = min(height, width * 0.9f) * layout.uiScale

    val categorySpacing = s * 0.34f
    val categoryIcon = s * 0.12f
    val categoryFocusScale = 1.38f
    val anchorX = Crosshair.clampX(layout.crosshairX, width, categorySpacing, categoryIcon)
    val itemIcon = s * 0.085f
    val itemFocusScale = layout.focusedScale

    /** Row pitch; the Standard 12 dp gap is the measured 0.15 of the short side (dp at a 360 dp short side). */
    val itemSpacing = s * (0.15f + (layout.itemSpacingDp - 12f) / 360f)
    val focusGap = s * 0.05f

    /** Bar to focused row. */
    private val focusDrop = s * 0.27f
    val barY = Crosshair.clampY(layout.crosshairY, height, s, listBelow = focusDrop + focusGap + itemSpacing + itemIcon / 2)
    val focusedY = barY + focusDrop
    val aboveY = barY - s * 0.19f
    val textGap = s * 0.06f
    val titleSize = s * 0.047f
    val subtitleSize = s * 0.036f
    val labelSize = s * 0.037f
    val statusSize = s * 0.042f

    /** How many rows fit above the bar and below the focus; only those are composed. */
    val rowsAbove = ceil(aboveY / itemSpacing).toInt() + 1
    val rowsBelow = ceil((height - focusedY - focusGap) / itemSpacing).toInt() + 1
}

@Composable
fun XmbScreen(
    state: XmbUiState,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    media: MediaOverlayController,
    music: MusicPlayer,
    /** The status bar's own slice; only the bar collects it. */
    statusBar: kotlinx.coroutines.flow.StateFlow<StatusBarState>,
    /** Every touch and swipe: see [XmbEvent]. */
    onEvent: (XmbEvent) -> Unit,
    /** What the screen reports about its own drawing and layout: see [ScreenSignal]. */
    onSignal: (ScreenSignal) -> Unit = {},
    snap: SnapPlayer? = null,
    videoFile: suspend (String) -> java.io.File? = { null },
    loadBadge: suspend (String, Int) -> ImageBitmap? = { _, _ -> null },
    /** The wallpaper behind the menu (the user's Xross wallpaper or the pack's); null: none. */
    wallpaper: WallpaperSource? = null,
    /** The pack's wallpaper, shown when [wallpaper] (the user's) can't be read. */
    packWallpaper: WallpaperSource? = null,
    loadWallpaper: suspend (WallpaperSource, Int) -> Wallpaper? = { _, _ -> null },
    /** How the user's own wallpaper is placed (the pack's always fills). */
    wallpaperFit: dev.xross.launcher.data.WallpaperFit = dev.xross.launcher.data.WallpaperFit.FILL,
    /** No input for [ParticleRest.IDLE_AFTER_MS]: the foreground particles rest. */
    particlesIdle: Boolean = false,
    /** The menu's alpha on a cold start ([BootReveal.menu], 2b.5d): the fade-in, or 1 under the boot video. */
    reveal: () -> Float = { 1f },
) {
    val palette = rememberActivePalette(state.theme, (LocalThemeLook.current ?: LocalThemePack.current?.look())?.palette)
    // The pack's layout with the context rules that hold and the quick profile's motion (MainActivity).
    val targetLayout = LocalThemeLayout.current ?: LocalThemePack.current?.layout ?: ThemeLayout.DEFAULT
    val motion = targetLayout.motion
    // A new crosshair, spacing or focus (the Layout panel, 2b.8a, or a rule) springs there.
    val layout = animatedLayout(targetLayout, motion)

    // Slow "breathing" glow shared by everything that is focused. It settles and holds still while
    // the screen rests (LocalUiIdle), so an untouched home screen draws no frames for it. Only the
    // breathing halo breathes: the other focus frames (2b.4d) hold a steady glow and never run it.
    val started by lifecycleStarted()
    val idle = LocalUiIdle.current
    val pulse by rememberFocusPulse(running = started && !idle)

    // The experimental UI filter (2b.4f, Android 13+): an AGSL RenderEffect on the whole menu.
    // Static filters never animate; a custom one that reads uTime rests with the screen.
    val filterRef = LocalThemeLook.current?.cosmetics?.screenFilter ?: UiFilterRef.OFF
    val filterSource = filterRef.takeIf { it.filter == UiFilter.CUSTOM }
        ?.let { LocalThemePack.current?.customUiFilters?.get(it.path) }
    val filterStill = LocalStillMotion.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .uiFilter(filterRef, filterSource, started, idle, filterStill) { key, text -> onSignal(ScreenSignal.UiFilterError(key, text)) }
            .graphicsLayer { alpha = reveal() }
            .frostHost()
            .swipeNavigation { onEvent(XmbEvent.Nav(it)) },
    ) {
        val m = remember(constraints.maxWidth, constraints.maxHeight, layout) {
            XmbMetrics(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), layout)
        }
        val signal by rememberUpdatedState(onSignal)
        LaunchedEffect(m.anchorX, m.width) { if (m.width > 0f) signal(ScreenSignal.CrosshairX(m.anchorX / m.width)) }
        val density = LocalDensity.current
        fun Float.sp(): TextUnit = with(density) { this@sp.toSp() }
        fun Float.dp(): Dp = with(density) { this@dp.toDp() }

        // Focused game: hero art after 250 ms, then its video snap after 1.2 s, full-bleed behind
        // the menu (see BackdropTiming). The wave pauses while either fully covers the screen.
        val focusedArt = when (val icon = state.focused?.icon) {
            is NodeIcon.Art -> FocusedArt(icon.uuid, icon.version)
            is NodeIcon.Tile -> if (icon.scraped) FocusedArt(icon.uuid, icon.version) else null
            else -> null
        }
        // The Options and Information panels sit over the focused game, so its art and snap keep
        // playing behind them; full-screen overlays stop them.
        val quiet = state.overlay == null && state.search == null &&
            state.booting == null && state.remap == null && state.waveEditor == null &&
            state.achievements == null && state.profileCard == null && state.nameEntry == null &&
            state.avatarEditor == null && state.raHub == null
        var heroCovers by remember { mutableStateOf(false) }
        var videoCovers by remember { mutableStateOf(false) }
        var wallpaperCovers by remember { mutableStateOf(false) }
        val viewport = androidx.compose.ui.geometry.Size(m.width, m.height)
        // Layer 3: the wallpaper, hidden while the wave editor shows the wave it edits.
        WallpaperBackdrop(
            source = wallpaper,
            fallback = packWallpaper,
            shown = state.waveEditor == null,
            maxPx = minOf(maxOf(m.width, m.height), 2560f).toInt(),
            viewport = viewport,
            load = loadWallpaper,
            onCovering = { wallpaperCovers = it },
            fit = wallpaperFit,
        )
        FocusBackdrop(
            uuid = focusedArt?.uuid?.takeIf { quiet && state.details == null },
            artVersion = focusedArt?.version ?: 0L,
            maxPx = minOf(maxOf(m.width, m.height), 1600f).toInt(),
            viewport = viewport,
            loadArt = loadArt,
            onCovering = { heroCovers = it },
        )
        if (snap != null) {
            SnapDriver(
                uuid = state.details?.uuid ?: focusedArt?.uuid.takeIf { quiet },
                snap = snap,
                videoFile = videoFile,
            )
            val playing by snap.player.collectAsState()
            playing?.let { SnapVideo(it, Modifier.fillMaxSize(), scrim = 0.55f, onCovering = { c -> videoCovers = c }) }
            if (playing == null) videoCovers = false
        }
        // Frosted surfaces show blurred art only where a still image is what's behind them:
        // not over a video snap or a media screen.
        val snapOn = snap?.player?.collectAsState()?.value != null
        val frost = LocalFrostedBackdrop.current
        val frostOff = snapOn || state.overlay != null
        androidx.compose.runtime.SideEffect { frost.suppressed = frostOff }
        // Layer 4: ambient particles over the art (2b.4e), hidden under a snap, full-screen media,
        // the wave editor and E-Ink, resting when stopped or after 10 s without input.
        val particlePreset = (LocalThemeLook.current?.cosmetics ?: Cosmetics.NONE).particleField
        if (particlePreset != ParticlePreset.NONE) {
            val stillMotion = LocalStillMotion.current
            val particleState = ParticleRest.of(
                preset = particlePreset,
                stillMotion = stillMotion,
                snapPlaying = snapOn,
                covered = state.waveEditor != null ||
                    state.overlay is MediaOverlay.Photos || state.overlay is MediaOverlay.VideoPlayback,
                started = started,
                idle = particlesIdle,
            )
            AmbientParticles(particlePreset, particleState, Color(palette.glow), stillMotion)
        }
        val covers = heroCovers || videoCovers || wallpaperCovers
        LaunchedEffect(covers) { signal(ScreenSignal.BackdropCover(covers)) }

        val catPos by animateFloatAsState(state.categoryIndex.toFloat(), motion.nav(), label = "category")
        val depth by animateFloatAsState(state.depth.coerceAtMost(1).toFloat(), motion.nav(), label = "depth")

        // The menu fades out under media screens so only one set of text and hints is visible.
        /** Height of the button-hint row, so the mini player can float just above it. */
        var hintsHeightPx by remember { mutableIntStateOf(0) }
        val menuShown = state.overlay == null && state.search == null && state.nameEntry == null && state.details == null &&
            state.waveEditor == null && state.achievements == null && state.profileCard == null &&
            state.avatarEditor == null && state.raHub == null
        val menuAlpha by animateFloatAsState(if (menuShown) 1f else 0f, motion.tween(220), label = "menu")
        // The avatar's glow pulses only while the menu is in front and in use: it holds still while
        // the screen rests, under overlays, and when the activity is stopped.
        val avatarGlowRuns = started && !idle && menuShown
        val components = LocalComponents.current
        // The mini player (card or ticker) hides while anything takes over the screen or the corner.
        val miniCovered = state.overlay != null || state.search != null || state.nameEntry != null ||
            state.details != null || state.optionsMenu != null || state.info != null ||
            state.profileCard != null || state.remap != null || state.booting != null ||
            state.waveEditor != null || state.achievements != null ||
            state.avatarEditor != null || state.raHub != null || state.layoutEditor != null

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = menuAlpha }) {
        CategoryBar(
            categories = state.categories,
            categoryIndex = state.categoryIndex,
            breadcrumb = state.breadcrumb.joinToString("  ›  "),
            folder = state.folderGlyph,
            style = layout.drillDown,
            m = m,
            catPos = { catPos },
            depth = { depth },
            pulse = { pulse },
            onCategoryTap = { onEvent(XmbEvent.Menu.CategoryTap(it)) },
        )
        ItemColumn(state, m, motion, { depth }, { pulse }, loadAppIcon, loadThumb, loadArt,
            onItemTap = { onEvent(XmbEvent.Menu.ItemTap(it)) },
            onItemLongPress = { onEvent(XmbEvent.Menu.ItemLongPress(it)) },
        )

        // (The "now playing" line that used to sit top-left is the floating MiniPlayerCard below.)

        // Status bar (2b.7a): the pack's left and right slot clusters, the avatar always the
        // rightmost slot. The avatar is drawn by AvatarHud over the room the bar keeps for it, and
        // grows in place into the mini-HUD, which takes the right cluster's place while it is open.
        val hudExpansion = animateFloatAsState(
            if (state.avatarFocused) 1f else 0f,
            if (motion.instant) snap() else tween(260, easing = FastOutSlowInEasing),
            label = "avatarHud",
        )
        val statusTop = (m.s * 0.04f).dp()
        val statusEnd = (m.width * 0.025f).dp()
        StatusBar(
            source = statusBar,
            components = components,
            textSize = m.statusSize.sp(),
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = statusTop, start = statusEnd, end = statusEnd),
            contentAlpha = { 1f - hudExpansion.value },
            trailingWidth = StatusAvatarBox + 10.dp,
            trailingHeight = StatusAvatarBox,
            // The left cluster clips before it reaches the category bar's focused icon.
            leftMaxHeight = (m.barY - m.categoryIcon * m.categoryFocusScale / 2 - m.s * 0.04f).coerceAtLeast(0f).dp(),
            ticker = state.nowPlaying.takeIf { components.miniPlayer == MiniPlayerMode.STATUS_TICKER },
            tickerCovered = miniCovered,
            onTickerTap = { onEvent(XmbEvent.Menu.MiniPlayerTap) },
            onTickerLongPress = { onEvent(XmbEvent.Menu.MiniPlayerLongPress) },
            social = if (SocialStack.shows(components, state.friends)) state.friends.friends else emptyList(),
            socialBadge = state.friends.badge,
            socialOpen = state.socialPanel != null,
            socialGlow = state.settings.avatarStyle.glowEnabled,
            onSocialTap = { onEvent(XmbEvent.Ra.SocialTap) },
        )
        state.socialPanel?.let { panel ->
            // A tap anywhere outside the panel closes it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = { onEvent(XmbEvent.Ra.CloseSocialPanel) }),
            )
            SocialPanel(
                friends = state.friends,
                state = panel,
                textSize = m.subtitleSize.sp(),
                width = (m.s * 0.82f).coerceAtMost(m.width * 0.62f).dp(),
                nowMs = remember(panel, state.friends) { System.currentTimeMillis() },
                backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
                modifier = Modifier.align(Alignment.TopStart).padding(top = statusTop + StatusAvatarBox + 6.dp, start = statusEnd),
            )
        }
        if (state.avatarFocused && components.avatar) {
            // A tap anywhere outside the expanded HUD collapses it.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = { onEvent(XmbEvent.Ra.CloseAvatarHud) }),
            )
        }
        if (components.avatar) AvatarHud(
            expansion = { hudExpansion.value },
            expanded = state.avatarFocused,
            profile = state.profile,
            style = state.settings.avatarStyle,
            animateGlow = avatarGlowRuns,
            m = m,
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            optionsGlyph = state.glyphs.north,
            onAvatarTap = { onEvent(XmbEvent.Ra.AvatarTap) },
            loadBadge = loadBadge,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = statusTop, end = statusEnd),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding((m.s * 0.04f).dp())
                .onSizeChanged { hintsHeightPx = it.height },
        ) {
            BusyIndicator(busy = state.busy, size = (m.subtitleSize * 1.5f).dp())
            // The Layout panel sits in this corner with its own hints.
            if (state.showButtonHints && state.layoutEditor == null) ButtonHints(state, m.subtitleSize.sp(), Modifier)
        }
        }

        // Floating mini player: at the root, outside the menu's fade, so it rides across every
        // category (miniCovered, above, hides it and the ticker alike).
        // The mini player stacks above the focused game's RetroAchievements badge or card, which sits
        // just above the hints; each takes no room while hidden, so the other settles into place.
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = maxOf(16.dp, (hintsHeightPx + m.s * 0.04f).dp() + 8.dp),
                ),
        ) {
            MiniPlayerCard(
                nowPlaying = state.nowPlaying,
                covered = miniCovered,
                loadThumb = loadThumb,
                onClick = { onEvent(XmbEvent.Menu.MiniPlayerTap) },
                onLongClick = { onEvent(XmbEvent.Menu.MiniPlayerLongPress) },
                mode = components.miniPlayer,
            )
            // telemetryMode (2b.7d): nothing, the compact pill or the rich card; the mini player's
            // ticker and Off modes leave the corner to it.
            GameTelemetryCorner(
                mode = components.telemetry,
                telemetry = state.focusedTelemetry,
                hidden = miniCovered || state.avatarFocused,
                friends = state.friends,
                size = m.subtitleSize.sp(),
                loadArt = loadArt,
                loadBadge = loadBadge,
            )
        }

        state.overlay?.let { overlay ->
            MediaOverlayLayer(
                overlay = overlay,
                controller = media,
                music = music,
                nowPlaying = state.nowPlaying,
                loadThumb = loadThumb,
                hints = { pairs -> OverlayHints(pairs, state, m.subtitleSize.sp()) },
            )
        }

        state.nameEntry?.let { entry ->
            NameEntryOverlay(
                entry = entry,
                m = m,
                pulse = { pulse },
                glyphs = state.glyphs,
                confirm = state.confirm,
                onKeyTap = { row, column -> onEvent(XmbEvent.Typing.NameKeyTap(row, column)) },
            )
        }

        state.search?.let { search ->
            SearchOverlay(
                search = search,
                m = m,
                pulse = { pulse },
                glyphs = state.glyphs,
                confirm = state.confirm,
                onKeyTap = { row, column -> onEvent(XmbEvent.Typing.SearchKeyTap(row, column)) },
                onResultTap = { onEvent(XmbEvent.Typing.SearchResultTap(it)) },
            )
        }

        OptionsPanel(
            menu = state.optionsMenu,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onTap = { onEvent(XmbEvent.Menu.OptionTap(it)) },
            onDismiss = { onEvent(XmbEvent.Menu.CloseOptions) },
        )

        DetailsScreen(
            details = state.details,
            m = m,
            snapPlaying = snap?.player?.collectAsState()?.value != null,
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            optionsGlyph = state.glyphs.north,
            loadArt = loadArt,
            loadBadge = loadBadge,
            onPlay = { onEvent(XmbEvent.Menu.PlayDetails) },
            onAchievements = { onEvent(XmbEvent.Menu.DetailsAchievements) },
            onDismiss = { onEvent(XmbEvent.Menu.CloseDetails) },
        )

        InfoPanel(
            info = state.info,
            m = m,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onRowTap = { onEvent(XmbEvent.Menu.InfoRowTap(it)) },
            onDismiss = { onEvent(XmbEvent.Menu.CloseInfo) },
        )

        UserCard(
            card = state.profileCard,
            profile = state.profile,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onButtonTap = { onEvent(XmbEvent.Ra.ProfileCardButtonTap(it)) },
            onDismiss = { onEvent(XmbEvent.Ra.CloseProfileCard) },
            loadBadge = loadBadge,
            avatarStyle = state.settings.avatarStyle,
        )

        RaHubScreen(
            hub = state.raHub,
            profile = state.profile,
            style = state.settings.avatarStyle,
            m = m,
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            loadArt = loadArt,
            loadBadge = loadBadge,
            onDismiss = { onEvent(XmbEvent.Ra.CloseHub) },
            friends = state.friends,
            onPillTap = { onEvent(XmbEvent.Ra.HubPillTap(it)) },
            onRowTap = { onEvent(XmbEvent.Ra.HubRowTap(it)) },
        )

        AchievementsScreen(
            view = state.achievements,
            m = m,
            pulse = { pulse },
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            optionsGlyph = state.glyphs.north,
            loadArt = loadArt,
            loadBadge = loadBadge,
            onBadgeTap = { onEvent(XmbEvent.Ra.AchievementTap(it)) },
            onDismiss = { onEvent(XmbEvent.Ra.CloseAchievements) },
            onColumns = { onEvent(XmbEvent.Ra.AchievementsColumns(it)) },
        )

        WaveSettingsPanel(
            editor = state.waveEditor,
            settings = state.settings,
            basePalette = palette,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onRowTap = { onEvent(XmbEvent.Editor.WaveRowTap(it)) },
            onSlider = { index, fraction -> onEvent(XmbEvent.Editor.WaveSlider(index, fraction)) },
            onDismiss = { onEvent(XmbEvent.Editor.CloseWave) },
        )

        LayoutSettingsPanel(
            editor = state.layoutEditor,
            user = state.settings.layoutOverrides,
            pack = LocalThemePack.current?.layout ?: ThemeLayout.DEFAULT,
            // At 100% UI scale, so the panel holds still while the menu behind it scales.
            m = remember(m.width, m.height) { XmbMetrics(m.width, m.height) },
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onRowTap = { onEvent(XmbEvent.Layout.RowTap(it)) },
            onSlider = { index, fraction -> onEvent(XmbEvent.Layout.Slider(index, fraction)) },
            onDismiss = { onEvent(XmbEvent.Layout.Close) },
        )

        AvatarStylePanel(
            editor = state.avatarEditor,
            style = state.settings.avatarStyle,
            profile = state.profile,
            m = m,
            pulse = { pulse },
            confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south,
            backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east,
            onRowTap = { onEvent(XmbEvent.Editor.AvatarRowTap(it)) },
            onSlider = { index, fraction -> onEvent(XmbEvent.Editor.AvatarSlider(index, fraction)) },
            onDismiss = { onEvent(XmbEvent.Editor.CloseAvatar) },
        )

        state.remap?.let { RemapOverlay(it, m) { onEvent(XmbEvent.Menu.CancelRemap) } }
        state.booting?.let { GameBootOverlay(it, palette, m, LocalThemeLook.current?.cosmetics?.curtain ?: LaunchCurtain.BLOOM, LocalStillMotion.current) }

        // Unlock toasts: top right under the status bar; drawn only, never focused.
        UnlockToast(
            toast = state.unlockToast,
            m = m,
            loadBadge = loadBadge,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = (m.s * 0.12f).dp(), end = (m.width * 0.025f).dp()),
        )

        // Toasts sit above every overlay (search, Options, media) so feedback is never hidden.
        AnimatedVisibility(
            visible = state.toast != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = (m.s * 0.04f).dp()),
        ) {
            var last by remember { mutableStateOf("") }
            state.toast?.let { last = it }
            Text(
                last,
                style = shadowed(m.subtitleSize.sp()),
                modifier = Modifier
                    // Full strength: toasts can land on top of overlay text.
                    .xrossSurface(RoundedCornerShape(50), borderAlpha = 0.7f)
                    .padding(horizontal = (m.s * 0.04f).dp(), vertical = (m.s * 0.018f).dp()),
            )
        }
    }
}

/**
 * [target] with its crosshair, row spacing and focused scale springing to new values with
 * [motion] (a snap under INSTANT); everything else as [target]. At rest it is [target] itself.
 * The UI scale snaps: springing it would lay every text on screen out again at a new size each
 * frame, which drops frames while a key is held on the Layout panel's UI scale row.
 */
@Composable
private fun animatedLayout(target: ThemeLayout, motion: Motion): ThemeLayout {
    val spec = motion.nav<Float>()
    val x by animateFloatAsState(target.crosshairX, spec, label = "crosshairX")
    val y by animateFloatAsState(target.crosshairY, spec, label = "crosshairY")
    val spacing by animateFloatAsState(target.itemSpacingDp, spec, label = "itemSpacing")
    val focus by animateFloatAsState(target.focusedScale, spec, label = "focusedScale")
    val settled = x == target.crosshairX && y == target.crosshairY && spacing == target.itemSpacingDp && focus == target.focusedScale
    return if (settled) target else target.copy(crosshairX = x, crosshairY = y, itemSpacingDp = spacing, focusedScale = focus)
}

// region Category bar

/** The focused row's scraped art, for the hero backdrop and the video snap. */
private data class FocusedArt(val uuid: String, val version: Long)


/** True while the activity is at least started; animations tied to it stop in the background. */
@Composable
private fun lifecycleStarted(): androidx.compose.runtime.State<Boolean> {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val state = owner.lifecycle.currentStateAsState()
    return remember(state) { derivedStateOf { state.value.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) } }
}

/**
 * Takes only what it draws, so cursor moves in the item list don't recompose the bar.
 *
 * Drilled into a list, [style] decides what shows where it is: the [breadcrumb] text beside the
 * open category ([DrillDownStyle.BREADCRUMB]), the opened [folder]'s icon parked left of the list
 * ([DrillDownStyle.XMB_FOLDER_COLUMN]), or nothing: the whole bar collapses and the focused row
 * shows a ◀ ([DrillDownStyle.CHEVRON_COLLAPSE], drawn by the row).
 */
@Composable
private fun CategoryBar(
    categories: List<XmbCategory>,
    categoryIndex: Int,
    breadcrumb: String,
    folder: GlyphKey?,
    style: DrillDownStyle,
    m: XmbMetrics,
    catPos: () -> Float,
    depth: () -> Float,
    pulse: () -> Float,
    onCategoryTap: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val iconDp = with(density) { m.categoryIcon.toDp() }
    val cellDp = with(density) { m.categorySpacing.toDp() }
    val labelSp = with(density) { m.labelSize.toSp() }
    val collapse = style == DrillDownStyle.CHEVRON_COLLAPSE

    categories.forEachIndexed { i, category ->
        val focus = { 1f - abs(i - catPos()).coerceAtMost(1f) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(cellDp)
                .offset {
                    // Drilling into a list slides the bar left, leaving the open category's icon.
                    val shift = depth() * m.categorySpacing * 0.85f
                    val x = m.anchorX + (i - catPos()) * m.categorySpacing - shift - m.categorySpacing / 2
                    // Collapsing, the whole bar also rises a little as it fades.
                    val lift = if (collapse) depth() * m.s * 0.06f else 0f
                    IntOffset(x.roundToInt(), (m.barY - m.categoryIcon / 2 - lift).roundToInt())
                }
                .fadeAlpha {
                    val f = focus()
                    val d = depth()
                    (0.62f + 0.38f * f) * (1f - d * (1f - f)) * (if (collapse) 1f - d else 1f)
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) { onCategoryTap(i) },
        ) {
            XmbIcon(
                painter = glyphPainter(category.icon),
                size = iconDp,
                focus = focus,
                focusScale = m.categoryFocusScale,
                glow = { pulse() * focus() * (1f - depth()) },
            )
            Spacer(Modifier.height(with(density) { (m.categoryIcon * 0.32f).toDp() }))
            GlowText(
                text = category.title,
                fontSize = labelSp,
                glow = { pulse() * focus() },
                fontFamily = LocalThemeTypography.current.displayOrBody,
                modifier = Modifier.fadeAlpha { focus() * (1f - depth()) },
            )
        }
    }

    // XMB folder column: the opened folder's icon parks left of the list, under the open
    // category, level with the focused row.
    // Kept while the list closes, so the icon fades out with the depth instead of popping.
    var parked by remember { mutableStateOf(folder) }
    if (folder != null) parked = folder
    val shownFolder = parked
    if (style == DrillDownStyle.XMB_FOLDER_COLUMN && shownFolder != null) {
        val size = with(density) { (m.itemIcon * 1.15f).toDp() }
        Box(
            Modifier
                .offset {
                    val x = m.anchorX - m.categorySpacing * 0.85f - m.itemIcon * 1.15f / 2
                    IntOffset(x.roundToInt(), (m.focusedY - m.itemIcon * 1.15f / 2).roundToInt())
                }
                .fadeAlpha { depth() * 0.9f },
        ) {
            XmbIcon(glyphPainter(shownFolder), size, focus = { 0f }, focusScale = 1f, glow = { 0f })
        }
    }

    // Breadcrumb beside the open category while drilled in.
    if (style == DrillDownStyle.BREADCRUMB && breadcrumb.isNotEmpty()) {
        GlowText(
            text = breadcrumb,
            fontSize = with(density) { m.titleSize.toSp() },
            glow = { 0f },
            modifier = Modifier
                .offset {
                    IntOffset(
                        (m.anchorX - m.categorySpacing * 0.85f + m.categoryIcon * 1.15f).roundToInt(),
                        (m.barY - m.titleSize * 0.7f).roundToInt(),
                    )
                }
                .fadeAlpha { 0.92f },
        )
    }
}

// endregion

// region Item column

@Composable
private fun ItemColumn(
    state: XmbUiState,
    m: XmbMetrics,
    motion: Motion,
    depth: () -> Float,
    pulse: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    onItemTap: (Int) -> Unit,
    onItemLongPress: (Int) -> Unit,
) {
    // Scroll snaps when the list itself changes and glides when the cursor moves.
    val scroll = remember { Animatable(state.focusedIndex.toFloat()) }
    var lastKey by remember { mutableStateOf(state.listKey) }
    LaunchedEffect(state.listKey, state.focusedIndex) {
        if (state.listKey != lastKey || motion.instant) {
            lastKey = state.listKey
            scroll.snapTo(state.focusedIndex.toFloat())
        } else {
            scroll.animateTo(state.focusedIndex.toFloat(), motion.nav())
        }
    }
    val enter = remember(state.listKey) { Animatable(if (motion.instant) 1f else 0f) }
    LaunchedEffect(state.listKey) { if (!motion.instant) enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }

    // Compose only the rows that can be on screen for this display.
    val first by remember(m) { derivedStateOf { floor(scroll.value).toInt() - m.rowsAbove } }
    val last by remember(m) { derivedStateOf { ceil(scroll.value).toInt() + m.rowsBelow } }
    val range = first.coerceAtLeast(0)..last.coerceAtMost(state.items.lastIndex)

    val density = LocalDensity.current
    val iconDp = with(density) { m.itemIcon.toDp() }
    val textX = m.anchorX + m.itemIcon * m.itemFocusScale / 2 + m.textGap
    // At least a readable width, but never past the screen edge (a large UI scale on a square or
    // 4:3 screen can leave less than that), so long titles ellipsize instead of being cut off.
    val textWidth = with(density) {
        (m.width - textX - m.width * 0.03f).coerceAtLeast(m.s * 0.3f).coerceAtMost((m.width - textX).coerceAtLeast(1f)).toDp()
    }
    val layout = m.layout
    val aspect = state.categories.getOrNull(state.categoryIndex)?.id?.let(layout::aspectFor)
    val chevron: () -> Float = if (layout.drillDown == DrillDownStyle.CHEVRON_COLLAPSE) depth else NoChevron
    val dim = layout.unfocusedAlpha

    // Read through State so the remembered row modifiers below never hold a stale callback.
    val tap by rememberUpdatedState(onItemTap)
    val longPress by rememberUpdatedState(onItemLongPress)

    for (i in range) {
        val node = state.items[i]
        key(state.listKey, node.id) {
            // Remembered per row so every argument is identical across cursor moves, and rows
            // whose focus didn't change skip recomposition. Position and fade are read in the
            // layout/draw lambdas, so scrolling itself never recomposes a row.
            val focus = remember(i) { { 1f - abs(i - scroll.value).coerceAtMost(1f) } }
            val interaction = remember { MutableInteractionSource() }
            val rowModifier = remember(i, m, enter) {
                Modifier
                    .offset {
                        val y = itemY(i - scroll.value, m)
                        val slide = (1f - enter.value) * m.s * 0.08f
                        IntOffset(
                            (m.anchorX - m.itemIcon / 2 + slide).roundToInt(),
                            (y - m.itemIcon / 2).roundToInt(),
                        )
                    }
                    .fadeAlpha {
                        // Items scrolled up past the top edge fade out instead of clipping.
                        val fade = (itemY(i - scroll.value, m) / m.itemSpacing).coerceIn(0f, 1f)
                        // A pack's unfocused opacity dims whole rows, icon and text alike.
                        val rest = dim?.let { it + (1f - it) * focus() } ?: 1f
                        enter.value * fade * rest
                    }
                    // Long-press opens the item's Options menu (touch equivalent of △ / Y).
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = { longPress(i) },
                        onClick = { tap(i) },
                    )
            }
            ItemRow(
                node = node,
                m = m,
                iconSize = iconDp,
                textWidth = textWidth,
                // A system override's tile aspect shapes that system's game tiles.
                aspect = if (layout.systemAspects.isEmpty()) aspect else {
                    (node.action as? NodeAction.LaunchGame)?.let { state.gamePlatforms[it.gameId] }?.let(layout.systemAspects::get) ?: aspect
                },
                isFocused = i == state.focusedIndex,
                focus = focus,
                pulse = pulse,
                chevron = chevron,
                loadAppIcon = loadAppIcon,
                loadThumb = loadThumb,
                loadArt = loadArt,
                modifier = rowModifier,
                pills = if (i != state.focusedIndex || node.pills.isEmpty()) emptyList() else {
                    val telemetry = state.focusedTelemetry
                    remember(node.pills, telemetry) { BadgePills.shown(node.pills, telemetry) }
                },
            )
        }
    }
}

private val NoChevron: () -> Float = { 0f }

private fun itemY(d: Float, m: XmbMetrics): Float = when {
    d >= 0f -> m.focusedY + d * m.itemSpacing + m.focusGap * d.coerceAtMost(1f)
    d > -1f -> m.focusedY + (m.aboveY - m.focusedY) * -d
    else -> m.aboveY + (d + 1f) * m.itemSpacing
}

/** Photo and video thumbnails without a pack aspect: a little wider than tall. */
private const val ThumbWideAspect = 1.3f

/** A console logo's widest and tallest, in icon slots; a near-square logo just fills the slot. */
private const val LogoMaxWidth = 2.4f
private const val LogoMaxHeight = 0.8f

/** [painter]'s size in an icon slot of [iconSize]: square stays the slot, wide fits [LogoMaxWidth] x [LogoMaxHeight]. */
private fun logoSize(painter: Painter, iconSize: Dp): DpSize {
    val intrinsic = painter.intrinsicSize
    val aspect = if (intrinsic.isSpecified && intrinsic.height > 0f) intrinsic.width / intrinsic.height else 1f
    if (aspect <= 1.25f) return DpSize(iconSize, iconSize)
    val height = minOf(iconSize * LogoMaxHeight, iconSize * LogoMaxWidth / aspect)
    return DpSize(height * aspect, height)
}

@Composable
private fun ItemRow(
    node: XmbNode,
    m: XmbMetrics,
    iconSize: Dp,
    textWidth: Dp,
    /** The category's tile aspect from the theme layout, or null for each icon's own. */
    aspect: Float?,
    isFocused: Boolean,
    focus: () -> Float,
    pulse: () -> Float,
    /** ◀ opacity beside the focused row ([DrillDownStyle.CHEVRON_COLLAPSE] while drilled in). */
    chevron: () -> Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
    modifier: Modifier,
    /** The title badges drawn (2b.7b): the focused game's [XmbNode.pills], empty on every other row. */
    pills: List<TitlePill> = emptyList(),
) {
    val density = LocalDensity.current
    val titleSp = with(density) { m.titleSize.toSp() }
    val subtitleSp = with(density) { m.subtitleSize.toSp() }
    val layout = m.layout
    val scale = m.itemFocusScale
    // A game tile (or a wide thumbnail) is wider than the square icon slot it is centered on: the
    // text starts further right by half the difference (at the focus scale) and gives up the same
    // width. Built-in thumbnails (1.3) keep their slight overhang into the gap.
    val tileHeight = iconSize * GameTileHeightScale
    val tileAspect = aspect ?: GameTileAspect
    val thumbAspect = (node.icon as? NodeIcon.Thumb)?.let { aspect ?: if (it.kind == ThumbKind.ALBUM_ART) 1f else ThumbWideAspect }
    // A console logo is a wordmark, wider than tall: it keeps its shape and overhangs like a tile.
    val logo = (node.icon as? NodeIcon.System)?.let { systemLogo(it.platformId, it.style) }
    val logoSize = logo?.let { logoSize(it.painter, iconSize) }
    val visualWidth = when (node.icon) {
        is NodeIcon.Tile -> tileHeight * tileAspect
        is NodeIcon.Thumb -> iconSize * thumbAspect!!
        is NodeIcon.System -> logoSize?.width ?: iconSize
        else -> iconSize
    }
    val tileOverhang = when (node.icon) {
        is NodeIcon.Tile, is NodeIcon.System -> ((visualWidth - iconSize) * scale / 2).coerceAtLeast(0.dp)
        is NodeIcon.Thumb -> ((visualWidth - iconSize * ThumbWideAspect) * scale / 2).coerceAtLeast(0.dp)
        else -> 0.dp
    }
    val gap = with(density) { (m.textGap + m.itemIcon * (scale - 1f) / 2).toDp() } + tileOverhang
    // How far the icon reaches past its slot on each side at full focus.
    val reach = (visualWidth * scale - iconSize) / 2
    val glow = { if (isFocused) pulse() * focus() else 0f }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            when (val icon = node.icon) {
                is NodeIcon.Tile -> GameTile(icon, tileHeight, focus, scale, glow, loadArt, aspect = tileAspect)
                is NodeIcon.Glyph -> XmbIcon(
                    painter = glyphPainter(icon.key),
                    size = iconSize,
                    focus = focus,
                    focusScale = scale,
                    glow = glow,
                )
                is NodeIcon.System -> XmbIcon(
                    painter = logo?.painter ?: glyphPainter(icon.fallback),
                    size = iconSize,
                    focus = focus,
                    focusScale = scale,
                    glow = glow,
                    width = logoSize?.width ?: iconSize,
                    height = logoSize?.height ?: iconSize,
                    gloss = logo?.keepsColors != true,
                )
                is NodeIcon.App -> AppIcon(icon.packageName, iconSize, focus, scale, loadAppIcon)
                is NodeIcon.Thumb -> ThumbIcon(icon, iconSize, thumbAspect!!, focus, scale, glow, loadThumb)
                is NodeIcon.Art -> ArtIcon(icon, iconSize, focus, scale, glow, loadArt)
            }
            if (isFocused && chevron !== NoChevron) BackChevron(subtitleSp * 1.4f, reach, { chevron() * focus() })
        }
        Spacer(Modifier.width(gap))
        Column(Modifier.width((textWidth - tileOverhang).coerceAtLeast(iconSize))) {
            GlowText(
                text = node.title,
                fontSize = titleSp,
                glow = glow,
                overflow = layout.textOverflow,
                marquee = isFocused,
                modifier = Modifier.fadeAlpha { (if (layout.unfocusedAlpha != null) 1f else 0.72f + 0.28f * focus()) },
            )
            FocusDivider(
                style = layout.focusDivider,
                // From the icon's right edge (at full focus) to the screen's right edge.
                lead = gap - reach,
                screenWidth = m.width,
                focus = focus,
                modifier = Modifier.padding(top = with(density) { (m.s * 0.008f).toDp() }),
            )
            node.subtitle?.let {
                SubtitleLine(
                    text = it,
                    pills = pills,
                    size = subtitleSp,
                    modifier = Modifier
                        .padding(top = with(density) { (m.s * 0.01f).toDp() })
                        .fadeAlpha { focus() },
                )
            }
        }
    }
}

/**
 * The line under a title: [pills] (2b.7b, the focused game's badges) then [text], ellipsized in
 * the width the pills leave. The line is as tall as its text whether or not pills are drawn, so
 * focusing a game with badges never moves its title.
 */
@Composable
private fun SubtitleLine(text: String, pills: List<TitlePill>, size: TextUnit, modifier: Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Text(text, style = shadowed(size, color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pills.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.clipToBounds(),
                ) {
                    for (p in pills) TitlePillChip(p, size * 0.8f)
                }
            }
        },
    ) { measurables, constraints ->
        val pillRow = measurables.getOrNull(1)?.measure(Constraints(maxWidth = constraints.maxWidth))
        val used = pillRow?.let { it.width + if (text.isEmpty()) 0 else 8.dp.roundToPx() } ?: 0
        val line = measurables[0].measure(Constraints(maxWidth = (constraints.maxWidth - used).coerceAtLeast(0)))
        val width = (used + line.width).coerceAtMost(constraints.maxWidth)
        layout(width, line.height) {
            pillRow?.place(0, (line.height - pillRow.height) / 2)
            line.place(used, 0)
        }
    }
}

/** One title badge: the pack's surface at a small radius; figures in the `mono` font. */
@Composable
private fun TitlePillChip(pill: TitlePill, size: TextUnit) {
    val fonts = LocalThemeTypography.current
    val family = if (pill.mono) fonts.monoOrBody else fonts.family
    Text(
        pill.text,
        style = shadowed(size, weight = FontWeight.Medium).copy(fontFamily = family, shadow = null),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .xrossSurface(RoundedCornerShape(4.dp), strength = 0.85f, borderAlpha = 0.5f, borderScale = 2f / 3f)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The hairline between the focused title and its subtitle. [lead] is the distance from the
 * column back to the focused icon's right edge, where [FocusDividerStyle.FULL_BLEED_LINE] starts;
 * it runs on to the screen's edge (the window clips it).
 */
@Composable
private fun FocusDivider(style: FocusDividerStyle, lead: Dp, screenWidth: Float, focus: () -> Float, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .then(
                when (style) {
                    FocusDividerStyle.NONE -> Modifier
                    FocusDividerStyle.TITLE_UNDERLINE -> Modifier
                        .graphicsLayer { alpha = focus() }
                        .drawBehind {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.White.copy(alpha = 0.95f),
                                    0.7f to Color.White.copy(alpha = 0.8f),
                                    1f to Color.White.copy(alpha = 0.1f),
                                ),
                            )
                        }
                    // ModulateAlpha: the line draws outside the box, which a layer would clip.
                    FocusDividerStyle.FULL_BLEED_LINE -> Modifier
                        .fadeAlpha(focus)
                        .drawBehind {
                            val start = -lead.toPx()
                            val end = size.width + screenWidth
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.White.copy(alpha = 0.9f),
                                    1f to Color.White.copy(alpha = 0.55f),
                                    startX = start,
                                    endX = size.width,
                                ),
                                topLeft = Offset(start, 0f),
                                size = androidx.compose.ui.geometry.Size(end - start, size.height),
                            )
                        }
                },
            ),
    )
}

/**
 * The ◀ left of the focused icon while drilled in with [DrillDownStyle.CHEVRON_COLLAPSE]. Takes
 * no room: it is drawn [reach] past the icon slot's left edge, plus a small gap.
 */
@Composable
private fun BoxScope.BackChevron(size: TextUnit, reach: Dp, alpha: () -> Float) {
    Text(
        "◀",
        style = shadowed(size, weight = FontWeight.Bold),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .align(Alignment.CenterStart)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                val x = -p.width - reach.roundToPx() - 6.dp.roundToPx()
                // Its own size (the slot is fixed-size, so it takes no room), drawn left of the slot.
                layout(p.width, p.height) { p.place(x, 0) }
            }
            // Inside the placement, so the node's bounds are where the ◀ is drawn.
            .semantics { contentDescription = "Back" }
            .fadeAlpha(alpha),
    )
}

// endregion

// region Icons and text

/**
 * A theme icon: glossy white-to-silver gradient fill, soft drop shadow, and an animated halo
 * when focused. The shadow and halo blur on Android 12+; older versions use offset copies and
 * a radial gradient, which read the same at handheld sizes.
 */
@Composable
private fun XmbIcon(
    painter: Painter,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    /** A wide icon (a console logo) is drawn [width] x [height], centered on the [size] slot. */
    width: Dp = size,
    height: Dp = size,
    /** The white-to-silver fill; false keeps the icon's own colors. */
    gloss: Boolean = true,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val frame = LocalFocusFrame.current
    val accent = LocalThemeAccent.current
    val neon = frame.style == FocusFrameStyle.STATIC_NEON
    val wide = width > height
    Box(
        Modifier
            .requiredSize(width, height)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            },
    ) {
        // Halo (2b.4d: white and breathing, or the neon / gold frame's color held steady).
        if (frame.style != FocusFrameStyle.HARD_SHADOW) {
            // Drawn as a circle across the icon's width, then squashed to an ellipse for a wide logo.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(width)
                    .graphicsLayer {
                        alpha = glow()
                        val spread = if (neon) 1.6f else 1.9f
                        scaleX = if (wide) (if (neon) 1.15f else 1.25f) else spread
                        scaleY = spread * (height / width)
                    }
                    .drawBehind {
                        val c = frame.bloomColor(accent) ?: return@drawBehind
                        val core = if (neon) 0.6f else 0.42f
                        drawCircle(
                            Brush.radialGradient(
                                0f to c.copy(alpha = core),
                                0.45f to c.copy(alpha = core / 3f),
                                1f to Color.Transparent,
                            ),
                        )
                    },
            )
            if (canBlur) {
                val tint = when (frame.style) {
                    FocusFrameStyle.STATIC_NEON -> frame.neon
                    FocusFrameStyle.GOLD_BEZEL -> frame.gold
                    else -> Color.White
                }
                BlurredCopy(painter, width, height, tint, blur = if (neon) 3.dp else 6.dp, alpha = { glow() * 0.9f })
            }
        } else {
            // A solid copy 2 dp down and right, no blur.
            BlurredCopy(painter, width, height, Color.Black, blur = 0.dp, alpha = { glow() * 0.85f }, offset = IntOffset(2, 2))
        }
        // Drop shadow.
        BlurredCopy(
            painter,
            width,
            height,
            Color.Black,
            blur = if (canBlur) 2.5.dp else 0.dp,
            alpha = { 0.38f },
            offset = IntOffset(1, 2),
        )
        // Glossy body (or the logo's own colors).
        Image(
            painter,
            contentDescription = null,
            modifier = if (!gloss) Modifier.fillMaxSize() else Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(IconTop, IconBottom)), blendMode = BlendMode.SrcIn)
                },
        )
    }
}

/**
 * A tinted, blurred copy of an icon. The blur runs in a layer twice the icon's size so the
 * spread is never clipped into a visible rectangle at the icon's bounds.
 */
@Composable
private fun BlurredCopy(
    painter: Painter,
    width: Dp,
    height: Dp,
    tint: Color,
    blur: Dp,
    alpha: () -> Float,
    offset: IntOffset = IntOffset.Zero,
) {
    Box(
        Modifier
            .wrapContentSize(unbounded = true)
            .requiredSize(width * 2, height * 2)
            .offset { IntOffset((offset.x * density).roundToInt(), (offset.y * density).roundToInt()) }
            .graphicsLayer { this.alpha = alpha() }
            .then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(width, height))
    }
}

/**
 * Scraped box art as a game's icon. The platform glyph shows until the art is decoded, then the
 * art crossfades in over it (and the glyph out), so a row never pops.
 */
@Composable
private fun ArtIcon(
    icon: NodeIcon.Art,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    loadArt: suspend (String, List<ArtKind>, Int) -> ImageBitmap?,
) {
    val px = with(LocalDensity.current) { (size * focusScale * 1.4f).roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, icon.uuid, icon.version) { value = loadArt(icon.uuid, icon.kinds, px) }
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(320), label = "art")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (shown < 1f) {
            Box(Modifier.fadeAlpha { 1f - shown }) {
                XmbIcon(glyphPainter(icon.fallback), size, focus, focusScale, glow)
            }
        }
        val image = bitmap ?: return@Box
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + (focusScale - 1f) * focus()
                    scaleX = s
                    scaleY = s
                    alpha = shown
                },
            contentAlignment = Alignment.Center,
        ) {
            val frame = LocalFocusFrame.current
            val accent = LocalThemeAccent.current
            if (frame.style == FocusFrameStyle.BREATHING_HALO || frame.style == FocusFrameStyle.STATIC_NEON) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = glow(); scaleX = 1.6f; scaleY = 1.6f }
                        .drawBehind {
                            val c = frame.bloomColor(accent) ?: return@drawBehind
                            drawCircle(Brush.radialGradient(0f to c.copy(alpha = 0.3f), 1f to Color.Transparent))
                        },
                )
            }
            // Exactly the glyph's footprint, so tall capsules and wide boxes never reach the
            // neighbouring rows (the focus scale is applied by the layer above, as for glyphs).
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size)
                    .then(if (frame.framesPlates) Modifier.drawBehind { fittedFrame(frame, accent, glow(), image.width, image.height) } else Modifier)
                    .shadow(6.dp, RoundedCornerShape(3.dp), clip = false),
            )
        }
    }
}

/**
 * Photo/video thumbnail or album art: a rounded, bordered tile like the reference themes' game
 * icons. Shows the glyph until the image loads (or if there is none).
 */
@Composable
private fun ThumbIcon(
    icon: NodeIcon.Thumb,
    size: Dp,
    /** Width over height of the thumbnail ([ItemRow] resolves the theme's tile aspect). */
    aspect: Float,
    focus: () -> Float,
    focusScale: Float,
    glow: () -> Float,
    loadThumb: suspend (ThumbKind, Long, Int) -> ImageBitmap?,
) {
    val px = with(LocalDensity.current) { (size * focusScale).roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, icon) { value = loadThumb(icon.kind, icon.id, px) }
    val image = bitmap
    if (image == null) {
        XmbIcon(glyphPainter(icon.fallback), size, focus, focusScale, glow)
        return
    }
    // Wide thumbnails keep the row height; portrait ones keep the slot height and narrow.
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * focus()
                scaleX = s
                scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        val frame = LocalFocusFrame.current
        val accent = LocalThemeAccent.current
        if (frame.style == FocusFrameStyle.BREATHING_HALO || frame.style == FocusFrameStyle.STATIC_NEON) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glow(); scaleX = 1.7f; scaleY = 1.7f }
                    .drawBehind {
                        val c = frame.bloomColor(accent) ?: return@drawBehind
                        drawCircle(Brush.radialGradient(0f to c.copy(alpha = 0.35f), 1f to Color.Transparent))
                    },
            )
        }
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = size * aspect, height = size)
                .wrapContentSize(unbounded = true)
                .then(if (frame.framesPlates) Modifier.drawBehind { drawPlateFrame(frame, accent, glow(), 4.dp.toPx()) } else Modifier)
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * [drawPlateFrame] around an image of [imageW] × [imageH] drawn with [ContentScale.Fit] in this
 * scope (centered), so the frame hugs the art rather than the square slot.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.fittedFrame(frame: FocusFrame, accent: ThemeAccent, glow: Float, imageW: Int, imageH: Int) {
    if (glow <= 0f || imageW <= 0 || imageH <= 0) return
    val k = minOf(size.width / imageW, size.height / imageH)
    val w = imageW * k
    val h = imageH * k
    translate((size.width - w) / 2, (size.height - h) / 2) {
        drawPlateFrame(frame, accent, glow, 3.dp.toPx(), androidx.compose.ui.geometry.Size(w, h))
    }
}

/** Button hints for media screens; @confirm/@back/@options become the controller's symbols. */
@Composable
private fun OverlayHints(pairs: List<Pair<String, String>>, state: XmbUiState, size: TextUnit) {
    val confirm = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .hintBar()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        for ((glyph, label) in pairs) {
            val g = when (glyph) {
                "@confirm" -> confirm
                "@options" -> state.glyphs.north
                else -> glyph
            }
            Hint(g, label, size)
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    size: Dp,
    focus: () -> Float,
    focusScale: Float,
    loadAppIcon: suspend (String) -> ImageBitmap?,
) {
    // Keyed on the loader too: it changes with the theme's icon mask and filter (2b.4d).
    val bitmap by produceState<ImageBitmap?>(null, packageName, loadAppIcon) { value = loadAppIcon(packageName) }
    bitmap?.let {
        Image(
            it,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + (focusScale - 1f) * focus()
                    scaleX = s
                    scaleY = s
                },
        )
    }
}

/**
 * Partial opacity for content whose glow, halo or text shadow spills past its own bounds.
 *
 * A plain `graphicsLayer { alpha = … }` below 1 renders into an offscreen buffer the size of
 * the layer, which cuts the spill off at the layer's edges. On a category cell or row that is
 * fading in, that shows up as a lighter box around the icon until the alpha reaches exactly 1.
 * Modulating each draw call's alpha instead never clips.
 */
internal fun Modifier.fadeAlpha(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun shadowed(size: TextUnit, color: Color = TextBright, weight: FontWeight = FontWeight.Normal) = TextStyle(
    color = color,
    fontSize = size,
    fontWeight = weight,
    fontFamily = LocalThemeTypography.current.family,
    shadow = Shadow(DropShadow, Offset(0f, 3f), blurRadius = 6f),
)

/**
 * White text with a dark drop shadow, plus a second pass rendered as a soft white bloom whose
 * opacity follows [glow]. Only the bloom layer's alpha animates, so the pulse never re-lays-out
 * text.
 *
 * A title too long for its width ends in an ellipsis ([TextOverflowMode.ELLIPSIS]), fades out at
 * the edge ([TextOverflowMode.EDGE_FADE]), or, while [marquee] (the focused row), scrolls a few
 * times and stops ([TextOverflowMode.MARQUEE]; other rows use the ellipsis).
 */
@Composable
internal fun GlowText(
    text: String,
    fontSize: TextUnit,
    glow: () -> Float,
    modifier: Modifier = Modifier,
    /** A font role other than body (the category bar uses `display`). */
    fontFamily: androidx.compose.ui.text.font.FontFamily? = LocalThemeTypography.current.family,
    overflow: TextOverflowMode = TextOverflowMode.ELLIPSIS,
    marquee: Boolean = false,
) {
    val mode = if (overflow == TextOverflowMode.MARQUEE && !marquee) TextOverflowMode.ELLIPSIS else overflow
    val cut = if (mode == TextOverflowMode.ELLIPSIS) TextOverflow.Ellipsis else TextOverflow.Clip
    var overflowing by remember { mutableStateOf(false) }
    val clipped = when (mode) {
        TextOverflowMode.ELLIPSIS -> Modifier
        TextOverflowMode.MARQUEE -> Modifier.bleed(GlowBleed).basicMarquee(iterations = 3, initialDelayMillis = 1200, repeatDelayMillis = 1600)
        TextOverflowMode.EDGE_FADE -> Modifier.bleed(GlowBleed).edgeFade { overflowing }
    }
    Box(modifier.then(clipped)) {
        // The clipping modes draw inside a padded box so the bloom isn't cut at the text's edges.
        Box(if (mode == TextOverflowMode.ELLIPSIS) Modifier else Modifier.padding(start = GlowBleed, top = GlowBleed, bottom = GlowBleed, end = if (mode == TextOverflowMode.MARQUEE) GlowBleed else 0.dp)) {
            GlowBloom(text, fontSize, fontFamily, mode, cut, glow)
            Text(
                text,
                style = shadowed(fontSize).copy(fontFamily = fontFamily),
                maxLines = 1,
                softWrap = mode == TextOverflowMode.ELLIPSIS,
                overflow = cut,
                onTextLayout = if (mode == TextOverflowMode.EDGE_FADE) ({ overflowing = it.hasVisualOverflow }) else NoLayoutCallback,
            )
        }
    }
}

private val NoLayoutCallback: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {}

/**
 * The bloom copy behind a [GlowText]: its color is [ThemeAccent.haloTarget] (white unless game
 * art, a quick profile or the battery tint colors it), switched at once rather than morphed, since
 * each new shadow color blurs the text again. Its own composable, so a color change recomposes
 * this one text and nothing around it.
 */
@Composable
private fun GlowBloom(
    text: String,
    fontSize: TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    mode: TextOverflowMode,
    cut: TextOverflow,
    glow: () -> Float,
) {
    val halo = LocalThemeAccent.current.haloTarget
    Text(
        text,
        style = TextStyle(
            color = halo.copy(alpha = 0.55f),
            fontSize = fontSize,
            fontFamily = fontFamily,
            shadow = Shadow(halo, Offset.Zero, blurRadius = 18f),
        ),
        maxLines = 1,
        softWrap = mode == TextOverflowMode.ELLIPSIS,
        overflow = cut,
        modifier = Modifier
            .clearAndSetSemantics { } // decorative copy; screen readers read the text once
            .graphicsLayer {
                alpha = glow()
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    )
}

/** Room for the title bloom inside a clipping text box. */
private val GlowBleed = 8.dp

/**
 * Lets the content extend [amount] past this box's start, top and bottom without taking that room:
 * the box reports its size minus the padding, so neighbours lay out as if it weren't there.
 */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val pad = amount.roundToPx()
    val p = measurable.measure(
        constraints.copy(
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + pad else constraints.maxWidth,
            minWidth = 0,
            minHeight = 0,
        ),
    )
    layout((p.width - pad).coerceAtLeast(0), (p.height - pad * 2).coerceAtLeast(0)) { p.place(-pad, -pad) }
}

/** Fades the last stretch of the box to transparent while [active] (the text doesn't fit). */
private fun Modifier.edgeFade(active: () -> Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = if (active()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithContent {
        drawContent()
        if (!active()) return@drawWithContent
        val fade = size.height.coerceAtMost(size.width / 3)
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f),
            size = androidx.compose.ui.geometry.Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
    }

// endregion

// region Chrome

@Composable
private fun ButtonHints(state: XmbUiState, size: TextUnit, modifier: Modifier) {
    val confirmGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.east else state.glyphs.south
    val backGlyph = if (state.confirm == ConfirmLayout.EAST) state.glyphs.south else state.glyphs.east
    val focused = state.focused
    if (focused == null || (focused.action == NodeAction.None && state.depth == 0 && focused.menu.isEmpty())) return
    // Backed so it stays legible where long titles run underneath on narrow (4:3, 1:1) screens.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .hintBar()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .graphicsLayer { alpha = 0.85f },
    ) {
        if (focused.action != NodeAction.None) Hint(confirmGlyph, "Select", size)
        if (state.depth > 0) Hint(backGlyph, "Back", size)
        if (focused.menu.isNotEmpty()) Hint(state.glyphs.north, "Options", size)
    }
}

/**
 * One button hint: [glyph] on a pill, then [label]. A face label of the set on screen
 * ([LocalGlyphs]) takes the set's style: an Xbox letter its color, a minimal glyph an outlined pill.
 */
@Composable
internal fun Hint(glyph: String, label: String, size: TextUnit) {
    val glyphs = LocalGlyphs.current
    val face = if (glyphs.style == GlyphStyle.PLAIN) null else glyphs.faceOf(glyph)
    val shape = RoundedCornerShape(50)
    val pill = Modifier
        .clip(shape)
        .then(
            if (face != null && glyphs.style == GlyphStyle.OUTLINE) Modifier.border(1.dp, Color(0xCCFFFFFF), shape)
            else Modifier.background(Pill),
        )
    val color = face?.let { glyphColor(glyphs.style, it) } ?: TextBright
    Row(verticalAlignment = Alignment.CenterVertically) {
        val drawn = FaceShape.of(glyph)
        if (drawn != null) {
            // Shapes are drawn, not set in the font, so all four are the same size and centered;
            // the pill keeps the height a text label's would have.
            val line = with(LocalDensity.current) { (size * 1.17f).toDp() }
            Box(contentAlignment = Alignment.Center, modifier = pill.padding(horizontal = 5.dp, vertical = 2.dp).size(line)) {
                FaceShapeGlyph(drawn, glyph, line * 0.72f, color)
            }
        } else {
            Text(
                glyph,
                style = shadowed(size, color = color, weight = FontWeight.Bold),
                modifier = pill.padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = shadowed(size), maxLines = 1, softWrap = false)
    }
}

/** The color of [face]'s label in [style]: the Xbox letters' own colors, white otherwise. */
internal fun glyphColor(style: GlyphStyle, face: Face): Color = when (style) {
    GlyphStyle.XBOX_COLORS -> when (face) {
        Face.SOUTH -> Color(0xFF6CC24A) // A
        Face.EAST -> Color(0xFFF2554B) // B
        Face.WEST -> Color(0xFF4A9BF5) // X
        Face.NORTH -> Color(0xFFF5C82E) // Y
    }
    else -> TextBright
}

@Composable
private fun RemapOverlay(session: RemapSession, m: XmbMetrics, onCancel: () -> Unit) {
    val density = LocalDensity.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB0000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val device = session.controller?.name?.ifBlank { null }
                ?: if (session.deviceChosen) "Default mapping" else "Press a button on the controller to remap"
            Text("Remap buttons  ${session.progress}  ·  $device", style = shadowed(with(density) { m.subtitleSize.toSp() }, TextDim))
            Spacer(Modifier.height(10.dp))
            GlowText(
                "Press the button for: " + (session.target?.let(RemapSession::label) ?: ""),
                fontSize = with(density) { (m.titleSize * 1.3f).toSp() },
                glow = { 0.8f },
            )
            Spacer(Modifier.height(14.dp))
            Text("Tap the screen to cancel", style = shadowed(with(density) { m.subtitleSize.toSp() }, TextDim))
        }
    }
}

/** Game start transition: a bright bloom washes over the screen while the start cue plays. */
/**
 * The start transition (2b.5d, `effects.launchCurtain`), timed to the 1100 ms hand-off while the
 * `gameboot` cue plays: [LaunchCurtain.BLOOM] a glow over the dimmed menu, [LaunchCurtain.FADE_BLACK]
 * the menu fading to black, [LaunchCurtain.IRIS] a circle closing on the focused tile, and
 * [LaunchCurtain.FLASH] a white flash falling to black. The game's title glows in over each.
 * [still] (E-Ink) is black at once.
 */
@Composable
private fun GameBootOverlay(title: String, palette: ThemePalette, m: XmbMetrics, curtain: LaunchCurtain, still: Boolean) {
    val progress = remember(title) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(title) {
        if (dev.xross.launcher.BuildConfig.PERF_LOG) {
            android.util.Log.i("XrossBoot", "curtain=${if (still) "BLACK" else curtain.name} title=$title")
        }
        if (!still) progress.animateTo(1f, tween(LaunchCurtainTiming.CLOSE_MS, easing = if (curtain == LaunchCurtain.IRIS) LinearEasing else FastOutSlowInEasing))
    }
    val density = LocalDensity.current
    val focus = Offset(m.anchorX, m.focusedY)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress.value
                when {
                    still -> drawRect(Color.Black)
                    curtain == LaunchCurtain.BLOOM -> {
                        // Fade the menu out first so only the bloom and the game title remain.
                        drawRect(Color.Black.copy(alpha = 0.92f * (p * 1.6f).coerceAtMost(1f)))
                        drawCircle(
                            Brush.radialGradient(
                                0f to palette.glowColor.copy(alpha = 0.85f * p),
                                0.6f to palette.glowColor.copy(alpha = 0.25f * p),
                                1f to Color.Transparent,
                                center = center,
                                radius = size.maxDimension * (0.2f + 0.6f * p),
                            ),
                            radius = size.maxDimension,
                        )
                    }
                    curtain == LaunchCurtain.FADE_BLACK -> drawRect(Color.Black.copy(alpha = p))
                    curtain == LaunchCurtain.IRIS -> {
                        val r = LaunchCurtainTiming.irisRadius(p, focus, size)
                        val hole = Path().apply { addOval(Rect(focus, r)) }
                        clipPath(hole, ClipOp.Difference) { drawRect(Color.Black) }
                        if (r <= 0.5f) drawRect(Color.Black)
                    }
                    else -> {
                        // FLASH: white up to full in the first part, then falling into black.
                        val (white, black) = LaunchCurtainTiming.flash(p)
                        drawRect(Color.Black.copy(alpha = black))
                        drawRect(Color.White.copy(alpha = white))
                    }
                }
            },
    ) {
        GlowText(
            title,
            fontSize = with(density) { (m.titleSize * 1.4f).toSp() },
            glow = { 1f },
            modifier = Modifier.graphicsLayer { alpha = LaunchCurtainTiming.titleAlpha(curtain, progress.value, still) },
        )
    }
}

/** The launch curtains' shapes over their progress (0 → 1 over [CLOSE_MS]), pure for tests. */
internal object LaunchCurtainTiming {
    /** The curtain closes over this; the hand-off to the emulator is at 1100 ms. */
    const val CLOSE_MS = 900

    /** IRIS: the circle's radius around [focus], from past the farthest corner to 0. */
    fun irisRadius(p: Float, focus: Offset, size: androidx.compose.ui.geometry.Size): Float {
        val far = maxOf(
            (focus - Offset.Zero).getDistance(), (focus - Offset(size.width, 0f)).getDistance(),
            (focus - Offset(0f, size.height)).getDistance(), (focus - Offset(size.width, size.height)).getDistance(),
        )
        return (far * (1f - p.coerceIn(0f, 1f))).coerceAtLeast(0f)
    }

    /** FLASH: (white alpha, black alpha): white peaks at 15%, then gives way to black by 70%. */
    fun flash(p: Float): Pair<Float, Float> {
        val q = p.coerceIn(0f, 1f)
        val white = if (q < 0.15f) q / 0.15f else (1f - (q - 0.15f) / 0.55f).coerceIn(0f, 1f)
        val black = ((q - 0.1f) / 0.3f).coerceIn(0f, 1f)
        return white to black
    }

    /** The title glows in as the curtain closes (after the flash, as the iris shuts: not over the rows still showing). */
    fun titleAlpha(curtain: LaunchCurtain, p: Float, still: Boolean): Float = when {
        still -> 1f
        curtain == LaunchCurtain.FLASH -> ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        curtain == LaunchCurtain.IRIS -> ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
        else -> p
    }
}

// endregion

/**
 * Touch parity: a horizontal swipe moves exactly one category (like a D-pad press); a vertical
 * drag scrolls items continuously, one item per [step] of finger travel.
 */
private fun Modifier.swipeNavigation(onAction: (NavAction) -> Unit): Modifier = pointerInput(Unit) {
    val step = 56.dp.toPx()
    val swipe = 48.dp.toPx()
    var dx = 0f
    var dy = 0f
    var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
    var categoryMoved = false
    detectDragGestures(
        onDragStart = { dx = 0f; dy = 0f; axis = 0; categoryMoved = false },
        onDrag = { change, amount ->
            change.consume()
            dx += amount.x
            dy += amount.y
            if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
            if (axis == 1) {
                if (!categoryMoved && abs(dx) >= swipe) {
                    onAction(if (dx < 0) NavAction.RIGHT else NavAction.LEFT)
                    categoryMoved = true
                }
            } else {
                while (dy <= -step) { onAction(NavAction.DOWN); dy += step }
                while (dy >= step) { onAction(NavAction.UP); dy -= step }
            }
        },
    )
}
