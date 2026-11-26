package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class GeelyAmapKeyTrigger(
    val displayName: String,
    val shortLabel: String,
) {
    DOUBLE_CLICK("双击", "双击"),
    SHORT_CLICK("单击", "单击"),
    LONG_PRESS("长按", "长按");

    fun next(): GeelyAmapKeyTrigger = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(raw: String?): GeelyAmapKeyTrigger =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DOUBLE_CLICK
    }
}

internal data class GeelyAmapOverviewConfig(
    val autoOverviewOnNavStart: Boolean = true,
    val autoOverviewDelayMs: Int = DEFAULT_AUTO_DELAY_MS,
    val gestureEnabled: Boolean = true,
    val wheelKeyCode: Int = 0,
    val wheelKeyTrigger: GeelyAmapKeyTrigger = GeelyAmapKeyTrigger.DOUBLE_CLICK,
    val visualCheckEnabled: Boolean = true,
    val buttonX: Float = DEFAULT_BUTTON_X,
    val buttonY: Float = DEFAULT_BUTTON_Y,
    val calibrated: Boolean = false,
    val wakeX: Float = DEFAULT_WAKE_X,
    val wakeY: Float = DEFAULT_WAKE_Y,
    val dayLaneSample: String? = null,
    val dayOverviewSample: String? = null,
    val nightLaneSample: String? = null,
    val nightOverviewSample: String? = null,
) {
    val hasDaySamples: Boolean get() = !dayLaneSample.isNullOrEmpty()
    val hasNightSamples: Boolean get() = !nightLaneSample.isNullOrEmpty()

    companion object {
        const val DEFAULT_AUTO_DELAY_MS = 2500
        const val DEFAULT_BUTTON_X = 0.945f
        const val DEFAULT_BUTTON_Y = 0.145f
        const val DEFAULT_WAKE_X = 0.55f
        const val DEFAULT_WAKE_Y = 0.50f
        val DELAY_PRESETS_MS = listOf(1500, 2500, 3500, 5000)
    }
}

internal object GeelyAmapOverviewPrefs {
    private const val PREFS_NAME = "geely_amap_overview"
    private const val KEY_AUTO_ON_NAV = "auto_overview_on_nav_start"
    private const val KEY_AUTO_DELAY_MS = "auto_overview_delay_ms"
    private const val KEY_GESTURE_ENABLED = "two_finger_gesture_enabled"
    private const val KEY_WHEEL_KEY_CODE = "wheel_key_code"
    private const val KEY_WHEEL_KEY_TRIGGER = "wheel_key_trigger"
    private const val KEY_VISUAL_CHECK = "visual_check_enabled"
    private const val KEY_BUTTON_X = "button_x"
    private const val KEY_BUTTON_Y = "button_y"
    private const val KEY_CALIBRATED = "calibrated"
    private const val KEY_WAKE_X = "wake_x"
    private const val KEY_WAKE_Y = "wake_y"
    private const val KEY_DAY_LANE_SAMPLE = "day_lane_sample"
    private const val KEY_DAY_OVERVIEW_SAMPLE = "day_overview_sample"
    private const val KEY_NIGHT_LANE_SAMPLE = "night_lane_sample"
    private const val KEY_NIGHT_OVERVIEW_SAMPLE = "night_overview_sample"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GeelyAmapOverviewConfig {
        val sp = prefs(context)
        return GeelyAmapOverviewConfig(
            autoOverviewOnNavStart = sp.getBoolean(KEY_AUTO_ON_NAV, true),
            autoOverviewDelayMs = sp.getInt(KEY_AUTO_DELAY_MS, GeelyAmapOverviewConfig.DEFAULT_AUTO_DELAY_MS)
                .coerceIn(500, 10_000),
            gestureEnabled = sp.getBoolean(KEY_GESTURE_ENABLED, true),
            wheelKeyCode = sp.getInt(KEY_WHEEL_KEY_CODE, 0),
            wheelKeyTrigger = GeelyAmapKeyTrigger.fromName(sp.getString(KEY_WHEEL_KEY_TRIGGER, null)),
            visualCheckEnabled = sp.getBoolean(KEY_VISUAL_CHECK, true),
            buttonX = sp.getFloat(KEY_BUTTON_X, GeelyAmapOverviewConfig.DEFAULT_BUTTON_X).coerceIn(0.02f, 0.98f),
            buttonY = sp.getFloat(KEY_BUTTON_Y, GeelyAmapOverviewConfig.DEFAULT_BUTTON_Y).coerceIn(0.02f, 0.98f),
            calibrated = sp.getBoolean(KEY_CALIBRATED, false),
            wakeX = sp.getFloat(KEY_WAKE_X, GeelyAmapOverviewConfig.DEFAULT_WAKE_X).coerceIn(0.1f, 0.9f),
            wakeY = sp.getFloat(KEY_WAKE_Y, GeelyAmapOverviewConfig.DEFAULT_WAKE_Y).coerceIn(0.1f, 0.9f),
            dayLaneSample = sp.getString(KEY_DAY_LANE_SAMPLE, null),
            dayOverviewSample = sp.getString(KEY_DAY_OVERVIEW_SAMPLE, null),
            nightLaneSample = sp.getString(KEY_NIGHT_LANE_SAMPLE, null),
            nightOverviewSample = sp.getString(KEY_NIGHT_OVERVIEW_SAMPLE, null),
        )
    }

