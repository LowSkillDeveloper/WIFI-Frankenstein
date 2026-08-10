package com.lsd.wififrankenstein.ui.api3wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class WpaSecHelper {

    companion object {
        private const val BASE_URL = "https://wpa-sec.stanev.org"
        private const val ENDPOINT_MACSSID = "$BASE_URL/bmacssid"
        private const val TIMEOUT = 10000
    }

    data class WpaSecResult(
        val bssid: String,
        val ssid: String,
        val isLeaked: Boolean,
        val error: String? = null
    )

    suspend fun checkBssidSsid(bssid: String, ssid: String): WpaSecResult {
        return withContext(Dispatchers.IO) {
            try {
                val cleanBssid = bssid.replace(":", "").replace("-", "").lowercase()
                val ssidHex =
                    ssid.encodeToByteArray().joinToString("") { "%02x".format(it) }.lowercase()
                val hashInput = "$cleanBssid$ssidHex"
                val hash = sha1Hex(hashInput)
                val clid = hash.substring(0, 4)
                val suffix = hash.substring(24)

                val requestBody = JSONArray(listOf(clid)).toString()
                val response = postJson(ENDPOINT_MACSSID, requestBody)

                val json = JSONObject(response)
                val suffixes = json.optJSONArray(clid)
                if (suffixes != null) {
                    for (i in 0 until suffixes.length()) {
                        if (suffixes.getString(i) == suffix) {
                            return@withContext WpaSecResult(cleanBssid, ssid, true)
                        }
                    }
                }

                WpaSecResult(cleanBssid, ssid, false)
            } catch (e: Exception) {
                WpaSecResult(bssid, ssid, false, e.message)
            }
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(input.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun postJson(urlString: String, jsonBody: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        try {
            connection.outputStream.use { it.write(jsonBody.encodeToByteArray()) }
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode"
                throw Exception(err)
            }
        } finally {
            connection.disconnect()
        }
    }
}
