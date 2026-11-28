package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.update.UpdateRelease
import kotlin.math.roundToInt

/** Six focused setting categories tailored for Geely Xingyue L (KX11 / ECARX). */
internal enum class GeelyKx11SettingsCategory(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
) {
    CONNECTION("连接与启动", "无线/USB · 自动启动 · iPhone", R.drawable.ic_dp_connection),
    CLUSTER("全境仪表与 HUD", "原车/纯地图/机甲主题 · W-HUD · 歌词", R.drawable.ic_dp_dashboard),
    WHEEL("方向盘与操控", "语音键 Siri · 微信键切屏 · 手势", R.drawable.ic_dp_controls),
    DISPLAY("显示与画面", "60fps · 昼夜同步 · 缩放 · 系统栏", R.drawable.ic_dp_display),
    AUDIO("音频与定位", "音频焦点 · 通道 · CAN 轮速融合", R.drawable.ic_dp_audio),
    DIAGNOSTICS("诊断与关于", "R2 上传 · 备份 · 更新 · 原生设置", R.drawable.ic_dp_diagnostics),
}

/** Narrow bridge between [DiPlayActivity] and the standalone Xingyue L UI shell. */
internal interface GeelyKx11ShellHost {
    val activity: Activity
    val palette: DiPlayPalette
    val isNight: Boolean
    val setupError: String?
    val versionName: String
    val availableUpdate: UpdateRelease?
    val exportInProgress: Boolean

    fun connect(wireless: Boolean)
    fun disconnect()
    fun openProjection()
    fun choosePhone()
    fun openConnectionSetup()
    fun openBackup(forDiagnostics: Boolean)
    fun openSettings(category: GeelyKx11SettingsCategory? = null)
    fun openSetupGuide(fromSettings: Boolean)
    fun closeSetupGuide(connectNow: Boolean = false)
    fun openLegacyFullSettings()
    fun navigateBack()
    fun requestRender()

    fun toggleAppearance()
    fun applyFullscreenMode()
    fun checkForAppearanceChange()
    fun onLocationReportingChanged(enabled: Boolean)
    fun markReconnectNeeded()
    fun reconnectIfRunning()
    fun openPictureAdjustments()
    fun openBluetoothSettings()
    fun openAppPermissions()
    fun promptEnableUsbAutoConfirm()
    fun autoApplyPermissions()
    fun repairBootStart()
    fun saveReportToDownloads()
    fun chooseReportDestination()
    fun reportFileName(): String
    fun diagnosticReportText(appContext: Context): String
    fun openAvailableUpdate(release: UpdateRelease)
    fun createUpdateSectionView(): View
    fun createReconnectBarView(): View
    fun createCarButtonSection(parent: LinearLayout)
    fun bindStatusViews(
        statusView: TextView?,
        cardTitleView: TextView?,
        connectionHintView: TextView?,
        lastProblemView: TextView?,
        connectBtn: Button?,
        disconnectBtn: Button?,
        exportBtn: Button?,
    )
    fun bindScrollViews(contentScroll: ScrollView?, railScroll: ScrollView?)
}

/**
 * Dedicated B2 UI shell for Geely Xingyue L (KX11 / ECARX) head units.
 *
 * Activated automatically only when [GeelyVehicleDetector.isGeelyOrEcarx] matches and the user has
 * not switched to the full legacy settings view. All underlying persistence and services are shared
 * directly with upstream DiPlay so upstream merges stay nearly conflict-free.
 */
internal object GeelyKx11ShellUi {
    var selectedCategory: GeelyKx11SettingsCategory = GeelyKx11SettingsCategory.CLUSTER
    var setupStep: Int = 0

    private var wheelLearnRole: WheelZoomSettings.Role? = null
    private var clusterLearnTarget: EcarxKx11WheelKeys.ClusterLearnTarget? = null

    fun shouldUse(context: Context, forceLegacyFullUi: Boolean): Boolean =
        !forceLegacyFullUi && GeelyVehicleDetector.isGeelyOrEcarx(context)

    fun isWheelLearning(): Boolean = wheelLearnRole != null || clusterLearnTarget != null

    fun cancelAllWheelLearning() {
        wheelLearnRole = null
        clusterLearnTarget = null
        EcarxKx11WheelKeys.cancelLearning()
    }

    internal fun activeWheelLearnRole(): WheelZoomSettings.Role? = wheelLearnRole
    internal fun setWheelLearnRole(role: WheelZoomSettings.Role?) {
        wheelLearnRole = role
    }

