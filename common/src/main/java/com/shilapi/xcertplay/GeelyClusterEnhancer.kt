package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Low-conflict coordinator connecting:
 * 1. `ClusterMapPresentation` (`1920x1080` top `1920x720` video frame + Fake Ultra dual-dial overlay + `DimMenuInteraction`)
 * 2. `CarPlayHostActivity` (`TYPE_APPLICATION_OVERLAY (2038)` standalone W-HUD & classic cluster card window + iAP2 guidance/lane sync)
 * 3. `DiPlayActivity` (single-button entry to the standalone Geely Cluster & Self-Draw HUD configuration dialog)
 */
internal object GeelyClusterEnhancer {
    private var hostAppContext: Context? = null
    private var hostLogger: (String) -> Unit = {}
    private var overlayWindow: GeelySelfDrawOverlayWindow? = null

    private var activeFakeUltraView: GeelyFakeUltraOverlayView? = null
    private var activePresentationSelfDrawView: GeelySelfDrawCanvasView? = null
    private var activePresentationInnerRoot: FrameLayout? = null
    private var activeDimController: GeelyDimMenuController? = null
    private var activeDimWatcher: GeelyDimLogcatWatcher? = null
    private var musicSubscription: (() -> Unit)? = null

    private var latestGuidance: ClusterTurnGuidance? = null
    @Volatile
    private var latestLanes: List<LaneGuidanceLane> = emptyList()
    private var isFullScreenPresentationActive: Boolean = false
    private var previousLaneListener: ((List<LaneGuidanceLane>) -> Unit)? = null
    private val laneListenerHook: (List<LaneGuidanceLane>) -> Unit = { lanes ->
        previousLaneListener?.invoke(lanes)
        latestLanes = lanes
        overlayWindow?.view?.let { v ->
            v.post {
                v.lanes = lanes
                v.invalidate()
            }
        }
        activePresentationSelfDrawView?.let { v ->
            v.post {
                v.lanes = lanes
                v.invalidate()
            }
        }
    }

