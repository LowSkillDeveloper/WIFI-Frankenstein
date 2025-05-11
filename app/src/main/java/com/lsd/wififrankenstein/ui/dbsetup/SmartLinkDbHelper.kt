package com.lsd.wififrankenstein.ui.dbsetup

import android.content.ContentValues.TAG
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
data class SmartLinkDbInfo(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val version: String,
    val type: String
)

@Serializable
data class SmartLinkResponse(
    val databases: List<SmartLinkDbInfo>
)

class SmartLinkDbHelper(private val context: Context) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val _databases = MutableLiveData<List<SmartLinkDbInfo>>()
    val databases: LiveData<List<SmartLinkDbInfo>> = _databases

    private lateinit var jsonUrl: String

    suspend fun fetchDatabases(url: String) {
        withContext(Dispatchers.IO) {
            jsonUrl = url
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body.string()
                val smartLinkResponse = json.decodeFromString<SmartLinkResponse>(jsonString)
                _databases.postValue(smartLinkResponse.databases)
            } else {
                throw Exception("Failed to fetch database info")
            }
        }
    }

    suspend fun downloadDatabase(
        dbInfo: SmartLinkDbInfo,
        progressCallback: (progress: Int, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): DbItem {
        return withContext(Dispatchers.IO) {
            try {
                ensureActive()
                Log.d(TAG, "Starting download for ${dbInfo.name}")
                val request = Request.Builder().url(dbInfo.downloadUrl).build()
                val response = client.newCall(request).execute()
                Log.d(TAG, "Response code: ${response.code}")

                if (!response.isSuccessful) {
                    throw Exception("Server returned code ${response.code}")
                }

                val fileSize = response.header("Content-Length")?.toLongOrNull()
                val fileName = "${dbInfo.id}_${dbInfo.version}.db"
                val file = File(context.filesDir, fileName)
                var downloadedSize = 0L

                FileOutputStream(file).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (isActive) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break

                            output.write(buffer, 0, bytesRead)
                            downloadedSize += bytesRead
                            val progress = if (fileSize != null) {
                                ((downloadedSize.toDouble() / fileSize.toDouble()) * 100).toInt()
                            } else 0
                            ensureActive()
                            withContext(Dispatchers.Main) {
                                progressCallback(progress, downloadedSize, fileSize)
                            }
                        }
                    }
                }

                Log.d(TAG, "Download completed. Size: $downloadedSize bytes")

                val dbType = detectDbType(file)
                val actualFileSize = file.length().toFloat() / (1024 * 1024)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

                return@withContext DbItem(
                    id = UUID.randomUUID().toString(),
                    path = uri.toString(),
                    directPath = file.absolutePath,
                    type = dbInfo.name,
                    dbType = dbType,
                    originalSizeInMB = actualFileSize,
                    cachedSizeInMB = actualFileSize,
                    idJson = dbInfo.id,
                    version = dbInfo.version,
                    updateUrl = jsonUrl,
                    smartlinkType = dbInfo.type,
                    tableName = null,
                    columnMap = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                throw e
            }
        }
    }

    private suspend fun detectDbType(file: File): DbType {
        return withContext(Dispatchers.IO) {
            try {
                val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursor.getString(0))
                        }
                    }
                }
                db.close()
                if (tables.contains("geo") && (tables.contains("nets") || tables.contains("base"))) {
                    DbType.SQLITE_FILE_3WIFI
                } else {
                    DbType.SQLITE_FILE_CUSTOM
                }
            } catch (_: Exception) {
                DbType.SQLITE_FILE_CUSTOM
            }
        }
    }

    suspend fun updateDatabase(
        dbItem: DbItem,
        downloadUrl: String,
        newVersion: String,
        progressCallback: (Int) -> Unit
    ): DbItem {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val fileName = "${dbItem.idJson}_$newVersion.db"
                val file = File(context.filesDir, fileName)

                val body = response.body
                val contentLength = body.contentLength()
                var bytesWritten = 0L

                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            bytesWritten += bytes
                            val progress = if (contentLength > 0) {
                                (bytesWritten * 100 / contentLength).toInt()
                            } else {
                                -1
                            }
                            progressCallback(progress)
                        }
                    }
                }

                val fileSize = file.length().toFloat() / (1024 * 1024) // Size in MB
                val directPath = file.absolutePath

                dbItem.copy(
                    path = "content://${context.packageName}.fileprovider/databases/$fileName",
                    directPath = directPath,
                    originalSizeInMB = fileSize,
                    cachedSizeInMB = fileSize,
                    version = newVersion
                )
            } else {
                throw Exception("Failed to download updated database")
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}