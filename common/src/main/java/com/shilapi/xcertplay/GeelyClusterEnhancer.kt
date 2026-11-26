package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Low-conflict coordinator connecting:
 * 1. `ClusterMapPresentation` (`1920x1080` top `1920x720` video frame + Fake Ultra dual-dial overlay + `DimMenuInteraction`)
 * 2. `CarPlayHostActivity` (`TYPE_APPLICATION_OVERLAY (2038)` standalone W-HUD & classic cluster card window + iAP2 guidance/lane sync)
 * 3. `DiPlayActivity` (single-button entry to the standalone Geely Cluster & Self-Draw HUD configuration dialog)
 */
internal object GeelyClusterEnhancer {
    private var hostAppContext: Context? = null
    private var hostLogger: (String) -> Unit = {}
    private var overlayWindow: GeelySelfDrawOverlayWindow? = null
    private var persistentThemeWindow: GeelyClusterThemeWindow? = null

    private var activeFakeUltraView: GeelyFakeUltraOverlayView? = null
    private var activePresentationSelfDrawView: GeelySelfDrawCanvasView? = null
    private var activePresentationInnerRoot: FrameLayout? = null
    private var activeDimController: GeelyDimMenuController? = null
    private var activeDimWatcher: GeelyDimLogcatWatcher? = null
    private var musicSubscription: (() -> Unit)? = null
    private var vehicleMediaSubscription: (() -> Unit)? = null
    private var lastVehicleMediaSnapshot: GeelyVehicleMediaSnapshot = GeelyVehicleMediaSnapshot()
    private var hostNightObserverRemoval: (() -> Unit)? = null
    private var vehicleDayNightRemoval: (() -> Unit)? = null
    private var carPlayNightSink: ((Boolean) -> Unit)? = null

    private val currentFakeUltraView: GeelyFakeUltraOverlayView?
        get() = persistentThemeWindow?.takeIf { it.attached }?.view ?: activeFakeUltraView

    fun isPersistentThemeActive(): Boolean = persistentThemeWindow?.attached == true

