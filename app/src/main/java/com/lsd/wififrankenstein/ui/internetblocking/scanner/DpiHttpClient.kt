package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.StageTrace
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager




data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
)










class DpiHttpClient(
    private val context: android.content.Context,
    private val tlsVersion: String? = null,
    private val proxyConfig: ProxyConfig? = null,
    private val callTracker: ((Call) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DpiHttpClient"
        private const val CONNECT_TIMEOUT = 8L
        private const val READ_TIMEOUT = 8L


        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
    }

    private val client: OkHttpClient by lazy {
        createClient()
    }

    private val pinnedClients = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    private fun pinnedClient(ip: String): OkHttpClient =
        pinnedClients.getOrPut(ip) { createClient(pinnedIp = ip) }




    fun shutdown() {
        fun quiet(c: OkHttpClient?) {
            try {
                c?.dispatcher?.executorService?.shutdown()
            } catch (_: Exception) {
            }
        }
        quiet(client)
        quiet(httpClientLazy.value)
        for (c in pinnedClients.values) quiet(c)
        pinnedClients.clear()
    }

    private val httpClientLazy = lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    private fun createClient(pinnedIp: String? = null): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())


        val connectionSpec = when (tlsVersion) {
            "TLSv1.2" -> ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()

            "TLSv1.3" -> ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                .tlsVersions(TlsVersion.TLS_1_3)
                .build()

            else -> ConnectionSpec.MODERN_TLS
        }

        val builder = OkHttpClient.Builder()

            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }


            .protocols(listOf(Protocol.HTTP_1_1))





            .connectionSpecs(listOf(connectionSpec))


            .followRedirects(false)
            .followSslRedirects(false)


            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT, TimeUnit.SECONDS)


            .proxy(if (proxyConfig != null) createProxy() else Proxy.NO_PROXY)




        if (pinnedIp != null) {
            builder.dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(pinnedIp))
            })
        }


        if (proxyConfig?.username != null && proxyConfig?.password != null) {
            builder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: okhttp3.Route?, response: Response): Request {
                    val credential =
                        okhttp3.Credentials.basic(proxyConfig!!.username!!, proxyConfig.password!!)
                    return response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            })
        }

        return builder.build()
    }

    private fun createProxy(): Proxy {
        val host = proxyConfig?.host ?: "localhost"
        val port = proxyConfig?.port ?: 8080
        return Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(host, port)
        )
    }








    suspend fun checkHttps(
        domain: String,
        resolvedIp: String? = null,
        stubIps: Set<String> = emptySet(),
        fakeIpType: String? = null,
        pinnedIp: String? = null
    ): DpiResult = withContext(Dispatchers.IO) {
        val url = "https://$domain"
        val connectionState = DpiTraceState()
        val activeClient = if (pinnedIp != null) pinnedClient(pinnedIp) else client


        if (resolvedIp != null) {
            if (fakeIpType == "isp" && stubIps.contains(resolvedIp)) {
                Log.w(TAG, "ISP stub detected for $domain ($resolvedIp)")
                return@withContext buildResult(
                    connectionState,
                    status = CheckStatus.IspPage,
                    detail = "ISP Stub DNS: $resolvedIp",
                    bytesRead = 0,
                    elapsed = 0.0
                )
            } else if (fakeIpType == "local") {
                Log.w(TAG, "Local IP detected for $domain ($resolvedIp)")
                return@withContext buildResult(
                    connectionState,
                    status = CheckStatus.LocalIp,
                    detail = "Local IP: $resolvedIp",
                    bytesRead = 0,
                    elapsed = 0.0
                )
            } else if (fakeIpType == "fakeip") {
                Log.w(TAG, "Fake-IP detected for $domain ($resolvedIp)")
                return@withContext buildResult(
                    connectionState,
                    status = CheckStatus.FakeIp,
                    detail = "Fake-IP (198.18.0.0/15): $resolvedIp",
                    bytesRead = 0,
                    elapsed = 0.0
                )
            }
        }


        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
            )
            .addHeader("Accept-Encoding", "identity")
            .addHeader("Connection", "close")
            .build()

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "HTTPS START: $domain | resolvedIp=$resolvedIp | fakeIpType=$fakeIpType")

        return@withContext try {

            val call = activeClient.newCall(request)
            call.addEventListener(DpiTraceEventListener(connectionState))
            callTracker?.invoke(call)

            var response: Response? = null
            try {
                response = call.execute()
                val statusCode = response.code
                val location = response.header("Location", "")
                val contentLength = response.body?.contentLength() ?: 0


                var actualBytesRead = 0L
                val bodyBuf = okio.Buffer()
                try {
                    val body = response.body
                    if (body != null) {
                        val source = body.source()
                        val maxRead = 128L * 1024L
                        while (actualBytesRead < maxRead) {
                            val remaining = maxRead - actualBytesRead
                            val chunkSize = minOf(remaining, 8192L)
                            val bytes = source.read(bodyBuf, chunkSize)
                            if (bytes == -1L) break
                            actualBytesRead += bytes
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    val kbRead = ((actualBytesRead + 1023) / 1024).toInt()
                    Log.w(
                        TAG,
                        "HTTPS READ TIMEOUT: $domain | bytesRead=$actualBytesRead | kbRead=$kbRead | stage=${connectionState.stage}"
                    )
                    val isTcpBlock = kbRead in 12..69
                    return@withContext buildResult(
                        connectionState,
                        status = if (isTcpBlock) CheckStatus.ReadTimeout else CheckStatus.Timeout,
                        detail = if (isTcpBlock) "TCP 16-20KB drop ($kbRead KB)" else "Read timeout ($kbRead KB) at ${connectionState.stage}",
                        bytesRead = actualBytesRead,
                        elapsed = elapsed(startTime)
                    )
                }

                response.close()
                val bytesRead = maxOf(actualBytesRead, contentLength)


                if (statusCode == 451) {
                    Log.d(
                        TAG,
                        "HTTPS BLOCKED: $domain | status=451 | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Blocked,
                        detail = "HTTP 451",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                }

                else if (!location.isNullOrEmpty() && statusCode in 300..399) {
                    val locDomain = parseLocationDomain(location) ?: ""
                    val normLoc = locDomain.removePrefix("www.")
                    val normDomain = domain.removePrefix("www.")
                    val isOwnRedirect = normLoc == normDomain || normLoc.endsWith(".$normDomain")

                    if (isOwnRedirect) {
                        Log.d(
                            TAG,
                            "HTTPS REDIR: $domain | status=$statusCode | redirect=same-domain | bytes=$bytesRead | elapsed=${
                                elapsed(startTime)
                            }s"
                        )
                        buildResult(
                            connectionState,
                            status = CheckStatus.Redirect,
                            detail = "Same-domain redirect",
                            bytesRead = bytesRead,
                            elapsed = elapsed(startTime)
                        )
                    } else {
                        Log.d(
                            TAG,
                            "HTTPS BLOCKED: $domain | status=$statusCode | redirect=cross-domain → $locDomain | bytes=$bytesRead | elapsed=${
                                elapsed(startTime)
                            }s"
                        )
                        buildResult(
                            connectionState,
                            status = CheckStatus.Blocked,
                            detail = "Cross-domain redirect → ${locDomain.take(30)}",
                            bytesRead = bytesRead,
                            elapsed = elapsed(startTime)
                        )
                    }
                }

                else if (statusCode in 200..399) {
                    Log.d(
                        TAG,
                        "HTTPS OK: $domain | status=$statusCode | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Ok,
                        detail = "HTTP $statusCode",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                } else {
                    Log.d(
                        TAG,
                        "HTTPS OK: $domain | status=$statusCode | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Ok,
                        detail = "HTTP $statusCode",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                }
            } finally {
                response?.close()
            }

        } catch (e: java.net.SocketTimeoutException) {
            val classification = classifyTimeout(connectionState.stage)
            Log.w(
                TAG,
                "HTTPS TIMEOUT: $domain | status=${classification.first.label()} | stage=${connectionState.stage} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = classification.first,
                detail = classification.second,
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: javax.net.ssl.SSLException) {
            val classification = ErrorClassifier.classifySslError(context, e, 0, connectionState.stage)
            Log.w(
                TAG,
                "HTTPS SSL ERROR: $domain | status=${classification.first.label()} | detail=${classification.second} | stage=${connectionState.stage} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = classification.first,
                detail = classification.second,
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: java.net.ConnectException) {
            val classification = ErrorClassifier.classifyConnectError(context, e, 0, connectionState.stage)
            Log.w(
                TAG,
                "HTTPS CONNECT ERROR: $domain | status=${classification.first.label()} | detail=${classification.second} | stage=${connectionState.stage} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = classification.first,
                detail = classification.second,
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: IOException) {
            val classification = ErrorClassifier.classifyReadError(context, e, 0, connectionState.stage)
            Log.w(
                TAG,
                "HTTPS IO ERROR: $domain | status=${classification.first.label()} | detail=${classification.second} | stage=${connectionState.stage} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = classification.first,
                detail = classification.second,
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "HTTPS UNEXPECTED ERROR: $domain | error=${e.javaClass.simpleName} | msg=${e.message} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = CheckStatus.Error,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        }
    }






    suspend fun checkHttpsWithPinnedIp(
        sniHost: String,
        pinnedIp: String
    ): DpiResult = checkHttps(
        domain = sniHost,
        resolvedIp = null,
        stubIps = emptySet(),
        fakeIpType = null,
        pinnedIp = pinnedIp
    )





    suspend fun checkHttp(
        domain: String,
    ): DpiResult = withContext(Dispatchers.IO) {
        val url = "http://$domain"
        val connectionState = DpiTraceState()


        val httpClient = httpClientLazy.value

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
            )
            .addHeader("Host", domain)
            .addHeader("Accept", "*/*")
            .addHeader("Connection", "close")
            .build()

        val startTime = System.currentTimeMillis()
        Log.d(TAG, "HTTP START: $domain")

        return@withContext try {
            val httpCall = httpClient.newCall(request)
            httpCall.addEventListener(DpiTraceEventListener(connectionState))
            callTracker?.invoke(httpCall)
            var response: Response? = null
            try {
                response = httpCall.execute()
                val statusCode = response.code
                val location = response.header("Location", "")
                val contentLength = response.body?.contentLength() ?: 0


                var actualBytes = 0L
                var stubDetected = false
                try {
                    val body = response.body
                    if (body != null) {
                        val source = body.source()
                        val buf = okio.Buffer()
                        val maxRead = 2000L
                        while (actualBytes < maxRead) {
                            val remaining = maxRead - actualBytes
                            val bytes = source.read(buf, minOf(remaining, 2000L))
                            if (bytes == -1L) break
                            actualBytes += bytes
                        }
                        stubDetected = StubDetector.looksLikeStub(buf.readUtf8())
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    val kbRead = ((actualBytes + 1023) / 1024).toInt()
                    Log.w(
                        TAG,
                        "HTTP READ TIMEOUT: $domain | kbRead=$kbRead | stage=${connectionState.stage}"
                    )
                    return@withContext buildResult(
                        connectionState,
                        status = CheckStatus.ReadTimeout,
                        detail = "Read timeout ($kbRead KB)",
                        bytesRead = actualBytes,
                        elapsed = elapsed(startTime)
                    )
                }
                response.close()
                val bytesRead = maxOf(actualBytes, contentLength)

                if (stubDetected) {
                    Log.d(
                        TAG,
                        "HTTP STUB PAGE: $domain | bytes=$bytesRead | elapsed=${elapsed(startTime)}s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Blocked,
                        detail = "ISP stub page",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime),
                        stubDetected = true
                    )
                }

                else if (statusCode == 451) {
                    Log.d(
                        TAG,
                        "HTTP BLOCKED: $domain | status=451 | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Blocked,
                        detail = "HTTP 451",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                }

                else if (!location.isNullOrEmpty() && statusCode in 300..399) {
                    val locDomain = parseLocationDomain(location) ?: ""
                    val normLoc = locDomain.removePrefix("www.")
                    val normDomain = domain.removePrefix("www.")
                    val isOwnRedirect = normLoc == normDomain || normLoc.endsWith(".$normDomain")

                    if (isOwnRedirect) {
                        Log.d(
                            TAG,
                            "HTTP REDIR: $domain | status=$statusCode | redirect=same-domain | bytes=$bytesRead | elapsed=${
                                elapsed(startTime)
                            }s"
                        )
                        buildResult(
                            connectionState,
                            status = CheckStatus.Redirect,
                            detail = "Same-domain redirect",
                            bytesRead = bytesRead,
                            elapsed = elapsed(startTime)
                        )
                    } else {
                        Log.d(
                            TAG,
                            "HTTP BLOCKED: $domain | status=$statusCode | redirect=cross-domain → $locDomain | bytes=$bytesRead | elapsed=${
                                elapsed(startTime)
                            }s"
                        )
                        buildResult(
                            connectionState,
                            status = CheckStatus.Blocked,
                            detail = "Cross-domain redirect → ${locDomain.take(30)}",
                            bytesRead = bytesRead,
                            elapsed = elapsed(startTime)
                        )
                    }
                }

                else if (statusCode in 200..399) {
                    Log.d(
                        TAG,
                        "HTTP OK: $domain | status=$statusCode | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Ok,
                        detail = "HTTP $statusCode",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                } else {
                    Log.d(
                        TAG,
                        "HTTP OK: $domain | status=$statusCode | bytes=$bytesRead | elapsed=${
                            elapsed(startTime)
                        }s"
                    )
                    buildResult(
                        connectionState,
                        status = CheckStatus.Ok,
                        detail = "HTTP $statusCode",
                        bytesRead = bytesRead,
                        elapsed = elapsed(startTime)
                    )
                }
            } finally {
                response?.close()
            }

        } catch (e: java.net.SocketTimeoutException) {
            val classification = classifyTimeout(connectionState.stage)
            Log.w(
                TAG,
                "HTTP TIMEOUT: $domain | status=${classification.first.label()} | stage=${connectionState.stage} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = classification.first,
                detail = classification.second,
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "HTTP ERROR: $domain | error=${e.javaClass.simpleName} | msg=${e.message} | elapsed=${
                    elapsed(startTime)
                }s"
            )
            buildResult(
                connectionState,
                status = CheckStatus.Error,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        }
    }

    private fun parseLocationDomain(location: String?): String {
        return try {
            val uri = if (location?.startsWith("http") == true) location else "https://${location}"
            java.net.URI.create(uri).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun elapsed(start: Long): Double = (System.currentTimeMillis() - start) / 1000.0

    private fun buildResult(
        state: DpiTraceState,
        status: CheckStatus,
        detail: String,
        bytesRead: Long,
        elapsed: Double,
        stubDetected: Boolean = false
    ): DpiResult = DpiResult(
        status = status,
        detail = detail,
        bytesRead = bytesRead,
        elapsed = elapsed,
        timeline = state.events,
        stubDetected = stubDetected
    )





    internal fun classifyTimeout(stage: String): Pair<CheckStatus, String> = when (stage) {
        "tcp_connect" -> CheckStatus.SynDrop to "TCP SYN timeout (blackhole)"
        "tls_handshake", "tls_connected" -> CheckStatus.TlsDrop to "TLS handshake timeout"
        "sending_data" -> CheckStatus.SendTimeout to context.getString(R.string.ib_ec_send_timeout)
        "reading_data" -> CheckStatus.ReadTimeout to context.getString(R.string.ib_ec_read_timeout)
        else -> CheckStatus.Timeout to context.getString(R.string.ib_ec_timeout_stage, stage)
    }
}







class DpiTraceState {
    private val startMs = System.currentTimeMillis()
    private val _events = mutableListOf<StageTrace>()

    val events: List<StageTrace> get() = _events.toList()

    var stage: String = "init"
        private set

    fun setStage(newStage: String, note: String? = null) {
        stage = newStage
        _events += StageTrace(newStage, System.currentTimeMillis() - startMs, note)
    }
}





class DpiTraceEventListener(
    private val state: DpiTraceState
) : EventListener() {
    override fun dnsStart(call: Call, domainName: String) {
        state.setStage("dns")
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        state.setStage("dns_resolved")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        state.setStage("tcp_connect")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        state.setStage("tcp_connect", "failed: ${ioe.javaClass.simpleName}")
    }

    override fun connectionAcquired(
        call: Call,
        connection: Connection
    ) {
        state.setStage("tcp_connected")
    }

    override fun secureConnectStart(call: Call) {
        state.setStage("tls_handshake")
    }

    override fun callEnd(call: Call) {
        state.setStage("tls_connected")
    }

    override fun requestHeadersStart(call: Call) {
        state.setStage("sending_data")
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        state.setStage("reading_data")
    }

    override fun responseHeadersStart(call: Call) {
        state.setStage("reading_data")
    }
}





data class DpiResult(
    val status: CheckStatus,
    val detail: String,
    val bytesRead: Long,
    val elapsed: Double,
    val timeline: List<StageTrace> = emptyList(),
    val stubDetected: Boolean = false
)
