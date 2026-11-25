package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.Locale

internal enum class GeelyDialColorMode {
    DEFAULT,
    SPEED,
    ALBUM;

    companion object {
        fun fromName(raw: String?): GeelyDialColorMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

internal enum class GeelyClusterAppearanceMode(
    val displayName: String,
    val shortLabel: String,
) {
    AUTO("跟随 CarPlay 昼夜", "自动"),
    DARK("始终深色（夜间）", "深色"),
    LIGHT("始终浅色（日间）", "浅色");

    companion object {
        fun fromName(raw: String?): GeelyClusterAppearanceMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}

internal enum class GeelyClusterThemeStyle(
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val supportsLightMode: Boolean = false,
) {
    SPORT_CHRONO(
        displayName = "赛道计时 · 运动指针",
        shortLabel = "赛道计时",
        description = "保时捷运动指针 + 顶部 9 颗动态换挡灯珠 + 碳纤维表底（支持昼夜）",
        supportsLightMode = true,
    ),
    HERITAGE_GOLD(
        displayName = "典藏鎏金 · 奢华机械",
        shortLabel = "典藏鎏金",
        description = "瑞士铁轨刻度 + 立体剑形指针 + 夜间墨玉金 / 日间象牙陶瓷盘",
        supportsLightMode = true,
    ),
    AERO_MECHA(
        displayName = "机甲光翼 · 未来战术",
        shortLabel = "机甲光翼",
        description = "战术切角双翼外框 + 28 格独立发光 LED 能量块 + 斜体数字",
        supportsLightMode = false,
    ),
    NORDIC_GLASS(
        displayName = "北欧冰川 · 液态玻璃",
        shortLabel = "北欧冰川",
        description = "通透冰霜玻璃环 + 镜面高光边 + 悬浮流光圆点（支持昼夜）",
        supportsLightMode = true,
    ),
    RETRO_VFD(
        displayName = "复古微光 · 荧光液晶",
        shortLabel = "复古微光",
        description = "80s VFD 荧光微点阵底 + 36 枚阶梯荧光柱 + 格栅复古油量条",
        supportsLightMode = false,
    ),
    ULTRA_CLASSIC_TICKS(
        displayName = "Ultra 精密刻度",
        shortLabel = "精密刻度",
        description = "双圈精密长短刻度 + 扇区光带 + 右盘档位/RPM + 专辑氛围底",
        supportsLightMode = false,
    ),
    ULTRA_MINIMAL_RING(
        displayName = "Ultra 极简光环",
        shortLabel = "极简光环",
        description = "无数字磨砂厚光环 + 流畅主题色弧 + 极简档位与底部油量弧",
        supportsLightMode = false,
    ),
    ULTRA_CLEAN_LIGHT(
        displayName = "Ultra 现代点刻（自适应昼夜）",
        shortLabel = "现代点刻",
        description = "日间陶瓷浅银 / 夜间深石墨 + 圆点刻度 + 圆角进度弧 + 右盘方角封面",
        supportsLightMode = true,
    );

    fun defaultAccentColor(nightMode: Boolean): Int = when (this) {
        SPORT_CHRONO -> if (nightMode) 0xFFFF3B30.toInt() else 0xFFD92D20.toInt()
        HERITAGE_GOLD -> if (nightMode) 0xFFDEB973.toInt() else 0xFF8F6A2E.toInt()
        AERO_MECHA -> 0xFF00E5FF.toInt()
        NORDIC_GLASS -> if (nightMode) 0xFF38BDF8.toInt() else 0xFF0369A1.toInt()
        RETRO_VFD -> 0xFF2EE6A6.toInt()
        ULTRA_CLASSIC_TICKS,
        ULTRA_MINIMAL_RING,
        ULTRA_CLEAN_LIGHT -> GeelyColorTransition.DEFAULT_CYAN
    }

    companion object {
        fun fromName(raw: String?): GeelyClusterThemeStyle =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SPORT_CHRONO
    }
}

internal data class GeelyOverlayArea(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val isValid: Boolean get() = width > 0 && height > 0

    fun clamp(maxWidth: Int = CANVAS_WIDTH, maxHeight: Int = CANVAS_HEIGHT): GeelyOverlayArea {
        val w = width.coerceIn(40, maxWidth)
        val h = height.coerceIn(32, maxHeight)
        val cx = x.coerceIn(0, (maxWidth - w).coerceAtLeast(0))
        val cy = y.coerceIn(0, (maxHeight - h).coerceAtLeast(0))
        return GeelyOverlayArea(cx, cy, w, h)
    }

    companion object {
        const val CANVAS_WIDTH = 1920
        const val CANVAS_HEIGHT = 1080
        const val VIDEO_HEIGHT = 720

        val DEFAULT_HUD = GeelyOverlayArea(x = 0, y = 720, width = 228, height = 186)
        val DEFAULT_CARD = GeelyOverlayArea(x = 1165, y = 720, width = 364, height = 112)
        val DEFAULT_FULLSCREEN_CARD = GeelyOverlayArea(x = 595, y = 118, width = 728, height = 112)
    }
}

internal data class GeelySelfDrawConfig(
    val hudEnabled: Boolean = true,
    val cardEnabled: Boolean = true,
    val fullscreenCardEnabled: Boolean = true,
    val laneEnabled: Boolean = true,
    val hudArea: GeelyOverlayArea = GeelyOverlayArea.DEFAULT_HUD,
    val cardArea: GeelyOverlayArea = GeelyOverlayArea.DEFAULT_CARD,
    val fullscreenCardArea: GeelyOverlayArea = GeelyOverlayArea.DEFAULT_FULLSCREEN_CARD,
    val laneX: Int? = null,
    val laneY: Int? = null,
    val previewEnabled: Boolean = false,
    val mapThemeDark: Boolean = true,
    val mapVignetteEnabled: Boolean = true,
    val dialLeftEnabled: Boolean = true,
    val dialRightEnabled: Boolean = true,
    val albumArtEnabled: Boolean = true,
    val bottomBarEnabled: Boolean = true,
    val dialColorMode: GeelyDialColorMode = GeelyDialColorMode.DEFAULT,
    val dialThemeStyle: GeelyClusterThemeStyle = GeelyClusterThemeStyle.SPORT_CHRONO,
    val appearanceMode: GeelyClusterAppearanceMode = GeelyClusterAppearanceMode.AUTO,
    val preferredDisplayId: Int = 2,
) {
    val hasAnySelfDrawOverlay: Boolean
        get() = hudEnabled || cardEnabled || fullscreenCardEnabled || laneEnabled || previewEnabled

    val hasAnyClusterVisual: Boolean
        get() = dialLeftEnabled || dialRightEnabled || albumArtEnabled || bottomBarEnabled || mapVignetteEnabled
}

internal object GeelyClusterPrefs {
    const val PREFS_NAME = "geely_cluster_safe_area"

    private const val KEY_HUD_ENABLED = "self_draw_hud_enabled"
    private const val KEY_CARD_ENABLED = "self_draw_card_enabled"
    private const val KEY_FULLSCREEN_CARD_ENABLED = "self_draw_fullscreen_card_enabled"
    private const val KEY_LANE_ENABLED = "self_draw_lane_enabled"
    private const val KEY_LANE_X = "self_draw_lane_x"
    private const val KEY_LANE_Y = "self_draw_lane_y"
    private const val KEY_PREVIEW_ENABLED = "self_draw_preview_enabled"
    private const val KEY_MAP_THEME_DARK = "self_draw_map_theme_dark"
    private const val KEY_MAP_VIGNETTE_ENABLED = "self_draw_map_vignette_enabled"
    private const val KEY_DIAL_LEFT_ENABLED = "self_draw_dial_left_enabled"
    private const val KEY_DIAL_RIGHT_ENABLED = "self_draw_dial_right_enabled"
    private const val KEY_ALBUM_ART_ENABLED = "self_draw_album_art_enabled"
    private const val KEY_BOTTOM_BAR_ENABLED = "self_draw_bottom_bar_enabled"
    private const val KEY_DIAL_COLOR_MODE = "self_draw_dial_color_mode"
    private const val KEY_DIAL_THEME_STYLE = "self_draw_dial_theme_style"
    private const val KEY_APPEARANCE_MODE = "self_draw_appearance_mode"
    private const val KEY_DISPLAY_ID = "display_id"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GeelySelfDrawConfig {
        val sp = prefs(context)
        val hudArea = readArea(sp, "hud", GeelyOverlayArea.DEFAULT_HUD)
        val cardArea = readArea(sp, "card", GeelyOverlayArea.DEFAULT_CARD)
        val fullscreenCardArea = readArea(sp, "fullscreen_card", GeelyOverlayArea.DEFAULT_FULLSCREEN_CARD)
        val laneX = if (sp.contains(KEY_LANE_X)) sp.getInt(KEY_LANE_X, 0) else null
        val laneY = if (sp.contains(KEY_LANE_Y)) sp.getInt(KEY_LANE_Y, 0) else null
        return GeelySelfDrawConfig(
            hudEnabled = sp.getBoolean(KEY_HUD_ENABLED, true),
            cardEnabled = sp.getBoolean(KEY_CARD_ENABLED, true),
            fullscreenCardEnabled = sp.getBoolean(KEY_FULLSCREEN_CARD_ENABLED, true),
            laneEnabled = sp.getBoolean(KEY_LANE_ENABLED, true),
            hudArea = hudArea,
            cardArea = cardArea,
            fullscreenCardArea = fullscreenCardArea,
            laneX = laneX,
            laneY = laneY,
            previewEnabled = sp.getBoolean(KEY_PREVIEW_ENABLED, false),
            mapThemeDark = sp.getBoolean(KEY_MAP_THEME_DARK, true),
            mapVignetteEnabled = sp.getBoolean(KEY_MAP_VIGNETTE_ENABLED, true),
            dialLeftEnabled = sp.getBoolean(KEY_DIAL_LEFT_ENABLED, true),
            dialRightEnabled = sp.getBoolean(KEY_DIAL_RIGHT_ENABLED, true),
            albumArtEnabled = sp.getBoolean(KEY_ALBUM_ART_ENABLED, true),
            bottomBarEnabled = sp.getBoolean(KEY_BOTTOM_BAR_ENABLED, true),
            dialColorMode = GeelyDialColorMode.fromName(sp.getString(KEY_DIAL_COLOR_MODE, null)),
            dialThemeStyle = GeelyClusterThemeStyle.fromName(sp.getString(KEY_DIAL_THEME_STYLE, null)),
            appearanceMode = GeelyClusterAppearanceMode.fromName(sp.getString(KEY_APPEARANCE_MODE, null)),
            preferredDisplayId = sp.getInt(KEY_DISPLAY_ID, 2),
        )
    }

    fun save(context: Context, config: GeelySelfDrawConfig) {
        val editor = prefs(context).edit()
            .putBoolean(KEY_HUD_ENABLED, config.hudEnabled)
            .putBoolean(KEY_CARD_ENABLED, config.cardEnabled)
            .putBoolean(KEY_FULLSCREEN_CARD_ENABLED, config.fullscreenCardEnabled)
            .putBoolean(KEY_LANE_ENABLED, config.laneEnabled)
            .putBoolean(KEY_PREVIEW_ENABLED, config.previewEnabled)
            .putBoolean(KEY_MAP_THEME_DARK, config.mapThemeDark)
            .putBoolean(KEY_MAP_VIGNETTE_ENABLED, config.mapVignetteEnabled)
            .putBoolean(KEY_DIAL_LEFT_ENABLED, config.dialLeftEnabled)
            .putBoolean(KEY_DIAL_RIGHT_ENABLED, config.dialRightEnabled)
            .putBoolean(KEY_ALBUM_ART_ENABLED, config.albumArtEnabled)
            .putBoolean(KEY_BOTTOM_BAR_ENABLED, config.bottomBarEnabled)
            .putString(KEY_DIAL_COLOR_MODE, config.dialColorMode.name)
            .putString(KEY_DIAL_THEME_STYLE, config.dialThemeStyle.name)
            .putString(KEY_APPEARANCE_MODE, config.appearanceMode.name)
            .putInt(KEY_DISPLAY_ID, config.preferredDisplayId)
        writeArea(editor, "hud", config.hudArea.clamp())
        writeArea(editor, "card", config.cardArea.clamp())
        writeArea(editor, "fullscreen_card", config.fullscreenCardArea.clamp())
        if (config.laneX != null && config.laneY != null) {
            editor.putInt(KEY_LANE_X, config.laneX)
            editor.putInt(KEY_LANE_Y, config.laneY)
        } else {
            editor.remove(KEY_LANE_X).remove(KEY_LANE_Y)
        }
        editor.apply()
    }

    fun resetAreasToDefault(context: Context): GeelySelfDrawConfig {
        val current = load(context)
        val updated = current.copy(
            hudArea = GeelyOverlayArea.DEFAULT_HUD,
            cardArea = GeelyOverlayArea.DEFAULT_CARD,
            fullscreenCardArea = GeelyOverlayArea.DEFAULT_FULLSCREEN_CARD,
            laneX = null,
            laneY = null,
        )
        save(context, updated)
        return updated
    }

    private fun readArea(sp: SharedPreferences, prefix: String, fallback: GeelyOverlayArea): GeelyOverlayArea {
        val x = sp.getInt("${prefix}_x", fallback.x)
        val y = sp.getInt("${prefix}_y", fallback.y)
        val w = sp.getInt("${prefix}_width", fallback.width)
        val h = sp.getInt("${prefix}_height", fallback.height)
        return GeelyOverlayArea(x, y, w, h).clamp()
    }

    private fun writeArea(editor: SharedPreferences.Editor, prefix: String, area: GeelyOverlayArea) {
        editor.putInt("${prefix}_x", area.x)
            .putInt("${prefix}_y", area.y)
            .putInt("${prefix}_width", area.width)
            .putInt("${prefix}_height", area.height)
    }
}

internal object GeelyVehicleDetector {
    @Volatile
    private var cachedPackageMatch: Boolean? = null

    fun isGeelyOrEcarx(context: Context? = null): Boolean {
        if (GeelyClusterDisplay.supported() || EcarxKx11HeadUnit.detected) return true
        if (EcarxKx11HeadUnit.matches(Build.PRODUCT, Build.DEVICE, Build.MODEL, true)) return true
        if (Build.DEVICE.equals("robolectric", ignoreCase = true)) return false
        val combined = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.PRODUCT} ${Build.DEVICE} ${Build.MODEL}"
            .lowercase(Locale.ROOT)
        if ("geely" in combined || "ecarx" in combined || "kx11" in combined || "fx11" in combined ||
            "xingyue" in combined || "monjaro" in combined || "cs11" in combined || "fs11" in combined ||
            "ss11" in combined
        ) {
            return true
        }
        if (EcarxWheelSpeed.present()) return true
        val ctx = context ?: return cachedPackageMatch == true
        cachedPackageMatch?.let { return it }
        val matched = runCatching {
            val apps = ctx.packageManager.getInstalledApplications(0)
            apps.any { info ->
                val pkg = info.packageName
                pkg.startsWith("com.ecarx.") || pkg.startsWith("com.geely.")
            }
        }.getOrDefault(false)
        cachedPackageMatch = matched
        return matched
    }

    fun isComposite1080Display(display: Display): Boolean {
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x == GeelyOverlayArea.CANVAS_WIDTH && size.y == GeelyOverlayArea.CANVAS_HEIGHT) {
            return true
        }
        val mode = display.mode ?: return false
        return mode.physicalWidth == GeelyOverlayArea.CANVAS_WIDTH &&
            mode.physicalHeight == GeelyOverlayArea.CANVAS_HEIGHT
    }

    fun findClusterDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val preferredId = GeelyClusterPrefs.load(context).preferredDisplayId
        val presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .orEmpty()
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
        val candidates = if (presentationDisplays.isNotEmpty()) {
            presentationDisplays
        } else {
            dm.displays.filter {
                it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRESENTATION) != 0
            }
        }
        // 1. Prefer user-selected display if it matches 1920x1080 or 1920x720
        candidates.firstOrNull { d ->
            d.displayId == preferredId && isClusterResolution(d)
        }?.let { return it }
        // 2. Prefer 1920x1080 composite cluster display (e.g. KX11 / Geely OneOS)
        candidates.firstOrNull { d ->
            isComposite1080Display(d)
        }?.let { return it }
        // 3. Fallback to 1920x720 cluster display (e.g. FX11)
        return candidates.firstOrNull { d -> isClusterResolution(d) }
    }

    fun isClusterResolution(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if ((display.flags and Display.FLAG_PRESENTATION) == 0) return false
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x == 1920 && (size.y == 1080 || size.y == 720)) return true
        val mode = display.mode ?: return false
        return mode.physicalWidth == 1920 && (mode.physicalHeight == 1080 || mode.physicalHeight == 720)
    }
}
