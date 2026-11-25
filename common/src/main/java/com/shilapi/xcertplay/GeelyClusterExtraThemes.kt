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
 * Pure SVG-backed renderers for the 5 extended CarPlay Ultra cluster themes:
 * - [GeelyClusterThemeStyle.SPORT_CHRONO] (`assets/cluster-visual/themes/sport-chrono/`)
 * - [GeelyClusterThemeStyle.HERITAGE_GOLD] (`assets/cluster-visual/themes/heritage-gold/`)
 * - [GeelyClusterThemeStyle.AERO_MECHA] (`assets/cluster-visual/themes/aero-mecha/`)
 * - [GeelyClusterThemeStyle.NORDIC_GLASS] (`assets/cluster-visual/themes/nordic-glass/`)
 * - [GeelyClusterThemeStyle.RETRO_VFD] (`assets/cluster-visual/themes/retro-vfd/`)
 *
 * All static dial faces, bezels, carbon textures, guilloché tracks, HUD frames, glass rims,
 * VFD dot matrices, and ticks are loaded directly from editable SVG assets via
 * [GeelyVectorSvgCache]. Kotlin only renders real-time dynamic telemetry overlays.
 */
internal class GeelyClusterExtraThemesRenderer(private val context: Context) {
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
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansMediumBold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansBoldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val serifNormal = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun speedMaxFor(style: GeelyClusterThemeStyle): Float = when (style) {
        GeelyClusterThemeStyle.SPORT_CHRONO -> 240f
        GeelyClusterThemeStyle.HERITAGE_GOLD -> 200f
        GeelyClusterThemeStyle.AERO_MECHA -> 220f
        GeelyClusterThemeStyle.NORDIC_GLASS -> 180f
        GeelyClusterThemeStyle.RETRO_VFD -> 200f
        else -> 180f
    }

    fun rpmMaxFor(style: GeelyClusterThemeStyle): Float = when (style) {
        GeelyClusterThemeStyle.SPORT_CHRONO -> 8000f
        GeelyClusterThemeStyle.AERO_MECHA -> 7500f
        else -> 7000f
    }

