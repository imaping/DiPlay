package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal data class GeelyTimedFloat(
    val value: Float = Float.NaN,
    val updatedAtMs: Long = 0L,
) {
    fun valueIfFresh(nowElapsedMs: Long, maxAgeMs: Long = FRESH_TIMEOUT_MS): Float? {
        if (updatedAtMs <= 0L || !value.isFinite()) return null
        if (nowElapsedMs - updatedAtMs > maxAgeMs) return null
        return value
    }

    companion object {
        const val FRESH_TIMEOUT_MS = 2000L
    }
}

internal data class GeelyClusterSensorSnapshot(
    val speedKmh: GeelyTimedFloat = GeelyTimedFloat(),
    val rpm: GeelyTimedFloat = GeelyTimedFloat(),
    val avgFuelLPer100Km: GeelyTimedFloat = GeelyTimedFloat(),
    val fuelPercent: GeelyTimedFloat = GeelyTimedFloat(),
    val coolantTempC: GeelyTimedFloat = GeelyTimedFloat(),
    val gearLabel: String? = null,
    val gearUpdatedAtMs: Long = 0L,
) {
    fun gearIfFresh(nowElapsedMs: Long, maxAgeMs: Long = 4000L): String? {
        if (gearLabel == null || gearUpdatedAtMs <= 0L) return null
        if (nowElapsedMs - gearUpdatedAtMs > maxAgeMs) return null
        return gearLabel
    }
}

/**
 * Reflection reader for ECARX AdaptAPI `ISensor` 6 vehicle channels:
 * - Speed (`0x00101A00`, m/s -> km/h)
 * - RPM (`0x00100900`)
 * - Average fuel consumption (`0x00400100`, L/100km)
 * - Fuel level percentage (`0x00404500`, %)
 * - Coolant temperature (`0x00100F00`, °C)
 * - Transmission gear (`0x00200200`, `P`/`R`/`N`/`D`/`M1..M10`)
 */
internal object GeelyEcarxSensorReader {
    private const val SENSOR_SPEED = 0x00101A00
    private const val SENSOR_RPM = 0x00100900
    private const val SENSOR_AVG_FUEL = 0x00400100
    private const val SENSOR_FUEL_PERCENT = 0x00404500
    private const val SENSOR_COOLANT_TEMP = 0x00100F00
    private const val SENSOR_GEAR = 0x00200200
    private const val GEAR_PARK = 2097712
    private const val GEAR_REVERSE = 2097728
    private const val GEAR_NEUTRAL = 2097680
    private const val GEAR_DRIVE = 2097696
    private val GEAR_MANUAL_RANGE = 2097665..2097674

    private val lock = Any()
    private var car: Any? = null
    private var sensorManager: Any? = null
    private var sensorValueMethod: java.lang.reflect.Method? = null
    private var sensorEventMethod: java.lang.reflect.Method? = null
    private var sensorSupportedMethod: java.lang.reflect.Method? = null
    @Volatile
    private var discoveredCoolantSensorId: Int = SENSOR_COOLANT_TEMP
    @Volatile
    private var discoveredCoolantUsesEvent: Boolean = false
    @Volatile
    private var sensorProbeLogged: Boolean = false
    @Volatile
    private var logger: (String) -> Unit = {}
    @Volatile
    private var connected = false
    private var latestSnapshot = GeelyClusterSensorSnapshot()

    fun setLogger(log: (String) -> Unit) {
        logger = log
    }