    fun matchesClusterStream(context: Context, display: Display, width: Int, height: Int): Boolean =
        GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION) != 0 &&
            width == GeelyOverlayArea.CANVAS_WIDTH &&
            (height == GeelyOverlayArea.VIDEO_HEIGHT || height == GeelyOverlayArea.CANVAS_HEIGHT)

    fun settingsEntryLabel(context: Context): String {
        val cfg = GeelyClusterPrefs.load(context)
        val dialsOn = cfg.dialLeftEnabled || cfg.dialRightEnabled
        val hudOn = cfg.hasAnySelfDrawOverlay
        val status = when {
            dialsOn && hudOn -> "双圆盘 + 自绘 HUD"
            dialsOn -> "仅双圆盘"
            hudOn -> "仅自绘 HUD"
            else -> "已关闭"
        }
        return "吉利 Fake Ultra 仪表与自绘 HUD · $status"
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val ctx = hostAppContext ?: activeFakeUltraView?.context ?: return@OnSharedPreferenceChangeListener
        refreshConfig(ctx)
    }

    /** Called in `CarPlayHostActivity.onCreate` / `onResume` to start standalone 2038 overlay if enabled. */
    fun attachHost(context: Context, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        hostAppContext = app
        hostLogger = log
        if (CarPlayGlance.laneListener !== laneListenerHook) {
            previousLaneListener = CarPlayGlance.laneListener
            CarPlayGlance.laneListener = laneListenerHook
        }
        GeelyClusterPrefs.prefs(app).registerOnSharedPreferenceChangeListener(prefsListener)
        syncStandaloneOverlayWindow(app)
    }

    /** Called in `CarPlayHostActivity.onDestroy`. */
    fun detachHost() {
        if (CarPlayGlance.laneListener === laneListenerHook) {
            CarPlayGlance.laneListener = previousLaneListener
            previousLaneListener = null
        }
        hostAppContext?.let { app ->
            runCatching { GeelyClusterPrefs.prefs(app).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        }
        closeStandaloneOverlayWindow()
    }

    /**
     * Called from `CarPlayHostActivity.applyClusterTurnOverlay(guidance)` on the main thread
     * whenever iAP2 route guidance changes or refreshes every second.
     */
    fun onTurnGuidanceUpdated(context: Context, guidance: ClusterTurnGuidance?, presentationShowing: Boolean) {
        if (hostAppContext == null) {
            attachHost(context)
        }
        latestGuidance = guidance
        if (guidance == null) {
            latestLanes = emptyList()
        }
        isFullScreenPresentationActive = presentationShowing

        val routeActive = guidance != null
        activeFakeUltraView?.routeActive = routeActive

        syncStandaloneOverlayWindow(context.applicationContext)

        overlayWindow?.view?.let { v ->
            v.fullScreen = presentationShowing
            v.guidance = guidance
            v.lanes = latestLanes
            v.invalidate()
        }

        activePresentationSelfDrawView?.let { v ->
            v.visibility = if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
            v.fullScreen = presentationShowing
            v.guidance = guidance
            v.lanes = latestLanes
            v.invalidate()
        }
    }

    /**
     * Wraps `ClusterMapPresentation`'s root `FrameLayout` when running on a Geely / ECARX cluster display:
     * - Places the existing `1920x720` map & stock views inside a `1920x720` top container (`Gravity.TOP`)
     *   so `1920x1080` displays do not stretch the map into the `Y: 720..1080` hardware crop region.
     * - Overlays `GeelyFakeUltraOverlayView` (`1920x720`) for dual vector dials, blurred album background,
     *   vignette, and bottom status bar.
     * - Overlays fallback `GeelySelfDrawCanvasView` (`1920x1080`) when the standalone 2038 window is unavailable.
     */
    fun attachToPresentation(
        presentation: Dialog,
        display: Display,
        currentRoot: FrameLayout,
        nightMode: Boolean,
    ): FrameLayout {
        val context = presentation.context
        if (!GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            !GeelyVehicleDetector.isComposite1080Display(display)
        ) {
            return currentRoot
        }

        val config = GeelyClusterPrefs.load(context)
        val is1080 = GeelyVehicleDetector.isComposite1080Display(display)

        val outerRoot = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Top 1920x720 container holding CarPlay video SurfaceView + Fake Ultra dual dials
        val topViewportParams = if (is1080) {
            FrameLayout.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.VIDEO_HEIGHT,
                Gravity.TOP or Gravity.START,
            )
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        currentRoot.clipChildren = true
        currentRoot.clipToPadding = true

        val fakeUltraView = GeelyFakeUltraOverlayView(context).apply {
            this.config = config
            this.nightMode = nightMode
            this.routeActive = latestGuidance != null
        }
        currentRoot.addView(
            fakeUltraView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        outerRoot.addView(currentRoot, topViewportParams)

        // Full 1920x1080 self-draw fallback layer inside Presentation (used if 2038 overlay window is not attached)
        val selfDrawView = GeelySelfDrawCanvasView(context).apply {
            this.config = config
            this.nightMode = nightMode
            this.fullScreen = true
            this.guidance = latestGuidance
            this.lanes = latestLanes
            this.visibility = if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
        }
        val selfDrawParams = if (is1080) {
            FrameLayout.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.CANVAS_HEIGHT,
                Gravity.TOP or Gravity.START,
            )
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        outerRoot.addView(selfDrawView, selfDrawParams)

        activeFakeUltraView = fakeUltraView
        activePresentationSelfDrawView = selfDrawView
        activePresentationInnerRoot = currentRoot
        syncStockClusterViewsVisibility(currentRoot, config)
        return outerRoot
    }

    private fun syncStockClusterViewsVisibility(root: FrameLayout?, config: GeelySelfDrawConfig) {
        val container = root ?: return
        val hideDials = config.dialLeftEnabled || config.dialRightEnabled || config.albumArtEnabled
        for (i in 0 until container.childCount) {
            when (val child = container.getChildAt(i)) {
                is ClusterSpeedometerView, is ClusterMusicView -> {
                    child.alpha = if (hideDials) 0f else 1f
                }
                is ClusterTurnCardView -> {
                    child.alpha = if (config.fullscreenCardEnabled) 0f else 1f
                }
            }
        }
    }

    /** Called in `ClusterMapPresentation.onStart()`. */
    fun onPresentationStarted(context: Context, display: Display) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(context) &&
            !GeelyVehicleDetector.isComposite1080Display(display)
        ) {
            return
        }
        isFullScreenPresentationActive = true
        GeelyClusterPrefs.prefs(context).registerOnSharedPreferenceChangeListener(prefsListener)

        musicSubscription?.invoke()
        musicSubscription = ClusterMusicState.observe { snapshot ->
            activeFakeUltraView?.setArtwork(snapshot.artwork)
        }

        val controller = activeDimController ?: GeelyDimMenuController(context, hostLogger).also {
            activeDimController = it
        }
        controller.enterFullScreenMap()

        activeDimWatcher?.close()
        activeDimWatcher = GeelyDimLogcatWatcher(controller) {
            hostLogger("GeelyDimMenu: passive exit to classic cluster detected")
        }.also { it.start() }

        syncStandaloneOverlayWindow(context.applicationContext)
        overlayWindow?.view?.fullScreen = true
        activePresentationSelfDrawView?.fullScreen = true
    }

    /** Called in `ClusterMapPresentation.onStop()`. */
    fun onPresentationStopped() {
        isFullScreenPresentationActive = false
        musicSubscription?.invoke()
        musicSubscription = null

        activeDimWatcher?.close()
        activeDimWatcher = null

        activeDimController?.restoreClassicCluster()

        activeFakeUltraView = null
        activePresentationSelfDrawView = null
        activePresentationInnerRoot = null
        overlayWindow?.view?.fullScreen = false
    }

    /** Called when day/night mode changes on `ClusterMapPresentation`. */
    fun onNightModeChanged(night: Boolean) {
        activeFakeUltraView?.nightMode = night
        activePresentationSelfDrawView?.nightMode = night
        overlayWindow?.view?.nightMode = night
    }

    fun refreshConfig(context: Context) {
        val cfg = GeelyClusterPrefs.load(context)
        activeFakeUltraView?.config = cfg
        activePresentationSelfDrawView?.config = cfg
        syncStockClusterViewsVisibility(activePresentationInnerRoot, cfg)
        syncStandaloneOverlayWindow(context.applicationContext)
        overlayWindow?.view?.config = cfg
        activePresentationSelfDrawView?.visibility =
            if (overlayWindow?.attached == true) View.INVISIBLE else View.VISIBLE
    }

    private fun syncStandaloneOverlayWindow(appContext: Context) {
        if (!GeelyVehicleDetector.isGeelyOrEcarx(appContext)) {
            closeStandaloneOverlayWindow()
            return
        }
        val cfg = GeelyClusterPrefs.load(appContext)
        if (!cfg.hasAnySelfDrawOverlay) {
            closeStandaloneOverlayWindow()
            return
        }
        val targetDisplay = GeelyVehicleDetector.findClusterDisplay(appContext)
        if (targetDisplay == null) {
            closeStandaloneOverlayWindow()
            return
        }
        val currentWindow = overlayWindow
        if (currentWindow != null && currentWindow.attached &&
            currentWindow.display.displayId == targetDisplay.displayId
        ) {
            currentWindow.view.config = cfg
            currentWindow.view.fullScreen = isFullScreenPresentationActive
            currentWindow.view.guidance = latestGuidance
            currentWindow.view.lanes = latestLanes
            return
        }
        closeStandaloneOverlayWindow()
        val newWindow = GeelySelfDrawOverlayWindow(appContext, targetDisplay, hostLogger)
        if (newWindow.attach()) {
            newWindow.view.config = cfg
            newWindow.view.fullScreen = isFullScreenPresentationActive
            newWindow.view.guidance = latestGuidance
            newWindow.view.lanes = latestLanes
            overlayWindow = newWindow
        } else {
            newWindow.close()
        }
    }

    private fun closeStandaloneOverlayWindow() {
        overlayWindow?.close()
        overlayWindow = null
    }

    /** Opens the standalone Geely Fake Ultra & Self-Draw HUD configuration dialog (Choice 2 A). */
    fun showSettingsDialog(activity: Activity, onDismiss: () -> Unit = {}) {
        GeelyClusterSettingsDialog(activity, onDismiss).show()
    }
}

