package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Strictly read-only diagnostic probe for Geely / ECARX AdaptAPI vehicle telemetry signals
 * (TPMS tire pressure & temperature, trip computer, odometer, fuel/range, ambient/coolant/battery,
 * etc.).
 *
 * Never calls any `set*`, `reset*`, `clear*`, or control-mutating method. Intended to discover the
 * exact interfaces, constant IDs, support flags, zones, and raw units exposed by a specific head
 * unit firmware before implementing cluster telemetry cards.
 */
internal object GeelyVehicleSignalProbe {
    private const val TAG = "GeelySignalProbe"
    private const val CONNECT_WAIT_MS = 800L
    private const val MAX_TYPE_GRAPH_CLASSES = 64
    private const val MAX_FUNCTION_PROBES = 220

    private val TELEMETRY_KEYWORDS = listOf(
        "TIRE", "TYRE", "TPMS", "WHEEL",
        "PRESSURE", "TEMP",
        "TRIP", "ODO", "MILE", "RANGE", "ENDURANCE", "DISTANCE",
        "FUEL", "OIL", "CONSUMPTION", "ENERGY", "AVG", "AVERAGE", "INSTANT",
        "SPEED", "RPM", "GEAR", "TIME", "DURATION",
        "VOLT", "BATTERY", "COOLANT", "WATER", "ENGINE", "SOC",
        "AMBIENT", "OUTSIDE", "INSIDE", "ENV",
        "MAINTENANCE", "SERVICE", "DRIVE_INFO", "DRIVING", "DIM", "CLUSTER",
    )

    private val MUTATOR_NAME_PREFIXES = listOf(
        "SET_", "RESET_", "CLEAR_", "SWITCH_", "OPEN_", "CLOSE_", "ENABLE_", "DISABLE_", "TRIGGER_",
    )

    private val CANDIDATE_CLASSES = listOf(
        "com.ecarx.xui.adaptapi.car.ICar",
        "com.ecarx.xui.adaptapi.car.base.ICarFunction",
        "com.ecarx.xui.adaptapi.car.base.ICarInfo",
        "com.ecarx.xui.adaptapi.car.sensor.ISensor",
        "com.ecarx.xui.adaptapi.car.sensor.ISensorEvent",
        "com.ecarx.xui.adaptapi.car.sensor.ISensorGroup",
        "com.ecarx.xui.adaptapi.car.sensor.ISensorGroupValue",
        "com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor",
        "com.ecarx.xui.adaptapi.car.sensor.ITireSensor",
        "com.ecarx.xui.adaptapi.tpms.TPMS",
        "com.ecarx.xui.adaptapi.car.vehicle.IVehicle",
        "com.ecarx.xui.adaptapi.car.vehicle.IBcm",
        "com.ecarx.xui.adaptapi.car.vehicle.ISafety",
        "com.ecarx.xui.adaptapi.car.vehicle.IDriveMode",
        "com.ecarx.xui.adaptapi.car.vehicle.IDriveInfo",
        "com.ecarx.xui.adaptapi.car.vehicle.ITripData",
        "com.ecarx.xui.adaptapi.car.vehicle.ITire",
        "com.ecarx.xui.adaptapi.car.vehicle.ITpms",
        "com.ecarx.xui.adaptapi.car.vehicle.IEnergy",
        "com.ecarx.xui.adaptapi.car.vehicle.IFuel",
        "com.ecarx.xui.adaptapi.car.vehicle.ICluster",
        "com.ecarx.xui.adaptapi.car.vehicle.IDim",
        "com.ecarx.xui.adaptapi.car.vehicle.VehicleZone",
        "com.ecarx.xui.adaptapi.car.vehicle.VehicleAreaWheel",
        "com.ecarx.xui.adaptapi.car.hev.IHev",
        "com.ecarx.xui.adaptapi.car.hev.ITripData",
        "com.ecarx.xui.adaptapi.car.hev.ICharging",
        "com.ecarx.xui.adaptapi.car.diagnostics.IDiagnostics",
        "com.ecarx.xui.adaptapi.car.diagnostics.IDtcManager",
        "com.ecarx.xui.adaptapi.diminteraction.DimInteraction",
        "com.ecarx.xui.adaptapi.diminteraction.IDimMenuInteraction",
    )

    private val KNOWN_BASELINE_SENSORS = listOf(
        "BASELINE_SPEED" to 0x00101A00,
        "BASELINE_RPM" to 0x00100900,
        "BASELINE_AVG_FUEL" to 0x00400100,
        "BASELINE_FUEL_PERCENT" to 0x00404500,
        "BASELINE_GEAR" to 0x00200200,
        "BASELINE_IGNITION" to 0x00200100,
        "BASELINE_DAY_NIGHT" to 0x00201000,
    )

    data class ProbeReport(
        val fileName: String,
        val summaryLine: String,
        val fullText: String,
        val elapsedMs: Long,
    )

    private data class IntConstant(
        val owner: String,
        val name: String,
        val value: Int,
    )

    fun runProbe(context: Context): ProbeReport {
        val app = context.applicationContext
        val startElapsed = SystemClock.elapsedRealtime()
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val isoTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)
        val fileName = "DiPlay-geely-signal-probe-$stamp.txt"

        val highlights = mutableListOf<String>()
        val body = StringBuilder(16 * 1024)

        body.appendLine("=== 1. DEVICE & ENVIRONMENT ===")
        body.appendLine("Time: $isoTime")
        body.appendLine("Manufacturer/Brand: ${Build.MANUFACTURER} / ${Build.BRAND}")
        body.appendLine("Model/Product/Device: ${Build.MODEL} / ${Build.PRODUCT} / ${Build.DEVICE}")
        body.appendLine("Display: ${Build.DISPLAY}")
        body.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        body.appendLine("GeelyVehicleDetector.isGeelyOrEcarx: ${GeelyVehicleDetector.isGeelyOrEcarx(app)}")
        body.appendLine("EcarxKx11HeadUnit.detected: ${EcarxKx11HeadUnit.detected}")
        body.appendLine("EcarxWheelSpeed.present: ${EcarxWheelSpeed.present()}")
        body.appendLine("BootClasspath ECARX/Geely entries: ${findBootClasspathMatches()}")
        body.appendLine()

        val carInstance = createEcarxCar(app, body)
        val managers = LinkedHashMap<String, Any>()
        val seedClasses = LinkedHashSet<Class<*>>()

        // Load candidate interfaces that exist on this head unit
        val loadedCandidates = LinkedHashMap<String, Class<*>>()
        for (className in CANDIDATE_CLASSES) {
            runCatching { Class.forName(className) }.getOrNull()?.let { cls ->
                loadedCandidates[className] = cls
                seedClasses.add(cls)
            }
        }

        if (carInstance != null) {
            seedClasses.add(carInstance.javaClass)
            discoverCarManagers(carInstance, managers, seedClasses, body, highlights)
        } else {
            highlights.add("ECARX Car.create() 不可用（当前设备可能非 ECARX 车机或未就绪）")
        }

        // Probe ISensor
        val sensorManager = managers["getSensorManager"]
            ?: managers.entries.firstOrNull { it.key.contains("Sensor", ignoreCase = true) }?.value
        val sensorEventMap = buildSensorEventMap(loadedCandidates, seedClasses)
        probeISensor(sensorManager, loadedCandidates, seedClasses, sensorEventMap, body, highlights)

        // Probe ICarFunction
        val carFunction = managers["getICarFunction"]
            ?: managers.entries.firstOrNull { it.key.contains("Function", ignoreCase = true) }?.value
        probeICarFunction(carFunction, loadedCandidates, seedClasses, body, highlights)

        // Probe ITripData / IHev / ICarInfo / IDiagnostics read-only getters
        probeTripAndInfoManagers(managers, loadedCandidates, body, highlights)

        // Walk type graph of discovered com.ecarx.* / com.geely.* classes
        val discoveredClasses = walkTypeGraph(seedClasses)
        dumpTypeGraph(discoveredClasses, body)

        // Probe underlying ECarXCar / ecarx.car.jar & framework JAR TPMS/Trip classes
        probeUnderlyingEcarxCarAndFrameworkJars(sensorManager, body, highlights)

        // Probe standard AAOS android.car.Car (optional bonus check)
        probeAndroidCarProperties(app, body, highlights)

