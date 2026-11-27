package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Handles silent background startup of the persistent Geely / ECARX instrument-cluster theme
 * on `ACTION_BOOT_COMPLETED`, without popping up `DiPlayActivity` on the center screen when the
 * driver only wants the cluster theme running in the background (e.g. pure head-unit usage
 * without connecting CarPlay).
 *
 * Kept as a standalone extension file so upstream [BootReceiver] only needs a single hook line.
 */
internal object GeelyClusterBootLauncher {
    private const val TAG = "DiPlay-GeelyBoot"

    /**
     * Evaluates boot-time cluster startup on Geely / ECARX head units.
     *
     * @param launchEnabled Whether the global "Open after the car starts" (`openAfterBoot`)
     * setting is enabled.
     * @return `true` when this handler has taken care of boot startup silently in the background
     * and [BootReceiver] should NOT pop up `DiPlayActivity` on the center screen; `false` to let
     * [BootReceiver] continue with its default activity launch logic.
     */
    fun onBootCompleted(context: Context, launchEnabled: Boolean): Boolean {
        val app = context.applicationContext
        if (!GeelyVehicleDetector.isGeelyOrEcarx(app)) return false

        val cfg = GeelyClusterPrefs.load(app)
        if (!cfg.launchThemeOnStart || !cfg.hasAnyClusterVisual) return false

        val overlayGranted = Settings.canDrawOverlays(app)
        if (overlayGranted) {
            GeelyClusterKeepAliveService.startForBoot(app)
            note("Geely cluster boot silent launch requested (openAfterBoot=$launchEnabled)")
        } else {
            note("Geely cluster boot skipped: SYSTEM_ALERT_WINDOW not granted")
        }

        if (!launchEnabled) {
            // User did not enable foreground launch on boot; stay silent in background.
            return true
        }

        // Even when "Open after the car starts" is enabled, stay completely silent in the
        // background unless the user also enabled "Connect when DiPlay opens". Pure head-unit
        // drivers can use the cluster theme immediately without DiPlay taking over the center screen.
        val wantsAutoConnectCarPlay = DiPlayPreferences.autoConnect(app)
        if (!wantsAutoConnectCarPlay && overlayGranted) {
            note("Geely cluster boot suppressed center UI (connectWhenOpened=false)")
            return true
        }
        return false
    }

    internal fun note(message: String) {
        Log.i(TAG, message)
        WheelKeyLog.note(message)
    }
}

/**
 * Coordinates boot-time readiness retries for the persistent Geely cluster theme:
 * 1. Waits for the instrument-cluster HDMI display (`Display 2`) to appear via both
 *    [DisplayManager.DisplayListener] and bounded backoff timers, attaching the `1920x720`
 *    overlay as soon as the panel is ready.
 * 2. After attaching, verifies `DimMenuInteraction` mode 3 a few times during the head-unit
 *    boot animation window so a late-starting DIM service or boot animation cannot leave the
 *    instrument panel stuck in classic mode (`mode 1`), while stopping early once confirmed to
 *    never fight manual mode switches later.
 */
