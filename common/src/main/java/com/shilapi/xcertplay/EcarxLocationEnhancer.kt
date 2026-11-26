package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationMessages
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.Locale

/**
 * Low-conflict extension for ECARX head units (e.g. domestic Geely Monjaro / KX11):
 * - Combines hardware-only GNSS fixes (never falling back to network location, unthrottled so
 *   1 Hz fixes are delivered immediately without beat drops, and deduplicated so stale tunnel-entry
 *   fixes stop immediately on signal loss) with ECARX `$PASCD` wheel speed.
 * - Prefetches the current tick's ECARX wheel speed so `$GPRMC` carries low-latency CAN speed
 *   matching `$PASCD` instead of the lagged GNSS Doppler speed.
 * - Wraps [EcarxWheelSpeed] via [VehicleSpeedSource] decoration to log per-minute peak wheel speed
 *   and gear transitions into DiPlay's diagnostic report without modifying upstream classes.
 */
internal object EcarxLocationEnhancer {
    fun create(context: Context, log: (String) -> Unit): Iap2LocationProvider {
        val speedSource = DiagnosticVehicleSpeedSource(
            EcarxWheelSpeed.attach(context.applicationContext),
            log = log,
        )
        return VehicleSpeedLocationProvider(
            GpsOnlyCarPlayLocationProvider(context, speedSource = speedSource, log = log),
            speedSource,
            log = log,
        )
    }
}

/**
 * Hardware-GPS-only [Iap2LocationProvider]. On domestic KX11 head units with a roof GNSS antenna,
 * fresh GPS fixes are encoded as `$GPGGA` + `$GPRMC`; on GPS-less export units, no network fixes
 * are ever emitted so behavior degrades cleanly to pure `$PASCD`.
 */
