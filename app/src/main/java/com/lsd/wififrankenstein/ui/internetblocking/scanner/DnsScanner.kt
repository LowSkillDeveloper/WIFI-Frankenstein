package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DnsCheckResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DnsScanner {
    companion object {
        private const val TAG = "DnsScanner"
        private const val RETRY_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 500L

        private val QUICK_UDP_SERVERS = listOf("8.8.8.8", "1.1.1.1")
        private val QUICK_DOH_SERVERS = listOf(
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query"
        )

        private fun createInsecureClient(timeoutMs: Long): okhttp3.OkHttpClient {
            val trustAllCerts = arrayOf<TrustManager>(object : TrustManager, X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            return okhttp3.OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }

    private data class DnsServer(val ip: String, val name: String)

    private val udpServers = listOf(
        DnsServer("8.8.8.8", "Google"),
        DnsServer("1.1.1.1", "Cloudflare"),
        DnsServer("9.9.9.9", "Quad9"),
        DnsServer("208.67.222.222", "OpenDNS"),
        DnsServer("94.140.14.14", "AdGuard"),
        DnsServer("77.88.8.8", "Yandex"),
        DnsServer("76.76.2.2", "ControlD"),
        DnsServer("194.242.2.2", "Mullvad")
    )

    private val dohJsonServers = listOf(
        DnsServer("https://8.8.8.8/resolve", "Google"),
        DnsServer("https://dns.google/resolve", "Google2"),
        DnsServer("https://1.1.1.1/dns-query", "Cloudflare"),
        DnsServer("https://cloudflare-dns.com/dns-query", "Cloudflare2"),
        DnsServer("https://dns.adguard-dns.com/resolve", "AdGuard")
    )

    private val dohWireServers = listOf(
        DnsServer("https://dns.google/dns-query", "Google"),
        DnsServer("https://cloudflare-dns.com/dns-query", "Cloudflare"),
        DnsServer("https://1.1.1.1/dns-query", "Cloudflare2"),
        DnsServer("https://dns.adguard-dns.com/dns-query", "AdGuard"),
        DnsServer("https://dns.mullvad.net/dns-query", "Mullvad")
    )


    private data class FullProbeResult(
        val ok: Int,
        val timeout: Int,
        val error: Int,
        val blocked: Int,
        val results: Map<String, Any>
    )


    private data class ServerQuickResult(val server: DnsServer, val ips: List<String>?)


    private data class ServerFullResult(val server: DnsServer, val probe: FullProbeResult)

    suspend fun checkDnsSpoofing(
        domains: List<String>,
        timeoutMs: Long = 5000,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<DnsCheckResult> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting DNS spoofing check for ${domains.size} domains")
            val probeDomain = domains.firstOrNull() ?: "example.com"
            onProgress?.invoke(0, "Подготовка...")


            onProgress?.invoke(5, "Фаза 1: пинг серверов...")
            val udpPhase1 = quickPingAllUdp(probeDomain)
            val jsonPhase1 = quickPingAllJson(probeDomain)
            val wirePhase1 = quickPingAllWire(probeDomain)

            Log.d(TAG, "=== Phase 1 Results for $probeDomain ===")
            udpPhase1.forEach {
                Log.d(
                    TAG,
                    "  UDP ${it.server.name} (${it.server.ip}): ${it.ips ?: "null"}"
                )
            }
            jsonPhase1.forEach {
                Log.d(
                    TAG,
                    "  JSON ${it.server.name} (${it.server.ip}): ${it.ips ?: "null"}"
                )
            }
            wirePhase1.forEach {
                Log.d(
                    TAG,
                    "  Wire ${it.server.name} (${it.server.ip}): ${it.ips ?: "null"}"
                )
            }
            onProgress?.invoke(25, "Пинг завершён")


            val silentUdp = udpPhase1.filter { it.ips == null }
            val silentJson = jsonPhase1.filter { it.ips == null }
            val silentWire = wirePhase1.filter { it.ips == null }

            Log.d(
                TAG,
                "Silent servers - UDP: ${silentUdp.map { it.server.ip }}, JSON: ${silentJson.map { it.server.ip }}, Wire: ${silentWire.map { it.server.ip }}"
            )

            if (silentUdp.isNotEmpty() || silentJson.isNotEmpty() || silentWire.isNotEmpty()) {
                onProgress?.invoke(30, "Фаза 2: полный тест молчащих серверов...")
            }

            val (fullUdp, fullJson, fullWire) = if (silentUdp.isNotEmpty() || silentJson.isNotEmpty() || silentWire.isNotEmpty()) {
                val phase2Domains = listOf(probeDomain)
                coroutineScope {
                    val fUdp = silentUdp.map { s ->
                        async {
                            ServerFullResult(
                                s.server,
                                probeUdpAll(s.server.ip, phase2Domains, timeoutMs)
                            )
                        }
                    }
                    val fJson = silentJson.map { s ->
                        async {
                            ServerFullResult(
                                s.server,
                                probeDoHJsonAll(s.server.ip, phase2Domains, timeoutMs)
                            )
                        }
                    }
                    val fWire = silentWire.map { s ->
                        async {
                            ServerFullResult(
                                s.server,
                                probeDoHWireAll(s.server.ip, phase2Domains, timeoutMs)
                            )
                        }
                    }
                    Triple(fUdp.awaitAll(), fJson.awaitAll(), fWire.awaitAll())
                }
            } else {
                Triple(emptyList(), emptyList(), emptyList())
            }

            onProgress?.invoke(45, "Выбор серверов...")

            Log.d(
                TAG,
                "Phase 2 Results - UDP: ${fullUdp.map { "${it.server.ip}: ok=${it.probe.ok}" }}, JSON: ${fullJson.map { "${it.server.ip}: ok=${it.probe.ok}" }}, Wire: ${fullWire.map { "${it.server.ip}: ok=${it.probe.ok}" }}"
            )


            val udpWorking = udpPhase1.filter { it.ips != null }.map { it.server } +
                    fullUdp.filter { it.probe.ok > 0 }.map { it.server }
            val jsonWorking = jsonPhase1.filter { it.ips != null }.map { it.server } +
                    fullJson.filter { it.probe.ok > 0 }.map { it.server }
            val wireWorking = wirePhase1.filter { it.ips != null }.map { it.server } +
                    fullWire.filter { it.probe.ok > 0 }.map { it.server }

            Log.d(
                TAG,
                "Working servers - UDP: ${udpWorking.map { it.ip }}, JSON: ${jsonWorking.map { it.ip }}, Wire: ${wireWorking.map { it.ip }}"
            )


            val udpServer = pickServer(udpWorking, udpServers)
            val jsonServer = pickServer(jsonWorking, dohJsonServers)
            val wireServer = pickServer(wireWorking, dohWireServers)

            Log.d(
                TAG,
                "Selected servers - UDP: ${udpServer?.ip} (${udpServer?.name}), JSON: ${jsonServer?.ip} (${jsonServer?.name}), Wire: ${wireServer?.ip} (${wireServer?.name})"
            )


            onProgress?.invoke(50, "Полный тест: UDP...")
            val udpProbe = if (udpServer != null) {
                Log.d(TAG, "Using UDP server: ${udpServer.ip} (${udpServer.name})")
                probeUdpAll(udpServer.ip, domains, timeoutMs)
            } else {
                Log.w(TAG, "No working UDP server found")
                FullProbeResult(0, 0, 0, 0, domains.associateWith { "UNAVAIL" })
            }
            Log.d(TAG, "UDP probe results: ${udpProbe.results}")

            onProgress?.invoke(65, "Полный тест: DoH JSON...")
            val jsonProbe = if (jsonServer != null) {
                Log.d(TAG, "Using DoH JSON server: ${jsonServer.ip} (${jsonServer.name})")
                probeDoHJsonAll(jsonServer.ip, domains, timeoutMs)
            } else {
                Log.w(TAG, "No working DoH JSON server found")
                FullProbeResult(0, 0, 0, 0, domains.associateWith { "UNAVAIL" })
            }
            Log.d(TAG, "JSON probe results: ${jsonProbe.results}")

            onProgress?.invoke(80, "Полный тест: DoH Wire...")
            val wireProbe = if (wireServer != null) {
                Log.d(TAG, "Using DoH Wire server: ${wireServer.ip} (${wireServer.name})")
                probeDoHWireAll(wireServer.ip, domains, timeoutMs)
            } else {
                Log.w(TAG, "No working DoH Wire server found")
                FullProbeResult(0, 0, 0, 0, domains.associateWith { "UNAVAIL" })
            }
            Log.d(TAG, "Wire probe results: ${wireProbe.results}")

            onProgress?.invoke(90, "Анализ результатов...")

            val ipCount = mutableMapOf<String, Int>()
            for (res in udpProbe.results.values) {
                if (res is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (res as List<String>).forEach { ipCount[it] = (ipCount[it] ?: 0) + 1 }
                }
            }
            val stubIps = ipCount.filterValues { it >= 2 }.keys
            if (stubIps.isNotEmpty()) {
                Log.d(TAG, "Stub IPs detected (appeared >=2 times): $stubIps (counts: $ipCount)")
            } else {
                Log.d(TAG, "No stub IPs detected. IP counts: $ipCount")
            }

            onProgress?.invoke(95, "Генерация отчёта...")

            Log.d(TAG, "=== Full Probe Results ===")
            Log.d(
                TAG,
                "UDP probe: ok=${udpProbe.ok}, timeout=${udpProbe.timeout}, error=${udpProbe.error}, blocked=${udpProbe.blocked}"
            )
            Log.d(
                TAG,
                "JSON probe: ok=${jsonProbe.ok}, timeout=${jsonProbe.timeout}, error=${jsonProbe.error}, blocked=${jsonProbe.blocked}"
            )
            Log.d(
                TAG,
                "Wire probe: ok=${wireProbe.ok}, timeout=${wireProbe.timeout}, error=${wireProbe.error}, blocked=${wireProbe.blocked}"
            )


            val results = domains.map { domain ->
                val udpRes = udpProbe.results[domain]
                val jsonRes = jsonProbe.results[domain]
                val wireRes = wireProbe.results[domain]

                val udpIps =
                    if (udpRes is List<*>) @Suppress("UNCHECKED_CAST") udpRes as List<String> else emptyList()
                val jsonIps =
                    if (jsonRes is List<*>) @Suppress("UNCHECKED_CAST") jsonRes as List<String> else emptyList()
                val wireIps =
                    if (wireRes is List<*>) @Suppress("UNCHECKED_CAST") wireRes as List<String> else emptyList()

                val udpStatus = if (udpRes is String) udpRes else null
                val jsonStatus = if (jsonRes is String) jsonRes else null
                val wireStatus = if (wireRes is String) wireRes else null


                val trusted = mutableSetOf<String>()
                trusted.addAll(jsonIps)
                trusted.addAll(wireIps)


                val udpIsFakeIp = udpIps.any { isFakeIp(it) }


                val status = when {
                    trusted.isNotEmpty() && udpIps.isNotEmpty() -> {
                        val intersection = udpIps.toSet() intersect trusted
                        if (intersection.isNotEmpty()) {
                            CheckStatus.Ok
                        } else if (udpIsFakeIp) {
                            CheckStatus.FakeIp
                        } else {



                            val udpIsStub = udpIps.any { it in stubIps }
                            if (udpIsStub) CheckStatus.DnsSpoof else CheckStatus.Ok
                        }
                    }

                    trusted.isNotEmpty() && udpIps.isEmpty() -> {
                        when (udpStatus) {
                            "TIMEOUT" -> CheckStatus.DnsIntercept
                            "NXDOMAIN" -> CheckStatus.FakeNxdomain
                            "EMPTY" -> CheckStatus.FakeEmpty
                            "UNAVAIL" -> CheckStatus.Error
                            else -> CheckStatus.DnsIntercept
                        }
                    }

                    udpIps.isNotEmpty() && trusted.isEmpty() -> {


                        val dohFailedStatuses = listOf("BLOCKED", "TIMEOUT", "UNAVAIL")
                        val dohFailed = (jsonStatus in dohFailedStatuses) ||
                                (wireStatus in dohFailedStatuses)
                        when {
                            udpIsFakeIp -> CheckStatus.FakeIp
                            dohFailed -> CheckStatus.DohBlocked
                            else -> CheckStatus.DnsSpoof
                        }
                    }

                    else -> CheckStatus.Error
                }

                Log.d(
                    TAG,
                    "[$domain] UDP: $udpIps (${udpStatus ?: "—"}) | JSON: $jsonIps (${jsonStatus ?: "—"}) | Wire: $wireIps (${wireStatus ?: "—"})"
                )
                Log.d(
                    TAG,
                    "[$domain] Trusted: $trusted | Intersection: ${udpIps.toSet() intersect trusted} | Status: $status | FakeIP: $udpIsFakeIp | StubIPs: ${stubIps.filter { it in udpIps }}"
                )

                val details = buildString {
                    append("UDP: ${udpIps.joinToString(", ") ?: "—"}")
                    if (udpStatus != null) append(" ($udpStatus)")
                    append(" | DoH JSON: ${jsonIps.joinToString(", ") ?: "—"}")
                    if (jsonStatus != null) append(" ($jsonStatus)")
                    append(" | DoH Wire: ${wireIps.joinToString(", ") ?: "—"}")
                    if (wireStatus != null) append(" ($wireStatus)")
                    if (status == CheckStatus.DnsSpoof) {
                        append("\nПересечение: пусто")
                    } else if (status == CheckStatus.Ok) {
                        val intersection = udpIps.toSet() intersect trusted
                        append("\nПересечение: ${intersection.joinToString(", ")}")
                    }
                }

                val allIps = udpIps + jsonIps + wireIps
                val uniqueIps = allIps.toSet().size

                val rawJson = (jsonProbe.results["${domain}__raw"] as? String)

                DnsCheckResult(
                    domain = domain,
                    udpIps = udpIps,
                    jsonIps = jsonIps,
                    wireIps = wireIps,
                    udpStatus = udpStatus,
                    jsonStatus = jsonStatus,
                    wireStatus = wireStatus,
                    status = status,
                    details = details,
                    totalUniqueIps = uniqueIps,
                    jsonRawResponse = rawJson
                )
            }
            onProgress?.invoke(100, "Готово")
            results
        }
    }








    suspend fun quickCheckDns(domain: String, timeoutMs: Long = 3000): QuickDnsVerdict {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Quick DNS check for $domain")
            val udpIps = QUICK_UDP_SERVERS
                .map { server -> async { resolveUdpSingle(server, domain, timeoutMs) } }
                .awaitAll()
                .flatten()
                .distinct()
            val dohIps = QUICK_DOH_SERVERS
                .map { server -> async { resolveDoHJsonSingle(server, domain, timeoutMs) } }
                .awaitAll()
                .flatten()
                .distinct()
            classifyDnsVerdict(udpIps, dohIps)
        }
    }










    internal fun classifyDnsVerdict(udpIps: List<String>, dohIps: List<String>): QuickDnsVerdict {
        val details = buildString {
            append("UDP: ${udpIps.ifEmpty { listOf("—") }.joinToString(", ")}")
            append(" | DoH: ${dohIps.ifEmpty { listOf("—") }.joinToString(", ")}")
        }
        val status = when {
            dohIps.isNotEmpty() && udpIps.isNotEmpty() -> {
                val intersection = udpIps.toSet() intersect dohIps.toSet()
                when {
                    intersection.isNotEmpty() -> CheckStatus.Ok
                    udpIps.any { isFakeIp(it) } -> CheckStatus.FakeIp
                    else -> CheckStatus.DnsSpoof
                }
            }

            udpIps.isNotEmpty() -> CheckStatus.Ok
            dohIps.isNotEmpty() -> CheckStatus.DnsIntercept
            else -> CheckStatus.Error
        }
        return QuickDnsVerdict(
            udpIps = udpIps,
            dohIps = dohIps,
            status = status,
            details = details
        )
    }


    private fun pickServer(working: List<DnsServer>, allServers: List<DnsServer>): DnsServer? {
        if (working.isEmpty()) return null
        val firstPreferred = allServers.firstOrNull()?.ip
        for (server in working) {
            if (server.ip == firstPreferred) return server
        }
        return working.first()
    }



    private suspend fun quickPingAllUdp(domain: String): List<ServerQuickResult> = coroutineScope {
        udpServers.map { server ->
            async { ServerQuickResult(server, probeUdpSingle(server.ip, domain)) }
        }.awaitAll()
    }

    private suspend fun quickPingAllJson(domain: String): List<ServerQuickResult> = coroutineScope {
        dohJsonServers.map { server ->
            async { ServerQuickResult(server, probeDoHJsonSingle(server.ip, domain)) }
        }.awaitAll()
    }

    private suspend fun quickPingAllWire(domain: String): List<ServerQuickResult> = coroutineScope {
        dohWireServers.map { server ->
            async { ServerQuickResult(server, probeDoHWireSingle(server.ip, domain)) }
        }.awaitAll()
    }



    private suspend fun probeUdpSingle(serverIp: String, domain: String): List<String>? {
        var lastResult = emptyList<String>()
        for (attempt in 0 until RETRY_ATTEMPTS) {
            val ips = resolveUdpSingle(serverIp, domain, 5000L)
            if (ips.isNotEmpty()) {
                Log.d(
                    TAG,
                    "[Phase1] UDP $serverIp $domain: success on attempt ${attempt + 1}: $ips"
                )
                return ips
            }
            lastResult = ips
            if (attempt < RETRY_ATTEMPTS - 1) {
                Log.d(
                    TAG,
                    "[Phase1] UDP $serverIp $domain: attempt ${attempt + 1} returned empty, retrying..."
                )
                delay(RETRY_DELAY_MS)
            }
        }
        Log.d(TAG, "[Phase1] UDP $serverIp $domain: all attempts failed, last: $lastResult")
        return null
    }

    private suspend fun probeDoHJsonSingle(url: String, domain: String): List<String>? {
        for (attempt in 0 until RETRY_ATTEMPTS) {
            val ips = resolveDoHJsonSingle(url, domain, 5000L)
            if (ips.isNotEmpty()) {
                Log.d(TAG, "[Phase1] JSON $url $domain: success on attempt ${attempt + 1}: $ips")
                return ips
            }
            if (attempt < RETRY_ATTEMPTS - 1) {
                Log.d(
                    TAG,
                    "[Phase1] JSON $url $domain: attempt ${attempt + 1} returned empty, retrying..."
                )
                delay(RETRY_DELAY_MS)
            }
        }
        Log.d(TAG, "[Phase1] JSON $url $domain: all attempts failed")
        return null
    }

    private suspend fun probeDoHWireSingle(url: String, domain: String): List<String>? {
        for (attempt in 0 until RETRY_ATTEMPTS) {
            val ips = resolveDoHWireSingle(url, domain, 5000L)
            if (ips.isNotEmpty()) {
                Log.d(TAG, "[Phase1] Wire $url $domain: success on attempt ${attempt + 1}: $ips")
                return ips
            }
            if (attempt < RETRY_ATTEMPTS - 1) {
                Log.d(
                    TAG,
                    "[Phase1] Wire $url $domain: attempt ${attempt + 1} returned empty, retrying..."
                )
                delay(RETRY_DELAY_MS)
            }
        }
        Log.d(TAG, "[Phase1] Wire $url $domain: all attempts failed")
        return null
    }



    private suspend fun probeUdpAll(
        serverIp: String,
        domains: List<String>,
        timeoutMs: Long
    ): FullProbeResult {
        val results = mutableMapOf<String, Any>()
        val lock = Any()
        var ok = 0
        var timeoutCnt = 0
        var error = 0

        coroutineScope {
            domains.map { domain ->
                async {
                    try {
                        val channel = java.nio.channels.DatagramChannel.open()
                        try {
                            channel.configureBlocking(true)
                            channel.socket().soTimeout = timeoutMs.toInt()

                            val txId = (1..65535).random().toShort()
                            val query = DnsWireFormat.buildDnsQuery(txId, domain)

                            val serverAddr = java.net.InetSocketAddress(serverIp, 53)
                            channel.send(ByteBuffer.wrap(query), serverAddr)

                            val buffer = ByteBuffer.allocate(512)
                            val sender = channel.receive(buffer)

                            buffer.flip()
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)

                            val result = DnsWireFormat.parseDnsResponse(data, txId)
                            synchronized(lock) {
                                if (result is List<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    results[domain] = result as List<String>
                                    ok++
                                } else if (result == "NXDOMAIN") {
                                    results[domain] = "NXDOMAIN"
                                    ok++
                                } else {
                                    results[domain] = "ERROR"
                                    error++
                                }
                            }
                        } catch (e: Exception) {
                            val isTimeout = e is java.net.SocketTimeoutException ||
                                    e is java.nio.channels.ClosedByInterruptException
                            synchronized(lock) {
                                results[domain] = if (isTimeout) "TIMEOUT" else "ERROR"
                                if (isTimeout) timeoutCnt++ else error++
                            }
                        } finally {
                            channel.close()
                        }
                    } catch (_: Exception) {
                        synchronized(lock) {
                            results[domain] = "TIMEOUT"
                            timeoutCnt++
                        }
                    }
                }
            }.awaitAll()
        }

        return FullProbeResult(ok, timeoutCnt, error, 0, results)
    }

    private suspend fun probeDoHJsonAll(
        url: String,
        domains: List<String>,
        timeoutMs: Long
    ): FullProbeResult {
        val results = mutableMapOf<String, Any>()
        val lock = Any()
        var ok = 0
        var timeoutCnt = 0
        var blocked = 0

        val client = createInsecureClient(timeoutMs)

        coroutineScope {
            domains.map { domain ->
                async {
                    try {
                        val encodedDomain = java.net.URLEncoder.encode(domain, "UTF-8")
                        val request = okhttp3.Request.Builder()
                            .url("$url?name=$encodedDomain&type=A")
                            .header("Accept", "application/dns-json")
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                            )
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                synchronized(lock) {
                                    results[domain] = "BLOCKED"
                                    blocked++
                                }
                                return@use
                            }

                            val json = response.body?.string() ?: return@use
                            Log.d(TAG, "[DoH JSON] Response for $domain: ${json.take(200)}")
                            val answerArray = extractAnswerArray(json)

                            synchronized(lock) {
                                if (answerArray != null) {
                                    val ips = answerArray.filter { it["type"] == 1 }
                                        .mapNotNull { it["data"] as? String }
                                    if (ips.isNotEmpty()) {
                                        results[domain] = ips
                                        results["${domain}__raw"] = json
                                        ok++
                                    } else {
                                        val statusMatch =
                                            Regex("\"Status\"\\s*:\\s*(\\d+)").find(json)
                                        val status =
                                            statusMatch?.groupValues?.get(1)?.toInt()
                                        if (status == 3) {
                                            results[domain] = "NXDOMAIN"
                                            results["${domain}__raw"] = json
                                            ok++
                                        } else {
                                            results[domain] = "EMPTY"
                                            results["${domain}__raw"] = json
                                            ok++
                                        }
                                    }
                                } else {
                                    results[domain] = "EMPTY"
                                    results["${domain}__raw"] = json
                                    ok++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val isTimeout = e is java.net.SocketTimeoutException ||
                                (e is java.io.IOException && e.cause is java.net.SocketTimeoutException)
                        synchronized(lock) {
                            results[domain] = if (isTimeout) "TIMEOUT" else "BLOCKED"
                            if (isTimeout) timeoutCnt++ else blocked++
                        }
                    }
                }
            }.awaitAll()
        }

        client.dispatcher.executorService.shutdown()
        return FullProbeResult(ok, timeoutCnt, 0, blocked, results)
    }

    private suspend fun probeDoHWireAll(
        url: String,
        domains: List<String>,
        timeoutMs: Long
    ): FullProbeResult {
        val results = mutableMapOf<String, Any>()
        val lock = Any()
        var ok = 0
        var timeoutCnt = 0
        var blocked = 0

        val client = createInsecureClient(timeoutMs)

        coroutineScope {
            domains.map { domain ->
                async {
                    try {
                        val txId = (1..65535).random().toShort()
                        val query = DnsWireFormat.buildDnsQuery(txId, domain)

                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .post(okhttp3.RequestBody.create(null, query))
                            .header("Content-Type", "application/dns-message")
                            .header("Accept", "application/dns-message")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {

                                val dnsB64 = java.util.Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(query)
                                val getRequest = okhttp3.Request.Builder()
                                    .url("$url?dns=$dnsB64")
                                    .header("Accept", "application/dns-message")
                                    .build()

                                client.newCall(getRequest).execute().use { getResponse ->
                                    if (!getResponse.isSuccessful) {
                                        synchronized(lock) {
                                            results[domain] = "BLOCKED"
                                            blocked++
                                        }
                                        return@use
                                    }

                                    val data = getResponse.body?.bytes() ?: return@use
                                    val result = DnsWireFormat.parseDnsResponse(data, txId)
                                    synchronized(lock) {
                                        if (result is List<*>) {
                                            @Suppress("UNCHECKED_CAST")
                                            results[domain] = result as List<String>
                                            ok++
                                        } else if (result == "NXDOMAIN") {
                                            results[domain] = "NXDOMAIN"
                                            ok++
                                        } else {
                                            results[domain] = "EMPTY"
                                            ok++
                                        }
                                    }
                                }
                            } else {
                                val data = response.body?.bytes() ?: return@use
                                val result = DnsWireFormat.parseDnsResponse(data, txId)
                                synchronized(lock) {
                                    if (result is List<*>) {
                                        @Suppress("UNCHECKED_CAST")
                                        results[domain] = result as List<String>
                                        ok++
                                    } else if (result == "NXDOMAIN") {
                                        results[domain] = "NXDOMAIN"
                                        ok++
                                    } else {
                                        results[domain] = "EMPTY"
                                        ok++
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val isTimeout = e is java.net.SocketTimeoutException ||
                                (e is java.io.IOException && e.cause is java.net.SocketTimeoutException)
                        synchronized(lock) {
                            results[domain] = if (isTimeout) "TIMEOUT" else "BLOCKED"
                            if (isTimeout) timeoutCnt++ else blocked++
                        }
                    }
                }
            }.awaitAll()
        }

        client.dispatcher.executorService.shutdown()
        return FullProbeResult(ok, timeoutCnt, 0, blocked, results)
    }



    internal fun resolveUdpSingle(serverIp: String, domain: String, timeoutMs: Long): List<String> =
        resolveUdp(serverIp, 53, domain, timeoutMs)

    internal fun resolveUdp(
        serverIp: String,
        port: Int,
        domain: String,
        timeoutMs: Long
    ): List<String> {
        return try {
            val channel = java.nio.channels.DatagramChannel.open()
            try {
                channel.configureBlocking(true)
                channel.socket().soTimeout = timeoutMs.toInt()

                val txId = (1..65535).random().toShort()
                val query = DnsWireFormat.buildDnsQuery(txId, domain)

                val serverAddr = java.net.InetSocketAddress(serverIp, port)
                channel.send(ByteBuffer.wrap(query), serverAddr)

                val buffer = ByteBuffer.allocate(512)
                val sender = channel.receive(buffer)

                buffer.flip()
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                val result = DnsWireFormat.parseDnsResponse(data, txId)
                if (result is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    result as List<String>
                } else {
                    emptyList()
                }
            } finally {
                channel.close()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun resolveDoHJsonSingle(url: String, domain: String, timeoutMs: Long): List<String> {
        val client = createInsecureClient(timeoutMs)
        return try {
            val encodedDomain = java.net.URLEncoder.encode(domain, "UTF-8")
            val request = okhttp3.Request.Builder()
                .url("$url?name=$encodedDomain&type=A")
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            val result = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emptyList()
                } else {
                    val json = response.body?.string() ?: return@use emptyList()
                    extractAnswerArray(json)?.filter { it["type"] == 1 }
                        ?.mapNotNull { it["data"] as? String } ?: emptyList()
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    internal fun resolveDoHWireSingle(url: String, domain: String, timeoutMs: Long): List<String> {
        val client = createInsecureClient(timeoutMs)
        return try {
            val txId = (1..65535).random().toShort()
            val query = DnsWireFormat.buildDnsQuery(txId, domain)

            val request = okhttp3.Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(null, query))
                .header("Content-Type", "application/dns-message")
                .header("Accept", "application/dns-message")
                .build()

            val result = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val data = response.body?.bytes() ?: return@use emptyList()
                    val parsed = DnsWireFormat.parseDnsResponse(data, txId)
                    if (parsed is List<*>) {
                        @Suppress("UNCHECKED_CAST")
                        parsed as List<String>
                    } else {
                        emptyList()
                    }
                } else {

                    val dnsB64 = java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(query)
                    val getRequest = okhttp3.Request.Builder()
                        .url("$url?dns=$dnsB64")
                        .header("Accept", "application/dns-message")
                        .build()

                    client.newCall(getRequest).execute().use { getResponse ->
                        if (getResponse.isSuccessful) {
                            val data = getResponse.body?.bytes() ?: return@use emptyList()
                            val parsed = DnsWireFormat.parseDnsResponse(data, txId)
                            if (parsed is List<*>) {
                                @Suppress("UNCHECKED_CAST")
                                parsed as List<String>
                            } else {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun extractAnswerArray(json: String): List<Map<String, Any>>? {
        return try {
            val jsonParsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .parseToJsonElement(json)
            val answerArray = (jsonParsed as? kotlinx.serialization.json.JsonObject)?.get("Answer")
                ?.let { it as? kotlinx.serialization.json.JsonArray }
            if (answerArray == null) {
                Log.d(TAG, "[DoH JSON] No 'Answer' field found. JSON: ${json.take(500)}")
                return null
            }

            val entries = mutableListOf<Map<String, Any>>()
            for (entry in answerArray) {
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: continue
                val type = obj.get("type")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content?.toInt()
                val data = obj.get("data")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                if (type != null && data != null) {
                    entries += mapOf("type" to type, "data" to data)
                }
            }
            if (entries.isEmpty()) null else entries
        } catch (e: Exception) {
            Log.d(TAG, "[DoH JSON] Parse error: ${e.message}. JSON: ${json.take(500)}")
            null
        }
    }

    private fun isFakeIp(ip: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ip)
            val bytes = addr.address ?: return false
            bytes[0] == 198.toByte() && (bytes[1].toInt() and 0xFE) == 18
        } catch (e: Exception) {
            false
        }
    }
}




data class QuickDnsVerdict(
    val udpIps: List<String>,
    val dohIps: List<String>,
    val status: CheckStatus,
    val details: String?
)