    internal fun activeClusterLearnTarget(): EcarxKx11WheelKeys.ClusterLearnTarget? = clusterLearnTarget
    internal fun setClusterLearnTarget(target: EcarxKx11WheelKeys.ClusterLearnTarget?) {
        clusterLearnTarget = target
    }

    fun render(page: String, host: GeelyKx11ShellHost): View {
        EcarxKx11WheelKeys.ensureDefaultPresets(host.activity)
        val ui = GeelyKx11UiKit(host.activity, GeelyClusterUiColors.from(host.palette))
        val root = LinearLayout(host.activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.background)
        }
        val isWide = host.activity.resources.configuration.screenWidthDp >= 780

        root.addView(buildTopHeader(page, host, ui, isWide))

        when (page) {
            "setup" -> {
                val scroll = ScrollView(host.activity).apply {
                    isFillViewport = true
                    clipToPadding = false
                }
                val container = LinearLayout(host.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(if (isWide) 32 else 16), ui.dp(18), ui.dp(if (isWide) 32 else 16), ui.dp(24))
                }
                GeelyKx11SetupFlow.render(container, host, ui)
                scroll.addView(container)
                host.bindScrollViews(scroll, null)
                root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            "settings" -> {
                root.addView(buildSettingsShell(host, ui, isWide), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            else -> {
                val scroll = ScrollView(host.activity).apply {
                    isFillViewport = true
                    clipToPadding = false
                }
                val container = LinearLayout(host.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(if (isWide) 28 else 14), ui.dp(16), ui.dp(if (isWide) 28 else 14), ui.dp(24))
                }
                buildHomeContent(container, host, ui, isWide)
                scroll.addView(container)
                host.bindScrollViews(scroll, null)
                root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        return root
    }

    private fun buildTopHeader(
        page: String,
        host: GeelyKx11ShellHost,
        ui: GeelyKx11UiKit,
        isWide: Boolean,
    ): View {
        val bar = LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ui.colors.surface)
            setPadding(ui.dp(if (isWide) 24 else 14), ui.dp(12), ui.dp(if (isWide) 24 else 14), ui.dp(12))
        }
        val act = host.activity

        if (page != "home") {
            val backBtn = ui.actionButton("← 返回", heightDp = 42) {
                cancelAllWheelLearning()
                host.navigateBack()
            }
            bar.addView(
                backBtn,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(42)).apply {
                    marginEnd = ui.dp(12)
                },
            )
        }

        bar.addView(
            ImageView(act).apply {
                setImageResource(R.drawable.ic_carplay)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginEnd = ui.dp(12) },
        )

        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = when (page) {
            "settings" -> "星越L 设置中心"
            "setup" -> "星越L 首次引导"
            else -> "DiPlay · 星越L 专版"
        }
        titleRow.addView(
            TextView(act).apply {
                text = titleText
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isWide) 20f else 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        titleRow.addView(
            ui.badge(
                text = if (EcarxKx11HeadUnit.detected) "KX11 已识别" else "ECARX 车机",
                fg = ui.colors.accent,
                bg = ui.colors.selectedSurface,
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(10)
            },
        )
        titleCol.addView(titleRow)

        if (isWide) {
            val hasClusterDisplay = GeelyVehicleDetector.findClusterDisplay(act) != null
            val subtitle = buildString {
                append(if (EcarxWheelSpeed.present()) "ECARX CAN 轮速在线" else "ECARX 接口就绪")
                append(" · ")
                append(if (hasClusterDisplay) "1920×1080 全境仪表副屏已就绪" else "中控宽屏模式")
            }
            titleCol.addView(
                TextView(act).apply {
                    text = subtitle
                    setTextColor(ui.colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ui.dp(2), 0, 0)
                },
            )
        }
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Theme toggle button
        val themeBtn = ImageButton(act).apply {
            setImageResource(if (host.isNight) R.drawable.ic_dp_night else R.drawable.ic_dp_day)
            imageTintList = ColorStateList.valueOf(ui.colors.primaryText)
            background = ui.rippleBox(ui.colors.itemSurface, ui.colors.outline, 12)
            contentDescription = act.getString(
                if (host.isNight) R.string.settings_switch_to_light_appearance
                else R.string.settings_switch_to_dark_appearance,
            )
            setOnClickListener { host.toggleAppearance() }
        }
        bar.addView(
            themeBtn,
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(42)).apply { marginEnd = ui.dp(10) },
        )

