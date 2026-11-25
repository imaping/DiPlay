package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure SVG-backed multi-theme CarPlay Ultra instrument cluster renderer.
 *
 * All static dial faces, concentric rings, precision ticks, dot ticks, redline zones, and icons
 * are loaded directly from editable SVG assets under `assets/cluster-visual/themes/` via
 * [GeelyVectorSvgCache] (no duplicate Canvas fallback code for static dial faces).
 * Kotlin only draws real-time dynamic telemetry overlays (progress arcs/bands, speed/RPM/gear
 * numerals, fuel progress bar, album art, standby watermark, and bottom song visualizer).
 */
internal class GeelyUltraThemeRenderer(private val context: Context) {
    private val leftMainDamped = GeelyDampedFloat(durationMs = 200L)
    private val leftSubDamped = GeelyDampedFloat(durationMs = 700L)
    private val rightMainDamped = GeelyDampedFloat(durationMs = 200L)
    private val rightSubDamped = GeelyDampedFloat(durationMs = 700L)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val arcRect = RectF()
    private val innerArcRect = RectF()
    private val coverRect = RectF()
    private val coverClipPath = Path()
    private val curvedTextPath = Path()

    fun reset() {
        leftMainDamped.reset()
        leftSubDamped.reset()
        rightMainDamped.reset()
        rightSubDamped.reset()
    }

