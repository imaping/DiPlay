package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.update.InternetNetworkSelector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Unified playback snapshot fed into [GeelyClusterLyricsEngine] from either CarPlay iAP2
 * (`ClusterMusicState`) or Android head-unit / Bluetooth media sessions (`GeelyVehicleMediaObserver`).
 */
internal data class GeelyClusterMediaTrack(
    val source: Source = Source.NONE,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val positionMillis: Long? = null,
    val positionAtElapsedMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val playing: Boolean = false,
    val artworkIdentity: Int = 0,
) {
    enum class Source {
        NONE,
        CARPLAY,
        VEHICLE_LOCAL,
    }

    val hasAnyMetadata: Boolean
        get() = source != Source.NONE &&
            (!title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank())

    fun estimatedElapsedMillis(nowElapsedMs: Long): Long? {
        val base = positionMillis ?: return null
        val advanced = if (playing && positionAtElapsedMs > 0L && nowElapsedMs > positionAtElapsedMs) {
            val delta = ((nowElapsedMs - positionAtElapsedMs) * playbackSpeed.coerceIn(0.25f, 3f)).toLong()
            base + delta
        } else {
            base
        }
        val dur = durationMillis?.takeIf { it > 0L }
        return if (dur != null) advanced.coerceIn(0L, dur) else advanced.coerceAtLeast(0L)
    }
}

internal data class GeelyLyricLine(
    val timeMs: Long,
    val text: String,
    val durationMs: Long = 3_800L,
)

internal enum class GeelyLyricsDisplayMode {
    /** Full timestamped LRC lyrics with lookahead lines and synced karaoke sweep. */
    SYNCED_LRC,

    /** Zero-latency real-time lyric stream pushed line-by-line via title metadata. */
    LIVE_STREAM,

    /** Track metadata available, no lyric lines yet (shows clean title/artist fallback). */
    METADATA_ONLY,
}

internal data class GeelyLyricsRenderFrame(
    val mode: GeelyLyricsDisplayMode,
    val displayTitle: String,
    val displayArtist: String,
    val lines: List<GeelyLyricLine>,
    val activeIndex: Int,
    val lineProgress: Float,
    val elapsedMs: Long?,
    val durationMs: Long?,
    val playing: Boolean,
)

/**
 * Dual-track lyrics engine for the Geely cluster center album theater:
 * 1. **Live stream track (zero-latency offline fallback)**: Detects lyric-in-title updates from
 *    CarPlay and Android music apps (QQ Music, NetEase Cloud Music, Bluetooth AVRCP) and maintains
 *    a rolling history buffer for upward push transitions.
 * 2. **Online LRC track (optional enhancement)**: Fetches timestamped LRC lyrics asynchronously
 *    over the validated internet route ([InternetNetworkSelector], keeping Wireless CarPlay intact)
 *    with in-memory + disk caching, enabling lookahead lines and synced karaoke sweep.
 */
