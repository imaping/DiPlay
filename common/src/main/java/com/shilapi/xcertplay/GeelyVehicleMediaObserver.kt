package com.shilapi.xcertplay

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Build
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
 * Snapshot of active head-unit local/Bluetooth music playback (NetEase Cloud Music, QQ Music,
 * Bluetooth A2DP/AVRCP, OEM media apps) used on the Geely cluster when CarPlay is not connected.
 */
internal data class GeelyVehicleMediaSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val lyricLine: String? = null,
    val artwork: Bitmap? = null,
    val playing: Boolean = false,
    val active: Boolean = false,
    val packageName: String? = null,
) {
    val primaryLine: String?
        get() = lyricLine?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }

    val secondaryLine: String?
        get() {
            val cleanTitle = title?.trim().orEmpty()
            val cleanArtist = artist?.trim().orEmpty()
            val cleanLyric = lyricLine?.trim().orEmpty()
            return if (cleanLyric.isNotEmpty()) {
                when {
                    cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty() && !cleanTitle.equals(cleanLyric, ignoreCase = true) ->
                        "$cleanTitle · $cleanArtist"
                    cleanTitle.isNotEmpty() && !cleanTitle.equals(cleanLyric, ignoreCase = true) ->
                        cleanTitle
                    cleanArtist.isNotEmpty() -> cleanArtist
                    else -> null
                }
            } else {
                cleanArtist.takeIf { it.isNotEmpty() }
            }
        }

    val singleLineDisplay: String?
        get() {
            val cleanLyric = lyricLine?.trim().orEmpty()
            if (cleanLyric.isNotEmpty()) return cleanLyric
            val cleanTitle = title?.trim().orEmpty()
            val cleanArtist = artist?.trim().orEmpty()
            return when {
                cleanTitle.isNotEmpty() && cleanArtist.isNotEmpty() &&
                    !cleanTitle.contains(cleanArtist, ignoreCase = true) -> "$cleanTitle · $cleanArtist"
                cleanTitle.isNotEmpty() -> cleanTitle
                cleanArtist.isNotEmpty() -> cleanArtist
                else -> null
            }
        }

    val hasContent: Boolean
        get() = active && (primaryLine != null || (artwork != null && !artwork.isRecycled))
}

/**
 * Lightweight NotificationListenerService enabling:
 * 1. Authorized [MediaSessionManager.getActiveSessions] access for NetEase Cloud Music, QQ Music,
 *    Bluetooth AVRCP, and other head-unit music apps.
 * 2. Real-time status-bar lyrics (`notification.tickerText`) and fallback album artwork icons
 *    pushed by NetEase Cloud Music / QQ Music when status-bar lyrics are enabled.
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
 * via [MediaSessionManager], [GeelyMediaNotificationListenerService] status-bar lyrics, and
 * legacy Android music broadcasts as a zero-permission fallback.
 */
internal object GeelyVehicleMediaObserver {
    private const val TAG = "GeelyVehicleMedia"
    private const val PAUSED_RETENTION_MS = 12_000L
    private const val LYRIC_FRESH_MS = 30_000L
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

    private val IGNORED_TICKER_PHRASES = setOf(
        "网易云音乐",
        "qq音乐",
        "爱趣听",
        "酷我音乐",
        "酷狗音乐",
        "蓝牙音乐",
        "正在播放",
        "播放中",
        "已暂停",
    )

