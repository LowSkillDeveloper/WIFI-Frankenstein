package com.lsd.wififrankenstein.ui.dbsetup

import android.content.ContentValues.TAG
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lsd.wififrankenstein.R
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
    val downloadUrls: List<String> = emptyList(),
    val version: String,
    val type: String
) {
    fun isMultiPart(): Boolean {
        return downloadUrls.size > 1
    }
}

@Serializable
data class SmartLinkResponse(
    val databases: List<SmartLinkDbInfo>
)

@Serializable
data class DownloadMetadata(
    val version: String,
    val totalSize: Long,
    val downloadedSize: Long,
    val timestamp: Long
)

class SmartLinkDbHelper(private val context: Context) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val _databases = MutableLiveData<List<SmartLinkDbInfo>>()
    val databases: LiveData<List<SmartLinkDbInfo>> = _databases

    private lateinit var jsonUrl: String

    private fun getMetadataFile(dbId: String): File {
        return File(context.cacheDir, "${dbId}_download.metadata")
    }

    private fun getTempFile(dbId: String, version: String): File {
        return File(context.cacheDir, "${dbId}_${version}.tmp")
    }

    private fun saveDownloadMetadata(dbId: String, metadata: DownloadMetadata) {
        val metadataFile = getMetadataFile(dbId)
        val jsonString = json.encodeToString(metadata)
        metadataFile.writeText(jsonString)
    }

    private fun loadDownloadMetadata(dbId: String): DownloadMetadata? {
        val metadataFile = getMetadataFile(dbId)
        return if (metadataFile.exists()) {
            try {
                val jsonText = metadataFile.readText()
                json.decodeFromString<DownloadMetadata>(jsonText)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading download metadata", e)
                null
            }
        } else {
            null
        }
    }

    private fun clearDownloadMetadata(dbId: String) {
        getMetadataFile(dbId).delete()
        File(context.cacheDir, "${dbId}_parts_status.json").delete()
        context.cacheDir.listFiles { file ->
            (file.name.startsWith("${dbId}_") && file.name.endsWith(".tmp")) ||
                    (file.name.startsWith("part_${dbId}_") && file.name.endsWith(".tmp"))
        }?.forEach { it.delete() }
    }

    private suspend fun checkResumeDownload(dbInfo: SmartLinkDbInfo): Pair<File?, Long> {
        val metadata = loadDownloadMetadata(dbInfo.id)
        val tempFile = getTempFile(dbInfo.id, dbInfo.version)

        return if (metadata != null && tempFile.exists()) {
            when {
                metadata.version != dbInfo.version -> {
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, context.getString(R.string.version_changed_restarting))
                    }
                    clearDownloadMetadata(dbInfo.id)
                    Pair(null, 0L)
                }
                tempFile.length() == metadata.downloadedSize -> {
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, context.getString(R.string.download_resumed, (metadata.downloadedSize * 100 / metadata.totalSize).toInt()))
                    }
                    Pair(tempFile, metadata.downloadedSize)
                }
                else -> {
                    clearDownloadMetadata(dbInfo.id)
                    Pair(null, 0L)
                }
            }
        } else {
            Pair(null, 0L)
        }
    }

    private fun parseDownloadUrls(jsonObject: JSONObject): List<String> {
        val urls = mutableListOf<String>()

        if (jsonObject.has("downloadUrl") && !jsonObject.isNull("downloadUrl")) {
            urls.add(jsonObject.getString("downloadUrl"))
        } else {
            var urlIndex = 1
            while (jsonObject.has("downloadUrl$urlIndex") && !jsonObject.isNull("downloadUrl$urlIndex")) {
                urls.add(jsonObject.getString("downloadUrl$urlIndex"))
                urlIndex++
            }
        }

        return urls
    }

    suspend fun fetchDatabases(url: String) {
        withContext(Dispatchers.IO) {
            jsonUrl = url
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body.string()
                val jsonObject = JSONObject(jsonString)
                val databasesArray = jsonObject.getJSONArray("databases")

                val databases = mutableListOf<SmartLinkDbInfo>()
                for (i in 0 until databasesArray.length()) {
                    val dbObject = databasesArray.getJSONObject(i)

                    databases.add(SmartLinkDbInfo(
                        id = dbObject.getString("id"),
                        name = dbObject.getString("name"),
                        downloadUrls = parseDownloadUrls(dbObject),
                        version = dbObject.getString("version"),
                        type = dbObject.getString("type")
                    ))
                }

                _databases.postValue(databases)
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

                val downloadUrls = dbInfo.downloadUrls
                val fileName = "${dbInfo.id}_${dbInfo.version}.db"
                val finalFile = File(context.filesDir, fileName)

                if (dbInfo.isMultiPart()) {
                    downloadMultiPartArchiveWithResume(dbInfo, downloadUrls, finalFile, progressCallback)
                } else {
                    val downloadUrl = downloadUrls.first()
                    if (downloadUrl.endsWith(".zip", true) || downloadUrl.endsWith(".7z", true)) {
                        downloadAndExtractArchiveWithResume(dbInfo, downloadUrl, finalFile, progressCallback)
                    } else {
                        downloadDirectFileWithResume(dbInfo, downloadUrl, finalFile, progressCallback)
                    }
                }

                clearDownloadMetadata(dbInfo.id)

                Log.d(TAG, "Download completed. Size: ${finalFile.length()} bytes")

                val dbType = detectDbType(finalFile)
                val actualFileSize = finalFile.length().toFloat() / (1024 * 1024)

                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", finalFile)

                return@withContext DbItem(
                    id = UUID.randomUUID().toString(),
                    path = uri.toString(),
                    directPath = finalFile.absolutePath,
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
                clearDownloadMetadata(dbInfo.id)
                Log.e(TAG, "Download failed", e)
                throw e
            }
        }
    }

    private suspend fun downloadDirectFileWithResume(
        dbInfo: SmartLinkDbInfo,
        url: String,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val (resumeFile, resumePosition) = checkResumeDownload(dbInfo)
            val tempFile = resumeFile ?: getTempFile(dbInfo.id, dbInfo.version)

            val requestBuilder = Request.Builder().url(url)
            if (resumePosition > 0) {
                requestBuilder.addHeader("Range", "bytes=$resumePosition-")
                withContext(Dispatchers.Main) {
                    progressCallback(-1, 0, null)
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                throw Exception("Server returned code ${response.code}")
            }

            val totalSize = if (response.code == 206) {
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast('/')?.toLongOrNull()
            } else {
                response.header("Content-Length")?.toLongOrNull()
            } ?: 0L

            var downloadedSize = resumePosition

            if (totalSize > 0) {
                saveDownloadMetadata(dbInfo.id, DownloadMetadata(
                    version = dbInfo.version,
                    totalSize = totalSize,
                    downloadedSize = downloadedSize,
                    timestamp = System.currentTimeMillis()
                ))
            }

            FileOutputStream(tempFile, resumePosition > 0).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (isActive) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        if (totalSize > 0) {
                            saveDownloadMetadata(dbInfo.id, DownloadMetadata(
                                version = dbInfo.version,
                                totalSize = totalSize,
                                downloadedSize = downloadedSize,
                                timestamp = System.currentTimeMillis()
                            ))
                        }

                        val progress = if (totalSize > 0) {
                            ((downloadedSize.toDouble() / totalSize.toDouble()) * 100).toInt()
                        } else 0

                        ensureActive()
                        withContext(Dispatchers.Main) {
                            progressCallback(progress, downloadedSize, totalSize.takeIf { it > 0 })
                        }
                    }
                }
            }

            tempFile.renameTo(outputFile)
        }
    }

    private suspend fun downloadAndExtractArchiveWithResume(
        dbInfo: SmartLinkDbInfo,
        url: String,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val archiveExtension = url.substringAfterLast('.', "").lowercase()
            val tempArchive = File(context.cacheDir, "${outputFile.nameWithoutExtension}.$archiveExtension")

            downloadDirectFileWithResume(dbInfo, url, tempArchive, progressCallback)

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchive(tempArchive, outputFile)
            tempArchive.delete()
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
                val partFile = File(context.cacheDir, "part_${outputFile.nameWithoutExtension}_${index + 1}.tmp")
                tempParts.add(partFile)

                withContext(Dispatchers.Main) {
                    progressCallback(-2, index.toLong() + 1, urls.size.toLong())
                }

                if (partFile.exists()) {
                    Log.d(TAG, "Part ${index + 1} already exists, skipping download")
                    totalDownloaded += partFile.length()
                } else {
                    downloadDirectFile(url, partFile) { progress, downloaded, total ->
                        val overallProgress = ((index * 100 + progress) / urls.size)
                        progressCallback(overallProgress, totalDownloaded + downloaded, null)
                    }
                    totalDownloaded += partFile.length()
                }
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

            if (!mergedArchive.exists() || mergedArchive.length() == 0L) {
                mergeFiles(tempParts, mergedArchive)
            }

            tempParts.forEach { it.delete() }

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchive(mergedArchive, outputFile)
            mergedArchive.delete()
        }
    }

    private suspend fun downloadMultiPartArchiveWithResume(
        dbInfo: SmartLinkDbInfo,
        urls: List<String>,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val metadata = loadDownloadMetadata(dbInfo.id)
            val completedParts = metadata?.let {
                json.decodeFromString<List<Boolean>>(
                    File(context.cacheDir, "${dbInfo.id}_parts_status.json").takeIf { it.exists() }?.readText() ?: "[]"
                )
            } ?: List(urls.size) { false }

            val tempParts = mutableListOf<File>()
            var totalDownloaded = 0L

            urls.forEachIndexed { index, url ->
                val partFile = File(context.cacheDir, "part_${dbInfo.id}_${index + 1}.tmp")
                tempParts.add(partFile)

                if (completedParts.getOrElse(index) { false } && partFile.exists()) {
                    withContext(Dispatchers.Main) {
                        progressCallback(-2, index.toLong() + 1, urls.size.toLong())
                    }
                    totalDownloaded += partFile.length()
                    Log.d(TAG, "Part ${index + 1} already downloaded, skipping")
                } else {
                    withContext(Dispatchers.Main) {
                        progressCallback(-2, index.toLong() + 1, urls.size.toLong())
                    }

                    downloadDirectFile(url, partFile) { progress, downloaded, total ->
                        val overallProgress = ((index * 100 + progress) / urls.size)
                        progressCallback(overallProgress, totalDownloaded + downloaded, null)
                    }

                    val updatedStatus = completedParts.toMutableList()
                    if (updatedStatus.size <= index) {
                        while (updatedStatus.size <= index) updatedStatus.add(false)
                    }
                    updatedStatus[index] = true
                    File(context.cacheDir, "${dbInfo.id}_parts_status.json").writeText(json.encodeToString(updatedStatus))

                    totalDownloaded += partFile.length()
                }
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

            if (!mergedArchive.exists() || mergedArchive.length() == 0L) {
                mergeFiles(tempParts, mergedArchive)
            }

            tempParts.forEach { it.delete() }
            File(context.cacheDir, "${dbInfo.id}_parts_status.json").delete()

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
            try {
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
                            downloadUrls = parseDownloadUrls(info),
                            version = info.getString("version"),
                            type = info.getString("type")
                        )
                        break
                    }
                }

                if (dbInfo == null) {
                    throw Exception("Database info not found in update JSON")
                }

                if (dbItem.version != newVersion) {
                    clearDownloadMetadata(dbItem.idJson ?: dbItem.id)
                }

                val fileName = "${dbItem.idJson}_$newVersion.db"
                val file = File(context.filesDir, fileName)

                val downloadUrls = dbInfo.downloadUrls

                if (dbInfo.isMultiPart()) {
                    downloadMultiPartArchiveWithResume(dbInfo, downloadUrls, file) { progress, _, _ ->
                        progressCallback(progress)
                    }
                } else {
                    val url = downloadUrls.first()
                    if (url.endsWith(".zip", true) || url.endsWith(".7z", true)) {
                        downloadAndExtractArchiveWithResume(dbInfo, url, file) { progress, _, _ ->
                            progressCallback(progress)
                        }
                    } else {
                        downloadDirectFileWithResume(dbInfo, url, file) { progress, _, _ ->
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
            } catch (e: Exception) {
                dbItem.idJson?.let { clearDownloadMetadata(it) }
                throw e
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}