        val homeDesktopBtn = ui.actionButton(
            text = if (page == "home") "车机桌面" else "返回首页",
            heightDp = 42,
        ) {
            if (page == "home") {
                runCatching { act.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
            } else {
                cancelAllWheelLearning()
                host.navigateBack()
            }
        }
        bar.addView(homeDesktopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(42)))

        // Bottom hairline divider
        val wrapper = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(bar)
        wrapper.addView(
            View(act).apply { setBackgroundColor(ui.colors.divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)),
        )
        return wrapper
    }

    private fun buildHomeContent(
        parent: LinearLayout,
        host: GeelyKx11ShellHost,
        ui: GeelyKx11UiKit,
        isWide: Boolean,
    ) {
        val act = host.activity
        val cfg = GeelyClusterPrefs.load(act)

        // Left column: CarPlay connection master card + 4 high-frequency quick tiles
        val leftCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val connCard = ui.surfaceCard(padH = 20, padV = 18)

        val topRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cardTitle = TextView(act).apply {
            text = act.getString(R.string.wireless_carplay)
            setTextColor(ui.colors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
        topRow.addView(cardTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fpsBadge = ui.badge(
            text = "${AirPlayPersistence.loadFps(act)} fps",
            fg = ui.colors.success,
            bg = ui.colors.successBg,
        )
        topRow.addView(fpsBadge)
        connCard.addView(topRow, ui.matchWrap(bottomMargin = ui.dp(8)))

        val statusView = TextView(act).apply {
            text = act.getString(R.string.ready_when_you_are)
            setTextColor(ui.colors.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isWide) 25f else 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        connCard.addView(statusView, ui.matchWrap(bottomMargin = ui.dp(6)))

        val hintText = when (AirPlayPersistence.loadWirelessHotspotMode(act)) {
            WirelessHotspotMode.EXISTING_WIFI -> act.getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> act.getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> act.getString(R.string.hotspot_hint_local)
            else -> act.getString(R.string.hotspot_hint_p2p)
        }
        val connHint = TextView(act).apply {
            text = hintText
            setTextColor(ui.colors.secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        connCard.addView(connHint, ui.matchWrap(bottomMargin = ui.dp(6)))

        val lastProblem = TextView(act).apply {
            setTextColor(ui.colors.warning)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            visibility = View.GONE
        }
        connCard.addView(lastProblem, ui.matchWrap(bottomMargin = ui.dp(12)))

        val connectBtn = ui.primaryButton(act.getString(R.string.connect_phone), heightDp = 56) {
            if (CarPlayBackgroundSession.hasSession()) host.openProjection()
            else host.connect(true)
        }
        connCard.addView(connectBtn, ui.matchHeight(56, bottomMargin = ui.dp(12)))

        // Secondary action row: USB / Choose iPhone / Setup or Disconnect
        val subRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val usbBtn = ui.actionButton("USB 有线连接", heightDp = 46) { host.connect(false) }
        val phoneBtn = ui.actionButton("切换 iPhone", heightDp = 46) { host.choosePhone() }
        val disconnectBtn = ui.actionButton(act.getString(R.string.disconnect), heightDp = 46) {
            host.disconnect()
        }.apply { visibility = View.GONE }
        val guideBtn = ui.actionButton("快速引导", heightDp = 46) {
            host.openSetupGuide(fromSettings = false)
        }

        subRow.addView(usbBtn, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { marginEnd = ui.dp(8) })
        subRow.addView(phoneBtn, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { marginEnd = ui.dp(8) })
        subRow.addView(guideBtn, LinearLayout.LayoutParams(0, ui.dp(46), 0.9f))
        connCard.addView(subRow, ui.matchWrap(bottomMargin = ui.dp(10)))
        connCard.addView(disconnectBtn, ui.matchHeight(46, bottomMargin = ui.dp(12)))

        // 4 Quick toggle tiles tailored for Xingyue L daily driving
        val tileGrid = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val locOn = AirPlayPersistence.loadLocationReportingEnabled(act)
        val quickTiles = listOf(
            Triple("常驻全境仪表", if (cfg.launchThemeOnStart) "已开启" else "仅 CarPlay", cfg.launchThemeOnStart) to {
                val updated = cfg.copy(launchThemeOnStart = !cfg.launchThemeOnStart)
                GeelyClusterPrefs.save(act, updated)
                GeelyClusterEnhancer.refreshConfig(act)
                host.requestRender()
            },
            Triple("W-HUD 导航", if (cfg.hudEnabled) "已开启" else "已关闭", cfg.hudEnabled) to {
                val updated = cfg.copy(hudEnabled = !cfg.hudEnabled)
                GeelyClusterPrefs.save(act, updated)
                GeelyClusterEnhancer.refreshConfig(act)
                host.requestRender()
            },
            Triple("GPS+CAN 轮速", if (locOn) "融合上报" else "已关闭", locOn) to {
                host.onLocationReportingChanged(!locOn)
            },
            Triple("方向盘语音键", if (cfg.interceptVoiceKeyForSiri) "短按 Siri" else "长按 Siri", cfg.interceptVoiceKeyForSiri) to {
                val updated = cfg.copy(interceptVoiceKeyForSiri = !cfg.interceptVoiceKeyForSiri)
                GeelyClusterPrefs.save(act, updated)
                EcarxKx11WheelKeys.settingsChanged()
                host.requestRender()
            },
        )
        for ((idx, entry) in quickTiles.withIndex()) {
            val (meta, onClick) = entry
            val (label, stateText, active) = meta
            val tile = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = ui.rippleBox(
                    fill = if (active) ui.colors.selectedSurface else ui.colors.itemSurface,
                    stroke = if (active) ui.colors.selectedOutline else ui.colors.outline,
                    cornerDp = 12,
                )
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            tile.addView(
                TextView(act).apply {
                    text = label
                    setTextColor(ui.colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            tile.addView(
                TextView(act).apply {
                    text = (if (active) "● " else "○ ") + stateText
                    setTextColor(if (active) ui.colors.accent else ui.colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setSingleLine(true)
                    setPadding(0, ui.dp(4), 0, 0)
                },
            )
            tileGrid.addView(
                tile,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) marginStart = ui.dp(8)
                },
            )
        }
        connCard.addView(tileGrid)

        host.setupError?.let { err ->
            connCard.addView(
                TextView(act).apply {
                    text = err
                    setTextColor(ui.colors.warning)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(0, ui.dp(10), 0, 0)
                },
            )
        }
        leftCol.addView(connCard, ui.matchWrap())

        // Right column: 星越L 全境仪表实时缩略舱 + 设置中心与车况入口
        val rightCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val clusterCard = ui.surfaceCard(padH = 16, padV = 14)

        val clusterHeader = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clusterTitleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        clusterTitleCol.addView(
            TextView(act).apply {
                text = "星越L 全境仪表"
                setTextColor(ui.colors.accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        val homeClusterSub = when {
            cfg.dialThemeStyle.isOemNative -> "主题：${cfg.dialThemeStyle.shortLabel} · 保留 W-HUD 与经典导航卡"
            cfg.dialThemeStyle.isPureMap -> "主题：${cfg.dialThemeStyle.shortLabel} · 下方保留音乐 · 无图回落原车"
            else -> "主题：${cfg.dialThemeStyle.shortLabel} · 视图：${cfg.centerMode.shortLabel}"
        }
        clusterTitleCol.addView(
            TextView(act).apply {
                text = homeClusterSub
                setTextColor(ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, ui.dp(2), 0, 0)
            },
        )
        clusterHeader.addView(clusterTitleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        clusterHeader.addView(
            ui.actionButton("调教仪表 →", heightDp = 36) {
                host.openSettings(GeelyKx11SettingsCategory.CLUSTER)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)),
        )
        clusterCard.addView(clusterHeader, ui.matchWrap(bottomMargin = ui.dp(10)))

        // Live composite cluster thumbnail reusing GeelyCompositePreviewView
        val preview = GeelyCompositePreviewView(act).apply {
            config = cfg
            previewRouteActive = true
        }
        clusterCard.addView(preview, ui.matchWrap(bottomMargin = ui.dp(10)))

        // Quick center-view mode pills (2 neat rows)
        val allModes = GeelyClusterCenterMode.entries
        val rows = listOf(allModes.take(4), allModes.drop(4))
        for ((rIdx, rowModes) in rows.withIndex()) {
            val pillRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((cIdx, mode) in rowModes.withIndex()) {
                val active = cfg.centerMode == mode
                val btn = ui.actionButton(mode.shortLabel, selected = active, heightDp = 34, textSizeSp = 12f) {
                    val prev = cfg.centerMode
                    val updated = cfg.copy(centerMode = mode)
                    GeelyClusterPrefs.save(act, updated)
                    GeelyClusterEnhancer.refreshConfig(act)
                    GeelyClusterEnhancer.onCenterModeChangedByUser(act, prev, mode)
                    host.requestRender()
                }
                pillRow.addView(
                    btn,
                    LinearLayout.LayoutParams(0, ui.dp(34), 1f).apply {
                        if (cIdx > 0) marginStart = ui.dp(6)
                    },
                )
            }
            clusterCard.addView(
                pillRow,
                ui.matchWrap(bottomMargin = if (rIdx == 0) ui.dp(6) else 0),
            )
        }
        rightCol.addView(clusterCard, ui.matchWrap(bottomMargin = ui.dp(12)))

        // Bottom 2 navigation cards: Settings Center + Telemetry Overview
        val navRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val settingsEntry = buildEntryCard(
            act = act,
            ui = ui,
            title = "星越L 设置中心",
            subtitle = "6 大精简分类",
            highlighted = true,
        ) { host.openSettings(selectedCategory) }
        val telemetryEntry = buildEntryCard(
            act = act,
            ui = ui,
            title = "车况与胎压总览",
            subtitle = "CAN / TPMS 实时读数",
            highlighted = false,
        ) {
            GeelyVehicleStatusDialog.show(act) { chosenMode ->
                val cur = GeelyClusterPrefs.load(act)
                val prev = cur.centerMode
                GeelyClusterPrefs.save(act, cur.copy(centerMode = chosenMode))
                GeelyClusterEnhancer.refreshConfig(act)
                GeelyClusterEnhancer.onCenterModeChangedByUser(act, prev, chosenMode)
                host.requestRender()
            }
        }
        navRow.addView(settingsEntry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) })
        navRow.addView(telemetryEntry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rightCol.addView(navRow, ui.matchWrap())

        host.availableUpdate?.let { release ->
            val updateBtn = ui.actionButton(
                text = "发现新版本 ${release.tagName} · 点击查看更新",
                selected = true,
                heightDp = 42,
            ) { host.openAvailableUpdate(release) }
            rightCol.addView(updateBtn, ui.matchHeight(42, topMargin = ui.dp(10)))
        }

        if (isWide) {
            val split = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            split.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.32f).apply { marginEnd = ui.dp(18) })
            split.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            parent.addView(split)
        } else {
            parent.addView(leftCol, ui.matchWrap(bottomMargin = ui.dp(14)))
            parent.addView(rightCol, ui.matchWrap())
        }

        host.bindStatusViews(
            statusView = statusView,
            cardTitleView = cardTitle,
            connectionHintView = connHint,
            lastProblemView = lastProblem,
            connectBtn = connectBtn,
            disconnectBtn = disconnectBtn,
            exportBtn = null,
        )
    }

    private fun buildEntryCard(
        act: Activity,
        ui: GeelyKx11UiKit,
        title: String,
        subtitle: String,
        highlighted: Boolean,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rippleBox(
                fill = ui.colors.surface,
                stroke = if (highlighted) ui.colors.selectedOutline else ui.colors.outline,
                cornerDp = 16,
            )
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val textCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(
            TextView(act).apply {
                text = title
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setSingleLine(true)
            },
        )
        textCol.addView(
            TextView(act).apply {
                text = subtitle
                setTextColor(ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setSingleLine(true)
                setPadding(0, ui.dp(2), 0, 0)
            },
        )
        card.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            TextView(act).apply {
                text = "→"
                setTextColor(if (highlighted) ui.colors.accent else ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        return card
    }

    private fun buildSettingsShell(
        host: GeelyKx11ShellHost,
        ui: GeelyKx11UiKit,
        isWide: Boolean,
    ): View {
        val act = host.activity
        val shell = LinearLayout(act).apply {
            orientation = if (isWide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }

        // Left rail (wide) or top horizontal selector (narrow)
        val railScroll = ScrollView(act).apply {
            setBackgroundColor(ui.colors.surface)
            clipToPadding = false
        }
        val railList = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(16))
        }
        for (cat in GeelyKx11SettingsCategory.entries) {
            val active = selectedCategory == cat
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ui.rippleBox(
                    fill = if (active) ui.colors.selectedSurface else Color.TRANSPARENT,
                    stroke = if (active) ui.colors.selectedOutline else Color.TRANSPARENT,
                    cornerDp = 12,
                )
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                isClickable = true
                isFocusable = true
                isSelected = active
                setOnClickListener {
                    if (selectedCategory != cat) {
                        cancelAllWheelLearning()
                        selectedCategory = cat
                        host.requestRender()
                    }
                }
            }
            item.addView(
                ImageView(act).apply {
                    setImageResource(cat.iconRes)
                    imageTintList = ColorStateList.valueOf(if (active) ui.colors.accent else ui.colors.secondaryText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(10) },
            )
            val textCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(
                TextView(act).apply {
                    text = cat.title
                    setTextColor(if (active) ui.colors.primaryText else ui.colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                    setSingleLine(true)
                },
            )
            textCol.addView(
                TextView(act).apply {
                    text = cat.subtitle
                    setTextColor(ui.colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, ui.dp(2), 0, 0)
                },
            )
            item.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            railList.addView(item, ui.matchWrap(bottomMargin = ui.dp(6)))
        }
        railScroll.addView(railList)

        val contentScroll = ScrollView(act).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val contentCol = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(if (isWide) 24 else 14), ui.dp(16), ui.dp(if (isWide) 24 else 14), ui.dp(28))
        }

        // Reconnect pending banner (reuses DiPlayActivity's live reconnectBar)
        val reconnectBanner = host.createReconnectBarView()
        contentCol.addView(
            reconnectBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(14)
            },
        )

        // Category header
        contentCol.addView(
            TextView(act).apply {
                text = selectedCategory.title
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            ui.matchWrap(bottomMargin = ui.dp(4)),
        )
        contentCol.addView(
            TextView(act).apply {
                text = selectedCategory.subtitle
                setTextColor(ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            },
            ui.matchWrap(bottomMargin = ui.dp(14)),
        )

        var exportBtnRef: Button? = null
        when (selectedCategory) {
            GeelyKx11SettingsCategory.CONNECTION ->
                GeelyKx11SettingsSections.renderConnection(contentCol, host, ui)
            GeelyKx11SettingsCategory.CLUSTER ->
                GeelyClusterEnhancer.appendSettingsCard(act, contentCol, host.palette, null) {
                    host.requestRender()
                }
            GeelyKx11SettingsCategory.WHEEL ->
                GeelyKx11SettingsSections.renderWheelAndControls(contentCol, host, ui)
            GeelyKx11SettingsCategory.DISPLAY ->
                GeelyKx11SettingsSections.renderDisplay(contentCol, host, ui)
            GeelyKx11SettingsCategory.AUDIO ->
                GeelyKx11SettingsSections.renderAudioAndLocation(contentCol, host, ui)
            GeelyKx11SettingsCategory.DIAGNOSTICS -> {
                exportBtnRef = GeelyKx11SettingsSections.renderDiagnosticsAndAbout(contentCol, host, ui)
            }
        }

        contentScroll.addView(contentCol)
        host.bindStatusViews(null, null, null, null, null, null, exportBtnRef)
        host.bindScrollViews(contentScroll, null)

        if (isWide) {
            val railWidthDp = if (act.resources.configuration.screenWidthDp >= 1000) 248 else 220
            shell.addView(railScroll, LinearLayout.LayoutParams(ui.dp(railWidthDp), ViewGroup.LayoutParams.MATCH_PARENT))
            shell.addView(
                View(act).apply { setBackgroundColor(ui.colors.divider) },
                LinearLayout.LayoutParams(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            shell.addView(contentScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            shell.addView(railScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(168)))
            shell.addView(
                View(act).apply { setBackgroundColor(ui.colors.divider) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)),
            )
            shell.addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return shell
    }
}

/** Shared light/dark UI component helpers matching GeelyClusterSettingsUi aesthetics. */
internal class GeelyKx11UiKit(
    val activity: Activity,
    val colors: GeelyClusterUiColors,
) {
    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    fun matchWrap(topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }

    fun matchHeight(heightDp: Int, topMargin: Int = 0, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }

    fun roundedBox(fill: Int, stroke: Int, cornerDp: Int = 14, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(cornerDp).toFloat()
            if (stroke != Color.TRANSPARENT && strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    fun rippleBox(fill: Int, stroke: Int, cornerDp: Int = 12): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(colors.ripple),
            roundedBox(fill, stroke, cornerDp),
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(cornerDp).toFloat()
            },
        )

    fun surfaceCard(padH: Int = 18, padV: Int = 16): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBox(colors.surface, colors.outline, cornerDp = 18)
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun subCard(padH: Int = 14, padV: Int = 12): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBox(colors.itemSurface, colors.outline, cornerDp = 12)
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun sectionTitle(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(colors.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.06f
    }

    fun hintText(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(colors.secondaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    fun badge(text: String, fg: Int, bg: Int): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = roundedBox(bg, ColorUtils.setAlphaComponent(fg, 96), cornerDp = 999)
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }

    fun primaryButton(text: String, heightDp: Int = 46, onClick: () -> Unit): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        setTextColor(colors.onAccent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rippleBox(colors.accent, Color.TRANSPARENT, 12)
        setPadding(dp(16), 0, dp(16), 0)
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        setOnClickListener { onClick() }
    }

    fun actionButton(
        text: String,
        selected: Boolean = false,
        heightDp: Int = 42,
        textSizeSp: Float = 13.5f,
        onClick: () -> Unit,
    ): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (selected) colors.accent else colors.primaryText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = rippleBox(
            fill = if (selected) colors.selectedSurface else colors.itemSurface,
            stroke = if (selected) colors.selectedOutline else colors.outline,
            cornerDp = 12,
        )
        setPadding(dp(14), 0, dp(14), 0)
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }

    fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        needsReconnectBadge: Boolean = false,
        onChanged: (Boolean) -> Unit,
    ): Switch {
        val box = subCard()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(activity).apply {
                text = title
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
        )
        if (needsReconnectBadge) {
            titleRow.addView(
                badge("需重连", colors.warning, colors.warningBg),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                },
            )
        }
        textCol.addView(titleRow)
        if (subtitle.isNotBlank()) {
            textCol.addView(
                TextView(activity).apply {
                    text = subtitle
                    setTextColor(colors.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, dp(3), 0, 0)
                },
            )
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })

        val sw = Switch(activity).apply {
            isChecked = checked
            contentDescription = title
            SettingsWidgets.applyLargeSwitchStyle(
                switch = this,
                thumbOn = colors.accent,
                thumbOff = colors.secondaryText,
                trackOn = colors.accent,
                trackOff = colors.secondaryText,
            )
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        row.addView(sw)
        box.isClickable = true
        box.setOnClickListener { sw.toggle() }
        box.addView(row)
        parent.addView(box, matchWrap(bottomMargin = dp(10)))
        return sw
    }

    fun <T> addSegmentedChoice(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelect: (T) -> Unit,
    ) {
        val box = subCard()
        box.addView(
            TextView(activity).apply {
                text = title
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            matchWrap(bottomMargin = if (subtitle.isNotBlank()) dp(2) else dp(8)),
        )
        if (subtitle.isNotBlank()) {
            box.addView(hintText(subtitle), matchWrap(bottomMargin = dp(8)))
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for ((idx, pair) in options.withIndex()) {
            val (value, label) = pair
            val btn = actionButton(label, selected = value == selected, heightDp = 38, textSizeSp = 13f) {
                if (value != selected) onSelect(value)
            }
            row.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                },
            )
        }
        box.addView(row)
        parent.addView(box, matchWrap(bottomMargin = dp(10)))
    }

    fun addSliderRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        values: List<Int>,
        current: Int,
        formatValue: (Int) -> String,
        onCommit: (Int) -> Unit,
    ) {
        val box = subCard()
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(activity).apply {
                text = title
                setTextColor(colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueLabel = TextView(activity).apply {
            text = formatValue(current)
            setTextColor(colors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        header.addView(valueLabel)
        box.addView(header, matchWrap(bottomMargin = dp(6)))

        val initialIndex = values.indexOf(current).let { idx ->
            if (idx >= 0) idx else values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        }
        val seekBar = SeekBar(activity).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = initialIndex
            progressTintList = ColorStateList.valueOf(colors.accent)
            thumbTintList = ColorStateList.valueOf(colors.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = values.getOrNull(progress) ?: return
                    valueLabel.text = formatValue(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = values.getOrNull(sb?.progress ?: initialIndex) ?: return
                    onCommit(v)
                }
            })
        }
        box.addView(seekBar, matchWrap(bottomMargin = if (subtitle.isNotBlank()) dp(4) else 0))
        if (subtitle.isNotBlank()) {
            box.addView(hintText(subtitle))
        }
        parent.addView(box, matchWrap(bottomMargin = dp(10)))
    }
}
