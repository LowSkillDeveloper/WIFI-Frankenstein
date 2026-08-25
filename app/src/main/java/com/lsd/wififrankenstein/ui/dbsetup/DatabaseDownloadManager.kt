package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import androidx.core.content.edit
import com.lsd.wififrankenstein.network.MegaQuotaException
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide orchestrator of background SmartLink database downloads.
 *
 * - Owns a sequential download queue exposed via [downloads] StateFlow.
 * - Persists the queue so it survives process death; on the next app start
 *   interrupted items are re-queued and SmartLinkDbHelper resumes from the
 *   partial .tmp/.metadata files automatically.
 * - When MEGA reports a bandwidth limit (HTTP 509 / MegaQuotaException) the
 *   item is parked in WAITING_QUOTA and retried automatically:
 *   the first 2 retries after 15 minutes, then every 6 hours.
 * - Custom SQLite databases that require interactive table/column mapping are
 *   parked in NEEDS_SETUP instead of being registered directly.
 */
class DatabaseDownloadManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "DatabaseDownloadManager"

        private const val QUOTA_RETRY_SHORT_MS = 15L * 60L * 1000L   // first 2 retries: every 15 min
        private const val QUOTA_RETRY_LONG_MS = 6L * 60L * 60L * 1000L // then: every 6 hours
        private const val QUOTA_SHORT_RETRY_LIMIT = 2
        private const val IDLE_POLL_MS = 1000L

        @Volatile
        private var instance: DatabaseDownloadManager? = null

        fun getOrCreate(context: Context): DatabaseDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DatabaseDownloadManager(context.applicationContext).also { instance = it }
            }
        }

        /** Returns the already-initialized instance, if any (does not create one). */
        fun peek(): DatabaseDownloadManager? = instance
    }

    sealed class Event {
        /** A regular database finished downloading and was registered into db_list. */
        data class Completed(val dbItem: DbItem) : Event()

        /** A custom SQLite database was downloaded but requires interactive setup. */
        data class NeedsSetupReady(val pending: PendingDownload, val dbItem: DbItem) : Event()

        data class Failed(val name: String, val reason: String?) : Event()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutationMutex = Mutex()
    private val dbListFileMutex = Mutex()

    private val _downloads = MutableStateFlow<List<PendingDownload>>(emptyList())
    val downloads: StateFlow<List<PendingDownload>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val userCancelledIds = ConcurrentHashMap.newKeySet<String>()
    private var queueRestored = false

    @Volatile
    private var currentItemJob: Job? = null

    @Volatile
    private var currentItemId: String? = null

    init {
        restoreFromDisk()
        scope.launch { persistWatcher() }
        scope.launch { queueWorker() }
    }

    // ------------------------------------------------------------------ state

    /**
     * Loads the persisted queue from disk (idempotent). Stale RUNNING items
     * left by a killed process are re-queued; SmartLinkDbHelper resumes them
     * from partial files on the next worker pass.
     */
    fun restoreFromDisk() {
        synchronized(this) {
            if (queueRestored) return
            queueRestored = true
        }
        val restored = PendingDownloadStore.load(appContext)
            .mapNotNull { item ->
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.EXTRACTING ->
                        item.copy(status = DownloadStatus.QUEUED, progressPercent = 0)

                    DownloadStatus.DONE -> null

                    else -> item
                }
            }
            .sortedBy { it.enqueuedAt }
        if (restored.isNotEmpty()) {
            Log.i(TAG, "Restored ${restored.size} pending download(s)")
            _downloads.value = restored
        }
    }

    /** True if any download is queued/running/waiting for MEGA quota. */
    fun hasNetworkWork(): Boolean = _downloads.value.any { it.hasNetworkWork() }

    /** True if anything is unfinished at all (incl. downloaded-but-unconfigured). */
    fun hasActiveWork(): Boolean = _downloads.value.any {
        it.hasNetworkWork() || it.status == DownloadStatus.NEEDS_SETUP
    }

    fun hasNeedsSetup(): Boolean = _downloads.value.any { it.status == DownloadStatus.NEEDS_SETUP }

    // ---------------------------------------------------------------- enqueue

    /**
     * Adds databases to the background queue. Skips entries that are already
     * queued or already registered in the database list.
     *
     * @param originUrl catalog/page URL the databases came from; stored on the
     * helper so `updateUrl` is preserved (same as the in-dialog flow).
     * @return number of items actually enqueued.
     */
    suspend fun enqueue(
        databases: List<SmartLinkDbInfo>,
        originUrl: String? = null
    ): Int {
        var added = 0
        mutationMutex.withLock {
            val existingIds = _downloads.value.map { it.dbId }.toMutableSet()
            val registeredIds = readRegisteredDbIds()
            val newItems = mutableListOf<PendingDownload>()
            for (info in databases) {
                if (info.id in existingIds || info.id in registeredIds) continue
                existingIds += info.id
                newItems += PendingDownload(
                    dbId = info.id,
                    name = info.name,
                    version = info.version,
                    dbInfoJson = json.encodeToString(info),
                    originUrl = originUrl,
                    status = DownloadStatus.QUEUED,
                    enqueuedAt = System.currentTimeMillis()
                )
            }
            if (newItems.isNotEmpty()) {
                _downloads.value = (_downloads.value + newItems).sortedBy { it.enqueuedAt }
                added = newItems.size
            }
        }
        if (added > 0) {
            persistNow()
            Log.i(TAG, "Enqueued $added database(s), origin=$originUrl")
        }
        return added
    }

    private suspend fun readRegisteredDbIds(): Set<String> {
        return try {
            val raw = appContext.getSharedPreferences("db_setup_prefs", Context.MODE_PRIVATE)
                .getString("db_list", null) ?: return emptySet()
            val list = json.decodeFromString<List<DbItem>>(raw)
            list.mapNotNull { it.idJson }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read registered db ids", e)
            emptySet()
        }
    }

    // ------------------------------------------------------- user actions

    fun retry(dbId: String) {
        scope.launch {
            mutationMutex.withLock {
                _downloads.update { list ->
                    list.map {
                        if (it.dbId == dbId) it.copy(
                            status = DownloadStatus.QUEUED,
                            quotaRetryCount = 0,
                            nextRetryAt = 0L,
                            error = null,
                            progressPercent = 0
                        ) else it
                    }
                }
            }
            persistNow()
        }
    }

    fun cancel(dbId: String) {
        if (currentItemId == dbId) {
            userCancelledIds.add(dbId)
            currentItemJob?.cancel()
        }
        scope.launch {
            mutationMutex.withLock {
                _downloads.update { list -> list.filterNot { it.dbId == dbId } }
            }
            persistNow()
        }
    }

    fun cancelAll() {
        currentItemId?.let { userCancelledIds.add(it) }
        currentItemJob?.cancel()
        scope.launch {
            mutationMutex.withLock { _downloads.value = emptyList() }
            persistNow()
        }
    }

    /** Called after the user finished interactive setup of a NEEDS_SETUP item. */
    fun markSetupCompleted(dbId: String) {
        scope.launch {
            mutationMutex.withLock {
                _downloads.update { list -> list.filterNot { it.dbId == dbId } }
            }
            persistNow()
        }
    }

    // ----------------------------------------------------------------- worker

    /**
     * Single permanent consumer of the queue. When nothing is due it polls
     * every [IDLE_POLL_MS] — this keeps the design race-free: enqueue/retry/
     * quota timers only mutate state, the worker always picks up what is due.
     */
    private suspend fun queueWorker() {
        while (currentCoroutineContext().isActive) {
            val next = pickNext()
            if (next == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            processItem(next)
        }
    }

    private fun pickNext(): PendingDownload? {
        val now = System.currentTimeMillis()
        return _downloads.value.firstOrNull { item ->
            item.status == DownloadStatus.QUEUED ||
                    (item.status == DownloadStatus.WAITING_QUOTA && item.nextRetryAt <= now)
        }
    }

    private suspend fun processItem(item: PendingDownload) {
        // Item may have been cancelled right before we picked it up
        if (_downloads.value.none { it.dbId == item.dbId }) return

        val info = item.decodeDbInfo() ?: run {
            markFailed(item, null)
            return
        }
        updateItem(item.dbId) {
            it.copy(status = DownloadStatus.RUNNING, error = null, progressPercent = 0)
        }
        Log.d(TAG, "Starting download: ${item.name} (${item.dbId})")

        // Child job so cancelling one item does not kill the queue worker.
        val job = scope.launch { runDownload(item, info) }
        currentItemJob = job
        currentItemId = item.dbId
        job.join()
        currentItemJob = null
        currentItemId = null
    }

    private suspend fun runDownload(item: PendingDownload, info: SmartLinkDbInfo) {
        val helper = SmartLinkDbHelper(appContext)
        helper.setDownloadOrigin(item.originUrl)
        try {
            val dbItem = helper.downloadDatabase(info) { progress, bytes, total ->
                onProgress(item.dbId, progress, bytes, total)
            }
            if (_downloads.value.none { it.dbId == item.dbId }) {
                Log.d(TAG, "Item removed during download, discarding result: ${item.name}")
                return
            }
            onSuccess(item, dbItem, helper)
        } catch (e: CancellationException) {
            if (userCancelledIds.remove(item.dbId)) {
                helper.clearDownloadMetadata(item.dbId)
                Log.d(TAG, "Cancelled by user: ${item.name}")
            } else {
                // Scope shutdown (app killed): leave partials for next-start resume
                updateItem(item.dbId) { it.copy(status = DownloadStatus.QUEUED, progressPercent = 0) }
            }
        } catch (e: MegaQuotaException) {
            markWaitingQuota(item)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${item.name}", e)
            markFailed(item, e.message)
        }
    }

    private fun onProgress(dbId: String, progress: Int, bytes: Long, total: Long?) {
        _downloads.update { list ->
            list.map { current ->
                if (current.dbId != dbId) return@map current
                when {
                    progress == PROGRESS_EXTRACT -> current.copy(
                        status = DownloadStatus.EXTRACTING,
                        progressPercent = bytes.toInt().coerceIn(0, 100),
                        totalBytes = total
                    )

                    progress >= 0 -> current.copy(
                        status = DownloadStatus.RUNNING,
                        progressPercent = progress,
                        downloadedBytes = bytes,
                        totalBytes = total
                    )

                    else -> current // PROGRESS_RESUME / PART / MERGE: keep last percent
                }
            }
        }
    }

    private suspend fun onSuccess(item: PendingDownload, dbItem: DbItem, helper: SmartLinkDbHelper) {
        helper.clearDownloadMetadata(item.dbId)

        val needsSetup = dbItem.dbType == DbType.SQLITE_FILE_CUSTOM ||
                dbItem.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM

        if (needsSetup) {
            val updated = updateAndGet(item.dbId) {
                it.copy(
                    status = DownloadStatus.NEEDS_SETUP,
                    progressPercent = 100,
                    error = null,
                    resultDbItemJson = json.encodeToString(dbItem)
                )
            }
            if (updated != null) {
                _events.tryEmit(Event.NeedsSetupReady(updated, dbItem))
            }
            Log.i(TAG, "Needs setup: ${item.name}")
        } else {
            appendToPersistedDbList(dbItem)
            DbSetupViewModel.needDataRefresh = true
            removeItem(item.dbId)
            _events.tryEmit(Event.Completed(dbItem))
            Log.i(TAG, "Completed: ${item.name}")
        }
    }

    private fun markWaitingQuota(item: PendingDownload) {
        val updated = updateAndGet(item.dbId) { current ->
            val retries = current.quotaRetryCount + 1
            val delayMs =
                if (retries <= QUOTA_SHORT_RETRY_LIMIT) QUOTA_RETRY_SHORT_MS else QUOTA_RETRY_LONG_MS
            current.copy(
                status = DownloadStatus.WAITING_QUOTA,
                quotaRetryCount = retries,
                nextRetryAt = System.currentTimeMillis() + delayMs,
                error = null
            )
        }
        if (updated != null) {
            Log.w(TAG, "MEGA quota exceeded for ${item.name}, retry #${updated.quotaRetryCount}")
        }
    }

    private fun markFailed(item: PendingDownload, reason: String?) {
        val updated = updateAndGet(item.dbId) {
            it.copy(status = DownloadStatus.FAILED, error = reason ?: "")
        }
        if (updated != null) {
            _events.tryEmit(Event.Failed(updated.name, reason))
        }
    }

    // -------------------------------------------------------------- helpers

    private fun updateItem(dbId: String, transform: (PendingDownload) -> PendingDownload) {
        _downloads.update { list -> list.map { if (it.dbId == dbId) transform(it) else it } }
    }

    private fun updateAndGet(
        dbId: String,
        transform: (PendingDownload) -> PendingDownload
    ): PendingDownload? {
        var result: PendingDownload? = null
        _downloads.update { list ->
            list.map {
                if (it.dbId == dbId) {
                    result = transform(it)
                    result!!
                } else it
            }
        }
        return result
    }

    private fun removeItem(dbId: String) {
        _downloads.update { list -> list.filterNot { it.dbId == dbId } }
    }

    private suspend fun persistNow() {
        PendingDownloadStore.save(appContext, _downloads.value)
    }

    /**
     * Persists the queue on every change, throttled to at most once per 2 s
     * (progress callbacks can arrive very frequently).
     */
    private suspend fun persistWatcher() {
        var lastSave = 0L
        downloads.collect { list ->
            val now = System.currentTimeMillis()
            if (now - lastSave >= 2000) {
                lastSave = now
                PendingDownloadStore.save(appContext, list)
            }
        }
    }

    /**
     * Appends a finished DbItem to the persisted "db_list" JSON directly.
     * UI instances reload via [DbSetupViewModel.loadDbList] when they receive
     * the Completed event or observe needDataRefresh.
     */
    private suspend fun appendToPersistedDbList(dbItem: DbItem) {
        dbListFileMutex.withLock {
            try {
                val prefs = appContext.getSharedPreferences("db_setup_prefs", Context.MODE_PRIVATE)
                val raw = prefs.getString("db_list", null)
                val current = if (raw.isNullOrBlank()) emptyList()
                else json.decodeFromString<List<DbItem>>(raw)
                if (current.any { it.id == dbItem.id || (dbItem.idJson != null && it.idJson == dbItem.idJson) }) {
                    return@withLock
                }
                prefs.edit { putString("db_list", json.encodeToString(current + dbItem)) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append db to persisted list", e)
            }
        }
    }
}