    fun save(context: Context, config: GeelyAmapOverviewConfig) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_ON_NAV, config.autoOverviewOnNavStart)
            .putInt(KEY_AUTO_DELAY_MS, config.autoOverviewDelayMs.coerceIn(500, 10_000))
            .putBoolean(KEY_GESTURE_ENABLED, config.gestureEnabled)
            .putInt(KEY_WHEEL_KEY_CODE, config.wheelKeyCode)
            .putString(KEY_WHEEL_KEY_TRIGGER, config.wheelKeyTrigger.name)
            .putBoolean(KEY_VISUAL_CHECK, config.visualCheckEnabled)
            .putFloat(KEY_BUTTON_X, config.buttonX.coerceIn(0.02f, 0.98f))
            .putFloat(KEY_BUTTON_Y, config.buttonY.coerceIn(0.02f, 0.98f))
            .putBoolean(KEY_CALIBRATED, config.calibrated)
            .putFloat(KEY_WAKE_X, config.wakeX.coerceIn(0.1f, 0.9f))
            .putFloat(KEY_WAKE_Y, config.wakeY.coerceIn(0.1f, 0.9f))
            .putString(KEY_DAY_LANE_SAMPLE, config.dayLaneSample)
            .putString(KEY_DAY_OVERVIEW_SAMPLE, config.dayOverviewSample)
            .putString(KEY_NIGHT_LANE_SAMPLE, config.nightLaneSample)
            .putString(KEY_NIGHT_OVERVIEW_SAMPLE, config.nightOverviewSample)
            .apply()
    }

    fun resetCalibration(context: Context): GeelyAmapOverviewConfig {
        val current = load(context)
        val reset = current.copy(
            buttonX = GeelyAmapOverviewConfig.DEFAULT_BUTTON_X,
            buttonY = GeelyAmapOverviewConfig.DEFAULT_BUTTON_Y,
            calibrated = false,
            dayLaneSample = null,
            dayOverviewSample = null,
            nightLaneSample = null,
            nightOverviewSample = null,
        )
        save(context, reset)
        return reset
    }
}

/**
 * Coordinates AMap CarPlay dual-screen lane-level navigation on Geely / ECARX head units:
 *
 * AMap only renders 3D lane-level guidance on ONE CarPlay screen at a time; switching the center
 * screen to "全览" (overview) automatically moves 3D lane-level guidance to the driver's cluster.
 *
 * Capabilities:
 * 1. Auto-switch center AMap to overview when navigation starts (configurable delay, default on).
 * 2. Manual steering-wheel key trigger (supports double-click / short-click / long-press).
 * 3. Two-finger horizontal swipe on center screen:
 *    - Two-finger swipe LEFT  -> open cluster lane-level (switch center to overview)
 *    - Two-finger swipe RIGHT -> close cluster lane-level (switch center back to lane-level)
 * 4. Handles AMap's auto-hiding top-right toolbar via optional wake-tap + local TextureView
 *    visual fingerprint verification to prevent accidental taps or state inversion.
 */
internal object GeelyAmapOverviewController {
    private const val TAG = "DiPlay-AmapOverview"
    private const val ROUTE_GUIDANCE_UPDATE = 0x5201
    private const val SAMPLE_BMP_WIDTH = 384
    private const val SAMPLE_BMP_HEIGHT = 144
    private const val PATCH_SIZE = 16
    private const val PATCH_BYTES = PATCH_SIZE * PATCH_SIZE * 3
    private const val INJECT_POINTER_ID = 0
    private const val TAP_HOLD_MS = 60L
    private const val WAKE_WAIT_MS = 380L
    private const val POST_GESTURE_DELAY_MS = 85L
    private const val CALIBRATION_SECOND_SAMPLE_DELAY_MS = 750L
    private const val USER_TOUCH_CANCEL_GRACE_MS = 600L
    private const val TOOLBAR_LIKELY_HIDDEN_IDLE_MS = 4_500L
    private const val MATCH_THRESHOLD = 0.165
    private const val SINGLE_TEMPLATE_THRESHOLD = 0.155
    private const val STATE_SEPARATION_MIN = 0.028
    private const val STATE_DECISION_MARGIN = 0.015

    private val ACTIVE_ROUTE_STATES = setOf(1, 3, 4, 5, 6)

    private enum class TargetClusterMode {
        OPEN_CLUSTER_LANE,  // Center -> Overview, Cluster -> Lane-level
        CLOSE_CLUSTER_LANE, // Center -> Lane-level, Cluster -> Overview
    }

    private enum class VisualState {
        LANE_MODE,                // Center is in lane-level (overview button unselected & visible)
        OVERVIEW_MODE,            // Center is in overview (overview button selected & visible)
        BUTTON_VISIBLE_AMBIGUOUS, // Button visible, but both templates look nearly identical
        HIDDEN_OR_OTHER,          // Button auto-hid or another app is on screen
        UNAVAILABLE,              // No template for current day/night or TextureView unavailable
    }