    val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "geely-cluster-sensors").apply { isDaemon = true }
        }
    }

    fun poll(context: Context): GeelyClusterSensorSnapshot = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        val sensor = ensureSensor(context.applicationContext) ?: return latestSnapshot
        val method = sensorValueMethod ?: return latestSnapshot

        val speedRaw = invokeSensor(sensor, method, SENSOR_SPEED)
        val speedKmh = if (speedRaw != null && speedRaw.isFinite()) {
            val kmh = abs(speedRaw) * 3.6f
            val normalized = if (kmh <= 1.0f) 0f else kmh
            if (normalized in 0f..360f) GeelyTimedFloat(normalized, now) else latestSnapshot.speedKmh
        } else {
            latestSnapshot.speedKmh
        }

        val rpmRaw = invokeSensor(sensor, method, SENSOR_RPM)
        val rpm = if (rpmRaw != null && rpmRaw.isFinite() && rpmRaw in 0f..20000f) {
            GeelyTimedFloat(rpmRaw, now)
        } else {
            latestSnapshot.rpm
        }

        val avgFuelRaw = invokeSensor(sensor, method, SENSOR_AVG_FUEL)
        val avgFuel = if (avgFuelRaw != null && avgFuelRaw.isFinite() && avgFuelRaw in 0f..99.9f) {
            GeelyTimedFloat(avgFuelRaw, now)
        } else {
            latestSnapshot.avgFuelLPer100Km
        }

        val fuelPctRaw = invokeSensor(sensor, method, SENSOR_FUEL_PERCENT)
        val fuelPct = if (fuelPctRaw != null && fuelPctRaw.isFinite() && fuelPctRaw in 0f..100f) {
            GeelyTimedFloat(fuelPctRaw, now)
        } else {
            latestSnapshot.fuelPercent
        }

        val coolantRaw = if (discoveredCoolantUsesEvent) {
            sensorEventMethod?.let { eventMethod ->
                runCatching { (eventMethod.invoke(sensor, discoveredCoolantSensorId) as? Number)?.toFloat() }.getOrNull()
            }
        } else {
            invokeSensor(sensor, method, discoveredCoolantSensorId)
        }
        val coolant = if (coolantRaw != null && coolantRaw.isFinite() && coolantRaw in -40f..200f && coolantRaw != 0f) {
            GeelyTimedFloat(coolantRaw, now)
        } else {
            latestSnapshot.coolantTempC
        }

        val gearRaw = sensorEventMethod?.let { eventMethod ->
            runCatching { (eventMethod.invoke(sensor, SENSOR_GEAR) as? Number)?.toInt() }.getOrNull()
        }
        val parsedGear = gearRaw?.let(::decodeGear)
        val gearLabel = parsedGear ?: latestSnapshot.gearLabel
        val gearUpdatedAt = if (parsedGear != null) now else latestSnapshot.gearUpdatedAtMs

        latestSnapshot = GeelyClusterSensorSnapshot(
            speedKmh = speedKmh,
            rpm = rpm,
            avgFuelLPer100Km = avgFuel,
            fuelPercent = fuelPct,
            coolantTempC = coolant,
            gearLabel = gearLabel,
            gearUpdatedAtMs = gearUpdatedAt,
        )
        return latestSnapshot
    }

    private fun decodeGear(event: Int): String? = when (event) {
        GEAR_PARK -> "P"
        GEAR_REVERSE -> "R"
        GEAR_NEUTRAL -> "N"
        GEAR_DRIVE -> "D"
        in GEAR_MANUAL_RANGE -> "M${event - 2097664}"
        else -> null
    }

    private fun invokeSensor(sensor: Any, method: java.lang.reflect.Method, type: Int): Float? =
        runCatching {
            (method.invoke(sensor, type) as? Number)?.toFloat()
        }.onFailure {
            sensorManager = null
        }.getOrNull()

    private fun ensureSensor(appContext: Context): Any? {
        sensorManager?.let { return it }
        return runCatching {
            val carInstance = car ?: Class.forName("com.ecarx.xui.adaptapi.car.Car")
                .getMethod("create", Context::class.java)
                .invoke(null, appContext)
                ?.also { created ->
                    car = created
                    attachConnectableIfPresent(created)
                } ?: return null

            val sensor = carInstance.javaClass.getMethod("getSensorManager").invoke(carInstance) ?: return null
            val getValue = sensor.javaClass.getMethod("getSensorLatestValue", Int::class.javaPrimitiveType)
            val getEvent = runCatching {
                sensor.javaClass.getMethod("getSensorEvent", Int::class.javaPrimitiveType)
            }.getOrNull()
            val isSupported = runCatching {
                sensor.javaClass.getMethod("isSensorSupported", Int::class.javaPrimitiveType)
            }.getOrNull()
            sensorManager = sensor
            sensorValueMethod = getValue
            sensorEventMethod = getEvent
            sensorSupportedMethod = isSupported
            connected = true
            probeCoolantSensorsOnce(sensor, getValue, getEvent, isSupported)
            sensor
        }.getOrNull()
    }

    private fun probeCoolantSensorsOnce(
        sensor: Any,
        getValue: java.lang.reflect.Method,
        getEvent: java.lang.reflect.Method?,
        isSupported: java.lang.reflect.Method?,
    ) {
        if (sensorProbeLogged) return
        sensorProbeLogged = true
        runCatching {
            val iSensorClass = Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensor")
            val summary = StringBuilder()
            for (field in iSensorClass.fields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                val name = field.name
                if (!name.startsWith("SENSOR_TYPE_")) continue
                val upper = name.uppercase()
                if ("COOLANT" !in upper && "WATER" !in upper && "ENGINE" !in upper && "TEMP" !in upper && "ENDURANCE" !in upper) {
                    continue
                }
                val id = runCatching { field.getInt(null) }.getOrNull() ?: continue
                val status = runCatching { isSupported?.invoke(sensor, id)?.toString() }.getOrNull()
                if (status != null && (status.equals("notavailable", true) || status.equals("error", true))) {
                    continue
                }
                val fVal = runCatching { (getValue.invoke(sensor, id) as? Number)?.toFloat() }.getOrNull()
                val eVal = runCatching { (getEvent?.invoke(sensor, id) as? Number)?.toInt() }.getOrNull()
                if (summary.isNotEmpty()) summary.append(", ")
                summary.append("$name(0x${id.toString(16)})[st=$status val=$fVal ev=$eVal]")
                if (("COOLANT" in upper || "WATER" in upper) && "LEVEL" !in upper) {
                    if (fVal != null && fVal.isFinite() && fVal in -40f..200f && fVal != 0f) {
                        discoveredCoolantSensorId = id
                        discoveredCoolantUsesEvent = false
                    } else if (eVal != null && eVal in -40..200 && eVal != 0) {
                        discoveredCoolantSensorId = id
                        discoveredCoolantUsesEvent = true
                    }
                }
            }
            logger("GeelySensors: probe selectedCoolant=0x${discoveredCoolantSensorId.toString(16)} found=[$summary]")
        }
    }

    private fun attachConnectableIfPresent(carInstance: Any) {
        runCatching {
            val connectableClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
            if (!connectableClass.isInstance(carInstance)) return
            val watcherClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val proxy = Proxy.newProxyInstance(
                watcherClass.classLoader,
                arrayOf(watcherClass),
            ) { proxyInstance, method, args ->
                when (method.name) {
                    "onConnected" -> synchronized(lock) {
                        connected = true
                        sensorManager = null
                    }
                    "onDisConnected" -> synchronized(lock) {
                        connected = false
                        sensorManager = null
                    }
                    "equals" -> proxyInstance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "toString" -> "GeelyEcarxSensorConnectWatcher"
                    else -> null
                }
            }
            connectableClass.getMethod("registerConnectWatcher", watcherClass).invoke(carInstance, proxy)
            connectableClass.getMethod("connect").invoke(carInstance)
        }
    }
}

