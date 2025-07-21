package com.lsd.wififrankenstein.ui.inappdatabase

import android.app.Application
import android.net.Uri
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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
            val dbHelper = LocalAppDbHelper(getApplication())
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
            val dbHelper = LocalAppDbHelper(getApplication())
            dbHelper.addRecord(record)
            updateStats()
        }
    }

    fun updateRecord(record: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = LocalAppDbHelper(getApplication())
            dbHelper.updateRecord(record)
            updateStats()
        }
    }

    fun deleteRecord(record: WifiNetwork) {
        viewModelScope.launch(Dispatchers.IO) {
            val dbHelper = LocalAppDbHelper(getApplication())
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
                val records = dbHelper.getAllRecords()
                val json = kotlinx.serialization.json.Json.encodeToString(records)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun exportToCsv(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val records = dbHelper.getAllRecords()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val writer = outputStream.writer()
                    writer.write("ID,WiFi Name,MAC Address,Password,WPS PIN,Admin Panel,Latitude,Longitude\n")
                    records.forEach { record ->
                        writer.write("${record.id},${record.wifiName},${record.macAddress},${record.wifiPassword ?: ""},${record.wpsCode ?: ""},${record.adminPanel ?: ""},${record.latitude ?: ""},${record.longitude ?: ""}\n")
                    }
                    writer.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importFromJson(uri: Uri, importType: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (importType == "replace") {
                    val dbHelper = LocalAppDbHelper(getApplication())
                    dbHelper.clearDatabase()
                }

                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.reader().readText()
                    val records = Json.decodeFromString<List<WifiNetwork>>(json)
                    val dbHelper = LocalAppDbHelper(getApplication())

                    if (importType == "append_check_duplicates") {
                        val stats = dbHelper.importRecordsWithStats(records, importType)
                        Log.d("Import", "Processed: ${stats.totalProcessed}, Inserted: ${stats.inserted}, Duplicates: ${stats.duplicates}")
                    } else {
                        dbHelper.importRecords(records)
                    }
                    updateStats()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun importFromCsv(uri: Uri, importType: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (importType == "replace") {
                    val dbHelper = LocalAppDbHelper(getApplication())
                    dbHelper.clearDatabase()
                }

                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.reader().readLines()
                    val records = lines.drop(1).mapNotNull { line ->
                        val parts = line.split(",")
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

                    val dbHelper = LocalAppDbHelper(getApplication())
                    if (importType == "append_check_duplicates") {
                        val stats = dbHelper.importRecordsWithStats(records, importType)
                        Log.d("Import", "Processed: ${stats.totalProcessed}, Inserted: ${stats.inserted}, Duplicates: ${stats.duplicates}")
                    } else {
                        dbHelper.importRecords(records)
                    }
                    updateStats()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }


    fun importFromRouterScan(uri: Uri, importType: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (importType == "replace") {
                    val dbHelper = LocalAppDbHelper(getApplication())
                    dbHelper.clearDatabase()
                }

                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val lines = inputStream.reader().readLines()
                    val records = lines.mapNotNull { line ->
                        parseRouterScanLine(line)
                    }

                    val dbHelper = LocalAppDbHelper(getApplication())
                    if (importType == "append_check_duplicates") {
                        val stats = dbHelper.importRecordsWithStats(records, importType)
                        Log.d("Import", "Processed: ${stats.totalProcessed}, Inserted: ${stats.inserted}, Duplicates: ${stats.duplicates}")
                    } else {
                        dbHelper.importRecords(records)
                    }
                    updateStats()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun parseRouterScanLine(line: String): WifiNetwork? {
        if (line.trim().isEmpty() || line.startsWith("#")) return null

        val parts = line.split("\t")
        if (parts.size < 9) return null

        return try {
            WifiNetwork(
                id = 0,
                wifiName = if (parts.size > 9) parts[9].trim() else "",
                macAddress = if (parts.size > 8) parts[8].trim() else "",
                wifiPassword = if (parts.size > 11) parts[11].trim().takeIf { it.isNotEmpty() && it != "0" && it != "-" } else null,
                wpsCode = if (parts.size > 12) parts[12].trim().takeIf { it.isNotEmpty() && it != "0" && it != "-" } else null,
                adminPanel = if (parts.size > 4) parts[4].trim().takeIf { it.isNotEmpty() && it != ":" && it != "-" } else null,
                latitude = null,
                longitude = null
            )
        } catch (e: Exception) {
            null
        }
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
                e.printStackTrace()
            }
        }
    }

    fun restoreDatabaseFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.restoreDatabaseFromUri(uri)
            updateStats()
        }
    }

    fun showRecordDetails(record: WifiNetwork) {
    }

    data class SearchParams(
        val query: String,
        val filterName: Boolean,
        val filterMac: Boolean,
        val filterPassword: Boolean,
        val filterWps: Boolean
    )
}