    private var latestGuidance: ClusterTurnGuidance? = null
    @Volatile
    private var latestLanes: List<LaneGuidanceLane> = emptyList()
    private var isFullScreenPresentationActive: Boolean = false
    private var presentationNightMode: Boolean? = null
    private var wasInPhoneCall: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callRecoveryTick = object : Runnable {
        override fun run() {
            if (!isFullScreenPresentationActive) {
                wasInPhoneCall = false
                return
            }
            val app = hostAppContext
            val inCall = isPhoneCallActive(app)
            if (wasInPhoneCall && !inCall) {
                wasInPhoneCall = false
                hostLogger("GeelyDimMenu: phone call ended; restoring full-screen cluster map")
                mainHandler.postDelayed({
                    if (isFullScreenPresentationActive && !isPhoneCallActive(hostAppContext)) {
                        activeDimController?.forceReenterFullScreenMap("post-call")
                    }
                }, 900L)
            } else if (inCall) {
                wasInPhoneCall = true
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun isPhoneCallActive(context: Context?): Boolean {
        if (BydNavigationOutputs.carPlayCall() != null) return true
        val am = context?.getSystemService(AudioManager::class.java) ?: return false
        return am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_RINGTONE
    }

    fun resolveEffectiveNightMode(config: GeelySelfDrawConfig, context: Context? = null): Boolean = when (config.appearanceMode) {
        GeelyClusterAppearanceMode.DARK -> true
        GeelyClusterAppearanceMode.LIGHT -> false
        GeelyClusterAppearanceMode.AUTO -> {
            val ctx = context ?: hostAppContext ?: currentFakeUltraView?.context
            val cpMode = ctx?.let { AirPlayPersistence.loadCarPlayNightMode(it) } ?: CarPlayNightMode.SYSTEM
            val explicitOverride = cpMode == CarPlayNightMode.DAY ||
                cpMode == CarPlayNightMode.NIGHT ||
                cpMode == CarPlayNightMode.SCHEDULE
            if (explicitOverride) {
                AppAppearanceRuntime.hostNight() ?: resolveConfiguredCarPlayNight(ctx)
            } else {
                GeelyEcarxDayNightReader.latestNight()
                    ?: AppAppearanceRuntime.hostNight()
                    ?: presentationNightMode
                    ?: resolveConfiguredCarPlayNight(ctx)
            }
        }
    }

    private fun resolveConfiguredCarPlayNight(context: Context?): Boolean {
        val ctx = context ?: return false
        val systemNight = GeelyEcarxDayNightReader.resolveSystemNight(
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
        )
        return when (AirPlayPersistence.loadCarPlayNightMode(ctx)) {
            CarPlayNightMode.DAY -> false
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.SCHEDULE -> {
                val now = Calendar.getInstance()
                val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                AirPlayPersistence.loadCarPlayNightSchedule(ctx).isNight(minuteOfDay)
            }
            CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT -> systemNight
        }
    }

    private fun ensureVehicleDayNightObserver(context: Context) {
        if (vehicleDayNightRemoval != null) return
        val app = context.applicationContext
        vehicleDayNightRemoval = GeelyEcarxDayNightReader.observe(app, hostLogger) { vehicleNight ->
            presentationNightMode = vehicleNight
            carPlayNightSink?.invoke(vehicleNight)
            runOnMain { applyEffectiveNight(app) }
        }
    }

    private var carPlayHostAttached: Boolean = false
    private var carPlayTrackActive: Boolean = false

    private fun ensureCarPlayMusicSubscription(context: Context) {
        val app = context.applicationContext
        if (musicSubscription != null) return
        runOnMain {
            if (musicSubscription != null) return@runOnMain
            musicSubscription = ClusterMusicState.observe { snapshot ->
                val hasCarPlayTrack = snapshot.info.playing ||
                    !snapshot.info.title.isNullOrBlank() ||
                    (snapshot.artwork != null && !snapshot.artwork.isRecycled)
                val wasTrackActive = carPlayTrackActive
                carPlayTrackActive = hasCarPlayTrack
                if (isFullScreenPresentationActive) {
                    currentFakeUltraView?.apply {
                        carPlayConnected = true
                        externalMediaActive = false
                        setArtwork(snapshot.artwork)
                        setMusicInfo(snapshot.info.title, snapshot.info.playing, snapshot.info.artist)
                    }
                } else if (carPlayHostAttached && hasCarPlayTrack) {
                    // Support CarPlay album/lyrics on the persistent dual-dial theme even when
                    // the separate cluster map stream (`clusterEnabled`) is turned off.
                    if (!wasTrackActive) {
                        GeelyVehicleMediaObserver.stopExternalPlayback(app)
                    }
                    currentFakeUltraView?.apply {
                        externalMediaActive = true
                        setArtwork(snapshot.artwork)
                        setMusicInfo(snapshot.info.title, snapshot.info.playing, snapshot.info.artist)
                    }
                } else if (!isFullScreenPresentationActive) {
                    applyVehicleMediaIfStandby()
                }
            }
        }
    }

    private fun stopCarPlayMusicSubscription() {
        carPlayTrackActive = false
        runOnMain {
            musicSubscription?.invoke()
            musicSubscription = null
        }
    }

    private fun ensureVehicleMediaObserver(context: Context) {
        val app = context.applicationContext
        if (vehicleMediaSubscription != null) {
            GeelyVehicleMediaObserver.refresh(app)
            return
        }
        vehicleMediaSubscription = GeelyVehicleMediaObserver.observe(app, hostLogger) { snapshot ->
            lastVehicleMediaSnapshot = snapshot
            runOnMain { applyVehicleMediaIfStandby() }
        }
    }

    private fun stopVehicleMediaObserver() {
        vehicleMediaSubscription?.invoke()
        vehicleMediaSubscription = null
        lastVehicleMediaSnapshot = GeelyVehicleMediaSnapshot()
    }

    private fun applyVehicleMediaIfStandby() {
        if (isFullScreenPresentationActive) return
        if (carPlayHostAttached && carPlayTrackActive) return
        currentFakeUltraView?.applyVehicleMedia(lastVehicleMediaSnapshot)
    }

    private fun ensureHostNightObserver() {
        if (hostNightObserverRemoval != null) return
        hostNightObserverRemoval = AppAppearanceRuntime.observeHost { night ->
            if (night != null) {
                presentationNightMode = night
            }
            val ctx = hostAppContext ?: activeFakeUltraView?.context ?: return@observeHost
            runOnMain { applyEffectiveNight(ctx) }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun applyEffectiveNight(context: Context) {
        val cfg = GeelyClusterPrefs.load(context)
        val effective = resolveEffectiveNightMode(cfg, context)
        currentFakeUltraView?.apply {
            nightMode = effective
            invalidate()
        }
        activePresentationSelfDrawView?.apply {
            nightMode = effective
            invalidate()
        }
        overlayWindow?.view?.apply {
            nightMode = effective
            invalidate()
        }
    }

    fun requestFullScreenMap(context: Context, reason: String = "manual"): Boolean {
        val controller = ensureDimController(context)
        return controller.forceReenterFullScreenMap(reason)
    }

    fun requestClassicCluster(context: Context): Boolean {
        val controller = ensureDimController(context)
        return controller.restoreClassicCluster()
    }

    private fun ensureDimController(context: Context): GeelyDimMenuController =
        activeDimController ?: GeelyDimMenuController(context.applicationContext) { msg ->
            hostLogger(msg)
        }.also { activeDimController = it }
    private var previousLaneListener: ((List<LaneGuidanceLane>) -> Unit)? = null
    private val laneListenerHook: (List<LaneGuidanceLane>) -> Unit = { lanes ->
        previousLaneListener?.invoke(lanes)
        latestLanes = lanes
        overlayWindow?.view?.let { v ->
            v.post {
                v.lanes = lanes
                v.invalidate()
            }
        }
        activePresentationSelfDrawView?.let { v ->
            v.post {
                v.lanes = lanes
                v.invalidate()
            }
        }
    }

    fun matchesClusterStream(context: Context, display: Display, width: Int, height: Int): Boolean =
        GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION) != 0 &&
            width == GeelyOverlayArea.CANVAS_WIDTH &&
            (height == GeelyOverlayArea.VIDEO_HEIGHT || height == GeelyOverlayArea.CANVAS_HEIGHT)

    fun settingsEntryLabel(context: Context): String {
        val cfg = GeelyClusterPrefs.load(context)
        val dialsOn = cfg.dialLeftEnabled || cfg.dialRightEnabled
        val hudOn = cfg.hasAnySelfDrawOverlay
        val status = when {
            dialsOn -> cfg.dialThemeStyle.displayName
            hudOn -> "仅自绘 HUD"
            else -> "已关闭"
        }
        return "吉利 Fake Ultra 仪表与自绘 HUD · $status"
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val ctx = hostAppContext ?: currentFakeUltraView?.context ?: return@OnSharedPreferenceChangeListener
        refreshConfig(ctx)
    }

    private var backgroundTurnListenerAttached: Boolean = false
    private val backgroundTurnOverlayListener: (ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnMain {
            val app = hostAppContext ?: return@runOnMain
            if (!CarPlayBackgroundSession.hasSession()) {
                detachBackgroundTurnListener()
                clearBackgroundCarPlayOverlay()
                return@runOnMain
            }
            onTurnGuidanceUpdated(app, guidance, isFullScreenPresentationActive)
        }
    }

    private fun detachBackgroundTurnListener() {
        if (!backgroundTurnListenerAttached) return
        backgroundTurnListenerAttached = false
        BydNavigationOutputs.setTurnOverlayListener(null)
    }

    private fun clearBackgroundCarPlayOverlay() {
        carPlayHostAttached = false
        stopCarPlayMusicSubscription()
        if (CarPlayGlance.laneListener === laneListenerHook) {
            CarPlayGlance.laneListener = previousLaneListener
            previousLaneListener = null
        }
        latestGuidance = null
        latestLanes = emptyList()
        currentFakeUltraView?.clearCarPlayData()
        closeStandaloneOverlayWindow()
        val keepThemeAlive = isPersistentThemeActive() &&
            (hostAppContext?.let { GeelyClusterPrefs.load(it).launchThemeOnStart } == true)
        if (keepThemeAlive) {
            applyVehicleMediaIfStandby()
        } else {
            stopVehicleMediaObserver()
            hostNightObserverRemoval?.invoke()
            hostNightObserverRemoval = null
            vehicleDayNightRemoval?.invoke()
            vehicleDayNightRemoval = null
            hostAppContext?.let { app ->
                runCatching { GeelyClusterPrefs.prefs(app).unregisterOnSharedPreferenceChangeListener(prefsListener) }
            }
        }
    }

    /**
     * Called once DiPlay launches (`DiPlayActivity.onCreate`) so the persistent cluster theme
     * window can appear immediately even before an iPhone connects to CarPlay.
     */
    fun onAppLaunched(context: Context) {
        val app = context.applicationContext
        hostAppContext = app
        GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(app)
        ensureVehicleMediaObserver(app)
        runOnMain {
            syncPersistentThemeWindow(app, requestFullScreen = true)
        }
    }

    /**
     * Called from [GeelyClusterKeepAliveService.onStartCommand] when the foreground keep-alive
     * service restarts (e.g. via `START_STICKY`) while the persistent cluster theme is not attached.
     */
    fun onKeepAliveServiceStarted(context: Context) {
        onAppLaunched(context)
    }

    /**
     * Called from [GeelyClusterKeepAliveService.onTaskRemoved] on Geely / ECARX head units where
     * `GeelyRecent` removes background activity tasks after ~30 s while the process remains alive.
     * Keeps the persistent cluster overlay attached instead of handing the cluster back to OEM.
     */
    fun onKeepAliveTaskRemoved(context: Context) {
        val app = context.applicationContext
        hostAppContext = app
        hostLogger("GeelyClusterTheme: background task removed by head unit; keeping cluster theme alive")
        runOnMain {
            if (!isPersistentThemeActive()) {
                syncPersistentThemeWindow(app, requestFullScreen = false)
            }
            syncClusterWheelKeyActive(app)
        }
    }

    /** Called when [DiPlaySessionService] stops (CarPlay disconnected or stopped from notification). */
    fun onSessionServiceDestroyed() {
        runOnMain {
            if (!CarPlayBackgroundSession.hasSession()) {
                detachBackgroundTurnListener()
                clearBackgroundCarPlayOverlay()
            }
        }
    }

    /** Called when the user taps "关闭主题" in the keep-alive foreground notification. */
    fun onKeepAliveStopRequested(context: Context) {
        val app = context.applicationContext
        runOnMain {
            val cfg = GeelyClusterPrefs.load(app)
            if (cfg.launchThemeOnStart) {
                GeelyClusterPrefs.save(app, cfg.copy(launchThemeOnStart = false))
            }
            shutdownPersistentTheme(app, handBackIfIdle = true)
        }
    }

    /** Called when DiPlay is swiped away from recents; hands the cluster back cleanly without altering saved prefs. */
    fun onAppTaskRemoved(context: Context) {
        val app = context.applicationContext
        runOnMain {
            detachBackgroundTurnListener()
            stopVehicleMediaObserver()
            vehicleDayNightRemoval?.invoke()
            vehicleDayNightRemoval = null
            carPlayNightSink = null
            closeStandaloneOverlayWindow()
            shutdownPersistentTheme(app, handBackIfIdle = true)
        }
    }

    /** Called in `CarPlayHostActivity.onCreate` / `onResume` to start standalone 2038 overlay if enabled. */
    fun attachHost(
        context: Context,
        log: (String) -> Unit = {},
        onVehicleNightChanged: ((Boolean) -> Unit)? = null,
    ) {
        val app = context.applicationContext
        hostAppContext = app
        hostLogger = log
        carPlayNightSink = onVehicleNightChanged
        carPlayHostAttached = true
        backgroundTurnListenerAttached = false
        GeelyEcarxSensorReader.setLogger { msg -> hostLogger(msg) }
        if (CarPlayGlance.laneListener !== laneListenerHook) {
            previousLaneListener = CarPlayGlance.laneListener
            CarPlayGlance.laneListener = laneListenerHook
        }
        GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(app)
        syncStandaloneOverlayWindow(app)
        syncPersistentThemeWindow(app, requestFullScreen = false)
        ensureCarPlayMusicSubscription(app)
    }

    /** Called in `CarPlayHostActivity.onDestroy`. */
    fun detachHost() {
        carPlayNightSink = null
        val backgroundSessionAlive = CarPlayBackgroundSession.hasSession() &&
            GeelyVehicleDetector.isGeelyOrEcarx(hostAppContext)
        if (backgroundSessionAlive) {
            // GeelyRecent may destroy CarPlayHostActivity in the background after ~30 s while the
            // CarPlay session remains active. Keep the standalone W-HUD / classic-card overlay and
            // lane/turn listeners alive so cluster HUD guidance continues seamlessly.
            overlayWindow?.view?.apply {
                fullScreen = false
                invalidate()
            }
            backgroundTurnListenerAttached = true
            BydNavigationOutputs.setTurnOverlayListener(backgroundTurnOverlayListener)
            return
        }
        detachBackgroundTurnListener()
        clearBackgroundCarPlayOverlay()
    }

    /**
     * Called from `CarPlayHostActivity.applyClusterTurnOverlay(guidance)` on the main thread
     * whenever iAP2 route guidance changes or refreshes every second.
     */
    fun onTurnGuidanceUpdated(context: Context, guidance: ClusterTurnGuidance?, presentationShowing: Boolean) {
        if (hostAppContext == null) {
            attachHost(context)
        }
        latestGuidance = guidance
        if (guidance == null) {
            latestLanes = emptyList()
        }
        isFullScreenPresentationActive = presentationShowing

        val routeActive = guidance != null
        GeelyAmapOverviewController.onTurnGuidanceUpdated(routeActive)
        currentFakeUltraView?.apply {
            carPlayConnected = presentationShowing
            this.routeActive = presentationShowing && routeActive
        }

        syncStandaloneOverlayWindow(context.applicationContext)

        overlayWindow?.view?.let { v ->
            v.fullScreen = presentationShowing
            v.guidance = guidance
            v.lanes = latestLanes
            v.invalidate()
        }

        activePresentationSelfDrawView?.let { v ->
            v.visibility = if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
            v.fullScreen = presentationShowing
            v.guidance = guidance
            v.lanes = latestLanes
            v.invalidate()
        }
    }

    /**
     * Wraps `ClusterMapPresentation`'s root `FrameLayout` when running on a Geely / ECARX cluster display:
     * - Places the existing `1920x720` map & stock views inside a `1920x720` top container (`Gravity.TOP`)
     *   so `1920x1080` displays do not stretch the map into the `Y: 720..1080` hardware crop region.
     * - Overlays `GeelyFakeUltraOverlayView` (`1920x720`) for dual vector dials, blurred album background,
     *   vignette, and bottom status bar.
     * - Overlays fallback `GeelySelfDrawCanvasView` (`1920x1080`) when the standalone 2038 window is unavailable.
     */
    fun attachToPresentation(
        presentation: Dialog,
        display: Display,
        currentRoot: FrameLayout,
        nightMode: Boolean,
    ): FrameLayout {
        val context = presentation.context
        if (!GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            !GeelyVehicleDetector.isComposite1080Display(display)
        ) {
            return currentRoot
        }

        val config = GeelyClusterPrefs.load(context)
        val is1080 = GeelyVehicleDetector.isComposite1080Display(display)
        presentationNightMode = nightMode
        val effectiveNight = resolveEffectiveNightMode(config, context)

        // Keep Presentation window & bottom Y:720..1080 hardware crop zone strictly TRANSPARENT
        // so returning to Classic Cluster (Mode 1) NEVER leaves an opaque black rectangle in the card slot!
        presentation.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val outerRoot = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Top 1920x720 container holding CarPlay video SurfaceView + Fake Ultra dual dials
        val topViewportParams = if (is1080) {
            FrameLayout.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.VIDEO_HEIGHT,
                Gravity.TOP or Gravity.START,
            )
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        currentRoot.clipChildren = true
        currentRoot.clipToPadding = true

        if (config.launchThemeOnStart) {
            syncPersistentThemeWindow(context.applicationContext, requestFullScreen = false)
        }

        val fakeUltraView: GeelyFakeUltraOverlayView? = if (isPersistentThemeActive()) {
            // Reuse the persistent 2038 theme overlay directly to avoid duplicate dial rendering.
            persistentThemeWindow?.view?.apply {
                this.config = config
                this.nightMode = effectiveNight
                this.carPlayConnected = true
                this.routeActive = latestGuidance != null
                invalidate()
            }
            null
        } else {
            GeelyFakeUltraOverlayView(context).apply {
                this.config = config
                this.nightMode = effectiveNight
                this.carPlayConnected = true
                this.routeActive = latestGuidance != null
            }.also { v ->
                currentRoot.addView(
                    v,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
        outerRoot.addView(currentRoot, topViewportParams)

        // Full 1920x1080 self-draw fallback layer inside Presentation (used if 2038 overlay window is not attached)
        val selfDrawView = GeelySelfDrawCanvasView(context).apply {
            this.config = config
            this.nightMode = effectiveNight
            this.fullScreen = true
            this.guidance = latestGuidance
            this.lanes = latestLanes
            this.visibility = if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
        }
        val selfDrawParams = if (is1080) {
            FrameLayout.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.CANVAS_HEIGHT,
                Gravity.TOP or Gravity.START,
            )
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        outerRoot.addView(selfDrawView, selfDrawParams)

        activeFakeUltraView = fakeUltraView
        activePresentationSelfDrawView = selfDrawView
        activePresentationInnerRoot = currentRoot
        syncStockClusterViewsVisibility(currentRoot, config)
        return outerRoot
    }

    private fun syncStockClusterViewsVisibility(root: FrameLayout?, config: GeelySelfDrawConfig) {
        val container = root ?: return
        val hideDials = config.dialLeftEnabled || config.dialRightEnabled || config.albumArtEnabled
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is ClusterSpeedometerView, is ClusterMusicView -> {
                    child.alpha = if (hideDials) 0f else 1f
                }
                is ClusterTurnCardView -> {
                    child.alpha = if (config.fullscreenCardEnabled) 0f else 1f
                }
            }
        }
    }

    /** Called in `ClusterMapPresentation.onStart()`. */
    fun onPresentationStarted(context: Context, display: Display) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            !GeelyVehicleDetector.isComposite1080Display(display)
        ) {
            return
        }
        isFullScreenPresentationActive = true
        GeelyClusterPrefs.prefs(context).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(context)
        currentFakeUltraView?.apply {
            carPlayConnected = true
            externalMediaActive = false
            routeActive = latestGuidance != null
            invalidate()
        }

        // Stop any lingering head-unit music session when CarPlay takes over the cluster.
        GeelyVehicleMediaObserver.stopExternalPlayback(context.applicationContext)
        stopCarPlayMusicSubscription()
        ensureCarPlayMusicSubscription(context.applicationContext)

        val controller = ensureDimController(context)
        controller.forceReenterFullScreenMap("presentation-start")

        activeDimWatcher?.close()
        activeDimWatcher = GeelyDimLogcatWatcher(controller) {
            if (isPhoneCallActive(hostAppContext)) {
                wasInPhoneCall = true
                hostLogger("GeelyDimMenu: cluster exited during phone call; will auto-restore after hang-up")
            } else {
                hostLogger("GeelyDimMenu: passive exit to classic cluster detected")
            }
        }.also { it.start() }

        wasInPhoneCall = isPhoneCallActive(hostAppContext)
        mainHandler.removeCallbacks(callRecoveryTick)
        mainHandler.postDelayed(callRecoveryTick, 1000L)

        syncStandaloneOverlayWindow(context.applicationContext)
        overlayWindow?.view?.fullScreen = true
        activePresentationSelfDrawView?.fullScreen = true
        syncClusterWheelKeyActive(context.applicationContext)
    }

    /** Called in `ClusterMapPresentation.onStop()`. */
    fun onPresentationStopped() {
        isFullScreenPresentationActive = false
        wasInPhoneCall = false
        mainHandler.removeCallbacks(callRecoveryTick)

        activeDimWatcher?.close()
        activeDimWatcher = null

        val keepBackgroundGuidance = CarPlayBackgroundSession.hasSession() &&
            GeelyVehicleDetector.isGeelyOrEcarx(hostAppContext)
        val keepCarPlaySession = carPlayHostAttached || keepBackgroundGuidance
        if (!keepBackgroundGuidance) {
            latestGuidance = null
            latestLanes = emptyList()
        }

        activeFakeUltraView = null
        activePresentationSelfDrawView = null
        activePresentationInnerRoot = null

        val keepThemeAlive = isPersistentThemeActive() &&
            (hostAppContext?.let { GeelyClusterPrefs.load(it).launchThemeOnStart } == true)

        if (!keepCarPlaySession) {
            stopCarPlayMusicSubscription()
            currentFakeUltraView?.clearCarPlayData()
            if (keepThemeAlive) {
                applyVehicleMediaIfStandby()
            } else {
                activeDimController?.restoreClassicCluster()
            }
        } else {
            currentFakeUltraView?.apply {
                carPlayConnected = false
                routeActive = false
                if (carPlayTrackActive) {
                    externalMediaActive = true
                }
                invalidate()
            }
            if (!keepThemeAlive) {
                activeDimController?.restoreClassicCluster()
            } else if (!carPlayTrackActive) {
                applyVehicleMediaIfStandby()
            }
        }

        overlayWindow?.view?.apply {
            fullScreen = false
            if (!keepBackgroundGuidance) {
                guidance = null
                lanes = emptyList()
            }
            invalidate()
        }
        hostAppContext?.let(::syncClusterWheelKeyActive)
    }

    /**
     * Cycles the center display content between `MAP` (transparent center for CarPlay or native
     * head-unit map projection), `ALBUM` (when album art is available), and `CLOCK` when triggered
     * by the user's bound steering-wheel button (long press or double click).
     */
    fun cycleCenterMode(context: Context): String {
        val app = context.applicationContext
        val cfg = GeelyClusterPrefs.load(app)
        val hasCarPlay = isFullScreenPresentationActive
        val hasArtwork = currentFakeUltraView?.hasUsableArtwork() == true
        val hasMediaAlbum = hasArtwork && (hasCarPlay || carPlayHostAttached || lastVehicleMediaSnapshot.hasContent)
        val routeNow = hasCarPlay && latestGuidance != null

        val available = ArrayList<GeelyClusterCenterMode>(3)
        available.add(GeelyClusterCenterMode.MAP)
        if (hasMediaAlbum) available.add(GeelyClusterCenterMode.ALBUM)
        available.add(GeelyClusterCenterMode.CLOCK)

        val currentVisible = when (cfg.centerMode) {
            GeelyClusterCenterMode.MAP -> GeelyClusterCenterMode.MAP
            GeelyClusterCenterMode.ALBUM -> if (hasMediaAlbum) GeelyClusterCenterMode.ALBUM else GeelyClusterCenterMode.CLOCK
            GeelyClusterCenterMode.CLOCK -> GeelyClusterCenterMode.CLOCK
            GeelyClusterCenterMode.AUTO -> when {
                !routeNow && cfg.albumArtEnabled && hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
                routeNow -> GeelyClusterCenterMode.MAP
                else -> GeelyClusterCenterMode.CLOCK
            }
        }

        val currentIdx = available.indexOf(currentVisible).coerceAtLeast(0)
        val nextMode = available[(currentIdx + 1) % available.size]
        GeelyClusterPrefs.save(app, cfg.copy(centerMode = nextMode))
        refreshConfig(app)
        return nextMode.name
    }

    private fun syncClusterWheelKeyActive(context: Context) {
        val active = isFullScreenPresentationActive || isPersistentThemeActive()
        EcarxKx11WheelKeys.setClusterActive(context, active)
    }

    /** Called when day/night mode changes on `ClusterMapPresentation`. */
    fun onNightModeChanged(night: Boolean) {
        presentationNightMode = night
        val ctx = hostAppContext ?: currentFakeUltraView?.context
        runOnMain {
            val effective = if (ctx != null) resolveEffectiveNightMode(GeelyClusterPrefs.load(ctx), ctx) else night
            currentFakeUltraView?.apply {
                nightMode = effective
                invalidate()
            }
            activePresentationSelfDrawView?.apply {
                nightMode = effective
                invalidate()
            }
            overlayWindow?.view?.apply {
                nightMode = effective
                invalidate()
            }
        }
    }

    fun refreshConfig(context: Context) {
        val appCtx = context.applicationContext
        hostAppContext = appCtx
        runOnMain {
            val cfg = GeelyClusterPrefs.load(appCtx)
            val effectiveNight = resolveEffectiveNightMode(cfg, appCtx)
            syncPersistentThemeWindow(appCtx, requestFullScreen = false)
            activeFakeUltraView?.apply {
                config = cfg
                nightMode = effectiveNight
                invalidate()
            }
            persistentThemeWindow?.view?.apply {
                config = cfg
                nightMode = effectiveNight
                invalidate()
            }
            activePresentationSelfDrawView?.apply {
                config = cfg
                nightMode = effectiveNight
                invalidate()
            }
            syncStockClusterViewsVisibility(activePresentationInnerRoot, cfg)
            syncStandaloneOverlayWindow(appCtx)
            overlayWindow?.view?.apply {
                config = cfg
                nightMode = effectiveNight
                invalidate()
            }
            activePresentationSelfDrawView?.visibility =
                if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
            syncClusterWheelKeyActive(appCtx)
            EcarxKx11WheelKeys.settingsChanged()
        }
    }

    private fun syncPersistentThemeWindow(appContext: Context, requestFullScreen: Boolean) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(appContext)) {
            shutdownPersistentTheme(appContext, handBackIfIdle = false)
            return
        }
        val cfg = GeelyClusterPrefs.load(appContext)
        if (!cfg.launchThemeOnStart || !cfg.hasAnyClusterVisual) {
            shutdownPersistentTheme(appContext, handBackIfIdle = true)
            return
        }
        val targetDisplay = GeelyVehicleDetector.findClusterDisplay(appContext)
        if (targetDisplay == null) {
            shutdownPersistentTheme(appContext, handBackIfIdle = false)
            return
        }
        val effectiveNight = resolveEffectiveNightMode(cfg, appContext)
        val currentWindow = persistentThemeWindow
        if (currentWindow != null && currentWindow.attached &&
            currentWindow.display.displayId == targetDisplay.displayId
        ) {
            ensureVehicleMediaObserver(appContext)
            currentWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive
                routeActive = isFullScreenPresentationActive && latestGuidance != null
                if (!isFullScreenPresentationActive) {
                    applyVehicleMedia(lastVehicleMediaSnapshot)
                }
                invalidate()
            }
            GeelyClusterKeepAliveService.start(appContext)
            syncClusterWheelKeyActive(appContext)
            if (requestFullScreen) {
                ensureDimController(appContext).forceReenterFullScreenMap("theme-launch")
                if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, appContext)
            }
            return
        }
        closePersistentThemeWindow()
        val newWindow = GeelyClusterThemeWindow(appContext, targetDisplay, hostLogger)
        if (newWindow.attach()) {
            ensureVehicleMediaObserver(appContext)
            newWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive
                routeActive = isFullScreenPresentationActive && latestGuidance != null
                if (!isFullScreenPresentationActive) {
                    applyVehicleMedia(lastVehicleMediaSnapshot)
                }
                invalidate()
            }
            persistentThemeWindow = newWindow
            syncClusterWheelKeyActive(appContext)
            // Hide any in-presentation duplicate dial view if toggled on mid-session.
            activeFakeUltraView?.visibility = View.GONE
            // Re-attach the HUD/turn-card overlay above the newly created theme window so navigation
            // cards always stay on the topmost Z-order layer even when toggled on mid-session.
            if (overlayWindow?.attached == true) {
                closeStandaloneOverlayWindow()
                syncStandaloneOverlayWindow(appContext)
            }
            GeelyClusterKeepAliveService.start(appContext)
            ensureDimController(appContext).forceReenterFullScreenMap(
                if (requestFullScreen) "app-launch" else "theme-enabled",
            )
            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, appContext)
        } else {
            newWindow.close()
        }
    }

    private fun shutdownPersistentTheme(appContext: Context, handBackIfIdle: Boolean) {
        val wasAttached = isPersistentThemeActive()
        stopVehicleMediaObserver()
        closePersistentThemeWindow()
        activeFakeUltraView?.visibility = View.VISIBLE
        GeelyClusterKeepAliveService.stop(appContext)
        syncClusterWheelKeyActive(appContext)
        if (wasAttached && handBackIfIdle && !isFullScreenPresentationActive) {
            activeDimController?.restoreClassicCluster()
            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, appContext)
        }
    }

    private fun closePersistentThemeWindow() {
        persistentThemeWindow?.close()
        persistentThemeWindow = null
    }

    private fun syncStandaloneOverlayWindow(appContext: Context) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(appContext)) {
            closeStandaloneOverlayWindow()
            return
        }
        val cfg = GeelyClusterPrefs.load(appContext)
        if (!cfg.hasAnySelfDrawOverlay) {
            closeStandaloneOverlayWindow()
            return
        }
        val targetDisplay = GeelyVehicleDetector.findClusterDisplay(appContext)
        if (targetDisplay == null) {
            closeStandaloneOverlayWindow()
            return
        }
        val effectiveNight = resolveEffectiveNightMode(cfg, appContext)
        val currentWindow = overlayWindow
        if (currentWindow != null && currentWindow.attached &&
            currentWindow.display.displayId == targetDisplay.displayId
        ) {
            currentWindow.view.config = cfg
            currentWindow.view.nightMode = effectiveNight
            currentWindow.view.fullScreen = isFullScreenPresentationActive
            currentWindow.view.guidance = latestGuidance
            currentWindow.view.lanes = latestLanes
            return
        }
        closeStandaloneOverlayWindow()
        val newWindow = GeelySelfDrawOverlayWindow(appContext, targetDisplay, hostLogger)
        if (newWindow.attach()) {
            newWindow.view.config = cfg
            newWindow.view.nightMode = effectiveNight
            newWindow.view.fullScreen = isFullScreenPresentationActive
            newWindow.view.guidance = latestGuidance
            newWindow.view.lanes = latestLanes
            overlayWindow = newWindow
        } else {
            newWindow.close()
        }
    }

    private fun closeStandaloneOverlayWindow() {
        overlayWindow?.close()
        overlayWindow = null
    }

    internal const val SETTINGS_CARD_TITLE = "吉利 Fake Ultra 仪表与自绘 HUD"
    internal var lastSelectedSettingsTab: Int = 0
    internal var lastPreviewRouteActive: Boolean = true

    /**
     * Appends the Geely Fake Ultra & Self-Draw HUD configuration card into the dedicated
     * Geely settings page, themed with the main UI's [palette].
     */
    fun appendSettingsCard(
        activity: Activity,
        parent: LinearLayout,
        palette: DiPlayPalette,
        searchIndexSink: MutableList<String>? = null,
        onStateChanged: () -> Unit = {},
    ) {
        searchIndexSink?.add(SETTINGS_CARD_TITLE)
        if (searchIndexSink != null) return
        val card = GeelyClusterSettingsPanel(
            activity = activity,
            colors = GeelyClusterUiColors.from(palette),
            onStateChanged = onStateChanged,
        ).buildInlineCard()
        val gap = (16 * activity.resources.displayMetrics.density).roundToInt()
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap },
        )
    }

    /** Opens the standalone Geely Fake Ultra & Self-Draw HUD configuration dialog (Choice 2 A). */
    fun showSettingsDialog(activity: Activity, onDismiss: () -> Unit = {}) {
        GeelyClusterSettingsDialog(activity, onDismiss).show()
    }
}

