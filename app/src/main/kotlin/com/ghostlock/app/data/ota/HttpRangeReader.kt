package com.ghostlock.app.data.ota

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * Reads byte ranges of a remote file over HTTP Range requests using standard HttpURLConnection.
 */
class HttpRangeReader(
    private val connectTimeoutMs: Int = 15000,
    private val readTimeoutMs: Int = 30000,
    private val userAgent: String = "Dalvik/2.1.0 (Linux; W; Android 17; Android Build/CP2A.260605.016)"
) {

    private fun openConnectionWithRedirects(
        urlStr: String, headers: Map<String, String>
    ): HttpURLConnection {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 6) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            /* Transparent proxies (carrier/roaming) may content-encode range
             * responses, shifting every byte offset; identity pins the body. */
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) {
                conn.setRequestProperty(k, v)
            }
            conn.connect()
            val code = conn.responseCode
            if (code in 301..303 || code in 307..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith(
                            "http://", ignoreCase = true
                        ) || location.startsWith("https://", ignoreCase = true)
                    ) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw IOException("Too many redirects: $urlStr")
    }

    suspend fun fileLength(url: String): Long? = withContext(Dispatchers.IO) {
        return@withContext try {
            val conn = openConnectionWithRedirects(url, mapOf("Range" to "bytes=0-0"))
            try {
                val contentRange = conn.getHeaderField("Content-Range")
                if (contentRange != null) {
                    val slash = contentRange.lastIndexOf('/')
                    if (slash != -1) {
                        contentRange.substring(slash + 1).trim().toLongOrNull()?.let { if (it > 0) return@withContext it }
                    }
                }
                val length = conn.contentLengthLong
                if (length > 0) return@withContext length
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The CDN path is not uniform: carrier/roaming transparent proxies can
     * strip the Range header (a 200 arrives instead of the 206), re-encode the
     * body (offsets shift), or reset mid-transfer (short read). The same URL
     * and APK on a different phone sits on a different network path, which is
     * why extraction succeeds for some devices and probabilistically fails
     * for others. Each read therefore retries, refuses short reads instead of
     * returning truncated data, and pins identity encoding.
     */
    suspend fun read(url: String, start: Long, size: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (size == 0) return@withContext ByteArray(0)
        if (size < 0 || start < 0) return@withContext null
        /* Operation blobs reach ~14 MB; a single long-lived range request dies
         * to one stall/reset/proxy hiccup on mobile networks (observed as
         * "Failed to read REPLACE_ZSTD operation ... at offset 18795414" on
         * some devices while others succeed). Split into 2 MiB sub-ranges:
         * only the failed chunk retries, and a flaky path loses at most one
         * small request, not the whole transfer. */
        val chunk = 2 shl 20
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val length = min(chunk, size - offset)
            val piece = readWithRetry(url, start + offset, length) ?: return@withContext null
            System.arraycopy(piece, 0, buffer, offset, length)
            offset += length
        }
        return@withContext buffer
    }

    private suspend fun readWithRetry(url: String, start: Long, size: Int): ByteArray? {
        val attempts = 3
        for (attempt in 1..attempts) {
            try {
                return readOnce(url, start, size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == attempts) return null
                kotlinx.coroutines.delay(400L * attempt)
            }
        }
        return null
    }

    private fun readOnce(url: String, start: Long, size: Int): ByteArray {
        val end = start + size - 1
        val conn = openConnectionWithRedirects(url, mapOf("Range" to "bytes=$start-$end"))
        try {
            val code = conn.responseCode
            if (code == 200 && start > 0) {
                // Server or proxy ignored the Range header; retrying may land
                // on a different node that honors it.
                throw IOException("server ignored the Range request (got 200 for offset $start)")
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code for range $start-$end")
            }
            val stream = conn.inputStream
            val buffer = ByteArray(size)
            var totalRead = 0
            while (totalRead < size) {
                val count = stream.read(buffer, totalRead, size - totalRead)
                if (count == -1) break
                totalRead += count
            }
            if (totalRead != size) {
                /* A truncated body would silently corrupt REPLACE writes and
                 * fail the zstd decode; it must fail loudly and retry. */
                throw IOException("short read: $totalRead of $size bytes at offset $start")
            }
            return buffer
        } finally {
            conn.disconnect()
        }
    }
}
