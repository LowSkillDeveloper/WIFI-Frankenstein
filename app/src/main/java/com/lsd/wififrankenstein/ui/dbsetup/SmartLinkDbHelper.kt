package com.lsd.wififrankenstein.ui.dbsetup

import android.content.ContentValues.TAG
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
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
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URL
import java.util.UUID
import java.util.zip.ZipInputStream

@Serializable
data class SmartLinkDbInfo(
    val id: String,
    val name: String,
    val downloadUrls: List<String> = emptyList(),
    val version: String,
    val type: String,
    val columnMapping: Map<String, String>? = null,
    val tableName: String? = null
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

private val isLegacyAndroid = Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1
private const val LEGACY_BUFFER_SIZE = 2048

class SmartLinkDbHelper(private val context: Context) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val _databases = MutableLiveData<List<SmartLinkDbInfo>>()
    val databases: LiveData<List<SmartLinkDbInfo>> = _databases

    private lateinit var jsonUrl: String

    private val _sources = MutableLiveData<List<DbSource>>()
    val sources: LiveData<List<DbSource>> = _sources

    private var currentSource: DbSource? = null

    private fun getMetadataFile(dbId: String): File {
        return File(context.cacheDir, "${dbId}_download.metadata")
    }

    private fun getTempFile(dbId: String, version: String): File {
        return File(context.cacheDir, "${dbId}_${version}.tmp")
    }

    private fun getPartsStatusFile(dbId: String, version: String): File {
        return File(context.cacheDir, "${dbId}_${version}_parts_status.json")
    }

    private fun getPartFile(dbId: String, version: String, partIndex: Int): File {
        return File(context.cacheDir, "part_${dbId}_${version}_${partIndex + 1}.tmp")
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

        context.cacheDir.listFiles { file ->
            file.name.startsWith("${dbId}_") && (
                    file.name.endsWith(".tmp") ||
                            file.name.endsWith("_parts_status.json") ||
                            file.name.contains("_parts_status.json")
                    )
        }?.forEach {
            Log.d(TAG, "Deleting cached file: ${it.name}")
            it.delete()
        }

        context.cacheDir.listFiles { file ->
            file.name.startsWith("part_${dbId}_") && file.name.endsWith(".tmp")
        }?.forEach {
            Log.d(TAG, "Deleting part file: ${it.name}")
            it.delete()
        }
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
            val url = jsonObject.getString("downloadUrl")
            if (url.isNotBlank()) {
                urls.add(url)
            }
        }

        var urlIndex = 1
        while (jsonObject.has("downloadUrl$urlIndex") && !jsonObject.isNull("downloadUrl$urlIndex")) {
            val url = jsonObject.getString("downloadUrl$urlIndex")
            if (url.isNotBlank()) {
                urls.add(url)
            }
            urlIndex++
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
                        type = dbObject.getString("type"),
                        columnMapping = if (dbObject.has("columnMapping") && !dbObject.isNull("columnMapping")) {
                            val mappingObject = dbObject.getJSONObject("columnMapping")
                            val mapping = mutableMapOf<String, String>()
                            mappingObject.keys().forEach { key ->
                                mapping[key] = mappingObject.getString(key)
                            }
                            mapping
                        } else null,
                        tableName = if (dbObject.has("tableName") && !dbObject.isNull("tableName")) {
                            dbObject.getString("tableName")
                        } else null
                    ))
                }

                _databases.postValue(databases)
            } else {
                throw Exception("Failed to fetch database info")
            }
        }
    }

    suspend fun fetchSources(url: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body.string()
                try {
                    val sourcesResponse = json.decodeFromString<RecommendedSourcesResponse>(jsonString)
                    _sources.postValue(sourcesResponse.sources ?: emptyList())
                } catch (e: Exception) {
                    Log.e("SmartLinkDbHelper", "Error parsing sources JSON", e)
                    _sources.postValue(emptyList())
                }
            } else {
                throw Exception("Failed to fetch sources info")
            }
        }
    }

    fun setCurrentSource(source: DbSource) {
        currentSource = source
    }

    fun getCurrentSource(): DbSource? = currentSource

    suspend fun downloadDatabase(
        dbInfo: SmartLinkDbInfo,
        progressCallback: (progress: Int, downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): DbItem {
        return withContext(Dispatchers.IO) {
            try {
                ensureActive()
                Log.d(TAG, "Starting download for ${dbInfo.name}, version: ${dbInfo.version}, multipart: ${dbInfo.isMultiPart()}")

                val partsStatusFile = getPartsStatusFile(dbInfo.id, dbInfo.version)
                if (partsStatusFile.exists()) {
                    Log.d(TAG, "Found existing parts status file: ${partsStatusFile.readText()}")
                }

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

                if (!validateDatabaseIntegrity(finalFile)) {
                    Log.e(TAG, "Database validation failed: ${finalFile.name}")
                    finalFile.delete()
                    throw Exception(context.getString(R.string.database_corrupted_after_download))
                }

                Log.d(TAG, "Download completed. Size: ${finalFile.length()} bytes")

                val dbType = detectDbType(finalFile)

                val (validatedTableName, validatedColumnMap) = if (dbInfo.type == "custom-auto-mapping" && dbInfo.columnMapping != null) {
                    validateColumnMapping(finalFile, dbInfo.columnMapping, dbInfo.tableName)
                } else {
                    Pair(dbInfo.tableName, dbInfo.columnMapping)
                }

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
                    updateUrl = currentSource?.smartlinkUrl ?: jsonUrl,
                    smartlinkType = dbInfo.type,
                    tableName = validatedTableName,
                    columnMap = validatedColumnMap
                )
            } catch (e: Exception) {
                clearDownloadMetadata(dbInfo.id)
                Log.e(TAG, "Download failed", e)
                throw e
            }
        }
    }

    private fun extract7zLegacy(archiveFile: File, outputFile: File) {
        var sevenZFile: SevenZFile? = null
        var outputStream: FileOutputStream? = null

        try {
            sevenZFile = SevenZFile(archiveFile)
            var entry = sevenZFile.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    outputStream = FileOutputStream(outputFile)
                    val buffer = ByteArray(LEGACY_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (totalBytesRead % (LEGACY_BUFFER_SIZE * 100) == 0L) {
                            outputStream.flush()
                            outputStream.fd.sync()
                        }

                        if (totalBytesRead > entry.size * 2) {
                            throw Exception("Extracted size exceeds expected size")
                        }
                    }

                    outputStream.flush()
                    outputStream.fd.sync()
                    extracted = true
                }
                entry = sevenZFile.nextEntry
            }

            if (!extracted) {
                throw Exception("No database file found in 7z archive")
            }

        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            try {
                sevenZFile?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing 7z file", e)
            }
        }
    }

    private fun extractZipLegacy(archiveFile: File, outputFile: File) {
        var zipInput: ZipInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            zipInput = ZipInputStream(BufferedInputStream(FileInputStream(archiveFile)))
            var entry = zipInput.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    outputStream = FileOutputStream(outputFile)
                    val buffer = ByteArray(LEGACY_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (totalBytesRead % (LEGACY_BUFFER_SIZE * 100) == 0L) {
                            outputStream.flush()
                            outputStream.fd.sync()
                        }

                        if (entry.size > 0 && totalBytesRead > entry.size * 2) {
                            throw Exception("Extracted size exceeds expected size")
                        }
                    }

                    outputStream.flush()
                    outputStream.fd.sync()
                    extracted = true
                }
                entry = zipInput.nextEntry
            }

            if (!extracted) {
                throw Exception("No database file found in zip archive")
            }

        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            try {
                zipInput?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing zip stream", e)
            }
        }
    }

    private fun validateDatabaseIntegrity(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() == 0L) {
            Log.e(TAG, "Database file is empty or doesn't exist")
            return false
        }

        if (isLegacyAndroid) {
            return validateDatabaseIntegrityLegacy(dbFile)
        }

        var db: SQLiteDatabase? = null
        return try {
            Thread.sleep(200)

            db = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
                SafeDatabaseErrorHandler()
            )

            val isValid = try {
                val cursor = db.rawQuery("SELECT 1", null)
                val hasResult = cursor.moveToFirst()
                cursor.close()
                hasResult
            } catch (e: Exception) {
                Log.e(TAG, "Basic query check failed", e)
                false
            }

            if (isValid) {
                try {
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1", null).use { cursor ->
                        cursor.count > 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Schema validation failed", e)
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database validation failed", e)
            false
        } finally {
            try {
                db?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing validation database", e)
            }
        }
    }

    private fun validateDatabaseIntegrityLegacy(dbFile: File): Boolean {
        return try {
            val headerBytes = ByteArray(100)
            dbFile.inputStream().use { it.read(headerBytes) }

            val sqliteHeader = "SQLite format 3"
            val headerString = String(headerBytes, 0, minOf(sqliteHeader.length, headerBytes.size))

            if (!headerString.startsWith(sqliteHeader)) {
                Log.e(TAG, "Invalid SQLite header")
                return false
            }

            val minSize = 1024L
            if (dbFile.length() < minSize) {
                Log.e(TAG, "Database file too small: ${dbFile.length()}")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Legacy validation failed", e)
            false
        }
    }

    private fun extractArchiveWithValidation(archiveFile: File, outputFile: File) {
        val tempOutputFile = File(outputFile.parent, "${outputFile.name}.extracting")

        var attempt = 0
        val maxAttempts = if (isLegacyAndroid) 3 else 1

        while (attempt < maxAttempts) {
            attempt++

            try {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }

                if (isLegacyAndroid) {
                    extractArchiveLegacy(archiveFile, tempOutputFile)
                } else {
                    extractArchive(archiveFile, tempOutputFile)
                }

                if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    Thread.sleep(if (isLegacyAndroid) 500 else 100)

                    if (validateDatabaseIntegrity(tempOutputFile)) {
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }

                        val renamed = tempOutputFile.renameTo(outputFile)
                        if (!renamed) {
                            tempOutputFile.copyTo(outputFile, true)
                            tempOutputFile.delete()
                        }
                        return
                    } else {
                        Log.e(TAG, "Validation failed on attempt $attempt")
                        tempOutputFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed on attempt $attempt", e)
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete()
                }
            }

            if (attempt < maxAttempts) {
                Thread.sleep((1000 * attempt).toLong())
            }
        }

        throw Exception(context.getString(R.string.database_extraction_failed_all_attempts))
    }

    private fun extractArchiveLegacy(archiveFile: File, outputFile: File) {
        val extension = archiveFile.extension.lowercase()

        when {
            extension == "7z" -> extract7zLegacy(archiveFile, outputFile)
            extension == "zip" -> extractZipLegacy(archiveFile, outputFile)
            else -> throw Exception("Unsupported archive format: $extension")
        }
    }

    private fun validateColumnMapping(dbFile: File, columnMapping: Map<String, String>, specifiedTableName: String?): Pair<String?, Map<String, String>?> {
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(0))
                    }
                }
            }

            if (tables.isEmpty()) {
                db.close()
                return Pair(null, null)
            }

            val targetTable = when {
                specifiedTableName != null && tables.contains(specifiedTableName) -> specifiedTableName
                specifiedTableName != null -> {
                    Log.w("SmartLinkDbHelper", "Specified table '$specifiedTableName' not found. Available tables: $tables")
                    db.close()
                    return Pair(null, null)
                }
                else -> {
                    Log.w("SmartLinkDbHelper", "No table specified for custom-auto-mapping. Available tables: $tables")
                    tables.first()
                }
            }

            val availableColumns = db.rawQuery("PRAGMA table_info($targetTable)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(1))
                    }
                }
            }
            db.close()

            val validatedMapping = columnMapping.filter { (_, dbColumn) ->
                availableColumns.contains(dbColumn)
            }

            Log.d("SmartLinkDbHelper", "Target table: $targetTable")
            Log.d("SmartLinkDbHelper", "Original mapping: $columnMapping")
            Log.d("SmartLinkDbHelper", "Available columns: $availableColumns")
            Log.d("SmartLinkDbHelper", "Validated mapping: $validatedMapping")

            val skippedColumns = columnMapping.keys - validatedMapping.keys
            if (skippedColumns.isNotEmpty()) {
                Log.w("SmartLinkDbHelper", "Skipped columns (not found in table '$targetTable'): $skippedColumns")
            }

            if (validatedMapping.isNotEmpty()) {
                Pair(targetTable, validatedMapping)
            } else {
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e("SmartLinkDbHelper", "Error validating column mapping", e)
            Pair(null, null)
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
                Log.d(TAG, context.getString(R.string.resuming_download))
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
            val tempArchive = File(context.cacheDir, "${outputFile.nameWithoutExtension}_${dbInfo.version}.$archiveExtension")

            if (!tempArchive.exists() || tempArchive.length() == 0L) {
                downloadDirectFileWithResume(dbInfo, url, tempArchive, progressCallback)
            } else {
                Log.d(TAG, "Archive already exists, skipping download")
            }

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchiveWithValidation(tempArchive, outputFile)
            tempArchive.delete()
        }
    }

    private suspend fun downloadMultiPartArchiveWithResume(
        dbInfo: SmartLinkDbInfo,
        urls: List<String>,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val partsStatusFile = getPartsStatusFile(dbInfo.id, dbInfo.version)
            val completedParts = if (partsStatusFile.exists()) {
                try {
                    json.decodeFromString<List<Boolean>>(partsStatusFile.readText())
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading parts status", e)
                    List(urls.size) { false }
                }
            } else {
                List(urls.size) { false }
            }

            val tempParts = mutableListOf<File>()
            var totalDownloaded = 0L
            var completedCount = completedParts.count { it }

            Log.d(TAG, "Resuming multipart download: ${completedCount}/${urls.size} parts already completed")

            urls.forEachIndexed { index, url ->
                val partFile = getPartFile(dbInfo.id, dbInfo.version, index)
                tempParts.add(partFile)

                if (completedParts.getOrElse(index) { false } && partFile.exists() && partFile.length() > 0) {
                    totalDownloaded += partFile.length()
                    Log.d(TAG, "Part ${index + 1}/${urls.size} already downloaded (${partFile.length()} bytes)")

                    withContext(Dispatchers.Main) {
                        val overallProgress = (completedCount * 100) / urls.size
                        progressCallback(overallProgress, totalDownloaded, null)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressCallback(-2, index.toLong() + 1, urls.size.toLong())
                    }

                    Log.d(TAG, "Downloading part ${index + 1}/${urls.size}")
                    downloadDirectFile(url, partFile) { progress, downloaded, total ->
                        val overallProgress = ((completedCount * 100 + progress) / urls.size)
                        progressCallback(overallProgress, totalDownloaded + downloaded, null)
                    }

                    val updatedStatus = completedParts.toMutableList()
                    while (updatedStatus.size <= index) updatedStatus.add(false)
                    updatedStatus[index] = true

                    partsStatusFile.writeText(json.encodeToString(updatedStatus))

                    totalDownloaded += partFile.length()
                    completedCount++
                }
            }

            Log.d(TAG, "All parts downloaded, merging...")
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

            val mergedArchive = File(context.cacheDir, "${outputFile.nameWithoutExtension}_${dbInfo.version}.$archiveExtension")

            if (!mergedArchive.exists() || mergedArchive.length() == 0L) {
                mergeFiles(tempParts, mergedArchive)
                Log.d(TAG, "Files merged into archive: ${mergedArchive.length()} bytes")
            }

            tempParts.forEach { it.delete() }
            partsStatusFile.delete()

            withContext(Dispatchers.Main) {
                progressCallback(-1, 0, null)
            }

            extractArchiveWithValidation(mergedArchive, outputFile)
            mergedArchive.delete()

            Log.d(TAG, "Archive extracted to final file: ${outputFile.length()} bytes")
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
        var sevenZFile: SevenZFile? = null
        var outputStream: FileOutputStream? = null

        try {
            sevenZFile = SevenZFile(archiveFile)
            var entry = sevenZFile.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    outputStream = FileOutputStream(outputFile)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (totalBytesRead > entry.size * 2) {
                            throw Exception("Extracted size exceeds expected size")
                        }
                    }

                    extracted = true
                    outputStream.close()
                    outputStream = null
                }
                entry = sevenZFile.nextEntry
            }

            if (!extracted) {
                throw Exception("No database file found in 7z archive")
            }

        } catch (e: Exception) {
            outputFile.delete()
            throw Exception("Failed to extract 7z archive: ${e.message}")
        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            try {
                sevenZFile?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing 7z file", e)
            }
        }
    }

    private fun extractZip(archiveFile: File, outputFile: File) {
        var zipInput: ZipInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            zipInput = ZipInputStream(BufferedInputStream(FileInputStream(archiveFile)))
            var entry = zipInput.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && entry.name.endsWith(".db", true)) {
                    outputStream = FileOutputStream(outputFile)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (entry.size > 0 && totalBytesRead > entry.size * 2) {
                            throw Exception("Extracted size exceeds expected size")
                        }
                    }

                    extracted = true
                    outputStream.close()
                    outputStream = null
                }
                entry = zipInput.nextEntry
            }

            if (!extracted) {
                throw Exception("No database file found in zip archive")
            }

        } catch (e: Exception) {
            outputFile.delete()
            throw Exception("Failed to extract zip archive: ${e.message}")
        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream", e)
            }
            try {
                zipInput?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing zip stream", e)
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