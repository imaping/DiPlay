package com.shilapi.xcertplay

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Snapshot of active head-unit local/Bluetooth music artwork (NetEase Cloud Music, QQ Music,
 * Bluetooth A2DP/AVRCP, OEM media apps) used on the Geely cluster when CarPlay is not connected.
 */
internal data class GeelyVehicleMediaSnapshot(
    val artwork: Bitmap? = null,
    val playing: Boolean = false,
    val active: Boolean = false,
    val packageName: String? = null,
) {
    val hasContent: Boolean
        get() = active && artwork != null && !artwork.isRecycled
}

/**
 * Lightweight NotificationListenerService enabling:
 * 1. Authorized [MediaSessionManager.getActiveSessions] access for NetEase Cloud Music, QQ Music,
 *    Bluetooth AVRCP, and other head-unit music apps.
 * 2. Fallback album artwork icons from media notifications.
 */
class GeelyMediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        GeelyVehicleMediaObserver.onNotificationListenerConnected(applicationContext)
        runCatching {
            activeNotifications?.forEach { sbn ->
                GeelyVehicleMediaObserver.onNotificationPosted(applicationContext, sbn)
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        GeelyVehicleMediaObserver.onNotificationListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        GeelyVehicleMediaObserver.onNotificationPosted(applicationContext, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        GeelyVehicleMediaObserver.onNotificationRemoved(sbn)
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, GeelyMediaNotificationListenerService::class.java)

        fun isEnabled(context: Context): Boolean {
            val flat = runCatching {
                Settings.Secure.getString(
                    context.applicationContext.contentResolver,
                    "enabled_notification_listeners",
                )
            }.getOrNull().orEmpty()
            if (flat.isBlank()) return false
            val target = componentName(context)
            return flat.split(':').any { entry ->
                val cn = ComponentName.unflattenFromString(entry.trim())
                cn != null && cn.packageName == target.packageName && cn.className == target.className
            }
        }
    }
}

/**
 * Observes head-unit native music apps (NetEase Cloud Music, QQ Music, Bluetooth music, etc.)
 * via [MediaSessionManager] and [GeelyMediaNotificationListenerService] for cluster album artwork
 * when CarPlay is not connected.
 */
internal object GeelyVehicleMediaObserver {
    private const val TAG = "GeelyVehicleMedia"
    private const val PAUSED_RETENTION_MS = 12_000L
    private const val MAX_ARTWORK_DIM_PX = 320
    private const val MAX_URI_CACHE_ENTRIES = 6

