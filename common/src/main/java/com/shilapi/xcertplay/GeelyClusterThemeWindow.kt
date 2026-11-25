package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/**
 * Manages a persistent `1920x720` `TYPE_APPLICATION_OVERLAY (2038)` window on the instrument
 * cluster [Display] that hosts [GeelyFakeUltraOverlayView] independently of `ClusterMapPresentation`.
 *
 * This allows dual-dial cluster themes to launch as soon as DiPlay starts and remain visible
 * after the phone disconnects from CarPlay, while seamlessly layering over `ClusterMapPresentation`'s
 * `1920x720` video surface whenever CarPlay reconnects.
 *
 * Strictly sized to `1920x720` at `(0, 0)` so it never interferes with the lower `Y: 720..1080`
 * factory W-HUD / classic cluster card hardware crop region.
 */
internal class GeelyClusterThemeWindow(
    context: Context,
    val display: Display,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val displayContext: Context = appContext.createDisplayContext(display)
    private val windowManager: WindowManager? =
        displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val view = GeelyFakeUltraOverlayView(displayContext)
    var attached: Boolean = false
        private set

    fun attach(): Boolean {
        if (attached) return true
        if (!Settings.canDrawOverlays(appContext)) return false
        val wm = windowManager ?: return false
        return runCatching {
            val lp = WindowManager.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.VIDEO_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                title = "DiPlayGeelyClusterTheme"
            }
            wm.addView(view, lp)
            attached = true
            log("GeelyClusterThemeWindow: attached 1920x720 overlay on displayId=${display.displayId}")
            true
        }.onFailure { e ->
            attached = false
            log("GeelyClusterThemeWindow: attach failed (${e.javaClass.simpleName}: ${e.message})")
        }.getOrDefault(false)
    }

    override fun close() {
        if (!attached) return
        attached = false
        runCatching { windowManager?.removeViewImmediate(view) }
    }
}

/**
 * Lightweight foreground keep-alive service for the persistent Geely cluster theme window.
 *
 * Kept separate from [DiPlaySessionService] so disconnecting CarPlay (which stops
 * [DiPlaySessionService]) does not terminate the cluster theme keep-alive process.
 * On [onTaskRemoved] or notification Stop action, cleanly removes the overlay and hands
 * the cluster back to the head unit so no frozen frame is left behind.
 */
class GeelyClusterKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GeelyClusterEnhancer.onKeepAliveStopRequested(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        val cfg = GeelyClusterPrefs.load(applicationContext)
        val openIntent = Intent(this, DiPlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, GeelyClusterKeepAliveService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            REQ_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "仪表盘主题常驻",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持吉利仪表盘主题在后台持续刷新"
                    setShowBadge(false)
                },
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay 仪表主题运行中")
            .setContentText("当前主题：${cfg.dialThemeStyle.displayName}")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭主题", stopPending).build())
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        GeelyClusterEnhancer.onAppTaskRemoved(applicationContext)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL_ID = "diplay_geely_cluster_theme"
        private const val NOTIFICATION_ID = 4207
        private const val REQ_OPEN = 42071
        private const val REQ_STOP = 42072
        const val ACTION_STOP = "com.shilapi.xcertplay.GEELY_CLUSTER_THEME_STOP"

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, GeelyClusterKeepAliveService::class.java)
            runCatching {
                ContextCompat.startForegroundService(app, intent)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.stopService(Intent(app, GeelyClusterKeepAliveService::class.java))
            }
        }
    }
}
