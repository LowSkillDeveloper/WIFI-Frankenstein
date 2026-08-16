package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.MegaFileUnavailableException
import com.lsd.wififrankenstein.network.MegaPublicDownloader
import com.lsd.wififrankenstein.network.MegaQuotaException
import com.lsd.wififrankenstein.network.MegaUrlParser
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Serializable
data class SmartLinkDbInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val downloadUrls: List<String> = emptyList(),
    val version: String,
    val type: String? = null,
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
private const val LEGACY_BUFFER_SIZE = 256 * 1024

const val PROGRESS_RESUME = -1
const val PROGRESS_PART = -2
const val PROGRESS_MERGE = -3
const val PROGRESS_EXTRACT = -4

enum class UrlType {
    JSON_API,
    DIRECT_DB,
    DIRECT_ARCHIVE,
    MEGA,
    UNKNOWN
}

private fun detectUrlType(url: String): UrlType {
    if (MegaUrlParser.isMegaUrl(url)) return UrlType.MEGA

    val path = url.substringBefore('?').substringBefore('#')
    val pathLower = path.lowercase()
    val fileName = pathLower.substringAfterLast('/')

    return when {
        fileName.endsWith(".db") || fileName.endsWith(".sqlite") -> UrlType.DIRECT_DB
        fileName.endsWith(".zip") || fileName.endsWith(".7z") ||
                fileName.endsWith(".gz") || fileName.endsWith(".tgz") ||
                fileName.endsWith(".tar") -> UrlType.DIRECT_ARCHIVE

        fileName.endsWith(".json") -> UrlType.JSON_API
        else -> {
            val namePart = fileName.substringBeforeLast('.').ifBlank { fileName }
            when {
                namePart.contains("smartlink") || url.contains("/api/") -> UrlType.JSON_API
                namePart.contains("sqlite") || namePart.endsWith(".db") -> UrlType.DIRECT_DB
                namePart.contains("zip") || namePart.contains("7z") -> UrlType.DIRECT_ARCHIVE
                pathLower.contains("github.com") && namePart.contains("json") -> UrlType.JSON_API
                pathLower.contains("github.com") && namePart.contains("sqlite") -> UrlType.DIRECT_DB
                pathLower.contains("github.com") && (namePart.contains("zip") || namePart.contains("7z")) -> UrlType.DIRECT_ARCHIVE
                else -> UrlType.UNKNOWN
            }
        }
    }
}

private fun smartLinkInfoFromDirectUrl(url: String): SmartLinkDbInfo {
    val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "database" }
    val name = fileName.substringBeforeLast('.').ifBlank { fileName }
    val id = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val fileLower = fileName.lowercase()
    val type = when {
        MegaUrlParser.isMegaUrl(url) -> "mega"
        fileLower.endsWith(".db") || fileLower.endsWith(".sqlite") -> "direct-db"
        fileLower.endsWith(".zip") || fileLower.endsWith(".7z") ||
                fileLower.endsWith(".gz") || fileLower.endsWith(".tgz") ||
                fileLower.endsWith(".tar") -> "direct-archive"

        else -> "direct-unknown"
    }
    return SmartLinkDbInfo(
        id = id,
        name = name,
        downloadUrls = listOf(url),
        version = "1.0",
        type = type,
        columnMapping = null,
        tableName = null
    )
}

private fun parseDownloadUrls(jsonObject: JSONObject): List<String> {
    val urls = mutableListOf<String>()

    if (jsonObject.has("downloadUrl") && !jsonObject.isNull("downloadUrl")) {
        val url = jsonObject.getString("downloadUrl")
        if (url.isNotBlank()) {
            urls.add(url)
        }

        var extraIndex = 1
        var hasExtraParts = false
        while (jsonObject.has("downloadUrl$extraIndex")) {
            if (!jsonObject.isNull("downloadUrl$extraIndex") &&
                jsonObject.getString("downloadUrl$extraIndex").isNotBlank()
            ) {
                hasExtraParts = true
            }
            extraIndex++
        }
        if (hasExtraParts) {
            Log.w(
                "SmartLinkDbHelper",
                "downloadUrl and downloadUrlN must not be mixed; using downloadUrl only"
            )
        }
    } else {
        var urlIndex = 1
        while (jsonObject.has("downloadUrl$urlIndex") && !jsonObject.isNull("downloadUrl$urlIndex")) {
            val url = jsonObject.getString("downloadUrl$urlIndex")
            if (url.isNotBlank()) {
                urls.add(url)
            }
            urlIndex++
        }
    }

    return urls
}

