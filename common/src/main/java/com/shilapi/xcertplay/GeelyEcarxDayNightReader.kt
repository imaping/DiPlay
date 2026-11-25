package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the vehicle's authoritative day/night state on Geely / ECARX (KX11 / OneOS) head units
 * via reflection, so DiPlay's instrument cluster themes and CarPlay day/night mode stay strictly
 * synchronized with the factory QNX cluster bottom bar and headlamp/theme state.
 *
 * Probing order (all reflection-based, zero compile-time vendor dependency):
 * 1. `com.ecarx.xui.adaptapi.device.Device.create(ctx).getDayNightMode()` (`IDayNightMode`)
 *    - Reflects the combined headlamp + head-unit theme state (`DAYMODE_DAY` / `DAYMODE_NIGHT`)
 *    - Registers `IDayNightModeCallBack` for immediate push updates
 * 2. `com.ecarx.xui.adaptapi.car.Car.create(ctx).getSensorManager()` (`ISensor`)
 *    - Reads `ISensor.SENSOR_TYPE_DAY_NIGHT` via `getSensorEvent(int)` (same proven channel as gear)
 *
 * Returns `null` on non-Geely devices or when no vendor signal is available, allowing callers
 * to fall back seamlessly to standard Android `Configuration.uiMode`.
 */
internal object GeelyEcarxDayNightReader {
    private const val TAG = "GeelyDayNight"
    private const val POLL_INTERVAL_MS = 1500L

    private const val DEFAULT_DAYMODE_DAY = 1
    private const val DEFAULT_DAYMODE_NIGHT = 2
    private const val DEFAULT_SENSOR_TYPE_DAY_NIGHT = 0x00200100
    private const val DEFAULT_SENSOR_EVENT_DAY = 2097409 // 0x00200101
    private const val DEFAULT_SENSOR_EVENT_NIGHT = 2097410 // 0x00200102

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<Any, (Boolean) -> Unit>()
    private val pollInFlight = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var logger: (String) -> Unit = {}
    @Volatile
    private var cachedNight: Boolean? = null
    @Volatile
    private var activeSource: String? = null
    @Volatile
    private var probeLogged = false

    private var pollFuture: ScheduledFuture<*>? = null

    // Channel 1: ECARX IDayNightMode
    private var deviceInstance: Any? = null
    private var dayNightModeObj: Any? = null
    private var getDayNightMethod: java.lang.reflect.Method? = null
    private var dayModeDayValue: Int = DEFAULT_DAYMODE_DAY
    private var dayModeNightValue: Int = DEFAULT_DAYMODE_NIGHT
    private var dayNightCallbackRegistered = false
    private var dayNightChannelFailed = false

    // Channel 2: ECARX ISensor SENSOR_TYPE_DAY_NIGHT
    private var carInstance: Any? = null
    private var sensorManagerObj: Any? = null
    private var getSensorEventMethod: java.lang.reflect.Method? = null
    private var sensorTypeDayNight: Int = DEFAULT_SENSOR_TYPE_DAY_NIGHT
    private var sensorEventDayValue: Int = DEFAULT_SENSOR_EVENT_DAY
    private var sensorEventNightValue: Int = DEFAULT_SENSOR_EVENT_NIGHT
    private var sensorChannelFailed = false

    fun latestNight(): Boolean? = cachedNight

    fun currentSource(): String? = activeSource

    /** Prefers the vehicle's authoritative day/night signal when available; falls back to Android uiMode. */
    fun resolveSystemNight(fallbackSystemNight: Boolean): Boolean = cachedNight ?: fallbackSystemNight

    fun observe(
        context: Context,
        log: (String) -> Unit = NOOP_LOG,
        onChanged: (Boolean) -> Unit,
    ): () -> Unit {
        val app = context.applicationContext
        if (!GeelyVehicleDetector.isGeelyOrEcarx(app)) {
            return {}
        }
        val token = Any()
        val existing = synchronized(lock) {
            appContext = app
            if (log !== NOOP_LOG) logger = log
            observers[token] = onChanged
            ensurePollingLocked()
            cachedNight
        }
        if (existing != null) {
            mainHandler.post { onChanged(existing) }
        }
        return {
            synchronized(lock) {
                observers.remove(token)
                if (observers.isEmpty()) {
                    stopPollingLocked()
                }
            }
        }
    }

