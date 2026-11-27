package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects whether a native head-unit map (e.g. `com.geely.map`, AutoNavi, Baidu Map) is actively
 * projecting a `TYPE_PRESENTATION` (`Sys2037`) surface onto the Geely / ECARX instrument cluster
 * display when CarPlay is not connected.
 *
 * Verified on Geely KX11 (`kx11_high`, Android 9):
 * - No projection: the cluster's `Display Root` in `dumpsys SurfaceFlinger --list` only contains
 *   DiPlay's overlay layers (`DiPlayGeelyClusterTheme`, `DiPlayGeelyClusterSelfDraw`).
 * - Native map projected: `Sys2037:com.geely.map/com.geely.map.activity.MainActivity` (and its
 *   `SurfaceView`) appears inside the same `Display Root` directly beneath `DiPlayGeelyClusterTheme`,
 *   and disappears immediately when projection is cancelled.
 *
 * Uses silent local ADB (`mayAsk = false`) with 30 s backoff when unavailable so it never shows an
 * authorization prompt while driving and degrades cleanly to `false` on head units without ADB.
 */
internal object GeelyNativeClusterMapObserver {
    private const val TAG = "GeelyNativeMap"
    private const val POLL_INTERVAL_MS = 2_000L
    private const val ADB_RETRY_BACKOFF_MS = 30_000L
    private const val ADB_SHELL_TIMEOUT_MS = 2_500
    private const val SF_LIST_COMMAND = "dumpsys SurfaceFlinger --list"

    private val OWN_OR_SYSTEM_LAYER_TOKENS = listOf(
        "diplaygeelycluster",
        "com.shihab.diplay",
        "com.shilapi.xcertplay",
        "com.geely.dimmenu",
        "com.android.systemui",
        "ecarx_daemon_overlaydisplay",
    )