internal class GeelyClusterLyricsEngine(
    context: Context,
    private val onLyricsUpdated: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var onlineLrcEnabled: Boolean = true
    private var currentTrack: GeelyClusterMediaTrack = GeelyClusterMediaTrack()
    private var trackIdentityKey: String = ""
    private var initialTrackTitle: String? = null
    private var initialTitleIsLyricOrCredit: Boolean = false
    private var matchedViaFallbackMeta: Boolean = false
    private var lastRawTitle: String? = null
    private var lastTitleUpdateUptimeMs: Long = 0L

    // Track 1: live lyric stream history (when music app pushes lyric lines in title)
    private val streamLines = ArrayList<GeelyLyricLine>(MAX_STREAM_LINES)
    private var streamConfirmed: Boolean = false
    private var streamAvgCadenceMs: Long = 3_800L
    private var lastStreamPushUptimeMs: Long = 0L

    // Track 2: timestamped LRC lines fetched asynchronously
    private var syncedLrcLines: List<GeelyLyricLine> = emptyList()
    private var syncedLrcTrackKey: String = ""
    private var lastLookupAttemptKey: String = ""

    fun setOnlineLrcEnabled(enabled: Boolean) {
        val shouldFetch: Boolean
        val trackSnap: GeelyClusterMediaTrack
        val keySnap: String
        val seedTitle: String?
        synchronized(lock) {
            if (onlineLrcEnabled == enabled) return
            onlineLrcEnabled = enabled
            if (!enabled) {
                syncedLrcLines = emptyList()
                syncedLrcTrackKey = ""
                shouldFetch = false
                trackSnap = currentTrack
                keySnap = trackIdentityKey
                seedTitle = initialTrackTitle
            } else {
                shouldFetch = trackIdentityKey.isNotEmpty() && syncedLrcTrackKey != trackIdentityKey
                trackSnap = currentTrack
                keySnap = trackIdentityKey
                seedTitle = initialTrackTitle
            }
        }
        if (shouldFetch) {
            maybeScheduleOnlineFetch(keySnap, trackSnap, seedTitle)
        }
        notifyUpdated()
    }

    fun clear() {
        synchronized(lock) {
            if (currentTrack.source == GeelyClusterMediaTrack.Source.NONE &&
                streamLines.isEmpty() &&
                syncedLrcLines.isEmpty()
            ) {
                return
            }
            currentTrack = GeelyClusterMediaTrack()
            trackIdentityKey = ""
            initialTrackTitle = null
            initialTitleIsLyricOrCredit = false
            matchedViaFallbackMeta = false
            lastRawTitle = null
            lastTitleUpdateUptimeMs = 0L
            streamLines.clear()
            streamConfirmed = false
            streamAvgCadenceMs = 3_800L
            lastStreamPushUptimeMs = 0L
            syncedLrcLines = emptyList()
            syncedLrcTrackKey = ""
            lastLookupAttemptKey = ""
        }
        notifyUpdated()
    }

    fun updateTrack(next: GeelyClusterMediaTrack) {
        if (!next.hasAnyMetadata) {
            clear()
            return
        }
        val nowUptime = SystemClock.uptimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        var fetchKey: String? = null
        var fetchTrack: GeelyClusterMediaTrack? = null
        var fetchTitle: String? = null
        var changed = false

        synchronized(lock) {
            val prev = currentTrack
            val isNewTrack = prev.source == GeelyClusterMediaTrack.Source.NONE || isTrackChanged(prev, next, nowElapsed)
            val cleanedTitle = next.title?.trim()?.takeIf { it.isNotEmpty() }

            if (isNewTrack) {
                val looksLikeCredit = cleanedTitle != null && isLikelyCreditLine(cleanedTitle)
                currentTrack = next
                trackIdentityKey = buildTrackKey(next, cleanedTitle)
                initialTrackTitle = cleanedTitle
                initialTitleIsLyricOrCredit = looksLikeCredit
                matchedViaFallbackMeta = false
                lastRawTitle = cleanedTitle
                lastTitleUpdateUptimeMs = nowUptime
                streamLines.clear()
                streamConfirmed = looksLikeCredit
                streamAvgCadenceMs = 3_800L
                lastStreamPushUptimeMs = if (looksLikeCredit) nowUptime else 0L
                syncedLrcLines = emptyList()
                syncedLrcTrackKey = ""
                lastLookupAttemptKey = ""
                if (cleanedTitle != null) {
                    streamLines.add(GeelyLyricLine(timeMs = nowUptime, text = cleanedTitle, durationMs = 4_200L))
                }
                if (onlineLrcEnabled) {
                    fetchKey = trackIdentityKey
                    fetchTrack = next
                    fetchTitle = cleanedTitle
                }
                changed = true
            } else {
                // Same track: update playback state/position and check for live lyric-in-title updates.
                val titleChanged = cleanedTitle != null && cleanedTitle != lastRawTitle
                if (titleChanged && cleanedTitle != null) {
                    val deltaMs = if (lastTitleUpdateUptimeMs > 0L) nowUptime - lastTitleUpdateUptimeMs else 3_800L
                    if (deltaMs in 700L..14_000L) {
                        streamAvgCadenceMs = ((streamAvgCadenceMs * 3L + deltaMs) / 4L).coerceIn(1_800L, 6_500L)
                    }
                    // If the previous first line was just the track title ("七里香") and a lyric just arrived,
                    // keep it in initialTrackTitle for the song capsule and start fresh lyric stream cleanly.
                    val wasJustSeedTitle = !streamConfirmed &&
                        streamLines.size == 1 &&
                        streamLines[0].text == initialTrackTitle
                    if (wasJustSeedTitle) {
                        streamLines.clear()
                    }
                    if (streamLines.isNotEmpty()) {
                        val prevIdx = streamLines.lastIndex
                        val prevLine = streamLines[prevIdx]
                        val actualDur = (nowUptime - prevLine.timeMs).coerceIn(1_200L, 10_000L)
                        streamLines[prevIdx] = prevLine.copy(durationMs = actualDur)
                    }
                    // Avoid duplicate back-to-back lines
                    if (streamLines.lastOrNull()?.text != cleanedTitle) {
                        streamLines.add(
                            GeelyLyricLine(
                                timeMs = nowUptime,
                                text = cleanedTitle,
                                durationMs = streamAvgCadenceMs,
                            ),
                        )
                        if (streamLines.size > MAX_STREAM_LINES) {
                            streamLines.removeAt(0)
                        }
                    }
                    streamConfirmed = true
                    lastRawTitle = cleanedTitle
                    lastTitleUpdateUptimeMs = nowUptime
                    lastStreamPushUptimeMs = nowUptime
                    changed = true
                }

                // Merge refined metadata (e.g. artwork / artist / duration arrived slightly after first frame)
                val mergedArtist = next.artist?.takeIf { it.isNotBlank() } ?: prev.artist
                val mergedAlbum = next.album?.takeIf { it.isNotBlank() } ?: prev.album
                val mergedDuration = next.durationMillis?.takeIf { it > 0L } ?: prev.durationMillis
                val mergedPos = next.positionMillis ?: prev.positionMillis
                val mergedPosAt = if (next.positionMillis != null) next.positionAtElapsedMs else prev.positionAtElapsedMs

                val updated = next.copy(
                    artist = mergedArtist,
                    album = mergedAlbum,
                    durationMillis = mergedDuration,
                    positionMillis = mergedPos,
                    positionAtElapsedMs = mergedPosAt,
                )
                if (updated != prev) {
                    currentTrack = updated
                    changed = true
                }

                // Retry online LRC lookup once artist or duration becomes available if initial attempt lacked them
                if (onlineLrcEnabled &&
                    syncedLrcLines.isEmpty() &&
                    trackIdentityKey.isNotEmpty()
                ) {
                    val refinedKey = buildTrackKey(updated, initialTrackTitle)
                    if (refinedKey != lastLookupAttemptKey && (!mergedArtist.isNullOrBlank() || mergedDuration != null)) {
                        fetchKey = trackIdentityKey
                        fetchTrack = updated
                        fetchTitle = initialTrackTitle
                    }
                }
            }
        }

        if (fetchKey != null && fetchTrack != null) {
            maybeScheduleOnlineFetch(fetchKey!!, fetchTrack!!, fetchTitle)
        }
        if (changed) {
            notifyUpdated()
        }
    }

    /**
     * Computes the instantaneous lyrics state for the current animation frame.
     * Safe to invoke from `View.onDraw` at 60 fps.
     */
    fun snapshotFrame(nowElapsedMs: Long, nowUptimeMs: Long): GeelyLyricsRenderFrame? {
        synchronized(lock) {
            if (!currentTrack.hasAnyMetadata) return null
            val track = currentTrack
            val elapsedMs = track.estimatedElapsedMillis(nowElapsedMs)
            val durationMs = track.durationMillis?.takeIf { it > 0L }

            val (capsuleTitle, capsuleArtist) = resolveCapsuleMeta(track)

            // 1. Prefer synced LRC when available and playback timeline is known
            val hasSyncedLrc = onlineLrcEnabled &&
                syncedLrcTrackKey == trackIdentityKey &&
                syncedLrcLines.size >= 2 &&
                elapsedMs != null

            if (hasSyncedLrc && elapsedMs != null) {
                val lines = syncedLrcLines
                var activeIdx = 0
                for (i in lines.indices) {
                    if (elapsedMs >= lines[i].timeMs) {
                        activeIdx = i
                    } else {
                        break
                    }
                }
                val cur = lines[activeIdx]
                val nextStart = lines.getOrNull(activeIdx + 1)?.timeMs
                val span = when {
                    nextStart != null && nextStart > cur.timeMs -> (nextStart - cur.timeMs).coerceIn(900L, 9_500L)
                    else -> cur.durationMs.coerceIn(1_500L, 8_000L)
                }
                val prog = if (elapsedMs < cur.timeMs) {
                    0f
                } else {
                    ((elapsedMs - cur.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                }
                return GeelyLyricsRenderFrame(
                    mode = GeelyLyricsDisplayMode.SYNCED_LRC,
                    displayTitle = capsuleTitle,
                    displayArtist = capsuleArtist,
                    lines = lines,
                    activeIndex = activeIdx,
                    lineProgress = prog,
                    elapsedMs = elapsedMs,
                    durationMs = durationMs,
                    playing = track.playing,
                )
            }

            // 2. Fallback to zero-latency live streamed lyrics (QQ Music / NetEase / Bluetooth lyric-in-title)
            if (streamConfirmed && streamLines.isNotEmpty()) {
                val lines = ArrayList(streamLines)
                val activeIdx = lines.lastIndex
                val cur = lines[activeIdx]
                val estSpan = streamAvgCadenceMs.coerceIn(2_000L, 6_500L)
                val prog = if (track.playing) {
                    ((nowUptimeMs - cur.timeMs).toFloat() / estSpan.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }
                return GeelyLyricsRenderFrame(
                    mode = GeelyLyricsDisplayMode.LIVE_STREAM,
                    displayTitle = capsuleTitle,
                    displayArtist = capsuleArtist,
                    lines = lines,
                    activeIndex = activeIdx,
                    lineProgress = prog,
                    elapsedMs = elapsedMs,
                    durationMs = durationMs,
                    playing = track.playing,
                )
            }

            // 3. Clean metadata-only mode (waiting for LRC or track has no lyrics)
            return GeelyLyricsRenderFrame(
                mode = GeelyLyricsDisplayMode.METADATA_ONLY,
                displayTitle = capsuleTitle,
                displayArtist = capsuleArtist,
                lines = emptyList(),
                activeIndex = 0,
                lineProgress = 0f,
                elapsedMs = elapsedMs,
                durationMs = durationMs,
                playing = track.playing,
            )
        }
    }

    private fun resolveCapsuleMeta(track: GeelyClusterMediaTrack): Pair<String, String> {
        val seed = initialTrackTitle?.trim().orEmpty()
        val artist = track.artist?.trim().orEmpty()
        val album = track.album?.trim().orEmpty()
        val hideSeedTitle = initialTitleIsLyricOrCredit ||
            matchedViaFallbackMeta ||
            (streamConfirmed && seed.isNotEmpty() && isLikelyLyricSentence(seed))

        if (hideSeedTitle && (artist.isNotEmpty() || album.isNotEmpty())) {
            return when {
                artist.isNotEmpty() && album.isNotEmpty() && !album.equals(artist, ignoreCase = true) ->
                    artist to album
                artist.isNotEmpty() -> artist to ""
                else -> album to ""
            }
        }

        val title = seed.ifEmpty { track.title?.trim().orEmpty().ifEmpty { album } }
        val sub = when {
            artist.isNotEmpty() && album.isNotEmpty() && !album.equals(artist, ignoreCase = true) &&
                !album.equals(title, ignoreCase = true) -> "$artist · $album"
            artist.isNotEmpty() && !artist.equals(title, ignoreCase = true) -> artist
            album.isNotEmpty() && !album.equals(title, ignoreCase = true) -> album
            else -> ""
        }
        return title to sub
    }

    private fun isTrackChanged(
        prev: GeelyClusterMediaTrack,
        next: GeelyClusterMediaTrack,
        nowElapsedMs: Long,
    ): Boolean {
        if (prev.source != next.source) return true
        if (!prev.sourceApp.isNullOrBlank() && !next.sourceApp.isNullOrBlank() && prev.sourceApp != next.sourceApp) {
            return true
        }
        if (prev.artworkIdentity != 0 && next.artworkIdentity != 0 && prev.artworkIdentity != next.artworkIdentity) {
            return true
        }
        if (!prev.artist.isNullOrBlank() && !next.artist.isNullOrBlank() &&
            !prev.artist.equals(next.artist, ignoreCase = true)
        ) {
            return true
        }
        if (!prev.album.isNullOrBlank() && !next.album.isNullOrBlank() &&
            !prev.album.equals(next.album, ignoreCase = true)
        ) {
            return true
        }
        val prevDur = prev.durationMillis?.takeIf { it > 0L }
        val nextDur = next.durationMillis?.takeIf { it > 0L }
        if (prevDur != null && nextDur != null && abs(prevDur - nextDur) > 2_500L) {
            return true
        }
        // Large backward jump from >15s to <3s with a different title indicates next/previous track
        val prevEst = prev.estimatedElapsedMillis(nowElapsedMs)
        val nextPos = next.positionMillis
        if (prev.title != next.title &&
            prevEst != null && prevEst > 15_000L &&
            nextPos != null && nextPos < 3_000L
        ) {
            return true
        }
        // If neither artist/album/duration/artwork is available to anchor identity, treat distinct titles
        // separated by >20s or identical-app non-lyric switches conservatively: keep same track so
        // Bluetooth lyric-in-title still flows when AVRCP omits artist/album.
        return false
    }

    private fun buildTrackKey(track: GeelyClusterMediaTrack, seedTitle: String?): String {
        val t = normalizeQueryPart(seedTitle ?: track.title)
        val a = normalizeQueryPart(track.artist)
        val al = normalizeQueryPart(track.album)
        val durBucket = ((track.durationMillis ?: 0L) / 2_000L)
        return "${track.source.name}|$t|$a|$al|$durBucket|${track.artworkIdentity}"
    }

    private fun maybeScheduleOnlineFetch(
        expectedTrackKey: String,
        track: GeelyClusterMediaTrack,
        seedTitle: String?,
    ) {
        val rawTitle = (seedTitle ?: track.title)?.trim().orEmpty()
        val artist = track.artist?.trim().orEmpty()
        val album = track.album?.trim().orEmpty()
        val skipRawTitle = rawTitle.isEmpty() ||
            isLikelyCreditLine(rawTitle) ||
            (artist.isEmpty() && album.isEmpty() && rawTitle.length > 26)

        val candidates = ArrayList<GeelyLrcQueryCandidate>(3)
        val seenKeys = HashSet<String>(4)
        fun addCandidate(qTitle: String, qArtist: String, qAlbum: String, isFallback: Boolean) {
            val clean = stripTitleDecorations(qTitle, qArtist)
            if (clean.isEmpty()) return
            val key = buildCacheKey(clean, qArtist, track.durationMillis)
            if (seenKeys.add(key)) {
                candidates.add(GeelyLrcQueryCandidate(key, clean, qArtist, qAlbum, isFallback))
            }
        }

        if (!skipRawTitle) {
            addCandidate(rawTitle, artist, album, isFallback = false)
        }
        // CarPlay QQ Music / NetEase Bluetooth-lyrics mode often puts lyric in `title`,
        // real song title in `artist`, and singer name in `album` (or `album` = title, `artist` = singer).
        if (artist.isNotBlank() && album.isNotBlank() && !artist.equals(rawTitle, ignoreCase = true)) {
            addCandidate(artist, album, "", isFallback = true)
        }
        if (album.isNotBlank() && !album.equals(rawTitle, ignoreCase = true) && !album.equals(artist, ignoreCase = true)) {
            addCandidate(album, artist, "", isFallback = true)
        }
        if (candidates.isEmpty() && artist.isNotBlank() && skipRawTitle) {
            addCandidate(artist, "", "", isFallback = true)
        }
        if (candidates.isEmpty()) return

        synchronized(lock) {
            if (!onlineLrcEnabled || trackIdentityKey != expectedTrackKey) return
            lastLookupAttemptKey = buildTrackKey(track, seedTitle)
        }

        // Fast-path: check memory cache across candidates synchronously
        for (cand in candidates) {
            val cached = GeelyOnlineLrcRepository.getMemoryCached(cand.cacheKey)
            if (cached != null && cached.size >= 2) {
                applyFetchedLrc(expectedTrackKey, cached, cand.isFallback)
                return
            }
        }

        GeelyOnlineLrcRepository.fetchCandidatesAsync(
            context = appContext,
            candidates = candidates,
            durationMs = track.durationMillis,
        ) { lines, usedFallback ->
            if (lines.isNotEmpty()) {
                applyFetchedLrc(expectedTrackKey, lines, usedFallback)
            }
        }
    }

    private fun applyFetchedLrc(
        expectedTrackKey: String,
        lines: List<GeelyLyricLine>,
        usedFallbackMeta: Boolean,
    ) {
        if (lines.size < 2) return
        val applied = synchronized(lock) {
            if (!onlineLrcEnabled || trackIdentityKey != expectedTrackKey) {
                false
            } else {
                syncedLrcLines = lines
                syncedLrcTrackKey = expectedTrackKey
                if (usedFallbackMeta) {
                    matchedViaFallbackMeta = true
                }
                true
            }
        }
        if (applied) {
            notifyUpdated()
        }
    }

    private fun notifyUpdated() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onLyricsUpdated()
        } else {
            mainHandler.post(onLyricsUpdated)
        }
    }

    companion object {
        private const val MAX_STREAM_LINES = 40
        private val CREDIT_LINE_PATTERN: Pattern = Pattern.compile(
            "^(作词|作曲|词曲|词|曲|编曲|制作人?|监制|统筹|出品人?|发行|混音|母带|录音|吉他|贝斯|鼓手?|钢琴|弦乐|和声|配唱|企划|宣发|宣传|文案|原唱|翻唱|演唱|人声|剪辑|策划|OP|SP|Lyricist|Composer|Arranger|Producer)\\s*[:：\\-]",
            Pattern.CASE_INSENSITIVE,
        )

        private fun isLikelyCreditLine(raw: String): Boolean =
            CREDIT_LINE_PATTERN.matcher(raw.trim()).find()

        private fun isLikelyLyricSentence(raw: String): Boolean {
            val s = raw.trim()
            if (isLikelyCreditLine(s)) return true
            if (s.length >= 16 && (s.contains(' ') || s.contains('，') || s.contains(',') || s.contains('。'))) {
                return true
            }
            return false
        }

        private fun normalizeQueryPart(raw: String?): String =
            raw?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ").orEmpty()

        private fun buildCacheKey(title: String, artist: String, durationMs: Long?): String {
            val durSec = ((durationMs ?: 0L) / 1_000L)
            return "${title.lowercase(Locale.ROOT)}|${artist.lowercase(Locale.ROOT)}|${durSec / 3}"
        }

        /** Strips trailing " - Artist" or "(Live)" suffixes only when redundant for cleaner lookup. */
        private fun stripTitleDecorations(rawTitle: String, artist: String): String {
            var s = rawTitle.trim()
            if (artist.isNotEmpty()) {
                val sepIdx = s.lastIndexOf(" - ")
                if (sepIdx > 0) {
                    val tail = s.substring(sepIdx + 3).trim()
                    if (tail.equals(artist, ignoreCase = true) || artist.contains(tail, ignoreCase = true)) {
                        s = s.substring(0, sepIdx).trim()
                    }
                }
            }
            return s
        }
    }
}

internal data class GeelyLrcQueryCandidate(
    val cacheKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val isFallback: Boolean,
)

/**
 * Lightweight HTTPS LRC repository with in-memory LRU + disk cache.
 * Uses [InternetNetworkSelector] so requests traverse the vehicle's cellular/internet interface
 * even while Wireless CarPlay is active on Wi-Fi.
 */
internal object GeelyOnlineLrcRepository {
    private const val TAG = "GeelyClusterLrc"
    private const val MAX_MEM_ENTRIES = 48
    private const val MAX_DISK_FILES = 120
    private const val DISK_TTL_MS = 14L * 24L * 60L * 60L * 1000L
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 4_500
    private const val MAX_BODY_BYTES = 512 * 1024

    private val lock = Any()
    private val memCache = LinkedHashMap<String, List<GeelyLyricLine>>(MAX_MEM_ENTRIES + 1, 0.75f, true)
    private val inFlight = HashMap<String, MutableList<(List<GeelyLyricLine>, Boolean) -> Unit>>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "geely-cluster-lyrics").apply { isDaemon = true }
    }

    fun getMemoryCached(cacheKey: String): List<GeelyLyricLine>? = synchronized(lock) {
        memCache[cacheKey]
    }

    fun fetchCandidatesAsync(
        context: Context,
        candidates: List<GeelyLrcQueryCandidate>,
        durationMs: Long?,
        onResult: (List<GeelyLyricLine>, Boolean) -> Unit,
    ) {
        if (candidates.isEmpty()) return
        val batchKey = candidates.joinToString("||") { it.cacheKey }
        val shouldStart: Boolean = synchronized(lock) {
            for (cand in candidates) {
                val hit = memCache[cand.cacheKey]
                if (hit != null && hit.size >= 2) {
                    onResult(hit, cand.isFallback)
                    return
                }
            }
            if (candidates.all { memCache.containsKey(it.cacheKey) }) {
                onResult(emptyList(), false)
                return
            }
            val waiters = inFlight[batchKey]
            if (waiters != null) {
                waiters.add(onResult)
                false
            } else {
                inFlight[batchKey] = mutableListOf(onResult)
                true
            }
        }
        if (!shouldStart) return

        val appCtx = context.applicationContext
        executor.execute {
            var matchedLines: List<GeelyLyricLine> = emptyList()
            var matchedFallback = false

            for (cand in candidates) {
                val existing = synchronized(lock) { memCache[cand.cacheKey] }
                if (existing != null) {
                    if (existing.size >= 2) {
                        matchedLines = existing
                        matchedFallback = cand.isFallback
                        break
                    }
                    continue
                }

                val parsed = runCatching {
                    loadFromDiskCache(appCtx, cand.cacheKey)
                        ?: fetchRemoteLrc(appCtx, cand.title, cand.artist, cand.album, durationMs)?.also { rawLrc ->
                            saveToDiskCache(appCtx, cand.cacheKey, rawLrc)
                        }
                }.getOrNull()?.let { raw ->
                    GeelyLrcParser.parse(raw)
                }.orEmpty()

                synchronized(lock) {
                    memCache[cand.cacheKey] = parsed
                    while (memCache.size > MAX_MEM_ENTRIES) {
                        val oldest = memCache.entries.firstOrNull()?.key ?: break
                        memCache.remove(oldest)
                    }
                }

                if (parsed.size >= 2) {
                    matchedLines = parsed
                    matchedFallback = cand.isFallback
                    break
                }
            }

            val callbacks: List<(List<GeelyLyricLine>, Boolean) -> Unit> = synchronized(lock) {
                inFlight.remove(batchKey).orEmpty()
            }
            callbacks.forEach { cb -> runCatching { cb(matchedLines, matchedFallback) } }
        }
    }

    private fun fetchRemoteLrc(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
    ): String? {
        // Provider 1: NetEase Cloud Music public search + lyric (fast & strong Chinese catalog)
        runCatching {
            fetchFromNetEase(context, title, artist, durationMs)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

        // Provider 2: LRCLIB open synced lyrics API (clean global catalog)
        runCatching {
            fetchFromLrcLib(context, title, artist, album, durationMs)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun fetchFromNetEase(
        context: Context,
        title: String,
        artist: String,
        durationMs: Long?,
    ): String? {
        val query = if (artist.isNotBlank()) "$title $artist" else title
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://music.163.com/api/search/get/web?csrf_token=&type=1&offset=0&limit=6&s=$encoded"
        val searchBody = httpGet(
            context = context,
            urlString = searchUrl,
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 11) DiPlayCluster/1.0",
            ),
        ) ?: return null

        val songs = JSONObject(searchBody)
            .optJSONObject("result")
            ?.optJSONArray("songs")
            ?: return null
        if (songs.length() == 0) return null

        val bestId = selectBestNetEaseSongId(songs, title, artist, durationMs) ?: return null
        val lyricUrl = "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=1&tv=-1"
        val lyricBody = httpGet(
            context = context,
            urlString = lyricUrl,
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 11) DiPlayCluster/1.0",
            ),
        ) ?: return null

        val rawLrc = JSONObject(lyricBody)
            .optJSONObject("lrc")
            ?.optString("lyric")
            ?.trim()
            .orEmpty()
        return rawLrc.takeIf { it.contains('[') && GeelyLrcParser.parse(it).size >= 2 }
    }

    private fun selectBestNetEaseSongId(
        songs: JSONArray,
        targetTitle: String,
        targetArtist: String,
        durationMs: Long?,
    ): Long? {
        val normTitle = targetTitle.trim().lowercase(Locale.ROOT)
        val normArtist = targetArtist.trim().lowercase(Locale.ROOT)
        var bestId: Long? = null
        var bestScore = Int.MIN_VALUE

        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            if (id <= 0L) continue
            val name = song.optString("name", "").trim().lowercase(Locale.ROOT)
            val dur = song.optLong("duration", 0L)
            val artistsArr = song.optJSONArray("artists")
            val artistNames = buildString {
                if (artistsArr != null) {
                    for (j in 0 until artistsArr.length()) {
                        val aName = artistsArr.optJSONObject(j)?.optString("name", "").orEmpty()
                        if (aName.isNotEmpty()) {
                            if (isNotEmpty()) append(' ')
                            append(aName.lowercase(Locale.ROOT))
                        }
                    }
                }
            }

            var score = 0
            when {
                name == normTitle -> score += 60
                name.contains(normTitle) || normTitle.contains(name) -> score += 32
                else -> score -= 10
            }
            if (normArtist.isNotEmpty()) {
                when {
                    artistNames.contains(normArtist) || normArtist.contains(artistNames) && artistNames.isNotEmpty() -> score += 45
                    normArtist.split('/', '、', ',', '&', ' ').any { part ->
                        part.length >= 2 && artistNames.contains(part)
                    } -> score += 30
                    else -> score -= 15
                }
            }
            if (durationMs != null && durationMs > 0L && dur > 0L) {
                val diffSec = abs(dur - durationMs) / 1_000L
                score += when {
                    diffSec <= 3L -> 35
                    diffSec <= 8L -> 18
                    diffSec <= 18L -> 4
                    else -> -25
                }
            }
            // Slight preference for top search ranking on ties
            score -= i * 2
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return if (bestScore >= 15) bestId else null
    }

    private fun fetchFromLrcLib(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long?,
    ): String? {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "DiPlayCluster/1.0 (https://github.com/shihabal3amri/DiPlay)",
        )

        // 1. Exact get if artist is known
        if (artist.isNotBlank()) {
            val sb = StringBuilder("https://lrclib.net/api/get?track_name=$encTitle&artist_name=$encArtist")
            if (album.isNotBlank()) {
                sb.append("&album_name=").append(URLEncoder.encode(album, "UTF-8"))
            }
            val durSec = durationMs?.takeIf { it > 0L }?.let { (it / 1_000L).toInt() }
            if (durSec != null) {
                sb.append("&duration=").append(durSec)
            }
            httpGet(context, sb.toString(), headers)?.let { body ->
                val synced = JSONObject(body).optString("syncedLyrics", "").trim()
                if (synced.contains('[') && GeelyLrcParser.parse(synced).size >= 2) {
                    return synced
                }
            }
        }

        // 2. Search fallback
        val searchUrl = if (artist.isNotBlank()) {
            "https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist"
        } else {
            "https://lrclib.net/api/search?track_name=$encTitle"
        }
        val body = httpGet(context, searchUrl, headers) ?: return null
        val arr = JSONArray(body)
        val targetDurSec = durationMs?.takeIf { it > 0L }?.div(1_000.0)
        var bestLrc: String? = null
        var bestDiff = Double.MAX_VALUE
        for (i in 0 until minOf(arr.length(), 8)) {
            val item = arr.optJSONObject(i) ?: continue
            val synced = item.optString("syncedLyrics", "").trim()
            if (!synced.contains('[') || GeelyLrcParser.parse(synced).size < 2) continue
            if (targetDurSec == null) return synced
            val itemDur = item.optDouble("duration", -1.0)
            val diff = if (itemDur > 0.0) abs(itemDur - targetDurSec) else 999.0
            if (diff < bestDiff) {
                bestDiff = diff
                bestLrc = synced
            }
        }
        return if (targetDurSec == null || bestDiff <= 15.0) bestLrc else null
    }

    private fun httpGet(
        context: Context,
        urlString: String,
        headers: Map<String, String>,
    ): String? {
        val url = URL(urlString)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val validatedNet = cm?.let { runCatching { InternetNetworkSelector.select(it) }.getOrNull() }
        val conn = runCatching {
            (validatedNet?.openConnection(url) ?: url.openConnection()) as? HttpURLConnection
        }.getOrNull() ?: return null

        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { readLimitedUtf8(it) }
        } catch (e: Exception) {
            Log.d(TAG, "LRC fetch skipped (${e.javaClass.simpleName})")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimitedUtf8(input: InputStream): String {
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_BODY_BYTES) break
            out.write(buf, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "geely_lrc_cache")

    private fun cacheFile(context: Context, cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = buildString(24) {
            for (i in 0 until 12) {
                append(String.format(Locale.US, "%02x", digest[i]))
            }
        }
        return File(cacheDir(context), "$hex.lrc")
    }

    private fun loadFromDiskCache(context: Context, cacheKey: String): String? {
        val file = cacheFile(context, cacheKey)
        if (!file.isFile) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > DISK_TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun saveToDiskCache(context: Context, cacheKey: String, rawLrc: String) {
        val dir = cacheDir(context)
        if (!dir.exists()) dir.mkdirs()
        runCatching {
            cacheFile(context, cacheKey).writeText(rawLrc, Charsets.UTF_8)
            val files = dir.listFiles() ?: return
            if (files.size > MAX_DISK_FILES) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_DISK_FILES)
                    .forEach { it.delete() }
            }
        }
    }
}

