package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private object GeelyUiDimens {
    const val BLOCK_GAP_DP = 16
    const val CARD_CORNER_DP = 20
    const val SUBCARD_CORNER_DP = 14
    const val CONTROL_CORNER_DP = 12
    const val PILL_CORNER_DP = 999
    const val CARD_PAD_H_DP = 20
    const val CARD_PAD_V_DP = 18
    const val SUBCARD_PAD_H_DP = 14
    const val SUBCARD_PAD_V_DP = 12
    const val CONTROL_HEIGHT_DP = 44
    const val COMPACT_BUTTON_HEIGHT_DP = 40
    const val SEGMENT_HEIGHT_DP = 42
    const val WIDE_SCREEN_MIN_DP = 760
    const val MEDIUM_SCREEN_MIN_DP = 520
    const val STUDIO_SPLIT_MIN_DP = 720
}

internal enum class GeelyCalibrationArea(
    val shortTitle: String,
    val fullTitle: String,
    val defaultSummary: String,
    val outlineColor: Int,
) {
    HUD(
        shortTitle = "W-HUD 区域",
        fullTitle = "W-HUD 抬头显示",
        defaultSummary = "默认 0, 720 · 228×186",
        outlineColor = Color.rgb(24, 203, 170),
    ),
    FULLSCREEN_CARD(
        shortTitle = "全屏导航卡",
        fullTitle = "全屏顶部导航卡",
        defaultSummary = "默认 595, 118 · 728×112",
        outlineColor = Color.rgb(110, 185, 255),
    ),
    CLASSIC_CARD(
        shortTitle = "经典仪表卡",
        fullTitle = "经典仪表导航卡",
        defaultSummary = "默认 1165, 720 · 364×112",
        outlineColor = Color.rgb(255, 194, 71),
    );
}

/** Semantic colors for Geely cluster settings UI, derived from DiPlay's active light/dark palette. */
internal data class GeelyClusterUiColors(
    val palette: DiPlayPalette,
    val isNight: Boolean,
) {
    val background: Int get() = palette.background
    val surface: Int get() = palette.surface
    val itemSurface: Int get() = if (isNight) Color.rgb(26, 37, 54) else Color.rgb(239, 244, 250)
    val previewFrame: Int get() = if (isNight) Color.rgb(10, 15, 24) else Color.rgb(220, 229, 241)
    val button: Int get() = palette.button
    val outline: Int get() = palette.outline
    val selectedSurface: Int get() = palette.railSelected
    val selectedOutline: Int get() = palette.railSelectedOutline
    val primaryText: Int get() = palette.primaryText
    val secondaryText: Int get() = palette.secondaryText
    val accent: Int get() = palette.accent
    val onAccent: Int get() = palette.onAccent
    val ripple: Int get() = palette.ripple
    val success: Int get() = palette.success
    val warning: Int get() = palette.warning
    val successBg: Int get() = ColorUtils.setAlphaComponent(palette.success, if (isNight) 38 else 32)
    val warningBg: Int get() = ColorUtils.setAlphaComponent(palette.warning, if (isNight) 38 else 32)
    val divider: Int get() = if (isNight) Color.argb(24, 255, 255, 255) else Color.argb(24, 28, 28, 30)
    val switchTheme: SettingsTheme get() = SettingsTheme.card(palette)

    companion object {
        fun from(palette: DiPlayPalette): GeelyClusterUiColors {
            val night = palette === DiPlayPalette.DARK || palette.background == DiPlayPalette.DARK.background
            return GeelyClusterUiColors(palette, night)
        }

        fun resolve(activity: Activity): GeelyClusterUiColors {
            val night = (activity as? AppAppearanceOwner)?.currentAppNight ?: activity.resolveAppNightNow()
            return GeelyClusterUiColors(DiPlayPalette.of(night), night)
        }
    }
}

internal fun formatGeelyWheelKey(code: Int): String {
    if (code <= 0) return "未绑定"
    val name = when (code) {
        EcarxKx11WheelKeys.KEY_WECHAT -> "微信键"
        EcarxKx11WheelKeys.KEY_MULTI_FUNCTION -> "自定义键"
        EcarxKx11WheelKeys.KEY_VOICE_ASSIST -> "语音键"
        EcarxKx11WheelKeys.KEY_VOLUME_MUTE -> "静音键"
        EcarxKx11WheelKeys.KEY_MODE -> "MODE 键"
        EcarxKx11WheelKeys.KEY_PLAY_PAUSE -> "播放/暂停键"
        EcarxKx11WheelKeys.KEY_NEXT -> "下一曲键"
        EcarxKx11WheelKeys.KEY_PREVIOUS -> "上一曲键"
        EcarxKx11WheelKeys.KEY_CALL -> "接听键"
        EcarxKx11WheelKeys.KEY_ENDCALL -> "挂断键"
        EcarxKx11WheelKeys.KEY_MENU -> "菜单键"
        EcarxKx11WheelKeys.KEY_SETTINGS -> "设置键"
        EcarxKx11WheelKeys.KEY_NAVI -> "导航键"
        EcarxKx11WheelKeys.KEY_RADIO -> "收音机键"
        EcarxKx11WheelKeys.KEY_MEDIA -> "媒体键"
        EcarxKx11WheelKeys.KEY_SRC -> "音源键"
        EcarxKx11WheelKeys.KEY_SEEK_NEXT -> "下搜台键"
        EcarxKx11WheelKeys.KEY_SEEK_PREVIOUS -> "上搜台键"
        else -> EcarxKx11WheelKeys.discoveredKeyLabel(code)
    }
    return if (name != null) "$name ($code)" else "按键 $code"
}

private fun themeSubtitleTag(style: GeelyClusterThemeStyle): String =
    style.displayName.substringAfter("·", "").trim()

private fun themeCompactSummary(style: GeelyClusterThemeStyle): String = when (style) {
    GeelyClusterThemeStyle.OEM_CLASSIC -> "保持原车 QNX 默认界面 · 保留 W-HUD 与经典卡"
    GeelyClusterThemeStyle.PURE_MAP -> "无圆盘无遮罩 · 下方保留音乐 · 无图自动回落原车"
    GeelyClusterThemeStyle.AERO_MECHA -> "战术切角双翼 · 28 格 LED 能量块"
    GeelyClusterThemeStyle.HYPER_WING -> "C 形镜像光翼 · 24 枚渐宽能量刃"
    GeelyClusterThemeStyle.VALKYRIE_HEX -> "八边形双层装甲 · 13 颗联动节点"
    GeelyClusterThemeStyle.ORBIT_RADAR -> "全息方位缺口环 · 36 枚脉冲光束"
    GeelyClusterThemeStyle.TITAN_CORE -> "12 枚宽体装甲块 · 36 枚同步微针"
    GeelyClusterThemeStyle.PHANTOM_STEALTH -> "隐形尖角折线翼 · 18 枚刀锋节流阀"
}

/**
 * Compact 42x42dp custom vector emblem visually distinguishing each of the 6 mecha themes.
 */
