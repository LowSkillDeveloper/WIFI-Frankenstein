package com.lsd.wififrankenstein.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class WpaSecEntry(
    val bssid: String,
    val ssid: String,
    val password: String
)

object WpaSecImporter {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(apiKey: String): List<WpaSecEntry> = withContext(Dispatchers.IO) {
        val url = "https://wpa-sec.stanev.org/?api&dl=1"

        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", "key=$apiKey")
            .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw WpaSecException("HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw WpaSecException("Empty response")

        body.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size >= 4) {
                    val bssidRaw = parts[0]
                    val ssid = parts[2]
                    val password = parts.drop(3).joinToString(":")
                    val bssid = bssidRaw.chunked(2).joinToString(":").uppercase()

                    if (bssid.isNotEmpty() && ssid.isNotEmpty()) {
                        WpaSecEntry(bssid, ssid, password)
                    } else null
                } else null
            }
    }
}

class WpaSecException(message: String, cause: Throwable? = null) : Exception(message, cause)
