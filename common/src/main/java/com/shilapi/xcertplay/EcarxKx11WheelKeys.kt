package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.geely.lib.oneosapi.input.IInputListener
import com.geely.lib.oneosapi.input.IInputManager
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Steering-wheel key learning and Siri/media dispatch for Geely Xingyue L / Monjaro ECARX KX11
 * head units ([EcarxKx11HeadUnit.detected]).
 *
 * Confirmed on `kx11_high` (Android 9): steering-wheel buttons arrive through
 * `ECarXCarHardKeyService` -> `com.geely.inputservice.InputService` (`IInputManager` /
 * `IInputListener`, vendor key codes in the 200000/210000 space such as `KEYCODE_R_WECHAT = 200400`
 * and `KEYCODE_R_VOICE_ASSIST = 200231`), without producing any Linux `/dev/input` or Android
 * `KeyEvent`. Because standard window/accessibility listeners never see them, this extension
 * binds `com.geely.inputservice.InputService` both during Settings key learning and while a
 * CarPlay session is active, storing learnt keys in [WheelZoomSettings] under [DEVICE_NAME].
 *
 * Silently does nothing off ECARX KX11 head units.
 */
internal object EcarxKx11WheelKeys {
    private const val TAG = "DiPlay-Kx11Wheel"
    private const val INPUT_PACKAGE = "com.geely.inputservice"
    private const val INPUT_CLASS = "com.geely.inputservice.InputService"
    private const val RETRY_MILLIS = 3_000L
    private const val LEARN_COOLDOWN_MILLIS = 1_500L
    private const val CLUSTER_DOUBLE_CLICK_INTERVAL_MS = 600L
    private const val SINGLE_VS_DOUBLE_SPLIT_MS = 380L
    private const val KEY_DUAL_TRACK_PRESET_V1 = "kx11_dual_track_wheel_preset_v1"
    private const val KEY_THEME_WHEEL_PRESET_V1 = "kx11_theme_wheel_preset_v1"

    internal const val DEVICE_NAME = "geely-inputservice"

    internal enum class ClusterLearnTarget {
        PRIMARY,
        TELEMETRY,
        THEME,
    }

    internal enum class VoiceInterceptStatus {
        IDLE,
        GRANTED,
        REJECTED,
        ERROR,
    }

    // Vendor key codes from Geely OneOS / ECARX AdaptAPI (com.ecarx.xui.adaptapi.input.KeyCode).
    internal const val KEY_CALL = 200005
    internal const val KEY_ENDCALL = 200006
    internal const val KEY_MENU = 200082
    internal const val KEY_PLAY_PAUSE = 200085
    internal const val KEY_NEXT = 200087
    internal const val KEY_PREVIOUS = 200088
    internal const val KEY_MODE = 200110
    internal const val KEY_VOLUME_MUTE = 200164
    internal const val KEY_SETTINGS = 200176
    internal const val KEY_VOICE_ASSIST = 200231
    internal const val KEY_WECHAT = 200400
    internal const val KEY_MULTI_FUNCTION = 200600
    internal const val KEY_NAVI = 210001
    internal const val KEY_RADIO = 210002
    internal const val KEY_MEDIA = 210003
    internal const val KEY_SRC = 210004
    internal const val KEY_SEEK_NEXT = 210005
    internal const val KEY_SEEK_PREVIOUS = 210006

    internal val LEARNABLE_CODES = intArrayOf(
        KEY_WECHAT,
        KEY_MULTI_FUNCTION,
        KEY_VOICE_ASSIST,
        KEY_VOLUME_MUTE,
        KEY_MODE,
        KEY_PLAY_PAUSE,
        KEY_NEXT,
        KEY_PREVIOUS,
        KEY_CALL,
        KEY_ENDCALL,
        KEY_MENU,
        KEY_SETTINGS,
        KEY_NAVI,
        KEY_RADIO,
        KEY_MEDIA,
        KEY_SRC,
        KEY_SEEK_NEXT,
        KEY_SEEK_PREVIOUS,
    )

    private val KEYCODE_CLASS_CANDIDATES = listOf(
        "com.ecarx.xui.adaptapi.input.KeyCode",
        "com.geely.lib.oneosapi.input.KeyCode",
        "com.geely.lib.oneosapi.input.KeyCodeEvent",
    )

    private val EXCLUDED_DISCOVERED_KEY_TOKENS = listOf(
        "VOL_UP",
        "VOL_DOWN",
        "VOLUME_UP",
        "VOLUME_DOWN",
        "POWER",
        "SCREEN_OFF",
        "BRIGHTNESS",
        "SOS",
        "ECALL",
        "CRUISE",
        "ACC",
        "APA",
        "LDW",
        "LKA",
        "DISTANCE",
    )

    private data class DiscoveredKeyCatalog(
        val codes: IntArray,
        val names: Map<Int, String>,
    )

    private val discoveredCatalog: DiscoveredKeyCatalog by lazy { buildDiscoveredKeyCatalog() }

