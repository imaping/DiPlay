package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Multi-theme SVG-backed Geely cluster coordinator: draws ambient edge vignettes,
 * standby large digital clock, floating bottom music/status pill, and delegates the
 * damped 520x520 left/right dials to [GeelyClusterExtraThemesRenderer].
 */
internal class GeelyUltraThemeRenderer(context: Context) {
    private val themeDials = GeelyClusterExtraThemesRenderer(context)
    private val leftMainDamped = GeelyDampedFloat(durationMs = 200L)
    private val leftSubDamped = GeelyDampedFloat(durationMs = 700L)
    private val rightMainDamped = GeelyDampedFloat(durationMs = 200L)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clockTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockDateZhFormatter = SimpleDateFormat("M月d日  ·  EEEE", Locale.CHINA)
    private val clockDateIntlFormatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    fun reset() {
        leftMainDamped.reset()
        leftSubDamped.reset()
        rightMainDamped.reset()
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
        val useLightPalette = style.supportsLightMode && !nightMode
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
        fillPaint.shader = null
    }

    /**
     * Centered large digital clock (`HH:mm`) + date/weekday when standby or when CarPlay is not
     * connected, styled to match the active theme's typography and day/night palette.
     */
    fun drawStandbyClock(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        accentColor: Int = style.defaultAccentColor(nightMode),
        fuelText: String? = null,
    ) {
        val useLightPalette = style.supportsLightMode && !nightMode
        val now = Date()
        val timeText = clockTimeFormatter.format(now)
        val locale = Locale.getDefault()
        val dateText = if (locale.language.equals("zh", ignoreCase = true)) {
            clockDateZhFormatter.format(now)
        } else {
            clockDateIntlFormatter.format(now)
        }

        val centerX = 960f
        val timeBaseY = 336f
        val dividerY = 366f
        val dateBaseY = 410f

        val timeColor = if (useLightPalette) Color.rgb(24, 32, 44) else Color.argb(246, 248, 250, 252)
        val dateColor = if (useLightPalette) Color.rgb(71, 85, 105) else Color.argb(210, 203, 213, 225)

        val timeTypeface = when (style) {
            GeelyClusterThemeStyle.PHANTOM_STEALTH -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            GeelyClusterThemeStyle.AERO_MECHA,
            GeelyClusterThemeStyle.HYPER_WING -> Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
            GeelyClusterThemeStyle.ORBIT_RADAR -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            GeelyClusterThemeStyle.VALKYRIE_HEX,
            GeelyClusterThemeStyle.TITAN_CORE -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        val dateTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        // Theme-specific subtle framing accents around the center clock
        when (style) {
            GeelyClusterThemeStyle.AERO_MECHA -> {
                strokePaint.strokeWidth = 2.2f
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.color = withAlpha(accentColor, if (useLightPalette) 145 else 150)
                canvas.drawLine(centerX - 186f, 234f, centerX - 166f, 234f, strokePaint)
                canvas.drawLine(centerX - 186f, 234f, centerX - 186f, 420f, strokePaint)
                canvas.drawLine(centerX - 186f, 420f, centerX - 166f, 420f, strokePaint)
                canvas.drawLine(centerX + 186f, 234f, centerX + 166f, 234f, strokePaint)
                canvas.drawLine(centerX + 186f, 234f, centerX + 186f, 420f, strokePaint)
                canvas.drawLine(centerX + 186f, 420f, centerX + 166f, 420f, strokePaint)
            }
            GeelyClusterThemeStyle.HYPER_WING,
            GeelyClusterThemeStyle.VALKYRIE_HEX,
            GeelyClusterThemeStyle.TITAN_CORE,
            GeelyClusterThemeStyle.PHANTOM_STEALTH -> {
                strokePaint.strokeWidth = 2.2f
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.color = withAlpha(accentColor, if (useLightPalette) 145 else 155)
                // Left chamfered tactical bracket
                canvas.drawLine(centerX - 170f, 224f, centerX - 196f, 246f, strokePaint)
                canvas.drawLine(centerX - 196f, 246f, centerX - 196f, 398f, strokePaint)
                canvas.drawLine(centerX - 196f, 398f, centerX - 170f, 420f, strokePaint)
                // Right chamfered tactical bracket
                canvas.drawLine(centerX + 170f, 224f, centerX + 196f, 246f, strokePaint)
                canvas.drawLine(centerX + 196f, 246f, centerX + 196f, 398f, strokePaint)
                canvas.drawLine(centerX + 196f, 398f, centerX + 170f, 420f, strokePaint)
            }
            GeelyClusterThemeStyle.ORBIT_RADAR -> {
                strokePaint.strokeWidth = 2.0f
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.color = withAlpha(accentColor, if (useLightPalette) 145 else 150)
                // Aviation HUD horizontal horizon ticks
                canvas.drawLine(centerX - 198f, 326f, centerX - 170f, 326f, strokePaint)
                canvas.drawLine(centerX - 170f, 326f, centerX - 170f, 340f, strokePaint)
                canvas.drawLine(centerX + 198f, 326f, centerX + 170f, 326f, strokePaint)
                canvas.drawLine(centerX + 170f, 326f, centerX + 170f, 340f, strokePaint)
            }
        }

        // Large digital clock (`HH:mm`)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = timeTypeface
        textPaint.textSize = 128f
        textPaint.color = timeColor
        canvas.drawText(timeText, centerX, timeBaseY, textPaint)

        // Accent hairline divider between clock and date
        strokePaint.strokeWidth = 2.4f
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = withAlpha(accentColor, if (useLightPalette) 170 else 185)
        canvas.drawLine(centerX - 92f, dividerY, centerX + 92f, dividerY, strokePaint)

        // Date & weekday (`M月d日  ·  星期X`)
        textPaint.typeface = dateTypeface
        textPaint.textSize = 28f
        textPaint.color = dateColor
        canvas.drawText(dateText, centerX, dateBaseY, textPaint)

        // Optional average fuel telemetry placed cleanly under the date so it never collides
        // with the factory QNX bottom center song/status bar (`Y: 656..720`).
        val fuel = fuelText?.trim().orEmpty()
        if (fuel.isNotEmpty()) {
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textPaint.textSize = 20f
            textPaint.color = withAlpha(dateColor, if (useLightPalette) 210 else 190)
            canvas.drawText(fuel, centerX, dateBaseY + 52f, textPaint)
        }
    }

    /**
     * Centered two-line music visualizer, real-time lyric / song title, and artist subtitle placed
     * in the clean center region below the album artwork (or standby clock), completely clear of
     * the factory QNX bottom bar (`Y: 656..720`).
     */
    fun drawCenterMusicCaption(
        canvas: Canvas,
        style: GeelyClusterThemeStyle,
        nightMode: Boolean,
        primaryLine: String?,
        secondaryLine: String?,
        isPlaying: Boolean,
        accentColor: Int,
        nowMs: Long,
        baselineY: Float,
        overArtworkBackdrop: Boolean,
    ): Boolean {
        val main = primaryLine?.trim().orEmpty()
        if (main.isEmpty()) return false

        val useLightText = overArtworkBackdrop || !(style.supportsLightMode && !nightMode)
        val primaryColor = if (useLightText) Color.argb(248, 250, 252, 255) else Color.rgb(24, 32, 44)
        val secondaryColor = if (useLightText) Color.argb(205, 220, 228, 240) else Color.rgb(71, 85, 105)
        val eqColor = if (useLightText) withAlpha(accentColor, 245) else accentColor

        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
        val displayMain = ellipsize(main, textPaint, 560f)
        val textW = textPaint.measureText(displayMain)
        val eqW = 24f
        val gap = 12f
        val totalW = eqW + gap + textW
        val startX = 960f - totalW / 2f

        val phase = (nowMs % 1200L) / 1200f * (2f * PI.toFloat())
        val h1 = if (isPlaying) 7f + 7f * (0.5f + 0.5f * sin(phase)) else 7f
        val h2 = if (isPlaying) 12f + 9f * (0.5f + 0.5f * sin(phase + 1.8f)) else 15f
        val h3 = if (isPlaying) 8f + 8f * (0.5f + 0.5f * sin(phase + 3.6f)) else 10f

        fillPaint.color = eqColor
        val barBottom = baselineY - 2f
        canvas.drawRoundRect(startX, barBottom - h1, startX + 4.5f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 8.5f, barBottom - h2, startX + 13f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 17f, barBottom - h3, startX + 21.5f, barBottom, 2f, 2f, fillPaint)

        if (overArtworkBackdrop) {
            textPaint.setShadowLayer(6f, 0f, 2f, Color.argb(170, 0, 0, 0))
        }
        textPaint.color = primaryColor
        canvas.drawText(displayMain, startX + eqW + gap, baselineY, textPaint)

        val sub = secondaryLine?.trim().orEmpty()
        if (sub.isNotEmpty() && !sub.equals(main, ignoreCase = true)) {
            textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textPaint.textSize = 20f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = secondaryColor
            val displaySub = ellipsize(sub, textPaint, 560f)
            canvas.drawText(displaySub, 960f, baselineY + 34f, textPaint)
        }
        if (overArtworkBackdrop) {
            textPaint.clearShadowLayer()
        }
        return isPlaying
    }

    /**
     * Centered bare music visualizer & song/lyrics line positioned cleanly above the factory QNX
     * bottom center media strip (`Y ≈ 656..704`, which QNX composites above Android), unified
     * across CarPlay, native head-unit music, map, album, and clock modes at `baselineY = 622f`.
     */
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
        overDarkBackdrop: Boolean = false,
    ): Boolean {
        val useLightPalette = !overDarkBackdrop && style.supportsLightMode && !nightMode

        val title = songTitle?.trim().orEmpty()
        if (title.isEmpty()) {
            val parts = listOf(fallbackTime, fallbackFuel).filter { it.isNotBlank() }
            if (parts.isEmpty()) return false
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textPaint.textSize = 21f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = if (useLightPalette) Color.rgb(36, 44, 54) else Color.argb(232, 232, 238, 246)
            if (!useLightPalette) {
                textPaint.setShadowLayer(4f, 0f, 1.5f, Color.argb(150, 0, 0, 0))
            }
            val text = ellipsize(parts.joinToString("   ·   "), textPaint, 460f)
            canvas.drawText(text, 960f, 621f, textPaint)
            if (!useLightPalette) {
                textPaint.clearShadowLayer()
            }
            return false
        }

        val textColor = if (useLightPalette) Color.rgb(24, 32, 42) else Color.argb(248, 248, 250, 253)
        val eqColor = if (useLightPalette) accentColor else withAlpha(accentColor, 245)

        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = 24f
        textPaint.textAlign = Paint.Align.LEFT
        val displayTitle = ellipsize(title, textPaint, 520f)
        val textW = textPaint.measureText(displayTitle)
        val eqW = 24f
        val gap = 10f
        val totalW = eqW + gap + textW

        val startX = 960f - totalW / 2f
        val baselineY = 622f

        val phase = (nowMs % 1200L) / 1200f * (2f * PI.toFloat())
        val h1 = if (isPlaying) 6f + 6f * (0.5f + 0.5f * sin(phase)) else 6f
        val h2 = if (isPlaying) 10f + 8f * (0.5f + 0.5f * sin(phase + 1.8f)) else 13f
        val h3 = if (isPlaying) 7f + 7f * (0.5f + 0.5f * sin(phase + 3.6f)) else 9f
        fillPaint.color = eqColor
        val barBottom = baselineY - 2f
        canvas.drawRoundRect(startX, barBottom - h1, startX + 4.5f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 8f, barBottom - h2, startX + 12.5f, barBottom, 2f, 2f, fillPaint)
        canvas.drawRoundRect(startX + 16f, barBottom - h3, startX + 20.5f, barBottom, 2f, 2f, fillPaint)

        if (!useLightPalette) {
            textPaint.setShadowLayer(5f, 0f, 1.5f, Color.argb(175, 0, 0, 0))
        }
        textPaint.color = textColor
        canvas.drawText(displayTitle, startX + eqW + gap, baselineY, textPaint)
        if (!useLightPalette) {
            textPaint.clearShadowLayer()
        }

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

        val speedMax = themeDials.speedMaxFor(style)
        val speedFrac = (rawSpeed / speedMax).coerceIn(0f, 1f)
        val fuelFrac = ((rawFuel ?: 0f) / 100f).coerceIn(0f, 1f)
        leftMainDamped.updateTarget(speedFrac, nowMs)
        if (rawFuel != null) leftSubDamped.updateTarget(fuelFrac, nowMs)

        val curSpeedFrac = leftMainDamped.valueAt(nowMs)
        val curFuelFrac = if (rawFuel != null) leftSubDamped.valueAt(nowMs) else 0f
        val anim = leftMainDamped.isAnimating(nowMs) || (rawFuel != null && leftSubDamped.isAnimating(nowMs))

        themeDials.drawLeftSpeedDial(
            canvas = canvas,
            style = style,
            rawSpeed = rawSpeed,
            curSpeedFrac = curSpeedFrac,
            rawFuel = rawFuel,
            curFuelFrac = curFuelFrac,
            accentColor = accentColor,
            nightMode = nightMode,
        )
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
        val rawRpm = sensors.rpm.valueIfFresh(nowMs) ?: if (previewDemo) 2450f else 0f
        val gearText = sensors.gearIfFresh(nowMs) ?: if (previewDemo) "D" else "P"

        val rpmMax = themeDials.rpmMaxFor(style)
        val rpmFrac = (rawRpm / rpmMax).coerceIn(0f, 1f)
        rightMainDamped.updateTarget(rpmFrac, nowMs)

        val curRpmFrac = rightMainDamped.valueAt(nowMs)
        val anim = rightMainDamped.isAnimating(nowMs)

        themeDials.drawRightTachDial(
            canvas = canvas,
            style = style,
            rawRpm = rawRpm,
            curRpmFrac = curRpmFrac,
            gearText = gearText,
            accentColor = accentColor,
            nightMode = nightMode,
            centerArtwork = centerArtwork,
        )
        return anim
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
}