    private val KNOWN_MUSIC_PACKAGE_HINTS = listOf(
        "netease.cloudmusic",
        "tencent.qqmusic",
        "qqmusiccar",
        "wecarflow",
        "aiquting",
        "tencent.music",
        "kuwo",
        "kugou",
        "bluetooth",
        "btmusic",
        "mediacenter",
        "ximalaya",
        "luna.music",
        "apple.android.music",
        "spotify",
        "migu.music",
        "music",
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "geely-vehicle-media").apply { isDaemon = true }
    }

    private val observers = linkedMapOf<Any, (GeelyVehicleMediaSnapshot) -> Unit>()
    private var appContext: Context? = null
    private var logger: (String) -> Unit = {}

    // Active MediaSession tracking
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListenerRegistered = false
    private val trackedControllers = linkedMapOf<MediaController, MediaController.Callback>()

    // Notification-sourced fallback album icons keyed by package name
    private val notifArtworkByPkg = HashMap<String, Bitmap>()

    // Artwork URI decode cache (bounded LRU-style LinkedHashMap)
    private val uriBitmapCache = LinkedHashMap<String, Bitmap>(MAX_URI_CACHE_ENTRIES + 1, 0.75f, true)
    private val uriInFlight = HashSet<String>()
    private var pendingArtworkUri: String? = null

    // Artwork retention across lyric-in-title metadata updates
    private var retainedArtworkPkg: String? = null
    private var retainedArtworkArtist: String? = null
    private var retainedArtworkAlbum: String? = null
    private var retainedArtworkBitmap: Bitmap? = null

    // Pause grace timer so transient pauses (e.g. track switch) do not flicker to standby clock,
    // while prolonged pauses cleanly return the cluster center to the clock.
    private var pausedSinceElapsedMs: Long = 0L
    private val pauseExpiryTick = Runnable {
        recomputeAndPublish()
    }

    @Volatile
    private var latestSnapshot = GeelyVehicleMediaSnapshot()

    fun currentSnapshot(): GeelyVehicleMediaSnapshot = latestSnapshot

    fun observe(
        context: Context,
        log: (String) -> Unit = NOOP_LOG,
        listener: (GeelyVehicleMediaSnapshot) -> Unit,
    ): () -> Unit {
        val app = context.applicationContext
        val token = Any()
        val initial = synchronized(lock) {
            appContext = app
            if (log !== NOOP_LOG) logger = log
            observers[token] = listener
            ensureStartedLocked(app)
            latestSnapshot
        }
        runOnMain { listener(initial) }
        return {
            synchronized(lock) {
                observers.remove(token)
                if (observers.isEmpty()) {
                    stopLocked()
                }
            }
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (observers.isNotEmpty()) {
                attachMediaSessionListenerLocked(app)
            }
        }
        recomputeAndPublish()
    }

    internal fun onNotificationListenerConnected(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (observers.isNotEmpty()) {
                attachMediaSessionListenerLocked(app)
            }
        }
        emitLog("GeelyVehicleMedia: NotificationListener connected")
        recomputeAndPublish()
    }

    internal fun onNotificationListenerDisconnected() {
        synchronized(lock) {
            sessionsListenerRegistered = false
        }
        emitLog("GeelyVehicleMedia: NotificationListener disconnected")
    }

    internal fun onNotificationPosted(context: Context, sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == context.packageName) return
        val notification = sbn.notification ?: return
        if (!isLikelyMediaNotification(pkg, notification)) return

        val largeBitmap = extractNotificationLargeBitmap(context, notification)
        if (largeBitmap != null && !largeBitmap.isRecycled) {
            val changed = synchronized(lock) {
                if (notifArtworkByPkg[pkg] !== largeBitmap) {
                    notifArtworkByPkg[pkg] = largeBitmap
                    true
                } else {
                    false
                }
            }
            if (changed) {
                recomputeAndPublish()
            }
        }
    }

    internal fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val changed = synchronized(lock) {
            notifArtworkByPkg.remove(pkg) != null
        }
        if (changed) {
            recomputeAndPublish()
        }
    }

    private fun ensureStartedLocked(app: Context) {
        attachMediaSessionListenerLocked(app)
    }

    private fun stopLocked() {
        mainHandler.removeCallbacks(pauseExpiryTick)
        detachMediaSessionListenerLocked()
        notifArtworkByPkg.clear()
        pausedSinceElapsedMs = 0L
        pendingArtworkUri = null
        latestSnapshot = GeelyVehicleMediaSnapshot()
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        synchronized(lock) {
            syncControllersLocked(controllers.orEmpty())
        }
        recomputeAndPublish()
    }

    private fun attachMediaSessionListenerLocked(app: Context) {
        if (sessionsListenerRegistered) return
        if (!GeelyMediaNotificationListenerService.isEnabled(app)) return
        val mgr = sessionManager ?: (app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
            ?.also { sessionManager = it }
            ?: return
        val component = GeelyMediaNotificationListenerService.componentName(app)
        runCatching {
            val current = mgr.getActiveSessions(component).orEmpty()
            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, component, mainHandler)
            sessionsListenerRegistered = true
            syncControllersLocked(current)
            emitLog("GeelyVehicleMedia: MediaSessionManager attached (${current.size} active sessions)")
        }.onFailure { e ->
            sessionsListenerRegistered = false
            emitLog("GeelyVehicleMedia: MediaSessionManager attach failed (${e.javaClass.simpleName})")
        }
    }

    private fun detachMediaSessionListenerLocked() {
        if (sessionsListenerRegistered) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener) }
            sessionsListenerRegistered = false
        }
        for ((controller, cb) in trackedControllers) {
            runCatching { controller.unregisterCallback(cb) }
        }
        trackedControllers.clear()
    }

    private fun syncControllersLocked(controllers: List<MediaController>) {
        val ownPkg = appContext?.packageName
        val desired = controllers.filter { it.packageName != ownPkg }
        val desiredTokens = desired.map { it.sessionToken }.toSet()

        val iter = trackedControllers.entries.iterator()
        while (iter.hasNext()) {
            val (ctrl, cb) = iter.next()
            if (ctrl.sessionToken !in desiredTokens) {
                runCatching { ctrl.unregisterCallback(cb) }
                iter.remove()
            }
        }

        val existingTokens = trackedControllers.keys.map { it.sessionToken }.toSet()
        for (ctrl in desired) {
            if (ctrl.sessionToken in existingTokens) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    recomputeAndPublish()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    recomputeAndPublish()
                }

                override fun onSessionDestroyed() {
                    synchronized(lock) {
                        trackedControllers.remove(ctrl)
                    }
                    recomputeAndPublish()
                }
            }
            runCatching {
                ctrl.registerCallback(cb, mainHandler)
                trackedControllers[ctrl] = cb
            }
        }
    }

    private fun recomputeAndPublish() {
        val next = buildSnapshot()
        val listeners: List<(GeelyVehicleMediaSnapshot) -> Unit>
        synchronized(lock) {
            if (snapshotsEqual(latestSnapshot, next)) return
            latestSnapshot = next
            listeners = observers.values.toList()
        }
        runOnMain {
            listeners.forEach { cb -> runCatching { cb(next) } }
        }
    }

    private fun isSystemMusicAudioActive(context: Context?): Boolean = runCatching {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.isMusicActive == true
    }.getOrDefault(false)

    private fun buildSnapshot(): GeelyVehicleMediaSnapshot {
        val now = SystemClock.elapsedRealtime()
        var uriToFetch: String? = null
        var schedulePauseExpiryMs: Long? = null

        val snapshot = synchronized(lock) {
            val musicAudioActive = isSystemMusicAudioActive(appContext)
            val controllers = trackedControllers.keys.toList()
            val selected = selectBestController(controllers, musicAudioActive)

            if (selected != null) {
                val pkg = selected.packageName.orEmpty()
                val stateCode = selected.playbackState?.state ?: PlaybackState.STATE_NONE
                val inferredFromAudio =
                    (stateCode == PlaybackState.STATE_NONE || stateCode == PlaybackState.STATE_CONNECTING) && musicAudioActive
                val isPlaying = stateCode == PlaybackState.STATE_PLAYING ||
                    stateCode == PlaybackState.STATE_BUFFERING ||
                    stateCode == PlaybackState.STATE_FAST_FORWARDING ||
                    stateCode == PlaybackState.STATE_REWINDING ||
                    inferredFromAudio
                val isPaused = stateCode == PlaybackState.STATE_PAUSED

                val isActive = when {
                    isPlaying -> {
                        pausedSinceElapsedMs = 0L
                        if (inferredFromAudio) {
                            schedulePauseExpiryMs = 3_000L
                        }
                        true
                    }
                    isPaused -> {
                        if (pausedSinceElapsedMs == 0L) {
                            pausedSinceElapsedMs = now
                        }
                        val elapsedPaused = now - pausedSinceElapsedMs
                        if (elapsedPaused < PAUSED_RETENTION_MS) {
                            schedulePauseExpiryMs = PAUSED_RETENTION_MS - elapsedPaused + 50L
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        pausedSinceElapsedMs = 0L
                        false
                    }
                }

                if (!isActive) {
                    clearRetainedArtworkLocked()
                    return@synchronized GeelyVehicleMediaSnapshot()
                }

                val meta = selected.metadata
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() }

                // Resolve artwork: MediaMetadata Bitmap -> MediaMetadata URI -> Notification LargeIcon -> Retained
                var art = extractMetadataBitmap(meta)
                if (art == null) {
                    val artUri = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                            ?.trim()?.takeIf { it.isNotEmpty() }
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                            ?.trim()?.takeIf { it.isNotEmpty() }
                    if (artUri != null) {
                        val cached = uriBitmapCache[artUri]?.takeUnless { it.isRecycled }
                        if (cached != null) {
                            art = cached
                        } else {
                            pendingArtworkUri = artUri
                            uriToFetch = artUri
                        }
                    }
                }
                if (art == null) {
                    art = notifArtworkByPkg[pkg]?.takeUnless { it.isRecycled }
                }

                if (art != null && !art.isRecycled) {
                    retainedArtworkPkg = pkg
                    retainedArtworkArtist = artist
                    retainedArtworkAlbum = album
                    retainedArtworkBitmap = art
                } else if (retainedArtworkBitmap != null &&
                    retainedArtworkBitmap?.isRecycled == false &&
                    retainedArtworkPkg == pkg &&
                    (artist == null || retainedArtworkArtist == null || artist == retainedArtworkArtist) &&
                    (album == null || retainedArtworkAlbum == null || album == retainedArtworkAlbum)
                ) {
                    art = retainedArtworkBitmap
                }

                return@synchronized GeelyVehicleMediaSnapshot(
                    artwork = art,
                    playing = isPlaying,
                    active = true,
                    packageName = pkg,
                )
            }

            pausedSinceElapsedMs = 0L
            clearRetainedArtworkLocked()
            GeelyVehicleMediaSnapshot()
        }

        mainHandler.removeCallbacks(pauseExpiryTick)
        if (schedulePauseExpiryMs != null) {
            mainHandler.postDelayed(pauseExpiryTick, schedulePauseExpiryMs!!)
        }
        if (uriToFetch != null) {
            scheduleArtworkUriFetch(uriToFetch!!)
        }
        return snapshot
    }

    private fun selectBestController(
        controllers: List<MediaController>,
        musicAudioActive: Boolean,
    ): MediaController? {
        if (controllers.isEmpty()) return null
        fun score(c: MediaController): Int {
            val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
            val hasMeta = c.metadata?.let { m ->
                !m.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() ||
                    !m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNullOrBlank() ||
                    extractMetadataBitmap(m) != null
            } == true
            val stateScore = when (state) {
                PlaybackState.STATE_PLAYING -> 100
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING -> 90
                PlaybackState.STATE_NONE,
                PlaybackState.STATE_CONNECTING -> if (musicAudioActive && hasMeta) 65 else 0
                PlaybackState.STATE_PAUSED -> 40
                else -> 0
            }
            if (stateScore == 0) return 0
            val pkg = c.packageName.orEmpty().lowercase(Locale.ROOT)
            val musicBonus = if (KNOWN_MUSIC_PACKAGE_HINTS.any { it in pkg }) 10 else 0
            return stateScore + (if (hasMeta) 20 else 0) + musicBonus
        }
        return controllers
            .map { it to score(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun clearRetainedArtworkLocked() {
        retainedArtworkPkg = null
        retainedArtworkArtist = null
        retainedArtworkAlbum = null
        retainedArtworkBitmap = null
    }

    private fun extractMetadataBitmap(meta: MediaMetadata?): Bitmap? {
        if (meta == null) return null
        return meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.takeUnless { it.isRecycled }
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)?.takeUnless { it.isRecycled }
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.takeUnless { it.isRecycled }
    }

    private fun scheduleArtworkUriFetch(uriString: String) {
        val ctx = synchronized(lock) {
            if (uriBitmapCache.containsKey(uriString) || !uriInFlight.add(uriString)) return
            appContext ?: run {
                uriInFlight.remove(uriString)
                return
            }
        }
        ioExecutor.execute {
            val decoded = runCatching { decodeArtworkFromUri(ctx, uriString) }.getOrNull()
            val shouldPublish = synchronized(lock) {
                uriInFlight.remove(uriString)
                if (decoded != null && !decoded.isRecycled) {
                    uriBitmapCache[uriString] = decoded
                    while (uriBitmapCache.size > MAX_URI_CACHE_ENTRIES) {
                        val oldestKey = uriBitmapCache.entries.firstOrNull()?.key ?: break
                        uriBitmapCache.remove(oldestKey)
                    }
                    uriString == pendingArtworkUri
                } else {
                    false
                }
            }
            if (shouldPublish) {
                recomputeAndPublish()
            }
        }
    }

    private fun decodeArtworkFromUri(context: Context, rawUri: String): Bitmap? {
        val uri = Uri.parse(rawUri)
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val bytes: ByteArray = when (scheme) {
            "content", "file", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes) ?: return null
            }
            "http", "https" -> {
                // Android 9+ blocks cleartext HTTP by default; NetEase/QQ Music CDNs often report
                // `http://` URLs that support HTTPS natively, so try upgrading to HTTPS first.
                val candidates = if (scheme == "http") {
                    listOf("https://${rawUri.substring(7)}", rawUri)
                } else {
                    listOf(rawUri)
                }
                var downloaded: ByteArray? = null
                for (candidate in candidates) {
                    downloaded = runCatching { downloadUrlBytes(candidate) }.getOrNull()
                    if (downloaded != null && downloaded.isNotEmpty()) break
                }
                downloaded ?: return null
            }
            else -> return null
        }
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_ARTWORK_DIM_PX * 2 ||
            bounds.outHeight / sample > MAX_ARTWORK_DIM_PX * 2
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun downloadUrlBytes(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.use(InputStream::readBytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun isLikelyMediaNotification(pkg: String, notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        val lower = pkg.lowercase(Locale.ROOT)
        return KNOWN_MUSIC_PACKAGE_HINTS.any { it in lower }
    }

    private fun extractNotificationLargeBitmap(context: Context, notification: Notification): Bitmap? {
        runCatching {
            val icon = notification.getLargeIcon()
            if (icon != null) {
                val drawable = icon.loadDrawable(context)
                if (drawable != null) {
                    return drawableToBoundedBitmap(drawable)
                }
            }
        }
        @Suppress("DEPRECATION")
        return (notification.extras?.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)
            ?.takeUnless { it.isRecycled }
    }

    private fun drawableToBoundedBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled) return bmp
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_ARTWORK_DIM_PX) ?: 192
        val h = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_ARTWORK_DIM_PX) ?: 192
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(c)
            bmp
        }.getOrNull()
    }

    private fun snapshotsEqual(a: GeelyVehicleMediaSnapshot, b: GeelyVehicleMediaSnapshot): Boolean =
        a.active == b.active &&
            a.playing == b.playing &&
            a.artwork === b.artwork &&
            a.packageName == b.packageName

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun emitLog(msg: String) {
        Log.i(TAG, msg)
        runCatching { logger(msg) }
    }

    private val NOOP_LOG: (String) -> Unit = {}
}