/**
 * Interactive 1920x1080 scaled preview widget showing:
 * - Upper `1920x720` map region + Fake Ultra dual vector dials + fullscreen navigation card
 * - Lower `1920x360` (`Y: 720..1080`) hardware crop region + W-HUD (`0,720,228x186`) + classic cluster card (`1165,720,364x112`)
 * - Dashed bounding boxes and labels for each region.
 */
private class GeelyCompositePreviewView(context: Context) : View(context) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(context)
        set(value) {
            field = value
            fakeUltraView.config = value
            fakeUltraView.previewMode = true
            invalidate()
        }

    var previewRouteActive: Boolean = true
        set(value) {
            field = value
            fakeUltraView.routeActive = value
            invalidate()
        }

    private val fakeUltraView = GeelyFakeUltraOverlayView(context).apply {
        previewMode = true
        routeActive = true
    }
    private val demoPreviewArtwork: android.graphics.Bitmap by lazy { createDemoPreviewArtwork() }

    private fun createDemoPreviewArtwork(): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(
            0f, 0f, 256f, 256f,
            intArrayOf(Color.rgb(28, 72, 138), Color.rgb(112, 46, 134), Color.rgb(218, 92, 68)),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = null
        p.color = Color.argb(65, 255, 255, 255)
        c.drawCircle(128f, 118f, 62f, p)
        p.color = Color.argb(190, 255, 255, 255)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = 42f
        c.drawText("♪", 128f, 132f, p)
        p.textSize = 20f
        c.drawText("ALBUM", 128f, 214f, p)
        return bmp
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val scale = min(
            width.toFloat() / GeelyOverlayArea.CANVAS_WIDTH.toFloat(),
            height.toFloat() / GeelyOverlayArea.CANVAS_HEIGHT.toFloat(),
        )
        val drawW = GeelyOverlayArea.CANVAS_WIDTH * scale
        val drawH = GeelyOverlayArea.CANVAS_HEIGHT * scale
        val dx = (width - drawW) / 2f
        val dy = (height - drawH) / 2f

        val save = canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        // 1. Upper 1920x720 simulated map background
        val previewNight = GeelyClusterEnhancer.resolveEffectiveNightMode(config, context)
        bgPaint.color = if (previewNight) Color.rgb(16, 26, 36) else Color.rgb(218, 226, 235)
        canvas.drawRect(0f, 0f, 1920f, 720f, bgPaint)

        // 2. Lower 1920x360 (Y: 720..1080) hardware crop source background
        bgPaint.color = Color.rgb(8, 11, 16)
        canvas.drawRect(0f, 720f, 1920f, 1080f, bgPaint)

        // 3. Render dual dials, vignette, center clock / album / map & bottom bar in 1920x720
        val previewCarPlay = previewRouteActive ||
            config.centerMode == GeelyClusterCenterMode.MAP ||
            config.centerMode == GeelyClusterCenterMode.ALBUM
        fakeUltraView.carPlayConnected = previewCarPlay
        fakeUltraView.nightMode = previewNight
        if (config.centerMode == GeelyClusterCenterMode.ALBUM) {
            fakeUltraView.setArtwork(demoPreviewArtwork)
            fakeUltraView.setMusicInfo("千千阙歌 · 陈慧娴", true)
        } else if (!previewRouteActive) {
            fakeUltraView.setArtwork(null)
            fakeUltraView.setMusicInfo(null, false)
        }
        fakeUltraView.measure(
            MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
        )
        fakeUltraView.layout(0, 0, 1920, 720)
        fakeUltraView.draw(canvas)

        // 4. Render Self-Draw HUD, cluster card, fullscreen card & lane guidance in 1920x1080
        GeelySelfDrawRenderer.drawAll(
            canvas = canvas,
            config = config,
            guidance = null,
            lanes = emptyList(),
            nightMode = previewNight,
            fullScreen = previewRouteActive,
            nowMs = SystemClock.uptimeMillis(),
            forcePreview = previewRouteActive,
        )

        // 5. Split line at Y = 720
        borderPaint.color = Color.argb(140, 100, 180, 255)
        canvas.drawLine(0f, 720f, 1920f, 720f, borderPaint)
        labelPaint.color = Color.argb(190, 130, 195, 255)
        canvas.drawText("▲ 1920×720 仪表主画面区   ▼ 1920×360 原车 W-HUD 与经典仪表卡硬件裁剪源区 (Y: 720..1080)", 24f, 750f, labelPaint)

        // 6. Bounding boxes for active self-draw areas
        if (config.hudEnabled) {
            drawAreaOutline(canvas, config.hudArea, Color.rgb(24, 203, 170), "W-HUD源")
        }
        if (config.cardEnabled) {
            drawAreaOutline(canvas, config.cardArea, Color.rgb(255, 194, 71), "经典仪表卡源")
        }
        if (config.fullscreenCardEnabled) {
            drawAreaOutline(canvas, config.fullscreenCardArea, Color.rgb(110, 185, 255), "全屏导航卡")
        }

        canvas.restoreToCount(save)
    }

    private fun drawAreaOutline(canvas: Canvas, area: GeelyOverlayArea, color: Int, tag: String) {
        borderPaint.color = color
        canvas.drawRect(
            area.x.toFloat(),
            area.y.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            borderPaint,
        )
        labelPaint.color = color
        val labelY = if (area.y >= 720) (area.bottom + 24f).coerceAtMost(1072f) else (area.y - 8f).coerceAtLeast(22f)
        canvas.drawText("$tag (${area.x},${area.y} ${area.width}×${area.height})", area.x.toFloat(), labelY, labelPaint)
    }
}

