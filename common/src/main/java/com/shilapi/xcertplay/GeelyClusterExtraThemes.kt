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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure SVG-backed dial dispatcher for the 6 mecha/tactical Geely cluster themes:
 * - [GeelyClusterThemeStyle.AERO_MECHA] (`assets/cluster-visual/themes/aero-mecha/`)
 * - [GeelyClusterThemeStyle.HYPER_WING], [GeelyClusterThemeStyle.VALKYRIE_HEX],
 *   [GeelyClusterThemeStyle.ORBIT_RADAR], [GeelyClusterThemeStyle.TITAN_CORE],
 *   [GeelyClusterThemeStyle.PHANTOM_STEALTH] delegated to [GeelyClusterMechaThemesRenderer]
 *
 * All static dial faces, bezels, HUD frames, and armor wings are loaded directly from editable
 * SVG assets via [GeelyVectorSvgCache]. Kotlin only renders real-time dynamic telemetry overlays.
 */
internal class GeelyClusterExtraThemesRenderer(private val context: Context) {
    private val mechaThemes = GeelyClusterMechaThemesRenderer(context)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val rectF = RectF()
    private val outerRectF = RectF()
    private val innerRectF = RectF()
    private val workPath = Path()
    private val clipPath = Path()

    private val sansBoldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun speedMaxFor(style: GeelyClusterThemeStyle): Float = when (style) {
        GeelyClusterThemeStyle.HYPER_WING -> 240f
        GeelyClusterThemeStyle.AERO_MECHA,
        GeelyClusterThemeStyle.VALKYRIE_HEX,
        GeelyClusterThemeStyle.TITAN_CORE,
        GeelyClusterThemeStyle.PHANTOM_STEALTH -> 220f
        GeelyClusterThemeStyle.ORBIT_RADAR -> 200f
    }

    fun rpmMaxFor(style: GeelyClusterThemeStyle): Float = when (style) {
        GeelyClusterThemeStyle.AERO_MECHA,
        GeelyClusterThemeStyle.HYPER_WING,
        GeelyClusterThemeStyle.VALKYRIE_HEX,
        GeelyClusterThemeStyle.TITAN_CORE,
        GeelyClusterThemeStyle.PHANTOM_STEALTH -> 7500f
        GeelyClusterThemeStyle.ORBIT_RADAR -> 7000f
    }

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
        if (style != GeelyClusterThemeStyle.AERO_MECHA) {
            mechaThemes.drawLeftSpeedDial(
                canvas = canvas,
                style = style,
                rawSpeed = rawSpeed,
                curSpeedFrac = curSpeedFrac,
                rawFuel = rawFuel,
                curFuelFrac = curFuelFrac,
                accentColor = accentColor,
                nightMode = nightMode,
            )
            return
        }
        val lightMode = style.supportsLightMode && !nightMode
        drawAeroMechaStaticSvgFace(canvas, isRight = false, lightMode = lightMode, accentColor = accentColor)
        drawAeroMechaDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
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
        if (style != GeelyClusterThemeStyle.AERO_MECHA) {
            mechaThemes.drawRightTachDial(
                canvas = canvas,
                style = style,
                rawRpm = rawRpm,
                curRpmFrac = curRpmFrac,
                gearText = gearText,
                accentColor = accentColor,
                nightMode = nightMode,
                centerArtwork = centerArtwork,
            )
            return
        }
        val lightMode = style.supportsLightMode && !nightMode
        drawAeroMechaStaticSvgFace(canvas, isRight = true, lightMode = lightMode, accentColor = accentColor)
        drawAeroMechaDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
    }

    // -------------------------------------------------------------------------
    // AERO_MECHA SVG Asset Loader & Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawAeroMechaStaticSvgFace(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
    ) {
        val kind = if (isRight) "tach" else "speed"
        val assetPath = if (lightMode) {
            "cluster-visual/themes/aero-mecha/${kind}_day.svg"
        } else {
            "cluster-visual/themes/aero-mecha/${kind}_dial.svg"
        }
        val tint = accentColor and 0xFFF8F8F8.toInt()
        GeelyVectorSvgCache.getOrLoad(context, assetPath, DIAL_SIZE_PX, DIAL_SIZE_PX, tintColor = tint)?.let {
            canvas.drawPicture(it)
        }
    }

    private fun drawAeroMechaDynamic(
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
        val hotColor = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(255, 77, 46)

        // 1. 28 segmented trapezoidal LED blocks
        val segments = 28
        val activeCount = (curFrac * segments).roundToInt().coerceIn(0, segments)
        val stepDeg = 270f / segments
        val gapDeg = 2.2f

        for (i in 0 until segments) {
            val startDeg = 135f + i * stepDeg + gapDeg / 2f
            val sweepSeg = stepDeg - gapDeg
            val isLit = i < activeCount
            val isHot = (isRight && i >= 22) || (!isRight && i >= 23)
            val rIn = if (isLit) 186f else 191f
            val rOut = if (isLit) 224f else 219f

            if (isLit && i == activeCount - 1) {
                val haloCol = if (isHot) hotColor else accentColor
                drawAnnularSector(canvas, cx, cy, rIn - 4f, rOut + 4f, startDeg - 0.6f, sweepSeg + 1.2f, withAlpha(haloCol, if (lightMode) 68 else 90))
            }

            val blockCol = when {
                isLit && isHot -> hotColor
                isLit -> accentColor
                isHot -> Color.argb(if (lightMode) 56 else 52, 239, 68, 68)
                else -> if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(42, 148, 163, 184)
            }
            drawAnnularSector(canvas, cx, cy, rIn, rOut, startDeg, sweepSeg, blockCol)
        }

        // 2. Minimal tactical numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "40", "80", "120", "160", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 147f,
            labels = labels,
            typeface = sansBoldItalic,
            textSize = 17f,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(200, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 3. Center bold-italic numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 164f, 14f, centerArtwork, accentColor)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBoldItalic
            textPaint.textSize = 104f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx - 4f, cy + 26f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 16f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "AERO // TACTICAL"), cx, cy + 58f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx - 4f, cy + 26f, textPaint)
                textPaint.typeface = monoBold
                textPaint.textSize = 20f
                textPaint.color = accentColor
                canvas.drawText("KM/H", cx, cy + 58f, textPaint)
            }
        }

        // 4. Bottom tactical fuel blocks / hexagonal RPM readout (frame is in tach SVG)
        if (!isRight) {
            val segs = 10
            val litSegs = if (rawFuel != null) (curFuelFrac * segs).roundToInt().coerceIn(0, segs) else 0
            val sx = cx - 74f
            val sy = 434f
            val fuelColor = if (rawFuel != null && rawFuel <= 15f) hotColor else accentColor
            val dimSegColor = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(56, 148, 163, 184)
            for (i in 0 until segs) {
                fillPaint.color = if (i < litSegs) fuelColor else dimSegColor
                canvas.drawRect(sx + i * 15.2f, sy, sx + i * 15.2f + 11f, sy + 7f, fillPaint)
            }
            val label = if (rawFuel != null) "FUEL ${rawFuel.roundToInt()}%" else "FUEL --%"
            val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
            drawFuelIconAndCustomText(canvas, cx, 401f, label, txtCol, monoBold, 18.5f, 19)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 429f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Shared Dynamic Helpers
    // -------------------------------------------------------------------------

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

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    companion object {
        private const val DIAL_SIZE_PX = 520
        private const val CX = 260f
        private const val CY = 260f
    }
}
