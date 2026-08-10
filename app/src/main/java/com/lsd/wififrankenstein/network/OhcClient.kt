package com.lsd.wififrankenstein.network

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OhcUploadResult(
    val success: Boolean,
    val acceptedCount: Int = 0,
    val skippedCount: Int = 0,
    val rejectedCount: Int = 0,
    val reason: String? = null,
    val message: String? = null,
    val requestId: String? = null,
    val acceptedHashes: List<String> = emptyList()
)

data class OhcTask(
    val hash: String,
    val algorithm: String,
    val status: String,
    val createdAt: String,
    val lastAttack: String?
)

class OhcClient(private val context: Context) {

    companion object {
        private const val TAG = "OhcClient"
        private const val PUBLIC_API_URL = "https://api.onlinehashcrack.com"
        private const val PRIVATE_API_URL = "https://api.onlinehashcrack.com/v2"
        private const val PREFS_NAME = "ohc_prefs"
        private const val KEY_API_KEY = "ohc_api_key"
        private const val KEY_EMAIL = "ohc_email"
    }

    private fun getPrefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedEmail(): String? = getPrefs().getString(KEY_EMAIL, null)
    fun saveEmail(email: String) = getPrefs().edit().putString(KEY_EMAIL, email).apply()

    fun getSavedApiKey(): String? = getPrefs().getString(KEY_API_KEY, null)
    fun saveApiKey(key: String) = getPrefs().edit().putString(KEY_API_KEY, key).apply()

    suspend fun uploadPublic(capFile: File, email: String): OhcUploadResult =
        withContext(Dispatchers.IO) {
            try {
                val result = NetworkClient.getInstance(context).postMultipart(
                    url = PUBLIC_API_URL,
                    parts = listOf("email" to email, "file" to capFile as Any)
                )
                val json = JSONObject(result)
                val accepted = json.optJSONObject("accepted")
                val skipped = json.optJSONObject("skipped")
                val rejected = json.optJSONObject("rejected")

                val acceptedHashes = accepted?.optJSONArray("hashes")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it, "") } }
                    ?: emptyList()

                OhcUploadResult(
                    success = accepted?.optInt("count", 0) ?: 0 > 0,
                    acceptedCount = accepted?.optInt("count", 0) ?: 0,
                    skippedCount = skipped?.optInt("count", 0) ?: 0,
                    rejectedCount = rejected?.optInt("count", 0) ?: 0,
                    reason = rejected?.optString("reason") ?: skipped?.optString("reason"),
                    message = rejected?.optString("message"),
                    acceptedHashes = acceptedHashes
                )
            } catch (e: Exception) {
                Log.e(TAG, "OHC public upload failed", e)
                OhcUploadResult(success = false, message = e.message)
            }
        }

    suspend fun uploadPrivate(
        hashes: List<String>,
        apiKey: String,
        algoMode: Int = 22000
    ): OhcUploadResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("api_key", apiKey)
                put("agree_terms", "yes")
                put("action", "add_tasks")
                put("algo_mode", algoMode)
                put("hashes", JSONArray(hashes))
            }
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val mediaType = "application/json".toMediaTypeOrNull()
            val body = okhttp3.RequestBody.create(mediaType, payload.toString())
            val request = okhttp3.Request.Builder()
                .url(PRIVATE_API_URL)
                .post(body)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"
            val json = JSONObject(bodyStr)
            val requestId = json.optString("request_id", null)

            if (!response.isSuccessful) {
                val errorCode = json.optString("error_code", "unknown")
                val message = json.optString("message", "HTTP ${response.code}")
                return@withContext OhcUploadResult(
                    success = false, message = message, reason = errorCode, requestId = requestId
                )
            }

            val accepted = json.optJSONObject("accepted")
            val skipped = json.optJSONObject("skipped")
            val rejected = json.optJSONObject("rejected")

            OhcUploadResult(
                success = accepted?.optInt("count", 0) ?: 0 > 0,
                acceptedCount = accepted?.optInt("count", 0) ?: 0,
                skippedCount = skipped?.optInt("count", 0) ?: 0,
                rejectedCount = rejected?.optInt("count", 0) ?: 0,
                reason = rejected?.optString("reason") ?: skipped?.optString("reason"),
                requestId = requestId,
                message = json.optString("message")
            )
        } catch (e: Exception) {
            Log.e(TAG, "OHC private upload failed", e)
            OhcUploadResult(success = false, message = e.message)
        }
    }

    suspend fun listTasks(apiKey: String): List<OhcTask> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("api_key", apiKey)
                put("agree_terms", "yes")
                put("action", "list_tasks")
            }
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val mediaType = "application/json".toMediaTypeOrNull()
            val body = okhttp3.RequestBody.create(mediaType, payload.toString())
            val request = okhttp3.Request.Builder()
                .url(PRIVATE_API_URL)
                .post(body)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
                .build()
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"
            val json = JSONObject(bodyStr)
            val tasks = json.optJSONArray("tasks") ?: JSONArray()
            (0 until tasks.length()).mapNotNull { i ->
                val t = tasks.optJSONObject(i) ?: return@mapNotNull null
                OhcTask(
                    hash = t.optString("hash", ""),
                    algorithm = t.optString("algorithm", ""),
                    status = t.optString("status", ""),
                    createdAt = t.optString("created_at", ""),
                    lastAttack = t.optString("lastAttack", null)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OHC list tasks failed", e)
            emptyList()
        }
    }
}
