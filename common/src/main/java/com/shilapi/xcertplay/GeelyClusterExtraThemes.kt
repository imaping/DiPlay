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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class GeelyDialFont {
    SANS_BOLD,
    SANS_LIGHT,
    SANS_BOLD_ITALIC,
    MONO_BOLD,
}

internal enum class GeelyClockFrameStyle {
    SQUARE_BRACKETS,
    CHAMFERED_BRACKETS,
    HORIZON_TICKS,
}

internal enum class GeelySegmentColorMode {
    ACCENT,
    ACCENT_TO_SECONDARY,
    SECONDARY_TO_ACCENT,
}

internal data class GeelySecondaryAccentSpec(
    val nightColor: Int,
    val dayColor: Int,
    val customWhiteBlend: Float,
)

internal sealed interface GeelyGaugeRingSpec {
    val mirrored: Boolean get() = false
    val spanDeg: Float get() = 270f

    fun startDeg(isRight: Boolean): Float = if (mirrored) (if (isRight) 85f else 95f) else 135f
    fun direction(isRight: Boolean): Float = if (mirrored && isRight) -1f else 1f

    data class SegmentedArc(
        val segments: Int,
        override val mirrored: Boolean = false,
        override val spanDeg: Float = if (mirrored) 232f else 270f,
        val gapDeg: Float,
        val hotFromLeft: Int,
        val hotFromRight: Int = hotFromLeft,
        val rInLit: Float,
        val rOutLit: Float,
        val rInUnlit: Float = rInLit,
        val rOutUnlit: Float = rOutLit,
        val progressiveOutSpan: Float = 0f,
        val haloInPad: Float = 3f,
        val haloOutPad: Float = 3.5f,
        val haloSweepPad: Float = 0.5f,
        val haloAlphaDay: Int = 64,
        val haloAlphaNight: Int = 92,
        val colorMode: GeelySegmentColorMode = GeelySegmentColorMode.ACCENT,
        val topShiftBars: Boolean = false,
        val innerDiamondNodes: Boolean = false,
        val outerTargetCursor: Boolean = false,
    ) : GeelyGaugeRingSpec

    data class RadialBeams(
        val beams: Int,
        val hotFromLeft: Int,
        val hotFromRight: Int,
    ) : GeelyGaugeRingSpec

    data class ArmorBlocksWithPins(
        val blocks: Int,
        val blockHotFrom: Int,
        val pins: Int,
        val pinHotFrom: Int,
    ) : GeelyGaugeRingSpec
}

internal sealed interface GeelyFuelGaugeSpec {
    data class Cells(
        val count: Int = 10,
        val startOffsetX: Float = -74f,
        val topY: Float = 434f,
        val stepX: Float = 15.2f,
        val cellWidth: Float = 11f,
        val cellHeight: Float = 7f,
        val cornerRadius: Float = 0f,
        val skew: Float = 0f,
        val dimAlphaNight: Int = 56,
        val useSecondaryColor: Boolean = false,
        val labelPrefix: String = "FUEL",
        val iconTopY: Float,
        val textSize: Float = 18f,
        val iconSize: Int = 18,
    ) : GeelyFuelGaugeSpec

    data class BottomArc(
        val radius: Float,
        val startDeg: Float,
        val maxSweepDeg: Float,
        val strokeWidth: Float,
        val iconTopY: Float,
        val textSize: Float,
        val iconSize: Int,
    ) : GeelyFuelGaugeSpec
}

internal data class GeelyClusterThemeSpec(
    val assetFolder: String,
    val nightAccentColor: Int,
    val dayAccentColor: Int,
    val secondaryAccent: GeelySecondaryAccentSpec? = null,
    val hotColorDay: Int = 0xFFDC2626.toInt(),
    val hotColorNight: Int,
    val unlitHotRgb: Int = 0xEF4444,
    val unlitHotAlphaDay: Int = 56,
    val unlitHotAlphaNight: Int,
    val unlitNormalAlphaDay: Int = 66,
    val unlitNormalAlphaNight: Int,
    val speedMax: Float = 200f,
    val speedLabels: List<String> = SPEED_LABELS_200,
    val rpmMax: Float = 7000f,
    val rpmLabels: List<String> = RPM_LABELS_7,
    val ring: GeelyGaugeRingSpec,
    val scaleRadius: Float,
    val scaleFont: GeelyDialFont,
    val scaleTextSize: Float = 17f,
    val scaleDarkAlpha: Int = 200,
    val artworkSize: Float = 164f,
    val artworkCornerRadius: Float = 16f,
    val useSecondaryForCenter: Boolean = false,
    val centerFont: GeelyDialFont,
    val centerTextSize: Float = 100f,
    val centerOffsetX: Float = 0f,
    val centerOffsetY: Float = 28f,
    val driveSubtitle: String,
    val speedSubSize: Float = 16f,
    val gearSubSize: Float = 16f,
    val subOffsetY: Float = 60f,
    val fuel: GeelyFuelGaugeSpec,
    val rpmFont: GeelyDialFont = GeelyDialFont.MONO_BOLD,
    val rpmPrefixFirst: Boolean = false,
    val rpmBaselineY: Float,
    val clockFont: GeelyDialFont,
    val clockFrame: GeelyClockFrameStyle = GeelyClockFrameStyle.CHAMFERED_BRACKETS,
) {
    fun defaultAccentColor(nightMode: Boolean): Int = if (nightMode) nightAccentColor else dayAccentColor

    companion object {
        val SPEED_LABELS_200 = listOf("0", "40", "80", "120", "160", "200")
        val SPEED_LABELS_240 = listOf("0", "40", "80", "120", "160", "200", "240")
        val RPM_LABELS_7 = listOf("0", "1", "2", "3", "4", "5", "6", "7")
    }
}

