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
        bssid.replace(Regex("[^0-9a-fA-F]"), "").lowercase()

    fun essidToHex(essid: String): String {
        val bytes = essid.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { b ->
            val v = b.toInt() and 0xFF
            sb.append(hexChar(v ushr 4)).append(hexChar(v and 0x0F))
        }
        return sb.toString()
    }

    private fun hexChar(nibble: Int): Char = "0123456789abcdef"[nibble]

    suspend fun checkPasswordByBssidSsid(
        bssidHex: String,
        essidHex: String
    ): Boolean = withContext(Dispatchers.IO) {
        val input = bssidHex + essidHex
        val md = MessageDigest.getInstance("SHA1")
        val fullHash = md.digest(input.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val prefix = fullHash.substring(0, 4)

        val jsonBody = JSONArray().put(prefix).toString()
        val request = Request.Builder()
            .url("$BASE_URL/bmacssid")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false

            val body = response.body?.string() ?: return@withContext false
            val json = JSONObject(body)
            val suffixes = json.optJSONArray(prefix) ?: return@withContext false

            for (i in 0 until suffixes.length()) {
                if (fullHash.endsWith(suffixes.getString(i).lowercase())) {
                    return@withContext true
                }
            }
            false
        }
    }

    suspend fun checkPasswordsBatch(
        queries: List<Pair<String, String>>
    ): List<Boolean> = checkPasswordsBatchDetailed(queries).map { it == true }

    suspend fun checkPasswordsBatchDetailed(
        queries: List<Pair<String, String>>
    ): List<Boolean?> = withContext(Dispatchers.IO) {
        if (queries.isEmpty()) {
            return@withContext emptyList()
        }

        val md = MessageDigest.getInstance("SHA1")
        val hexChars = "0123456789abcdef".toCharArray()
        val fullHashes = queries.map { (bssidHex, essidHex) ->
            val digest = md.digest((bssidHex + essidHex).toByteArray(Charsets.US_ASCII))
            val sb = StringBuilder(digest.size * 2)
            digest.forEach { b ->
                val v = b.toInt() and 0xFF
                sb.append(hexChars[v ushr 4]).append(hexChars[v and 0x0F])
            }
            sb.toString()
        }
        val uniquePrefixes = fullHashes.map { it.substring(0, 4) }.distinct()
        val prefixChunks = uniquePrefixes.chunked(300)

        val matchedFullHashes = mutableSetOf<String>()
        val failedPrefixes = mutableSetOf<String>()

        for (chunk in prefixChunks) {
            val jsonBody = JSONArray(chunk).toString()
            val request = Request.Builder()
                .url("$BASE_URL/bmacssid")
                .post(jsonBody.toRequestBody(JSON_MEDIA))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        failedPrefixes.addAll(chunk)
                        return@use
                    }
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        failedPrefixes.addAll(chunk)
                        return@use
                    }
                    val json = JSONObject(body)

                    for (prefix in chunk) {
                        val suffixes = json.optJSONArray(prefix) ?: continue
                        for (i in 0 until suffixes.length()) {
                            val suffix = suffixes.getString(i).lowercase()
                            fullHashes.forEach { h ->
                                if (h.endsWith(suffix)) matchedFullHashes.add(h)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WpaSecClient", "checkPasswordsBatchDetailed chunk failed", e)
                failedPrefixes.addAll(chunk)
            }
        }

        fullHashes.map { hash ->
            when {
                hash in matchedFullHashes -> true
                failedPrefixes.contains(hash.substring(0, 4)) -> null
                else -> false
            }
        }
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

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext UploadResponse(false, "HTTP ${response.code}: $body", null)
            }

            val password = Regex("""OK\s*\((.+)\)""").find(body)?.groupValues?.get(1)
            UploadResponse(true, body, password)
        }
    }

    data class UploadResponse(
        val success: Boolean,
        val message: String,
        val password: String?
    )
}
