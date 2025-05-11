package com.lsd.wififrankenstein.ui.updates

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.math.log10
import kotlin.math.pow

class UpdateChecker(private val context: Context) {

    data class UpdateStatus(
        val appUpdate: AppUpdateInfo? = null,
        val fileUpdates: List<FileUpdateInfo> = emptyList(),
        val dbUpdates: List<SmartLinkDbUpdateInfo> = emptyList(),
        val hasUpdates: Boolean = false
    )

    companion object {
        private const val TAG = "UpdateChecker"
        private const val UPDATE_URL = "https://github.com/LowSkillDeveloper/WIFI-Frankenstein/raw/service/updates.json"
    }

    fun checkForUpdates(): Flow<UpdateStatus> = flow {
        val jsonString = URL(UPDATE_URL).readText()
        val json = JSONObject(jsonString)

        val appUpdate = checkAppUpdate(json)

        val fileUpdates = checkFileUpdates(json)

        val dbUpdates = checkDatabaseUpdates(json)

        val hasUpdates = appUpdate != null ||
                fileUpdates.any { it.needsUpdate } ||
                dbUpdates.any { it.needsUpdate }

        emit(UpdateStatus(
            appUpdate = appUpdate,
            fileUpdates = fileUpdates,
            dbUpdates = dbUpdates,
            hasUpdates = hasUpdates
        ))
    }
        .catch { e ->
            Log.e(TAG, "Error checking updates", e)
            emit(UpdateStatus())
        }
        .flowOn(Dispatchers.IO)

    private fun checkAppUpdate(json: JSONObject): AppUpdateInfo? {
        return try {
            val appInfo = json.getJSONObject("app")
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
                ?.versionName ?: "Unknown"
            val newVersion = appInfo.getString("version")

            if (currentVersion != newVersion) {
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    newVersion = newVersion,
                    changelogUrl = appInfo.getString("changelog_url"),
                    downloadUrl = appInfo.getString("download_url")
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking app update", e)
            null
        }
    }

    private fun checkFileUpdates(json: JSONObject): List<FileUpdateInfo> {
        return try {
            val fileUpdates = json.getJSONArray("files")
            (0 until fileUpdates.length()).mapNotNull { i ->
                val fileInfo = fileUpdates.getJSONObject(i)
                val fileName = fileInfo.getString("name")
                val serverVersion = fileInfo.getString("version")
                val downloadUrl = fileInfo.getString("download_url")

                val localFile = File(context.filesDir, fileName)
                val localVersion = getFileVersion(fileName)
                val localSize = if (localFile.exists()) localFile.length() else 0

                FileUpdateInfo(
                    fileName = fileName,
                    localVersion = localVersion,
                    serverVersion = serverVersion,
                    localSize = formatFileSize(localSize),
                    downloadUrl = downloadUrl,
                    needsUpdate = compareVersions(localVersion, serverVersion) < 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file updates", e)
            emptyList()
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toInt() }
        val parts2 = v2.split(".").map { it.toInt() }

        for (i in 0 until minOf(parts1.size, parts2.size)) {
            when {
                parts1[i] < parts2[i] -> return -1
                parts1[i] > parts2[i] -> return 1
            }
        }

        return when {
            parts1.size < parts2.size -> -1
            parts1.size > parts2.size -> 1
            else -> 0
        }
    }

    private fun checkDatabaseUpdates(json: JSONObject): List<SmartLinkDbUpdateInfo> {
        return try {
            val dbUpdates = json.optJSONArray("databases") ?: return emptyList()
            (0 until dbUpdates.length()).mapNotNull { i ->
                val dbInfo = dbUpdates.getJSONObject(i)
                val info = SmartLinkDbInfo(
                    id = dbInfo.getString("id"),
                    name = dbInfo.getString("name"),
                    downloadUrl = dbInfo.getString("downloadUrl"),
                    version = dbInfo.getString("version"),
                    type = dbInfo.getString("type")
                )

                val localVersion = getFileVersion("${info.id}.db")

                val dbItem = DbItem(
                    id = info.id,
                    path = info.downloadUrl,
                    directPath = null,
                    type = info.name,
                    dbType = if (info.type == "3wifi") DbType.SMARTLINK_SQLITE_FILE_3WIFI
                    else DbType.SMARTLINK_SQLITE_FILE_CUSTOM,
                    originalSizeInMB = 0f,
                    cachedSizeInMB = 0f,
                    version = localVersion,
                    idJson = info.id,
                    updateUrl = UPDATE_URL,
                    smartlinkType = info.type
                )

                SmartLinkDbUpdateInfo(
                    dbItem = dbItem,
                    serverVersion = info.version,
                    downloadUrl = info.downloadUrl,
                    needsUpdate = info.version != localVersion,
                    isUpdating = false,
                    updateProgress = 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking database updates", e)
            emptyList()
        }
    }

    private fun getFileVersion(fileName: String): String {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionFile = File(context.filesDir, versionFileName)
        return if (versionFile.exists()) {
            try {
                JSONObject(versionFile.readText()).getString("version")
            } catch (_: Exception) {
                "1.0"
            }
        } else {
            "1.0"
        }
    }

    fun getChangelog(appUpdateInfo: AppUpdateInfo): Flow<String> = flow {
        try {
            val changelog = URL(appUpdateInfo.changelogUrl).readText()
            emit(changelog)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching changelog", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return "%.2f %s".format(size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}