/**
 * Single source of truth for all 6 mecha/tactical Geely cluster themes.
 * Adding a new SVG-backed skin only requires adding 4 normalized SVG files
 * (`{speed,tach}_{day,night}.svg`) and registering its [GeelyClusterThemeSpec] here.
 */
internal object GeelyClusterThemeSpecs {
    private val specs: Map<GeelyClusterThemeStyle, GeelyClusterThemeSpec> = mapOf(
        GeelyClusterThemeStyle.AERO_MECHA to GeelyClusterThemeSpec(
            assetFolder = "aero-mecha",
            nightAccentColor = 0xFF00E5FF.toInt(),
            dayAccentColor = 0xFF0284C7.toInt(),
            hotColorNight = 0xFFFF4D2E.toInt(),
            unlitHotAlphaNight = 52,
            unlitNormalAlphaNight = 42,
            ring = GeelyGaugeRingSpec.SegmentedArc(
                segments = 28,
                gapDeg = 2.2f,
                hotFromLeft = 23,
                hotFromRight = 22,
                rInLit = 186f,
                rOutLit = 224f,
                rInUnlit = 191f,
                rOutUnlit = 219f,
                haloInPad = 4f,
                haloOutPad = 4f,
                haloSweepPad = 0.6f,
                haloAlphaDay = 68,
                haloAlphaNight = 90,
            ),
            scaleRadius = 147f,
            scaleFont = GeelyDialFont.SANS_BOLD_ITALIC,
            artworkCornerRadius = 14f,
            centerFont = GeelyDialFont.SANS_BOLD_ITALIC,
            centerTextSize = 104f,
            centerOffsetX = -4f,
            centerOffsetY = 26f,
            driveSubtitle = "AERO // TACTICAL",
            speedSubSize = 20f,
            subOffsetY = 58f,
            fuel = GeelyFuelGaugeSpec.Cells(iconTopY = 401f, textSize = 18.5f, iconSize = 19),
            rpmBaselineY = 429f,
            clockFont = GeelyDialFont.SANS_BOLD_ITALIC,
            clockFrame = GeelyClockFrameStyle.SQUARE_BRACKETS,
        ),
        GeelyClusterThemeStyle.HYPER_WING to GeelyClusterThemeSpec(
            assetFolder = "hyper-wing",
            nightAccentColor = 0xFFFF6B00.toInt(),
            dayAccentColor = 0xFFEA580C.toInt(),
            secondaryAccent = GeelySecondaryAccentSpec(0xFFFFB800.toInt(), 0xFFD97706.toInt(), 0.25f),
            hotColorNight = 0xFFFF2D20.toInt(),
            unlitHotAlphaNight = 48,
            unlitNormalAlphaNight = 44,
            speedMax = 240f,
            speedLabels = GeelyClusterThemeSpec.SPEED_LABELS_240,
            ring = GeelyGaugeRingSpec.SegmentedArc(
                segments = 24,
                mirrored = true,
                gapDeg = 2.4f,
                hotFromLeft = 19,
                rInLit = 180f,
                rOutLit = 208f,
                rOutUnlit = 204f,
                progressiveOutSpan = 22f,
                haloInPad = 3.5f,
                haloOutPad = 4f,
                haloSweepPad = 0.6f,
                colorMode = GeelySegmentColorMode.ACCENT_TO_SECONDARY,
                topShiftBars = true,
            ),
            scaleRadius = 155f,
            scaleFont = GeelyDialFont.SANS_BOLD_ITALIC,
            scaleDarkAlpha = 205,
            artworkSize = 162f,
            centerFont = GeelyDialFont.SANS_BOLD_ITALIC,
            centerTextSize = 106f,
            centerOffsetX = -4f,
            driveSubtitle = "HYPER // WING",
            speedSubSize = 18f,
            gearSubSize = 17f,
            subOffsetY = 62f,
            fuel = GeelyFuelGaugeSpec.Cells(
                count = 8, startOffsetX = -72f, topY = 430f, stepX = 18.5f,
                cellWidth = 13f, cellHeight = 9f, skew = 4f, iconTopY = 400f,
            ),
            rpmFont = GeelyDialFont.SANS_BOLD_ITALIC,
            rpmBaselineY = 428f,
            clockFont = GeelyDialFont.SANS_BOLD_ITALIC,
        ),
        GeelyClusterThemeStyle.VALKYRIE_HEX to GeelyClusterThemeSpec(
            assetFolder = "valkyrie-hex",
            nightAccentColor = 0xFFA855F7.toInt(),
            dayAccentColor = 0xFF7C3AED.toInt(),
            secondaryAccent = GeelySecondaryAccentSpec(0xFF38BDF8.toInt(), 0xFF0284C7.toInt(), 0.28f),
            hotColorNight = 0xFFFF3B6B.toInt(),
            unlitHotRgb = 0xFF3B6B,
            unlitHotAlphaNight = 46,
            unlitNormalAlphaNight = 42,
            ring = GeelyGaugeRingSpec.SegmentedArc(
                segments = 26,
                gapDeg = 2.0f,
                hotFromLeft = 21,
                rInLit = 190f,
                rOutLit = 216f,
                rInUnlit = 194f,
                rOutUnlit = 212f,
                haloAlphaNight = 90,
                colorMode = GeelySegmentColorMode.SECONDARY_TO_ACCENT,
                innerDiamondNodes = true,
            ),
            scaleRadius = 150f,
            scaleFont = GeelyDialFont.MONO_BOLD,
            scaleDarkAlpha = 208,
            artworkSize = 162f,
            useSecondaryForCenter = true,
            centerFont = GeelyDialFont.SANS_BOLD,
            driveSubtitle = "VALKYRIE // HEX",
            fuel = GeelyFuelGaugeSpec.Cells(useSecondaryColor = true, iconTopY = 402f),
            rpmBaselineY = 428f,
            clockFont = GeelyDialFont.SANS_BOLD,
        ),
        GeelyClusterThemeStyle.ORBIT_RADAR to GeelyClusterThemeSpec(
            assetFolder = "orbit-radar",
            nightAccentColor = 0xFF22D3EE.toInt(),
            dayAccentColor = 0xFF0284C7.toInt(),
            hotColorNight = 0xFFFB7185.toInt(),
            unlitHotRgb = 0xFB7185,
            unlitHotAlphaDay = 76,
            unlitHotAlphaNight = 68,
            unlitNormalAlphaDay = 74,
            unlitNormalAlphaNight = 56,
            ring = GeelyGaugeRingSpec.RadialBeams(beams = 36, hotFromLeft = 30, hotFromRight = 29),
            scaleRadius = 149f,
            scaleFont = GeelyDialFont.MONO_BOLD,
            scaleTextSize = 16f,
            artworkSize = 166f,
            artworkCornerRadius = 22f,
            centerFont = GeelyDialFont.SANS_LIGHT,
            centerTextSize = 108f,
            centerOffsetY = 30f,
            driveSubtitle = "RADAR // SYNC",
            subOffsetY = 62f,
            fuel = GeelyFuelGaugeSpec.BottomArc(
                radius = 212f, startDeg = 118f, maxSweepDeg = -56f, strokeWidth = 5f,
                iconTopY = 406f, textSize = 20f, iconSize = 20,
            ),
            rpmPrefixFirst = true,
            rpmBaselineY = 424f,
            clockFont = GeelyDialFont.SANS_LIGHT,
            clockFrame = GeelyClockFrameStyle.HORIZON_TICKS,
        ),
        GeelyClusterThemeStyle.TITAN_CORE to GeelyClusterThemeSpec(
            assetFolder = "titan-core",
            nightAccentColor = 0xFFF59E0B.toInt(),
            dayAccentColor = 0xFFB45309.toInt(),
            secondaryAccent = GeelySecondaryAccentSpec(0xFF38BDF8.toInt(), 0xFF0369A1.toInt(), 0.30f),
            hotColorNight = 0xFFEF4444.toInt(),
            unlitHotAlphaDay = 58,
            unlitHotAlphaNight = 52,
            unlitNormalAlphaNight = 46,
            ring = GeelyGaugeRingSpec.ArmorBlocksWithPins(blocks = 12, blockHotFrom = 10, pins = 36, pinHotFrom = 30),
            scaleRadius = 150f,
            scaleFont = GeelyDialFont.SANS_BOLD,
            scaleDarkAlpha = 208,
            artworkCornerRadius = 14f,
            centerFont = GeelyDialFont.SANS_BOLD,
            driveSubtitle = "TITAN // CORE",
            fuel = GeelyFuelGaugeSpec.Cells(
                count = 8, startOffsetX = -72f, topY = 428f, stepX = 18.5f,
                cellWidth = 14f, cellHeight = 11f, cornerRadius = 2.2f,
                labelPrefix = "CORE", iconTopY = 398f,
            ),
            rpmBaselineY = 429f,
            clockFont = GeelyDialFont.SANS_BOLD,
        ),
        GeelyClusterThemeStyle.PHANTOM_STEALTH to GeelyClusterThemeSpec(
            assetFolder = "phantom-stealth",
            nightAccentColor = 0xFF10B981.toInt(),
            dayAccentColor = 0xFF047857.toInt(),
            hotColorDay = 0xFFE11D48.toInt(),
            hotColorNight = 0xFFF43F5E.toInt(),
            unlitHotRgb = 0xF43F5E,
            unlitHotAlphaNight = 48,
            unlitNormalAlphaNight = 46,
            ring = GeelyGaugeRingSpec.SegmentedArc(
                segments = 18,
                gapDeg = 3.6f,
                hotFromLeft = 14,
                rInLit = 200f,
                rOutLit = 214f,
                haloOutPad = 3f,
                outerTargetCursor = true,
            ),
            scaleRadius = 172f,
            scaleFont = GeelyDialFont.MONO_BOLD,
            scaleTextSize = 16f,
            scaleDarkAlpha = 198,
            artworkSize = 166f,
            centerFont = GeelyDialFont.MONO_BOLD,
            driveSubtitle = "STEALTH // HUD",
            speedSubSize = 15f,
            gearSubSize = 15f,
            fuel = GeelyFuelGaugeSpec.Cells(
                cellHeight = 5f, dimAlphaNight = 54, iconTopY = 403f, textSize = 17.5f,
            ),
            rpmBaselineY = 426f,
            clockFont = GeelyDialFont.MONO_BOLD,
        ),
    )