/**
 * Controls Geely OneOS / ECARX `DimMenuInteraction.switchNaviMode(mode)`:
 * - `mode = 3`: Enter full-screen cluster map projection mode
 * - `mode = 1`: Exit back to classic instrument cluster mode
 */
internal class GeelyDimMenuController(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dimMenuInteraction: Any? = null
    private var switchNaviModeMethod: java.lang.reflect.Method? = null
    private var getNaviModeMethod: java.lang.reflect.Method? = null

    @Volatile
    var lastActiveSwitchElapsedMs: Long = 0L
        private set

    fun enterFullScreenMap(): Boolean = switchNaviMode(MODE_FULLSCREEN_MAP)

    fun restoreClassicCluster(): Boolean = switchNaviMode(MODE_CLASSIC_CLUSTER)

    fun getNaviMode(): Int? = runCatching {
        val target = ensureDimMenuInteraction() ?: return null
        val method = getNaviModeMethod ?: return null
        (method.invoke(target) as? Number)?.toInt()
    }.getOrNull()

    /**
     * Robustly re-enters full-screen map mode (`Mode 3`), including after a phone call or
     * external cluster interruption where a stale `DimMenuInteraction` instance or cached
     * mode state causes a single `switchNaviMode(3)` call to be ignored.
     */
    fun forceReenterFullScreenMap(reason: String = "manual"): Boolean {
        val before = getNaviMode()
        log("GeelyDimMenu: forceReenter($reason) beforeMode=$before")
        val directOk = switchNaviMode(MODE_FULLSCREEN_MAP)
        val afterDirect = getNaviMode()
        if (directOk && (afterDirect == null || afterDirect == MODE_FULLSCREEN_MAP)) {
            return true
        }
        // Reset cached binder/proxy and pulse 1 -> 3 so the DIM MCU releases any stale phone/menu tab
        resetCachedHandle()
        switchNaviMode(MODE_CLASSIC_CLUSTER)
        mainHandler.postDelayed({
            val ok1 = switchNaviMode(MODE_FULLSCREEN_MAP)
            val modeAfter = getNaviMode()
            log("GeelyDimMenu: pulse($reason) -> ok=$ok1 afterMode=$modeAfter")
            if (!ok1 || (modeAfter != null && modeAfter != MODE_FULLSCREEN_MAP)) {
                mainHandler.postDelayed({
                    resetCachedHandle()
                    val ok2 = switchNaviMode(MODE_FULLSCREEN_MAP)
                    log("GeelyDimMenu: retry($reason) -> ok=$ok2 afterMode=${getNaviMode()}")
                }, 1000L)
            }
        }, 250L)
        return true
    }

    fun resetCachedHandle() {
        dimMenuInteraction = null
        switchNaviModeMethod = null
        getNaviModeMethod = null
    }

    fun switchNaviMode(mode: Int): Boolean {
        lastActiveSwitchElapsedMs = SystemClock.elapsedRealtime()
        return runCatching {
            val target = ensureDimMenuInteraction() ?: return false
            val method = switchNaviModeMethod ?: return false
            val result = method.invoke(target, mode)
            val ok = (result as? Boolean) ?: true
            log("GeelyDimMenu: switchNaviMode($mode) -> $ok (readback=${getNaviMode()})")
            ok
        }.onFailure { e ->
            resetCachedHandle()
            log("GeelyDimMenu: switchNaviMode($mode) failed: ${e.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    private fun ensureDimMenuInteraction(): Any? {
        dimMenuInteraction?.let { return it }
        // 1. Prefer direct DimMenuInteraction(Context) constructor used on Geely OneOS / KX11
        runCatching {
            val menuClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction")
            val menu = menuClass.getConstructor(Context::class.java).newInstance(appContext)
            val method = menuClass.getMethod("switchNaviMode", Int::class.javaPrimitiveType)
            val getMethod = runCatching { menuClass.getMethod("getNaviMode") }.getOrNull()
            dimMenuInteraction = menu
            switchNaviModeMethod = method
            getNaviModeMethod = getMethod
            return menu
        }
        // 2. Fallback to DimInteraction.create(Context).getDimMenuInteraction()
        return runCatching {
            val dimClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
            val dimInstance = dimClass.getMethod("create", Context::class.java).invoke(null, appContext)
                ?: return null
            val menu = dimInstance.javaClass.getMethod("getDimMenuInteraction").invoke(dimInstance)
                ?: return null
            val method = menu.javaClass.getMethod("switchNaviMode", Int::class.javaPrimitiveType)
            val getMethod = runCatching { menu.javaClass.getMethod("getNaviMode") }.getOrNull()
            dimMenuInteraction = menu
            switchNaviModeMethod = method
            getNaviModeMethod = getMethod
            menu
        }.getOrNull()
    }

    companion object {
        const val MODE_CLASSIC_CLUSTER = 1
        const val MODE_FULLSCREEN_MAP = 3
    }
}

/**
 * Watches `logcat -T 1 -v raw DimMenuInteraction:D *:S` for `notifySwitchNaviMode` events
 * triggered when the driver exits the full-screen map via steering-wheel keys.
 */
internal class GeelyDimLogcatWatcher(
    private val dimController: GeelyDimMenuController,
    private val onExternalSwitchDetected: () -> Unit,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var running = false
    private var process: Process? = null
    private var readerThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        readerThread = Thread({
            while (running) {
                try {
                    val proc = ProcessBuilder("logcat", "-T", "1", "-v", "raw", "DimMenuInteraction:D", "*:S")
                        .redirectErrorStream(true)
                        .start()
                    process = proc
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        while (running) {
                            val line = reader.readLine() ?: break
                            if ("switchNaviMode naviMode:1" in line || "notifySwitchNaviMode" in line) {
                                val elapsedSinceActive =
                                    SystemClock.elapsedRealtime() - dimController.lastActiveSwitchElapsedMs
                                if (elapsedSinceActive > COOLDOWN_After_ACTIVE_SWITCH_MS) {
                                    mainHandler.post {
                                        if (running) onExternalSwitchDetected()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Logcat process ended or interrupted
                }
                if (running) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "geely-dim-logcat-watcher").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running = false
        readerThread?.interrupt()
        readerThread = null
        runCatching { process?.destroy() }
        process = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val COOLDOWN_After_ACTIVE_SWITCH_MS = 1500L
    }
}