private class GeelyThemeGlyphView(
    context: Context,
    private val style: GeelyClusterThemeStyle,
    private val accentColor: Int,
    private val night: Boolean,
) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val path = Path()
    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val s = size / 48f
        val save = canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(s, s)

        val bgColor = if (night) Color.rgb(11, 18, 32) else Color.rgb(238, 243, 250)
        val dimColor = if (night) Color.argb(48, 255, 255, 255) else Color.argb(46, 15, 23, 42)

        when (style) {
            GeelyClusterThemeStyle.OEM_CLASSIC -> {
                rectF.set(3f, 7f, 45f, 41f)
                fillPaint.color = bgColor
                canvas.drawRoundRect(rectF, 10f, 10f, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.5f
                strokePaint.pathEffect = null
                canvas.drawRoundRect(rectF, 10f, 10f, strokePaint)

                // Classic OEM twin circular gauges
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 2.1f
                canvas.drawCircle(15.5f, 22f, 7.2f, strokePaint)
                canvas.drawCircle(32.5f, 22f, 7.2f, strokePaint)

                textPaint.color = accentColor
                textPaint.textSize = 8.5f
                textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                canvas.drawText("OEM", 24f, 36.5f, textPaint)
            }
            GeelyClusterThemeStyle.PURE_MAP -> {
                rectF.set(3f, 7f, 45f, 41f)
                fillPaint.color = bgColor
                canvas.drawRoundRect(rectF, 10f, 10f, fillPaint)
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 1.6f
                strokePaint.pathEffect = null
                canvas.drawRoundRect(rectF, 10f, 10f, strokePaint)

                // Full-bleed road horizon & navigation route arrow
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.3f
                canvas.drawLine(9f, 29f, 19f, 16f, strokePaint)
                canvas.drawLine(39f, 29f, 29f, 16f, strokePaint)

                path.reset()
                path.moveTo(24f, 12.5f)
                path.lineTo(29.5f, 25f)
                path.lineTo(24f, 22f)
                path.lineTo(18.5f, 25f)
                path.close()
                fillPaint.color = accentColor
                canvas.drawPath(path, fillPaint)

                // Bottom music EQ bars indicator
                fillPaint.color = accentColor
                canvas.drawRoundRect(16f, 33.5f, 18.2f, 36.8f, 1f, 1f, fillPaint)
                canvas.drawRoundRect(19.5f, 31.5f, 21.7f, 36.8f, 1f, 1f, fillPaint)
                canvas.drawRoundRect(23f, 33f, 25.2f, 36.8f, 1f, 1f, fillPaint)
                canvas.drawRoundRect(27.5f, 34f, 33.5f, 36f, 1f, 1f, fillPaint)
            }
            GeelyClusterThemeStyle.AERO_MECHA -> {
                buildOctagon(path, 24f, 24f, 20f)
                fillPaint.color = bgColor
                canvas.drawPath(path, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.6f
                strokePaint.pathEffect = null
                canvas.drawPath(path, strokePaint)

                rectF.set(10f, 10f, 38f, 38f)
                strokePaint.strokeWidth = 3.6f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(3.2f, 2.2f), 0f)
                strokePaint.color = dimColor
                canvas.drawArc(rectF, 135f, 270f, false, strokePaint)
                strokePaint.color = accentColor
                canvas.drawArc(rectF, 135f, 165f, false, strokePaint)
                strokePaint.pathEffect = null

                textPaint.color = accentColor
                textPaint.textSize = 11.5f
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
                canvas.drawText("86", 24f, 28f, textPaint)
            }
            GeelyClusterThemeStyle.HYPER_WING -> {
                rectF.set(3f, 3f, 45f, 45f)
                fillPaint.color = bgColor
                canvas.drawRoundRect(rectF, 12f, 12f, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.4f
                strokePaint.pathEffect = null
                canvas.drawRoundRect(rectF, 12f, 12f, strokePaint)

                rectF.set(9f, 11f, 39f, 37f)
                strokePaint.strokeWidth = 3.6f
                strokePaint.color = accentColor
                canvas.drawArc(rectF, 125f, 110f, false, strokePaint)
                strokePaint.color = dimColor
                canvas.drawArc(rectF, -55f, 110f, false, strokePaint)

                fillPaint.color = ColorUtils.setAlphaComponent(accentColor, 220)
                rectF.set(14f, 9f, 34f, 12f)
                canvas.drawRoundRect(rectF, 1.5f, 1.5f, fillPaint)

                textPaint.color = accentColor
                textPaint.textSize = 11f
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText("128", 24f, 29f, textPaint)
            }
            GeelyClusterThemeStyle.VALKYRIE_HEX -> {
                buildPolygon(path, 24f, 24f, 20f, 6, -Math.PI / 2.0)
                fillPaint.color = bgColor
                canvas.drawPath(path, fillPaint)
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 1.8f
                strokePaint.pathEffect = null
                canvas.drawPath(path, strokePaint)

                buildPolygon(path, 24f, 24f, 14f, 6, -Math.PI / 2.0)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.4f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(4f, 2f), 0f)
                canvas.drawPath(path, strokePaint)
                strokePaint.pathEffect = null

                path.reset()
                path.moveTo(24f, 12f)
                path.lineTo(27.5f, 15.5f)
                path.lineTo(24f, 19f)
                path.lineTo(20.5f, 15.5f)
                path.close()
                fillPaint.color = accentColor
                canvas.drawPath(path, fillPaint)

                textPaint.color = accentColor
                textPaint.textSize = 10.5f
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText("V6", 24f, 30.5f, textPaint)
            }
            GeelyClusterThemeStyle.ORBIT_RADAR -> {
                fillPaint.color = bgColor
                canvas.drawCircle(24f, 24f, 20f, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.3f
                strokePaint.pathEffect = null
                canvas.drawCircle(24f, 24f, 20f, strokePaint)

                rectF.set(9f, 9f, 39f, 39f)
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 2.6f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 6f), 0f)
                canvas.drawOval(rectF, strokePaint)
                strokePaint.pathEffect = null

                strokePaint.strokeWidth = 1.6f
                canvas.drawLine(8f, 24f, 15f, 24f, strokePaint)
                canvas.drawLine(33f, 24f, 40f, 24f, strokePaint)
                fillPaint.color = accentColor
                canvas.drawCircle(24f, 24f, 3.6f, fillPaint)
            }
            GeelyClusterThemeStyle.TITAN_CORE -> {
                rectF.set(4f, 4f, 44f, 44f)
                fillPaint.color = bgColor
                canvas.drawRoundRect(rectF, 8f, 8f, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.5f
                strokePaint.pathEffect = null
                canvas.drawRoundRect(rectF, 8f, 8f, strokePaint)

                rectF.set(9f, 9f, 39f, 39f)
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 4.5f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(5.5f, 3.5f), 0f)
                canvas.drawOval(rectF, strokePaint)
                strokePaint.pathEffect = null

                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.5f
                canvas.drawCircle(24f, 24f, 9.5f, strokePaint)
                fillPaint.color = accentColor
                rectF.set(19f, 21f, 29f, 27f)
                canvas.drawRoundRect(rectF, 1.8f, 1.8f, fillPaint)
            }
            GeelyClusterThemeStyle.PHANTOM_STEALTH -> {
                path.reset()
                path.moveTo(24f, 5f)
                path.lineTo(43f, 19f)
                path.lineTo(37f, 42f)
                path.lineTo(11f, 42f)
                path.lineTo(5f, 19f)
                path.close()
                fillPaint.color = bgColor
                canvas.drawPath(path, fillPaint)
                strokePaint.color = dimColor
                strokePaint.strokeWidth = 1.5f
                strokePaint.pathEffect = null
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(11f, 31f)
                path.lineTo(15f, 17f)
                path.lineTo(24f, 12f)
                path.lineTo(33f, 17f)
                path.lineTo(37f, 31f)
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 2.8f
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(24f, 18f)
                path.lineTo(28.5f, 25.5f)
                path.lineTo(19.5f, 25.5f)
                path.close()
                fillPaint.color = accentColor
                canvas.drawPath(path, fillPaint)

                strokePaint.color = ColorUtils.setAlphaComponent(accentColor, 180)
                strokePaint.strokeWidth = 2f
                canvas.drawLine(15f, 34f, 33f, 34f, strokePaint)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun buildOctagon(out: Path, cx: Float, cy: Float, r: Float) {
        buildPolygon(out, cx, cy, r, 8, Math.PI / 8.0)
    }

    private fun buildPolygon(out: Path, cx: Float, cy: Float, r: Float, sides: Int, offsetRad: Double) {
        out.reset()
        for (i in 0 until sides) {
            val angle = offsetRad + i * (2.0 * Math.PI / sides)
            val x = cx + (cos(angle) * r).toFloat()
            val y = cy + (sin(angle) * r).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
    }
}

/**
 * Interactive 1920x1080 scaled preview widget showing:
 * - Upper `1920x720` map region + dual mecha vector dials + fullscreen navigation card
 * - Lower `1920x360` (`Y: 720..1080`) hardware crop region + W-HUD + classic cluster card
 * - Dashed bounding boxes and coordinate labels with optional active-area highlight.
 */
internal class GeelyCompositePreviewView(context: Context) : View(context) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(context)
        set(value) {
            field = value
            fakeUltraView.config = value
            fakeUltraView.previewMode = true
            invalidate()
        }

    var previewRouteActive: Boolean = true
        set(value) {
            field = value
            fakeUltraView.routeActive = value
            invalidate()
        }

    var highlightedArea: GeelyCalibrationArea? = null
        set(value) {
            field = value
            invalidate()
        }

    private val fakeUltraView = GeelyFakeUltraOverlayView(context).apply {
        previewMode = true
        routeActive = true
    }

    private val demoPreviewArtwork: Bitmap by lazy { createDemoPreviewArtwork() }

    private fun createDemoPreviewArtwork(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 256f, 256f,
            intArrayOf(Color.rgb(28, 72, 138), Color.rgb(112, 46, 134), Color.rgb(218, 92, 68)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = null
        p.color = Color.argb(65, 255, 255, 255)
        c.drawCircle(128f, 118f, 62f, p)
        p.color = Color.argb(190, 255, 255, 255)
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        p.textSize = 42f
        c.drawText("♪", 128f, 132f, p)
        p.textSize = 20f
        c.drawText("ALBUM", 128f, 214f, p)
        return bmp
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 21f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val scale = min(
            width.toFloat() / GeelyOverlayArea.CANVAS_WIDTH.toFloat(),
            height.toFloat() / GeelyOverlayArea.CANVAS_HEIGHT.toFloat(),
        )
        val drawW = GeelyOverlayArea.CANVAS_WIDTH * scale
        val drawH = GeelyOverlayArea.CANVAS_HEIGHT * scale
        val dx = (width - drawW) / 2f
        val dy = (height - drawH) / 2f

        val save = canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        // 1. Upper 1920x720 simulated map / OEM cluster background
        val previewNight = GeelyClusterEnhancer.resolveEffectiveNightMode(config, context)
        val isOemPreview = config.dialThemeStyle.isOemNative
        val isPureMapPreview = config.dialThemeStyle.isPureMap
        bgPaint.color = when {
            isOemPreview -> if (previewNight) Color.rgb(12, 17, 26) else Color.rgb(225, 232, 240)
            else -> if (previewNight) Color.rgb(16, 26, 36) else Color.rgb(218, 226, 235)
        }
        canvas.drawRect(0f, 0f, 1920f, 720f, bgPaint)

        // Subtle simulated navigation road grid when previewing Pure Map full-bleed mode
        if (isPureMapPreview) {
            val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 6f
                color = if (previewNight) Color.argb(46, 96, 165, 250) else Color.argb(58, 37, 99, 235)
            }
            canvas.drawLine(960f, 170f, 440f, 720f, roadPaint)
            canvas.drawLine(960f, 170f, 1480f, 720f, roadPaint)
            roadPaint.strokeWidth = 10f
            roadPaint.color = if (previewNight) Color.argb(150, 56, 189, 248) else Color.argb(175, 2, 132, 199)
            canvas.drawLine(960f, 650f, 960f, 260f, roadPaint)
        }

        // 2. Lower 1920x360 (Y: 720..1080) hardware crop region
        bgPaint.color = Color.rgb(8, 11, 16)
        canvas.drawRect(0f, 720f, 1920f, 1080f, bgPaint)

        // 3. Render OEM classic schematic or dual dials / pure map & bottom bar in 1920x720
        if (isOemPreview) {
            val oemRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 12f
                color = if (previewNight) Color.argb(82, 148, 163, 184) else Color.argb(92, 71, 85, 105)
            }
            canvas.drawCircle(370f, 360f, 200f, oemRingPaint)
            canvas.drawCircle(1550f, 360f, 200f, oemRingPaint)
            val oemTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                textSize = 44f
                color = if (previewNight) Color.rgb(226, 232, 240) else Color.rgb(30, 41, 59)
            }
            val oemSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 26f
                color = if (previewNight) Color.rgb(148, 163, 184) else Color.rgb(71, 85, 105)
            }
            canvas.drawText("原车经典主驾仪表（QNX Mode 1）", 960f, 342f, oemTitlePaint)
            canvas.drawText("不覆盖主画面 · 下方 W-HUD 与经典仪表导航卡正常输出 · 方向盘可随时切出", 960f, 398f, oemSubPaint)
        } else {
            val showAlbum = config.centerMode == GeelyClusterCenterMode.ALBUM
            val previewCarPlay = previewRouteActive ||
                isPureMapPreview ||
                config.centerMode == GeelyClusterCenterMode.MAP ||
                showAlbum
            fakeUltraView.carPlayConnected = previewCarPlay
            fakeUltraView.carPlayMediaActive = showAlbum || previewRouteActive || isPureMapPreview
            fakeUltraView.nightMode = previewNight
            if (showAlbum || previewRouteActive || isPureMapPreview) {
                fakeUltraView.setArtwork(demoPreviewArtwork)
                fakeUltraView.setMusicInfo("千千阙歌 · 陈慧娴", true)
            } else {
                fakeUltraView.setArtwork(null)
                fakeUltraView.setMusicInfo(null, false)
            }
            fakeUltraView.measure(
                MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
            )
            fakeUltraView.layout(0, 0, 1920, 720)
            fakeUltraView.draw(canvas)
        }

        // 4. Render Self-Draw HUD, cluster card, fullscreen card & lane guidance in 1920x1080
        val effectivePreviewFullScreen = previewRouteActive && !isOemPreview
        GeelySelfDrawRenderer.drawAll(
            canvas = canvas,
            config = config,
            guidance = null,
            lanes = emptyList(),
            nightMode = previewNight,
            fullScreen = effectivePreviewFullScreen,
            nowMs = SystemClock.uptimeMillis(),
            forcePreview = previewRouteActive,
        )

        // 5. Split line at Y = 720
        borderPaint.strokeWidth = 2.5f
        borderPaint.color = Color.argb(150, 100, 180, 255)
        canvas.drawLine(0f, 720f, 1920f, 720f, borderPaint)
        labelPaint.color = Color.argb(200, 140, 200, 255)
        canvas.drawText("▲ 仪表主画面区 (1920×720)    ▼ 原车 W-HUD 与经典仪表卡裁剪源区 (Y: 720..1080)", 252f, 754f, labelPaint)

        // 6. Bounding boxes for active self-draw areas
        if (config.hudEnabled) {
            drawAreaOutline(
                canvas = canvas,
                area = config.hudArea,
                color = GeelyCalibrationArea.HUD.outlineColor,
                tag = "W-HUD",
                emphasized = highlightedArea == GeelyCalibrationArea.HUD,
            )
        }
        if (config.cardEnabled) {
            drawAreaOutline(
                canvas = canvas,
                area = config.cardArea,
                color = GeelyCalibrationArea.CLASSIC_CARD.outlineColor,
                tag = "经典仪表卡",
                emphasized = highlightedArea == GeelyCalibrationArea.CLASSIC_CARD,
            )
        }
        if (config.fullscreenCardEnabled && effectivePreviewFullScreen) {
            drawAreaOutline(
                canvas = canvas,
                area = config.fullscreenCardArea,
                color = GeelyCalibrationArea.FULLSCREEN_CARD.outlineColor,
                tag = "全屏导航卡",
                emphasized = highlightedArea == GeelyCalibrationArea.FULLSCREEN_CARD,
            )
        }

        canvas.restoreToCount(save)
    }

    private fun drawAreaOutline(
        canvas: Canvas,
        area: GeelyOverlayArea,
        color: Int,
        tag: String,
        emphasized: Boolean,
    ) {
        borderPaint.color = color
        borderPaint.strokeWidth = if (emphasized) 5.5f else 2.5f
        canvas.drawRect(
            area.x.toFloat(),
            area.y.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            borderPaint,
        )
        labelPaint.color = color
        val labelY = if (area.y >= 720) (area.bottom + 26f).coerceAtMost(1070f) else (area.y - 10f).coerceAtLeast(24f)
        canvas.drawText("$tag (${area.x}, ${area.y} · ${area.width}×${area.height})", area.x.toFloat(), labelY, labelPaint)
    }
}

/**
 * Redesigned Geely Cluster & HUD Settings Panel controller supporting:
 * 1. Multi-card inline layout inside `SettingsCategory.GEELY` (4 purpose-driven tabs, zero duplicate switches)
 * 2. Fullscreen widescreen split-pane Studio dialog (`GeelyClusterSettingsDialog`) with persistent live preview
 */
