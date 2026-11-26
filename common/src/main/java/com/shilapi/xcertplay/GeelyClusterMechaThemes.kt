package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure SVG-backed dynamic renderers for the 5 mecha/tactical Geely cluster themes:
 * - [GeelyClusterThemeStyle.HYPER_WING] (`assets/cluster-visual/themes/hyper-wing/`)
 * - [GeelyClusterThemeStyle.VALKYRIE_HEX] (`assets/cluster-visual/themes/valkyrie-hex/`)
 * - [GeelyClusterThemeStyle.ORBIT_RADAR] (`assets/cluster-visual/themes/orbit-radar/`)
 * - [GeelyClusterThemeStyle.TITAN_CORE] (`assets/cluster-visual/themes/titan-core/`)
 * - [GeelyClusterThemeStyle.PHANTOM_STEALTH] (`assets/cluster-visual/themes/phantom-stealth/`)
 *
 * All static dial bases, armor bezels, tactical wings, and RPM pods are loaded from editable
 * SVG assets via [GeelyVectorSvgCache]. This class draws only real-time dynamic overlays with
 * zero per-frame object allocations.
 */
internal class GeelyClusterMechaThemesRenderer(private val context: Context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val rectF = RectF()
    private val outerRectF = RectF()
    private val innerRectF = RectF()
    private val workPath = Path()
    private val clipPath = Path()

    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansBoldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun drawLeftSpeedDial(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        rawSpeed: Float,
        curSpeedFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        accentColor: Int,
        nightMode: Boolean,
    ) {
        val lightMode = style.supportsLightMode && !nightMode
        drawStaticSvgFace(canvas, style, isRight = false, lightMode = lightMode, accentColor = accentColor)
        when (style) {
            GeelyClusterThemeStyle.HYPER_WING ->
                drawHyperWingDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.VALKYRIE_HEX ->
                drawValkyrieHexDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.ORBIT_RADAR ->
                drawOrbitRadarDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.TITAN_CORE ->
                drawTitanCoreDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.PHANTOM_STEALTH ->
                drawPhantomStealthDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            else -> Unit
        }
    }

    fun drawRightTachDial(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        rawRpm: Float,
        curRpmFrac: Float,
        gearText: String,
        accentColor: Int,
        nightMode: Boolean,
        centerArtwork: Bitmap?,
    ) {
        val lightMode = style.supportsLightMode && !nightMode
        drawStaticSvgFace(canvas, style, isRight = true, lightMode = lightMode, accentColor = accentColor)
        when (style) {
            GeelyClusterThemeStyle.HYPER_WING ->
                drawHyperWingDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.VALKYRIE_HEX ->
                drawValkyrieHexDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.ORBIT_RADAR ->
                drawOrbitRadarDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.TITAN_CORE ->
                drawTitanCoreDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.PHANTOM_STEALTH ->
                drawPhantomStealthDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            else -> Unit
        }
    }

    // -------------------------------------------------------------------------
    // SVG Static Dial Asset Loader
    // -------------------------------------------------------------------------

    private fun drawStaticSvgFace(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
    ) {
        val kind = if (isRight) "tach" else "speed"
        val mode = if (lightMode) "day" else "night"
        val folder = when (style) {
            GeelyClusterThemeStyle.HYPER_WING -> "hyper-wing"
            GeelyClusterThemeStyle.VALKYRIE_HEX -> "valkyrie-hex"
            GeelyClusterThemeStyle.ORBIT_RADAR -> "orbit-radar"
            GeelyClusterThemeStyle.TITAN_CORE -> "titan-core"
            GeelyClusterThemeStyle.PHANTOM_STEALTH -> "phantom-stealth"
            else -> return
        }
        val assetPath = "cluster-visual/themes/$folder/${kind}_${mode}.svg"
        val quantizedTint = accentColor and 0xFFF8F8F8.toInt()
        GeelyVectorSvgCache.getOrLoad(context, assetPath, DIAL_SIZE_PX, DIAL_SIZE_PX, tintColor = quantizedTint)?.let {
            canvas.drawPicture(it)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 6: HYPER_WING Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawHyperWingDynamic(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        gearText: String,
        centerArtwork: Bitmap?,
    ) {
        val cx = CX
        val cy = CY
        val defaultAccent = GeelyClusterThemeStyle.HYPER_WING.defaultAccentColor(!lightMode)
        val secColor = if (isNearColor(accentColor, defaultAccent)) {
            if (lightMode) 0xFFD97706.toInt() else 0xFFFFB800.toInt()
        } else {
            blendColor(accentColor, Color.WHITE, 0.25f)
        }
        val hotColor = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(255, 45, 32)

        // 1. Mirrored C-shape sweep with 24 progressive-width blade segments
        val startDeg = if (isRight) 85f else 95f
        val dir = if (isRight) -1f else 1f
        val span = 232f
        val segs = 24
        val activeCount = (curFrac * segs).roundToInt().coerceIn(0, segs)
        val stepDeg = span / segs
        val gapDeg = 2.4f
        val segSweep = stepDeg - gapDeg

        for (i in 0 until segs) {
            val t = i.toFloat() / (segs - 1)
            val isLit = i < activeCount
            val isHot = i >= 19
            val rIn = 180f
            val rOut = 204f + t * 22f + if (isLit) 4f else 0f
            val segStart = if (dir > 0f) {
                startDeg + i * stepDeg + gapDeg / 2f
            } else {
                startDeg - (i + 1) * stepDeg + gapDeg / 2f
            }

            if (isLit && i == activeCount - 1) {
                val haloCol = if (isHot) hotColor else blendColor(accentColor, secColor, t)
                drawAnnularSector(
                    canvas, cx, cy, rIn - 3.5f, rOut + 4f,
                    segStart - 0.6f, segSweep + 1.2f,
                    withAlpha(haloCol, if (lightMode) 64 else 92),
                )
            }

            val blockCol = when {
                isLit && isHot -> hotColor
                isLit -> blendColor(accentColor, secColor, t)
                isHot -> Color.argb(if (lightMode) 56 else 48, 239, 68, 68)
                else -> if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(44, 148, 163, 184)
            }
            drawAnnularSector(canvas, cx, cy, rIn, rOut, segStart, segSweep, blockCol)
        }

        // 2. Top 5 skewed parallelogram shift / overspeed bars
        val shiftBars = 5
        val dimShift = if (lightMode) Color.argb(68, 100, 116, 139) else Color.argb(56, 148, 163, 184)
        for (i in 0 until shiftBars) {
            val bx = cx - 64f + i * 27f
            val by = 112f
            val lit = if (isRight) rawRpm >= 3000f + i * 950f else rawSpeed >= 80f + i * 30f
            val col = when {
                !lit -> dimShift
                i >= 4 -> hotColor
                i >= 2 -> secColor
                else -> accentColor
            }
            if (lit) {
                drawSkewedQuad(canvas, bx - 1.5f, by - 1.5f, 24f, 11f, 5f, withAlpha(col, if (lightMode) 56 else 85))
            }
            drawSkewedQuad(canvas, bx, by, 21f, 8f, 5f, col)
        }

        // 3. Mirrored scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200", "240")
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansBoldItalic
        textPaint.textSize = 17f
        val normalLabelCol = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(205, 226, 232, 240)
        for (i in labels.indices) {
            val ratio = i.toFloat() / (labels.size - 1)
            val rad = Math.toRadians((startDeg + dir * ratio * span).toDouble())
            textPaint.color = if (isRight && i >= 6) hotColor else normalLabelCol
            canvas.drawText(
                labels[i],
                cx + cos(rad).toFloat() * 155f,
                cy + sin(rad).toFloat() * 155f + 6f,
                textPaint,
            )
        }

        // 4. Center bold-italic numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 162f, 16f, centerArtwork, accentColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBoldItalic
            textPaint.textSize = 106f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx - 4f, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 17f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "HYPER // WING"), cx, cy + 62f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx - 4f, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 18f
                textPaint.color = accentColor
                canvas.drawText("KM/H", cx, cy + 62f, textPaint)
            }
        }

        // 5. Bottom skewed parallelogram fuel blocks / RPM pod readout
        if (!isRight) {
            val fuelSegs = 8
            val litSegs = if (rawFuel != null) (curFuelFrac * fuelSegs).roundToInt().coerceIn(0, fuelSegs) else 0
            val sx = cx - 72f
            val sy = 430f
            val fuelCol = if (rawFuel != null && rawFuel <= 15f) hotColor else accentColor
            val dimSeg = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(56, 148, 163, 184)
            for (i in 0 until fuelSegs) {
                drawSkewedQuad(canvas, sx + i * 18.5f, sy, 13f, 9f, 4f, if (i < litSegs) fuelCol else dimSeg)
            }
            val label = if (rawFuel != null) "FUEL ${rawFuel.roundToInt()}%" else "FUEL --%"
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndCustomText(canvas, cx, 400f, label, txtCol, monoBold, 18f, 18)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBoldItalic
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 428f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 7: VALKYRIE_HEX Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawValkyrieHexDynamic(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        gearText: String,
        centerArtwork: Bitmap?,
    ) {
        val cx = CX
        val cy = CY
        val defaultAccent = GeelyClusterThemeStyle.VALKYRIE_HEX.defaultAccentColor(!lightMode)
        val secColor = if (isNearColor(accentColor, defaultAccent)) {
            if (lightMode) 0xFF0284C7.toInt() else 0xFF38BDF8.toInt()
        } else {
            blendColor(accentColor, Color.WHITE, 0.28f)
        }
        val hotColor = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(255, 59, 107)

        // 1. Outer 26-segment dual-color track + inner 13 synchronized diamond nodes
        val segs = 26
        val activeCount = (curFrac * segs).roundToInt().coerceIn(0, segs)
        val stepDeg = 270f / segs
        val gapDeg = 2.0f
        val dimDiamond = if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(56, 148, 163, 184)

        for (i in 0 until segs) {
            val t = i.toFloat() / (segs - 1)
            val isLit = i < activeCount
            val isHot = i >= 21
            val startDeg = 135f + i * stepDeg + gapDeg / 2f
            val segSweep = stepDeg - gapDeg
            val rIn = if (isLit) 190f else 194f
            val rOut = if (isLit) 216f else 212f
            val gradCol = blendColor(secColor, accentColor, t)

            if (isLit && i == activeCount - 1) {
                val haloCol = if (isHot) hotColor else gradCol
                drawAnnularSector(
                    canvas, cx, cy, rIn - 3f, rOut + 3.5f,
                    startDeg - 0.5f, segSweep + 1.0f,
                    withAlpha(haloCol, if (lightMode) 64 else 90),
                )
            }

            val blockCol = when {
                isLit && isHot -> hotColor
                isLit -> gradCol
                isHot -> Color.argb(if (lightMode) 56 else 46, 255, 59, 107)
                else -> if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(42, 148, 163, 184)
            }
            drawAnnularSector(canvas, cx, cy, rIn, rOut, startDeg, segSweep, blockCol)

            if (i % 2 == 0) {
                val midDeg = 135f + (i + 0.5f) * stepDeg
                val rad = Math.toRadians(midDeg.toDouble())
                val dx = cx + cos(rad).toFloat() * 176f
                val dy = cy + sin(rad).toFloat() * 176f
                val dCol = if (isLit) (if (isHot) hotColor else secColor) else dimDiamond
                val save = canvas.save()
                canvas.translate(dx, dy)
                canvas.rotate(midDeg)
                if (isLit) {
                    workPath.reset()
                    workPath.moveTo(0f, -7.2f)
                    workPath.lineTo(5.6f, 0f)
                    workPath.lineTo(0f, 7.2f)
                    workPath.lineTo(-5.6f, 0f)
                    workPath.close()
                    fillPaint.color = withAlpha(dCol, if (lightMode) 60 else 86)
                    canvas.drawPath(workPath, fillPaint)
                }
                workPath.reset()
                workPath.moveTo(0f, -5.0f)
                workPath.lineTo(3.6f, 0f)
                workPath.lineTo(0f, 5.0f)
                workPath.lineTo(-3.6f, 0f)
                workPath.close()
                fillPaint.color = dCol
                canvas.drawPath(workPath, fillPaint)
                canvas.restoreToCount(save)
            }
        }

        // 2. Scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 150f,
            labels = labels,
            typeface = monoBold,
            textSize = 17f,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(208, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 3. Center numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 162f, 16f, centerArtwork, secColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBold
            textPaint.textSize = 100f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = secColor
                canvas.drawText(gearSubtitle(gearText, "VALKYRIE // HEX"), cx, cy + 60f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = secColor
                canvas.drawText("KM/H", cx, cy + 60f, textPaint)
            }
        }

        // 4. Bottom fuel cells / hex pod RPM readout
        if (!isRight) {
            val fuelSegs = 10
            val litSegs = if (rawFuel != null) (curFuelFrac * fuelSegs).roundToInt().coerceIn(0, fuelSegs) else 0
            val sx = cx - 74f
            val sy = 434f
            val fuelCol = if (rawFuel != null && rawFuel <= 15f) hotColor else secColor
            val dimSeg = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(56, 148, 163, 184)
            for (i in 0 until fuelSegs) {
                fillPaint.color = if (i < litSegs) fuelCol else dimSeg
                canvas.drawRect(sx + i * 15.2f, sy, sx + i * 15.2f + 11f, sy + 7f, fillPaint)
            }
            val label = if (rawFuel != null) "FUEL ${rawFuel.roundToInt()}%" else "FUEL --%"
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndCustomText(canvas, cx, 402f, label, txtCol, monoBold, 18f, 18)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 428f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 8: ORBIT_RADAR Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawOrbitRadarDynamic(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        gearText: String,
        centerArtwork: Bitmap?,
    ) {
        val cx = CX
        val cy = CY
        val hotColor = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(251, 113, 133)

        // 1. 36 radial pulse beam bars with bright radar scan head
        val beams = 36
        val activeCount = (curFrac * beams).roundToInt().coerceIn(0, beams)
        strokePaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until beams) {
            val ratio = i.toFloat() / (beams - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val isLit = i < activeCount
            val isHead = isLit && i == activeCount - 1
            val isHot = (isRight && i >= 29) || (!isRight && i >= 30)
            val beamCol = if (isHot) hotColor else accentColor

            if (isHead) {
                val rIn = 182f
                val rOut = 226f
                strokePaint.strokeWidth = 10f
                strokePaint.color = withAlpha(beamCol, if (lightMode) 78 else 105)
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
                strokePaint.strokeWidth = 5.2f
                strokePaint.color = if (lightMode) beamCol else Color.WHITE
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
            } else if (isLit) {
                val rIn = 188f
                val rOut = 220f
                strokePaint.strokeWidth = 7.5f
                strokePaint.color = withAlpha(beamCol, if (lightMode) 48 else 64)
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
                strokePaint.strokeWidth = 4.0f
                strokePaint.color = beamCol
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
            } else {
                val rIn = 188f
                val rOut = 212f
                strokePaint.strokeWidth = 2.6f
                strokePaint.color = when {
                    isHot -> Color.argb(if (lightMode) 76 else 68, 251, 113, 133)
                    lightMode -> Color.argb(74, 100, 116, 139)
                    else -> Color.argb(56, 148, 163, 184)
                }
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
            }
        }

        // 2. Scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 149f,
            labels = labels,
            typeface = monoBold,
            textSize = 16f,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(200, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 3. Center airy radar numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 166f, 22f, centerArtwork, accentColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansLight
            textPaint.textSize = 108f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 30f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "RADAR // SYNC"), cx, cy + 62f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 30f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = accentColor
                canvas.drawText("KM/H", cx, cy + 62f, textPaint)
            }
        }

        // 4. Bottom fuel arc (matches SVG static track r=212, 62..118 deg) / RPM readout
        if (!isRight) {
            if (rawFuel != null && curFuelFrac > 0.01f) {
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = 5f
                strokePaint.color = if (rawFuel <= 15f) hotColor else accentColor
                rectF.set(cx - 212f, cy - 212f, cx + 212f, cy + 212f)
                canvas.drawArc(rectF, 118f, -56f * curFuelFrac, false, strokePaint)
            }
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndText(canvas, cx, 406f, rawFuel, txtCol, monoBold, 20f)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("RPM ${rawRpm.roundToInt()}", cx, 424f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 9: TITAN_CORE Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawTitanCoreDynamic(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        gearText: String,
        centerArtwork: Bitmap?,
    ) {
        val cx = CX
        val cy = CY
        val defaultAccent = GeelyClusterThemeStyle.TITAN_CORE.defaultAccentColor(!lightMode)
        val secColor = if (isNearColor(accentColor, defaultAccent)) {
            if (lightMode) 0xFF0369A1.toInt() else 0xFF38BDF8.toInt()
        } else {
            blendColor(accentColor, Color.WHITE, 0.30f)
        }
        val hotColor = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(239, 68, 68)

        // 1. Outer ring: 12 heavy armored reactor blocks
        val blocks = 12
        val activeBlocks = if (curFrac > 0.01f) ceil(curFrac * blocks.toDouble()).toInt().coerceIn(0, blocks) else 0
        val stepDeg = 270f / blocks
        val gapDeg = 4.8f
        val segSweep = stepDeg - gapDeg

        for (i in 0 until blocks) {
            val startDeg = 135f + i * stepDeg + gapDeg / 2f
            val isLit = i < activeBlocks
            val isHot = i >= 10

            if (isLit && i == activeBlocks - 1) {
                val haloCol = if (isHot) hotColor else accentColor
                drawAnnularSector(
                    canvas, cx, cy, 190.5f, 229.5f,
                    startDeg - 0.7f, segSweep + 1.4f,
                    withAlpha(haloCol, if (lightMode) 64 else 92),
                )
            }

            val col = when {
                isLit && isHot -> hotColor
                isLit -> accentColor
                isHot -> Color.argb(if (lightMode) 58 else 52, 239, 68, 68)
                else -> if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(46, 148, 163, 184)
            }
            drawAnnularSector(canvas, cx, cy, 194f, 226f, startDeg, segSweep, col)
        }

        // 2. Inner ring: 36 synced precision pins
        val pins = 36
        val activePins = (curFrac * pins).roundToInt().coerceIn(0, pins)
        strokePaint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until pins) {
            val ratio = i.toFloat() / (pins - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val isLit = i < activePins
            val isHot = i >= 30
            strokePaint.strokeWidth = if (isLit) 3.2f else 1.8f
            strokePaint.color = when {
                isLit && isHot -> hotColor
                isLit -> secColor
                lightMode -> Color.argb(72, 100, 116, 139)
                else -> Color.argb(54, 148, 163, 184)
            }
            canvas.drawLine(cx + cosA * 172f, cy + sinA * 172f, cx + cosA * 184f, cy + sinA * 184f, strokePaint)
        }

        // 3. Scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 150f,
            labels = labels,
            typeface = sansBold,
            textSize = 17f,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(208, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 4. Center industrial numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 164f, 14f, centerArtwork, accentColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBold
            textPaint.textSize = 100f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "TITAN // CORE"), cx, cy + 60f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = accentColor
                canvas.drawText("KM/H", cx, cy + 60f, textPaint)
            }
        }

        // 5. Bottom 8 rounded reactor fuel cells / RPM rounded pod readout
        if (!isRight) {
            val cells = 8
            val litCells = if (rawFuel != null) (curFuelFrac * cells).roundToInt().coerceIn(0, cells) else 0
            val sx = cx - 72f
            val sy = 428f
            val fuelCol = if (rawFuel != null && rawFuel <= 15f) hotColor else accentColor
            val dimCell = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(56, 148, 163, 184)
            for (i in 0 until cells) {
                fillPaint.color = if (i < litCells) fuelCol else dimCell
                rectF.set(sx + i * 18.5f, sy, sx + i * 18.5f + 14f, sy + 11f)
                canvas.drawRoundRect(rectF, 2.2f, 2.2f, fillPaint)
            }
            val label = if (rawFuel != null) "CORE ${rawFuel.roundToInt()}%" else "CORE --%"
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndCustomText(canvas, cx, 398f, label, txtCol, monoBold, 18f, 18)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 429f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 10: PHANTOM_STEALTH Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawPhantomStealthDynamic(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
        rawFuel: Float?,
        curFuelFrac: Float,
        gearText: String,
        centerArtwork: Bitmap?,
    ) {
        val cx = CX
        val cy = CY
        val hotColor = if (lightMode) Color.rgb(225, 29, 72) else Color.rgb(244, 63, 94)

        // 1. 18 sleek throttle blade arc segments
        val segs = 18
        val activeCount = (curFrac * segs).roundToInt().coerceIn(0, segs)
        val stepDeg = 270f / segs
        val gapDeg = 3.6f
        val segSweep = stepDeg - gapDeg

        for (i in 0 until segs) {
            val startDeg = 135f + i * stepDeg + gapDeg / 2f
            val isLit = i < activeCount
            val isHot = i >= 14

            if (isLit && i == activeCount - 1) {
                val haloCol = if (isHot) hotColor else accentColor
                drawAnnularSector(
                    canvas, cx, cy, 197f, 217f,
                    startDeg - 0.5f, segSweep + 1.0f,
                    withAlpha(haloCol, if (lightMode) 64 else 92),
                )
            }

            val col = when {
                isLit && isHot -> hotColor
                isLit -> accentColor
                isHot -> Color.argb(if (lightMode) 56 else 48, 244, 63, 94)
                else -> if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(46, 148, 163, 184)
            }
            drawAnnularSector(canvas, cx, cy, 200f, 214f, startDeg, segSweep, col)
        }

        // 2. Sliding triangular target cursor on outer rail (r=224)
        val cursorDeg = 135f + curFrac.coerceIn(0f, 1f) * 270f
        val isCursorHot = isRight && rawRpm >= 5800f
        val cursorGlowCol = if (isCursorHot) hotColor else accentColor
        val cursorCoreCol = when {
            isCursorHot -> hotColor
            lightMode -> accentColor
            else -> Color.WHITE
        }
        val save = canvas.save()
        canvas.rotate(cursorDeg, cx, cy)
        workPath.reset()
        workPath.moveTo(cx + 214f, cy)
        workPath.lineTo(cx + 235f, cy - 9.5f)
        workPath.lineTo(cx + 235f, cy + 9.5f)
        workPath.close()
        fillPaint.color = withAlpha(cursorGlowCol, if (lightMode) 72 else 100)
        canvas.drawPath(workPath, fillPaint)

        workPath.reset()
        workPath.moveTo(cx + 217f, cy)
        workPath.lineTo(cx + 232f, cy - 7f)
        workPath.lineTo(cx + 232f, cy + 7f)
        workPath.close()
        fillPaint.color = cursorCoreCol
        canvas.drawPath(workPath, fillPaint)
        canvas.restoreToCount(save)

        // 3. Scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 172f,
            labels = labels,
            typeface = monoBold,
            textSize = 16f,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(198, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 4. Center stealth monospace numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 166f, 16f, centerArtwork, accentColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 100f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 15f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "STEALTH // HUD"), cx, cy + 60f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 15f
                textPaint.color = accentColor
                canvas.drawText("KM/H", cx, cy + 60f, textPaint)
            }
        }

        // 5. Bottom slim fuel bars / RPM readout
        if (!isRight) {
            val fuelSegs = 10
            val litSegs = if (rawFuel != null) (curFuelFrac * fuelSegs).roundToInt().coerceIn(0, fuelSegs) else 0
            val sx = cx - 74f
            val sy = 434f
            val fuelCol = if (rawFuel != null && rawFuel <= 15f) hotColor else accentColor
            val dimSeg = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(54, 148, 163, 184)
            for (i in 0 until fuelSegs) {
                fillPaint.color = if (i < litSegs) fuelCol else dimSeg
                canvas.drawRect(sx + i * 15.2f, sy, sx + i * 15.2f + 11f, sy + 5f, fillPaint)
            }
            val label = if (rawFuel != null) "FUEL ${rawFuel.roundToInt()}%" else "FUEL --%"
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndCustomText(canvas, cx, 403f, label, txtCol, monoBold, 17.5f, 18)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 426f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Shared Dynamic Helpers
    // -------------------------------------------------------------------------

    private fun drawSkewedQuad(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        skew: Float,
        color: Int,
    ) {
        workPath.reset()
        workPath.moveTo(x + skew, y)
        workPath.lineTo(x + w + skew, y)
        workPath.lineTo(x + w, y + h)
        workPath.lineTo(x, y + h)
        workPath.close()
        fillPaint.color = color
        canvas.drawPath(workPath, fillPaint)
    }

    private fun drawRadialScaleLabels(
        canvas: Canvas,
        radius: Float,
        labels: List<String>,
        typeface: Typeface,
        textSize: Float,
        normalColor: Int,
        redFromIndex: Int,
        redColor: Int,
    ) {
        if (labels.size < 2) return
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = typeface
        textPaint.textSize = textSize
        val stepDeg = 270f / (labels.size - 1)
        for (i in labels.indices) {
            val rad = Math.toRadians((135f + i * stepDeg).toDouble())
            textPaint.color = if (i >= redFromIndex) redColor else normalColor
            canvas.drawText(
                labels[i],
                CX + cos(rad).toFloat() * radius,
                CY + sin(rad).toFloat() * radius + textSize * 0.34f,
                textPaint,
            )
        }
    }

    private fun drawAnnularSector(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rInner: Float,
        rOuter: Float,
        startDeg: Float,
        sweepDeg: Float,
        color: Int,
    ) {
        outerRectF.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
        innerRectF.set(cx - rInner, cy - rInner, cx + rInner, cy + rInner)
        workPath.reset()
        workPath.arcTo(outerRectF, startDeg, sweepDeg, true)
        workPath.arcTo(innerRectF, startDeg + sweepDeg, -sweepDeg, false)
        workPath.close()
        fillPaint.color = color
        canvas.drawPath(workPath, fillPaint)
    }

    private fun drawRoundedArtwork(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        radius: Float,
        bitmap: Bitmap,
        borderColor: Int,
    ) {
        val left = cx - size / 2f
        val top = cy - size / 2f
        rectF.set(left, top, left + size, top + size)

        fillPaint.color = Color.argb(85, 0, 0, 0)
        outerRectF.set(left - 2f, top + 5f, left + size + 2f, top + size + 8f)
        canvas.drawRoundRect(outerRectF, radius, radius, fillPaint)

        val save = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, rectF, bitmapPaint)
        canvas.restoreToCount(save)

        strokePaint.strokeWidth = 2f
        strokePaint.color = borderColor
        canvas.drawRoundRect(rectF, radius, radius, strokePaint)
    }

    private fun drawFuelIconAndText(
        canvas: Canvas,
        cx: Float,
        iconTop: Float,
        rawFuel: Float?,
        color: Int,
        typeface: Typeface,
        textSize: Float,
    ) {
        val label = if (rawFuel != null) "${rawFuel.roundToInt()}%" else "--%"
        drawFuelIconAndCustomText(canvas, cx, iconTop, label, color, typeface, textSize, 20)
    }

    private fun drawFuelIconAndCustomText(
        canvas: Canvas,
        cx: Float,
        iconTop: Float,
        label: String,
        color: Int,
        typeface: Typeface,
        textSize: Float,
        iconSize: Int,
    ) {
        textPaint.typeface = typeface
        textPaint.textSize = textSize
        textPaint.textAlign = Paint.Align.LEFT
        val textW = textPaint.measureText(label)
        val gap = 6f
        val startX = cx - (iconSize + gap + textW) / 2f

        GeelyVectorSvgCache.getOrLoad(
            context = context,
            assetPath = "cluster-visual/themes/common/fuel_icon.svg",
            widthPx = iconSize,
            heightPx = iconSize,
            tintColor = color,
        )?.let { pic ->
            val save = canvas.save()
            canvas.translate(startX, iconTop)
            canvas.drawPicture(pic)
            canvas.restoreToCount(save)
        }

        textPaint.color = color
        canvas.drawText(label, startX + iconSize + gap, iconTop + iconSize * 0.84f, textPaint)
    }

    private fun gearSubtitle(gearText: String, driveLabel: String): String = when (gearText.uppercase(Locale.ROOT)) {
        "P" -> "PARK"
        "R" -> "REVERSE"
        "N" -> "NEUTRAL"
        else -> driveLabel
    }

    private fun isNearColor(c1: Int, c2: Int): Boolean {
        val dr = kotlin.math.abs(Color.red(c1) - Color.red(c2))
        val dg = kotlin.math.abs(Color.green(c1) - Color.green(c2))
        val db = kotlin.math.abs(Color.blue(c1) - Color.blue(c2))
        return dr <= 12 && dg <= 12 && db <= 12
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun blendColor(c1: Int, c2: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).roundToInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).roundToInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).roundToInt()
        return Color.rgb(r, g, b)
    }

    companion object {
        private const val DIAL_SIZE_PX = 520
        private const val CX = 260f
        private const val CY = 260f
    }
}
