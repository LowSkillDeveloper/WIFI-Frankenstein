package com.lsd.wififrankenstein.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class WpaSecDictManager(private val context: Context) {

    companion object {
        private const val DICT_URL = "https://wpa-sec.stanev.org/dict/cracked.txt.gz"
        private const val PREFS_NAME = "wpasec_dict_prefs"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val TAG = "WpaSecDictManager"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dictFile: File
        get() = File(context.filesDir, "wpasec/cracked.txt")

    fun getDictPath(): String? =
        dictFile.takeIf { it.exists() }?.absolutePath

    fun getDictSizeMb(): String {
        val f = dictFile
        if (!f.exists()) return "0"
        val mb = f.length() / (1024L * 1024L)
        return if (mb > 0) "$mb MB" else "${f.length() / 1024} KB"
    }

    suspend fun downloadIfNeeded(): String? {
        try {
            val headRequest = Request.Builder().head().url(DICT_URL)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
                .build()
            val headResponse = client.newCall(headRequest).execute()
            val serverLastModified = headResponse.header("Last-Modified")
            val savedLastModified = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MODIFIED, null)

            if (dictFile.exists() && serverLastModified != null && serverLastModified == savedLastModified) {
                Log.d(TAG, "Dictionary is up-to-date (Last-Modified: $serverLastModified)")
                return dictFile.absolutePath
            }

            Log.d(
                TAG,
                "Downloading dictionary from $DICT_URL (server: $serverLastModified, saved: $savedLastModified)"
            )

            val getRequest = Request.Builder().url(DICT_URL)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1")
                .build()
            val response = client.newCall(getRequest).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                return null
            }

            val parent = dictFile.parentFile
            if (parent == null || (parent.mkdirs().not() && parent.exists().not())) {
                Log.e(TAG, "Cannot create directory: $parent")
                return null
            }
            response.body?.let { body ->
                GZIPInputStream(body.byteStream()).use { gzip ->
                    dictFile.outputStream().use { out ->
                        gzip.copyTo(out)
                    }
                }
            }

            if (serverLastModified != null) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_MODIFIED, serverLastModified).apply()
            }

            Log.d(TAG, "Dictionary saved: ${dictFile.absolutePath} (${dictFile.length()}B)")
            return dictFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "downloadIfNeeded failed", e)
            return null
        }
    }
}