    private fun buildDiscoveredKeyCatalog(): DiscoveredKeyCatalog {
        val codes = LinkedHashSet<Int>(LEARNABLE_CODES.size + 32)
        for (c in LEARNABLE_CODES) codes.add(c)
        val names = LinkedHashMap<Int, String>()
        for (className in KEYCODE_CLASS_CANDIDATES) {
            val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue
            for (field in cls.fields) {
                val mods = field.modifiers
                if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) continue
                if (field.type != Int::class.javaPrimitiveType) continue
                val fieldName = field.name
                if (!fieldName.startsWith("KEYCODE_")) continue
                val upper = fieldName.uppercase(Locale.ROOT)
                if (EXCLUDED_DISCOVERED_KEY_TOKENS.any { it in upper }) continue
                val code = runCatching { field.getInt(null) }.getOrNull() ?: continue
                if (code !in 100_000..999_999) continue
                codes.add(code)
                names.putIfAbsent(code, fieldName.removePrefix("KEYCODE_"))
            }
        }
        return DiscoveredKeyCatalog(codes.toIntArray(), names)
    }

    internal fun discoveredKeyLabel(code: Int): String? = discoveredCatalog.names[code]

    private fun learnableCodesForSession(): IntArray = discoveredCatalog.codes

    private class LearningSession(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private class ClusterLearningSession(
        val target: ClusterLearnTarget,
        val cancelled: () -> Unit,
        val done: (Int) -> Unit,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val presetEnsured = AtomicBoolean(false)
    private val siriDebounce = WheelSiriKey()
    private val clusterDoublePress = WheelSiriKey()
    private val telemetryDoublePress = WheelSiriKey()
    private val themeDoublePress = WheelSiriKey()
    private var lastDoubleFiredKeyCode: Int = 0
    private var lastDoubleFiredElapsedMs: Long = 0L

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var sessionActive = false
    @Volatile
    private var clusterActive = false
    private var bound = false
    private var manager: IInputManager? = null
    private var listener: IInputListener? = null
    private var registeredCodes: IntArray = IntArray(0)
    private val interceptedCodes: MutableSet<Int> = LinkedHashSet()
    @Volatile
    var lastVoiceInterceptStatus: VoiceInterceptStatus = VoiceInterceptStatus.IDLE
        private set
    @Volatile
    private var learning: LearningSession? = null
    @Volatile
    private var clusterLearning: ClusterLearningSession? = null
    private var ignoreRuntimeKeyCode: Int? = null
    private var ignoreRuntimeUntilElapsedMs = 0L
    private var doubleSiriFirstPressWasInCall = false
    private var delayedSiriRunnable: Runnable? = null
    private var pendingSingleClickRunnable: Runnable? = null
    private var pendingSingleClickKeyCode: Int = 0

    fun isCarPlaySessionActive(): Boolean = sessionActive

    fun isVoiceKeyIntercepted(): Boolean = synchronized(this) { KEY_VOICE_ASSIST in interceptedCodes }

    private val endLearning = Runnable { cancelLearning() }
    private val retryBind = Runnable {
        synchronized(this) {
            val context = appContext ?: return@synchronized
            if (manager == null && desiredCodesLocked(context).isNotEmpty()) {
                bound = false
                bindLocked(context)
            }
        }
    }

    internal fun toWheelKey(keyCode: Int): WheelKey = WheelKey(keyCode, 0, DEVICE_NAME)

    internal fun isGeelyInputKey(key: WheelKey?): Boolean = key?.device == DEVICE_NAME

    /**
     * Applies the out-of-the-box recommended KX11 wheel-key preset once per installation/upgrade:
     * - Voice key (`200231`) -> Siri with CarPlay short-press intercept enabled (when Siri was unset
     *   or previously bound to WeChat `200400`)
     * - WeChat key (`200400`) -> single click cycles Map / Album; double click cycles cluster theme
     *   (`原车经典 / 纯地图全屏 / 机甲光翼`); long press peeks telemetry pages
     *   (`本次行程 / 胎压胎温 / 综合车况`) with 5 s auto-close.
     *
     * Runs once (guarded by [KEY_DUAL_TRACK_PRESET_V1] / [KEY_THEME_WHEEL_PRESET_V1]) so subsequent
     * manual user edits are always preserved. No-op off ECARX KX11 head units.
     */
    fun ensureDefaultPresets(context: Context) {
        if (!EcarxKx11HeadUnit.detected) return
        if (!presetEnsured.compareAndSet(false, true)) return
        val app = context.applicationContext
        val sp = GeelyClusterPrefs.prefs(app)

        if (!sp.getBoolean(KEY_DUAL_TRACK_PRESET_V1, false)) {
            val current = GeelyClusterPrefs.load(app)
            val primaryCode = if (current.centerSwitchKeyCode > 0) current.centerSwitchKeyCode else KEY_WECHAT
            GeelyClusterPrefs.save(
                app,
                current.copy(
                    centerSwitchKeyCode = primaryCode,
                    centerSwitchTrigger = GeelyClusterSwitchTrigger.SINGLE_CLICK,
                    primaryCycleModes = GeelyClusterCenterMode.DEFAULT_PRIMARY_MODES,
                    telemetrySwitchKeyCode = 0,
                    telemetrySwitchTrigger = GeelyClusterSwitchTrigger.LONG_PRESS,
                    telemetryCycleModes = GeelyClusterCenterMode.DEFAULT_TELEMETRY_MODES,
                    themeSwitchKeyCode = primaryCode,
                    themeSwitchTrigger = GeelyClusterSwitchTrigger.DOUBLE_CLICK,
                    themeCycleStyles = GeelyClusterThemeStyle.DEFAULT_CYCLE_STYLES,
                    navAutoReturnSeconds = GeelySelfDrawConfig.DEFAULT_NAV_AUTO_RETURN_SECONDS,
                    interceptVoiceKeyForSiri = true,
                ),
            )

            val currentSiriKey = WheelZoomSettings.key(app, WheelZoomSettings.Role.SIRI)
            val siriUnsetOrWeChat = !WheelZoomSettings.siriKey(app) ||
                currentSiriKey == null ||
                (isGeelyInputKey(currentSiriKey) && currentSiriKey.code == KEY_WECHAT)
            if (siriUnsetOrWeChat) {
                WheelZoomSettings.setSiriKey(app, true)
                WheelZoomSettings.setSiriDoublePress(app, false)
                WheelZoomSettings.assign(app, WheelZoomSettings.Role.SIRI, toWheelKey(KEY_VOICE_ASSIST))
            }

            sp.edit()
                .putBoolean(KEY_DUAL_TRACK_PRESET_V1, true)
                .putBoolean(KEY_THEME_WHEEL_PRESET_V1, true)
                .apply()
            note(
                "KX11 applied default wheel presets " +
                    "(primary=$primaryCode single, theme=$primaryCode double, telemetry=long 5s, voiceSiri=${siriUnsetOrWeChat}, intercept=true)",
            )
            return
        }

        if (!sp.getBoolean(KEY_THEME_WHEEL_PRESET_V1, false)) {
            val current = GeelyClusterPrefs.load(app)
            val themeKey = if (current.themeSwitchKeyCode > 0) current.themeSwitchKeyCode else KEY_WECHAT
            val used = LinkedHashSet<GeelyClusterSwitchTrigger>()
            if (current.centerSwitchKeyCode == themeKey) used.add(current.centerSwitchTrigger)
            if (current.effectiveTelemetrySwitchKeyCode == themeKey) used.add(current.telemetrySwitchTrigger)
            val themeTrigger = GeelyClusterSwitchTrigger.resolveNonConflicting(
                GeelyClusterSwitchTrigger.DOUBLE_CLICK,
                used,
            )
            GeelyClusterPrefs.save(
                app,
                current.copy(
                    themeSwitchKeyCode = themeKey,
                    themeSwitchTrigger = themeTrigger,
                ),
            )
            sp.edit().putBoolean(KEY_THEME_WHEEL_PRESET_V1, true).apply()
            note("KX11 applied default theme switch preset (theme=$themeKey ${themeTrigger.name.lowercase()})")
        }
    }

    /** Starts listening for assigned KX11 vendor keys while a CarPlay session is active. */
    fun start(context: Context) {
        if (!EcarxKx11HeadUnit.detected) return
        val app = context.applicationContext
        ensureDefaultPresets(app)
        synchronized(this) {
            appContext = app
            sessionActive = true
            clearDoubleSiriLocked()
            syncBindingLocked()
        }
    }

    /** Stops runtime listening when the CarPlay session ends. */
    @Synchronized
    fun stop() {
        if (!sessionActive && !clusterActive && !bound && learning == null && clusterLearning == null) return
        sessionActive = false
        clearDoubleSiriLocked()
        syncBindingLocked()
    }

    /** Tracks whether the Geely cluster display (CarPlay or standalone persistent theme) is active. */
    fun setClusterActive(context: Context, active: Boolean) {
        if (!EcarxKx11HeadUnit.detected) return
        val app = context.applicationContext
        if (active) ensureDefaultPresets(app)
        synchronized(this) {
            appContext = app
            if (clusterActive == active && (!active || manager != null || bound)) {
                syncBindingLocked()
                return
            }
            clusterActive = active
            if (!active) {
                clusterDoublePress.reset()
                telemetryDoublePress.reset()
                themeDoublePress.reset()
                cancelPendingSingleClickLocked()
            }
            syncBindingLocked()
        }
    }

    /** Refreshes subscriptions after any [WheelZoomSettings] or [GeelyClusterPrefs] change. */
    fun settingsChanged() {
        if (!EcarxKx11HeadUnit.detected) return
        synchronized(this) {
            clearDoubleSiriLocked()
            syncBindingLocked()
        }
    }

    /**
     * Enters key-learning mode on `com.geely.inputservice` for Siri or Play/Pause assignment in
     * Settings. Returns `false` off KX11 or for unsupported roles.
     */
    fun learn(
        context: Context,
        role: WheelZoomSettings.Role,
        cancelled: () -> Unit = {},
        refused: (WheelZoomSettings.Role) -> Unit = {},
        done: (WheelKey) -> Unit,
    ): Boolean {
        if (!EcarxKx11HeadUnit.detected) return false
        if (role != WheelZoomSettings.Role.SIRI && role != WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) {
            return false
        }
        val previous: LearningSession?
        val previousCluster: ClusterLearningSession?
        synchronized(this) {
            appContext = context.applicationContext
            previous = learning
            previousCluster = clusterLearning
            clusterLearning = null
            learning = LearningSession(role, cancelled, refused, done)
            handler.removeCallbacks(endLearning)
            handler.postDelayed(endLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
            syncBindingLocked()
        }
        previous?.cancelled?.invoke()
        previousCluster?.cancelled?.invoke()
        return true
    }

    /**
     * Enters key-learning mode on `com.geely.inputservice` to bind a steering-wheel button for
     * switching either the primary Geely cluster view (Map / Album / Clock) or the temporary
     * telemetry peek pages (Trip / Tire / Vehicle Info). Returns `false` off KX11.
     */
    fun learnClusterSwitchKey(
        context: Context,
        target: ClusterLearnTarget = ClusterLearnTarget.PRIMARY,
        cancelled: () -> Unit = {},
        done: (Int) -> Unit,
    ): Boolean {
        if (!EcarxKx11HeadUnit.detected) return false
        val previous: LearningSession?
        val previousCluster: ClusterLearningSession?
        synchronized(this) {
            appContext = context.applicationContext
            previous = learning
            previousCluster = clusterLearning
            learning = null
            clusterLearning = ClusterLearningSession(target, cancelled, done)
            handler.removeCallbacks(endLearning)
            handler.postDelayed(endLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
            syncBindingLocked()
        }
        previous?.cancelled?.invoke()
        previousCluster?.cancelled?.invoke()
        return true
    }

    /** Cancels any active KX11 key-learning session and restores runtime binding state. */
    fun cancelLearning() {
        val cancelledPair = synchronized(this) {
            val prevRole = learning
            val prevCluster = clusterLearning
            if (prevRole == null && prevCluster == null) return
            learning = null
            clusterLearning = null
            handler.removeCallbacks(endLearning)
            syncBindingLocked()
            prevRole to prevCluster
        }
        cancelledPair.first?.cancelled?.invoke()
        cancelledPair.second?.cancelled?.invoke()
    }

    private fun runtimeCodes(context: Context): IntArray {
        val codes = LinkedHashSet<Int>()
        if (sessionActive) {
            if (WheelZoomSettings.siriKey(context)) {
                WheelZoomSettings.key(context, WheelZoomSettings.Role.SIRI)
                    ?.takeIf(::isGeelyInputKey)
                    ?.let { codes.add(it.code) }
            }
            if (WheelZoomSettings.mediaPlayPause(context)) {
                WheelZoomSettings.key(context, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE)
                    ?.takeIf(::isGeelyInputKey)
                    ?.let { codes.add(it.code) }
            }
        }
        if (sessionActive || clusterActive) {
            val clusterCfg = GeelyClusterPrefs.load(context)
            if (clusterCfg.centerSwitchKeyCode > 0) codes.add(clusterCfg.centerSwitchKeyCode)
            if (clusterCfg.telemetrySwitchKeyCode > 0) codes.add(clusterCfg.telemetrySwitchKeyCode)
            if (clusterCfg.themeSwitchKeyCode > 0) codes.add(clusterCfg.themeSwitchKeyCode)
        }
        return codes.toIntArray()
    }

    private fun shouldInterceptVoiceKey(context: Context): Boolean {
        if (!sessionActive || learning != null || clusterLearning != null) return false
        if (!WheelZoomSettings.siriKey(context)) return false
        val siriKey = WheelZoomSettings.key(context, WheelZoomSettings.Role.SIRI) ?: return false
        if (!isGeelyInputKey(siriKey) || siriKey.code != KEY_VOICE_ASSIST) return false
        return GeelyClusterPrefs.load(context).interceptVoiceKeyForSiri
    }

    private fun syncVoiceKeyInterceptLocked(input: IInputManager, context: Context) {
        val wantIntercept = shouldInterceptVoiceKey(context)
        if (wantIntercept) {
            if (KEY_VOICE_ASSIST !in interceptedCodes) {
                runCatching { input.interceptKeyCode(KEY_VOICE_ASSIST, context.packageName) }
                    .onSuccess { granted ->
                        if (granted) {
                            interceptedCodes.add(KEY_VOICE_ASSIST)
                            lastVoiceInterceptStatus = VoiceInterceptStatus.GRANTED
                        } else {
                            lastVoiceInterceptStatus = VoiceInterceptStatus.REJECTED
                        }
                        note("KX11 interceptKeyCode($KEY_VOICE_ASSIST) granted=$granted")
                    }
                    .onFailure { error ->
                        lastVoiceInterceptStatus = VoiceInterceptStatus.ERROR
                        note("KX11 interceptKeyCode($KEY_VOICE_ASSIST) failed: ${error.javaClass.simpleName}")
                    }
            }
        } else {
            if (KEY_VOICE_ASSIST in interceptedCodes) {
                runCatching { input.releaseKeyCode(KEY_VOICE_ASSIST, context.packageName) }
                    .onSuccess { released ->
                        note("KX11 releaseKeyCode($KEY_VOICE_ASSIST) released=$released")
                    }
                    .onFailure { error ->
                        note("KX11 releaseKeyCode($KEY_VOICE_ASSIST) failed: ${error.javaClass.simpleName}")
                    }
                interceptedCodes.remove(KEY_VOICE_ASSIST)
            }
            if (!sessionActive || !GeelyClusterPrefs.load(context).interceptVoiceKeyForSiri) {
                lastVoiceInterceptStatus = VoiceInterceptStatus.IDLE
            }
        }
    }

    private fun releaseAllInterceptedLocked(input: IInputManager?, context: Context?) {
        if (interceptedCodes.isNotEmpty() && input != null && context != null) {
            for (code in interceptedCodes.toList()) {
                runCatching { input.releaseKeyCode(code, context.packageName) }
                    .onSuccess { released ->
                        note("KX11 releaseKeyCode($code) released=$released")
                    }
            }
        }
        interceptedCodes.clear()
        lastVoiceInterceptStatus = VoiceInterceptStatus.IDLE
    }

    private fun desiredCodesLocked(context: Context): IntArray = when {
        learning != null || clusterLearning != null -> learnableCodesForSession()
        sessionActive || clusterActive -> runtimeCodes(context)
        else -> IntArray(0)
    }

    private fun syncBindingLocked() {
        val context = appContext ?: return
        val desired = desiredCodesLocked(context)
        if (desired.isEmpty()) {
            unbindLocked()
            return
        }
        val input = manager
        if (input != null) {
            if (!registeredCodes.contentEquals(desired)) {
                registerLocked(input, context, desired)
            } else {
                syncVoiceKeyInterceptLocked(input, context)
            }
        } else if (!bound) {
            bindLocked(context)
        }
    }

    private fun bindLocked(context: Context) {
        handler.removeCallbacks(retryBind)
        val intent = Intent().setComponent(ComponentName(INPUT_PACKAGE, INPUT_CLASS))
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { note("KX11 input service bind failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        note("KX11 input service bind requested=$bound")
    }

    private fun unbindLocked() {
        handler.removeCallbacks(retryBind)
        val context = appContext
        releaseAllInterceptedLocked(manager, context)
        listener?.let { registered ->
            if (context != null) {
                runCatching { manager?.unregisterListener(registered, context.packageName) }
            }
        }
        listener = null
        manager = null
        registeredCodes = IntArray(0)
        if (bound && context != null) {
            runCatching { context.unbindService(connection) }
        }
        bound = false
    }

    private fun registerLocked(input: IInputManager, context: Context, codes: IntArray) {
        listener?.let { old ->
            runCatching { input.unregisterListener(old, context.packageName) }
        }
        val nextListener = createListener()
        val isLearn = learning != null || clusterLearning != null
        val registeredResult = runCatching {
            input.registerListener(nextListener, context.packageName, codes)
            codes
        }.recoverCatching { firstError ->
            if (isLearn && !codes.contentEquals(LEARNABLE_CODES)) {
                note("KX11 expanded learn register failed (${firstError.javaClass.simpleName}); falling back to baseline codes")
                input.registerListener(nextListener, context.packageName, LEARNABLE_CODES)
                LEARNABLE_CODES
            } else {
                throw firstError
            }
        }
        registeredResult.onSuccess { activeCodes ->
            listener = nextListener
            registeredCodes = activeCodes.copyOf()
            val mode = if (isLearn) "learn" else "runtime"
            note("KX11 input service registered ($mode) count=${activeCodes.size} codes=${activeCodes.joinToString()}")
            syncVoiceKeyInterceptLocked(input, context)
        }.onFailure { error ->
            listener = null
            registeredCodes = IntArray(0)
            note("KX11 input service register failed: ${error.javaClass.simpleName}")
        }
    }

    private fun createListener(): IInputListener = object : IInputListener.Stub() {
        override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {
            // Action 0 = key down on com.geely.inputservice; capture immediately during learning.
            if (event == 0 && (learning != null || clusterLearning != null)) {
                handleLearnKey(keyCode)
            }
        }

        override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
            if (learning != null || clusterLearning != null) {
                handleLearnKey(keyCode)
                return
            }
            handleRuntimeKey(keyCode, longPress = false, nativeDoubleClick = false)
        }

        override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) = Unit

        override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) = Unit

        override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
            if (learning != null || clusterLearning != null) {
                handleLearnKey(keyCode)
                return
            }
            handleRuntimeKey(keyCode, longPress = true, nativeDoubleClick = false)
        }

        override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) {
            if (learning != null || clusterLearning != null) {
                handleLearnKey(keyCode)
                return
            }
            handleRuntimeKey(keyCode, longPress = false, nativeDoubleClick = true)
        }
    }

    private fun handleLearnKey(keyCode: Int) {
        val capturedRole: LearningSession?
        val capturedCluster: ClusterLearningSession?
        val context: Context
        synchronized(this) {
            capturedRole = learning
            capturedCluster = clusterLearning
            if (capturedRole == null && capturedCluster == null) return
            context = appContext ?: return
            learning = null
            clusterLearning = null
            handler.removeCallbacks(endLearning)
            // Suppress the release/click of the press that just completed key learning.
            ignoreRuntimeKeyCode = keyCode
            ignoreRuntimeUntilElapsedMs = SystemClock.elapsedRealtime() + LEARN_COOLDOWN_MILLIS
        }
        handler.post {
            if (capturedCluster != null) {
                val current = GeelyClusterPrefs.load(context)
                val updated = when (capturedCluster.target) {
                    ClusterLearnTarget.PRIMARY -> {
                        val sameAsTelemetry = current.telemetrySwitchKeyCode == keyCode
                        val nextTelemetryCode = if (sameAsTelemetry) 0 else current.telemetrySwitchKeyCode
                        val nextTelemetryTrigger = if (nextTelemetryCode == 0) {
                            GeelyClusterSwitchTrigger.resolveNonConflicting(
                                current.telemetrySwitchTrigger,
                                setOf(current.centerSwitchTrigger),
                            )
                        } else {
                            current.telemetrySwitchTrigger
                        }
                        val nextThemeTrigger = if (current.themeSwitchKeyCode == keyCode) {
                            val used = linkedSetOf(current.centerSwitchTrigger)
                            if (nextTelemetryCode == 0) used.add(nextTelemetryTrigger)
                            GeelyClusterSwitchTrigger.resolveNonConflicting(current.themeSwitchTrigger, used)
                        } else {
                            current.themeSwitchTrigger
                        }
                        current.copy(
                            centerSwitchKeyCode = keyCode,
                            telemetrySwitchKeyCode = nextTelemetryCode,
                            telemetrySwitchTrigger = nextTelemetryTrigger,
                            themeSwitchTrigger = nextThemeTrigger,
                        )
                    }
                    ClusterLearnTarget.TELEMETRY -> {
                        val sharesPrimary = keyCode == current.centerSwitchKeyCode
                        val nextTelemetryCode = if (sharesPrimary) 0 else keyCode
                        val nextTelemetryTrigger = if (sharesPrimary) {
                            GeelyClusterSwitchTrigger.resolveNonConflicting(
                                current.telemetrySwitchTrigger,
                                setOf(current.centerSwitchTrigger),
                            )
                        } else {
                            current.telemetrySwitchTrigger
                        }
                        val nextThemeTrigger = if (current.themeSwitchKeyCode == keyCode) {
                            val used = linkedSetOf(nextTelemetryTrigger)
                            if (sharesPrimary) used.add(current.centerSwitchTrigger)
                            GeelyClusterSwitchTrigger.resolveNonConflicting(current.themeSwitchTrigger, used)
                        } else {
                            current.themeSwitchTrigger
                        }
                        current.copy(
                            telemetrySwitchKeyCode = nextTelemetryCode,
                            telemetrySwitchTrigger = nextTelemetryTrigger,
                            themeSwitchTrigger = nextThemeTrigger,
                        )
                    }
                    ClusterLearnTarget.THEME -> {
                        val used = LinkedHashSet<GeelyClusterSwitchTrigger>()
                        if (current.centerSwitchKeyCode == keyCode) used.add(current.centerSwitchTrigger)
                        if (current.effectiveTelemetrySwitchKeyCode == keyCode) used.add(current.telemetrySwitchTrigger)
                        val nextThemeTrigger = GeelyClusterSwitchTrigger.resolveNonConflicting(
                            current.themeSwitchTrigger,
                            used,
                        )
                        current.copy(
                            themeSwitchKeyCode = keyCode,
                            themeSwitchTrigger = nextThemeTrigger,
                        )
                    }
                }
                GeelyClusterPrefs.save(context, updated)
                GeelyClusterEnhancer.refreshConfig(context)
                note("KX11 cluster ${capturedCluster.target.name.lowercase()} key is now $keyCode")
                capturedCluster.done.invoke(keyCode)
                synchronized(this) { syncBindingLocked() }
                return@post
            }
            val captured = capturedRole ?: return@post
            val enabled = when (captured.role) {
                WheelZoomSettings.Role.SIRI -> WheelZoomSettings.siriKey(context)
                WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> WheelZoomSettings.mediaPlayPause(context)
                else -> false
            }
            if (inCall(context) || !enabled) {
                captured.cancelled.invoke()
                synchronized(this) { syncBindingLocked() }
                return@post
            }
            val wheelKey = toWheelKey(keyCode)
            val taken = WheelZoomSettings.conflict(context, captured.role, wheelKey)
            if (taken != null) {
                note("KX11 ${captured.role} key $wheelKey refused: it is the $taken key")
                captured.refused.invoke(taken)
            } else {
                WheelZoomSettings.assign(context, captured.role, wheelKey)
                note("KX11 ${captured.role} key is now $wheelKey")
                captured.done.invoke(wheelKey)
            }
            synchronized(this) { syncBindingLocked() }
        }
    }

    private fun handleRuntimeKey(keyCode: Int, longPress: Boolean, nativeDoubleClick: Boolean) {
        val context: Context
        synchronized(this) {
            if (!sessionActive && !clusterActive) return
            context = appContext ?: return
            val now = SystemClock.elapsedRealtime()
            if (ignoreRuntimeKeyCode == keyCode && now <= ignoreRuntimeUntilElapsedMs) {
                return
            }
            ignoreRuntimeKeyCode = null
        }
        handler.post { dispatchRuntimeKeyOnMain(context, keyCode, longPress, nativeDoubleClick) }
    }

    private fun dispatchRuntimeKeyOnMain(
        context: Context,
        keyCode: Int,
        longPress: Boolean,
        nativeDoubleClick: Boolean,
    ) {
        if ((sessionActive || clusterActive) &&
            dispatchClusterKeyOnMain(context, keyCode, longPress, nativeDoubleClick)
        ) {
            return
        }
        if (nativeDoubleClick) return

        if (!sessionActive) return
        val wheelKey = toWheelKey(keyCode)
        val calling = inCall(context)

        if (WheelZoomSettings.isMediaPlayPauseKey(context, wheelKey)) {
            if (!longPress && !calling) {
                note("KX11 remapped media play/pause key $keyCode")
                CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.PLAY_PAUSE)
            }
            return
        }

        if (!WheelZoomSettings.isSiriKey(context, wheelKey)) return

        val now = SystemClock.elapsedRealtime()
        if (WheelZoomSettings.siriDoublePress(context)) {
            if (longPress) return
            val intervalMs = WheelZoomSettings.siriDoublePressIntervalMillis(context)
            synchronized(this) {
                when (siriDebounce.onDoublePress(now, intervalMs.toLong())) {
                    WheelSiriKey.DoublePressAction.FIRST -> {
                        doubleSiriFirstPressWasInCall = calling
                    }
                    WheelSiriKey.DoublePressAction.REPEAT -> Unit
                    WheelSiriKey.DoublePressAction.CONFIRMED -> {
                        val firstWasInCall = doubleSiriFirstPressWasInCall
                        doubleSiriFirstPressWasInCall = false
                        if (firstWasInCall || calling) return
                        val delayMs = WheelZoomSettings.siriTriggerDelayMillis(context)
                        cancelDelayedSiriLocked()
                        val task = Runnable {
                            synchronized(this) { delayedSiriRunnable = null }
                            if (!sessionActive || !WheelZoomSettings.siriDoublePress(context) ||
                                !WheelZoomSettings.isSiriKey(context, wheelKey) || inCall(context)
                            ) return@Runnable
                            val sent = CarPlayMediaKeys.onExternalSiriKey()
                            note("KX11 Siri double key $keyCode sent=$sent intervalMs=$intervalMs delayMs=$delayMs")
                        }
                        delayedSiriRunnable = task
                        handler.postDelayed(task, delayMs.toLong())
                    }
                }
            }
            return
        }

        // Single-press mode: the steering-wheel voice key's short press belongs to the car's native
        // assistant unless CarPlay successfully intercepted KEY_VOICE_ASSIST via IInputManager.
        val voiceShortAllowed = keyCode == KEY_VOICE_ASSIST &&
            GeelyClusterPrefs.load(context).interceptVoiceKeyForSiri &&
            isVoiceKeyIntercepted()
        if (keyCode == KEY_VOICE_ASSIST && !longPress && !voiceShortAllowed) return
        if (calling) return
        val shouldOpen = synchronized(this) { siriDebounce.opens(now) }
        if (shouldOpen) {
            val sent = CarPlayMediaKeys.onExternalSiriKey()
            val gesture = if (longPress) "long" else "short"
            note("KX11 Siri key $keyCode ($gesture, intercepted=$voiceShortAllowed) sent=$sent")
        }
    }

    private enum class ClusterKeyRole(val logLabel: String) {
        PRIMARY("primary view"),
        TELEMETRY("telemetry"),
        THEME("cluster theme"),
    }

    private fun executeClusterRole(
        context: Context,
        role: ClusterKeyRole,
        closeTelemetryAfterLast: Boolean,
    ): String = when (role) {
        ClusterKeyRole.PRIMARY -> GeelyClusterEnhancer.cyclePrimaryCenterMode(context)
        ClusterKeyRole.TELEMETRY -> GeelyClusterEnhancer.cycleTelemetryCenterMode(
            context,
            closeAfterLast = closeTelemetryAfterLast,
        )
        ClusterKeyRole.THEME -> GeelyClusterEnhancer.cycleThemeStyle(context)
    }

    private fun doublePressTrackerFor(role: ClusterKeyRole): WheelSiriKey = when (role) {
        ClusterKeyRole.PRIMARY -> clusterDoublePress
        ClusterKeyRole.TELEMETRY -> telemetryDoublePress
        ClusterKeyRole.THEME -> themeDoublePress
    }

    private fun dispatchClusterKeyOnMain(
        context: Context,
        keyCode: Int,
        longPress: Boolean,
        nativeDoubleClick: Boolean,
    ): Boolean {
        val cfg = GeelyClusterPrefs.load(context)
        val primaryKey = cfg.centerSwitchKeyCode
        val telemetryKey = cfg.effectiveTelemetrySwitchKeyCode
        val themeKey = cfg.themeSwitchKeyCode
        if (primaryKey <= 0 && telemetryKey <= 0 && themeKey <= 0) return false

        // Never hijack call answer/hangup keys while a phone call is active.
        if ((keyCode == KEY_CALL || keyCode == KEY_ENDCALL) && inCall(context)) return false

        // In OEM Classic or Pure Map fallback (currently showing native OEM cluster),
        // only the theme switch key responds so accidental presses never pull the driver out of OEM.
        val showingOemFallback = cfg.dialThemeStyle.isOemNative ||
            (cfg.dialThemeStyle.isPureMap && !GeelyClusterEnhancer.hasActiveMapSourceNow())

        val bindings = ArrayList<Pair<ClusterKeyRole, GeelyClusterSwitchTrigger>>(3)
        if (!showingOemFallback) {
            if (primaryKey > 0 && keyCode == primaryKey) {
                bindings.add(ClusterKeyRole.PRIMARY to cfg.centerSwitchTrigger)
            }
            if (telemetryKey > 0 && keyCode == telemetryKey) {
                bindings.add(ClusterKeyRole.TELEMETRY to cfg.telemetrySwitchTrigger)
            }
        }
        if (themeKey > 0 && keyCode == themeKey) {
            bindings.add(ClusterKeyRole.THEME to cfg.themeSwitchTrigger)
        }
        if (bindings.isEmpty()) return false

        val singleRole = bindings.firstOrNull { it.second == GeelyClusterSwitchTrigger.SINGLE_CLICK }?.first
        val longRole = bindings.firstOrNull { it.second == GeelyClusterSwitchTrigger.LONG_PRESS }?.first
        val doubleRole = bindings.firstOrNull { it.second == GeelyClusterSwitchTrigger.DOUBLE_CLICK }?.first
        val inTelemetry = !showingOemFallback && GeelyClusterEnhancer.isTelemetryPageActive(context)

        if (longPress) {
            synchronized(this) { cancelPendingSingleClickLocked() }
            val role = longRole ?: return false
            val res = executeClusterRole(context, role, closeTelemetryAfterLast = false)
            note("KX11 ${role.logLabel} key $keyCode (long) -> $res")
            return true
        }

        if (nativeDoubleClick) {
            val role = doubleRole ?: return false
            val now = SystemClock.elapsedRealtime()
            val alreadyHandled = synchronized(this) {
                cancelPendingSingleClickLocked()
                val dup = lastDoubleFiredKeyCode == keyCode && (now - lastDoubleFiredElapsedMs) < 280L
                if (!dup) {
                    lastDoubleFiredKeyCode = keyCode
                    lastDoubleFiredElapsedMs = now
                }
                dup
            }
            if (alreadyHandled) return true
            val res = executeClusterRole(context, role, closeTelemetryAfterLast = false)
            note("KX11 ${role.logLabel} key $keyCode (native-double) -> $res")
            return true
        }

        // Short click while viewing telemetry: if this key drives primary or telemetry via single-click
        // and does not share double-click on the same key, advance telemetry immediately with zero delay.
        if (inTelemetry && doubleRole == null &&
            (singleRole == ClusterKeyRole.PRIMARY || singleRole == ClusterKeyRole.TELEMETRY)
        ) {
            synchronized(this) { cancelPendingSingleClickLocked() }
            val res = GeelyClusterEnhancer.cycleTelemetryCenterMode(context, closeAfterLast = true)
            note("KX11 telemetry next key $keyCode (short) -> $res")
            return true
        }

        if (singleRole != null && doubleRole != null) {
            val secondTapConfirmed = synchronized(this) {
                if (pendingSingleClickRunnable != null && pendingSingleClickKeyCode == keyCode) {
                    cancelPendingSingleClickLocked()
                    lastDoubleFiredKeyCode = keyCode
                    lastDoubleFiredElapsedMs = SystemClock.elapsedRealtime()
                    true
                } else {
                    cancelPendingSingleClickLocked()
                    val appCtx = context.applicationContext
                    val runnable = Runnable {
                        synchronized(this@EcarxKx11WheelKeys) {
                            pendingSingleClickRunnable = null
                            pendingSingleClickKeyCode = 0
                        }
                        val res = if (GeelyClusterEnhancer.isTelemetryPageActive(appCtx) &&
                            (singleRole == ClusterKeyRole.PRIMARY || singleRole == ClusterKeyRole.TELEMETRY)
                        ) {
                            GeelyClusterEnhancer.cycleTelemetryCenterMode(appCtx, closeAfterLast = true)
                        } else {
                            executeClusterRole(appCtx, singleRole, closeTelemetryAfterLast = true)
                        }
                        note("KX11 ${singleRole.logLabel} key $keyCode (single-delayed) -> $res")
                    }
                    pendingSingleClickRunnable = runnable
                    pendingSingleClickKeyCode = keyCode
                    handler.postDelayed(runnable, SINGLE_VS_DOUBLE_SPLIT_MS)
                    false
                }
            }
            if (secondTapConfirmed) {
                val res = executeClusterRole(context, doubleRole, closeTelemetryAfterLast = false)
                note("KX11 ${doubleRole.logLabel} key $keyCode (double) -> $res")
            }
            return true
        }

        if (singleRole != null) {
            val res = executeClusterRole(context, singleRole, closeTelemetryAfterLast = true)
            note("KX11 ${singleRole.logLabel} key $keyCode (short) -> $res")
            return true
        }

        if (doubleRole != null) {
            val now = SystemClock.elapsedRealtime()
            val tracker = doublePressTrackerFor(doubleRole)
            val action = synchronized(this) {
                tracker.onDoublePress(now, CLUSTER_DOUBLE_CLICK_INTERVAL_MS)
            }
            if (action == WheelSiriKey.DoublePressAction.CONFIRMED) {
                synchronized(this) {
                    lastDoubleFiredKeyCode = keyCode
                    lastDoubleFiredElapsedMs = now
                }
                val res = executeClusterRole(context, doubleRole, closeTelemetryAfterLast = false)
                note("KX11 ${doubleRole.logLabel} key $keyCode (double) -> $res")
                return true
            }
        }
        return false
    }

    private fun clearDoubleSiriLocked() {
        cancelDelayedSiriLocked()
        cancelPendingSingleClickLocked()
        doubleSiriFirstPressWasInCall = false
        siriDebounce.reset()
        clusterDoublePress.reset()
        telemetryDoublePress.reset()
        themeDoublePress.reset()
        lastDoubleFiredKeyCode = 0
        lastDoubleFiredElapsedMs = 0L
    }

    private fun cancelPendingSingleClickLocked() {
        pendingSingleClickRunnable?.let(handler::removeCallbacks)
        pendingSingleClickRunnable = null
        pendingSingleClickKeyCode = 0
    }

    private fun cancelDelayedSiriLocked() {
        delayedSiriRunnable?.let(handler::removeCallbacks)
        delayedSiriRunnable = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val input = IInputManager.Stub.asInterface(service)
            synchronized(this@EcarxKx11WheelKeys) {
                val context = appContext ?: return
                manager = input
                val desired = desiredCodesLocked(context)
                if (desired.isEmpty()) {
                    unbindLocked()
                } else {
                    registerLocked(input, context, desired)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val shouldRetry: Boolean
            synchronized(this@EcarxKx11WheelKeys) {
                manager = null
                listener = null
                registeredCodes = IntArray(0)
                interceptedCodes.clear()
                lastVoiceInterceptStatus = VoiceInterceptStatus.IDLE
                val context = appContext
                shouldRetry = context != null && desiredCodesLocked(context).isNotEmpty()
            }
            if (shouldRetry) {
                handler.removeCallbacks(retryBind)
                handler.postDelayed(retryBind, RETRY_MILLIS)
            }
        }
    }

    private fun note(message: String) {
        Log.i(TAG, message)
        WheelKeyLog.note(message)
    }
}
