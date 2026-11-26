package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.Locale

internal data class GeelyAmapOverviewConfig(
    val autoOverviewOnNavStart: Boolean = true,
    val autoOverviewDelayMs: Int = DEFAULT_AUTO_DELAY_MS,
) {
    companion object {
        const val DEFAULT_AUTO_DELAY_MS = 2500
        const val OVERVIEW_BUTTON_X = 0.90f
        const val OVERVIEW_BUTTON_Y = 0.10f
        val DELAY_PRESETS_MS = listOf(1500, 2500, 3500, 5000)
    }
}

internal object GeelyAmapOverviewPrefs {
    private const val PREFS_NAME = "geely_amap_overview"
    private const val KEY_AUTO_ON_NAV = "auto_overview_on_nav_start"
    private const val KEY_AUTO_DELAY_MS = "auto_overview_delay_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GeelyAmapOverviewConfig {
        val sp = prefs(context)
        return GeelyAmapOverviewConfig(
            autoOverviewOnNavStart = sp.getBoolean(KEY_AUTO_ON_NAV, true),
            autoOverviewDelayMs = sp.getInt(KEY_AUTO_DELAY_MS, GeelyAmapOverviewConfig.DEFAULT_AUTO_DELAY_MS)
                .coerceIn(500, 10_000),
        )
    }

    fun save(context: Context, config: GeelyAmapOverviewConfig) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_ON_NAV, config.autoOverviewOnNavStart)
            .putInt(KEY_AUTO_DELAY_MS, config.autoOverviewDelayMs.coerceIn(500, 10_000))
            .apply()
    }
}

/**
 * Automatically switches center-screen AMap to "全览" (overview) when CarPlay navigation starts,
 * so AMap moves 3D lane-level navigation to the Geely instrument cluster by default.
 */
internal object GeelyAmapOverviewController {
    private const val TAG = "DiPlay-AmapOverview"
    private const val ROUTE_GUIDANCE_UPDATE = 0x5201
    private const val INJECT_POINTER_ID = 0
    private const val TAP_HOLD_MS = 60L
    private const val NAV_END_DEBOUNCE_MS = 1_500L

    private val ACTIVE_ROUTE_STATES = setOf(1, 3, 4, 5, 6)