fun parseSmartLinkDbObject(jsonObject: JSONObject): SmartLinkDbInfo {
    return SmartLinkDbInfo(
        id = jsonObject.getString("id"),
        name = jsonObject.getString("name"),
        description = jsonObject.optString("description", null)?.takeIf { it.isNotBlank() },
        downloadUrls = parseDownloadUrls(jsonObject),
        version = jsonObject.getString("version"),
        type = jsonObject.optString("type", null)?.takeIf { it.isNotBlank() },
        columnMapping = if (jsonObject.has("columnMapping") && !jsonObject.isNull("columnMapping")) {
            val mappingObject = jsonObject.getJSONObject("columnMapping")
            val mapping = mutableMapOf<String, String>()
            mappingObject.keys().forEach { key ->
                mapping[key] = mappingObject.getString(key)
            }
            mapping
        } else null,
        tableName = jsonObject.optString("tableName", null)?.takeIf { it.isNotBlank() }
    )
}

fun interface LegacyDatabaseConflictResolver {
    suspend fun onExpressionIndexConflict(file: File): Boolean
}

class SmartLinkDbHelper(private val context: Context) {
    private companion object {
        const val TAG = "SmartLinkDbHelper"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val EXTRACT_BUFFER_SIZE = 1024 * 1024
    }

    var legacyConflictResolver: LegacyDatabaseConflictResolver? = null

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val _databases = MutableLiveData<List<SmartLinkDbInfo>>()
    val databases: LiveData<List<SmartLinkDbInfo>> = _databases

    private lateinit var jsonUrl: String
    private var currentUrlType: UrlType? = null

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