    fun of(style: GeelyClusterThemeStyle): GeelyClusterThemeSpec =
        specs[style] ?: specs.getValue(GeelyClusterThemeStyle.AERO_MECHA)
}

/**
 * Unified data-driven SVG + Canvas dial renderer for all 6 mecha/tactical Geely cluster themes.
 * Static faces are loaded from `assets/cluster-visual/themes/<folder>/{speed,tach}_{day,night}.svg`;
 * dynamic telemetry overlays are composed from reusable ring and fuel components driven by
 * [GeelyClusterThemeSpec] with zero per-frame object allocations.
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
    private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansBoldItalic = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun speedMaxFor(style: GeelyClusterThemeStyle): Float = GeelyClusterThemeSpecs.of(style).speedMax

    fun rpmMaxFor(style: GeelyClusterThemeStyle): Float = GeelyClusterThemeSpecs.of(style).rpmMax

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
        drawDial(
            canvas = canvas,
            spec = GeelyClusterThemeSpecs.of(style),
            isRight = false,
            lightMode = style.supportsLightMode && !nightMode,
            accentColor = accentColor,
            rawSpeed = rawSpeed,
            rawRpm = 0f,
            curFrac = curSpeedFrac,
            rawFuel = rawFuel,
            curFuelFrac = curFuelFrac,
            gearText = "D",
            centerArtwork = null,
        )
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
        drawDial(
            canvas = canvas,
            spec = GeelyClusterThemeSpecs.of(style),
            isRight = true,
            lightMode = style.supportsLightMode && !nightMode,
            accentColor = accentColor,
            rawSpeed = 0f,
            rawRpm = rawRpm,
            curFrac = curRpmFrac,
            rawFuel = null,
            curFuelFrac = 0f,
            gearText = gearText,
            centerArtwork = centerArtwork,
        )
    }

    // -------------------------------------------------------------------------
    // Unified Dial Pipeline
    // -------------------------------------------------------------------------

    private fun drawDial(
        canvas: Canvas,
        spec: GeelyClusterThemeSpec,
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
        drawStaticSvgFace(canvas, spec.assetFolder, isRight, lightMode, accentColor)

        val secColor = resolveSecondaryColor(spec, accentColor, lightMode)
        val hotColor = if (lightMode) spec.hotColorDay else spec.hotColorNight

        // 1. Main gauge ring + optional theme-specific telemetry attachments
        when (val ring = spec.ring) {
            is GeelyGaugeRingSpec.SegmentedArc -> drawSegmentedArcRing(
                canvas = canvas,
                spec = spec,
                ring = ring,
                isRight = isRight,
                lightMode = lightMode,
                accentColor = accentColor,
                secColor = secColor,
                hotColor = hotColor,
                rawSpeed = rawSpeed,
                rawRpm = rawRpm,
                curFrac = curFrac,
            )
            is GeelyGaugeRingSpec.RadialBeams -> drawRadialBeamsRing(
                canvas = canvas,
                spec = spec,
                ring = ring,
                isRight = isRight,
                lightMode = lightMode,
                accentColor = accentColor,
                hotColor = hotColor,
                curFrac = curFrac,
            )
            is GeelyGaugeRingSpec.ArmorBlocksWithPins -> drawArmorBlocksWithPinsRing(
                canvas = canvas,
                spec = spec,
                ring = ring,
                lightMode = lightMode,
                accentColor = accentColor,
                secColor = secColor,
                hotColor = hotColor,
                curFrac = curFrac,
            )
        }

        // 2. Scale labels
        val labels = if (isRight) spec.rpmLabels else spec.speedLabels
        drawRadialScaleLabels(
            canvas = canvas,
            startDeg = spec.ring.startDeg(isRight),
            dir = spec.ring.direction(isRight),
            spanDeg = spec.ring.spanDeg,
            radius = spec.scaleRadius,
            labels = labels,
            typeface = typefaceFor(spec.scaleFont),
            textSize = spec.scaleTextSize,
            normalColor = if (lightMode) Color.rgb(51, 65, 85) else Color.argb(spec.scaleDarkAlpha, 226, 232, 240),
            redFromIndex = if (isRight) 6 else Int.MAX_VALUE,
            redColor = hotColor,
        )

        // 3. Center numeral / gear / album art
        val centerAccent = if (spec.useSecondaryForCenter) secColor else accentColor
        if (isRight && centerArtwork != null && !centerArtwork.isRecycled) {
            drawRoundedArtwork(canvas, CX, CY - 4f, spec.artworkSize, spec.artworkCornerRadius, centerArtwork, centerAccent)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = typefaceFor(spec.centerFont)
            textPaint.textSize = spec.centerTextSize
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
            val mainText = if (isRight) gearText else rawSpeed.roundToInt().toString()
            canvas.drawText(mainText, CX + spec.centerOffsetX, CY + spec.centerOffsetY, textPaint)

            textPaint.typeface = monoBold
            textPaint.textSize = if (isRight) spec.gearSubSize else spec.speedSubSize
            textPaint.color = centerAccent
            val subText = if (isRight) gearSubtitle(gearText, spec.driveSubtitle) else "KM/H"
            canvas.drawText(subText, CX, CY + spec.subOffsetY, textPaint)
        }

        // 4. Bottom fuel bar (left) or RPM readout (right)
        if (!isRight) {
            drawFuelGauge(canvas, spec.fuel, lightMode, accentColor, secColor, hotColor, rawFuel, curFuelFrac)
        } else {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = typefaceFor(spec.rpmFont)
            textPaint.textSize = 19f
            textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(248, 250, 252)
            val rpmInt = rawRpm.roundToInt()
            val rpmText = if (spec.rpmPrefixFirst) "RPM $rpmInt" else "$rpmInt RPM"
            canvas.drawText(rpmText, CX, spec.rpmBaselineY, textPaint)
        }
    }

    // -------------------------------------------------------------------------
    // SVG Static Dial Asset Loader
    // -------------------------------------------------------------------------

    private fun drawStaticSvgFace(
        canvas: Canvas,
        folder: String,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
    ) {
        val kind = if (isRight) "tach" else "speed"
        val mode = if (lightMode) "day" else "night"
        val assetPath = "cluster-visual/themes/$folder/${kind}_${mode}.svg"
        val quantizedTint = accentColor and 0xFFF8F8F8.toInt()
        GeelyVectorSvgCache.getOrLoad(context, assetPath, DIAL_SIZE_PX, DIAL_SIZE_PX, tintColor = quantizedTint)?.let {
            canvas.drawPicture(it)
        }
    }

    // -------------------------------------------------------------------------
    // Ring Components
    // -------------------------------------------------------------------------

    private fun drawSegmentedArcRing(
        canvas: Canvas,
        spec: GeelyClusterThemeSpec,
        ring: GeelyGaugeRingSpec.SegmentedArc,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        secColor: Int,
        hotColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
        curFrac: Float,
    ) {
        val startDeg = ring.startDeg(isRight)
        val dir = ring.direction(isRight)
        val segs = ring.segments
        val activeCount = (curFrac * segs).roundToInt().coerceIn(0, segs)
        val stepDeg = ring.spanDeg / segs
        val segSweep = stepDeg - ring.gapDeg
        val hotFrom = if (isRight) ring.hotFromRight else ring.hotFromLeft
        val unlitHot = withAlpha(spec.unlitHotRgb, if (lightMode) spec.unlitHotAlphaDay else spec.unlitHotAlphaNight)
        val unlitNormal = if (lightMode) {
            Color.argb(spec.unlitNormalAlphaDay, 100, 116, 139)
        } else {
            Color.argb(spec.unlitNormalAlphaNight, 148, 163, 184)
        }
        val dimDiamond = if (lightMode) Color.argb(66, 100, 116, 139) else Color.argb(56, 148, 163, 184)

        for (i in 0 until segs) {
            val t = if (segs > 1) i.toFloat() / (segs - 1) else 0f
            val isLit = i < activeCount
            val isHot = i >= hotFrom
            val rIn = if (isLit) ring.rInLit else ring.rInUnlit
            val rOut = (if (isLit) ring.rOutLit else ring.rOutUnlit) + t * ring.progressiveOutSpan
            val segStart = if (dir > 0f) {
                startDeg + i * stepDeg + ring.gapDeg / 2f
            } else {
                startDeg - (i + 1) * stepDeg + ring.gapDeg / 2f
            }
            val litBaseCol = when (ring.colorMode) {
                GeelySegmentColorMode.ACCENT -> accentColor
                GeelySegmentColorMode.ACCENT_TO_SECONDARY -> blendColor(accentColor, secColor, t)
                GeelySegmentColorMode.SECONDARY_TO_ACCENT -> blendColor(secColor, accentColor, t)
            }

            if (isLit && i == activeCount - 1) {
                val haloCol = if (isHot) hotColor else litBaseCol
                drawAnnularSector(
                    canvas = canvas,
                    cx = CX,
                    cy = CY,
                    rInner = rIn - ring.haloInPad,
                    rOuter = rOut + ring.haloOutPad,
                    startDeg = segStart - ring.haloSweepPad,
                    sweepDeg = segSweep + ring.haloSweepPad * 2f,
                    color = withAlpha(haloCol, if (lightMode) ring.haloAlphaDay else ring.haloAlphaNight),
                )
            }

            val blockCol = when {
                isLit && isHot -> hotColor
                isLit -> litBaseCol
                isHot -> unlitHot
                else -> unlitNormal
            }
            drawAnnularSector(canvas, CX, CY, rIn, rOut, segStart, segSweep, blockCol)

            if (ring.innerDiamondNodes && i % 2 == 0) {
                val midDeg = startDeg + dir * (i + 0.5f) * stepDeg
                drawDiamondNode(
                    canvas = canvas,
                    midDeg = midDeg,
                    radius = 176f,
                    isLit = isLit,
                    lightMode = lightMode,
                    color = if (isLit) (if (isHot) hotColor else secColor) else dimDiamond,
                )
            }
        }

        if (ring.topShiftBars) {
            drawTopShiftBars(canvas, isRight, lightMode, accentColor, secColor, hotColor, rawSpeed, rawRpm)
        }

        if (ring.outerTargetCursor) {
            drawOuterTargetCursor(canvas, isRight, lightMode, accentColor, hotColor, rawRpm, curFrac)
        }
    }

    private fun drawDiamondNode(
        canvas: Canvas,
        midDeg: Float,
        radius: Float,
        isLit: Boolean,
        lightMode: Boolean,
        color: Int,
    ) {
        val rad = Math.toRadians(midDeg.toDouble())
        val dx = CX + cos(rad).toFloat() * radius
        val dy = CY + sin(rad).toFloat() * radius
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
            fillPaint.color = withAlpha(color, if (lightMode) 60 else 86)
            canvas.drawPath(workPath, fillPaint)
        }
        workPath.reset()
        workPath.moveTo(0f, -5.0f)
        workPath.lineTo(3.6f, 0f)
        workPath.lineTo(0f, 5.0f)
        workPath.lineTo(-3.6f, 0f)
        workPath.close()
        fillPaint.color = color
        canvas.drawPath(workPath, fillPaint)
        canvas.restoreToCount(save)
    }

    private fun drawTopShiftBars(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        secColor: Int,
        hotColor: Int,
        rawSpeed: Float,
        rawRpm: Float,
    ) {
        val shiftBars = 5
        val dimShift = if (lightMode) Color.argb(68, 100, 116, 139) else Color.argb(56, 148, 163, 184)
        for (i in 0 until shiftBars) {
            val bx = CX - 64f + i * 27f
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
    }

    private fun drawOuterTargetCursor(
        canvas: Canvas,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        hotColor: Int,
        rawRpm: Float,
        curFrac: Float,
    ) {
        val cursorDeg = 135f + curFrac.coerceIn(0f, 1f) * 270f
        val isCursorHot = isRight && rawRpm >= 5800f
        val cursorGlowCol = if (isCursorHot) hotColor else accentColor
        val cursorCoreCol = when {
            isCursorHot -> hotColor
            lightMode -> accentColor
            else -> Color.WHITE
        }
        val save = canvas.save()
        canvas.rotate(cursorDeg, CX, CY)
        workPath.reset()
        workPath.moveTo(CX + 214f, CY)
        workPath.lineTo(CX + 235f, CY - 9.5f)
        workPath.lineTo(CX + 235f, CY + 9.5f)
        workPath.close()
        fillPaint.color = withAlpha(cursorGlowCol, if (lightMode) 72 else 100)
        canvas.drawPath(workPath, fillPaint)

        workPath.reset()
        workPath.moveTo(CX + 217f, CY)
        workPath.lineTo(CX + 232f, CY - 7f)
        workPath.lineTo(CX + 232f, CY + 7f)
        workPath.close()
        fillPaint.color = cursorCoreCol
        canvas.drawPath(workPath, fillPaint)
        canvas.restoreToCount(save)
    }

    private fun drawRadialBeamsRing(
        canvas: Canvas,
        spec: GeelyClusterThemeSpec,
        ring: GeelyGaugeRingSpec.RadialBeams,
        isRight: Boolean,
        lightMode: Boolean,
        accentColor: Int,
        hotColor: Int,
        curFrac: Float,
    ) {
        val beams = ring.beams
        val activeCount = (curFrac * beams).roundToInt().coerceIn(0, beams)
        val hotFrom = if (isRight) ring.hotFromRight else ring.hotFromLeft
        val unlitHot = withAlpha(spec.unlitHotRgb, if (lightMode) spec.unlitHotAlphaDay else spec.unlitHotAlphaNight)
        val unlitNormal = if (lightMode) {
            Color.argb(spec.unlitNormalAlphaDay, 100, 116, 139)
        } else {
            Color.argb(spec.unlitNormalAlphaNight, 148, 163, 184)
        }

        strokePaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until beams) {
            val ratio = i.toFloat() / (beams - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val isLit = i < activeCount
            val isHead = isLit && i == activeCount - 1
            val isHot = i >= hotFrom
            val beamCol = if (isHot) hotColor else accentColor

            if (isHead) {
                val rIn = 182f
                val rOut = 226f
                strokePaint.strokeWidth = 10f
                strokePaint.color = withAlpha(beamCol, if (lightMode) 78 else 105)
                canvas.drawLine(CX + cosA * rIn, CY + sinA * rIn, CX + cosA * rOut, CY + sinA * rOut, strokePaint)
                strokePaint.strokeWidth = 5.2f
                strokePaint.color = if (lightMode) beamCol else Color.WHITE
                canvas.drawLine(CX + cosA * rIn, CY + sinA * rIn, CX + cosA * rOut, CY + sinA * rOut, strokePaint)
            } else if (isLit) {
                val rIn = 188f
                val rOut = 220f
                strokePaint.strokeWidth = 7.5f
                strokePaint.color = withAlpha(beamCol, if (lightMode) 48 else 64)
                canvas.drawLine(CX + cosA * rIn, CY + sinA * rIn, CX + cosA * rOut, CY + sinA * rOut, strokePaint)
                strokePaint.strokeWidth = 4.0f
                strokePaint.color = beamCol
                canvas.drawLine(CX + cosA * rIn, CY + sinA * rIn, CX + cosA * rOut, CY + sinA * rOut, strokePaint)
            } else {
                val rIn = 188f
                val rOut = 212f
                strokePaint.strokeWidth = 2.6f
                strokePaint.color = if (isHot) unlitHot else unlitNormal
                canvas.drawLine(CX + cosA * rIn, CY + sinA * rIn, CX + cosA * rOut, CY + sinA * rOut, strokePaint)
            }
        }
    }

    private fun drawArmorBlocksWithPinsRing(
        canvas: Canvas,
        spec: GeelyClusterThemeSpec,
        ring: GeelyGaugeRingSpec.ArmorBlocksWithPins,
        lightMode: Boolean,
        accentColor: Int,
        secColor: Int,
        hotColor: Int,
        curFrac: Float,
    ) {
        val blocks = ring.blocks
        val activeBlocks = if (curFrac > 0.01f) ceil(curFrac * blocks.toDouble()).toInt().coerceIn(0, blocks) else 0
        val stepDeg = 270f / blocks
        val gapDeg = 4.8f
        val segSweep = stepDeg - gapDeg
        val unlitHot = withAlpha(spec.unlitHotRgb, if (lightMode) spec.unlitHotAlphaDay else spec.unlitHotAlphaNight)
        val unlitNormal = if (lightMode) {
            Color.argb(spec.unlitNormalAlphaDay, 100, 116, 139)
        } else {
            Color.argb(spec.unlitNormalAlphaNight, 148, 163, 184)
        }

        for (i in 0 until blocks) {
            val startDeg = 135f + i * stepDeg + gapDeg / 2f
            val isLit = i < activeBlocks
            val isHot = i >= ring.blockHotFrom

            if (isLit && i == activeBlocks - 1) {
                val haloCol = if (isHot) hotColor else accentColor
                drawAnnularSector(
                    canvas, CX, CY, 190.5f, 229.5f,
                    startDeg - 0.7f, segSweep + 1.4f,
                    withAlpha(haloCol, if (lightMode) 64 else 92),
                )
            }

            val col = when {
                isLit && isHot -> hotColor
                isLit -> accentColor
                isHot -> unlitHot
                else -> unlitNormal
            }
            drawAnnularSector(canvas, CX, CY, 194f, 226f, startDeg, segSweep, col)
        }

        val pins = ring.pins
        val activePins = (curFrac * pins).roundToInt().coerceIn(0, pins)
        strokePaint.strokeCap = Paint.Cap.BUTT
        for (i in 0 until pins) {
            val ratio = i.toFloat() / (pins - 1)
            val rad = Math.toRadians((135f + ratio * 270f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val isLit = i < activePins
            val isHot = i >= ring.pinHotFrom
            strokePaint.strokeWidth = if (isLit) 3.2f else 1.8f
            strokePaint.color = when {
                isLit && isHot -> hotColor
                isLit -> secColor
                lightMode -> Color.argb(72, 100, 116, 139)
                else -> Color.argb(54, 148, 163, 184)
            }
            canvas.drawLine(CX + cosA * 172f, CY + sinA * 172f, CX + cosA * 184f, CY + sinA * 184f, strokePaint)
        }
    }

    // -------------------------------------------------------------------------
    // Fuel Gauge Components
    // -------------------------------------------------------------------------

    private fun drawFuelGauge(
        canvas: Canvas,
        fuelSpec: GeelyFuelGaugeSpec,
        lightMode: Boolean,
        accentColor: Int,
        secColor: Int,
        hotColor: Int,
        rawFuel: Float?,
        curFuelFrac: Float,
    ) {
        val txtCol = if (lightMode) Color.rgb(30, 41, 59) else Color.rgb(226, 232, 240)
        when (fuelSpec) {
            is GeelyFuelGaugeSpec.Cells -> {
                val litCount = if (rawFuel != null) (curFuelFrac * fuelSpec.count).roundToInt().coerceIn(0, fuelSpec.count) else 0
                val sx = CX + fuelSpec.startOffsetX
                val sy = fuelSpec.topY
                val baseCol = if (fuelSpec.useSecondaryColor) secColor else accentColor
                val fuelCol = if (rawFuel != null && rawFuel <= 15f) hotColor else baseCol
                val dimCol = if (lightMode) Color.argb(72, 100, 116, 139) else Color.argb(fuelSpec.dimAlphaNight, 148, 163, 184)

                for (i in 0 until fuelSpec.count) {
                    val col = if (i < litCount) fuelCol else dimCol
                    val x = sx + i * fuelSpec.stepX
                    when {
                        fuelSpec.skew != 0f ->
                            drawSkewedQuad(canvas, x, sy, fuelSpec.cellWidth, fuelSpec.cellHeight, fuelSpec.skew, col)
                        fuelSpec.cornerRadius > 0f -> {
                            fillPaint.color = col
                            rectF.set(x, sy, x + fuelSpec.cellWidth, sy + fuelSpec.cellHeight)
                            canvas.drawRoundRect(rectF, fuelSpec.cornerRadius, fuelSpec.cornerRadius, fillPaint)
                        }
                        else -> {
                            fillPaint.color = col
                            canvas.drawRect(x, sy, x + fuelSpec.cellWidth, sy + fuelSpec.cellHeight, fillPaint)
                        }
                    }
                }
                val pctText = if (rawFuel != null) "${rawFuel.roundToInt()}%" else "--%"
                val label = "${fuelSpec.labelPrefix} $pctText"
                drawFuelIconAndCustomText(canvas, CX, fuelSpec.iconTopY, label, txtCol, monoBold, fuelSpec.textSize, fuelSpec.iconSize)
            }
            is GeelyFuelGaugeSpec.BottomArc -> {
                if (rawFuel != null && curFuelFrac > 0.01f) {
                    strokePaint.strokeCap = Paint.Cap.ROUND
                    strokePaint.strokeWidth = fuelSpec.strokeWidth
                    strokePaint.color = if (rawFuel <= 15f) hotColor else accentColor
                    rectF.set(CX - fuelSpec.radius, CY - fuelSpec.radius, CX + fuelSpec.radius, CY + fuelSpec.radius)
                    canvas.drawArc(rectF, fuelSpec.startDeg, fuelSpec.maxSweepDeg * curFuelFrac, false, strokePaint)
                }
                val label = if (rawFuel != null) "${rawFuel.roundToInt()}%" else "--%"
                drawFuelIconAndCustomText(canvas, CX, fuelSpec.iconTopY, label, txtCol, monoBold, fuelSpec.textSize, fuelSpec.iconSize)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared Dynamic Helpers
    // -------------------------------------------------------------------------

    private fun typefaceFor(font: GeelyDialFont): Typeface = when (font) {
        GeelyDialFont.SANS_BOLD -> sansBold
        GeelyDialFont.SANS_LIGHT -> sansLight
        GeelyDialFont.SANS_BOLD_ITALIC -> sansBoldItalic
        GeelyDialFont.MONO_BOLD -> monoBold
    }

    private fun resolveSecondaryColor(
        spec: GeelyClusterThemeSpec,
        accentColor: Int,
        lightMode: Boolean,
    ): Int {
        val sec = spec.secondaryAccent ?: return accentColor
        val defaultAccent = spec.defaultAccentColor(!lightMode)
        return if (isNearColor(accentColor, defaultAccent)) {
            if (lightMode) sec.dayColor else sec.nightColor
        } else {
            blendColor(accentColor, Color.WHITE, sec.customWhiteBlend)
        }
    }

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
        startDeg: Float,
        dir: Float,
        spanDeg: Float,
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
        val stepDeg = spanDeg / (labels.size - 1)
        for (i in labels.indices) {
            val rad = Math.toRadians((startDeg + dir * i * stepDeg).toDouble())
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

    private fun isNearColor(c1: Int, c2: Int): Boolean {
        val dr = abs(Color.red(c1) - Color.red(c2))
        val dg = abs(Color.green(c1) - Color.green(c2))
        val db = abs(Color.blue(c1) - Color.blue(c2))
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
