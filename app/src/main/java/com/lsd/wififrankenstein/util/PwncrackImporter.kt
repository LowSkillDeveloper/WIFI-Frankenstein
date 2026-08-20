package com.lsd.wififrankenstein.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class PwncrackEntry(
    val bssid: String,
    val ssid: String,
    val password: String
)

object PwncrackImporter {

    private const val BASE_URL = "https://pwncrack.org"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(apiKey: String): List<PwncrackEntry> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/results?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw PwncrackException("HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw PwncrackException("Empty response")

        val jsonArray = try {
            JSONArray(body)
        } catch (_: Exception) {
            throw PwncrackException("Invalid JSON response")
        }

        val entries = mutableListOf<PwncrackEntry>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val ssid = obj.optString("SSID", "")
            val password = obj.optString("password", "")
            val bssid = obj.optString("BSSID", "")
            if (ssid.isNotEmpty() && password.isNotEmpty()) {
                entries.add(PwncrackEntry(bssid, ssid, password))
            }
        }
        entries
    }
}

class PwncrackException(message: String, cause: Throwable? = null) : Exception(message, cause)
