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

internal enum class GeelyClusterCenterMode(
    val displayName: String,
    val shortLabel: String,
) {
    AUTO("自动（地图 / 专辑 / 时钟）", "自动"),
    MAP("导航地图", "导航地图"),
    ALBUM("专辑封面", "专辑封面"),
    CLOCK("大号数字时钟", "数字时钟"),
    TRIP("本次行程", "本次行程"),
    TIRE("胎压与胎温", "胎压胎温"),
    VEHICLE_INFO("车辆综合信息", "综合车况");

    val isTelemetryPage: Boolean
        get() = this == TRIP || this == TIRE || this == VEHICLE_INFO

    companion object {
        val PRIMARY_ORDER: List<GeelyClusterCenterMode> = listOf(
            MAP,
            ALBUM,
            CLOCK,
        )

        val TELEMETRY_ORDER: List<GeelyClusterCenterMode> = listOf(
            TRIP,
            TIRE,
            VEHICLE_INFO,
        )

        val CYCLE_ORDER: List<GeelyClusterCenterMode> = PRIMARY_ORDER + TELEMETRY_ORDER

        val DEFAULT_PRIMARY_MODES: Set<GeelyClusterCenterMode> = setOf(MAP, ALBUM)

        val DEFAULT_TELEMETRY_MODES: Set<GeelyClusterCenterMode> = TELEMETRY_ORDER.toSet()

        val DEFAULT_CYCLE_MODES: Set<GeelyClusterCenterMode> = CYCLE_ORDER.toSet()

        fun fromName(raw: String?): GeelyClusterCenterMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO

        fun parsePrimaryModes(raw: String?): Set<GeelyClusterCenterMode> {
            if (raw.isNullOrBlank()) return DEFAULT_PRIMARY_MODES
            val parsed = raw.split(',')
                .mapNotNull { token -> PRIMARY_ORDER.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) } }
                .toCollection(LinkedHashSet())
            return if (parsed.isEmpty()) DEFAULT_PRIMARY_MODES else parsed
        }

        fun serializePrimaryModes(modes: Set<GeelyClusterCenterMode>): String =
            PRIMARY_ORDER.filter { it in modes }.joinToString(",") { it.name }

        fun parseTelemetryModes(raw: String?): Set<GeelyClusterCenterMode> {
            if (raw.isNullOrBlank()) return DEFAULT_TELEMETRY_MODES
            val parsed = raw.split(',')
                .mapNotNull { token -> TELEMETRY_ORDER.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) } }
                .toCollection(LinkedHashSet())
            return if (parsed.isEmpty()) DEFAULT_TELEMETRY_MODES else parsed
        }

        fun serializeTelemetryModes(modes: Set<GeelyClusterCenterMode>): String =
            TELEMETRY_ORDER.filter { it in modes }.joinToString(",") { it.name }

        fun parseCycleModes(raw: String?): Set<GeelyClusterCenterMode> {
            if (raw.isNullOrBlank()) return DEFAULT_CYCLE_MODES
            val parsed = raw.split(',')
                .mapNotNull { token -> CYCLE_ORDER.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) } }
                .toCollection(LinkedHashSet())
            return if (parsed.isEmpty()) setOf(CLOCK) else parsed
        }

        fun serializeCycleModes(modes: Set<GeelyClusterCenterMode>): String =
            CYCLE_ORDER.filter { it in modes }.joinToString(",") { it.name }
    }
}

internal enum class GeelyClusterSwitchTrigger(
    val displayName: String,
    val shortLabel: String,
) {
    SINGLE_CLICK("单击", "单击"),
    LONG_PRESS("长按", "长按"),
    DOUBLE_CLICK("双击", "双击");

    fun next(): GeelyClusterSwitchTrigger = entries[(ordinal + 1) % entries.size]

    companion object {
        private val FALLBACK_ORDER = listOf(LONG_PRESS, DOUBLE_CLICK, SINGLE_CLICK)

        fun fromName(
            raw: String?,
            defaultValue: GeelyClusterSwitchTrigger = SINGLE_CLICK,
        ): GeelyClusterSwitchTrigger =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: defaultValue

        /**
         * Returns [preferred] if it does not collide with [used], otherwise picks the first
         * remaining gesture in [FALLBACK_ORDER] (preferring zero-delay LONG_PRESS before DOUBLE_CLICK).
         */
        fun resolveNonConflicting(
            preferred: GeelyClusterSwitchTrigger,
            used: Set<GeelyClusterSwitchTrigger>,
        ): GeelyClusterSwitchTrigger {
            if (preferred !in used) return preferred
            return FALLBACK_ORDER.firstOrNull { it !in used } ?: preferred
        }
    }
}

