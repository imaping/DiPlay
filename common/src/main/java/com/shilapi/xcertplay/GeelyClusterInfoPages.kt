package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure-vector instrument cluster info pages (`1920x720` center safe zone `X: 600..1320`) matching
 * the active Geely Fake Ultra theme accent, font, and day/night palette:
 * - Current Trip (`本次行程`: distance, duration, avg speed, trip avg fuel, instant fuel)
 * - Tire Pressure & Temp (`胎压与胎温`: top-down vector car + 4-wheel kPa / °C + alert highlights)
 * - Vehicle Overview (`车辆综合信息`: odometer, total avg fuel, range/fuel, temps, maintenance, health)
 * - Transient steering-wheel page-switch pill & background TPMS alert banner.
 */
internal class GeelyClusterInfoPageRenderer {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansBoldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val tempRect = RectF()
    private val carPath = Path()
    private val glassPath = Path()
    private val iconPath = Path()
    private val integerFormatter = NumberFormat.getIntegerInstance(Locale.US)

    private var toastLabel: String? = null
    private var toastIndex: Int = 0
    private var toastTotal: Int = 0
    private var toastStartUptimeMs: Long = 0L

    fun triggerPageSwitchToast(label: String, index: Int, total: Int) {
        toastLabel = label
        toastIndex = index
        toastTotal = total
        toastStartUptimeMs = SystemClock.uptimeMillis()
    }

    fun isToastAnimating(nowUptimeMs: Long): Boolean {
        if (toastLabel == null || toastStartUptimeMs <= 0L) return false
        return nowUptimeMs - toastStartUptimeMs < TOAST_TOTAL_DURATION_MS
    }

