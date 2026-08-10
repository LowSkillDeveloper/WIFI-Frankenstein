package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.TcpCheckResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.EventListener
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class Tcp16Target(
    val id: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val asn: String? = null,
    val sni: String? = null
)

class Tcp16Scanner {
    companion object {
        private const val TAG = "Tcp16Scanner"
        private const val CHUNK_SIZE = 4000
        private const val MAX_CHUNKS = 16
        private const val INITIAL_TIMEOUT_MS = 3000L
        private const val DEFAULT_TIMEOUT_MS = 8000L
        private const val PAUSE_BETWEEN_REQUESTS_MS = 50L
        private const val FAT_DEFAULT_SNI = "example.com"


        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
    }

    suspend fun checkTcp16(
        targets: List<Tcp16Target>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): List<TcpCheckResult> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting TCP 16-20KB check for ${targets.size} targets")
            val semaphore = kotlinx.coroutines.sync.Semaphore(32)

            coroutineScope {
                targets.map { target ->
                    async {
                        semaphore.acquire()
                        try {
                            probeTarget(target, timeoutMs)
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun probeTarget(target: Tcp16Target, defaultTimeoutMs: Long): TcpCheckResult {
        Log.d(TAG, "Probing TCP target: ${target.provider} (${target.ip}:${target.port})")

        val scheme = if (target.port == 80) "http" else "https"



        val sni = target.sni?.takeIf { it.isNotBlank() } ?: FAT_DEFAULT_SNI
        val url = "$scheme://$sni:${target.port}/"
        val pinnedIp = target.ip


        val randomPool = buildString(100000) {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            for (i in 0 until 100000) {
                append(chars.random())
            }
        }

        var blockKb: Int? = null
        var blockDetail: String? = null
        var blockLabel: String? = null
        var measuredRttMs: Long = 0
        var rtt: Float? = null

        val dpiTraceState = DpiTraceState()
        val eventListener = DpiTraceEventListener(dpiTraceState)
        dpiTraceState.setStage("tcp_connect")


        var currentClient =
            createClient(INITIAL_TIMEOUT_MS, defaultTimeoutMs, eventListener, pinnedIp)

        var alive = false

        for (i in 0 until MAX_CHUNKS) {
            val request = createRequest(url, randomPool, i, target.port)
            val start = System.currentTimeMillis()

            try {
                currentClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start


                    if (i == 0) {
                        alive = true
                        measuredRttMs = elapsed
                        rtt = elapsed.toFloat() / 1000f
                        Log.d(TAG, "First alive response for ${target.provider}: ${rtt}s")

                        val adjustedTimeout =
                            calculateDynamicTimeout(measuredRttMs, defaultTimeoutMs)
                        currentClient =
                            createClient(adjustedTimeout, defaultTimeoutMs, eventListener, pinnedIp)
                    }


                    if (i < MAX_CHUNKS - 1) {
                        kotlinx.coroutines.delay(PAUSE_BETWEEN_REQUESTS_MS)
                    }
                }
            } catch (e: Exception) {
                val stage = dpiTraceState.stage
                val totalKb = ((i + 1) * CHUNK_SIZE / 1024)

                Log.w(
                    TAG,
                    "Chunk $i failed for ${target.provider}: ${e.javaClass.simpleName}: ${e.message}"
                )


                val (label, detail) = ErrorClassifier.classifyProbeErrorStageAware(e, i, stage)
                blockDetail = detail
                blockLabel = label

                if (totalKb in 12..69) {
                    blockKb = totalKb
                    Log.w(
                        TAG,
                        "TCP 16-20KB block detected for ${target.provider} at ${totalKb}KB: $label"
                    )
                }
                break
            }
        }

        val status = when {
            blockKb != null -> {
                Log.w(TAG, "Target ${target.provider} blocked at ${blockKb}KB: $blockDetail")
                CheckStatus.Blocked
            }

            !alive && blockLabel != null -> {
                Log.w(TAG, "Target ${target.provider} classified: ${blockLabel}")
                when (blockLabel) {
                    "TLS ALERT" -> CheckStatus.TlsAlert
                    "SYN DROP" -> CheckStatus.SynDrop
                    "SEND_TIMEOUT" -> CheckStatus.SendTimeout
                    "READ_TIMEOUT" -> CheckStatus.ReadTimeout
                    "PROTO ERR" -> CheckStatus.ProtoErr
                    "REFUSED" -> CheckStatus.Refused
                    else -> CheckStatus.Timeout
                }
            }

            !alive -> {
                Log.w(TAG, "Target ${target.provider} is not alive")
                CheckStatus.Timeout
            }

            else -> {
                Log.d(TAG, "Target ${target.provider} is OK")
                CheckStatus.Ok
            }
        }

        return TcpCheckResult(
            id = target.id,
            provider = target.provider,
            ip = target.ip,
            port = target.port,
            alive = alive,
            blockKb = blockKb,
            rtt = rtt,
            status = status,
            blockDetail = blockDetail,
            blockLabel = blockLabel,
            asn = target.asn
        )
    }

    private fun createClient(
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        eventListener: EventListener,
        pinnedIp: String? = null
    ): okhttp3.OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val builder = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionPool(
                ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 5,
                    timeUnit = java.util.concurrent.TimeUnit.MINUTES
                )
            )
            .eventListener(eventListener)
            .connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)


            .followRedirects(false)
            .followSslRedirects(false)

        if (pinnedIp != null) {
            builder.dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(pinnedIp))
            })
        }

        val client = builder.build()
        createdClients.add(client)
        return client
    }

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

    private fun createRequest(
        url: String,
        randomPool: String,
        chunkIndex: Int,
        port: Int
    ): okhttp3.Request {
        val headers = okhttp3.Headers.Builder().apply {
            add("Connection", "keep-alive")
            add("User-Agent", "Mozilla/5.0")
            if (chunkIndex > 0) {
                val padLength = CHUNK_SIZE
                val startIndex = (0 until randomPool.length - padLength).random()
                add("X-Pad", randomPool.substring(startIndex, startIndex + padLength))
            }
        }.build()

        return okhttp3.Request.Builder()
            .url(url)
            .head()
            .headers(headers)
            .build()
    }

    private fun calculateDynamicTimeout(measuredRttMs: Long, defaultTimeoutMs: Long): Long {
        if (measuredRttMs <= 0) return defaultTimeoutMs
        val dynamicTimeout = maxOf(measuredRttMs * 3, 1500L)
        return minOf(dynamicTimeout, defaultTimeoutMs)
    }
}