    private fun ensurePollingLocked() {
        if (pollFuture != null) return
        pollFuture = GeelyEcarxSensorReader.executor.scheduleWithFixedDelay(
            { pollOnce() },
            0L,
            POLL_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopPollingLocked() {
        pollFuture?.cancel(false)
        pollFuture = null
    }

    private fun pollOnce() {
        val ctx = appContext ?: return
        if (!pollInFlight.compareAndSet(false, true)) return
        try {
            val fromDevice = readFromDeviceDayNightMode(ctx)
            if (fromDevice != null) {
                publish(fromDevice.first, "IDayNightMode", fromDevice.second)
                return
            }
            val fromSensor = readFromCarSensor(ctx)
            if (fromSensor != null) {
                publish(fromSensor.first, "ISensor(0x${sensorTypeDayNight.toString(16)})", fromSensor.second)
                return
            }
            if (!probeLogged) {
                probeLogged = true
                emitLog("GeelyDayNight: no ECARX day/night channel available; using Android uiMode fallback")
            }
        } finally {
            pollInFlight.set(false)
        }
    }

    private fun readFromDeviceDayNightMode(ctx: Context): Pair<Boolean, Int>? {
        if (dayNightChannelFailed) return null
        return runCatching {
            val obj = ensureDayNightMode(ctx) ?: return null
            val getter = getDayNightMethod ?: return null
            val raw = (getter.invoke(obj) as? Number)?.toInt() ?: return null
            decodeDayNightModeValue(raw)?.let { it to raw }
        }.onFailure { e ->
            dayNightModeObj = null
            getDayNightMethod = null
            dayNightCallbackRegistered = false
            emitLog("GeelyDayNight: IDayNightMode read failed (${e.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun ensureDayNightMode(ctx: Context): Any? {
        dayNightModeObj?.let { return it }
        return runCatching {
            val deviceClass = Class.forName("com.ecarx.xui.adaptapi.device.Device")
            val device = deviceInstance ?: deviceClass.getMethod("create", Context::class.java)
                .invoke(null, ctx)
                ?.also { deviceInstance = it }
                ?: run {
                    dayNightChannelFailed = true
                    return null
                }
            val modeObj = device.javaClass.getMethod("getDayNightMode").invoke(device) ?: return null
            val modeInterface = runCatching {
                Class.forName("com.ecarx.xui.adaptapi.device.daynightmode.IDayNightMode")
            }.getOrNull() ?: modeObj.javaClass

            dayModeDayValue = readStaticInt(modeInterface, "DAYMODE_DAY")
                ?: readStaticInt(modeInterface, "DAY_MODE")
                ?: DEFAULT_DAYMODE_DAY
            dayModeNightValue = readStaticInt(modeInterface, "DAYMODE_NIGHT")
                ?: readStaticInt(modeInterface, "NIGHT_MODE")
                ?: DEFAULT_DAYMODE_NIGHT

            val getter = runCatching { modeObj.javaClass.getMethod("getDayNight") }.getOrNull()
                ?: runCatching { modeObj.javaClass.getMethod("getDayNightMode") }.getOrNull()
                ?: return null

            dayNightModeObj = modeObj
            getDayNightMethod = getter
            registerDayNightCallback(modeObj)
            modeObj
        }.onFailure {
            dayNightChannelFailed = true
        }.getOrNull()
    }

    private fun registerDayNightCallback(modeObj: Any) {
        if (dayNightCallbackRegistered) return
        runCatching {
            val cbClass = Class.forName("com.ecarx.xui.adaptapi.device.daynightmode.IDayNightMode\$IDayNightModeCallBack")
            val registerMethod = modeObj.javaClass.methods.firstOrNull { m ->
                (m.name == "registerDayNightModeCallBack" || m.name == "registerDayNightModeCallback") &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0].isAssignableFrom(cbClass)
            } ?: return

            val proxy = Proxy.newProxyInstance(
                cbClass.classLoader,
                arrayOf(cbClass),
            ) { proxyInstance, method, args ->
                when (method.name) {
                    "onDayNightChanged", "onDayNightModeChange" -> {
                        val raw = (args?.firstOrNull() as? Number)?.toInt()
                        if (raw != null) {
                            decodeDayNightModeValue(raw)?.let { night ->
                                publish(night, "IDayNightMode-cb", raw)
                            }
                        }
                        null
                    }
                    "equals" -> proxyInstance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "toString" -> "GeelyEcarxDayNightCallback"
                    else -> null
                }
            }
            registerMethod.invoke(modeObj, proxy)
            dayNightCallbackRegistered = true
        }
    }

    private fun decodeDayNightModeValue(raw: Int): Boolean? = when (raw) {
        dayModeDayValue -> false
        dayModeNightValue -> true
        else -> null
    }

    private fun readFromCarSensor(ctx: Context): Pair<Boolean, Int>? {
        if (sensorChannelFailed) return null
        return runCatching {
            val sensor = ensureSensorManager(ctx) ?: return null
            val getter = getSensorEventMethod ?: return null
            val raw = (getter.invoke(sensor, sensorTypeDayNight) as? Number)?.toInt() ?: return null
            decodeSensorDayNightEvent(raw)?.let { it to raw }
        }.onFailure { e ->
            sensorManagerObj = null
            getSensorEventMethod = null
            emitLog("GeelyDayNight: ISensor day/night read failed (${e.javaClass.simpleName})")
        }.getOrNull()
    }

    private fun ensureSensorManager(ctx: Context): Any? {
        sensorManagerObj?.let { return it }
        return runCatching {
            val carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val car = carInstance ?: carClass.getMethod("create", Context::class.java)
                .invoke(null, ctx)
                ?.also { carInstance = it }
                ?: run {
                    sensorChannelFailed = true
                    return null
                }
            val sensor = car.javaClass.getMethod("getSensorManager").invoke(car) ?: return null
            val getEvent = sensor.javaClass.getMethod("getSensorEvent", Int::class.javaPrimitiveType)

            val iSensorClass = runCatching {
                Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensor")
            }.getOrNull()
            val iSensorEventClass = runCatching {
                Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensorEvent")
            }.getOrNull()

            if (iSensorClass != null) {
                sensorTypeDayNight = readStaticInt(iSensorClass, "SENSOR_TYPE_DAY_NIGHT")
                    ?: readStaticInt(iSensorClass, "SENSOR_TYPE_DAY_NIGHT_MODE")
                    ?: DEFAULT_SENSOR_TYPE_DAY_NIGHT
            }
            if (iSensorEventClass != null) {
                sensorEventDayValue = readStaticInt(iSensorEventClass, "DAY_NIGHT_MODE_DAY")
                    ?: readStaticInt(iSensorEventClass, "DAY_NIGHT_DAY")
                    ?: DEFAULT_SENSOR_EVENT_DAY
                sensorEventNightValue = readStaticInt(iSensorEventClass, "DAY_NIGHT_MODE_NIGHT")
                    ?: readStaticInt(iSensorEventClass, "DAY_NIGHT_NIGHT")
                    ?: DEFAULT_SENSOR_EVENT_NIGHT
            }

            sensorManagerObj = sensor
            getSensorEventMethod = getEvent
            sensor
        }.onFailure {
            sensorChannelFailed = true
        }.getOrNull()
    }

    private fun decodeSensorDayNightEvent(raw: Int): Boolean? = when (raw) {
        sensorEventDayValue -> false
        sensorEventNightValue -> true
        else -> null
    }

    private fun readStaticInt(clazz: Class<*>, fieldName: String): Int? =
        runCatching { clazz.getField(fieldName).getInt(null) }.getOrNull()

    private fun publish(night: Boolean, source: String, raw: Int) {
        val callbacks: List<(Boolean) -> Unit>
        val shouldLog: Boolean
        synchronized(lock) {
            val changed = cachedNight != night
            val firstSource = activeSource == null
            cachedNight = night
            activeSource = source
            probeLogged = true
            shouldLog = changed || firstSource
            callbacks = if (changed) observers.values.toList() else emptyList()
        }
        if (shouldLog) {
            emitLog("GeelyDayNight: source=$source raw=$raw -> ${if (night) "night" else "day"}")
        }
        if (callbacks.isNotEmpty()) {
            dispatchToMain {
                callbacks.forEach { cb -> runCatching { cb(night) } }
            }
        }
    }

    private fun dispatchToMain(action: () -> Unit) {
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
