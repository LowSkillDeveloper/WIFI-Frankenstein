package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import androidx.core.content.edit
import com.lsd.wififrankenstein.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Status of a background database download.
 *
 * Lifecycle:
 * QUEUED -> RUNNING -> EXTRACTING -> DONE (removed from the queue on success)
 *                     \-> WAITING_QUOTA (MEGA bandwidth limit, retried automatically)
 *                     \-> FAILED (shown in the card with a Retry action)
 * RUNNING -> NEEDS_SETUP (custom SQLite downloaded; requires interactive table/column mapping)
 */
@Serializable
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    EXTRACTING,
    WAITING_QUOTA,
    FAILED,
    NEEDS_SETUP,
    DONE
}

/**
 * Lenient Json instance shared by pending-download helpers.
 */
val pendingDownloadJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Persisted entry of the background download queue. Survives process death;
 * byte-accurate resume is provided by [SmartLinkDbHelper] (.tmp/.metadata files).
 */
@Serializable
data class PendingDownload(
    val dbId: String,
    val name: String,
    val version: String,
    val dbInfoJson: String,
    val originUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val error: String? = null,
    val quotaRetryCount: Int = 0,
    val nextRetryAt: Long = 0L,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val resultDbItemJson: String? = null
) {
    fun decodeDbInfo(json: Json = pendingDownloadJson): SmartLinkDbInfo? = try {
        json.decodeFromString<SmartLinkDbInfo>(dbInfoJson)
    } catch (e: Exception) {
        Log.e("PendingDownload", "Failed to decode SmartLinkDbInfo", e)
        null
    }

    fun decodeDbItem(json: Json = pendingDownloadJson): DbItem? = try {
        resultDbItemJson?.let { json.decodeFromString<DbItem>(it) }
    } catch (e: Exception) {
        Log.e("PendingDownload", "Failed to decode DbItem", e)
        null
    }

    fun hasNetworkWork(): Boolean = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.RUNNING ||
            status == DownloadStatus.EXTRACTING ||
            status == DownloadStatus.WAITING_QUOTA
}

/**
 * SharedPreferences-backed storage for the pending download queue.
 * Uses the same prefs file as the registered database list ("db_setup_prefs").
 */
object PendingDownloadStore {

    private const val TAG = "PendingDownloadStore"
    private const val PREFS_NAME = "db_setup_prefs"
    private const val KEY_PENDING_DOWNLOADS = "pending_downloads"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<PendingDownload> {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_DOWNLOADS, null)
            if (raw.isNullOrBlank()) emptyList()
            else json.decodeFromString<List<PendingDownload>>(raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending downloads", e)
            emptyList()
        }
    }

    fun save(context: Context, items: List<PendingDownload>) {
        try {
            val raw = json.encodeToString(items)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_PENDING_DOWNLOADS, raw) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pending downloads", e)
        }
    }
}