    private class HostBinding(
        val activityRef: WeakReference<Activity>,
        val rootProvider: () -> FrameLayout?,
        val textureProvider: () -> TextureView?,
        val contentRectProvider: () -> CarPlayVideoLayout?,
        val touchSender: (List<AirPlayContact>) -> Boolean,
        val menuOpenProvider: () -> Boolean,
        val mainStreamActiveProvider: () -> Boolean,
        val clusterActiveProvider: () -> Boolean,
        val darkModeProvider: () -> Boolean,
        val log: (String) -> Unit,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val navLock = Any()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var binding: HostBinding? = null
    @Volatile
    private var hostResumed = false
    @Volatile
    private var pendingCalibration = false
    @Volatile
    private var calibrationOverlay: View? = null

    private var navActive = false
    private var navStateCode: Int? = null
    private var navSourceName: String? = null
    private var navStartedAtElapsedMs = 0L
    private var lastUserTouchElapsedMs = 0L
    private var actionToken = 0
    private var pendingAutoRunnable: Runnable? = null

    // Two-finger horizontal swipe tracking state (main thread only)
    private var twoFingerTracking = false
    private var twoFingerConsumed = false
    private var twoFingerPointer0 = -1
    private var twoFingerPointer1 = -1
    private var twoFingerStartX0 = 0f
    private var twoFingerStartY0 = 0f
    private var twoFingerStartX1 = 0f
    private var twoFingerStartY1 = 0f
    private var twoFingerStartDist = 0f

    private val routeFrameObserver: (Iap2Frame) -> Unit = { frame ->
        if (frame.messageId == ROUTE_GUIDANCE_UPDATE) {
            onRouteGuidanceFrame(frame.payload)
        }
    }

    fun attachHost(
        activity: Activity,
        rootProvider: () -> FrameLayout?,
        textureProvider: () -> TextureView?,
        contentRectProvider: () -> CarPlayVideoLayout?,
        touchSender: (List<AirPlayContact>) -> Boolean,
        menuOpenProvider: () -> Boolean,
        mainStreamActiveProvider: () -> Boolean,
        clusterActiveProvider: () -> Boolean,
        darkModeProvider: () -> Boolean,
        log: (String) -> Unit,
    ) {
        appContext = activity.applicationContext
        binding = HostBinding(
            activityRef = WeakReference(activity),
            rootProvider = rootProvider,
            textureProvider = textureProvider,
            contentRectProvider = contentRectProvider,
            touchSender = touchSender,
            menuOpenProvider = menuOpenProvider,
            mainStreamActiveProvider = mainStreamActiveProvider,
            clusterActiveProvider = clusterActiveProvider,
            darkModeProvider = darkModeProvider,
            log = log,
        )
        CarPlayGlance.routeFrameListener = routeFrameObserver
    }

    fun onHostResumed() {
        hostResumed = true
        if (pendingCalibration) {
            pendingCalibration = false
            mainHandler.postDelayed({ showCalibrationOverlayInternal() }, 180L)
        }
    }

    fun onHostPaused() {
        hostResumed = false
        cancelPendingActions()
        dismissCalibrationOverlay()
        cancelTwoFingerGesture()
    }

    fun detachHost() {
        hostResumed = false
        cancelPendingActions()
        dismissCalibrationOverlay()
        cancelTwoFingerGesture()
        if (CarPlayGlance.routeFrameListener === routeFrameObserver) {
            CarPlayGlance.routeFrameListener = null
        }
        synchronized(navLock) {
            navActive = false
            navStateCode = null
            navSourceName = null
            navStartedAtElapsedMs = 0L
        }
        binding = null
    }

    /** Backup hook from `GeelyClusterEnhancer.onTurnGuidanceUpdated` to catch disconnect/clear cleanly. */
    fun onTurnGuidanceUpdated(hasGuidance: Boolean) {
        if (!hasGuidance) {
            var shouldCancel = false
            synchronized(navLock) {
                if (navActive && navStateCode == null) {
                    navActive = false
                    navSourceName = null
                    shouldCancel = true
                }
            }
            if (shouldCancel) {
                mainHandler.post { cancelAutoNavRunnable() }
            }
        }
    }

    /** Called by `EcarxKx11WheelKeys` when the bound steering-wheel key triggers. */
    fun onWheelKeyTriggered(context: Context): Boolean {
        appContext = context.applicationContext
        val current = binding
        if (current == null || !hostResumed) {
            Log.i(TAG, "wheel trigger ignored: CarPlay host not in foreground")
            return false
        }
        mainHandler.post {
            executeSwitch(TargetClusterMode.OPEN_CLUSTER_LANE, reason = "wheel-key", isAuto = false)
        }
        return true
    }

    /** Manual test trigger from settings UI. */
    fun triggerTestOverview(activity: Activity): Boolean {
        val currentHost = CarPlayScreen.current()
        if (currentHost == null || binding == null) {
            Toast.makeText(activity, "请先连接 CarPlay 并打开高德导航后再测试", Toast.LENGTH_SHORT).show()
            return false
        }
        if (activity !== currentHost) {
            runCatching {
                val intent = CarPlayScreen.intent(activity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)
            }
            mainHandler.postDelayed({
                executeSwitch(TargetClusterMode.OPEN_CLUSTER_LANE, reason = "settings-test", isAuto = false)
            }, 450L)
        } else {
            executeSwitch(TargetClusterMode.OPEN_CLUSTER_LANE, reason = "settings-test", isAuto = false)
        }
        return true
    }

    /** Launches live on-screen calibration over CarPlay. */
    fun requestCalibration(activity: Activity): Boolean {
        val currentHost = CarPlayScreen.current()
        if (currentHost == null || binding == null) {
            Toast.makeText(activity, "请先连接 CarPlay 并进入高德导航界面后再校准", Toast.LENGTH_LONG).show()
            return false
        }
        pendingCalibration = true
        if (activity === currentHost && hostResumed) {
            pendingCalibration = false
            mainHandler.post { showCalibrationOverlayInternal() }
        } else {
            runCatching {
                val intent = CarPlayScreen.intent(activity).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)
            }.onFailure {
                pendingCalibration = false
                Toast.makeText(activity, "无法切换到 CarPlay 画面，请手动返回 CarPlay", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    fun cancelTwoFingerGesture() {
        twoFingerTracking = false
        twoFingerConsumed = false
        twoFingerPointer0 = -1
        twoFingerPointer1 = -1
    }

    /**
     * Detects two-finger parallel horizontal swipes on the CarPlay touch surface:
     * - Two-finger swipe LEFT  -> open cluster lane-level (center switches to overview)
     * - Two-finger swipe RIGHT -> close cluster lane-level (center switches back to lane-level)
     *
     * Strictly distinguishes parallel horizontal swipes from two-finger pinch-to-zoom and
     * yields immediately to three-finger gestures.
     */
    fun onHostTouch(
        view: View,
        event: MotionEvent,
        content: CarPlayVideoLayout,
        releaseTouches: () -> Unit,
    ): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            val now = SystemClock.elapsedRealtime()
            lastUserTouchElapsedMs = now
            // Cancel pending auto-overview if the driver actively touches the screen after nav start
            if (action == MotionEvent.ACTION_DOWN &&
                pendingAutoRunnable != null &&
                now - navStartedAtElapsedMs >= USER_TOUCH_CANCEL_GRACE_MS
            ) {
                log("AmapOverview: auto-switch cancelled because user touched screen")
                cancelAutoNavRunnable()
            }
        }

        val ctx = appContext ?: view.context.applicationContext
        val cfg = GeelyAmapOverviewPrefs.load(ctx)
        if (!cfg.gestureEnabled) {
            cancelTwoFingerGesture()
            return false
        }

        if (twoFingerConsumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                cancelTwoFingerGesture()
            }
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cancelTwoFingerGesture()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    val x0 = event.getX(0)
                    val y0 = event.getY(0)
                    val x1 = event.getX(1)
                    val y1 = event.getY(1)
                    if (content.contains(x0, y0) && content.contains(x1, y1)) {
                        twoFingerTracking = true
                        twoFingerPointer0 = event.getPointerId(0)
                        twoFingerPointer1 = event.getPointerId(1)
                        twoFingerStartX0 = x0
                        twoFingerStartY0 = y0
                        twoFingerStartX1 = x1
                        twoFingerStartY1 = y1
                        twoFingerStartDist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                    }
                } else if (event.pointerCount >= 3) {
                    cancelTwoFingerGesture()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerTracking && event.pointerCount == 2) {
                    val idx0 = event.findPointerIndex(twoFingerPointer0)
                    val idx1 = event.findPointerIndex(twoFingerPointer1)
                    if (idx0 < 0 || idx1 < 0) {
                        cancelTwoFingerGesture()
                        return false
                    }
                    val x0 = event.getX(idx0)
                    val y0 = event.getY(idx0)
                    val x1 = event.getX(idx1)
                    val y1 = event.getY(idx1)
                    val dx0 = x0 - twoFingerStartX0
                    val dy0 = y0 - twoFingerStartY0
                    val dx1 = x1 - twoFingerStartX1
                    val dy1 = y1 - twoFingerStartY1
                    val avgDx = (dx0 + dx1) * 0.5f
                    val avgDy = (dy0 + dy1) * 0.5f
                    val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                    val pinchDelta = abs(dist - twoFingerStartDist)

                    val density = view.resources.displayMetrics.density
                    val minFingerDx = 48f * density
                    val minAvgDx = 76f * density
                    val maxPinchTol = max(abs(avgDx) * 0.45f, 36f * density)

                    val bothLeft = dx0 <= -minFingerDx && dx1 <= -minFingerDx
                    val bothRight = dx0 >= minFingerDx && dx1 >= minFingerDx
                    if ((bothLeft || bothRight) &&
                        abs(avgDx) >= minAvgDx &&
                        abs(avgDx) >= abs(avgDy) * 1.6f &&
                        pinchDelta <= maxPinchTol
                    ) {
                        twoFingerTracking = false
                        twoFingerConsumed = true
                        releaseTouches()
                        val target = if (bothLeft) {
                            TargetClusterMode.OPEN_CLUSTER_LANE
                        } else {
                            TargetClusterMode.CLOSE_CLUSTER_LANE
                        }
                        val reason = if (bothLeft) "two-finger-left" else "two-finger-right"
                        log("AmapOverview: gesture detected ($reason)")
                        mainHandler.postDelayed({
                            executeSwitch(target, reason = reason, isAuto = false)
                        }, POST_GESTURE_DELAY_MS)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingerTracking = false
                twoFingerPointer0 = -1
                twoFingerPointer1 = -1
            }
        }
        return false
    }

    private fun onRouteGuidanceFrame(payload: ByteArray) {
        val parsed = parseRouteGuidanceTlv(payload) ?: return
        var startedNow = false
        var endedNow = false
        synchronized(navLock) {
            if (parsed.state != null) navStateCode = parsed.state
            if (parsed.source != null) navSourceName = parsed.source
            val nowActive = when {
                navSourceName == "" -> false
                navStateCode in ACTIVE_ROUTE_STATES -> true
                navStateCode != null -> false
                else -> !navSourceName.isNullOrBlank()
            }
            if (!navActive && nowActive) {
                navActive = true
                navStartedAtElapsedMs = SystemClock.elapsedRealtime()
                startedNow = true
            } else if (navActive && !nowActive) {
                navActive = false
                navStartedAtElapsedMs = 0L
                endedNow = true
            }
        }
        if (endedNow) {
            mainHandler.post { cancelAutoNavRunnable() }
            return
        }
        if (startedNow) {
            mainHandler.post { scheduleAutoOverviewOnNavStart() }
        }
    }

    private fun scheduleAutoOverviewOnNavStart() {
        cancelAutoNavRunnable()
        val ctx = appContext ?: return
        val cfg = GeelyAmapOverviewPrefs.load(ctx)
        if (!cfg.autoOverviewOnNavStart) return
        val runnable = Runnable {
            pendingAutoRunnable = null
            val (active, source) = synchronized(navLock) { navActive to navSourceName }
            if (!active) return@Runnable
            if (!isAmapOrUnknown(source)) {
                log("AmapOverview: auto-switch skipped (non-AMap source=$source)")
                return@Runnable
            }
            executeSwitch(TargetClusterMode.OPEN_CLUSTER_LANE, reason = "nav-start", isAuto = true)
        }
        pendingAutoRunnable = runnable
        log("AmapOverview: navigation started; scheduling auto-overview in ${cfg.autoOverviewDelayMs} ms")
        mainHandler.postDelayed(runnable, cfg.autoOverviewDelayMs.toLong())
    }

    private fun executeSwitch(target: TargetClusterMode, reason: String, isAuto: Boolean) {
        val host = binding ?: return
        val ctx = appContext ?: host.activityRef.get()?.applicationContext ?: return
        if (!hostResumed || host.menuOpenProvider() || calibrationOverlay != null) {
            log("AmapOverview: skip ($reason) hostResumed=$hostResumed menuOpen=${host.menuOpenProvider()}")
            return
        }
        if (!host.mainStreamActiveProvider()) {
            log("AmapOverview: skip ($reason) main screen stream not active")
            return
        }
        if (isAuto) {
            if (!host.clusterActiveProvider()) {
                log("AmapOverview: skip ($reason) cluster display not active")
                return
            }
            if (isPhoneCallActive(ctx)) {
                log("AmapOverview: skip ($reason) phone call active")
                return
            }
        }

        val cfg = GeelyAmapOverviewPrefs.load(ctx)
        val night = host.darkModeProvider()
        val token = ++actionToken
        val state = if (cfg.visualCheckEnabled) {
            inspectVisualState(host, cfg, night)
        } else {
            VisualState.UNAVAILABLE
        }
        log("AmapOverview: execute target=$target reason=$reason night=$night state=$state pos=(${fmtPct(cfg.buttonX)},${fmtPct(cfg.buttonY)})")

        val alreadySatisfied = (target == TargetClusterMode.OPEN_CLUSTER_LANE && state == VisualState.OVERVIEW_MODE) ||
            (target == TargetClusterMode.CLOSE_CLUSTER_LANE && state == VisualState.LANE_MODE)
        if (alreadySatisfied) {
            val msg = if (target == TargetClusterMode.OPEN_CLUSTER_LANE) {
                "主驾已是车道级"
            } else {
                "中控已是车道级"
            }
            log("AmapOverview: already satisfied ($reason) -> $msg")
            if (!isAuto) showShortToast(host, msg)
            return
        }

        val directlyActionable = (target == TargetClusterMode.OPEN_CLUSTER_LANE && state == VisualState.LANE_MODE) ||
            (target == TargetClusterMode.CLOSE_CLUSTER_LANE && state == VisualState.OVERVIEW_MODE) ||
            state == VisualState.BUTTON_VISIBLE_AMBIGUOUS
        if (directlyActionable) {
            injectSingleTap(host, cfg.buttonX, cfg.buttonY, token, "$reason-direct")
            return
        }

        if (state == VisualState.HIDDEN_OR_OTHER) {
            // Toolbar may have auto-hidden after a few seconds; send one safe wake tap, then re-verify.
            log("AmapOverview: button hidden or obscured ($reason); sending wake tap then re-checking")
            injectSingleTap(host, cfg.wakeX, cfg.wakeY, token, "$reason-wake") {
                mainHandler.postDelayed({
                    if (actionToken != token || !hostResumed || host.menuOpenProvider() || calibrationOverlay != null) {
                        return@postDelayed
                    }
                    val afterWake = inspectVisualState(host, cfg, host.darkModeProvider())
                    log("AmapOverview: post-wake state=$afterWake ($reason)")
                    when {
                        (target == TargetClusterMode.OPEN_CLUSTER_LANE && afterWake == VisualState.OVERVIEW_MODE) -> {
                            if (!isAuto) showShortToast(host, "主驾已是车道级")
                        }
                        (target == TargetClusterMode.CLOSE_CLUSTER_LANE && afterWake == VisualState.LANE_MODE) -> {
                            if (!isAuto) showShortToast(host, "中控已是车道级")
                        }
                        (target == TargetClusterMode.OPEN_CLUSTER_LANE && afterWake == VisualState.LANE_MODE) ||
                            (target == TargetClusterMode.CLOSE_CLUSTER_LANE && afterWake == VisualState.OVERVIEW_MODE) ||
                            afterWake == VisualState.BUTTON_VISIBLE_AMBIGUOUS -> {
                            injectSingleTap(host, cfg.buttonX, cfg.buttonY, token, "$reason-after-wake")
                        }
                        else -> {
                            log("AmapOverview: aborted ($reason) button still not matched after wake")
                            if (!isAuto) {
                                showShortToast(host, "未检测到高德全览按钮，请确认在高德导航页或重新校准")
                            }
                        }
                    }
                }, WAKE_WAIT_MS)
            }
            return
        }

        // VisualState.UNAVAILABLE (no template sampled for current day/night, or visual check disabled)
        val idleMs = SystemClock.elapsedRealtime() - lastUserTouchElapsedMs
        val likelyHidden = !isAuto && idleMs > TOOLBAR_LIKELY_HIDDEN_IDLE_MS
        if (likelyHidden) {
            log("AmapOverview: uncalibrated & idle ${idleMs}ms ($reason); wake tap then button tap")
            injectSingleTap(host, cfg.wakeX, cfg.wakeY, token, "$reason-uncal-wake") {
                mainHandler.postDelayed({
                    if (actionToken != token || !hostResumed || host.menuOpenProvider() || calibrationOverlay != null) {
                        return@postDelayed
                    }
                    injectSingleTap(host, cfg.buttonX, cfg.buttonY, token, "$reason-uncal-button")
                }, WAKE_WAIT_MS)
            }
        } else {
            injectSingleTap(host, cfg.buttonX, cfg.buttonY, token, "$reason-uncal-direct")
        }
    }

    private fun injectSingleTap(
        host: HostBinding,
        nx: Float,
        ny: Float,
        token: Int,
        tag: String,
        onReleased: (() -> Unit)? = null,
    ) {
        val x = nx.coerceIn(0.01f, 0.99f).toDouble()
        val y = ny.coerceIn(0.01f, 0.99f).toDouble()
        val sentDown = host.touchSender(listOf(AirPlayContact(id = INJECT_POINTER_ID, x = x, y = y, down = true)))
        log("AmapOverview: inject tap [$tag] (${fmtPct(nx)},${fmtPct(ny)}) down=$sentDown")
        if (!sentDown) return
        mainHandler.postDelayed({
            host.touchSender(emptyList())
            if (actionToken == token) {
                onReleased?.invoke()
            }
        }, TAP_HOLD_MS)
    }

    private fun inspectVisualState(
        host: HostBinding,
        cfg: GeelyAmapOverviewConfig,
        night: Boolean,
    ): VisualState {
        val laneStr = if (night) cfg.nightLaneSample else cfg.dayLaneSample
        val overviewStr = if (night) cfg.nightOverviewSample else cfg.dayOverviewSample
        val laneTpl = decodeSample(laneStr) ?: return VisualState.UNAVAILABLE
        val overviewTpl = decodeSample(overviewStr)
        val live = capturePatch(host.textureProvider(), cfg.buttonX, cfg.buttonY) ?: return VisualState.UNAVAILABLE

        val dLane = weightedMad(live, laneTpl)
        val dOverview = overviewTpl?.let { weightedMad(live, it) }
        log(
            String.format(
                Locale.US,
                "AmapOverview: visual check night=%s dLane=%.3f dOverview=%s",
                night,
                dLane,
                dOverview?.let { String.format(Locale.US, "%.3f", it) } ?: "none",
            ),
        )

        if (overviewTpl == null || dOverview == null) {
            return if (dLane <= SINGLE_TEMPLATE_THRESHOLD) VisualState.LANE_MODE else VisualState.HIDDEN_OR_OTHER
        }

        val minDist = minOf(dLane, dOverview)
        if (minDist > MATCH_THRESHOLD) return VisualState.HIDDEN_OR_OTHER

        val separation = weightedMad(laneTpl, overviewTpl)
        if (separation < STATE_SEPARATION_MIN) {
            return VisualState.BUTTON_VISIBLE_AMBIGUOUS
        }
        return when {
            dLane + STATE_DECISION_MARGIN < dOverview -> VisualState.LANE_MODE
            dOverview + STATE_DECISION_MARGIN < dLane -> VisualState.OVERVIEW_MODE
            minDist <= 0.12 -> if (dLane <= dOverview) VisualState.LANE_MODE else VisualState.OVERVIEW_MODE
            else -> VisualState.HIDDEN_OR_OTHER
        }
    }

    private fun capturePatch(textureView: TextureView?, nx: Float, ny: Float): ByteArray? {
        if (textureView == null || !textureView.isAvailable) return null
        val bmp: Bitmap = try {
            textureView.getBitmap(SAMPLE_BMP_WIDTH, SAMPLE_BMP_HEIGHT)
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w < PATCH_SIZE || h < PATCH_SIZE) return null
            val cx = (nx * w).roundToInt()
            val cy = (ny * h).roundToInt()
            val half = PATCH_SIZE / 2
            val left = (cx - half).coerceIn(0, w - PATCH_SIZE)
            val top = (cy - half).coerceIn(0, h - PATCH_SIZE)
            val pixels = IntArray(PATCH_SIZE * PATCH_SIZE)
            bmp.getPixels(pixels, 0, PATCH_SIZE, left, top, PATCH_SIZE, PATCH_SIZE)
            val out = ByteArray(PATCH_BYTES)
            var o = 0
            for (c in pixels) {
                out[o++] = ((c ushr 16) and 0xFF).toByte()
                out[o++] = ((c ushr 8) and 0xFF).toByte()
                out[o++] = (c and 0xFF).toByte()
            }
            out
        } finally {
            bmp.recycle()
        }
    }