    private class HostBinding(
        val touchSender: (List<AirPlayContact>) -> Boolean,
        val menuOpenProvider: () -> Boolean,
        val mainStreamActiveProvider: () -> Boolean,
        val clusterActiveProvider: () -> Boolean,
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

    private var navActive = false
    private var navStateCode: Int? = null
    private var navSourceName: String? = null
    private var pendingAutoRunnable: Runnable? = null
    private var pendingNavEndRunnable: Runnable? = null

    private val routeFrameObserver: (Iap2Frame) -> Unit = { frame ->
        if (frame.messageId == ROUTE_GUIDANCE_UPDATE) {
            onRouteGuidanceFrame(frame.payload)
        }
    }

    fun attachHost(
        context: Context,
        touchSender: (List<AirPlayContact>) -> Boolean,
        menuOpenProvider: () -> Boolean,
        mainStreamActiveProvider: () -> Boolean,
        clusterActiveProvider: () -> Boolean,
        log: (String) -> Unit,
    ) {
        appContext = context.applicationContext
        binding = HostBinding(
            touchSender = touchSender,
            menuOpenProvider = menuOpenProvider,
            mainStreamActiveProvider = mainStreamActiveProvider,
            clusterActiveProvider = clusterActiveProvider,
            log = log,
        )
        CarPlayGlance.routeFrameListener = routeFrameObserver
    }

    fun onHostResumed() {
        hostResumed = true
    }

    fun onHostPaused() {
        hostResumed = false
        cancelAutoNavRunnable()
    }

    fun detachHost() {
        hostResumed = false
        cancelAutoNavRunnable()
        cancelNavEndDebounce()
        if (CarPlayGlance.routeFrameListener === routeFrameObserver) {
            CarPlayGlance.routeFrameListener = null
        }
        synchronized(navLock) {
            navActive = false
            navStateCode = null
            navSourceName = null
        }
        binding = null
    }

    /** Backup hook from `GeelyClusterEnhancer.onTurnGuidanceUpdated` to clear state on disconnect. */
    fun onTurnGuidanceUpdated(hasGuidance: Boolean) {
        if (!hasGuidance) {
            var shouldClear = false
            synchronized(navLock) {
                if (navActive && navStateCode == null) {
                    navActive = false
                    navSourceName = null
                    shouldClear = true
                }
            }
            if (shouldClear) {
                mainHandler.post {
                    cancelAutoNavRunnable()
                    cancelNavEndDebounce()
                }
            }
        }
    }

    private fun onRouteGuidanceFrame(payload: ByteArray) {
        val parsed = parseRouteGuidanceTlv(payload) ?: return
        var startedNow = false
        var becameInactive = false
        var becameActiveAgain = false
        synchronized(navLock) {
            if (parsed.state != null) navStateCode = parsed.state
            if (parsed.source != null) navSourceName = parsed.source
            val nowActive = when {
                navSourceName == "" -> false
                navStateCode in ACTIVE_ROUTE_STATES -> true
                navStateCode != null -> false
                else -> !navSourceName.isNullOrBlank()
            }
            if (nowActive) {
                if (!navActive) {
                    navActive = true
                    startedNow = true
                } else {
                    becameActiveAgain = true
                }
            } else if (navActive) {
                becameInactive = true
            }
        }
        when {
            startedNow -> mainHandler.post {
                cancelNavEndDebounce()
                scheduleAutoOverviewOnNavStart()
            }
            becameActiveAgain -> mainHandler.post { cancelNavEndDebounce() }
            becameInactive -> mainHandler.post { scheduleNavEndDebounce() }
        }
    }

    private fun scheduleNavEndDebounce() {
        if (pendingNavEndRunnable != null) return
        val runnable = Runnable {
            pendingNavEndRunnable = null
            synchronized(navLock) {
                navActive = false
                navStateCode = null
                navSourceName = null
            }
            cancelAutoNavRunnable()
        }
        pendingNavEndRunnable = runnable
        mainHandler.postDelayed(runnable, NAV_END_DEBOUNCE_MS)
    }

    private fun cancelNavEndDebounce() {
        pendingNavEndRunnable?.let(mainHandler::removeCallbacks)
        pendingNavEndRunnable = null
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
            injectOverviewTap()
        }
        pendingAutoRunnable = runnable
        log("AmapOverview: navigation started; scheduling auto-overview in ${cfg.autoOverviewDelayMs} ms")
        mainHandler.postDelayed(runnable, cfg.autoOverviewDelayMs.toLong())
    }

    private fun injectOverviewTap() {
        val host = binding ?: return
        val ctx = appContext ?: return
        if (!hostResumed || host.menuOpenProvider()) {
            log("AmapOverview: skip (nav-start) hostResumed=$hostResumed menuOpen=${host.menuOpenProvider()}")
            return
        }
        if (!host.mainStreamActiveProvider() || !host.clusterActiveProvider()) {
            log("AmapOverview: skip (nav-start) stream inactive")
            return
        }
        if (isPhoneCallActive(ctx)) {
            log("AmapOverview: skip (nav-start) phone call active")
            return
        }
        val x = GeelyAmapOverviewConfig.OVERVIEW_BUTTON_X.toDouble()
        val y = GeelyAmapOverviewConfig.OVERVIEW_BUTTON_Y.toDouble()
        val sentDown = host.touchSender(listOf(AirPlayContact(id = INJECT_POINTER_ID, x = x, y = y, down = true)))
        log("AmapOverview: inject tap [nav-start] (90%,10%) down=$sentDown")
        if (!sentDown) return
        mainHandler.postDelayed({
            host.touchSender(emptyList())
        }, TAP_HOLD_MS)
    }

    private fun cancelAutoNavRunnable() {
        pendingAutoRunnable?.let(mainHandler::removeCallbacks)
        pendingAutoRunnable = null
    }

    private fun isPhoneCallActive(context: Context): Boolean {
        if (BydNavigationOutputs.carPlayCall() != null) return true
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_RINGTONE
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        binding?.log?.invoke(message)
    }

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
