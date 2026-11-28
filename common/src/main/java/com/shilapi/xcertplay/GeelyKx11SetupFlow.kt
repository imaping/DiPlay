package com.shilapi.xcertplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Streamlined 3-step first-run guide dedicated to Geely Xingyue L (KX11 / ECARX).
 * Replaces the upstream DiLink 3/4/5 selection wizard on Geely head units.
 */
internal object GeelyKx11SetupFlow {
    private val STEP_TITLES = listOf(
        "1. 确认星越L专版预设",
        "2. 连接方式与 iPhone",
        "3. 核心权限一键自检",
    )

    fun render(parent: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity
        val step = GeelyKx11ShellUi.setupStep.coerceIn(0, STEP_TITLES.lastIndex)

        // Step progress pills
        val stepBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((idx, label) in STEP_TITLES.withIndex()) {
            val active = idx == step
            val btn = if (active) {
                ui.primaryButton(label, heightDp = 42) {
                    GeelyKx11ShellUi.setupStep = idx
                    host.requestRender()
                }
            } else {
                ui.actionButton(label, selected = idx < step, heightDp = 42, textSizeSp = 13f) {
                    GeelyKx11ShellUi.setupStep = idx
                    host.requestRender()
                }
            }
            stepBar.addView(
                btn,
                LinearLayout.LayoutParams(0, ui.dp(42), 1f).apply {
                    if (idx > 0) marginStart = ui.dp(8)
                },
            )
        }
        parent.addView(stepBar, ui.matchWrap(bottomMargin = ui.dp(16)))

        // Main step card
        val card = ui.surfaceCard(padH = 22, padV = 20)
        when (step) {
            0 -> renderPresetsStep(card, ui)
            1 -> renderConnectionStep(card, host, ui)
            else -> renderPermissionsStep(card, host, ui)
        }
        parent.addView(card, ui.matchWrap(bottomMargin = ui.dp(16)))

        // Footer navigation
        val footer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prevBtn = ui.actionButton(if (step == 0) "跳过引导" else "← 上一步", heightDp = 48) {
            if (step == 0) {
                GeelyKx11ShellUi.setupStep = 0
                host.closeSetupGuide(connectNow = false)
            } else {
                GeelyKx11ShellUi.setupStep = step - 1
                host.requestRender()
            }
        }
        val nextBtn = ui.primaryButton(if (step == STEP_TITLES.lastIndex) "完成设置，进入星越L首页 ✓" else "下一步 →", heightDp = 48) {
            if (step == STEP_TITLES.lastIndex) {
                GeelyKx11ShellUi.setupStep = 0
                host.closeSetupGuide(connectNow = false)
            } else {
                GeelyKx11ShellUi.setupStep = step + 1
                host.requestRender()
            }
        }
        footer.addView(prevBtn, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(12) })
        footer.addView(nextBtn, LinearLayout.LayoutParams(0, ui.dp(48), 1.6f))
        parent.addView(footer, ui.matchWrap())
    }

    private fun renderPresetsStep(card: LinearLayout, ui: GeelyKx11UiKit) {
        val act = ui.activity
        card.addView(
            TextView(act).apply {
                text = "欢迎使用 XingPlay · 吉利星越L 专版"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
            ui.matchWrap(bottomMargin = ui.dp(6)),
        )
        card.addView(
            ui.hintText("系统已自动识别当前车机为吉利 ECARX（KX11），跳过 DiLink 车型向导，并自动为您启用了以下最佳开箱默认配置："),
            ui.matchWrap(bottomMargin = ui.dp(14)),
        )

        val presets = listOf(
            "60 fps 丝滑帧率" to "发挥星越L 8155 车机性能，操作更跟手",
            "导航 / 媒体独立音频焦点" to "方向盘音量键自动区分播报与音乐音量",
            "GPS + CAN 轮速融合定位" to "车顶 GNSS 叠加 250ms 轮速，进隧道不漂移",
            "方向盘快捷键预绑" to "语音键呼出 Siri，微信键切换全境仪表与车况",
        )
        for (pair in presets.chunked(2)) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((idx, item) in pair.withIndex()) {
                val box = ui.subCard()
                box.addView(
                    TextView(act).apply {
                        text = "✓ ${item.first}"
                        setTextColor(ui.colors.accent)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    },
                    ui.matchWrap(bottomMargin = ui.dp(4)),
                )
                box.addView(ui.hintText(item.second))
                row.addView(
                    box,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = ui.dp(10)
                    },
                )
            }
            card.addView(row, ui.matchWrap(bottomMargin = ui.dp(10)))
        }
    }

    private fun renderConnectionStep(card: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity
        card.addView(
            TextView(act).apply {
                text = "选择连接方式与常用 iPhone"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
            ui.matchWrap(bottomMargin = ui.dp(10)),
        )

        ui.addSegmentedChoice(
            parent = card,
            title = "无线连接方式",
            subtitle = "星越L 推荐使用车机个人热点，稳定且不影响全境仪表渲染",
            options = listOf(
                WirelessHotspotMode.MANUAL to "车机自带热点（推荐）",
                WirelessHotspotMode.WIFI_P2P to "Wi-Fi Direct (P2P)",
                WirelessHotspotMode.EXISTING_WIFI to "已有局域网 Wi-Fi",
            ),
            selected = AirPlayPersistence.loadWirelessHotspotMode(act),
        ) { mode ->
            AirPlayPersistence.saveWirelessHotspotMode(act, mode)
            host.requestRender()
        }

        val phoneName = if (DiPlayPreferences.phoneAddress(act) != null) DiPlayPreferences.phoneName(act) else "尚未选择"
        val actionRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(
            ui.actionButton("配置热点详情", heightDp = 44) { host.openConnectionSetup() },
            LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { marginEnd = ui.dp(10) },
        )
        actionRow.addView(
            ui.primaryButton("选择 iPhone（$phoneName）", heightDp = 44) { host.choosePhone() },
            LinearLayout.LayoutParams(0, ui.dp(44), 1.3f),
        )
        card.addView(actionRow)
    }

    private fun renderPermissionsStep(card: LinearLayout, host: GeelyKx11ShellHost, ui: GeelyKx11UiKit) {
        val act = host.activity
        card.addView(
            TextView(act).apply {
                text = "星越L 核心权限自检"
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
            ui.matchWrap(bottomMargin = ui.dp(6)),
        )
        card.addView(
            ui.hintText("确认以下核心权限就绪后，无线 CarPlay、全境仪表、W-HUD 与原车歌词同步即可完整工作（可随时在设置中补授）："),
            ui.matchWrap(bottomMargin = ui.dp(12)),
        )

        val locGranted = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(act)
        val notifGranted = GeelyMediaNotificationListenerService.isEnabled(act)
        val ecarxReady = EcarxWheelSpeed.present() || EcarxKx11HeadUnit.detected

        addPermRow(
            parent = card,
            ui = ui,
            title = "精准定位权限（GPS 融合与无线扫描）",
            granted = locGranted,
            actionText = "去开启",
        ) { host.openAppPermissions() }

        addPermRow(
            parent = card,
            ui = ui,
            title = "悬浮窗权限（常驻全境仪表 & W-HUD）",
            granted = overlayGranted,
            actionText = "去授权",
        ) {
            runCatching {
                act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
            }.onFailure { host.openAppPermissions() }
        }

        addPermRow(
            parent = card,
            ui = ui,
            title = "通知读取权限（车机网易云/QQ音乐实时歌词）",
            granted = notifGranted,
            actionText = "去授权",
        ) {
            runCatching {
                act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure { host.openAppPermissions() }
        }

        addPermRow(
            parent = card,
            ui = ui,
            title = "ECARX CAN 轮速与吉利方向盘输入服务",
            granted = ecarxReady,
            actionText = "自动识别",
            onAction = null,
        )
    }

    private fun addPermRow(
        parent: LinearLayout,
        ui: GeelyKx11UiKit,
        title: String,
        granted: Boolean,
        actionText: String,
        onAction: (() -> Unit)?,
    ) {
        val act = ui.activity
        val box = ui.subCard()
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(act).apply {
                text = title
                setTextColor(ui.colors.primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(10) },
        )
        if (granted) {
            row.addView(ui.badge("✓ 已就绪", ui.colors.success, ui.colors.successBg))
        } else if (onAction != null) {
            row.addView(
                ui.actionButton(actionText, heightDp = 36, textSizeSp = 12.5f, onClick = onAction),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(36)),
            )
        } else {
            row.addView(ui.badge("等待检测", ui.colors.warning, ui.colors.warningBg))
        }
        box.addView(row)
        parent.addView(box, ui.matchWrap(bottomMargin = ui.dp(8)))
    }
}
