package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.SweepResult
import com.lsd.wififrankenstein.ui.internetblocking.model.TcpCheckResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SniScanner {
    companion object {
        private const val TAG = "SniScanner"
        private const val SNI_BATCH_SIZE = 5
        private const val TOP_N = 3
        private const val CONNECT_TIMEOUT = 8L
        private const val READ_TIMEOUT = 8L


        private val CONNECT_FAILED_STATUSES = setOf(
            CheckStatus.SynDrop,
            CheckStatus.Refused,
            CheckStatus.NetUnreachable,
            CheckStatus.HostUnreachable,
            CheckStatus.TcpAbort,
            CheckStatus.TcpRst,
            CheckStatus.Timeout
        )

        val BASE_SNI_LIST = listOf(
            "2gis.com",
            "2gis.ru",
            "300.ya.ru",
            "3475482542.mc.yandex.ru",
            "742231.ms.ok.ru",
            "a.wb.ru",
            "ad.adriver.ru",
            "ad.mail.ru",
            "adm.mp.rzd.ru",
            "akashi.vk-portal.net",
            "alfabank.ru",
            "ams2-cdn.2gis.com",
            "an.yandex.ru",
            "api.2gis.ru",
            "api.avito.ru",
            "api.browser.yandex.com",
            "api.browser.yandex.ru",
            "api.events.plus.yandex.net",
            "api.github.com",
            "api.mindbox.ru",
            "api.photo.2gis.com",
            "api.reviews.2gis.com",
            "api-maps.yandex.ru",
            "apple.com",
            "avatars.mds.yandex.com",
            "avatars.mds.yandex.net",
            "avito.ru",
            "banners-website.wildberries.ru",
            "bot.gosuslugi.ru",
            "bro-bg-store.s3.yandex.com",
            "bro-bg-store.s3.yandex.net",
            "bro-bg-store.s3.yandex.ru",
            "brontp-pre.yandex.ru",
            "browser.yandex.com",
            "browser.yandex.ru",
            "cargo.rzd.ru",
            "catalog.api.2gis.com",
            "cdn.jsdelivr.net",
            "cdn.lemanapro.ru",
            "cdn.yandex.ru",
            "cdnrhkgfkkpupuotntfj.svc.cdn.yandex.net",
            "chat3.vtb.ru",
            "chat-prod.wildberries.ru",
            "cloud.cdn.yandex.com",
            "cloud.cdn.yandex.net",
            "cloud.cdn.yandex.ru",
            "cloudcdn-ams19.cdn.yandex.net",
            "cloudflare.com",
            "collections.yandex.com",
            "collections.yandex.ru",
            "company.rzd.ru",
            "contacts.rzd.ru",
            "contract.gosuslugi.ru",
            "cs.avito.ru",
            "csp.yandex.net",
            "d-assets.2gis.ru",
            "disk.2gis.com",
            "disk.rzd.ru",
            "dmp.dmpkit.lemanapro.ru",
            "dr.yandex.net",
            "dr2.yandex.net",
            "dzen.ru",
            "egress.yandex.net",
            "eh.vk.com",
            "ekmp-a-51.rzd.ru",
            "enterprise.api-maps.yandex.ru",
            "esia.gosuslugi.ru",
            "favicon.yandex.com",
            "favicon.yandex.net",
            "favicon.yandex.ru",
            "favorites.api.2gis.com",
            "filekeeper-vod.2gis.com",
            "fonts.googleapis.com",
            "frontend.vh.yandex.ru",
            "gosuslugi.ru",
            "hcaptcha.com",
            "i0.photo.2gis.com",
            "i1.photo.2gis.com",
            "i2.photo.2gis.com",
            "i3.photo.2gis.com",
            "i4.photo.2gis.com",
            "i5.photo.2gis.com",
            "i6.photo.2gis.com",
            "i7.photo.2gis.com",
            "i8.photo.2gis.com",
            "i9.photo.2gis.com",
            "id.sber.ru",
            "jam.api.2gis.com",
            "keys.api.2gis.com",
            "kiks.yandex.com",
            "kiks.yandex.ru",
            "lemanapro.ru",
            "link.mp.rzd.ru",
            "lk.gosuslugi.ru",
            "log.strm.yandex.ru",
            "login.vk.com",
            "m.avito.ru",
            "mail.yandex.com",
            "mail.yandex.ru",
            "map.gosuslugi.ru",
            "mapgl.2gis.com",
            "market.rzd.ru",
            "mc.yandex.com",
            "mc.yandex.ru",
            "mddc.tinkoff.ru",
            "mediafeeds.yandex.com",
            "mediafeeds.yandex.ru",
            "metrics.alfabank.ru",
            "microsoft.com",
            "mp.rzd.ru",
            "my.rzd.ru",
            "neuro.translate.yandex.ru",
            "novorossiya.gosuslugi.ru",
            "ok.ru",
            "ozon.ru",
            "partners.gosuslugi.ru",
            "partners.lemanapro.ru",
            "personalization-web-stable.mindbox.ru",
            "pos.gosuslugi.ru",
            "privacy-cs.mail.ru",
            "prodvizhenie.rzd.ru",
            "public-api.reviews.2gis.com",
            "pulse.mp.rzd.ru",
            "queuev4.vk.com",
            "rs.mail.ru",
            "rzd.ru",
            "s.vtb.ru",
            "s0.bss.2gis.com",
            "s1.bss.2gis.com",
            "s3.yandex.net",
            "sba.yandex.com",
            "sba.yandex.net",
            "sba.yandex.ru",
            "secure.rzd.ru",
            "secure-cloud.rzd.ru",
            "sfd.gosuslugi.ru",
            "sntr.avito.ru",
            "speller.yandex.net",
            "splitter.wb.ru",
            "sso.dzen.ru",
            "sso-app4.vtb.ru",
            "sso-app5.vtb.ru",
            "st.avito.ru",
            "static.lemanapro.ru",
            "static-mon.yandex.net",
            "stats.avito.ru",
            "stats.vk-portal.net",
            "st-ok.cdn-vk.ru",
            "storage.ape.yandex.net",
            "strm.yandex.net",
            "strm.yandex.ru",
            "strm-rad-23.strm.yandex.net",
            "strm-spbmiran-08.strm.yandex.net",
            "styles.api.2gis.com",
            "suggest.dzen.ru",
            "suggest.sso.dzen.ru",
            "surveys.yandex.ru",
            "sync.browser.yandex.net",
            "team.rzd.ru",
            "ticket.rzd.ru",
            "tile0.maps.2gis.com",
            "tile1.maps.2gis.com",
            "tile2.maps.2gis.com",
            "tile3.maps.2gis.com",
            "tile4.maps.2gis.com",
            "top-fwz1.mail.ru",
            "travel.rzd.ru",
            "user-geo-data.wildberries.ru",
            "vk.com",
            "vk-portal.net",
            "wap.yandex.com",
            "wap.yandex.ru",
            "wb.ru",
            "web.max.ru",
            "web-static.mindbox.ru",
            "welcome.rzd.ru",
            "widgets.cbonds.ru",
            "www.avito.ru",
            "www.facebook.com",
            "www.google.com",
            "www.gosuslugi.ru",
            "www.instagram.com",
            "www.ozon.ru",
            "www.rzd.ru",
            "www.vtb.ru",
            "www.wildberries.ru",
            "www.youtube.com",
            "xapi.ozon.ru",
            "yabro-wbplugin.edadeal.yandex.ru",
            "yabs.yandex.ru",
            "yandex.com",
            "yandex.net",
            "yandex.ru",
            "yastatic.net",
            "zen.yandex.com",
            "zen.yandex.net",
            "zen.yandex.ru",
            "zen-yabro-morda.mediascope.mc.yandex.ru"
        )

        val RUSSIA_SNI_LIST = listOf(
            "yandex.ru",
            "vk.com",
            "ok.ru",
            "mail.ru",
            "wildberries.ru",
            "ozon.ru",
            "sberbank.ru",
            "tinkoff.ru",
            "avito.ru",
            "gosuslugi.ru",
            "rzhd.ru",
            "vtb.ru",
            "alfabank.ru",
            "mts.ru",
            "megafon.ru",
            "beeline.ru",
            "tele2.ru",
            "youtube.com",
            "google.ru",
            "rambler.ru"
        )

        val UKRAINE_SNI_LIST = listOf(
            "ukr.net",
            "olx.ua",
            "rozetka.com.ua",
            "pravda.com.ua",
            "unian.ua",
            "prom.ua",
            "obozrevatel.com",
            "korrespondent.net",
            "ria.com",
            "privatbank.ua",
            "tsn.ua",
            "rbc.ua",
            "epicentrk.ua",
            "24tv.ua",
            "sport.ua",
            "sinoptik.ua",
            "tabletki.ua",
            "censor.net",
            "novyny.live",
            "monobank.ua"
        )

        val CHINA_SNI_LIST = listOf(
            "baidu.com",
            "qq.com",
            "taobao.com",
            "weixin.qq.com",
            "douyin.com",
            "alipay.com",
            "sina.com.cn",
            "163.com",
            "jd.com",
            "sohu.com",
            "ctrip.com",
            "zhihu.com",
            "bilibili.com",
            "meituan.com",
            "dianping.com",
            "xinhuanet.com",
            "gov.cn",
            "12306.cn",
            "csdn.net",
            "nationalgeographic.com"
        )

        val BELARUS_SNI_LIST = listOf(
            "onliner.by",
            "tut.by",
            "21.by",
            "belarusbank.by",
            "belarus.by",
            "president.gov.by",
            "minfin.gov.by",
            "sputnik.by",
            "ctv.by",
            "ont.by",
            "belta.by",
            "kufar.by",
            "realt.by",
            "dev.by",
            "alfabank.by",
            "priorbank.by",
            "belagroprombank.by",
            "mtbank.by",
            "bsuir.by",
            "tam.by"
        )

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
    }

    suspend fun sweepSni(
        targets: List<TcpCheckResult>,
        sniWhitelist: List<String> = BASE_SNI_LIST,
        batchSize: Int = SNI_BATCH_SIZE,
        topN: Int = TOP_N
    ): List<SweepResult> {
        return withContext(Dispatchers.IO) {
            Log.d(
                TAG,
                "Starting SNI sweep for ${targets.size} targets with ${sniWhitelist.size} SNIs"
            )



            val candidates = targets.filter { isSweepCandidate(it) }

            if (candidates.isEmpty()) {
                Log.d(TAG, "No sweep candidates on port 443")
                return@withContext emptyList()
            }

            Log.d(TAG, "Found ${candidates.size} sweep candidates on port 443")

            val semaphore = Semaphore(4)
            val allResults = mutableListOf<SweepResult>()



            val grouped = candidates.groupBy { it.asn ?: it.ip }
            for ((_, groupTargets) in grouped) {
                val target = groupTargets.minByOrNull { it.rtt ?: Float.MAX_VALUE }
                    ?: groupTargets.first()
                allResults.addAll(sweepForTarget(target, sniWhitelist, batchSize, topN, semaphore))
            }

            Log.d(TAG, "SNI sweep complete: ${allResults.size} working SNI found")
            allResults
        }
    }







    internal fun isSweepCandidate(target: TcpCheckResult): Boolean {
        return target.port == 443 &&
                target.ip.isNotEmpty() &&
                target.status != CheckStatus.Ok &&
                target.status !in CONNECT_FAILED_STATUSES
    }

    private suspend fun sweepForTarget(
        target: TcpCheckResult,
        sniWhitelist: List<String>,
        batchSize: Int,
        topN: Int,
        semaphore: Semaphore
    ): List<SweepResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SweepResult>()



        val client = createClient(pinnedIp = target.ip)
        try {

            try {
                semaphore.acquire()
                try {
                    val bareResult = checkWithSni(target, "", client)
                    if (bareResult.status == CheckStatus.Ok) {
                        results.add(
                            SweepResult(
                                targetId = target.id,
                                targetProvider = target.provider,
                                targetIp = target.ip,
                                targetPort = target.port,
                                workingSni = "(no SNI)",
                                rtt = bareResult.elapsed.toFloat(),
                                status = CheckStatus.Ok
                            )
                        )
                        Log.d(TAG, "${target.provider}: Bare IP OK")
                    }
                } finally {
                    semaphore.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bare IP check failed for ${target.provider}: ${e.message}")
            }

            if (results.size >= topN) return@withContext results


            val batches = sniWhitelist.chunked(batchSize)

            for (batch in batches) {
                if (results.size >= topN) break

                val batchResults = mutableListOf<Pair<String, DpiResult>>()

                coroutineScope {
                    val deferred = batch.map { sni ->
                        async {
                            try {
                                semaphore.acquire()
                                try {
                                    val result = checkWithSni(target, sni, client)
                                    sni to result
                                } finally {
                                    semaphore.release()
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "SNI check failed for ${target.provider} with SNI $sni: ${e.message}"
                                )
                                null
                            }
                        }
                    }
                    batchResults.addAll(deferred.awaitAll().filterNotNull())
                }


                for ((sni, dpiResult) in batchResults) {
                    if (results.size >= topN) break
                    if (dpiResult.status == CheckStatus.Ok) {
                        results.add(
                            SweepResult(
                                targetId = target.id,
                                targetProvider = target.provider,
                                targetIp = target.ip,
                                targetPort = target.port,
                                workingSni = sni,
                                rtt = dpiResult.elapsed.toFloat(),
                                status = CheckStatus.Ok
                            )
                        )
                        Log.d(
                            TAG,
                            "${target.provider}: Working SNI found: $sni (${dpiResult.elapsed}s)"
                        )
                    }
                }
            }

            if (results.isEmpty()) {
                Log.w(TAG, "${target.provider}: No working SNI found")
            }

            results
        } finally {
            try {
                client.dispatcher.executorService.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun checkWithSni(
        target: TcpCheckResult,
        sni: String,
        client: OkHttpClient
    ): DpiResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()


        val url = if (sni.isNotEmpty()) {
            "https://$sni:${target.port}/"
        } else {
            "https://${target.ip}:${target.port}/"
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

        return@withContext try {
            val call = client.newCall(request)
            call.addEventListener(DpiTraceEventListener(DpiTraceState()))

            val response = call.execute()
            val statusCode = response.code
            val bytesRead = response.body?.contentLength() ?: 0

            response.close()

            if (statusCode == 451) {
                DpiResult(
                    status = CheckStatus.Blocked,
                    detail = "HTTP 451",
                    bytesRead = bytesRead,
                    elapsed = elapsed(startTime)
                )
            } else {
                DpiResult(
                    status = CheckStatus.Ok,
                    detail = "HTTP $statusCode",
                    bytesRead = bytesRead,
                    elapsed = elapsed(startTime)
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            DpiResult(
                status = CheckStatus.Timeout,
                detail = "Timeout",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: javax.net.ssl.SSLException) {
            DpiResult(
                status = CheckStatus.SslError,
                detail = "SSL: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: java.net.ConnectException) {
            DpiResult(
                status = CheckStatus.Timeout,
                detail = "Connect: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: IOException) {
            DpiResult(
                status = CheckStatus.Error,
                detail = "IO: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        } catch (e: Exception) {
            DpiResult(
                status = CheckStatus.Error,
                detail = "${e.javaClass.simpleName}: ${e.message}",
                bytesRead = 0,
                elapsed = elapsed(startTime)
            )
        }
    }

    private val createdClients = java.util.Collections.synchronizedList(
        mutableListOf<OkHttpClient>()
    )

    private fun createClient(pinnedIp: String? = null): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)

        if (pinnedIp != null) {
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByName(pinnedIp))
            })
        }

        val client = builder.build()
        createdClients.add(client)
        return client
    }

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

    private fun elapsed(start: Long): Double = (System.currentTimeMillis() - start) / 1000.0
}
