package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.app.Application
import android.content.Context
import android.net.Uri
import com.lsd.wififrankenstein.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class LocalAppDbViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LocalAppDbHelper(application)

    private val _isIndexingEnabled = MutableLiveData<Boolean>()
    val isIndexingEnabled: LiveData<Boolean> = _isIndexingEnabled

    init {
        checkIndexingStatus()
    }

    private fun checkIndexingStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val indexesExist = dbHelper.hasIndexes()
            _isIndexingEnabled.postValue(indexesExist)
        }
    }

    fun getIndexingLevel(): String {
        val dbHelper = LocalAppDbHelper(getApplication())
        return dbHelper.getIndexLevel()
    }

    fun getAllRecords(): List<WifiNetwork> {
        return dbHelper.getAllRecords()
    }

    fun clearDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.clearDatabase()
        }
    }

    fun importRecords(records: List<WifiNetwork>) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.importRecords(records)
        }
    }

    fun toggleIndexing(enable: Boolean, level: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                val indexLevel = level ?: getSavedIndexLevel()
                dbHelper.enableIndexing(indexLevel)
                saveIndexLevel(indexLevel)
            } else {
                dbHelper.disableIndexing()
                clearSavedIndexLevel()
            }
            _isIndexingEnabled.postValue(enable)
        }
    }

    suspend fun importRecordsWithStats(
        records: List<WifiNetwork>,
        importType: String,
        progressCallback: ((String, Int) -> Unit)? = null
    ): LocalAppDbHelper.ImportStats = withContext(Dispatchers.IO) {
        try {
            val checkDuplicates = importType == "append_check_duplicates"

            when (importType) {
                "replace" -> {
                    progressCallback?.invoke("Очистка базы данных...", 0)
                    dbHelper.clearDatabase()
                }
            }

            val shouldOptimize = records.size > 5000

            if (shouldOptimize) {
                progressCallback?.invoke("Оптимизация базы данных...", 10)
                dbHelper.temporaryDropIndexes()
            }

            val result = if (checkDuplicates && records.size > 1000) {
                processLargeImportWithDuplicateCheck(records, progressCallback)
            } else {
                progressCallback?.invoke("Импорт записей...", 50)
                dbHelper.bulkInsertOptimized(records, checkDuplicates)
            }

            if (shouldOptimize) {
                progressCallback?.invoke("Восстановление индексов...", 90)
                dbHelper.recreateIndexes()
            }

            return@withContext LocalAppDbHelper.ImportStats(
                totalProcessed = records.size,
                inserted = result.first,
                duplicates = result.second
            )
        } catch (e: Exception) {
            Log.e("LocalAppDbViewModel", "Error in import", e)
            return@withContext LocalAppDbHelper.ImportStats(
                totalProcessed = records.size,
                inserted = 0,
                duplicates = 0
            )
        }
    }

    private suspend fun processLargeImportWithDuplicateCheck(
        records: List<WifiNetwork>,
        progressCallback: ((String, Int) -> Unit)?
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {

        progressCallback?.invoke("Загрузка существующих записей...", 20)
        val existingKeys = Collections.synchronizedSet(dbHelper.getAllExistingKeys())

        progressCallback?.invoke("Обработка ${records.size} записей...", 30)

        val chunkSize = 5000
        val chunks = records.chunked(chunkSize)
        val insertedCounter = AtomicInteger(0)
        val duplicatesCounter = AtomicInteger(0)

        val jobs = chunks.mapIndexed { index, chunk ->
            async {
                if (!isActive) return@async

                val progress = 30 + (index * 50) / chunks.size
                progressCallback?.invoke("Обработка блока ${index + 1}/${chunks.size}...", progress)

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
                    val inserted = dbHelper.bulkInsertBatch(uniqueNetworks)
                    insertedCounter.addAndGet(inserted)
                }
            }
        }

        jobs.awaitAll()

        return@withContext Pair(insertedCounter.get(), duplicatesCounter.get())
    }

    private fun getSavedIndexLevel(): String {
        return getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .getString("local_db_index_level", "BASIC") ?: "BASIC"
    }

    private fun saveIndexLevel(level: String) {
        getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .edit {
                putString("local_db_index_level", level)
            }
    }

    private fun clearSavedIndexLevel() {
        getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .edit {
                remove("local_db_index_level")
            }
    }

    fun isIndexingConfigured(): Boolean {
        return getApplication<Application>().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            .contains("local_db_index_level")
    }

    fun optimizeDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.optimizeDatabase()
        }
    }

    fun removeDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.removeDuplicates()
        }
    }

    fun restoreDatabaseFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.restoreDatabaseFromUri(uri)
            checkIndexingStatus()
        }
    }

}