package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale
import kotlin.math.abs

internal data class GeelyTireReading(
    val pressureKpa: Float? = null,
    val tempC: Float? = null,
    val pressureWarning: Boolean = false,
    val tempWarning: Boolean = false,
    val quickLeakWarning: Boolean = false,
    val sensorState: Int? = null,
) {
    val hasPressure: Boolean get() = pressureKpa != null && pressureKpa.isFinite()
    val hasTemp: Boolean get() = tempC != null && tempC.isFinite()
    val hasAnyValue: Boolean get() = hasPressure || hasTemp
    val isLowPressure: Boolean get() = pressureKpa != null && pressureKpa < LOW_PRESSURE_KPA
    val isHighPressure: Boolean get() = pressureKpa != null && pressureKpa > HIGH_PRESSURE_KPA
    val isHighTemp: Boolean get() = tempC != null && tempC > HIGH_TEMP_C
    val isAlert: Boolean
        get() = pressureWarning || tempWarning || quickLeakWarning ||
            isLowPressure || isHighPressure || isHighTemp

    companion object {
        const val LOW_PRESSURE_KPA = 195f
        const val HIGH_PRESSURE_KPA = 300f
        const val HIGH_TEMP_C = 68f
    }
}

internal data class GeelyTireTelemetry(
    val frontLeft: GeelyTireReading = GeelyTireReading(),
    val frontRight: GeelyTireReading = GeelyTireReading(),
    val rearLeft: GeelyTireReading = GeelyTireReading(),
    val rearRight: GeelyTireReading = GeelyTireReading(),
    val systemState: Int? = null,
    val anyChannelSupported: Boolean = false,
) {
    val hasAnyValue: Boolean
        get() = frontLeft.hasAnyValue || frontRight.hasAnyValue ||
            rearLeft.hasAnyValue || rearRight.hasAnyValue

    val hasAnyAlert: Boolean
        get() = frontLeft.isAlert || frontRight.isAlert ||
            rearLeft.isAlert || rearRight.isAlert ||
            (systemState != null && systemState in TPMS_WARN_STATE_RANGE)

    fun alertDescription(): String? {
        val items = mutableListOf<String>()
        fun check(label: String, tire: GeelyTireReading) {
            if (!tire.isAlert) return
            val detail = when {
                tire.quickLeakWarning -> "快速漏气"
                tire.tempWarning || tire.isHighTemp -> tire.tempC?.let { "胎温过高 ${it.toInt()}°C" } ?: "胎温过高"
                tire.isLowPressure -> tire.pressureKpa?.let { "胎压偏低 ${it.toInt()} kPa" } ?: "胎压偏低"
                tire.isHighPressure -> tire.pressureKpa?.let { "胎压偏高 ${it.toInt()} kPa" } ?: "胎压偏高"
                else -> tire.pressureKpa?.let { "胎压异常 ${it.toInt()} kPa" } ?: "胎压异常"
            }
            items.add("$label $detail")
        }
        check("左前轮", frontLeft)
        check("右前轮", frontRight)
        check("左后轮", rearLeft)
        check("右后轮", rearRight)
        if (items.isNotEmpty()) return items.joinToString(" · ")
        return if (systemState != null && systemState in TPMS_WARN_STATE_RANGE) {
            "胎压监测系统告警"
        } else {
            null
        }
    }

    companion object {
        // ITireSensor.TPMS_STATES_CMN_WARN (0x00504002) .. TPMS_STATES_RR_WARN (0x00504006)
        private val TPMS_WARN_STATE_RANGE = 0x00504002..0x00504006
    }
}

internal data class GeelyTripTelemetry(
    val distanceKm: Float? = null,
    val durationMs: Long? = null,
    val avgSpeedKmh: Float? = null,
    val avgFuelLPer100Km: Float? = null,
    val instantFuelLPer100Km: Float? = null,
    val fromNativeTripData: Boolean = false,
)