    fun drawTripPage(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int,
        telemetry: GeelyVehicleTelemetrySnapshot,
        compactForTopCard: Boolean,
    ) {
        val light = style.supportsLightMode && !nightMode
        val spec = GeelyClusterThemeSpecs.of(style)
        val numTypeface = resolveDialTypeface(spec.clockFont)
        val primaryText = if (light) Color.rgb(22, 30, 42) else Color.argb(246, 248, 250, 252)
        val secondaryText = if (light) Color.rgb(74, 88, 108) else Color.argb(215, 203, 213, 225)
        val mutedText = if (light) Color.rgb(112, 126, 146) else Color.argb(165, 160, 174, 192)
        val dividerColor = if (light) Color.argb(28, 15, 23, 42) else Color.argb(34, 255, 255, 255)

        val save = canvas.save()
        if (compactForTopCard) {
            canvas.translate(960f, 38f)
            canvas.scale(0.88f, 0.88f)
            canvas.translate(-960f, 0f)
        }

        val cx = 960f
        drawPageHeader(canvas, "本次行程", cx, 186f, accentColor, primaryText, light, spec.clockFrame)

        val trip = telemetry.trip
        val rows = listOf(
            TripRowSpec(
                icon = TripIcon.ROUTE,
                label = "里程",
                value = trip.distanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "—",
            ),
            TripRowSpec(
                icon = TripIcon.CLOCK,
                label = "用时",
                value = trip.durationMs?.let(::formatDurationZh) ?: "—",
            ),
            TripRowSpec(
                icon = TripIcon.SPEED,
                label = "平均速度",
                value = trip.avgSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—",
            ),
            TripRowSpec(
                icon = TripIcon.AVG_FUEL,
                label = "平均油耗",
                value = trip.avgFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—",
            ),
            TripRowSpec(
                icon = TripIcon.INSTANT_FUEL,
                label = "瞬时油耗",
                value = trip.instantFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—",
            ),
        )

        val rowLeft = 708f
        val rowRight = 1212f
        val startY = 262f
        val rowStep = 66f

        for ((idx, row) in rows.withIndex()) {
            val rowCenterY = startY + idx * rowStep
            drawTripRowIcon(canvas, row.icon, rowLeft + 14f, rowCenterY, withAlpha(accentColor, if (light) 225 else 235))

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = sansMedium
            textPaint.textSize = 25f
            textPaint.color = secondaryText
            canvas.drawText(row.label, rowLeft + 46f, rowCenterY + 9f, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = if (row.value == "—") sansMedium else numTypeface
            textPaint.textSize = 28f
            textPaint.color = if (row.value == "—") mutedText else primaryText
            canvas.drawText(row.value, rowRight, rowCenterY + 10f, textPaint)

            if (idx < rows.lastIndex) {
                strokePaint.strokeWidth = 1.2f
                strokePaint.color = dividerColor
                canvas.drawLine(rowLeft, rowCenterY + rowStep * 0.5f, rowRight, rowCenterY + rowStep * 0.5f, strokePaint)
            }
        }

        // Subtle bottom context strip (range & outside temp)
        val footerParts = mutableListOf<String>()
        telemetry.vehicle.enduranceRangeKm?.let { footerParts.add("续航 ${it.roundToInt()} km") }
        telemetry.vehicle.ambientTempC?.let { footerParts.add("车外 ${it.roundToInt()}°C") }
        if (footerParts.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansMedium
            textPaint.textSize = 20f
            textPaint.color = mutedText
            canvas.drawText(footerParts.joinToString("   ·   "), cx, 594f, textPaint)
        }

        canvas.restoreToCount(save)
    }

    fun drawTirePage(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int,
        telemetry: GeelyVehicleTelemetrySnapshot,
        compactForTopCard: Boolean,
    ) {
        val light = style.supportsLightMode && !nightMode
        val spec = GeelyClusterThemeSpecs.of(style)
        val numTypeface = resolveDialTypeface(spec.clockFont)
        val primaryText = if (light) Color.rgb(22, 30, 42) else Color.argb(248, 248, 250, 252)
        val secondaryText = if (light) Color.rgb(74, 88, 108) else Color.argb(210, 203, 213, 225)
        val mutedText = if (light) Color.rgb(112, 126, 146) else Color.argb(165, 160, 174, 192)
        val alertColor = Color.rgb(255, 82, 82)
        val warnAmber = Color.rgb(255, 179, 54)

        val save = canvas.save()
        if (compactForTopCard) {
            canvas.translate(960f, 38f)
            canvas.scale(0.88f, 0.88f)
            canvas.translate(-960f, 0f)
        }

        val cx = 960f
        val carCenterY = 344f
        val tires = telemetry.tires

        // Top-down sculpted car silhouette + 4 wheel capsules
        drawTopDownVehicle(
            canvas = canvas,
            cx = cx,
            cy = carCenterY,
            light = light,
            accentColor = accentColor,
            tires = tires,
            alertColor = alertColor,
        )

        // Four symmetric tire readouts flanking the car silhouette
        drawTireReadoutBlock(
            canvas = canvas,
            tire = tires.frontLeft,
            anchorX = 838f,
            anchorY = 266f,
            alignRight = true,
            numTypeface = numTypeface,
            primaryText = primaryText,
            secondaryText = secondaryText,
            mutedText = mutedText,
            accentColor = accentColor,
            alertColor = alertColor,
            warnAmber = warnAmber,
            light = light,
        )
        drawTireReadoutBlock(
            canvas = canvas,
            tire = tires.frontRight,
            anchorX = 1082f,
            anchorY = 266f,
            alignRight = false,
            numTypeface = numTypeface,
            primaryText = primaryText,
            secondaryText = secondaryText,
            mutedText = mutedText,
            accentColor = accentColor,
            alertColor = alertColor,
            warnAmber = warnAmber,
            light = light,
        )
        drawTireReadoutBlock(
            canvas = canvas,
            tire = tires.rearLeft,
            anchorX = 838f,
            anchorY = 442f,
            alignRight = true,
            numTypeface = numTypeface,
            primaryText = primaryText,
            secondaryText = secondaryText,
            mutedText = mutedText,
            accentColor = accentColor,
            alertColor = alertColor,
            warnAmber = warnAmber,
            light = light,
        )
        drawTireReadoutBlock(
            canvas = canvas,
            tire = tires.rearRight,
            anchorX = 1082f,
            anchorY = 442f,
            alignRight = false,
            numTypeface = numTypeface,
            primaryText = primaryText,
            secondaryText = secondaryText,
            mutedText = mutedText,
            accentColor = accentColor,
            alertColor = alertColor,
            warnAmber = warnAmber,
            light = light,
        )

        // Bottom caption or active TPMS alert notice
        val alertText = tires.alertDescription()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansMedium
        if (alertText != null) {
            textPaint.textSize = 23f
            textPaint.color = alertColor
            canvas.drawText("⚠ $alertText", cx, 558f, textPaint)
        } else {
            textPaint.textSize = 22f
            textPaint.color = secondaryText
            val caption = if (tires.hasAnyValue) "胎压与胎温" else "胎压与胎温 · 行驶后自动唤醒"
            canvas.drawText(caption, cx, 558f, textPaint)
        }

        canvas.restoreToCount(save)
    }

    fun drawVehicleInfoPage(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int,
        telemetry: GeelyVehicleTelemetrySnapshot,
        compactForTopCard: Boolean,
    ) {
        val light = style.supportsLightMode && !nightMode
        val spec = GeelyClusterThemeSpecs.of(style)
        val numTypeface = resolveDialTypeface(spec.clockFont)
        val primaryText = if (light) Color.rgb(22, 30, 42) else Color.argb(248, 248, 250, 252)
        val secondaryText = if (light) Color.rgb(74, 88, 108) else Color.argb(205, 203, 213, 225)
        val mutedText = if (light) Color.rgb(112, 126, 146) else Color.argb(165, 160, 174, 192)
        val tileBg = if (light) Color.argb(175, 255, 255, 255) else Color.argb(110, 18, 26, 40)
        val tileBorder = if (light) Color.argb(38, 15, 23, 42) else withAlpha(accentColor, 58)
        val alertColor = Color.rgb(255, 82, 82)
        val okGreen = if (light) Color.rgb(18, 150, 88) else Color.rgb(52, 211, 153)

        val save = canvas.save()
        if (compactForTopCard) {
            canvas.translate(960f, 38f)
            canvas.scale(0.88f, 0.88f)
            canvas.translate(-960f, 0f)
        }

        val cx = 960f
        drawPageHeader(canvas, "车辆综合信息", cx, 184f, accentColor, primaryText, light, spec.clockFrame)

        val v = telemetry.vehicle
        val odoText = v.odometerKm?.let { "${integerFormatter.format(it.roundToInt())} km" } ?: "—"
        val totalFuelText = v.totalAvgFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—"
        val rangeMain = v.enduranceRangeKm?.let { "${it.roundToInt()} km" } ?: "—"
        val rangeSub = buildList {
            v.fuelLevelLiters?.let { add(String.format(Locale.US, "%.1f L", it)) }
            v.fuelPercent?.let { add("${it.roundToInt()}%") }
        }.joinToString(" · ")
        val tempMain = v.ambientTempC?.let { "车外 ${it.roundToInt()}°C" } ?: "—"
        val tempSub = v.indoorTempC?.let { String.format(Locale.US, "车内 %.1f°C", it) } ?: ""
        val maintMain = v.maintenanceMileageKm?.let { "${integerFormatter.format(it.roundToInt())} km" } ?: "—"
        val healthAlert = v.powertrainWarningText != null || v.oilLevelOk == false || v.coolantLevelOk == false
        val healthMain = when {
            v.powertrainWarningText != null -> v.powertrainWarningText
            v.engineOilPercent != null -> "机油 ${v.engineOilPercent.roundToInt()}%"
            v.oilLevelOk == true -> "机油正常"
            else -> "—"
        }
        val healthSub = when {
            v.powertrainWarningText != null -> "请检查车辆状态"
            v.coolantLevelOk == true -> "机油 / 冷却液正常"
            v.coolantLevelOk == false -> "冷却液液位偏低"
            else -> ""
        }

        val cards = listOf(
            InfoTileSpec("总里程 (ODO)", odoText, "", primaryText, false),
            InfoTileSpec("总平均油耗", totalFuelText, "", primaryText, false),
            InfoTileSpec("剩余续航", rangeMain, rangeSub, primaryText, false),
            InfoTileSpec("环境温度", tempMain, tempSub, primaryText, false),
            InfoTileSpec("距保养参考", maintMain, "", primaryText, false),
            InfoTileSpec(
                label = "车况健康",
                main = healthMain,
                sub = healthSub,
                valueColor = if (healthAlert) alertColor else okGreen,
                isAlert = healthAlert,
            ),
        )

        val colW = 286f
        val colGap = 16f
        val rowH = 96f
        val rowGap = 12f
        val gridLeft = cx - colW - colGap * 0.5f
        val gridTop = 224f

        for ((idx, item) in cards.withIndex()) {
            val col = idx % 2
            val row = idx / 2
            val left = gridLeft + col * (colW + colGap)
            val top = gridTop + row * (rowH + rowGap)
            tempRect.set(left, top, left + colW, top + rowH)

            fillPaint.shader = null
            fillPaint.color = if (item.isAlert) Color.argb(if (light) 36 else 58, 255, 82, 82) else tileBg
            canvas.drawRoundRect(tempRect, 14f, 14f, fillPaint)

            strokePaint.strokeWidth = 1.4f
            strokePaint.color = if (item.isAlert) withAlpha(alertColor, 180) else tileBorder
            canvas.drawRoundRect(tempRect, 14f, 14f, strokePaint)

            // Label
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = sansMedium
            textPaint.textSize = 18f
            textPaint.color = secondaryText
            canvas.drawText(item.label, left + 18f, top + 30f, textPaint)

            // Main value
            textPaint.typeface = if (item.main == "—") sansMedium else numTypeface
            textPaint.textSize = if (item.main.length > 10) 22f else 28f
            textPaint.color = if (item.main == "—") mutedText else item.valueColor
            val mainY = if (item.sub.isEmpty()) top + 72f else top + 64f
            canvas.drawText(item.main, left + 18f, mainY, textPaint)

            // Optional sub value
            if (item.sub.isNotEmpty()) {
                textPaint.typeface = sansMedium
                textPaint.textSize = 16f
                textPaint.color = if (item.isAlert) alertColor else mutedText
                canvas.drawText(item.sub, left + 18f, top + 85f, textPaint)
            }
        }

        // Bottom persistent custom cumulative trip summary (Trip B)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansMedium
        textPaint.textSize = 19f
        textPaint.color = mutedText
        val footer = if (v.customTripDistanceKm >= 0.1f) {
            val dist = String.format(Locale.US, "%.1f km", v.customTripDistanceKm)
            val dur = formatDurationZh(v.customTripDurationMs)
            val spd = v.customTripAvgSpeedKmh?.let { String.format(Locale.US, " · 均速 %.1f km/h", it) }.orEmpty()
            "累计统计  $dist · $dur$spd"
        } else {
            "总览数据由原车 CAN 实时同步"
        }
        canvas.drawText(footer, cx, 578f, textPaint)

        canvas.restoreToCount(save)
    }

    /**
     * Draws a high-visibility top-center alert pill when TPMS flags an abnormal tire while the
     * driver is viewing another center page (e.g. Map / Album / Clock / Trip).
     */
    fun drawTireAlertBanner(
        canvas: Canvas,
        nightMode: Boolean,
        alertMessage: String,
    ) {
        val cx = 960f
        textPaint.typeface = sansBold
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.CENTER
        val fullText = "⚠ $alertMessage"
        val textW = textPaint.measureText(fullText)
        val pillW = (textW + 52f).coerceIn(280f, 680f)
        val top = 42f
        val bottom = 86f
        tempRect.set(cx - pillW / 2f, top, cx + pillW / 2f, bottom)

        fillPaint.shader = null
        fillPaint.color = if (nightMode) Color.argb(230, 148, 24, 24) else Color.argb(235, 214, 40, 40)
        canvas.drawRoundRect(tempRect, 22f, 22f, fillPaint)

        strokePaint.strokeWidth = 1.6f
        strokePaint.color = Color.argb(220, 255, 160, 160)
        canvas.drawRoundRect(tempRect, 22f, 22f, strokePaint)

        textPaint.color = Color.WHITE
        canvas.drawText(fullText, cx, top + 29f, textPaint)
    }

    /**
     * Draws a brief translucent page-switch toast (`本次行程  ● ○ ○ ○`) near top-center for ~2.2 s
     * after cycling center modes via steering-wheel button. Returns `true` while still animating.
     */
    fun drawPageSwitchToast(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int,
        nowUptimeMs: Long,
    ): Boolean {
        val label = toastLabel ?: return false
        val elapsed = nowUptimeMs - toastStartUptimeMs
        if (elapsed < 0L || elapsed >= TOAST_TOTAL_DURATION_MS) {
            toastLabel = null
            return false
        }
        val alphaFrac = when {
            elapsed < 160L -> elapsed / 160f
            elapsed > TOAST_HOLD_MS -> 1f - (elapsed - TOAST_HOLD_MS).toFloat() / (TOAST_TOTAL_DURATION_MS - TOAST_HOLD_MS).toFloat()
            else -> 1f
        }.coerceIn(0f, 1f)
        val alphaInt = (alphaFrac * 255f).roundToInt().coerceIn(0, 255)
        val light = style.supportsLightMode && !nightMode

        val cx = 960f
        textPaint.typeface = sansMedium
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.LEFT
        val textW = textPaint.measureText(label)
        val dotsCount = toastTotal.coerceIn(1, 8)
        val dotsW = if (dotsCount > 1) dotsCount * 14f + 16f else 0f
        val contentW = textW + dotsW
        val pillW = (contentW + 44f).coerceAtLeast(180f)
        val top = 46f
        val bottom = 84f
        tempRect.set(cx - pillW / 2f, top, cx + pillW / 2f, bottom)

        fillPaint.shader = null
        val bgRgb = if (light) Color.rgb(248, 250, 252) else Color.rgb(14, 20, 32)
        fillPaint.color = withAlpha(bgRgb, (alphaFrac * 224f).roundToInt())
        canvas.drawRoundRect(tempRect, 19f, 19f, fillPaint)

        strokePaint.strokeWidth = 1.5f
        strokePaint.color = withAlpha(accentColor, (alphaFrac * 185f).roundToInt())
        canvas.drawRoundRect(tempRect, 19f, 19f, strokePaint)

        val contentLeft = cx - contentW / 2f
        val fgRgb = if (light) Color.rgb(22, 30, 42) else Color.rgb(245, 248, 252)
        textPaint.color = withAlpha(fgRgb, alphaInt)
        canvas.drawText(label, contentLeft, top + 26.5f, textPaint)

        if (dotsCount > 1) {
            var dotX = contentLeft + textW + 20f
            val dotY = (top + bottom) * 0.5f
            for (i in 0 until dotsCount) {
                val active = i == toastIndex
                fillPaint.color = if (active) {
                    withAlpha(accentColor, alphaInt)
                } else {
                    withAlpha(fgRgb, (alphaFrac * 85f).roundToInt())
                }
                canvas.drawCircle(dotX, dotY, if (active) 4.2f else 2.8f, fillPaint)
                dotX += 14f
            }
        }
        return true
    }

    private fun drawPageHeader(
        canvas: Canvas,
        title: String,
        cx: Float,
        baselineY: Float,
        accentColor: Int,
        titleColor: Int,
        light: Boolean,
        frameStyle: GeelyClockFrameStyle,
    ) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansBold
        textPaint.textSize = 31f
        textPaint.color = titleColor
        canvas.drawText(title, cx, baselineY, textPaint)

        val lineY = baselineY + 18f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 2.4f
        strokePaint.color = withAlpha(accentColor, if (light) 190 else 210)
        canvas.drawLine(cx - 84f, lineY, cx + 84f, lineY, strokePaint)

        // Theme-matched micro side accents
        strokePaint.strokeWidth = 1.8f
        strokePaint.color = withAlpha(accentColor, if (light) 115 else 135)
        when (frameStyle) {
            GeelyClockFrameStyle.CHAMFERED_BRACKETS -> {
                canvas.drawLine(cx - 118f, lineY, cx - 96f, lineY, strokePaint)
                canvas.drawLine(cx + 96f, lineY, cx + 118f, lineY, strokePaint)
            }
            GeelyClockFrameStyle.HORIZON_TICKS -> {
                canvas.drawLine(cx - 122f, baselineY - 10f, cx - 102f, baselineY - 10f, strokePaint)
                canvas.drawLine(cx + 102f, baselineY - 10f, cx + 122f, baselineY - 10f, strokePaint)
            }
            GeelyClockFrameStyle.SQUARE_BRACKETS -> {
                canvas.drawLine(cx - 108f, lineY - 4f, cx - 108f, lineY + 4f, strokePaint)
                canvas.drawLine(cx + 108f, lineY - 4f, cx + 108f, lineY + 4f, strokePaint)
            }
        }
    }