/**
 * Interactive 1920x1080 scaled preview widget showing:
 * - Upper `1920x720` map region + Fake Ultra dual vector dials + fullscreen navigation card
 * - Lower `1920x360` (`Y: 720..1080`) hardware crop region + W-HUD (`0,720,228x186`) + classic cluster card (`1165,720,364x112`)
 * - Dashed bounding boxes and labels for each region.
 */
private class GeelyCompositePreviewView(context: Context) : View(context) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(context)
        set(value) {
            field = value
            fakeUltraView.config = value
            fakeUltraView.previewMode = true
            invalidate()
        }

    private val fakeUltraView = GeelyFakeUltraOverlayView(context).apply {
        previewMode = true
        routeActive = true
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 20f
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

        // 1. Upper 1920x720 simulated map background
        bgPaint.color = if (config.mapThemeDark) Color.rgb(16, 26, 36) else Color.rgb(218, 226, 235)
        canvas.drawRect(0f, 0f, 1920f, 720f, bgPaint)

        // 2. Lower 1920x360 (Y: 720..1080) hardware crop source background
        bgPaint.color = Color.rgb(8, 11, 16)
        canvas.drawRect(0f, 720f, 1920f, 1080f, bgPaint)

        // 3. Render Fake Ultra dual dials, vignette & bottom bar in 1920x720
        fakeUltraView.nightMode = config.mapThemeDark
        fakeUltraView.measure(
            MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(720, MeasureSpec.EXACTLY),
        )
        fakeUltraView.layout(0, 0, 1920, 720)
        fakeUltraView.draw(canvas)

        // 4. Render Self-Draw HUD, cluster card, fullscreen card & lane guidance in 1920x1080
        GeelySelfDrawRenderer.drawAll(
            canvas = canvas,
            config = config,
            guidance = null,
            lanes = emptyList(),
            nightMode = config.mapThemeDark,
            fullScreen = true,
            nowMs = SystemClock.uptimeMillis(),
            forcePreview = true,
        )

        // 5. Split line at Y = 720
        borderPaint.color = Color.argb(140, 100, 180, 255)
        canvas.drawLine(0f, 720f, 1920f, 720f, borderPaint)
        labelPaint.color = Color.argb(190, 130, 195, 255)
        canvas.drawText("▲ 1920×720 仪表主画面区   ▼ 1920×360 原车 W-HUD 与经典仪表卡硬件裁剪源区 (Y: 720..1080)", 24f, 750f, labelPaint)

        // 6. Bounding boxes for active self-draw areas
        if (config.hudEnabled) {
            drawAreaOutline(canvas, config.hudArea, Color.rgb(24, 203, 170), "W-HUD源")
        }
        if (config.cardEnabled) {
            drawAreaOutline(canvas, config.cardArea, Color.rgb(255, 194, 71), "经典仪表卡源")
        }
        if (config.fullscreenCardEnabled) {
            drawAreaOutline(canvas, config.fullscreenCardArea, Color.rgb(110, 185, 255), "全屏导航卡")
        }

        canvas.restoreToCount(save)
    }

    private fun drawAreaOutline(canvas: Canvas, area: GeelyOverlayArea, color: Int, tag: String) {
        borderPaint.color = color
        canvas.drawRect(
            area.x.toFloat(),
            area.y.toFloat(),
            area.right.toFloat(),
            area.bottom.toFloat(),
            borderPaint,
        )
        labelPaint.color = color
        val labelY = if (area.y >= 720) (area.bottom + 24f).coerceAtMost(1072f) else (area.y - 8f).coerceAtLeast(22f)
        canvas.drawText("$tag (${area.x},${area.y} ${area.width}×${area.height})", area.x.toFloat(), labelY, labelPaint)
    }
}

