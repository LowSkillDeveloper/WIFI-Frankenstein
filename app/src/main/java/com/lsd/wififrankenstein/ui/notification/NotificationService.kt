package com.lsd.wififrankenstein.ui.notification

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class NotificationService(context: Context) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkForNotifications(url: String): NotificationMessage? = withContext(Dispatchers.IO) {
        try {
            val lastSeenId = prefs.getString("last_notification_id", "")

            val jsonString = fetchJson(url)
            if (jsonString.isNullOrEmpty()) {
                return@withContext null
            }

            val notification = json.decodeFromString<NotificationMessage>(jsonString)

            if (notification.id != lastSeenId) {
                return@withContext notification
            }

            return@withContext null
        } catch (_: Exception) {
            return@withContext null
        }
    }

    fun markNotificationAsSeen(id: String) {
        prefs.edit { putString("last_notification_id", id) }
    }

    private suspend fun fetchJson(urlString: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext response
            } else {

                return@withContext null
            }
        } catch (_: Exception) {
            return@withContext null
        } finally {
            connection?.disconnect()
        }
    }

    fun getCurrentLanguageCode(): String {
        val locale = Locale.getDefault()
        return locale.language
    }
}