internal data class GeelyVehicleInfoTelemetry(
    val odometerKm: Float? = null,
    val totalAvgFuelLPer100Km: Float? = null,
    val enduranceRangeKm: Float? = null,
    val fuelLevelLiters: Float? = null,
    val fuelPercent: Float? = null,
    val ambientTempC: Float? = null,
    val indoorTempC: Float? = null,
    val maintenanceMileageKm: Float? = null,
    val engineOilPercent: Float? = null,
    val oilLevelOk: Boolean? = null,
    val coolantLevelOk: Boolean? = null,
    val powertrainWarningText: String? = null,
    val customTripDistanceKm: Float = 0f,
    val customTripDurationMs: Long = 0L,
) {
    val customTripAvgSpeedKmh: Float?
        get() {
            if (customTripDurationMs < 10_000L || customTripDistanceKm <= 0.05f) return null
            val hours = customTripDurationMs / 3_600_000f
            return (customTripDistanceKm / hours).takeIf { it.isFinite() && it in 0f..300f }
        }
}

internal data class GeelyVehicleTelemetrySnapshot(
    val trip: GeelyTripTelemetry = GeelyTripTelemetry(),
    val tires: GeelyTireTelemetry = GeelyTireTelemetry(),
    val vehicle: GeelyVehicleInfoTelemetry = GeelyVehicleInfoTelemetry(),
    val updatedAtMs: Long = 0L,
) {
    companion object {
        fun previewDemo(): GeelyVehicleTelemetrySnapshot = GeelyVehicleTelemetrySnapshot(
            trip = GeelyTripTelemetry(
                distanceKm = 4.0f,
                durationMs = 19L * 60_000L + 28_000L,
                avgSpeedKmh = 12.3f,
                avgFuelLPer100Km = 11.2f,
                instantFuelLPer100Km = 6.4f,
                fromNativeTripData = true,
            ),
            tires = GeelyTireTelemetry(
                frontLeft = GeelyTireReading(pressureKpa = 243f, tempC = 23f),
                frontRight = GeelyTireReading(pressureKpa = 242f, tempC = 23f),
                rearLeft = GeelyTireReading(pressureKpa = 242f, tempC = 22f),
                rearRight = GeelyTireReading(pressureKpa = 242f, tempC = 22f),
                anyChannelSupported = true,
            ),
            vehicle = GeelyVehicleInfoTelemetry(
                odometerKm = 28910f,
                totalAvgFuelLPer100Km = 8.7f,
                enduranceRangeKm = 82f,
                fuelLevelLiters = 10.4f,
                fuelPercent = 13f,
                ambientTempC = 17f,
                indoorTempC = 24.2f,
                maintenanceMileageKm = 4010f,
                engineOilPercent = 100f,
                oilLevelOk = true,
                coolantLevelOk = true,
                customTripDistanceKm = 126.4f,
                customTripDurationMs = 3L * 3_600_000L + 18L * 60_000L,
            ),
            updatedAtMs = SystemClock.elapsedRealtime(),
        )
    }
}

/**
 * Strictly read-only telemetry reader for Geely / ECARX AdaptAPI (verified on KX11 Android 9):
 * - Current Trip: `IHev.getTripData()` (`getLatestDrivingInfo` / `getLatestAvgEnergyInfo`) +
 *   `IVirtualSensor` (`0x00400200` instant fuel, `0x00400300` one-ignition avg fuel) with automatic
 *   CAN-speed numerical integration fallback when `ITripData` is unavailable.
 * - TPMS Tire Pressure & Temp: `ITireSensor` (`0x00500100..0x00504000`) via `ISensor`.
 * - Overall Vehicle Info: `ISensor` & `IVirtualSensor` (`0x00100700` ODO, `0x00400100` avg fuel,
 *   `0x00100800` endurance range, `0x00100600` fuel liters, `0x00404500` fuel %,
 *   `0x00100B00`/`0x00100C00` ambient/indoor temp, `0x00403000` maintenance mileage,
 *   `0x00102C00`/`0x00200400`/`0x00200500` oil & coolant, `0x0030xxxx` powertrain warnings) plus
 *   a local persistent cumulative Trip B counter.
 */