    private data class NotifLyricEntry(
        val text: String,
        val updatedAtMs: Long,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "geely-vehicle-media").apply { isDaemon = true }
    }

    private val observers = linkedMapOf<Any, (GeelyVehicleMediaSnapshot) -> Unit>()
    private var appContext: Context? = null
    private var logger: (String) -> Unit = {}
    private var started = false

    // Active MediaSession tracking
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListenerRegistered = false
    private val trackedControllers = linkedMapOf<MediaController, MediaController.Callback>()

    // Notification-sourced real-time lyrics & fallback icons keyed by package name
    private val notifLyricsByPkg = HashMap<String, NotifLyricEntry>()
    private val notifArtworkByPkg = HashMap<String, Bitmap>()

    // Legacy broadcast fallback state
    private var broadcastReceiverRegistered = false
    private var broadcastTitle: String? = null
    private var broadcastArtist: String? = null
    private var broadcastAlbum: String? = null
    private var broadcastPlaying: Boolean = false
    private var broadcastUpdatedAtMs: Long = 0L

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

    /**
     * Gracefully sends `TransportControls.stop()` to active external head-unit music sessions
     * (e.g. NetEase Cloud Music, QQ Music) when CarPlay takes over playback, preventing stale
     * paused tracks from lingering on the factory QNX bottom media bar.
     */
    fun stopExternalPlayback(context: Context) {
        val app = context.applicationContext
        val ownPkg = app.packageName
        val controllers = synchronized(lock) {
            if (trackedControllers.isNotEmpty()) {
                trackedControllers.keys.toList()
            } else {
                emptyList()
            }
        }
        val targets = if (controllers.isNotEmpty()) {
            controllers
        } else if (GeelyMediaNotificationListenerService.isEnabled(app)) {
            runCatching {
                val mgr = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                mgr?.getActiveSessions(GeelyMediaNotificationListenerService.componentName(app))
                    .orEmpty()
                    .filter { it.packageName != ownPkg }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        for (ctrl in targets) {
            val state = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
            if (state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_PAUSED ||
                state == PlaybackState.STATE_BUFFERING
            ) {
                runCatching {
                    ctrl.transportControls.stop()
                    emitLog("GeelyVehicleMedia: sent stop() to ${ctrl.packageName} (prevState=$state)")
                }
            }
        }
        synchronized(lock) {
            broadcastPlaying = false
            pausedSinceElapsedMs = 0L
            clearRetainedArtworkLocked()
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
        val isMediaNotif = isLikelyMediaNotification(pkg, notification)
        if (!isMediaNotif) return

        var changed = false
        val now = SystemClock.elapsedRealtime()

        // 1. Extract status-bar real-time lyric from tickerText (NetEase Cloud Music / QQ Music)
        val rawTicker = notification.tickerText?.toString()?.trim().orEmpty()
        val cleanedTicker = sanitizeLyricCandidate(rawTicker)
        if (cleanedTicker != null) {
            synchronized(lock) {
                val prev = notifLyricsByPkg[pkg]
                if (prev == null || prev.text != cleanedTicker) {
                    notifLyricsByPkg[pkg] = NotifLyricEntry(cleanedTicker, now)
                    changed = true
                } else {
                    notifLyricsByPkg[pkg] = prev.copy(updatedAtMs = now)
                }
            }
        }

        // 2. Extract fallback album art from notification large icon
        val largeBitmap = extractNotificationLargeBitmap(context, notification)
        if (largeBitmap != null && !largeBitmap.isRecycled) {
            synchronized(lock) {
                if (notifArtworkByPkg[pkg] !== largeBitmap) {
                    notifArtworkByPkg[pkg] = largeBitmap
                    changed = true
                }
            }
        }

        if (changed) {
            recomputeAndPublish()
        }
    }

    internal fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        var changed = false
        synchronized(lock) {
            if (notifLyricsByPkg.remove(pkg) != null) changed = true
            if (notifArtworkByPkg.remove(pkg) != null) changed = true
        }
        if (changed) {
            recomputeAndPublish()
        }
    }

    private fun ensureStartedLocked(app: Context) {
        if (!started) {
            started = true
            registerBroadcastFallbackLocked(app)
        }
        attachMediaSessionListenerLocked(app)
    }

    private fun stopLocked() {
        started = false
        mainHandler.removeCallbacks(pauseExpiryTick)
        detachMediaSessionListenerLocked()
        unregisterBroadcastFallbackLocked()
        notifLyricsByPkg.clear()
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

    private val legacyBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val title = intent.getStringExtra("track")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("song")
            val artist = intent.getStringExtra("artist")
                ?: intent.getStringExtra("singer")
            val album = intent.getStringExtra("album")
            val hasPlayingExtra = intent.hasExtra("playing") || intent.hasExtra("isplaying")
            val playingExtra = when {
                intent.hasExtra("playing") -> intent.getBooleanExtra("playing", false)
                intent.hasExtra("isplaying") -> intent.getBooleanExtra("isplaying", false)
                action.endsWith("metachanged", ignoreCase = true) -> true
                else -> broadcastPlaying
            }

            synchronized(lock) {
                if (!title.isNullOrBlank()) broadcastTitle = title.trim()
                if (!artist.isNullOrBlank()) broadcastArtist = artist.trim()
                if (!album.isNullOrBlank()) broadcastAlbum = album.trim()
                if (hasPlayingExtra || action.endsWith("metachanged", ignoreCase = true)) {
                    broadcastPlaying = playingExtra
                }
                broadcastUpdatedAtMs = SystemClock.elapsedRealtime()
            }
            recomputeAndPublish()
        }
    }

    private fun registerBroadcastFallbackLocked(app: Context) {
        if (broadcastReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.netease.cloudmusic.metachanged")
            addAction("com.netease.cloudmusic.playstatechanged")
            addAction("com.tencent.qqmusic.metachanged")
            addAction("com.tencent.qqmusic.playstatechanged")
            addAction("com.tencent.qqmusic.ACTION_META_CHANGED")
            addAction("com.tencent.qqmusic.ACTION_PLAY_STATE_CHANGED")
            addAction("com.tencent.qqmusiccar.metachanged")
            addAction("com.tencent.qqmusiccar.playstatechanged")
            addAction("cn.kuwo.player.metachanged")
            addAction("cn.kuwo.player.playstatechanged")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(legacyBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                app.registerReceiver(legacyBroadcastReceiver, filter)
            }
            broadcastReceiverRegistered = true
        }
    }

    private fun unregisterBroadcastFallbackLocked() {
        if (!broadcastReceiverRegistered) return
        val app = appContext
        if (app != null) {
            runCatching { app.unregisterReceiver(legacyBroadcastReceiver) }
        }
        broadcastReceiverRegistered = false
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
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() }

                val lyricEntry = notifLyricsByPkg[pkg]
                val freshLyric = lyricEntry?.takeIf { now - it.updatedAtMs <= LYRIC_FRESH_MS }?.text
                    ?.takeIf { lyric ->
                        !lyric.equals(title.orEmpty(), ignoreCase = true)
                    }

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
                    title = title,
                    artist = artist,
                    album = album,
                    lyricLine = freshLyric,
                    artwork = art,
                    playing = isPlaying,
                    active = true,
                    packageName = pkg,
                )
            }

            // Fallback: legacy broadcast when MediaSessionManager is unavailable or empty
            if (broadcastPlaying && !broadcastTitle.isNullOrBlank() &&
                (now - broadcastUpdatedAtMs) <= 180_000L
            ) {
                pausedSinceElapsedMs = 0L
                val anyFreshLyric = notifLyricsByPkg.values
                    .filter { now - it.updatedAtMs <= LYRIC_FRESH_MS }
                    .maxByOrNull { it.updatedAtMs }
                    ?.text
                val anyArt = notifArtworkByPkg.values.firstOrNull { !it.isRecycled }
                return@synchronized GeelyVehicleMediaSnapshot(
                    title = broadcastTitle,
                    artist = broadcastArtist,
                    album = broadcastAlbum,
                    lyricLine = anyFreshLyric,
                    artwork = anyArt,
                    playing = true,
                    active = true,
                    packageName = "broadcast",
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

    private fun sanitizeLyricCandidate(raw: String): String? {
        if (raw.isEmpty() || raw.length > 96) return null
        val clean = raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase(Locale.ROOT)
        if (lower in IGNORED_TICKER_PHRASES) return null
        if (IGNORED_STATUS_SUBSTRINGS.any { it in clean }) return null
        return clean
    }

    private val IGNORED_STATUS_SUBSTRINGS = listOf(
        "正在运行",
        "后台运行",
        "运行中",
        "点击返回",
        "点击打开",
        "点击进入",
        "点击查看",
    )

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
            a.title == b.title &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.lyricLine == b.lyricLine &&
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
