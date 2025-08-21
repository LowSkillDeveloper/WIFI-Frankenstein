package com.lsd.wififrankenstein.ui.updates

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.NetworkClient
import com.lsd.wififrankenstein.network.NetworkUtils
import com.lsd.wififrankenstein.service.DownloadService
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow

class UpdatesViewModel(application: Application) : AndroidViewModel(application) {

    private lateinit var dbSetupViewModel: DbSetupViewModel

    private val _updateInfo = MutableStateFlow<List<FileUpdateInfo>>(emptyList())
    val updateInfo: StateFlow<List<FileUpdateInfo>> = _updateInfo.asStateFlow()

    private val _appUpdateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val appUpdateInfo: StateFlow<AppUpdateInfo?> = _appUpdateInfo.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _changelog = MutableStateFlow<String?>(null)
    val changelog: StateFlow<String?> = _changelog.asStateFlow()

    private val _appDownloadId = MutableStateFlow<Long>(-1)
    val appDownloadId: StateFlow<Long> = _appDownloadId.asStateFlow()

    private val _openUrlInBrowser = MutableStateFlow<String?>(null)
    val openUrlInBrowser: StateFlow<String?> = _openUrlInBrowser.asStateFlow()

    private val _fileUpdateProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val fileUpdateProgress: StateFlow<Map<String, Int>> = _fileUpdateProgress.asStateFlow()

    private val _appUpdateProgress = MutableStateFlow(0)
    val appUpdateProgress: StateFlow<Int> = _appUpdateProgress.asStateFlow()