/**
 * Standard LRC parser supporting `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss]`, multiple timestamps per
 * line, and `[offset:+/-ms]`.
 */
internal object GeelyLrcParser {
    private val TIME_TAG: Pattern = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET_TAG: Pattern = Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE)
    private val INLINE_WORD_TAG: Pattern = Pattern.compile("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>")

    fun parse(rawLrc: String): List<GeelyLyricLine> {
        if (rawLrc.isBlank()) return emptyList()
        var offsetMs = 0L
        val offsetMatcher = OFFSET_TAG.matcher(rawLrc)
        if (offsetMatcher.find()) {
            offsetMs = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
        }

        val parsed = ArrayList<Pair<Long, String>>(64)
        for (rawLine in rawLrc.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.contains('[')) continue
            val matcher = TIME_TAG.matcher(line)
            val timestamps = ArrayList<Long>(2)
            var lastEnd = 0
            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: continue
                val sec = matcher.group(2)?.toLongOrNull() ?: continue
                val fracRaw = matcher.group(3).orEmpty()
                val fracMs = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.substring(0, 3).toLongOrNull() ?: 0L
                }
                val ts = (min * 60_000L + sec * 1_000L + fracMs - offsetMs).coerceAtLeast(0L)
                timestamps.add(ts)
                lastEnd = matcher.end()
            }
            if (timestamps.isEmpty()) continue
            val content = INLINE_WORD_TAG.matcher(line.substring(lastEnd))
                .replaceAll("")
                .trim()
            if (content.isEmpty()) continue
            for (ts in timestamps) {
                parsed.add(ts to content)
            }
        }
        if (parsed.isEmpty()) return emptyList()
        parsed.sortBy { it.first }

        // Merge duplicate identical-timestamp translation/empty entries conservatively
        val dedup = ArrayList<Pair<Long, String>>(parsed.size)
        for (item in parsed) {
            val last = dedup.lastOrNull()
            if (last != null && abs(last.first - item.first) < 80L) {
                continue
            }
            dedup.add(item)
        }

        val result = ArrayList<GeelyLyricLine>(dedup.size)
        for (i in dedup.indices) {
            val (ts, text) = dedup[i]
            val nextTs = dedup.getOrNull(i + 1)?.first
            val dur = if (nextTs != null && nextTs > ts) {
                (nextTs - ts).coerceIn(1_200L, 8_500L)
            } else {
                4_200L
            }
            result.add(GeelyLyricLine(timeMs = ts, text = text, durationMs = dur))
        }
        return result
    }
}
