package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.wpacracker.CrackSessionData
import com.lsd.wififrankenstein.ui.wpacracker.CrackSessionManager
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.OfflineProgress
import com.lsd.wififrankenstein.util.OfflineResult
import com.lsd.wififrankenstein.util.PskOfflineBruteForceRunner
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WpaCrackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var crackJob: Job? = null
    private var runner: PskOfflineBruteForceRunner? = null
    private var sessionHandshakeLine: String = ""
    private var sessionWordlistUri: String = ""
    private var sessionTotalLines: Long = 0

    @Volatile
    private var lastKnownOffset: Long = 0
    private var chrootCrackJob: Job? = null
    private lateinit var sessionManager: CrackSessionManager

    companion object {
        private const val TAG = "WpaCrackService"
        private const val CHANNEL_ID = "wpa_crack_channel"
        private const val NOTIFICATION_ID = 4002
        private const val CHROOT_CHANNEL_ID = "wpa_crack_chroot_channel"
        private const val CHROOT_NOTIFICATION_ID = 4003

        const val ACTION_START_CRACK = "start_crack"
        const val ACTION_PAUSE_CRACK = "pause_crack"
        const val ACTION_RESUME_CRACK = "resume_crack"
        const val ACTION_STOP_CRACK = "stop_crack"
        const val ACTION_UPDATE_NOTIFICATION = "update_notification"
        const val ACTION_START_CHROOT_CRACK = "start_chroot_crack"
        const val ACTION_STOP_CHROOT_CRACK = "stop_chroot_crack"

        const val EXTRA_HANDSHAKE_LINE = "handshake_line"
        const val EXTRA_WORDLIST_URI = "wordlist_uri"
        const val EXTRA_OFFSET = "offset"
        const val EXTRA_TOTAL_LINES = "total_lines"
        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_IS_PAUSED = "is_paused"
        const val EXTRA_CURRENT_PASSWORD = "current_password"
        const val EXTRA_ATTEMPTS = "attempts"
        const val EXTRA_SPEED = "speed"

        const val EXTRA_RESULT_PSK = "result_psk"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        const val BROADCAST_CRACK_PROGRESS = "wpa_crack_progress"
        const val BROADCAST_CRACK_FOUND = "wpa_crack_found"
        const val BROADCAST_CRACK_ERROR = "wpa_crack_error"
        const val BROADCAST_CRACK_PAUSED = "wpa_crack_paused"
        const val BROADCAST_CRACK_RESUMED = "wpa_crack_resumed"
        const val BROADCAST_CRACK_STOPPED = "wpa_crack_stopped"
        const val BROADCAST_CHROOT_LINE = "wpa_crack_chroot_line"

        fun startChrootCrack(context: Context, handshakeLine: String, wordlistUri: String) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_START_CHROOT_CRACK
                putExtra(EXTRA_HANDSHAKE_LINE, handshakeLine)
                putExtra(EXTRA_WORDLIST_URI, wordlistUri)
            }
            context.startService(intent)
        }

        fun stopChrootCrack(context: Context) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_STOP_CHROOT_CRACK
            }
            context.startService(intent)
        }

        fun startCrack(
            context: Context,
            handshakeLine: String,
            wordlistUri: String,
            offset: Long = 0,
            totalLines: Long = 0
        ) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_START_CRACK
                putExtra(EXTRA_HANDSHAKE_LINE, handshakeLine)
                putExtra(EXTRA_WORDLIST_URI, wordlistUri)
                putExtra(EXTRA_OFFSET, offset)
                putExtra(EXTRA_TOTAL_LINES, totalLines)
            }
            context.startService(intent)
        }

        fun pauseCrack(context: Context) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_PAUSE_CRACK
            }
            context.startService(intent)
        }

        fun resumeCrack(context: Context) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_RESUME_CRACK
            }
            context.startService(intent)
        }

        fun stopCrack(context: Context) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_STOP_CRACK
            }
            context.startService(intent)
        }

        fun updateNotification(context: Context, progress: OfflineProgress, isPaused: Boolean) {
            val intent = Intent(context, WpaCrackService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                putExtra(EXTRA_PROGRESS_TEXT, progress.currentPassword)
                putExtra(
                    EXTRA_PROGRESS_PERCENT,
                    if (progress.totalPasswords > 0) (progress.attempts.toDouble() / progress.totalPasswords * 100.0).toInt() else -1
                )
                putExtra(EXTRA_IS_PAUSED, isPaused)
                putExtra(EXTRA_CURRENT_PASSWORD, progress.currentPassword)
                putExtra(EXTRA_ATTEMPTS, progress.attempts)
                putExtra(EXTRA_SPEED, progress.speed)
                putExtra(EXTRA_OFFSET, progress.offset)
                putExtra(EXTRA_TOTAL_LINES, progress.totalPasswords)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createChrootNotificationChannel()
        sessionManager = CrackSessionManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CRACK -> handleStartCrack(intent)
            ACTION_PAUSE_CRACK -> handlePauseCrack()
            ACTION_RESUME_CRACK -> handleResumeCrack()
            ACTION_STOP_CRACK -> handleStopCrack()
            ACTION_UPDATE_NOTIFICATION -> handleUpdateNotification(intent)
            ACTION_START_CHROOT_CRACK -> handleStartChrootCrack(intent)
            ACTION_STOP_CHROOT_CRACK -> handleStopChrootCrack()
        }
        return START_NOT_STICKY
    }

    private fun handleStartCrack(intent: Intent) {
        val handshakeLine = intent.getStringExtra(EXTRA_HANDSHAKE_LINE) ?: return
        val wordlistUriStr = intent.getStringExtra(EXTRA_WORDLIST_URI) ?: return
        val offset = intent.getLongExtra(EXTRA_OFFSET, 0)
        val totalLines = intent.getLongExtra(EXTRA_TOTAL_LINES, 0)

        sessionHandshakeLine = handshakeLine
        sessionWordlistUri = wordlistUriStr
        sessionTotalLines = totalLines

        val hash = HandshakeHash.parseAny(handshakeLine) ?: run {
            Log.e(TAG, "Failed to parse handshake line")
            return
        }

        val wordlistUri = android.net.Uri.parse(wordlistUriStr)

        val notification = buildNotification(
            getString(R.string.wpa_crack_notif_title),
            if (offset > 0) getString(R.string.svc_resuming_crack, offset) else getString(R.string.svc_starting),
            isPaused = false,
            currentPassword = "",
            attempts = 0,
            speed = 0.0,
            offset = offset,
            totalLines = totalLines
        )
        startForeground(NOTIFICATION_ID, notification.build())

        crackJob = serviceScope.launch {
            try {
                runner = PskOfflineBruteForceRunner(this@WpaCrackService)
                val result = runner!!.crackFromWordlist(
                    handshakeHash = hash,
                    wordlistUri = wordlistUri,
                    startOffset = offset,
                    onProgress = { progress ->
                        lastKnownOffset = progress.offset
                        updateNotif(progress, isPaused = false)
                        broadcastProgress(progress)
                    }
                )
                handleResult(result, hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Crack failed", e)
                broadcastError(e.message ?: getString(R.string.svc_unknown_error))
                updateNotificationSimple(
                    getString(R.string.wpa_crack_notif_title),
                    getString(R.string.svc_failed, e.message)
                )
            } finally {
                if (sessionHandshakeLine.isNotBlank() && sessionWordlistUri.isNotBlank()) {
                    sessionManager.removeSession(sessionHandshakeLine, sessionWordlistUri)
                }
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun handleResult(result: OfflineResult, hash: HandshakeHash) {
        if (result.foundPassword != null) {
            val text = getString(R.string.svc_found, result.foundPassword)
            updateNotificationSimple(getString(R.string.wpa_crack_notif_title), text)
            broadcastFound(result.foundPassword, hash)
        } else if (result.cancelled) {
            broadcastStopped()
        } else {
            broadcastError(getString(R.string.svc_password_not_found))
        }
    }

    private fun handlePauseCrack() {
        runner?.pause()
        if (sessionHandshakeLine.isNotBlank() && sessionWordlistUri.isNotBlank()) {
            sessionManager.saveSession(
                CrackSessionData(
                    wordlistUri = sessionWordlistUri,
                    handshakeLine = sessionHandshakeLine,
                    offset = lastKnownOffset,
                    totalLines = sessionTotalLines,
                    engineName = "NATIVE",
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        broadcastPaused()
    }

    private fun handleResumeCrack() {
        runner?.resume()
        broadcastResumed()
    }

    private fun handleStopCrack() {
        runner?.cancel()
        runner = null
        crackJob?.cancel()
        crackJob = null
        if (sessionHandshakeLine.isNotBlank() && sessionWordlistUri.isNotBlank()) {
            sessionManager.removeSession(sessionHandshakeLine, sessionWordlistUri)
        }
        broadcastStopped()
        notificationManager.cancel(NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    private fun handleStartChrootCrack(intent: Intent) {
        if (crackJob?.isActive == true || chrootCrackJob?.isActive == true) {
            broadcastError(getString(R.string.svc_crack_running))
            return
        }
        val handshakeLine = intent.getStringExtra(EXTRA_HANDSHAKE_LINE) ?: return
        val wordlistUriStr = intent.getStringExtra(EXTRA_WORDLIST_URI) ?: return

        val notification = NotificationCompat.Builder(this, CHROOT_CHANNEL_ID)
            .setContentTitle(getString(R.string.wpa_crack_notif_title))
            .setContentText(getString(R.string.svc_starting_chroot_crack))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .build()
        startForeground(CHROOT_NOTIFICATION_ID, notification)

        chrootCrackJob = serviceScope.launch {
            try {
                runChrootCrack(handshakeLine, wordlistUriStr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chroot crack failed", e)
                broadcastError(e.message ?: getString(R.string.svc_chroot_crack_error))
            } finally {
                chrootCrackJob = null
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun handleStopChrootCrack() {
        chrootCrackJob?.cancel()
        chrootCrackJob = null
        try {
            val cm = ChrootManager.get(this)
            cm.executeInChroot("killall -9 aircrack-ng 2>/dev/null || true")
        } catch (_: Exception) {
        }
        broadcastStopped()
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun runChrootCrack(handshakeLine: String, wordlistUriStr: String) {
        val cm = ChrootManager.get(this)
        cm.mountChroot()

        val wordlistUri = android.net.Uri.parse(wordlistUriStr)
        val wPath = copyWordlistToChroot(wordlistUri)
        if (wPath == null) {
            broadcastError(getString(R.string.svc_copy_wordlist_failed))
            return
        }
        val capPath = generateCapForChroot(handshakeLine, cm)
        if (capPath == null) {
            broadcastError(getString(R.string.svc_generate_cap_failed))
            return
        }

        updateChrootNotification(getString(R.string.svc_cracking))

        val found = crackWithWordlistStreaming(capPath, wPath, cm) { line ->
            val clean = line
                .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
                .replace(Regex("\u001B\\]"), "")
                .replace("\r", "")
                .trim()
            if (clean.isNotBlank()) {
                broadcastChrootLine(clean)
                updateChrootNotification(clean.take(60))
            }
        }

        if (found != null) {
            broadcastFound(found)
            updateChrootNotification(getString(R.string.svc_found, found))
        } else {
            broadcastError(getString(R.string.svc_password_not_found))
            updateChrootNotification(getString(R.string.svc_not_found))
        }
    }

    private suspend fun copyWordlistToChroot(uri: android.net.Uri): String? =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File(cacheDir, "wl_chroot_${System.nanoTime()}.txt")
                tempFile.outputStream().use { inputStream.copyTo(it) }
                inputStream.close()
                val sdcard = Environment.getExternalStorageDirectory().absolutePath
                val chrootPath = if (tempFile.absolutePath.startsWith(sdcard)) {
                    tempFile.absolutePath.replace(sdcard, "/sdcard")
                } else {
                    val target = "/sdcard/WIFI-Frankenstein/temp/${tempFile.name}"
                    com.topjohnwu.superuser.Shell.cmd(
                        "mkdir -p /sdcard/WIFI-Frankenstein/temp && cp '${tempFile.absolutePath}' '$target'"
                    ).exec()
                    target
                }
                val cm = ChrootManager.get(this@WpaCrackService)
                cm.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
                chrootPath
            } catch (e: Exception) {
                Log.e(TAG, "copyWordlistToChroot failed", e)
                null
            }
        }

    private suspend fun generateCapForChroot(
        handshakeLine: String,
        cm: ChrootManager
    ): String? = withContext(Dispatchers.IO) {
        try {
            val capName = "chroot_crack_${System.nanoTime()}.cap"
            val capChrootPath = "/sdcard/WIFI-Frankenstein/temp/$capName"
            val tmpName = "chroot_crack_${System.nanoTime()}.22000"
            val tmp = File(cacheDir, tmpName)
            tmp.writeText(handshakeLine)
            val tmpChrootPath = "/sdcard/WIFI-Frankenstein/temp/$tmpName"
            com.topjohnwu.superuser.Shell.cmd(
                "mkdir -p /sdcard/WIFI-Frankenstein/temp && cp '${tmp.absolutePath}' '$tmpChrootPath'"
            ).exec()
            cm.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
            val res = cm.executeInChroot(
                "hcxhash2cap --pmkid-eapol '$tmpChrootPath' -c '$capChrootPath' 2>&1"
            )
            if (res.isSuccess) {
                capChrootPath
            } else {
                Log.w(TAG, "hcxhash2cap failed: ${res.out.joinToString("; ")}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateCapForChroot failed", e)
            null
        } finally {

        }
    }

    private suspend fun crackWithWordlistStreaming(
        capPath: String,
        wPath: String,
        cm: ChrootManager,
        onLine: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val ts = System.nanoTime()
        val chrootOut = "/sdcard/WIFI-Frankenstein/temp/crack_$ts.txt"

        cm.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
        cm.executeInChroot("aircrack-ng -w \"$wPath\" \"$capPath\" > \"$chrootOut\" 2>&1 &")

        val keyFoundRegex = Regex("""KEY\s*FOUND!\s*\[([^\]]+)]""", RegexOption.IGNORE_CASE)
        val ansiCleaner = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
        var found: String? = null
        var lastLineCount = 0

        try {
            while (isActive) {
                delay(1500)

                val result = cm.executeInChroot("cat \"$chrootOut\" 2>/dev/null || true")
                val lines = result.out
                if (lines.isEmpty()) continue

                if (lines.size > lastLineCount) {
                    for (i in lastLineCount until lines.size) {
                        val raw = lines[i]
                        val clean = raw.replace(ansiCleaner, "").replace("\r", "").trim()
                        if (clean.isNotBlank()) onLine(clean)
                        val match = keyFoundRegex.find(raw)
                        if (match != null) found = match.groupValues[1]
                    }
                    lastLineCount = lines.size
                }

                val alive = cm.executeInChroot(
                    "pgrep -f 'aircrack-ng' > /dev/null 2>&1 && echo ALIVE || echo DEAD"
                )
                if (alive.out.any { it.trim() == "DEAD" }) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Streaming crack error", e)
        } finally {
            cm.executeInChroot("killall -9 aircrack-ng 2>/dev/null || true")
            cm.executeInChroot("rm -f \"$chrootOut\"")
        }

        found
    }

    private fun updateChrootNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHROOT_CHANNEL_ID)
            .setContentTitle(getString(R.string.wpa_crack_notif_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .build()
        notificationManager.notify(CHROOT_NOTIFICATION_ID, notification)
    }

    private fun broadcastChrootLine(line: String) {
        val intent = Intent(BROADCAST_CHROOT_LINE).apply {
            putExtra(EXTRA_PROGRESS_TEXT, line)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun handleUpdateNotification(intent: Intent) {
        val text = intent.getStringExtra(EXTRA_PROGRESS_TEXT) ?: ""
        val percent = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, -1)
        val isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
        val currentPassword = intent.getStringExtra(EXTRA_CURRENT_PASSWORD) ?: ""
        val attempts = intent.getLongExtra(EXTRA_ATTEMPTS, 0)
        val speed = intent.getDoubleExtra(EXTRA_SPEED, 0.0)
        val offset = intent.getLongExtra(EXTRA_OFFSET, 0)
        val totalLines = intent.getLongExtra(EXTRA_TOTAL_LINES, 0)

        val notification = buildNotification(
            getString(if (isPaused) R.string.wpa_crack_paused else R.string.wpa_crack_notif_title),
            text,
            isPaused,
            currentPassword,
            attempts,
            speed,
            offset,
            totalLines
        )
        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

    private fun updateNotif(progress: OfflineProgress, isPaused: Boolean) {
        val title =
            getString(if (isPaused) R.string.wpa_crack_paused else R.string.wpa_crack_notif_title)
        val notification = buildNotification(
            title,
            progress.currentPassword,
            isPaused,
            progress.currentPassword,
            progress.attempts,
            progress.speed,
            progress.offset,
            progress.totalPasswords
        )
        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

    private fun updateNotificationSimple(title: String, content: String) {
        val notification = buildNotification(title, content, isPaused = false, "", 0, 0.0, 0, 0)
        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

    private fun buildNotification(
        title: String,
        content: String,
        isPaused: Boolean,
        currentPassword: String,
        attempts: Long,
        speed: Double,
        offset: Long,
        totalLines: Long
    ): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WpaCrackService::class.java).apply {
                action = if (isPaused) ACTION_RESUME_CRACK else ACTION_PAUSE_CRACK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, WpaCrackService::class.java).apply {
                action = ACTION_STOP_CRACK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(!isPaused)
            .setSilent(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (isPaused) R.string.wpa_crack_action_resume else R.string.wpa_crack_action_pause),
                pauseIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.wpa_crack_action_stop),
                stopIntent
            )

        if (totalLines > 0) {
            val pct = (attempts.toDouble() / totalLines * 100.0).toInt().coerceIn(0, 100)
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val infoStr = buildString {
            if (currentPassword.isNotBlank()) {
                append(getString(R.string.svc_trying, currentPassword))
            }
            if (attempts > 0) {
                if (isNotEmpty()) append("\n")
                append(getString(R.string.svc_attempts, attempts))
                if (totalLines > 0) append("/$totalLines")
            }
            if (speed > 0) {
                if (isNotEmpty()) append(" | ")
                append(getString(R.string.svc_speed, "%.0f".format(speed)))
            }
        }
        if (infoStr.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(infoStr))
        }

        return builder
    }

    private fun broadcastProgress(progress: OfflineProgress) {
        val intent = Intent(BROADCAST_CRACK_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS_TEXT, progress.currentPassword)
            putExtra(EXTRA_CURRENT_PASSWORD, progress.currentPassword)
            putExtra(EXTRA_ATTEMPTS, progress.attempts)
            putExtra(EXTRA_SPEED, progress.speed)
            putExtra(EXTRA_OFFSET, progress.offset)
            putExtra(EXTRA_TOTAL_LINES, progress.totalPasswords)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFound(password: String, hash: HandshakeHash? = null) {
        val intent = Intent(BROADCAST_CRACK_FOUND).apply {
            putExtra(EXTRA_RESULT_PSK, password)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(BROADCAST_CRACK_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastPaused() {
        val intent = Intent(BROADCAST_CRACK_PAUSED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        updateNotificationSimple(
            getString(R.string.wpa_crack_paused),
            getString(R.string.svc_paused)
        )
    }

    private fun broadcastResumed() {
        val intent = Intent(BROADCAST_CRACK_RESUMED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStopped() {
        val intent = Intent(BROADCAST_CRACK_STOPPED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        runner?.cancel()
        runner = null
        crackJob?.cancel()
        crackJob = null
        chrootCrackJob?.cancel()
        chrootCrackJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wpa_crack_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createChrootNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHROOT_CHANNEL_ID,
                getString(R.string.wpa_crack_chroot_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
