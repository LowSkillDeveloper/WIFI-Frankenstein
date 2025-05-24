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
import com.lsd.wififrankenstein.ui.dbsetup.SmartLinkDbInfo
import kotlinx.serialization.json.Json

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
            val context = this.context

            val dbSetupPrefs = context.getSharedPreferences("db_setup_prefs", Context.MODE_PRIVATE)
            val dbListJson = dbSetupPrefs.getString("db_list", null) ?: return emptyList()

            val installedDbs = try {
                Json.decodeFromString<List<DbItem>>(dbListJson).filter {
                    it.smartlinkType != null && !it.smartlinkType.isBlank() &&
                            !it.updateUrl.isNullOrBlank() &&
                            !it.idJson.isNullOrBlank()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing installed databases", e)
                emptyList()
            }

            Log.d(TAG, "Found ${installedDbs.size} SmartLink databases to check for updates")
            installedDbs.forEach { db ->
                Log.d(TAG, "SmartLink DB: ${db.type}, smartlinkType: ${db.smartlinkType}, version: ${db.version}")
            }

            installedDbs.mapNotNull { installedDb ->
                try {
                    val updateUrl = installedDb.updateUrl ?: return@mapNotNull null
                    val response = URL(updateUrl).readText()
                    val updateJson = JSONObject(response)
                    val databasesArray = updateJson.getJSONArray("databases")

                    for (i in 0 until databasesArray.length()) {
                        val dbInfo = databasesArray.getJSONObject(i)
                        val serverId = dbInfo.getString("id")

                        if (serverId == installedDb.idJson) {
                            val serverVersion = dbInfo.getString("version")
                            val localVersion = installedDb.version ?: "1.0"

                            Log.d(TAG, "Checking DB ${installedDb.type}: local=$localVersion, server=$serverVersion")

                            val info = SmartLinkDbInfo(
                                id = serverId,
                                name = dbInfo.getString("name"),
                                downloadUrl = dbInfo.optString("downloadUrl", null),
                                downloadUrl1 = dbInfo.optString("downloadUrl1", null),
                                downloadUrl2 = dbInfo.optString("downloadUrl2", null),
                                downloadUrl3 = dbInfo.optString("downloadUrl3", null),
                                downloadUrl4 = dbInfo.optString("downloadUrl4", null),
                                downloadUrl5 = dbInfo.optString("downloadUrl5", null),
                                version = serverVersion,
                                type = dbInfo.getString("type")
                            )

                            return@mapNotNull SmartLinkDbUpdateInfo(
                                dbItem = installedDb,
                                serverVersion = serverVersion,
                                downloadUrl = info.getDownloadUrls().firstOrNull() ?: "",
                                needsUpdate = serverVersion != localVersion,
                                isUpdating = false,
                                updateProgress = 0
                            )
                        }
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking updates for database ${installedDb.type}", e)
                    null
                }
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