        val elapsedMs = SystemClock.elapsedRealtime() - startElapsed
        val summaryHeader = StringBuilder(2048).apply {
            appendLine("========================================================")
            appendLine("DiPlay Geely / ECARX Vehicle Signal Probe (Read-Only)")
            appendLine("Generated: $isoTime | Elapsed: ${elapsedMs} ms")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT}) Android ${Build.VERSION.RELEASE}")
            appendLine("========================================================")
            appendLine()
            appendLine("=== 0. HIGHLIGHTS (摘要) ===")
            if (highlights.isEmpty()) {
                appendLine("- (无命中项，请查看下方详细段落)")
            } else {
                for (line in highlights) {
                    appendLine("- $line")
                }
            }
            appendLine()
        }

        val fullText = summaryHeader.toString() + body.toString()
        val summaryLine = "探测完成（${elapsedMs} ms），命中摘要 ${highlights.size} 条，报告大小 ${formatBytes(fullText.toByteArray(Charsets.UTF_8).size)}"
        Log.i(TAG, "$summaryLine\n${highlights.take(12).joinToString("\n") { "  * $it" }}")
        return ProbeReport(
            fileName = fileName,
            summaryLine = summaryLine,
            fullText = fullText,
            elapsedMs = elapsedMs,
        )
    }

    private fun findBootClasspathMatches(): String {
        val raw = System.getProperty("java.boot.class.path").orEmpty()
        val matches = raw.split(':')
            .map { it.trim() }
            .filter { entry ->
                entry.isNotEmpty() && (
                    entry.contains("ecarx", ignoreCase = true) ||
                        entry.contains("adapt", ignoreCase = true) ||
                        entry.contains("geely", ignoreCase = true) ||
                        entry.contains("oneos", ignoreCase = true) ||
                        entry.contains("car", ignoreCase = true)
                    )
            }
        return if (matches.isEmpty()) "(none)" else matches.joinToString(", ")
    }

    private fun createEcarxCar(app: Context, out: StringBuilder): Any? {
        out.appendLine("=== 2. ECARX CAR INITIALIZATION ===")
        val carClass = runCatching { Class.forName("com.ecarx.xui.adaptapi.car.Car") }.getOrElse { e ->
            out.appendLine("Car class not found: ${e.javaClass.simpleName}: ${e.message}")
            out.appendLine()
            return null
        }
        val instance = runCatching {
            carClass.getMethod("create", Context::class.java).invoke(null, app)
        }.getOrElse { e ->
            out.appendLine("Car.create(ctx) threw: ${describeError(e)}")
            out.appendLine()
            return null
        }
        if (instance == null) {
            out.appendLine("Car.create(ctx) returned null")
            out.appendLine()
            return null
        }
        out.appendLine("Car instance: ${instance.javaClass.name}")
        connectIfConnectable(instance, out)
        out.appendLine()
        return instance
    }

    private fun connectIfConnectable(carInstance: Any, out: StringBuilder) {
        runCatching {
            val connectableClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
            if (!connectableClass.isInstance(carInstance)) {
                out.appendLine("IConnectable: false")
                return
            }
            val latch = CountDownLatch(1)
            var connectedCallback = false
            val watcherClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val proxy = Proxy.newProxyInstance(
                watcherClass.classLoader,
                arrayOf(watcherClass),
            ) { proxyObj, method, args ->
                when (method.name) {
                    "onConnected" -> {
                        connectedCallback = true
                        latch.countDown()
                        null
                    }
                    "onDisConnected" -> null
                    "equals" -> proxyObj === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "toString" -> "GeelySignalProbeConnectWatcher"
                    else -> null
                }
            }
            connectableClass.getMethod("registerConnectWatcher", watcherClass).invoke(carInstance, proxy)
            connectableClass.getMethod("connect").invoke(carInstance)
            val waited = latch.await(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS)
            out.appendLine("IConnectable: true (connectedCallback=$connectedCallback, latchDone=$waited)")
        }.onFailure { e ->
            out.appendLine("IConnectable connect note: ${describeError(e)}")
        }
    }

    private fun discoverCarManagers(
        carInstance: Any,
        managers: MutableMap<String, Any>,
        seedClasses: MutableSet<Class<*>>,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 3. ICar MANAGERS DISCOVERY ===")
        val methods = collectUniqueZeroArgGetters(carInstance.javaClass)
        val safeManagerKeywords = listOf(
            "Manager", "Function", "Sensor", "Info", "Trip", "Hev", "Vehicle",
            "Diagnostics", "Energy", "Drive", "Cluster", "Dim", "Tire", "Tyre", "Tpms", "Fuel",
        )
        for (method in methods) {
            val name = method.name
            if (name == "getClass" || name == "getContext") continue
            val isCandidate = safeManagerKeywords.any { name.contains(it, ignoreCase = true) }
            val sig = "$name() -> ${method.returnType.name}"
            if (!isCandidate) {
                out.appendLine("  [skip] $sig")
                continue
            }
            val result = runCatching { method.invoke(carInstance) }
            if (result.isSuccess) {
                val obj = result.getOrNull()
                if (obj != null) {
                    managers[name] = obj
                    seedClasses.add(obj.javaClass)
                    val ifaces = collectAllInterfaces(obj.javaClass).map { it.name }
                    out.appendLine("  [ok]   $sig | impl=${obj.javaClass.name} | ifaces=$ifaces")
                } else {
                    out.appendLine("  [null] $sig")
                }
            } else {
                out.appendLine("  [err]  $sig | ${describeError(result.exceptionOrNull())}")
            }
        }
        highlights.add("ICar 可用管理器(${managers.size}): ${managers.keys.joinToString(", ").ifEmpty { "无" }}")
        out.appendLine()
    }

    private fun buildSensorEventMap(
        loadedCandidates: Map<String, Class<*>>,
        seedClasses: Set<Class<*>>,
    ): Map<Int, List<String>> {
        val map = LinkedHashMap<Int, MutableList<String>>()
        val eventClasses = LinkedHashSet<Class<*>>()
        loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.ISensorEvent"]?.let { eventClasses.add(it) }
        seedClasses.filter { it.simpleName.contains("SensorEvent", ignoreCase = true) }.forEach { eventClasses.add(it) }
        for (cls in eventClasses) {
            for (c in extractStaticIntConstants(cls)) {
                map.getOrPut(c.value) { mutableListOf() }.add(c.name)
            }
        }
        return map
    }

    private fun probeISensor(
        sensorManager: Any?,
        loadedCandidates: Map<String, Class<*>>,
        seedClasses: Set<Class<*>>,
        sensorEventMap: Map<Int, List<String>>,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 4. ISensor CONSTANTS & LIVE READINGS ===")
        val sensorClasses = LinkedHashSet<Class<*>>()
        loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.ISensor"]?.let { sensorClasses.add(it) }
        loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.ISensorGroup"]?.let { sensorClasses.add(it) }
        loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor"]?.let { sensorClasses.add(it) }
        if (sensorManager != null) {
            sensorClasses.add(sensorManager.javaClass)
            sensorClasses.addAll(collectAllInterfaces(sensorManager.javaClass))
        }
        seedClasses.filter { it.name.startsWith("com.ecarx.xui.adaptapi.car.sensor.") }.forEach { sensorClasses.add(it) }

        if (sensorManager != null) {
            out.appendLine("SensorManager impl: ${sensorManager.javaClass.name}")
            out.appendLine("ISensor methods:")
            dumpMethods(sensorManager.javaClass, out, indent = "  ")
        } else {
            out.appendLine("SensorManager is NULL")
        }

        val isSupportedMethod = sensorManager?.let { findMethod(it.javaClass, "isSensorSupported", Int::class.javaPrimitiveType) }
        val getLatestValueMethod = sensorManager?.let { findMethod(it.javaClass, "getSensorLatestValue", Int::class.javaPrimitiveType) }
        val getEventMethod = sensorManager?.let { findMethod(it.javaClass, "getSensorEvent", Int::class.javaPrimitiveType) }
        val getGroupValueMethod = sensorManager?.let {
            findMethod(it.javaClass, "getSensorGroupLatestValue", Int::class.javaPrimitiveType)
                ?: findMethod(it.javaClass, "getSensorGroupValue", Int::class.javaPrimitiveType)
        }

        // 4A. Sanity baseline check (known KX11 IDs)
        out.appendLine()
        out.appendLine("--- 4A. Baseline Known KX11 Sensor IDs ---")
        val baselineSummary = mutableListOf<String>()
        for ((label, id) in KNOWN_BASELINE_SENSORS) {
            val status = invokeSensorStatus(sensorManager, isSupportedMethod, id)
            val fVal = invokeSensorFloat(sensorManager, getLatestValueMethod, id)
            val eVal = invokeSensorEvent(sensorManager, getEventMethod, id)
            val eName = eVal?.let { sensorEventMap[it]?.joinToString("/") }.orEmpty()
            out.appendLine("  $label (0x${id.toHex8()} / $id): status=$status | value=${formatFloat(fVal)} | event=$eVal${if (eName.isNotEmpty()) " ($eName)" else ""}")
            baselineSummary.add("${label.removePrefix("BASELINE_")}=${formatCompactSensor(fVal, eVal)}")
        }
        highlights.add("基线传感器: ${baselineSummary.joinToString(", ")}")

        // 4B. Full ISensor constant enumeration
        val constantsByValue = LinkedHashMap<Int, MutableList<IntConstant>>()
        for (cls in sensorClasses) {
            if (cls.simpleName.contains("Event", ignoreCase = true)) continue
            for (c in extractStaticIntConstants(cls)) {
                if (c.name.startsWith("SENSOR_TYPE") || c.name.startsWith("VIRTUAL_SENSOR") || c.name.contains("SENSOR")) {
                    constantsByValue.getOrPut(c.value) { mutableListOf() }.add(c)
                }
            }
        }

        out.appendLine()
        out.appendLine("--- 4B. Enumerated ISensor Constants (unique IDs=${constantsByValue.size}) ---")
        var supportedCount = 0
        val tireHits = mutableListOf<String>()
        val tripFuelOdoHits = mutableListOf<String>()
        val otherEnvHits = mutableListOf<String>()

        for ((id, list) in constantsByValue.entries.sortedBy { it.key.toLong() and 0xFFFF_FFFFL }) {
            val primaryName = list.map { it.name }.distinct().joinToString(" | ")
            val status = invokeSensorStatus(sensorManager, isSupportedMethod, id)
            val supported = isStatusSupported(status)
            if (supported) supportedCount++

            val keywordMatched = matchesTelemetryKeyword(primaryName)
            val shouldRead = sensorManager != null && (supported || keywordMatched)
            if (shouldRead) {
                val fVal = invokeSensorFloat(sensorManager, getLatestValueMethod, id)
                val eVal = invokeSensorEvent(sensorManager, getEventMethod, id)
                val eName = eVal?.let { sensorEventMap[it]?.joinToString("/") }.orEmpty()
                val gVal = if (getGroupValueMethod != null && (primaryName.contains("GROUP", ignoreCase = true) || primaryName.contains("TRIP", ignoreCase = true))) {
                    runCatching { getGroupValueMethod.invoke(sensorManager, id)?.toString() }.getOrNull()
                } else {
                    null
                }
                val tag = if (supported) "SUP" else "KEY"
                val extraGroup = if (gVal != null) " | group=$gVal" else ""
                out.appendLine(
                    "  [$tag] 0x${id.toHex8()} ($id) $primaryName => status=$status | val=${formatFloat(fVal)} | evt=$eVal${if (eName.isNotEmpty()) "($eName)" else ""}$extraGroup",
                )
                if (supported) {
                    val shortEntry = "$primaryName(0x${id.toHex8()})=${formatCompactSensor(fVal, eVal)}"
                    when {
                        primaryName.contains("TIRE", ignoreCase = true) ||
                            primaryName.contains("TYRE", ignoreCase = true) ||
                            primaryName.contains("TPMS", ignoreCase = true) -> tireHits.add(shortEntry)
                        primaryName.contains("TRIP", ignoreCase = true) ||
                            primaryName.contains("ODO", ignoreCase = true) ||
                            primaryName.contains("FUEL", ignoreCase = true) ||
                            primaryName.contains("RANGE", ignoreCase = true) ||
                            primaryName.contains("ENDURANCE", ignoreCase = true) ||
                            primaryName.contains("MILE", ignoreCase = true) ||
                            primaryName.contains("CONSUMPTION", ignoreCase = true) -> tripFuelOdoHits.add(shortEntry)
                        primaryName.contains("TEMP", ignoreCase = true) ||
                            primaryName.contains("VOLT", ignoreCase = true) ||
                            primaryName.contains("BATTERY", ignoreCase = true) ||
                            primaryName.contains("COOLANT", ignoreCase = true) ||
                            primaryName.contains("OIL", ignoreCase = true) -> otherEnvHits.add(shortEntry)
                    }
                }
            } else {
                out.appendLine("  [---] 0x${id.toHex8()} ($id) $primaryName => status=$status")
            }
        }

        highlights.add("ISensor 常量总数=${constantsByValue.size}，实车支持(supported)=$supportedCount")
        highlights.add("ISensor 胎压/胎温命中(${tireHits.size}): ${tireHits.take(12).joinToString("; ").ifEmpty { "无" }}")
        highlights.add("ISensor 行程/里程/油耗/续航命中(${tripFuelOdoHits.size}): ${tripFuelOdoHits.take(12).joinToString("; ").ifEmpty { "无" }}")
        if (otherEnvHits.isNotEmpty()) {
            highlights.add("ISensor 温度/电瓶/冷却液命中(${otherEnvHits.size}): ${otherEnvHits.take(10).joinToString("; ")}")
        }

        // 4C. FULL IVirtualSensor constant enumeration (NO name filter — includes all 0x0040xxxx TYPE_* constants!)
        val virtualSensorClass = loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor"]
            ?: runCatching { Class.forName("com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor") }.getOrNull()
        out.appendLine()
        if (virtualSensorClass != null) {
            val vsByValue = LinkedHashMap<Int, MutableList<IntConstant>>()
            for (c in extractStaticIntConstants(virtualSensorClass)) {
                vsByValue.getOrPut(c.value) { mutableListOf() }.add(c)
            }
            out.appendLine("--- 4C. FULL IVirtualSensor Constants (unique IDs=${vsByValue.size}) ---")
            var vsSupportedCount = 0
            val vsTireHits = mutableListOf<String>()
            val vsTripFuelHits = mutableListOf<String>()
            val vsOtherSupported = mutableListOf<String>()

            for ((id, list) in vsByValue.entries.sortedBy { it.key.toLong() and 0xFFFF_FFFFL }) {
                val primaryName = list.map { it.name }.distinct().joinToString(" | ")
                val status = invokeSensorStatus(sensorManager, isSupportedMethod, id)
                val supported = isStatusSupported(status)
                if (supported) vsSupportedCount++

                val fVal = invokeSensorFloat(sensorManager, getLatestValueMethod, id)
                val eVal = invokeSensorEvent(sensorManager, getEventMethod, id)
                val eName = eVal?.let { sensorEventMap[it]?.joinToString("/") }.orEmpty()
                val tag = if (supported) "SUP" else "---"
                out.appendLine(
                    "  [$tag] 0x${id.toHex8()} ($id) $primaryName => status=$status | val=${formatFloat(fVal)} | evt=$eVal${if (eName.isNotEmpty()) "($eName)" else ""}",
                )
                if (supported) {
                    val entry = "$primaryName(0x${id.toHex8()})=${formatCompactSensor(fVal, eVal)}"
                    when {
                        primaryName.contains("TIRE", ignoreCase = true) ||
                            primaryName.contains("TYRE", ignoreCase = true) ||
                            primaryName.contains("TPMS", ignoreCase = true) -> vsTireHits.add(entry)
                        primaryName.contains("TRIP", ignoreCase = true) ||
                            primaryName.contains("FUEL", ignoreCase = true) ||
                            primaryName.contains("AVG", ignoreCase = true) ||
                            primaryName.contains("INSTANT", ignoreCase = true) ||
                            primaryName.contains("SPEED", ignoreCase = true) ||
                            primaryName.contains("MILE", ignoreCase = true) ||
                            primaryName.contains("ODO", ignoreCase = true) ||
                            primaryName.contains("RANGE", ignoreCase = true) ||
                            primaryName.contains("TIME", ignoreCase = true) ||
                            primaryName.contains("CONSUMPTION", ignoreCase = true) -> vsTripFuelHits.add(entry)
                        else -> vsOtherSupported.add(entry)
                    }
                }
            }
            highlights.add("IVirtualSensor 常量总数=${vsByValue.size}，实车支持(supported)=$vsSupportedCount")
            if (vsTireHits.isNotEmpty()) {
                highlights.add("IVirtualSensor 胎压/胎温命中(${vsTireHits.size}): ${vsTireHits.joinToString("; ")}")
            }
            if (vsTripFuelHits.isNotEmpty()) {
                highlights.add("IVirtualSensor 行程/油耗/速度命中(${vsTripFuelHits.size}): ${vsTripFuelHits.joinToString("; ")}")
            }
            if (vsOtherSupported.isNotEmpty()) {
                highlights.add("IVirtualSensor 其他支持项(${vsOtherSupported.size}): ${vsOtherSupported.take(12).joinToString("; ")}")
            }
        } else {
            out.appendLine("--- 4C. IVirtualSensor class not found ---")
        }

        // 4D. ISensorGroup live inspection for supported group sensors
        out.appendLine()
        out.appendLine("--- 4D. ISensorGroup Live Readings ---")
        val groupIds = constantsByValue.entries
            .filter { entry -> entry.value.any { it.name.startsWith("SENSOR_GROUP_TYPE") } }
            .sortedBy { it.key.toLong() and 0xFFFF_FFFFL }
        if (sensorManager != null && getGroupValueMethod != null && groupIds.isNotEmpty()) {
            for ((id, list) in groupIds) {
                val primaryName = list.map { it.name }.distinct().joinToString(" | ")
                val status = invokeSensorStatus(sensorManager, isSupportedMethod, id)
                if (!isStatusSupported(status)) continue
                val res = runCatching { getGroupValueMethod.invoke(sensorManager, id) }
                if (res.isSuccess) {
                    val gv = res.getOrNull()
                    out.appendLine("  [GRP] 0x${id.toHex8()} $primaryName => ${formatObjectShallow(gv)}")
                } else {
                    out.appendLine("  [GRP] 0x${id.toHex8()} $primaryName => ERR: ${describeError(res.exceptionOrNull())}")
                }
            }
        } else {
            out.appendLine("  (getSensorGroupLatestValue unavailable or no supported sensor groups)")
        }

        // 4E. ISensorEvent TPMS / Tire / Trip / Fuel / Temp constant names (to check if events exist)
        out.appendLine()
        out.appendLine("--- 4E. ISensorEvent Matching Constants (TIRE/TYRE/TPMS/PRESSURE/TRIP/FUEL/TEMP) ---")
        val eventCls = loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.ISensorEvent"]
        if (eventCls != null) {
            val matchedEvents = extractStaticIntConstants(eventCls)
                .filter { c ->
                    val u = c.name.uppercase(Locale.ROOT)
                    u.contains("TIRE") || u.contains("TYRE") || u.contains("TPMS") ||
                        u.contains("PRESSURE") || u.contains("TRIP") || u.contains("FUEL") ||
                        u.contains("TEMP") || u.contains("ODO")
                }
                .sortedBy { it.value.toLong() and 0xFFFF_FFFFL }
            out.appendLine("  matched=${matchedEvents.size}")
            for (c in matchedEvents.take(80)) {
                out.appendLine("  0x${c.value.toHex8()} (${c.value}) ${c.name}")
            }
        }

        // 4F. FULL ITireSensor (0x0050xxxx) live sampling via ISensor
        val tireSensorClass = loadedCandidates["com.ecarx.xui.adaptapi.car.sensor.ITireSensor"]
            ?: runCatching { Class.forName("com.ecarx.xui.adaptapi.car.sensor.ITireSensor") }.getOrNull()
        out.appendLine()
        if (tireSensorClass != null) {
            val tireByVal = LinkedHashMap<Int, MutableList<IntConstant>>()
            for (c in extractStaticIntConstants(tireSensorClass)) {
                tireByVal.getOrPut(c.value) { mutableListOf() }.add(c)
            }
            out.appendLine("--- 4F. FULL ITireSensor Live Readings (unique IDs=${tireByVal.size}) ---")
            val tireLiveHits = mutableListOf<String>()
            for ((id, list) in tireByVal.entries.sortedBy { it.key.toLong() and 0xFFFF_FFFFL }) {
                val primaryName = list.map { it.name }.distinct().joinToString(" | ")
                // Only query channel IDs (0x0050xx00) and report enum values separately
                val isChannel = (id and 0xFF) == 0 && (id ushr 16) == 0x0050
                if (!isChannel) {
                    out.appendLine("  [ENM] 0x${id.toHex8()} ($id) $primaryName")
                    continue
                }
                val status = invokeSensorStatus(sensorManager, isSupportedMethod, id)
                val supported = isStatusSupported(status)
                val fVal = invokeSensorFloat(sensorManager, getLatestValueMethod, id)
                val eVal = invokeSensorEvent(sensorManager, getEventMethod, id)
                val tag = if (supported) "SUP" else "---"
                out.appendLine("  [$tag] 0x${id.toHex8()} ($id) $primaryName => status=$status | val=${formatFloat(fVal)} | evt=$eVal")
                if (supported) {
                    tireLiveHits.add("$primaryName=${formatCompactSensor(fVal, eVal)}")
                }
            }
            highlights.add("ITireSensor(0x0050xxxx) 实车支持(${tireLiveHits.size}): ${tireLiveHits.take(12).joinToString("; ").ifEmpty { "无" }}")
        } else {
            out.appendLine("--- 4F. ITireSensor class not found ---")
        }
        out.appendLine()
    }

    private fun probeICarFunction(
        carFunction: Any?,
        loadedCandidates: Map<String, Class<*>>,
        seedClasses: Set<Class<*>>,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 5. ICarFunction TELEMETRY FUNCTION PROBE ===")
        if (carFunction == null) {
            out.appendLine("ICarFunction is NULL")
            out.appendLine()
            return
        }
        out.appendLine("ICarFunction impl: ${carFunction.javaClass.name}")
        out.appendLine("ICarFunction methods:")
        dumpMethods(carFunction.javaClass, out, indent = "  ")

        val isSup1 = findMethod(carFunction.javaClass, "isFunctionSupported", Int::class.javaPrimitiveType)
        val isSup2 = findMethod(carFunction.javaClass, "isFunctionSupported", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val getZones = findMethod(carFunction.javaClass, "getSupportedFunctionZones", Int::class.javaPrimitiveType)
        val getVal1 = findMethod(carFunction.javaClass, "getFunctionValue", Int::class.javaPrimitiveType)
        val getVal2 = findMethod(carFunction.javaClass, "getFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val getCust1 = findMethod(carFunction.javaClass, "getCustomizeFunctionValue", Int::class.javaPrimitiveType)
        val getCust2 = findMethod(carFunction.javaClass, "getCustomizeFunctionValue", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        // Gather vehicle function ID classes and wheel zone constants
        val fnClasses = LinkedHashSet<Class<*>>()
        loadedCandidates.values.forEach { cls ->
            if (!cls.name.contains(".sensor.")) fnClasses.add(cls)
        }
        seedClasses.forEach { cls ->
            if (cls.name.startsWith("com.ecarx.xui.adaptapi.car.") && !cls.name.contains(".sensor.")) {
                fnClasses.add(cls)
                fnClasses.addAll(collectAllInterfaces(cls))
            }
        }

        val wheelZones = LinkedHashMap<String, Int>()
        val matchedFunctions = LinkedHashMap<Int, IntConstant>()
        for (cls in fnClasses) {
            for (c in extractStaticIntConstants(cls)) {
                val upper = c.name.uppercase(Locale.ROOT)
                if (upper.contains("WHEEL") || upper.startsWith("ZONE_") || upper.contains("AREA_WHEEL")) {
                    wheelZones["${c.owner}.${c.name}"] = c.value
                }
                if (MUTATOR_NAME_PREFIXES.any { upper.startsWith(it) }) continue
                if (upper.startsWith("SENSOR_TYPE")) continue
                if (matchesTelemetryKeyword(upper) && (c.value >= 0x00100000 || upper.contains("FUNC") || upper.contains("TPMS") || upper.contains("TIRE") || upper.contains("TYRE") || upper.contains("TRIP"))) {
                    matchedFunctions.putIfAbsent(c.value, c)
                }
            }
        }

        out.appendLine()
        out.appendLine("Discovered wheel/zone constants (${wheelZones.size}):")
        wheelZones.entries.take(32).forEach { (name, v) ->
            out.appendLine("  $name = 0x${v.toHex8()} ($v)")
        }

        out.appendLine()
        out.appendLine("Matched candidate ICarFunction constants (${matchedFunctions.size}, probing up to $MAX_FUNCTION_PROBES):")
        val supportedFnHits = mutableListOf<String>()
        var probed = 0

        for ((id, c) in matchedFunctions.entries.sortedBy { it.key.toLong() and 0xFFFF_FFFFL }) {
            if (probed >= MAX_FUNCTION_PROBES) {
                out.appendLine("  ... truncated remaining ${matchedFunctions.size - probed} constants")
                break
            }
            probed++
            val status1 = isSup1?.let { runCatching { it.invoke(carFunction, id)?.toString() }.getOrElse { "ERR:${describeError(it)}" } }
            val zones = getZones?.let {
                runCatching {
                    when (val raw = it.invoke(carFunction, id)) {
                        is IntArray -> raw.toList()
                        is Array<*> -> raw.mapNotNull { item -> (item as? Number)?.toInt() }
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }.orEmpty()

            val supported = isStatusSupported(status1) || zones.isNotEmpty()
            if (!supported) {
                out.appendLine("  [---] 0x${id.toHex8()} ${c.owner}.${c.name} => status=$status1")
                continue
            }

            val v1 = getVal1?.let { runCatching { it.invoke(carFunction, id) }.getOrNull() }
            val cv1 = getCust1?.let { runCatching { it.invoke(carFunction, id) }.getOrNull() }
            val zoneReadings = mutableListOf<String>()
            val zonesToQuery = if (zones.isNotEmpty()) {
                zones.take(8)
            } else if (c.name.contains("TIRE", ignoreCase = true) || c.name.contains("TYRE", ignoreCase = true) || c.name.contains("TPMS", ignoreCase = true)) {
                wheelZones.values.distinct().take(6)
            } else {
                emptyList()
            }

            for (z in zonesToQuery) {
                val zStatus = isSup2?.let { runCatching { it.invoke(carFunction, id, z)?.toString() }.getOrNull() }
                val zv = getVal2?.let { runCatching { it.invoke(carFunction, id, z) }.getOrNull() }
                val zcv = getCust2?.let { runCatching { it.invoke(carFunction, id, z) }.getOrNull() }
                zoneReadings.add("z=0x${z.toHex8()}[st=$zStatus,v=$zv,cv=$zcv]")
            }

            val line = "  [SUP] 0x${id.toHex8()} ${c.owner}.${c.name} => status=$status1 | val=$v1 | cust=$cv1 | zones=${zones.map { "0x${it.toHex8()}" }} ${zoneReadings.joinToString(", ")}"
            out.appendLine(line)
            supportedFnHits.add("${c.name}(0x${id.toHex8()})=v:$v1/cv:$cv1${if (zoneReadings.isNotEmpty()) " ${zoneReadings.take(4)}" else ""}")
        }

        highlights.add("ICarFunction 车况相关命中(${supportedFnHits.size}): ${supportedFnHits.take(10).joinToString("; ").ifEmpty { "无" }}")
        out.appendLine()
    }

    private fun probeTripAndInfoManagers(
        managers: Map<String, Any>,
        loadedCandidates: Map<String, Class<*>>,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 6. ITripData / IHev / ICarInfo / IDiagnostics READ-ONLY INSPECTION ===")
        val tripObjects = LinkedHashMap<String, Any>()

        for ((mgrName, mgrObj) in managers) {
            if (mgrName.contains("Sensor", ignoreCase = true) || mgrName.contains("Function", ignoreCase = true)) {
                continue
            }
            out.appendLine("--- Manager: $mgrName (${mgrObj.javaClass.name}) ---")
            dumpMethods(mgrObj.javaClass, out, indent = "  ")

            val zeroArgGetters = collectUniqueZeroArgGetters(mgrObj.javaClass)
            for (m in zeroArgGetters) {
                val mName = m.name
                if (mName == "getClass" || mName == "getContext") continue
                val interesting = mName.contains("Trip", ignoreCase = true) ||
                    mName.contains("Tire", ignoreCase = true) ||
                    mName.contains("Tyre", ignoreCase = true) ||
                    mName.contains("Fuel", ignoreCase = true) ||
                    mName.contains("Odo", ignoreCase = true) ||
                    mName.contains("Mileage", ignoreCase = true) ||
                    mName.contains("Range", ignoreCase = true) ||
                    mName.contains("Info", ignoreCase = true) ||
                    mName.contains("Speed", ignoreCase = true) ||
                    mName.contains("Temp", ignoreCase = true) ||
                    mName.contains("Energy", ignoreCase = true) ||
                    mName.contains("Battery", ignoreCase = true) ||
                    mName.contains("Model", ignoreCase = true)
                if (!interesting) continue

                val res = runCatching { m.invoke(mgrObj) }
                if (res.isSuccess) {
                    val value = res.getOrNull()
                    out.appendLine("  call $mName() => ${formatAny(value)}")
                    if (value != null && mName.contains("Trip", ignoreCase = true) && !isSimpleValue(value)) {
                        tripObjects["$mgrName.$mName"] = value
                    }
                } else {
                    out.appendLine("  call $mName() => ERR: ${describeError(res.exceptionOrNull())}")
                }
            }
        }

        // 6A. Deep ITripData constant dump + 2-arg matrix probe
        val tripInterfaceCls = loadedCandidates["com.ecarx.xui.adaptapi.car.hev.ITripData"]
            ?: runCatching { Class.forName("com.ecarx.xui.adaptapi.car.hev.ITripData") }.getOrNull()
        for ((label, tripObj) in tripObjects) {
            out.appendLine()
            out.appendLine("--- 6A. Deep ITripData Probe: $label (${tripObj.javaClass.name}) ---")
            val tripConstClasses = LinkedHashSet<Class<*>>().apply {
                tripInterfaceCls?.let { add(it) }
                add(tripObj.javaClass)
                addAll(collectAllInterfaces(tripObj.javaClass))
            }
            val tripConstsByVal = LinkedHashMap<Int, MutableList<String>>()
            val allTripConsts = LinkedHashMap<String, Int>()
            for (cls in tripConstClasses) {
                for (c in extractStaticIntConstants(cls)) {
                    allTripConsts.putIfAbsent(c.name, c.value)
                    tripConstsByVal.getOrPut(c.value) { mutableListOf() }.let { list ->
                        if (c.name !in list) list.add(c.name)
                    }
                }
            }
            out.appendLine("  ITripData all int constants (${allTripConsts.size}):")
            for ((cName, cVal) in allTripConsts.entries.sortedBy { it.value.toLong() and 0xFFFF_FFFFL }) {
                out.appendLine("    0x${cVal.toHex8()} ($cVal) $cName")
            }

            // Zero-arg summary getters
            val tripGetters = collectUniqueZeroArgGetters(tripObj.javaClass)
            for (m in tripGetters) {
                if (m.name == "getClass" || m.name == "getECarXCar") continue
                val res = runCatching { m.invoke(tripObj) }
                val formatted = if (res.isSuccess) formatObjectShallow(res.getOrNull()) else "ERR:${describeError(res.exceptionOrNull())}"
                out.appendLine("  $label.${m.name}() => $formatted")
            }

            // 2-arg matrix: TRIP_TYPE_* x TRIP_INFO_TYPE_*
            val tripTypes = LinkedHashMap<String, Int>()
            allTripConsts.entries
                .filter { it.key.startsWith("TRIP_TYPE_") }
                .sortedBy { it.value }
                .forEach { tripTypes[it.key] = it.value }
            for (fallback in listOf(0, 1, 2)) {
                if (fallback !in tripTypes.values) tripTypes["TYPE_$fallback"] = fallback
            }

            val infoTypes = LinkedHashMap<String, Int>()
            allTripConsts.entries
                .filter { it.key.startsWith("TRIP_INFO_TYPE_") }
                .sortedBy { it.value }
                .forEach { infoTypes[it.key] = it.value }
            for ((fbName, fbVal) in listOf("INFO_0" to 0, "INFO_0x1000" to 0x1000, "INFO_0x2000" to 0x2000, "INFO_0x3000" to 0x3000)) {
                if (fbVal !in infoTypes.values) infoTypes[fbName] = fbVal
            }

            val candidateInfoIds = allTripConsts.entries
                .filter { (k, _) ->
                    k.startsWith("TRIP_DI_") || k.startsWith("TRIP_ED_") ||
                        k.startsWith("TRIP_AVG_") || k.startsWith("TRIP_DC_") ||
                        k.startsWith("TRIP_AIR_")
                }
                .map { it.value }
                .distinct()

            val isSup1 = findMethod(tripObj.javaClass, "isTripDataSupported", Int::class.javaPrimitiveType)
            val isSup2 = findMethod(tripObj.javaClass, "isTripDataSupported", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val getLatest2 = findMethod(tripObj.javaClass, "getLatestTripInfo", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val getArr2 = findMethod(tripObj.javaClass, "getTripInfo", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val tripMatrixHighlights = mutableListOf<String>()

            out.appendLine("  2-arg ITripData matrix (tripTypes=${tripTypes.size} x infoTypes=${infoTypes.size}):")
            for ((tName, tVal) in tripTypes) {
                val st1 = isSup1?.let { runCatching { it.invoke(tripObj, tVal)?.toString() }.getOrElse { "ERR:${describeError(it)}" } }
                out.appendLine("  * $tName($tVal): isTripDataSupported=$st1")
                for ((iName, iVal) in infoTypes) {
                    val st2 = isSup2?.let { runCatching { it.invoke(tripObj, tVal, iVal)?.toString() }.getOrElse { "ERR:${describeError(it)}" } }
                    val latestObj = getLatest2?.let { runCatching { it.invoke(tripObj, tVal, iVal) }.getOrNull() }
                    val arrRaw = getArr2?.let { runCatching { it.invoke(tripObj, tVal, iVal) }.getOrNull() }
                    val arrList = when (arrRaw) {
                        is Array<*> -> arrRaw.filterNotNull()
                        is List<*> -> arrRaw.filterNotNull()
                        else -> emptyList()
                    }
                    if (!isStatusSupported(st2) && latestObj == null && arrList.isEmpty()) {
                        out.appendLine("      [$tName x $iName(0x${iVal.toHex8()})] status=$st2 | latest=null | list=0")
                        continue
                    }
                    val latestDesc = formatTripInfoObject(latestObj, candidateInfoIds, tripConstsByVal)
                    out.appendLine("      [$tName x $iName(0x${iVal.toHex8()})] status=$st2 | latest=$latestDesc | listCount=${arrList.size}")
                    if (arrList.isNotEmpty()) {
                        val samples = if (arrList.size <= 3) arrList else listOf(arrList.first(), arrList[1], arrList.last())
                        samples.forEachIndexed { idx, item ->
                            out.appendLine("        item[$idx]: ${formatTripInfoObject(item, candidateInfoIds, tripConstsByVal)}")
                        }
                    }
                    if (latestObj != null || arrList.isNotEmpty()) {
                        tripMatrixHighlights.add("$tName/$iName:$latestDesc(n=${arrList.size})")
                    }
                }
            }

            // Read-only inspect TRIP_FUNC_DRIVING_COMPUTER (0x24800600) supported values & property
            val dcFuncId = allTripConsts["TRIP_FUNC_DRIVING_COMPUTER"] ?: 0x24800600
            val getSupFnVal = findMethod(tripObj.javaClass, "getSupportedFunctionValue", Int::class.javaPrimitiveType)
            val getProp1 = findMethod(tripObj.javaClass, "getProperty", Int::class.javaPrimitiveType)
            val dcSupVals = getSupFnVal?.let { runCatching { formatAny(it.invoke(tripObj, dcFuncId)) }.getOrNull() }
            val dcProp = getProp1?.let { runCatching { formatObjectShallow(it.invoke(tripObj, dcFuncId)) }.getOrNull() }
            out.appendLine("  TRIP_FUNC_DRIVING_COMPUTER(0x${dcFuncId.toHex8()}): supportedValues=$dcSupVals | property=$dcProp")

            highlights.add(
                "ITripData 双参探测(${tripMatrixHighlights.size}): " +
                    tripMatrixHighlights.take(6).joinToString("; ").ifEmpty { "无非空 ITripInfo（见第 6A 节）" },
            )
        }

        // 6B. Dedicated ICarInfo read-only probe
        val carInfo = managers["getCarInfoManager"]
        if (carInfo != null) {
            out.appendLine()
            out.appendLine("--- 6B. Dedicated ICarInfo Read-Only Probe ---")
            val carInfoClasses = LinkedHashSet<Class<*>>().apply {
                loadedCandidates["com.ecarx.xui.adaptapi.car.base.ICarInfo"]?.let { add(it) }
                add(carInfo.javaClass)
                addAll(collectAllInterfaces(carInfo.javaClass))
            }
            val isInfoSup = findMethod(carInfo.javaClass, "isCarInfoSupported", Int::class.javaPrimitiveType)
            val getInfoInt = findMethod(carInfo.javaClass, "getCarInfoInt", Int::class.javaPrimitiveType)
            val getInfoInts = findMethod(carInfo.javaClass, "getCarInfoInts", Int::class.javaPrimitiveType)
            val getInfoFlt = findMethod(carInfo.javaClass, "getCarInfoFloat", Int::class.javaPrimitiveType)
            val getInfoStr = findMethod(carInfo.javaClass, "getCarInfoString", Int::class.javaPrimitiveType)
            val getInfoCfg = findMethod(carInfo.javaClass, "getCarInfoConfig", Int::class.javaPrimitiveType)

            val infoConsts = LinkedHashMap<Int, IntConstant>()
            for (cls in carInfoClasses) {
                for (c in extractStaticIntConstants(cls)) {
                    val u = c.name.uppercase(Locale.ROOT)
                    if (u.startsWith("INT_INFO_") || u.startsWith("INTS_INFO_") ||
                        u.startsWith("FLT_INFO_") || u.startsWith("STR_INFO_") ||
                        u.startsWith("MAP_INFO_") || u.startsWith("CONFIG_INFO_")
                    ) {
                        infoConsts.putIfAbsent(c.value, c)
                    }
                }
            }
            val carInfoHighlights = mutableListOf<String>()
            for ((id, c) in infoConsts.entries.sortedBy { it.key.toLong() and 0xFFFF_FFFFL }) {
                val u = c.name.uppercase(Locale.ROOT)
                // Skip potential PII identifiers such as VIN / serial / IMEI / MAC
                if (u.contains("VIN") || u.contains("IMEI") || u.contains("ICCID") ||
                    u.contains("SERIAL") || u.contains("MAC") || u.contains("IHU_ID")
                ) {
                    out.appendLine("  [PII] 0x${id.toHex8()} ${c.name} => skipped")
                    continue
                }
                val st = isInfoSup?.let { runCatching { it.invoke(carInfo, id)?.toString() }.getOrElse { "ERR:${describeError(it)}" } }
                val sup = isStatusSupported(st)
                if (!sup && !matchesTelemetryKeyword(u)) {
                    out.appendLine("  [---] 0x${id.toHex8()} ${c.name} => status=$st")
                    continue
                }
                val rawVal = when {
                    u.startsWith("INT_INFO_") -> getInfoInt?.let { runCatching { formatAny(it.invoke(carInfo, id)) }.getOrElse { "ERR:${describeError(it)}" } }
                    u.startsWith("INTS_INFO_") -> getInfoInts?.let { runCatching { formatAny(it.invoke(carInfo, id)) }.getOrElse { "ERR:${describeError(it)}" } }
                    u.startsWith("FLT_INFO_") -> getInfoFlt?.let { runCatching { formatAny(it.invoke(carInfo, id)) }.getOrElse { "ERR:${describeError(it)}" } }
                    u.startsWith("STR_INFO_") -> getInfoStr?.let { runCatching { formatAny(it.invoke(carInfo, id)) }.getOrElse { "ERR:${describeError(it)}" } }
                    u.startsWith("CONFIG_INFO_") -> getInfoCfg?.let { runCatching { formatAny(it.invoke(carInfo, id)) }.getOrElse { "ERR:${describeError(it)}" } }
                    else -> "(map/other)"
                }
                val tag = if (sup) "SUP" else "KEY"
                out.appendLine("  [$tag] 0x${id.toHex8()} ${c.name} => status=$st | val=$rawVal")
                if (sup) {
                    carInfoHighlights.add("${c.name}=$rawVal")
                }
            }
            highlights.add("ICarInfo 实车支持(${carInfoHighlights.size}): ${carInfoHighlights.take(10).joinToString("; ").ifEmpty { "无" }}")
        }

        out.appendLine()
    }

    private fun formatTripInfoObject(
        infoObj: Any?,
        candidateInfoIds: List<Int>,
        tripConstsByVal: Map<Int, List<String>>,
    ): String {
        if (infoObj == null) return "null"
        val parts = mutableListOf<String>()
        val cls = infoObj.javaClass
        val getTripType = findMethod(cls, "getTripType")
        val getInfoType = findMethod(cls, "getInfoType")
        val getFreq = findMethod(cls, "getFrequencyUnit")
        val getIds = findMethod(cls, "getContainsInfoIds")
        val containsId = findMethod(cls, "containsInfoId", Int::class.javaPrimitiveType)
        val getVal = findMethod(cls, "getInfoValue", Int::class.javaPrimitiveType)

        getTripType?.let { runCatching { it.invoke(infoObj) }.getOrNull() }?.let { parts.add("tripType=$it") }
        getInfoType?.let { runCatching { it.invoke(infoObj) }.getOrNull() }?.let { parts.add("infoType=$it") }
        getFreq?.let { runCatching { it.invoke(infoObj) }.getOrNull() }?.let { parts.add("freq=$it") }

        val ids = LinkedHashSet<Int>()
        getIds?.let {
            runCatching {
                when (val raw = it.invoke(infoObj)) {
                    is IntArray -> raw.forEach { id -> ids.add(id) }
                    is Array<*> -> raw.forEach { item -> (item as? Number)?.toInt()?.let(ids::add) }
                }
            }
        }
        if (containsId != null) {
            for (cid in candidateInfoIds) {
                val has = runCatching { containsId.invoke(infoObj, cid) as? Boolean }.getOrDefault(false)
                if (has == true) ids.add(cid)
            }
        }
        if (getVal != null && ids.isNotEmpty()) {
            val kv = ids.map { id ->
                val name = tripConstsByVal[id]?.firstOrNull() ?: "0x${id.toHex8()}"
                val v = runCatching { (getVal.invoke(infoObj, id) as? Number)?.toFloat() }.getOrNull()
                "$name=${formatFloat(v)}"
            }
            parts.add("values={${kv.joinToString(", ")}}")
        }
        val shallow = formatObjectShallow(infoObj)
        return if (parts.isEmpty()) shallow else "${cls.simpleName}[${parts.joinToString(", ")}] | $shallow"
    }

    private fun probeUnderlyingEcarxCarAndFrameworkJars(
        sensorManager: Any?,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 7B. UNDERLYING ECarXCar / ecarx.car.jar & FRAMEWORK JAR SCAN ===")
        if (sensorManager != null) {
            val getEcarxCar = findMethod(sensorManager.javaClass, "getECarXCar")
            val ecarxCar = getEcarxCar?.let { runCatching { it.invoke(sensorManager) }.getOrNull() }
            if (ecarxCar != null) {
                out.appendLine("ECarXCar impl: ${ecarxCar.javaClass.name}")
                dumpMethods(ecarxCar.javaClass, out, indent = "  ")
                // Inspect any read-only zero-arg manager getters on ECarXCar (e.g. getCarSignalManager)
                for (m in collectUniqueZeroArgGetters(ecarxCar.javaClass)) {
                    if (m.name == "getClass" || m.name == "getContext") continue
                    if (m.name.contains("Signal", ignoreCase = true) ||
                        m.name.contains("Sensor", ignoreCase = true) ||
                        m.name.contains("Car", ignoreCase = true) ||
                        m.name.contains("Tire", ignoreCase = true) ||
                        m.name.contains("Tpms", ignoreCase = true) ||
                        m.name.contains("Trip", ignoreCase = true)
                    ) {
                        val sub = runCatching { m.invoke(ecarxCar) }.getOrNull()
                        if (sub != null && !isSimpleValue(sub)) {
                            out.appendLine("  ECarXCar.${m.name}() => ${sub.javaClass.name}")
                            dumpMethods(sub.javaClass, out, indent = "    ")
                        }
                    }
                }
            } else {
                out.appendLine("ECarXCar: null")
            }

            val getSigFilter = findMethod(sensorManager.javaClass, "getSignalFilter")
            val getPaFilter = findMethod(sensorManager.javaClass, "getPAFilter")
            getSigFilter?.let { runCatching { it.invoke(sensorManager) }.getOrNull() }?.let {
                out.appendLine("SensorNew.getSignalFilter() => ${formatObjectShallow(it)}")
            }
            getPaFilter?.let { runCatching { it.invoke(sensorManager) }.getOrNull() }?.let {
                out.appendLine("SensorNew.getPAFilter() => ${formatObjectShallow(it)}")
            }
        }

        // Read-only DEX class-name scan of ECARX framework jars to discover any hidden TPMS/Tire/Trip classes
        val jarPaths = listOf(
            "/system/framework/ecarx.adaptapi.jar",
            "/system/framework/ecarx.car.jar",
            "/system/framework/ecarx.jar",
        )
        val discoveredTpmsOrTripClasses = LinkedHashSet<String>()
        val dexFileClass = runCatching { Class.forName("dalvik.system.DexFile") }.getOrNull()
        val dexCtor = dexFileClass?.let { runCatching { it.getConstructor(String::class.java) }.getOrNull() }
        val entriesMethod = dexFileClass?.let { runCatching { it.getMethod("entries") }.getOrNull() }
        val closeMethod = dexFileClass?.let { runCatching { it.getMethod("close") }.getOrNull() }

        for (jarPath in jarPaths) {
            val file = File(jarPath)
            out.appendLine()
            out.appendLine("--- Framework JAR: $jarPath (exists=${file.exists()}, canRead=${file.canRead()}, size=${file.length()}) ---")
            if (!file.exists() || !file.canRead() || dexCtor == null || entriesMethod == null) continue
            val matchedClasses = mutableListOf<String>()
            runCatching {
                val dexObj = dexCtor.newInstance(jarPath)
                try {
                    val enumObj = entriesMethod.invoke(dexObj) as? java.util.Enumeration<*>
                    while (enumObj != null && enumObj.hasMoreElements()) {
                        val clsName = enumObj.nextElement()?.toString() ?: continue
                        val upper = clsName.uppercase(Locale.ROOT)
                        if (upper.contains("TIRE") || upper.contains("TYRE") || upper.contains("TPMS") ||
                            upper.contains("TRIP") || upper.contains("FUEL") || upper.contains("ODO") ||
                            upper.contains("SENSOR") || upper.contains("SIGNAL") || upper.contains("CARINFO") ||
                            upper.contains("VEHICLE") || upper.contains("CLUSTER") || upper.contains("DIM") ||
                            upper.contains("DRIVING")
                        ) {
                            matchedClasses.add(clsName)
                            if (upper.contains("TIRE") || upper.contains("TYRE") || upper.contains("TPMS") ||
                                upper.contains("TRIP") || upper.contains("SIGNALID") || upper.contains("CARSIGNAL") ||
                                upper.contains("SENSOR")
                            ) {
                                discoveredTpmsOrTripClasses.add(clsName)
                            }
                        }
                    }
                } finally {
                    runCatching { closeMethod?.invoke(dexObj) }
                }
            }.onFailure { e ->
                out.appendLine("  DexFile scan unavailable: ${describeError(e)}")
            }
            out.appendLine("  Matched class names (${matchedClasses.size}, showing up to 140):")
            matchedClasses.sorted().take(140).forEach { out.appendLine("    $it") }
        }

        // Inspect static int constants of any newly discovered Tire/TPMS/Signal classes
        val tpmsSignalHits = mutableListOf<String>()
        val targetInspect = discoveredTpmsOrTripClasses
            .filter { name ->
                val u = name.uppercase(Locale.ROOT)
                !name.contains("$") && (
                    u.contains("TIRE") || u.contains("TYRE") || u.contains("TPMS") ||
                        u.contains("SIGNAL") || u.contains("CARSENSOR")
                    )
            }
            .sorted()
            .take(24)
        if (targetInspect.isNotEmpty()) {
            out.appendLine()
            out.appendLine("--- Discovered TPMS / Signal Classes Constant Inspection (${targetInspect.size} classes) ---")
            for (clsName in targetInspect) {
                val cls = runCatching { Class.forName(clsName) }.getOrNull() ?: continue
                val consts = extractStaticIntConstants(cls).filter { c ->
                    val u = c.name.uppercase(Locale.ROOT)
                    u.contains("TIRE") || u.contains("TYRE") || u.contains("TPMS") ||
                        u.contains("PRESSURE") || u.contains("TEMP") || u.contains("TRIP") ||
                        u.contains("FUEL") || u.contains("ODO") || u.contains("RANGE") ||
                        u.contains("ENDURANCE") || u.contains("MILE") || u.contains("CONSUMPTION") ||
                        u.contains("AVG") || u.contains("INSTANT") || u.contains("WHEEL")
                }
                if (consts.isEmpty()) continue
                out.appendLine("  Class $clsName (matchedIntConsts=${consts.size}):")
                for (c in consts.sortedBy { it.value.toLong() and 0xFFFF_FFFFL }.take(120)) {
                    out.appendLine("    0x${c.value.toHex8()} (${c.value}) ${c.name}")
                    val u = c.name.uppercase(Locale.ROOT)
                    if (u.contains("TIRE") || u.contains("TYRE") || u.contains("TPMS")) {
                        tpmsSignalHits.add("${cls.simpleName}.${c.name}(0x${c.value.toHex8()})")
                    }
                }
            }
        }
        if (tpmsSignalHits.isNotEmpty()) {
            highlights.add("ecarx.car/adaptapi 隐藏胎压常量发现(${tpmsSignalHits.size}): ${tpmsSignalHits.take(12).joinToString("; ")}")
        } else if (discoveredTpmsOrTripClasses.isNotEmpty()) {
            highlights.add("Framework JAR 扫描命中相关类 ${discoveredTpmsOrTripClasses.size} 个（见第 7B 节）")
        }
        out.appendLine()
    }

    private fun walkTypeGraph(seeds: Set<Class<*>>): List<Class<*>> {
        val visited = LinkedHashMap<String, Class<*>>()
        val queue = ArrayDeque<Pair<Class<*>, Int>>()
        for (cls in seeds) {
            queue.add(cls to 0)
        }
        while (queue.isNotEmpty() && visited.size < MAX_TYPE_GRAPH_CLASSES) {
            val (cls, depth) = queue.removeFirst()
            val name = cls.name
            if (!name.startsWith("com.ecarx.") && !name.startsWith("com.geely.")) continue
            if (visited.containsKey(name)) continue
            visited[name] = cls
            if (depth >= 2) continue

            for (iface in cls.interfaces) {
                queue.add(iface to depth)
            }
            cls.superclass?.let { sup ->
                if (sup.name.startsWith("com.ecarx.") || sup.name.startsWith("com.geely.")) {
                    queue.add(sup to depth)
                }
            }
            runCatching { cls.methods }.getOrNull()?.forEach { method ->
                val ret = method.returnType
                if (ret.name.startsWith("com.ecarx.") || ret.name.startsWith("com.geely.")) {
                    queue.add(ret to (depth + 1))
                }
                method.parameterTypes.forEach { pt ->
                    if (pt.name.startsWith("com.ecarx.") || pt.name.startsWith("com.geely.")) {
                        queue.add(pt to (depth + 1))
                    }
                }
            }
        }
        return visited.values.toList()
    }

    private fun dumpTypeGraph(classes: List<Class<*>>, out: StringBuilder) {
        out.appendLine("=== 7. DISCOVERED ECARX / GEELY API SURFACE (${classes.size} classes) ===")
        for (cls in classes.sortedBy { it.name }) {
            val methods = runCatching {
                cls.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .sortedBy { it.name }
            }.getOrDefault(emptyList())
            val matchingConsts = extractStaticIntConstants(cls).filter { matchesTelemetryKeyword(it.name) }
            out.appendLine("Class: ${cls.name} (declaredPublicMethods=${methods.size}, telemetryIntConsts=${matchingConsts.size})")
            methods.take(40).forEach { m ->
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                out.appendLine("    ${m.name}($params): ${m.returnType.simpleName}")
            }
            if (methods.size > 40) {
                out.appendLine("    ... (+${methods.size - 40} more methods)")
            }
        }
        out.appendLine()
    }

    private fun probeAndroidCarProperties(
        app: Context,
        out: StringBuilder,
        highlights: MutableList<String>,
    ) {
        out.appendLine("=== 8. STANDARD ANDROID CAR (android.car.Car) CHECK ===")
        val carClass = runCatching { Class.forName("android.car.Car") }.getOrElse { e ->
            out.appendLine("android.car.Car not present: ${e.javaClass.simpleName}")
            out.appendLine()
            return
        }
        var androidCar: Any? = null
        try {
            val createNoConn = runCatching {
                carClass.getMethod("createCar", Context::class.java)
            }.getOrNull()
            if (createNoConn != null) {
                androidCar = runCatching { createNoConn.invoke(null, app) }.getOrNull()
            }
            if (androidCar == null) {
                out.appendLine("android.car.Car present, but createCar(Context) returned null or requires ServiceConnection")
                out.appendLine()
                return
            }
            val propMgr = runCatching {
                carClass.getMethod("getCarManager", String::class.java).invoke(androidCar, "property")
            }.getOrNull()
            if (propMgr == null) {
                out.appendLine("android.car.Car connected, CarPropertyManager unavailable")
                out.appendLine()
                return
            }
            val list = runCatching {
                propMgr.javaClass.getMethod("getPropertyList").invoke(propMgr) as? List<*>
            }.getOrElse { e ->
                out.appendLine("CarPropertyManager.getPropertyList() failed: ${describeError(e)}")
                out.appendLine()
                return
            }.orEmpty()
            out.appendLine("CarPropertyManager property count: ${list.size}")
            highlights.add("Android VHAL CarPropertyManager 可访问，属性总数=${list.size}")
            list.take(80).forEach { cfg ->
                out.appendLine("  $cfg")
            }
        } catch (t: Throwable) {
            out.appendLine("android.car.Car probe error: ${describeError(t)}")
        } finally {
            if (androidCar != null) {
                runCatching { carClass.getMethod("disconnect").invoke(androidCar) }
            }
        }
        out.appendLine()
    }

    private fun extractStaticIntConstants(cls: Class<*>): List<IntConstant> {
        val result = mutableListOf<IntConstant>()
        val fields = runCatching { cls.fields }.getOrDefault(emptyArray())
        for (field in fields) {
            val mods = field.modifiers
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) continue
            if (field.type != Int::class.javaPrimitiveType) continue
            runCatching {
                val value = field.getInt(null)
                result.add(IntConstant(cls.simpleName, field.name, value))
            }
        }
        return result
    }

    private fun collectUniqueZeroArgGetters(cls: Class<*>): List<Method> {
        val byName = LinkedHashMap<String, Method>()
        // Prefer public interface methods first so reflection never hits IllegalAccessException
        // on package-private ECARX AdaptAPI implementation classes.
        val candidates = ArrayList<Class<*>>()
        candidates.addAll(collectAllInterfaces(cls).filter { Modifier.isPublic(it.modifiers) })
        candidates.add(cls)
        for (owner in candidates) {
            val allMethods = runCatching { owner.methods }.getOrDefault(emptyArray())
            for (m in allMethods) {
                if (!Modifier.isPublic(m.modifiers) || Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isNotEmpty()) continue
                if (!m.name.startsWith("get") && !m.name.startsWith("is")) continue
                byName.putIfAbsent(m.name, m)
            }
        }
        return byName.values.sortedBy { it.name }
    }

    private fun collectAllInterfaces(cls: Class<*>): Set<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        val q = ArrayDeque<Class<*>>()
        q.add(cls)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (iface in cur.interfaces) {
                if (out.add(iface)) q.add(iface)
            }
            cur.superclass?.let { sup ->
                if (sup != Any::class.java) q.add(sup)
            }
        }
        return out
    }

    private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>?): Method? {
        val nonNull = params.filterNotNull().toTypedArray()
        for (iface in collectAllInterfaces(cls)) {
            if (!Modifier.isPublic(iface.modifiers)) continue
            runCatching { iface.getMethod(name, *nonNull) }.getOrNull()?.let { return it }
        }
        return runCatching {
            cls.getMethod(name, *nonNull).apply { runCatching { isAccessible = true } }
        }.getOrNull()
    }

    private fun dumpMethods(cls: Class<*>, out: StringBuilder, indent: String) {
        val methods = runCatching {
            cls.methods
                .filter { it.declaringClass != Any::class.java && !it.isSynthetic }
                .distinctBy { "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.name }})" }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
        for (m in methods) {
            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
            out.appendLine("$indent${m.name}($params): ${m.returnType.simpleName}")
        }
    }

    private fun invokeSensorStatus(sensor: Any?, method: Method?, id: Int): String {
        if (sensor == null || method == null) return "N/A"
        return runCatching { method.invoke(sensor, id)?.toString() ?: "null" }
            .getOrElse { "ERR:${describeError(it)}" }
    }

    private fun invokeSensorFloat(sensor: Any?, method: Method?, id: Int): Float? {
        if (sensor == null || method == null) return null
        return runCatching { (method.invoke(sensor, id) as? Number)?.toFloat() }.getOrNull()
    }

    private fun invokeSensorEvent(sensor: Any?, method: Method?, id: Int): Int? {
        if (sensor == null || method == null) return null
        return runCatching { (method.invoke(sensor, id) as? Number)?.toInt() }.getOrNull()
    }

    private fun isStatusSupported(status: String?): Boolean {
        if (status.isNullOrBlank()) return false
        val low = status.lowercase(Locale.ROOT)
        return low != "notavailable" &&
            low != "notsupported" &&
            low != "error" &&
            low != "null" &&
            low != "n/a" &&
            !low.startsWith("err:")
    }

    private fun matchesTelemetryKeyword(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        return TELEMETRY_KEYWORDS.any { upper.contains(it) }
    }

    private fun formatFloat(value: Float?): String = when {
        value == null -> "null"
        value.isNaN() -> "NaN"
        value.isInfinite() -> value.toString()
        else -> String.format(Locale.US, "%.4f", value)
    }

    private fun formatCompactSensor(fVal: Float?, eVal: Int?): String {
        val parts = mutableListOf<String>()
        if (fVal != null && fVal.isFinite()) {
            parts.add(String.format(Locale.US, "%.2f", fVal))
        }
        if (eVal != null && eVal != 0 && eVal != -1) {
            parts.add("evt=$eVal")
        }
        return if (parts.isEmpty()) "${formatFloat(fVal)}/evt=$eVal" else parts.joinToString(",")
    }

    private fun isSimpleValue(value: Any): Boolean =
        value is CharSequence || value is Number || value is Boolean || value.javaClass.isEnum

    private fun formatAny(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }

    private fun formatObjectShallow(value: Any?): String {
        if (value == null) return "null"
        if (isSimpleValue(value)) return value.toString()
        if (value is IntArray || value is FloatArray || value is DoubleArray || value is Array<*>) {
            return formatAny(value)
        }
        val fields = mutableListOf<String>()
        runCatching {
            value.javaClass.fields
                .filter { !Modifier.isStatic(it.modifiers) }
                .take(16)
                .forEach { f ->
                    val fv = runCatching { f.get(value) }.getOrNull()
                    fields.add("${f.name}=${formatAny(fv)}")
                }
        }
        if (fields.isNotEmpty()) {
            return "${value.javaClass.simpleName}{${fields.joinToString(", ")}}"
        }
        val getters = collectUniqueZeroArgGetters(value.javaClass)
            .filter { it.name != "getClass" && it.returnType != Void.TYPE }
            .take(16)
        val getterVals = mutableListOf<String>()
        for (g in getters) {
            val gv = runCatching { g.invoke(value) }.getOrNull()
            if (gv == null || isSimpleValue(gv)) {
                getterVals.add("${g.name}=${formatAny(gv)}")
            }
        }
        return if (getterVals.isNotEmpty()) {
            "${value.javaClass.simpleName}(${getterVals.joinToString(", ")})"
        } else {
            value.toString()
        }
    }

    private fun describeError(error: Throwable?): String {
        if (error == null) return "unknown"
        val root = error.cause ?: error
        val msg = root.message?.take(120)
        return if (msg.isNullOrBlank()) root.javaClass.simpleName else "${root.javaClass.simpleName}: $msg"
    }

    private fun Int.toHex8(): String = String.format(Locale.US, "%08X", this)

    internal fun formatBytes(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * Self-contained modal dialog for running [GeelyVehicleSignalProbe] on the head unit, inspecting
 * the read-only probe report, saving/copying locally, and uploading directly to Cloudflare R2.
 */
internal object GeelyVehicleSignalProbeUi {
    fun showProbeDialog(activity: Activity) {
        val dialogContext = activity.appDialogContext()
        val density = dialogContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        var latestReport: GeelyVehicleSignalProbe.ProbeReport? = null
        var running = false
        var uploading = false

        val root = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }

        val topBar = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusView = TextView(dialogContext).apply {
            text = "正在以只读方式探测原车 ECARX 传感器与功能接口，请稍候…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        topBar.addView(
            statusView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            },
        )

        val rerunButton = Button(dialogContext).apply {
            text = "重新探测"
            isAllCaps = false
            isEnabled = false
        }
        topBar.addView(
            rerunButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            topBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )

        val reportView = TextView(dialogContext).apply {
            text = "提示：\n" +
                "• 本次探测为 100% 只读操作，不会修改任何车辆设置。\n" +
                "• 建议在车辆通电（最好已行驶一小段唤醒胎压传感器）时点击「重新探测」以获取最完整的实时数值。\n" +
                "• 探测完成后可直接点击「上传到 R2」或「保存并复制」导出完整报告。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val scrollView = ScrollView(dialogContext).apply {
            addView(
                reportView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val maxScrollHeight = (activity.resources.displayMetrics.heightPixels * 0.56f)
            .roundToInt()
            .coerceIn(dp(240), dp(520))
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight),
        )

        val dialog = activity.appDialogBuilder()
            .setTitle("原车车况信号探测（只读）")
            .setView(root)
            .setPositiveButton("上传到 R2", null)
            .setNeutralButton("保存并复制", null)
            .setNegativeButton("关闭", null)
            .create()

        fun updateActionStates() {
            val hasReport = latestReport != null
            rerunButton.isEnabled = !running && !uploading
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = hasReport && !running && !uploading
                text = if (uploading) "上传中…" else "上传到 R2"
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = hasReport && !running
        }

        fun startProbe() {
            if (running) return
            running = true
            statusView.text = "正在以只读方式探测原车传感器与接口…"
            updateActionStates()
            val app = activity.applicationContext
            Thread({
                val result = runCatching { GeelyVehicleSignalProbe.runProbe(app) }
                activity.runOnUiThread {
                    running = false
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (result.isSuccess) {
                        val report = result.getOrThrow()
                        latestReport = report
                        statusView.text = report.summaryLine
                        reportView.text = report.fullText
                        scrollView.scrollTo(0, 0)
                    } else {
                        val err = result.exceptionOrNull()
                        statusView.text = "探测失败：${err?.javaClass?.simpleName ?: "unknown"}"
                        reportView.text = err?.stackTraceToString() ?: "未知错误"
                    }
                    updateActionStates()
                }
            }, "geely-signal-probe").start()
        }

        rerunButton.setOnClickListener { startProbe() }

        dialog.show()
        updateActionStates()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val report = latestReport ?: return@setOnClickListener
            if (uploading) return@setOnClickListener
            val cfg = DiagnosticR2Uploader.loadConfig(activity)
            if (!cfg.isConfigured) {
                Toast.makeText(activity, "请先在「设置 → 诊断 → R2 上传配置」填写上传地址", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            uploading = true
            statusView.text = "正在 Gzip 压缩并上传探测报告到 R2…"
            updateActionStates()
            val app = activity.applicationContext
            Thread({
                val outcome = runCatching {
                    DiagnosticR2Uploader.upload(app, report.fileName, report.fullText)
                }
                activity.runOnUiThread {
                    uploading = false
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    if (outcome.isSuccess) {
                        val uploaded = outcome.getOrThrow()
                        val msg = "已上传到 R2：${uploaded.objectKey}（${GeelyVehicleSignalProbe.formatBytes(uploaded.compressedBytes)}）"
                        statusView.text = msg
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val err = outcome.exceptionOrNull()
                        val msg = "R2 上传失败：${err?.message ?: err?.javaClass?.simpleName ?: "unknown"}"
                        statusView.text = msg
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                    updateActionStates()
                }
            }, "geely-signal-probe-r2").start()
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val report = latestReport ?: return@setOnClickListener
            val savedFile = runCatching {
                val baseDir = activity.getExternalFilesDir("diagnostics") ?: activity.filesDir
                if (!baseDir.exists()) baseDir.mkdirs()
                File(baseDir, report.fileName).also { it.writeText(report.fullText, Charsets.UTF_8) }
            }.getOrNull()

            runCatching {
                val cm = activity.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText(report.fileName, report.fullText))
            }

            val msg = if (savedFile != null) {
                "已复制并保存至：${savedFile.absolutePath}"
            } else {
                "已复制到剪贴板（本地文件写入失败）"
            }
            statusView.text = msg
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }

        startProbe()
    }
}