internal class GeelyClusterBootRetryController(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private var appContext: Context? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private var attachAttemptIndex: Int = 0
    private var dimCheckIndex: Int = 0
    private var consecutiveDimMode3Count: Int = 0
    private var active: Boolean = false

    private var tryAttachCallback: (() -> Boolean)? = null
    private var shouldContinueCallback: (() -> Boolean)? = null
    private var ensureDimFullScreenCallback: (() -> Boolean)? = null
    private var onGiveUpCallback: (() -> Unit)? = null

    private val attachRetryRunnable = Runnable {
        if (!active) return@Runnable
        attemptAttach()
    }

    private val dimVerifyRunnable = Runnable {
        if (!active) return@Runnable
        verifyDimFullScreen()
    }

    fun start(
        context: Context,
        shouldContinue: () -> Boolean,
        tryAttach: () -> Boolean,
        ensureDimFullScreen: () -> Boolean,
        onGiveUp: () -> Unit,
    ) {
        cancel()
        val app = context.applicationContext
        appContext = app
        shouldContinueCallback = shouldContinue
        tryAttachCallback = tryAttach
        ensureDimFullScreenCallback = ensureDimFullScreen
        onGiveUpCallback = onGiveUp
        attachAttemptIndex = 0
        dimCheckIndex = 0
        consecutiveDimMode3Count = 0
        active = true

        attemptAttach()
    }

    fun cancel() {
        active = false
        mainHandler.removeCallbacks(attachRetryRunnable)
        mainHandler.removeCallbacks(dimVerifyRunnable)
        unregisterDisplayListener()
        appContext = null
        tryAttachCallback = null
        shouldContinueCallback = null
        ensureDimFullScreenCallback = null
        onGiveUpCallback = null
    }

    private fun attemptAttach() {
        if (!active) return
        val keepGoing = shouldContinueCallback?.invoke() == true
        if (!keepGoing) {
            cancel()
            return
        }
        val attached = tryAttachCallback?.invoke() == true
        if (attached) {
            GeelyClusterBootLauncher.note("Geely cluster boot overlay attached (attempt=$attachAttemptIndex)")
            unregisterDisplayListener()
            mainHandler.removeCallbacks(attachRetryRunnable)
            scheduleNextDimVerify()
            return
        }
        if (attachAttemptIndex >= ATTACH_BACKOFF_MS.size) {
            GeelyClusterBootLauncher.note("Geely cluster boot overlay attach timed out after $attachAttemptIndex retries")
            val giveUp = onGiveUpCallback
            cancel()
            giveUp?.invoke()
            return
        }
        registerDisplayListenerIfNeeded()
        val delayMs = ATTACH_BACKOFF_MS[attachAttemptIndex++]
        mainHandler.removeCallbacks(attachRetryRunnable)
        mainHandler.postDelayed(attachRetryRunnable, delayMs)
    }

    private fun scheduleNextDimVerify() {
        if (!active) return
        if (dimCheckIndex >= DIM_VERIFY_DELAYS_MS.size) {
            cancel()
            return
        }
        val delayMs = DIM_VERIFY_DELAYS_MS[dimCheckIndex++]
        mainHandler.removeCallbacks(dimVerifyRunnable)
        mainHandler.postDelayed(dimVerifyRunnable, delayMs)
    }

    private fun verifyDimFullScreen() {
        if (!active) return
        val keepGoing = shouldContinueCallback?.invoke() == true
        if (!keepGoing) {
            cancel()
            return
        }
        val confirmedMode3 = ensureDimFullScreenCallback?.invoke() == true
        if (confirmedMode3) {
            consecutiveDimMode3Count++
            if (consecutiveDimMode3Count >= REQUIRED_CONSECUTIVE_DIM_CONFIRMS) {
                GeelyClusterBootLauncher.note("Geely cluster boot DIM mode 3 stable")
                cancel()
                return
            }
        } else {
            consecutiveDimMode3Count = 0
        }
        scheduleNextDimVerify()
    }

    private fun registerDisplayListenerIfNeeded() {
        if (displayListener != null) return
        val app = appContext ?: return
        val dm = app.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (!active) return
                mainHandler.post { attemptAttach() }
            }

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (!active) return
                mainHandler.post { attemptAttach() }
            }
        }
        runCatching {
            dm.registerDisplayListener(listener, mainHandler)
            displayManager = dm
            displayListener = listener
        }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        runCatching { displayManager?.unregisterDisplayListener(listener) }
        displayManager = null
    }

    private companion object {
        // Covers ~90 s of cold head-unit boot while waiting for HDMI cluster display enumeration.
        val ATTACH_BACKOFF_MS = longArrayOf(
            2_000L,
            3_000L,
            5_000L,
            8_000L,
            12_000L,
            15_000L,
            20_000L,
            25_000L,
        )

        // Confirms DIM mode 3 survives the instrument boot animation / late DIM binder startup.
        val DIM_VERIFY_DELAYS_MS = longArrayOf(
            3_000L,
            6_000L,
            10_000L,
            15_000L,
        )

        const val REQUIRED_CONSECUTIVE_DIM_CONFIRMS = 2
    }
}
