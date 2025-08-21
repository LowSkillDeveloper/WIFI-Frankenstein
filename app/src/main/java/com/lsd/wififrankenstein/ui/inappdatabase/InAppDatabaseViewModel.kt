package com.lsd.wififrankenstein.ui.inappdatabase

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class InAppDatabaseViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)

    private val _databaseStats = MutableLiveData<String>()
    val databaseStats: LiveData<String> = _databaseStats

    private val _indexStatus = MutableLiveData<String>()
    val indexStatus: LiveData<String> = _indexStatus

    private val searchParameters = MutableStateFlow(SearchParams("", true, true, false, false))

    val records: Flow<PagingData<WifiNetwork>> = searchParameters
        .flatMapLatest { params ->
            Pager(
                config = PagingConfig(pageSize = 50, enablePlaceholders = false),
                pagingSourceFactory = {
                    DatabaseRecordsPagingSource(dbHelper, params)
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    init {
        updateStats()
    }

    fun updateSearch(query: String, filterName: Boolean, filterMac: Boolean, filterPassword: Boolean, filterWps: Boolean) {
        searchParameters.value = SearchParams(query, filterName, filterMac, filterPassword, filterWps)
    }

    fun updateStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val recordCount = dbHelper.getRecordsCount()
            val dbSize = getDatabaseSize()
            val indexLevel = dbHelper.getIndexLevel()

            val statsText = getApplication<Application>().getString(
                R.string.database_stats_format,
                recordCount,
                dbSize
            )

            withContext(Dispatchers.Main) {
                _databaseStats.value = statsText
                _indexStatus.value = indexLevel
            }
        }
    }

    private fun getDatabaseSize(): String {
        val dbFile = getApplication<Application>().getDatabasePath(LocalAppDbHelper.DATABASE_NAME)
        val bytes = dbFile.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun isIndexingEnabled(): Boolean {
        return dbHelper.hasIndexes()
    }

    fun enableIndexing(level: String = "BASIC") {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.enableIndexing(level)
            updateStats()
        }
    }

    fun disableIndexing() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.disableIndexing()
            updateStats()
        }
    }

    fun optimizeDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.optimizeDatabase()
            updateStats()
        }
    }

    fun removeDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.removeDuplicates()
            updateStats()
        }
    }

    fun addRecord(record: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.addRecord(record)
            updateStats()
        }
    }

    fun updateRecord(record: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.updateRecord(record)
            updateStats()
        }
    }

    fun deleteRecord(record: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.deleteRecord(record.id)
            updateStats()
        }
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.clearDatabase()
            updateStats()
        }
    }

    fun exportToJson(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localDbHelper = LocalAppDbHelper(getApplication())
                val records = localDbHelper.getAllRecords()
                val json = Json.encodeToString(records)

                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.writer().use { writer ->
                        writer.write(json)
                    }
                }
                localDbHelper.close()
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error exporting to JSON", e)
            }
        }
    }

    fun exportToCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localDbHelper = LocalAppDbHelper(getApplication())
                val records = localDbHelper.getAllRecords()

                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.writer().use { writer ->
                        writer.write("ID,WiFi Name,MAC Address,Password,WPS PIN,Admin Panel,Latitude,Longitude\n")
                        records.forEach { record ->
                            val line = "${record.id}," +
                                    "\"${record.wifiName.replace("\"", "\"\"")}\"," +
                                    "\"${record.macAddress}\"," +
                                    "\"${record.wifiPassword?.replace("\"", "\"\"") ?: ""}\"," +
                                    "\"${record.wpsCode ?: ""}\"," +
                                    "\"${record.adminPanel?.replace("\"", "\"\"") ?: ""}\"," +
                                    "${record.latitude ?: ""}," +
                                    "${record.longitude ?: ""}\n"
                            writer.write(line)
                        }
                    }
                }
                localDbHelper.close()
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error exporting to CSV", e)
            }
        }
    }

    fun importFromJson(uri: Uri, importType: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localDbHelper = LocalAppDbHelper(getApplication())

                if (importType == "replace") {
                    localDbHelper.clearDatabase()
                }

                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.reader().readText()
                    val records = Json.decodeFromString<List<WifiNetwork>>(json)

                    val stats = importRecordsWithStats(records, importType) { _, _ -> }
                    Log.d("InAppDatabaseViewModel", "JSON Import - Processed: ${stats.totalProcessed}, Inserted: ${stats.inserted}, Duplicates: ${stats.duplicates}")
                    updateStats()
                }
                localDbHelper.close()
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error importing from JSON", e)
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun importFromCsv(uri: Uri, importType: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localDbHelper = LocalAppDbHelper(getApplication())

                if (importType == "replace") {
                    localDbHelper.clearDatabase()
                }

                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.reader().readLines()
                    val records = lines.drop(1).mapNotNull { line ->
                        val parts = parseCsvLine(line)
                        if (parts.size >= 8) {
                            WifiNetwork(
                                id = parts[0].toLongOrNull() ?: 0,
                                wifiName = parts[1],
                                macAddress = parts[2],
                                wifiPassword = parts[3].takeIf { it.isNotEmpty() },
                                wpsCode = parts[4].takeIf { it.isNotEmpty() },
                                adminPanel = parts[5].takeIf { it.isNotEmpty() },
                                latitude = parts[6].toDoubleOrNull(),
                                longitude = parts[7].toDoubleOrNull()
                            )
                        } else null
                    }

                    val stats = importRecordsWithStats(records, importType) { _, _ -> }
                    Log.d("InAppDatabaseViewModel", "CSV Import - Processed: ${stats.totalProcessed}, Inserted: ${stats.inserted}, Duplicates: ${stats.duplicates}")
                    updateStats()
                }
                localDbHelper.close()
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error importing from CSV", e)
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    suspend fun importFromRouterScanWithProgress(
        uri: Uri,
        importType: String,
        progressCallback: (String, Int) -> Unit
    ): LocalAppDbHelper.ImportStats = withContext(Dispatchers.IO) {
        val localDbHelper = LocalAppDbHelper(getApplication())

        try {
            if (importType == "replace") {
                progressCallback("Clearing database…", 5)
                localDbHelper.clearDatabase()
            }

            val networksToAdd = mutableListOf<WifiNetwork>()
            var processedLines = 0
            var totalLines = 0

            progressCallback("Analyzing file…", 2)

            getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { _ -> totalLines++ }
            }

            progressCallback("Parsing $totalLines lines…", 5)

            val parseJobs = mutableListOf<kotlinx.coroutines.Deferred<List<WifiNetwork>>>()
            val chunkSize = 10000

            getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val allLines = reader.readLines()

                allLines.chunked(chunkSize).forEachIndexed { chunkIndex, linesChunk ->
                    val job = async {
                        val chunkNetworks = mutableListOf<WifiNetwork>()
                        linesChunk.forEach { line ->
                            if (!isActive) return@forEach
                            parseRouterScanLine(line)?.let { network ->
                                chunkNetworks.add(network)
                            }
                        }

                        processedLines += linesChunk.size
                        val progress = 5 + (processedLines * 10) / totalLines
                        progressCallback("Parsing: $processedLines/$totalLines", progress)

                        chunkNetworks
                    }
                    parseJobs.add(job)
                }
            }

            parseJobs.awaitAll().forEach { chunk ->
                networksToAdd.addAll(chunk)
            }

            progressCallback("Found ${networksToAdd.size} records for import", 15)

            val stats = importRecordsWithStatsLocal(networksToAdd, importType, progressCallback, localDbHelper)
            updateStats()

            localDbHelper.close()
            return@withContext stats

        } catch (e: Exception) {
            Log.e("InAppDatabaseViewModel", "RouterScan import error", e)
            localDbHelper.close()
            throw e
        }
    }

    private suspend fun importRecordsWithStatsLocal(
        records: List<WifiNetwork>,
        importType: String,
        progressCallback: (String, Int) -> Unit,
        localDbHelper: LocalAppDbHelper
    ): LocalAppDbHelper.ImportStats = withContext(Dispatchers.IO) {
        try {
            val checkDuplicates = importType == "append_check_duplicates"
            val shouldOptimize = records.size > 5000

            if (shouldOptimize) {
                progressCallback("Optimizing database…", 20)
                localDbHelper.temporaryDropIndexes()
            }

            val result = if (checkDuplicates && records.size > 1000) {
                processLargeImportWithDuplicateCheckLocal(records, progressCallback, localDbHelper)
            } else {
                progressCallback("Importing records…", 50)
                localDbHelper.bulkInsertOptimized(records, checkDuplicates)
            }

            if (shouldOptimize) {
                progressCallback("Restoring indexes…", 90)
                localDbHelper.recreateIndexes()
            }

            return@withContext LocalAppDbHelper.ImportStats(
                totalProcessed = records.size,
                inserted = result.first,
                duplicates = result.second
            )
        } catch (e: Exception) {
            Log.e("InAppDatabaseViewModel", "Error in import", e)
            return@withContext LocalAppDbHelper.ImportStats(
                totalProcessed = records.size,
                inserted = 0,
                duplicates = 0
            )
        }
    }
    private suspend fun importRecordsWithStats(
        records: List<WifiNetwork>,
        importType: String,
        progressCallback: (String, Int) -> Unit
    ): LocalAppDbHelper.ImportStats = withContext(Dispatchers.IO) {
        val localDbHelper = LocalAppDbHelper(getApplication())
        try {
            val result = importRecordsWithStatsLocal(records, importType, progressCallback, localDbHelper)
            localDbHelper.close()
            return@withContext result
        } catch (e: Exception) {
            localDbHelper.close()
            throw e
        }
    }

    private suspend fun processLargeImportWithDuplicateCheckLocal(
        records: List<WifiNetwork>,
        progressCallback: (String, Int) -> Unit,
        localDbHelper: LocalAppDbHelper
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {

        progressCallback("Loading existing records…", 25)
        val existingKeys = Collections.synchronizedSet(localDbHelper.getAllExistingKeys())

        progressCallback("Processing ${records.size} records…", 30)

        val chunkSize = 5000
        val chunks = records.chunked(chunkSize)
        val insertedCounter = AtomicInteger(0)
        val duplicatesCounter = AtomicInteger(0)

        val jobs = chunks.mapIndexed { index, chunk ->
            async {
                if (!isActive) return@async

                val progress = 30 + (index * 50) / chunks.size
                progressCallback("Processing chunk ${index + 1}/${chunks.size}…", progress)

                val uniqueNetworks = chunk.filter { network ->
                    val key = "${network.wifiName}|${network.macAddress}"
                    if (existingKeys.add(key)) {
                        true
                    } else {
                        duplicatesCounter.incrementAndGet()
                        false
                    }
                }

                if (uniqueNetworks.isNotEmpty()) {
                    val inserted = localDbHelper.bulkInsertBatch(uniqueNetworks)
                    insertedCounter.addAndGet(inserted)
                }
            }
        }

        jobs.awaitAll()

        return@withContext Pair(insertedCounter.get(), duplicatesCounter.get())
    }
    private suspend fun processLargeImportWithDuplicateCheck(
        records: List<WifiNetwork>,
        progressCallback: (String, Int) -> Unit
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val localDbHelper = LocalAppDbHelper(getApplication())
        try {
            val result = processLargeImportWithDuplicateCheckLocal(records, progressCallback, localDbHelper)
            localDbHelper.close()
            return@withContext result
        } catch (e: Exception) {
            localDbHelper.close()
            throw e
        }
    }

    private fun parseRouterScanLine(line: String): WifiNetwork? {
        if (line.trim().isEmpty() || line.startsWith("#")) return null

        val parts = line.split("\t")

        if (parts.size >= 9) {
            try {
                val bssid = if (parts.size > 8) parts[8].trim() else ""
                val essid = if (parts.size > 9) parts[9].trim() else ""
                val wifiKey = if (parts.size > 11) parts[11].trim() else ""
                val wpsPin = if (parts.size > 12) parts[12].trim() else ""
                val adminCredentials = if (parts.size > 4) parts[4].trim() else ""

                var latitude: Double? = null
                var longitude: Double? = null

                if (parts.size >= 14) {
                    try {
                        for (i in (parts.size - 5) until (parts.size - 1)) {
                            if (i >= 0 && i + 1 < parts.size) {
                                val latStr = parts[i].trim()
                                val lonStr = parts[i + 1].trim()

                                if (latStr.matches(Regex("^\\d{1,2}\\.\\d+$")) &&
                                    lonStr.matches(Regex("^\\d{1,3}\\.\\d+$"))) {
                                    val lat = latStr.toDoubleOrNull()
                                    val lon = lonStr.toDoubleOrNull()

                                    if (lat != null && lon != null &&
                                        lat >= -90.0 && lat <= 90.0 &&
                                        lon >= -180.0 && lon <= 180.0 &&
                                        lat != 0.0 && lon != 0.0) {
                                        latitude = lat
                                        longitude = lon
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }

                if (essid.isNotEmpty() || bssid.isNotEmpty()) {
                    val cleanBssid = bssid.uppercase().replace("-", ":").trim()
                    val cleanEssid = essid.trim()
                    val cleanWifiKey = wifiKey.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length > 1 }
                    val cleanWpsPin = wpsPin.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length >= 8 }
                    val cleanAdminPanel = adminCredentials.takeIf {
                        it.isNotEmpty() && it != ":" && it != "-" && !it.contains("0.0.0.0") && it.contains(":")
                    }

                    if (cleanBssid.isNotEmpty()) {
                        if (!cleanBssid.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
                            return null
                        }
                    }

                    if (cleanEssid.isNotEmpty() || cleanBssid.isNotEmpty()) {
                        return WifiNetwork(
                            id = 0,
                            wifiName = cleanEssid,
                            macAddress = cleanBssid,
                            wifiPassword = cleanWifiKey,
                            wpsCode = cleanWpsPin,
                            adminPanel = cleanAdminPanel,
                            latitude = latitude,
                            longitude = longitude
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d("InAppDatabaseViewModel", "Error parsing line: $line", e)
            }
        }

        return null
    }

    fun exportDatabase(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dbFile = getApplication<Application>().getDatabasePath(LocalAppDbHelper.DATABASE_NAME)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    dbFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error exporting database", e)
            }
        }
    }

    fun restoreDatabaseFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val localDbHelper = LocalAppDbHelper(getApplication())
                localDbHelper.restoreDatabaseFromUri(uri)
                localDbHelper.close()
                updateStats()
            } catch (e: Exception) {
                Log.e("InAppDatabaseViewModel", "Error restoring database", e)
            }
        }
    }

    fun showRecordDetails(record: WifiNetwork) {
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var currentField = StringBuilder()
        var insideQuotes = false
        var i = 0

        while (i < line.length) {
            val char = line[i]

            when {
                char == '"' -> {
                    if (insideQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        insideQuotes = !insideQuotes
                    }
                }
                char == ',' && !insideQuotes -> {
                    result.add(currentField.toString())
                    currentField = StringBuilder()
                }
                else -> {
                    currentField.append(char)
                }
            }
            i++
        }

        result.add(currentField.toString())
        return result
    }

    data class SearchParams(
        val query: String,
        val filterName: Boolean,
        val filterMac: Boolean,
        val filterPassword: Boolean,
        val filterWps: Boolean
    )
}