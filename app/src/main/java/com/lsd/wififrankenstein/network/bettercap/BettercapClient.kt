package com.lsd.wififrankenstein.network.bettercap

import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class BettercapClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 8081,
    private val username: String = "wff",
    private val password: String = "wff"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val basicAuth =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", basicAuth)
                    .build()
                chain.proceed(request)
            }
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun url(path: String): String = "http://$host:$port$path"

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/session"))
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.d("BettercapClient", "ping failed: ${e.message}")
            false
        }
    }

    suspend fun waitForReady(timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ping()) return@withContext true
            delay(500)
        }
        false
    }

    suspend fun getWifiState(): WifiState = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/session/wifi"))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                json.decodeFromString<WifiState>(body)
            }
        } catch (e: Exception) {
            Log.d("BettercapClient", "getWifiState failed: ${e.message}")
            WifiState()
        }
    }

    suspend fun executeCommand(cmd: String): SessionResponse = withContext(Dispatchers.IO) {
        try {
            val escapedCmd = cmd.replace("\\", "\\\\").replace("\"", "\\\"")
            val cmdJson = """{"cmd":"$escapedCmd"}"""
            val request = Request.Builder()
                .url(url("/api/session"))
                .post(cmdJson.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: """{"success":false,"msg":"empty response"}"""
                json.decodeFromString<SessionResponse>(body)
            }
        } catch (e: Exception) {
            SessionResponse(success = false, msg = e.message ?: "unknown error")
        }
    }

    suspend fun getEvents(n: Int = 50): List<BettercapEvent> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/events?n=$n"))
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            json.decodeFromString<List<BettercapEvent>>(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun downloadFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(path, "UTF-8")
            val request = Request.Builder()
                .url(url("/api/file?name=$encoded"))
                .get()
                .build()
            client.newCall(request).execute().use { it.body?.bytes() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getModules(): List<ModuleState> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/session/modules"))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "[]"
                json.decodeFromString<List<ModuleState>>(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

}