private class GpsOnlyCarPlayLocationProvider(
    context: Context,
    private val speedSource: DiagnosticVehicleSpeedSource,
    private val log: (String) -> Unit = {},
) : Iap2LocationProvider {
    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    @Volatile private var latestGpsFix: ReceivedFix? = null
    @Volatile private var lastEmittedKey: EmittedFixKey? = null
    @Volatile private var firstFixLogged = false
    @Volatile private var wheelSpeedRequested = false
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER && isUsableFix(location)) {
                latestGpsFix = ReceivedFix(location, SystemClock.elapsedRealtime())
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    override fun onRequested(components: Set<Int>) {
        wheelSpeedRequested = Iap2LocationMessages.VEHICLE_SPEED_DATA in components
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) return@synchronized true
        latestGpsFix = null
        lastEmittedKey = null
        firstFixLogged = false
        val manager = locationManager
        if (manager == null || LocationManager.GPS_PROVIDER !in manager.allProviders) {
            log("ECARX GPS source unavailable: providers=${manager?.allProviders ?: emptyList<String>()}")
            return@synchronized false
        }
        seedLastKnownGps(manager)
        started = try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MILLIS,
                MIN_DISTANCE_METERS,
                listener,
                Looper.getMainLooper(),
            )
            true
        } catch (error: Exception) {
            Log.w(TAG, "Could not subscribe to Android GPS provider", error)
            false
        }
        if (started) {
            log("ECARX GPS source started: provider=gps intervalMs=$UPDATE_INTERVAL_MILLIS maxAgeMs=$MAX_FIX_AGE_MILLIS")
        } else {
            log("ECARX GPS source could not start")
        }
        started
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownGps(manager: LocationManager) {
        val location = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read last known GPS location", error)
            null
        } ?: return
        val ageMs = fixAgeMillis(location)
        if (ageMs <= MAX_FIX_AGE_MILLIS && isUsableFix(location)) {
            latestGpsFix = ReceivedFix(location, SystemClock.elapsedRealtime() - ageMs)
            Log.i(TAG, "Seeded gps location ageMs=$ageMs")
        }
    }

    override fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            val wasStarted = started
            started = false
            latestGpsFix = null
            lastEmittedKey = null
            firstFixLogged = false
            wasStarted
        }
        if (!shouldUnregister) return
        try {
            locationManager?.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        }
    }

    override fun latestNmea(): String? {
        val received = latestGpsFix ?: return null
        val fix = received.location
        val ageMs = (SystemClock.elapsedRealtime() - received.receivedElapsedMs).coerceAtLeast(0L)
        if (ageMs > MAX_FIX_AGE_MILLIS || !isUsableFix(fix)) return null
        val key = EmittedFixKey(fix.time, fix.elapsedRealtimeNanos)
        // Stop emitting as soon as GNSS stops updating (e.g. entering a tunnel) so stale
        // tunnel-entry coordinates never override iPhone wheel-speed dead reckoning.
        if (key == lastEmittedKey) return null
        // Use the latest CAN wheel speed for $GPRMC so it matches $PASCD without GNSS Doppler lag.
        val wheelSpeedMps = if (wheelSpeedRequested) speedSource.prefetchLatestMps() else null
        val speedMps = wheelSpeedMps ?: fix.speed.takeIf { fix.hasSpeed() }?.toDouble()
        return try {
            val encoded = NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() }?.toDouble(),
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = speedMps,
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
            lastEmittedKey = key
            if (!firstFixLogged) {
                firstFixLogged = true
                val acc = if (fix.hasAccuracy()) String.format(Locale.US, "%.1fm", fix.accuracy) else "none"
                val gpsSpd = if (fix.hasSpeed()) String.format(Locale.US, "%.1fkm/h", fix.speed * 3.6) else "none"
                val rmcSpd = speedMps?.let { String.format(Locale.US, "%.1fkm/h", it * 3.6) } ?: "none"
                val brg = if (fix.hasBearing()) String.format(Locale.US, "%.1f", fix.bearing) else "none"
                val spdSrc = if (wheelSpeedMps != null) "wheel" else "gps"
                log("ECARX GPS first fix: acc=$acc gpsSpeed=$gpsSpd rmcSpeed=$rmcSpd($spdSrc) bearing=$brg ageMs=$ageMs")
            }
            encoded
        } catch (error: Throwable) {
            Log.w(TAG, "Could not encode ECARX GPS location as NMEA", error)
            null
        }
    }

    private fun isUsableFix(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return false
        if (location.latitude == 0.0 && location.longitude == 0.0) return false
        if (location.hasAccuracy() && location.accuracy > MAX_GPS_ACCURACY_METERS) return false
        return true
    }

    private fun fixAgeMillis(location: Location): Long {
        val elapsedNanos = location.elapsedRealtimeNanos
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (elapsedNanos > 0L && nowNanos >= elapsedNanos) {
            return (nowNanos - elapsedNanos) / 1_000_000L
        }
        return (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
    }

    private data class ReceivedFix(
        val location: Location,
        val receivedElapsedMs: Long,
    )

    private data class EmittedFixKey(
        val timeMillis: Long,
        val elapsedRealtimeNanos: Long,
    )

    private companion object {
        const val TAG = "DiPlay-EcarxGps"
        // Request unthrottled updates so Android does not add extra jitter on top of native 1 Hz GNSS.
        const val UPDATE_INTERVAL_MILLIS = 0L
        const val MIN_DISTANCE_METERS = 0f
        const val MAX_FIX_AGE_MILLIS = 5_000L
        const val MAX_GPS_ACCURACY_METERS = 100f
    }
}

/**
 * Decorator around [VehicleSpeedSource] that records per-minute peak wheel speed and gear changes
 * into the diagnostic log without touching the wrapped upstream implementation, and allows the
 * sibling GPS provider to prefetch the current tick's latest CAN speed for `$GPRMC`.
 */
