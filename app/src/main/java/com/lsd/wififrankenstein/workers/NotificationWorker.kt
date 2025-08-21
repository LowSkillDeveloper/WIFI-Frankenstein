package com.lsd.wififrankenstein.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lsd.wififrankenstein.network.NetworkClient
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.util.NotificationHelper
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class GeneralNotification(
    val id: String,
    val title: String,
    val message: String,
    val created_at: String,
    val expires_at: String? = null,
    val min_version: String? = null,
    val max_version: String? = null,
    val priority: String = "normal"
)

@Serializable
data class GeneralNotificationsResponse(
    val notifications: List<GeneralNotification>
)

class NotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationHelper = NotificationHelper(applicationContext)
    private val updateChecker = UpdateChecker(applicationContext)
    private val networkClient = NetworkClient.getInstance(applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = applicationContext.getSharedPreferences("notification_worker", Context.MODE_PRIVATE)

    companion object {
        private const val GENERAL_NOTIFICATIONS_URL = "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/notifications.json"
        private const val KEY_SHOWN_NOTIFICATIONS = "shown_notifications"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LAST_GENERAL_CHECK = "last_general_check"
        private const val UPDATE_CHECK_INTERVAL = 24 * 60 * 60 * 1000L
        private const val GENERAL_CHECK_INTERVAL = 48 * 60 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        return try {
            val currentTime = System.currentTimeMillis()

            val lastUpdateCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
            if (currentTime - lastUpdateCheck > UPDATE_CHECK_INTERVAL) {
                checkForUpdates()
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, currentTime).apply()
            }

            val lastGeneralCheck = prefs.getLong(KEY_LAST_GENERAL_CHECK, 0)
            if (currentTime - lastGeneralCheck > GENERAL_CHECK_INTERVAL) {
                checkGeneralNotifications()
                prefs.edit().putLong(KEY_LAST_GENERAL_CHECK, currentTime).apply()
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun checkForUpdates() {
        val updateStatus = updateChecker.checkForUpdates().first()

        updateStatus.appUpdate?.let { appUpdate ->
            val currentVersion = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)?.versionName ?: ""

            if (currentVersion != appUpdate.newVersion) {
                notificationHelper.showAppUpdateNotification(appUpdate.newVersion)
            }
        }

        val dbUpdatesCount = updateStatus.dbUpdates.count { it.needsUpdate }
        if (dbUpdatesCount > 0) {
            notificationHelper.showDatabaseUpdateNotification(dbUpdatesCount)
        }

        val componentUpdatesCount = updateStatus.fileUpdates.count { it.needsUpdate }
        if (componentUpdatesCount > 0) {
            notificationHelper.showComponentUpdateNotification(componentUpdatesCount)
        }
    }

    private suspend fun checkGeneralNotifications() {
        try {
            val response = networkClient.get(GENERAL_NOTIFICATIONS_URL)
            val notificationsResponse = json.decodeFromString<GeneralNotificationsResponse>(response)

            val shownNotifications = prefs.getStringSet(KEY_SHOWN_NOTIFICATIONS, emptySet()) ?: emptySet()
            val currentVersion = applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)?.versionName ?: ""

            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val currentTime = System.currentTimeMillis()

            val newShownNotifications = shownNotifications.toMutableSet()

            notificationsResponse.notifications.forEach { notification ->
                if (notification.id !in shownNotifications) {
                    val createdTime = try {
                        dateFormat.parse(notification.created_at)?.time ?: 0
                    } catch (e: Exception) {
                        0
                    }

                    val expiresTime = notification.expires_at?.let { expires ->
                        try {
                            dateFormat.parse(expires)?.time ?: Long.MAX_VALUE
                        } catch (e: Exception) {
                            Long.MAX_VALUE
                        }
                    } ?: Long.MAX_VALUE

                    val isVersionMatch = isVersionInRange(currentVersion, notification.min_version, notification.max_version)
                    val isNotExpired = currentTime < expiresTime
                    val isAfterCreated = currentTime > createdTime

                    if (isVersionMatch && isNotExpired && isAfterCreated) {
                        notificationHelper.showGeneralNotification(
                            notification.id,
                            notification.title,
                            notification.message
                        )
                        newShownNotifications.add(notification.id)
                    }
                }
            }

            prefs.edit().putStringSet(KEY_SHOWN_NOTIFICATIONS, newShownNotifications).apply()

        } catch (e: Exception) {
            android.util.Log.e("NotificationWorker", "Error checking general notifications", e)
        }
    }

    private fun isVersionInRange(currentVersion: String, minVersion: String?, maxVersion: String?): Boolean {
        if (minVersion == null && maxVersion == null) return true

        return try {
            val current = parseVersion(currentVersion)
            val min = minVersion?.let { parseVersion(it) }
            val max = maxVersion?.let { parseVersion(it) }

            val isAboveMin = min?.let { current >= it } ?: true
            val isBelowMax = max?.let { current <= it } ?: true

            isAboveMin && isBelowMax
        } catch (e: Exception) {
            true
        }
    }

    private fun parseVersion(version: String): Int {
        return try {
            val parts = version.split(".")
            var result = 0
            for (i in 0 until minOf(parts.size, 3)) {
                result = result * 1000 + (parts[i].toIntOrNull() ?: 0)
            }
            result
        } catch (e: Exception) {
            0
        }
    }
}