                tempFile.length() == metadata.downloadedSize ||
                        (tempFile.length() in (metadata.downloadedSize + 1)..metadata.totalSize) -> {
                    withContext(Dispatchers.Main) {
                        Log.d(
                            TAG,
                            context.getString(
                                R.string.download_resumed,
                                (metadata.downloadedSize * 100 / metadata.totalSize).toInt()
                            )
                        )
                    }
                    Pair(tempFile, tempFile.length())
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

    suspend fun fetchDatabases(url: String) {
        withContext(Dispatchers.IO) {
            jsonUrl = url
            val urlType = detectUrlType(url)
            currentUrlType = urlType

            if (urlType != UrlType.JSON_API) {
                if (urlType == UrlType.MEGA && MegaUrlParser.parse(url) == null) {
                    throw Exception(context.getString(R.string.ds_mega_folder_not_supported))
                }
                val dbInfo = if (urlType == UrlType.MEGA) {
                    Log.d(TAG, "Fetching MEGA filename from API...")
                    val name = MegaPublicDownloader(client).resolveFileName(url)
                    Log.d(TAG, "MEGA filename resolved: $name")
                    val fallbackName = smartLinkInfoFromDirectUrl(url).name
                    Log.d(TAG, "Fallback name from URL: $fallbackName")
                    val finalName = name ?: fallbackName
                    Log.d(TAG, "Final name to use: $finalName")
                    smartLinkInfoFromDirectUrl(url).copy(name = finalName)
                } else {
                    smartLinkInfoFromDirectUrl(url)
                }
                _databases.postValue(listOf(dbInfo))
                return@withContext
            }

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body.string()
                val jsonObject = JSONObject(jsonString)
                val databasesArray = jsonObject.getJSONArray("databases")

                val databases = mutableListOf<SmartLinkDbInfo>()
                for (i in 0 until databasesArray.length()) {
                    val parsed = parseSmartLinkDbObject(databasesArray.getJSONObject(i))
                    databases.add(parsed.copy(type = parsed.type ?: "auto"))
                }

                _databases.postValue(databases)
            } else {
                throw Exception(context.getString(R.string.ds_failed_fetch_db_info))
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
                    val sourcesResponse =
                        json.decodeFromString<RecommendedSourcesResponse>(jsonString)
                    _sources.postValue(sourcesResponse.sources ?: emptyList())
                } catch (e: Exception) {
                    Log.e("SmartLinkDbHelper", "Error parsing sources JSON", e)
                    _sources.postValue(emptyList())
                }
            } else {
                throw Exception(context.getString(R.string.ds_failed_fetch_sources_info))
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
                Log.d(
                    TAG,
                    "Starting download for ${dbInfo.name}, version: ${dbInfo.version}, multipart: ${dbInfo.isMultiPart()}"
                )

                val partsStatusFile = getPartsStatusFile(dbInfo.id, dbInfo.version)
                if (partsStatusFile.exists()) {
                    Log.d(TAG, "Found existing parts status file: ${partsStatusFile.readText()}")
                }

                val downloadUrls = dbInfo.downloadUrls
                val fileName = "${dbInfo.id}_${dbInfo.version}.db"
                val finalFile = File(context.filesDir, fileName)

                if (dbInfo.isMultiPart()) {
                    downloadMultiPartArchiveWithResume(
                        dbInfo,
                        downloadUrls,
                        finalFile,
                        progressCallback
                    )
                } else {
                    val downloadUrl = downloadUrls.first()
                    when {
                        MegaUrlParser.isMegaUrl(downloadUrl) -> {
                            if (MegaUrlParser.parse(downloadUrl) == null) {
                                throw Exception(context.getString(R.string.ds_mega_folder_not_supported))
                            }
                            downloadMegaFile(dbInfo, downloadUrl, finalFile, progressCallback)
                        }

                        downloadUrl.endsWith(".zip", true) || downloadUrl.endsWith(".7z", true) ||
                                downloadUrl.endsWith(".gz", true) || downloadUrl.endsWith(".tgz", true) ||
                                downloadUrl.endsWith(".tar", true) -> {
                            downloadAndExtractArchiveWithResume(
                                dbInfo,
                                downloadUrl,
                                finalFile,
                                progressCallback
                            )
                        }

                        else -> {
                            downloadDirectFileWithResume(
                                dbInfo,
                                downloadUrl,
                                finalFile,
                                progressCallback
                            )
                        }
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

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    finalFile
                )

                val oldFormatWarning = checkDbFormatWarning(finalFile)

                return@withContext DbItem(
                    id = UUID.randomUUID().toString(),
                    path = uri.toString(),
                    directPath = finalFile.absolutePath,
                    type = dbInfo.name,
                    dbType = if (dbType == DbType.SQLITE_FILE_P3WIFI) DbType.SMARTLINK_SQLITE_FILE_P3WIFI else DbType.SMARTLINK_SQLITE_FILE_CUSTOM,
                    originalSizeInMB = actualFileSize,
                    cachedSizeInMB = actualFileSize,
                    idJson = dbInfo.id,
                    version = dbInfo.version,
                    updateUrl = currentSource?.smartlinkUrl
                        ?: if (currentUrlType == UrlType.JSON_API) jsonUrl else null,
                    smartlinkType = dbInfo.type,
                    tableName = validatedTableName,
                    columnMap = validatedColumnMap,
                    oldFormatWarning = oldFormatWarning
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: MegaQuotaException) {
                Log.e(TAG, "Download failed due to MEGA bandwidth quota, preserving partial download", e)
                throw e
            } catch (e: Exception) {
                clearDownloadMetadata(dbInfo.id)
                Log.e(TAG, "Download failed", e)
                throw e
            }
        }
    }

    private fun extract7zLegacy(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        var sevenZFile: SevenZFile? = null
        var outputStream: FileOutputStream? = null

        try {
            sevenZFile = SevenZFile(archiveFile)
            var entry = sevenZFile.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && isDbFileName(entry.name)) {
                    outputStream = FileOutputStream(outputFile)
                    val bufferedOut = BufferedOutputStream(outputStream, LEGACY_BUFFER_SIZE)
                    val buffer = ByteArray(LEGACY_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    try {
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            bufferedOut.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onProgress?.invoke(totalBytesRead, entry.size.takeIf { it > 0 })

                            if (totalBytesRead > entry.size * 2) {
                                throw Exception("Extracted size exceeds expected size")
                            }
                        }
                        bufferedOut.flush()
                        outputStream.fd.sync()
                    } finally {
                        bufferedOut.close()
                        outputStream = null
                    }
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

    private fun extractZipLegacy(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        var zipInput: ZipInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            zipInput = ZipInputStream(BufferedInputStream(FileInputStream(archiveFile)))
            var entry = zipInput.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && isDbFileName(entry.name)) {
                    outputStream = FileOutputStream(outputFile)
                    val bufferedOut = BufferedOutputStream(outputStream, LEGACY_BUFFER_SIZE)
                    val buffer = ByteArray(LEGACY_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    try {
                        while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                            bufferedOut.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onProgress?.invoke(totalBytesRead, entry.size.takeIf { it > 0 })

                            if (entry.size > 0 && totalBytesRead > entry.size * 2) {
                                throw Exception("Extracted size exceeds expected size")
                            }
                        }
                        bufferedOut.flush()
                        outputStream.fd.sync()
                    } finally {
                        bufferedOut.close()
                        outputStream = null
                    }
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

    private fun openDatabaseSafe(path: String): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            SafeDatabaseErrorHandler()
        )
    }

    private fun validateDatabaseIntegrity(dbFile: File): Boolean {
        if (!dbFile.exists() || dbFile.length() == 0L) {
            Log.e(TAG, "Database file is empty or doesn't exist")
            return false
        }

        var db: SQLiteDatabase? = null
        return try {
            Thread.sleep(200)

            db = openDatabaseSafe(dbFile.path)

            val isValid = try {
                db.rawQuery("SELECT 1", null).use { cursor ->
                    cursor.moveToFirst()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Basic query check failed", e)
                false
            }

            if (isValid) {
                try {
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1", null)
                        .use { cursor ->
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

    private fun containsExpressionIndex(file: File): Boolean {
        return try {
            val pattern = Regex(
                """(?i)(?:CREATE(?:\s+UNIQUE)?\s+INDEX\s+\w+\s+ON\s+\w+\s*\([^()]*)(?:LOWER|UPPER)\s*\(""",
                RegexOption.MULTILINE
            )
            val chunkSize = 1024 * 1024
            val overlap = 64 * 1024
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                var position = 0L
                val buffer = ByteArray(chunkSize + overlap)
                while (position < fileLength) {
                    val toRead = minOf(buffer.size.toLong(), fileLength - position).toInt()
                    raf.seek(position)
                    raf.readFully(buffer, 0, toRead)
                    val content = String(buffer, 0, toRead, Charsets.ISO_8859_1)
                    if (pattern.containsMatchIn(content)) return true
                    position += chunkSize
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for expression indexes", e)
            false
        }
    }

    private fun patchExpressionIndexes(file: File): Boolean {
        return try {
            val patterns = listOf(
                Regex("""LOWER\s*\(\s*([\w"`\[\].]+)\s*\)""", RegexOption.IGNORE_CASE),
                Regex("""UPPER\s*\(\s*([\w"`\[\].]+)\s*\)""", RegexOption.IGNORE_CASE),
            )

            val chunkSize = 1024 * 1024
            val overlap = 64 * 1024
            var modified = false

            RandomAccessFile(file, "rw").use { raf ->
                val fileLength = raf.length()
                var position = 0L
                val buffer = ByteArray(chunkSize + overlap)
                while (position < fileLength) {
                    val toRead = minOf(buffer.size.toLong(), fileLength - position).toInt()
                    raf.seek(position)
                    raf.readFully(buffer, 0, toRead)
                    val content = String(buffer, 0, toRead, Charsets.ISO_8859_1)

                    for (pattern in patterns) {
                        for (match in pattern.findAll(content)) {
                            if (match.range.first >= chunkSize) continue
                            val colName = match.groupValues[1]
                            val replacement = colName.padEnd(match.value.length)
                            if (replacement != match.value) {
                                val absOffset = position + match.range.first
                                raf.seek(absOffset)
                                raf.write(replacement.toByteArray(Charsets.ISO_8859_1))
                                modified = true
                            }
                        }
                    }

                    position += chunkSize
                }
            }

            if (modified) {
                Log.d(TAG, "Expression indexes patched in ${file.name}")
                true
            } else {
                Log.d(TAG, "No expression indexes found to patch")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch expression indexes", e)
            false
        }
    }

    private suspend fun extractArchiveWithValidation(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
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
                    extractArchiveLegacy(archiveFile, tempOutputFile, onProgress)
                } else {
                    extractArchive(archiveFile, tempOutputFile, onProgress)
                }

                if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    Thread.sleep(if (isLegacyAndroid) 500 else 100)

                    if (isLegacyAndroid && containsExpressionIndex(tempOutputFile)) {
                        val shouldPatch =
                            legacyConflictResolver?.onExpressionIndexConflict(tempOutputFile)
                                ?: false
                        if (shouldPatch) {
                            if (!patchExpressionIndexes(tempOutputFile)) {
                                Log.e(TAG, "Failed to patch expression indexes on attempt $attempt")
                            }
                        }
                    }

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

    private fun extractArchiveLegacy(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        val extension = archiveFile.extension.lowercase()

        when (extension) {
            "7z" -> extract7zLegacy(archiveFile, outputFile, onProgress)
            "zip" -> extractZipLegacy(archiveFile, outputFile, onProgress)
            "gz" -> extractGzip(archiveFile, outputFile, onProgress)
            "tgz", "tar" -> extractTar(archiveFile, outputFile, onProgress)
            else -> throw Exception("Unsupported archive format: $extension")
        }
    }

    private fun isDbFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3")
    }

    private fun isGzipMagic(bytes: ByteArray, length: Int): Boolean {
        return length >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    }

    private fun isTarMagic(bytes: ByteArray, length: Int): Boolean {
        return length >= 262 && String(bytes, 257, 5, Charsets.ISO_8859_1) == "ustar"
    }

    private fun readPrefix(file: File, buffer: ByteArray): Int {
        var read = 0
        file.inputStream().use { ins ->
            while (read < buffer.size) {
                val r = ins.read(buffer, read, buffer.size - read)
                if (r == -1) break
                read += r
            }
        }
        return read
    }

    private fun extractGzip(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        val gz = GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile)))
        try {
            val first = ByteArray(262)
            var read = 0
            while (read < first.size) {
                val r = gz.read(first, read, first.size - read)
                if (r == -1) break
                read += r
            }

            if (isTarMagic(first, read)) {
                val pushback = PushbackInputStream(gz, first.size)
                pushback.unread(first, 0, read)
                val tar = TarArchiveInputStream(BufferedInputStream(pushback))
                try {
                    extractFirstDbEntry(tar, outputFile, onProgress)
                } finally {
                    tar.close()
                }
            } else {
                FileOutputStream(outputFile).use { out ->
                    val bufferedOut = BufferedOutputStream(out, EXTRACT_BUFFER_SIZE)
                    try {
                        bufferedOut.write(first, 0, read)
                        val buf = ByteArray(EXTRACT_BUFFER_SIZE)
                        var total = read.toLong()
                        var r: Int
                        while (gz.read(buf).also { r = it } != -1) {
                            bufferedOut.write(buf, 0, r)
                            total += r
                            onProgress?.invoke(total, null)
                        }
                    } finally {
                        bufferedOut.flush()
                    }
                }
            }
        } finally {
            gz.close()
        }
    }

    private fun extractTar(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        val baseInput = BufferedInputStream(FileInputStream(archiveFile))
        val first = ByteArray(2)
        var read = 0
        while (read < first.size) {
            val r = baseInput.read(first, read, first.size - read)
            if (r == -1) break
            read += r
        }
        val pushback = PushbackInputStream(baseInput, first.size)
        pushback.unread(first, 0, read)
        val input: InputStream =
            if (isGzipMagic(first, read)) GzipCompressorInputStream(BufferedInputStream(pushback))
            else pushback
        val tar = TarArchiveInputStream(BufferedInputStream(input))
        try {
            extractFirstDbEntry(tar, outputFile, onProgress)
        } finally {
            tar.close()
        }
    }

    private fun extractFirstDbEntry(
        tar: TarArchiveInputStream,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && isDbFileName(entry.name)) {
                val entrySize = entry.size
                FileOutputStream(outputFile).use { out ->
                    val bufferedOut = BufferedOutputStream(out, EXTRACT_BUFFER_SIZE)
                    try {
                        val buf = ByteArray(EXTRACT_BUFFER_SIZE)
                        var total = 0L
                        var r: Int
                        while (tar.read(buf).also { r = it } != -1) {
                            bufferedOut.write(buf, 0, r)
                            total += r
                            onProgress?.invoke(total, entrySize.takeIf { it > 0 })
                        }
                    } finally {
                        bufferedOut.flush()
                    }
                }
                return
            }
            entry = tar.nextEntry
        }
        throw Exception("No database file found in tar archive")
    }

    private fun validateColumnMapping(
        dbFile: File,
        columnMapping: Map<String, String>,
        specifiedTableName: String?
    ): Pair<String?, Map<String, String>?> {
        return try {
            val db = openDatabaseSafe(dbFile.path)
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                .use { cursor ->
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
                    Log.w(
                        "SmartLinkDbHelper",
                        "Specified table '$specifiedTableName' not found. Available tables: $tables"
                    )
                    db.close()
                    return Pair(null, null)
                }

                else -> {
                    Log.w(
                        "SmartLinkDbHelper",
                        "No table specified for custom-auto-mapping. Available tables: $tables"
                    )
                    tables.first()
                }
            }

            val availableColumns =
                db.rawQuery("PRAGMA table_info($targetTable)", null).use { cursor ->
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
                Log.w(
                    "SmartLinkDbHelper",
                    "Skipped columns (not found in table '$targetTable'): $skippedColumns"
                )
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

    private suspend fun downloadMegaFile(
        dbInfo: SmartLinkDbInfo,
        url: String,
        outputFile: File,
        progressCallback: (Int, Long, Long?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val (resumeFile, _) = checkResumeDownload(dbInfo)
            val tempFile = resumeFile ?: getTempFile(dbInfo.id, dbInfo.version)
            val mainHandler = Handler(Looper.getMainLooper())

            val megaDownloader = MegaPublicDownloader(client)

            var resumeBytes = tempFile.length()
            if (resumeBytes > 0L) {
                Log.d(TAG, context.getString(R.string.resuming_download))
                withContext(Dispatchers.Main) {
                    progressCallback(PROGRESS_RESUME, 0, null)
                }
            }

            val maxAttempts = 5
            var attempt = 0
            var lastError: Exception? = null
            var lastMetaSaved = 0L
            var lastPostedProgress = -1

            while (attempt < maxAttempts) {
                attempt++
                ensureActive()
                try {
                    val result = megaDownloader.download(
                        megaUrl = url,
                        outputFile = tempFile,
                        resumeBytes = resumeBytes,
                        onProgress = { downloaded, total ->
                            val progress = if (total != null && total > 0) {
                                ((downloaded.toDouble() / total.toDouble()) * 100).toInt()
                            } else 0
                            val currentLength = tempFile.length()
                            if (total != null && total > 0 &&
                                (currentLength - lastMetaSaved >= 2 * 1024 * 1024)
                            ) {
                                lastMetaSaved = currentLength
                                saveDownloadMetadata(
                                    dbInfo.id,
                                    DownloadMetadata(
                                        version = dbInfo.version,
                                        totalSize = total,
                                        downloadedSize = currentLength,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                            if (progress != lastPostedProgress) {
                                lastPostedProgress = progress
                                mainHandler.post {
                                    progressCallback(progress, downloaded, total)
                                }
                            }
                        }
                    )

                    val downloadedFile = result.getOrThrow()

                    if (isArchiveFile(downloadedFile)) {
                        val archiveExtension = detectArchiveExtension(downloadedFile)
                        val tempArchiveFile = File(
                            context.cacheDir,
                            "${outputFile.nameWithoutExtension}_${dbInfo.version}.$archiveExtension"
                        )
                        downloadedFile.renameTo(tempArchiveFile)
                        var lastExtractPct = -1
                        try {
                            extractArchiveWithValidation(tempArchiveFile, outputFile) { bytes, total ->
                                val pct = if (total != null && total > 0) {
                                    ((bytes.toDouble() / total.toDouble()) * 100).toInt()
                                } else 0
                                if (pct != lastExtractPct) {
                                    lastExtractPct = pct
                                    mainHandler.post {
                                        progressCallback(PROGRESS_EXTRACT, pct.toLong(), total)
                                    }
                                }
                            }
                        } finally {
                            tempArchiveFile.delete()
                        }
                    } else {
                        downloadedFile.renameTo(outputFile)
                    }
                    return@withContext
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MegaQuotaException) {
                    lastError = e
                    Log.e(TAG, "MEGA bandwidth quota exceeded, not retrying: ${e.message}", e)
                    break
                } catch (e: MegaFileUnavailableException) {
                    lastError = e
                    Log.e(TAG, "MEGA file unavailable, not retrying: ${e.message}", e)
                    break
                } catch (e: IOException) {
                    lastError = e
                    Log.e(TAG, "MEGA download attempt $attempt/$maxAttempts failed: ${e.message}", e)
                    resumeBytes = tempFile.length()
                    if (attempt < maxAttempts) {
                        withContext(Dispatchers.Main) {
                            progressCallback(PROGRESS_RESUME, 0, null)
                        }
                        delay(2000L * attempt)
                    }
                }
            }

            if (lastError !is MegaQuotaException) {
                tempFile.delete()
            }
            throw lastError ?: Exception(context.getString(R.string.ds_failed_fetch_db_info))
        }
    }

    private fun isArchiveFile(file: File): Boolean {
        return try {
            val bytes = ByteArray(262)
            val length = readPrefix(file, bytes)
            val isZip = length >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            val is7z = length >= 6 && bytes[0] == 0x37.toByte() && bytes[1] == 0x7A.toByte() &&
                    bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() &&
                    bytes[4] == 0x27.toByte() && bytes[5] == 0x1C.toByte()
            isZip || is7z || isGzipMagic(bytes, length) || isTarMagic(bytes, length)
        } catch (e: Exception) {
            false
        }
    }

    private fun detectArchiveExtension(file: File): String {
        return try {
            val bytes = ByteArray(262)
            val length = readPrefix(file, bytes)
            when {
                length >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> "zip"
                length >= 6 && bytes[0] == 0x37.toByte() && bytes[1] == 0x7A.toByte() &&
                        bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() &&
                        bytes[4] == 0x27.toByte() && bytes[5] == 0x1C.toByte() -> "7z"
                isTarMagic(bytes, length) -> "tar"
                isGzipMagic(bytes, length) -> "gz"
                else -> "zip"
            }
        } catch (e: Exception) {
            "zip"
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

            var effectiveResume = resumePosition
            if (resumePosition > 0 && response.code == 200) {
                Log.w(TAG, "Server ignored Range while resuming, restarting from 0")
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.setLength(0L)
                }
                effectiveResume = 0L
            }

            val totalSize = if (response.code == 206) {
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast('/')?.toLongOrNull()
            } else {
                response.header("Content-Length")?.toLongOrNull()
            } ?: 0L

            var downloadedSize = effectiveResume

            if (totalSize > 0) {
                saveDownloadMetadata(
                    dbInfo.id, DownloadMetadata(
                        version = dbInfo.version,
                        totalSize = totalSize,
                        downloadedSize = downloadedSize,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            FileOutputStream(tempFile, effectiveResume > 0).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (isActive) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        if (totalSize > 0) {
                            saveDownloadMetadata(
                                dbInfo.id, DownloadMetadata(
                                    version = dbInfo.version,
                                    totalSize = totalSize,
                                    downloadedSize = downloadedSize,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
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
            val tempArchive = File(
                context.cacheDir,
                "${outputFile.nameWithoutExtension}_${dbInfo.version}.$archiveExtension"
            )
            val mainHandler = Handler(Looper.getMainLooper())

            if (!tempArchive.exists() || tempArchive.length() == 0L) {
                downloadDirectFileWithResume(dbInfo, url, tempArchive, progressCallback)
            } else {
                Log.d(TAG, "Archive already exists, skipping download")
            }

            withContext(Dispatchers.Main) {
                progressCallback(PROGRESS_RESUME, 0, null)
            }

            var lastExtractPct = -1
            extractArchiveWithValidation(tempArchive, outputFile) { bytes, total ->
                val pct = if (total != null && total > 0) {
                    ((bytes.toDouble() / total.toDouble()) * 100).toInt()
                } else 0
                if (pct != lastExtractPct) {
                    lastExtractPct = pct
                    mainHandler.post {
                        progressCallback(PROGRESS_EXTRACT, pct.toLong(), total)
                    }
                }
            }
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
            val mainHandler = Handler(Looper.getMainLooper())
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

            Log.d(
                TAG,
                "Resuming multipart download: ${completedCount}/${urls.size} parts already completed"
            )

            urls.forEachIndexed { index, url ->
                val partFile = getPartFile(dbInfo.id, dbInfo.version, index)
                tempParts.add(partFile)

                if (completedParts.getOrElse(index) { false } && partFile.exists() && partFile.length() > 0) {
                    totalDownloaded += partFile.length()
                    Log.d(
                        TAG,
                        "Part ${index + 1}/${urls.size} already downloaded (${partFile.length()} bytes)"
                    )

                    withContext(Dispatchers.Main) {
                        val overallProgress = (completedCount * 100) / urls.size
                        progressCallback(overallProgress, totalDownloaded, null)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressCallback(PROGRESS_PART, index.toLong() + 1, urls.size.toLong())
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
                progressCallback(PROGRESS_MERGE, 0, null)
            }

            val firstUrl = urls.firstOrNull() ?: throw Exception("No URLs provided")
            val archiveExtension = when {
                firstUrl.contains(".zip.", ignoreCase = true) -> "zip"
                firstUrl.contains(".7z.", ignoreCase = true) -> "7z"
                firstUrl.contains(".tgz.", ignoreCase = true) -> "tgz"
                firstUrl.contains(".tar.", ignoreCase = true) -> "tar"
                firstUrl.contains(".gz.", ignoreCase = true) -> "gz"
                else -> {
                    val fullName = firstUrl.substringAfterLast('/')
                    val extensionMatch =
                        Regex("\\.(zip|7z|tgz|tar|gz)\\.[0-9]+$", RegexOption.IGNORE_CASE).find(fullName)
                    extensionMatch?.groupValues?.get(1)?.lowercase() ?: "zip"
                }
            }

            val mergedArchive = File(
                context.cacheDir,
                "${outputFile.nameWithoutExtension}_${dbInfo.version}.$archiveExtension"
            )

            if (!mergedArchive.exists() || mergedArchive.length() == 0L) {
                mergeFiles(tempParts, mergedArchive)
                Log.d(TAG, "Files merged into archive: ${mergedArchive.length()} bytes")
            }

            tempParts.forEach { it.delete() }
            partsStatusFile.delete()

            withContext(Dispatchers.Main) {
                progressCallback(PROGRESS_MERGE, 0, null)
            }

            var lastExtractPct = -1
            extractArchiveWithValidation(mergedArchive, outputFile) { bytes, total ->
                val pct = if (total != null && total > 0) {
                    ((bytes.toDouble() / total.toDouble()) * 100).toInt()
                } else 0
                if (pct != lastExtractPct) {
                    lastExtractPct = pct
                    mainHandler.post {
                        progressCallback(PROGRESS_EXTRACT, pct.toLong(), total)
                    }
                }
            }
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
                                position += inChannel.transferTo(
                                    position,
                                    size - position,
                                    outChannel
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun extractAllSqliteFiles(archiveFile: File, destDir: File): List<File> {
        destDir.mkdirs()
        val extension = archiveFile.extension.lowercase()
        val allFiles = mutableListOf<File>()

        when {
            extension == "7z" -> extract7zAll(archiveFile, destDir, allFiles)
            extension == "zip" -> extractZipAll(archiveFile, destDir, allFiles)
            else -> throw Exception("Unsupported archive format: $extension")
        }

        return allFiles.filter { it.extension.lowercase() in setOf("db", "sqlite", "sqlite3") }
    }

    private fun extractZipAll(archiveFile: File, destDir: File, results: MutableList<File>) {
        java.util.zip.ZipInputStream(archiveFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                    results.add(outFile)
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun extract7zAll(archiveFile: File, destDir: File, results: MutableList<File>) {
        SevenZFile(archiveFile).use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    val buffer = ByteArray(BUFFER_SIZE)
                    outFile.outputStream().use { out ->
                        var bytesRead: Int
                        while (sevenZ.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                    results.add(outFile)
                }
                entry = sevenZ.nextEntry
            }
        }
    }

    private fun extractArchive(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        val extension = archiveFile.extension.lowercase()

        when (extension) {
            "7z" -> extract7z(archiveFile, outputFile, onProgress)
            "zip" -> extractZip(archiveFile, outputFile, onProgress)
            "gz" -> extractGzip(archiveFile, outputFile, onProgress)
            "tgz", "tar" -> extractTar(archiveFile, outputFile, onProgress)
            else -> throw Exception("Unsupported archive format: $extension")
        }
    }

    private fun extract7z(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        var sevenZFile: SevenZFile? = null
        var outputStream: FileOutputStream? = null

        try {
            sevenZFile = SevenZFile(archiveFile)
            var entry = sevenZFile.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && isDbFileName(entry.name)) {
                    outputStream = FileOutputStream(outputFile)
                    val bufferedOut = BufferedOutputStream(outputStream, EXTRACT_BUFFER_SIZE)
                    val entrySize = entry.size
                    val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    try {
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            bufferedOut.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onProgress?.invoke(totalBytesRead, entrySize.takeIf { it > 0 })

                            if (totalBytesRead > entry.size * 2) {
                                throw Exception("Extracted size exceeds expected size")
                            }
                        }
                        bufferedOut.flush()
                    } finally {
                        bufferedOut.close()
                        outputStream = null
                    }

                    extracted = true
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

    private fun extractZip(
        archiveFile: File,
        outputFile: File,
        onProgress: ((Long, Long?) -> Unit)? = null
    ) {
        var zipInput: ZipInputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            zipInput = ZipInputStream(BufferedInputStream(FileInputStream(archiveFile)))
            var entry = zipInput.nextEntry
            var extracted = false

            while (entry != null && !extracted) {
                if (!entry.isDirectory && isDbFileName(entry.name)) {
                    outputStream = FileOutputStream(outputFile)
                    val bufferedOut = BufferedOutputStream(outputStream, EXTRACT_BUFFER_SIZE)
                    val entrySize = entry.size
                    val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
                    var totalBytesRead = 0L
                    var bytesRead: Int

                    try {
                        while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                            bufferedOut.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            onProgress?.invoke(totalBytesRead, entrySize.takeIf { it > 0 })

                            if (entry.size > 0 && totalBytesRead > entry.size * 2) {
                                throw Exception("Extracted size exceeds expected size")
                            }
                        }
                        bufferedOut.flush()
                    } finally {
                        bufferedOut.close()
                        outputStream = null
                    }

                    extracted = true
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
                val db = openDatabaseSafe(file.path)
                try {
                    if (!DatabaseTypeUtils.hasTable(db, "geo")) {
                        return@withContext DbType.SQLITE_FILE_CUSTOM
                    }

                    val tables =
                        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                            .use { cursor ->
                                buildList {
                                    while (cursor.moveToNext()) {
                                        add(cursor.getString(0))
                                    }
                                }
                            }

                    val is3WiFi = tables.any { tableName ->
                        if (tableName.equals(
                                "geo",
                                ignoreCase = true
                            ) || tableName.equals("comments", ignoreCase = true)
                        ) false
                        else {
                            val hasBssid = DatabaseTypeUtils.hasColumn(db, tableName, "BSSID")
                            val hasEssid = DatabaseTypeUtils.hasColumn(db, tableName, "ESSID")
                            val hasWifiKey = DatabaseTypeUtils.hasColumn(db, tableName, "WiFiKey")
                            val hasWpsPin = DatabaseTypeUtils.hasColumn(db, tableName, "WPSPIN")
                            hasBssid && hasEssid && hasWifiKey && hasWpsPin
                        }
                    }

                    if (is3WiFi) DbType.SQLITE_FILE_P3WIFI else DbType.SQLITE_FILE_CUSTOM
                } finally {
                    db.close()
                }
            } catch (_: Exception) {
                DbType.SQLITE_FILE_CUSTOM
            }
        }
    }

    fun checkDbFormatWarning(file: File): String? {
        return try {
            val db = openDatabaseSafe(file.path)
            try {
                val hasBase = DatabaseTypeUtils.hasTable(db, "base")
                val hasNets = DatabaseTypeUtils.hasTable(db, "nets")
                val hasComments = DatabaseTypeUtils.hasTable(db, "comments")

                when {
                    hasBase -> context.getString(R.string.db_old_format_warning_base)
                    hasNets && !hasComments -> context.getString(R.string.db_old_format_warning_no_comments)
                    else -> null
                }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            null
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
                        dbInfo = parseSmartLinkDbObject(info)
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
                    downloadMultiPartArchiveWithResume(
                        dbInfo,
                        downloadUrls,
                        file
                    ) { progress, bytes, _ ->
                        progressCallback(if (progress == PROGRESS_EXTRACT) bytes.toInt() else progress)
                    }
                } else {
                    val url = downloadUrls.first()
                    if (url.endsWith(".zip", true) || url.endsWith(".7z", true)) {
                        downloadAndExtractArchiveWithResume(dbInfo, url, file) { progress, bytes, _ ->
                            progressCallback(if (progress == PROGRESS_EXTRACT) bytes.toInt() else progress)
                        }
                    } else {
                        downloadDirectFileWithResume(dbInfo, url, file) { progress, _, _ ->
                            progressCallback(progress)
                        }
                    }
                }

                val fileSize = file.length().toFloat() / (1024 * 1024)
                val directPath = file.absolutePath

                val (validatedTableName, validatedColumnMap) =
                    if (dbInfo.type == "custom-auto-mapping" && dbInfo.columnMapping != null) {
                        validateColumnMapping(file, dbInfo.columnMapping, dbInfo.tableName)
                    } else {
                        Pair(dbInfo.tableName, dbInfo.columnMapping)
                    }

                dbItem.copy(
                    path = "content://${context.packageName}.fileprovider/databases/$fileName",
                    directPath = directPath,
                    originalSizeInMB = fileSize,
                    cachedSizeInMB = fileSize,
                    version = newVersion,
                    tableName = validatedTableName,
                    columnMap = validatedColumnMap
                )
            } catch (e: Exception) {
                dbItem.idJson?.let { clearDownloadMetadata(it) }
                throw e
            }
        }
    }

}