package com.lsd.wififrankenstein.ui.personalmap

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.network.WpaSecClient
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.service.WiFiMapScanningService
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeMetadataDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapStats
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalPointDetail
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalWifiNetwork
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class PersonalWiFiMapViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = PersonalMapDbHelper(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _networksCount = MutableLiveData<Int>(0)
    val networksCount: LiveData<Int> = _networksCount

    private val _dataVersion = MutableLiveData(0L)
    val dataVersion: LiveData<Long> = _dataVersion
    private var lastDataVersionMs = 0L

    private fun maybeBumpDataVersion() {
        val now = System.currentTimeMillis()
        if (now - lastDataVersionMs < DATA_VERSION_THROTTLE_MS) return
        lastDataVersionMs = now
        _dataVersion.postValue((_dataVersion.value ?: 0L) + 1L)
    }

    fun bumpDataVersionNow() {
        lastDataVersionMs = System.currentTimeMillis()
        _dataVersion.postValue((_dataVersion.value ?: 0L) + 1L)
    }

    private val _gpsHealth = MutableLiveData<String>("UNKNOWN")
    val gpsHealth: LiveData<String> = _gpsHealth

    private val _recentNetworks = MutableLiveData<List<PersonalMapLogEntry>>(emptyList())
    val recentNetworks: LiveData<List<PersonalMapLogEntry>> = _recentNetworks

    private val _records = MutableLiveData<List<PersonalMapLogEntry>>(emptyList())
    val records: LiveData<List<PersonalMapLogEntry>> = _records

    private val _stats = MutableLiveData<PersonalMapStats>(PersonalMapStats())
    val stats: LiveData<PersonalMapStats> = _stats

    private val _sessionStats = MutableLiveData<PersonalSessionStats>(PersonalSessionStats())
    val sessionStats: LiveData<PersonalSessionStats> = _sessionStats

    private val _gpsDetail = MutableLiveData<PersonalGpsDetail>(PersonalGpsDetail())
    val gpsDetail: LiveData<PersonalGpsDetail> = _gpsDetail

    private val _busyCount = MutableLiveData<Int>(0)
    private val _isBusy = MutableLiveData<Boolean>(false)
    val isBusy: LiveData<Boolean> = _isBusy

    private val busyCounter = java.util.concurrent.atomic.AtomicInteger(0)

    private fun setBusy(busy: Boolean) {
        val next = if (busy) {
            busyCounter.incrementAndGet()
        } else {
            busyCounter.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }
        }
        _busyCount.postValue(next)
        _isBusy.postValue(next > 0)
    }

    private fun setDbLocked(locked: Boolean) {
        prefs.edit { putBoolean(WiFiMapScanningService.KEY_DB_LOCK, locked) }
    }

    private fun launchBusy(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            setBusy(true)
            try {
                block()
            } finally {
                setBusy(false)
            }
        }

    private val sessionLock = Any()
    private val sessionNetworks =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private var sessionScans = 0
    private var sessionMeasurements = 0
    private var sessionStartedAt = 0L
    private var sessionCapped = false
    private var lastStatsRefreshMs = 0L
    private val broadcastSeq = java.util.concurrent.atomic.AtomicLong(0)

    private var lastAppliedSeq = 0L
    private var recentNetworksSnapshot: List<PersonalMapLogEntry> = emptyList()

    private val sessionWpasecQueue = LinkedHashMap<String, String>()
    private var sessionWpasecWindowStart = 0L
    private var sessionWpasecDrainAll = false
    private var sessionWpasecJob: Job? = null
    private var sessionWpasecScanning = false

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WiFiMapScanningService.BROADCAST_STATE) return

            val found = intent.getIntExtra(WiFiMapScanningService.EXTRA_NETWORKS_COUNT, 0)
            val scanning = intent.getBooleanExtra(WiFiMapScanningService.EXTRA_SCANNING, false)
            _isScanning.postValue(scanning)
            val wpasecOn = prefs.getBoolean(PersonalWiFiMapFragment.KEY_WPASEC, true)
            var stopDrain = false
            var timerDrain = false
            synchronized(sessionLock) {
                val wasScanning = sessionWpasecScanning
                sessionWpasecScanning = scanning
                if (!wpasecOn && (sessionWpasecQueue.isNotEmpty() || sessionWpasecJob?.isActive == true)) {
                    sessionWpasecQueue.clear()
                    sessionWpasecDrainAll = false
                    sessionWpasecWindowStart = 0L
                    sessionWpasecJob?.cancel()
                    sessionWpasecJob = null
                } else {
                    if (wasScanning && !scanning && sessionWpasecQueue.isNotEmpty()) {
                        sessionWpasecDrainAll = true
                        stopDrain = true
                    } else if (scanning && sessionWpasecQueue.isNotEmpty() &&
                        sessionWpasecWindowStart > 0L &&
                        System.currentTimeMillis() - sessionWpasecWindowStart >= SESSION_WPASEC_WINDOW_MS
                    ) {
                        sessionWpasecDrainAll = true
                        timerDrain = true
                    }
                }
            }
            if (stopDrain || timerDrain) ensureSessionWpasecWorker()
            _networksCount.postValue(found)
            _gpsHealth.postValue(
                intent.getStringExtra(WiFiMapScanningService.EXTRA_GPS_HEALTH) ?: "UNKNOWN"
            )
            _gpsDetail.postValue(
                PersonalGpsDetail(
                    accuracy = intent.getFloatExtra(WiFiMapScanningService.EXTRA_GPS_ACCURACY, 0f),
                    speed = intent.getFloatExtra(WiFiMapScanningService.EXTRA_GPS_SPEED, 0f),
                    satellites = intent.getIntExtra(
                        WiFiMapScanningService.EXTRA_GPS_SATELLITES, 0
                    ),
                    fixAgeMs = intent.getLongExtra(WiFiMapScanningService.EXTRA_GPS_FIX_AGE, 0L)
                )
            )

            val entries = parseLogEntries(intent)
            val scanTime = intent.getLongExtra(WiFiMapScanningService.EXTRA_SCAN_TIME, 0L)

            val seq = broadcastSeq.incrementAndGet()
            if (entries.isNotEmpty()) {

                viewModelScope.launch(Dispatchers.Default) {
                    val snapshot: List<PersonalMapLogEntry>
                    val stats: PersonalSessionStats
                    var triggerWpasec = false
                    synchronized(sessionLock) {
                        if (seq < lastAppliedSeq) return@launch
                        lastAppliedSeq = seq
                        if (sessionStartedAt == 0L) sessionStartedAt = System.currentTimeMillis()
                        sessionScans++
                        sessionMeasurements += entries.size
                        entries.forEach {
                            if (sessionNetworks.size < MAX_SESSION_NETWORKS) {
                                sessionNetworks.add(it.bssid.uppercase())
                            } else {
                                sessionCapped = true
                            }
                        }

                        var triggerWpasecFeed = false
                        if (wpasecOn) {
                            if (sessionWpasecQueue.isEmpty()) {
                                sessionWpasecWindowStart = System.currentTimeMillis()
                            }
                            entries.forEach {
                                val ssid = it.ssid.trim()
                                if (ssid.isNotEmpty()) {
                                    sessionWpasecQueue.putIfAbsent(
                                        PersonalMapDbHelper.normalizeMacKey(it.bssid), ssid
                                    )
                                }
                            }
                            if (sessionWpasecQueue.size >= SESSION_WPASEC_CHUNK) {
                                triggerWpasecFeed = true
                            } else if (!sessionWpasecScanning && sessionWpasecQueue.isNotEmpty()) {
                                sessionWpasecDrainAll = true
                                triggerWpasecFeed = true
                            }
                        }
                        triggerWpasec = triggerWpasecFeed

                        val previous = recentNetworksSnapshot
                        snapshot = (entries + previous).take(MAX_SESSION_LOG)
                        recentNetworksSnapshot = snapshot

                        val newCount = entries.count { it.isNew }
                        stats = PersonalSessionStats(
                            scans = sessionScans,
                            measurements = sessionMeasurements,
                            distinctNetworks = sessionNetworks.size,
                            distinctCapped = sessionCapped,
                            startedAt = sessionStartedAt,
                            lastFound = found,
                            lastNew = newCount,
                            lastUpdated = entries.size - newCount,
                            lastScanTime = scanTime
                        )
                    }
                    if (triggerWpasec) ensureSessionWpasecWorker()
                    _recentNetworks.postValue(snapshot)
                    _sessionStats.postValue(stats)
                }
            } else {

                val current = _sessionStats.value ?: PersonalSessionStats()
                _sessionStats.postValue(current.copy(lastFound = found))
            }

            refreshStatsThrottled()
            maybeBumpDataVersion()
        }
    }

    init {
        LocalBroadcastManager.getInstance(application).registerReceiver(
            serviceReceiver,
            IntentFilter(WiFiMapScanningService.BROADCAST_STATE)
        )
        refreshStats()
    }

    @Volatile private var recordsExhausted = false
    private val recordsLoadingMore = AtomicBoolean(false)
    @Volatile private var currentQuery: String = ""
    @Volatile private var lastRecordTimestamp = 0L
    @Volatile private var lastRecordId = Long.MAX_VALUE
    private val recordsGeneration = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var recordsSnapshot: List<PersonalMapLogEntry> = emptyList()

    private fun PersonalWifiNetwork.toLogEntry(tags: List<String> = emptyList()) = PersonalMapLogEntry(
        ssid = wifiName,
        bssid = macAddress,
        level = level,
        isReliable = isReliable,
        isNew = false,
        timestamp = timestamp,
        id = id,
        otherDbState = otherDbState,
        wpasecState = wpasecState,
        isWps = isWps,
        isPasspoint = isPasspoint,
        isHidden = isHidden,
        isOpen = (securityType?.takeIf { it.isNotBlank() }
            ?: PersonalMapDbHelper.classifySecurity(security)) == PersonalMapDbHelper.SECURITY_OPEN,
        tags = tags
    )

    private fun attachTags(entries: List<PersonalWifiNetwork>): List<PersonalMapLogEntry> {
        if (entries.isEmpty()) return emptyList()
        val tagMap = try {
            dbHelper.getTagsForBssids(entries.map { it.macAddress }.toSet())
        } catch (_: Exception) { emptyMap() }
        return entries.map { net ->
            net.toLogEntry(tagMap[PersonalMapDbHelper.normalizeMacKey(net.macAddress)].orEmpty())
        }
    }

    fun refreshRecords(limit: Int = MAX_RECORDS_LOADED) {
        currentQuery = ""
        searchJob?.cancel()
        recordsLoadingMore.set(true)
        val gen = recordsGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = dbHelper.getPersonalRecordsPage(0L, Long.MAX_VALUE, limit)
                if (recordsGeneration.get() != gen) return@launch
                lastRecordTimestamp = page.lastOrNull()?.timestamp ?: 0L
                lastRecordId = page.lastOrNull()?.id ?: Long.MAX_VALUE
                recordsExhausted = page.size < limit
                val entries = attachTags(page)
                recordsSnapshot = entries
                _records.postValue(entries)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load personal map records: ${e.message}")
            } finally {
                recordsLoadingMore.set(false)
            }
        }
    }

    fun loadMoreRecords(limit: Int = MAX_RECORDS_LOADED) {
        if (recordsExhausted) return
        if (!recordsLoadingMore.compareAndSet(false, true)) return
        val gen = recordsGeneration.get()
        val cursorTs = lastRecordTimestamp
        val cursorId = lastRecordId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = dbHelper.getPersonalRecordsPage(cursorTs, cursorId, limit)

                if (recordsGeneration.get() != gen) return@launch
                if (page.isEmpty()) {
                    recordsExhausted = true
                    return@launch
                }
                lastRecordTimestamp = page.last().timestamp
                lastRecordId = page.last().id
                if (page.size < limit) recordsExhausted = true
                val more = attachTags(page)
                val current = recordsSnapshot
                val remaining = (MAX_RECORDS_IN_MEMORY - current.size).coerceAtLeast(0)
                if (more.size > remaining) recordsExhausted = true
                val combined = current + more.take(remaining)
                if (combined.size >= MAX_RECORDS_IN_MEMORY) recordsExhausted = true
                recordsSnapshot = combined
                _records.postValue(combined)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load more personal map records: ${e.message}")
            } finally {
                recordsLoadingMore.set(false)
            }
        }
    }

    private var searchJob: Job? = null

    fun searchRecords(query: String, limit: Int = MAX_RECORDS_LOADED) {
        runSearch(query, limit, debounce = true)
    }

    private fun runSearch(query: String, limit: Int, debounce: Boolean) {
        currentQuery = query
        searchJob?.cancel()
        val gen = recordsGeneration.incrementAndGet()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            try {
                val found = dbHelper.searchPersonalRecords(query, limit)
                if (recordsGeneration.get() != gen) return@launch

                recordsExhausted = true
                recordsLoadingMore.set(false)
                val entries = attachTags(found)
                recordsSnapshot = entries
                _records.postValue(entries)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to search personal map records: ${e.message}")
            }
        }
    }

    fun reloadRecords() {
        val q = currentQuery
        if (q.isBlank()) refreshRecords() else runSearch(q, MAX_RECORDS_LOADED, debounce = false)
    }

    fun getPointDetail(id: Long, onResult: (PersonalPointDetail?) -> Unit) {
        if (id <= 0L) {
            onResult(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val detail = try {
                dbHelper.getPersonalPointDetail(id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load point detail: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) { onResult(detail) }
        }
    }

    private fun parseLogEntries(intent: Intent): List<PersonalMapLogEntry> {
        val json = intent.getStringExtra(WiFiMapScanningService.EXTRA_RECENT_NETWORKS)
            ?: return emptyList()
        return try {
            Json.decodeFromString<List<PersonalMapLogEntry>>(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recent networks: ${e.message}")
            emptyList()
        }
    }

    private fun refreshStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _stats.postValue(dbHelper.getPersonalMapStats())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load personal map stats: ${e.message}")
            }
        }
        bumpDataVersionNow()
    }

    private fun refreshStatsThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastStatsRefreshMs < STATS_REFRESH_THROTTLE_MS) return
        lastStatsRefreshMs = now
        refreshStats()
    }

    private fun resetSession() {
        synchronized(sessionLock) {
            sessionNetworks.clear()
            sessionScans = 0
            sessionMeasurements = 0
            sessionCapped = false
            sessionStartedAt = System.currentTimeMillis()
            recentNetworksSnapshot = emptyList()
            lastAppliedSeq = broadcastSeq.get()
            sessionWpasecQueue.clear()
            sessionWpasecDrainAll = false
            sessionWpasecWindowStart = 0L
            sessionWpasecJob?.cancel()
            sessionWpasecJob = null
            sessionWpasecScanning = false
        }
        _recentNetworks.postValue(emptyList())
        _sessionStats.postValue(PersonalSessionStats(startedAt = sessionStartedAt))
    }

    fun startScanning(saveToDb: Boolean) {
        resetSession()
        val intent = Intent(getApplication(), WiFiMapScanningService::class.java).apply {
            action = WiFiMapScanningService.ACTION_START
            putExtra(WiFiMapScanningService.EXTRA_SAVE, saveToDb)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stopScanning() {
        synchronized(sessionLock) {
            if (sessionWpasecQueue.isNotEmpty()) {
                sessionWpasecDrainAll = true
            }
        }
        ensureSessionWpasecWorker()
        val app = getApplication<Application>()
        val intent = Intent(app, WiFiMapScanningService::class.java).apply {
            action = WiFiMapScanningService.ACTION_STOP
        }
        try {
            app.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startService(ACTION_STOP) rejected, using stopService: ${e.message}")
            try {
                app.stopService(Intent(app, WiFiMapScanningService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    fun clearMap() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            app.stopService(Intent(app, WiFiMapScanningService::class.java))
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline) {
                if (prefs.getBoolean(WiFiMapScanningService.KEY_DB_LOCK, false)) break
                delay(50)
            }
            setDbLocked(true)
            try {
                dbHelper.checkpointWal()
                dbHelper.clearPersonalMap()
            } finally {
                setDbLocked(false)
            }
            resetSession()
            _networksCount.postValue(0)
            refreshStats()
            refreshRecords()
        }
    }

    fun updateNetworkName(id: Long, newName: String, onResult: (Boolean) -> Unit = {}) {
        val name = newName.trim()
        if (id <= 0L || name.isEmpty()) {
            onResult(false)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                dbHelper.updatePersonalNetworkInfo(id, name)
            } catch (e: Exception) {
                Log.w(TAG, "Rename personal record failed: ${e.message}")
                false
            }
            if (ok) {
                refreshStats()
                reloadRecords()
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun deleteNetwork(id: Long, onDeleted: (PersonalWifiNetwork?) -> Unit) {
        if (id <= 0L) {
            onDeleted(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val record = try {
                dbHelper.getPersonalRecordById(id)
            } catch (e: Exception) {
                Log.w(TAG, "Load record for delete failed: ${e.message}")
                null
            }
            val ok = try {
                dbHelper.deletePersonalRecord(id)
            } catch (e: Exception) {
                Log.w(TAG, "Delete personal record failed: ${e.message}")
                false
            }
            if (ok) {
                refreshStats()
                reloadRecords()
            }
            withContext(Dispatchers.Main) { onDeleted(if (ok) record else null) }
        }
    }

    fun restoreNetwork(record: PersonalWifiNetwork, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                dbHelper.addPersonalRecords(listOf(record))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Restore personal record failed: ${e.message}")
                false
            }
            if (ok) {
                refreshStats()
                reloadRecords()
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun exportWigleCsv(uri: Uri, gzip: Boolean, onResult: (Boolean) -> Unit = {}) {
        launchBusy {
            val app = getApplication<Application>()
            setDbLocked(true)
            var ok = false
            try {
                app.contentResolver.openOutputStream(uri)?.use { raw ->
                    val writer = OutputStreamWriter(if (gzip) GZIPOutputStream(raw) else raw)
                    writer.buffered().use { dbHelper.exportWigleCsv(it) }
                    ok = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "WiGLE CSV export failed", e)
            } finally {
                setDbLocked(false)
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun importWigleCsv(uri: Uri, onResult: (Int, Int) -> Unit = { _, _ -> }) {
        launchBusy {
            val app = getApplication<Application>()
            setDbLocked(true)
            try {
                val input = app.contentResolver.openInputStream(uri) ?: return@launchBusy
                val result = input.use { raw ->
                    val buffered = BufferedInputStream(raw)
                    buffered.mark(2)
                    val b1 = buffered.read()
                    val b2 = buffered.read()
                    buffered.reset()
                    val reader = if (b1 == 0x1f && b2 == 0x8b) {
                        InputStreamReader(GZIPInputStream(buffered), Charsets.UTF_8)
                    } else {
                        InputStreamReader(buffered, Charsets.UTF_8)
                    }
                    reader.use { dbHelper.importWigleCsv(it) }
                }
                refreshStats()
                refreshRecords()
                withContext(Dispatchers.Main) { onResult(result.inserted, result.updated) }
            } catch (e: Exception) {
                Log.e(TAG, "WiGLE CSV import failed", e)
                withContext(Dispatchers.Main) { onResult(-1, -1) }
            } finally {
                setDbLocked(false)
            }
        }
    }

    fun uploadWigleCsv(uri: Uri, onResult: (Boolean, String) -> Unit) {
        launchBusy {
            val app = getApplication<Application>()
            val apiKey = prefs.getString(KEY_WIGLE_API, null)?.trim().orEmpty()
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main) { onResult(false, "No WiGLE API key") }
                return@launchBusy
            }
            val temp = File(app.cacheDir, "wigle_upload.csv")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { out -> input.copyTo(out) }
                }
                val isGzip = try {
                    temp.inputStream().use {
                        val b1 = it.read()
                        val b2 = it.read()
                        b1 == 0x1f && b2 == 0x8b
                    }
                } catch (_: Exception) { false }
                val fileName = if (isGzip) "wigle.csv.gz" else "wigle.csv"
                val mime = if (isGzip) "application/gzip" else "text/csv"
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, temp.asRequestBody(mime.toMediaType()))
                    .build()
                if (!apiKey.contains(':')) {
                    withContext(Dispatchers.Main) { onResult(false, "WiGLE API key must be name:token") }
                    return@launchBusy
                }
                val auth = Base64.encodeToString(apiKey.toByteArray(), Base64.NO_WRAP)
                val request = Request.Builder()
                    .url("https://api.wigle.net/api/v2/file/upload")
                    .header("Authorization", "Basic $auth")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val message = when {
                        response.code == 429 -> "HTTP 429: rate limited, retry later"
                        else -> "HTTP ${response.code}: $text"
                    }
                    withContext(Dispatchers.Main) {
                        onResult(
                            response.isSuccessful && text.contains("\"success\":true"),
                            message
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WiGLE upload failed", e)
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "error") }
            } finally {
                temp.delete()
            }
        }
    }

    fun importWifiLocTracker(uri: Uri, onResult: (Int) -> Unit = {}) {
        launchBusy {
            val app = getApplication<Application>()
            val temp = File(app.cacheDir, "temp_personal_wifiloc_${System.currentTimeMillis()}.db")
            var externalDb: SQLiteDatabase? = null
            var cursor: Cursor? = null
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    withContext(Dispatchers.Main) { onResult(-1) }
                    return@launchBusy
                }
                externalDb = try {
                    SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                } catch (e: Exception) {
                    Log.e(TAG, "WifiLocTracker: not a database", e)
                    withContext(Dispatchers.Main) { onResult(-1) }
                    return@launchBusy
                }
                val hasTable = try {
                    externalDb.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf("access_points")
                    ).use { it.moveToFirst() }
                } catch (_: Exception) { false }
                if (!hasTable) {
                    withContext(Dispatchers.Main) { onResult(-1) }
                    return@launchBusy
                }
                cursor = externalDb.query("access_points", null, null, null, null, null, null)

                val bssidIdx = cursor.getColumnIndex("bssid")
                val ssidIdx = cursor.getColumnIndex("ssid")
                val latIdx = cursor.getColumnIndex("latitude")
                val lonIdx = cursor.getColumnIndex("longitude")
                val accIdx = cursor.getColumnIndex("accuracy")
                val timeIdx = cursor.getColumnIndex("lastSeen")
                val countIdx = cursor.getColumnIndex("scanCount")

                var imported = 0
                val batch = mutableListOf<PersonalWifiNetwork>()
                while (cursor.moveToNext()) {
                    val bssid = if (bssidIdx >= 0) cursor.getString(bssidIdx).orEmpty() else ""
                    val lat = if (latIdx >= 0) cursor.getDouble(latIdx) else 0.0
                    val lon = if (lonIdx >= 0) cursor.getDouble(lonIdx) else 0.0
                    if (bssid.isBlank() || (lat == 0.0 && lon == 0.0)) continue
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                    val acc = if (accIdx >= 0) cursor.getFloat(accIdx) else 0f
                    batch.add(
                        PersonalWifiNetwork(
                            wifiName = if (ssidIdx >= 0) cursor.getString(ssidIdx).orEmpty() else "",
                            macAddress = bssid,
                            latitude = lat,
                            longitude = lon,
                            accuracy = acc,
                            timestamp = if (timeIdx >= 0) cursor.getLong(timeIdx) else System.currentTimeMillis(),
                            isReliable = acc > 0f && acc < 100f,
                            level = -100,
                            measureCount = if (countIdx >= 0) cursor.getInt(countIdx).coerceAtLeast(1) else 1,
                            source = PersonalMapDbHelper.SOURCE_IMPORT_WIFILOC
                        )
                    )
                    if (batch.size >= IMPORT_BATCH_SIZE) {
                        imported += dbHelper.addPersonalRecords(batch)
                            .let { it.inserted + it.updated }
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) {
                    imported += dbHelper.addPersonalRecords(batch)
                        .let { it.inserted + it.updated }
                }
                refreshStats()
                refreshRecords()
                withContext(Dispatchers.Main) { onResult(imported) }
            } catch (e: Exception) {
                Log.e(TAG, "WifiLocTracker import failed", e)
                withContext(Dispatchers.Main) { onResult(-1) }
            } finally {
                cursor?.close()
                externalDb?.close()
                temp.delete()
            }
        }
    }

    fun resetCrossChecks(onDone: () -> Unit = {}) {
        launchBusy {
            setDbLocked(true)
            try {
                try {
                    dbHelper.resetOtherDbStates()
                } catch (e: Exception) {
                    Log.w(TAG, "reset other-db states failed: ${e.message}")
                }
                try {
                    dbHelper.resetWpaSecStates()
                } catch (e: Exception) {
                    Log.w(TAG, "reset wpa-sec states failed: ${e.message}")
                }
                try {
                    dbHelper.backfillMissingVendors()
                } catch (e: Exception) {
                    Log.w(TAG, "vendor backfill failed: ${e.message}")
                }
                prefs.edit { remove(KEY_WPASEC_LAST_RUN) }
                prefs.edit { remove(KEY_WPASEC_LAST_FAIL) }
                try {
                    runFullCrossCheck()
                } catch (e: Exception) {
                    Log.w(TAG, "full cross-check failed: ${e.message}")
                }
                try {
                    runFullWpaSecCheck()
                } catch (e: Exception) {
                    Log.w(TAG, "full wpa-sec check failed: ${e.message}")
                }
            } finally {
                setDbLocked(false)
            }
            refreshStats()
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun runFullCrossCheck() {
        val app = getApplication<Application>()
        val toCheck = dbHelper.getBssidsNeedingCrossCheck(0L, RECHECK_MAX_ROWS)
        if (toCheck.isEmpty()) return
        val found = HashSet<String>()
        var sourceFailed = false
        try {
            val localHelper = LocalAppDbHelper(app)
            try {
                val existing = localHelper.filterExistingMacDecimals(
                    toCheck.mapNotNull { MacAddressUtils.convertToDecimal(it) }
                )
                toCheck.forEach { mac ->
                    val dec = MacAddressUtils.convertToDecimal(mac)
                    if (dec != null && existing.contains(dec)) {
                        found.add(PersonalMapDbHelper.normalizeMacKey(mac))
                    }
                }
            } finally {
                localHelper.close()
            }
        } catch (e: Exception) {
            sourceFailed = true
            Log.w(TAG, "recheck local db failed: ${e.message}")
        }
        try {
            val hsHelper = HandshakeMetadataDbHelper(app)
            try {
                hsHelper.filterExistingBssids(toCheck.toSet()).forEach {
                    found.add(PersonalMapDbHelper.normalizeMacKey(it))
                }
            } finally {
                hsHelper.close()
            }
        } catch (e: Exception) {
            sourceFailed = true
            Log.w(TAG, "recheck handshake db failed: ${e.message}")
        }
        val dbSetup = DbSetupViewModel.getInstance(app)
        try {
            dbSetup.loadDbList()
        } catch (_: Exception) {
        }
        for (db in dbSetup.dbList.value.orEmpty()) {
            try {
                when (db.dbType) {
                    DbType.SQLITE_FILE_P3WIFI,
                    DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                        if (db.path.isBlank()) continue
                        val helper = SQLite3WiFiHelper(app, uriForPath(db.path), db.directPath)
                        try {
                            toCheck.chunked(500).forEach { chunk ->
                                helper.searchNetworksByBSSIDsAsync(chunk).forEach { row ->
                                    (row["BSSID"] as? String)?.let {
                                        found.add(PersonalMapDbHelper.normalizeMacKey(it))
                                    }
                                }
                            }
                        } finally {
                            helper.close()
                        }
                    }
                    DbType.SQLITE_FILE_CUSTOM,
                    DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                        val table = db.tableName
                        val map = db.columnMap
                        if (db.path.isBlank() || table.isNullOrBlank() || map.isNullOrEmpty()) {
                            continue
                        }
                        val helper = SQLiteCustomHelper(app, uriForPath(db.path), db.directPath)
                        try {
                            toCheck.chunked(40).forEach { chunk ->
                                helper.searchNetworksByBSSIDsAll(table, map, chunk).keys.forEach {
                                    found.add(PersonalMapDbHelper.normalizeMacKey(it))
                                }
                            }
                        } finally {
                            helper.close()
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                sourceFailed = true
                Log.w(TAG, "recheck source ${db.id} failed: ${e.message}")
            }
        }
        val now = System.currentTimeMillis()
        val states = HashMap<String, Int>(toCheck.size)
        toCheck.forEach { mac ->
            val present = found.contains(PersonalMapDbHelper.normalizeMacKey(mac))
            when {
                present -> states[mac] = PersonalMapDbHelper.STATE_PRESENT
                !sourceFailed -> states[mac] = PersonalMapDbHelper.STATE_ABSENT
            }
        }
        dbHelper.setOtherDbStates(states, now)
    }

    private suspend fun runFullWpaSecCheck() {
        if (!prefs.getBoolean(PersonalWiFiMapFragment.KEY_WPASEC, true)) return
        val macs = dbHelper.getBssidsNeedingWpaSecCheck(0L, RECHECK_MAX_ROWS)
        if (macs.isEmpty()) return
        val candidates = dbHelper.getWpaSecCandidates(macs.toSet(), 0L)
        if (candidates.isEmpty()) return
        val client = WpaSecClient(getApplication())
        candidates.keys.sorted().chunked(SESSION_WPASEC_CHUNK).forEachIndexed { index, chunk ->
            if (index > 0) delay(SESSION_WPASEC_GAP_MS)
            val queries = chunk.mapNotNull { mac ->
                val ssid = candidates[mac]
                if (ssid.isNullOrBlank()) null
                else Triple(mac, client.bssidToHex(mac), client.essidToHex(ssid))
            }
            if (queries.isEmpty()) return@forEachIndexed
            try {
                val results =
                    client.checkPasswordsBatchDetailed(queries.map { it.second to it.third })
                val checkedAt = System.currentTimeMillis()
                val states = HashMap<String, Int>(queries.size)
                queries.forEachIndexed { qIndex, query ->
                    when (results.getOrNull(qIndex)) {
                        true -> states[query.first] = PersonalMapDbHelper.STATE_PRESENT
                        false -> states[query.first] = PersonalMapDbHelper.STATE_ABSENT
                        else -> {}
                    }
                }
                if (states.isNotEmpty()) dbHelper.setWpaSecStates(states, checkedAt)
            } catch (e: Exception) {
                Log.w(TAG, "recheck wpa-sec chunk failed: ${e.message}")
            }
        }
    }

    fun syncPersonalToInApp(onResult: (Int) -> Unit = {}) {
        launchBusy {
            val app = getApplication<Application>()
            val personalPath = app.getDatabasePath(PersonalMapDbHelper.DATABASE_NAME).absolutePath
            val helper = LocalAppDbHelper(app)
            val count = try {
                setDbLocked(true)
                helper.syncLocationsFromPersonalMap(personalPath)
            } finally {
                helper.close()
                setDbLocked(false)
            }
            withContext(Dispatchers.Main) { onResult(count) }
        }
    }

    fun backupPersonalDatabase(uri: Uri, onResult: (Boolean) -> Unit = {}) {
        launchBusy {
            val app = getApplication<Application>()
            val dbFile = app.getDatabasePath(PersonalMapDbHelper.DATABASE_NAME)
            var ok = false

            setDbLocked(true)
            try {
                val out = app.contentResolver.openOutputStream(uri) ?: return@launchBusy
                out.use {
                    dbHelper.checkpointWal()
                    dbFile.inputStream().use { it.copyTo(out) }
                }
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "Personal DB backup failed", e)
            } finally {
                setDbLocked(false)
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    fun restorePersonalDatabase(uri: Uri, onResult: (Boolean) -> Unit = {}) {
        launchBusy {
            val app = getApplication<Application>()
            val tempFile = File(app.cacheDir, "personal_restore.tmp")
            var ok = false

            setDbLocked(true)
            try {
                app.stopService(Intent(app, WiFiMapScanningService::class.java))
                delay(SERVICE_STOP_DELAY_MS)

                app.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open input")

                val test = SQLiteDatabase.openDatabase(
                    tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                )
                val valid = try {
                    val integrityOk = test.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                    }
                    integrityOk && test.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(PersonalMapDbHelper.TABLE_PERSONAL_MAP)
                    ).use { cursor -> cursor.moveToFirst() }
                } finally {
                    test.close()
                }
                if (!valid) throw IllegalStateException("Invalid database file")

                val imported = dbHelper.replaceAllFromDatabaseFile(tempFile.absolutePath)
                if (imported < 0) throw IllegalStateException("Restore import failed")
                ok = true
            } catch (e: Exception) {
                Log.e(TAG, "Personal DB restore failed", e)
            } finally {
                tempFile.delete()
                setDbLocked(false)
                refreshStats()
                reloadRecords()
                withContext(Dispatchers.Main) { onResult(ok) }
            }
        }
    }

    fun getSecurityBreakdown(callback: (List<Pair<String, Long>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dbHelper.getSecurityBreakdown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load security breakdown: ${e.message}")
                emptyList()
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun getChannelBreakdown(callback: (List<Pair<Int, Long>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                dbHelper.getChannelBreakdown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load channel breakdown: ${e.message}")
                emptyList()
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun getBandBreakdown(callback: (List<Pair<String, Long>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try { dbHelper.getBandBreakdown() } catch (e: Exception) {
                Log.w(TAG, "Failed to load band breakdown: ${e.message}"); emptyList()
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun getVendorTop10(callback: (List<Pair<String, Long>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try { dbHelper.getVendorTop10() } catch (e: Exception) {
                Log.w(TAG, "Failed to load vendor top 10: ${e.message}"); emptyList()
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun getSsidPatternBreakdown(callback: (List<Pair<String, Long>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try { dbHelper.getSsidPatternBreakdown() } catch (e: Exception) {
                Log.w(TAG, "Failed to load SSID patterns: ${e.message}"); emptyList()
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun getNote(bssid: String, callback: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = dbHelper.getNote(bssid)
            withContext(Dispatchers.Main) { callback(note) }
        }
    }

    fun setNote(bssid: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.setNote(bssid, note)
            bumpDataVersionNow()
        }
    }

    fun markCrossPresent(bssid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.setOtherDbStates(
                    mapOf(PersonalMapDbHelper.normalizeMacKey(bssid) to PersonalMapDbHelper.STATE_PRESENT),
                    System.currentTimeMillis()
                )
                refreshStats()
            } catch (e: Exception) {
                Log.w(TAG, "markCrossPresent failed: ${e.message}")
            }
        }
    }

    fun markWpasecPresent(bssid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbHelper.setWpaSecStates(
                    mapOf(PersonalMapDbHelper.normalizeMacKey(bssid) to PersonalMapDbHelper.STATE_PRESENT),
                    System.currentTimeMillis()
                )
                refreshStats()
            } catch (e: Exception) {
                Log.w(TAG, "markWpasecPresent failed: ${e.message}")
            }
        }
    }

    private fun ensureSessionWpasecWorker() {
        synchronized(sessionLock) {
            if (sessionWpasecJob?.isActive == true) return
            sessionWpasecJob = viewModelScope.launch(Dispatchers.IO) { runSessionWpasecLoop() }
        }
    }

    private suspend fun runSessionWpasecLoop() {
        val client = WpaSecClient(getApplication())
        while (true) {
            val macs: List<String>
            synchronized(sessionLock) {
                val size = sessionWpasecQueue.size
                if (size == 0) {
                    sessionWpasecDrainAll = false
                    sessionWpasecWindowStart = 0L
                    sessionWpasecJob = null
                    return
                }
                if (!sessionWpasecDrainAll && size < SESSION_WPASEC_CHUNK) {
                    sessionWpasecJob = null
                    sessionWpasecWindowStart = System.currentTimeMillis()
                    return
                }
                macs = sessionWpasecQueue.keys.take(minOf(size, SESSION_WPASEC_CHUNK))
                macs.forEach { sessionWpasecQueue.remove(it) }
            }
            try {
                val candidates = dbHelper.getWpaSecCandidates(macs.toSet(), SESSION_WPASEC_TTL_MS)
                val queries = candidates.mapNotNull { (mac, ssid) ->
                    if (ssid.isBlank()) null
                    else Triple(mac, client.bssidToHex(mac), client.essidToHex(ssid))
                }
                if (queries.isNotEmpty()) {
                    val results =
                        client.checkPasswordsBatchDetailed(queries.map { it.second to it.third })
                    val checkedAt = System.currentTimeMillis()
                    val states = HashMap<String, Int>(queries.size)
                    queries.forEachIndexed { index, query ->
                        when (results.getOrNull(index)) {
                            true -> states[query.first] = PersonalMapDbHelper.STATE_PRESENT
                            false -> states[query.first] = PersonalMapDbHelper.STATE_ABSENT
                            else -> {}
                        }
                    }
                    if (states.isNotEmpty()) {
                        dbHelper.setWpaSecStates(states, checkedAt)
                        refreshStats()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "session wpa-sec check failed: ${e.message}")
            }
            synchronized(sessionLock) {
                if (sessionWpasecQueue.isEmpty()) {
                    sessionWpasecDrainAll = false
                    sessionWpasecWindowStart = 0L
                    sessionWpasecJob = null
                    return
                }
            }
            delay(SESSION_WPASEC_GAP_MS)
        }
    }

    fun getTags(bssid: String, callback: (List<Pair<String, Int>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tags = dbHelper.getTags(bssid)
            withContext(Dispatchers.Main) { callback(tags) }
        }
    }

    fun addTag(bssid: String, tag: String, color: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.addTag(bssid, tag, color)
            bumpDataVersionNow()
        }
    }

    fun removeTag(bssid: String, tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.removeTag(bssid, tag)
            bumpDataVersionNow()
        }
    }

    fun getAllTags(callback: (List<Pair<String, Int>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tags = dbHelper.getAllTags()
            withContext(Dispatchers.Main) { callback(tags) }
        }
    }


    fun checkLocalDatabasesForPoint(
        bssid: String,
        ssid: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val normalized = PersonalMapDbHelper.normalizeMacKey(bssid)
            var found = false
            var message = ""

            try {
                if (!found) {
                    val localHelper = LocalAppDbHelper(app)
                    try {
                        val exists = localHelper.readableDatabase.rawQuery(
                            "SELECT 1 FROM ${LocalAppDbHelper.TABLE_NAME} WHERE ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} = ? LIMIT 1",
                            arrayOf(normalized)
                        ).use { it.moveToFirst() }
                        if (exists) {
                            found = true
                            message = app.getString(R.string.pm_found_in_local_db)
                        }
                    } catch (_: Exception) {}
                    localHelper.close()
                }

                if (!found) {
                    val dbSetup = com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel.getInstance(app)
                    val availableDbs = dbSetup.dbList.value.orEmpty()

                    for (db in availableDbs) {
                        if (db.path.isNullOrBlank()) continue
                        if (db.type != "SQLITE_FILE_P3WIFI" && db.type != "SMARTLINK_SQLITE_FILE_P3WIFI") continue
                        try {
                            val dbUri = android.net.Uri.fromFile(java.io.File(db.path!!))
                            val helper = com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper(app, dbUri, db.path!!)
                            val results = helper.use { h ->
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    h.searchNetworksByBSSIDsAsync(listOf(normalized))
                                }
                            }
                            if (results.isNotEmpty()) {
                                found = true
                                message = app.getString(R.string.pm_found_in_db, db.id)
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (!found) {
                    message = app.getString(R.string.pm_not_found_in_dbs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local DB check failed: ${e.message}")
                message = e.message ?: "Error"
            }

            withContext(Dispatchers.Main) { onResult(found, message) }
        }
    }

    fun findMatchesForPoint(
        bssid: String,
        onResult: (List<DbMatchEntry>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val matches = mutableListOf<DbMatchEntry>()
            val normalized = PersonalMapDbHelper.normalizeMacKey(bssid)
            val bare = normalized.replace(":", "").replace("-", "")
            val shownP3 = setOf(
                "BSSID", "bssid", "ESSID", "ssid", "SSID",
                "password", "Password", "key", "Key", "WiFiKey", "wifiKey",
                "WPS", "wps", "wpsPin", "WPSPIN", "wpspin"
            )
            try {
                val localHelper = LocalAppDbHelper(app)
                try {
                    localHelper.readableDatabase.rawQuery(
                        "SELECT ${LocalAppDbHelper.COLUMN_WIFI_NAME}, " +
                                "${LocalAppDbHelper.COLUMN_MAC_ADDRESS}, " +
                                "${LocalAppDbHelper.COLUMN_WIFI_PASSWORD}, " +
                                "${LocalAppDbHelper.COLUMN_WPS_CODE} " +
                                "FROM ${LocalAppDbHelper.TABLE_NAME} " +
                                "WHERE REPLACE(REPLACE(UPPER(${LocalAppDbHelper.COLUMN_MAC_ADDRESS}), ':', ''), '-', '') = ?",
                        arrayOf(bare.uppercase())
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            matches.add(
                                DbMatchEntry(
                                    source = app.getString(R.string.pm_match_source_local),
                                    ssid = cursor.getString(0).orEmpty(),
                                    bssid = cursor.getString(1).orEmpty(),
                                    password = cursor.getString(2)?.takeIf { it.isNotBlank() },
                                    wps = cursor.getString(3)?.takeIf { it.isNotBlank() },
                                    databaseId = "local",
                                    databaseName = app.getString(R.string.local_database),
                                    color = colorForSource("local")
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                }
                localHelper.close()

                val dbSetup = DbSetupViewModel.getInstance(app)
                try {
                    dbSetup.loadDbList()
                } catch (_: Exception) {
                }

                for (db in dbSetup.dbList.value.orEmpty()) {
                    try {
                        when (db.dbType) {
                            DbType.SQLITE_FILE_P3WIFI,
                            DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                                if (db.path.isBlank()) continue
                                val helper = SQLite3WiFiHelper(
                                    app,
                                    uriForPath(db.path),
                                    db.directPath
                                )
                                try {
                                    val results = helper.searchNetworksByBSSIDsAsync(
                                        listOf(normalized)
                                    )
                                    for (row in results) {
                                        val rowSsid = (row["ESSID"] ?: row["ssid"] ?: row["SSID"])
                                            ?.toString().orEmpty()
                                        val rowBssid = (row["BSSID"] ?: row["bssid"])
                                            ?.toString().orEmpty()
                                        val rowPass = (row["WiFiKey"]
                                        ?: row["password"] ?: row["Password"]
                                        ?: row["key"] ?: row["Key"])
                                            ?.toString()?.takeIf {
                                                it.isNotBlank() && it != "0" && it != "-"
                                            }
                                        val rowWps = (row["WPSPIN"] ?: row["wpspin"]
                                        ?: row["WPS"] ?: row["wps"] ?: row["wpsPin"])
                                            ?.toString()?.takeIf {
                                                it.isNotBlank() && it != "0" && it != "-"
                                            }
                                        val extra = row.entries
                                            .filter { (k, v) ->
                                                k !in shownP3 && v != null &&
                                                    v.toString().isNotBlank()
                                            }
                                            .joinToString("\n") { (k, v) -> "$k: $v" }
                                            .take(EXTRA_MATCH_MAX)
                                        matches.add(
                                            DbMatchEntry(
                                                source = databaseLabel(db),
                                                ssid = rowSsid,
                                                bssid = rowBssid,
                                                password = rowPass,
                                                wps = rowWps,
                                                extra = extra,
                                                databaseId = db.id,
                                                databaseName = databaseLabel(db),
                                                color = colorForSource(db.id)
                                            )
                                        )
                                    }
                                } finally {
                                    helper.close()
                                }
                            }

                            DbType.SQLITE_FILE_CUSTOM,
                            DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                                val table = db.tableName
                                val map = db.columnMap
                                if (db.path.isBlank() || table.isNullOrBlank() || map.isNullOrEmpty()) {
                                    continue
                                }
                                val helper = SQLiteCustomHelper(
                                    app,
                                    uriForPath(db.path),
                                    db.directPath
                                )
                                try {
                                    val results = helper.searchNetworksByBSSIDsAll(
                                        table,
                                        map,
                                        listOf(normalized)
                                    )
                                    for ((_key, rows) in results) {
                                        for (row in rows) {
                                            val essid = map["essid"]?.let { row[it]?.toString() }
                                                .orEmpty()
                                            val password = map["wifi_pass"]?.let { row[it]?.toString() }
                                                ?.takeIf { it.isNotBlank() }
                                            val wps = map["wps_pin"]?.let { row[it]?.toString() }
                                                ?.takeIf { it.isNotBlank() }
                                            matches.add(
                                                DbMatchEntry(
                                                    source = databaseLabel(db),
                                                    ssid = essid,
                                                    bssid = normalized,
                                                    password = password,
                                                    wps = wps,
                                                    databaseId = db.id,
                                                    databaseName = databaseLabel(db),
                                                    color = colorForSource(db.id)
                                                )
                                            )
                                        }
                                    }
                                } finally {
                                    helper.close()
                                }
                            }

                            DbType.HANDSHAKE_STORAGE -> {
                                val helper = HandshakeMetadataDbHelper(app)
                                try {
                                    for (item in helper.getByBssid(normalized)) {
                                        matches.add(
                                            DbMatchEntry(
                                                source = databaseLabel(db),
                                                ssid = item.essid.orEmpty(),
                                                bssid = item.bssid.orEmpty(),
                                                password = item.crackedPassword
                                                    ?.takeIf { it.isNotBlank() },
                                                databaseId = db.id,
                                                databaseName = databaseLabel(db),
                                                color = colorForSource(db.id)
                                            )
                                        )
                                    }
                                } finally {
                                    helper.close()
                                }
                            }

                            else -> Unit
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Find matches failed: ${e.message}")
            }
            withContext(Dispatchers.Main) { onResult(matches) }
        }
    }

    private fun uriForPath(path: String): Uri =
        if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))

    private fun databaseLabel(db: DbItem): String {
        val app = getApplication<Application>()
        return when (db.dbType) {
            DbType.LOCAL_APP_DB -> app.getString(R.string.local_database)
            DbType.HANDSHAKE_STORAGE -> app.getString(R.string.handshake_storage)
            DbType.PERSONAL_WIFI_MAP -> app.getString(R.string.personal_map_title)
            else -> db.path.substringAfterLast('/').ifBlank { db.id }
        }
    }

    private fun colorForSource(source: String): Int {
        val key = source.ifBlank { "unknown" }
        val hash = key.hashCode()
        val index = (if (hash < 0) -hash else hash) % MATCH_COLORS.size
        return MATCH_COLORS[index]
    }

    fun uploadWardrivingTo3WifiApp(onResult: (com.lsd.wififrankenstein.network.ThreeWifiAppUploader.UploadReport) -> Unit) {
        launchBusy {
            val app = getApplication<Application>()
            val report = try {
                val server =
                    com.lsd.wififrankenstein.network.ThreeWifiAppSession.authenticatedServer(app)
                if (server == null) {
                    com.lsd.wififrankenstein.network.ThreeWifiAppUploader.UploadReport(
                        0,
                        0,
                        app.getString(R.string.pm_no_api_server)
                    )
                } else {
                    val token =
                        com.lsd.wififrankenstein.network.ThreeWifiAppSession.currentToken(app, server)
                    if (token == null) {
                        com.lsd.wififrankenstein.network.ThreeWifiAppUploader.UploadReport(
                            0,
                            0,
                            app.getString(R.string.api3_error_auth_required)
                        )
                    } else {
                        val networks = dbHelper.getPersonalRecords()
                        com.lsd.wififrankenstein.network.ThreeWifiAppUploader.uploadWardriving(
                            app,
                            server,
                            token,
                            networks
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "3wifi.app wardriving upload failed: ${e.message}")
                com.lsd.wififrankenstein.network.ThreeWifiAppUploader.UploadReport(
                    0,
                    0,
                    e.message ?: "Error"
                )
            }
            withContext(Dispatchers.Main) { onResult(report) }
        }
    }

    fun check3WifiApiForPoint(
        bssid: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            var found = false
            var message = ""
            try {
                val dbSetup = com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel.getInstance(app)
                val servers = dbSetup.getWifiApiDatabases()
                if (servers.isEmpty()) {
                    message = app.getString(R.string.pm_no_api_server)
                } else {
                    val server = servers.first()
                    val readKey = server.apiReadKey ?: server.apiKey ?: "000000000000"
                    val network = com.lsd.wififrankenstein.ui.api3wifi.API3WiFiNetwork(
                        context = app,
                        serverUrl = server.path,
                        connectTimeout = 5000,
                        readTimeout = 10000,
                        ignoreSSL = false,
                        includeAppIdentifier = true,
                        apiReadKey = readKey,
                        apiWriteKey = server.apiWriteKey
                    )
                    val response = network.executeRequest(
                        com.lsd.wififrankenstein.ui.api3wifi.API3WiFiRequest.ApiQuery(
                            key = readKey,
                            bssidList = listOf(bssid.uppercase()),
                            essidList = null,
                            sens = false
                        ),
                        com.lsd.wififrankenstein.ui.api3wifi.API3WiFiViewModel.RequestType.GET
                    )
                    found = response.contains(bssid, ignoreCase = true) &&
                            !response.contains("\"result\":false") &&
                            !response.contains("\"success\":false")
                    message = response.take(4000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "3WiFi API check failed: ${e.message}")
                message = e.message ?: "Error"
            }
            withContext(Dispatchers.Main) { onResult(found, message) }
        }
    }

    fun checkWpaSecForPoint(
        bssid: String,
        ssid: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var found = false
            var message = ""
            try {
                if (ssid.isBlank()) {
                    message = getApplication<Application>().getString(R.string.pm_need_ssid)
                } else {
                    val helper = com.lsd.wififrankenstein.ui.api3wifi.WpaSecHelper()
                    val check = helper.checkBssidSsid(bssid, ssid)
                    if (check.error != null) {
                        message = check.error!!
                    } else if (check.isLeaked) {
                        found = true
                        message = getApplication<Application>().getString(R.string.pm_password_found)
                    } else {
                        message = getApplication<Application>().getString(R.string.pm_password_not_found)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "wpa-sec check failed: ${e.message}")
                message = e.message ?: "Error"
            }
            withContext(Dispatchers.Main) { onResult(found, message) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(serviceReceiver)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            withTimeoutOrNull(500L) { delay(500L) }
            try {
                dbHelper.close()
            } catch (e: Exception) {
                Log.w(TAG, "dbHelper.close() in onCleared: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PersonalWiFiMapVM"
        private const val KEY_WIGLE_API = "wigle_api"
        private const val KEY_WPASEC_LAST_RUN = "personal_wpasec_last_run"
        private const val KEY_WPASEC_LAST_FAIL = "personal_wpasec_last_fail"
        private const val MAX_SESSION_LOG = 100
        private const val MAX_SESSION_NETWORKS = 10_000
        private const val SESSION_WPASEC_CHUNK = 300
        private const val SESSION_WPASEC_WINDOW_MS = 3L * 60 * 1000
        private const val SESSION_WPASEC_GAP_MS = 10_000L
        private const val SESSION_WPASEC_TTL_MS = 24L * 60 * 60 * 1000
        private const val RECHECK_MAX_ROWS = 20_000
        private const val MAX_RECORDS_LOADED = 2000
        private const val MAX_RECORDS_IN_MEMORY = 2000
        private const val IMPORT_BATCH_SIZE = 500
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATS_REFRESH_THROTTLE_MS = 60_000L
        private const val DATA_VERSION_THROTTLE_MS = 5_000L
        private const val SERVICE_STOP_DELAY_MS = 1_000L
        private const val EXTRA_MATCH_MAX = 4000

        private val MATCH_COLORS = listOf(
            Color.rgb(65, 105, 225),
            Color.rgb(34, 139, 34),
            Color.rgb(220, 20, 60),
            Color.rgb(138, 43, 226),
            Color.rgb(218, 165, 32),
            Color.rgb(32, 178, 170),
            Color.rgb(255, 140, 0),
            Color.rgb(199, 21, 133)
        )

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        const val ARG_SELECT_PERSONAL_MAP = "select_personal_map"
        const val ARG_PERSONAL_MODE = "personal_mode"
    }
}
