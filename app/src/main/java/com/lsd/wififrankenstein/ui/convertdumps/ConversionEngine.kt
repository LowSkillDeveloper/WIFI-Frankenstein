package com.lsd.wififrankenstein.ui.convertdumps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class ConversionEngine(
    private val context: Context,
    private val mode: ConversionMode,
    private val indexing: IndexingOption,
    private val outputLocation: Uri? = null,
    private val optimizationEnabled: Boolean = true,
    private val progressCallback: (String, Int) -> Unit
) {

    private companion object {
        const val DB_VERSION = 1
        const val BATCH_SIZE_PERFORMANCE = 20000
        const val BATCH_SIZE_ECONOMY = 800
        const val BATCH_SIZE_ROUTERSCAN = 500
        const val BUFFER_SIZE_PERFORMANCE = 512 * 1024
        const val BUFFER_SIZE_ECONOMY = 64 * 1024
        const val BUFFER_SIZE = 64 * 1024
        const val DB_TIMEOUT = 30000L
    }

    suspend fun convertFiles(files: List<SelectedFile>): String = withContext(Dispatchers.IO) {
        Log.d("ConversionEngine", "Starting conversion of ${files.size} files")

        val databaseType = getDatabaseType(files)
        progressCallback(context.getString(R.string.detecting_file_types), 2)
        val fileName = "${databaseType}_${System.currentTimeMillis()}.db"
        val tempFile = File(context.cacheDir, fileName)

        Log.d("ConversionEngine", "Temp file: ${tempFile.absolutePath}")

        try {
            Log.d("ConversionEngine", "Creating database...")
            progressCallback(context.getString(R.string.setting_up_database), 1)
            val db = SQLiteDatabase.openOrCreateDatabase(tempFile, null)

            try {
                if (mode == ConversionMode.PERFORMANCE) {
                    db.execSQL("PRAGMA synchronous=OFF")
                    db.execSQL("PRAGMA cache_size=50000")
                    db.execSQL("PRAGMA temp_store=MEMORY")
                    db.execSQL("PRAGMA mmap_size=268435456")
                } else {
                    db.execSQL("PRAGMA synchronous=NORMAL")
                    db.execSQL("PRAGMA cache_size=5000")
                    db.execSQL("PRAGMA temp_store=MEMORY")
                }
            } catch (e: Exception) {
                Log.w("ConversionEngine", "Some PRAGMA commands not supported: ${e.message}")
            }

            progressCallback(context.getString(R.string.configuring_performance), 3)

            setupDatabase(db)

            Log.d("ConversionEngine", "Processing ${files.size} files...")
            progressCallback(context.getString(R.string.preparing_file_processing), 5)

            if (mode == ConversionMode.PERFORMANCE && files.size > 1) {
                convertFilesParallel(db, files)
            } else {
                convertFilesSequential(db, files)
            }

            if (coroutineContext.isActive) {
                progressCallback(context.getString(R.string.preparing_database_optimization), 86)
                yield()
                progressCallback(context.getString(R.string.starting_database_optimization), 87)
                Log.d("ConversionEngine", "Creating indexes...")
                progressCallback(context.getString(R.string.creating_database_indexes), 88)
                createIndexes(db)

                Log.d("ConversionEngine", "Optimizing database...")
                if (optimizationEnabled) {
                    Log.d("ConversionEngine", "Optimizing database...")
                    optimizeDatabase(db)
                } else {
                    progressCallback(context.getString(R.string.skipping_optimization), 92)
                    yield()
                }

                progressCallback(context.getString(R.string.completing_conversion), 100)
                delay(100)

                db.close()

                progressCallback(context.getString(R.string.saving_database_file), 99)

                val finalFile = if (outputLocation != null) {
                    copyToSelectedLocation(tempFile, outputLocation, fileName)
                } else {
                    val internalFile = File(context.filesDir, fileName)
                    tempFile.copyTo(internalFile, overwrite = true)
                    tempFile.delete()
                    internalFile
                }

                Log.d("ConversionEngine", "Conversion complete")

                Log.d("ConversionEngine", "Final file: ${finalFile.absolutePath}")
                finalFile.absolutePath
            } else {
                db.close()
                tempFile.delete()
                throw InterruptedException("Conversion was cancelled")
            }

        } catch (e: OutOfMemoryError) {
            Log.e("ConversionEngine", "Out of memory during conversion", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IllegalStateException("Not enough memory to complete conversion. Try using Economy mode or reduce file size.")
        } catch (e: Exception) {
            Log.e("ConversionEngine", "Conversion error", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    private suspend fun convertFilesParallel(db: SQLiteDatabase, files: List<SelectedFile>) {
        progressCallback(context.getString(R.string.preparing_parallel_processing), 6)
        val progressPerFile = 85 / files.size
        val cpuCount = Runtime.getRuntime().availableProcessors()
        val maxConcurrency = minOf(cpuCount, files.size, 4)

        Log.d("ConversionEngine", "Using parallel processing with $maxConcurrency threads")

        val semaphore = Semaphore(maxConcurrency)
        val progressMap = mutableMapOf<Int, Int>()

        coroutineScope {
            val jobs = files.mapIndexed { index, file ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!coroutineContext.isActive) return@async

                        Log.d("ConversionEngine", "Processing file ${index + 1}/${files.size}: ${file.name} (${file.type})")

                        when (file.type) {
                            DumpFileType.ROUTERSCAN_TXT -> {
                                convertRouterScanFile(db, file.uri, file.name) { progress ->
                                    synchronized(progressMap) {
                                        progressMap[index] = progress
                                        val globalProgress = calculateGlobalProgress(progressMap, progressPerFile)
                                        progressCallback(file.name, globalProgress)
                                    }
                                }
                            }
                            DumpFileType.WIFI_3_SQL, DumpFileType.P3WIFI_SQL -> {
                                convertSqlFile(db, file.uri, file.name, file.type) { progress ->
                                    synchronized(progressMap) {
                                        progressMap[index] = progress
                                        val globalProgress = calculateGlobalProgress(progressMap, progressPerFile)
                                        progressCallback(file.name, globalProgress)
                                    }
                                }
                            }
                            DumpFileType.UNKNOWN -> {
                                Log.w("ConversionEngine", "Skipping unknown file type: ${file.name}")
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()
        }
    }

    private suspend fun convertFilesSequential(db: SQLiteDatabase, files: List<SelectedFile>) {
        val progressPerFile = 85 / files.size

        for ((index, file) in files.withIndex()) {
            if (!coroutineContext.isActive) break

            Log.d("ConversionEngine", "Processing file ${index + 1}/${files.size}: ${file.name} (${file.type})")

            val baseProgress = index * progressPerFile

            when (file.type) {
                DumpFileType.ROUTERSCAN_TXT -> {
                    convertRouterScanFile(db, file.uri, file.name) { progress ->
                        val globalProgress = baseProgress + (progress * progressPerFile / 100)
                        progressCallback(file.name, globalProgress)
                    }
                }
                DumpFileType.WIFI_3_SQL, DumpFileType.P3WIFI_SQL -> {
                    convertSqlFile(db, file.uri, file.name, file.type) { progress ->
                        val globalProgress = baseProgress + (progress * progressPerFile / 100)
                        progressCallback(file.name, globalProgress)
                    }
                }
                DumpFileType.UNKNOWN -> {
                    Log.w("ConversionEngine", "Skipping unknown file type: ${file.name}")
                    continue
                }
            }

            yield()
        }
    }

    private fun calculateGlobalProgress(progressMap: Map<Int, Int>, progressPerFile: Int): Int {
        val totalProgress = progressMap.values.sum() * progressPerFile / 100
        return minOf(totalProgress / progressMap.size, 85)
    }

    private fun copyToSelectedLocation(sourceFile: File, directoryUri: Uri, fileName: String): File {
        progressCallback(context.getString(R.string.copying_to_destination), 99)
        return try {
            val documentsResolver = context.contentResolver
            val documentFile = DocumentFile.fromTreeUri(context, directoryUri)

            if (documentFile != null && documentFile.exists() && documentFile.isDirectory) {
                val existingFile = documentFile.findFile(fileName)
                existingFile?.delete()

                val newFile = documentFile.createFile("application/octet-stream", fileName)
                if (newFile != null) {
                    val outputStream = documentsResolver.openOutputStream(newFile.uri)
                    if (outputStream != null) {
                        sourceFile.inputStream().use { input ->
                            outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }

                        sourceFile.delete()

                        val resultFile = File(context.getExternalFilesDir(null), fileName)
                        resultFile.writeText("File saved to: ${newFile.uri}")

                        Log.d("ConversionEngine", "File successfully saved to selected location: ${newFile.uri}")
                        return resultFile
                    }
                }
            }

            Log.w("ConversionEngine", "Failed to save to selected location, copying to internal storage")
            val internalFile = File(context.filesDir, fileName)
            sourceFile.copyTo(internalFile, overwrite = true)
            sourceFile.delete()
            internalFile

        } catch (e: Exception) {
            Log.e("ConversionEngine", "Failed to copy file to selected location", e)
            val internalFile = File(context.filesDir, fileName)
            sourceFile.copyTo(internalFile, overwrite = true)
            sourceFile.delete()
            internalFile
        }
    }

    private suspend fun convertRouterScanFile(
        db: SQLiteDatabase,
        uri: Uri,
        fileName: String,
        progressCallback: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        Log.d("ConversionEngine", "Starting RouterScan conversion for: $fileName")

        val inputStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e("ConversionEngine", "Failed to open input stream for $fileName", e)
            throw IllegalArgumentException("Cannot open file: $fileName - ${e.message}")
        }

        if (inputStream == null) {
            Log.e("ConversionEngine", "Input stream is null for $fileName")
            throw IllegalArgumentException("Cannot open file: $fileName")
        }

        Log.d("ConversionEngine", "Input stream opened successfully for $fileName")
        progressCallback(context.getString(R.string.opening_file, fileName), 0)

        val bufferSize = if (mode == ConversionMode.PERFORMANCE) BUFFER_SIZE_PERFORMANCE else BUFFER_SIZE_ECONOMY
        val reader = BufferedReader(InputStreamReader(inputStream), bufferSize)
        val batchSize = BATCH_SIZE_ROUTERSCAN

        val geoInserts = mutableListOf<Array<Any?>>()
        val netsInserts = mutableListOf<Array<Any?>>()

        var lineCount = 0
        var validCount = 0
        var totalLines = 0

        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val fileSize = documentFile?.length() ?: 0L
            totalLines = if (fileSize > 0L) {
                (fileSize / 100).toInt().coerceAtLeast(1000)
            } else {
                10000
            }
        } catch (e: Exception) {
            totalLines = 10000
        }

        val newInputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot reopen file: $fileName")

        BufferedReader(InputStreamReader(newInputStream), bufferSize).use { bufferedReader ->

            try {
                bufferedReader.lineSequence().forEach { line ->
                    if (!coroutineContext.isActive) return@forEach

                    lineCount++

                    if (lineCount % 50 == 0) {
                        val progress = (lineCount * 100 / totalLines).coerceAtMost(100)
                        progressCallback(progress)
                        yield()
                    }

                    parseRouterScanLine(line)?.let { network ->
                        validCount++

                        network.bssidInt?.let { bssid ->
                            if (network.latitude != null && network.longitude != null) {
                                val quadkey = calculateQuadkey(network.latitude, network.longitude)
                                geoInserts.add(arrayOf(bssid, network.latitude, network.longitude, quadkey))
                            }

                            netsInserts.add(arrayOf(
                                System.currentTimeMillis(),
                                0,
                                0,
                                0,
                                network.adminPanel,
                                "",
                                0,
                                0,
                                if (network.bssid.isNullOrEmpty()) 1 else 0,
                                bssid,
                                network.essid,
                                if (network.wifiKey.isNullOrEmpty()) 0 else 2,
                                if (network.wifiKey.isNullOrEmpty()) 1 else 0,
                                network.wifiKey ?: "",
                                if (network.wpsPin.isNullOrEmpty()) 1 else 0,
                                network.wpsPin?.toLongOrNull() ?: 0,
                                0, 0, 0, 0, 0, 0, 0, 0
                            ))
                        }

                        if (geoInserts.size >= batchSize) {
                            insertGeoBatchWithTransaction(db, geoInserts)
                            geoInserts.clear()
                            yield()
                        }

                        if (netsInserts.size >= batchSize) {
                            insertNetsBatchWithTransaction(db, netsInserts)
                            netsInserts.clear()
                            yield()
                        }
                    }
                }

                if (geoInserts.isNotEmpty()) {
                    insertGeoBatchWithTransaction(db, geoInserts)
                }
                if (netsInserts.isNotEmpty()) {
                    insertNetsBatchWithTransaction(db, netsInserts)
                }

            } catch (e: Exception) {
                Log.e("ConversionEngine", "Error processing RouterScan file: $fileName", e)
                throw e
            }
        }
    }

    private fun insertGeoBatchWithTransaction(db: SQLiteDatabase, batch: List<Array<Any?>>) {
        db.beginTransaction()
        try {
            insertGeoBatch(db, batch)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertNetsBatchWithTransaction(db: SQLiteDatabase, batch: List<Array<Any?>>) {
        db.beginTransaction()
        try {
            insertNetsBatch(db, batch)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun getDatabaseType(files: List<SelectedFile>): String {
        return when {
            files.any { it.type == DumpFileType.P3WIFI_SQL } -> "p3wifi"
            files.any { it.type == DumpFileType.WIFI_3_SQL } -> "3wifi"
            files.any { it.type == DumpFileType.ROUTERSCAN_TXT } -> "routerscan"
            else -> "converted"
        }
    }

    private suspend fun convertSqlFile(
        db: SQLiteDatabase,
        uri: Uri,
        fileName: String,
        type: DumpFileType,
        progressCallback: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        Log.d("ConversionEngine", "Starting SQL conversion for: $fileName (type: $type)")

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file: $fileName")

        val bufferSize = if (mode == ConversionMode.PERFORMANCE) BUFFER_SIZE_PERFORMANCE else BUFFER_SIZE_ECONOMY
        val reader = BufferedReader(InputStreamReader(inputStream), bufferSize)
        val batchSize = if (mode == ConversionMode.PERFORMANCE) BATCH_SIZE_PERFORMANCE else BATCH_SIZE_ECONOMY

        var totalBytes = 0L
        var processedBytes = 0L

        try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            totalBytes = documentFile?.length() ?: 0L

            if (totalBytes <= 0L) {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeColumn != -1) {
                            totalBytes = cursor.getLong(sizeColumn)
                        }
                    }
                }
            }

            if (totalBytes <= 0L) {
                totalBytes = 1024L * 1024L * 1024L
                progressCallback(context.getString(R.string.calculating_file_size), 0)
            }
        } catch (e: Exception) {
            totalBytes = 1024L * 1024L * 1024L
        }

        Log.d("ConversionEngine", "File size: $totalBytes bytes")

        val tablePatterns = when (type) {
            DumpFileType.WIFI_3_SQL -> mapOf(
                "base" to listOf(
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?base`?",
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?nets`?"
                ),
                "geo" to listOf(
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?geo`?"
                )
            )
            DumpFileType.P3WIFI_SQL -> mapOf(
                "nets" to listOf(
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?nets`?"
                ),
                "geo" to listOf(
                    "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+`?geo`?"
                )
            )
            else -> emptyMap()
        }

        val buffer = StringBuilder()
        val charArray = CharArray(bufferSize)
        var charsRead: Int
        var lastProgressUpdate = 0

        while (reader.read(charArray).also { charsRead = it } != -1) {
            if (!coroutineContext.isActive) break

            buffer.append(charArray, 0, charsRead)
            processedBytes += charsRead * 2

            val currentProgress = if (totalBytes > 0L) {
                (processedBytes * 100 / totalBytes).coerceAtMost(100).toInt()
            } else {
                50
            }
            if (currentProgress > lastProgressUpdate) {
                progressCallback(currentProgress)
                lastProgressUpdate = currentProgress
                yield()
            }

            if (buffer.length > bufferSize * 4) {
                processBufferForInserts(db, buffer, tablePatterns, batchSize, type)

                val lastInsert = buffer.lastIndexOf("INSERT")
                if (lastInsert > bufferSize) {
                    buffer.delete(0, lastInsert)
                }
            }
        }

        if (buffer.isNotEmpty()) {
            processBufferForInserts(db, buffer, tablePatterns, batchSize, type, true)
        }

        reader.close()
    }

    private suspend fun processBufferForInserts(
        db: SQLiteDatabase,
        buffer: StringBuilder,
        tablePatterns: Map<String, List<String>>,
        batchSize: Int,
        fileType: DumpFileType,
        isLastChunk: Boolean = false
    ) {
        for ((tableName, patterns) in tablePatterns) {
            for (pattern in patterns) {
                val insertPattern = Pattern.compile(
                    "$pattern.*?VALUES\\s*(.+?)(?=INSERT|$)",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                )

                val matcher = insertPattern.matcher(buffer)
                val batches = mutableListOf<Array<Any?>>()

                while (matcher.find()) {
                    val valuesBlock = matcher.group(1)
                    if (valuesBlock != null) {
                        val values = parseValuesBlock(valuesBlock)
                        for (valueArray in values) {
                            when (tableName) {
                                "geo" -> {
                                    if (valueArray.size >= 4) {
                                        batches.add(arrayOf(
                                            valueArray[0]?.toString()?.toLongOrNull(),
                                            valueArray[1]?.toString()?.toDoubleOrNull(),
                                            valueArray[2]?.toString()?.toDoubleOrNull(),
                                            valueArray[3]?.toString()?.toLongOrNull()
                                        ))
                                    }
                                }
                                "base" -> {
                                    if (fileType == DumpFileType.WIFI_3_SQL) {
                                        val skipFirstColumn = true
                                        val startIndex = if (skipFirstColumn) 1 else 0
                                        val columnsNeeded = 23

                                        val processedRow = Array<Any?>(columnsNeeded) { index ->
                                            val sourceIndex = index + startIndex
                                            val originalValue = if (sourceIndex < valueArray.size) valueArray[sourceIndex] else null
                                            when {
                                                index == 9 && originalValue == null -> 0L
                                                index == 8 && originalValue == null -> 0L
                                                else -> originalValue
                                            }
                                        }
                                        batches.add(processedRow)
                                    } else {
                                        val columnsNeeded = 24
                                        val skipFirstColumn = fileType == DumpFileType.P3WIFI_SQL
                                        val startIndex = if (skipFirstColumn) 1 else 0

                                        val processedRow = Array<Any?>(columnsNeeded) { index ->
                                            val sourceIndex = index + startIndex
                                            val originalValue = if (sourceIndex < valueArray.size) valueArray[sourceIndex] else null
                                            when {
                                                index == 15 && originalValue == null -> 0L
                                                index == 9 && originalValue == null -> 0L
                                                index == 8 && originalValue == null -> 0L
                                                else -> originalValue
                                            }
                                        }
                                        batches.add(processedRow)
                                    }
                                }
                                "nets" -> {
                                    val columnsNeeded = 24
                                    val skipFirstColumn = fileType == DumpFileType.P3WIFI_SQL
                                    val startIndex = if (skipFirstColumn) 1 else 0

                                    val processedRow = Array<Any?>(columnsNeeded) { index ->
                                        val sourceIndex = index + startIndex
                                        val originalValue = if (sourceIndex < valueArray.size) valueArray[sourceIndex] else null
                                        when {
                                            index == 15 && originalValue == null -> 0L
                                            index == 9 && originalValue == null -> 0L
                                            index == 8 && originalValue == null -> 0L
                                            else -> originalValue
                                        }
                                    }
                                    batches.add(processedRow)
                                }
                            }

                            if (batches.size >= batchSize) {
                                executeBatch(db, tableName, batches, fileType)
                                batches.clear()
                                yield()
                            }
                        }
                    }
                }

                if (batches.isNotEmpty()) {
                    executeBatch(db, tableName, batches, fileType)
                }
            }
        }

        if (isLastChunk) {
            buffer.clear()
        }
    }

    private fun executeBatch(db: SQLiteDatabase, tableName: String, batches: List<Array<Any?>>, fileType: DumpFileType) {
        db.beginTransaction()
        try {
            when (tableName) {
                "geo" -> insertGeoBatch(db, batches)
                "base" -> {
                    if (fileType == DumpFileType.WIFI_3_SQL) {
                        insertBaseBatch(db, batches)
                    } else {
                        insertNetsBatch(db, batches)
                    }
                }
                "nets" -> insertNetsBatch(db, batches)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertBaseBatch(db: SQLiteDatabase, batch: List<Array<Any?>>) {
        val sql = """INSERT INTO base (time, cmtid, IP, Port, Authorization, name, RadioOff, 
                Hidden, NoBSSID, BSSID, ESSID, Security, WiFiKey, 
                WPSPIN, LANIP, LANMask, WANIP, WANMask, WANGateway, DNS1, DNS2, DNS3) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

        val statement = db.compileStatement(sql)

        try {
            for (row in batch) {
                statement.clearBindings()
                for (i in 0 until 22) {
                    val value = if (i < row.size) row[i] else null
                    when (value) {
                        null -> {
                            when (i) {
                                8, 9 -> statement.bindLong(i + 1, 0)
                                13 -> statement.bindLong(i + 1, 0)
                                else -> statement.bindNull(i + 1)
                            }
                        }
                        is Long -> statement.bindLong(i + 1, value)
                        is Double -> statement.bindDouble(i + 1, value)
                        is String -> {
                            if (value.isEmpty() && i == 13) {
                                statement.bindLong(i + 1, 0)
                            } else {
                                statement.bindString(i + 1, value)
                            }
                        }
                        else -> {
                            val strValue = value.toString()
                            if (strValue.isEmpty() && i == 13) {
                                statement.bindLong(i + 1, 0)
                            } else {
                                statement.bindString(i + 1, strValue)
                            }
                        }
                    }
                }
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun parseValuesBlock(valuesBlock: String): List<List<Any?>> {
        val results = mutableListOf<List<Any?>>()
        val cleanValues = valuesBlock.trim().removeSuffix(";")

        var i = 0
        while (i < cleanValues.length) {
            if (cleanValues[i] == '(') {
                val tupleEnd = findMatchingParen(cleanValues, i)
                if (tupleEnd != -1) {
                    val tupleContent = cleanValues.substring(i + 1, tupleEnd)
                    results.add(parseTupleContent(tupleContent))
                    i = tupleEnd + 1

                    while (i < cleanValues.length && (cleanValues[i] == ',' || cleanValues[i].isWhitespace())) {
                        i++
                    }
                } else {
                    break
                }
            } else {
                i++
            }
        }

        return results
    }

    private fun findMatchingParen(text: String, start: Int): Int {
        var level = 0
        var inString = false
        var stringChar = '\u0000'
        var i = start

        while (i < text.length) {
            val char = text[i]

            if (!inString) {
                when (char) {
                    '(' -> level++
                    ')' -> {
                        level--
                        if (level == 0) return i
                    }
                    '\'', '"' -> {
                        inString = true
                        stringChar = char
                    }
                }
            } else {
                if (char == stringChar && (i == 0 || text[i-1] != '\\')) {
                    inString = false
                }
            }
            i++
        }

        return -1
    }

    private fun parseTupleContent(content: String): List<Any?> {
        val values = mutableListOf<Any?>()
        var current = StringBuilder()
        var inString = false
        var stringChar = '\u0000'
        var parenLevel = 0

        for (i in content.indices) {
            val char = content[i]

            when {
                !inString && char == '(' -> {
                    parenLevel++
                    current.append(char)
                }
                !inString && char == ')' -> {
                    parenLevel--
                    current.append(char)
                }
                !inString && (char == '\'' || char == '"') -> {
                    inString = true
                    stringChar = char
                    current.append(char)
                }
                inString && char == stringChar && (i == 0 || content[i-1] != '\\') -> {
                    inString = false
                    current.append(char)
                }
                !inString && char == ',' && parenLevel == 0 -> {
                    values.add(processValue(current.toString().trim()))
                    current.clear()
                }
                else -> {
                    current.append(char)
                }
            }
        }

        if (current.isNotEmpty()) {
            values.add(processValue(current.toString().trim()))
        }

        return values
    }

    private fun processValue(value: String): Any? {
        if (value.isEmpty() || value.equals("NULL", ignoreCase = true)) {
            return null
        }

        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))) {
            val cleanValue = value.substring(1, value.length - 1)
                .replace("''", "'")
                .replace("\\\"", "\"")
                .replace("\\'", "'")

            cleanValue.toLongOrNull()?.let { return it }
            cleanValue.toDoubleOrNull()?.let { return it }
            return cleanValue
        }

        if (value.equals("0", ignoreCase = true) ||
            value.equals("false", ignoreCase = true) ||
            value.equals("''", ignoreCase = true) ||
            value.equals("\"\"", ignoreCase = true)) {
            return 0L
        }

        value.toLongOrNull()?.let { return it }
        value.toDoubleOrNull()?.let { return it }

        return value
    }

    private fun insertGeoBatch(db: SQLiteDatabase, batch: List<Array<Any?>>) {
        val sql = "INSERT INTO geo (BSSID, latitude, longitude, quadkey) VALUES (?, ?, ?, ?)"
        val statement = db.compileStatement(sql)

        try {
            for (row in batch) {
                statement.clearBindings()
                for (i in row.indices) {
                    when (val value = row[i]) {
                        null -> statement.bindNull(i + 1)
                        is Long -> statement.bindLong(i + 1, value)
                        is Double -> statement.bindDouble(i + 1, value)
                        is String -> statement.bindString(i + 1, value)
                        else -> statement.bindString(i + 1, value.toString())
                    }
                }
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun insertNetsBatch(db: SQLiteDatabase, batch: List<Array<Any?>>) {
        val sql = """INSERT INTO nets (time, cmtid, IP, Port, Authorization, name, RadioOff, 
                    Hidden, NoBSSID, BSSID, ESSID, Security, NoWiFiKey, WiFiKey, NoWPS, 
                    WPSPIN, LANIP, LANMask, WANIP, WANMask, WANGateway, DNS1, DNS2, DNS3) 
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

        val statement = db.compileStatement(sql)

        try {
            for (row in batch) {
                statement.clearBindings()
                for (i in 0 until 24) {
                    val value = if (i < row.size) row[i] else null
                    when (value) {
                        null -> {
                            when (i) {
                                8, 9 -> statement.bindLong(i + 1, 0)
                                15 -> statement.bindLong(i + 1, 0)
                                else -> statement.bindNull(i + 1)
                            }
                        }
                        is Long -> statement.bindLong(i + 1, value)
                        is Double -> statement.bindDouble(i + 1, value)
                        is String -> {
                            if (value.isEmpty() && i == 15) {
                                statement.bindLong(i + 1, 0)
                            } else {
                                statement.bindString(i + 1, value)
                            }
                        }
                        else -> {
                            val strValue = value.toString()
                            if (strValue.isEmpty() && i == 15) {
                                statement.bindLong(i + 1, 0)
                            } else {
                                statement.bindString(i + 1, strValue)
                            }
                        }
                    }
                }
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun setupDatabase(db: SQLiteDatabase) {
        progressCallback(context.getString(R.string.creating_database_tables), 4)
        try {
            db.execSQL("PRAGMA busy_timeout=$DB_TIMEOUT")
            db.execSQL("PRAGMA journal_mode=WAL")
            db.execSQL("PRAGMA wal_autocheckpoint=1000")
        } catch (e: Exception) {
            Log.w("ConversionEngine", "Some PRAGMA commands not supported: ${e.message}")
        }

        db.execSQL("""
        CREATE TABLE IF NOT EXISTS geo (
            BSSID INTEGER,
            latitude REAL,
            longitude REAL,
            quadkey INTEGER
        )
    """)

        db.execSQL("""
        CREATE TABLE IF NOT EXISTS nets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            time TEXT,
            cmtid INTEGER,
            IP INTEGER,
            Port INTEGER,
            Authorization TEXT,
            name TEXT,
            RadioOff INTEGER DEFAULT 0,
            Hidden INTEGER DEFAULT 0,
            NoBSSID INTEGER DEFAULT 0,
            BSSID INTEGER DEFAULT 0,
            ESSID TEXT,
            Security INTEGER,
            NoWiFiKey INTEGER DEFAULT 0,
            WiFiKey TEXT DEFAULT '',
            NoWPS INTEGER DEFAULT 0,
            WPSPIN INTEGER DEFAULT 0,
            LANIP INTEGER,
            LANMask INTEGER,
            WANIP INTEGER,
            WANMask INTEGER,
            WANGateway INTEGER,
            DNS1 INTEGER,
            DNS2 INTEGER,
            DNS3 INTEGER
        )
    """)

        db.execSQL("""
        CREATE TABLE IF NOT EXISTS base (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            time TEXT,
            cmtid INTEGER,
            IP INTEGER,
            Port INTEGER,
            Authorization TEXT,
            name TEXT,
            RadioOff INTEGER DEFAULT 0,
            Hidden INTEGER DEFAULT 0,
            NoBSSID INTEGER DEFAULT 0,
            BSSID INTEGER DEFAULT 0,
            ESSID TEXT,
            Security INTEGER,
            WiFiKey TEXT DEFAULT '',
            WPSPIN INTEGER DEFAULT 0,
            LANIP INTEGER,
            LANMask INTEGER,
            WANIP INTEGER,
            WANMask INTEGER,
            WANGateway INTEGER,
            DNS1 INTEGER,
            DNS2 INTEGER,
            DNS3 INTEGER
        )
    """)
    }

    private suspend fun createIndexes(db: SQLiteDatabase) {
        val indexes = when (indexing) {
            IndexingOption.FULL -> listOf(
                "CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude)",
                "CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude)",
                "CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID)",
                "CREATE INDEX IF NOT EXISTS idx_nets_wpspin ON nets (WPSPIN)",
                "CREATE INDEX IF NOT EXISTS idx_nets_wifikey ON nets (WiFiKey)",
                "CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID)",
                "CREATE INDEX IF NOT EXISTS idx_base_wpspin ON base (WPSPIN)",
                "CREATE INDEX IF NOT EXISTS idx_base_wifikey ON base (WiFiKey)"
            )
            IndexingOption.BASIC -> listOf(
                "CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude)",
                "CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude)",
                "CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID)",
                "CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID)",
                "CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID)"
            )
            IndexingOption.NONE -> emptyList()
        }

        Log.d("ConversionEngine", "Starting index creation with ${indexes.size} indexes")

        if (indexes.isEmpty()) {
            Log.d("ConversionEngine", "No indexes to create")
            progressCallback(context.getString(R.string.skipping_index_creation), 90)
            return
        }

        Log.d("ConversionEngine", "Creating ${indexes.size} indexes")
        val progressStep = 4.0 / indexes.size
        var currentProgress = 88.0

        for ((index, sql) in indexes.withIndex()) {
            if (!coroutineContext.isActive) break

            try {
                val tableName = extractTableNameFromIndex(sql)
                progressCallback(context.getString(R.string.creating_index_for_table, index + 1, indexes.size, tableName), currentProgress.toInt())
                yield()
                db.execSQL(sql)
                currentProgress += progressStep
                Log.d("ConversionEngine", "Created index ${index + 1}/${indexes.size} for table $tableName")
            } catch (e: Exception) {
                Log.w("ConversionEngine", "Failed to create index: $sql", e)
            }
        }

        progressCallback(context.getString(R.string.indexes_creation_complete), 92)
    }

    private fun extractTableNameFromIndex(sql: String): String {
        return try {
            val pattern = "ON\\s+(\\w+)\\s*\\(".toRegex(RegexOption.IGNORE_CASE)
            pattern.find(sql)?.groupValues?.get(1) ?: "table"
        } catch (e: Exception) {
            "table"
        }
    }

    private suspend fun optimizeDatabase(db: SQLiteDatabase) {
        try {
            progressCallback(context.getString(R.string.analyzing_database), 93)
            yield()
            db.execSQL("ANALYZE")

            progressCallback(context.getString(R.string.optimizing_database_queries), 95)
            yield()
            db.execSQL("PRAGMA optimize")

            progressCallback(context.getString(R.string.finalizing_database), 98)
            yield()

        } catch (e: Exception) {
            Log.w("ConversionEngine", "Database optimization partially failed: ${e.message}")
        }
    }

    private fun parseRouterScanLine(line: String): RouterScanNetwork? {
        if (line.trim().isEmpty() || line.startsWith("#")) return null

        val parts = line.split("\t")
        if (parts.size < 9) return null

        val essid = if (parts.size > 9) parts[9].trim() else ""
        val bssid = if (parts.size > 8) parts[8].trim() else ""
        val wifiKey = if (parts.size > 11) parts[11].trim() else ""
        val wpsPin = if (parts.size > 12) parts[12].trim() else ""
        val adminPanel = if (parts.size > 4) parts[4].trim() else ""

        var latitude: Double? = null
        var longitude: Double? = null

        if (parts.size > 20) {
            try {
                val lat = parts[19].trim().toDoubleOrNull()
                val lon = parts[20].trim().toDoubleOrNull()
                if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                    latitude = lat
                    longitude = lon
                }
            } catch (e: Exception) {
            }
        }

        val bssidInt = macToInteger(bssid)
        if (bssidInt == 0L && essid.isEmpty()) return null

        return RouterScanNetwork(
            essid = essid,
            bssid = bssid,
            bssidInt = bssidInt,
            wifiKey = wifiKey.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it != "NULL" },
            wpsPin = wpsPin.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it != "NULL" },
            adminPanel = adminPanel.takeIf { it.isNotEmpty() && it != ":" && it != "-" && it != "NULL" && it.contains(":") },
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun macToInteger(mac: String): Long {
        if (mac.isEmpty()) return 0L

        try {
            val cleanMac = mac.replace(":", "").replace("-", "").uppercase()
            if (cleanMac.length == 12) {
                return cleanMac.toLong(16)
            }
        } catch (e: Exception) {
        }

        return 0L
    }

    private fun calculateQuadkey(lat: Double, lon: Double, level: Int = 18): Long {
        if (lat == 0.0 || lon == 0.0) return 0L

        try {
            val n = 1 shl level
            val xTile = ((lon + 180.0) / 360.0 * n).toInt()
            val yTile = ((1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * n).toInt()

            var quadkey = 0L
            for (i in level - 1 downTo 0) {
                val mask = 1 shl i
                var cell = 0
                if ((xTile and mask) != 0) cell += 1
                if ((yTile and mask) != 0) cell += 2
                quadkey = (quadkey shl 2) or cell.toLong()
            }

            return quadkey
        } catch (e: Exception) {
            return 0L
        }
    }

    private data class RouterScanNetwork(
        val essid: String,
        val bssid: String,
        val bssidInt: Long,
        val wifiKey: String?,
        val wpsPin: String?,
        val adminPanel: String?,
        val latitude: Double?,
        val longitude: Double?
    )
}