internal object GeelyVehicleTelemetryReader {
    // Verified ISensor IDs
    private const val SENSOR_CAR_SPEED_IPK = 0x00101A00
    private const val SENSOR_FUEL_LEVEL_ML = 0x00100600
    private const val SENSOR_ODOMETER = 0x00100700
    private const val SENSOR_ENDURANCE_MILEAGE = 0x00100800
    private const val SENSOR_ENDURANCE_MILEAGE_FUEL = 0x00101800
    private const val SENSOR_TEMP_AMBIENT = 0x00100B00
    private const val SENSOR_TEMP_INDOOR = 0x00100C00
    private const val SENSOR_ENGINE_OIL_PERC = 0x00102C00
    private const val SENSOR_IGNITION_STATE = 0x00200100
    private const val SENSOR_ENGINE_OIL_LEVEL = 0x00200400
    private const val SENSOR_ENGINE_COOLANT_LEVEL = 0x00200500
    private const val SENSOR_WARN_FUEL_RED = 0x00300100
    private const val SENSOR_WARN_OIL_PRESSURE = 0x00300300
    private const val SENSOR_WARN_COOLANT_TEMP_HIGH = 0x00300400
    private const val SENSOR_WARN_TRANSMISSION_TEMP_HIGH = 0x00300500
    private const val SENSOR_WARN_TRANSMISSION_FAULT = 0x00300600
    private const val SENSOR_WARN_COOLANT_FAULT = 0x00300900

    private const val EVENT_ENGINE_OIL_LEVEL_OK = 2098177
    private const val EVENT_COOLANT_LEVEL_NORMAL = 2098433
    private const val EVENT_WARNING_OFF = 3145728

    // Verified IVirtualSensor IDs
    private const val VS_AVG_FUEL = 0x00400100
    private const val VS_INS_FUEL = 0x00400200
    private const val VS_AVG_FUEL_ONE_IGNITION = 0x00400300
    private const val VS_MAINTENANCE_MILEAGE = 0x00403000
    private const val VS_FUEL_PERCENT = 0x00404500

    // Discovered ITireSensor IDs (0x0050xxxx via ISensor)
    private const val TIRE_PRESSURE_FL = 0x00500100
    private const val TIRE_PRESSURE_FR = 0x00500200
    private const val TIRE_PRESSURE_RL = 0x00500300
    private const val TIRE_PRESSURE_RR = 0x00500400
    private const val TIRE_TEMP_FL = 0x00500500
    private const val TIRE_TEMP_FR = 0x00500600
    private const val TIRE_TEMP_RL = 0x00500700
    private const val TIRE_TEMP_RR = 0x00500800
    private const val TIRE_WARN_FL = 0x00500900
    private const val TIRE_WARN_FR = 0x00500A00
    private const val TIRE_WARN_RL = 0x00500B00
    private const val TIRE_WARN_RR = 0x00500C00
    private const val TIRE_WARN_TEMP_FL = 0x00500D00
    private const val TIRE_WARN_TEMP_FR = 0x00500E00
    private const val TIRE_WARN_TEMP_RL = 0x00500F00
    private const val TIRE_WARN_TEMP_RR = 0x00501000
    private const val TIRE_WARN_LEAK_FL = 0x00501100
    private const val TIRE_WARN_LEAK_FR = 0x00501200
    private const val TIRE_WARN_LEAK_RL = 0x00501300
    private const val TIRE_WARN_LEAK_RR = 0x00501400
    private const val TIRE_STATE_FL = 0x00501500
    private const val TIRE_STATE_FR = 0x00501600
    private const val TIRE_STATE_RL = 0x00501700
    private const val TIRE_STATE_RR = 0x00501800
    private const val TIRE_TPMS_SYS_STATES = 0x00504000
    private const val TPMS_STATES_NO_WARN = 0x00504001

    private const val PREFS_NAME = "geely_vehicle_telemetry_stats"
    private const val KEY_CUSTOM_TRIP_DIST_KM = "custom_trip_dist_km"
    private const val KEY_CUSTOM_TRIP_DURATION_MS = "custom_trip_duration_ms"
    private const val PERSIST_INTERVAL_MS = 15_000L

    private val lock = Any()
    private var carInstance: Any? = null
    private var sensorManager: Any? = null
    private var sensorSupportedMethod: Method? = null
    private var sensorValueMethod: Method? = null
    private var sensorEventMethod: Method? = null

