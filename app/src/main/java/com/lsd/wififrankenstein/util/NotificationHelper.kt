package com.lsd.wififrankenstein.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R

class NotificationHelper(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    companion object {
        const val CHANNEL_APP_UPDATES = "app_updates"
        const val CHANNEL_DB_UPDATES = "db_updates"
        const val CHANNEL_COMPONENT_UPDATES = "component_updates"
        const val CHANNEL_RECOMMENDED_DB = "recommended_db"
        const val CHANNEL_GENERAL = "general"

        const val NOTIFICATION_APP_UPDATE = 1001
        const val NOTIFICATION_DB_UPDATE = 1002
        const val NOTIFICATION_COMPONENT_UPDATE = 1003
        const val NOTIFICATION_RECOMMENDED_DB = 1004
        const val NOTIFICATION_GENERAL_BASE = 2000
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_APP_UPDATES,
                    context.getString(R.string.notification_channel_app_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_app_updates_desc)
                },
                NotificationChannel(
                    CHANNEL_DB_UPDATES,
                    context.getString(R.string.notification_channel_db_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_db_updates_desc)
                },
                NotificationChannel(
                    CHANNEL_COMPONENT_UPDATES,
                    context.getString(R.string.notification_channel_component_updates),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_component_updates_desc)
                },
                NotificationChannel(
                    CHANNEL_RECOMMENDED_DB,
                    context.getString(R.string.notification_channel_recommended_db),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_recommended_db_desc)
                },
                NotificationChannel(
                    CHANNEL_GENERAL,
                    context.getString(R.string.notification_channel_general),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notification_channel_general_desc)
                }
            )

            val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            channels.forEach { systemNotificationManager.createNotificationChannel(it) }
        }
    }

    fun showAppUpdateNotification(newVersion: String) {
        if (!areNotificationsEnabled() || !isAppUpdateNotificationEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_updates", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATES)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(context.getString(R.string.notification_app_update_title))
            .setContentText(context.getString(R.string.notification_app_update_text, newVersion))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_APP_UPDATE, notification)
    }

    fun showDatabaseUpdateNotification(count: Int) {
        if (!areNotificationsEnabled() || !isDatabaseUpdateNotificationEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_updates", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DB_UPDATES)
            .setSmallIcon(R.drawable.ic_database)
            .setContentTitle(context.getString(R.string.notification_db_update_title))
            .setContentText(context.resources.getQuantityString(R.plurals.notification_db_update_text, count, count))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_DB_UPDATE, notification)
    }

    fun showComponentUpdateNotification(count: Int) {
        if (!areNotificationsEnabled() || !isComponentUpdateNotificationEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_updates", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPONENT_UPDATES)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(context.getString(R.string.notification_component_update_title))
            .setContentText(context.resources.getQuantityString(R.plurals.notification_component_update_text, count, count))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_COMPONENT_UPDATE, notification)
    }

    fun showRecommendedDatabaseNotification(count: Int) {
        if (!areNotificationsEnabled() || !isRecommendedDatabaseNotificationEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_db_setup", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_RECOMMENDED_DB)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(context.getString(R.string.notification_recommended_db_title))
            .setContentText(context.resources.getQuantityString(R.plurals.notification_recommended_db_text, count, count))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_RECOMMENDED_DB, notification)
    }

    fun showGeneralNotification(id: String, title: String, message: String) {
        if (!areNotificationsEnabled() || !isGeneralNotificationEnabled()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notificationId = NOTIFICATION_GENERAL_BASE + id.hashCode()

        val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun areNotificationsEnabled(): Boolean {
        return notificationManager.areNotificationsEnabled()
    }

    private fun isAppUpdateNotificationEnabled(): Boolean {
        return context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .getBoolean("app_updates", true)
    }

    private fun isDatabaseUpdateNotificationEnabled(): Boolean {
        return context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .getBoolean("database_updates", true)
    }

    private fun isComponentUpdateNotificationEnabled(): Boolean {
        return context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .getBoolean("component_updates", false)
    }

    private fun isRecommendedDatabaseNotificationEnabled(): Boolean {
        return context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .getBoolean("recommended_databases", true)
    }

    private fun isGeneralNotificationEnabled(): Boolean {
        return context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .getBoolean("general_notifications", true)
    }
}
