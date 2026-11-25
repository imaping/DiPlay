package com.shilapi.xcertplay

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.update.InternetNetworkSelector
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Uploads diagnostic reports to a user-owned Cloudflare Worker backed by an R2 bucket.
 *
 * Kept in its own file so upstream syncs stay conflict-free, and uses [InternetNetworkSelector]
 * so wireless CarPlay hotspots without internet access do not hijack the upload route.
 */
internal object DiagnosticR2Uploader {
    private const val PREFS_NAME = "diplay_r2_upload"
    private const val KEY_OVERRIDE_ENABLED = "override_enabled"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_TOKEN = "token"

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_ERROR_BODY_BYTES = 512

    enum class ErrorKind {
        NOT_CONFIGURED,
        INVALID_ENDPOINT,
        NO_INTERNET,
        UNAUTHORIZED,
        HTTP_ERROR,
        NETWORK_ERROR,
    }

    data class Config(
        val endpoint: String,
        val token: String,
        val fromBuildDefaults: Boolean,
    ) {
        val isConfigured: Boolean get() = endpoint.isNotBlank()
    }

    data class UploadResult(
        val objectKey: String,
        val compressedBytes: Int,
        val uncompressedBytes: Int,
        val httpStatus: Int,
    )

    class UploadException(
        val kind: ErrorKind,
        message: String,
        val httpStatus: Int? = null,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    fun hasBuildDefaults(): Boolean = BuildConfig.R2_UPLOAD_ENDPOINT.isNotBlank()

    fun loadConfig(context: Context): Config {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OVERRIDE_ENABLED, false)) {
            return Config(
                endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty().trim(),
                token = prefs.getString(KEY_TOKEN, "").orEmpty().trim(),
                fromBuildDefaults = false,
            )
        }
        return Config(
            endpoint = BuildConfig.R2_UPLOAD_ENDPOINT.trim(),
            token = BuildConfig.R2_UPLOAD_TOKEN.trim(),
            fromBuildDefaults = BuildConfig.R2_UPLOAD_ENDPOINT.isNotBlank(),
        )
    }

    fun saveOverride(context: Context, endpoint: String, token: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        val cleanToken = token.trim()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OVERRIDE_ENABLED, true)
            .putString(KEY_ENDPOINT, cleanEndpoint)
            .putString(KEY_TOKEN, cleanToken)
            .apply()
    }

    fun clearOverride(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_OVERRIDE_ENABLED)
            .remove(KEY_ENDPOINT)
            .remove(KEY_TOKEN)
            .apply()
    }

    /** Returns a normalized http(s) endpoint without a trailing slash, or null when invalid. */
    fun normalizeEndpoint(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed
    }

    fun buildObjectKey(fileName: String, now: Date = Date()): String {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val deviceFolder = sanitizeSegment("${Build.MANUFACTURER}-${Build.MODEL}").ifEmpty { "android" }
        val cleanFileName = sanitizeSegment(fileName).ifEmpty { "DiPlay-report.txt" }
        val gzipName = if (cleanFileName.endsWith(".gz", ignoreCase = true)) cleanFileName else "$cleanFileName.gz"
        return "$dateFolder/$deviceFolder/$gzipName"
    }

    fun buildUploadUrl(normalizedEndpoint: String, objectKey: String): URL {
        val encodedPath = objectKey.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        return URL("$normalizedEndpoint/$encodedPath")
    }

    fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    fun upload(
        context: Context,
        fileName: String,
        reportText: String,
        openConnectionOverride: ((URL) -> URLConnection)? = null,
    ): UploadResult {
        val config = loadConfig(context)
        if (!config.isConfigured) {
            throw UploadException(ErrorKind.NOT_CONFIGURED, "R2 upload endpoint is not configured")
        }
        val endpoint = normalizeEndpoint(config.endpoint)
            ?: throw UploadException(ErrorKind.INVALID_ENDPOINT, "Invalid R2 upload endpoint URL")

        val objectKey = buildObjectKey(fileName)
        val targetUrl = buildUploadUrl(endpoint, objectKey)
        val rawBytes = reportText.toByteArray(Charsets.UTF_8)
        val compressed = gzip(rawBytes)

        val openConnection: (URL) -> URLConnection = openConnectionOverride ?: run {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.let { mgr ->
                runCatching { InternetNetworkSelector.select(mgr) }.getOrNull()
            } ?: throw UploadException(ErrorKind.NO_INTERNET, "No validated internet connection")
            network::openConnection
        }

        val connection = try {
            openConnection(targetUrl) as? HttpURLConnection
                ?: throw UploadException(ErrorKind.NETWORK_ERROR, "Upload URL did not open an HTTP connection")
        } catch (error: UploadException) {
            throw error
        } catch (error: IOException) {
            throw UploadException(ErrorKind.NETWORK_ERROR, error.message ?: "Failed to open connection", cause = error)
        }

        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(compressed.size)
            connection.setRequestProperty("Content-Type", "application/gzip")
            connection.setRequestProperty("User-Agent", "DiPlay-DiagnosticUploader")
            connection.setRequestProperty("X-DiPlay-Device", sanitizeSegment("${Build.MANUFACTURER}-${Build.MODEL}"))
            connection.setRequestProperty("X-DiPlay-Report-Name", sanitizeSegment(fileName))
            connection.setRequestProperty("X-DiPlay-Uncompressed-Bytes", rawBytes.size.toString())
            if (config.token.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            }

            connection.outputStream.use { out ->
                out.write(compressed)
                out.flush()
            }

            val status = connection.responseCode
            if (status in 200..299) {
                runCatching { connection.inputStream?.use { drainLimited(it) } }
                return UploadResult(
                    objectKey = objectKey,
                    compressedBytes = compressed.size,
                    uncompressedBytes = rawBytes.size,
                    httpStatus = status,
                )
            }

            val detail = runCatching { connection.errorStream?.use { readErrorSnippet(it) } }.getOrNull().orEmpty()
            val kind = if (status == 401 || status == 403) ErrorKind.UNAUTHORIZED else ErrorKind.HTTP_ERROR
            val message = if (detail.isNotBlank()) "HTTP $status: $detail" else "HTTP $status"
            throw UploadException(kind, message, httpStatus = status)
        } catch (error: UploadException) {
            throw error
        } catch (error: IOException) {
            throw UploadException(ErrorKind.NETWORK_ERROR, error.message ?: error.javaClass.simpleName, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun sanitizeSegment(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(80)

    private fun drainLimited(stream: InputStream) {
        val buffer = ByteArray(1024)
        var remaining = 8 * 1024
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun readErrorSnippet(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(MAX_ERROR_BODY_BYTES)
        var total = 0
        while (total < MAX_ERROR_BODY_BYTES) {
            val read = stream.read(buffer, 0, MAX_ERROR_BODY_BYTES - total)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toString("UTF-8").replace(Regex("\\s+"), " ").trim()
    }
}
