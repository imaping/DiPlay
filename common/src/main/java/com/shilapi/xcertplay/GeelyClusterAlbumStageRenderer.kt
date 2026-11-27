package com.shilapi.xcertplay

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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Scheme C ("居中剧场") Canvas renderer for the Geely 1920x720 cluster center album area:
 * - Upper stage: centered floating rounded album cover + breathing dominant-color halo +
 *   14-bar symmetric audio spectrum wings + frosted title/artist capsule.
 * - Lower stage: 700px ultra-wide 3-line centered spring-scrolling lyrics stage with
 *   karaoke sweep highlight and glowing gradient progress bar.
 * - Automatically compacts smoothly when the fullscreen top navigation card is active.
 */
internal class GeelyClusterAlbumStageRenderer {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val monoSemiBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val coverRect = RectF()
    private val shadowRect = RectF()
    private val coverClipPath = Path()
    private val tempRect = RectF()

    // Smooth animation state
    private var smoothLineIndex: Float = 0f
    private var smoothIndexInitialized: Boolean = false
    private var lastFrameUptimeMs: Long = 0L
    private var playIntensity: Float = 0f
    private var lastMode: GeelyLyricsDisplayMode? = null
    private var lastLineCount: Int = 0

    fun reset() {
        smoothLineIndex = 0f
        smoothIndexInitialized = false
        lastFrameUptimeMs = 0L
        playIntensity = 0f
        lastMode = null
        lastLineCount = 0
    }