/** Semantic colors for Geely cluster settings UI, derived from DiPlay's active light/dark palette. */
private data class GeelyClusterUiColors(
    val palette: DiPlayPalette,
    val isNight: Boolean,
) {
    val background: Int get() = palette.background
    val surface: Int get() = palette.surface
    val itemSurface: Int get() = if (isNight) Color.rgb(26, 37, 54) else Color.rgb(239, 244, 250)
    val previewFrame: Int get() = if (isNight) Color.rgb(12, 17, 27) else Color.rgb(224, 231, 241)
    val button: Int get() = palette.button
    val outline: Int get() = palette.outline
    val selectedSurface: Int get() = palette.railSelected
    val selectedOutline: Int get() = palette.railSelectedOutline
    val primaryText: Int get() = palette.primaryText
    val secondaryText: Int get() = palette.secondaryText
    val accent: Int get() = palette.accent
    val onAccent: Int get() = palette.onAccent
    val ripple: Int get() = palette.ripple
    val switchTheme: SettingsTheme get() = SettingsTheme.card(palette)

    companion object {
        fun from(palette: DiPlayPalette): GeelyClusterUiColors {
            val night = palette === DiPlayPalette.DARK || palette.background == DiPlayPalette.DARK.background
            return GeelyClusterUiColors(palette, night)
        }

        fun resolve(activity: Activity): GeelyClusterUiColors {
            val night = (activity as? AppAppearanceOwner)?.currentAppNight ?: activity.resolveAppNightNow()
            return GeelyClusterUiColors(DiPlayPalette.of(night), night)
        }
    }
}