internal enum class GeelyClusterThemeStyle(
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val supportsLightMode: Boolean = true,
) {
    OEM_CLASSIC(
        displayName = "原车经典 · 默认主驾",
        shortLabel = "原车经典",
        description = "保持原车 QNX 默认主驾界面，同时保留 CarPlay W-HUD 与经典仪表导航卡（方向盘可随时切出）",
        supportsLightMode = true,
    ),
    PURE_MAP(
        displayName = "纯地图全屏 · 极简沉浸",
        shortLabel = "纯地图全屏",
        description = "隐藏左右圆盘与边缘遮罩、下方保留音乐栏；无地图投屏时自动回落原车经典仪表（支持昼夜）",
        supportsLightMode = true,
    ),
    AERO_MECHA(
        displayName = "机甲光翼 · 未来战术",
        shortLabel = "机甲光翼",
        description = "战术切角双翼外框 + 28 格独立发光 LED 能量块 + 斜体数字（支持昼夜）",
        supportsLightMode = true,
    ),
    HYPER_WING(
        displayName = "超跑双翼 · 极速光刃",
        shortLabel = "超跑双翼",
        description = "左右镜像 C 形开口光翼 + 24 枚渐宽能量刃 + 顶部 5 格换挡条（支持昼夜）",
        supportsLightMode = true,
    ),
    VALKYRIE_HEX(
        displayName = "女武神 · 六维装甲",
        shortLabel = "女武神",
        description = "八边形切角双层装甲框 + 紫青双色能量矩阵 + 13 颗联动菱形节点（支持昼夜）",
        supportsLightMode = true,
    ),
    ORBIT_RADAR(
        displayName = "量子雷达 · 脉冲星环",
        shortLabel = "量子雷达",
        description = "45° 方位缺口全息雷达 + 36 枚高频脉冲光束 + 航空地平线准星（支持昼夜）",
        supportsLightMode = true,
    ),
    TITAN_CORE(
        displayName = "泰坦核心 · 重装反应堆",
        shortLabel = "泰坦核心",
        description = "外圈 12 枚宽体装甲模块 + 内圈 36 枚同步微针 + 重型机械锁止翼（支持昼夜）",
        supportsLightMode = true,
    ),
    PHANTOM_STEALTH(
        displayName = "幽灵隐形 · 暗影战机",
        shortLabel = "幽灵战机",
        description = "隐形战机尖角折线翼 + 18 枚刀锋节流阀弧 + 滑动三角瞄准游标（支持昼夜）",
        supportsLightMode = true,
    );

    val isOemNative: Boolean
        get() = this == OEM_CLASSIC

    val isPureMap: Boolean
        get() = this == PURE_MAP

    val usesDialChrome: Boolean
        get() = !isOemNative && !isPureMap

    fun defaultAccentColor(nightMode: Boolean): Int = when (this) {
        OEM_CLASSIC -> if (nightMode) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
        PURE_MAP -> if (nightMode) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt()
        else -> GeelyClusterThemeSpecs.of(this).defaultAccentColor(nightMode)
    }

    companion object {
        val DEFAULT_CYCLE_STYLES: Set<GeelyClusterThemeStyle> = linkedSetOf(
            OEM_CLASSIC,
            PURE_MAP,
            AERO_MECHA,
        )

        fun fromName(raw: String?): GeelyClusterThemeStyle =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AERO_MECHA

        fun parseCycleStyles(raw: String?): Set<GeelyClusterThemeStyle> {
            if (raw.isNullOrBlank()) return DEFAULT_CYCLE_STYLES
            val parsed = raw.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) } }
                .toCollection(LinkedHashSet())
            return if (parsed.isEmpty()) DEFAULT_CYCLE_STYLES else parsed
        }

        fun serializeCycleStyles(styles: Set<GeelyClusterThemeStyle>): String =
            entries.filter { it in styles }.joinToString(",") { it.name }
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
    val albumLyricsEnabled: Boolean = true,
    val albumOnlineLyricsEnabled: Boolean = true,
    val albumLocalMediaLyricsEnabled: Boolean = true,
    val bottomBarEnabled: Boolean = true,
    val dialColorMode: GeelyDialColorMode = GeelyDialColorMode.DEFAULT,
    val dialThemeStyle: GeelyClusterThemeStyle = GeelyClusterThemeStyle.AERO_MECHA,
    val appearanceMode: GeelyClusterAppearanceMode = GeelyClusterAppearanceMode.AUTO,
    val launchThemeOnStart: Boolean = false,
    val preferredDisplayId: Int = 2,
    val centerMode: GeelyClusterCenterMode = GeelyClusterCenterMode.AUTO,
    val centerSwitchKeyCode: Int = 0,
    val centerSwitchTrigger: GeelyClusterSwitchTrigger = GeelyClusterSwitchTrigger.SINGLE_CLICK,
    val primaryCycleModes: Set<GeelyClusterCenterMode> = GeelyClusterCenterMode.DEFAULT_PRIMARY_MODES,
    val telemetrySwitchKeyCode: Int = 0,
    val telemetrySwitchTrigger: GeelyClusterSwitchTrigger = GeelyClusterSwitchTrigger.LONG_PRESS,
    val telemetryCycleModes: Set<GeelyClusterCenterMode> = GeelyClusterCenterMode.DEFAULT_TELEMETRY_MODES,
    val themeSwitchKeyCode: Int = 0,
    val themeSwitchTrigger: GeelyClusterSwitchTrigger = GeelyClusterSwitchTrigger.DOUBLE_CLICK,
    val themeCycleStyles: Set<GeelyClusterThemeStyle> = GeelyClusterThemeStyle.DEFAULT_CYCLE_STYLES,
    val tireAlertPopupEnabled: Boolean = true,
    val navAutoReturnSeconds: Int = DEFAULT_NAV_AUTO_RETURN_SECONDS,
    val interceptVoiceKeyForSiri: Boolean = false,
) {
    companion object {
        const val DEFAULT_NAV_AUTO_RETURN_SECONDS = 5
        val NAV_AUTO_RETURN_OPTIONS = listOf(0, 5, 10, 15, 20)
    }

    val cycleModes: Set<GeelyClusterCenterMode>
        get() = primaryCycleModes + telemetryCycleModes

    val effectiveTelemetrySwitchKeyCode: Int
        get() = if (telemetrySwitchKeyCode > 0) telemetrySwitchKeyCode else centerSwitchKeyCode

    val sharesTelemetryKeyWithPrimary: Boolean
        get() = centerSwitchKeyCode > 0 && (telemetrySwitchKeyCode <= 0 || telemetrySwitchKeyCode == centerSwitchKeyCode)

    val sharesThemeKeyWithPrimary: Boolean
        get() = themeSwitchKeyCode > 0 && themeSwitchKeyCode == centerSwitchKeyCode

    val sharesThemeKeyWithTelemetry: Boolean
        get() = themeSwitchKeyCode > 0 && themeSwitchKeyCode == effectiveTelemetrySwitchKeyCode

    val hasAnySelfDrawOverlay: Boolean
        get() = hudEnabled || cardEnabled || fullscreenCardEnabled || laneEnabled || previewEnabled

    val hasAnyClusterVisual: Boolean
        get() = when {
            dialThemeStyle.isOemNative -> false
            dialThemeStyle.isPureMap -> true
            else -> dialLeftEnabled || dialRightEnabled || albumArtEnabled || bottomBarEnabled || mapVignetteEnabled
        }

    /**
     * Whether persistent cluster theme standby & wheel-key theme switching should stay active
     * even when the currently selected theme is [GeelyClusterThemeStyle.OEM_CLASSIC] or
     * [GeelyClusterThemeStyle.PURE_MAP] waiting for a map source.
     */
    val hasClusterThemeCapability: Boolean
        get() = launchThemeOnStart && (
            hasAnyClusterVisual ||
                dialThemeStyle.isOemNative ||
                themeSwitchKeyCode > 0
            )
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
    private const val KEY_ALBUM_LYRICS_ENABLED = "self_draw_album_lyrics_enabled"
    private const val KEY_ALBUM_ONLINE_LYRICS_ENABLED = "self_draw_album_online_lyrics_enabled"
    private const val KEY_ALBUM_LOCAL_MEDIA_LYRICS_ENABLED = "self_draw_album_local_media_lyrics_enabled"
    private const val KEY_BOTTOM_BAR_ENABLED = "self_draw_bottom_bar_enabled"
    private const val KEY_DIAL_COLOR_MODE = "self_draw_dial_color_mode"
    private const val KEY_DIAL_THEME_STYLE = "self_draw_dial_theme_style"
    private const val KEY_APPEARANCE_MODE = "self_draw_appearance_mode"
    private const val KEY_LAUNCH_THEME_ON_START = "self_draw_launch_theme_on_start"
    private const val KEY_DISPLAY_ID = "display_id"
    private const val KEY_CENTER_MODE = "self_draw_center_mode"
    private const val KEY_CENTER_SWITCH_KEY_CODE = "self_draw_center_switch_key_code"
    private const val KEY_CENTER_SWITCH_TRIGGER = "self_draw_center_switch_trigger"
    private const val KEY_CYCLE_MODES = "self_draw_cycle_modes"
    private const val KEY_PRIMARY_CYCLE_MODES = "self_draw_primary_cycle_modes"
    private const val KEY_TELEMETRY_SWITCH_KEY_CODE = "self_draw_telemetry_switch_key_code"
    private const val KEY_TELEMETRY_SWITCH_TRIGGER = "self_draw_telemetry_switch_trigger"
    private const val KEY_TELEMETRY_CYCLE_MODES = "self_draw_telemetry_cycle_modes"
    private const val KEY_THEME_SWITCH_KEY_CODE = "self_draw_theme_switch_key_code"
    private const val KEY_THEME_SWITCH_TRIGGER = "self_draw_theme_switch_trigger"
    private const val KEY_THEME_CYCLE_STYLES = "self_draw_theme_cycle_styles"
    private const val KEY_TIRE_ALERT_POPUP_ENABLED = "self_draw_tire_alert_popup_enabled"
    private const val KEY_NAV_AUTO_RETURN_SECONDS = "self_draw_nav_auto_return_seconds"
    private const val KEY_INTERCEPT_VOICE_KEY_FOR_SIRI = "self_draw_intercept_voice_key_for_siri"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GeelySelfDrawConfig {
        val sp = prefs(context)
        val hudArea = readArea(sp, "hud", GeelyOverlayArea.DEFAULT_HUD)
        val cardArea = readArea(sp, "card", GeelyOverlayArea.DEFAULT_CARD)
        val fullscreenCardArea = readArea(sp, "fullscreen_card", GeelyOverlayArea.DEFAULT_FULLSCREEN_CARD)
        val laneX = if (sp.contains(KEY_LANE_X)) sp.getInt(KEY_LANE_X, 0) else null
        val laneY = if (sp.contains(KEY_LANE_Y)) sp.getInt(KEY_LANE_Y, 0) else null
        val legacyCycleRaw = sp.getString(KEY_CYCLE_MODES, null)
        val primaryModes = if (sp.contains(KEY_PRIMARY_CYCLE_MODES)) {
            GeelyClusterCenterMode.parsePrimaryModes(sp.getString(KEY_PRIMARY_CYCLE_MODES, null))
        } else if (!legacyCycleRaw.isNullOrBlank()) {
            GeelyClusterCenterMode.parsePrimaryModes(legacyCycleRaw)
        } else {
            GeelyClusterCenterMode.DEFAULT_PRIMARY_MODES
        }
        val telemetryModes = if (sp.contains(KEY_TELEMETRY_CYCLE_MODES)) {
            GeelyClusterCenterMode.parseTelemetryModes(sp.getString(KEY_TELEMETRY_CYCLE_MODES, null))
        } else if (!legacyCycleRaw.isNullOrBlank()) {
            GeelyClusterCenterMode.parseTelemetryModes(legacyCycleRaw)
        } else {
            GeelyClusterCenterMode.DEFAULT_TELEMETRY_MODES
        }
        val defaultPrimaryKey = if (EcarxKx11HeadUnit.detected) EcarxKx11WheelKeys.KEY_WECHAT else 0
        val primaryKeyCode = sp.getInt(KEY_CENTER_SWITCH_KEY_CODE, defaultPrimaryKey)
        val telemetryKeyCode = sp.getInt(KEY_TELEMETRY_SWITCH_KEY_CODE, 0)
        val defaultThemeKey = if (EcarxKx11HeadUnit.detected) EcarxKx11WheelKeys.KEY_WECHAT else 0
        val themeKeyCode = sp.getInt(KEY_THEME_SWITCH_KEY_CODE, defaultThemeKey)
        val primaryTrigger = GeelyClusterSwitchTrigger.fromName(
            sp.getString(KEY_CENTER_SWITCH_TRIGGER, null),
            defaultValue = GeelyClusterSwitchTrigger.SINGLE_CLICK,
        )
        val rawTelemetryTrigger = GeelyClusterSwitchTrigger.fromName(
            sp.getString(KEY_TELEMETRY_SWITCH_TRIGGER, null),
            defaultValue = GeelyClusterSwitchTrigger.LONG_PRESS,
        )
        val rawThemeTrigger = GeelyClusterSwitchTrigger.fromName(
            sp.getString(KEY_THEME_SWITCH_TRIGGER, null),
            defaultValue = GeelyClusterSwitchTrigger.DOUBLE_CLICK,
        )
        val sharesKey = primaryKeyCode > 0 && (telemetryKeyCode <= 0 || telemetryKeyCode == primaryKeyCode)
        val telemetryTrigger = if (sharesKey) {
            GeelyClusterSwitchTrigger.resolveNonConflicting(rawTelemetryTrigger, setOf(primaryTrigger))
        } else {
            rawTelemetryTrigger
        }
        val effectiveTelemetryKey = if (telemetryKeyCode > 0) telemetryKeyCode else primaryKeyCode
        val usedBySameKeyAsTheme = LinkedHashSet<GeelyClusterSwitchTrigger>()
        if (themeKeyCode > 0) {
            if (themeKeyCode == primaryKeyCode) usedBySameKeyAsTheme.add(primaryTrigger)
            if (themeKeyCode == effectiveTelemetryKey) usedBySameKeyAsTheme.add(telemetryTrigger)
        }
        val themeTrigger = GeelyClusterSwitchTrigger.resolveNonConflicting(rawThemeTrigger, usedBySameKeyAsTheme)
        val themeCycleStyles = GeelyClusterThemeStyle.parseCycleStyles(sp.getString(KEY_THEME_CYCLE_STYLES, null))
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
            albumLyricsEnabled = sp.getBoolean(KEY_ALBUM_LYRICS_ENABLED, true),
            albumOnlineLyricsEnabled = sp.getBoolean(KEY_ALBUM_ONLINE_LYRICS_ENABLED, true),
            albumLocalMediaLyricsEnabled = sp.getBoolean(KEY_ALBUM_LOCAL_MEDIA_LYRICS_ENABLED, true),
            bottomBarEnabled = sp.getBoolean(KEY_BOTTOM_BAR_ENABLED, true),
            dialColorMode = GeelyDialColorMode.fromName(sp.getString(KEY_DIAL_COLOR_MODE, null)),
            dialThemeStyle = GeelyClusterThemeStyle.fromName(sp.getString(KEY_DIAL_THEME_STYLE, null)),
            appearanceMode = GeelyClusterAppearanceMode.fromName(sp.getString(KEY_APPEARANCE_MODE, null)),
            launchThemeOnStart = sp.getBoolean(KEY_LAUNCH_THEME_ON_START, false),
            preferredDisplayId = sp.getInt(KEY_DISPLAY_ID, 2),
            centerMode = GeelyClusterCenterMode.fromName(sp.getString(KEY_CENTER_MODE, null)),
            centerSwitchKeyCode = primaryKeyCode,
            centerSwitchTrigger = primaryTrigger,
            primaryCycleModes = primaryModes,
            telemetrySwitchKeyCode = telemetryKeyCode,
            telemetrySwitchTrigger = telemetryTrigger,
            telemetryCycleModes = telemetryModes,
            themeSwitchKeyCode = themeKeyCode,
            themeSwitchTrigger = themeTrigger,
            themeCycleStyles = themeCycleStyles,
            tireAlertPopupEnabled = sp.getBoolean(KEY_TIRE_ALERT_POPUP_ENABLED, true),
            navAutoReturnSeconds = sp.getInt(
                KEY_NAV_AUTO_RETURN_SECONDS,
                GeelySelfDrawConfig.DEFAULT_NAV_AUTO_RETURN_SECONDS,
            ).coerceIn(0, 60),
            interceptVoiceKeyForSiri = sp.getBoolean(
                KEY_INTERCEPT_VOICE_KEY_FOR_SIRI,
                EcarxKx11HeadUnit.detected,
            ),
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
            .putBoolean(KEY_ALBUM_LYRICS_ENABLED, config.albumLyricsEnabled)
            .putBoolean(KEY_ALBUM_ONLINE_LYRICS_ENABLED, config.albumOnlineLyricsEnabled)
            .putBoolean(KEY_ALBUM_LOCAL_MEDIA_LYRICS_ENABLED, config.albumLocalMediaLyricsEnabled)
            .putBoolean(KEY_BOTTOM_BAR_ENABLED, config.bottomBarEnabled)
            .putString(KEY_DIAL_COLOR_MODE, config.dialColorMode.name)
            .putString(KEY_DIAL_THEME_STYLE, config.dialThemeStyle.name)
            .putString(KEY_APPEARANCE_MODE, config.appearanceMode.name)
            .putBoolean(KEY_LAUNCH_THEME_ON_START, config.launchThemeOnStart)
            .putInt(KEY_DISPLAY_ID, config.preferredDisplayId)
            .putString(KEY_CENTER_MODE, config.centerMode.name)
            .putInt(KEY_CENTER_SWITCH_KEY_CODE, config.centerSwitchKeyCode)
            .putString(KEY_CENTER_SWITCH_TRIGGER, config.centerSwitchTrigger.name)
            .putString(KEY_PRIMARY_CYCLE_MODES, GeelyClusterCenterMode.serializePrimaryModes(config.primaryCycleModes))
            .putInt(KEY_TELEMETRY_SWITCH_KEY_CODE, config.telemetrySwitchKeyCode)
            .putString(KEY_TELEMETRY_SWITCH_TRIGGER, config.telemetrySwitchTrigger.name)
            .putString(KEY_TELEMETRY_CYCLE_MODES, GeelyClusterCenterMode.serializeTelemetryModes(config.telemetryCycleModes))
            .putInt(KEY_THEME_SWITCH_KEY_CODE, config.themeSwitchKeyCode)
            .putString(KEY_THEME_SWITCH_TRIGGER, config.themeSwitchTrigger.name)
            .putString(KEY_THEME_CYCLE_STYLES, GeelyClusterThemeStyle.serializeCycleStyles(config.themeCycleStyles))
            .putString(KEY_CYCLE_MODES, GeelyClusterCenterMode.serializeCycleModes(config.cycleModes))
            .putBoolean(KEY_TIRE_ALERT_POPUP_ENABLED, config.tireAlertPopupEnabled)
            .putInt(KEY_NAV_AUTO_RETURN_SECONDS, config.navAutoReturnSeconds.coerceIn(0, 60))
            .putBoolean(KEY_INTERCEPT_VOICE_KEY_FOR_SIRI, config.interceptVoiceKeyForSiri)
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
