package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.util.Calendar

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
    private var nativeMapSubscription: (() -> Unit)? = null
    private var nativeMapProjected: Boolean = false
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

    private var modeBeforeTelemetryPeek: GeelyClusterCenterMode? = null
    private val navAutoReturnRunnable = Runnable { performNavAutoReturn() }

    private fun cancelNavAutoReturn() {
        mainHandler.removeCallbacks(navAutoReturnRunnable)
        modeBeforeTelemetryPeek = null
    }

    /**
     * When actively navigating and the driver peeks at a telemetry page (`TRIP` / `TIRE` /
     * `VEHICLE_INFO`), automatically returns to the map (`AUTO` or `MAP`) after
     * [GeelySelfDrawConfig.navAutoReturnSeconds] seconds. Each subsequent switch resets the timer.
     */
    fun onCenterModeChangedByUser(
        context: Context,
        previousMode: GeelyClusterCenterMode?,
        nextMode: GeelyClusterCenterMode,
    ) {
        val app = context.applicationContext
        runOnMain {
            mainHandler.removeCallbacks(navAutoReturnRunnable)
            if (!nextMode.isTelemetryPage) {
                modeBeforeTelemetryPeek = null
                return@runOnMain
            }
            val cfg = GeelyClusterPrefs.load(app)
            val routeActive = isFullScreenPresentationActive && latestGuidance != null
            if (!routeActive || cfg.navAutoReturnSeconds <= 0) {
                modeBeforeTelemetryPeek = null
                return@runOnMain
            }
            if (modeBeforeTelemetryPeek == null) {
                modeBeforeTelemetryPeek = if (previousMode == GeelyClusterCenterMode.AUTO) {
                    GeelyClusterCenterMode.AUTO
                } else {
                    GeelyClusterCenterMode.MAP
                }
            }
            mainHandler.postDelayed(navAutoReturnRunnable, cfg.navAutoReturnSeconds * 1000L)
        }
    }

    private fun performNavAutoReturn() {
        val app = hostAppContext ?: return
        val cfg = GeelyClusterPrefs.load(app)
        val routeActive = isFullScreenPresentationActive && latestGuidance != null
        if (!routeActive || cfg.navAutoReturnSeconds <= 0 || !cfg.centerMode.isTelemetryPage) {
            modeBeforeTelemetryPeek = null
            return
        }
        val returnMode = modeBeforeTelemetryPeek ?: GeelyClusterCenterMode.MAP
        modeBeforeTelemetryPeek = null
        GeelyClusterPrefs.save(app, cfg.copy(centerMode = returnMode))
        refreshConfig(app)
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

    private fun ensureCarPlayMusicSubscription() {
        if (musicSubscription != null) return
        runOnMain {
            if (musicSubscription != null) return@runOnMain
            musicSubscription = ClusterMusicState.observe { snapshot ->
                val hasCarPlayTrack = snapshot.info.playing ||
                    !snapshot.info.title.isNullOrBlank() ||
                    (snapshot.artwork != null && !snapshot.artwork.isRecycled)
                carPlayTrackActive = hasCarPlayTrack
                if (isFullScreenPresentationActive) {
                    currentFakeUltraView?.apply {
                        carPlayConnected = true
                        carPlayMediaActive = hasCarPlayTrack
                        externalMediaActive = false
                        setCarPlayMediaSnapshot(snapshot)
                    }
                } else if (carPlayHostAttached && hasCarPlayTrack) {
                    // Support CarPlay album/lyrics on the persistent dual-dial theme even when
                    // the separate cluster map stream (`clusterEnabled`) is turned off.
                    currentFakeUltraView?.apply {
                        carPlayMediaActive = true
                        externalMediaActive = false
                        setCarPlayMediaSnapshot(snapshot)
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

    private fun ensureNativeMapObserver(context: Context) {
        val app = context.applicationContext
        if (nativeMapSubscription != null) {
            GeelyNativeClusterMapObserver.refreshNow()
            return
        }
        nativeMapSubscription = GeelyNativeClusterMapObserver.observe(app, hostLogger) { projected ->
            nativeMapProjected = projected
            runOnMain { applyNativeMapState() }
        }
    }

    private fun stopNativeMapObserver() {
        nativeMapSubscription?.invoke()
        nativeMapSubscription = null
        nativeMapProjected = false
        runOnMain { currentFakeUltraView?.nativeMapProjected = false }
    }

    private fun applyNativeMapState() {
        currentFakeUltraView?.nativeMapProjected = !isFullScreenPresentationActive && nativeMapProjected
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
            dialsOn -> cfg.dialThemeStyle.shortLabel
            hudOn -> "仅抬头显示与导航卡"
            else -> "已关闭"
        }
        return "吉利仪表与 HUD · $status"
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
            hostAppContext?.let(::ensureNativeMapObserver)
        } else {
            stopVehicleMediaObserver()
            stopNativeMapObserver()
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
            stopNativeMapObserver()
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
        ensureCarPlayMusicSubscription()
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
        if (!routeActive || !presentationShowing) {
            cancelNavAutoReturn()
        }
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
        stopNativeMapObserver()
        GeelyClusterPrefs.prefs(context).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(context)
        currentFakeUltraView?.apply {
            carPlayConnected = true
            externalMediaActive = false
            routeActive = latestGuidance != null
            invalidate()
        }

        stopCarPlayMusicSubscription()
        ensureCarPlayMusicSubscription()

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
        cancelNavAutoReturn()

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
                hostAppContext?.let(::ensureNativeMapObserver)
            } else {
                stopNativeMapObserver()
                activeDimController?.restoreClassicCluster()
            }
        } else {
            currentFakeUltraView?.apply {
                carPlayConnected = false
                routeActive = false
                carPlayMediaActive = carPlayTrackActive
                if (carPlayTrackActive) {
                    externalMediaActive = false
                }
                invalidate()
            }
            if (!keepThemeAlive) {
                stopNativeMapObserver()
                activeDimController?.restoreClassicCluster()
            } else {
                hostAppContext?.let(::ensureNativeMapObserver)
                if (!carPlayTrackActive) {
                    applyVehicleMediaIfStandby()
                }
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
     * Cycles the center display content across the user-enabled pages in [GeelySelfDrawConfig.cycleModes]
     * (`MAP`, `ALBUM`, `CLOCK`, `TRIP`, `TIRE`, `VEHICLE_INFO`), automatically skipping `MAP` when
     * no map projection is active and skipping `ALBUM` when no artwork is available.
     */
    fun cycleCenterMode(context: Context): String {
        val app = context.applicationContext
        val cfg = GeelyClusterPrefs.load(app)
        val hasCarPlay = isFullScreenPresentationActive
        val hasNativeMap = !hasCarPlay && nativeMapProjected
        val hasMapSource = hasCarPlay || hasNativeMap
        val hasArtwork = currentFakeUltraView?.hasUsableArtwork() == true
        val hasMediaAlbum = hasArtwork && (hasCarPlay || carPlayHostAttached || lastVehicleMediaSnapshot.hasContent)
        val routeNow = hasCarPlay && latestGuidance != null

        val enabledSet = cfg.cycleModes
        val available = ArrayList<GeelyClusterCenterMode>(GeelyClusterCenterMode.CYCLE_ORDER.size)
        for (mode in GeelyClusterCenterMode.CYCLE_ORDER) {
            if (mode !in enabledSet) continue
            when (mode) {
                GeelyClusterCenterMode.MAP -> if (hasMapSource) available.add(mode)
                GeelyClusterCenterMode.ALBUM -> if (hasMediaAlbum) available.add(mode)
                else -> available.add(mode)
            }
        }
        if (available.isEmpty()) {
            available.add(GeelyClusterCenterMode.CLOCK)
        }

        val currentVisible = when (cfg.centerMode) {
            GeelyClusterCenterMode.MAP -> when {
                hasMapSource -> GeelyClusterCenterMode.MAP
                cfg.albumArtEnabled && hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
                else -> GeelyClusterCenterMode.CLOCK
            }
            GeelyClusterCenterMode.ALBUM -> when {
                hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
                routeNow || hasNativeMap -> GeelyClusterCenterMode.MAP
                else -> GeelyClusterCenterMode.CLOCK
            }
            GeelyClusterCenterMode.CLOCK -> GeelyClusterCenterMode.CLOCK
            GeelyClusterCenterMode.TRIP -> GeelyClusterCenterMode.TRIP
            GeelyClusterCenterMode.TIRE -> GeelyClusterCenterMode.TIRE
            GeelyClusterCenterMode.VEHICLE_INFO -> GeelyClusterCenterMode.VEHICLE_INFO
            GeelyClusterCenterMode.AUTO -> when {
                routeNow || hasNativeMap -> GeelyClusterCenterMode.MAP
                cfg.albumArtEnabled && hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
                else -> GeelyClusterCenterMode.CLOCK
            }
        }

        val currentIdx = available.indexOf(currentVisible)
        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % available.size else 0
        val nextMode = available[nextIdx]
        GeelyClusterPrefs.save(app, cfg.copy(centerMode = nextMode))
        refreshConfig(app)
        onCenterModeChangedByUser(app, cfg.centerMode, nextMode)
        if (!hasCarPlay) {
            GeelyNativeClusterMapObserver.refreshNow()
        }
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
            if (!isFullScreenPresentationActive) {
                ensureNativeMapObserver(appContext)
            }
            currentWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive
                routeActive = isFullScreenPresentationActive && latestGuidance != null
                this.nativeMapProjected = !isFullScreenPresentationActive && this@GeelyClusterEnhancer.nativeMapProjected
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
            if (!isFullScreenPresentationActive) {
                ensureNativeMapObserver(appContext)
            }
            newWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive
                routeActive = isFullScreenPresentationActive && latestGuidance != null
                this.nativeMapProjected = !isFullScreenPresentationActive && this@GeelyClusterEnhancer.nativeMapProjected
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
        stopNativeMapObserver()
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

    internal const val SETTINGS_CARD_TITLE = "吉利仪表与 HUD"
    internal var lastSelectedSettingsTab: Int = 0
    internal var lastStudioTab: Int = 0
    internal var lastPreviewRouteActive: Boolean = true
    internal var lastCalibrationArea: GeelyCalibrationArea = GeelyCalibrationArea.HUD

    /**
     * Appends the redesigned Geely Cluster & HUD settings cards into the dedicated
     * Geely settings page, themed with the main UI's [palette].
     */
    fun appendSettingsCard(
        activity: Activity,
        parent: LinearLayout,
        palette: DiPlayPalette,
        searchIndexSink: MutableList<String>? = null,
        onStateChanged: () -> Unit = {},
    ) {
        if (searchIndexSink != null) {
            searchIndexSink.add(SETTINGS_CARD_TITLE)
            searchIndexSink.add("机甲仪表主题风格")
            searchIndexSink.add("W-HUD 抬头显示引导")
            searchIndexSink.add("方向盘视图循环切换键")
            searchIndexSink.add("中央专辑滚动歌词")
            searchIndexSink.add("车机本地与蓝牙音乐歌词")
            searchIndexSink.add("仪表副屏输出")
            return
        }
        GeelyClusterSettingsPanel(
            activity = activity,
            colors = GeelyClusterUiColors.from(palette),
            studioMode = false,
            onStateChanged = onStateChanged,
        ).appendInlineContent(parent)
    }

    /** Opens the standalone fullscreen live-preview studio dialog for Geely Cluster & HUD settings. */
    fun showSettingsDialog(activity: Activity, onDismiss: () -> Unit = {}) {
        GeelyClusterSettingsDialog(activity, onDismiss).show()
    }
}

