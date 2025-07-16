package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class API3WiFiHelper(
    private val context: Context,
    private val serverUrl: String,
    private val apiKey: String
) {
    private val cachedResults = mutableMapOf<String, List<Map<String, Any?>>>()
    private var lastRequestTime = 0L
    private val sharedPreferences by lazy {
        context.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)
    }

    var includeAppIdentifier: Boolean
        get() = sharedPreferences.getBoolean("includeAppIdentifier", true)
        set(value) = sharedPreferences.edit { putBoolean("includeAppIdentifier", value) }


    var maxPointsPerRequest: Int
        get() = sharedPreferences.getInt("maxPointsPerRequest", 99)
        set(value) = sharedPreferences.edit { putInt("maxPointsPerRequest", value) }

    var requestDelay: Long
        get() = sharedPreferences.getLong("requestDelay", 1000)
        set(value) = sharedPreferences.edit { putLong("requestDelay", value) }

    var connectTimeout: Int
        get() = sharedPreferences.getInt("connectTimeout", 5000)
        set(value) = sharedPreferences.edit { putInt("connectTimeout", value) }

    var readTimeout: Int
        get() = sharedPreferences.getInt("readTimeout", 10000)
        set(value) = sharedPreferences.edit { putInt("readTimeout", value) }

    var cacheResults: Boolean
        get() = sharedPreferences.getBoolean("cacheResults", true)
        set(value) = sharedPreferences.edit { putBoolean("cacheResults", value) }

    var tryAlternativeUrl: Boolean
        get() = sharedPreferences.getBoolean("tryAlternativeUrl", true)
        set(value) = sharedPreferences.edit { putBoolean("tryAlternativeUrl", value) }

    var ignoreSSLCertificate: Boolean
        get() = sharedPreferences.getBoolean("ignoreSSLCertificate", false)
        set(value) = sharedPreferences.edit { putBoolean("ignoreSSLCertificate", value) }

    class API3WiFiException(val errorCode: String, message: String) : Exception(message)

    init {
        if (ignoreSSLCertificate) {
            ignoreSSLCertificates()
        }
    }

    private fun ignoreSSLCertificates() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)

            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun searchNetworksByBSSIDs(bssids: List<String>): Map<String, List<Map<String, Any?>>> {
        return withContext(Dispatchers.IO) {
            val result = mutableMapOf<String, List<Map<String, Any?>>>()
            val uncachedBssids = if (cacheResults) {
                bssids.filter { !cachedResults.containsKey(it) }
            } else {
                bssids
            }

            uncachedBssids.chunked(maxPointsPerRequest).map { chunk ->
                async {
                    try {
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastRequest = currentTime - lastRequestTime
                        if (timeSinceLastRequest < requestDelay) {
                            delay(requestDelay - timeSinceLastRequest)
                        }

                        val jsonObject = JSONObject().apply {
                            put("key", apiKey)
                            put("bssid", JSONArray(chunk.map { it.uppercase(Locale.ROOT) }))
                            if (includeAppIdentifier) {
                                put("appinfo", context.getString(R.string.app_identifier))
                            }
                        }
                        Log.d("API3WiFiHelper", "Sending request: $jsonObject")

                        val response = sendRequest(jsonObject)
                        val responseJson = JSONObject(response)
                        Log.d("API3WiFiHelper", "Server response: $response")

                        if (responseJson.getBoolean("result")) {
                            val data = responseJson.getJSONObject("data")
                            data.keys().forEach { bssid ->
                                val networks = data.getJSONArray(bssid)
                                val networkList = mutableListOf<Map<String, Any?>>()
                                for (i in 0 until networks.length()) {
                                    val network = networks.getJSONObject(i)
                                    Log.d("API3WiFiHelper", "Network data for $bssid: $network")
                                    networkList.add(mapOf(
                                        "time" to network.getString("time"),
                                        "bssid" to network.getString("bssid"),
                                        "essid" to network.getString("essid"),
                                        "sec" to network.getString("sec"),
                                        "key" to network.getString("key"),
                                        "wps" to network.getString("wps"),
                                        "lat" to network.optDouble("lat"),
                                        "lon" to network.optDouble("lon")
                                    ))
                                }
                                result[bssid.lowercase(Locale.ROOT)] = networkList
                                if (cacheResults) {
                                    cachedResults[bssid] = networkList
                                }
                            }
                        } else {
                            val errorCode = responseJson.getString("error")
                            throw API3WiFiException(errorCode, "API error: $errorCode")
                        }

                        lastRequestTime = System.currentTimeMillis()
                    } catch (e: Exception) {
                        when (e) {
                            is API3WiFiException -> throw e
                            else -> {
                                Log.e("API3WiFiHelper", "Error in API request", e)
                                throw API3WiFiException("network", "Network error: ${e.message}")
                            }
                        }
                    }
                }
            }.awaitAll()

            if (cacheResults) {
                bssids.filter { cachedResults.containsKey(it) }.forEach { bssid ->
                    result[bssid] = cachedResults[bssid] ?: emptyList()
                }
            }

            result
        }
    }

    private suspend fun getPing(): Long {
        return withContext(Dispatchers.IO) {
            val url = URL(serverUrl)
            val startTime = System.currentTimeMillis()
            try {
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout
                connection.connect()
                val elapsedTime = System.currentTimeMillis() - startTime
                Log.d("API3WiFiHelper", "Ping: $elapsedTime ms")
                elapsedTime
            } catch (e: Exception) {
                Log.e("API3WiFiHelper", "Ping error", e)
                -1L
            }
        }
    }

    suspend fun testServer(): Pair<Long, List<Pair<String, Pair<Boolean, String>>>> {
        return withContext(Dispatchers.IO) {
            val pingResult = getPing()
            val results = mutableListOf<Pair<String, Pair<Boolean, String>>>()
            val testBSSID = "01:23:45:67:89:AB"
            val endpoints = listOf(
                "$serverUrl/api/apiquery",
                "$serverUrl/3wifi.php?a=apiquery",
                "$serverUrl/api/apiwps",
                "$serverUrl/3wifi.php?a=apiwps",
                "$serverUrl/api/apidev",
                "$serverUrl/3wifi.php?a=apidev"
            )
            val requestJson = JSONObject().apply {
                put("key", apiKey)
                put("bssid", JSONArray().put(testBSSID))
                if (includeAppIdentifier) {
                    put("appinfo", context.getString(R.string.app_identifier))
                }
            }

            endpoints.forEach { url ->
                val result = sendTestRequest(url, requestJson)
                results.add(url to result)
            }

            pingResult to results
        }
    }

    private suspend fun sendTestRequest(url: String, jsonObject: JSONObject): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout

                connection.outputStream.use { it.write(jsonObject.toString().toByteArray()) }

                val startTime = System.currentTimeMillis()
                val responseCode = connection.responseCode
                val elapsedTime = System.currentTimeMillis() - startTime
                Log.d("API3WiFiHelper", "URL: $url, Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val responseJson = JSONObject(response)
                    Log.d("API3WiFiHelper", "Response: $response")
                    if (responseJson.getBoolean("result")) {
                        Pair(true, "Response Code: $responseCode\nResponse: $response\nTime: $elapsedTime ms")
                    } else {
                        Pair(false, "Response Code: $responseCode\nResponse: $response\nTime: $elapsedTime ms")
                    }
                } else {
                    Log.e("API3WiFiHelper", "HTTP error code: $responseCode")
                    Pair(false, "Response Code: $responseCode\nTime: $elapsedTime ms")
                }
            } catch (e: Exception) {
                Log.e("API3WiFiHelper", "Error in sendTestRequest", e)
                Pair(false, "Error: ${e.message}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun sendRequest(jsonObject: JSONObject): String {
        val urls = if (tryAlternativeUrl) {
            listOf("$serverUrl/api/apiquery", "$serverUrl/3wifi.php?a=apiquery")
        } else {
            listOf("$serverUrl/api/apiquery")
        }

        for (url in urls) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = connectTimeout
                connection.readTimeout = readTimeout

                connection.outputStream.use { it.write(jsonObject.toString().toByteArray()) }

                val responseCode = connection.responseCode
                Log.d("API3WiFiHelper", "Server response code: $responseCode")

                when (responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        return connection.inputStream.bufferedReader().use { it.readText() }
                    }
                    HttpURLConnection.HTTP_FORBIDDEN -> {
                        throw API3WiFiException("forbidden", "Access forbidden. Check your API key and permissions.")
                    }
                    else -> {
                        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                        Log.e("API3WiFiHelper", "HTTP error: $responseCode. Body: $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("API3WiFiHelper", "Error sending request to $url", e)

            }
        }

        throw API3WiFiException("network", "Failed to send request to all URLs")
    }

    fun clearCache() {
        cachedResults.clear()
    }
}