private class DiagnosticVehicleSpeedSource(
    private val delegate: VehicleSpeedSource,
    private val log: (String) -> Unit = {},
) : VehicleSpeedSource {
    private val lock = Any()
    private var windowStartElapsedMs = 0L
    private var lastGear: VehicleGear? = null
    private var windowMaxMps: Double? = null
    private var windowLastMps: Double? = null
    private var windowSampleCount = 0
    private var windowDrainCount = 0
    private var firstReadingLogged = false
    private var pendingReading: VehicleSpeedReading? = null
    private var lastFreshMps: Double? = null
    private var lastFreshElapsedMs = 0L

    override fun start() {
        synchronized(lock) {
            windowStartElapsedMs = SystemClock.elapsedRealtime()
            lastGear = null
            windowMaxMps = null
            windowLastMps = null
            windowSampleCount = 0
            windowDrainCount = 0
            firstReadingLogged = false
            pendingReading = null
            lastFreshMps = null
            lastFreshElapsedMs = 0L
        }
        delegate.start()
    }

    override fun stop() {
        synchronized(lock) {
            pendingReading = null
            lastFreshMps = null
            lastFreshElapsedMs = 0L
        }
        delegate.stop()
    }

    /**
     * Drains the underlying source early during the same iAP2 tick so `$GPRMC` can carry the
     * newest 250 ms wheel-speed sample; the drained reading is held for the immediate [drain] call.
     */
    fun prefetchLatestMps(): Double? {
        val drained = delegate.drain()
        val now = SystemClock.elapsedRealtime()
        return synchronized(lock) {
            if (drained != null && drained.samples.isNotEmpty()) {
                val merged = pendingReading?.let { prev ->
                    VehicleSpeedReading(drained.gear, prev.samples + drained.samples)
                } ?: drained
                pendingReading = merged
                val latestMps = drained.samples.last().metersPerSecond
                lastFreshMps = latestMps
                lastFreshElapsedMs = now
                latestMps
            } else {
                lastFreshMps?.takeIf { now - lastFreshElapsedMs <= FRESH_SPEED_MAX_AGE_MILLIS }
            }
        }
    }

    override fun drain(): VehicleSpeedReading? {
        val prefetched = synchronized(lock) {
            pendingReading.also { pendingReading = null }
        }
        val reading = prefetched ?: delegate.drain()
        val now = SystemClock.elapsedRealtime()
        val lines = ArrayList<String>(3)
        synchronized(lock) {
            if (windowStartElapsedMs == 0L) windowStartElapsedMs = now
            if (reading != null && reading.samples.isNotEmpty()) {
                windowDrainCount++
                windowSampleCount += reading.samples.size
                if (lastGear != reading.gear) {
                    lastGear = reading.gear
                    lines += "ECARX gear=${reading.gear.name}"
                }
                for (sample in reading.samples) {
                    val mps = sample.metersPerSecond
                    windowLastMps = mps
                    if (windowMaxMps == null || mps > windowMaxMps!!) windowMaxMps = mps
                }
                lastFreshMps = reading.samples.last().metersPerSecond
                lastFreshElapsedMs = now
                if (!firstReadingLogged) {
                    firstReadingLogged = true
                    val firstMps = reading.samples.first().metersPerSecond
                    lines += String.format(
                        Locale.US,
                        "ECARX first wheelSpeed=%.1f km/h (%.3f m/s) gear=%s",
                        firstMps * 3.6,
                        firstMps,
                        reading.gear.letter,
                    )
                }
            }
            val elapsedMs = now - windowStartElapsedMs
            if (elapsedMs >= SUMMARY_MILLIS) {
                val maxKmh = windowMaxMps?.let { String.format(Locale.US, "%.1f km/h", it * 3.6) } ?: "none"
                val maxMps = windowMaxMps?.let { String.format(Locale.US, "%.3f", it) } ?: "none"
                val lastKmh = windowLastMps?.let { String.format(Locale.US, "%.1f km/h", it * 3.6) } ?: "none"
                lines += "ECARX wheelSpeed: last ${elapsedMs / 1000} s " +
                    "gear=${lastGear?.letter ?: "none"} " +
                    "maxWheelSpeed=$maxKmh maxMps=$maxMps lastWheelSpeed=$lastKmh " +
                    "drains=$windowDrainCount samples=$windowSampleCount"
                windowStartElapsedMs = now
                windowMaxMps = null
                windowSampleCount = 0
                windowDrainCount = 0
            }
        }
        for (line in lines) log(line)
        return reading
    }

    override fun status(): String? = delegate.status()

    private companion object {
        const val SUMMARY_MILLIS = 60_000L
        const val FRESH_SPEED_MAX_AGE_MILLIS = 1_500L
    }
}