/**
 * Shared Geely Fake Ultra & Self-Draw HUD settings panel controller used by both:
 * 1. The inline card inside the unified Instrument Cluster settings page
 * 2. The fullscreen [GeelyClusterSettingsDialog]
 */
private class GeelyClusterSettingsPanel(
    private val activity: Activity,
    private val colors: GeelyClusterUiColors,
    private val onStateChanged: () -> Unit = {},
) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(activity)
        private set
    var selectedTab: Int
        get() = GeelyClusterEnhancer.lastSelectedSettingsTab.coerceIn(0, TAB_TITLES.lastIndex)
        set(value) {
            GeelyClusterEnhancer.lastSelectedSettingsTab = value.coerceIn(0, TAB_TITLES.lastIndex)
        }
    private var previewRouteActive: Boolean
        get() = GeelyClusterEnhancer.lastPreviewRouteActive
        set(value) {
            GeelyClusterEnhancer.lastPreviewRouteActive = value
        }

    private var previewView: GeelyCompositePreviewView? = null
    private var statusTextView: TextView? = null
    private var tabContentContainer: LinearLayout? = null
    private var scrollToTopCallback: (() -> Unit)? = null
    private val tabButtons = ArrayList<Button>()

    fun buildInlineCard(): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.surface, colors.outline, cornerDp = 20)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val heading = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        heading.addView(
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_dp_dashboard)
                imageTintList = ColorStateList.valueOf(colors.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) },
        )
        heading.addView(
            TextView(activity).apply {
                text = GeelyClusterEnhancer.SETTINGS_CARD_TITLE
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(heading)

        val statusTv = TextView(activity).apply {
            setTextColor(colors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, dp(12))
        }
        statusTextView = statusTv
        card.addView(statusTv)

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        val fullscreenBtn = createActionButton("全屏配置与预览") {
            GeelyClusterEnhancer.showSettingsDialog(activity) {
                onStateChanged()
            }
        }
        val resetBtn = createActionButton("恢复 HUD 默认布局") {
            resetAreasToDefault()
        }
        actionRow.addView(fullscreenBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) })
        actionRow.addView(resetBtn, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(actionRow)

        val tabScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(12))
        }
        val tabStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabButtons.clear()
        for (i in TAB_TITLES.indices) {
            val btn = createActionButton(TAB_TITLES[i]) {
                selectTab(i)
            }.apply {
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }
            tabButtons.add(btn)
            tabStrip.addView(
                btn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                    if (i > 0) marginStart = dp(8)
                },
            )
        }
        tabScroll.addView(tabStrip)
        card.addView(tabScroll)

        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        tabContentContainer = contentCol
        card.addView(contentCol)

        updateStatusText()
        updateTabButtonStyles()
        rebuildTabContent()
        return card
    }

    fun bindDialogViews(
        statusView: TextView,
        tabBtns: List<Button>,
        contentContainer: LinearLayout,
        onScrollToTop: () -> Unit,
    ) {
        statusTextView = statusView
        tabButtons.clear()
        tabButtons.addAll(tabBtns)
        tabContentContainer = contentContainer
        scrollToTopCallback = onScrollToTop
        updateStatusText()
        updateTabButtonStyles()
        rebuildTabContent()
    }

    fun selectTab(index: Int) {
        EcarxKx11WheelKeys.cancelLearning()
        selectedTab = index
        updateTabButtonStyles()
        rebuildTabContent()
        scrollToTopCallback?.invoke()
    }

    fun resetAreasToDefault() {
        config = GeelyClusterPrefs.resetAreasToDefault(activity)
        onConfigUpdated()
        rebuildTabContent()
        Toast.makeText(activity, "已恢复 HUD 与导航卡片默认布局", Toast.LENGTH_SHORT).show()
    }

    fun onConfigUpdated() {
        GeelyClusterPrefs.save(activity, config)
        GeelyClusterEnhancer.refreshConfig(activity)
        previewView?.config = config
        updateStatusText()
    }

    fun updateStatusText() {
        val display = GeelyVehicleDetector.findClusterDisplay(activity)
        val dispInfo = if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            "仪表屏 ID=${display.displayId} (${size.x}×${size.y})"
        } else {
            "未检测到副屏 (默认 ID=${config.preferredDisplayId})"
        }
        val overlayOk = if (Settings.canDrawOverlays(activity)) "悬浮窗已授权" else "悬浮窗未授权"
        val topCardStatus = if (config.fullscreenCardEnabled) "全屏导航卡:开" else "全屏导航卡:关"
        statusTextView?.text = "$dispInfo · $overlayOk · $topCardStatus"
    }

    fun updateTabButtonStyles() {
        for (i in tabButtons.indices) {
            val selected = i == selectedTab
            styleButton(tabButtons[i], selected = selected)
        }
    }

    fun rebuildTabContent() {
        val container = tabContentContainer ?: return
        container.removeAllViews()
        previewView = null
        when (selectedTab) {
            0 -> buildDialTab(container)
            1 -> buildCardsTab(container)
            2 -> buildHudTab(container)
            3 -> buildPreviewTab(container)
            4 -> buildDisplayAndPermissionTab(container)
        }
    }

    private fun buildDialTab(container: LinearLayout) {
        addSectionTitle(container, "仪表盘主题样式（${GeelyClusterThemeStyle.entries.size} 套风格 · 实时切换）")
        for (style in GeelyClusterThemeStyle.entries) {
            val selected = config.dialThemeStyle == style
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = RippleDrawable(
                    ColorStateList.valueOf(colors.ripple),
                    roundedBox(
                        fill = if (selected) colors.selectedSurface else colors.itemSurface,
                        stroke = if (selected) colors.selectedOutline else colors.outline,
                        cornerDp = 12,
                        strokeDp = if (selected) 2 else 1,
                    ),
                    null,
                )
                setOnClickListener {
                    if (config.dialThemeStyle != style) {
                        config = config.copy(dialThemeStyle = style)
                        onConfigUpdated()
                        rebuildTabContent()
                    }
                }
            }
            card.addView(
                TextView(activity).apply {
                    text = if (selected) "●  ${style.displayName}（当前使用）" else "○  ${style.displayName}"
                    setTextColor(if (selected) colors.accent else colors.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
            )
            card.addView(
                TextView(activity).apply {
                    text = style.description
                    setTextColor(colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(18), dp(3), 0, 0)
                }
            )
            container.addView(
                card,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                },
            )
        }

        addSectionTitle(container, "仪表组件")
        addSwitchRow(
            container = container,
            title = "软件启动时加载主题",
            subtitle = "打开 DiPlay 即在仪表显示双圆盘与大号数字时钟；手机未连接或断开后主题不消失（隐藏地图/导航/音乐），后台通知保活；需授予悬浮窗权限。",
            checked = config.launchThemeOnStart,
        ) { checked ->
            config = config.copy(launchThemeOnStart = checked)
            onConfigUpdated()
            if (checked && !Settings.canDrawOverlays(activity)) {
                Toast.makeText(
                    activity,
                    "已开启启动加载主题，请先在「屏幕与权限」中授予悬浮窗权限",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        addSwitchRow(
            container = container,
            title = "显示圆盘仪表",
            subtitle = "统一开启或隐藏左右两个圆盘；也可以通过下面的开关单独选择。",
            checked = config.dialLeftEnabled || config.dialRightEnabled,
        ) { checked ->
            config = config.copy(dialLeftEnabled = checked, dialRightEnabled = checked)
            onConfigUpdated()
            rebuildTabContent()
        }
        val modeBtn = createActionButton("仪表配色：${colorModeLabel(config.dialColorMode)}  (点击切换)") {
            val modes = GeelyDialColorMode.entries
            val next = modes[(config.dialColorMode.ordinal + 1) % modes.size]
            config = config.copy(dialColorMode = next)
            onConfigUpdated()
            rebuildTabContent()
        }
        container.addView(
            modeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            },
        )
        val effectiveNightNow = GeelyClusterEnhancer.resolveEffectiveNightMode(config, activity)
        val hasVehicleSignal = GeelyEcarxDayNightReader.latestNight() != null
        val autoHint = if (config.appearanceMode == GeelyClusterAppearanceMode.AUTO) {
            val tag = if (hasVehicleSignal) "原车" else "当前"
            if (effectiveNightNow) "·${tag}深色" else "·${tag}浅色"
        } else {
            ""
        }
        val appearanceBtn = createActionButton("仪表昼夜模式：${config.appearanceMode.displayName}$autoHint  (点击切换)") {
            val modes = GeelyClusterAppearanceMode.entries
            val next = modes[(config.appearanceMode.ordinal + 1) % modes.size]
            config = config.copy(appearanceMode = next, mapThemeDark = next != GeelyClusterAppearanceMode.LIGHT)
            onConfigUpdated()
            rebuildTabContent()
        }
        container.addView(
            appearanceBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(2)
                bottomMargin = dp(6)
            },
        )
        addSwitchRow(
            container = container,
            title = "左侧圆盘仪表",
            subtitle = "时速（0..240 km/h）＋燃油百分比。",
            checked = config.dialLeftEnabled,
        ) { checked ->
            config = config.copy(dialLeftEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "右侧圆盘仪表",
            subtitle = "转速与实时档位；导航时中心显示圆角专辑封面。",
            checked = config.dialRightEnabled,
        ) { checked ->
            config = config.copy(dialRightEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "底部歌词与状态栏",
            subtitle = "播放音乐时居中显示动态声波与实时歌词/歌名，静音时显示时间与平均油耗。",
            checked = config.bottomBarEnabled,
        ) { checked ->
            config = config.copy(bottomBarEnabled = checked)
            onConfigUpdated()
        }

        addSectionTitle(container, "CarPlay 专辑封面")
        addSwitchRow(
            container = container,
            title = "非导航时显示专辑封面",
            subtitle = "自动模式下，非导航时显示全宽模糊封面背景及中央圆角封面；导航开始或没有封面时显示地图。",
            checked = config.albumArtEnabled,
        ) { checked ->
            config = config.copy(albumArtEnabled = checked)
            onConfigUpdated()
        }

        addSectionTitle(container, "中央显示切换与方向盘按键")
        val centerModeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        for ((idx, mode) in GeelyClusterCenterMode.entries.withIndex()) {
            val active = config.centerMode == mode
            val btn = createActionButton(mode.shortLabel, selected = active) {
                config = config.copy(centerMode = mode)
                onConfigUpdated()
                rebuildTabContent()
            }
            centerModeRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
        container.addView(centerModeRow)

        val keyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        fun keyBindLabel(): String = if (config.centerSwitchKeyCode > 0) {
            "切换按键 · ${config.centerSwitchKeyCode}（${config.centerSwitchTrigger.shortLabel}）"
        } else {
            "绑定方向盘切换按键 · 未设置"
        }
        lateinit var bindKeyBtn: Button
        bindKeyBtn = createActionButton(keyBindLabel(), selected = config.centerSwitchKeyCode > 0) {
            bindKeyBtn.text = "现在请按下方向盘按键…"
            val started = EcarxKx11WheelKeys.learnClusterSwitchKey(
                context = activity,
                cancelled = {
                    activity.runOnUiThread { bindKeyBtn.text = keyBindLabel() }
                },
                done = { learnedCode ->
                    activity.runOnUiThread {
                        config = config.copy(centerSwitchKeyCode = learnedCode)
                        onConfigUpdated()
                        val siriSameKey = WheelZoomSettings.siriKey(activity) &&
                            WheelZoomSettings.key(activity, WheelZoomSettings.Role.SIRI)
                                ?.let { EcarxKx11WheelKeys.isGeelyInputKey(it) && it.code == learnedCode } == true
                        val tip = if (siriSameKey && config.centerSwitchTrigger == GeelyClusterSwitchTrigger.DOUBLE_CLICK) {
                            "已绑定按键 $learnedCode（与 Siri 共键建议改用「长按」触发）"
                        } else {
                            "已绑定仪表切换按键：$learnedCode（${config.centerSwitchTrigger.displayName}切换）"
                        }
                        Toast.makeText(activity, tip, Toast.LENGTH_LONG).show()
                        rebuildTabContent()
                    }
                },
            )
            if (!started) {
                bindKeyBtn.text = keyBindLabel()
                Toast.makeText(activity, "当前车机不支持吉利方向盘输入服务", Toast.LENGTH_SHORT).show()
            }
        }
        val triggerBtn = createActionButton("触发：${config.centerSwitchTrigger.shortLabel}") {
            val next = config.centerSwitchTrigger.next()
            config = config.copy(centerSwitchTrigger = next)
            onConfigUpdated()
            rebuildTabContent()
        }
        keyRow.addView(bindKeyBtn, LinearLayout.LayoutParams(0, dp(46), 1.9f).apply { marginEnd = dp(6) })
        keyRow.addView(triggerBtn, LinearLayout.LayoutParams(0, dp(46), 0.9f))
        if (config.centerSwitchKeyCode > 0) {
            val clearBtn = createActionButton("清除") {
                EcarxKx11WheelKeys.cancelLearning()
                config = config.copy(centerSwitchKeyCode = 0)
                onConfigUpdated()
                rebuildTabContent()
            }
            keyRow.addView(clearBtn, LinearLayout.LayoutParams(0, dp(46), 0.65f).apply { leftMargin = dp(6) })
        }
        container.addView(keyRow)
        container.addView(
            TextView(activity).apply {
                text = "通过长按或连点（双击）已绑定的方向盘按键，在「导航地图 / 专辑封面 / 大号数字时钟」之间循环切换。若与短按 Siri 共用同一按键，请使用「长按」触发，互不冲突。"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(2), 0, dp(2), dp(6))
            }
        )

        addSectionTitle(container, "地图视觉")
        addSwitchRow(
            container = container,
            title = "地图主题遮罩",
            subtitle = "使用专属明暗遮罩覆盖地图上下及两侧，保留中间透明地图窗口。",
            checked = config.mapVignetteEnabled,
        ) { checked ->
            config = config.copy(mapVignetteEnabled = checked)
            onConfigUpdated()
        }
    }

    private fun buildCardsTab(container: LinearLayout) {
        addSectionTitle(container, "仪表导航提示")
        addSwitchRow(
            container = container,
            title = "全屏导航卡片",
            subtitle = "全屏地图顶部显示转向、距离、道路与剩余行程（关闭即可隐藏顶部导航条）。",
            checked = config.fullscreenCardEnabled,
        ) { checked ->
            config = config.copy(fullscreenCardEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "仪表导航卡片",
            subtitle = "普通经典仪表模式下显示转向、距离和道路（Y: 720..1080 裁剪源）。",
            checked = config.cardEnabled,
        ) { checked ->
            config = config.copy(cardEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "车道提示",
            subtitle = "同时用于 HUD 与全屏导航卡片；没有车道数据时自动隐藏。",
            checked = config.laneEnabled,
        ) { checked ->
            config = config.copy(laneEnabled = checked)
            onConfigUpdated()
        }

        addSectionTitle(container, "导航卡片位置微调")
        addAreaEditorCard(
            container = container,
            label = "全屏导航卡片区域 (默认 595, 118, 728×112)",
            area = config.fullscreenCardArea,
        ) { updated ->
            config = config.copy(fullscreenCardArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }
        addAreaEditorCard(
            container = container,
            label = "经典仪表卡片区域 (默认 1165, 720, 364×112)",
            area = config.cardArea,
        ) { updated ->
            config = config.copy(cardArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }

        addSectionTitle(container, "高德主驾车道级")
        val amapCfg = GeelyAmapOverviewPrefs.load(activity)
        addSwitchRow(
            container = container,
            title = "导航开始自动切中控全览",
            subtitle = "高德开始导航后自动点击中控右上角「全览」(90%, 10%)，使主驾仪表默认显示车道级导航。",
            checked = amapCfg.autoOverviewOnNavStart,
        ) { checked ->
            GeelyAmapOverviewPrefs.save(activity, amapCfg.copy(autoOverviewOnNavStart = checked))
            rebuildTabContent()
        }
        if (amapCfg.autoOverviewOnNavStart) {
            val delayRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(6))
            }
            for ((idx, delayMs) in GeelyAmapOverviewConfig.DELAY_PRESETS_MS.withIndex()) {
                val active = amapCfg.autoOverviewDelayMs == delayMs
                val label = String.format(java.util.Locale.US, "延迟 %.1f 秒", delayMs / 1000f)
                val btn = createActionButton(label, selected = active) {
                    GeelyAmapOverviewPrefs.save(activity, amapCfg.copy(autoOverviewDelayMs = delayMs))
                    rebuildTabContent()
                }
                delayRow.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (idx > 0) leftMargin = dp(6)
                    },
                )
            }
            container.addView(delayRow)
        }
    }

    private fun buildHudTab(container: LinearLayout) {
        addSectionTitle(container, "抬头显示 (W-HUD)")
        addSwitchRow(
            container = container,
            title = "HUD 导航提示",
            subtitle = "行驶时在原车抬头显示中查看转向、距离和道路（Y: 720..1080 硬件裁剪源）。",
            checked = config.hudEnabled,
        ) { checked ->
            config = config.copy(hudEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "车道提示",
            subtitle = "同时用于 HUD 与全屏导航卡片；没有车道数据时自动隐藏。",
            checked = config.laneEnabled,
        ) { checked ->
            config = config.copy(laneEnabled = checked)
            onConfigUpdated()
        }
        addAreaEditorCard(
            container = container,
            label = "W-HUD 裁剪源区域 (默认 0, 720, 228×186)",
            area = config.hudArea,
        ) { updated ->
            config = config.copy(hudArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }
    }

    private fun buildPreviewTab(container: LinearLayout) {
        addSectionTitle(container, "仪表效果预览")
        val previewCard = FrameLayout(activity).apply {
            background = roundedBox(colors.previewFrame, colors.outline, cornerDp = 12)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val pv = GeelyCompositePreviewView(activity).also {
            it.config = config
            it.previewRouteActive = previewRouteActive
            previewView = it
        }
        previewCard.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        container.addView(
            previewCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            },
        )

        val styleRows = GeelyClusterThemeStyle.entries.chunked(3)
        for ((rowIdx, rowStyles) in styleRows.withIndex()) {
            val themeRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, if (rowIdx == styleRows.lastIndex) dp(8) else dp(6))
            }
            for ((idx, style) in rowStyles.withIndex()) {
                val active = config.dialThemeStyle == style
                val btn = createActionButton(style.shortLabel, selected = active) {
                    config = config.copy(dialThemeStyle = style)
                    onConfigUpdated()
                    rebuildTabContent()
                }
                themeRow.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (idx > 0) leftMargin = dp(6)
                    },
                )
            }
            container.addView(themeRow)
        }

        val previewCenterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        for ((idx, mode) in GeelyClusterCenterMode.entries.withIndex()) {
            val active = config.centerMode == mode
            val btn = createActionButton(mode.shortLabel, selected = active) {
                config = config.copy(centerMode = mode)
                onConfigUpdated()
                rebuildTabContent()
            }
            previewCenterRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                },
            )
        }
        container.addView(previewCenterRow)

        val quickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val effectiveNightPreview = GeelyClusterEnhancer.resolveEffectiveNightMode(config, activity)
        val previewNightLabel = when (config.appearanceMode) {
            GeelyClusterAppearanceMode.AUTO -> if (effectiveNightPreview) "自动(深)" else "自动(浅)"
            else -> config.appearanceMode.shortLabel
        }
        val btnTheme = createActionButton("昼夜：$previewNightLabel") {
            val modes = GeelyClusterAppearanceMode.entries
            val next = modes[(config.appearanceMode.ordinal + 1) % modes.size]
            config = config.copy(appearanceMode = next, mapThemeDark = next != GeelyClusterAppearanceMode.LIGHT)
            onConfigUpdated()
            rebuildTabContent()
        }
        val btnRoute = createActionButton(if (previewRouteActive) "场景：导航中" else "场景：待机态") {
            previewRouteActive = !previewRouteActive
            previewView?.previewRouteActive = previewRouteActive
            rebuildTabContent()
        }
        val btnEnterMap = createActionButton("切全屏 (M3)") {
            val ok = GeelyClusterEnhancer.requestFullScreenMap(activity, "dialog-button")
            Toast.makeText(
                activity,
                if (ok) "已发送强制切全屏地图 (Mode 3)" else "车机 DimMenuInteraction 不可用",
                Toast.LENGTH_SHORT,
            ).show()
        }
        val btnExitMap = createActionButton("回经典 (M1)") {
            val ok = GeelyClusterEnhancer.requestClassicCluster(activity)
            Toast.makeText(
                activity,
                if (ok) "已发送 switchNaviMode(1)" else "车机 DimMenuInteraction 不可用",
                Toast.LENGTH_SHORT,
            ).show()
        }
        quickRow.addView(btnTheme, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        quickRow.addView(btnRoute, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        quickRow.addView(btnEnterMap, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        quickRow.addView(btnExitMap, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(quickRow)

        addSwitchRow(
            container = container,
            title = "在实车仪表与 HUD 上显示模拟预览",
            subtitle = "未开始导航时在实车仪表和 HUD 上输出演示数据，便于校准裁剪坐标。",
            checked = config.previewEnabled,
        ) { checked ->
            config = config.copy(previewEnabled = checked)
            onConfigUpdated()
        }
    }

    private fun buildDisplayAndPermissionTab(container: LinearLayout) {
        addSectionTitle(container, "输出权限与仪表屏幕")
        val hasOverlay = Settings.canDrawOverlays(activity)
        val permBtn = createActionButton(
            if (hasOverlay) "悬浮窗权限已开启 · 管理悬浮窗权限"
            else "管理悬浮窗权限 (允许 TYPE_APPLICATION_OVERLAY 2038)"
        ) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                )
                activity.startActivity(intent)
            }.onFailure {
                Toast.makeText(activity, "车机未提供此权限页面", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(
            permBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            },
        )
        val hasNotifAccess = GeelyMediaNotificationListenerService.isEnabled(activity)
        val mediaPermBtn = createActionButton(
            if (hasNotifAccess) "车机音乐与歌词读取权限已开启 · 管理通知使用权"
            else "开启车机音乐与歌词读取权限 (网易云 / QQ 音乐 / 蓝牙)"
        ) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure {
                Toast.makeText(
                    activity,
                    "车机未提供此权限页面，可通过 ADB 授予通知使用权",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        container.addView(
            mediaPermBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(10)
            },
        )
        val selectDispBtn = createActionButton("选择仪表屏幕 (当前优先 ID=${config.preferredDisplayId})") {
            showDisplayPickerDialog()
        }
        container.addView(
            selectDispBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        container.addView(
            TextView(activity).apply {
                text = "允许悬浮窗权限可提高独立导航与常驻仪表主题的兼容性；开启通知使用权后，不连 CarPlay 也能读取网易云、QQ 音乐与蓝牙音乐的专辑封面及实时歌词。"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(10), 0, 0)
            }
        )
    }

    private fun addSectionTitle(container: LinearLayout, title: String) {
        container.addView(
            TextView(activity).apply {
                text = title
                setTextColor(colors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            }
        )
    }

    private fun addSwitchRow(
        container: LinearLayout,
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        val rowResult = SettingsWidgets.createSwitchRow(
            context = activity,
            label = title,
            description = subtitle.orEmpty(),
            checked = checked,
            theme = colors.switchTheme,
            contentDescription = title,
            labelSizeSp = 15.5f,
            onChanged = onChanged,
        )
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.itemSurface, colors.outline, cornerDp = 12)
            setPadding(dp(14), dp(2), dp(14), dp(2))
            addView(
                rowResult.rowView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        container.addView(
            wrapper,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            },
        )
    }

    private fun addAreaEditorCard(
        container: LinearLayout,
        label: String,
        area: GeelyOverlayArea,
        onAreaChanged: (GeelyOverlayArea) -> Unit,
    ) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.itemSurface, colors.outline, cornerDp = 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.addView(
            TextView(activity).apply {
                text = "$label\n当前: X=${area.x}, Y=${area.y}, 宽=${area.width}, 高=${area.height}"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            }
        )

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val editBtn = createActionButton("输入精确坐标") {
            showNumericAreaDialog(label, area, onAreaChanged)
        }
        val leftBtn = createActionButton("X-4") { onAreaChanged(area.copy(x = area.x - 4)) }
        val rightBtn = createActionButton("X+4") { onAreaChanged(area.copy(x = area.x + 4)) }
        val upBtn = createActionButton("Y-4") { onAreaChanged(area.copy(y = area.y - 4)) }
        val downBtn = createActionButton("Y+4") { onAreaChanged(area.copy(y = area.y + 4)) }

        row.addView(editBtn, LinearLayout.LayoutParams(0, dp(42), 1.6f))
        for (b in listOf(leftBtn, rightBtn, upBtn, downBtn)) {
            row.addView(b, LinearLayout.LayoutParams(0, dp(42), 0.7f).apply { leftMargin = dp(4) })
        }
        box.addView(row)
        container.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            },
        )
    }

    private fun showNumericAreaDialog(
        title: String,
        current: GeelyOverlayArea,
        onSaved: (GeelyOverlayArea) -> Unit,
    ) {
        val dialogContext = activity.appDialogContext()
        val layout = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val fields = listOf(
            "左边距 X (0..1920)" to current.x,
            "上边距 Y (0..1080)" to current.y,
            "宽度 Width" to current.width,
            "高度 Height" to current.height,
        )
        val inputs = fields.map { (hint, value) ->
            EditText(dialogContext).apply {
                this.hint = hint
                setText(value.toString())
                setTextColor(colors.primaryText)
                setHintTextColor(colors.secondaryText)
                inputType = InputType.TYPE_CLASS_NUMBER
            }.also { et ->
                layout.addView(
                    TextView(dialogContext).apply {
                        text = hint
                        setTextColor(colors.secondaryText)
                    }
                )
                layout.addView(et)
            }
        }
        activity.appDialogBuilder()
            .setTitle(title)
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val x = inputs[0].text.toString().trim().toIntOrNull()
                val y = inputs[1].text.toString().trim().toIntOrNull()
                val w = inputs[2].text.toString().trim().toIntOrNull()
                val h = inputs[3].text.toString().trim().toIntOrNull()
                if (x != null && y != null && w != null && h != null) {
                    onSaved(GeelyOverlayArea(x, y, w, h).clamp())
                } else {
                    Toast.makeText(activity, "请输入有效整数", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showDisplayPickerDialog() {
        val dm = activity.getSystemService(DisplayManager::class.java)
        val displays = dm?.displays?.filter { it.displayId != Display.DEFAULT_DISPLAY }.orEmpty()
        if (displays.isEmpty()) {
            Toast.makeText(activity, "当前未检测到副屏，连接车机后将自动匹配 ID=2 (1920×1080/720)", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = displays.map { d ->
            val m = d.mode
            "屏幕 ID=${d.displayId} (${m?.physicalWidth ?: 0}×${m?.physicalHeight ?: 0})"
        }.toTypedArray()
        activity.appDialogBuilder()
            .setTitle("选择仪表屏幕")
            .setItems(labels) { _, which ->
                val chosen = displays[which]
                config = config.copy(preferredDisplayId = chosen.displayId)
                onConfigUpdated()
                rebuildTabContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun createActionButton(text: String, selected: Boolean = false, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            styleButton(this, selected = selected)
            setOnClickListener { onClick() }
        }

    fun styleButton(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) colors.accent else colors.primaryText)
        button.typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        button.background = RippleDrawable(
            ColorStateList.valueOf(colors.ripple),
            roundedBox(
                fill = if (selected) colors.selectedSurface else colors.button,
                stroke = if (selected) colors.selectedOutline else colors.outline,
                cornerDp = 12,
                strokeDp = 1,
            ),
            null,
        )
    }

    fun roundedBox(fill: Int, stroke: Int, cornerDp: Int = 12, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(cornerDp).toFloat()
            setStroke(dp(strokeDp), stroke)
        }

    private fun colorModeLabel(mode: GeelyDialColorMode): String = when (mode) {
        GeelyDialColorMode.DEFAULT -> "默认青色 (#18CBAA)"
        GeelyDialColorMode.SPEED -> "随车速四段渐变"
        GeelyDialColorMode.ALBUM -> "随专辑封面主色"
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    companion object {
        val TAB_TITLES = listOf(
            "仪表显示",
            "导航卡片",
            "抬头显示 (HUD)",
            "效果预览",
            "权限与兼容",
        )
    }
}

/**
 * Standalone configuration dialog for Geely Fake Ultra Dials & Self-Draw HUD/Navigation Cards,
 * following DiPlay's active light/dark appearance palette.
 */
internal class GeelyClusterSettingsDialog(
    private val activity: Activity,
    private val onDismiss: () -> Unit = {},
) {
    private val colors = GeelyClusterUiColors.resolve(activity)
    private val panel = GeelyClusterSettingsPanel(activity, colors)

    fun show() {
        val themeRes = if (colors.isNight) {
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        } else {
            android.R.style.Theme_Light_NoTitleBar_Fullscreen
        }
        val dialog = Dialog(activity, themeRes)
        dialog.setOnDismissListener {
            EcarxKx11WheelKeys.cancelLearning()
            onDismiss()
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(colors.background))
            statusBarColor = colors.palette.systemBar
            navigationBarColor = colors.palette.systemBar
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = colors.palette.systemBarIconsAreDark
                isAppearanceLightNavigationBars = colors.palette.systemBarIconsAreDark
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        // 1. Compact Header Bar (fits 1920x720 wide head-unit displays cleanly)
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = panel.createActionButton("‹ 返回") {
            dialog.dismiss()
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(104), dp(42)))

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        titleCol.addView(
            TextView(activity).apply {
                text = "仪表与导航显示 (Fake Ultra · 设置自动保存)"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
            }
        )
        val statusTv = TextView(activity).apply {
            setTextColor(colors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        titleCol.addView(statusTv)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val resetBtn = panel.createActionButton("恢复 HUD 默认布局") {
            panel.resetAreasToDefault()
        }
        header.addView(resetBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        root.addView(header)

        // 2. Main Body: Left Vertical Sidebar Menu + Right Scrollable Settings Panel
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val sidebarScroll = ScrollView(activity).apply {
            background = panel.roundedBox(colors.surface, colors.outline, cornerDp = 12)
        }
        val sidebarCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val tabButtons = ArrayList<Button>()
        for (i in GeelyClusterSettingsPanel.TAB_TITLES.indices) {
            val btn = panel.createActionButton(GeelyClusterSettingsPanel.TAB_TITLES[i]) {
                panel.selectTab(i)
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(6), dp(12), dp(6))
            }
            tabButtons.add(btn)
            sidebarCol.addView(
                btn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                    if (i > 0) topMargin = dp(8)
                },
            )
        }
        sidebarScroll.addView(sidebarCol)
        body.addView(
            sidebarScroll,
            LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(14)
            },
        )

        val rightScroll = ScrollView(activity).apply {
            background = panel.roundedBox(colors.surface, colors.outline, cornerDp = 12)
        }
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(20))
        }
        rightScroll.addView(contentCol)
        body.addView(rightScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.bindDialogViews(statusTv, tabButtons, contentCol) {
            rightScroll.scrollTo(0, 0)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
