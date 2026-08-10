package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.YouTubeCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.YouTubeEndpointResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class YouTubeScanner {
    companion object {
        private const val TAG = "YouTubeScanner"
        private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
    }

    private val youtubeEndpoints = listOf(
        YouTubeEndpoint("YT-Web", "www.youtube.com"),
        YouTubeEndpoint("YT-Images", "i.ytimg.com"),
        YouTubeEndpoint("YT-Gallery", "yt3.ggpht.com"),
        YouTubeEndpoint("G-Static", "www.gstatic.com"),
        YouTubeEndpoint("G-Accounts", "accounts.google.com")
    )

    private val downloadUrls = listOf(
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        "https://i.ytimg.com/vi/jNQXAC9IVRw/maxresdefault.jpg",
        "https://i.ytimg.com/vi/xvFZjo5PgG0/maxresdefault.jpg",
        "https://yt3.ggpht.com/ytc/AIdro_l9pLJ5JPfW-g41-9o3FuCqDs89eCi3rMaPHFx6SA=s900-c-k-c0x00ffffff-no-rj"
    )

    private val endpointClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun shutdown() {
        try {
            endpointClient.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
        try {
            downloadClient.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
    }

    private data class YouTubeEndpoint(
        val label: String,
        val host: String
    )

    private data class DownloadResult(
        val speedKbps: Float?,
        val urlUsed: String?,
        val bytesDownloaded: Long?
    )

    suspend fun checkYoutube(): YouTubeCheckResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting YouTube check in parallel")
        return coroutineScope {
            val epDeferred = async(Dispatchers.IO) { checkEndpoints() }
            val dlDeferred = async(Dispatchers.IO) { checkDownload() }

            val ep = epDeferred.await()
            val dl = dlDeferred.await()

            val totalDurationMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "Endpoints reachable: ${ep.count { it.reachable }}/${ep.size}")
            Log.d(
                TAG,
                "Download: ${dl.speedKbps?.let { "${it} Kbps" } ?: "null"} via ${dl.urlUsed ?: "none"}")
            Log.d(TAG, "Total duration: ${totalDurationMs}ms")

            val status = when {
                ep.none { it.reachable } && dl.speedKbps == null -> CheckStatus.Blocked
                ep.none { it.reachable } -> CheckStatus.PartiallyBlocked
                dl.speedKbps != null && dl.speedKbps < 100f -> CheckStatus.Throttled
                else -> CheckStatus.Ok
            }

            YouTubeCheckResult(
                endpointResults = ep,
                downloadSpeedKbps = dl.speedKbps,
                downloadUrlUsed = dl.urlUsed,
                downloadBytes = dl.bytesDownloaded,
                status = status,
                totalDurationMs = totalDurationMs
            )
        }
    }

    private suspend fun checkEndpoints(): List<YouTubeEndpointResult> {
        Log.d(TAG, "Checking YouTube endpoints via HTTPS")
        return coroutineScope {
            youtubeEndpoints.map { ep ->
                async(Dispatchers.IO) {
                    try {
                        val request = okhttp3.Request.Builder()
                            .url("https://${ep.host}/")
                            .head()
                            .build()
                        val response = endpointClient.newCall(request).execute()
                        val reachable = response.code < 500
                        response.close()
                        Log.d(TAG, "${ep.label} (${ep.host}) reachable: ${response.code}")
                        YouTubeEndpointResult(ep.label, ep.host, reachable)
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "${ep.label} (${ep.host}) unreachable: ${e.javaClass.simpleName}: ${e.message}"
                        )
                        YouTubeEndpointResult(ep.label, ep.host, reachable = false)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkDownload(): DownloadResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking YouTube download speed")
        var bestSpeed: Float? = null
        var bestUrl: String? = null
        var bestBytes: Long? = null

        for (url in downloadUrls) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                val start = System.currentTimeMillis()
                var bytesRead = 0L

                downloadClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Download $url response: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Download $url failed with code ${response.code}")
                        return@use
                    }
                    response.body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (bytesRead < MAX_DOWNLOAD_BYTES) {
                            count = stream.read(buffer)
                            if (count < 0) break
                            bytesRead += count
                        }
                    }
                }

                if (bytesRead == 0L) continue

                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                if (elapsed > 0) {
                    val speed = ((bytesRead / 1024.0) / elapsed).toFloat()
                    Log.d(TAG, "Download speed via $url: ${speed} Kbps (${bytesRead} bytes)")
                    if (bestSpeed == null || bytesRead > (bestBytes ?: 0L)) {
                        bestSpeed = speed
                        bestUrl = url
                        bestBytes = bytesRead
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (bestSpeed != null) {
            return@withContext DownloadResult(bestSpeed, bestUrl, bestBytes)
        }
        Log.w(TAG, "All download URLs failed")
        return@withContext DownloadResult(null, null, null)
    }
}
