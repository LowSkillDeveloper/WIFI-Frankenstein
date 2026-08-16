package com.lsd.wififrankenstein.ui.dbsetup

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.MegaFileUnavailableException
import com.lsd.wififrankenstein.network.MegaQuotaException
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.wifimap.ExternalIndexManager
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

data class SmartLinkDownloadResult(
    val dbInfo: SmartLinkDbInfo,
    val dbItem: DbItem?,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null
}

class DbSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val _dbList = MutableLiveData<List<DbItem>>()
    val dbList: LiveData<List<DbItem>> = _dbList

    private val _errorEvent = MutableLiveData<String>()
    val errorEvent: LiveData<String> = _errorEvent

    private val _oldFormatWarning = MutableLiveData<String?>()
    val oldFormatWarning: LiveData<String?> = _oldFormatWarning

    private val _expressionIndexConflictEvent = MutableLiveData<File?>()
    val expressionIndexConflictEvent: LiveData<File?> = _expressionIndexConflictEvent
    private var pendingConflictResult: CompletableDeferred<Boolean>? = null

    private val prefs by lazy {
        application.getSharedPreferences("db_setup_prefs", Context.MODE_PRIVATE)
    }

    private var sqlite3WiFiHelper: SQLite3WiFiHelper? = null

    private val selectedSources = mutableSetOf<String>()

    private var selectedContentPath: String? = null
    private var selectedDirectPath: String? = null
    private var selectedFileSize: Float = 0f
    private val externalIndexManager = ExternalIndexManager(getApplication())

    private val downloadJob = SupervisorJob()

    private val smartLinkDbHelper = SmartLinkDbHelper(application)
    val smartLinkDatabases = smartLinkDbHelper.databases

    val sources = smartLinkDbHelper.sources

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private var _isLoaded = false
    val isLoaded: Boolean
        get() = _isLoaded

    private var loadJob: Job? = null
    private val loadMutex = Mutex()

    suspend fun fetchSources(url: String): List<DbSource>? {
        return try {
            smartLinkDbHelper.fetchSources(url)
            smartLinkDbHelper.sources.value
        } catch (e: Exception) {
            _errorEvent.value =
                e.message ?: getApplication<Application>().getString(R.string.ds_failed_fetch_sources)
            null
        }
    }

    fun setCurrentSource(source: DbSource) {
        smartLinkDbHelper.setCurrentSource(source)
    }

    fun getCurrentSource(): DbSource? {
        return smartLinkDbHelper.getCurrentSource()
    }

    private val _indexingProgress = MutableLiveData<Pair<String, Int>>()
    val indexingProgress: LiveData<Pair<String, Int>> = _indexingProgress

    data class ColumnMappingEvent(
        val dbType: DbType,
        val type: String,
        val path: String,
        val directPath: String?
    )

    companion object {
        @Volatile
        private var instance: DbSetupViewModel? = null
        private val lock = Any()

        @JvmStatic
        var needDataRefresh: Boolean
            get() = _needDataRefresh.get()
            set(value) {
                _needDataRefresh.set(value)
            }
        private val _needDataRefresh = java.util.concurrent.atomic.AtomicBoolean(false)

        fun getInstance(application: Application): DbSetupViewModel {
            return instance ?: synchronized(lock) {
                instance ?: DbSetupViewModel(application).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(lock) {
                instance = null
            }
        }
    }

    private val _showColumnMappingEvent = MutableLiveData<ColumnMappingEvent>()
    val showColumnMappingEvent: LiveData<ColumnMappingEvent> = _showColumnMappingEvent


    private var sqliteCustomHelper: SQLiteCustomHelper? = null
    private val _columnNames = MutableLiveData<List<String>>()

    init {

    }

    fun getSmartLinkDatabases(): List<DbItem> {
        val result = dbList.value?.filter {
            it.smartlinkType != null && !it.smartlinkType.isBlank() &&
                    it.updateUrl != null && !it.updateUrl.isBlank() &&
                    it.idJson != null && !it.idJson.isBlank()
        } ?: emptyList()

        Log.d("DbSetupViewModel", "getSmartLinkDatabases: found ${result.size} databases")
        result.forEach { db ->
            Log.d(
                "DbSetupViewModel",
                "SmartLink DB: ${db.type}, smartlinkType: ${db.smartlinkType}, updateUrl: ${db.updateUrl}, idJson: ${db.idJson}, version: ${db.version}"
            )
        }

        return result
    }

    suspend fun updateSmartLinkDatabase(
        dbItem: DbItem,
        downloadUrl: String,
        newVersion: String,
        progressCallback: (Int) -> Unit
    ): DbItem {
        return withContext(Dispatchers.IO) {
            val updatedDbItem =
                smartLinkDbHelper.updateDatabase(dbItem, downloadUrl, newVersion, progressCallback)
            val currentList = _dbList.value.orEmpty().toMutableList()
            val index = currentList.indexOfFirst { it.id == dbItem.id }
            if (index != -1) {
                currentList[index] = updatedDbItem
                _dbList.postValue(currentList)
                saveDbList()
            }
            updatedDbItem
        }
    }

    suspend fun createDbIndexes(dbItem: DbItem): Boolean {
        Log.d("DbSetupViewModel", "createDbIndexes: id=${dbItem.id}, dbType=${dbItem.dbType}")
        if (dbItem.dbType != DbType.SQLITE_FILE_CUSTOM && dbItem.dbType != DbType.SMARTLINK_SQLITE_FILE_CUSTOM) return false

        val directPath = dbItem.directPath ?: return false
        val tableName = dbItem.tableName ?: return false
        val columnMap = dbItem.columnMap ?: return false

        try {
            val result = externalIndexManager.createExternalIndexes(
                dbItem.id,
                directPath,
                tableName,
                columnMap
            ) { progress ->
                _indexingProgress.postValue(dbItem.id to progress)
            }

            if (result) {
                updateDbIndexStatus(dbItem.id, DbIndexLevel.PARTIAL)
            }

            return result
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error creating indexes", e)
            return false
        }
    }


    fun deleteDbIndexes(dbItem: DbItem): Boolean {
        Log.d("DbSetupViewModel", "deleteDbIndexes: id=${dbItem.id}, dbType=${dbItem.dbType}")
        if (dbItem.dbType != DbType.SQLITE_FILE_CUSTOM && dbItem.dbType != DbType.SMARTLINK_SQLITE_FILE_CUSTOM) return false

        val result = externalIndexManager.deleteIndexes(dbItem.id)
        if (result) {
            updateDbIndexStatus(dbItem.id, DbIndexLevel.NONE)
        }
        return result
    }


    private fun updateDbIndexStatus(dbId: String, indexLevel: DbIndexLevel) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == dbId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(indexLevel = indexLevel)
            _dbList.value = currentList
            saveDbList()
        }
    }

    fun check3WiFiIndexes(dbItem: DbItem): DbIndexLevel {
        if (dbItem.dbType != DbType.SQLITE_FILE_P3WIFI && dbItem.dbType != DbType.SMARTLINK_SQLITE_FILE_P3WIFI) {
            Log.d(
                "DbSetupViewModel",
                "check3WiFiIndexes: ${dbItem.id} is not 3WiFi type (${dbItem.dbType})"
            )
            return DbIndexLevel.NONE
        }
        Log.d("DbSetupViewModel", "check3WiFiIndexes: starting for ${dbItem.id}")

        try {
            val helper = SQLite3WiFiHelper(getApplication(), dbItem.path.toUri(), dbItem.directPath)
            val database = helper.database
                ?: run {
                    Log.e(
                        "DbSetupViewModel",
                        "check3WiFiIndexes: database is null for ${dbItem.id}"
                    )
                    return DbIndexLevel.NONE
                }

            val indexLevel = when (DatabaseIndices.determineIndexLevel(database)) {
                DatabaseIndices.IndexLevel.FULL -> DbIndexLevel.FULL
                DatabaseIndices.IndexLevel.BASIC -> DbIndexLevel.PARTIAL
                else -> DbIndexLevel.NONE
            }
            helper.close()

            Log.d("DbSetupViewModel", "check3WiFiIndexes for ${dbItem.id}: $indexLevel")
            return indexLevel
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error checking 3WiFi indexes", e)
            return DbIndexLevel.NONE
        }
    }

    fun updateAllDbIndexStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("DbSetupViewModel", "updateAllDbIndexStatuses started")
            val currentList = _dbList.value.orEmpty().toMutableList()
            var updated = false

            for (i in currentList.indices) {
                val dbItem = currentList[i]
                Log.d(
                    "DbSetupViewModel",
                    "updateAllDbIndexStatuses: processing ${dbItem.id}, dbType=${dbItem.dbType}"
                )
                val indexLevel = withContext(Dispatchers.IO) {
                    when (dbItem.dbType) {
                        DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                            if (dbItem.directPath != null) {
                                val exists = externalIndexManager.indexesExist(dbItem.id)
                                Log.d(
                                    "DbSetupViewModel",
                                    "Custom DB ${dbItem.id}: indexesExist=$exists"
                                )
                                if (exists) DbIndexLevel.PARTIAL else DbIndexLevel.NONE
                            } else {
                                Log.d(
                                    "DbSetupViewModel",
                                    "Custom DB ${dbItem.id}: directPath is null"
                                )
                                DbIndexLevel.NONE
                            }
                        }

                        DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                            check3WiFiIndexesDirectlyLevel(dbItem)
                        }

                        else -> {
                            Log.d(
                                "DbSetupViewModel",
                                "DB ${dbItem.id}: unsupported type ${dbItem.dbType}"
                            )
                            DbIndexLevel.NONE
                        }
                    }
                }

                if (dbItem.indexLevel != indexLevel) {
                    currentList[i] = dbItem.copy(indexLevel = indexLevel)
                    updated = true
                    Log.d(
                        "DbSetupViewModel",
                        "Updated index status for ${dbItem.id}: ${dbItem.indexLevel} -> $indexLevel"
                    )
                }
            }

            if (updated) {
                withContext(Dispatchers.Main) {
                    _dbList.postValue(currentList)
                    saveDbList()
                }
            }
        }
    }






    fun refreshLight() {
        updateAllDbIndexStatuses()
    }

    fun forceUpdateIndexStatus(dbId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _dbList.value.orEmpty().toMutableList()
            val index = currentList.indexOfFirst { it.id == dbId }

            if (index != -1) {
                val dbItem = currentList[index]
                Log.d(
                    "DbSetupViewModel",
                    "forceUpdateIndexStatus: id=${dbItem.id}, dbType=${dbItem.dbType}"
                )
                val indexLevel = when (dbItem.dbType) {
                    DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                        if (dbItem.directPath != null) {
                            if (externalIndexManager.indexesExist(dbItem.id)) DbIndexLevel.PARTIAL else DbIndexLevel.NONE
                        } else {
                            DbIndexLevel.NONE
                        }
                    }

                    DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                        check3WiFiIndexesDirectlyLevel(dbItem)
                    }

                    else -> DbIndexLevel.NONE
                }

                if (dbItem.indexLevel != indexLevel) {
                    Log.d(
                        "DbSetupViewModel",
                        "Index status changed for ${dbItem.id}: ${dbItem.indexLevel} -> $indexLevel"
                    )
                    currentList[index] = dbItem.copy(indexLevel = indexLevel)
                    withContext(Dispatchers.Main) {
                        _dbList.postValue(currentList)
                        saveDbList()
                    }
                }
            }
        }
    }

    private fun check3WiFiIndexesDirectlyLevel(dbItem: DbItem): DbIndexLevel {
        return try {
            val helper = SQLite3WiFiHelper(getApplication(), dbItem.path.toUri(), dbItem.directPath)
            val database = helper.database
                ?: run {
                    Log.e("DbSetupViewModel", "Database is null for ${dbItem.id}")
                    return DbIndexLevel.NONE
                }

            val level = when (DatabaseIndices.determineIndexLevel(database)) {
                DatabaseIndices.IndexLevel.FULL -> DbIndexLevel.FULL
                DatabaseIndices.IndexLevel.BASIC -> DbIndexLevel.PARTIAL
                else -> DbIndexLevel.NONE
            }
            helper.close()

            Log.d("DbSetupViewModel", "Index check for ${dbItem.id}: $level")
            level
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error checking 3WiFi indexes directly", e)
            DbIndexLevel.NONE
        }
    }


    fun getWifiApiDatabases(): List<DbItem> {
        return dbList.value?.filter { it.dbType == DbType.WIFI_API } ?: emptyList()
    }

    suspend fun loadDbList(force: Boolean = false) {
        if (!force && _isLoaded) return

        loadMutex.withLock {
            if (_isLoading.value == true) {
                loadJob?.join()
            }
            if (!force && _isLoaded) return

            loadJob?.cancel()
            loadJob = viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    _isLoading.postValue(true)
                    try {
                        val jsonString = prefs.getString("db_list", null)
                        Log.d("DbSetupViewModel", "Loaded DB list string: $jsonString")
                        if (jsonString != null) {
                            val dbList = Json.decodeFromString<List<DbItem>>(jsonString)
                            withContext(Dispatchers.Main) {
                                _dbList.value = dbList
                                migrateOldApiKeys()
                                updateMainApi()
                                validateCachedDatabases()
                            }
                            Log.d("DbSetupViewModel", "Loaded DB list: $dbList")
                        } else {
                            withContext(Dispatchers.Main) {
                                _dbList.value = emptyList()
                            }
                            Log.d("DbSetupViewModel", "No saved DB list found")
                        }
                        _isLoaded = true
                    } catch (e: Exception) {
                        Log.e("DbSetupViewModel", "Error loading DB list: ${e.message}")
                        withContext(Dispatchers.Main) {
                            _dbList.value = emptyList()
                        }
                    } finally {
                        _isLoading.postValue(false)
                    }
                }
            }
            loadJob?.join()
        }
    }

    private suspend fun migrateOldApiKeys() {
        val dbList = _dbList.value ?: return
        var hasChanges = false

        val updatedList = dbList.map { dbItem ->
            if (dbItem.dbType == DbType.WIFI_API && dbItem.apiReadKey == null && dbItem.apiKey != null) {
                hasChanges = true
                Log.d("DbSetupViewModel", "Migrating old API key for ${dbItem.path}")
                dbItem.copy(
                    apiReadKey = dbItem.apiKey,
                    apiWriteKey = null,
                    authMethod = AuthMethod.API_KEYS
                )
            } else {
                dbItem
            }
        }

        if (hasChanges) {
            Log.d("DbSetupViewModel", "Migration completed, saving updated list")
            _dbList.value = updatedList
            saveDbList()
        }
    }


    private fun saveDbList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Json.encodeToString(_dbList.value?.toList() ?: emptyList())
                prefs.edit { putString("db_list", jsonString) }
                Log.d("DbSetupViewModel", "Saved DB list: $jsonString")
            } catch (e: Exception) {
                Log.e("DbSetupViewModel", "Error saving DB list: ${e.message}")
            }
        }
    }

    suspend fun fetchSmartLinkDatabases(url: String): List<SmartLinkDbInfo>? {
        return try {
            smartLinkDbHelper.fetchDatabases(url)
            smartLinkDbHelper.databases.value
        } catch (e: MegaFileUnavailableException) {
            _errorEvent.value = getApplication<Application>().getString(R.string.mega_file_unavailable)
            null
        } catch (e: Exception) {
            _errorEvent.value = e.message
                ?: getApplication<Application>().getString(R.string.ds_failed_fetch_smartlink)
            null
        }
    }

    fun resolveExpressionIndexConflict(shouldPatch: Boolean) {
        pendingConflictResult?.complete(shouldPatch)
        pendingConflictResult = null
        _expressionIndexConflictEvent.postValue(null)
    }

    suspend fun downloadSmartLinkDatabase(
        dbInfo: SmartLinkDbInfo,
        onProgress: (Int, Long, Long?) -> Unit
    ): SmartLinkDownloadResult {
        return withContext(viewModelScope.coroutineContext) {
            try {
                smartLinkDbHelper.legacyConflictResolver = LegacyDatabaseConflictResolver { file ->
                    suspendCancellableCoroutine<Boolean> { cont ->
                        pendingConflictResult = CompletableDeferred()
                        _expressionIndexConflictEvent.postValue(file)
                        cont.invokeOnCancellation {
                            pendingConflictResult?.cancel()
                            pendingConflictResult = null
                        }
                    }
                    pendingConflictResult?.await() ?: false
                }
                val dbItem = smartLinkDbHelper.downloadDatabase(dbInfo, onProgress)
                if (dbItem != null && dbItem.directPath != null) {
                    val warning = withContext(Dispatchers.IO) {
                        smartLinkDbHelper.checkDbFormatWarning(File(dbItem.directPath))
                    }
                    dbItem.oldFormatWarning = warning
                    if (warning != null) {
                        _oldFormatWarning.postValue(warning)
                    }
                }
                SmartLinkDownloadResult(dbInfo, dbItem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = when (e) {
                    is MegaQuotaException ->
                        getApplication<Application>().getString(R.string.mega_bandwidth_exceeded)
                    is MegaFileUnavailableException ->
                        getApplication<Application>().getString(R.string.mega_file_unavailable)
                    else -> e.message
                        ?: getApplication<Application>().getString(R.string.operation_failed)
                }
                SmartLinkDownloadResult(dbInfo, null, error)
            } finally {
                smartLinkDbHelper.legacyConflictResolver = null
            }
        }
    }

    fun updateDbItem(updatedItem: DbItem) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedItem.id }
        if (index != -1) {
            currentList[index] = updatedItem
            _dbList.value = currentList
            saveDbList()
            Log.d("DbSetupViewModel", "Updated DB item: ${updatedItem.id}")
        }
    }

    fun initializeSQLiteCustomHelper(dbUri: Uri, directPath: String?) {
        sqliteCustomHelper = SQLiteCustomHelper(getApplication(), dbUri, directPath)
    }

    suspend fun initializeSQLiteCustomHelperWithProgress(
        dbUri: Uri,
        directPath: String?,
        onProgress: (Int, Long, Long) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        sqliteCustomHelper = SQLiteCustomHelper(getApplication(), dbUri, directPath)
        return sqliteCustomHelper?.copyAndOpenWithProgress(onProgress, onCancel) ?: false
    }

    fun getCustomTableNames(): List<String>? {
        return sqliteCustomHelper?.getTableNames()
    }

    fun getCustomColumnNames(tableName: String): List<String>? {
        return sqliteCustomHelper?.getColumnNames(tableName)
    }

    fun getCustomSampleValues(tableName: String, column: String): List<String>? {
        return sqliteCustomHelper?.getSampleValues(tableName, column)
    }

    fun getCustomFillRatio(tableName: String, column: String): Double {
        return sqliteCustomHelper?.getColumnFillRatio(tableName, column) ?: 0.0
    }

    fun setSelectedTable(tableName: String) {
        viewModelScope.launch {
            _columnNames.value = getCustomColumnNames(tableName) ?: emptyList()
        }
    }

    fun setSelectedFilePaths(contentPath: String, directPath: String?) {
        selectedContentPath = contentPath
        selectedDirectPath = directPath
    }

    fun setSelectedCachedFilePath(cachedPath: String) {
        selectedDirectPath = cachedPath
        Log.d("DbSetupViewModel", "Set cached file path: $cachedPath")
    }

    fun getSelectedDirectPath(): String? {
        return selectedDirectPath
    }

    fun setSelectedFileSize(size: Float) {
        selectedFileSize = size
    }

    fun getSelectedFileSize(): Float {
        return selectedFileSize
    }

    fun addDb(dbItem: DbItem) {

        if ((dbItem.dbType == DbType.SQLITE_FILE_CUSTOM || dbItem.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) && dbItem.columnMap == null) {
            _showColumnMappingEvent.value = ColumnMappingEvent(
                dbType = dbItem.dbType,
                type = dbItem.type,
                path = dbItem.path,
                directPath = dbItem.directPath
            )
            return
        }

        Log.d("DbSetupViewModel", "Adding DbItem: $dbItem")
        val currentList = _dbList.value.orEmpty().toMutableList()
        val newItem = dbItem.copy(
            id = UUID.randomUUID().toString(),
            path = selectedContentPath ?: dbItem.path,
            directPath = selectedDirectPath ?: dbItem.directPath,
            originalSizeInMB = selectedFileSize.takeIf { it > 0f } ?: dbItem.originalSizeInMB,
            cachedSizeInMB = if (dbItem.dbType == DbType.SQLITE_FILE_P3WIFI || dbItem.dbType == DbType.SQLITE_FILE_CUSTOM || dbItem.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI || dbItem.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) {
                getUpdatedCachedFileSize((selectedContentPath ?: dbItem.path).toUri())
            } else {
                dbItem.cachedSizeInMB
            }
        )

        currentList.add(newItem)
        _dbList.value = currentList
        updateMainApi()
        saveDbList()

        Log.d(
            "DbSetupViewModel",
            "Initializing helper for newItem: dbType=${newItem.dbType}, id=${newItem.id}"
        )

        when (newItem.dbType) {
            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                if (sqlite3WiFiHelper?.database?.isOpen != true) {
                    initializeSQLite3WiFiHelper(newItem.path.toUri(), newItem.directPath)
                }
                sqlite3WiFiHelper?.getCachedDbPath()?.let { cachedPath ->
                    val updatedItem = newItem.copy(directPath = cachedPath)
                    updateDbItem(updatedItem)
                }
            }

            DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                if (sqliteCustomHelper?.database?.isOpen != true) {
                    initializeSQLiteCustomHelper(newItem.path.toUri(), newItem.directPath)
                }
                sqliteCustomHelper?.getCachedDbPath()?.let { cachedPath ->
                    val updatedItem = newItem.copy(directPath = cachedPath)
                    updateDbItem(updatedItem)
                }
            }

            else -> {
                Log.d("DbSetupViewModel", "No initialization needed for dbType=${newItem.dbType}")
            }
        }


        if (newItem.dbType == DbType.SQLITE_FILE_P3WIFI && newItem.oldFormatWarning == null) {
            val db = sqlite3WiFiHelper?.database
            if (db != null) {
                val hasBase = DatabaseTypeUtils.hasTable(db, "base")
                val hasNets = DatabaseTypeUtils.hasTable(db, "nets")
                val hasComments = DatabaseTypeUtils.hasTable(db, "comments")
                val warning = when {
                    hasBase -> getApplication<Application>().getString(R.string.db_old_format_warning_base)
                    hasNets && !hasComments -> getApplication<Application>().getString(R.string.db_old_format_warning_no_comments)
                    else -> null
                }
                if (warning != null) {
                    val idx = _dbList.value?.indexOfFirst { it.id == newItem.id } ?: -1
                    if (idx >= 0) {
                        val updated = _dbList.value!!.toMutableList()
                        updated[idx] = updated[idx].copy(oldFormatWarning = warning)
                        _dbList.value = updated
                        saveDbList()
                        Log.d(
                            "DbSetupViewModel",
                            "Set old format warning for ${newItem.id}: $warning"
                        )
                    }
                }
            }
        }

        needDataRefresh = true


        forceUpdateIndexStatus(newItem.id)

        Log.d("DbSetupViewModel", "Added DB item: $newItem")
        selectedContentPath = null
        selectedDirectPath = null
        selectedFileSize = 0f
    }

    fun getUpdatedCachedFileSize(uri: Uri): Float {
        val file =
            File(getApplication<Application>().cacheDir, "CacheDB/${getFileNameFromUri(uri)}")
        return if (file.exists()) file.length().toFloat() / (1024 * 1024) else 0f
    }

    fun updateDbOrder(fromPosition: Int, toPosition: Int) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val item = currentList.removeAt(fromPosition)
        currentList.add(toPosition, item)
        _dbList.value = currentList
        updateMainApi()
        saveDbList()

        needDataRefresh = true
        Log.d("DbSetupViewModel", "Database order updated, setting refresh flag")
    }

    fun getLocalDbItem(): DbItem {
        return DbItem(
            id = "local_db",
            path = "local_db",
            directPath = null,
            type = "Local Database",
            dbType = DbType.LOCAL_APP_DB,
            isMain = false,
            apiKey = null,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )
    }

    fun removeDb(id: String) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList.removeAt(index)
            _dbList.value = currentList
            updateMainApi()
            saveDbList()

            needDataRefresh = true
        }
    }

    fun removeDb(position: Int) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val removedDb = currentList.removeAt(position)
        _dbList.value = currentList
        updateMainApi()
        saveDbList()

        needDataRefresh = true
        Log.d(
            "DbSetupViewModel",
            "Database removed, setting refresh flag: id=${removedDb.id}, path=${removedDb.path}"
        )

        removeDbFiles(removedDb)
    }

    private fun removeDbFiles(dbItem: DbItem) {
        when (dbItem.dbType) {
            DbType.SQLITE_FILE_P3WIFI, DbType.SQLITE_FILE_CUSTOM -> {
                val sharesPath = _dbList.value.orEmpty().any { it.path == dbItem.path }
                if (!sharesPath) {
                    SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), dbItem.path.toUri())
                }
            }

            DbType.SMARTLINK_SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                val sharesFile = _dbList.value.orEmpty().any {
                    it.path == dbItem.path ||
                            (dbItem.directPath != null && it.directPath == dbItem.directPath)
                }
                if (!sharesFile) {
                    val dbFile = File(dbItem.directPath ?: "")
                    if (dbFile.exists()) {
                        dbFile.delete()
                    }
                    val uri = dbItem.path.toUri()
                    SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), uri)
                }
                externalIndexManager.deleteIndexes(dbItem.id)
            }

            else -> {}
        }
    }

    fun clearAllCachedDatabases() {
        _dbList.value?.forEach { dbItem ->
            dbItem.path.let { path ->
                val uri = path.toUri()
                SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), uri)
            }
        }
    }

    fun updateDbSize(dbItem: DbItem) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == dbItem.id }
        if (index != -1) {
            val originalSize = getUpdatedOriginalFileSize(dbItem)
            if (originalSize == -1f) {
                Log.w("DbSetupViewModel", "Removing missing database file: ${dbItem.type}")
                currentList.removeAt(index)
                _dbList.value = currentList
                saveDbList()
                _errorEvent.value = "missing_file_removed"
            } else {
                val updatedItem = dbItem.copy(
                    originalSizeInMB = originalSize,
                    cachedSizeInMB = getUpdatedCachedFileSize(dbItem.path.toUri())
                )
                currentList[index] = updatedItem
                _dbList.value = currentList
                saveDbList()
            }
        }
    }

    private fun getUpdatedOriginalFileSize(dbItem: DbItem): Float {
        Log.d(
            "DbSetupViewModel",
            "getUpdatedOriginalFileSize: id=${dbItem.id}, dbType=${dbItem.dbType}"
        )

        return when (dbItem.dbType) {
            DbType.SQLITE_FILE_P3WIFI, DbType.SQLITE_FILE_CUSTOM,
            DbType.SMARTLINK_SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                try {
                    val uri = dbItem.path.toUri()
                    var fileSize = 0L
                    getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                        ?.use { fileDescriptor ->
                            fileSize = fileDescriptor.statSize
                        }
                    fileSize.toFloat() / (1024 * 1024)
                } catch (e: java.io.FileNotFoundException) {
                    Log.w("DbSetupViewModel", "File not found for ${dbItem.type}: ${dbItem.path}")
                    -1f
                } catch (e: Exception) {
                    Log.e("DbSetupViewModel", "Error getting file size for ${dbItem.type}", e)
                    dbItem.originalSizeInMB
                }
            }

            else -> dbItem.originalSizeInMB
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
        return if (name.endsWith(".sqlite", ignoreCase = true)) name else "$name.sqlite"
    }

    fun checkAndUpdateDatabases() {
        viewModelScope.launch {
            val currentList = _dbList.value ?: return@launch
            val updatedList = mutableListOf<DbItem>()

            currentList.forEach { dbItem ->
                Log.d(
                    "DbSetupViewModel",
                    "checkAndUpdateDatabases: id=${dbItem.id}, dbType=${dbItem.dbType}"
                )
                when (dbItem.dbType) {
                    DbType.SQLITE_FILE_P3WIFI, DbType.SQLITE_FILE_CUSTOM,
                    DbType.SMARTLINK_SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                        val originalSize = getUpdatedOriginalFileSize(dbItem)
                        if (originalSize == -1f) {
                            Log.w(
                                "DbSetupViewModel",
                                "Skipping missing database file: ${dbItem.type}"
                            )
                            _errorEvent.postValue("missing_file_removed")
                        } else if (originalSize != dbItem.originalSizeInMB) {
                            val uri = dbItem.path.toUri()
                            SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), uri)
                            val helper = SQLite3WiFiHelper(getApplication(), uri, dbItem.directPath)
                            val cachedSize = helper.getSelectedFileSize()
                            updatedList.add(
                                dbItem.copy(
                                    originalSizeInMB = originalSize,
                                    cachedSizeInMB = cachedSize
                                )
                            )
                        } else {
                            updatedList.add(dbItem)
                        }
                    }

                    else -> updatedList.add(dbItem)
                }
            }

            if (updatedList.size != currentList.size) {
                _dbList.postValue(updatedList)
                saveDbList()
            } else {
                _dbList.postValue(updatedList)
                saveDbList()
            }
        }
    }




    fun checkAndUpdateDatabasesWithIndexes() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _dbList.value ?: return@launch
            val updatedList = mutableListOf<DbItem>()

            currentList.forEach { dbItem ->
                Log.d(
                    "DbSetupViewModel",
                    "checkAndUpdateDatabasesWithIndexes: id=${dbItem.id}, dbType=${dbItem.dbType}"
                )
                when (dbItem.dbType) {
                    DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                        val originalSize = getUpdatedOriginalFileSize(dbItem)
                        if (originalSize == -1f) {
                            Log.w(
                                "DbSetupViewModel",
                                "Skipping missing database file: ${dbItem.type}"
                            )
                            _errorEvent.postValue("missing_file_removed")
                            updatedList.add(dbItem)
                        } else {
                            val uri = dbItem.path.toUri()
                            val helper = SQLite3WiFiHelper(getApplication(), uri, dbItem.directPath)
                            val database = helper.database

                            val indexLevel = if (database != null) {
                                val level = DatabaseIndices.determineIndexLevel(database)
                                when (level) {
                                    DatabaseIndices.IndexLevel.FULL -> DbIndexLevel.FULL
                                    DatabaseIndices.IndexLevel.BASIC -> DbIndexLevel.PARTIAL
                                    else -> DbIndexLevel.NONE
                                }
                            } else {
                                DbIndexLevel.NONE
                            }

                            val cachedSize = helper.getSelectedFileSize()
                            val finalOriginalSize =
                                if (originalSize != dbItem.originalSizeInMB) originalSize else dbItem.originalSizeInMB
                            val finalCachedSize =
                                if (originalSize != dbItem.originalSizeInMB) cachedSize else dbItem.cachedSizeInMB

                            updatedList.add(
                                dbItem.copy(
                                    originalSizeInMB = finalOriginalSize,
                                    cachedSizeInMB = finalCachedSize,
                                    indexLevel = indexLevel
                                )
                            )
                            helper.close()
                            Log.d(
                                "DbSetupViewModel",
                                "Checked ${dbItem.id}: size=${finalOriginalSize}MB, index=$indexLevel"
                            )
                        }
                    }

                    DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                        val indexLevel = if (dbItem.directPath != null) {
                            if (externalIndexManager.indexesExist(dbItem.id)) DbIndexLevel.PARTIAL else DbIndexLevel.NONE
                        } else {
                            DbIndexLevel.NONE
                        }
                        updatedList.add(dbItem.copy(indexLevel = indexLevel))
                    }

                    else -> updatedList.add(dbItem)
                }
            }

            withContext(Dispatchers.Main) {
                _dbList.postValue(updatedList)
                saveDbList()
            }
        }
    }

    private fun updateMainApi() {
        val currentList = _dbList.value.orEmpty().toMutableList()
        Log.d("DbSetupViewModel", "List at start of updateMainApi: $currentList")
        currentList.forEachIndexed { index, item ->
            if (item.dbType == DbType.WIFI_API) {
                item.isMain = index == currentList.indexOfFirst { it.dbType == DbType.WIFI_API }
            } else {
                item.isMain = false
            }
        }
        Log.d("DbSetupViewModel", "List at end of updateMainApi: $currentList")
        _dbList.value = currentList
    }

    fun initializeSQLite3WiFiHelper(dbUri: Uri, directPath: String?) {
        sqlite3WiFiHelper = SQLite3WiFiHelper(getApplication(), dbUri, directPath)
    }

    suspend fun initializeSQLite3WiFiHelperWithProgress(
        dbUri: Uri,
        directPath: String?,
        onProgress: (Int, Long, Long) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        sqlite3WiFiHelper = SQLite3WiFiHelper(getApplication(), dbUri, directPath, deferOpen = true)
        return sqlite3WiFiHelper?.copyAndOpenWithProgress(onProgress, onCancel) ?: false
    }

    fun getTableNames(): List<String>? {
        return sqlite3WiFiHelper?.getTableNames()
    }

    fun getCached3WiFiDbPath(): String? {
        return sqlite3WiFiHelper?.getCachedDbPath()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun hasStoragePermissions(): Boolean {
        val context = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getTotalRecordsCount(): Int {
        return LocalAppDbHelper(getApplication()).getRecordsCount()
    }

    fun getDbSize(): String {
        val dbFile = getApplication<Application>().getDatabasePath(LocalAppDbHelper.DATABASE_NAME)
        val bytes = dbFile.length()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    fun isStoragePermissionMessageShown(): Boolean {
        return prefs.getBoolean("storage_permission_message_shown", false)
    }

    fun setStoragePermissionMessageShown() {
        prefs.edit { putBoolean("storage_permission_message_shown", true) }
    }

    fun getDirectPathFromUri(uri: Uri): String? {
        val context = getApplication<Application>().applicationContext
        return when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                when {
                    isExternalStorageDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        if ("primary".equals(type, ignoreCase = true)) {
                            "${Environment.getExternalStorageDirectory()}/${split[1]}"
                        } else {
                            val externalStorageVolumes: Array<out File> =
                                ContextCompat.getExternalFilesDirs(context, null)
                            for (file in externalStorageVolumes) {
                                val path = file.absolutePath
                                if (path.contains(type)) {
                                    return path.substringBefore("/Android") + "/${split[1]}"
                                }
                            }
                            null
                        }
                    }

                    isDownloadsDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        when {
                            docId.startsWith("msf:") -> {
                                val contentUri = ContentUris.withAppendedId(
                                    "content://downloads/public_downloads".toUri(),
                                    docId.substringAfter("msf:").toLong()
                                )
                                getDataColumn(context, contentUri, null, null)
                            }

                            docId.startsWith("raw:") -> {
                                docId.substringAfter("raw:")
                            }

                            else -> {
                                try {
                                    val contentUri = ContentUris.withAppendedId(
                                        "content://downloads/public_downloads".toUri(),
                                        docId.toLong()
                                    )
                                    getDataColumn(context, contentUri, null, null)
                                } catch (_: NumberFormatException) {
                                    getDataColumn(context, uri, null, null)
                                }
                            }
                        }
                    }

                    isMediaDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        var contentUri: Uri? = null
                        when (type) {
                            "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }
                        val selection = "_id=?"
                        val selectionArgs = arrayOf(split[1])
                        getDataColumn(context, contentUri, selection, selectionArgs)
                    }

                    else -> null
                }
            }

            "content".equals(uri.scheme, ignoreCase = true) -> {
                if (isGooglePhotosUri(uri)) {
                    uri.lastPathSegment
                } else {
                    getDataColumn(context, uri, null, null)
                }
            }

            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path
            }

            else -> null
        }
    }

    data class CopyProgress(
        val progress: Int,
        val bytesCopied: Long,
        val totalBytes: Long,
        val fileName: String
    )

    suspend fun copyUriToCacheWithProgress(
        uri: Uri,
        onProgress: (CopyProgress) -> Unit,
        onCancel: () -> Unit
    ): File {
        val context = getApplication<Application>()
        val fileName = getFileNameFromUri(uri)
        val tempFile = File(context.cacheDir, fileName)

        var fileSize: Long = 0
        context.contentResolver.openFileDescriptor(uri, "r")?.use { fileDescriptor ->
            fileSize = fileDescriptor.statSize
        }

        var totalBytes = 0L
        var lastProgressReport = 0
        var blocksSinceLastYield = 0
        val yieldInterval = if (fileSize > 100 * 1024 * 1024) 64 else 16

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val bufferSize = if (fileSize > 500 * 1024 * 1024) 131072 else 65536
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val progress = if (fileSize > 0) {
                            (totalBytes * 100 / fileSize).toInt()
                        } else {
                            -1
                        }

                        if (progress != lastProgressReport && progress > 0) {
                            lastProgressReport = progress
                            onProgress(CopyProgress(progress, totalBytes, fileSize, fileName))
                        }

                        blocksSinceLastYield++
                        if (blocksSinceLastYield >= yieldInterval) {
                            blocksSinceLastYield = 0
                            yield()
                        }
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("Failed to copy file: empty or missing")
            }

            return tempFile
        } catch (e: CancellationException) {
            tempFile.delete()
            onCancel()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        uri ?: return null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(column)
                        return cursor.getString(columnIndex)
                    }
                }
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error getting data column", e)
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    fun validateCachedDatabases() {
        val currentList = _dbList.value ?: return
        val updatedList = currentList.toMutableList()

        for (i in updatedList.indices) {
            val dbItem = updatedList[i]
            Log.d(
                "DbSetupViewModel",
                "validateCachedDatabases: id=${dbItem.id}, dbType=${dbItem.dbType}"
            )
            if (dbItem.dbType !in listOf(
                    DbType.SQLITE_FILE_P3WIFI,
                    DbType.SQLITE_FILE_CUSTOM,
                    DbType.SMARTLINK_SQLITE_FILE_P3WIFI,
                    DbType.SMARTLINK_SQLITE_FILE_CUSTOM
                )
            ) continue

            if (!dbItem.directPath.isNullOrEmpty()) continue

            val cachedFile = getCachedDbFile(dbItem)
            if (cachedFile.exists()) {
                Log.d(
                    "DbSetupViewModel",
                    "Setting cached path for ${dbItem.id}: ${cachedFile.absolutePath}"
                )
                updatedList[i] = dbItem.copy(directPath = cachedFile.absolutePath)
            }
        }

        if (updatedList != currentList) {
            _dbList.value = updatedList
            saveDbList()
        }
    }

    private fun getCachedDbFile(dbItem: DbItem): File {
        val fileName = getFileNameFromUri(dbItem.path.toUri())
        return File(getApplication<Application>().cacheDir, "CacheDB/$fileName")
    }

    override fun onCleared() {
        super.onCleared()
        sqlite3WiFiHelper?.close()
        downloadJob.cancel()
    }
}