    /** Center-weighted RGB MAD (inner 10x10 weighted 3x so scrolling map edges barely affect matching). */
    private fun weightedMad(a: ByteArray, b: ByteArray): Double {
        if (a.size != PATCH_BYTES || b.size != PATCH_BYTES) return 1.0
        var sum = 0.0
        var totalWeight = 0.0
        var idx = 0
        for (y in 0 until PATCH_SIZE) {
            val innerY = y in 3..(PATCH_SIZE - 4)
            for (x in 0 until PATCH_SIZE) {
                val w = if (innerY && x in 3..(PATCH_SIZE - 4)) 3.0 else 1.0
                val dr = abs((a[idx].toInt() and 0xFF) - (b[idx].toInt() and 0xFF))
                val dg = abs((a[idx + 1].toInt() and 0xFF) - (b[idx + 1].toInt() and 0xFF))
                val db = abs((a[idx + 2].toInt() and 0xFF) - (b[idx + 2].toInt() and 0xFF))
                idx += 3
                sum += (dr + dg + db) * w
                totalWeight += 3.0 * 255.0 * w
            }
        }
        return if (totalWeight > 0.0) sum / totalWeight else 1.0
    }

    private fun encodeSample(data: ByteArray?): String? =
        data?.takeIf { it.size == PATCH_BYTES }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private fun decodeSample(raw: String?): ByteArray? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { Base64.decode(raw, Base64.NO_WRAP) }
            .getOrNull()
            ?.takeIf { it.size == PATCH_BYTES }
    }

    private fun showCalibrationOverlayInternal() {
        dismissCalibrationOverlay()
        val host = binding ?: return
        val activity = host.activityRef.get() ?: return
        val root = host.rootProvider() ?: return
        val cfg = GeelyAmapOverviewPrefs.load(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val overlay = object : FrameLayout(activity) {
            private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 0, 229, 255)
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
            }
            private val crosshairFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(55, 0, 229, 255)
                style = Paint.Style.FILL
            }

            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                val rect = host.contentRectProvider() ?: return
                val px = rect.left + cfg.buttonX * rect.width
                val py = rect.top + cfg.buttonY * rect.height
                val r = 26f * density
                canvas.drawCircle(px, py, r, crosshairFill)
                canvas.drawCircle(px, py, r, crosshairPaint)
                canvas.drawLine(px - r * 1.4f, py, px + r * 1.4f, py, crosshairPaint)
                canvas.drawLine(px, py - r * 1.4f, px, py + r * 1.4f, crosshairPaint)
            }
        }.apply {
            setBackgroundColor(Color.argb(72, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }

        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 18, 24, 34))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.argb(180, 0, 229, 255))
            }
            isClickable = true
        }
        val modeTag = if (host.darkModeProvider()) "夜间" else "日间"
        banner.addView(
            TextView(activity).apply {
                text = "校准高德「全览」按钮（当前：$modeTag 模式）"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        banner.addView(
            TextView(activity).apply {
                text = "1. 请确认中控处于高德【车道级（未全览）】导航界面，且右上角「全览」按钮可见。\n" +
                    "2. 如按钮已自动隐藏，请先点下方「唤出工具栏」，再直接点击画面上的「全览」按钮。\n" +
                    "3. 点击后将自动记录坐标，并同步采集【未全览 / 已全览】两态特征用于防误触。"
                setTextColor(Color.argb(220, 226, 236, 245))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, dp(10))
            },
        )
        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun makeBannerButton(label: String, accent: Boolean, onClick: () -> Unit): Button =
            Button(activity).apply {
                text = label
                isAllCaps = false
                stateListAnimator = null
                setTextColor(if (accent) Color.rgb(12, 22, 30) else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                    GradientDrawable().apply {
                        setColor(if (accent) Color.rgb(0, 229, 255) else Color.argb(180, 48, 58, 72))
                        cornerRadius = dp(10).toFloat()
                    },
                    null,
                )
                setOnClickListener { onClick() }
            }

        val wakeBtn = makeBannerButton("唤出高德工具栏", accent = true) {
            val token = ++actionToken
            injectSingleTap(host, cfg.wakeX, cfg.wakeY, token, "calibration-wake")
        }
        val cancelBtn = makeBannerButton("取消", accent = false) {
            dismissCalibrationOverlay()
        }
        btnRow.addView(wakeBtn, LinearLayout.LayoutParams(0, dp(42), 1.4f).apply { marginEnd = dp(10) })
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(42), 0.8f))
        banner.addView(btnRow)

        val bannerWidth = (root.width * 0.56f).roundToInt().coerceIn(dp(420), dp(680))
        overlay.addView(
            banner,
            FrameLayout.LayoutParams(bannerWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(24)
                bottomMargin = dp(22)
            },
        )

        overlay.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val rect = host.contentRectProvider()
                if (rect != null && rect.contains(ev.x, ev.y)) {
                    val nx = ((ev.x - rect.left) / rect.width.coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
                    val ny = ((ev.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0.02f, 0.98f)
                    completeCalibrationTap(host, nx, ny)
                }
            }
            true
        }

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        calibrationOverlay = overlay
    }

    private fun completeCalibrationTap(host: HostBinding, nx: Float, ny: Float) {
        val ctx = appContext ?: host.activityRef.get()?.applicationContext ?: return
        val night = host.darkModeProvider()
        // 1. Sample "lane mode (unselected overview button)" BEFORE forwarding the tap
        val lanePatch = encodeSample(capturePatch(host.textureProvider(), nx, ny))
        val prev = GeelyAmapOverviewPrefs.load(ctx)
        val savedBefore = if (night) {
            prev.copy(
                buttonX = nx,
                buttonY = ny,
                calibrated = true,
                nightLaneSample = lanePatch ?: prev.nightLaneSample,
            )
        } else {
            prev.copy(
                buttonX = nx,
                buttonY = ny,
                calibrated = true,
                dayLaneSample = lanePatch ?: prev.dayLaneSample,
            )
        }
        GeelyAmapOverviewPrefs.save(ctx, savedBefore)
        dismissCalibrationOverlay()

        // 2. Forward the tap to CarPlay so AMap toggles into Overview, then sample the 2nd state
        val token = ++actionToken
        injectSingleTap(host, nx, ny, token, "calibration-confirm") {
            mainHandler.postDelayed({
                if (actionToken != token || !hostResumed) return@postDelayed
                val overviewPatch = encodeSample(capturePatch(host.textureProvider(), nx, ny))
                val latest = GeelyAmapOverviewPrefs.load(ctx)
                val savedAfter = if (night) {
                    latest.copy(nightOverviewSample = overviewPatch ?: latest.nightOverviewSample)
                } else {
                    latest.copy(dayOverviewSample = overviewPatch ?: latest.dayOverviewSample)
                }
                GeelyAmapOverviewPrefs.save(ctx, savedAfter)
                val themeLabel = if (night) "夜间" else "日间"
                val sampleStatus = if (lanePatch != null && overviewPatch != null) "双态特征已采样" else "坐标已保存"
                log("AmapOverview: calibrated (${fmtPct(nx)},${fmtPct(ny)}) $themeLabel $sampleStatus")
                showShortToast(host, "已校准全览按钮 (${fmtPct(nx)}, ${fmtPct(ny)}) · $themeLabel$sampleStatus")
            }, CALIBRATION_SECOND_SAMPLE_DELAY_MS)
        }
    }

    private fun dismissCalibrationOverlay() {
        val view = calibrationOverlay ?: return
        calibrationOverlay = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun cancelAutoNavRunnable() {
        pendingAutoRunnable?.let(mainHandler::removeCallbacks)
        pendingAutoRunnable = null
    }

    private fun cancelPendingActions() {
        actionToken++
        cancelAutoNavRunnable()
    }

    private fun isPhoneCallActive(context: Context): Boolean {
        if (BydNavigationOutputs.carPlayCall() != null) return true
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_RINGTONE
    }

    private fun showShortToast(host: HostBinding, message: String) {
        val ctx = host.activityRef.get() ?: appContext ?: return
        mainHandler.post { Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        binding?.log?.invoke(message)
    }

    private fun fmtPct(v: Float): String = String.format(Locale.US, "%.0f%%", v * 100f)

    private fun isAmapOrUnknown(source: String?): Boolean {
        val s = source?.trim().orEmpty()
        if (s.isEmpty()) return true
        val lower = s.lowercase(Locale.ROOT)
        if ("高德" in s || "amap" in lower || "autonavi" in lower || "gaode" in lower) return true
        if ("百度" in s || "baidu" in lower || "腾讯" in s || "tencent" in lower ||
            "google" in lower || "waze" in lower || lower == "maps" || s == "地图" || "apple" in lower
        ) {
            return false
        }
        return true
    }

    private data class ParsedRouteTlv(val state: Int?, val source: String?)

    private fun parseRouteGuidanceTlv(payload: ByteArray): ParsedRouteTlv? {
        var offset = 0
        while (offset < payload.size) {
            if (offset + 4 > payload.size) return null
            val len = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            if (len < 4 || len > payload.size - offset) return null
            offset += len
        }
        var state: Int? = null
        var source: String? = null
        offset = 0
        while (offset < payload.size) {
            val len = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            val id = ((payload[offset + 2].toInt() and 0xFF) shl 8) or (payload[offset + 3].toInt() and 0xFF)
            val valOff = offset + 4
            val valLen = len - 4
            when {
                id == 0x01 && valLen >= 1 -> state = payload[valOff].toInt() and 0xFF
                id == 0x13 -> {
                    var end = valOff
                    while (end < valOff + valLen && payload[end] != 0.toByte()) end++
                    source = String(payload, valOff, end - valOff, Charsets.UTF_8).trim()
                }
            }
            offset += len
        }
        return ParsedRouteTlv(state, source)
    }
}