    private fun drawTopDownVehicle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        light: Boolean,
        accentColor: Int,
        tires: GeelyTireTelemetry,
        alertColor: Int,
    ) {
        val halfW = 68f
        val halfH = 148f
        val left = cx - halfW
        val right = cx + halfW
        val top = cy - halfH
        val bottom = cy + halfH

        // Subtle ambient halo beneath the car
        val haloColor = if (tires.hasAnyAlert) alertColor else accentColor
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            195f,
            intArrayOf(
                withAlpha(haloColor, if (light) 36 else 56),
                withAlpha(haloColor, if (light) 12 else 18),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 195f, fillPaint)
        fillPaint.shader = null

        // 4 wheel capsules protruding slightly from wheel arches
        fun drawWheel(wx: Float, wy: Float, tire: GeelyTireReading) {
            val wHalfW = 10f
            val wHalfH = 26f
            tempRect.set(wx - wHalfW, wy - wHalfH, wx + wHalfW, wy + wHalfH)
            val wheelColor = when {
                tire.isAlert -> alertColor
                tire.hasAnyValue -> accentColor
                else -> if (light) Color.rgb(156, 168, 184) else Color.rgb(88, 102, 122)
            }
            fillPaint.color = withAlpha(wheelColor, if (tire.isAlert) 235 else 205)
            canvas.drawRoundRect(tempRect, 6f, 6f, fillPaint)
            if (tire.isAlert) {
                strokePaint.strokeWidth = 2.2f
                strokePaint.color = Color.argb(220, 255, 190, 190)
                canvas.drawRoundRect(tempRect, 6f, 6f, strokePaint)
            }
        }

        val wheelLeftX = left - 6f
        val wheelRightX = right + 6f
        val frontWheelY = cy - 78f
        val rearWheelY = cy + 94f
        drawWheel(wheelLeftX, frontWheelY, tires.frontLeft)
        drawWheel(wheelRightX, frontWheelY, tires.frontRight)
        drawWheel(wheelLeftX, rearWheelY, tires.rearLeft)
        drawWheel(wheelRightX, rearWheelY, tires.rearRight)

        // Subtle leader hairlines from wheel wells to readout blocks
        strokePaint.strokeWidth = 1.4f
        fun drawLeader(x1: Float, y: Float, x2: Float, tire: GeelyTireReading) {
            strokePaint.color = if (tire.isAlert) {
                withAlpha(alertColor, 200)
            } else {
                withAlpha(accentColor, if (light) 105 else 115)
            }
            canvas.drawLine(x1, y, x2, y, strokePaint)
        }
        drawLeader(wheelLeftX - 16f, frontWheelY, 850f, tires.frontLeft)
        drawLeader(wheelRightX + 16f, frontWheelY, 1070f, tires.frontRight)
        drawLeader(wheelLeftX - 16f, rearWheelY, 850f, tires.rearLeft)
        drawLeader(wheelRightX + 16f, rearWheelY, 1070f, tires.rearRight)

        // Side mirrors
        fillPaint.color = if (light) Color.rgb(218, 226, 236) else Color.rgb(198, 208, 220)
        tempRect.set(left - 18f, cy - 58f, left + 4f, cy - 42f)
        canvas.drawRoundRect(tempRect, 6f, 6f, fillPaint)
        tempRect.set(right - 4f, cy - 58f, right + 18f, cy - 42f)
        canvas.drawRoundRect(tempRect, 6f, 6f, fillPaint)

        // Sculpted top-down SUV/sedan body silhouette
        carPath.reset()
        carPath.moveTo(cx - 38f, top + 6f)
        carPath.quadTo(cx, top - 10f, cx + 38f, top + 6f)
        carPath.cubicTo(right + 2f, top + 20f, right + 4f, top + 68f, right, cy)
        carPath.cubicTo(right + 2f, bottom - 56f, right - 2f, bottom - 18f, cx + 44f, bottom - 4f)
        carPath.quadTo(cx, bottom + 6f, cx - 44f, bottom - 4f)
        carPath.cubicTo(left + 2f, bottom - 18f, left - 2f, bottom - 56f, left, cy)
        carPath.cubicTo(left - 4f, top + 68f, left - 2f, top + 20f, cx - 38f, top + 6f)
        carPath.close()

        fillPaint.shader = LinearGradient(
            left,
            top,
            right,
            bottom,
            if (light) {
                intArrayOf(Color.rgb(245, 248, 252), Color.rgb(212, 220, 232), Color.rgb(184, 195, 210))
            } else {
                intArrayOf(Color.rgb(236, 242, 248), Color.rgb(176, 188, 204), Color.rgb(112, 126, 144))
            },
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(carPath, fillPaint)
        fillPaint.shader = null

        strokePaint.strokeWidth = 2.0f
        strokePaint.color = if (light) Color.argb(135, 71, 85, 105) else Color.argb(175, 235, 244, 255)
        canvas.drawPath(carPath, strokePaint)

        // Hood crease lines
        strokePaint.strokeWidth = 1.4f
        strokePaint.color = if (light) Color.argb(70, 71, 85, 105) else Color.argb(75, 255, 255, 255)
        canvas.drawLine(cx - 28f, top + 24f, cx - 34f, cy - 68f, strokePaint)
        canvas.drawLine(cx + 28f, top + 24f, cx + 34f, cy - 68f, strokePaint)

        // Panoramic glass cabin (windshield + dark sunroof + rear glass)
        glassPath.reset()
        glassPath.moveTo(cx - 46f, cy - 64f)
        glassPath.quadTo(cx, cy - 82f, cx + 46f, cy - 64f)
        glassPath.lineTo(cx + 42f, cy + 96f)
        glassPath.quadTo(cx, cy + 114f, cx - 42f, cy + 96f)
        glassPath.close()

        fillPaint.shader = LinearGradient(
            cx,
            cy - 82f,
            cx,
            cy + 114f,
            intArrayOf(Color.rgb(32, 42, 58), Color.rgb(18, 24, 34), Color.rgb(36, 46, 62)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(glassPath, fillPaint)
        fillPaint.shader = null

        strokePaint.strokeWidth = 1.3f
        strokePaint.color = Color.argb(95, 180, 202, 228)
        canvas.drawPath(glassPath, strokePaint)

        // Subtle roof cross-beam dividing windshield & panoramic roof
        canvas.drawLine(cx - 43f, cy - 22f, cx + 43f, cy - 22f, strokePaint)
        canvas.drawLine(cx - 41f, cy + 56f, cx + 41f, cy + 56f, strokePaint)

        // Front DRL light strips & rear tail light bar
        strokePaint.strokeWidth = 3.0f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = Color.argb(230, 235, 248, 255)
        canvas.drawLine(cx - 44f, top + 14f, cx - 22f, top + 7f, strokePaint)
        canvas.drawLine(cx + 22f, top + 7f, cx + 44f, top + 14f, strokePaint)
        strokePaint.color = Color.argb(200, 255, 74, 74)
        canvas.drawLine(cx - 38f, bottom - 7f, cx + 38f, bottom - 7f, strokePaint)
    }

    private fun drawTireReadoutBlock(
        canvas: Canvas,
        tire: GeelyTireReading,
        anchorX: Float,
        anchorY: Float,
        alignRight: Boolean,
        numTypeface: Typeface,
        primaryText: Int,
        secondaryText: Int,
        mutedText: Int,
        accentColor: Int,
        alertColor: Int,
        warnAmber: Int,
        light: Boolean,
    ) {
        textPaint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT

        val pressureText = tire.pressureKpa?.let { "${it.roundToInt()}" } ?: "--"
        val pressureColor = when {
            tire.isAlert -> alertColor
            tire.hasPressure -> primaryText
            else -> mutedText
        }
        textPaint.typeface = numTypeface
        textPaint.textSize = 46f
        textPaint.color = pressureColor
        canvas.drawText(pressureText, anchorX, anchorY, textPaint)

        val subLine = when {
            tire.hasPressure && tire.hasTemp -> "kPa / ${tire.tempC!!.roundToInt()}°C"
            tire.hasPressure -> "kPa"
            tire.hasTemp -> "-- kPa / ${tire.tempC!!.roundToInt()}°C"
            else -> "kPa / --°C"
        }
        textPaint.typeface = sansMedium
        textPaint.textSize = 19f
        textPaint.color = if (tire.tempWarning || tire.isHighTemp) warnAmber else secondaryText
        canvas.drawText(subLine, anchorX, anchorY + 27f, textPaint)

        if (tire.isAlert) {
            val tag = when {
                tire.quickLeakWarning -> "快速漏气"
                tire.tempWarning || tire.isHighTemp -> "胎温过高"
                tire.isLowPressure -> "胎压偏低"
                tire.isHighPressure -> "胎压偏高"
                else -> "胎压异常"
            }
            textPaint.typeface = sansBold
            textPaint.textSize = 16f
            textPaint.color = alertColor
            canvas.drawText(tag, anchorX, anchorY + 50f, textPaint)
        } else if (tire.hasPressure && !light) {
            // Subtle accent dot under healthy readout in dark mode
            fillPaint.shader = null
            fillPaint.color = withAlpha(accentColor, 145)
            val dotX = if (alignRight) anchorX - 3f else anchorX + 3f
            canvas.drawCircle(dotX, anchorY + 44f, 2.6f, fillPaint)
        }
    }

    private fun drawTripRowIcon(canvas: Canvas, icon: TripIcon, cx: Float, cy: Float, color: Int) {
        strokePaint.color = color
        strokePaint.strokeWidth = 2.2f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        fillPaint.shader = null
        fillPaint.color = color

        when (icon) {
            TripIcon.ROUTE -> {
                iconPath.reset()
                iconPath.moveTo(cx - 7f, cy - 8f)
                iconPath.cubicTo(cx + 9f, cy - 8f, cx - 9f, cy + 8f, cx + 7f, cy + 8f)
                canvas.drawPath(iconPath, strokePaint)
                canvas.drawCircle(cx - 7f, cy - 8f, 2.8f, fillPaint)
                canvas.drawCircle(cx + 7f, cy + 8f, 2.8f, fillPaint)
            }
            TripIcon.CLOCK -> {
                canvas.drawCircle(cx, cy + 1f, 9.5f, strokePaint)
                canvas.drawLine(cx, cy - 11f, cx, cy - 8.5f, strokePaint)
                canvas.drawLine(cx, cy + 1f, cx, cy - 4.5f, strokePaint)
                canvas.drawLine(cx, cy + 1f, cx + 4.5f, cy + 3.5f, strokePaint)
            }
            TripIcon.SPEED -> {
                tempRect.set(cx - 10f, cy - 9f, cx + 10f, cy + 11f)
                canvas.drawArc(tempRect, 145f, 250f, false, strokePaint)
                canvas.drawLine(cx, cy + 2f, cx + 5.5f, cy - 4.5f, strokePaint)
                canvas.drawCircle(cx, cy + 2f, 2.2f, fillPaint)
            }
            TripIcon.AVG_FUEL -> {
                tempRect.set(cx - 8f, cy - 9f, cx + 3f, cy + 10f)
                canvas.drawRoundRect(tempRect, 2.5f, 2.5f, strokePaint)
                canvas.drawLine(cx - 5f, cy - 3f, cx, cy - 3f, strokePaint)
                canvas.drawLine(cx + 3f, cy - 5f, cx + 8f, cy - 2f, strokePaint)
                canvas.drawLine(cx + 8f, cy - 2f, cx + 8f, cy + 6f, strokePaint)
            }
            TripIcon.INSTANT_FUEL -> {
                tempRect.set(cx - 8f, cy - 9f, cx + 3f, cy + 10f)
                canvas.drawRoundRect(tempRect, 2.5f, 2.5f, strokePaint)
                // Mini bolt/pulse mark inside pump
                iconPath.reset()
                iconPath.moveTo(cx - 1.5f, cy - 5f)
                iconPath.lineTo(cx - 4.5f, cy + 1f)
                iconPath.lineTo(cx - 1f, cy + 1f)
                iconPath.lineTo(cx - 3.5f, cy + 6f)
                canvas.drawPath(iconPath, strokePaint)
                canvas.drawLine(cx + 3f, cy - 4f, cx + 8f, cy - 1f, strokePaint)
                canvas.drawLine(cx + 8f, cy - 1f, cx + 8f, cy + 6f, strokePaint)
            }
        }
    }

    private fun resolveDialTypeface(font: GeelyDialFont): Typeface = when (font) {
        GeelyDialFont.SANS_BOLD -> sansBold
        GeelyDialFont.SANS_LIGHT -> sansLight
        GeelyDialFont.SANS_BOLD_ITALIC -> sansBoldItalic
        GeelyDialFont.MONO_BOLD -> monoBold
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private enum class TripIcon { ROUTE, CLOCK, SPEED, AVG_FUEL, INSTANT_FUEL }

    private data class TripRowSpec(
        val icon: TripIcon,
        val label: String,
        val value: String,
    )

    private data class InfoTileSpec(
        val label: String,
        val main: String,
        val sub: String,
        val valueColor: Int,
        val isAlert: Boolean,
    )

    companion object {
        private const val TOAST_HOLD_MS = 1_800L
        private const val TOAST_TOTAL_DURATION_MS = 2_300L

        fun formatDurationZh(durationMs: Long): String {
            val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000L).toInt()
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            return "$hours 小时 $mins 分"
        }
    }
}

/**
 * Head-unit modal dialog showing all live vehicle telemetry (Current Trip, 4-wheel Tire Pressure &
 * Temperature, Overall Vehicle Info, and resettable cumulative Trip B) on one screen, plus 1-tap
 * cluster center view switching.
 */
internal object GeelyVehicleStatusDialog {
    fun show(
        activity: Activity,
        onSelectCenterMode: (GeelyClusterCenterMode) -> Unit,
    ) {
        val dialogContext = activity.appDialogContext()
        val colors = GeelyClusterUiColors.resolve(activity)
        val density = dialogContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val root = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }

        val contentHolder = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(dialogContext).apply {
            addView(
                contentHolder,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val maxScrollHeight = (activity.resources.displayMetrics.heightPixels * 0.60f)
            .roundToInt()
            .coerceIn(dp(260), dp(540))
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight),
        )

        fun buildSectionCard(title: String, rows: List<Pair<String, String>>): LinearLayout {
            val card = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(colors.itemSurface)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), colors.outline)
                }
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(
                TextView(dialogContext).apply {
                    text = title
                    setTextColor(colors.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(6))
                },
            )
            for ((k, v) in rows) {
                val r = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                }
                r.addView(
                    TextView(dialogContext).apply {
                        text = k
                        setTextColor(colors.secondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                r.addView(
                    TextView(dialogContext).apply {
                        text = v
                        setTextColor(colors.primaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    },
                )
                card.addView(r)
            }
            return card
        }

        fun formatTire(t: GeelyTireReading): String {
            val p = t.pressureKpa?.let { "${it.roundToInt()} kPa" } ?: "-- kPa"
            val temp = t.tempC?.let { "${it.roundToInt()}°C" } ?: "--°C"
            val warn = if (t.isAlert) "  ⚠" else ""
            return "$p / $temp$warn"
        }

        fun renderSnapshot(snap: GeelyVehicleTelemetrySnapshot) {
            contentHolder.removeAllViews()
            val intFmt = NumberFormat.getIntegerInstance(Locale.US)
            val trip = snap.trip
            val tires = snap.tires
            val v = snap.vehicle

            // 1. Current Trip
            contentHolder.addView(
                buildSectionCard(
                    title = "本次行程",
                    rows = listOf(
                        "里程" to (trip.distanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "—"),
                        "用时" to (trip.durationMs?.let(GeelyClusterInfoPageRenderer::formatDurationZh) ?: "—"),
                        "平均速度" to (trip.avgSpeedKmh?.let { String.format(Locale.US, "%.1f km/h", it) } ?: "—"),
                        "平均油耗" to (trip.avgFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—"),
                        "瞬时油耗" to (trip.instantFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—"),
                    ),
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) },
            )

            // 2. Tire Status
            val tireRows = mutableListOf(
                "左前轮" to formatTire(tires.frontLeft),
                "右前轮" to formatTire(tires.frontRight),
                "左后轮" to formatTire(tires.rearLeft),
                "右后轮" to formatTire(tires.rearRight),
            )
            tires.alertDescription()?.let { tireRows.add("告警提示" to "⚠ $it") }
            contentHolder.addView(
                buildSectionCard("胎压与胎温", tireRows),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) },
            )

            // 3. Vehicle Overview & Resettable Trip B
            val fuelDetail = buildList {
                v.fuelLevelLiters?.let { add(String.format(Locale.US, "%.1f L", it)) }
                v.fuelPercent?.let { add("${it.roundToInt()}%") }
            }.joinToString(" / ").ifEmpty { "—" }
            val customTripStr = if (v.customTripDistanceKm >= 0.1f) {
                val spd = v.customTripAvgSpeedKmh?.let { String.format(Locale.US, " · %.1f km/h", it) }.orEmpty()
                "${String.format(Locale.US, "%.1f km", v.customTripDistanceKm)} · ${GeelyClusterInfoPageRenderer.formatDurationZh(v.customTripDurationMs)}$spd"
            } else {
                "0.0 km"
            }
            contentHolder.addView(
                buildSectionCard(
                    title = "综合车况与累计信息",
                    rows = listOf(
                        "总里程 (ODO)" to (v.odometerKm?.let { "${intFmt.format(it.roundToInt())} km" } ?: "—"),
                        "总平均油耗" to (v.totalAvgFuelLPer100Km?.let { String.format(Locale.US, "%.1f L/100km", it) } ?: "—"),
                        "剩余续航" to (v.enduranceRangeKm?.let { "${it.roundToInt()} km" } ?: "—"),
                        "剩余油量" to fuelDetail,
                        "车外 / 车内温度" to "${v.ambientTempC?.let { "${it.roundToInt()}°C" } ?: "—"} / ${v.indoorTempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"}",
                        "距保养参考里程" to (v.maintenanceMileageKm?.let { "${intFmt.format(it.roundToInt())} km" } ?: "—"),
                        "机油 / 冷却液" to (v.powertrainWarningText ?: "${v.engineOilPercent?.let { "机油 ${it.roundToInt()}%" } ?: "机油正常"} · ${if (v.coolantLevelOk == false) "冷却液偏低" else "冷却液正常"}"),
                        "自重置累计统计" to customTripStr,
                    ),
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) },
            )

            // 4. Quick Switch Cluster Center View
            val switchCard = LinearLayout(dialogContext).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(colors.itemSurface)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), colors.outline)
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            switchCard.addView(
                TextView(dialogContext).apply {
                    text = "一键切换仪表中央视图"
                    setTextColor(colors.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setPadding(0, 0, 0, dp(8))
                },
            )
            val quickRows = listOf(
                listOf(
                    GeelyClusterCenterMode.MAP,
                    GeelyClusterCenterMode.ALBUM,
                    GeelyClusterCenterMode.CLOCK,
                ),
                listOf(
                    GeelyClusterCenterMode.TRIP,
                    GeelyClusterCenterMode.TIRE,
                    GeelyClusterCenterMode.VEHICLE_INFO,
                ),
            )
            val activeMode = GeelyClusterPrefs.load(activity).centerMode
            for ((rIdx, modes) in quickRows.withIndex()) {
                val btnRow = LinearLayout(dialogContext).apply { orientation = LinearLayout.HORIZONTAL }
                for ((cIdx, mode) in modes.withIndex()) {
                    val selected = mode == activeMode
                    val btn = Button(dialogContext).apply {
                        text = mode.shortLabel
                        isAllCaps = false
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(if (selected) colors.accent else colors.primaryText)
                        background = GradientDrawable().apply {
                            setColor(if (selected) colors.selectedSurface else colors.button)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1).coerceAtLeast(1), if (selected) colors.selectedOutline else colors.outline)
                        }
                        setOnClickListener {
                            onSelectCenterMode(mode)
                            Toast.makeText(activity, "仪表已切换至：${mode.shortLabel}", Toast.LENGTH_SHORT).show()
                            renderSnapshot(snap)
                        }
                    }
                    btnRow.addView(
                        btn,
                        LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            if (cIdx > 0) marginStart = dp(8)
                        },
                    )
                }
                switchCard.addView(
                    btnRow,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { if (rIdx > 0) topMargin = dp(8) },
                )
            }
            contentHolder.addView(switchCard)
        }

        fun refreshAsync() {
            val app = activity.applicationContext
            Thread({
                val snap = runCatching { GeelyVehicleTelemetryReader.poll(app) }
                    .getOrDefault(GeelyVehicleTelemetryReader.latestSnapshot)
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        renderSnapshot(snap)
                    }
                }
            }, "geely-vehicle-status-poll").start()
        }

        // Initial render with cached snapshot (or demo data off-car for preview)
        val initial = GeelyVehicleTelemetryReader.latestSnapshot.let { snap ->
            if (snap.updatedAtMs > 0L) snap else if (!GeelyVehicleDetector.isGeelyOrEcarx(activity)) {
                GeelyVehicleTelemetrySnapshot.previewDemo()
            } else {
                snap
            }
        }
        renderSnapshot(initial)

        val dialog = activity.appDialogBuilder()
            .setTitle("车况与行程总览")
            .setView(root)
            .setPositiveButton("刷新数据", null)
            .setNeutralButton("清零累计统计", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            refreshAsync()
            Toast.makeText(activity, "正在刷新原车数据…", Toast.LENGTH_SHORT).show()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            GeelyVehicleTelemetryReader.resetCustomTrip(activity)
            renderSnapshot(GeelyVehicleTelemetryReader.latestSnapshot)
            Toast.makeText(activity, "已清零自重置累计统计", Toast.LENGTH_SHORT).show()
        }

        if (GeelyVehicleDetector.isGeelyOrEcarx(activity)) {
            refreshAsync()
        }
    }
}
