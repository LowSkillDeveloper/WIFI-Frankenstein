package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DcResult
import com.lsd.wififrankenstein.ui.internetblocking.model.TelegramCheckResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TelegramScanner {
    companion object {
        private const val TAG = "TelegramScanner"
        private const val UPLOAD_TEST_IP = "149.154.167.220"
        private const val UPLOAD_TEST_PORT = 443
        private const val UPLOAD_SIZE_BYTES = 10 * 1024 * 1024
    }

    private val telegramDCs = listOf(
        TcpPingTarget("149.154.175.53", 443, "DC1"),
        TcpPingTarget("149.154.167.51", 443, "DC2"),
        TcpPingTarget("149.154.175.100", 443, "DC3"),
        TcpPingTarget("149.154.167.91", 443, "DC4"),
        TcpPingTarget("91.108.56.130", 443, "DC5")
    )

    private val downloadUrls = listOf(
        "https://telegram.org/img/Telegram200million.png",
        "https://telegram.org/file/30970313",
        "https://telegram.org/img/t_logo.png",
        "https://telegram.org/js/telegram.js"
    )

    private val createdClients = java.util.Collections.synchronizedList(
        mutableListOf<okhttp3.OkHttpClient>()
    )

    fun shutdown() {
        synchronized(createdClients) {
            for (c in createdClients) {
                try {
                    c.dispatcher.executorService.shutdown()
                } catch (_: Exception) {
                }
            }
            createdClients.clear()
        }
    }

    private data class DcCheckResult(
        val results: List<DcResult>,
        val anyReachable: Boolean
    )

    private data class DownloadResult(
        val speedKbps: Float?,
        val urlUsed: String?,
        val bytesDownloaded: Long?
    )

    private data class UploadResult(
        val speedKbps: Float?,
        val bytesUploaded: Long?
    )

    suspend fun checkTelegram(): TelegramCheckResult {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting Telegram check in parallel")
        return coroutineScope {
            val dcDeferred = async(Dispatchers.IO) { checkDCs(5000L) }
            val dlDeferred = async(Dispatchers.IO) { checkDownload() }
            val ulDeferred = async(Dispatchers.IO) { checkUpload() }

            val dc = dcDeferred.await()
            val dl = dlDeferred.await()
            val ul = ulDeferred.await()

            val totalDurationMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "DC reachable: ${dc.anyReachable}")
            Log.d(
                TAG,
                "Download: ${dl.speedKbps?.let { "${it} Kbps" } ?: "null"} via ${dl.urlUsed ?: "none"}")
            Log.d(TAG, "Upload: ${ul.speedKbps?.let { "${it} Kbps" } ?: "null"}")
            Log.d(TAG, "Total duration: ${totalDurationMs}ms")

            val status = when {
                !dc.anyReachable && dl.speedKbps == null && ul.speedKbps == null -> {
                    Log.w(TAG, "Telegram appears fully blocked")
                    CheckStatus.Blocked
                }

                !dc.anyReachable -> {
                    Log.w(TAG, "Telegram DCs unreachable but download works")
                    CheckStatus.PartiallyBlocked
                }

                dl.speedKbps != null && dl.speedKbps < 100f -> {
                    Log.w(TAG, "Telegram throttled: ${dl.speedKbps} Kbps")
                    CheckStatus.Throttled
                }

                else -> {
                    Log.d(TAG, "Telegram OK")
                    CheckStatus.Ok
                }
            }

            TelegramCheckResult(
                dcResults = dc.results,
                downloadSpeedKbps = dl.speedKbps,
                downloadUrlUsed = dl.urlUsed,
                downloadBytes = dl.bytesDownloaded,
                uploadSpeedKbps = ul.speedKbps,
                uploadBytes = ul.bytesUploaded,
                status = status,
                totalDurationMs = totalDurationMs
            )
        }
    }

    private suspend fun checkDCs(timeoutMs: Long): DcCheckResult {
        Log.d(TAG, "Checking Telegram DCs")
        return coroutineScope {
            val results = telegramDCs.map { dc ->
                async(Dispatchers.IO) {
                    Log.d(TAG, "Pinging ${dc.label} (${dc.ip}:${dc.port})")
                    try {
                        Socket().use { socket ->
                            socket.connect(
                                java.net.InetSocketAddress(dc.ip, dc.port),
                                timeoutMs.toInt()
                            )
                            Log.d(TAG, "${dc.label} reachable")
                            DcResult(dc.label, dc.ip, reachable = true)
                        }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "${dc.label} unreachable: ${e.javaClass.simpleName}: ${e.message}"
                        )
                        DcResult(dc.label, dc.ip, reachable = false)
                    }
                }
            }.awaitAll()
            DcCheckResult(results, results.any { it.reachable })
        }
    }

    private fun checkDownload(): DownloadResult {
        Log.d(TAG, "Checking Telegram download speed")
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        createdClients.add(client)

        for (url in downloadUrls) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                val start = System.currentTimeMillis()
                var bytesRead = 0L

                client.newCall(request).execute().use { response ->
                    Log.d(TAG, "Download $url response: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Download $url failed with code ${response.code}")
                        return@use
                    }
                    response.body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (bytesRead < 5 * 1024 * 1024) {
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
                    Log.d(TAG, "Download speed via $url: ${speed} Kbps")
                    return DownloadResult(speed, url, bytesRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error for $url: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.w(TAG, "All download URLs failed")
        return DownloadResult(null, null, null)
    }

    private suspend fun checkUpload(): UploadResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking Telegram upload speed")
        return@withContext try {
            val sslContext = SSLContext.getInstance("TLS")
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
                override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
            createdClients.add(client)

            val uploadData = ByteArray(UPLOAD_SIZE_BYTES)
            for (i in uploadData.indices) {
                uploadData[i] = (i % 256).toByte()
            }

            val requestBody = uploadData.toRequestBody(
                "application/octet-stream".toMediaTypeOrNull()
            )

            val request = okhttp3.Request.Builder()
                .url("https://${UPLOAD_TEST_IP}:${UPLOAD_TEST_PORT}/upload")
                .post(requestBody)
                .build()

            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "Upload response: ${response.code}")
                response.close()
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            if (elapsed > 0) {
                val uploadSpeedKbps = ((UPLOAD_SIZE_BYTES / 1024.0) / elapsed).toFloat()
                Log.d(
                    TAG,
                    "Upload complete: ${UPLOAD_SIZE_BYTES} bytes in ${elapsed}s = ${uploadSpeedKbps} Kbps"
                )
                UploadResult(uploadSpeedKbps, UPLOAD_SIZE_BYTES.toLong())
            } else {
                Log.w(TAG, "Upload elapsed time is 0")
                UploadResult(null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.javaClass.simpleName}: ${e.message}")
            UploadResult(null, null)
        }
    }
}
