package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DomainCheckResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.InetAddress









class TlsScanner(
    private val context: android.content.Context,
    private val callTracker: ((okhttp3.Call) -> Unit)? = null
) {
    companion object {
        private const val TAG = "TlsScanner"
        private const val DEFAULT_TIMEOUT_MS = 8000L
        private const val FAKEIP_START = 198
        private const val FAKEIP_END = 199
        private const val MAX_CONCURRENT = 100
    }

    data class DnsResult(
        val domain: String,
        val resolvedIp: String?,
        val fakeIpType: String?,
        val dnsStatus: String?
    )

    private val trustAllManager = object : javax.net.ssl.X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        override fun checkClientTrusted(
            certs: Array<out java.security.cert.X509Certificate>?,
            authType: String?
        ) {
        }

        override fun checkServerTrusted(
            certs: Array<out java.security.cert.X509Certificate>?,
            authType: String?
        ) {
        }
    }

    private fun getFakeIpType(ipStr: String): String? {
        if (ipStr.isEmpty()) return null
        return try {
            val parts = ipStr.split(".")
            if (parts.size != 4) return null
            val first = parts[0].toInt()
            val second = parts[1].toInt()


            if (first == FAKEIP_START && (second == FAKEIP_START || second == FAKEIP_END)) {
                return "fakeip"
            }


            if (first == 100 && second in 64..95) {
                return "isp"
            }


            if (first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                first == 127 ||
                first == 0 ||
                (first == 169 && second == 254)
            ) {
                return "local"
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveDomain(domain: String): String? {
        repeat(2) { attempt ->
            try {
                val addrs = withContext(Dispatchers.IO) {
                    InetAddress.getAllByName(domain)
                }
                if (addrs.isNotEmpty()) {

                    return (addrs.firstOrNull { it is java.net.Inet4Address }
                        ?: addrs.first()).hostAddress
                }
            } catch (_: Exception) {
                if (attempt == 0) {
                    delay(200)
                } else {
                    return null
                }
            }
        }
        return null
    }






    suspend fun checkSniDifferential(resolvedIp: String): DpiResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "SNI differential probe to $resolvedIp with benign SNI")
        differentialClient.checkHttpsWithPinnedIp("www.google.com", resolvedIp)
    }

    fun shutdown() {
        differentialClient.shutdown()
    }

    private val differentialClient by lazy(LazyThreadSafetyMode.NONE) {
        DpiHttpClient(context, tlsVersion = "TLSv1.3", callTracker = callTracker)
    }

    suspend fun checkDomains(
        domains: List<String>,
        stubIps: Set<String> = emptySet(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<DomainCheckResult> {
        Log.d(
            TAG,
            "Starting TLS domain check for ${domains.size} domains with stubIps=${stubIps.size}"
        )
        val semaphore = Semaphore(MAX_CONCURRENT)

        val dnsResults: MutableList<DnsResult> = mutableListOf()
        val tls13Results: MutableList<DpiResult> = mutableListOf()
        val tls12Results: MutableList<DpiResult> = mutableListOf()
        val httpResults: MutableList<DpiResult> = mutableListOf()


        val tls13Client = DpiHttpClient(context, tlsVersion = "TLSv1.3", callTracker = callTracker)
        val tls12Client = DpiHttpClient(context, tlsVersion = "TLSv1.2", callTracker = callTracker)
        val httpClient = DpiHttpClient(context, tlsVersion = null, callTracker = callTracker)


        onProgress?.invoke(5, context.getString(R.string.ib_tls_progress_phase0))
        Log.d(TAG, "Phase 0/4: DNS resolution for ${domains.size} domains")
        coroutineScope {
            dnsResults.addAll(domains.map { domain ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val resolvedIp = resolveDomain(domain)
                        val fakeIpType = getFakeIpType(resolvedIp ?: "")
                        val dnsStatus = when (fakeIpType) {
                            "fakeip" -> context.getString(R.string.ib_tls_dns_fakeip)
                            "isp" -> context.getString(R.string.ib_tls_dns_isp)
                            "local" -> context.getString(R.string.ib_tls_dns_local)
                            else -> null
                        }
                        DnsResult(domain, resolvedIp, fakeIpType, dnsStatus)
                    } catch (e: Exception) {
                        Log.w(TAG, "DNS resolution failed for $domain: ${e.message}")
                        DnsResult(domain, null, null, null)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll())
        }


        onProgress?.invoke(25, context.getString(R.string.ib_tls_progress_phase1))
        Log.d(TAG, "Phase 1/4: TLS 1.3 probing for ${domains.size} domains")
        coroutineScope {
            tls13Results.addAll(dnsResults.map { dnsResult ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        tls13Client.checkHttps(
                            domain = dnsResult.domain,
                            resolvedIp = dnsResult.resolvedIp,
                            stubIps = stubIps,
                            fakeIpType = dnsResult.fakeIpType
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "TLS 1.3 probe failed for ${dnsResult.domain}: ${e.message}")
                        DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll())
        }


        onProgress?.invoke(50, context.getString(R.string.ib_tls_progress_phase2))
        Log.d(TAG, "Phase 2/4: TLS 1.2 probing for ${domains.size} domains")
        coroutineScope {
            tls12Results.addAll(dnsResults.map { dnsResult ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        tls12Client.checkHttps(
                            domain = dnsResult.domain,
                            resolvedIp = dnsResult.resolvedIp,
                            stubIps = stubIps,
                            fakeIpType = dnsResult.fakeIpType
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "TLS 1.2 probe failed for ${dnsResult.domain}: ${e.message}")
                        DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll())
        }


        onProgress?.invoke(75, context.getString(R.string.ib_tls_progress_phase3))
        Log.d(TAG, "Phase 3/4: HTTP probing for ${domains.size} domains")
        coroutineScope {
            httpResults.addAll(dnsResults.map { dnsResult ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        httpClient.checkHttp(dnsResult.domain)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "HTTP probe failed for ${dnsResult.domain}: ${e.message}")
                        DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll())
        }

        onProgress?.invoke(95, context.getString(R.string.ib_tls_progress_collect))


        Log.d(TAG, "Merging results for ${domains.size} domains")
        return dnsResults.mapIndexed { i, dnsResult ->
            val tls13 = tls13Results[i]
            val tls12 = tls12Results[i]
            val http = httpResults[i]

            val bytesReceived = maxOf(
                tls13.bytesRead,
                tls12.bytesRead,
                http.bytesRead
            )

            val details = buildString {
                if (dnsResult.dnsStatus != null) append("DNS: ${dnsResult.dnsStatus}; ")
                if (tls13.status != CheckStatus.Ok) append("TLS 1.3: ${tls13.status.label(context)} - ${tls13.detail}; ")
                if (tls12.status != CheckStatus.Ok) append("TLS 1.2: ${tls12.status.label(context)} - ${tls12.detail}; ")
                if (http.status != CheckStatus.Ok) append("HTTP: ${http.status.label(context)} - ${http.detail}")
            }.takeIf { it.isNotBlank() }

            DomainCheckResult(
                domain = dnsResult.domain,
                tls13Status = tls13.status,
                tls12Status = tls12.status,
                httpStatus = http.status,
                details = details,
                dnsResolvedIp = dnsResult.resolvedIp,
                dnsIpType = dnsResult.fakeIpType,
                dnsStatus = dnsResult.dnsStatus,
                tls13Elapsed = tls13.elapsed.toLong(),
                tls12Elapsed = tls12.elapsed.toLong(),
                httpElapsed = http.elapsed.toLong(),
                bytesReceived = bytesReceived,
                tls13Detail = tls13.detail,
                tls12Detail = tls12.detail,
                httpDetail = http.detail,
                httpStub = http.stubDetected,
                tls13Trace = tls13.timeline,
                tls12Trace = tls12.timeline,
                httpTrace = http.timeline
            )
        }
    }






    suspend fun checkDomainParallel(
        domain: String,
        stubIps: Set<String> = emptySet(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onProgress: ((Int, String) -> Unit)? = null
    ): DomainCheckResult {
        Log.d(TAG, "Parallel domain check for $domain (timeout=${timeoutMs}ms)")
        onProgress?.invoke(5, context.getString(R.string.ib_tls_progress_dns))

        val resolvedIp = resolveDomain(domain)
        val fakeIpType = getFakeIpType(resolvedIp ?: "")
        val dnsStatus = when (fakeIpType) {
            "fakeip" -> context.getString(R.string.ib_tls_dns_fakeip)
            "isp" -> context.getString(R.string.ib_tls_dns_isp)
            "local" -> context.getString(R.string.ib_tls_dns_local)
            else -> null
        }
        val dnsResult = DnsResult(domain, resolvedIp, fakeIpType, dnsStatus)

        val tls13Client = DpiHttpClient(context, tlsVersion = "TLSv1.3", callTracker = callTracker)
        val tls12Client = DpiHttpClient(context, tlsVersion = "TLSv1.2", callTracker = callTracker)
        val httpClient = DpiHttpClient(context, tlsVersion = null, callTracker = callTracker)

        onProgress?.invoke(25, context.getString(R.string.ib_tls_progress_combined))
        val (tls13, tls12, http) = coroutineScope {
            val d1 = async(Dispatchers.IO) {
                try {
                    tls13Client.checkHttps(domain, resolvedIp, stubIps, fakeIpType)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                }
            }
            val d2 = async(Dispatchers.IO) {
                try {
                    tls12Client.checkHttps(domain, resolvedIp, stubIps, fakeIpType)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                }
            }
            val d3 = async(Dispatchers.IO) {
                try {
                    httpClient.checkHttp(domain)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DpiResult(CheckStatus.Error, context.getString(R.string.ib_tls_probe_failed, e.message), 0, 0.0)
                }
            }
            Triple(d1.await(), d2.await(), d3.await())
        }

        onProgress?.invoke(95, context.getString(R.string.ib_tls_progress_collect))

        val bytesReceived = maxOf(tls13.bytesRead, tls12.bytesRead, http.bytesRead)
        val details = buildString {
            if (dnsResult.dnsStatus != null) append("DNS: ${dnsResult.dnsStatus}; ")
            if (tls13.status != CheckStatus.Ok) append("TLS 1.3: ${tls13.status.label(context)} - ${tls13.detail}; ")
            if (tls12.status != CheckStatus.Ok) append("TLS 1.2: ${tls12.status.label(context)} - ${tls12.detail}; ")
            if (http.status != CheckStatus.Ok) append("HTTP: ${http.status.label(context)} - ${http.detail}")
        }.takeIf { it.isNotBlank() }

        return DomainCheckResult(
            domain = domain,
            tls13Status = tls13.status,
            tls12Status = tls12.status,
            httpStatus = http.status,
            details = details,
            dnsResolvedIp = dnsResult.resolvedIp,
            dnsIpType = dnsResult.fakeIpType,
            dnsStatus = dnsResult.dnsStatus,
            tls13Elapsed = tls13.elapsed.toLong(),
            tls12Elapsed = tls12.elapsed.toLong(),
            httpElapsed = http.elapsed.toLong(),
            bytesReceived = bytesReceived,
            tls13Detail = tls13.detail,
            tls12Detail = tls12.detail,
            httpDetail = http.detail,
            httpStub = http.stubDetected,
            tls13Trace = tls13.timeline,
            tls12Trace = tls12.timeline,
            httpTrace = http.timeline
        )
    }
}