    private var tripDataManager: Any? = null
    private var getLatestDrivingInfoMethod: Method? = null
    private var getLatestAvgEnergyInfoMethod: Method? = null

    // Sticky caches to avoid transient single-frame 0.0 glitches (seen on 0x00400100)
    private var lastNonZeroTotalAvgFuel: Float? = null
    private var lastNonZeroTripAvgFuel: Float? = null

    // Software fallback trip accumulator (only used when native ITripData is absent)
    private var fallbackTripDistKm: Float = 0f
    private var fallbackTripDurationMs: Long = 0L

    // Persistent cumulative Trip B (user-resettable)
    private var customTripLoaded: Boolean = false
    private var customTripDistKm: Float = 0f
    private var customTripDurationMs: Long = 0L
    private var lastIntegrateElapsedMs: Long = 0L
    private var lastSpeedKmhForIntegrate: Float = 0f
    private var lastPersistElapsedMs: Long = 0L

    @Volatile
    var latestSnapshot: GeelyVehicleTelemetrySnapshot = GeelyVehicleTelemetrySnapshot()
        private set

    fun resetCustomTrip(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            customTripLoaded = true
            customTripDistKm = 0f
            customTripDurationMs = 0L
            lastPersistElapsedMs = SystemClock.elapsedRealtime()
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_CUSTOM_TRIP_DIST_KM, 0f)
                .putLong(KEY_CUSTOM_TRIP_DURATION_MS, 0L)
                .apply()
            latestSnapshot = latestSnapshot.copy(
                vehicle = latestSnapshot.vehicle.copy(
                    customTripDistanceKm = 0f,
                    customTripDurationMs = 0L,
                ),
            )
        }
    }

    fun poll(context: Context): GeelyVehicleTelemetrySnapshot = synchronized(lock) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        ensureCustomTripLoadedLocked(app)
        ensureManagersLocked(app)

        val sensor = sensorManager
        val speedKmh = readFloat(sensor, SENSOR_CAR_SPEED_IPK)
            ?.let { abs(it) * 3.6f }
            ?.takeIf { it.isFinite() && it in 0f..360f }
            ?.let { if (it <= 1.0f) 0f else it }

        // Integrate distance/duration using CAN speed for cumulative Trip B and fallback trip
        updateIntegratorsLocked(app, now, speedKmh)

        val trip = readTripTelemetryLocked(sensor)
        val tires = readTireTelemetryLocked(sensor)
        val vehicle = readVehicleInfoTelemetryLocked(sensor)

        val snapshot = GeelyVehicleTelemetrySnapshot(
            trip = trip,
            tires = tires,
            vehicle = vehicle,
            updatedAtMs = now,
        )
        latestSnapshot = snapshot
        return snapshot
    }

    private fun ensureCustomTripLoadedLocked(app: Context) {
        if (customTripLoaded) return
        val sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        customTripDistKm = sp.getFloat(KEY_CUSTOM_TRIP_DIST_KM, 0f).coerceAtLeast(0f)
        customTripDurationMs = sp.getLong(KEY_CUSTOM_TRIP_DURATION_MS, 0L).coerceAtLeast(0L)
        customTripLoaded = true
    }

    private fun updateIntegratorsLocked(app: Context, now: Long, speedKmh: Float?) {
        val prev = lastIntegrateElapsedMs
        lastIntegrateElapsedMs = now
        val curSpd = speedKmh ?: 0f
        if (prev > 0L) {
            val dtMs = (now - prev).coerceIn(0L, 5_000L)
            if (dtMs > 0L) {
                val avgSpd = (lastSpeedKmhForIntegrate + curSpd) * 0.5f
                if (avgSpd > 0.5f) {
                    val deltaKm = avgSpd * (dtMs / 3_600_000f)
                    customTripDistKm += deltaKm
                    fallbackTripDistKm += deltaKm
                    customTripDurationMs += dtMs
                    fallbackTripDurationMs += dtMs
                } else if (fallbackTripDurationMs > 0L && curSpd == 0f) {
                    // Keep counting trip duration while ignition is still on after driving started
                    fallbackTripDurationMs += dtMs
                }
            }
        }
        lastSpeedKmhForIntegrate = curSpd
        if (now - lastPersistElapsedMs >= PERSIST_INTERVAL_MS) {
            lastPersistElapsedMs = now
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_CUSTOM_TRIP_DIST_KM, customTripDistKm)
                .putLong(KEY_CUSTOM_TRIP_DURATION_MS, customTripDurationMs)
                .apply()
        }
    }

    private fun readTripTelemetryLocked(sensor: Any?): GeelyTripTelemetry {
        var nativeDistKm: Float? = null
        var nativeDurationMs: Long? = null
        var nativeAvgFuel: Float? = null
        var fromNative = false

        val tripMgr = tripDataManager
        if (tripMgr != null) {
            val drivingInfo = getLatestDrivingInfoMethod?.let { m ->
                runCatching { m.invoke(tripMgr) }.getOrNull()
            }
            if (drivingInfo != null) {
                val distMeters = invokeZeroArgNumber(drivingInfo, "getTripDistance")?.toLong()
                val durMs = invokeZeroArgNumber(drivingInfo, "getTripDuration")?.toLong()
                // Filter out ODO-like sentinel when trip hasn't started (e.g. dur == 0 && dist > 1_000_000m)
                val validDist = distMeters != null && distMeters >= 0L &&
                    !(durMs == 0L && distMeters > 500_000L)
                if (validDist && distMeters != null) {
                    nativeDistKm = distMeters / 1000f
                    fromNative = true
                }
                if (durMs != null && durMs >= 0L && durMs < 365L * 86_400_000L) {
                    nativeDurationMs = durMs
                    fromNative = true
                }
            }

            val avgEnergyInfo = getLatestAvgEnergyInfoMethod?.let { m ->
                runCatching { m.invoke(tripMgr) }.getOrNull()
            }
            if (avgEnergyInfo != null) {
                val fuel = invokeZeroArgNumber(avgEnergyInfo, "getAvgFuelConsumption")?.toFloat()
                if (fuel != null && fuel.isFinite() && fuel in 0.1f..99.9f) {
                    nativeAvgFuel = fuel
                    lastNonZeroTripAvgFuel = fuel
                }
            }
        }

        // Secondary native source: IVirtualSensor one-ignition avg fuel (`0x00400300`)
        if (nativeAvgFuel == null) {
            val oneIgnFuel = readFloat(sensor, VS_AVG_FUEL_ONE_IGNITION)
            if (oneIgnFuel != null && oneIgnFuel in 0.1f..99.9f) {
                nativeAvgFuel = oneIgnFuel
                lastNonZeroTripAvgFuel = oneIgnFuel
            }
        }

        val effectiveDistKm = if (fromNative) nativeDistKm else fallbackTripDistKm.takeIf { it > 0f || fallbackTripDurationMs > 0L }
        val effectiveDurMs = if (fromNative) nativeDurationMs else fallbackTripDurationMs.takeIf { it > 0L }
        val avgSpeedKmh = if (effectiveDistKm != null && effectiveDurMs != null && effectiveDurMs >= 1_000L) {
            val hours = effectiveDurMs / 3_600_000f
            (effectiveDistKm / hours).takeIf { it.isFinite() && it in 0f..300f }
        } else {
            null
        }

        // Keep sticky non-zero trip avg fuel while trip distance > 0, clear if a new trip starts at 0
        val effectiveTripAvgFuel = when {
            nativeAvgFuel != null -> nativeAvgFuel
            effectiveDistKm != null && effectiveDistKm > 0.1f -> lastNonZeroTripAvgFuel
            else -> {
                lastNonZeroTripAvgFuel = null
                null
            }
        }

        val rawInsFuel = readFloat(sensor, VS_INS_FUEL)
        val instantFuel = rawInsFuel?.takeIf { it in 0.1f..99.9f }

        return GeelyTripTelemetry(
            distanceKm = effectiveDistKm,
            durationMs = effectiveDurMs,
            avgSpeedKmh = avgSpeedKmh,
            avgFuelLPer100Km = effectiveTripAvgFuel,
            instantFuelLPer100Km = instantFuel,
            fromNativeTripData = fromNative,
        )
    }

    private fun readTireTelemetryLocked(sensor: Any?): GeelyTireTelemetry {
        if (sensor == null) return GeelyTireTelemetry()
        val anySup = isSupported(sensor, TIRE_PRESSURE_FL) ||
            isSupported(sensor, TIRE_PRESSURE_FR) ||
            isSupported(sensor, TIRE_PRESSURE_RL) ||
            isSupported(sensor, TIRE_PRESSURE_RR) ||
            isSupported(sensor, TIRE_TEMP_FL)

        val fl = readSingleTire(sensor, TIRE_PRESSURE_FL, TIRE_TEMP_FL, TIRE_WARN_FL, TIRE_WARN_TEMP_FL, TIRE_WARN_LEAK_FL, TIRE_STATE_FL)
        val fr = readSingleTire(sensor, TIRE_PRESSURE_FR, TIRE_TEMP_FR, TIRE_WARN_FR, TIRE_WARN_TEMP_FR, TIRE_WARN_LEAK_FR, TIRE_STATE_FR)
        val rl = readSingleTire(sensor, TIRE_PRESSURE_RL, TIRE_TEMP_RL, TIRE_WARN_RL, TIRE_WARN_TEMP_RL, TIRE_WARN_LEAK_RL, TIRE_STATE_RL)
        val rr = readSingleTire(sensor, TIRE_PRESSURE_RR, TIRE_TEMP_RR, TIRE_WARN_RR, TIRE_WARN_TEMP_RR, TIRE_WARN_LEAK_RR, TIRE_STATE_RR)
        val sysState = readEvent(sensor, TIRE_TPMS_SYS_STATES)
            ?.takeIf { it != Int.MIN_VALUE && it != -1 && it != 0 }

        return GeelyTireTelemetry(
            frontLeft = fl,
            frontRight = fr,
            rearLeft = rl,
            rearRight = rr,
            systemState = sysState,
            anyChannelSupported = anySup || fl.hasAnyValue || fr.hasAnyValue || rl.hasAnyValue || rr.hasAnyValue,
        )
    }

    private fun readSingleTire(
        sensor: Any,
        pressureId: Int,
        tempId: Int,
        warnPressureId: Int,
        warnTempId: Int,
        warnLeakId: Int,
        stateId: Int,
    ): GeelyTireReading {
        val rawP = readFloat(sensor, pressureId)
        val pressureKpa = normalizePressureToKpa(rawP)
        val rawT = readFloat(sensor, tempId)
        val tempC = normalizeTireTempC(rawT)
        val pWarnEvt = readEvent(sensor, warnPressureId)
        val tWarnEvt = readEvent(sensor, warnTempId)
        val lWarnEvt = readEvent(sensor, warnLeakId)
        val stateEvt = readEvent(sensor, stateId)?.takeIf { it != Int.MIN_VALUE && it != -1 }

        return GeelyTireReading(
            pressureKpa = pressureKpa,
            tempC = tempC,
            pressureWarning = isActiveTireWarningEvent(pWarnEvt),
            tempWarning = isActiveTireWarningEvent(tWarnEvt),
            quickLeakWarning = isActiveTireWarningEvent(lWarnEvt),
            sensorState = stateEvt,
        )
    }

    private fun readVehicleInfoTelemetryLocked(sensor: Any?): GeelyVehicleInfoTelemetry {
        val odo = readFloat(sensor, SENSOR_ODOMETER)?.takeIf { it in 0f..9_999_999f }

        val rawTotalAvgFuel = readFloat(sensor, VS_AVG_FUEL)
        if (rawTotalAvgFuel != null && rawTotalAvgFuel in 0.1f..99.9f) {
            lastNonZeroTotalAvgFuel = rawTotalAvgFuel
        }
        val totalAvgFuel = lastNonZeroTotalAvgFuel

        val rangeKm = (readFloat(sensor, SENSOR_ENDURANCE_MILEAGE)
            ?: readFloat(sensor, SENSOR_ENDURANCE_MILEAGE_FUEL))
            ?.takeIf { it in 0f..3000f }

        val fuelMl = readFloat(sensor, SENSOR_FUEL_LEVEL_ML)?.takeIf { it in 0f..200_000f }
        val fuelLiters = fuelMl?.let { it / 1000f }
        val fuelPct = readFloat(sensor, VS_FUEL_PERCENT)?.takeIf { it in 0f..100f }

        val ambientC = readFloat(sensor, SENSOR_TEMP_AMBIENT)?.takeIf { it in -50f..85f }
        val indoorC = readFloat(sensor, SENSOR_TEMP_INDOOR)?.takeIf { it in -40f..85f }
        val maintKm = readFloat(sensor, VS_MAINTENANCE_MILEAGE)?.takeIf { it in 0f..100_000f }
        val oilPerc = readFloat(sensor, SENSOR_ENGINE_OIL_PERC)?.takeIf { it in 0f..100f }

        val oilLevelEvt = readEvent(sensor, SENSOR_ENGINE_OIL_LEVEL)
        val oilOk = oilLevelEvt?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it == EVENT_ENGINE_OIL_LEVEL_OK }
        val coolantEvt = readEvent(sensor, SENSOR_ENGINE_COOLANT_LEVEL)
        val coolantOk = coolantEvt?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it == EVENT_COOLANT_LEVEL_NORMAL }

        val warnings = mutableListOf<String>()
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_FUEL_RED))) warnings.add("油量过低")
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_OIL_PRESSURE))) warnings.add("机油压力异常")
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_COOLANT_TEMP_HIGH))) warnings.add("冷却液高温")
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_TRANSMISSION_TEMP_HIGH))) warnings.add("变速箱高温")
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_TRANSMISSION_FAULT))) warnings.add("变速箱故障")
        if (isWarningOn(readEvent(sensor, SENSOR_WARN_COOLANT_FAULT))) warnings.add("冷却系统故障")

        return GeelyVehicleInfoTelemetry(
            odometerKm = odo,
            totalAvgFuelLPer100Km = totalAvgFuel,
            enduranceRangeKm = rangeKm,
            fuelLevelLiters = fuelLiters,
            fuelPercent = fuelPct,
            ambientTempC = ambientC,
            indoorTempC = indoorC,
            maintenanceMileageKm = maintKm,
            engineOilPercent = oilPerc,
            oilLevelOk = oilOk,
            coolantLevelOk = coolantOk,
            powertrainWarningText = warnings.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            customTripDistanceKm = customTripDistKm,
            customTripDurationMs = customTripDurationMs,
        )
    }

    internal fun normalizePressureToKpa(raw: Float?): Float? {
        if (raw == null || !raw.isFinite() || raw <= 0f || raw > 1_000f) return null
        return when {
            // bar (e.g. 2.43 bar -> 243 kPa)
            raw in 0.5f..6.5f -> raw * 100f
            // kPa (e.g. 243 kPa)
            raw in 50f..650f -> raw
            else -> null
        }
    }

    internal fun normalizeTireTempC(raw: Float?): Float? {
        if (raw == null || !raw.isFinite()) return null
        if (raw < -40f || raw > 150f) return null
        return raw
    }

    private fun isActiveTireWarningEvent(evt: Int?): Boolean {
        if (evt == null || evt == Int.MIN_VALUE || evt == -1 || evt == 0) return false
        if (evt == EVENT_WARNING_OFF || evt == TPMS_STATES_NO_WARN) return false
        // Normal / off state on ECARX ISensorEvent often ends in 0x00 or 0x01 ("NO_WARN"/"OFF")
        val lowByte = evt and 0xFF
        if (lowByte == 0 || lowByte == 1) return false
        return true
    }

    private fun isWarningOn(evt: Int?): Boolean {
        if (evt == null || evt == Int.MIN_VALUE || evt == -1 || evt == 0) return false
        return evt != EVENT_WARNING_OFF
    }

    private fun isSupported(sensor: Any, id: Int): Boolean {
        val m = sensorSupportedMethod ?: return false
        val status = runCatching { m.invoke(sensor, id)?.toString()?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return status != "notavailable" && status != "notsupported" && status != "error"
    }

    private fun readFloat(sensor: Any?, id: Int): Float? {
        if (sensor == null) return null
        val m = sensorValueMethod ?: return null
        val value = runCatching { (m.invoke(sensor, id) as? Number)?.toFloat() }
            .onFailure { sensorManager = null }
            .getOrNull() ?: return null
        if (!value.isFinite() || abs(value) >= 1e20f) return null
        return value
    }

    private fun readEvent(sensor: Any?, id: Int): Int? {
        if (sensor == null) return null
        val m = sensorEventMethod ?: return null
        return runCatching { (m.invoke(sensor, id) as? Number)?.toInt() }
            .onFailure { sensorManager = null }
            .getOrNull()
    }

    private fun invokeZeroArgNumber(target: Any, methodName: String): Number? =
        runCatching {
            val m = findPublicMethod(target.javaClass, methodName) ?: return null
            m.invoke(target) as? Number
        }.getOrNull()

    private fun ensureManagersLocked(app: Context) {
        if (sensorManager != null && tripDataManager != null) return
        val car = carInstance ?: runCatching {
            Class.forName("com.ecarx.xui.adaptapi.car.Car")
                .getMethod("create", Context::class.java)
                .invoke(null, app)
                ?.also { created ->
                    carInstance = created
                    connectIfConnectable(created)
                }
        }.getOrNull() ?: return

        if (sensorManager == null) {
            runCatching {
                val getSensor = findPublicMethod(car.javaClass, "getSensorManager") ?: return@runCatching
                val sm = getSensor.invoke(car) ?: return@runCatching
                sensorSupportedMethod = findPublicMethod(sm.javaClass, "isSensorSupported", Int::class.javaPrimitiveType)
                sensorValueMethod = findPublicMethod(sm.javaClass, "getSensorLatestValue", Int::class.javaPrimitiveType)
                sensorEventMethod = findPublicMethod(sm.javaClass, "getSensorEvent", Int::class.javaPrimitiveType)
                sensorManager = sm
            }
        }

        if (tripDataManager == null) {
            runCatching {
                val getHev = findPublicMethod(car.javaClass, "getHevManager") ?: return@runCatching
                val hev = getHev.invoke(car) ?: return@runCatching
                val getTrip = findPublicMethod(hev.javaClass, "getTripData") ?: return@runCatching
                val trip = getTrip.invoke(hev) ?: return@runCatching
                getLatestDrivingInfoMethod = findPublicMethod(trip.javaClass, "getLatestDrivingInfo")
                getLatestAvgEnergyInfoMethod = findPublicMethod(trip.javaClass, "getLatestAvgEnergyInfo")
                tripDataManager = trip
            }
        }
    }

    private fun connectIfConnectable(carObj: Any) {
        runCatching {
            val connectableClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
            if (!connectableClass.isInstance(carObj)) return
            val watcherClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val proxy = Proxy.newProxyInstance(
                watcherClass.classLoader,
                arrayOf(watcherClass),
            ) { proxyInstance, method, args ->
                when (method.name) {
                    "onConnected" -> synchronized(lock) {
                        sensorManager = null
                        tripDataManager = null
                    }
                    "onDisConnected" -> synchronized(lock) {
                        sensorManager = null
                        tripDataManager = null
                    }
                    "equals" -> proxyInstance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "toString" -> "GeelyVehicleTelemetryConnectWatcher"
                    else -> null
                }
            }
            connectableClass.getMethod("registerConnectWatcher", watcherClass).invoke(carObj, proxy)
            connectableClass.getMethod("connect").invoke(carObj)
        }
    }

    private fun findPublicMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? {
        val nonNull = params.filterNotNull().toTypedArray()
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            for (iface in cur.interfaces) {
                if (Modifier.isPublic(iface.modifiers)) {
                    runCatching { iface.getMethod(name, *nonNull) }.getOrNull()?.let { return it }
                }
            }
            cur = cur.superclass
        }
        return runCatching {
            cls.getMethod(name, *nonNull).apply { runCatching { isAccessible = true } }
        }.getOrNull()
    }
}