    /**
     * Draws the Scheme C centered album theater & scrolling lyrics stage.
     * Returns `true` when animation frames should continue (playing, scrolling, or transitioning).
     */
    fun draw(
        canvas: Canvas,
        artwork: Bitmap,
        prevArtwork: Bitmap?,
        artworkTransitionProgress: Float,
        lyricsFrame: GeelyLyricsRenderFrame?,
        accentColor: Int,
        lightMode: Boolean,
        compactForTopCard: Boolean,
        nowUptimeMs: Long,
        previewMode: Boolean,
    ): Boolean {
        if (artwork.isRecycled) return false

        val dtSec = if (lastFrameUptimeMs > 0L && nowUptimeMs > lastFrameUptimeMs) {
            ((nowUptimeMs - lastFrameUptimeMs) / 1000f).coerceIn(0.001f, 0.05f)
        } else {
            0.016f
        }
        lastFrameUptimeMs = nowUptimeMs

        val effectiveFrame = lyricsFrame ?: if (previewMode) {
            buildPreviewDemoFrame(nowUptimeMs)
        } else {
            null
        }
        val isPlaying = effectiveFrame?.playing == true || previewMode
        val targetIntensity = if (isPlaying) 1f else 0f
        playIntensity = if (previewMode) {
            1f
        } else {
            playIntensity + (targetIntensity - playIntensity) * (dtSec * 6.5f).coerceAtMost(1f)
        }

        // Secondary harmonic accent derived from primary accent (+42 deg HSV shift)
        val secondaryAccent = deriveSecondaryAccent(accentColor)
        val tSec = nowUptimeMs / 1000f

        var keepAnimating = isPlaying || artworkTransitionProgress < 1f || abs(targetIntensity - playIntensity) > 0.02f

        val save = canvas.save()
        if (compactForTopCard) {
            canvas.translate(CENTER_X, 150f)
            canvas.scale(0.84f, 0.84f)
            canvas.translate(-CENTER_X, -86f)
        }

        val artSize = COVER_SIZE
        val artLeft = CENTER_X - artSize / 2f
        val artTop = COVER_TOP_Y
        val artCenterY = artTop + artSize / 2f

        // 1. Symmetric audio spectrum wings flanking the album cover
        drawSymmetricSpectrumWings(
            canvas = canvas,
            artLeft = artLeft,
            artRight = artLeft + artSize,
            artCenterY = artCenterY,
            accent = accentColor,
            secondary = secondaryAccent,
            lightMode = lightMode,
            tSec = tSec,
            intensity = playIntensity,
        )

        // 2. Breathing dominant-color halo behind cover
        drawCoverHalo(
            canvas = canvas,
            cx = CENTER_X,
            cy = artCenterY,
            accent = accentColor,
            lightMode = lightMode,
            tSec = tSec,
            intensity = playIntensity,
        )

        // 3. Floating rounded album cover with shadow, crossfade, and glass highlight rim
        drawAlbumCoverCard(
            canvas = canvas,
            artwork = artwork,
            prevArtwork = prevArtwork,
            transitionProgress = artworkTransitionProgress,
            left = artLeft,
            top = artTop,
            size = artSize,
            lightMode = lightMode,
        )

        // 4. Song title & artist glass capsule
        val capsuleText = buildCapsuleText(effectiveFrame)
        if (capsuleText.isNotEmpty()) {
            drawMetadataCapsule(
                canvas = canvas,
                text = capsuleText,
                cx = CENTER_X,
                topY = artTop + artSize + 18f,
                accent = accentColor,
                lightMode = lightMode,
            )
        }

        // 5. Lower 700px ultra-wide 3-line centered scrolling lyrics stage
        if (effectiveFrame != null) {
            val scrollAnimating = drawLyricsTheater(
                canvas = canvas,
                frame = effectiveFrame,
                accent = accentColor,
                secondary = secondaryAccent,
                lightMode = lightMode,
                dtSec = dtSec,
                tSec = tSec,
                previewMode = previewMode,
            )
            if (scrollAnimating) keepAnimating = true

            // 6. Glowing gradient progress bar (when track duration & elapsed position exist)
            val dur = effectiveFrame.durationMs
            val elapsed = effectiveFrame.elapsedMs
            if (dur != null && dur > 0L && elapsed != null) {
                val frac = (elapsed.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                drawProgressBar(
                    canvas = canvas,
                    left = CENTER_X - PROGRESS_WIDTH / 2f,
                    topY = PROGRESS_TOP_Y,
                    width = PROGRESS_WIDTH,
                    fraction = frac,
                    elapsedMs = elapsed,
                    durationMs = dur,
                    accent = accentColor,
                    secondary = secondaryAccent,
                    lightMode = lightMode,
                )
            }
        }

        canvas.restoreToCount(save)
        return keepAnimating
    }

    private fun drawSymmetricSpectrumWings(
        canvas: Canvas,
        artLeft: Float,
        artRight: Float,
        artCenterY: Float,
        accent: Int,
        secondary: Int,
        lightMode: Boolean,
        tSec: Float,
        intensity: Float,
    ) {
        val wingBars = 14
        val barW = 5.5f
        val step = 12f
        val maxAlpha = if (lightMode) 0.72f else 0.86f
        fillPaint.shader = null
        for (i in 0 until wingBars) {
            val falloff = 1f - i.toFloat() / (wingBars + 3f)
            val wave = 0.5f + 0.5f * sin(tSec * 4.8f + i * 0.55f) * cos(tSec * 2.1f - i * 0.30f)
            val activeH = 12f + 46f * wave.toDouble().pow(1.3).toFloat() * falloff
            val idleH = 7f + 4f * falloff
            val h = idleH + (activeH - idleH) * intensity
            val alpha = ((1f - i.toFloat() / wingBars) * maxAlpha * 255f).roundToInt().coerceIn(18, 255)
            val color = if (i % 2 == 0) accent else secondary
            fillPaint.color = withAlpha(color, alpha)

            val lx = artLeft - 24f - i * step
            tempRect.set(lx - barW, artCenterY - h / 2f, lx, artCenterY + h / 2f)
            canvas.drawRoundRect(tempRect, 3f, 3f, fillPaint)

            val rx = artRight + 24f + i * step
            tempRect.set(rx, artCenterY - h / 2f, rx + barW, artCenterY + h / 2f)
            canvas.drawRoundRect(tempRect, 3f, 3f, fillPaint)
        }
    }

    private fun drawCoverHalo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        accent: Int,
        lightMode: Boolean,
        tSec: Float,
        intensity: Float,
    ) {
        val breath = 1f + 0.03f * sin(tSec * 2.2f) * intensity
        val radius = 168f * breath
        val centerAlpha = if (lightMode) 74 else 128
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                withAlpha(accent, centerAlpha),
                withAlpha(accent, centerAlpha / 3),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null
    }