/**
 * Standalone configuration dialog for Geely Fake Ultra Dials & Self-Draw HUD/Navigation Cards.
 */
internal class GeelyClusterSettingsDialog(
    private val activity: Activity,
    private val onDismiss: () -> Unit = {},
) {
    private var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(activity)
    private var selectedTab: Int = 0
    private var previewView: GeelyCompositePreviewView? = null
    private var statusTextView: TextView? = null
    private var tabContentContainer: LinearLayout? = null
    private val tabButtons = ArrayList<Button>()
    private val dimController = GeelyDimMenuController(activity)

    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setOnDismissListener { onDismiss() }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.rgb(12, 17, 27)))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 17, 27))
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        // 1. Header Bar
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = createActionButton("‹ 返回设置") {
            dialog.dismiss()
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(128), dp(46)))

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        titleCol.addView(
            TextView(activity).apply {
                text = "吉利全液晶仪表 (Fake Ultra) 与自绘 HUD 设置"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
        )
        val statusTv = TextView(activity).apply {
            setTextColor(Color.rgb(156, 182, 206))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        statusTextView = statusTv
        titleCol.addView(statusTv)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val resetBtn = createActionButton("恢复默认坐标") {
            config = GeelyClusterPrefs.resetAreasToDefault(activity)
            onConfigUpdated()
            rebuildTabContent()
            Toast.makeText(activity, "已恢复吉利星越L / OneOS 默认坐标", Toast.LENGTH_SHORT).show()
        }
        header.addView(resetBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        root.addView(header)

        // 2. Main Body: Left Preview + Category Tabs, Right Scrollable Controls
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        // Left Column (Live 1920x1080 Composite Canvas Preview + Quick Mode Buttons)
        val leftCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), 0)
        }
        val previewCard = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 25, 38))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(42, 58, 82))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val pv = GeelyCompositePreviewView(activity).also {
            it.config = config
            previewView = it
        }
        previewCard.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        leftCol.addView(previewCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Quick Action Row under preview
        val quickRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val btnEnterMap = createActionButton("切全屏地图 (Mode 3)") {
            val ok = dimController.enterFullScreenMap()
            Toast.makeText(
                activity,
                if (ok) "已发送 switchNaviMode(3)" else "车机 DimMenuInteraction 不可用",
                Toast.LENGTH_SHORT,
            ).show()
        }
        val btnExitMap = createActionButton("回经典仪表 (Mode 1)") {
            val ok = dimController.restoreClassicCluster()
            Toast.makeText(
                activity,
                if (ok) "已发送 switchNaviMode(1)" else "车机 DimMenuInteraction 不可用",
                Toast.LENGTH_SHORT,
            ).show()
        }
        quickRow.addView(btnEnterMap, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        quickRow.addView(btnExitMap, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        leftCol.addView(quickRow)

        // Category Tab Bar
        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val tabTitles = listOf("仪表双圆盘", "导航卡片", "自绘 HUD", "屏幕与权限")
        tabButtons.clear()
        for (i in tabTitles.indices) {
            val btn = createActionButton(tabTitles[i]) {
                selectedTab = i
                updateTabButtonStyles()
                rebuildTabContent()
            }
            tabButtons.add(btn)
            tabRow.addView(
                btn,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (i > 0) leftMargin = dp(6)
                },
            )
        }
        leftCol.addView(tabRow)
        body.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))

        // Right Column: Scrollable Settings List
        val rightScroll = ScrollView(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 25, 38))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(38, 52, 74))
            }
        }
        val contentCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        tabContentContainer = contentCol
        rightScroll.addView(contentCol)
        body.addView(rightScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        updateStatusText()
        updateTabButtonStyles()
        rebuildTabContent()

        dialog.setContentView(root)
        dialog.show()
    }

    private fun onConfigUpdated() {
        GeelyClusterPrefs.save(activity, config)
        GeelyClusterEnhancer.refreshConfig(activity)
        previewView?.config = config
        updateStatusText()
    }

    private fun updateStatusText() {
        val display = GeelyVehicleDetector.findClusterDisplay(activity)
        val dispInfo = if (display != null) {
            val m = display.mode
            "仪表屏 ID=${display.displayId} (${m?.physicalWidth ?: 0}×${m?.physicalHeight ?: 0})"
        } else {
            "未检测到副屏 (默认 ID=${config.preferredDisplayId})"
        }
        val overlayOk = if (Settings.canDrawOverlays(activity)) "2038悬浮窗已授权" else "悬浮窗未授权(回退Presentation)"
        statusTextView?.text = "$dispInfo · $overlayOk · 配色: ${colorModeLabel(config.dialColorMode)}"
    }

    private fun updateTabButtonStyles() {
        for (i in tabButtons.indices) {
            val selected = i == selectedTab
            tabButtons[i].background = GradientDrawable().apply {
                setColor(if (selected) Color.rgb(24, 155, 134) else Color.rgb(27, 39, 57))
                cornerRadius = dp(10).toFloat()
            }
        }
    }

    private fun rebuildTabContent() {
        val container = tabContentContainer ?: return
        container.removeAllViews()
        when (selectedTab) {
            0 -> buildDialTab(container)
            1 -> buildCardsTab(container)
            2 -> buildHudTab(container)
            3 -> buildDisplayAndPermissionTab(container)
        }
    }

    private fun buildDialTab(container: LinearLayout) {
        addSectionTitle(container, "Fake Ultra 矢量双圆盘与视觉")
        addSwitchRow(
            container = container,
            title = "显示左圆盘（车速 0..240 km/h + 燃油百分比）",
            checked = config.dialLeftEnabled,
        ) { checked ->
            config = config.copy(dialLeftEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "显示右圆盘（转速 0..8 ×1000rpm + 水温 °C）",
            checked = config.dialRightEnabled,
        ) { checked ->
            config = config.copy(dialRightEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "专辑封面联动（非导航全屏模糊背景+居中封面 / 导航时嵌于右表盘圆心）",
            checked = config.albumArtEnabled,
        ) { checked ->
            config = config.copy(albumArtEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "底部状态栏（时间 · 平均油耗 L/100km · 水温 °C）",
            checked = config.bottomBarEnabled,
        ) { checked ->
            config = config.copy(bottomBarEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "地图边缘明暗渐变遮罩（Vignette）",
            checked = config.mapVignetteEnabled,
        ) { checked ->
            config = config.copy(mapVignetteEnabled = checked)
            onConfigUpdated()
        }

        addSectionTitle(container, "表盘主题色模式（800ms 平滑过渡）")
        val modeBtn = createActionButton("当前配色模式：${colorModeLabel(config.dialColorMode)}  (点击切换)") {
            val modes = GeelyDialColorMode.entries
            val next = modes[(config.dialColorMode.ordinal + 1) % modes.size]
            config = config.copy(dialColorMode = next)
            onConfigUpdated()
            rebuildTabContent()
        }
        container.addView(
            modeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) },
        )
    }

    private fun buildCardsTab(container: LinearLayout) {
        addSectionTitle(container, "经典仪表与全屏地图导航卡片")
        addSwitchRow(
            container = container,
            title = "启用经典仪表导航卡裁剪源 (Y: 720..1080)",
            checked = config.cardEnabled,
        ) { checked ->
            config = config.copy(cardEnabled = checked)
            onConfigUpdated()
        }
        addAreaEditorCard(
            container = container,
            label = "经典仪表卡区域 (默认 1165, 720, 364×112)",
            area = config.cardArea,
        ) { updated ->
            config = config.copy(cardArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }

        addSwitchRow(
            container = container,
            title = "启用全屏地图顶部大导航卡（含剩余距离与预计到达时间 ETA）",
            checked = config.fullscreenCardEnabled,
        ) { checked ->
            config = config.copy(fullscreenCardEnabled = checked)
            onConfigUpdated()
        }
        addAreaEditorCard(
            container = container,
            label = "全屏导航卡区域 (默认 595, 118, 728×112)",
            area = config.fullscreenCardArea,
        ) { updated ->
            config = config.copy(fullscreenCardArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }

        addSwitchRow(
            container = container,
            title = "启用 iAP2 (0x5204) 车道线转向引导阵列",
            checked = config.laneEnabled,
        ) { checked ->
            config = config.copy(laneEnabled = checked)
            onConfigUpdated()
        }
    }

    private fun buildHudTab(container: LinearLayout) {
        addSectionTitle(container, "吉利原车 W-HUD 抬头显示自绘裁剪源")
        addSwitchRow(
            container = container,
            title = "启用自绘 W-HUD 投射源 (Y: 720..1080 区域硬件截屏投射)",
            checked = config.hudEnabled,
        ) { checked ->
            config = config.copy(hudEnabled = checked)
            onConfigUpdated()
        }
        addAreaEditorCard(
            container = container,
            label = "W-HUD 裁剪源区域 (默认 0, 720, 228×186)",
            area = config.hudArea,
        ) { updated ->
            config = config.copy(hudArea = updated.clamp())
            onConfigUpdated()
            rebuildTabContent()
        }

        addSectionTitle(container, "实车常驻模拟预览")
        addSwitchRow(
            container = container,
            title = "在实车仪表与 W-HUD 上显示模拟导航数据（调校位置时开启）",
            checked = config.previewEnabled,
        ) { checked ->
            config = config.copy(previewEnabled = checked)
            onConfigUpdated()
        }
        addSwitchRow(
            container = container,
            title = "预览画布使用深色地图底色",
            checked = config.mapThemeDark,
        ) { checked ->
            config = config.copy(mapThemeDark = checked)
            onConfigUpdated()
        }
    }

    private fun buildDisplayAndPermissionTab(container: LinearLayout) {
        addSectionTitle(container, "仪表 Display 与 2038 独立悬浮窗权限")
        val selectDispBtn = createActionButton("选择目标仪表屏幕 (当前优先 ID=${config.preferredDisplayId})") {
            showDisplayPickerDialog()
        }
        container.addView(
            selectDispBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) },
        )

        val hasOverlay = Settings.canDrawOverlays(activity)
        val permBtn = createActionButton(
            if (hasOverlay) "悬浮窗权限已开启 (支持经典仪表下独立投射 W-HUD 与小卡)"
            else "前往开启悬浮窗权限 (TYPE_APPLICATION_OVERLAY 2038)"
        ) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                )
                activity.startActivity(intent)
            }.onFailure {
                Toast.makeText(activity, "无法打开系统悬浮窗权限页", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(
            permBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) },
        )
    }

    private fun addSectionTitle(container: LinearLayout, title: String) {
        container.addView(
            TextView(activity).apply {
                text = title
                setTextColor(Color.rgb(24, 203, 170))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            }
        )
    }

    private fun addSwitchRow(
        container: LinearLayout,
        title: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        val sw = Switch(activity).apply {
            text = title
            isChecked = checked
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(46)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
        container.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addAreaEditorCard(
        container: LinearLayout,
        label: String,
        area: GeelyOverlayArea,
        onAreaChanged: (GeelyOverlayArea) -> Unit,
    ) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 34, 50))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(
            TextView(activity).apply {
                text = "$label\n当前: X=${area.x}, Y=${area.y}, 宽=${area.width}, 高=${area.height}"
                setTextColor(Color.rgb(210, 226, 242))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        )

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val editBtn = createActionButton("输入精确坐标") {
            showNumericAreaDialog(label, area, onAreaChanged)
        }
        val leftBtn = createActionButton("X-4") { onAreaChanged(area.copy(x = area.x - 4)) }
        val rightBtn = createActionButton("X+4") { onAreaChanged(area.copy(x = area.x + 4)) }
        val upBtn = createActionButton("Y-4") { onAreaChanged(area.copy(y = area.y - 4)) }
        val downBtn = createActionButton("Y+4") { onAreaChanged(area.copy(y = area.y + 4)) }

        row.addView(editBtn, LinearLayout.LayoutParams(0, dp(40), 1.6f))
        for (b in listOf(leftBtn, rightBtn, upBtn, downBtn)) {
            row.addView(b, LinearLayout.LayoutParams(0, dp(40), 0.7f).apply { leftMargin = dp(4) })
        }
        box.addView(row)
        container.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            },
        )
    }

    private fun showNumericAreaDialog(
        title: String,
        current: GeelyOverlayArea,
        onSaved: (GeelyOverlayArea) -> Unit,
    ) {
        val layout = LinearLayout(activity).apply {
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
            EditText(activity).apply {
                this.hint = hint
                setText(value.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
            }.also { et ->
                layout.addView(TextView(activity).apply { text = hint })
                layout.addView(et)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
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
            val m = d.mode
            "屏幕 ID=${d.displayId} (${m?.physicalWidth ?: 0}×${m?.physicalHeight ?: 0})"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("选择仪表屏幕")
            .setItems(labels) { _, which ->
                val chosen = displays[which]
                config = config.copy(preferredDisplayId = chosen.displayId)
                onConfigUpdated()
                rebuildTabContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createActionButton(text: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = GradientDrawable().apply {
                setColor(Color.rgb(27, 39, 57))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }

    private fun colorModeLabel(mode: GeelyDialColorMode): String = when (mode) {
        GeelyDialColorMode.DEFAULT -> "默认青色 (#18CBAA)"
        GeelyDialColorMode.SPEED -> "随车速四段渐变"
        GeelyDialColorMode.ALBUM -> "随专辑封面主色"
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
