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
    private var nativeMapObserverRegistering: Boolean = false
    private var nativeMapProjected: Boolean = false
    private var syncingPersistentThemeWindow: Boolean = false
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
    private var lastAppliedThemeStyle: GeelyClusterThemeStyle? = null
    private var lastRenderedOnCluster: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bootRetry = GeelyClusterBootRetryController(mainHandler)
    private val callRecoveryTick = object : Runnable {
        override fun run() {
            val app = hostAppContext
            val cfg = app?.let(GeelyClusterPrefs::load)
            if (!isFullScreenPresentationActive || cfg?.dialThemeStyle?.isOemNative == true) {
                wasInPhoneCall = false
                return
            }
            val inCall = isPhoneCallActive(app)
            if (wasInPhoneCall && !inCall) {
                wasInPhoneCall = false
                hostLogger("GeelyDimMenu: phone call ended; restoring full-screen cluster map")
                mainHandler.postDelayed({
                    val curCfg = hostAppContext?.let(GeelyClusterPrefs::load)
                    if (isFullScreenPresentationActive &&
                        curCfg?.dialThemeStyle?.isOemNative != true &&
                        !isPhoneCallActive(hostAppContext)
                    ) {
                        activeDimController?.forceReenterFullScreenMap("post-call")
                    }
                }, 900L)
            } else if (inCall) {
                wasInPhoneCall = true
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun hasActiveMapSourceNow(): Boolean =
        isFullScreenPresentationActive || nativeMapProjected || GeelyNativeClusterMapObserver.isNativeMapProjected()

    private fun shouldRenderThemeOnCluster(
        cfg: GeelySelfDrawConfig,
        assumeCarPlayActive: Boolean = false,
    ): Boolean = when {
        cfg.dialThemeStyle.isOemNative -> false
        cfg.dialThemeStyle.isPureMap -> assumeCarPlayActive || hasActiveMapSourceNow()
        else -> cfg.hasAnyClusterVisual
    }

    private var modeBeforeTelemetryPeek: GeelyClusterCenterMode? = null
    private val navAutoReturnRunnable = Runnable { performNavAutoReturn() }

    private fun cancelNavAutoReturn() {
        mainHandler.removeCallbacks(navAutoReturnRunnable)
        modeBeforeTelemetryPeek = null
    }

    fun isTelemetryPageActive(context: Context): Boolean =
        GeelyClusterPrefs.load(context).centerMode.isTelemetryPage

    private fun resolveFallbackPrimaryMode(cfg: GeelySelfDrawConfig): GeelyClusterCenterMode {
        val hasCarPlay = isFullScreenPresentationActive
        val hasNativeMap = !hasCarPlay && (nativeMapProjected || GeelyNativeClusterMapObserver.isNativeMapProjected())
        val hasMap = hasCarPlay || hasNativeMap
        val hasArtwork = currentFakeUltraView?.hasUsableArtwork() == true
        val hasMediaAlbum = hasArtwork && (hasCarPlay || carPlayHostAttached || lastVehicleMediaSnapshot.hasContent)
        return when {
            hasMap -> GeelyClusterCenterMode.MAP
            cfg.albumArtEnabled && hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
            else -> GeelyClusterCenterMode.AUTO
        }
    }

    /**
     * When the driver switches to a telemetry page (`TRIP` / `TIRE` / `VEHICLE_INFO`), automatically
     * closes the telemetry view and returns to the previous primary mode (`MAP` / `ALBUM` / `CLOCK`
     * / `AUTO`) after [GeelySelfDrawConfig.navAutoReturnSeconds] seconds. Each subsequent switch
     * resets the timer.
     */
    fun onCenterModeChangedByUser(
        context: Context,
        previousMode: GeelyClusterCenterMode?,
        nextMode: GeelyClusterCenterMode,
    ) {
        val app = context.applicationContext
        hostAppContext = app
        runOnMain {
            mainHandler.removeCallbacks(navAutoReturnRunnable)
            if (!nextMode.isTelemetryPage) {
                modeBeforeTelemetryPeek = null
                return@runOnMain
            }
            val cfg = GeelyClusterPrefs.load(app)
            if (cfg.navAutoReturnSeconds <= 0) {
                modeBeforeTelemetryPeek = null
                return@runOnMain
            }
            if (modeBeforeTelemetryPeek == null && previousMode != null && !previousMode.isTelemetryPage) {
                modeBeforeTelemetryPeek = previousMode
            }
            val targetReturn = modeBeforeTelemetryPeek ?: resolveFallbackPrimaryMode(cfg)
            val delaySec = cfg.navAutoReturnSeconds
            hostLogger(
                "GeelyCluster: telemetry auto-close to ${targetReturn.name} in ${delaySec}s " +
                    "(from=${nextMode.name}, carPlayMap=$isFullScreenPresentationActive, nativeMap=$nativeMapProjected)",
            )
            mainHandler.postDelayed(navAutoReturnRunnable, delaySec * 1000L)
        }
    }

    private fun performNavAutoReturn() {
        val app = hostAppContext ?: return
        val cfg = GeelyClusterPrefs.load(app)
        if (cfg.navAutoReturnSeconds <= 0 || !cfg.centerMode.isTelemetryPage) {
            modeBeforeTelemetryPeek = null
            return
        }
        closeTelemetryPeek(app, cfg, reason = "timeout")
    }

    private fun closeTelemetryPeek(
        app: Context,
        cfg: GeelySelfDrawConfig,
        reason: String,
    ): GeelyClusterCenterMode {
        mainHandler.removeCallbacks(navAutoReturnRunnable)
        val returnMode = modeBeforeTelemetryPeek?.takeUnless { it.isTelemetryPage }
            ?: resolveFallbackPrimaryMode(cfg)
        modeBeforeTelemetryPeek = null
        hostLogger("GeelyCluster: telemetry closed ($reason) -> ${returnMode.name} from ${cfg.centerMode.name}")
        GeelyClusterPrefs.save(app, cfg.copy(centerMode = returnMode))
        refreshConfig(app)
        return returnMode
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
        if (nativeMapSubscription != null || nativeMapObserverRegistering) {
            if (nativeMapSubscription != null) {
                GeelyNativeClusterMapObserver.refreshNow()
            }
            return
        }
        nativeMapObserverRegistering = true
        try {
            nativeMapSubscription = GeelyNativeClusterMapObserver.observe(app, hostLogger) { projected ->
                val changed = nativeMapProjected != projected
                nativeMapProjected = projected
                runOnMain { applyNativeMapState(stateChanged = changed) }
            }
        } finally {
            nativeMapObserverRegistering = false
        }
    }

    private fun stopNativeMapObserver() {
        nativeMapSubscription?.invoke()
        nativeMapSubscription = null
        nativeMapObserverRegistering = false
        nativeMapProjected = false
        runOnMain { currentFakeUltraView?.nativeMapProjected = false }
    }

    private fun applyNativeMapState(stateChanged: Boolean = false) {
        val activeNative = !isFullScreenPresentationActive && nativeMapProjected
        currentFakeUltraView?.nativeMapProjected = activeNative
        val app = hostAppContext ?: return
        if (!isFullScreenPresentationActive) {
            val cfg = GeelyClusterPrefs.load(app)
            if (cfg.dialThemeStyle.isPureMap && (stateChanged || (activeNative && !isPersistentThemeActive()))) {
                mainHandler.post {
                    val curCfg = GeelyClusterPrefs.load(app)
                    if (!isFullScreenPresentationActive && curCfg.dialThemeStyle.isPureMap) {
                        val nowActive = nativeMapProjected
                        syncPersistentThemeWindow(app, requestFullScreen = nowActive)
                    }
                }
            }
        }
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
            cfg.dialThemeStyle.isOemNative -> cfg.dialThemeStyle.shortLabel
            cfg.dialThemeStyle.isPureMap -> cfg.dialThemeStyle.shortLabel
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
        val app = hostAppContext
        val cfg = app?.let(GeelyClusterPrefs::load)
        val keepThemeAlive = cfg?.hasClusterThemeCapability == true
        if (keepThemeAlive && app != null) {
            if (cfg.dialThemeStyle.usesDialChrome) {
                applyVehicleMediaIfStandby()
                ensureNativeMapObserver(app)
            } else if (cfg.dialThemeStyle.isPureMap) {
                stopVehicleMediaObserver()
                ensureNativeMapObserver(app)
            } else {
                stopVehicleMediaObserver()
                stopNativeMapObserver()
            }
        } else {
            stopVehicleMediaObserver()
            stopNativeMapObserver()
            hostNightObserverRemoval?.invoke()
            hostNightObserverRemoval = null
            vehicleDayNightRemoval?.invoke()
            vehicleDayNightRemoval = null
            app?.let {
                runCatching { GeelyClusterPrefs.prefs(it).unregisterOnSharedPreferenceChangeListener(prefsListener) }
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
        EcarxKx11WheelKeys.ensureDefaultPresets(app)
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
     * Called from [GeelyClusterKeepAliveService.onStartCommand] after `ACTION_BOOT_COMPLETED`
     * to bring up the persistent cluster theme silently in the background without opening
     * `DiPlayActivity`. Retries until the instrument display and DIM service finish booting.
     */
    fun onBootKeepAliveStarted(context: Context) {
        val app = context.applicationContext
        hostAppContext = app
        EcarxKx11WheelKeys.ensureDefaultPresets(app)
        GeelyClusterProjection.releaseStale(app)
        GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(app)
        val initialCfg = GeelyClusterPrefs.load(app)
        if (initialCfg.dialThemeStyle.usesDialChrome) {
            ensureVehicleMediaObserver(app)
        }
        runOnMain {
            val cfgNow = GeelyClusterPrefs.load(app)
            if (cfgNow.dialThemeStyle.isOemNative || cfgNow.dialThemeStyle.isPureMap) {
                // OEM Classic and Pure Map without an active map source stay cleanly on native
                // QNX classic cluster while keeping wheel-key & native-map observers alive.
                syncPersistentThemeWindow(app, requestFullScreen = false)
                return@runOnMain
            }
            bootRetry.start(
                context = app,
                shouldContinue = {
                    val cfg = GeelyClusterPrefs.load(app)
                    GeelyVehicleDetector.isGeelyOrEcarx(app) &&
                        cfg.launchThemeOnStart &&
                        cfg.dialThemeStyle.usesDialChrome &&
                        cfg.hasAnyClusterVisual &&
                        !isFullScreenPresentationActive
                },
                tryAttach = {
                    syncPersistentThemeWindow(
                        appContext = app,
                        requestFullScreen = true,
                        keepAliveWhenDisplayPending = true,
                    )
                    isPersistentThemeActive()
                },
                ensureDimFullScreen = {
                    if (!isPersistentThemeActive()) {
                        false
                    } else {
                        val controller = ensureDimController(app)
                        val mode = controller.getNaviMode()
                        if (mode == GeelyDimMenuController.MODE_FULLSCREEN_MAP) {
                            true
                        } else {
                            controller.forceReenterFullScreenMap("boot-retry")
                            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, app)
                            false
                        }
                    }
                },
                onGiveUp = {
                    if (!isPersistentThemeActive()) {
                        shutdownPersistentTheme(app, handBackIfIdle = false)
                    }
                },
            )
        }
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
            shutdownPersistentTheme(app, handBackIfIdle = true, allowRecallStandby = false)
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

        val cfg = GeelyClusterPrefs.load(context)
        val effectiveFullScreen = presentationShowing && !cfg.dialThemeStyle.isOemNative
        val routeActive = guidance != null
        currentFakeUltraView?.apply {
            carPlayConnected = presentationShowing
            this.routeActive = presentationShowing && routeActive
        }

        syncStandaloneOverlayWindow(context.applicationContext)

        overlayWindow?.view?.let { v ->
            v.fullScreen = effectiveFullScreen
            v.guidance = guidance
            v.lanes = latestLanes
            v.invalidate()
        }

        activePresentationSelfDrawView?.let { v ->
            v.visibility = if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
            v.fullScreen = effectiveFullScreen
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

        val oemNative = config.dialThemeStyle.isOemNative
        if (!oemNative && config.launchThemeOnStart) {
            syncPersistentThemeWindow(
                appContext = context.applicationContext,
                requestFullScreen = false,
                assumeCarPlayActive = true,
            )
        }

        val fakeUltraView: GeelyFakeUltraOverlayView? = if (!oemNative && isPersistentThemeActive()) {
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
                this.visibility = if (oemNative) View.GONE else View.VISIBLE
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
            this.fullScreen = !oemNative
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
        val hideDials = config.dialThemeStyle.isPureMap ||
            config.dialThemeStyle.isOemNative ||
            config.dialLeftEnabled ||
            config.dialRightEnabled ||
            config.albumArtEnabled
        val hideTurnCard = config.fullscreenCardEnabled || config.dialThemeStyle.isOemNative
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is ClusterSpeedometerView, is ClusterMusicView -> {
                    child.alpha = if (hideDials) 0f else 1f
                }
                is ClusterTurnCardView -> {
                    child.alpha = if (hideTurnCard) 0f else 1f
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
        val app = context.applicationContext
        val cfg = GeelyClusterPrefs.load(app)
        isFullScreenPresentationActive = true
        bootRetry.cancel()
        stopNativeMapObserver()
        GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
        ensureHostNightObserver()
        ensureVehicleDayNightObserver(app)

        if (!cfg.dialThemeStyle.isOemNative && cfg.launchThemeOnStart) {
            syncPersistentThemeWindow(app, requestFullScreen = false, assumeCarPlayActive = true)
        }

        currentFakeUltraView?.apply {
            carPlayConnected = true
            externalMediaActive = false
            routeActive = latestGuidance != null
            invalidate()
        }

        stopCarPlayMusicSubscription()
        ensureCarPlayMusicSubscription()

        val controller = ensureDimController(app)
        if (cfg.dialThemeStyle.isOemNative) {
            // Respect OEM Classic theme: keep native QNX cluster active while retaining W-HUD & classic card.
            closePersistentThemeWindow()
            activeFakeUltraView?.visibility = View.GONE
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = false
            activeDimWatcher?.close()
            activeDimWatcher = null
            mainHandler.removeCallbacks(callRecoveryTick)
            wasInPhoneCall = false
            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, app)
            syncStandaloneOverlayWindow(app)
            overlayWindow?.view?.fullScreen = false
            activePresentationSelfDrawView?.fullScreen = false
            syncClusterWheelKeyActive(app)
            return
        }

        controller.forceReenterFullScreenMap("presentation-start")
        lastAppliedThemeStyle = cfg.dialThemeStyle
        lastRenderedOnCluster = true

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

        syncStandaloneOverlayWindow(app)
        overlayWindow?.view?.fullScreen = true
        activePresentationSelfDrawView?.fullScreen = true
        syncClusterWheelKeyActive(app)
    }

    /** Called in `ClusterMapPresentation.onStop()`. */
    fun onPresentationStopped() {
        isFullScreenPresentationActive = false
        wasInPhoneCall = false
        mainHandler.removeCallbacks(callRecoveryTick)
        cancelNavAutoReturn()

        activeDimWatcher?.close()
        activeDimWatcher = null

        val app = hostAppContext
        val cfg = app?.let(GeelyClusterPrefs::load)
        val keepBackgroundGuidance = CarPlayBackgroundSession.hasSession() &&
            GeelyVehicleDetector.isGeelyOrEcarx(app)
        val keepCarPlaySession = carPlayHostAttached || keepBackgroundGuidance
        if (!keepBackgroundGuidance) {
            latestGuidance = null
            latestLanes = emptyList()
        }

        activeFakeUltraView = null
        activePresentationSelfDrawView = null
        activePresentationInnerRoot = null

        val keepMechaWindowAlive = isPersistentThemeActive() &&
            cfg?.launchThemeOnStart == true &&
            cfg.dialThemeStyle.usesDialChrome

        if (!keepCarPlaySession) {
            stopCarPlayMusicSubscription()
            currentFakeUltraView?.clearCarPlayData()
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
        }

        when {
            keepMechaWindowAlive && app != null -> {
                ensureNativeMapObserver(app)
                if (!keepCarPlaySession || !carPlayTrackActive) {
                    applyVehicleMediaIfStandby()
                }
            }
            cfg?.dialThemeStyle?.isPureMap == true && app != null -> {
                // Pure Map automatically falls back to OEM Classic when CarPlay disconnects
                // and no native head-unit map is projected, while keeping standby observers alive.
                closePersistentThemeWindow()
                lastRenderedOnCluster = false
                stopVehicleMediaObserver()
                if (cfg.hasClusterThemeCapability) {
                    ensureNativeMapObserver(app)
                    GeelyClusterKeepAliveService.start(app)
                } else {
                    stopNativeMapObserver()
                }
                activeDimController?.restoreClassicCluster()
                if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, app)
            }
            cfg?.dialThemeStyle?.isOemNative == true && app != null -> {
                closePersistentThemeWindow()
                lastRenderedOnCluster = false
                stopNativeMapObserver()
                stopVehicleMediaObserver()
                if (cfg.hasClusterThemeCapability) {
                    GeelyClusterKeepAliveService.start(app)
                }
            }
            else -> {
                lastRenderedOnCluster = false
                stopNativeMapObserver()
                activeDimController?.restoreClassicCluster()
                if (app != null && GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, app)
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
        app?.let(::syncClusterWheelKeyActive)
    }

    /**
     * Cycles the persistent primary center display content across [GeelySelfDrawConfig.primaryCycleModes]
     * (`MAP`, `ALBUM`, `CLOCK`), automatically skipping `MAP` when no map projection is active and
     * skipping `ALBUM` when no artwork is available.
     *
     * If a telemetry peek page is currently open, returns directly to the primary view that was
     * showing before opening telemetry.
     */
    fun cyclePrimaryCenterMode(context: Context): String {
        val app = context.applicationContext
        hostAppContext = app
        val cfg = GeelyClusterPrefs.load(app)
        ensureClusterThemeVisibleForWheelKey(app, cfg)?.let { return it }
        if (cfg.centerMode.isTelemetryPage) {
            val returned = closeTelemetryPeek(app, cfg, reason = "primary-key")
            if (!isFullScreenPresentationActive) {
                GeelyNativeClusterMapObserver.refreshNow()
            }
            return returned.name
        }

        val hasCarPlay = isFullScreenPresentationActive
        val hasNativeMap = !hasCarPlay && (nativeMapProjected || GeelyNativeClusterMapObserver.isNativeMapProjected())
        val hasMapSource = hasCarPlay || hasNativeMap
        val hasArtwork = currentFakeUltraView?.hasUsableArtwork() == true
        val hasMediaAlbum = hasArtwork && (hasCarPlay || carPlayHostAttached || lastVehicleMediaSnapshot.hasContent)
        val routeNow = hasCarPlay && latestGuidance != null

        val enabledSet = cfg.primaryCycleModes
        val available = ArrayList<GeelyClusterCenterMode>(GeelyClusterCenterMode.PRIMARY_ORDER.size)
        for (mode in GeelyClusterCenterMode.PRIMARY_ORDER) {
            if (mode !in enabledSet) continue
            when (mode) {
                GeelyClusterCenterMode.MAP -> if (hasMapSource) available.add(mode)
                GeelyClusterCenterMode.ALBUM -> if (hasMediaAlbum) available.add(mode)
                else -> available.add(mode)
            }
        }
        if (available.isEmpty()) {
            when {
                hasMapSource -> available.add(GeelyClusterCenterMode.MAP)
                hasMediaAlbum -> available.add(GeelyClusterCenterMode.ALBUM)
                else -> available.add(GeelyClusterCenterMode.CLOCK)
            }
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
            GeelyClusterCenterMode.AUTO -> when {
                routeNow || hasNativeMap -> GeelyClusterCenterMode.MAP
                cfg.albumArtEnabled && hasMediaAlbum -> GeelyClusterCenterMode.ALBUM
                else -> GeelyClusterCenterMode.CLOCK
            }
            else -> available.first()
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

    /**
     * Opens or advances the temporary telemetry peek pages (`TRIP` / `TIRE` / `VEHICLE_INFO`).
     * Automatically closes and restores the prior primary view after [GeelySelfDrawConfig.navAutoReturnSeconds]
     * seconds of inactivity, or immediately after the last enabled page when [closeAfterLast] is `true`.
     */
    fun cycleTelemetryCenterMode(context: Context, closeAfterLast: Boolean = false): String {
        val app = context.applicationContext
        hostAppContext = app
        val cfg = GeelyClusterPrefs.load(app)
        ensureClusterThemeVisibleForWheelKey(app, cfg)?.let { return it }
        val telemetryList = GeelyClusterCenterMode.TELEMETRY_ORDER
            .filter { it in cfg.telemetryCycleModes }
            .ifEmpty { listOf(GeelyClusterCenterMode.TRIP) }

        if (!cfg.centerMode.isTelemetryPage) {
            val nextMode = telemetryList.first()
            GeelyClusterPrefs.save(app, cfg.copy(centerMode = nextMode))
            refreshConfig(app)
            onCenterModeChangedByUser(app, cfg.centerMode, nextMode)
            return nextMode.name
        }

        val currentIdx = telemetryList.indexOf(cfg.centerMode)
        if (closeAfterLast && currentIdx == telemetryList.lastIndex) {
            val returned = closeTelemetryPeek(app, cfg, reason = "cycle-end")
            if (!isFullScreenPresentationActive) {
                GeelyNativeClusterMapObserver.refreshNow()
            }
            return returned.name
        }

        val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % telemetryList.size else 0
        val nextMode = telemetryList[nextIdx]
        GeelyClusterPrefs.save(app, cfg.copy(centerMode = nextMode))
        refreshConfig(app)
        onCenterModeChangedByUser(app, null, nextMode)
        return nextMode.name
    }

    /** Backwards-compatible alias for cycling the primary cluster center display. */
    fun cycleCenterMode(context: Context): String = cyclePrimaryCenterMode(context)

    /**
     * Cycles the active cluster theme across [GeelySelfDrawConfig.themeCycleStyles]
     * (e.g. `OEM_CLASSIC` -> `PURE_MAP` -> `AERO_MECHA`).
     *
     * When no map source is active (`CarPlay` disconnected and no native map projection), `PURE_MAP`
     * automatically falls back to OEM classic visually, so wheel-key cycling skips `PURE_MAP` when
     * other themes are enabled to guarantee every press yields an immediate visible change.
     */
    fun cycleThemeStyle(context: Context): String {
        val app = context.applicationContext
        hostAppContext = app
        val cfg = GeelyClusterPrefs.load(app)
        val hasMapSource = hasActiveMapSourceNow()

        val orderedEnabled = GeelyClusterThemeStyle.entries
            .filter { it in cfg.themeCycleStyles }
            .ifEmpty { GeelyClusterThemeStyle.entries.filter { it in GeelyClusterThemeStyle.DEFAULT_CYCLE_STYLES } }

        val candidates = if (!hasMapSource) {
            val filtered = orderedEnabled.filterNot { it.isPureMap }
            if (filtered.isNotEmpty()) filtered else orderedEnabled
        } else {
            orderedEnabled
        }

        val currentIdx = candidates.indexOf(cfg.dialThemeStyle)
        val nextStyle = if (currentIdx >= 0) {
            candidates[(currentIdx + 1) % candidates.size]
        } else {
            candidates.first()
        }

        cancelNavAutoReturn()
        val nextCenterMode = if (cfg.centerMode.isTelemetryPage) {
            resolveFallbackPrimaryMode(cfg)
        } else {
            cfg.centerMode
        }
        val updated = cfg.copy(
            dialThemeStyle = nextStyle,
            centerMode = nextCenterMode,
        )
        GeelyClusterPrefs.save(app, updated)
        refreshConfig(app)
        if (!isFullScreenPresentationActive && nextStyle.isPureMap) {
            GeelyNativeClusterMapObserver.refreshNow()
        }
        return nextStyle.name
    }

    /**
     * Ensures the cluster theme overlay is attached and the instrument panel is in full-screen
     * projection mode (`Mode 3`) when the driver presses a cluster view-switch wheel key.
     *
     * After an ignition off/on cycle (or manual switch back to classic cluster), the head-unit
     * Android process stays alive while the cluster MCU resets to classic mode (`Mode 1`) or drops
     * the display handle. When that is detected, this press recalls/reloads the cluster theme
     * without advancing the page; subsequent presses switch pages normally.
     *
     * @return a diagnostic status string if this press was consumed to recall/load the theme,
     * or `null` when the theme is already visible and the caller should proceed with page cycling.
     */
    private fun ensureClusterThemeVisibleForWheelKey(app: Context, cfg: GeelySelfDrawConfig): String? {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(app) || !shouldRenderThemeOnCluster(cfg)) {
            return if (cfg.dialThemeStyle.isOemNative || cfg.dialThemeStyle.isPureMap) {
                "IGNORED(oem-standby)"
            } else {
                null
            }
        }

        val currentWin = persistentThemeWindow
        val windowValid = currentWin != null && currentWin.attached &&
            runCatching { currentWin.display.isValid }.getOrDefault(false)

        if (!isFullScreenPresentationActive && !windowValid) {
            if (!cfg.launchThemeOnStart) return null
            GeelyClusterProjection.releaseStale(app)
            GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureHostNightObserver()
            ensureVehicleDayNightObserver(app)
            if (cfg.dialThemeStyle.usesDialChrome) ensureVehicleMediaObserver(app)
            syncPersistentThemeWindow(app, requestFullScreen = true)
            return if (isPersistentThemeActive()) "RECALL(window-attach)" else "RECALL(window-unavailable)"
        }

        val controller = ensureDimController(app)
        val naviMode = controller.getNaviMode() ?: run {
            controller.resetCachedHandle()
            controller.getNaviMode()
        }
        if (naviMode != null && naviMode != GeelyDimMenuController.MODE_FULLSCREEN_MAP) {
            currentFakeUltraView?.invalidate()
            controller.forceReenterFullScreenMap("wheel-key-recall")
            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, app)
            return "RECALL(dim-mode-$naviMode->3)"
        }
        return null
    }

    private fun syncClusterWheelKeyActive(context: Context, allowRecallStandby: Boolean = true) {
        val cfg = GeelyClusterPrefs.load(context)
        val keepListeningForRecall = allowRecallStandby &&
            GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            (cfg.hasClusterThemeCapability || cfg.themeSwitchKeyCode > 0)
        val active = isFullScreenPresentationActive || isPersistentThemeActive() || keepListeningForRecall
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
            val effectiveFullScreen = isFullScreenPresentationActive && !cfg.dialThemeStyle.isOemNative
            syncPersistentThemeWindow(appCtx, requestFullScreen = false)
            activeFakeUltraView?.apply {
                config = cfg
                nightMode = effectiveNight
                visibility = if (cfg.dialThemeStyle.isOemNative || isPersistentThemeActive()) View.GONE else View.VISIBLE
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
                fullScreen = effectiveFullScreen
                invalidate()
            }
            syncStockClusterViewsVisibility(activePresentationInnerRoot, cfg)
            syncStandaloneOverlayWindow(appCtx)
            overlayWindow?.view?.apply {
                config = cfg
                nightMode = effectiveNight
                fullScreen = effectiveFullScreen
                invalidate()
            }
            activePresentationSelfDrawView?.visibility =
                if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
            syncClusterWheelKeyActive(appCtx)
            EcarxKx11WheelKeys.settingsChanged()
        }
    }

    private fun syncPersistentThemeWindow(
        appContext: Context,
        requestFullScreen: Boolean,
        keepAliveWhenDisplayPending: Boolean = false,
        assumeCarPlayActive: Boolean = false,
    ) {
        if (syncingPersistentThemeWindow) return
        syncingPersistentThemeWindow = true
        try {
            syncPersistentThemeWindowInternal(
                appContext = appContext,
                requestFullScreen = requestFullScreen,
                keepAliveWhenDisplayPending = keepAliveWhenDisplayPending,
                assumeCarPlayActive = assumeCarPlayActive,
            )
        } finally {
            syncingPersistentThemeWindow = false
        }
    }

    private fun syncPersistentThemeWindowInternal(
        appContext: Context,
        requestFullScreen: Boolean,
        keepAliveWhenDisplayPending: Boolean = false,
        assumeCarPlayActive: Boolean = false,
    ) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(appContext)) {
            shutdownPersistentTheme(appContext, handBackIfIdle = false)
            return
        }
        val cfg = GeelyClusterPrefs.load(appContext)
        val prevStyle = lastAppliedThemeStyle
        val styleChanged = prevStyle != null && prevStyle != cfg.dialThemeStyle
        val shouldRender = shouldRenderThemeOnCluster(cfg, assumeCarPlayActive)
        val wasRendered = lastRenderedOnCluster
        val wasAttached = isPersistentThemeActive()

        if (!cfg.hasClusterThemeCapability) {
            shutdownPersistentTheme(appContext, handBackIfIdle = !isFullScreenPresentationActive)
            // Even without persistent theme enabled, support live switching between OEM Classic,
            // Pure Map, and Mecha themes while a CarPlay presentation is active.
            if (isFullScreenPresentationActive) {
                if (cfg.dialThemeStyle.isOemNative) {
                    activeFakeUltraView?.visibility = View.GONE
                    overlayWindow?.view?.fullScreen = false
                    activePresentationSelfDrawView?.fullScreen = false
                    mainHandler.removeCallbacks(callRecoveryTick)
                    if (wasRendered || styleChanged) {
                        ensureDimController(appContext).restoreClassicCluster()
                        if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, appContext)
                    }
                    lastRenderedOnCluster = false
                } else {
                    activeFakeUltraView?.visibility = View.VISIBLE
                    overlayWindow?.view?.fullScreen = true
                    activePresentationSelfDrawView?.fullScreen = true
                    if (!wasRendered || (styleChanged && prevStyle?.isOemNative == true)) {
                        ensureDimController(appContext).forceReenterFullScreenMap("theme-switch")
                        if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, appContext)
                        mainHandler.removeCallbacks(callRecoveryTick)
                        mainHandler.postDelayed(callRecoveryTick, 1000L)
                    }
                    lastRenderedOnCluster = true
                }
            } else {
                lastRenderedOnCluster = false
            }
            lastAppliedThemeStyle = cfg.dialThemeStyle
            return
        }

        if (!shouldRender) {
            // Persistent standby mode: OEM Classic or Pure Map waiting for a map source.
            // Keep foreground service & wheel keys alive while handing the cluster cleanly to OEM Mode 1.
            bootRetry.cancel()
            closePersistentThemeWindow()
            activeFakeUltraView?.visibility = View.GONE
            if (cfg.dialThemeStyle.isPureMap && !isFullScreenPresentationActive) {
                ensureNativeMapObserver(appContext)
                stopVehicleMediaObserver()
            } else {
                stopNativeMapObserver()
                stopVehicleMediaObserver()
            }
            overlayWindow?.view?.fullScreen = false
            activePresentationSelfDrawView?.fullScreen = false
            mainHandler.removeCallbacks(callRecoveryTick)
            GeelyClusterKeepAliveService.start(appContext)
            syncClusterWheelKeyActive(appContext)
            if (wasAttached || wasRendered || (styleChanged && (cfg.dialThemeStyle.isOemNative || cfg.dialThemeStyle.isPureMap))) {
                ensureDimController(appContext).restoreClassicCluster()
                if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(false, appContext)
            }
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = false
            return
        }

        val targetDisplay = GeelyVehicleDetector.findClusterDisplay(appContext)
        if (targetDisplay == null) {
            if (keepAliveWhenDisplayPending) {
                closePersistentThemeWindow()
            } else {
                shutdownPersistentTheme(appContext, handBackIfIdle = false)
            }
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = false
            return
        }
        val effectiveNight = resolveEffectiveNightMode(cfg, appContext)
        val currentWindow = persistentThemeWindow
        val currentDisplayValid = currentWindow != null &&
            runCatching { currentWindow.display.isValid }.getOrDefault(true)
        if (currentWindow != null && currentWindow.attached && currentDisplayValid &&
            currentWindow.display.displayId == targetDisplay.displayId
        ) {
            if (cfg.dialThemeStyle.usesDialChrome) {
                ensureVehicleMediaObserver(appContext)
            } else {
                stopVehicleMediaObserver()
            }
            if (!isFullScreenPresentationActive && !assumeCarPlayActive) {
                ensureNativeMapObserver(appContext)
            }
            currentWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive || assumeCarPlayActive
                routeActive = (isFullScreenPresentationActive || assumeCarPlayActive) && latestGuidance != null
                this.nativeMapProjected = !isFullScreenPresentationActive && !assumeCarPlayActive && this@GeelyClusterEnhancer.nativeMapProjected
                if (!isFullScreenPresentationActive && !assumeCarPlayActive && cfg.dialThemeStyle.usesDialChrome) {
                    applyVehicleMedia(lastVehicleMediaSnapshot)
                }
                invalidate()
            }
            GeelyClusterKeepAliveService.start(appContext)
            syncClusterWheelKeyActive(appContext)
            if (requestFullScreen || !wasRendered || (styleChanged && prevStyle?.isOemNative == true)) {
                ensureDimController(appContext).forceReenterFullScreenMap(
                    if (styleChanged) "theme-switch" else "theme-launch",
                )
                if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, appContext)
            }
            if (isFullScreenPresentationActive) {
                overlayWindow?.view?.fullScreen = true
                activePresentationSelfDrawView?.fullScreen = true
                mainHandler.removeCallbacks(callRecoveryTick)
                mainHandler.postDelayed(callRecoveryTick, 1000L)
            }
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = true
            return
        }
        closePersistentThemeWindow()
        val newWindow = GeelyClusterThemeWindow(appContext, targetDisplay, hostLogger)
        if (newWindow.attach()) {
            if (cfg.dialThemeStyle.usesDialChrome) {
                ensureVehicleMediaObserver(appContext)
            } else {
                stopVehicleMediaObserver()
            }
            if (!isFullScreenPresentationActive && !assumeCarPlayActive) {
                ensureNativeMapObserver(appContext)
            }
            newWindow.view.apply {
                config = cfg
                nightMode = effectiveNight
                carPlayConnected = isFullScreenPresentationActive || assumeCarPlayActive
                routeActive = (isFullScreenPresentationActive || assumeCarPlayActive) && latestGuidance != null
                this.nativeMapProjected = !isFullScreenPresentationActive && !assumeCarPlayActive && this@GeelyClusterEnhancer.nativeMapProjected
                if (!isFullScreenPresentationActive && !assumeCarPlayActive && cfg.dialThemeStyle.usesDialChrome) {
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
                when {
                    styleChanged -> "theme-switch"
                    requestFullScreen -> "app-launch"
                    else -> "theme-enabled"
                },
            )
            if (GeelyClusterDisplay.supported()) GeelyClusterProjection.set(true, appContext)
            if (isFullScreenPresentationActive) {
                overlayWindow?.view?.fullScreen = true
                activePresentationSelfDrawView?.fullScreen = true
                mainHandler.removeCallbacks(callRecoveryTick)
                mainHandler.postDelayed(callRecoveryTick, 1000L)
            }
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = true
        } else {
            newWindow.close()
            lastAppliedThemeStyle = cfg.dialThemeStyle
            lastRenderedOnCluster = false
        }
    }

    private fun shutdownPersistentTheme(
        appContext: Context,
        handBackIfIdle: Boolean,
        allowRecallStandby: Boolean = true,
    ) {
        bootRetry.cancel()
        val wasAttached = isPersistentThemeActive()
        lastRenderedOnCluster = false
        stopVehicleMediaObserver()
        stopNativeMapObserver()
        closePersistentThemeWindow()
        val isOem = GeelyClusterPrefs.load(appContext).dialThemeStyle.isOemNative
        activeFakeUltraView?.visibility = if (isOem) View.GONE else View.VISIBLE
        GeelyClusterKeepAliveService.stop(appContext)
        syncClusterWheelKeyActive(appContext, allowRecallStandby = allowRecallStandby)
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

    internal const val SETTINGS_CARD_TITLE = "全境仪表与 HUD"
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
            searchIndexSink.add("纯地图全屏主题")
            searchIndexSink.add("原车经典仪表主题")
            searchIndexSink.add("方向盘主题切换快捷键")
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