internal class GeelyClusterSettingsPanel(
    private val activity: Activity,
    private val colors: GeelyClusterUiColors,
    private val studioMode: Boolean = false,
    private val onStateChanged: () -> Unit = {},
) {
    var config: GeelySelfDrawConfig = run {
        EcarxKx11WheelKeys.ensureDefaultPresets(activity)
        GeelyClusterPrefs.load(activity)
    }
        private set

    var selectedTab: Int
        get() = (if (studioMode) GeelyClusterEnhancer.lastStudioTab else GeelyClusterEnhancer.lastSelectedSettingsTab)
            .coerceIn(0, tabLabels().lastIndex)
        set(value) {
            val clamped = value.coerceIn(0, tabLabels().lastIndex)
            if (studioMode) {
                GeelyClusterEnhancer.lastStudioTab = clamped
            } else {
                GeelyClusterEnhancer.lastSelectedSettingsTab = clamped
            }
        }

    private var previewRouteActive: Boolean
        get() = GeelyClusterEnhancer.lastPreviewRouteActive
        set(value) {
            GeelyClusterEnhancer.lastPreviewRouteActive = value
        }

    private var activeCalibrationArea: GeelyCalibrationArea
        get() = GeelyClusterEnhancer.lastCalibrationArea
        set(value) {
            GeelyClusterEnhancer.lastCalibrationArea = value
        }

    private var learningTarget: EcarxKx11WheelKeys.ClusterLearnTarget? = null
    private var previewView: GeelyCompositePreviewView? = null
    private var studioHeaderSubtitle: TextView? = null
    private var inlineStatusRow: LinearLayout? = null
    private var tabContentContainer: LinearLayout? = null
    private var studioPreviewControlsContainer: LinearLayout? = null
    private var scrollToTopCallback: (() -> Unit)? = null
    private val tabButtons = ArrayList<LinearLayout>()

    private fun tabLabels(): List<String> = if (studioMode) STUDIO_TAB_TITLES else INLINE_TAB_TITLES

    private fun isWideScreen(): Boolean =
        activity.resources.configuration.screenWidthDp >= GeelyUiDimens.WIDE_SCREEN_MIN_DP

    private fun isMediumScreen(): Boolean =
        activity.resources.configuration.screenWidthDp >= GeelyUiDimens.MEDIUM_SCREEN_MIN_DP

    private fun useTwoRowTabs(): Boolean =
        !studioMode && (activity.resources.configuration.screenWidthDp < 600 || activity.resources.configuration.fontScale > 1.15f)

    /** Appends the redesigned status banner, 4-tab switcher, and semantic cards directly into the Geely settings page. */
    fun appendInlineContent(parent: LinearLayout) {
        val gap = dp(GeelyUiDimens.BLOCK_GAP_DP)

        // 1. Compact Status & Studio Entry Card
        val statusCard = createSurfaceCard(padV = 14)
        val statusLayout = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pillGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        inlineStatusRow = pillGroup
        statusLayout.addView(
            pillGroup,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            },
        )
        val studioBtn = createPrimaryButton("打开全屏预览与校准 ↗") {
            openFullscreenStudio()
        }
        statusLayout.addView(
            studioBtn,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.CONTROL_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.CONTROL_HEIGHT_DP))
            },
        )
        statusCard.addView(statusLayout)
        parent.addView(statusCard, matchWrap(bottomMargin = gap))

        // 2. Clean 4-Tab Segmented Bar
        val tabBarCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.surface, colors.outline, cornerDp = 16)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        tabButtons.clear()
        val tabs = INLINE_TAB_TITLES
        val icons = INLINE_TAB_ICONS
        if (useTwoRowTabs()) {
            for (rowIndex in 0..1) {
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (col in 0..1) {
                    val idx = rowIndex * 2 + col
                    val btn = createTabButton(tabs[idx], icons[idx]) { selectTab(idx) }
                    tabButtons.add(btn)
                    row.addView(
                        btn,
                        LinearLayout.LayoutParams(0, dp(GeelyUiDimens.CONTROL_HEIGHT_DP), 1f).apply {
                            if (col > 0) marginStart = dp(6)
                        },
                    )
                }
                tabBarCard.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (rowIndex > 0) topMargin = dp(6)
                    },
                )
            }
        } else {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (idx in tabs.indices) {
                val btn = createTabButton(tabs[idx], icons[idx]) { selectTab(idx) }
                tabButtons.add(btn)
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dp(GeelyUiDimens.CONTROL_HEIGHT_DP), 1f).apply {
                        if (idx > 0) marginStart = dp(6)
                    },
                )
            }
            tabBarCard.addView(row)
        }
        parent.addView(tabBarCard, matchWrap(bottomMargin = gap))

        // 3. Dynamic Tab Cards Container
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        tabContentContainer = contentCol
        parent.addView(contentCol, matchWrap())

        refreshStatusIndicators()
        updateTabButtonStyles()
        rebuildTabContent()
    }

    /** Binds views owned by the fullscreen widescreen [GeelyClusterSettingsDialog]. */
    fun bindStudioViews(
        headerSubtitle: TextView,
        livePreview: GeelyCompositePreviewView,
        previewControlsHost: LinearLayout,
        studioTabViews: List<LinearLayout>,
        contentContainer: LinearLayout,
        onScrollToTop: () -> Unit,
    ) {
        studioHeaderSubtitle = headerSubtitle
        previewView = livePreview
        studioPreviewControlsContainer = previewControlsHost
        tabButtons.clear()
        tabButtons.addAll(studioTabViews)
        tabContentContainer = contentContainer
        scrollToTopCallback = onScrollToTop

        livePreview.config = config
        livePreview.previewRouteActive = previewRouteActive
        livePreview.highlightedArea = if (selectedTab == 2) activeCalibrationArea else null

        refreshStatusIndicators()
        rebuildStudioPreviewControls()
        updateTabButtonStyles()
        rebuildTabContent()
    }

    fun selectTab(index: Int) {
        cancelLearningState()
        selectedTab = index
        previewView?.highlightedArea = if (studioMode && selectedTab == 2) activeCalibrationArea else null
        updateTabButtonStyles()
        rebuildTabContent()
        scrollToTopCallback?.invoke()
    }

    fun resetAreasToDefault() {
        config = GeelyClusterPrefs.resetAreasToDefault(activity)
        onConfigUpdated()
        rebuildTabContent()
        Toast.makeText(activity, "已恢复 HUD 与导航卡片默认坐标", Toast.LENGTH_SHORT).show()
    }

    fun cancelLearningState() {
        learningTarget = null
        EcarxKx11WheelKeys.cancelLearning()
    }

    private fun openFullscreenStudio() {
        cancelLearningState()
        if (selectedTab == 1) {
            GeelyClusterEnhancer.lastStudioTab = 2
        } else if (selectedTab == 3) {
            GeelyClusterEnhancer.lastStudioTab = 3
        }
        GeelyClusterEnhancer.showSettingsDialog(activity) {
            onStateChanged()
        }
    }

    private fun onConfigUpdated() {
        GeelyClusterPrefs.save(activity, config)
        GeelyClusterEnhancer.refreshConfig(activity)
        previewView?.apply {
            config = this@GeelyClusterSettingsPanel.config
            previewRouteActive = this@GeelyClusterSettingsPanel.previewRouteActive
            highlightedArea = if (studioMode && selectedTab == 2) activeCalibrationArea else null
            invalidate()
        }
        refreshStatusIndicators()
        if (studioMode) {
            rebuildStudioPreviewControls()
        }
    }

    private fun refreshStatusIndicators() {
        val display = GeelyVehicleDetector.findClusterDisplay(activity)
        val dispOk = display != null
        val dispText = if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            "仪表屏 · ${size.x}×${size.y} (ID ${display.displayId})"
        } else {
            "未检测到仪表副屏 (默认 ID ${config.preferredDisplayId})"
        }
        val overlayOk = Settings.canDrawOverlays(activity)
        val notifOk = GeelyMediaNotificationListenerService.isEnabled(activity)

        studioHeaderSubtitle?.text = "$dispText · 左侧画布随右侧修改即时联动"

        val host = inlineStatusRow ?: return
        host.removeAllViews()
        val firstRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        firstRow.addView(createStatusPill(dispText, dispOk) { selectTab(3) })
        host.addView(firstRow)

        val secondRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        secondRow.addView(
            createStatusPill(if (overlayOk) "悬浮窗已授权" else "悬浮窗未授权", overlayOk) { selectTab(3) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) },
        )
        secondRow.addView(
            createStatusPill(if (notifOk) "车机封面已授权" else "车机封面未授权", notifOk) { selectTab(3) },
        )
        host.addView(
            secondRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) },
        )
    }

    private fun updateTabButtonStyles() {
        for (i in tabButtons.indices) {
            styleTabButton(tabButtons[i], selected = i == selectedTab)
        }
    }

    private fun rebuildTabContent() {
        val container = tabContentContainer ?: return
        container.removeAllViews()
        if (!studioMode) {
            previewView = null
        }
        if (studioMode) {
            when (selectedTab) {
                0 -> {
                    addThemeGridCard(container, forceMaxTwoCols = true)
                    addAppearanceAndLaunchCard(container)
                }
                1 -> {
                    addElementsCard(container)
                    addCenterAndWheelCard(container)
                }
                2 -> {
                    addNavigationGuidanceCard(container)
                    addCalibrationCard(container)
                }
                3 -> {
                    addPermissionsCard(container)
                    addDisplayOutputCard(container)
                    addVehicleSignalProbeCard(container)
                    addResetCard(container)
                }
            }
        } else {
            when (selectedTab) {
                0 -> {
                    addThemeGridCard(container, forceMaxTwoCols = false)
                    addAppearanceAndLaunchCard(container)
                    addElementsCard(container)
                    addCenterAndWheelCard(container)
                }
                1 -> {
                    addNavigationGuidanceCard(container)
                    addCalibrationCard(container)
                }
                2 -> {
                    addPreviewCard(container)
                    addRealCarDebugCard(container)
                }
                3 -> {
                    addPermissionsCard(container)
                    addDisplayOutputCard(container)
                    addVehicleSignalProbeCard(container)
                    addResetCard(container)
                }
            }
        }
    }

    private fun rebuildStudioPreviewControls() {
        val host = studioPreviewControlsContainer ?: return
        host.removeAllViews()
        host.addView(buildPreviewActionRow())
        val divider = createHairline()
        host.addView(divider, matchWrap(topMargin = dp(10), bottomMargin = dp(8)))
        val labelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(
            TextView(activity).apply {
                text = "中央视图快捷切换"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        labelRow.addView(
            TextView(activity).apply {
                text = config.dialThemeStyle.shortLabel
                setTextColor(colors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        host.addView(labelRow, matchWrap(bottomMargin = dp(6)))
        host.addView(
            createCenterModeGrid(
                selected = config.centerMode,
                compact = true,
                heightDp = GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP,
            ) { mode ->
                val prevMode = config.centerMode
                config = config.copy(centerMode = mode)
                onConfigUpdated()
                GeelyClusterEnhancer.onCenterModeChangedByUser(activity, prevMode, mode)
                if (selectedTab == 1) rebuildTabContent()
            },
        )
    }

    // -------------------------------------------------------------------------
    // Tab 0: 仪表与主题 (Themes, Appearance & Launch, Elements, Center & Wheel)
    // -------------------------------------------------------------------------

    private fun addThemeGridCard(parent: LinearLayout, forceMaxTwoCols: Boolean) {
        val card = createSurfaceCard()
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(createCardTitle("全境仪表主题风格"))
        titleCol.addView(
            TextView(activity).apply {
                text = "原车经典 + 纯地图全屏 + 6 套机甲矢量双圆盘（共 ${GeelyClusterThemeStyle.entries.size} 套），点击立即切换"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            createTagBadge("当前：${config.dialThemeStyle.shortLabel}", colors.accent, colors.selectedSurface),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) },
        )
        card.addView(header, matchWrap(bottomMargin = dp(12)))

        val cols = when {
            forceMaxTwoCols -> if (activity.resources.configuration.screenWidthDp >= 560) 2 else 1
            isWideScreen() -> 3
            isMediumScreen() -> 2
            else -> 1
        }
        val effectiveNight = GeelyClusterEnhancer.resolveEffectiveNightMode(config, activity)
        val rows = GeelyClusterThemeStyle.entries.chunked(cols)
        for ((rowIdx, rowStyles) in rows.withIndex()) {
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (colIdx in 0 until cols) {
                val style = rowStyles.getOrNull(colIdx)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (colIdx > 0) marginStart = dp(10)
                }
                if (style != null) {
                    rowLayout.addView(buildThemeTile(style, effectiveNight), lp)
                } else {
                    rowLayout.addView(View(activity), lp)
                }
            }
            card.addView(
                rowLayout,
                matchWrap(topMargin = if (rowIdx > 0) dp(10) else 0),
            )
        }
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun buildThemeTile(style: GeelyClusterThemeStyle, night: Boolean): View {
        val selected = config.dialThemeStyle == style
        val themeAccent = style.defaultAccentColor(night)
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(
                ColorStateList.valueOf(colors.ripple),
                roundedBox(
                    fill = if (selected) colors.selectedSurface else colors.itemSurface,
                    stroke = if (selected) themeAccent else colors.outline,
                    cornerDp = GeelyUiDimens.SUBCARD_CORNER_DP,
                    strokeDp = if (selected) 2 else 1,
                ),
                null,
            )
            setOnClickListener {
                if (config.dialThemeStyle != style) {
                    config = config.copy(dialThemeStyle = style)
                    onConfigUpdated()
                    rebuildTabContent()
                }
            }
        }

        tile.addView(
            GeelyThemeGlyphView(activity, style, themeAccent, night),
            LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) },
        )

        val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val topLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topLine.addView(
            TextView(activity).apply {
                text = style.shortLabel
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (selected) {
            topLine.addView(
                createTagBadge("使用中", themeAccent, ColorUtils.setAlphaComponent(themeAccent, 38)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(6) },
            )
        } else {
            val tag = themeSubtitleTag(style)
            if (tag.isNotEmpty()) {
                topLine.addView(
                    TextView(activity).apply {
                        text = tag
                        setTextColor(colors.secondaryText)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(6) },
                )
            }
        }
        textCol.addView(topLine)
        textCol.addView(
            TextView(activity).apply {
                text = themeCompactSummary(style)
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            },
        )
        tile.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return tile
    }

    private fun addAppearanceAndLaunchCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(createCardTitle("外观与启动"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(createTagBadge("实时生效", colors.secondaryText, colors.itemSurface))
        card.addView(head, matchWrap(bottomMargin = dp(12)))

        // Day/Night segmented bar
        val effectiveNightNow = GeelyClusterEnhancer.resolveEffectiveNightMode(config, activity)
        val hasVehicleSignal = GeelyEcarxDayNightReader.latestNight() != null
        val autoTag = if (hasVehicleSignal) "原车" else "当前"
        val dayNightHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dayNightHead.addView(
            createSubLabel("仪表昼夜模式"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (config.appearanceMode == GeelyClusterAppearanceMode.AUTO) {
            dayNightHead.addView(
                TextView(activity).apply {
                    text = if (effectiveNightNow) "当前同步：${autoTag}深色" else "当前同步：${autoTag}浅色"
                    setTextColor(colors.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
            )
        }
        card.addView(dayNightHead, matchWrap(bottomMargin = dp(6)))
        card.addView(
            createSegmentedRow(
                options = listOf(
                    GeelyClusterAppearanceMode.AUTO to "跟随车辆",
                    GeelyClusterAppearanceMode.DARK to "始终深色",
                    GeelyClusterAppearanceMode.LIGHT to "始终浅色",
                ),
                selected = config.appearanceMode,
            ) { mode ->
                config = config.copy(appearanceMode = mode, mapThemeDark = mode != GeelyClusterAppearanceMode.LIGHT)
                onConfigUpdated()
                rebuildTabContent()
            },
            matchWrap(bottomMargin = dp(14)),
        )

        // Dial accent color mode segmented bar
        card.addView(createSubLabel("强调配色模式"), matchWrap(bottomMargin = dp(6)))
        card.addView(
            createSegmentedRow(
                options = listOf(
                    GeelyDialColorMode.DEFAULT to "随主题默认",
                    GeelyDialColorMode.SPEED to "随车速变化",
                    GeelyDialColorMode.ALBUM to "随专辑封面",
                ),
                selected = config.dialColorMode,
            ) { mode ->
                config = config.copy(dialColorMode = mode)
                onConfigUpdated()
                rebuildTabContent()
            },
            matchWrap(bottomMargin = dp(6)),
        )
        val colorHint = when (config.dialColorMode) {
            GeelyDialColorMode.DEFAULT -> "遵循当前主题专属配色（${config.dialThemeStyle.shortLabel}）"
            GeelyDialColorMode.SPEED -> "按车速 0 → 40 → 80 → 120+ km/h 从青、绿、琥珀平滑过渡至红色"
            GeelyDialColorMode.ALBUM -> "自动提取当前播放专辑封面主色，与中央封面浑然一体"
        }
        card.addView(createHintText(colorHint), matchWrap(bottomMargin = dp(12)))

        card.addView(createHairline(), matchWrap(bottomMargin = dp(4)))

        // Launch theme on start
        addFlatSwitchRow(
            container = card,
            title = "开机与启动时自动加载常驻全境仪表",
            subtitle = "车机开机后后台静默加载双圆盘与待机时钟，不弹出软件界面（除非同时开启「打开时自动连接 CarPlay」）；纯车机使用或断开 CarPlay 后保持显示。",
            checked = config.launchThemeOnStart,
        ) { checked ->
            config = config.copy(launchThemeOnStart = checked)
            onConfigUpdated()
            if (checked && !Settings.canDrawOverlays(activity)) {
                Toast.makeText(
                    activity,
                    "已开启启动加载主题，请先在「权限与副屏」中授予悬浮窗权限",
                    Toast.LENGTH_LONG,
                ).show()
            }
            rebuildTabContent()
        }

        if (config.launchThemeOnStart && !Settings.canDrawOverlays(activity)) {
            val banner = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBox(colors.warningBg, colors.warning, cornerDp = 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            banner.addView(
                TextView(activity).apply {
                    text = "需要授予「悬浮窗权限」才能在断开 CarPlay 后保持常驻仪表显示。"
                    setTextColor(colors.warning)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) },
            )
            banner.addView(
                createCompactPillAction("去授权", colors.warning, Color.rgb(17, 24, 39)) {
                    openOverlayPermissionSettings()
                },
            )
            card.addView(banner, matchWrap(topMargin = dp(6)))
        }

        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun addElementsCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("仪表画面元素"), matchWrap(bottomMargin = dp(6)))

        if (config.dialThemeStyle.isOemNative || config.dialThemeStyle.isPureMap) {
            val modeNotice = if (config.dialThemeStyle.isOemNative) {
                "当前为「原车经典」主题，左右圆盘、遮罩与底部音乐栏自动让位于原车界面；切回机甲或纯地图主题后自动生效。"
            } else {
                "当前为「纯地图全屏」主题，已自动隐藏左右圆盘与边缘遮罩，下方音乐栏与全屏导航卡按开关生效；切回机甲主题自动恢复以下开关。"
            }
            card.addView(
                createHintText(modeNotice),
                matchWrap(bottomMargin = dp(8)),
            )
        }

        addFlatSwitchRow(
            container = card,
            title = "左侧车速圆盘",
            subtitle = "显示实时车速与剩余油量百分比。",
            checked = config.dialLeftEnabled,
        ) { checked ->
            config = config.copy(dialLeftEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "右侧转速圆盘",
            subtitle = "显示发动机转速与实时档位；导航时中心可嵌入圆角专辑封面。",
            checked = config.dialRightEnabled,
        ) { checked ->
            config = config.copy(dialRightEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "底部 CarPlay 音乐与状态栏",
            subtitle = "CarPlay 播放音乐时居中显示动态声波与歌名，避免与原车底栏冲突。",
            checked = config.bottomBarEnabled,
        ) { checked ->
            config = config.copy(bottomBarEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "非导航专辑封面模式",
            subtitle = "未导航且正在播放音乐时，中央展示圆角专辑封面与雾面动态模糊背景。",
            checked = config.albumArtEnabled,
        ) { checked ->
            config = config.copy(albumArtEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "中央专辑滚动歌词（居中剧场）",
            subtitle = "专辑页升级为居中大封面 + 对称声波光翼 + 3 行大字扫光滚动歌词与流光进度条；关闭则仅显示简约封面。",
            checked = config.albumLyricsEnabled,
        ) { checked ->
            config = config.copy(albumLyricsEnabled = checked)
            onConfigUpdated()
            rebuildTabContent()
        }
        if (config.albumLyricsEnabled) {
            card.addView(createHairline())
            addFlatSwitchRow(
                container = card,
                title = "在线 LRC 完整歌词增强",
                subtitle = "识别歌名与歌手后后台联网匹配带时间轴的完整 LRC（支持前瞻下文与精准扫光），断网自动回落为实时流式歌词。",
                checked = config.albumOnlineLyricsEnabled,
            ) { checked ->
                config = config.copy(albumOnlineLyricsEnabled = checked)
                onConfigUpdated()
            }
            card.addView(createHairline())
            addFlatSwitchRow(
                container = card,
                title = "车机本地 / 蓝牙音乐开启中央滚动歌词",
                subtitle = "未连接 CarPlay 时，也同步读取车机本地音乐（网易云、QQ 音乐等）与蓝牙音乐的歌名、歌词及进度；若部分音源元数据不规范可随时关闭。",
                checked = config.albumLocalMediaLyricsEnabled,
            ) { checked ->
                config = config.copy(albumLocalMediaLyricsEnabled = checked)
                onConfigUpdated()
            }
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "地图边缘融合遮罩",
            subtitle = "柔化全屏地图四周边缘，突出双圆盘与顶部导航卡片的层次感。",
            checked = config.mapVignetteEnabled,
        ) { checked ->
            config = config.copy(mapVignetteEnabled = checked)
            onConfigUpdated()
        }

        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun addCenterAndWheelCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("中央视图与方向盘快捷键"), matchWrap(bottomMargin = dp(10)))

        card.addView(createSubLabel("中央区域默认显示"), matchWrap(bottomMargin = dp(6)))
        card.addView(
            createCenterModeGrid(
                selected = config.centerMode,
                compact = false,
                heightDp = GeelyUiDimens.SEGMENT_HEIGHT_DP,
            ) { mode ->
                val prevMode = config.centerMode
                config = config.copy(centerMode = mode)
                onConfigUpdated()
                GeelyClusterEnhancer.onCenterModeChangedByUser(activity, prevMode, mode)
                rebuildTabContent()
            },
            matchWrap(bottomMargin = dp(6)),
        )
        val centerHint = when (config.centerMode) {
            GeelyClusterCenterMode.AUTO -> "导航时显示地图，非导航播放音乐时显示专辑封面，待机时显示大号数字时钟。"
            GeelyClusterCenterMode.MAP -> "中央区域始终保留透明窗口用于显示全屏导航地图。"
            GeelyClusterCenterMode.ALBUM -> "优先展示专辑封面与居中剧场滚动歌词（无封面时自动回退为数字时钟）。"
            GeelyClusterCenterMode.CLOCK -> "始终显示大号数字时钟、日期与平均油耗。"
            GeelyClusterCenterMode.TRIP -> "实时展示本次行程的里程、用时、平均速度、平均油耗与瞬时油耗。"
            GeelyClusterCenterMode.TIRE -> "实时展示四轮胎压（kPa）与胎温（°C），异常轮胎自动高亮提醒。"
            GeelyClusterCenterMode.VEHICLE_INFO -> "实时展示总里程、总平均油耗、剩余续航与油量、车内外温度、保养参考及累计统计。"
        }
        card.addView(createHintText(centerHint), matchWrap(bottomMargin = dp(10)))

        card.addView(
            createActionButton("在中控屏查看车况与行程总览") {
                GeelyVehicleStatusDialog.show(activity) { chosenMode ->
                    val prevMode = config.centerMode
                    config = config.copy(centerMode = chosenMode)
                    onConfigUpdated()
                    GeelyClusterEnhancer.onCenterModeChangedByUser(activity, prevMode, chosenMode)
                    rebuildTabContent()
                }
            },
            matchWrap(bottomMargin = dp(10)).apply {
                height = dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)
            },
        )

        addFlatSwitchRow(
            container = card,
            title = "胎压 / 胎温异常自动提醒",
            subtitle = "即使当前仪表停留在导航地图、专辑或时钟页，检测到任一轮胎胎压或胎温异常时也会在仪表顶部弹出警示条。",
            checked = config.tireAlertPopupEnabled,
        ) { checked ->
            config = config.copy(tireAlertPopupEnabled = checked)
            onConfigUpdated()
        }

        card.addView(createHairline(), matchWrap(topMargin = dp(6), bottomMargin = dp(12)))

        // Subcard 1: Primary center view wheel key (Map / Album / Clock)
        val primaryKeyBox = createSubCard()
        val learningPrimary = learningTarget == EcarxKx11WheelKeys.ClusterLearnTarget.PRIMARY
        val primaryKeyHeader = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val primaryKeyInfoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        primaryKeyInfoCol.addView(
            TextView(activity).apply {
                text = "主视图切换键（地图 / 专辑 / 时钟）"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        primaryKeyInfoCol.addView(
            TextView(activity).apply {
                text = "当前绑定：${formatGeelyWheelKey(config.centerSwitchKeyCode)}"
                setTextColor(if (config.centerSwitchKeyCode > 0) colors.accent else colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        primaryKeyHeader.addView(
            primaryKeyInfoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            },
        )

        val primaryKeyBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val primaryLearnText = when {
            learningPrimary -> "请按下方向盘按键…"
            config.centerSwitchKeyCode > 0 -> "重新学习"
            else -> "学习按键"
        }
        val primaryLearnBtn = if (learningPrimary) {
            createPrimaryButton(primaryLearnText) {
                cancelLearningState()
                rebuildTabContent()
            }
        } else {
            createActionButton(primaryLearnText, selected = config.centerSwitchKeyCode > 0) {
                startWheelKeyLearning(EcarxKx11WheelKeys.ClusterLearnTarget.PRIMARY)
            }
        }
        primaryKeyBtnRow.addView(
            primaryLearnBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)),
        )
        if (config.centerSwitchKeyCode > 0) {
            val clearBtn = createActionButton("清除") {
                cancelLearningState()
                config = config.copy(centerSwitchKeyCode = 0)
                onConfigUpdated()
                rebuildTabContent()
            }
            primaryKeyBtnRow.addView(
                clearBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                    .apply { marginStart = dp(8) },
            )
        }
        primaryKeyHeader.addView(primaryKeyBtnRow)
        primaryKeyBox.addView(primaryKeyHeader, matchWrap(bottomMargin = dp(10)))

        val primaryTriggerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        primaryTriggerRow.addView(
            TextView(activity).apply {
                text = "触发手势"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        primaryTriggerRow.addView(
            createSegmentedRow(
                options = listOf(
                    GeelyClusterSwitchTrigger.SINGLE_CLICK to "单击",
                    GeelyClusterSwitchTrigger.LONG_PRESS to "长按",
                    GeelyClusterSwitchTrigger.DOUBLE_CLICK to "双击",
                ),
                selected = config.centerSwitchTrigger,
                heightDp = 38,
            ) { trigger ->
                val adjustedTelemetryTrigger = if (config.sharesTelemetryKeyWithPrimary && config.telemetrySwitchTrigger == trigger) {
                    val alt = GeelyClusterSwitchTrigger.resolveNonConflicting(
                        config.telemetrySwitchTrigger,
                        setOf(trigger),
                    )
                    Toast.makeText(activity, "已自动将车况查看手势错开为「${alt.shortLabel}」", Toast.LENGTH_SHORT).show()
                    alt
                } else {
                    config.telemetrySwitchTrigger
                }
                val adjustedThemeTrigger = if (config.sharesThemeKeyWithPrimary) {
                    val used = linkedSetOf(trigger)
                    if (config.sharesTelemetryKeyWithPrimary) used.add(adjustedTelemetryTrigger)
                    GeelyClusterSwitchTrigger.resolveNonConflicting(config.themeSwitchTrigger, used)
                } else {
                    config.themeSwitchTrigger
                }
                config = config.copy(
                    centerSwitchTrigger = trigger,
                    telemetrySwitchTrigger = adjustedTelemetryTrigger,
                    themeSwitchTrigger = adjustedThemeTrigger,
                )
                onConfigUpdated()
                rebuildTabContent()
            },
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        primaryKeyBox.addView(primaryTriggerRow, matchWrap(bottomMargin = dp(10)))

        primaryKeyBox.addView(
            TextView(activity).apply {
                text = "主视图循环页面（常驻显示，不自动跳回）"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            matchWrap(bottomMargin = dp(6)),
        )
        val primaryRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((cIdx, mode) in GeelyClusterCenterMode.PRIMARY_ORDER.withIndex()) {
            val enabled = mode in config.primaryCycleModes
            val label = if (enabled) "✓ ${mode.shortLabel}" else mode.shortLabel
            val chip = createActionButton(label, selected = enabled) {
                val nextSet = LinkedHashSet(config.primaryCycleModes)
                if (enabled) {
                    if (nextSet.size <= 1) {
                        Toast.makeText(activity, "至少保留 1 个主视图页面", Toast.LENGTH_SHORT).show()
                        return@createActionButton
                    }
                    nextSet.remove(mode)
                } else {
                    nextSet.add(mode)
                }
                config = config.copy(primaryCycleModes = nextSet)
                onConfigUpdated()
                rebuildTabContent()
            }
            primaryRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (cIdx > 0) marginStart = dp(6)
                },
            )
        }
        primaryKeyBox.addView(primaryRow, matchWrap(bottomMargin = dp(8)))
        primaryKeyBox.addView(
            createHintText("推荐微信键「单击」在导航地图与专辑封面间秒切并保持；若当前无地图或无封面会自动跳过。"),
        )
        card.addView(primaryKeyBox, matchWrap(bottomMargin = dp(10)))

        // Subcard 2: Temporary telemetry peek wheel key (Trip / Tire / Vehicle Info)
        val telemetryKeyBox = createSubCard()
        val learningTelemetry = learningTarget == EcarxKx11WheelKeys.ClusterLearnTarget.TELEMETRY
        val telemetryHeader = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val telemetryInfoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        telemetryInfoCol.addView(
            TextView(activity).apply {
                text = "车况查看快捷键（行程 / 胎压 / 车况）"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        val telemetryBindLabel = when {
            config.telemetrySwitchKeyCode > 0 ->
                "独立按键：${formatGeelyWheelKey(config.telemetrySwitchKeyCode)}"
            config.centerSwitchKeyCode > 0 ->
                "共用主视图键：${formatGeelyWheelKey(config.centerSwitchKeyCode)}"
            else -> "未绑定（先绑定主视图键或单独学习）"
        }
        telemetryInfoCol.addView(
            TextView(activity).apply {
                text = telemetryBindLabel
                setTextColor(if (config.effectiveTelemetrySwitchKeyCode > 0) colors.accent else colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        telemetryHeader.addView(
            telemetryInfoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            },
        )

        val telemetryBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val telemetryLearnText = when {
            learningTelemetry -> "请按下方向盘按键…"
            config.telemetrySwitchKeyCode > 0 -> "改绑按键"
            else -> "单独绑定按键"
        }
        val telemetryLearnBtn = if (learningTelemetry) {
            createPrimaryButton(telemetryLearnText) {
                cancelLearningState()
                rebuildTabContent()
            }
        } else {
            createActionButton(telemetryLearnText, selected = config.telemetrySwitchKeyCode > 0) {
                startWheelKeyLearning(EcarxKx11WheelKeys.ClusterLearnTarget.TELEMETRY)
            }
        }
        telemetryBtnRow.addView(
            telemetryLearnBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)),
        )
        if (config.telemetrySwitchKeyCode > 0) {
            val shareBtn = createActionButton("共用主视图键") {
                cancelLearningState()
                val nextTelemetryTrigger = if (config.centerSwitchKeyCode > 0 &&
                    config.telemetrySwitchTrigger == config.centerSwitchTrigger
                ) {
                    when (config.centerSwitchTrigger) {
                        GeelyClusterSwitchTrigger.SINGLE_CLICK -> GeelyClusterSwitchTrigger.LONG_PRESS
                        GeelyClusterSwitchTrigger.LONG_PRESS -> GeelyClusterSwitchTrigger.DOUBLE_CLICK
                        GeelyClusterSwitchTrigger.DOUBLE_CLICK -> GeelyClusterSwitchTrigger.LONG_PRESS
                    }
                } else {
                    config.telemetrySwitchTrigger
                }
                config = config.copy(
                    telemetrySwitchKeyCode = 0,
                    telemetrySwitchTrigger = nextTelemetryTrigger,
                )
                onConfigUpdated()
                rebuildTabContent()
            }
            telemetryBtnRow.addView(
                shareBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                    .apply { marginStart = dp(8) },
            )
        }
        telemetryHeader.addView(telemetryBtnRow)
        telemetryKeyBox.addView(telemetryHeader, matchWrap(bottomMargin = dp(10)))

        val telemetryTriggerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        telemetryTriggerRow.addView(
            TextView(activity).apply {
                text = "触发手势"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        telemetryTriggerRow.addView(
            createSegmentedRow(
                options = listOf(
                    GeelyClusterSwitchTrigger.SINGLE_CLICK to "单击",
                    GeelyClusterSwitchTrigger.LONG_PRESS to "长按",
                    GeelyClusterSwitchTrigger.DOUBLE_CLICK to "双击",
                ),
                selected = config.telemetrySwitchTrigger,
                heightDp = 38,
            ) { trigger ->
                val adjustedPrimaryTrigger = if (config.sharesTelemetryKeyWithPrimary && config.centerSwitchTrigger == trigger) {
                    val alt = GeelyClusterSwitchTrigger.resolveNonConflicting(
                        config.centerSwitchTrigger,
                        setOf(trigger),
                    )
                    Toast.makeText(activity, "已自动将主视图切换手势错开为「${alt.shortLabel}」", Toast.LENGTH_SHORT).show()
                    alt
                } else {
                    config.centerSwitchTrigger
                }
                val adjustedThemeTrigger = if (config.sharesThemeKeyWithTelemetry) {
                    val used = linkedSetOf(trigger)
                    if (config.sharesThemeKeyWithPrimary) used.add(adjustedPrimaryTrigger)
                    GeelyClusterSwitchTrigger.resolveNonConflicting(config.themeSwitchTrigger, used)
                } else {
                    config.themeSwitchTrigger
                }
                config = config.copy(
                    centerSwitchTrigger = adjustedPrimaryTrigger,
                    telemetrySwitchTrigger = trigger,
                    themeSwitchTrigger = adjustedThemeTrigger,
                )
                onConfigUpdated()
                rebuildTabContent()
            },
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        telemetryKeyBox.addView(telemetryTriggerRow, matchWrap(bottomMargin = dp(10)))

        telemetryKeyBox.addView(
            TextView(activity).apply {
                text = "车况查看包含页面"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            matchWrap(bottomMargin = dp(6)),
        )
        val telemetryRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((cIdx, mode) in GeelyClusterCenterMode.TELEMETRY_ORDER.withIndex()) {
            val enabled = mode in config.telemetryCycleModes
            val label = if (enabled) "✓ ${mode.shortLabel}" else mode.shortLabel
            val chip = createActionButton(label, selected = enabled) {
                val nextSet = LinkedHashSet(config.telemetryCycleModes)
                if (enabled) {
                    if (nextSet.size <= 1) {
                        Toast.makeText(activity, "至少保留 1 个车况页面", Toast.LENGTH_SHORT).show()
                        return@createActionButton
                    }
                    nextSet.remove(mode)
                } else {
                    nextSet.add(mode)
                }
                config = config.copy(telemetryCycleModes = nextSet)
                onConfigUpdated()
                rebuildTabContent()
            }
            telemetryRow.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (cIdx > 0) marginStart = dp(6)
                },
            )
        }
        telemetryKeyBox.addView(telemetryRow, matchWrap(bottomMargin = dp(10)))

        telemetryKeyBox.addView(createSubLabel("车况页自动关闭时长"), matchWrap(bottomMargin = dp(6)))
        telemetryKeyBox.addView(
            createSegmentedRow(
                options = GeelySelfDrawConfig.NAV_AUTO_RETURN_OPTIONS.map { sec ->
                    sec to if (sec == 0) "关闭" else "${sec}秒"
                },
                selected = config.navAutoReturnSeconds,
                heightDp = 38,
            ) { sec ->
                config = config.copy(navAutoReturnSeconds = sec)
                onConfigUpdated()
                GeelyClusterEnhancer.onCenterModeChangedByUser(activity, null, config.centerMode)
                rebuildTabContent()
            },
            matchWrap(bottomMargin = dp(6)),
        )
        telemetryKeyBox.addView(
            createHintText(
                "推荐同键「长按」呼出车况（零延迟；也可选「双击」或单独绑键）。打开车况后，倒计时内再短按可顺切下一项（翻完即关），无操作到达设定时间后自动关闭并返回原主视图（地图 / 专辑 / 时钟）。",
            ),
        )
        card.addView(telemetryKeyBox, matchWrap(bottomMargin = dp(10)))

        // Subcard 3: Cluster theme cycle wheel key (OEM Classic / Pure Map / Mecha)
        val themeKeyBox = createSubCard()
        val learningTheme = learningTarget == EcarxKx11WheelKeys.ClusterLearnTarget.THEME
        val themeHeader = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val themeInfoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        themeInfoCol.addView(
            TextView(activity).apply {
                text = "仪表主题切换键（原车 / 纯地图 / 机甲）"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        val themeBindLabel = when {
            config.themeSwitchKeyCode <= 0 -> "未绑定（由用户自由学习任意车机实体键）"
            config.sharesThemeKeyWithPrimary -> "共用主视图键：${formatGeelyWheelKey(config.themeSwitchKeyCode)}"
            else -> "独立按键：${formatGeelyWheelKey(config.themeSwitchKeyCode)}"
        }
        themeInfoCol.addView(
            TextView(activity).apply {
                text = themeBindLabel
                setTextColor(if (config.themeSwitchKeyCode > 0) colors.accent else colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        themeHeader.addView(
            themeInfoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            },
        )

        val themeBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val themeLearnText = when {
            learningTheme -> "请按下车机按键…"
            config.themeSwitchKeyCode > 0 -> "重新学习"
            else -> "学习按键"
        }
        val themeLearnBtn = if (learningTheme) {
            createPrimaryButton(themeLearnText) {
                cancelLearningState()
                rebuildTabContent()
            }
        } else {
            createActionButton(themeLearnText, selected = config.themeSwitchKeyCode > 0) {
                startWheelKeyLearning(EcarxKx11WheelKeys.ClusterLearnTarget.THEME)
            }
        }
        themeBtnRow.addView(
            themeLearnBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)),
        )
        if (config.centerSwitchKeyCode > 0 && !config.sharesThemeKeyWithPrimary) {
            val shareThemeBtn = createActionButton("共用主视图键") {
                cancelLearningState()
                val used = linkedSetOf(config.centerSwitchTrigger)
                if (config.sharesTelemetryKeyWithPrimary) used.add(config.telemetrySwitchTrigger)
                val nextTrig = GeelyClusterSwitchTrigger.resolveNonConflicting(config.themeSwitchTrigger, used)
                config = config.copy(
                    themeSwitchKeyCode = config.centerSwitchKeyCode,
                    themeSwitchTrigger = nextTrig,
                )
                onConfigUpdated()
                Toast.makeText(
                    activity,
                    "已与主视图共用 ${formatGeelyWheelKey(config.themeSwitchKeyCode)}（${nextTrig.shortLabel}切主题）",
                    Toast.LENGTH_SHORT,
                ).show()
                rebuildTabContent()
            }
            themeBtnRow.addView(
                shareThemeBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                    .apply { marginStart = dp(8) },
            )
        }
        if (config.themeSwitchKeyCode > 0) {
            val clearThemeBtn = createActionButton("清除") {
                cancelLearningState()
                config = config.copy(themeSwitchKeyCode = 0)
                onConfigUpdated()
                rebuildTabContent()
            }
            themeBtnRow.addView(
                clearThemeBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                    .apply { marginStart = dp(8) },
            )
        }
        themeHeader.addView(themeBtnRow)
        themeKeyBox.addView(themeHeader, matchWrap(bottomMargin = dp(10)))

        val themeTriggerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        themeTriggerRow.addView(
            TextView(activity).apply {
                text = "触发手势"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        themeTriggerRow.addView(
            createSegmentedRow(
                options = listOf(
                    GeelyClusterSwitchTrigger.SINGLE_CLICK to "单击",
                    GeelyClusterSwitchTrigger.LONG_PRESS to "长按",
                    GeelyClusterSwitchTrigger.DOUBLE_CLICK to "双击",
                ),
                selected = config.themeSwitchTrigger,
                heightDp = 38,
            ) { trigger ->
                var nextPrimaryTrig = config.centerSwitchTrigger
                var nextTelTrig = config.telemetrySwitchTrigger
                if (config.sharesThemeKeyWithPrimary && nextPrimaryTrig == trigger) {
                    val used = linkedSetOf(trigger)
                    if (config.sharesTelemetryKeyWithPrimary) used.add(nextTelTrig)
                    nextPrimaryTrig = GeelyClusterSwitchTrigger.resolveNonConflicting(nextPrimaryTrig, used)
                }
                if (config.sharesThemeKeyWithTelemetry && nextTelTrig == trigger) {
                    val used = linkedSetOf(trigger)
                    if (config.sharesThemeKeyWithPrimary) used.add(nextPrimaryTrig)
                    nextTelTrig = GeelyClusterSwitchTrigger.resolveNonConflicting(nextTelTrig, used)
                }
                config = config.copy(
                    centerSwitchTrigger = nextPrimaryTrig,
                    telemetrySwitchTrigger = nextTelTrig,
                    themeSwitchTrigger = trigger,
                )
                onConfigUpdated()
                rebuildTabContent()
            },
            LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        themeKeyBox.addView(themeTriggerRow, matchWrap(bottomMargin = dp(10)))

        themeKeyBox.addView(
            TextView(activity).apply {
                text = "按键循环包含的主题（默认：原车经典 + 纯地图全屏 + 机甲光翼）"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            },
            matchWrap(bottomMargin = dp(6)),
        )
        val themeChipCols = if (isMediumScreen()) 4 else 2
        val themeChipRows = GeelyClusterThemeStyle.entries.chunked(themeChipCols)
        for ((rIdx, rowStyles) in themeChipRows.withIndex()) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (cIdx in 0 until themeChipCols) {
                val style = rowStyles.getOrNull(cIdx)
                val lp = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (cIdx > 0) marginStart = dp(6)
                }
                if (style != null) {
                    val enabled = style in config.themeCycleStyles
                    val label = if (enabled) "✓ ${style.shortLabel}" else style.shortLabel
                    val chip = createActionButton(label, selected = enabled) {
                        val nextSet = LinkedHashSet(config.themeCycleStyles)
                        if (enabled) {
                            if (nextSet.size <= 2) {
                                Toast.makeText(activity, "至少保留 2 套主题以便循环切换", Toast.LENGTH_SHORT).show()
                                return@createActionButton
                            }
                            nextSet.remove(style)
                        } else {
                            nextSet.add(style)
                        }
                        config = config.copy(themeCycleStyles = nextSet)
                        onConfigUpdated()
                        rebuildTabContent()
                    }
                    row.addView(chip, lp)
                } else {
                    row.addView(View(activity), lp)
                }
            }
            themeKeyBox.addView(
                row,
                matchWrap(bottomMargin = if (rIdx == themeChipRows.lastIndex) dp(8) else dp(6)),
            )
        }
        themeKeyBox.addView(
            createHintText(
                "点击「学习按键」即可自动探测并绑定车机方向盘/中控可用实体键（推荐使用自定义键、MODE 键或静音键「长按」避免触发原车短按功能；也可共用微信键）。无地图投屏时，纯地图全屏自动回落原车且按键循环自动跳过。",
            ),
        )
        card.addView(themeKeyBox, matchWrap(bottomMargin = dp(10)))

        // Subcard 4: Steering-wheel Voice key -> Siri shortcut & override
        val voiceBox = createSubCard()
        val siriEnabled = WheelZoomSettings.siriKey(activity)
        val currentSiriKey = WheelZoomSettings.key(activity, WheelZoomSettings.Role.SIRI)
        val siriBoundToVoiceKey = siriEnabled &&
            EcarxKx11WheelKeys.isGeelyInputKey(currentSiriKey) &&
            currentSiriKey?.code == EcarxKx11WheelKeys.KEY_VOICE_ASSIST

        val voiceHeader = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val voiceInfoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        voiceInfoCol.addView(
            TextView(activity).apply {
                text = "方向盘语音键（Siri）"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        val siriKeySummary = when {
            !siriEnabled -> "Siri 快捷键未启用"
            currentSiriKey != null && EcarxKx11WheelKeys.isGeelyInputKey(currentSiriKey) ->
                "当前绑定：${formatGeelyWheelKey(currentSiriKey.code)}"
            currentSiriKey != null -> "当前绑定：按键 ${currentSiriKey.code}"
            else -> "尚未学习 Siri 按键"
        }
        voiceInfoCol.addView(
            TextView(activity).apply {
                text = siriKeySummary
                setTextColor(if (siriBoundToVoiceKey) colors.accent else colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        voiceHeader.addView(
            voiceInfoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            },
        )

        val bindVoiceBtn = createActionButton(
            text = if (siriBoundToVoiceKey) "已绑定原车语音键" else "一键绑定原车语音键",
            selected = siriBoundToVoiceKey,
        ) {
            WheelZoomSettings.setSiriKey(activity, true)
            WheelZoomSettings.setSiriDoublePress(activity, false)
            WheelZoomSettings.assign(
                activity,
                WheelZoomSettings.Role.SIRI,
                EcarxKx11WheelKeys.toWheelKey(EcarxKx11WheelKeys.KEY_VOICE_ASSIST),
            )
            EcarxKx11WheelKeys.settingsChanged()
            Toast.makeText(activity, "已将原车语音键 (200231) 设为 Siri 快捷键", Toast.LENGTH_SHORT).show()
            rebuildTabContent()
        }
        voiceHeader.addView(
            bindVoiceBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)),
        )
        voiceBox.addView(voiceHeader, matchWrap(bottomMargin = dp(8)))

        val interceptSubtitle = when {
            !config.interceptVoiceKeyForSiri ->
                "关闭时短按语音键保留给原车「你好吉利」，长按语音键呼出 Siri。"
            !siriBoundToVoiceKey ->
                "请先点击上方「一键绑定原车语音键」，连接 CarPlay 时再尝试拦截短按。"
            EcarxKx11WheelKeys.lastVoiceInterceptStatus == EcarxKx11WheelKeys.VoiceInterceptStatus.GRANTED ->
                "CarPlay 已接管语音键短按（直呼 Siri），断开 CarPlay 后自动还原原车语音。"
            EcarxKx11WheelKeys.lastVoiceInterceptStatus == EcarxKx11WheelKeys.VoiceInterceptStatus.REJECTED ->
                "车机系统拒绝拦截语音键短按，已自动退回长按呼出 Siri（短按保留原车语音）。"
            EcarxKx11WheelKeys.lastVoiceInterceptStatus == EcarxKx11WheelKeys.VoiceInterceptStatus.ERROR ->
                "拦截语音键接口异常，已自动退回长按呼出 Siri。"
            EcarxKx11WheelKeys.isCarPlaySessionActive() ->
                "已开启，正在尝试接管语音键短按（未接管前可长按呼出 Siri）。"
            else ->
                "已开启：连接 CarPlay 时尝试调用车机拦截接口覆盖原车语音助手（断开自动还原）。"
        }
        addFlatSwitchRow(
            container = voiceBox,
            title = "CarPlay 期间覆盖原车语音键（短按呼 Siri）",
            subtitle = interceptSubtitle,
            checked = config.interceptVoiceKeyForSiri,
        ) { checked ->
            config = config.copy(interceptVoiceKeyForSiri = checked)
            onConfigUpdated()
            EcarxKx11WheelKeys.settingsChanged()
            rebuildTabContent()
        }
        card.addView(voiceBox)
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun startWheelKeyLearning(target: EcarxKx11WheelKeys.ClusterLearnTarget) {
        learningTarget = target
        rebuildTabContent()
        val started = EcarxKx11WheelKeys.learnClusterSwitchKey(
            context = activity,
            target = target,
            cancelled = {
                activity.runOnUiThread {
                    learningTarget = null
                    rebuildTabContent()
                }
            },
            done = { learnedCode ->
                activity.runOnUiThread {
                    learningTarget = null
                    config = GeelyClusterPrefs.load(activity)
                    onConfigUpdated()
                    val siriSameKey = WheelZoomSettings.siriKey(activity) &&
                        WheelZoomSettings.key(activity, WheelZoomSettings.Role.SIRI)
                            ?.let { EcarxKx11WheelKeys.isGeelyInputKey(it) && it.code == learnedCode } == true
                    val keyDisplay = formatGeelyWheelKey(learnedCode)
                    val tip = when {
                        siriSameKey ->
                            "已绑定 $keyDisplay（与 Siri 共键，建议将 Siri 改绑原车语音键以避免冲突）"
                        target == EcarxKx11WheelKeys.ClusterLearnTarget.PRIMARY ->
                            "已绑定主视图切换键：$keyDisplay（${config.centerSwitchTrigger.displayName}切换）"
                        target == EcarxKx11WheelKeys.ClusterLearnTarget.THEME ->
                            "已绑定仪表主题切换键：$keyDisplay（${config.themeSwitchTrigger.displayName}切换主题）"
                        config.sharesTelemetryKeyWithPrimary ->
                            "车况查看已与主视图共用 $keyDisplay（${config.telemetrySwitchTrigger.displayName}触发）"
                        else ->
                            "已绑定独立车况按键：$keyDisplay（${config.telemetrySwitchTrigger.displayName}触发）"
                    }
                    Toast.makeText(activity, tip, Toast.LENGTH_LONG).show()
                    rebuildTabContent()
                }
            },
        )
        if (!started) {
            learningTarget = null
            rebuildTabContent()
            Toast.makeText(activity, "当前车机不支持吉利方向盘输入服务", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------------------
    // Tab 1 (Studio Tab 2): 导航与 HUD (Unified Switches, Calibration)
    // -------------------------------------------------------------------------

    private fun addNavigationGuidanceCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("导航引导与抬头显示 (W-HUD)"), matchWrap(bottomMargin = dp(6)))

        addFlatSwitchRow(
            container = card,
            title = "W-HUD 抬头显示引导",
            subtitle = "行驶时将转向箭头、距离与路名投射到原车前挡风 HUD。",
            checked = config.hudEnabled,
        ) { checked ->
            config = config.copy(hudEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "全屏地图顶部导航卡",
            subtitle = "全屏仪表地图顶部显示转向引导、道路名称与剩余行程（关闭即可隐藏顶部条）。",
            checked = config.fullscreenCardEnabled,
        ) { checked ->
            config = config.copy(fullscreenCardEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "经典仪表导航卡片",
            subtitle = "原车经典仪表模式下，在仪表卡槽内显示精简转向、距离与路名。",
            checked = config.cardEnabled,
        ) { checked ->
            config = config.copy(cardEnabled = checked)
            onConfigUpdated()
        }
        card.addView(createHairline())
        addFlatSwitchRow(
            container = card,
            title = "实时车道提示",
            subtitle = "同步用于 W-HUD 与全屏导航卡片，没有车道数据时自动隐藏。",
            checked = config.laneEnabled,
        ) { checked ->
            config = config.copy(laneEnabled = checked)
            onConfigUpdated()
        }

        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun addCalibrationCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(createCardTitle("HUD 与卡片位置校准"))
        titleCol.addView(
            TextView(activity).apply {
                text = "若实车 HUD 或导航卡片存在偏移，可切换目标区域按像素微调"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        head.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(
            createActionButton("恢复默认坐标") { resetAreasToDefault() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                .apply { marginStart = dp(8) },
        )
        card.addView(head, matchWrap(bottomMargin = dp(8)))

        addFlatSwitchRow(
            container = card,
            title = "在实车仪表与 HUD 显示模拟导航",
            subtitle = "未开始导航时也在实车屏幕输出演示卡片，方便边看边校准坐标。",
            checked = config.previewEnabled,
        ) { checked ->
            config = config.copy(previewEnabled = checked)
            onConfigUpdated()
        }

        // Region selector pills
        card.addView(
            createSegmentedRow(
                options = GeelyCalibrationArea.entries.map { it to it.shortTitle },
                selected = activeCalibrationArea,
            ) { chosen ->
                activeCalibrationArea = chosen
                previewView?.highlightedArea = chosen
                rebuildTabContent()
            },
            matchWrap(topMargin = dp(10), bottomMargin = dp(10)),
        )

        // Selected area editor subcard
        val area = currentArea(activeCalibrationArea)
        val box = createSubCard()
        val boxHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        boxHead.addView(
            View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(activeCalibrationArea.outlineColor)
                }
            },
            LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) },
        )
        boxHead.addView(
            TextView(activity).apply {
                text = activeCalibrationArea.fullTitle
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        boxHead.addView(
            TextView(activity).apply {
                text = activeCalibrationArea.defaultSummary
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            },
        )
        box.addView(boxHead, matchWrap(bottomMargin = dp(10)))

        // 4-tile live coordinate readout
        val metricsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val metrics = listOf(
            "X 坐标" to area.x,
            "Y 坐标" to area.y,
            "宽度" to area.width,
            "高度" to area.height,
        )
        for ((idx, pair) in metrics.withIndex()) {
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = roundedBox(colors.surface, colors.outline, cornerDp = 10)
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            tile.addView(
                TextView(activity).apply {
                    text = pair.first
                    setTextColor(colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                },
            )
            tile.addView(
                TextView(activity).apply {
                    text = pair.second.toString()
                    setTextColor(colors.primaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(2), 0, 0)
                },
            )
            metricsRow.addView(
                tile,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                },
            )
        }
        box.addView(metricsRow, matchWrap(bottomMargin = dp(10)))

        // Nudge row
        val nudgeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val nudges = listOf(
            "← X-4" to { updateArea(activeCalibrationArea, area.copy(x = area.x - 4)) },
            "X+4 →" to { updateArea(activeCalibrationArea, area.copy(x = area.x + 4)) },
            "↑ Y-4" to { updateArea(activeCalibrationArea, area.copy(y = area.y - 4)) },
            "Y+4 ↓" to { updateArea(activeCalibrationArea, area.copy(y = area.y + 4)) },
        )
        for ((idx, pair) in nudges.withIndex()) {
            val btn = createActionButton(pair.first) { pair.second() }
            nudgeRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                },
            )
        }
        box.addView(nudgeRow, matchWrap(bottomMargin = dp(8)))

        val exactBtn = createActionButton("输入精确坐标数值", selected = true) {
            showNumericAreaDialog(activeCalibrationArea.fullTitle, area) { updated ->
                updateArea(activeCalibrationArea, updated)
            }
        }
        box.addView(exactBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)))

        card.addView(box)

        if (!studioMode) {
            val studioJump = createActionButton("在全屏分栏工作台中边看画布边微调 ↗", selected = true) {
                openFullscreenStudio()
            }
            card.addView(
                studioJump,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.CONTROL_HEIGHT_DP))
                    .apply { topMargin = dp(12) },
            )
        }

        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun currentArea(which: GeelyCalibrationArea): GeelyOverlayArea = when (which) {
        GeelyCalibrationArea.HUD -> config.hudArea
        GeelyCalibrationArea.FULLSCREEN_CARD -> config.fullscreenCardArea
        GeelyCalibrationArea.CLASSIC_CARD -> config.cardArea
    }

    private fun updateArea(which: GeelyCalibrationArea, updated: GeelyOverlayArea) {
        val clamped = updated.clamp()
        config = when (which) {
            GeelyCalibrationArea.HUD -> config.copy(hudArea = clamped)
            GeelyCalibrationArea.FULLSCREEN_CARD -> config.copy(fullscreenCardArea = clamped)
            GeelyCalibrationArea.CLASSIC_CARD -> config.copy(cardArea = clamped)
        }
        onConfigUpdated()
        rebuildTabContent()
    }

    // -------------------------------------------------------------------------
    // Tab 2 (Inline only): 预览与调试
    // -------------------------------------------------------------------------

    private fun addPreviewCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(createCardTitle("复合仪表实时预览 (1920×1080)"))
        titleCol.addView(
            TextView(activity).apply {
                text = "同步展示主仪表画面与下方原车 HUD / 经典卡裁剪源位置"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        head.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(
            createActionButton("全屏工作台 ↗", selected = true) { openFullscreenStudio() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
                .apply { marginStart = dp(8) },
        )
        card.addView(head, matchWrap(bottomMargin = dp(12)))

        val previewFrame = FrameLayout(activity).apply {
            background = roundedBox(colors.previewFrame, colors.outline, cornerDp = 14)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val pv = GeelyCompositePreviewView(activity).also {
            it.config = config
            it.previewRouteActive = previewRouteActive
            previewView = it
        }
        previewFrame.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(248)))
        card.addView(previewFrame, matchWrap(bottomMargin = dp(12)))

        // Quick 8-theme switcher chips (2 rows x 4 cols)
        val styleRows = GeelyClusterThemeStyle.entries.chunked(4)
        for ((rowIdx, rowStyles) in styleRows.withIndex()) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for ((colIdx, style) in rowStyles.withIndex()) {
                val active = config.dialThemeStyle == style
                val btn = createActionButton(style.shortLabel, selected = active) {
                    config = config.copy(dialThemeStyle = style)
                    onConfigUpdated()
                    rebuildTabContent()
                }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
                        if (colIdx > 0) marginStart = dp(6)
                    },
                )
            }
            card.addView(
                row,
                matchWrap(bottomMargin = if (rowIdx == styleRows.lastIndex) dp(10) else dp(6)),
            )
        }

        // Quick center mode strip
        card.addView(
            createCenterModeGrid(
                selected = config.centerMode,
                compact = false,
                heightDp = GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP,
            ) { mode ->
                val prevMode = config.centerMode
                config = config.copy(centerMode = mode)
                onConfigUpdated()
                GeelyClusterEnhancer.onCenterModeChangedByUser(activity, prevMode, mode)
                rebuildTabContent()
            },
            matchWrap(bottomMargin = dp(10)),
        )

        card.addView(buildPreviewActionRow())
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun buildPreviewActionRow(): View {
        val effectiveNight = GeelyClusterEnhancer.resolveEffectiveNightMode(config, activity)
        val nightLabel = when (config.appearanceMode) {
            GeelyClusterAppearanceMode.AUTO -> if (effectiveNight) "自动(深)" else "自动(浅)"
            GeelyClusterAppearanceMode.DARK -> "深色"
            GeelyClusterAppearanceMode.LIGHT -> "浅色"
        }
        val actions = listOf(
            "昼夜：$nightLabel" to {
                val modes = GeelyClusterAppearanceMode.entries
                val next = modes[(config.appearanceMode.ordinal + 1) % modes.size]
                config = config.copy(appearanceMode = next, mapThemeDark = next != GeelyClusterAppearanceMode.LIGHT)
                onConfigUpdated()
                rebuildTabContent()
            },
            (if (previewRouteActive) "场景：导航中" else "场景：待机态") to {
                previewRouteActive = !previewRouteActive
                previewView?.previewRouteActive = previewRouteActive
                if (studioMode) rebuildStudioPreviewControls() else rebuildTabContent()
            },
            "切至全屏仪表" to {
                val ok = GeelyClusterEnhancer.requestFullScreenMap(activity, "settings-button")
                Toast.makeText(
                    activity,
                    if (ok) "已请求车机切换至全屏仪表模式" else "当前车机仪表切换通道不可用",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            "恢复经典仪表" to {
                val ok = GeelyClusterEnhancer.requestClassicCluster(activity)
                Toast.makeText(
                    activity,
                    if (ok) "已请求车机恢复原车经典仪表模式" else "当前车机仪表切换通道不可用",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )

        val twoRows = activity.resources.configuration.screenWidthDp < 600 || studioMode
        return if (twoRows) {
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                for (rowIdx in 0..1) {
                    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    for (col in 0..1) {
                        val (label, click) = actions[rowIdx * 2 + col]
                        row.addView(
                            createActionButton(label, onClick = click),
                            LinearLayout.LayoutParams(0, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
                                if (col > 0) marginStart = dp(6)
                            },
                        )
                    }
                    addView(row, matchWrap(topMargin = if (rowIdx > 0) dp(6) else 0))
                }
            }
        } else {
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((idx, pair) in actions.withIndex()) {
                    addView(
                        createActionButton(pair.first, onClick = pair.second),
                        LinearLayout.LayoutParams(0, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
                            if (idx > 0) marginStart = dp(6)
                        },
                    )
                }
            }
        }
    }

    private fun addRealCarDebugCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("实车联调"), matchWrap(bottomMargin = dp(6)))
        addFlatSwitchRow(
            container = card,
            title = "在实车仪表与 HUD 上输出模拟数据",
            subtitle = "未开始导航时也在实车屏幕输出示例引导卡片，便于现场核对位置与显示效果。",
            checked = config.previewEnabled,
        ) { checked ->
            config = config.copy(previewEnabled = checked)
            onConfigUpdated()
        }
        card.addView(
            createHintText("提示：上方「切至全屏仪表 / 恢复经典仪表」按钮可用于手动验证车机仪表模式切换通道是否响应。"),
            matchWrap(topMargin = dp(4)),
        )
        card.addView(
            createActionButton("探测原车车况信号（胎压 / 行程 / 里程）") {
                GeelyVehicleSignalProbeUi.showProbeDialog(activity)
            },
            matchWrap(topMargin = dp(10)).apply {
                height = dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP)
            },
        )
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    // -------------------------------------------------------------------------
    // Tab 3 (Studio Tab 3): 权限与副屏
    // -------------------------------------------------------------------------

    private fun addPermissionsCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("运行权限检查"), matchWrap(bottomMargin = dp(4)))
        card.addView(
            createHintText("授予以下系统权限，确保常驻仪表主题、独立 W-HUD 与车机专辑封面稳定工作。"),
            matchWrap(bottomMargin = dp(12)),
        )

        val hasOverlay = Settings.canDrawOverlays(activity)
        card.addView(
            buildPermissionSubCard(
                title = "悬浮窗权限",
                granted = hasOverlay,
                description = "用于挂载独立常驻仪表主题与顶层 W-HUD / 导航卡片，切换中控应用或断开 CarPlay 时不中断显示。",
                actionLabel = if (hasOverlay) "管理权限" else "去授权",
                onClick = ::openOverlayPermissionSettings,
            ),
            matchWrap(bottomMargin = dp(10)),
        )

        val hasNotif = GeelyMediaNotificationListenerService.isEnabled(activity)
        card.addView(
            buildPermissionSubCard(
                title = "通知使用权（车机音乐封面与歌词）",
                granted = hasNotif,
                description = "未连接 CarPlay 时，自动读取网易云音乐、QQ 音乐及蓝牙音乐的专辑封面、歌名与歌词并同步至仪表。",
                actionLabel = if (hasNotif) "管理权限" else "去授权",
                onClick = ::openNotificationListenerSettings,
            ),
        )

        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun buildPermissionSubCard(
        title: String,
        granted: Boolean,
        description: String,
        actionLabel: String,
        onClick: () -> Unit,
    ): View {
        val box = createSubCard()
        val row = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            TextView(activity).apply {
                text = title
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        head.addView(
            createTagBadge(
                text = if (granted) "已授权" else "未授权",
                textColor = if (granted) colors.success else colors.warning,
                bgColor = if (granted) colors.successBg else colors.warningBg,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) },
        )
        infoCol.addView(head)
        infoCol.addView(
            TextView(activity).apply {
                text = description
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        row.addView(
            infoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            },
        )
        val btn = if (granted) {
            createActionButton(actionLabel, onClick = onClick)
        } else {
            createPrimaryButton(actionLabel, onClick = onClick)
        }
        row.addView(
            btn,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            },
        )
        box.addView(row)
        return box
    }

    private fun addDisplayOutputCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        card.addView(createCardTitle("仪表副屏输出"), matchWrap(bottomMargin = dp(10)))

        val display = GeelyVehicleDetector.findClusterDisplay(activity)
        val box = createSubCard()
        val row = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val statusHead = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dotColor = if (display != null) colors.success else colors.warning
        statusHead.addView(
            View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            },
            LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) },
        )
        statusHead.addView(
            TextView(activity).apply {
                text = if (display != null) "已识别仪表副屏" else "未检测到仪表副屏"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        infoCol.addView(statusHead)
        val detail = if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            val compositeHint = if (GeelyVehicleDetector.isComposite1080Display(display)) "（支持 W-HUD 裁剪区）" else ""
            "当前优先屏幕：Display ID ${config.preferredDisplayId} · 实际输出 ID ${display.displayId} (${size.x}×${size.y})$compositeHint"
        } else {
            "当前优先屏幕：Display ID ${config.preferredDisplayId}，连接车机副屏后将自动匹配 1920×1080 / 1920×720 仪表屏。"
        }
        infoCol.addView(
            TextView(activity).apply {
                text = detail
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(4), 0, 0)
            },
        )
        row.addView(
            infoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            },
        )
        val pickBtn = createActionButton("切换输出屏幕") { showDisplayPickerDialog() }
        row.addView(
            pickBtn,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            },
        )
        box.addView(row)
        card.addView(box)
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun addVehicleSignalProbeCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        val row = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        infoCol.addView(createCardTitle("原车车况信号探测（只读）"))
        infoCol.addView(
            TextView(activity).apply {
                text = "只读枚举实车 ECARX ISensor / ICarFunction / TripData 中的胎压、胎温、行程、总里程、续航与油耗通道，支持一键上传 R2 或本地保存。"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        row.addView(
            infoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            },
        )
        row.addView(
            createPrimaryButton("开始探测") {
                GeelyVehicleSignalProbeUi.showProbeDialog(activity)
            },
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            },
        )
        card.addView(row)
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    private fun addResetCard(parent: LinearLayout) {
        val card = createSurfaceCard()
        val row = LinearLayout(activity).apply {
            orientation = if (isMediumScreen()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val infoCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        infoCol.addView(createCardTitle("布局坐标重置"))
        infoCol.addView(
            TextView(activity).apply {
                text = "仅将 W-HUD、全屏导航卡与经典仪表卡恢复为默认像素坐标，不影响主题与其他开关。"
                setTextColor(colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(3), 0, 0)
            },
        )
        row.addView(
            infoCol,
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(10) }
            },
        )
        row.addView(
            createActionButton("恢复 HUD 默认坐标") { resetAreasToDefault() },
            if (isMediumScreen()) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP))
            },
        )
        card.addView(row)
        parent.addView(card, matchWrap(bottomMargin = dp(GeelyUiDimens.BLOCK_GAP_DP)))
    }

    // -------------------------------------------------------------------------
    // System Settings & Modal Dialogs
    // -------------------------------------------------------------------------

    private fun openOverlayPermissionSettings() {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        }.onFailure {
            Toast.makeText(activity, "车机未提供此权限页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure {
            Toast.makeText(
                activity,
                "车机未提供此权限页面，可通过 ADB 授予通知使用权",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showNumericAreaDialog(
        title: String,
        current: GeelyOverlayArea,
        onSaved: (GeelyOverlayArea) -> Unit,
    ) {
        val dialogContext = activity.appDialogContext()
        val layout = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val fields = listOf(
            "左边距 X (0..1920)" to current.x,
            "上边距 Y (0..1080)" to current.y,
            "宽度 Width" to current.width,
            "高度 Height" to current.height,
        )
        val inputs = fields.map { (hint, value) ->
            EditText(dialogContext).apply {
                this.hint = hint
                setText(value.toString())
                setTextColor(colors.primaryText)
                setHintTextColor(colors.secondaryText)
                inputType = InputType.TYPE_CLASS_NUMBER
            }.also { et ->
                layout.addView(
                    TextView(dialogContext).apply {
                        text = hint
                        setTextColor(colors.secondaryText)
                    },
                )
                layout.addView(et)
            }
        }
        activity.appDialogBuilder()
            .setTitle("精确输入坐标 · $title")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val x = inputs[0].text.toString().trim().toIntOrNull()
                val y = inputs[1].text.toString().trim().toIntOrNull()
                val w = inputs[2].text.toString().trim().toIntOrNull()
                val h = inputs[3].text.toString().trim().toIntOrNull()
                if (x != null && y != null && w != null && h != null) {
                    onSaved(GeelyOverlayArea(x, y, w, h).clamp())
                } else {
                    Toast.makeText(activity, "请输入有效整数", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showDisplayPickerDialog() {
        val dm = activity.getSystemService(DisplayManager::class.java)
        val displays = dm?.displays?.filter { it.displayId != Display.DEFAULT_DISPLAY }.orEmpty()
        if (displays.isEmpty()) {
            Toast.makeText(activity, "当前未检测到副屏，连接车机后将自动匹配 ID=2 (1920×1080/720)", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = displays.map { d ->
            val size = ClusterMapPresentation.sizeOf(d)
            val tag = if (GeelyVehicleDetector.isComposite1080Display(d)) " · 复合仪表屏" else ""
            "屏幕 ID ${d.displayId} (${size.x}×${size.y})$tag"
        }.toTypedArray()
        activity.appDialogBuilder()
            .setTitle("选择仪表输出屏幕")
            .setItems(labels) { _, which ->
                val chosen = displays[which]
                config = config.copy(preferredDisplayId = chosen.displayId)
                onConfigUpdated()
                rebuildTabContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // -------------------------------------------------------------------------
    // Reusable UI Primitives (Consistent with DiPlay Design System)
    // -------------------------------------------------------------------------

    private fun createSurfaceCard(padV: Int = GeelyUiDimens.CARD_PAD_V_DP): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.surface, colors.outline, cornerDp = GeelyUiDimens.CARD_CORNER_DP)
            setPadding(dp(GeelyUiDimens.CARD_PAD_H_DP), dp(padV), dp(GeelyUiDimens.CARD_PAD_H_DP), dp(padV))
        }

    private fun createSubCard(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBox(colors.itemSurface, colors.outline, cornerDp = GeelyUiDimens.SUBCARD_CORNER_DP)
            setPadding(
                dp(GeelyUiDimens.SUBCARD_PAD_H_DP),
                dp(GeelyUiDimens.SUBCARD_PAD_V_DP),
                dp(GeelyUiDimens.SUBCARD_PAD_H_DP),
                dp(GeelyUiDimens.SUBCARD_PAD_V_DP),
            )
        }

    private fun createCardTitle(title: String): TextView =
        TextView(activity).apply {
            text = title
            setTextColor(colors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun createSubLabel(text: String): TextView =
        TextView(activity).apply {
            this.text = text
            setTextColor(colors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun createHintText(text: String): TextView =
        TextView(activity).apply {
            this.text = text
            setTextColor(colors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun createHairline(): View =
        View(activity).apply {
            setBackgroundColor(colors.divider)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
        }

    private fun createTagBadge(text: String, textColor: Int, bgColor: Int): TextView =
        TextView(activity).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(GeelyUiDimens.PILL_CORNER_DP).toFloat()
            }
            maxLines = 1
        }

    private fun createStatusPill(label: String, ok: Boolean, onClick: () -> Unit): LinearLayout {
        val fg = if (ok) colors.success else colors.warning
        val bg = if (ok) colors.successBg else colors.warningBg
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = RippleDrawable(
                ColorStateList.valueOf(colors.ripple),
                GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = dp(GeelyUiDimens.PILL_CORNER_DP).toFloat()
                },
                null,
            )
            addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(fg)
                    }
                },
                LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) },
            )
            addView(
                TextView(activity).apply {
                    text = label
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            setOnClickListener { onClick() }
        }
    }

    private fun createCompactPillAction(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minHeight = dp(34)
            minimumHeight = dp(34)
            minWidth = 0
            minimumWidth = 0
            background = RippleDrawable(
                ColorStateList.valueOf(colors.ripple),
                GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dp(10).toFloat()
                },
                null,
            )
            setOnClickListener { onClick() }
        }

    fun createTabButton(label: String, iconRes: Int? = null, onClick: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            if (iconRes != null) {
                addView(
                    ImageView(activity).apply {
                        setImageResource(iconRes)
                        tag = TAG_TAB_ICON
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) },
                )
            }
            addView(
                TextView(activity).apply {
                    text = label
                    tag = TAG_TAB_LABEL
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            setOnClickListener { onClick() }
        }
    }

    private fun styleTabButton(tabView: LinearLayout, selected: Boolean) {
        val textColor = if (selected) colors.accent else colors.secondaryText
        tabView.findViewWithTag<ImageView>(TAG_TAB_ICON)?.imageTintList = ColorStateList.valueOf(textColor)
        tabView.findViewWithTag<TextView>(TAG_TAB_LABEL)?.apply {
            setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        tabView.background = RippleDrawable(
            ColorStateList.valueOf(colors.ripple),
            if (selected) {
                roundedBox(colors.selectedSurface, colors.selectedOutline, cornerDp = GeelyUiDimens.CONTROL_CORNER_DP)
            } else {
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(GeelyUiDimens.CONTROL_CORNER_DP).toFloat()
                }
            },
            null,
        )
    }

    private fun createCenterModeGrid(
        selected: GeelyClusterCenterMode,
        compact: Boolean,
        heightDp: Int,
        onSelected: (GeelyClusterCenterMode) -> Unit,
    ): LinearLayout {
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row1 = if (compact) {
            listOf(
                GeelyClusterCenterMode.AUTO to "自动",
                GeelyClusterCenterMode.MAP to "地图",
                GeelyClusterCenterMode.ALBUM to "专辑",
                GeelyClusterCenterMode.CLOCK to "时钟",
            )
        } else {
            listOf(
                GeelyClusterCenterMode.AUTO to "智能自动",
                GeelyClusterCenterMode.MAP to "导航地图",
                GeelyClusterCenterMode.ALBUM to "专辑封面",
                GeelyClusterCenterMode.CLOCK to "数字时钟",
            )
        }
        val row2 = listOf(
            GeelyClusterCenterMode.TRIP to "本次行程",
            GeelyClusterCenterMode.TIRE to "胎压胎温",
            GeelyClusterCenterMode.VEHICLE_INFO to "综合车况",
        )
        wrapper.addView(
            createSegmentedRow(options = row1, selected = selected, heightDp = heightDp, onSelected = onSelected),
            matchWrap(bottomMargin = dp(6)),
        )
        wrapper.addView(
            createSegmentedRow(options = row2, selected = selected, heightDp = heightDp, onSelected = onSelected),
            matchWrap(),
        )
        return wrapper
    }

    private fun <T> createSegmentedRow(
        options: List<Pair<T, String>>,
        selected: T,
        heightDp: Int = GeelyUiDimens.SEGMENT_HEIGHT_DP,
        onSelected: (T) -> Unit,
    ): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBox(colors.itemSurface, colors.outline, cornerDp = GeelyUiDimens.CONTROL_CORNER_DP)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((idx, pair) in options.withIndex()) {
            val (value, label) = pair
            val active = value == selected
            val btn = Button(activity).apply {
                text = label
                isAllCaps = false
                stateListAnimator = null
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (active) colors.accent else colors.secondaryText)
                typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(8), 0, dp(8), 0)
                minWidth = 0
                minimumWidth = 0
                background = RippleDrawable(
                    ColorStateList.valueOf(colors.ripple),
                    if (active) {
                        roundedBox(colors.selectedSurface, colors.selectedOutline, cornerDp = 9)
                    } else {
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(9).toFloat()
                        }
                    },
                    null,
                )
                setOnClickListener {
                    if (value != selected) onSelected(value)
                }
            }
            container.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
                    if (idx > 0) marginStart = dp(4)
                },
            )
        }
        return container
    }

    private fun addFlatSwitchRow(
        container: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        val rowResult = SettingsWidgets.createSwitchRow(
            context = activity,
            label = title,
            description = subtitle,
            checked = checked,
            theme = colors.switchTheme,
            contentDescription = title,
            labelSizeSp = 15.5f,
            onChanged = onChanged,
        )
        container.addView(rowResult.rowView, matchWrap())
    }

    fun createActionButton(text: String, selected: Boolean = false, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            stateListAnimator = null
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            minWidth = 0
            minimumWidth = 0
            setTextColor(if (selected) colors.accent else colors.primaryText)
            typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = RippleDrawable(
                ColorStateList.valueOf(colors.ripple),
                roundedBox(
                    fill = if (selected) colors.selectedSurface else colors.button,
                    stroke = if (selected) colors.selectedOutline else colors.outline,
                    cornerDp = GeelyUiDimens.CONTROL_CORNER_DP,
                ),
                null,
            )
            setOnClickListener { onClick() }
        }

    fun createPrimaryButton(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            stateListAnimator = null
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(colors.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            minWidth = 0
            minimumWidth = 0
            val accentFill = colors.accent
            background = RippleDrawable(
                ColorStateList.valueOf(colors.ripple),
                GradientDrawable().apply {
                    setColor(accentFill)
                    cornerRadius = dp(GeelyUiDimens.CONTROL_CORNER_DP).toFloat()
                },
                null,
            )
            setOnClickListener { onClick() }
        }

    fun roundedBox(fill: Int, stroke: Int, cornerDp: Int = 12, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(cornerDp).toFloat()
            setStroke(dp(strokeDp).coerceAtLeast(1), stroke)
        }

    private fun matchWrap(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG_TAB_ICON = "geely_tab_icon"
        private const val TAG_TAB_LABEL = "geely_tab_label"

        val INLINE_TAB_TITLES = listOf(
            "仪表与主题",
            "导航与 HUD",
            "预览与调试",
            "权限与副屏",
        )

        val INLINE_TAB_ICONS = listOf(
            R.drawable.ic_dp_dashboard,
            R.drawable.ic_dp_navigation,
            R.drawable.ic_dp_display,
            R.drawable.ic_dp_permissions,
        )

        val STUDIO_TAB_TITLES = listOf(
            "主题与配色",
            "画面与按键",
            "导航与坐标",
            "权限与副屏",
        )
    }
}

/**
 * Fullscreen live-preview studio dialog for Geely Cluster & HUD configuration:
 * - Widescreen (>= 720dp): Left persistent 1920x1080 live preview + Right tabbed configuration column
 * - Compact (< 720dp): Top live preview + Bottom tabbed configuration column
 */
internal class GeelyClusterSettingsDialog(
    private val activity: Activity,
    private val onDismiss: () -> Unit = {},
) {
    private val colors = GeelyClusterUiColors.resolve(activity)
    private val panel = GeelyClusterSettingsPanel(activity, colors, studioMode = true)

    fun show() {
        val themeRes = if (colors.isNight) {
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        } else {
            android.R.style.Theme_Light_NoTitleBar_Fullscreen
        }
        val dialog = Dialog(activity, themeRes)
        dialog.setOnDismissListener {
            panel.cancelLearningState()
            onDismiss()
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(colors.background))
            statusBarColor = colors.palette.systemBar
            navigationBarColor = colors.palette.systemBar
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = colors.palette.systemBarIconsAreDark
                isAppearanceLightNavigationBars = colors.palette.systemBarIconsAreDark
            }
        }

        val splitSideBySide = activity.resources.configuration.screenWidthDp >= GeelyUiDimens.STUDIO_SPLIT_MIN_DP

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }

        // 1. Compact Header Bar
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.surface)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val backBtn = panel.createActionButton("‹ 返回设置") { dialog.dismiss() }
        header.addView(backBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
        }
        titleCol.addView(
            TextView(activity).apply {
                text = "吉利仪表与 HUD · 实时预览工作台"
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val statusTv = TextView(activity).apply {
            setTextColor(colors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(statusTv)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val resetBtn = panel.createActionButton("恢复 HUD 默认坐标") {
            panel.resetAreasToDefault()
        }
        header.addView(resetBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        root.addView(header)

        // Subtle separator under header
        root.addView(
            View(activity).apply { setBackgroundColor(colors.divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)),
        )

        // 2. Main Split Body
        val body = LinearLayout(activity).apply {
            orientation = if (splitSideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Left / Top Persistent Live Preview Pane
        val previewPane = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panel.roundedBox(colors.surface, colors.outline, cornerDp = GeelyUiDimens.CARD_CORNER_DP)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        previewPane.addView(
            TextView(activity).apply {
                text = "1920×1080 实车画面与裁剪源实时预览"
                setTextColor(colors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            },
        )
        val previewFrame = FrameLayout(activity).apply {
            background = panel.roundedBox(colors.previewFrame, colors.outline, cornerDp = 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val livePreview = GeelyCompositePreviewView(activity)
        previewFrame.addView(
            livePreview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (splitSideBySide) ViewGroup.LayoutParams.MATCH_PARENT else dp(200),
            ),
        )
        previewPane.addView(
            previewFrame,
            if (splitSideBySide) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(10) }
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8) }
            },
        )
        val previewControlsHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        previewPane.addView(previewControlsHost)

        if (splitSideBySide) {
            body.addView(
                previewPane,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f).apply { marginEnd = dp(14) },
            )
        } else {
            body.addView(
                previewPane,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(12) },
            )
        }

        // Right / Bottom Tabbed Configuration Pane
        val configPane = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val tabStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = panel.roundedBox(colors.surface, colors.outline, cornerDp = 14)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val studioTabViews = ArrayList<LinearLayout>()
        for (i in GeelyClusterSettingsPanel.STUDIO_TAB_TITLES.indices) {
            val tabView = panel.createTabButton(GeelyClusterSettingsPanel.STUDIO_TAB_TITLES[i]) {
                panel.selectTab(i)
            }
            studioTabViews.add(tabView)
            tabStrip.addView(
                tabView,
                LinearLayout.LayoutParams(0, dp(GeelyUiDimens.COMPACT_BUTTON_HEIGHT_DP), 1f).apply {
                    if (i > 0) marginStart = dp(4)
                },
            )
        }
        configPane.addView(
            tabStrip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) },
        )

        val rightScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
        }
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        rightScroll.addView(contentCol)
        configPane.addView(
            rightScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        if (splitSideBySide) {
            body.addView(configPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            body.addView(configPane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.bindStudioViews(
            headerSubtitle = statusTv,
            livePreview = livePreview,
            previewControlsHost = previewControlsHost,
            studioTabViews = studioTabViews,
            contentContainer = contentCol,
        ) {
            rightScroll.scrollTo(0, 0)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
