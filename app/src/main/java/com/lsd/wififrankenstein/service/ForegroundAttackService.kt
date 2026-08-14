package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NativePskBruteForceRunner
import com.lsd.wififrankenstein.util.PskBruteForceRunner
import com.lsd.wififrankenstein.util.WpsBruteForceRunner
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class AttackType {
    WPS_BRUTE,
    CUSTOM_PIN,
    PSK_BRUTE,
    NATIVE_PSK_BRUTE
}

class ForegroundAttackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var attackJob: Job? = null

    companion object {
        private const val TAG = "ForegroundAttackService"
        private const val CHANNEL_ID = "attack_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START_ATTACK = "start_attack"
        const val ACTION_CANCEL_ATTACK = "cancel_attack"
        const val ACTION_STOP_SERVICE = "stop_service"

        const val EXTRA_ATTACK_TYPE = "attack_type"
        const val EXTRA_BSSID = "bssid"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_INTERFACE = "interface"
        const val EXTRA_PIN = "pin"
        const val EXTRA_WORDLIST_URI = "wordlist_uri"

        const val BROADCAST_ATTACK_PROGRESS = "attack_progress"
        const val BROADCAST_ATTACK_COMPLETE = "attack_complete"
        const val BROADCAST_ATTACK_ERROR = "attack_error"

        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_RESULT_PIN = "result_pin"
        const val EXTRA_RESULT_PSK = "result_psk"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        fun startWpsBruteForce(context: Context, bssid: String, iface: String = "wlan0") {
            val intent = Intent(context, ForegroundAttackService::class.java).apply {
                action = ACTION_START_ATTACK
                putExtra(EXTRA_ATTACK_TYPE, AttackType.WPS_BRUTE.name)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_INTERFACE, iface)
            }
            context.startService(intent)
        }

        fun startCustomPin(context: Context, bssid: String, pin: String, iface: String = "wlan0") {
            val intent = Intent(context, ForegroundAttackService::class.java).apply {
                action = ACTION_START_ATTACK
                putExtra(EXTRA_ATTACK_TYPE, AttackType.CUSTOM_PIN.name)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_PIN, pin)
                putExtra(EXTRA_INTERFACE, iface)
            }
            context.startService(intent)
        }

        fun startPskBruteForce(context: Context, ssid: String, bssid: String, wordlistUri: String) {
            val intent = Intent(context, ForegroundAttackService::class.java).apply {
                action = ACTION_START_ATTACK
                putExtra(EXTRA_ATTACK_TYPE, AttackType.PSK_BRUTE.name)
                putExtra(EXTRA_SSID, ssid)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_WORDLIST_URI, wordlistUri)
            }
            context.startService(intent)
        }

        fun startNativePskBruteForce(
            context: Context,
            ssid: String,
            bssid: String,
            wordlistUri: String
        ) {
            val intent = Intent(context, ForegroundAttackService::class.java).apply {
                action = ACTION_START_ATTACK
                putExtra(EXTRA_ATTACK_TYPE, AttackType.NATIVE_PSK_BRUTE.name)
                putExtra(EXTRA_SSID, ssid)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_WORDLIST_URI, wordlistUri)
            }
            context.startService(intent)
        }

        fun cancelAttack(context: Context) {
            val intent = Intent(context, ForegroundAttackService::class.java).apply {
                action = ACTION_CANCEL_ATTACK
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ATTACK -> handleStartAttack(intent)
            ACTION_CANCEL_ATTACK -> handleCancelAttack()
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStartAttack(intent: Intent) {
        val attackTypeStr = intent.getStringExtra(EXTRA_ATTACK_TYPE)
            ?: return
        val attackType = try {
            AttackType.valueOf(attackTypeStr)
        } catch (e: Exception) {
            return
        }

        val notification = buildNotification(getAttackTypeLabel(attackType), getString(R.string.svc_starting)).build()
        startForeground(NOTIFICATION_ID, notification)

        attackJob = serviceScope.launch {
            try {
                when (attackType) {
                    AttackType.WPS_BRUTE -> runWpsBrute(intent)
                    AttackType.CUSTOM_PIN -> runCustomPin(intent)
                    AttackType.PSK_BRUTE -> runPskBrute(intent)
                    AttackType.NATIVE_PSK_BRUTE -> runNativePskBrute(intent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Attack failed", e)
                broadcastError(e.message ?: getString(R.string.svc_unknown_error))
                updateNotification(getAttackTypeLabel(attackType), getString(R.string.svc_failed, e.message))
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun runWpsBrute(intent: Intent) {
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: return
        val iface = intent.getStringExtra(EXTRA_INTERFACE) ?: "wlan0"
        val runner = WpsBruteForceRunner(this)

        val result = runner.runBruteForce(bssid, iface, onProgress = { progress ->
            val text = if (progress.percentComplete != null) {
                getString(R.string.svc_pin_percent, progress.percentComplete, progress.currentPin ?: "...")
            } else {
                progress.line
            }
            updateNotification(getString(R.string.foreground_attack_wps_brute), text)
            broadcastProgress(text)
        })

        val broadcastIntent = Intent(BROADCAST_ATTACK_COMPLETE).apply {
            putExtra(EXTRA_RESULT_PIN, result.wpsPin)
            putExtra(EXTRA_RESULT_PSK, result.wpaPsk)
            putExtra(EXTRA_PROGRESS_TEXT, if (result.success) getString(R.string.svc_pin, result.wpsPin) else getString(R.string.svc_failed_short))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

        updateNotification(
            getString(R.string.foreground_attack_wps_brute),
            if (result.success) getString(R.string.svc_pin, result.wpsPin) else getString(R.string.svc_not_found)
        )
    }

    private suspend fun runCustomPin(intent: Intent) {
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: return
        val pin = intent.getStringExtra(EXTRA_PIN) ?: return
        val iface = intent.getStringExtra(EXTRA_INTERFACE) ?: "wlan0"
        val runner = WpsBruteForceRunner(this)

        val result = runner.runCustomPin(bssid, pin, iface, onProgress = { progress ->
            updateNotification(getString(R.string.foreground_attack_custom_pin), progress.line)
            broadcastProgress(progress.line)
        })

        val broadcastIntent = Intent(BROADCAST_ATTACK_COMPLETE).apply {
            putExtra(EXTRA_RESULT_PIN, result.wpsPin)
            putExtra(EXTRA_RESULT_PSK, result.wpaPsk)
            putExtra(
                EXTRA_PROGRESS_TEXT,
                if (result.success) getString(R.string.svc_pin, result.wpsPin) else getString(R.string.svc_not_found)
            )
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
    }

    private suspend fun runPskBrute(intent: Intent) {
        val ssid = intent.getStringExtra(EXTRA_SSID) ?: return
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: return
        val wordlistUriStr = intent.getStringExtra(EXTRA_WORDLIST_URI) ?: return
        val wordlistUri = android.net.Uri.parse(wordlistUriStr)
        val runner = PskBruteForceRunner(this)

        val result = runner.runAttack(ssid, bssid, wordlistUri, onProgress = { progress ->
            val text =
                getString(R.string.svc_attempt, progress.attemptNumber, progress.totalPasswords, progress.currentPassword)
            updateNotification(getString(R.string.foreground_attack_psk_brute), text)
            broadcastProgress(text)
        })

        val broadcastIntent = Intent(BROADCAST_ATTACK_COMPLETE).apply {
            putExtra(EXTRA_RESULT_PSK, result.foundPassword)
            putExtra(
                EXTRA_PROGRESS_TEXT,
                if (result.success) getString(R.string.svc_psk, result.foundPassword) else getString(R.string.svc_not_found_attempts, result.attemptsMade)
            )
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)
    }

    private suspend fun runNativePskBrute(intent: Intent) {
        val ssid = intent.getStringExtra(EXTRA_SSID) ?: return
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: return
        val wordlistUriStr = intent.getStringExtra(EXTRA_WORDLIST_URI) ?: return
        val wordlistUri = android.net.Uri.parse(wordlistUriStr)
        val runner = NativePskBruteForceRunner(this)

        val result = runner.runAttack(ssid, bssid, wordlistUri, onProgress = { progress ->
            val text =
                getString(R.string.svc_attempt, progress.attemptNumber, progress.totalPasswords, progress.currentPassword)
            updateNotification(getString(R.string.foreground_attack_psk_brute), text)
            broadcastProgress(text)
        })

        val broadcastIntent = Intent(BROADCAST_ATTACK_COMPLETE).apply {
            putExtra(EXTRA_RESULT_PSK, result.foundPassword)
            putExtra(
                EXTRA_PROGRESS_TEXT,
                if (result.success) getString(R.string.svc_psk, result.foundPassword) else getString(R.string.svc_not_found_attempts, result.attemptsMade)
            )
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent)

        updateNotification(
            getString(R.string.foreground_attack_psk_brute),
            if (result.success) getString(R.string.svc_psk, result.foundPassword) else getString(R.string.svc_not_found_attempts, result.attemptsMade)
        )
    }

    private fun handleCancelAttack() {
        attackJob?.cancel()
        attackJob = null
        notificationManager.cancel(NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_attack_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content).build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastProgress(text: String) {
        val intent = Intent(BROADCAST_ATTACK_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_TEXT, text)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(BROADCAST_ATTACK_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getAttackTypeLabel(type: AttackType): String {
        return getString(
            when (type) {
                AttackType.WPS_BRUTE -> R.string.foreground_attack_wps_brute
                AttackType.CUSTOM_PIN -> R.string.foreground_attack_custom_pin
                AttackType.PSK_BRUTE -> R.string.foreground_attack_psk_brute
                AttackType.NATIVE_PSK_BRUTE -> R.string.foreground_attack_psk_brute
            }
        )
    }
}