    private val _smartLinkDbUpdates = MutableStateFlow<List<SmartLinkDbUpdateInfo>>(emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()

    private val _hasActiveDownloads = MutableStateFlow(false)
    val hasActiveDownloads: StateFlow<Boolean> = _hasActiveDownloads.asStateFlow()

    val smartLinkDbUpdates: StateFlow<List<SmartLinkDbUpdateInfo>> = _smartLinkDbUpdates.asStateFlow()

    private val networkClient = NetworkClient.getInstance(getApplication())
    private val downloadProgressMap = ConcurrentHashMap<String, Int>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.BROADCAST_DOWNLOAD_PROGRESS -> {
                    val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: return
                    val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                    updateDownloadProgress(fileName, progress)
                }
                DownloadService.BROADCAST_DOWNLOAD_COMPLETE -> {
                    val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: return
                    onDownloadComplete(fileName)
                }
                DownloadService.BROADCAST_DOWNLOAD_ERROR -> {
                    val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: return
                    val error = intent.getStringExtra(DownloadService.EXTRA_ERROR_MESSAGE) ?: ""
                    onDownloadError(fileName, error)
                }
                DownloadService.BROADCAST_DOWNLOAD_CANCELLED -> {
                    val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME) ?: return
                    onDownloadCancelled(fileName)
                }
            }
        }
    }

    init {
        registerDownloadReceiver()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter().apply {
            addAction(DownloadService.BROADCAST_DOWNLOAD_PROGRESS)
            addAction(DownloadService.BROADCAST_DOWNLOAD_COMPLETE)
            addAction(DownloadService.BROADCAST_DOWNLOAD_ERROR)
            addAction(DownloadService.BROADCAST_DOWNLOAD_CANCELLED)
        }
        LocalBroadcastManager.getInstance(getApplication()).registerReceiver(downloadReceiver, filter)
    }

    fun checkUpdates() {
        _isLoading.value = true
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch {
            if (!NetworkUtils.hasActiveConnection(context)) {
                _errorMessage.value = context.getString(R.string.error_no_internet)
                _isLoading.value = false
                return@launch
            }

            try {
                val jsonString = networkClient.get("https://github.com/LowSkillDeveloper/WIFI-Frankenstein/raw/service/updates.json")
                val json = JSONObject(jsonString)

                _appUpdateInfo.value = AppUpdateInfo(
                    currentVersion = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)?.versionName ?: "Unknown",
                    newVersion = json.getJSONObject("app").getString("version"),
                    changelogUrl = json.getJSONObject("app").getString("changelog_url"),
                    downloadUrl = json.getJSONObject("app").getString("download_url")
                )

                val fileUpdates = json.getJSONArray("files")
                val serverFilesList = (0 until fileUpdates.length()).map { i ->
                    val fileInfo = fileUpdates.getJSONObject(i)
                    val fileName = fileInfo.getString("name")
                    val serverVersion = fileInfo.getString("version")
                    val localFile = File(context.filesDir, fileName)
                    val localVersion = getFileVersion(context, fileName)

                    FileUpdateInfo(
                        fileName = fileName,
                        localVersion = localVersion,
                        serverVersion = serverVersion,
                        localSize = formatFileSize(if (localFile.exists()) localFile.length() else 0),
                        downloadUrl = fileInfo.getString("download_url"),
                        needsUpdate = serverVersion != localVersion
                    )
                }.toMutableList()

                val routerKeygenFileName = "RouterKeygen.dic"
                val hasRouterKeygen = serverFilesList.any { it.fileName == routerKeygenFileName }

                if (!hasRouterKeygen) {
                    val localFile = File(context.filesDir, routerKeygenFileName)
                    val localVersion = getFileVersion(context, routerKeygenFileName)
                    serverFilesList.add(
                        FileUpdateInfo(
                            fileName = routerKeygenFileName,
                            localVersion = localVersion,
                            serverVersion = "0.0",
                            localSize = formatFileSize(if (localFile.exists()) localFile.length() else 0),
                            downloadUrl = "",
                            needsUpdate = false
                        )
                    )
                }

                _updateInfo.value = serverFilesList
                _errorMessage.value = null
                _isLoading.value = false
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_connection_failed)
                _isLoading.value = false
            }
        }
    }

    fun setDbSetupViewModel(viewModel: DbSetupViewModel) {
        dbSetupViewModel = viewModel
    }

    fun checkSmartLinkDbUpdates() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                Log.d("UpdatesViewModel", "Starting SmartLink DB updates check...")
                val dbList = dbSetupViewModel.getSmartLinkDatabases()
                Log.d("UpdatesViewModel", "Found ${dbList.size} SmartLink databases")

                val updateInfoList = dbList.mapNotNull { dbItem ->
                    Log.d("UpdatesViewModel", "Checking updates for: ${dbItem.type}, updateUrl: ${dbItem.updateUrl}")

                    if (dbItem.updateUrl.isNullOrBlank()) {
                        Log.w("UpdatesViewModel", "No updateUrl for database: ${dbItem.type}")
                        return@mapNotNull null
                    }

                    val updateInfos = fetchUpdateInfo(dbItem.updateUrl)
                    val matchingInfo = updateInfos.find { it.id == dbItem.idJson }

                    if (matchingInfo == null) {
                        Log.w("UpdatesViewModel", "No matching info found for database: ${dbItem.type} with id: ${dbItem.idJson}")
                        return@mapNotNull null
                    }

                    val needsUpdate = matchingInfo.version != dbItem.version
                    Log.d("UpdatesViewModel", "Database ${dbItem.type}: local=${dbItem.version}, server=${matchingInfo.version}, needsUpdate=$needsUpdate")

                    SmartLinkDbUpdateInfo(
                        dbItem = dbItem,
                        serverVersion = matchingInfo.version,
                        downloadUrl = matchingInfo.getDownloadUrls().firstOrNull() ?: "",
                        needsUpdate = needsUpdate
                    )
                }

                Log.d("UpdatesViewModel", "SmartLink DB update results: ${updateInfoList.size} items, ${updateInfoList.count { it.needsUpdate }} need updates")
                _smartLinkDbUpdates.value = updateInfoList
                _isLoading.value = false
            } catch (e: Exception) {
                Log.e("UpdatesViewModel", "Error checking SmartLink DB updates", e)
                _errorMessage.value = e.message ?: "Failed to check SmartLink DB updates"
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchUpdateInfo(updateUrl: String?): List<SmartLinkDbInfo> {
        return withContext(Dispatchers.IO) {
            if (updateUrl.isNullOrBlank()) return@withContext emptyList()

            try {
                val response = networkClient.get(updateUrl)
                val json = JSONObject(response)
                val databasesArray = json.getJSONArray("databases")

                (0 until databasesArray.length()).map { i ->
                    val dbInfo = databasesArray.getJSONObject(i)
                    SmartLinkDbInfo(
                        id = dbInfo.getString("id"),
                        name = dbInfo.getString("name"),
                        downloadUrl = dbInfo.optString("downloadUrl", null),
                        downloadUrl1 = dbInfo.optString("downloadUrl1", null),
                        downloadUrl2 = dbInfo.optString("downloadUrl2", null),
                        downloadUrl3 = dbInfo.optString("downloadUrl3", null),
                        downloadUrl4 = dbInfo.optString("downloadUrl4", null),
                        downloadUrl5 = dbInfo.optString("downloadUrl5", null),
                        version = dbInfo.getString("version"),
                        type = dbInfo.getString("type")
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun updateFile(fileInfo: FileUpdateInfo) {
        val context = getApplication<Application>().applicationContext

        if (_activeDownloads.value.contains(fileInfo.fileName)) {
            return
        }

        if (fileInfo.downloadUrl.isBlank()) {
            _errorMessage.value = context.getString(R.string.error_download_url_not_available, fileInfo.fileName)
            return
        }

        _activeDownloads.value = _activeDownloads.value + fileInfo.fileName
        updateHasActiveDownloads()

        DownloadService.startDownload(context, fileInfo.fileName, fileInfo.downloadUrl, fileInfo.serverVersion)
    }

    fun cancelDownload(fileName: String) {
        val context = getApplication<Application>().applicationContext
        DownloadService.cancelDownload(context, fileName)
    }

    fun revertFile(fileInfo: FileUpdateInfo) {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileInfo.fileName)

                if (fileInfo.fileName == "RouterKeygen.dic") {
                    if (file.exists()) {
                        file.delete()
                    }
                    updateFileVersion(context, fileInfo.fileName, "0.0")
                } else {
                    val assetManager = context.assets
                    assetManager.open(fileInfo.fileName).use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    updateFileVersion(context, fileInfo.fileName, "1.0")
                }

                checkUpdates()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_reverting_file, fileInfo.fileName, e.message)
            }
        }
    }

    fun updateAllFiles() {
        val filesToUpdate = _updateInfo.value.filter { it.needsUpdate && !_activeDownloads.value.contains(it.fileName) }
        filesToUpdate.forEach { fileInfo ->
            updateFile(fileInfo)
        }
    }

    fun cancelAllDownloads() {
        val context = getApplication<Application>().applicationContext
        DownloadService.cancelAllDownloads(context)
    }

    fun getChangelog() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch {
            try {
                val changelogUrl = _appUpdateInfo.value?.changelogUrl
                if (changelogUrl.isNullOrBlank()) {
                    _errorMessage.value = context.getString(R.string.error_changelog_url_not_available)
                    return@launch
                }
                _changelog.value = networkClient.get(changelogUrl)
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_fetching_changelog, "Network error")
            }
        }
    }

    @SuppressLint("Range")
    fun updateApp() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadUrl = _appUpdateInfo.value?.downloadUrl
                    ?: throw Exception(context.getString(R.string.error_download_url_not_available_generic))

                if (URLUtil.isValidUrl(downloadUrl) && !downloadUrl.endsWith(".apk", ignoreCase = true)) {
                    _openUrlInBrowser.value = downloadUrl
                } else {
                    val request = DownloadManager.Request(downloadUrl.toUri())
                        .setTitle(context.getString(R.string.app_update_title))
                        .setDescription(context.getString(R.string.downloading_new_version))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "app-update.apk")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)

                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val downloadId = downloadManager.enqueue(request)
                    _appDownloadId.value = downloadId

                    var downloading = true
                    while (downloading) {
                        val q = DownloadManager.Query()
                        q.setFilterById(downloadId)
                        val cursor = downloadManager.query(q)
                        cursor.moveToFirst()
                        val bytesDownloaded = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val bytesTotal = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                        }
                        val progress = if (bytesTotal > 0) (bytesDownloaded * 100L / bytesTotal).toInt() else 0
                        _appUpdateProgress.value = progress
                        cursor.close()
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_starting_app_update, e.message)
            }
        }
    }

    fun updateSmartLinkDb(updateInfo: SmartLinkDbUpdateInfo) {
        viewModelScope.launch {
            try {
                val updatedList = _smartLinkDbUpdates.value.toMutableList()
                val index = updatedList.indexOfFirst { it.dbItem.id == updateInfo.dbItem.id }
                if (index != -1) {
                    updatedList[index] = updateInfo.copy(isUpdating = true, updateProgress = 0)
                    _smartLinkDbUpdates.value = updatedList
                }

                dbSetupViewModel.updateSmartLinkDatabase(
                    updateInfo.dbItem,
                    updateInfo.downloadUrl,
                    updateInfo.serverVersion
                ) { progress ->
                    viewModelScope.launch {
                        val currentList = _smartLinkDbUpdates.value.toMutableList()
                        val currentIndex = currentList.indexOfFirst { it.dbItem.id == updateInfo.dbItem.id }
                        if (currentIndex != -1) {
                            currentList[currentIndex] = currentList[currentIndex].copy(updateProgress = progress)
                            _smartLinkDbUpdates.value = currentList
                        }
                    }
                }

                checkSmartLinkDbUpdates()
            } catch (e: Exception) {
                Log.e("UpdatesViewModel", "Error updating SmartLink database", e)
                _errorMessage.value = e.message
            }
        }
    }

    private fun updateDownloadProgress(fileName: String, progress: Int) {
        downloadProgressMap[fileName] = progress
        _fileUpdateProgress.value = downloadProgressMap.toMap()
    }

    private fun onDownloadComplete(fileName: String) {
        _activeDownloads.value = _activeDownloads.value - fileName
        downloadProgressMap.remove(fileName)
        _fileUpdateProgress.value = downloadProgressMap.toMap()
        updateHasActiveDownloads()
        checkUpdates()
    }

    private fun onDownloadError(fileName: String, errorMessage: String) {
        _activeDownloads.value = _activeDownloads.value - fileName
        downloadProgressMap.remove(fileName)
        _fileUpdateProgress.value = downloadProgressMap.toMap()
        updateHasActiveDownloads()
        _errorMessage.value = errorMessage
    }

    private fun onDownloadCancelled(fileName: String) {
        _activeDownloads.value = _activeDownloads.value - fileName
        downloadProgressMap.remove(fileName)
        _fileUpdateProgress.value = downloadProgressMap.toMap()
        updateHasActiveDownloads()
    }

    private fun updateHasActiveDownloads() {
        _hasActiveDownloads.value = _activeDownloads.value.isNotEmpty()
    }

    private fun getFileVersion(context: Context, fileName: String): String {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionFile = File(context.filesDir, versionFileName)
        val actualFile = File(context.filesDir, fileName)

        return if (versionFile.exists()) {
            try {
                JSONObject(versionFile.readText()).getString("version")
            } catch (_: Exception) {
                if (fileName == "RouterKeygen.dic") "0.0" else "1.0"
            }
        } else {
            val defaultVersion = if (fileName == "RouterKeygen.dic") {
                if (actualFile.exists()) "1.0" else "0.0"
            } else {
                "1.0"
            }
            createVersionFile(context, fileName, defaultVersion)
            defaultVersion
        }
    }

    private fun createVersionFile(context: Context, fileName: String, version: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionJson = JSONObject().put("version", version)
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use { output ->
            output.write(versionJson.toString().toByteArray())
        }
    }

    private fun updateFileVersion(context: Context, fileName: String, newVersion: String) {
        createVersionFile(context, fileName, newVersion)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return "%.2f %s".format(size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(downloadReceiver)
    }
}

data class FileUpdateInfo(
    val fileName: String,
    val localVersion: String,
    val serverVersion: String,
    val localSize: String,
    val downloadUrl: String,
    val needsUpdate: Boolean
)

data class AppUpdateInfo(
    val currentVersion: String,
    val newVersion: String,
    val changelogUrl: String,
    val downloadUrl: String
)

data class SmartLinkDbInfo(
    val id: String,
    val name: String,
    val downloadUrl: String? = null,
    val downloadUrl1: String? = null,
    val downloadUrl2: String? = null,
    val downloadUrl3: String? = null,
    val downloadUrl4: String? = null,
    val downloadUrl5: String? = null,
    val version: String,
    val type: String
) {
    fun getDownloadUrls(): List<String> {
        return listOfNotNull(downloadUrl, downloadUrl1, downloadUrl2, downloadUrl3, downloadUrl4, downloadUrl5)
    }

    fun isMultiPart(): Boolean {
        return downloadUrl == null && downloadUrl1 != null
    }
}