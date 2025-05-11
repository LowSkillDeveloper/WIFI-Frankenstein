package com.lsd.wififrankenstein.ui.updates

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.pow

class UpdatesViewModel(application: Application) : AndroidViewModel(application) {

    private val dbSetupViewModel = DbSetupViewModel(application)

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
    val smartLinkDbUpdates: StateFlow<List<SmartLinkDbUpdateInfo>> = _smartLinkDbUpdates.asStateFlow()

    fun checkUpdates() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = URL("https://github.com/LowSkillDeveloper/WIFI-Frankenstein/raw/service/updates.json").readText()
                val json = JSONObject(jsonString)

                val appInfo = json.getJSONObject("app")
                _appUpdateInfo.value = AppUpdateInfo(
                    currentVersion = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)?.versionName ?: "Unknown",
                    newVersion = appInfo.getString("version"),
                    changelogUrl = appInfo.getString("changelog_url"),
                    downloadUrl = appInfo.getString("download_url")
                )

                val fileUpdates = json.getJSONArray("files")
                val updateInfoList = (0 until fileUpdates.length()).map { i ->
                    val fileInfo = fileUpdates.getJSONObject(i)
                    val fileName = fileInfo.getString("name")
                    val serverVersion = fileInfo.getString("version")
                    val downloadUrl = fileInfo.getString("download_url")

                    val localFile = File(context.filesDir, fileName)
                    val localVersion = getFileVersion(context, fileName)
                    val localSize = if (localFile.exists()) localFile.length() else 0

                    FileUpdateInfo(
                        fileName = fileName,
                        localVersion = localVersion,
                        serverVersion = serverVersion,
                        localSize = formatFileSize(localSize),
                        downloadUrl = downloadUrl,
                        needsUpdate = serverVersion != localVersion
                    )
                }

                _updateInfo.value = updateInfoList
            } catch (e: Exception) {
                Log.e("UpdatesViewModel", "Error checking updates", e)
                _errorMessage.value = context.getString(R.string.error_checking_updates, e.message)
            }
        }
    }

    fun checkSmartLinkDbUpdates() {
        viewModelScope.launch {
            try {
                val dbList = dbSetupViewModel.getSmartLinkDatabases()
                Log.d("UpdatesViewModel", "SmartLinkDb list: $dbList")

                val updateInfoList = dbList.mapNotNull { dbItem ->
                    val updateInfos = fetchUpdateInfo(dbItem.updateUrl)
                    updateInfos.find { it.id == dbItem.idJson }?.let { info ->
                        SmartLinkDbUpdateInfo(
                            dbItem = dbItem,
                            serverVersion = info.version,
                            downloadUrl = info.downloadUrl,
                            needsUpdate = info.version != dbItem.version
                        )
                    }
                }

                Log.d("UpdatesViewModel", "SmartLinkDb updates: $updateInfoList")
                _smartLinkDbUpdates.value = updateInfoList
            } catch (e: Exception) {
                Log.e("UpdatesViewModel", "Error checking SmartLink DB updates", e)
                _errorMessage.value = e.message ?: "Failed to check SmartLink DB updates"
            }
        }
    }

    private suspend fun fetchUpdateInfo(updateUrl: String?): List<SmartLinkDbInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val response = URL(updateUrl).readText()
                val json = JSONObject(response)
                val databasesArray = json.getJSONArray("databases")
                val result = mutableListOf<SmartLinkDbInfo>()

                for (i in 0 until databasesArray.length()) {
                    val dbInfo = databasesArray.getJSONObject(i)
                    result.add(SmartLinkDbInfo(
                        id = dbInfo.getString("id"),
                        name = dbInfo.getString("name"),
                        downloadUrl = dbInfo.getString("downloadUrl"),
                        version = dbInfo.getString("version"),
                        type = dbInfo.getString("type")
                    ))
                }
                result
            } catch (e: Exception) {
                Log.e("SmartLinkDbHelper", "Error fetching update info", e)
                emptyList()
            }
        }
    }

    fun updateFile(fileInfo: FileUpdateInfo) {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, fileInfo.fileName)
                val url = URL(fileInfo.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                val fileSize = connection.contentLength

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytesRead = 0
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val progress = (totalBytesRead * 100 / fileSize)
                            _fileUpdateProgress.value = _fileUpdateProgress.value + (fileInfo.fileName to progress)
                        }
                    }
                }
                updateFileVersion(context, fileInfo.fileName, fileInfo.serverVersion)
                _fileUpdateProgress.value = _fileUpdateProgress.value + (fileInfo.fileName to 100)
                checkUpdates()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_updating_file, fileInfo.fileName, e.message)
            }
        }
    }

    fun revertFile(fileInfo: FileUpdateInfo) {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assetManager = context.assets
                val file = File(context.filesDir, fileInfo.fileName)

                assetManager.open(fileInfo.fileName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                updateFileVersion(context, fileInfo.fileName, "1.0")
                _fileUpdateProgress.value = mapOf(fileInfo.fileName to 100)
                checkUpdates()
            } catch (e: Exception) {
                _errorMessage.value = "Error reverting file ${fileInfo.fileName}: ${e.message}"
            }
        }
    }

    fun updateAllFiles() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _updateInfo.value.filter { it.needsUpdate }.forEach { fileInfo ->
                    updateFile(fileInfo)
                }
                checkUpdates()
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_updating_files, e.message)
            }
        }
    }

    fun getChangelog() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val changelogUrl = _appUpdateInfo.value?.changelogUrl
                    ?: throw Exception(context.getString(R.string.error_changelog_url_not_available))
                val changelog = URL(changelogUrl).readText()
                _changelog.value = changelog
            } catch (e: Exception) {
                _errorMessage.value = context.getString(R.string.error_fetching_changelog, e.message)
            }
        }
    }

    @SuppressLint("Range")
    fun updateApp() {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadUrl = _appUpdateInfo.value?.downloadUrl
                    ?: throw Exception("Download URL not available")

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
                        val progress = (bytesDownloaded * 100L / bytesTotal).toInt()
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

    private fun getFileVersion(context: Context, fileName: String): String {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionFile = File(context.filesDir, versionFileName)
        return if (versionFile.exists()) {
            try {
                JSONObject(versionFile.readText()).getString("version")
            } catch (_: Exception) {
                "1.0"
            }
        } else {
            createVersionFile(context, fileName, "1.0")
            "1.0"
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
    val downloadUrl: String,
    val version: String,
    val type: String
)