    private fun drawAlbumCoverCard(
        canvas: Canvas,
        artwork: Bitmap,
        prevArtwork: Bitmap?,
        transitionProgress: Float,
        left: Float,
        top: Float,
        size: Float,
        lightMode: Boolean,
    ) {
        coverRect.set(left, top, left + size, top + size)
        shadowRect.set(left - 4f, top + 8f, left + size + 4f, top + size + 12f)

        fillPaint.shader = null
        fillPaint.color = if (lightMode) Color.argb(56, 15, 23, 42) else Color.argb(128, 0, 0, 0)
        canvas.drawRoundRect(shadowRect, COVER_CORNER, COVER_CORNER, fillPaint)

        val clipSave = canvas.save()
        coverClipPath.reset()
        coverClipPath.addRoundRect(coverRect, COVER_CORNER, COVER_CORNER, Path.Direction.CW)
        canvas.clipPath(coverClipPath)
        if (prevArtwork != null && !prevArtwork.isRecycled && transitionProgress < 1f) {
            bitmapPaint.alpha = ((1f - transitionProgress) * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(prevArtwork, null, coverRect, bitmapPaint)
        }
        bitmapPaint.alpha = (transitionProgress.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(artwork, null, coverRect, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restoreToCount(clipSave)

        strokePaint.shader = null
        strokePaint.strokeWidth = 2f
        strokePaint.color = if (lightMode) Color.argb(225, 255, 255, 255) else Color.argb(82, 255, 255, 255)
        canvas.drawRoundRect(coverRect, COVER_CORNER, COVER_CORNER, strokePaint)
    }

    private fun drawMetadataCapsule(
        canvas: Canvas,
        text: String,
        cx: Float,
        topY: Float,
        accent: Int,
        lightMode: Boolean,
    ) {
        textPaint.clearShadowLayer()
        textPaint.typeface = sansBold
        textPaint.textSize = 21f
        textPaint.textAlign = Paint.Align.CENTER

        val maxPillTextW = 540f
        val clipped = ellipsize(text, textPaint, maxPillTextW)
        val textW = textPaint.measureText(clipped)
        val pillW = (textW + 40f).coerceAtMost(600f)
        val pillH = 36f
        tempRect.set(cx - pillW / 2f, topY, cx + pillW / 2f, topY + pillH)

        fillPaint.shader = null
        fillPaint.color = if (lightMode) Color.argb(188, 255, 255, 255) else Color.argb(150, 15, 23, 42)
        canvas.drawRoundRect(tempRect, 18f, 18f, fillPaint)

        strokePaint.shader = null
        strokePaint.strokeWidth = 1.3f
        strokePaint.color = withAlpha(accent, if (lightMode) 115 else 96)
        canvas.drawRoundRect(tempRect, 18f, 18f, strokePaint)

        textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.rgb(241, 245, 249)
        val fm = textPaint.fontMetrics
        val baselineY = topY + pillH / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(clipped, cx, baselineY, textPaint)
    }

    private fun drawLyricsTheater(
        canvas: Canvas,
        frame: GeelyLyricsRenderFrame,
        accent: Int,
        secondary: Int,
        lightMode: Boolean,
        dtSec: Float,
        tSec: Float,
        previewMode: Boolean,
    ): Boolean {
        val stageLeft = CENTER_X - STAGE_WIDTH / 2f
        val stageRight = CENTER_X + STAGE_WIDTH / 2f
        val stageTop = STAGE_TOP_Y
        val stageBottom = STAGE_TOP_Y + STAGE_HEIGHT
        val stageCenterY = stageTop + STAGE_HEIGHT * 0.50f

        if (frame.mode == GeelyLyricsDisplayMode.METADATA_ONLY || frame.lines.isEmpty()) {
            drawMetadataFallbackStage(
                canvas = canvas,
                frame = frame,
                cx = CENTER_X,
                centerY = stageCenterY,
                accent = accent,
                lightMode = lightMode,
                tSec = tSec,
            )
            return frame.playing
        }

        val targetIndex = frame.activeIndex.toFloat()
        val modeChanged = lastMode != frame.mode || abs(lastLineCount - frame.lines.size) > 4
        if (!smoothIndexInitialized || modeChanged || abs(targetIndex - smoothLineIndex) > 3.5f || previewMode) {
            smoothLineIndex = targetIndex
            smoothIndexInitialized = true
        } else {
            smoothLineIndex += (targetIndex - smoothLineIndex) * (dtSec * 7.5f).coerceAtMost(1f)
        }
        lastMode = frame.mode
        lastLineCount = frame.lines.size

        val save = canvas.save()
        canvas.clipRect(stageLeft, stageTop, stageRight, stageBottom)

        val stepY = 58f
        val maxTextW = STAGE_WIDTH - 24f
        val maxRenderIdx = when (frame.mode) {
            GeelyLyricsDisplayMode.SYNCED_LRC -> frame.lines.lastIndex
            GeelyLyricsDisplayMode.LIVE_STREAM -> frame.activeIndex.coerceAtMost(frame.lines.lastIndex)
            GeelyLyricsDisplayMode.METADATA_ONLY -> -1
        }

        for (i in 0..maxRenderIdx) {
            val delta = i - smoothLineIndex
            if (abs(delta) > 2.25f) continue
            val rowCenterY = stageCenterY + delta * stepY
            val dist = abs(delta)
            val focus = (1f - dist).coerceIn(0f, 1f)
            val baseSize = 23f + focus * 13f // 23px -> 36px

            val lineText = frame.lines[i].text
            if (i == frame.activeIndex && dist < 0.45f) {
                drawKaraokeCenteredLine(
                    canvas = canvas,
                    text = lineText,
                    cx = CENTER_X,
                    centerY = rowCenterY,
                    desiredSize = baseSize,
                    maxWidth = maxTextW,
                    progress = frame.lineProgress,
                    accent = accent,
                    lightMode = lightMode,
                )
            } else {
                val alphaFrac = (0.76f - dist * 0.42f).coerceIn(0.10f, 0.76f)
                val alphaInt = (alphaFrac * 255f).roundToInt().coerceIn(22, 210)
                textPaint.clearShadowLayer()
                textPaint.typeface = sansMedium
                textPaint.textAlign = Paint.Align.CENTER
                val fittedSize = fitFontSize(lineText, textPaint, baseSize, 18f, maxTextW)
                textPaint.textSize = fittedSize
                val display = ellipsize(lineText, textPaint, maxTextW)
                textPaint.color = if (lightMode) {
                    Color.argb(alphaInt, 30, 41, 59)
                } else {
                    Color.argb(alphaInt, 226, 232, 240)
                }
                val fm = textPaint.fontMetrics
                val baseline = rowCenterY - (fm.ascent + fm.descent) / 2f
                canvas.drawText(display, CENTER_X, baseline, textPaint)
            }
        }

        // In LIVE_STREAM mode (where upcoming next line is unknown), draw a subtle glowing
        // audio rhythm pulse below the active lyric so the lower row feels balanced and alive.
        if (frame.mode == GeelyLyricsDisplayMode.LIVE_STREAM) {
            drawLiveStreamPulseRow(
                canvas = canvas,
                cx = CENTER_X,
                centerY = stageCenterY + stepY,
                accent = accent,
                secondary = secondary,
                lightMode = lightMode,
                tSec = tSec,
                intensity = playIntensity,
            )
        }

        canvas.restoreToCount(save)
        return abs(targetIndex - smoothLineIndex) > 0.01f || frame.playing
    }

    private fun drawKaraokeCenteredLine(
        canvas: Canvas,
        text: String,
        cx: Float,
        centerY: Float,
        desiredSize: Float,
        maxWidth: Float,
        progress: Float,
        accent: Int,
        lightMode: Boolean,
    ) {
        textPaint.clearShadowLayer()
        textPaint.typeface = sansBold
        textPaint.textAlign = Paint.Align.CENTER
        val fittedSize = fitFontSize(text, textPaint, desiredSize, 25f, maxWidth)
        textPaint.textSize = fittedSize
        val display = ellipsize(text, textPaint, maxWidth)
        val tw = textPaint.measureText(display).coerceAtLeast(1f)
        val fm = textPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f

        // 1. Base unswept lyric pass
        textPaint.color = if (lightMode) {
            Color.argb(96, 15, 23, 42)
        } else {
            Color.argb(122, 255, 255, 255)
        }
        canvas.drawText(display, cx, baseline, textPaint)

        // 2. Swept karaoke highlight pass with feathered leading accent glow
        val leftX = cx - tw / 2f
        val p = progress.coerceIn(0f, 1f)
        val hiColor = if (lightMode) Color.rgb(11, 18, 32) else Color.WHITE
        val edgeStart = (p - 0.09f).coerceIn(0f, 1f)
        val edgeEnd = (p + 0.03f).coerceIn(0f, 1f)

        sweepTextPaint.typeface = sansBold
        sweepTextPaint.textSize = fittedSize
        sweepTextPaint.textAlign = Paint.Align.CENTER
        sweepTextPaint.color = hiColor
        sweepTextPaint.shader = LinearGradient(
            leftX,
            centerY,
            leftX + tw,
            centerY,
            intArrayOf(hiColor, hiColor, accent, Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, edgeStart, p, edgeEnd, 1f),
            Shader.TileMode.CLAMP,
        )
        if (!lightMode) {
            sweepTextPaint.setShadowLayer(16f, 0f, 0f, withAlpha(accent, 175))
        } else {
            sweepTextPaint.setShadowLayer(5f, 0f, 1f, withAlpha(accent, 70))
        }
        canvas.drawText(display, cx, baseline, sweepTextPaint)
        sweepTextPaint.clearShadowLayer()
        sweepTextPaint.shader = null
    }

    private fun drawLiveStreamPulseRow(
        canvas: Canvas,
        cx: Float,
        centerY: Float,
        accent: Int,
        secondary: Int,
        lightMode: Boolean,
        tSec: Float,
        intensity: Float,
    ) {
        val count = 11
        val step = 13f
        val startX = cx - (count - 1) * step / 2f
        fillPaint.shader = null
        for (i in 0 until count) {
            val distFromMid = abs(i - count / 2) / (count / 2f)
            val env = 1f - distFromMid * 0.6f
            val wave = 0.5f + 0.5f * sin(tSec * 5.0f + i * 0.65f)
            val h = 4f + (14f * wave * env) * intensity
            val alpha = ((if (lightMode) 150 else 170) * env).roundToInt().coerceIn(36, 210)
            fillPaint.color = withAlpha(if (i % 2 == 0) accent else secondary, alpha)
            val x = startX + i * step
            tempRect.set(x - 2.2f, centerY - h / 2f, x + 2.2f, centerY + h / 2f)
            canvas.drawRoundRect(tempRect, 2.2f, 2.2f, fillPaint)
        }
    }

    private fun drawMetadataFallbackStage(
        canvas: Canvas,
        frame: GeelyLyricsRenderFrame,
        cx: Float,
        centerY: Float,
        accent: Int,
        lightMode: Boolean,
        tSec: Float,
    ) {
        val maxW = STAGE_WIDTH - 40f
        textPaint.clearShadowLayer()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansBold
        val title = frame.displayTitle.ifEmpty { "正在播放" }
        textPaint.textSize = fitFontSize(title, textPaint, 34f, 24f, maxW)
        textPaint.color = if (lightMode) Color.rgb(15, 23, 42) else Color.WHITE
        if (!lightMode) {
            textPaint.setShadowLayer(12f, 0f, 0f, withAlpha(accent, 110))
        }
        canvas.drawText(ellipsize(title, textPaint, maxW), cx, centerY - 6f, textPaint)
        textPaint.clearShadowLayer()

        if (frame.displayArtist.isNotEmpty()) {
            textPaint.typeface = sansMedium
            textPaint.textSize = 22f
            textPaint.color = if (lightMode) Color.argb(180, 51, 65, 85) else Color.argb(185, 203, 213, 225)
            canvas.drawText(ellipsize(frame.displayArtist, textPaint, maxW), cx, centerY + 34f, textPaint)
        } else {
            drawLiveStreamPulseRow(
                canvas = canvas,
                cx = cx,
                centerY = centerY + 36f,
                accent = accent,
                secondary = deriveSecondaryAccent(accent),
                lightMode = lightMode,
                tSec = tSec,
                intensity = playIntensity,
            )
        }
    }

    private fun drawProgressBar(
        canvas: Canvas,
        left: Float,
        topY: Float,
        width: Float,
        fraction: Float,
        elapsedMs: Long,
        durationMs: Long,
        accent: Int,
        secondary: Int,
        lightMode: Boolean,
    ) {
        val barH = 5f
        tempRect.set(left, topY, left + width, topY + barH)
        fillPaint.shader = null
        fillPaint.color = if (lightMode) Color.argb(38, 15, 23, 42) else Color.argb(44, 255, 255, 255)
        canvas.drawRoundRect(tempRect, 3f, 3f, fillPaint)

        val filledW = (width * fraction).coerceAtLeast(8f)
        tempRect.set(left, topY, left + filledW, topY + barH)
        fillPaint.shader = LinearGradient(
            left,
            topY,
            left + filledW,
            topY,
            intArrayOf(secondary, accent),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(tempRect, 3f, 3f, fillPaint)
        fillPaint.shader = null

        // Glowing knob dot
        val knobX = left + filledW
        val knobY = topY + barH / 2f
        fillPaint.color = withAlpha(accent, if (lightMode) 75 else 110)
        canvas.drawCircle(knobX, knobY, 9.5f, fillPaint)
        fillPaint.color = if (lightMode) accent else Color.WHITE
        canvas.drawCircle(knobX, knobY, 5f, fillPaint)

        // Timestamps
        textPaint.clearShadowLayer()
        textPaint.typeface = monoSemiBold
        textPaint.textSize = 15.5f
        textPaint.color = if (lightMode) Color.argb(185, 51, 65, 85) else Color.argb(175, 226, 232, 240)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(formatMillis(elapsedMs), left, topY + 24f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatMillis(durationMs), left + width, topY + 24f, textPaint)
    }

    private fun buildCapsuleText(frame: GeelyLyricsRenderFrame?): String {
        if (frame == null) return ""
        val t = frame.displayTitle.trim()
        val a = frame.displayArtist.trim()
        return when {
            t.isNotEmpty() && a.isNotEmpty() && !t.contains(a, ignoreCase = true) -> "$t  ·  $a"
            t.isNotEmpty() -> t
            a.isNotEmpty() -> a
            else -> ""
        }
    }

    private fun buildPreviewDemoFrame(nowUptimeMs: Long): GeelyLyricsRenderFrame {
        val demoLines = PREVIEW_DEMO_LINES
        val cycleSec = ((nowUptimeMs / 1000.0) % 24.0).toFloat()
        val activeIdx = (cycleSec / 4f).toInt().coerceIn(0, demoLines.lastIndex)
        val prog = ((cycleSec - activeIdx * 4f) / 4f).coerceIn(0f, 1f)
        return GeelyLyricsRenderFrame(
            mode = GeelyLyricsDisplayMode.SYNCED_LRC,
            displayTitle = "星海漫游",
            displayArtist = "霓虹旅人",
            lines = demoLines,
            activeIndex = activeIdx,
            lineProgress = prog,
            elapsedMs = 62_000L + (cycleSec * 1000f).toLong(),
            durationMs = 242_000L,
            playing = true,
        )
    }

    private fun fitFontSize(text: String, paint: Paint, maxSize: Float, minSize: Float, maxWidth: Float): Float {
        paint.textSize = maxSize
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) return maxSize
        return (maxSize * (maxWidth / measured)).coerceIn(minSize, maxSize)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun formatMillis(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    private fun deriveSecondaryAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + 42f) % 360f
        hsv[1] = hsv[1].coerceAtLeast(0.68f)
        hsv[2] = hsv[2].coerceAtLeast(0.88f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    companion object {
        private const val CENTER_X = 960f
        private const val COVER_SIZE = 204f
        private const val COVER_TOP_Y = 104f
        private const val COVER_CORNER = 26f
        private const val STAGE_WIDTH = 700f
        private const val STAGE_TOP_Y = 386f
        private const val STAGE_HEIGHT = 190f
        private const val PROGRESS_WIDTH = 420f
        private const val PROGRESS_TOP_Y = 590f

        private val PREVIEW_DEMO_LINES = listOf(
            GeelyLyricLine(0L, "夜风掠过挡风玻璃的微光", 4_000L),
            GeelyLyricLine(4_000L, "沿着高速路灯驶向无尽海港", 4_000L),
            GeelyLyricLine(8_000L, "星河在仪表盘边缘轻轻荡漾", 4_000L),
            GeelyLyricLine(12_000L, "此刻心跳与引擎声同频共振", 4_000L),
            GeelyLyricLine(16_000L, "把城市的喧嚣都留在后视镜里", 4_000L),
            GeelyLyricLine(20_000L, "下一段旅程正随旋律缓缓点亮", 4_000L),
        )
    }
}
