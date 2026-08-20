package com.lsd.wififrankenstein.network

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PwncrackClient(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://pwncrack.org"
        private const val KEY_PREF = "pwncrack_prefs"
        private const val KEY_PREF_NAME = "pwncrack_key"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    fun getSavedKey(): String? {
        val prefs = context.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREF_NAME, null)
    }

    fun saveKey(key: String) {
        context.getSharedPreferences(KEY_PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREF_NAME, key).apply()
    }

    suspend fun uploadHandshake(
        hash22000: String,
        key: String
    ): UploadResponse = withContext(Dispatchers.IO) {
        try {
            val tempFile = File.createTempFile("handshake_", ".hc22000", context.cacheDir)
            try {
                tempFile.writeText(hash22000)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "handshake",
                        tempFile.name,
                        tempFile.readBytes().toRequestBody("application/octet-stream".toMediaType())
                    )
                    .addFormDataPart("key", key)
                    .build()

                val request = Request.Builder()
                    .url("$BASE_URL/upload_handshake")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext UploadResponse(false, "HTTP ${response.code}: $body")
                }

                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    null
                }

                val success = json?.optBoolean("success") ?: body.contains("success", true)
                UploadResponse(success, body)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e("PwncrackClient", "uploadHandshake failed", e)
            UploadResponse(false, e.message ?: "Unknown error")
        }
    }

    suspend fun checkResults(
        key: String
    ): List<PwncrackResult> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/results?key=$key")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"

            if (!response.isSuccessful) {
                Log.w("PwncrackClient", "checkResults: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val jsonArray = try {
                JSONArray(body)
            } catch (_: Exception) {
                return@withContext emptyList()
            }

            val results = mutableListOf<PwncrackResult>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val ssid = obj.optString("SSID", "")
                val password = obj.optString("password", "")
                val bssid = obj.optString("BSSID", "")
                if (ssid.isNotEmpty() && password.isNotEmpty()) {
                    results.add(PwncrackResult(ssid, password, bssid))
                }
            }
            results
        } catch (e: Exception) {
            Log.e("PwncrackClient", "checkResults failed", e)
            emptyList()
        }
    }

    data class UploadResponse(
        val success: Boolean,
        val message: String
    )

    data class PwncrackResult(
        val ssid: String,
        val password: String,
        val bssid: String
    )
}