    /**
     * Draws ambient edge feathering (during navigation) or studio backdrop (when standby).
     */
    fun drawThemeVignette(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int,
        routeActive: Boolean,
    ) {
        val useLightPalette = style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT && !nightMode
        if (!routeActive) {
            if (useLightPalette) {
                fillPaint.shader = LinearGradient(
                    0f, 0f, 1920f, 720f,
                    intArrayOf(Color.rgb(232, 236, 240), Color.rgb(242, 244, 247), Color.rgb(228, 233, 238)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                val r = (Color.red(accentColor) * 0.14f).roundToInt().coerceIn(8, 42)
                val g = (Color.green(accentColor) * 0.14f).roundToInt().coerceIn(10, 36)
                val b = (Color.blue(accentColor) * 0.14f).roundToInt().coerceIn(14, 46)
                fillPaint.shader = RadialGradient(
                    960f, 360f, 1100f,
                    intArrayOf(Color.rgb(r + 14, g + 12, b + 16), Color.rgb(10, 12, 18), Color.rgb(6, 7, 11)),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, 1920f, 720f, fillPaint)
            fillPaint.shader = null
            return
        }

        val baseRgb = if (useLightPalette) 0x00EEF2F6 else 0x000A0D14
        fillPaint.shader = LinearGradient(
            0f, 0f, 650f, 0f,
            intArrayOf((238 shl 24) or baseRgb, (205 shl 24) or baseRgb, (90 shl 24) or baseRgb, baseRgb),
            floatArrayOf(0f, 0.52f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 650f, 720f, fillPaint)

        fillPaint.shader = LinearGradient(
            1270f, 0f, 1920f, 0f,
            intArrayOf(baseRgb, (90 shl 24) or baseRgb, (205 shl 24) or baseRgb, (238 shl 24) or baseRgb),
            floatArrayOf(0f, 0.20f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(1270f, 0f, 1920f, 720f, fillPaint)

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, 118f,
            intArrayOf((210 shl 24) or baseRgb, (95 shl 24) or baseRgb, baseRgb),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 1920f, 118f, fillPaint)

        fillPaint.shader = LinearGradient(
            0f, 640f, 0f, 720f,
            intArrayOf(baseRgb, (165 shl 24) or baseRgb, (230 shl 24) or baseRgb),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 640f, 1920f, 720f, fillPaint)
        fillPaint.shader = null
    }

    /** Centered `CarPlay Ultra` standby watermark when not navigating and no album cover is active. */
    fun drawStandbyWatermark(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
    ) {
        val useLightPalette = style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT && !nightMode
        val primaryColor = if (useLightPalette) Color.rgb(44, 50, 58) else Color.argb(238, 245, 248, 252)
        val secondaryColor = if (useLightPalette) Color.rgb(92, 102, 114) else Color.argb(200, 218, 226, 236)

        val centerX = 960f
        val baseY = 374f

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 54f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val carPlayW = textPaint.measureText("CarPlay ")
        textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val ultraW = textPaint.measureText("Ultra")
        val badgeSize = 36f
        val gap = 14f
        val startX = centerX - (badgeSize + gap + carPlayW + ultraW) / 2f

        val iconCx = startX + badgeSize / 2f
        val iconCy = baseY - 18f
        fillPaint.color = primaryColor
        canvas.drawCircle(iconCx, iconCy, 16f, fillPaint)
        fillPaint.color = if (useLightPalette) Color.rgb(236, 240, 244) else Color.rgb(12, 14, 20)
        val tri = Path().apply {
            moveTo(iconCx - 4f, iconCy - 8f)
            lineTo(iconCx + 9f, iconCy)
            lineTo(iconCx - 4f, iconCy + 8f)
            close()
        }
        canvas.drawPath(tri, fillPaint)

        textPaint.color = primaryColor
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        canvas.drawText("CarPlay", startX + badgeSize + gap, baseY, textPaint)

        textPaint.color = secondaryColor
        textPaint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        canvas.drawText("Ultra", startX + badgeSize + gap + carPlayW, baseY, textPaint)
    }

    /** Centered bottom music visualizer & song title pill (fallback to time/fuel/coolant when silent). */
    fun drawUltraBottomMusicBar(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        songTitle: String?,
        isPlaying: Boolean,
        accentColor: Int,
        nowMs: Long,
        fallbackTime: String,
        fallbackFuel: String,
        fallbackTemp: String,
    ): Boolean {
        val useLightPalette = style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT && !nightMode
        val title = songTitle?.trim().orEmpty()
        if (title.isEmpty()) {
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textPaint.textSize = 22f
            textPaint.color = if (useLightPalette) Color.argb(195, 36, 44, 54) else Color.argb(200, 224, 232, 240)
            textPaint.textAlign = Paint.Align.CENTER
            val parts = listOf(fallbackTime, fallbackFuel, fallbackTemp).filter { it.isNotBlank() }
            canvas.drawText(parts.joinToString("   ·   "), 960f, 696f, textPaint)
            return false
        }

        val textColor = if (useLightPalette) Color.rgb(34, 42, 52) else Color.argb(240, 244, 248, 252)
        val lineColor = if (useLightPalette) Color.argb(140, 60, 72, 86) else Color.argb(190, 230, 236, 244)
        val eqColor = if (useLightPalette) Color.rgb(58, 72, 88) else Color.argb(220, 235, 242, 248)

        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 26f
        textPaint.textAlign = Paint.Align.LEFT
        val displayTitle = ellipsize(title, textPaint, 420f)
        val textW = textPaint.measureText(displayTitle)
        val eqW = 24f
        val gap = 10f
        val totalW = eqW + gap + textW
        val startX = 960f - totalW / 2f
        val baselineY = 695f

        val phase = (nowMs % 1200L) / 1200f * (2f * PI.toFloat())
        val h1 = if (isPlaying) 6f + 6f * (0.5f + 0.5f * sin(phase)) else 6f
        val h2 = if (isPlaying) 10f + 8f * (0.5f + 0.5f * sin(phase + 1.8f)) else 13f
        val h3 = if (isPlaying) 7f + 7f * (0.5f + 0.5f * sin(phase + 3.6f)) else 9f
        fillPaint.color = eqColor
        val barBottom = baselineY - 2f
        canvas.drawRoundRect(startX, barBottom - h1, startX + 4.5f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 8f, barBottom - h2, startX + 12.5f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 16f, barBottom - h3, startX + 20.5f, barBottom, 2f, 2f, fillPaint)

        textPaint.color = textColor
        canvas.drawText(displayTitle, startX + eqW + gap, baselineY, textPaint)

        val underlineHalf = max(84f, totalW * 0.58f).coerceAtMost(240f)
        strokePaint.color = lineColor
        strokePaint.strokeWidth = 2.5f
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(960f - underlineHalf, 709f, 960f + underlineHalf, 709f, strokePaint)

        return isPlaying
    }

    /** Renders the left speedometer dial (`520x520`): SVG static face + dynamic speed/fuel telemetry. */
    fun drawLeftSpeedDial(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        sensors: GeelyClusterSensorSnapshot,
        accentColor: Int,
        nightMode: Boolean,
        nowMs: Long,
        previewDemo: Boolean,
    ): Boolean {
        val rawSpeed = sensors.speedKmh.valueIfFresh(nowMs) ?: if (previewDemo) 76f else 0f
        val rawFuel = sensors.fuelPercent.valueIfFresh(nowMs) ?: if (previewDemo) 68f else null

        val speedFrac = (rawSpeed / 180f).coerceIn(0f, 1f)
        val fuelFrac = ((rawFuel ?: 0f) / 100f).coerceIn(0f, 1f)
        leftMainDamped.updateTarget(speedFrac, nowMs)
        if (rawFuel != null) leftSubDamped.updateTarget(fuelFrac, nowMs)

        val curSpeedFrac = leftMainDamped.valueAt(nowMs)
        val curFuelFrac = if (rawFuel != null) leftSubDamped.valueAt(nowMs) else 0f
        val anim = leftMainDamped.isAnimating(nowMs) || (rawFuel != null && leftSubDamped.isAnimating(nowMs))

        val cx = 260f
        val cy = 260f
        val light = style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT && !nightMode
        val effectiveAccent = if (light && accentColor == GeelyColorTransition.DEFAULT_CYAN) {
            Color.rgb(54, 106, 152)
        } else {
            accentColor
        }

        // 1. Static SVG dial face (cached Picture)
        val assetPath = speedDialAssetPath(style, nightMode)
        val svgTint = if (style == GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS) effectiveAccent else null
        GeelyVectorSvgCache.getOrLoad(context, assetPath, 520, 520, tintColor = svgTint)?.let {
            canvas.drawPicture(it)
        }

        // 2. Dynamic progress band / arc + scale numerals per theme
        val sweep = curSpeedFrac * 270f
        when (style) {
            GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS -> {
                if (sweep > 0.5f) {
                    drawClassicSweepBand(canvas, cx, cy, 152f, 236f, sweep, blendWithWhite(effectiveAccent, 0.35f))
                }
                drawRadialNumerals(canvas, cx, cy, 194f, (0..180 step 20).map { it.toString() }, 21f, Color.argb(242, 246, 249, 255), bold = true)
                drawFuelTelemetry(canvas, cx, cy, 228f, curFuelFrac, rawFuel, effectiveAccent, lightMode = false)
            }
            GeelyClusterThemeStyle.ULTRA_MINIMAL_RING -> {
                if (sweep > 0.5f) {
                    drawProgressArc(canvas, cx, cy, 212f, 24f, sweep, withAlpha(effectiveAccent, 235))
                }
                drawFuelTelemetry(canvas, cx, cy, 212f, curFuelFrac, rawFuel, effectiveAccent, lightMode = false)
            }
            GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT -> {
                if (sweep > 0.5f) {
                    drawProgressArc(canvas, cx, cy, 220f, 16f, sweep, withAlpha(effectiveAccent, 240))
                }
                val numCol = if (light) Color.rgb(46, 54, 64) else Color.argb(232, 236, 242, 248)
                drawRadialNumerals(canvas, cx, cy, 178f, (0..180 step 20).map { it.toString() }, 20f, numCol, bold = false)
                drawFuelTelemetry(canvas, cx, cy, 220f, curFuelFrac, rawFuel, effectiveAccent, lightMode = light)
            }
            GeelyClusterThemeStyle.FAKE_ULTRA -> Unit
        }

        // 3. Giant live speed numeral + km/h
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", if (light) Typeface.NORMAL else Typeface.BOLD)
        textPaint.textSize = if (style == GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS) 104f else 112f
        textPaint.color = if (light) Color.rgb(34, 40, 48) else Color.WHITE
        canvas.drawText(rawSpeed.roundToInt().toString(), cx, cy + 20f, textPaint)

        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = 24f
        textPaint.color = if (light) Color.rgb(92, 102, 114) else Color.argb(195, 220, 228, 238)
        canvas.drawText("km/h", cx, cy + 58f, textPaint)

        return anim
    }

    /** Renders the right tachometer/gear/album dial (`520x520`): SVG static face + dynamic telemetry. */
    fun drawRightTachDial(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        sensors: GeelyClusterSensorSnapshot,
        accentColor: Int,
        nightMode: Boolean,
        nowMs: Long,
        previewDemo: Boolean,
        centerArtwork: Bitmap?,
    ): Boolean {
        val rawRpm = sensors.rpm.valueIfFresh(nowMs) ?: if (previewDemo) 1290f else 0f
        val rawCoolant = sensors.coolantTempC.valueIfFresh(nowMs) ?: if (previewDemo) 90f else null
        val gearText = sensors.gearIfFresh(nowMs) ?: if (previewDemo) "D" else "P"

        val rpmFrac = (rawRpm / 7000f).coerceIn(0f, 1f)
        val coolantFrac = (((rawCoolant ?: 50f) - 50f) / 80f).coerceIn(0f, 1f)
        rightMainDamped.updateTarget(rpmFrac, nowMs)
        if (rawCoolant != null) rightSubDamped.updateTarget(coolantFrac, nowMs)

        val curRpmFrac = rightMainDamped.valueAt(nowMs)
        val curCoolantFrac = if (rawCoolant != null) rightSubDamped.valueAt(nowMs) else 0f
        val anim = rightMainDamped.isAnimating(nowMs) || (rawCoolant != null && rightSubDamped.isAnimating(nowMs))

        val cx = 260f
        val cy = 260f
        val light = style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT && !nightMode
        val effectiveAccent = if (light && accentColor == GeelyColorTransition.DEFAULT_CYAN) {
            Color.rgb(54, 106, 152)
        } else {
            accentColor
        }

        // 1. Static SVG dial face (cached Picture)
        val assetPath = tachDialAssetPath(style, nightMode)
        val svgTint = if (style == GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS) effectiveAccent else null
        GeelyVectorSvgCache.getOrLoad(context, assetPath, 520, 520, tintColor = svgTint)?.let {
            canvas.drawPicture(it)
        }

        // 2. Dynamic progress band / arc + scale numerals per theme
        val sweep = curRpmFrac * 270f
        when (style) {
            GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS -> {
                if (sweep > 0.5f) {
                    val bandCol = if (rawRpm >= 5800f) Color.rgb(255, 75, 62) else blendWithWhite(effectiveAccent, 0.35f)
                    drawClassicSweepBand(canvas, cx, cy, 152f, 236f, sweep, bandCol)
                }
                drawRadialNumerals(
                    canvas, cx, cy, 194f,
                    (0..7).map { it.toString() },
                    22f, Color.argb(242, 246, 249, 255),
                    bold = true, redFromIndex = 6,
                )
                drawCurvedEngraving(canvas, cx, cy, 244f, "DEEPLY ADAPTED FOR GEELY")
            }
            GeelyClusterThemeStyle.ULTRA_MINIMAL_RING -> {
                if (sweep > 0.5f) {
                    val arcCol = if (rawRpm >= 5800f) Color.rgb(255, 76, 58) else withAlpha(effectiveAccent, 235)
                    drawProgressArc(canvas, cx, cy, 212f, 24f, sweep, arcCol)
                }
                if (curCoolantFrac > 0.02f) {
                    arcRect.set(cx - 212f, cy - 212f, cx + 212f, cy + 212f)
                    strokePaint.strokeWidth = 5f
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.color = withAlpha(effectiveAccent, 185)
                    canvas.drawArc(arcRect, 56f, 68f * curCoolantFrac, false, strokePaint)
                }
            }
            GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT -> {
                if (sweep > 0.5f) {
                    val arcCol = if (rawRpm >= 5800f) Color.rgb(232, 86, 58) else withAlpha(effectiveAccent, 240)
                    drawProgressArc(canvas, cx, cy, 220f, 16f, sweep, arcCol)
                }
                val numCol = if (light) Color.rgb(46, 54, 64) else Color.argb(232, 236, 242, 248)
                drawRadialNumerals(
                    canvas, cx, cy, 178f,
                    (0..7).map { it.toString() },
                    21f, numCol,
                    bold = false, redFromIndex = 6,
                )
                if (rawCoolant != null && curCoolantFrac > 0.02f) {
                    arcRect.set(cx - 220f, cy - 220f, cx + 220f, cy + 220f)
                    strokePaint.strokeWidth = 4.5f
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.color = if (rawCoolant >= 110f) Color.rgb(232, 86, 58) else withAlpha(effectiveAccent, 210)
                    canvas.drawArc(arcRect, 56f, 68f * curCoolantFrac, false, strokePaint)
                }
            }
            GeelyClusterThemeStyle.FAKE_ULTRA -> Unit
        }

        // 3. Bottom live RPM (+ Coolant °C when sensor is available on this car)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 22f
        textPaint.color = if (light) Color.rgb(58, 68, 80) else Color.argb(215, 232, 238, 245)
        val bottomRpmLabel = if (rawCoolant != null) {
            "RPM ${rawRpm.roundToInt()} · ${rawCoolant.roundToInt()}°C"
        } else {
            "RPM ${rawRpm.roundToInt()}"
        }
        canvas.drawText(bottomRpmLabel, cx, if (style == GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS) 444f else 426f, textPaint)

        // 4. Center: Album cover (when navigating & available) or live Gear letter
        if (centerArtwork != null && !centerArtwork.isRecycled) {
            val coverSize = if (style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT) 168f else 176f
            val cornerR = if (style == GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT) 20f else 24f
            drawCenteredRoundedCover(canvas, cx, cy - 6f, coverSize, cornerR, centerArtwork)
        } else {
            textPaint.typeface = Typeface.create("sans-serif-medium", if (light) Typeface.NORMAL else Typeface.BOLD)
            textPaint.textSize = if (style == GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS) 104f else 110f
            textPaint.color = if (light) Color.rgb(34, 40, 48) else Color.WHITE
            canvas.drawText(gearText, cx, cy + 18f, textPaint)

            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textPaint.textSize = 23f
            textPaint.color = if (light) Color.rgb(92, 102, 114) else Color.argb(185, 220, 228, 236)
            canvas.drawText(if (gearText == "P") "PARK" else "DRIVE", cx, cy + 56f, textPaint)
        }

        return anim
    }

    // -------------------------------------------------------------------------
    // Dynamic Telemetry Helpers (no static dial geometry duplication)
    // -------------------------------------------------------------------------

    private fun speedDialAssetPath(style: GeelyClusterThemeStyle, nightMode: Boolean): String = when (style) {
        GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS -> "cluster-visual/themes/classic-ticks/speed_dial.svg"
        GeelyClusterThemeStyle.ULTRA_MINIMAL_RING -> "cluster-visual/themes/minimal-ring/speed_dial.svg"
        GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT -> if (nightMode) {
            "cluster-visual/themes/clean-light/speed_night.svg"
        } else {
            "cluster-visual/themes/clean-light/speed_day.svg"
        }
        GeelyClusterThemeStyle.FAKE_ULTRA -> "cluster-visual/themes/fake-ultra/dark.svg"
    }

    private fun tachDialAssetPath(style: GeelyClusterThemeStyle, nightMode: Boolean): String = when (style) {
        GeelyClusterThemeStyle.ULTRA_CLASSIC_TICKS -> "cluster-visual/themes/classic-ticks/tach_dial.svg"
        GeelyClusterThemeStyle.ULTRA_MINIMAL_RING -> "cluster-visual/themes/minimal-ring/tach_dial.svg"
        GeelyClusterThemeStyle.ULTRA_CLEAN_LIGHT -> if (nightMode) {
            "cluster-visual/themes/clean-light/tach_night.svg"
        } else {
            "cluster-visual/themes/clean-light/tach_day.svg"
        }
        GeelyClusterThemeStyle.FAKE_ULTRA -> "cluster-visual/themes/fake-ultra/dark.svg"
    }

    private fun drawClassicSweepBand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerR: Float,
        outerR: Float,
        sweepDeg: Float,
        color: Int,
    ) {
        val bandR = (outerR + innerR) / 2f
        strokePaint.strokeWidth = (outerR - innerR) - 4f
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.color = withAlpha(color, 92)
        arcRect.set(cx - bandR, cy - bandR, cx + bandR, cy + bandR)
        canvas.drawArc(arcRect, 135f, sweepDeg, false, strokePaint)

        val rad = Math.toRadians((135f + sweepDeg).toDouble())
        strokePaint.strokeWidth = 3.5f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = Color.argb(245, 255, 255, 255)
        canvas.drawLine(
            cx + (innerR + 2f) * cos(rad).toFloat(),
            cy + (innerR + 2f) * sin(rad).toFloat(),
            cx + (outerR - 2f) * cos(rad).toFloat(),
            cy + (outerR - 2f) * sin(rad).toFloat(),
            strokePaint,
        )
    }

    private fun drawProgressArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        strokeWidth: Float,
        sweepDeg: Float,
        color: Int,
    ) {
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        strokePaint.strokeWidth = strokeWidth
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = color
        canvas.drawArc(arcRect, 135f, sweepDeg, false, strokePaint)
    }

    private fun drawRadialNumerals(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        labelRadius: Float,
        labels: List<String>,
        textSize: Float,
        normalColor: Int,
        bold: Boolean,
        redFromIndex: Int = Int.MAX_VALUE,
    ) {
        if (labels.size < 2) return
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        textPaint.textSize = textSize
        val stepDeg = 270f / (labels.size - 1)
        for (i in labels.indices) {
            val rad = Math.toRadians((135f + i * stepDeg).toDouble())
            textPaint.color = if (i >= redFromIndex) Color.rgb(232, 80, 56) else normalColor
            canvas.drawText(
                labels[i],
                cx + labelRadius * cos(rad).toFloat(),
                cy + labelRadius * sin(rad).toFloat() + textSize * 0.34f,
                textPaint,
            )
        }
    }

    private fun drawCurvedEngraving(canvas: Canvas, cx: Float, cy: Float, radius: Float, text: String) {
        curvedTextPath.reset()
        innerArcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        curvedTextPath.addArc(innerArcRect, -58f, 64f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 10.5f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.color = Color.argb(150, 224, 232, 240)
        canvas.drawTextOnPath(text, curvedTextPath, 0f, 0f, textPaint)
    }

    private fun drawFuelTelemetry(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        arcRadius: Float,
        fuelFrac: Float,
        rawFuel: Float?,
        accentColor: Int,
        lightMode: Boolean,
    ) {
        if (rawFuel != null && fuelFrac > 0.01f) {
            arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            strokePaint.strokeWidth = 5f
            strokePaint.strokeCap = Paint.Cap.ROUND
            val fuelColor = if (rawFuel <= 15f) Color.rgb(255, 82, 62) else accentColor
            strokePaint.color = withAlpha(fuelColor, 232)
            canvas.drawArc(arcRect, 124f, -68f * fuelFrac, false, strokePaint)
        }

        val label = if (rawFuel != null) "${rawFuel.roundToInt()}%" else "--%"
        val textColor = if (lightMode) Color.rgb(52, 62, 74) else Color.argb(225, 235, 240, 248)
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 22f
        textPaint.textAlign = Paint.Align.LEFT
        val textW = textPaint.measureText(label)
        val iconSize = 22
        val gap = 6f
        val startX = cx - (iconSize + gap + textW) / 2f
        val iconTop = 405f

        GeelyVectorSvgCache.getOrLoad(
            context = context,
            assetPath = "cluster-visual/themes/common/fuel_icon.svg",
            widthPx = iconSize,
            heightPx = iconSize,
            tintColor = textColor,
        )?.let { pic ->
            val save = canvas.save()
            canvas.translate(startX, iconTop)
            canvas.drawPicture(pic)
            canvas.restoreToCount(save)
        }

        textPaint.color = textColor
        canvas.drawText(label, startX + iconSize + gap, iconTop + 18f, textPaint)
    }

    private fun drawCenteredRoundedCover(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        radius: Float,
        bitmap: Bitmap,
    ) {
        val left = cx - size / 2f
        val top = cy - size / 2f
        coverRect.set(left, top, left + size, top + size)

        fillPaint.color = Color.argb(72, 0, 0, 0)
        canvas.drawRoundRect(left - 2f, top + 4f, left + size + 2f, top + size + 6f, radius, radius, fillPaint)

        val save = canvas.save()
        coverClipPath.reset()
        coverClipPath.addRoundRect(coverRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(coverClipPath)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bitmap, null, coverRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun blendWithWhite(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (Color.red(color) + (255 - Color.red(color)) * f).roundToInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * f).roundToInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * f).roundToInt()
        return Color.rgb(r, g, b)
    }
}
