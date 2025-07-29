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
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URL
import java.util.UUID

@Serializable
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

                val downloadUrls = dbInfo.getDownloadUrls()
                val fileName = "${dbInfo.id}_${dbInfo.version}.db"
                val file = File(context.filesDir, fileName)

                if (dbInfo.isMultiPart()) {
                    downloadMultiPartArchive(downloadUrls, file, progressCallback)
                } else {
                    val downloadUrl = downloadUrls.first()
                    if (downloadUrl.endsWith(".zip", true) || downloadUrl.endsWith(".7z", true)) {
                        downloadAndExtractArchive(downloadUrl, file, progressCallback)
                    } else {
                        downloadDirectFile(downloadUrl, file, progressCallback)
                    }
                }

                Log.d(TAG, "Download completed. Size: ${file.length()} bytes")

                val dbType = detectDbType(file)
                val actualFileSize = file.length().toFloat() / (1024 * 1024)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

                return@withContext DbItem(
                    id = UUID.randomUUID().toString(),
                    path = uri.toString(),
                    directPath = file.absolutePath,
                    type = dbInfo.name,
                    dbType = if (dbType == DbType.SQLITE_FILE_3WIFI) DbType.SMARTLINK_SQLITE_FILE_3WIFI else DbType.SMARTLINK_SQLITE_FILE_CUSTOM,
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

    private suspend fun downloadDirectFile(
        url: String,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Server returned code ${response.code}")
            }

            val fileSize = response.header("Content-Length")?.toLongOrNull()
            var downloadedSize = 0L

            FileOutputStream(outputFile).use { output ->
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
        }
    }

    private suspend fun downloadAndExtractArchive(
        url: String,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val archiveExtension = url.substringAfterLast('.', "").lowercase()
            val tempArchive = File(context.cacheDir, "${outputFile.nameWithoutExtension}.$archiveExtension")
            downloadDirectFile(url, tempArchive, progressCallback)

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchive(tempArchive, outputFile)
            tempArchive.delete()
        }
    }

    private suspend fun downloadMultiPartArchive(
        urls: List<String>,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val tempParts = mutableListOf<File>()
            var totalDownloaded = 0L

            urls.forEachIndexed { index, url ->
                val partFile = File(context.cacheDir, "part_${index + 1}.tmp")
                tempParts.add(partFile)

                withContext(Dispatchers.Main) {
                    progressCallback(-2, index.toLong() + 1, urls.size.toLong())
                }

                downloadDirectFile(url, partFile) { progress, downloaded, total ->
                    val overallProgress = ((index * 100 + progress) / urls.size)
                    progressCallback(overallProgress, totalDownloaded + downloaded, null)
                }

                totalDownloaded += partFile.length()
            }

            withContext(Dispatchers.Main) {
                progressCallback(-3, 0, null)
            }

            val firstUrl = urls.firstOrNull() ?: throw Exception("No URLs provided")
            val archiveExtension = when {
                firstUrl.contains(".zip.", ignoreCase = true) -> "zip"
                firstUrl.contains(".7z.", ignoreCase = true) -> "7z"
                else -> {
                    val fullName = firstUrl.substringAfterLast('/')
                    val extensionMatch = Regex("\\.(zip|7z)\\.[0-9]+$", RegexOption.IGNORE_CASE).find(fullName)
                    extensionMatch?.groupValues?.get(1)?.lowercase() ?: "zip"
                }
            }
            val mergedArchive = File(context.cacheDir, "${outputFile.nameWithoutExtension}.$archiveExtension")
            mergeFiles(tempParts, mergedArchive)

            tempParts.forEach { it.delete() }

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchive(mergedArchive, outputFile)
            mergedArchive.delete()
        }
    }

    private fun mergeFiles(parts: List<File>, outputFile: File) {
        RandomAccessFile(outputFile, "rw").use { output ->
            output.channel.use { outChannel ->
                parts.forEach { part ->
                    RandomAccessFile(part, "r").use { input ->
                        input.channel.use { inChannel ->
                            val size = inChannel.size()
                            var position = 0L
                            while (position < size) {
                                position += inChannel.transferTo(position, size - position, outChannel)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractArchive(archiveFile: File, outputFile: File) {
        val extension = archiveFile.extension.lowercase()

        when {
            extension == "7z" -> extract7z(archiveFile, outputFile)
            extension == "zip" -> extractZip(archiveFile, outputFile)
            else -> throw Exception("Unsupported archive format: $extension")
        }
    }

    private fun extract7z(archiveFile: File, outputFile: File) {
        SevenZFile(archiveFile).use { sevenZFile ->
            var entry: ArchiveEntry? = sevenZFile.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                    return
                }
                entry = sevenZFile.nextEntry
            }
            throw Exception("No database file found in archive")
        }
    }

    private fun extractZip(archiveFile: File, outputFile: File) {
        ZipArchiveInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    FileOutputStream(outputFile).use { output ->
                        zipInput.copyTo(output, BUFFER_SIZE)
                    }
                    return
                }
                entry = zipInput.nextEntry
            }
            throw Exception("No database file found in archive")
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
            val updateUrl = dbItem.updateUrl ?: throw Exception("Update URL not available")
            val response = URL(updateUrl).readText()
            val json = JSONObject(response)
            val databasesArray = json.getJSONArray("databases")

            var dbInfo: SmartLinkDbInfo? = null
            for (i in 0 until databasesArray.length()) {
                val info = databasesArray.getJSONObject(i)
                if (info.getString("id") == dbItem.idJson) {
                    dbInfo = SmartLinkDbInfo(
                        id = info.getString("id"),
                        name = info.getString("name"),
                        downloadUrl = info.optString("downloadUrl", null),
                        downloadUrl1 = info.optString("downloadUrl1", null),
                        downloadUrl2 = info.optString("downloadUrl2", null),
                        downloadUrl3 = info.optString("downloadUrl3", null),
                        downloadUrl4 = info.optString("downloadUrl4", null),
                        downloadUrl5 = info.optString("downloadUrl5", null),
                        version = info.getString("version"),
                        type = info.getString("type")
                    )
                    break
                }
            }

            if (dbInfo == null) {
                throw Exception("Database info not found in update JSON")
            }

            val fileName = "${dbItem.idJson}_$newVersion.db"
            val file = File(context.filesDir, fileName)

            val downloadUrls = dbInfo.getDownloadUrls()

            if (dbInfo.isMultiPart()) {
                downloadMultiPartArchive(downloadUrls, file) { progress, _, _ ->
                    progressCallback(progress)
                }
            } else {
                val url = downloadUrls.first()
                if (url.endsWith(".zip", true) || url.endsWith(".7z", true)) {
                    downloadAndExtractArchive(url, file) { progress, _, _ ->
                        progressCallback(progress)
                    }
                } else {
                    downloadDirectFile(url, file) { progress, _, _ ->
                        progressCallback(progress)
                    }
                }
            }

            val fileSize = file.length().toFloat() / (1024 * 1024)
            val directPath = file.absolutePath

            dbItem.copy(
                path = "content://${context.packageName}.fileprovider/databases/$fileName",
                directPath = directPath,
                originalSizeInMB = fileSize,
                cachedSizeInMB = fileSize,
                version = newVersion
            )
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}