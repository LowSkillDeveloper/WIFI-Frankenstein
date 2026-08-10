package com.lsd.wififrankenstein.util

import java.net.HttpURLConnection
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SslHelper {
    fun configure(connection: HttpURLConnection): HttpURLConnection {
        if (connection is HttpsURLConnection) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(
                    certs: Array<X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    certs: Array<X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return connection
    }
}