    private val KNOWN_MAP_PACKAGE_TOKENS = listOf(
        "com.geely.map",
        "com.autonavi.",
        "com.baidu.baidumap",
        "com.baidu.navi",
        "com.tencent.map",
        "com.tencent.wecarnavi",
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<Any, (Boolean) -> Unit>()
    private val pollInFlight = AtomicBoolean(false)

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "geely-native-map-observer").apply { isDaemon = true }
        }
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logger: (String) -> Unit = {}
    @Volatile
    private var projected: Boolean = false

    private var pollFuture: ScheduledFuture<*>? = null
    private var localAdb: LocalAdb? = null
    private var retryAfterElapsedMs: Long = 0L
    private var lastUnavailableAccess: LocalAdb.Access? = null

    fun isNativeMapProjected(): Boolean = projected

    fun observe(
        context: Context,
        log: (String) -> Unit = NOOP_LOG,
        listener: (Boolean) -> Unit,
    ): () -> Unit {
        val app = context.applicationContext
        if (!GeelyVehicleDetector.isGeelyOrEcarx(app)) {
            return {}
        }
        val token = Any()
        val initial = synchronized(lock) {
            appContext = app
            if (log !== NOOP_LOG) logger = log
            observers[token] = listener
            ensurePollingLocked()
            projected
        }
        runOnMain { listener(initial) }
        return {
            synchronized(lock) {
                observers.remove(token)
                if (observers.isEmpty()) {
                    stopPollingLocked()
                }
            }
        }
    }

    /** Triggers an immediate off-thread probe if polling is active. */
    fun refreshNow() {
        val app = appContext ?: return
        if (synchronized(lock) { observers.isEmpty() }) return
        executor.execute { pollOnce(app) }
    }

    private fun ensurePollingLocked() {
        if (pollFuture != null) return
        pollFuture = executor.scheduleWithFixedDelay(
            {
                val app = appContext ?: return@scheduleWithFixedDelay
                pollOnce(app)
            },
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopPollingLocked() {
        pollFuture?.cancel(false)
        pollFuture = null
        projected = false
        retryAfterElapsedMs = 0L
        lastUnavailableAccess = null
        executor.execute {
            runCatching { localAdb?.close() }
            localAdb = null
        }
    }

    private fun pollOnce(app: Context) {
        if (!pollInFlight.compareAndSet(false, true)) return
        try {
            val output = querySurfaceFlingerList(app) ?: return
            val detected = parseClusterMapProjected(output)
            publish(detected)
        } finally {
            pollInFlight.set(false)
        }
    }

    private fun querySurfaceFlingerList(app: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAfterElapsedMs) return null
        val client = localAdb ?: runCatching {
            LocalAdb(AdbKeys.load(app))
        }.getOrNull()?.also { localAdb = it } ?: return null

        val access = runCatching { client.connect(mayAsk = false) }
            .getOrDefault(LocalAdb.Access.UNREACHABLE)
        if (access != LocalAdb.Access.READY) {
            retryAfterElapsedMs = now + ADB_RETRY_BACKOFF_MS
            if (lastUnavailableAccess != access) {
                lastUnavailableAccess = access
                emitLog("GeelyNativeMap: local ADB $access; native map projection auto-detection paused")
            }
            publish(false)
            return null
        }

        lastUnavailableAccess = null
        val result = runCatching { client.shell(SF_LIST_COMMAND, ADB_SHELL_TIMEOUT_MS) }.getOrNull()
        if (result == null) {
            // Transient socket drop; retry on the next tick without flipping current state blindly.
            runCatching { client.close() }
            localAdb = null
        }
        return result
    }

    private fun publish(next: Boolean) {
        val callbacks: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (projected == next) return
            projected = next
            callbacks = observers.values.toList()
        }
        emitLog("GeelyNativeMap: native cluster map projection -> ${if (next) "PRESENT" else "ABSENT"}")
        if (callbacks.isNotEmpty()) {
            runOnMain {
                callbacks.forEach { cb -> runCatching { cb(next) } }
            }
        }
    }

    /**
     * Parses `dumpsys SurfaceFlinger --list` output to decide whether a third-party presentation
     * or map surface is rendered on the same display root as DiPlay's cluster theme overlay.
     */
    internal fun parseClusterMapProjected(surfaceFlingerList: String): Boolean {
        if (surfaceFlingerList.isBlank()) return false

        val sections = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (rawLine in surfaceFlingerList.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("Display Root#")) {
                if (current.isNotEmpty()) {
                    sections.add(current)
                    current = mutableListOf()
                }
            }
            current.add(line)
        }
        if (current.isNotEmpty()) {
            sections.add(current)
        }

        val clusterSection = sections.firstOrNull { lines ->
            lines.any { l ->
                l.contains("DiPlayGeelyClusterTheme", ignoreCase = true) ||
                    l.contains("DiPlayGeelyClusterSelfDraw", ignoreCase = true)
            }
        }

        if (clusterSection != null) {
            return clusterSection.any(::isNativeClusterMapLayer)
        }

        // Fallback if theme window hasn't shown up in SF list yet: check for any third-party
        // TYPE_PRESENTATION (Sys2037) layer anywhere in the list.
        return surfaceFlingerList.lineSequence()
            .map { it.trim() }
            .any(::isThirdPartyPresentationLayer)
    }

    private fun isNativeClusterMapLayer(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (OWN_OR_SYSTEM_LAYER_TOKENS.any { it in lower }) return false
        if (lower.startsWith("display root#") ||
            lower.startsWith("display overlays#") ||
            lower.startsWith("com.android.server.wm.")
        ) {
            return false
        }
        if ("sys2037:" in lower) return true
        if (KNOWN_MAP_PACKAGE_TOKENS.any { it in lower } &&
            ("surfaceview" in lower || "presentation" in lower)
        ) {
            return true
        }
        return false
    }

    private fun isThirdPartyPresentationLayer(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if ("sys2037:" !in lower) return false
        return OWN_OR_SYSTEM_LAYER_TOKENS.none { it in lower }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun emitLog(msg: String) {
        Log.i(TAG, msg)
        runCatching { logger(msg) }
    }

    private val NOOP_LOG: (String) -> Unit = {}
}
