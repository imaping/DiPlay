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

    internal const val DEVICE_NAME = "geely-inputservice"

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

    private class LearningSession(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val siriDebounce = WheelSiriKey()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var sessionActive = false
    private var bound = false
    private var manager: IInputManager? = null
    private var listener: IInputListener? = null
    private var registeredCodes: IntArray = IntArray(0)
    @Volatile
    private var learning: LearningSession? = null
    private var ignoreRuntimeKeyCode: Int? = null
    private var ignoreRuntimeUntilElapsedMs = 0L
    private var doubleSiriFirstPressWasInCall = false
    private var delayedSiriRunnable: Runnable? = null

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

    /** Starts listening for assigned KX11 vendor keys while a CarPlay session is active. */
    @Synchronized
    fun start(context: Context) {
        if (!EcarxKx11HeadUnit.detected) return
        appContext = context.applicationContext
        sessionActive = true
        clearDoubleSiriLocked()
        syncBindingLocked()
    }

    /** Stops runtime listening when the CarPlay session ends. */
    @Synchronized
    fun stop() {
        if (!sessionActive && !bound && learning == null) return
        sessionActive = false
        clearDoubleSiriLocked()
        syncBindingLocked()
    }

    /** Refreshes subscriptions after any [WheelZoomSettings] change. */
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
        synchronized(this) {
            appContext = context.applicationContext
            previous = learning
            learning = LearningSession(role, cancelled, refused, done)
            handler.removeCallbacks(endLearning)
            handler.postDelayed(endLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
            syncBindingLocked()
        }
        previous?.cancelled?.invoke()
        return true
    }

    /** Cancels any active KX11 key-learning session and restores runtime binding state. */
    fun cancelLearning() {
        val previous: LearningSession?
        synchronized(this) {
            previous = learning
            if (previous == null) return
            learning = null
            handler.removeCallbacks(endLearning)
            syncBindingLocked()
        }
        previous.cancelled.invoke()
    }

    private fun runtimeCodes(context: Context): IntArray {
        val codes = LinkedHashSet<Int>()
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
        return codes.toIntArray()
    }

    private fun desiredCodesLocked(context: Context): IntArray = when {
        learning != null -> LEARNABLE_CODES
        sessionActive -> runtimeCodes(context)
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
        runCatching {
            input.registerListener(nextListener, context.packageName, codes)
        }.onSuccess {
            listener = nextListener
            registeredCodes = codes.copyOf()
            val mode = if (learning != null) "learn" else "runtime"
            note("KX11 input service registered ($mode) codes=${codes.joinToString()}")
        }.onFailure { error ->
            listener = null
            registeredCodes = IntArray(0)
            note("KX11 input service register failed: ${error.javaClass.simpleName}")
        }
    }

    private fun createListener(): IInputListener = object : IInputListener.Stub() {
        override fun onKeyCodeEvent(keyCode: Int, event: Int, softKeyFunction: Int) {
            // Action 0 = key down on com.geely.inputservice; capture immediately during learning.
            if (event == 0 && learning != null) {
                handleLearnKey(keyCode)
            }
        }

        override fun onShortClick(keyCode: Int, softKeyFunction: Int) {
            if (learning != null) {
                handleLearnKey(keyCode)
                return
            }
            handleRuntimeKey(keyCode, longPress = false)
        }

        override fun onHoldingPressStarted(keyCode: Int, softKeyFunction: Int) = Unit

        override fun onHoldingPressStopped(keyCode: Int, softKeyFunction: Int) = Unit

        override fun onLongPressTriggered(keyCode: Int, softKeyFunction: Int) {
            if (learning != null) {
                handleLearnKey(keyCode)
                return
            }
            handleRuntimeKey(keyCode, longPress = true)
        }

        override fun onDoubleClick(keyCode: Int, softKeyFunction: Int) = Unit
    }

    private fun handleLearnKey(keyCode: Int) {
        val captured: LearningSession
        val context: Context
        synchronized(this) {
            captured = learning ?: return
            context = appContext ?: return
            learning = null
            handler.removeCallbacks(endLearning)
            // Suppress the release/click of the press that just completed key learning.
            ignoreRuntimeKeyCode = keyCode
            ignoreRuntimeUntilElapsedMs = SystemClock.elapsedRealtime() + LEARN_COOLDOWN_MILLIS
        }
        handler.post {
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

    private fun handleRuntimeKey(keyCode: Int, longPress: Boolean) {
        val context: Context
        synchronized(this) {
            if (!sessionActive) return
            context = appContext ?: return
            val now = SystemClock.elapsedRealtime()
            if (ignoreRuntimeKeyCode == keyCode && now <= ignoreRuntimeUntilElapsedMs) {
                return
            }
            ignoreRuntimeKeyCode = null
        }
        handler.post { dispatchRuntimeKeyOnMain(context, keyCode, longPress) }
    }

    private fun dispatchRuntimeKeyOnMain(context: Context, keyCode: Int, longPress: Boolean) {
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
        // assistant, so only its long press triggers Siri; spare keys (e.g. WeChat 200400) trigger
        // cleanly on either short click or long press (debounced once per press).
        if (keyCode == KEY_VOICE_ASSIST && !longPress) return
        if (calling) return
        val shouldOpen = synchronized(this) { siriDebounce.opens(now) }
        if (shouldOpen) {
            val sent = CarPlayMediaKeys.onExternalSiriKey()
            val gesture = if (longPress) "long" else "short"
            note("KX11 Siri key $keyCode ($gesture) sent=$sent")
        }
    }

    private fun clearDoubleSiriLocked() {
        cancelDelayedSiriLocked()
        doubleSiriFirstPressWasInCall = false
        siriDebounce.reset()
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
