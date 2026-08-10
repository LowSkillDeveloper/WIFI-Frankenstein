package com.lsd.wififrankenstein.network

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class WpaSecClient(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://wpa-sec.stanev.org"
        private const val KEY_PREF = "wpasec_prefs"
        private const val KEY_PREF_NAME = "wpasec_key"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    fun bssidToHex(bssid: String): String =
        bssid.replace(":", "").lowercase()

    fun essidToHex(essid: String): String =
        essid.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02x".format(it) }

    suspend fun checkPasswordByBssidSsid(
        bssidHex: String,
        essidHex: String
    ): Boolean = withContext(Dispatchers.IO) {
        val input = bssidHex + essidHex
        val md = MessageDigest.getInstance("SHA1")
        val fullHash = md.digest(input.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        val prefix = fullHash.substring(0, 4)

        val jsonBody = JSONArray().put(prefix).toString()
        val request = Request.Builder()
            .url("$BASE_URL/bmacssid")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext false

        val body = response.body?.string() ?: return@withContext false
        val json = JSONObject(body)
        val suffixes = json.optJSONArray(prefix) ?: return@withContext false

        for (i in 0 until suffixes.length()) {
            if (fullHash.endsWith(suffixes.getString(i))) {
                return@withContext true
            }
        }
        false
    }

    suspend fun checkPasswordsBatch(
        queries: List<Pair<String, String>>
    ): List<Boolean> = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) {
            Log.d("WpaSecClient", "checkPasswordsBatch: empty query list")
            return@withContext emptyList()
        }

        Log.d("WpaSecClient", "checkPasswordsBatch: preparing ${queries.size} queries")
        val md = MessageDigest.getInstance("SHA1")
        val fullHashes = queries.map { (bssidHex, essidHex) ->
            md.digest((bssidHex + essidHex).toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
        }
        val uniquePrefixes = fullHashes.map { it.substring(0, 4) }.distinct()
        Log.d(
            "WpaSecClient",
            "checkPasswordsBatch: ${uniquePrefixes.size} unique prefixes from ${queries.size} queries"
        )
        val prefixChunks = uniquePrefixes.chunked(300)
        Log.d(
            "WpaSecClient",
            "checkPasswordsBatch: split into ${prefixChunks.size} chunk(s) (max 300 per request)"
        )

        val matchedFullHashes = mutableSetOf<String>()

        for ((chunkIndex, chunk) in prefixChunks.withIndex()) {
            val jsonBody = JSONArray(chunk).toString()
            val request = Request.Builder()
                .url("$BASE_URL/bmacssid")
                .post(jsonBody.toRequestBody(JSON_MEDIA))
                .build()
            try {
                Log.d(
                    "WpaSecClient",
                    "checkPasswordsBatch: sending request ${chunkIndex + 1}/${prefixChunks.size} with ${chunk.size} prefixes"
                )
                val response = client.newCall(request).execute()
                Log.d(
                    "WpaSecClient",
                    "checkPasswordsBatch: response ${chunkIndex + 1} — HTTP ${response.code}"
                )
                if (!response.isSuccessful) {
                    Log.w(
                        "WpaSecClient",
                        "checkPasswordsBatch: request ${chunkIndex + 1} failed with HTTP ${response.code}"
                    )
                    continue
                }
                val body = response.body?.string() ?: run {
                    Log.w(
                        "WpaSecClient",
                        "checkPasswordsBatch: request ${chunkIndex + 1} — empty body"
                    )
                    continue
                }
                val json = JSONObject(body)
                var matchedInChunk = 0
                for (prefix in chunk) {
                    val suffixes = json.optJSONArray(prefix) ?: continue
                    for (i in 0 until suffixes.length()) {
                        val suffix = suffixes.getString(i)
                        val matching = fullHashes.filter { it.endsWith(suffix) }
                        matchedFullHashes.addAll(matching)
                        matchedInChunk += matching.size
                    }
                }
                Log.d(
                    "WpaSecClient",
                    "checkPasswordsBatch: request ${chunkIndex + 1} — $matchedInChunk matches found"
                )
            } catch (e: Exception) {
                Log.e("WpaSecClient", "checkPasswordsBatch: request ${chunkIndex + 1} failed", e)
            }
        }
        val foundCount = matchedFullHashes.size
        Log.d(
            "WpaSecClient",
            "checkPasswordsBatch: done — $foundCount/${queries.size} networks found on wpa-sec"
        )
        fullHashes.map { it in matchedFullHashes }
    }

    suspend fun uploadHash(
        hash22000: String,
        key: String?
    ): UploadResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("$BASE_URL/?submit")
            .post(hash22000.toRequestBody("text/plain".toMediaType()))

        if (!key.isNullOrBlank()) {
            requestBuilder.header("Cookie", "key=$key")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            return@withContext UploadResponse(false, "HTTP ${response.code}: $body", null)
        }


        val password = Regex("""OK\s*\((.+)\)""").find(body)?.groupValues?.get(1)
        UploadResponse(true, body, password)
    }

    data class UploadResponse(
        val success: Boolean,
        val message: String,
        val password: String?
    )
}
