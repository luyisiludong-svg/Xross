package dev.xross.launcher

import androidx.compose.ui.graphics.toArgb
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.xross.launcher.input.GamepadHandler
import dev.xross.launcher.input.InputSink
import dev.xross.launcher.input.NavAction
import dev.xross.launcher.launch.LaunchResult
import dev.xross.launcher.state.InactivityTracker
import dev.xross.launcher.state.MediaOverlay
import dev.xross.launcher.state.UiEffect
import dev.xross.launcher.storage.VolumeMonitor
import dev.xross.launcher.state.XmbViewModel
import dev.xross.launcher.theme.XrossTheme
import dev.xross.launcher.theme.rememberActivePalette
import dev.xross.launcher.ui.XmbScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Indices of [pack]'s battery rules that hold at [b]; equal sets mean the same look. */
private fun batteryRulesHeld(pack: dev.xross.launcher.theme.pack.LoadedTheme, b: dev.xross.launcher.theme.BatteryLevel?): List<Int> =
    if (b == null) emptyList() else pack.rules.filter { r -> r.battery?.holds(b) == true }.map { it.index }

class MainActivity : ComponentActivity() {

    private val container get() = (application as XrossApplication).container

    private val viewModel: XmbViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as XrossApplication
                val coldBoot = app.coldBootPending
                app.coldBootPending = false
                XmbViewModel(applicationContext, container, coldBoot)
            }
        }
    }

    private lateinit var gamepad: GamepadHandler

    /** No input for a few seconds: the wave drops to a standby rate and the glows hold still. */
    private val inactivity = InactivityTracker(now = android.os.SystemClock::uptimeMillis)

    /** No input for 10 s: the foreground particles (2b.4e) settle and stop. */
    private val particleInactivity = InactivityTracker(
        timeoutMs = dev.xross.launcher.ui.ParticleRest.IDLE_AFTER_MS,
        now = android.os.SystemClock::uptimeMillis,
    )

    /** Any key, stick or touch input: wakes both idle timers. */
    private fun noteInput() {
        inactivity.onInput()
        particleInactivity.onInput()
    }

    /** When the gamepad handler last consumed a KEYCODE_BACK (see the back callback). */
    private var lastBackKeyAt = 0L

    /** Decoded achievement badges (downloaded once into Xross/media/ra/badges). */
    private val badgeImages by lazy { dev.xross.launcher.profile.BadgeImages({ name -> container.achievements.badgeFile(name) }) }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onFolderPicked(it)
    }

    private val pickDatFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onDatFolderPicked(it)
    }

    /** Settings ▸ Theme ▸ Import theme: `.xtheme` files have no registered type, so octet-stream too. */
    private val pickThemeArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.onThemeArchivePicked(it)
    }

    /** Settings ▸ Theme ▸ Xross wallpaper ▸ Pick a picture (2b.4f): a document, so its read grant can persist. */
    private val pickWallpaperImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        viewModel.onWallpaperPicked(it)
    }

    private val pickBaseRomsFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onBaseRomsFolderPicked(it)
    }

    /** Which tab the media folder picker is choosing for; survives only while the picker is up. */
    private var pendingMediaFolder = dev.xross.launcher.state.MediaFolderKind.PHOTO

    private val pickMediaFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        viewModel.onMediaFolderPicked(pendingMediaFolder, it)
    }

    /** Android 9–10: read for the library, write for the portable Xross folder. */
    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshEnvironment()
        }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = viewModel.onAppsChanged()
    }

    /** Storage hot-swap: microSD and USB-OTG mounts/removals. */
    private val volumeMonitor = VolumeMonitor { viewModel.onVolumeEvent(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // Every nav action, including held-stick repeats, counts as input for the idle timer.
        val sink = object : InputSink by viewModel {
            override fun onAction(action: NavAction) {
                noteInput()
                viewModel.onAction(action)
            }
        }
        gamepad = GamepadHandler(lifecycleScope, sink) { viewModel.inputConfig }

        // System back (gesture / nav bar). Gamepad back buttons arrive via dispatchKeyEvent.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // With predictive back (target SDK 36 on Android 16), a KEYCODE_BACK key that the
                // gamepad handler already acted on also reaches this callback: skip that echo.
                if (android.os.SystemClock.uptimeMillis() - lastBackKeyAt < BACK_ECHO_MS) return
                viewModel.onAction(NavAction.BACK)
            }
        })

        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        volumeMonitor.register(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect(::handleEffect)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { inactivity.run() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { particleInactivity.run() }
        }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val pack by container.themes.active.collectAsStateWithLifecycle()
            // The theme pack's font for every launcher text style.
            androidx.compose.runtime.LaunchedEffect(pack) {
                fun family(t: android.graphics.Typeface?) = t?.let { androidx.compose.ui.text.font.FontFamily(it) }
                dev.xross.launcher.ui.ThemeTypography.family = family(pack.fonts.body)
                dev.xross.launcher.ui.ThemeTypography.display = family(pack.fonts.display)
                dev.xross.launcher.ui.ThemeTypography.mono = family(pack.fonts.mono)
            }
            // The pack's look here (Appendix A.4): its variant, then the open category's override,
            // then the focused game's system override (once the cursor rests, so scrolling a mixed
            // list doesn't flicker). The user's settings (theme color, surface style) win over it.
            val categoryId = state.categoryId
            val system = dev.xross.launcher.theme.rememberSettledSystem(state.focusedSystem, categoryId)
            // Battery and clock for the pack's context rules: read only when it has such rules, and
            // passed on only when the set of rules that hold changes (not on every percent or minute).
            val ruleBattery by androidx.compose.runtime.remember(pack) {
                viewModel.battery
                    .map { b -> b.takeIf { pack.usesBattery } }
                    .distinctUntilChanged { a, b -> batteryRulesHeld(pack, a) == batteryRulesHeld(pack, b) }
            }.collectAsStateWithLifecycle(initialValue = null)
            val ruleMinute by dev.xross.launcher.theme.rememberMinuteOfDay(active = pack.usesClock, key = pack) { m ->
                pack.rules.filter { r -> r.time?.contains(m) == true }.map { it.index }
            }
            // The whole cascade (pack, variant, overrides, rules, the user's picks, the quick
            // profile) in one pure step: see ThemeLookResolver.
            val st = state.settings
            val userEffects = st.effectOverrides
            val profile = dev.xross.launcher.theme.QuickProfile.byName(st.quickProfile)
            val lookInputs = dev.xross.launcher.theme.LookInputs(
                pack = pack,
                variant = st.themeVariant,
                category = categoryId,
                system = system,
                battery = ruleBattery,
                minuteOfDay = ruleMinute,
                effects = userEffects,
                surfaceStyle = st.surfaceStyle,
                profile = profile,
                circadian = st.circadianCurve,
                batteryTint = st.batteryTint,
                adaptive = st.adaptiveGamePalette,
            )
            val resolved = androidx.compose.runtime.remember(lookInputs) { dev.xross.launcher.theme.ThemeLookResolver.resolve(lookInputs) }
            val look = resolved.look
            // The pack's programs plus the user's background shader, all compiled ahead of time.
            val shaderLibrary = androidx.compose.runtime.remember(pack, userEffects.shader) { pack.shaderLibrary(userEffects.shader) }
            if (BuildConfig.PERF_LOG) {
                androidx.compose.runtime.LaunchedEffect(look.shader, look.cosmetics) {
                    val c = look.cosmetics
                    android.util.Log.i(
                        "XrossEffects",
                        "shader=${look.shader.preset} bgFilter=${c.bgFilter} uiFilter=${c.screenFilter.filter} frame=${c.frame} " +
                            "particles=${c.particleField} mask=${c.mask} user=${!userEffects.empty}",
                    )
                }
            }
            val layout = resolved.layout
            // Sound levels follow the look too (variant, category, system, rules: 2b.5a).
            androidx.compose.runtime.LaunchedEffect(pack, look.gains) { container.sounds.useLook(pack, look) }
            // So does the theme music loop (a category override can give Games its own, 2b.5c).
            androidx.compose.runtime.LaunchedEffect(pack, look.bgm) { container.audio.onBgmTrack(pack.bgmTrack(look.bgm)) }
            // Read in the draw phase only, so a category or system switch redraws without recomposing.
            androidx.compose.runtime.LaunchedEffect(resolved.surface) {
                dev.xross.launcher.ui.ThemeSurfaces.active = resolved.surface
            }
            var backdropCovers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            // Cold-start intro (2b.5d): the black, the boot video or the shader ramp, then the menu.
            val bootReveal = dev.xross.launcher.ui.rememberBootReveal(state.coldBoot, state.bootPending, state.boot)
            var bootCovers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            // Resting: no input for a few seconds and nothing playing. Music and video snaps keep
            // the screen awake (the mini player and the snap are live content).
            val noInput by inactivity.idle.collectAsStateWithLifecycle()
            val music by container.music.state.collectAsStateWithLifecycle()
            val snapPlaying by container.snaps.player.collectAsStateWithLifecycle()
            val uiIdle = noInput && !music.isPlaying && snapPlaying == null
            // The particles rest on input alone (music doesn't keep them going), after 10 s.
            val particlesIdle by particleInactivity.idle.collectAsStateWithLifecycle()
            androidx.compose.runtime.CompositionLocalProvider(
                dev.xross.launcher.ui.LocalThemePack provides pack,
                dev.xross.launcher.ui.LocalThemeLook provides look,
                dev.xross.launcher.ui.LocalUiIdle provides uiIdle,
                dev.xross.launcher.ui.LocalThemeLayout provides layout,
                dev.xross.launcher.ui.LocalStillMotion provides resolved.stillMotion,
            ) {
            Box(Modifier.fillMaxSize()) {
                // The wave is hidden (and its GL loop stopped) under full-screen photos and video,
                // and paused while focused-game art or a video snap covers it.
                val fullscreenMedia = state.overlay is MediaOverlay.Photos || state.overlay is MediaOverlay.VideoPlayback
                val s = state.settings
                val base = rememberActivePalette(state.theme, look.palette)
                val wavePalette = androidx.compose.runtime.remember(base, s.waveTint, s.waveColors) {
                    dev.xross.launcher.theme.wavePalette(base, dev.xross.launcher.theme.WaveTint.byName(s.waveTint), s.waveColors)
                }
                val switches = resolved.switches
                // Game-art palette: the focused game's (or the Details screen's) art, debounced 250 ms.
                val artKey = dev.xross.launcher.ui.adaptiveArtKey(state)
                androidx.compose.runtime.LaunchedEffect(artKey, switches.adaptive) {
                    container.gamePalette.focus(artKey.takeIf { switches.adaptive })
                }
                dev.xross.launcher.ui.AdaptiveWave(
                    themed = wavePalette,
                    profile = profile,
                    switches = switches,
                    art = container.gamePalette.colors,
                    battery = viewModel.battery,
                    settings = s,
                    enabled = state.waveEnabled && !fullscreenMedia,
                    paused = (backdropCovers && state.waveEditor == null) || bootCovers,
                    idle = uiIdle,
                    shaders = shaderLibrary,
                    shader = shaderLibrary.keyOf(look.shader),
                    category = categoryId,
                    health = container.shaderHealth,
                    audio = container.music.energy,
                    filter = look.cosmetics.bgFilter,
                    intro = state.boot?.takeIf { it.plan.mode == dev.xross.launcher.ui.BootMode.SHADER }?.let {
                        dev.xross.launcher.ui.WaveIntro(it.startedAtNanos, dev.xross.launcher.ui.BootSequence.SHADER_MS, it.endedAtNanos)
                    },
                )
                // Focus frame and app-icon look (2b.4d): the look's, colored by the active palette.
                val cosmetics = look.cosmetics
                val focusFrame = androidx.compose.runtime.remember(cosmetics.frame, base, look.focusGold) {
                    dev.xross.launcher.ui.focusFrameOf(cosmetics.frame, base, look.focusGold)
                }
                val iconStyle = androidx.compose.runtime.remember(cosmetics.mask, cosmetics.filter, base) {
                    dev.xross.launcher.data.IconStyle.of(cosmetics.mask, cosmetics.filter, canvas = base.top, accent = base.glow)
                }
                androidx.compose.runtime.LaunchedEffect(focusFrame, iconStyle) {
                    if (BuildConfig.PERF_LOG) {
                        android.util.Log.i(
                            "XrossCosmetics",
                            "frame=${focusFrame.style} mask=${iconStyle.mask} filter=${iconStyle.filter} " +
                                "gold=#%06X".format(focusFrame.gold.toArgb() and 0xFFFFFF),
                        )
                    }
                }
                // Remembered per style: rows reload their icon only when the style changes.
                val loadAppIcon: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? =
                    androidx.compose.runtime.remember(iconStyle) { { pkg -> container.apps.icon(pkg, ICON_PX, iconStyle) } }
                androidx.compose.runtime.CompositionLocalProvider(dev.xross.launcher.ui.LocalFocusFrame provides focusFrame) {
                XmbScreen(
                    state = state,
                    loadAppIcon = loadAppIcon,
                    loadThumb = { kind, id, px -> container.media.thumbnail(kind, id, px) },
                    loadArt = { uuid, kinds, px -> container.art.load(uuid, kinds, px) },
                    media = viewModel.media,
                    music = container.music,
                    onAction = viewModel::onAction,
                    onCategoryTap = viewModel::onCategoryTapped,
                    onItemTap = viewModel::onItemTapped,
                    onCancelRemap = viewModel::cancelRemap,
                    onItemLongPress = viewModel::onItemLongPressed,
                    onOptionTap = viewModel::onOptionTapped,
                    onCloseOptions = viewModel::closeOptions,
                    onSearchKeyTap = viewModel::onSearchKeyTapped,
                    onSearchResultTap = viewModel::onSearchResultTapped,
                    onNameKeyTap = viewModel::onNameKeyTapped,
                    onInfoRowTap = viewModel::onInfoRowTapped,
                    onCloseInfo = viewModel::closeInfo,
                    snap = container.snaps,
                    videoFile = { uuid ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            container.assets.file(uuid, dev.xross.launcher.scrape.ArtKind.VIDEO)
                        }
                    },
                    onPlayDetails = { viewModel.onAction(NavAction.CONFIRM) },
                    onCloseDetails = viewModel::closeDetails,
                    onAvatarTap = viewModel::onAvatarTapped,
                    onProfileCardButtonTap = viewModel::onProfileCardButtonTapped,
                    onCloseProfileCard = viewModel::closeProfileCard,
                    onMiniPlayerTap = viewModel::onMiniPlayerTapped,
                    onMiniPlayerLongPress = viewModel::onMiniPlayerLongPressed,
                    loadBadge = { name, px -> badgeImages.load(name, px) },
                    onBackdropCoverChanged = { backdropCovers = it },
                    wallpaper = androidx.compose.runtime.remember(state.settings.customWallpaper, pack, look) {
                        dev.xross.launcher.theme.WallpaperSource.resolve(state.settings.customWallpaper, pack, look)
                    },
                    packWallpaper = androidx.compose.runtime.remember(pack, look) {
                        dev.xross.launcher.theme.WallpaperSource.packWallpaper(pack, look)
                    },
                    loadWallpaper = { source, px -> container.wallpapers.load(source, px) },
                    onWaveRowTap = viewModel::onWaveRowTapped,
                    onWaveSlider = viewModel::onWaveSliderChanged,
                    onCloseWaveSettings = viewModel::closeWaveSettings,
                    onAchievementTap = viewModel::onAchievementTapped,
                    onCloseAchievements = viewModel::closeAchievements,
                    onDetailsAchievements = viewModel::onDetailsAchievementsTapped,
                    onCloseAvatarHud = viewModel::closeAvatarHud,
                    onAvatarRowTap = viewModel::onAvatarRowTapped,
                    onAvatarSlider = viewModel::onAvatarSliderChanged,
                    onCloseAvatarStyle = viewModel::closeAvatarStyle,
                    onCloseRaHub = viewModel::closeRaHub,
                    particlesIdle = particlesIdle,
                    onUiFilterError = container.shaderHealth::notice,
                    onCrosshairX = container.sounds::setCrosshair,
                    reveal = { bootReveal.menu },
                )
                }
                dev.xross.launcher.ui.BootOverlay(
                    reveal = bootReveal,
                    boot = state.boot,
                    pending = state.bootPending,
                    videoVolume = container.audio.cueVolume,
                    onSkip = viewModel::skipBoot,
                    onVideoEnded = viewModel::onBootVideoEnded,
                    onCovering = { bootCovers = it },
                )
            }
            }
        }

        // All key input is routed through dispatchKeyEvent, so no view needs focus. Without this,
        // the first D-pad press after a touch is swallowed by ViewRootImpl to "leave touch mode"
        // by focusing the Compose view, and the user's input is lost.
        (window.decorView as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    override fun onResume() {
        super.onResume()
        noteInput()
        // Picks up permission grants, installed emulators, and newly mounted drives, and resumes
        // background fingerprinting.
        viewModel.onForeground()
    }

    /** Other apps (emulators, the wallpaper cropper) can bring the system bars back; hide them again. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        gamepad.release()
        viewModel.onBackground()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStarted()
    }

    override fun onStop() {
        viewModel.onStopped()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        volumeMonitor.unregister(this)
        if (!isChangingConfigurations) container.audio.release()
        super.onDestroy()
    }

    // ComponentActivity re-declares dispatchKeyEvent under @RestrictTo; overriding the public Activity method is fine.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInput()
        val handled = gamepad.onKeyEvent(event)
        if (handled && event.keyCode == KeyEvent.KEYCODE_BACK) lastBackKeyAt = android.os.SystemClock.uptimeMillis()
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        noteInput()
        return super.dispatchTouchEvent(event)
    }

    // Raw stick motion isn't counted (an idle stick can jitter); the nav actions it produces are.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    private fun handleEffect(effect: UiEffect) {
        when (effect) {
            is UiEffect.StartActivity -> safeStart(effect.intent)
            UiEffect.PickFolder -> runCatching { pickFolder.launch(null) }
            UiEffect.PickDatFolder -> runCatching { pickDatFolder.launch(null) }
            UiEffect.PickThemeArchive -> runCatching {
                pickThemeArchive.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/x-xtheme"))
            }
            UiEffect.PickBaseRomsFolder -> runCatching { pickBaseRomsFolder.launch(null) }
            UiEffect.PickWallpaperImage -> runCatching { pickWallpaperImage.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }
            is UiEffect.PickMediaFolder -> {
                pendingMediaFolder = effect.kind
                runCatching { pickMediaFolder.launch(null) }
            }
            UiEffect.RequestStorageAccess -> requestStorageAccess()
            is UiEffect.SetWallpaper -> setWallpaper(effect.uri)
            is UiEffect.PromptText -> promptText(effect.prompt)
            is UiEffect.PromptRetroAchievements -> promptRetroAchievements(effect.user, effect.linked)
            is UiEffect.Launch -> when (val result = container.launcher.start(effect.plan, this)) {
                LaunchResult.Started -> Unit
                is LaunchResult.Failed -> viewModel.onLaunchFailed(result.message)
            }
            UiEffect.RestartApp -> restartApp()
        }
    }

    /**
     * Relaunches Xross in a fresh process, so a restored library database replaces the live one
     * before Room opens it. As the home app, the system would bring it back anyway.
     */
    private fun restartApp() {
        val relaunch = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(relaunch)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /**
     * A system text dialog (works with touch, hardware keyboards and the device's IME; the D-pad
     * moves between the field and the buttons). Secrets are masked and never pre-filled.
     */
    private fun promptText(prompt: dev.xross.launcher.state.TextPrompt) {
        val input = android.widget.EditText(this).apply {
            setSingleLine()
            setText(prompt.initial)
            setSelection(text.length)
            hint = prompt.hint
            inputType = if (prompt.secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(prompt.title)
            .setView(frame)
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.onTextEntered(prompt, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                setOnDismissListener { hideSystemBars() }
            }
            .show()
    }

    /**
     * RetroAchievements link dialog: user name and Web API key in one system dialog. The key is
     * masked and never pre-filled; left empty it keeps the saved key for the same user.
     */
    private fun promptRetroAchievements(user: String, linked: Boolean) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val name = android.widget.EditText(this).apply {
            setSingleLine()
            hint = "User name"
            setText(user)
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = NO_FULLSCREEN_IME
        }
        val key = android.widget.EditText(this).apply {
            setSingleLine()
            hint = if (linked) "Web API key (empty keeps the saved one)" else "Web API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = NO_FULLSCREEN_IME
        }
        val note = android.widget.TextView(this).apply {
            text = "Find your Web API key at retroachievements.org ▸ Settings ▸ Keys. It stays on this device " +
                "and is never written to the Xross folder." + if (linked) " Clear the user name to unlink." else ""
            setPadding(0, pad / 2, 0, 0)
            alpha = 0.7f
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(key)
            addView(note)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("RetroAchievements account")
            // Scrolls, so the key field comes into view above a landscape keyboard.
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setPositiveButton(if (linked) "Save" else "Link") { _, _ ->
                viewModel.onRetroAchievementsEntered(name.text.toString(), key.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                setOnDismissListener { hideSystemBars() }
            }
            .show()
    }

    /** System crop-and-set screen (it doubles as confirmation); falls back to "Set picture as". */
    private fun setWallpaper(uri: Uri) {
        val crop = runCatching {
            android.app.WallpaperManager.getInstance(this).getCropAndSetWallpaperIntent(uri)
        }.getOrNull()
        if (crop != null && safeStart(crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return
        val attach = Intent(Intent.ACTION_ATTACH_DATA)
            .setDataAndType(uri, "image/*")
            .putExtra("mimeType", "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        safeStart(Intent.createChooser(attach, "Set as wallpaper"))
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val perApp = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            if (!safeStart(perApp)) safeStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            legacyStoragePermission.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            )
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private companion object {
        /** A back callback this soon after a consumed KEYCODE_BACK is the same press. */
        const val BACK_ECHO_MS = 400L
        const val ICON_PX = 144

        /**
         * Keeps a landscape keyboard below the dialog instead of a full-screen editor, which would
         * hide the second field and the note.
         */
        const val NO_FULLSCREEN_IME = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
    }
}
