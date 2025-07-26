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
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.wifimap.ExternalIndexManager
import com.lsd.wififrankenstein.util.DatabaseIndices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class DbSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val _dbList = MutableLiveData<List<DbItem>>()
    val dbList: LiveData<List<DbItem>> = _dbList

    private val _errorEvent = MutableLiveData<String>()
    val errorEvent: LiveData<String> = _errorEvent

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
        var needDataRefresh = false
    }

    private val _showColumnMappingEvent = MutableLiveData<ColumnMappingEvent>()
    val showColumnMappingEvent: LiveData<ColumnMappingEvent> = _showColumnMappingEvent


    private var sqliteCustomHelper: SQLiteCustomHelper? = null
    private val _columnNames = MutableLiveData<List<String>>()

    fun getAvailableSources(): List<String> {
        return dbList.value?.map { it.path } ?: emptyList()
    }


    init {
        viewModelScope.launch {
            loadDbList()
            val currentList = _dbList.value.orEmpty().toMutableList()
            if (!currentList.any { it.dbType == DbType.LOCAL_APP_DB }) {
                currentList.add(getLocalDbItem())
                _dbList.value = currentList
                saveDbList()
            }
            selectedSources.addAll(getAvailableSources().toList())
        }
    }

    fun getSmartLinkDatabases(): List<DbItem> {
        val result = dbList.value?.filter {
            it.smartlinkType != null && !it.smartlinkType.isBlank() &&
                    it.updateUrl != null && !it.updateUrl.isBlank() &&
                    it.idJson != null && !it.idJson.isBlank()
        } ?: emptyList()

        Log.d("DbSetupViewModel", "getSmartLinkDatabases: found ${result.size} databases")
        result.forEach { db ->
            Log.d("DbSetupViewModel", "SmartLink DB: ${db.type}, smartlinkType: ${db.smartlinkType}, updateUrl: ${db.updateUrl}, idJson: ${db.idJson}, version: ${db.version}")
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
            val updatedDbItem = smartLinkDbHelper.updateDatabase(dbItem, downloadUrl, newVersion, progressCallback)
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
        if (dbItem.dbType != DbType.SQLITE_FILE_CUSTOM) return false

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
                updateDbIndexStatus(dbItem.id, true)
            }

            return result
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error creating indexes", e)
            return false
        }
    }


    fun deleteDbIndexes(dbItem: DbItem): Boolean {
        if (dbItem.dbType != DbType.SQLITE_FILE_CUSTOM) return false

        val result = externalIndexManager.deleteIndexes(dbItem.id)
        if (result) {
            updateDbIndexStatus(dbItem.id, false)
        }
        return result
    }


    private fun updateDbIndexStatus(dbId: String, isIndexed: Boolean) {
        val currentList = _dbList.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == dbId }
        if (index != -1) {
            currentList[index] = currentList[index].copy(isIndexed = isIndexed)
            _dbList.value = currentList
            saveDbList()
        }
    }

    fun check3WiFiIndexes(dbItem: DbItem): Boolean {
        if (dbItem.dbType != DbType.SQLITE_FILE_3WIFI) return false

        try {
            val helper = SQLite3WiFiHelper(getApplication(), dbItem.path.toUri(), dbItem.directPath)
            val database = helper.database ?: return false

            val indexLevel = DatabaseIndices.determineIndexLevel(database)
            helper.close()

            return indexLevel >= DatabaseIndices.IndexLevel.BASIC
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error checking 3WiFi indexes", e)
            return false
        }
    }

    fun updateAllDbIndexStatuses() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _dbList.value.orEmpty().toMutableList()
            var updated = false

            for (i in currentList.indices) {
                val dbItem = currentList[i]
                val isIndexed = withContext(Dispatchers.IO) {
                    when (dbItem.dbType) {
                        DbType.SQLITE_FILE_CUSTOM -> {
                            if (dbItem.directPath != null) {
                                externalIndexManager.indexesExist(dbItem.id)
                            } else {
                                false
                            }
                        }
                        DbType.SQLITE_FILE_3WIFI -> {
                            check3WiFiIndexesDirectly(dbItem)
                        }
                        else -> false
                    }
                }

                if (dbItem.isIndexed != isIndexed) {
                    currentList[i] = dbItem.copy(isIndexed = isIndexed)
                    updated = true
                    Log.d("DbSetupViewModel", "Updated index status for ${dbItem.id}: $isIndexed")
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

    fun forceUpdateIndexStatus(dbId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _dbList.value.orEmpty().toMutableList()
            val index = currentList.indexOfFirst { it.id == dbId }

            if (index != -1) {
                val dbItem = currentList[index]
                val isIndexed = when (dbItem.dbType) {
                    DbType.SQLITE_FILE_CUSTOM -> {
                        if (dbItem.directPath != null) {
                            externalIndexManager.indexesExist(dbItem.id)
                        } else {
                            false
                        }
                    }
                    DbType.SQLITE_FILE_3WIFI -> {
                        check3WiFiIndexesDirectly(dbItem)
                    }
                    else -> false
                }

                if (dbItem.isIndexed != isIndexed) {
                    currentList[index] = dbItem.copy(isIndexed = isIndexed)
                    withContext(Dispatchers.Main) {
                        _dbList.postValue(currentList)
                        saveDbList()
                    }
                }
            }
        }
    }

    private fun check3WiFiIndexesDirectly(dbItem: DbItem): Boolean {
        return try {
            val helper = SQLite3WiFiHelper(getApplication(), dbItem.path.toUri(), dbItem.directPath)
            val database = helper.database ?: return false

            val indexLevel = DatabaseIndices.determineIndexLevel(database)
            helper.close()

            indexLevel >= DatabaseIndices.IndexLevel.BASIC
        } catch (e: Exception) {
            Log.e("DbSetupViewModel", "Error checking 3WiFi indexes directly", e)
            false
        }
    }


    fun getWifiApiDatabases(): List<DbItem> {
        return dbList.value?.filter { it.dbType == DbType.WIFI_API } ?: emptyList()
    }

    suspend fun loadDbList() {
        withContext(Dispatchers.IO) {
            try {
                val jsonString = prefs.getString("db_list", null)
                Log.d("DbSetupViewModel", "Loaded DB list string: $jsonString")
                if (jsonString != null) {
                    val dbList = Json.decodeFromString<List<DbItem>>(jsonString)
                    withContext(Dispatchers.Main) {
                        _dbList.value = dbList
                        updateMainApi()
                    }
                    Log.d("DbSetupViewModel", "Loaded DB list: $dbList")
                } else {
                    withContext(Dispatchers.Main) {
                        _dbList.value = emptyList()
                    }
                    Log.d("DbSetupViewModel", "No saved DB list found")
                }
            } catch (e: Exception) {
                Log.e("DbSetupViewModel", "Error loading DB list: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _dbList.value = emptyList()
                }
            }
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
                e.printStackTrace()
            }
        }
    }

    fun fetchSmartLinkDatabases(url: String) {
        viewModelScope.launch {
            try {
                smartLinkDbHelper.fetchDatabases(url)
            } catch (e: Exception) {
                _errorEvent.value = e.message ?: "Failed to fetch SmartLink databases"
            }
        }
    }

    suspend fun downloadSmartLinkDatabase(
        dbInfo: SmartLinkDbInfo,
        onProgress: (Int, Long, Long?) -> Unit
    ): DbItem? {
        return withContext(viewModelScope.coroutineContext) {
            try {
                smartLinkDbHelper.downloadDatabase(dbInfo, onProgress)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _errorEvent.value = e.message
                }
                null
            }
        }
    }


    fun initializeSQLiteCustomHelper(dbUri: Uri, directPath: String?) {
        sqliteCustomHelper = SQLiteCustomHelper(getApplication(), dbUri, directPath)
    }

    fun getCustomTableNames(): List<String>? {
        return sqliteCustomHelper?.getTableNames()
    }

    fun getCustomColumnNames(tableName: String): List<String>? {
        return sqliteCustomHelper?.getColumnNames(tableName)
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

        if (dbItem.dbType == DbType.SQLITE_FILE_CUSTOM && dbItem.columnMap == null) {
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
            cachedSizeInMB = if (dbItem.dbType == DbType.SQLITE_FILE_3WIFI || dbItem.dbType == DbType.SQLITE_FILE_CUSTOM) {
                getUpdatedCachedFileSize((selectedContentPath ?: dbItem.path).toUri())
            } else {
                dbItem.cachedSizeInMB
            }
        )

        currentList.add(newItem)
        _dbList.value = currentList
        updateMainApi()
        saveDbList()

        needDataRefresh = true

        Log.d("DbSetupViewModel", "Added DB item: $newItem")
        selectedContentPath = null
        selectedDirectPath = null
        selectedFileSize = 0f
    }

    fun getUpdatedCachedFileSize(uri: Uri): Float {
        val file = File(getApplication<Application>().cacheDir, "CacheDB/${getFileNameFromUri(uri)}")
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
        Log.d("DbSetupViewModel", "Database removed, setting refresh flag: id=${removedDb.id}, path=${removedDb.path}")

        removedDb.path.let { path ->
            val uri = path.toUri()
            SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), uri)
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
            val updatedItem = dbItem.copy(
                originalSizeInMB = getUpdatedOriginalFileSize(dbItem),
                cachedSizeInMB = getUpdatedCachedFileSize(dbItem.path.toUri())
            )
            currentList[index] = updatedItem
            _dbList.value = currentList
            saveDbList()
        }
    }

    private fun getUpdatedOriginalFileSize(dbItem: DbItem): Float {
        return when (dbItem.dbType) {
            DbType.SQLITE_FILE_3WIFI, DbType.SQLITE_FILE_CUSTOM -> {
                val uri = dbItem.path.toUri()
                val fileDescriptor = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                val fileSize = fileDescriptor?.statSize ?: 0
                fileDescriptor?.close()
                fileSize.toFloat() / (1024 * 1024)
            }
            else -> dbItem.originalSizeInMB
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
        return "$name.sqlite"
    }

    fun checkAndUpdateDatabases() {
        viewModelScope.launch {
            val currentList = _dbList.value ?: return@launch
            val updatedList = currentList.map { dbItem ->
                when (dbItem.dbType) {
                    DbType.SQLITE_FILE_3WIFI, DbType.SQLITE_FILE_CUSTOM -> {
                        val uri = dbItem.path.toUri()
                        val originalSize = getUpdatedOriginalFileSize(dbItem)
                        if (originalSize != dbItem.originalSizeInMB) {
                            SQLite3WiFiHelper.deleteCachedDatabase(getApplication(), uri)
                            val helper = SQLite3WiFiHelper(getApplication(), uri, dbItem.directPath)
                            val cachedSize = helper.getSelectedFileSize()
                            dbItem.copy(originalSizeInMB = originalSize, cachedSizeInMB = cachedSize)
                        } else {
                            dbItem
                        }
                    }
                    else -> dbItem
                }
            }
            _dbList.postValue(updatedList)
            saveDbList()
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

    fun getTableNames(): List<String>? {
        return sqlite3WiFiHelper?.getTableNames()
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

    override fun onCleared() {
        super.onCleared()
        sqlite3WiFiHelper?.close()
        downloadJob.cancel()
    }
}