    fun isExtraTheme(style: GeelyClusterThemeStyle): Boolean = when (style) {
        GeelyClusterThemeStyle.SPORT_CHRONO,
        GeelyClusterThemeStyle.HERITAGE_GOLD,
        GeelyClusterThemeStyle.AERO_MECHA,
        GeelyClusterThemeStyle.NORDIC_GLASS,
        GeelyClusterThemeStyle.RETRO_VFD -> true
        else -> false
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
        val lightMode = style.supportsLightMode && !nightMode
        drawStaticSvgFace(canvas, style, isRight = false, lightMode = lightMode, accentColor = accentColor)
        when (style) {
            GeelyClusterThemeStyle.SPORT_CHRONO ->
                drawSportChronoDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.HERITAGE_GOLD ->
                drawHeritageGoldDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.AERO_MECHA ->
                drawAeroMechaDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.NORDIC_GLASS ->
                drawNordicGlassDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
            GeelyClusterThemeStyle.RETRO_VFD ->
                drawRetroVfdDynamic(canvas, false, lightMode, accentColor, rawSpeed, 0f, curSpeedFrac, rawFuel, curFuelFrac, "D", null)
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
            GeelyClusterThemeStyle.SPORT_CHRONO ->
                drawSportChronoDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.HERITAGE_GOLD ->
                drawHeritageGoldDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.AERO_MECHA ->
                drawAeroMechaDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.NORDIC_GLASS ->
                drawNordicGlassDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
            GeelyClusterThemeStyle.RETRO_VFD ->
                drawRetroVfdDynamic(canvas, true, lightMode, accentColor, 0f, rawRpm, curRpmFrac, null, 0f, gearText, centerArtwork)
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
        val assetPath = dialAssetPath(style, isRight, lightMode)
        if (assetPath.isEmpty()) return
        val tint = when (style) {
            GeelyClusterThemeStyle.HERITAGE_GOLD,
            GeelyClusterThemeStyle.AERO_MECHA,
            GeelyClusterThemeStyle.RETRO_VFD -> accentColor and 0xFFF8F8F8.toInt()
            else -> null
        }
        GeelyVectorSvgCache.getOrLoad(context, assetPath, DIAL_SIZE_PX, DIAL_SIZE_PX, tintColor = tint)?.let {
            canvas.drawPicture(it)
        }
    }

    private fun dialAssetPath(
        style: GeelyClusterThemeStyle,
        isRight: Boolean,
        lightMode: Boolean,
    ): String {
        val kind = if (isRight) "tach" else "speed"
        val mode = if (lightMode) "day" else "night"
        return when (style) {
            GeelyClusterThemeStyle.SPORT_CHRONO -> "cluster-visual/themes/sport-chrono/${kind}_${mode}.svg"
            GeelyClusterThemeStyle.HERITAGE_GOLD -> "cluster-visual/themes/heritage-gold/${kind}_${mode}.svg"
            GeelyClusterThemeStyle.AERO_MECHA -> if (lightMode) {
                "cluster-visual/themes/aero-mecha/${kind}_day.svg"
            } else {
                "cluster-visual/themes/aero-mecha/${kind}_dial.svg"
            }
            GeelyClusterThemeStyle.NORDIC_GLASS -> "cluster-visual/themes/nordic-glass/${kind}_${mode}.svg"
            GeelyClusterThemeStyle.RETRO_VFD -> if (lightMode) {
                "cluster-visual/themes/retro-vfd/${kind}_day.svg"
            } else {
                "cluster-visual/themes/retro-vfd/${kind}_dial.svg"
            }
            else -> ""
        }
    }

    // -------------------------------------------------------------------------
    // Theme 1: SPORT_CHRONO Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawSportChronoDynamic(
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
        val sweepDeg = (curFrac * 270f).coerceIn(0f, 270f)
        val activeCol = if (isRight && rawRpm >= 6000f) Color.rgb(239, 68, 68) else accentColor

        // 1. Dynamic sweep trail + outer accent ring
        if (sweepDeg > 0.8f) {
            strokePaint.strokeCap = Paint.Cap.BUTT
            strokePaint.strokeWidth = 26f
            strokePaint.color = withAlpha(activeCol, if (lightMode) 60 else 72)
            rectF.set(cx - 212f, cy - 212f, cx + 212f, cy + 212f)
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)

            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = 4.5f
            strokePaint.color = activeCol
            rectF.set(cx - 225f, cy - 225f, cx + 225f, cy + 225f)
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)
        }

        // 2. Radial scale numerals (SVG parser intentionally skips <text>)
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7", "8")
        } else {
            listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180", "200", "220", "240")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 176f,
            labels = labels,
            typeface = sansBold,
            textSize = 20f,
            normalColor = if (lightMode) Color.rgb(30, 41, 59) else Color.argb(235, 241, 245, 249),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = Color.rgb(239, 68, 68),
        )

        // 3. Top 9 F1/GT3 shift lights on tachometer
        if (isRight) {
            val leds = 9
            for (i in 0 until leds) {
                val t = i.toFloat() / (leds - 1)
                val rad = Math.toRadians((-114f + t * 48f).toDouble())
                val lx = cx + cos(rad).toFloat() * 134f
                val ly = cy + sin(rad).toFloat() * 134f
                val lit = rawRpm >= 2200f + t * 4600f
                val ledColor = when {
                    i >= 7 -> Color.rgb(239, 68, 68)
                    i >= 4 -> Color.rgb(245, 158, 11)
                    else -> Color.rgb(34, 197, 94)
                }
                if (lit) {
                    fillPaint.color = withAlpha(ledColor, 95)
                    canvas.drawCircle(lx, ly, 7.8f, fillPaint)
                    fillPaint.color = ledColor
                    canvas.drawCircle(lx, ly, 4.2f, fillPaint)
                } else {
                    fillPaint.color = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(56, 148, 163, 184)
                    canvas.drawCircle(lx, ly, 4.0f, fillPaint)
                }
            }
        }

        // 4. Floating glowing sport needle
        val save = canvas.save()
        canvas.rotate(135f + sweepDeg, cx, cy)
        workPath.reset()
        workPath.moveTo(cx + 114f, cy - 6.5f)
        workPath.lineTo(cx + 224f, cy - 2.6f)
        workPath.lineTo(cx + 224f, cy + 2.6f)
        workPath.lineTo(cx + 114f, cy + 6.5f)
        workPath.close()
        fillPaint.color = withAlpha(activeCol, 95)
        canvas.drawPath(workPath, fillPaint)

        workPath.reset()
        workPath.moveTo(cx + 116f, cy - 4.4f)
        workPath.lineTo(cx + 222f, cy - 1.5f)
        workPath.lineTo(cx + 222f, cy + 1.5f)
        workPath.lineTo(cx + 116f, cy + 4.4f)
        workPath.close()
        fillPaint.color = activeCol
        canvas.drawPath(workPath, fillPaint)

        workPath.reset()
        workPath.moveTo(cx + 126f, cy - 1.4f)
        workPath.lineTo(cx + 219f, cy - 0.7f)
        workPath.lineTo(cx + 219f, cy + 0.7f)
        workPath.lineTo(cx + 126f, cy + 1.4f)
        workPath.close()
        fillPaint.color = Color.WHITE
        canvas.drawPath(workPath, fillPaint)
        canvas.restoreToCount(save)

        // 5. Center telemetry / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 166f, 24f, centerArtwork, withAlpha(accentColor, 150))
        } else {
            strokePaint.strokeWidth = 1.5f
            strokePaint.color = if (lightMode) Color.argb(32, 15, 23, 42) else Color.argb(26, 255, 255, 255)
            canvas.drawCircle(cx, cy, 114f, strokePaint)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansBold
            textPaint.textSize = 98f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = sansMediumBold
                textPaint.textSize = 20f
                textPaint.color = accentColor
                canvas.drawText(gearSubtitle(gearText, "SPORT · DRIVE"), cx, cy + 64f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = sansMedium
                textPaint.textSize = 22f
                textPaint.color = if (lightMode) Color.rgb(100, 116, 139) else Color.argb(200, 203, 213, 225)
                canvas.drawText("km/h", cx, cy + 62f, textPaint)
            }
        }

        // 6. Bottom fuel telemetry / RPM pill
        if (!isRight) {
            if (rawFuel != null && curFuelFrac > 0.01f) {
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = 6f
                strokePaint.color = if (rawFuel <= 15f) Color.rgb(239, 68, 68) else accentColor
                rectF.set(cx - 214f, cy - 214f, cx + 214f, cy + 214f)
                canvas.drawArc(rectF, 122f, -64f * curFuelFrac, false, strokePaint)
            }
            val txtCol = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(228, 241, 245, 249)
            drawFuelIconAndText(canvas, cx, 412f, rawFuel, txtCol, sansMediumBold, 22f)
        } else {
            fillPaint.color = if (lightMode) Color.argb(20, 15, 23, 42) else Color.argb(22, 255, 255, 255)
            rectF.set(cx - 72f, 412f, cx + 72f, 446f)
            canvas.drawRoundRect(rectF, 17f, 17f, fillPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 19.5f
            textPaint.color = if (lightMode) Color.rgb(30, 41, 59) else Color.argb(232, 241, 245, 249)
            canvas.drawText("RPM ${rawRpm.roundToInt()}", cx, 436f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 2: HERITAGE_GOLD Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawHeritageGoldDynamic(
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
        val sweepDeg = (curFrac * 270f).coerceIn(0f, 270f)
        val gold = accentColor

        // 1. Soft gold sector sweep + railway highlight arc
        if (sweepDeg > 0.8f) {
            strokePaint.strokeCap = Paint.Cap.BUTT
            strokePaint.strokeWidth = 54f
            strokePaint.color = withAlpha(gold, if (lightMode) 56 else 52)
            rectF.set(cx - 184f, cy - 184f, cx + 184f, cy + 184f)
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)

            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.strokeWidth = 5f
            strokePaint.color = if (isRight && rawRpm >= 6000f) Color.rgb(200, 75, 49) else gold
            rectF.set(cx - 217f, cy - 217f, cx + 217f, cy + 217f)
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)
        }

        // 2. Serif scale labels
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180", "200")
        }
        drawRadialScaleLabels(
            canvas = canvas,
            radius = 182f,
            labels = labels,
            typeface = serifBold,
            textSize = 22f,
            normalColor = if (lightMode) Color.rgb(44, 37, 27) else Color.rgb(243, 234, 216),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = Color.rgb(200, 75, 49),
        )

        // 3. Dauphine faceted two-tone watch hand
        val save = canvas.save()
        canvas.rotate(135f + sweepDeg, cx, cy)
        workPath.reset()
        workPath.moveTo(cx + 126f, cy)
        workPath.lineTo(cx + 148f, cy - 5.5f)
        workPath.lineTo(cx + 222f, cy)
        workPath.close()
        fillPaint.color = if (lightMode) blendColor(gold, Color.WHITE, 0.22f) else blendColor(gold, Color.WHITE, 0.45f)
        canvas.drawPath(workPath, fillPaint)

        workPath.reset()
        workPath.moveTo(cx + 126f, cy)
        workPath.lineTo(cx + 148f, cy + 5.5f)
        workPath.lineTo(cx + 222f, cy)
        workPath.close()
        fillPaint.color = if (lightMode) blendColor(gold, Color.BLACK, 0.25f) else blendColor(gold, Color.BLACK, 0.22f)
        canvas.drawPath(workPath, fillPaint)
        canvas.restoreToCount(save)

        // 4. Center serif numeral / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 4f, 166f, 22f, centerArtwork, withAlpha(gold, 185))
        } else {
            strokePaint.strokeWidth = 1.6f
            strokePaint.color = withAlpha(gold, 140)
            canvas.drawCircle(cx, cy, 122f, strokePaint)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = serifNormal
            textPaint.textSize = 100f
            textPaint.color = if (lightMode) Color.rgb(35, 29, 20) else Color.rgb(253, 248, 238)
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = serifBold
                textPaint.textSize = 17f
                textPaint.color = gold
                canvas.drawText(gearSubtitle(gearText, "GRAND TOURING"), cx, cy + 62f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = serifNormal
                textPaint.textSize = 20f
                textPaint.color = if (lightMode) Color.rgb(115, 101, 78) else withAlpha(gold, 210)
                canvas.drawText("km/h", cx, cy + 60f, textPaint)
            }
        }

        // 5. Bottom fuel / RPM
        if (!isRight) {
            if (rawFuel != null && curFuelFrac > 0.01f) {
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = 4.5f
                strokePaint.color = if (rawFuel <= 15f) Color.rgb(200, 75, 49) else gold
                rectF.set(cx - 217f, cy - 217f, cx + 217f, cy + 217f)
                canvas.drawArc(rectF, 118f, -56f * curFuelFrac, false, strokePaint)
            }
            val txtCol = if (lightMode) Color.rgb(75, 62, 43) else Color.rgb(235, 216, 179)
            drawFuelIconAndText(canvas, cx, 408f, rawFuel, txtCol, serifNormal, 21f)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = serifNormal
            textPaint.textSize = 20f
            textPaint.color = if (lightMode) Color.rgb(75, 62, 43) else Color.rgb(235, 216, 179)
            canvas.drawText("RPM  ${rawRpm.roundToInt()}", cx, 428f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 3: AERO_MECHA Dynamic Overlay
    // -------------------------------------------------------------------------

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
    // Theme 4: NORDIC_GLASS Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawNordicGlassDynamic(
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
        val sweepDeg = (curFrac * 270f).coerceIn(0f, 270f)
        val activeCol = if (isRight && rawRpm >= 5800f) Color.rgb(249, 115, 22) else accentColor

        // 1. Soft aurora glow + laser arc + orbital glowing dot
        if (sweepDeg > 0.5f) {
            strokePaint.strokeCap = Paint.Cap.ROUND
            rectF.set(cx - 202f, cy - 202f, cx + 202f, cy + 202f)

            strokePaint.strokeWidth = 26f
            strokePaint.color = withAlpha(activeCol, if (lightMode) 64 else 88)
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)

            strokePaint.strokeWidth = 8f
            strokePaint.color = activeCol
            canvas.drawArc(rectF, 135f, sweepDeg, false, strokePaint)

            val endRad = Math.toRadians((135f + sweepDeg).toDouble())
            val ox = cx + cos(endRad).toFloat() * 202f
            val oy = cy + sin(endRad).toFloat() * 202f
            fillPaint.color = withAlpha(activeCol, 110)
            canvas.drawCircle(ox, oy, 12.5f, fillPaint)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(ox, oy, 7.2f, fillPaint)
        }

        // 2. Dynamic minimalist orbital dots
        val dots = if (isRight) 8 else 10
        for (i in 0 until dots) {
            val ratio = i.toFloat() / (dots - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val passed = ratio <= curFrac + 0.01f
            fillPaint.color = when {
                passed -> if (lightMode) activeCol else Color.WHITE
                lightMode -> Color.argb(90, 71, 85, 105)
                else -> Color.argb(82, 203, 213, 225)
            }
            canvas.drawCircle(cx + cos(rad).toFloat() * 174f, cy + sin(rad).toFloat() * 174f, if (passed) 3.2f else 2.2f, fillPaint)
        }

        // 3. Center airy numeral / large-radius album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            val borderCol = if (lightMode) Color.argb(230, 255, 255, 255) else Color.argb(72, 255, 255, 255)
            drawRoundedArtwork(canvas, cx, cy - 6f, 174f, 34f, centerArtwork, borderCol)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansLight
            textPaint.textSize = 114f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            if (isRight) {
                canvas.drawText(gearText, cx, cy + 28f, textPaint)
                textPaint.typeface = sansMedium
                textPaint.textSize = 19f
                textPaint.color = if (lightMode) Color.rgb(100, 116, 139) else Color.argb(185, 203, 213, 225)
                canvas.drawText(gearSubtitle(gearText, "COMFORT"), cx, cy + 62f, textPaint)
            } else {
                canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 28f, textPaint)
                textPaint.typeface = sansLight
                textPaint.textSize = 22f
                textPaint.color = if (lightMode) Color.rgb(100, 116, 139) else Color.argb(185, 203, 213, 225)
                canvas.drawText("km/h", cx, cy + 62f, textPaint)
            }
        }

        // 4. Bottom fuel / RPM
        if (!isRight) {
            if (rawFuel != null && curFuelFrac > 0.01f) {
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = 5f
                strokePaint.color = if (rawFuel <= 15f) Color.rgb(239, 68, 68) else activeCol
                rectF.set(cx - 202f, cy - 202f, cx + 202f, cy + 202f)
                canvas.drawArc(rectF, 118f, -56f * curFuelFrac, false, strokePaint)
            }
            val txtCol = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(226, 241, 245, 249)
            drawFuelIconAndText(canvas, cx, 405f, rawFuel, txtCol, sansMedium, 21f)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = sansLight
            textPaint.textSize = 21f
            textPaint.color = if (lightMode) Color.rgb(71, 85, 105) else Color.argb(210, 226, 232, 240)
            canvas.drawText("${rawRpm.roundToInt()} RPM", cx, 425f, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Theme 5: RETRO_VFD Dynamic Overlay
    // -------------------------------------------------------------------------

    private fun drawRetroVfdDynamic(
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
        val amber = if (lightMode) Color.rgb(217, 119, 6) else Color.rgb(255, 176, 32)
        val danger = if (lightMode) Color.rgb(220, 38, 38) else Color.rgb(255, 77, 79)

        // 1. 36 stepped-height VFD / reflective LCD bars
        val bars = 36
        val activeBars = (curFrac * bars).roundToInt().coerceIn(0, bars)
        strokePaint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until bars) {
            val ratio = i.toFloat() / (bars - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val lit = i < activeBars
            val rIn = 186f
            val rOut = 204f + ratio * 18f
            val barCol = when {
                isRight && ratio >= 0.82f -> danger
                ratio >= 0.62f -> amber
                else -> accentColor
            }
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            if (lit) {
                if (!lightMode) {
                    strokePaint.strokeWidth = 9.5f
                    strokePaint.color = withAlpha(barCol, 68)
                    canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
                }
                strokePaint.strokeWidth = 6.0f
                strokePaint.color = barCol
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
            } else {
                strokePaint.strokeWidth = 6.0f
                strokePaint.color = withAlpha(barCol, if (lightMode) 48 else 42)
                canvas.drawLine(cx + cosA * rIn, cy + sinA * rIn, cx + cosA * rOut, cy + sinA * rOut, strokePaint)
            }
        }

        // 2. Monospace scale numerals
        val labels = if (isRight) {
            listOf("0", "1", "2", "3", "4", "5", "6", "7")
        } else {
            listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180", "200")
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = monoBold
        textPaint.textSize = 17f
        for (i in labels.indices) {
            val ratio = i.toFloat() / (labels.size - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            textPaint.color = when {
                isRight && i >= 6 -> danger
                ratio >= 0.65f -> amber
                lightMode -> Color.rgb(30, 48, 40)
                else -> withAlpha(accentColor, 226)
            }
            canvas.drawText(labels[i], cx + cos(rad).toFloat() * 162f, cy + sin(rad).toFloat() * 162f + 6f, textPaint)
        }

        // 3. Center VFD / LCD digital readout / album art
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, cx, cy - 6f, 162f, 12f, centerArtwork, accentColor)
        } else {
            strokePaint.strokeWidth = 1.5f
            strokePaint.color = withAlpha(accentColor, if (lightMode) 120 else 85)
            rectF.set(cx - 104f, cy - 64f, cx + 104f, cy + 64f)
            canvas.drawRoundRect(rectF, 12f, 12f, strokePaint)

            val mainNum = if (isRight) gearText else String.format(Locale.US, "%03d", rawSpeed.roundToInt().coerceIn(0, 999))
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 90f
            if (!lightMode) {
                textPaint.color = withAlpha(accentColor, 72)
                canvas.drawText(mainNum, cx, cy + 28f, textPaint)
            }
            textPaint.color = if (lightMode) Color.rgb(18, 36, 28) else accentColor
            canvas.drawText(mainNum, cx, cy + 26f, textPaint)

            textPaint.textSize = 17f
            textPaint.color = amber
            canvas.drawText(if (isRight) gearSubtitle(gearText, "GEAR · AUTO") else "DIGITAL KM/H", cx, cy + 54f, textPaint)
        }

        // 4. Bottom retro segmented fuel bar / 4-digit RPM
        if (!isRight) {
            val blocks = 12
            val lit = if (rawFuel != null) (curFuelFrac * blocks).roundToInt().coerceIn(0, blocks) else 0
            val startX = cx - 92f
            textPaint.typeface = monoBold
            textPaint.textSize = 16f
            textPaint.color = amber
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("E", startX - 8f, 434f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("F", startX + blocks * 15.5f + 4f, 434f, textPaint)

            val dimBlock = if (lightMode) Color.argb(46, 30, 48, 40) else Color.argb(32, 255, 255, 255)
            for (i in 0 until blocks) {
                fillPaint.color = if (i < lit) {
                    when {
                        i < 2 -> danger
                        i < 4 -> amber
                        else -> accentColor
                    }
                } else {
                    dimBlock
                }
                canvas.drawRect(startX + i * 15.5f, 420f, startX + i * 15.5f + 11f, 436f, fillPaint)
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 18f
            textPaint.color = if (lightMode) Color.rgb(30, 48, 40) else withAlpha(accentColor, 220)
            val label = if (rawFuel != null) "FUEL ${rawFuel.roundToInt()}%" else "FUEL --%"
            canvas.drawText(label, cx, 408f, textPaint)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = monoBold
            textPaint.textSize = 20f
            textPaint.color = amber
            canvas.drawText(String.format(Locale.US, "RPM %04d", rawRpm.roundToInt().coerceIn(0, 9999)), cx, 428f, textPaint)
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
        drawFuelIconAndCustomText(canvas, cx, iconTop, label, color, typeface, textSize, 21)
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
