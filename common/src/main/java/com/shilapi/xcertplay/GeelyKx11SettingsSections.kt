package com.shilapi.xcertplay

import android.app.TimePickerDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.media.NavigationAudioGain
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.update.UpdateAvailability
import java.util.Locale

/**
 * Renders the 5 streamlined non-cluster categories inside the Geely Xingyue L (KX11) settings shell.
 * The 6th category ([GeelyKx11SettingsCategory.CLUSTER]) directly embeds [GeelyClusterEnhancer].
 */
internal object GeelyKx11SettingsSections {

    // -------------------------------------------------------------------------
    // 1. 连接与启动
    // -------------------------------------------------------------------------
    fun renderConnection(parent: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity

        // Card 1: Wireless & Setup
        val setupCard = ui.surfaceCard()
        setupCard.addView(ui.sectionTitle("无线网络与连接向导"), ui.matchWrap(bottomMargin = ui.dp(10)))

        val modeLabel = when (AirPlayPersistence.loadWirelessHotspotMode(act)) {
            WirelessHotspotMode.MANUAL -> "车机自带热点（推荐）"
            WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct (P2P)"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "临时热点"
            WirelessHotspotMode.EXISTING_WIFI -> "已有局域网 Wi-Fi"
        }
        setupCard.addView(
            ui.hintText("当前无线方式：$modeLabel。已自动精简 BYD ADB 与 DiLink 专属向导，仅保留星越L常用连接选项。"),
            ui.matchWrap(bottomMargin = ui.dp(10)),
        )
        val btnRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(
            ui.primaryButton("配置无线热点 / 连接详情", heightDp = 44) { host.openConnectionSetup() },
            LinearLayout.LayoutParams(0, ui.dp(44), 1.2f).apply { marginEnd = ui.dp(8) },
        )
        btnRow.addView(
            ui.actionButton("重新运行 3 步引导", heightDp = 44) { host.openSetupGuide(fromSettings = true) },
            LinearLayout.LayoutParams(0, ui.dp(44), 1f),
        )
        setupCard.addView(btnRow, ui.matchWrap(bottomMargin = ui.dp(10)))

        val phoneName = if (DiPlayPreferences.phoneAddress(act) != null) DiPlayPreferences.phoneName(act) else "尚未选择"
        setupCard.addView(
            ui.actionButton("选择默认 iPhone · $phoneName", heightDp = 44) { host.choosePhone() },
            ui.matchHeight(44),
        )
        parent.addView(setupCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 2: Auto connect & boot
        val autoCard = ui.surfaceCard()
        autoCard.addView(ui.sectionTitle("自动连接与自启动"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = autoCard,
            title = "打开 DiPlay 时自动连接",
            subtitle = "进入软件后立即按默认方式拉起 CarPlay 连接",
            checked = DiPlayPreferences.autoConnect(act),
        ) { enabled ->
            DiPlayPreferences.saveAutoConnect(act, enabled)
        }

        ui.addSwitchRow(
            parent = autoCard,
            title = "iPhone 蓝牙连上车机时自动连接",
            subtitle = "上车蓝牙握手成功后自动触发无线 CarPlay",
            checked = DiPlayPreferences.connectOnPhoneBluetooth(act),
        ) { enabled ->
            DiPlayPreferences.saveConnectOnPhoneBluetooth(act, enabled)
            if (enabled && DiPlayPreferences.phoneAddress(act) == null) {
                host.choosePhone()
            }
        }

        ui.addSegmentedChoice(
            parent = autoCard,
            title = "默认连接方式",
            subtitle = "自动连接时优先使用的通道",
            options = listOf(
                DefaultConnectionMode.LAST_USED to "上次使用",
                DefaultConnectionMode.WIRELESS to "无线优先",
                DefaultConnectionMode.USB to "USB 有线",
            ),
            selected = DiPlayPreferences.defaultConnectionMode(act),
        ) { mode ->
            DiPlayPreferences.saveDefaultConnectionMode(act, mode)
            host.requestRender()
        }

        ui.addSwitchRow(
            parent = autoCard,
            title = "随车机开机自动启动",
            subtitle = "通电开机后后台自启（取决于车机系统自启白名单）",
            checked = AirPlayPersistence.loadAutoStartOnBoot(act),
        ) { enabled ->
            AirPlayPersistence.saveAutoStartOnBoot(act, enabled)
        }
        autoCard.addView(
            ui.actionButton("修复车机开机自启权限", heightDp = 42) { host.repairBootStart() },
            ui.matchHeight(42),
        )
        parent.addView(autoCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 3: USB & Permissions
        val usbCard = ui.surfaceCard()
        usbCard.addView(ui.sectionTitle("USB 有线与系统权限"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = usbCard,
            title = "插入 iPhone USB 时自动打开",
            subtitle = "检测到数据口插入 iPhone 时自动切入 CarPlay",
            checked = UsbAutoLaunchControl.isEnabled(act),
        ) { enabled ->
            UsbAutoLaunchControl.setEnabled(act, enabled)
        }

        val usbConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(act)
        ui.addSwitchRow(
            parent = usbCard,
            title = "自动确认 USB 授权弹窗",
            subtitle = if (usbConfirmActive) "无障碍自动确认服务已就绪" else "开启后无需每次插线手动点确定",
            checked = usbConfirmActive,
        ) { enabled ->
            if (enabled) host.promptEnableUsbAutoConfirm()
            else {
                UsbAutoConfirmService.openSettings(act)
                host.requestRender()
            }
        }
        if (!usbConfirmActive) {
            usbCard.addView(
                ui.primaryButton(act.getString(R.string.btn_auto_apply_permissions), heightDp = 42) {
                    host.autoApplyPermissions()
                },
                ui.matchHeight(42, bottomMargin = ui.dp(10)),
            )
        }

        ui.addSegmentedChoice(
            parent = usbCard,
            title = "USB 有线充电电流",
            subtitle = "如遇有线连接时因车机口供电不足断连，可适当降低充电电流",
            options = listOf(
                UsbChargingCurrent.NORMAL to "正常",
                UsbChargingCurrent.REDUCED to "轻度降流",
                UsbChargingCurrent.MEDIUM to "中度降流",
                UsbChargingCurrent.LOW to "低电流",
            ),
            selected = AirPlayPersistence.loadUsbChargingCurrent(act),
        ) { current ->
            AirPlayPersistence.saveUsbChargingCurrent(act, current)
            if (!AirPlayPersistence.loadWirelessEnabled(act)) host.markReconnectNeeded()
            host.requestRender()
        }

        val permRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        permRow.addView(
            ui.actionButton("系统蓝牙设置", heightDp = 42) { host.openBluetoothSettings() },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(8) },
        )
        permRow.addView(
            ui.actionButton("应用权限管理", heightDp = 42) { host.openAppPermissions() },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f),
        )
        usbCard.addView(permRow)
        parent.addView(usbCard, ui.matchWrap(bottomMargin = ui.dp(14)))
    }

    // -------------------------------------------------------------------------
    // 3. 方向盘与操控（统一收口原「车辆」与「吉利方向盘」）
    // -------------------------------------------------------------------------
    fun renderWheelAndControls(parent: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity
        var cfg = GeelyClusterPrefs.load(act)

        // Card 1: KX11 Voice Key -> Siri
        val siriCard = ui.surfaceCard()
        val siriTop = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        siriTop.addView(ui.sectionTitle("KX11 方向盘语音键（Siri）"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        siriTop.addView(ui.badge("直连 geely.inputservice", ui.colors.success, ui.colors.successBg))
        siriCard.addView(siriTop, ui.matchWrap(bottomMargin = ui.dp(10)))

        val siriEnabled = WheelZoomSettings.siriKey(act)
        val currentSiriKey = WheelZoomSettings.key(act, WheelZoomSettings.Role.SIRI)
        val siriBoundToVoice = siriEnabled &&
            EcarxKx11WheelKeys.isGeelyInputKey(currentSiriKey) &&
            currentSiriKey?.code == EcarxKx11WheelKeys.KEY_VOICE_ASSIST

        val siriSummary = when {
            !siriEnabled -> "Siri 方向盘快捷键未启用"
            currentSiriKey != null && EcarxKx11WheelKeys.isGeelyInputKey(currentSiriKey) ->
                "当前绑定：${formatGeelyWheelKey(currentSiriKey.code)}"
            currentSiriKey != null -> "当前绑定：按键 ${currentSiriKey.code}"
            else -> "尚未绑定 Siri 按键"
        }

        val siriActionRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        siriActionRow.addView(
            TextView(act).apply {
                text = siriSummary
                setTextColor(if (siriBoundToVoice) ui.colors.accent else ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) },
        )
        val learningSiri = GeelyKx11ShellUi.activeWheelLearnRole() == WheelZoomSettings.Role.SIRI
        siriActionRow.addView(
            ui.actionButton(if (siriBoundToVoice) "已绑原车语音键" else "一键绑原车语音键", selected = siriBoundToVoice, heightDp = 38) {
                WheelZoomSettings.setSiriKey(act, true)
                WheelZoomSettings.setSiriDoublePress(act, false)
                WheelZoomSettings.assign(
                    act,
                    WheelZoomSettings.Role.SIRI,
                    EcarxKx11WheelKeys.toWheelKey(EcarxKx11WheelKeys.KEY_VOICE_ASSIST),
                )
                EcarxKx11WheelKeys.settingsChanged()
                Toast.makeText(act, "已将原车语音键 (200231) 设为 Siri", Toast.LENGTH_SHORT).show()
                host.requestRender()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)).apply { marginEnd = ui.dp(6) },
        )
        siriActionRow.addView(
            if (learningSiri) {
                ui.primaryButton("请按方向盘…", heightDp = 38) {
                    GeelyKx11ShellUi.cancelAllWheelLearning()
                    host.requestRender()
                }
            } else {
                ui.actionButton("学习其他键", heightDp = 38) {
                    startRoleLearning(act, WheelZoomSettings.Role.SIRI, host)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)),
        )
        siriCard.addView(siriActionRow, ui.matchWrap(bottomMargin = ui.dp(10)))

        val interceptSub = when {
            !cfg.interceptVoiceKeyForSiri -> "关闭时短按保留原车「你好吉利」，长按呼出 Siri"
            !siriBoundToVoice -> "请先点击「一键绑原车语音键」，连接 CarPlay 时再自动接管短按"
            EcarxKx11WheelKeys.lastVoiceInterceptStatus == EcarxKx11WheelKeys.VoiceInterceptStatus.GRANTED ->
                "CarPlay 已接管语音键短按（直呼 Siri），断开自动还原原车语音"
            EcarxKx11WheelKeys.lastVoiceInterceptStatus == EcarxKx11WheelKeys.VoiceInterceptStatus.REJECTED ->
                "当前车机固件拒绝拦截短按，已自动退回长按呼出 Siri"
            else -> "连接 CarPlay 时自动接管原车语音键短按直呼 Siri，断开自动还原"
        }
        ui.addSwitchRow(
            parent = siriCard,
            title = "CarPlay 期间覆盖原车语音键（短按呼 Siri）",
            subtitle = interceptSub,
            checked = cfg.interceptVoiceKeyForSiri,
        ) { checked ->
            cfg = cfg.copy(interceptVoiceKeyForSiri = checked)
            GeelyClusterPrefs.save(act, cfg)
            EcarxKx11WheelKeys.settingsChanged()
            host.requestRender()
        }

        ui.addSegmentedChoice(
            parent = siriCard,
            title = "Siri 按键触发方式",
            subtitle = "选择单按立即触发或双击触发",
            options = listOf(false to "单按触发", true to "双击触发"),
            selected = WheelZoomSettings.siriDoublePress(act),
        ) { doublePress ->
            WheelZoomSettings.setSiriDoublePress(act, doublePress)
            EcarxKx11WheelKeys.settingsChanged()
            host.requestRender()
        }

        // Play / Pause optional binding
        val playPauseOn = WheelZoomSettings.mediaPlayPause(act)
        val playPauseKey = WheelZoomSettings.key(act, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE)
        val learningPlay = GeelyKx11ShellUi.activeWheelLearnRole() == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE
        ui.addSwitchRow(
            parent = siriCard,
            title = "方向盘播放 / 暂停快捷键",
            subtitle = if (playPauseKey != null && EcarxKx11WheelKeys.isGeelyInputKey(playPauseKey)) {
                "当前绑定：${formatGeelyWheelKey(playPauseKey.code)}"
            } else {
                "可选：将某颗方向盘按键映射为 CarPlay 播放/暂停"
            },
            checked = playPauseOn,
        ) { checked ->
            WheelZoomSettings.setMediaPlayPause(act, checked)
            EcarxKx11WheelKeys.settingsChanged()
            host.requestRender()
        }
        if (playPauseOn) {
            siriCard.addView(
                if (learningPlay) {
                    ui.primaryButton("请按下方向盘上的目标按键（点击取消）", heightDp = 40) {
                        GeelyKx11ShellUi.cancelAllWheelLearning()
                        host.requestRender()
                    }
                } else {
                    ui.actionButton("学习播放 / 暂停按键", heightDp = 40) {
                        startRoleLearning(act, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE, host)
                    }
                },
                ui.matchHeight(40),
            )
        }
        parent.addView(siriCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 2: Cluster theme, primary & telemetry peek keys
        val clusterKeyCard = ui.surfaceCard()
        clusterKeyCard.addView(ui.sectionTitle("全境仪表主题、视图与车况快捷键"), ui.matchWrap(bottomMargin = ui.dp(10)))

        val learningPrimary = GeelyKx11ShellUi.activeClusterLearnTarget() == EcarxKx11WheelKeys.ClusterLearnTarget.PRIMARY
        val primaryBox = ui.subCard()
        val primaryTop = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val primaryInfo = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        primaryInfo.addView(
            TextView(act).apply {
                text = "主视图切换键（地图 / 专辑 / 时钟）"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            },
        )
        primaryInfo.addView(
            TextView(act).apply {
                text = "当前：${formatGeelyWheelKey(cfg.centerSwitchKeyCode)} · ${cfg.centerSwitchTrigger.shortLabel}"
                setTextColor(if (cfg.centerSwitchKeyCode > 0) ui.colors.accent else ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, ui.dp(2), 0, 0)
            },
        )
        primaryTop.addView(primaryInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) })
        primaryTop.addView(
            if (learningPrimary) {
                ui.primaryButton("请按方向盘…", heightDp = 38) {
                    GeelyKx11ShellUi.cancelAllWheelLearning()
                    host.requestRender()
                }
            } else {
                ui.actionButton("学习按键", selected = cfg.centerSwitchKeyCode > 0, heightDp = 38) {
                    startClusterLearning(act, EcarxKx11WheelKeys.ClusterLearnTarget.PRIMARY, host)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)),
        )
        primaryBox.addView(primaryTop, ui.matchWrap(bottomMargin = ui.dp(8)))

        val primaryTrigRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((idx, trig) in listOf(
            GeelyClusterSwitchTrigger.SINGLE_CLICK to "单击切换",
            GeelyClusterSwitchTrigger.LONG_PRESS to "长按切换",
            GeelyClusterSwitchTrigger.DOUBLE_CLICK to "双击切换",
        ).withIndex()) {
            primaryTrigRow.addView(
                ui.actionButton(trig.second, selected = cfg.centerSwitchTrigger == trig.first, heightDp = 36, textSizeSp = 12.5f) {
                    val adjTel = if (cfg.sharesTelemetryKeyWithPrimary && cfg.telemetrySwitchTrigger == trig.first) {
                        GeelyClusterSwitchTrigger.resolveNonConflicting(cfg.telemetrySwitchTrigger, setOf(trig.first))
                    } else cfg.telemetrySwitchTrigger
                    val adjTheme = if (cfg.sharesThemeKeyWithPrimary) {
                        val used = linkedSetOf(trig.first)
                        if (cfg.sharesTelemetryKeyWithPrimary) used.add(adjTel)
                        GeelyClusterSwitchTrigger.resolveNonConflicting(cfg.themeSwitchTrigger, used)
                    } else cfg.themeSwitchTrigger
                    GeelyClusterPrefs.save(
                        act,
                        cfg.copy(
                            centerSwitchTrigger = trig.first,
                            telemetrySwitchTrigger = adjTel,
                            themeSwitchTrigger = adjTheme,
                        ),
                    )
                    GeelyClusterEnhancer.refreshConfig(act)
                    host.requestRender()
                },
                LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply { if (idx > 0) marginStart = ui.dp(6) },
            )
        }
        primaryBox.addView(primaryTrigRow)
        clusterKeyCard.addView(primaryBox, ui.matchWrap(bottomMargin = ui.dp(10)))

        val learningTel = GeelyKx11ShellUi.activeClusterLearnTarget() == EcarxKx11WheelKeys.ClusterLearnTarget.TELEMETRY
        val telBox = ui.subCard()
        val telTop = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val telInfo = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        telInfo.addView(
            TextView(act).apply {
                text = "车况临时查看键（行程 / 胎压 / 综合车况）"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            },
        )
        val telDesc = when {
            cfg.telemetrySwitchKeyCode > 0 -> "独立按键：${formatGeelyWheelKey(cfg.telemetrySwitchKeyCode)}"
            cfg.centerSwitchKeyCode > 0 -> "共用主视图键：${formatGeelyWheelKey(cfg.centerSwitchKeyCode)}"
            else -> "未绑定"
        }
        telInfo.addView(
            TextView(act).apply {
                text = "$telDesc · ${cfg.telemetrySwitchTrigger.shortLabel}"
                setTextColor(if (cfg.effectiveTelemetrySwitchKeyCode > 0) ui.colors.accent else ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, ui.dp(2), 0, 0)
            },
        )
        telTop.addView(telInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) })
        telTop.addView(
            if (learningTel) {
                ui.primaryButton("请按方向盘…", heightDp = 38) {
                    GeelyKx11ShellUi.cancelAllWheelLearning()
                    host.requestRender()
                }
            } else {
                ui.actionButton(if (cfg.telemetrySwitchKeyCode > 0) "改绑独立键" else "单独绑键", heightDp = 38) {
                    startClusterLearning(act, EcarxKx11WheelKeys.ClusterLearnTarget.TELEMETRY, host)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)),
        )
        telBox.addView(telTop, ui.matchWrap(bottomMargin = ui.dp(8)))

        ui.addSegmentedChoice(
            parent = clusterKeyCard,
            title = "车况页无操作自动关闭时长",
            subtitle = "超时自动退回导航地图 / 专辑 / 时钟主视图",
            options = GeelySelfDrawConfig.NAV_AUTO_RETURN_OPTIONS.map { sec ->
                sec to if (sec == 0) "不自动关" else "${sec} 秒"
            },
            selected = cfg.navAutoReturnSeconds,
        ) { sec ->
            GeelyClusterPrefs.save(act, cfg.copy(navAutoReturnSeconds = sec))
            GeelyClusterEnhancer.refreshConfig(act)
            GeelyClusterEnhancer.onCenterModeChangedByUser(act, null, cfg.centerMode)
            host.requestRender()
        }
        clusterKeyCard.addView(telBox, ui.matchWrap(bottomMargin = ui.dp(10)))

        // Theme switch wheel key subcard
        val learningTheme = GeelyKx11ShellUi.activeClusterLearnTarget() == EcarxKx11WheelKeys.ClusterLearnTarget.THEME
        val themeBox = ui.subCard()
        val themeTop = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val themeInfo = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        themeInfo.addView(
            TextView(act).apply {
                text = "仪表主题切换键（原车 / 纯地图 / 机甲）"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            },
        )
        val themeKeyDesc = when {
            cfg.themeSwitchKeyCode <= 0 -> "未绑定（自由学习方向盘或中控键）"
            cfg.sharesThemeKeyWithPrimary -> "共用主视图键：${formatGeelyWheelKey(cfg.themeSwitchKeyCode)} · ${cfg.themeSwitchTrigger.shortLabel}"
            else -> "独立按键：${formatGeelyWheelKey(cfg.themeSwitchKeyCode)} · ${cfg.themeSwitchTrigger.shortLabel}"
        }
        themeInfo.addView(
            TextView(act).apply {
                text = themeKeyDesc
                setTextColor(if (cfg.themeSwitchKeyCode > 0) ui.colors.accent else ui.colors.secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, ui.dp(2), 0, 0)
            },
        )
        themeTop.addView(themeInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) })
        themeTop.addView(
            if (learningTheme) {
                ui.primaryButton("请按车机键…", heightDp = 38) {
                    GeelyKx11ShellUi.cancelAllWheelLearning()
                    host.requestRender()
                }
            } else {
                ui.actionButton(if (cfg.themeSwitchKeyCode > 0) "改绑按键" else "学习按键", selected = cfg.themeSwitchKeyCode > 0, heightDp = 38) {
                    startClusterLearning(act, EcarxKx11WheelKeys.ClusterLearnTarget.THEME, host)
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)),
        )
        if (cfg.themeSwitchKeyCode > 0) {
            themeTop.addView(
                ui.actionButton("清除", heightDp = 38) {
                    GeelyClusterPrefs.save(act, cfg.copy(themeSwitchKeyCode = 0))
                    GeelyClusterEnhancer.refreshConfig(act)
                    host.requestRender()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(38)).apply { marginStart = ui.dp(6) },
            )
        }
        themeBox.addView(themeTop, ui.matchWrap(bottomMargin = ui.dp(8)))

        val themeTrigRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((idx, trig) in listOf(
            GeelyClusterSwitchTrigger.SINGLE_CLICK to "单击切主题",
            GeelyClusterSwitchTrigger.LONG_PRESS to "长按切主题",
            GeelyClusterSwitchTrigger.DOUBLE_CLICK to "双击切主题",
        ).withIndex()) {
            themeTrigRow.addView(
                ui.actionButton(trig.second, selected = cfg.themeSwitchTrigger == trig.first, heightDp = 36, textSizeSp = 12.5f) {
                    var nextPrimary = cfg.centerSwitchTrigger
                    var nextTel = cfg.telemetrySwitchTrigger
                    if (cfg.sharesThemeKeyWithPrimary && nextPrimary == trig.first) {
                        val used = linkedSetOf(trig.first)
                        if (cfg.sharesTelemetryKeyWithPrimary) used.add(nextTel)
                        nextPrimary = GeelyClusterSwitchTrigger.resolveNonConflicting(nextPrimary, used)
                    }
                    if (cfg.sharesThemeKeyWithTelemetry && nextTel == trig.first) {
                        val used = linkedSetOf(trig.first)
                        if (cfg.sharesThemeKeyWithPrimary) used.add(nextPrimary)
                        nextTel = GeelyClusterSwitchTrigger.resolveNonConflicting(nextTel, used)
                    }
                    GeelyClusterPrefs.save(
                        act,
                        cfg.copy(
                            centerSwitchTrigger = nextPrimary,
                            telemetrySwitchTrigger = nextTel,
                            themeSwitchTrigger = trig.first,
                        ),
                    )
                    GeelyClusterEnhancer.refreshConfig(act)
                    host.requestRender()
                },
                LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply { if (idx > 0) marginStart = ui.dp(6) },
            )
        }
        themeBox.addView(themeTrigRow, ui.matchWrap(bottomMargin = ui.dp(6)))
        val enabledThemeNames = GeelyClusterThemeStyle.entries
            .filter { it in cfg.themeCycleStyles }
            .joinToString(" → ") { it.shortLabel }
        themeBox.addView(
            ui.hintText("当前循环：$enabledThemeNames（可在「全境仪表与 HUD」中勾选循环主题）"),
        )
        clusterKeyCard.addView(themeBox)
        parent.addView(clusterKeyCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 3: Touch Gesture & Car Button
        val ctrlCard = ui.surfaceCard()
        ctrlCard.addView(ui.sectionTitle("CarPlay 触控手势与车标按钮"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSegmentedChoice(
            parent = ctrlCard,
            title = "CarPlay 画面内多指返回手势",
            subtitle = "在 CarPlay 全屏画面中多指轻点快速呼出设置与返回菜单",
            options = listOf(0 to "关闭", 2 to "2 指", 3 to "3 指", 4 to "4 指"),
            selected = AirPlayPersistence.loadSettingsGestureFingers(act),
        ) { fingers ->
            AirPlayPersistence.saveSettingsGestureFingers(act, fingers)
            host.requestRender()
        }

        // Reuse native car button icon & brand selector controls.
        // Index 0 must be a heading view because DiPlayActivity.refreshCarButton() preserves child 0.
        val carButtonBox = ui.subCard()
        carButtonBox.addView(
            TextView(act).apply {
                text = act.getString(R.string.car_button_in_carplay)
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            },
            ui.matchWrap(bottomMargin = ui.dp(10)),
        )
        host.createCarButtonSection(carButtonBox)
        ctrlCard.addView(carButtonBox)
        parent.addView(ctrlCard, ui.matchWrap(bottomMargin = ui.dp(14)))
    }

    private fun startRoleLearning(act: android.app.Activity, role: WheelZoomSettings.Role, host: GeelyKx11ShellHost) {
        GeelyKx11ShellUi.cancelAllWheelLearning()
        GeelyKx11ShellUi.setWheelLearnRole(role)
        host.requestRender()
        val started = EcarxKx11WheelKeys.learn(
            context = act,
            role = role,
            cancelled = {
                act.runOnUiThread {
                    GeelyKx11ShellUi.setWheelLearnRole(null)
                    host.requestRender()
                }
            },
            refused = {
                act.runOnUiThread {
                    GeelyKx11ShellUi.setWheelLearnRole(null)
                    Toast.makeText(act, "该按键已被其他功能占用，请换一颗按键", Toast.LENGTH_SHORT).show()
                    host.requestRender()
                }
            },
            done = { key ->
                act.runOnUiThread {
                    GeelyKx11ShellUi.setWheelLearnRole(null)
                    if (role == WheelZoomSettings.Role.SIRI) WheelZoomSettings.setSiriKey(act, true)
                    else if (role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) WheelZoomSettings.setMediaPlayPause(act, true)
                    WheelZoomSettings.assign(act, role, key)
                    EcarxKx11WheelKeys.settingsChanged()
                    Toast.makeText(act, "已绑定：${formatGeelyWheelKey(key.code)}", Toast.LENGTH_SHORT).show()
                    host.requestRender()
                }
            },
        )
        if (!started) {
            GeelyKx11ShellUi.setWheelLearnRole(null)
            Toast.makeText(act, "未能连接吉利方向盘输入服务", Toast.LENGTH_SHORT).show()
            host.requestRender()
        }
    }

    private fun startClusterLearning(
        act: android.app.Activity,
        target: EcarxKx11WheelKeys.ClusterLearnTarget,
        host: GeelyKx11ShellHost,
    ) {
        GeelyKx11ShellUi.cancelAllWheelLearning()
        GeelyKx11ShellUi.setClusterLearnTarget(target)
        host.requestRender()
        val started = EcarxKx11WheelKeys.learnClusterSwitchKey(
            context = act,
            target = target,
            cancelled = {
                act.runOnUiThread {
                    GeelyKx11ShellUi.setClusterLearnTarget(null)
                    host.requestRender()
                }
            },
            done = { code ->
                act.runOnUiThread {
                    GeelyKx11ShellUi.setClusterLearnTarget(null)
                    GeelyClusterEnhancer.refreshConfig(act)
                    Toast.makeText(act, "已绑定按键：${formatGeelyWheelKey(code)}", Toast.LENGTH_SHORT).show()
                    host.requestRender()
                }
            },
        )
        if (!started) {
            GeelyKx11ShellUi.setClusterLearnTarget(null)
            Toast.makeText(act, "未能连接吉利方向盘输入服务", Toast.LENGTH_SHORT).show()
            host.requestRender()
        }
    }

    // -------------------------------------------------------------------------
    // 4. 显示与画面
    // -------------------------------------------------------------------------
    fun renderDisplay(parent: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity

        val lookCard = ui.surfaceCard()
        lookCard.addView(ui.sectionTitle("昼夜主题与外观"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSegmentedChoice(
            parent = lookCard,
            title = "DiPlay 应用界面主题",
            subtitle = "选择「自动」将跟随原车大灯与 ECARX 昼夜状态切换",
            options = listOf(
                AppAppearance.AUTO to "自动跟随",
                AppAppearance.DARK to "始终深色",
                AppAppearance.LIGHT to "始终浅色",
            ),
            selected = AirPlayPersistence.loadAppAppearance(act),
        ) { appearance ->
            AirPlayPersistence.saveAppAppearance(act, appearance)
            host.checkForAppearanceChange()
            host.requestRender()
        }

        val currentNightMode = AirPlayPersistence.loadCarPlayNightMode(act)
        ui.addSegmentedChoice(
            parent = lookCard,
            title = "CarPlay 昼夜模式",
            subtitle = "星越L 推荐「跟随车机」，自动权威同步 ECARX 原车大灯信号",
            options = listOf(
                CarPlayNightMode.SYSTEM to "跟随车机",
                CarPlayNightMode.SCHEDULE to "按时间",
                CarPlayNightMode.NIGHT to "始终深色",
                CarPlayNightMode.DAY to "始终浅色",
            ),
            selected = if (currentNightMode == CarPlayNightMode.AMBIENT) CarPlayNightMode.SYSTEM else currentNightMode,
        ) { mode ->
            AirPlayPersistence.saveCarPlayNightMode(act, mode)
            host.checkForAppearanceChange()
            host.requestRender()
        }

        if (currentNightMode == CarPlayNightMode.SCHEDULE) {
            val sched = AirPlayPersistence.loadCarPlayNightSchedule(act)
            val timeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val startLabel = String.format(Locale.getDefault(), "深色开始 · %02d:%02d", sched.startMinute / 60, sched.startMinute % 60)
            val endLabel = String.format(Locale.getDefault(), "浅色开始 · %02d:%02d", sched.endMinute / 60, sched.endMinute % 60)
            timeRow.addView(
                ui.actionButton(startLabel, heightDp = 40) {
                    pickScheduleTime(act, true, host)
                },
                LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { marginEnd = ui.dp(8) },
            )
            timeRow.addView(
                ui.actionButton(endLabel, heightDp = 40) {
                    pickScheduleTime(act, false, host)
                },
                LinearLayout.LayoutParams(0, ui.dp(40), 1f),
            )
            lookCard.addView(timeRow, ui.matchWrap(bottomMargin = ui.dp(10)))
        }

        lookCard.addView(
            ui.actionButton("CarPlay 画面色彩与亮度微调", heightDp = 44) { host.openPictureAdjustments() },
            ui.matchHeight(44),
        )
        parent.addView(lookCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 2: Frame rate, Size, Resolution, Dock
        val screenCard = ui.surfaceCard()
        screenCard.addView(ui.sectionTitle("星越L 1920×720 画面比例与刷新率"), ui.matchWrap(bottomMargin = ui.dp(10)))

        val curFps = AirPlayPersistence.loadFps(act)
        ui.addSegmentedChoice(
            parent = screenCard,
            title = "CarPlay 刷新率",
            subtitle = "星越L 8155 车机推荐 60 fps（更改后需重连生效）",
            options = listOf(30 to "30 fps（低负载）", 60 to "60 fps（推荐流畅）"),
            selected = if (curFps <= 30) 30 else 60,
        ) { fps ->
            AirPlayPersistence.saveFps(act, fps)
            host.markReconnectNeeded()
            host.requestRender()
        }

        val savedMm = AirPlayPersistence.loadWidthPhysicalMm(act)
        val sizeChoices = carPlaySizeChoices(act, savedMm)
        val currentSizeLabel = sizeChoices.firstOrNull { it.widthMillimeters == savedMm }?.label ?: "$savedMm mm"
        screenCard.addView(
            ui.actionButton("CarPlay 图标尺寸 · $currentSizeLabel", heightDp = 44) {
                var pending = sizeChoices.indexOfFirst { it.widthMillimeters == savedMm }.coerceAtLeast(0)
                act.appDialogBuilder()
                    .setTitle(act.getString(R.string.carplay_size))
                    .setSingleChoiceItems(sizeChoices.map { it.label }.toTypedArray(), pending) { _, idx ->
                        pending = idx
                    }
                    .setPositiveButton(act.getString(if (CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                        val picked = sizeChoices[pending]
                        if (picked.widthMillimeters != savedMm) {
                            AirPlayPersistence.saveWidthPhysicalMm(act, picked.widthMillimeters)
                            if (CarPlayBackgroundSession.hasSession()) {
                                host.reconnectIfRunning()
                            } else {
                                host.requestRender()
                            }
                        }
                    }
                    .setNegativeButton(act.getString(R.string.cancel), null)
                    .show()
            },
            ui.matchHeight(44, bottomMargin = ui.dp(10)),
        )

        val scaleSteps = (CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT step 5).toList()
        ui.addSliderRow(
            parent = screenCard,
            title = "画面分辨率缩放",
            subtitle = "调整渲染分辨率比例（默认 100%，修改后需重连生效）",
            values = scaleSteps,
            current = AirPlayPersistence.loadDisplayScalePercent(act),
            formatValue = { "$it%" },
        ) { pct ->
            AirPlayPersistence.saveDisplayScalePercent(act, pct)
            host.markReconnectNeeded()
        }

        ui.addSegmentedChoice(
            parent = screenCard,
            title = "CarPlay Dock 栏位置",
            subtitle = "在左侧与底部之间切换可实时生效",
            options = listOf(
                CarPlayDock.AUTOMATIC to "自动",
                CarPlayDock.DRIVER_SIDE to "主驾侧（左）",
                CarPlayDock.BOTTOM to "底部",
            ),
            selected = CarPlayDock.load(act),
        ) { to ->
            val from = CarPlayDock.load(act)
            CarPlayDock.save(act, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                host.markReconnectNeeded()
            }
            host.requestRender()
        }

        ui.addSwitchRow(
            parent = screenCard,
            title = "投屏时隐藏车机顶部状态栏",
            subtitle = "充分利用星越L中控宽屏显示高度",
            checked = AirPlayPersistence.loadHideTopBar(act),
        ) { hide ->
            AirPlayPersistence.saveHideTopBar(act, hide)
            host.applyFullscreenMode()
        }

        ui.addSwitchRow(
            parent = screenCard,
            title = "投屏时隐藏车机底部系统栏",
            subtitle = "边缘滑动可随时呼出原车空调与导航栏",
            checked = AirPlayPersistence.loadHideBottomBar(act),
        ) { hide ->
            AirPlayPersistence.saveHideBottomBar(act, hide)
            host.applyFullscreenMode()
        }
        parent.addView(screenCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 3: Video decoder options relevant to KX11
        val videoCard = ui.surfaceCard()
        videoCard.addView(ui.sectionTitle("视频解码与流畅度"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = videoCard,
            title = "平滑视频渲染",
            subtitle = "减少地图拖拽与高帧率动画时的轻微抖动",
            checked = AirPlayPersistence.loadSmoothVideo(act),
            needsReconnectBadge = true,
        ) { on ->
            AirPlayPersistence.saveSmoothVideo(act, on)
            host.markReconnectNeeded()
        }

        ui.addSwitchRow(
            parent = videoCard,
            title = "HEVC (H.265) 高效编码（实验性）",
            subtitle = "更省无线带宽；如遇花屏或卡顿请保持关闭",
            checked = AirPlayPersistence.loadHevcEnabled(act),
            needsReconnectBadge = true,
        ) { on ->
            AirPlayPersistence.saveHevcEnabled(act, on)
            host.markReconnectNeeded()
        }

        ui.addSwitchRow(
            parent = videoCard,
            title = "低延迟硬解模式（实验性）",
            subtitle = "进一步缩短触控跟手延迟",
            checked = AirPlayPersistence.loadLowLatencyDecoder(act),
            needsReconnectBadge = true,
        ) { on ->
            AirPlayPersistence.saveLowLatencyDecoder(act, on)
            host.markReconnectNeeded()
        }
        parent.addView(videoCard, ui.matchWrap(bottomMargin = ui.dp(14)))
    }

    private fun pickScheduleTime(act: android.app.Activity, start: Boolean, host: GeelyKx11ShellHost) {
        val current = AirPlayPersistence.loadCarPlayNightSchedule(act)
        val initial = if (start) current.startMinute else current.endMinute
        TimePickerDialog(act, act.appDialogTheme(), { _, hour, minute ->
            val updatedMinute = hour * 60 + minute
            val other = if (start) current.endMinute else current.startMinute
            if (updatedMinute == other) {
                Toast.makeText(act, act.getString(R.string.carplay_night_schedule_same_time), Toast.LENGTH_SHORT).show()
            } else {
                val updated = if (start) current.copy(startMinute = updatedMinute) else current.copy(endMinute = updatedMinute)
                AirPlayPersistence.saveCarPlayNightSchedule(act, updated)
                host.checkForAppearanceChange()
                host.requestRender()
            }
        }, initial / 60, initial % 60, true).show()
    }

    // -------------------------------------------------------------------------
    // 5. 音频与定位
    // -------------------------------------------------------------------------
    fun renderAudioAndLocation(parent: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity

        // Card 1: ECARX GNSS + CAN Wheel Speed
        val locCard = ui.surfaceCard()
        locCard.addView(ui.sectionTitle("ECARX 硬件 GPS + CAN 轮速融合定位"), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = locCard,
            title = "向 iPhone 发送车机 GPS 与 CAN 轮速",
            subtitle = "KX11 默认开启：融合车顶 GNSS 天线与 250ms ECARX 轮速 (\$PASCD)，隧道与地库不漂移",
            checked = AirPlayPersistence.loadLocationReportingEnabled(act),
            needsReconnectBadge = true,
        ) { enabled ->
            host.onLocationReportingChanged(enabled)
        }

        val statusRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        statusRow.addView(
            ui.badge("车顶 GNSS 1Hz 零节流", ui.colors.success, ui.colors.successBg),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ui.dp(8)
            },
        )
        statusRow.addView(
            ui.badge(
                text = if (EcarxWheelSpeed.present()) "ECARX 轮速源在线" else "仅 GNSS 模式",
                fg = if (EcarxWheelSpeed.present()) ui.colors.success else ui.colors.warning,
                bg = if (EcarxWheelSpeed.present()) ui.colors.successBg else ui.colors.warningBg,
            ),
        )
        locCard.addView(statusRow)
        parent.addView(locCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 2: Audio Focus & Channels
        val audioCard = ui.surfaceCard()
        audioCard.addView(ui.sectionTitle("星越L 音频焦点与音量路由"), ui.matchWrap(bottomMargin = ui.dp(10)))

        val focusOn = AirPlayPersistence.loadAudioFocusEnabled(act)
        ui.addSwitchRow(
            parent = audioCard,
            title = "启用音频焦点（方向盘音量键联动）",
            subtitle = "KX11 推荐开启：确保方向盘音量键正确调节 CarPlay 音量",
            checked = focusOn,
            needsReconnectBadge = true,
        ) { on ->
            AirPlayPersistence.saveAudioFocusEnabled(act, on)
            host.markReconnectNeeded()
            host.requestRender()
        }

        if (focusOn) {
            ui.addSwitchRow(
                parent = audioCard,
                title = "导航播报独立音频焦点",
                subtitle = "播报期间方向盘调节导航音量（Stream 15），播报结束自动切回媒体音量（Stream 3）",
                checked = AirPlayPersistence.loadNavigationPromptAudioFocus(act),
                needsReconnectBadge = true,
            ) { on ->
                AirPlayPersistence.saveNavigationPromptAudioFocus(act, on)
                host.markReconnectNeeded()
            }
            ui.addSwitchRow(
                parent = audioCard,
                title = "其他应用播放时自动让出焦点",
                subtitle = "切回原车收音机或车机音乐时自动暂停 CarPlay 音频",
                checked = AirPlayPersistence.loadAudioFocusAutoYield(act),
                needsReconnectBadge = true,
            ) { on ->
                AirPlayPersistence.saveAudioFocusAutoYield(act, on)
                host.markReconnectNeeded()
            }
        }

        val chRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val mediaCh = AirPlayPersistence.loadMediaAudioChannel(act)
        val navCh = AirPlayPersistence.loadNavigationAudioChannel(act)
        chRow.addView(
            ui.actionButton("媒体音频通道 · $mediaCh", heightDp = 42) {
                showChannelPicker(act, "媒体音频通道", mediaCh, navigation = false, host = host)
            },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(8) },
        )
        chRow.addView(
            ui.actionButton("导航播报通道 · $navCh", heightDp = 42) {
                showChannelPicker(act, "导航播报通道", navCh, navigation = true, host = host)
            },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f),
        )
        audioCard.addView(chRow, ui.matchWrap(bottomMargin = ui.dp(10)))

        val gainSteps = (NavigationAudioGain.MIN_DB..NavigationAudioGain.MAX_DB).toList()
        ui.addSliderRow(
            parent = audioCard,
            title = "导航播报音量衰减",
            subtitle = "调整 CarPlay 导航语音相对背景音乐的响度",
            values = gainSteps,
            current = AirPlayPersistence.loadNavigationAudioAttenuationDb(act),
            formatValue = { "$it dB" },
        ) { db ->
            AirPlayPersistence.saveNavigationAudioAttenuationDb(act, db)
            CarPlayBackgroundSession.snapshot()?.sink?.setNavigationAttenuationDb(db)
        }

        ui.addSliderRow(
            parent = audioCard,
            title = "通话音量衰减",
            subtitle = "调整 CarPlay 通话声音的增益/衰减",
            values = gainSteps,
            current = AirPlayPersistence.loadCallAudioAttenuationDb(act),
            formatValue = { "$it dB" },
        ) { db ->
            AirPlayPersistence.saveCallAudioAttenuationDb(act, db)
            CarPlayBackgroundSession.snapshot()?.sink?.setCallAttenuationDb(db)
        }

        val wirelessPresets = MediaAudioBuffer.wirelessPresets
        ui.addSegmentedChoice(
            parent = audioCard,
            title = "无线音乐缓冲时长",
            subtitle = "无线音乐卡顿时适当调大，默认 300 ms",
            options = wirelessPresets.map { it to "${it}ms" },
            selected = AirPlayPersistence.loadMediaBufferMillis(act, wireless = true),
        ) { ms ->
            AirPlayPersistence.saveMediaBufferMillis(act, wireless = true, millis = ms)
            if (AirPlayPersistence.loadWirelessEnabled(act)) host.markReconnectNeeded()
            host.requestRender()
        }

        ui.addSwitchRow(
            parent = audioCard,
            title = "使用车机原车蓝牙播放声音",
            subtitle = "默认关闭；开启后将改走原车蓝牙音频链路（需重连）",
            checked = AirPlayPersistence.loadCarBluetoothAudio(act),
            needsReconnectBadge = true,
        ) { on ->
            AirPlayPersistence.saveCarBluetoothAudio(act, on)
            host.markReconnectNeeded()
        }

        ui.addSwitchRow(
            parent = audioCard,
            title = "通话免提扬声器模式",
            subtitle = "通话声音异常偏小时可尝试开启",
            checked = AirPlayPersistence.loadCallSpeakerphone(act),
        ) { on ->
            AirPlayPersistence.saveCallSpeakerphone(act, on)
        }
        parent.addView(audioCard, ui.matchWrap(bottomMargin = ui.dp(14)))
    }

    private fun showChannelPicker(
        act: android.app.Activity,
        title: String,
        current: Int,
        navigation: Boolean,
        host: GeelyKx11ShellHost,
    ) {
        val preview = AudioChannelPreview { ch ->
            Toast.makeText(act, act.getString(R.string.contrib_audio_home_channel_preview_unavailable, ch), Toast.LENGTH_SHORT).show()
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        act.appDialogBuilder()
            .setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(act.getString(if (CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                if (selection != current) {
                    if (navigation) AirPlayPersistence.saveNavigationAudioChannel(act, selection)
                    else AirPlayPersistence.saveMediaAudioChannel(act, selection)
                    if (CarPlayBackgroundSession.hasSession()) host.reconnectIfRunning()
                    else host.requestRender()
                }
            }
            .setNegativeButton(act.getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    // -------------------------------------------------------------------------
    // 6. 诊断与关于（含原生完整设置兜底入口）
    // -------------------------------------------------------------------------
    fun renderDiagnosticsAndAbout(
        parent: LinearLayout,
        host: GeelyKx11ShellHost,
        ui: GeelyKx11UiKit,
    ): Button {
        val act = host.activity

        // Card 1: Diagnostics & R2 upload
        val diagCard = ui.surfaceCard()
        diagCard.addView(ui.sectionTitle("诊断报告与 Cloudflare R2 一键上传"), ui.matchWrap(bottomMargin = ui.dp(10)))

        DiagnosticR2UploadUi.attach(
            activity = act,
            card = diagCard,
            createButton = { text, onClick ->
                if (text == act.getString(R.string.r2_upload_report) || text == act.getString(R.string.r2_upload_in_progress)) {
                    ui.primaryButton(text, heightDp = 44, onClick = onClick)
                } else {
                    ui.actionButton(text, heightDp = 42, onClick = onClick)
                }
            },
            buttonLayout = { ui.matchHeight(44, bottomMargin = ui.dp(8)) },
            reportFileName = host::reportFileName,
            reportText = host::diagnosticReportText,
            fallbackSaveLocal = host::saveReportToDownloads,
        )

        val localRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val exportBtn = ui.actionButton(
            text = if (host.exportInProgress) act.getString(R.string.saving_report) else act.getString(R.string.save_diagnostic_report),
            heightDp = 42,
        ) { host.saveReportToDownloads() }.apply { isEnabled = !host.exportInProgress }
        val pickBtn = ui.actionButton("自选保存目录", heightDp = 42) { host.chooseReportDestination() }
        val phoneReportBtn = ui.actionButton("手机扫码获取", heightDp = 42) { host.openBackup(forDiagnostics = true) }
        localRow.addView(exportBtn, LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(8) })
        localRow.addView(pickBtn, LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(8) })
        localRow.addView(phoneReportBtn, LinearLayout.LayoutParams(0, ui.dp(42), 1f))
        diagCard.addView(localRow, ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = diagCard,
            title = "显示实时帧率浮窗",
            subtitle = "用于在投屏画面角落排查帧率与解码耗时",
            checked = AirPlayPersistence.loadFpsCounter(act),
        ) { on ->
            AirPlayPersistence.saveFpsCounter(act, on)
        }
        parent.addView(diagCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 2: Backup, Language & About
        val aboutCard = ui.surfaceCard()
        aboutCard.addView(ui.sectionTitle("备份、更新与语言"), ui.matchWrap(bottomMargin = ui.dp(10)))
        aboutCard.addView(
            TextView(act).apply {
                text = "DiPlay · 星越L（KX11）专版 v${host.versionName}"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            },
            ui.matchWrap(bottomMargin = ui.dp(4)),
        )
        aboutCard.addView(
            ui.hintText("基于 DiPlay 低冲突扩展架构构建，保留全部上游 CarPlay 协议与同步能力。"),
            ui.matchWrap(bottomMargin = ui.dp(10)),
        )

        aboutCard.addView(host.createUpdateSectionView(), ui.matchWrap(bottomMargin = ui.dp(10)))

        ui.addSwitchRow(
            parent = aboutCard,
            title = "自动检查更新",
            subtitle = "有新版本时在首页提示",
            checked = UpdateAvailability.backgroundChecksEnabled(act),
        ) { on ->
            UpdateAvailability.saveBackgroundChecksEnabled(act, on)
        }

        val toolRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val langLabel = AppLocale.displayName(act, AppLocale.preference(act))
        toolRow.addView(
            ui.actionButton("扫码备份 / 恢复配置", heightDp = 42) { host.openBackup(forDiagnostics = false) },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply { marginEnd = ui.dp(8) },
        )
        toolRow.addView(
            ui.actionButton("语言 · $langLabel", heightDp = 42) { AppLocale.showPicker(act) },
            LinearLayout.LayoutParams(0, ui.dp(42), 1f),
        )
        aboutCard.addView(toolRow)
        parent.addView(aboutCard, ui.matchWrap(bottomMargin = ui.dp(14)))

        // Card 3: Fallback to full native upstream settings
        val legacyCard = ui.subCard(padH = 16, padV = 14)
        val legacyRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val legacyInfo = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        legacyInfo.addView(
            TextView(act).apply {
                text = "打开 DiPlay 原生完整设置（高级排查兜底）"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            },
        )
        legacyInfo.addView(
            ui.hintText("临时切回上游原版 11 分类完整界面（含全部隐藏与实验项），按返回键自动回到星越L专版。"),
            ui.matchWrap(topMargin = ui.dp(2)),
        )
        legacyRow.addView(legacyInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(12) })
        legacyRow.addView(
            ui.actionButton("进入原生设置 →", heightDp = 40) { host.openLegacyFullSettings() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)),
        )
        legacyCard.addView(legacyRow)
        parent.addView(legacyCard, ui.matchWrap())

        return exportBtn
    }
}
