package com.lsd.wififrankenstein.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class NetworkClient(private val context: Context) {

    companion object {

        @Volatile
        private var INSTANCE: NetworkClient? = null

        fun getInstance(context: Context): NetworkClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val fallbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                return when (hostname) {
                    "github.com" -> listOf(
                        InetAddress.getByName("140.82.112.3"),
                        InetAddress.getByName("140.82.112.4")
                    )
                    "raw.githubusercontent.com" -> listOf(
                        InetAddress.getByName("185.199.108.133"),
                        InetAddress.getByName("185.199.109.133")
                    )
                    else -> throw e
                }
            }
        }
    }

    private val okHttpClient by lazy {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .dns(fallbackDns)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .retryOnConnectionFailure(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .dns(fallbackDns)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        if (!NetworkUtils.hasActiveConnection(context)) {
            throw NetworkException("No active network connection")
        }

        val urlsToTry = if (url.contains("github.com")) {
            listOf(
                url,
                url.replace("github.com", "raw.githubusercontent.com").replace("/raw/", "/")
            )
        } else {
            listOf(url)
        }

        var lastException: Exception? = null

        for ((index, attemptUrl) in urlsToTry.withIndex()) {
            try {
                val request = Request.Builder()
                    .url(attemptUrl)
                    .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                return@withContext response.body?.string() ?: throw Exception("Empty response")

            } catch (e: Exception) {
                lastException = e
                if (index < urlsToTry.size - 1) {
                    delay(1000)
                }
            }
        }

        throw NetworkException("Network request failed", lastException)
    }
}

class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)