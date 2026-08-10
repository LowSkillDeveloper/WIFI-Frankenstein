package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Looper
import com.lsd.wififrankenstein.ui.databasefinder.AdvancedSearchQuery
import com.lsd.wififrankenstein.ui.databasefinder.SearchMode
import com.lsd.wififrankenstein.ui.wifimap.ClusteredMapPoint
import com.lsd.wififrankenstein.util.CompatibilityHelper
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

class SQLiteCustomHelper(
    private val context: Context,
    private val dbUri: Uri,
    private val directPath: String?
) : SQLiteOpenHelper(context, null, null, 1) {

    val database: SQLiteDatabase? get() = _database
    private var _database: SQLiteDatabase? = null
    private val databaseLock = ReentrantLock()

    private val resultsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, Any?>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Any?>?>): Boolean {
                return size > 1000
            }
        }
    )

    init {
        try {
            _database = if (!directPath.isNullOrBlank()) {
                openDatabaseFromDirectPath()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SQLiteCustomHelper", "Unable to open database from direct path", e)
            _database = null
        }

        if (_database == null) {
            try {
                _database = openDatabaseFromUri()
            } catch (e: Exception) {
                Log.e("SQLiteCustomHelper", "Failed to open database from URI", e)
            }
        }
    }

    suspend fun copyAndOpenWithProgress(
        onProgress: (Int, Long, Long) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        if (_database != null) {
            return true
        }

        return try {
            _database = copyUriToCacheWithProgressAndOpen(onProgress, onCancel)
            _database != null
        } catch (e: Exception) {
            Log.e("SQLiteCustomHelper", "Failed to copy and open database", e)
            throw e
        }
    }

    private suspend fun copyUriToCacheWithProgressAndOpen(
        onProgress: (Int, Long, Long) -> Unit,
        onCancel: () -> Unit
    ): SQLiteDatabase {
        var fileSize: Long = 0
        context.contentResolver.openFileDescriptor(dbUri, "r")?.use { fileDescriptor ->
            fileSize = fileDescriptor.statSize
        }

        val fileName = getFileNameFromUri(dbUri)
        val cacheDir = File(context.cacheDir, "CacheDB").apply { mkdirs() }
        val tempFile = File(cacheDir, fileName)
        var totalBytes = 0L
        var lastProgressReport = -1

        try {
            context.contentResolver.openInputStream(dbUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val bufferSize = if (fileSize > 500 * 1024 * 1024) 131072 else 65536
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val progress = if (fileSize > 0) {
                            (totalBytes * 100 / fileSize).toInt()
                        } else {
                            -1
                        }

                        if (progress != lastProgressReport && progress > 0) {
                            lastProgressReport = progress
                            onProgress(progress, totalBytes, fileSize)
                        }
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("Failed to copy file: empty or missing")
            }

            return SQLiteDatabase.openDatabase(
                tempFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                SafeDatabaseErrorHandler()
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    fun getRecommendedIndexLevel(tableName: String): String {
        return try {
            databaseLock.lock()
            try {
                val recordCount =
                    _database?.rawQuery("SELECT COUNT(*) FROM $tableName", null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                    } ?: 0L

                when {
                    recordCount < 50_000 -> "NONE"
                    recordCount < 500_000 -> "BASIC"
                    else -> "FULL"
                }
            } finally {
                databaseLock.unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommended index level", e)
            "BASIC"
        }
    }

    suspend fun getPointsInBoundingBox(
        bounds: BoundingBox,
        tableName: String,
        columnMap: Map<String, String>,
        limit: Int = Int.MAX_VALUE
    ): List<Triple<Long, Double, Double>>? {
        return withContext(Dispatchers.IO) {
            try {
                val latColumn = columnMap["latitude"] ?: return@withContext null
                val lonColumn = columnMap["longitude"] ?: return@withContext null
                val macColumn = columnMap["mac"] ?: return@withContext null

                val query = getOptimalQuery(tableName, columnMap, bounds, limit)
                if (query.isEmpty()) return@withContext null

                _database?.rawQuery(
                    query, arrayOf(
                        bounds.latSouth.toString(),
                        bounds.latNorth.toString(),
                        bounds.lonWest.toString(),
                        bounds.lonEast.toString()
                    )
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            try {
                                val mac = when (cursor.getType(0)) {
                                    Cursor.FIELD_TYPE_STRING -> {
                                        val macStr = cursor.getString(0)
                                        MacAddressUtils.macToDecimal(macStr) ?: -1L
                                    }

                                    else -> cursor.getLong(0)
                                }
                                val lat = cursor.getDouble(1)
                                val lon = cursor.getDouble(2)

                                if (mac != -1L) {
                                    add(Triple(mac, lat, lon))
                                }
                            } catch (e: Exception) {
                                Log.e("SQLiteCustomHelper", "Error parsing point", e)
                                continue
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SQLiteCustomHelper", "Error getting points", e)
                null
            }
        }
    }

    suspend fun getPointsInBoundingBoxLegacy(
        bounds: BoundingBox,
        tableName: String,
        columnMap: Map<String, String>
    ): List<ClusteredMapPoint> {
        return withContext(Dispatchers.IO) {
            try {
                val latColumn = columnMap["latitude"] ?: return@withContext emptyList()
                val lonColumn = columnMap["longitude"] ?: return@withContext emptyList()
                val macColumn = columnMap["mac"] ?: return@withContext emptyList()

                val query =
                    "SELECT $macColumn, $latColumn, $lonColumn FROM $tableName WHERE $latColumn BETWEEN ? AND ? AND $lonColumn BETWEEN ? AND ?"

                _database?.rawQuery(
                    query, arrayOf(
                        bounds.latSouth.toString(),
                        bounds.latNorth.toString(),
                        bounds.lonWest.toString(),
                        bounds.lonEast.toString()
                    )
                )?.use { cursor ->
                    return@withContext buildList {
                        if (cursor.moveToFirst()) {
                            val macIdx = cursor.getColumnIndex(macColumn)
                            val latIdx = cursor.getColumnIndex(latColumn)
                            val lonIdx = cursor.getColumnIndex(lonColumn)

                            if (macIdx >= 0 && latIdx >= 0 && lonIdx >= 0) {
                                do {
                                    val macStr = cursor.getString(macIdx)
                                    val mac = MacAddressUtils.macToDecimal(macStr) ?: continue
                                    val lat = cursor.getDouble(latIdx)
                                    val lon = cursor.getDouble(lonIdx)

                                    add(ClusteredMapPoint(mac, lat, lon, 1, false))
                                } while (cursor.moveToNext())
                            }
                        }
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                Log.e("SQLiteCustomHelper", "Error getting points legacy", e)
                emptyList()
            }
        }
    }

    fun getCustomIndexLevel(): String {
        return try {
            val hasEssidIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND sql LIKE '%essid%'",
                null
            )?.use { it.count > 0 } ?: false

            val hasPasswordIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND (sql LIKE '%password%' OR sql LIKE '%wifi_pass%')",
                null
            )?.use { it.count > 0 } ?: false

            val hasWpsIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND sql LIKE '%wps%'",
                null
            )?.use { it.count > 0 } ?: false

            when {
                hasEssidIndex && hasPasswordIndex && hasWpsIndex -> "FULL"
                hasEssidIndex -> "BASIC"
                else -> "NONE"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining custom index level", e)
            "NONE"
        }
    }

    fun getOptimalQuery(
        tableName: String,
        columnMap: Map<String, String>,
        bounds: BoundingBox,
        limit: Int = Int.MAX_VALUE
    ): String {
        val latColumn = columnMap["latitude"] ?: return ""
        val lonColumn = columnMap["longitude"] ?: return ""
        val macColumn = columnMap["mac"] ?: return ""

        val indexLevel = getCustomIndexLevel()

        return when (indexLevel) {
            "FULL", "BASIC" -> {
                val hasCoordsIndex = _database?.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql LIKE '%$latColumn%'",
                    arrayOf(tableName)
                )?.use { it.count > 0 } ?: false

                if (hasCoordsIndex) {
                    val limitClause = if (limit != Int.MAX_VALUE) " LIMIT $limit" else ""
                    """
SELECT $macColumn, $latColumn, $lonColumn 
FROM $tableName 
WHERE $latColumn >= ? AND $latColumn <= ?
AND $lonColumn >= ? AND $lonColumn <= ?
ORDER BY $latColumn, $lonColumn$limitClause
"""
                } else {
                    val limitClause = if (limit != Int.MAX_VALUE) " LIMIT $limit" else ""
                    """
SELECT $macColumn, $latColumn, $lonColumn 
FROM $tableName 
WHERE $latColumn >= ? AND $latColumn <= ?
AND $lonColumn >= ? AND $lonColumn <=?$limitClause
"""
                }
            }

            else -> {
                """
            SELECT $macColumn, $latColumn, $lonColumn 
            FROM $tableName 
            WHERE $latColumn >= ? AND $latColumn <= ?
            AND $lonColumn >= ? AND $lonColumn <= ?
            """
            }
        }
    }

    fun searchNetworksByBSSIDs(
        tableName: String,
        columnMap: Map<String, String>,
        bssids: List<String>
    ): Map<String, Map<String, Any?>> {
        val macColumn = columnMap["mac"] ?: return emptyMap()
        val searchStartTime = System.currentTimeMillis()
        Log.d(TAG, "Searching for ${bssids.size} BSSIDs in $tableName")

        val allMacFormats = mutableMapOf<String, List<String>>()
        bssids.forEach { bssid ->
            allMacFormats[bssid] = generateAllMacFormats(bssid)
        }

        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        allMacFormats.values.flatten().distinct().forEach { format ->
            conditions.add("UPPER($macColumn) = UPPER(?)")
            params.add(format)
            conditions.add("REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')")
            params.add(format)
        }

        if (conditions.isEmpty()) {
            Log.d(TAG, "No valid MAC formats generated, returning empty result")
            return emptyMap()
        }

        val query = "SELECT * FROM $tableName WHERE ${conditions.joinToString(" OR ")}"
        Log.d(TAG, "Executing search query with ${params.size} parameters")

        databaseLock.lock()
        try {
            val results = _database?.rawQuery(query, params.toTypedArray())?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val result = cursorToMap(cursor)
                        val dbMac = result[macColumn]?.toString()
                        if (dbMac != null) {
                            val matchingOriginalBssid =
                                findMatchingOriginalBssid(dbMac, allMacFormats)
                            if (matchingOriginalBssid != null) {
                                put(matchingOriginalBssid, result)
                            }
                        }
                    }
                }
            } ?: emptyMap()

            Log.d(
                TAG,
                "Found ${results.size} results in ${System.currentTimeMillis() - searchStartTime}ms"
            )
            return results
        } catch (e: Exception) {
            Log.e(TAG, "Error searching BSSIDs in $tableName", e)
            return emptyMap()
        } finally {
            databaseLock.unlock()
        }
    }

    fun searchNetworksByBSSIDsAll(
        tableName: String,
        columnMap: Map<String, String>,
        bssids: List<String>
    ): Map<String, List<Map<String, Any?>>> {
        val macColumn = columnMap["mac"] ?: return emptyMap()

        val allMacFormats = mutableMapOf<String, List<String>>()
        bssids.forEach { bssid ->
            allMacFormats[bssid] = generateAllMacFormats(bssid)
        }

        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        allMacFormats.values.flatten().distinct().forEach { format ->
            conditions.add("UPPER($macColumn) = UPPER(?)")
            params.add(format)
            conditions.add("REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')")
            params.add(format)
        }

        val query = "SELECT * FROM $tableName WHERE ${conditions.joinToString(" OR ")}"

        databaseLock.lock()
        return try {
            _database?.rawQuery(query, params.toTypedArray())?.use { cursor ->
                val resultMap = mutableMapOf<String, MutableList<Map<String, Any?>>>()
                while (cursor.moveToNext()) {
                    val result = cursorToMap(cursor)
                    val dbMac = result[macColumn]?.toString()
                    if (dbMac != null) {
                        val matchingOriginalBssid = findMatchingOriginalBssid(dbMac, allMacFormats)
                        if (matchingOriginalBssid != null) {
                            resultMap.getOrPut(matchingOriginalBssid) { mutableListOf() }
                                .add(result)
                        }
                    }
                }
                resultMap
            } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error searching all BSSIDs in $tableName", e)
            emptyMap()
        } finally {
            databaseLock.unlock()
        }
    }

    private fun findMatchingOriginalBssid(
        dbMac: String,
        allMacFormats: Map<String, List<String>>
    ): String? {
        return allMacFormats.entries.find { (_, formats) ->
            formats.any { format ->
                dbMac.equals(format, ignoreCase = true) ||
                        dbMac.replace("[^a-fA-F0-9]".toRegex(), "")
                            .equals(format.replace("[^a-fA-F0-9]".toRegex(), ""), ignoreCase = true)
            }
        }?.key
    }

    fun searchNetworksByBSSIDAndFields(
        tableName: String,
        columnMap: Map<String, String>,
        query: String,
        filters: Set<String>,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        Log.d(
            TAG, """
        Starting search with:
        Query: $query
        Filters: $filters
        SearchMode: $searchMode
        TableName: $tableName
        ColumnMap: $columnMap
    """.trimIndent()
        )

        val reverseColumnMap = columnMap.entries.associate { (k, v) -> v to k }
        Log.d(TAG, "Reverse column map: $reverseColumnMap")

        val allResults = mutableSetOf<Map<String, Any?>>()

        filters.forEach { columnName ->
            Log.d(TAG, "Processing filter: $columnName")
            val mappedColumn = columnMap[reverseColumnMap[columnName] ?: columnName]
            Log.d(TAG, "Mapped column name: $mappedColumn")

            mappedColumn?.let { dbColumn ->
                val fieldResults = when (reverseColumnMap[columnName]) {
                    "mac" -> searchByMacAllFormats(tableName, dbColumn, query, searchMode)
                    else -> searchByField(tableName, dbColumn, query, searchMode)
                }
                allResults.addAll(fieldResults)
            }
        }

        val results = allResults.distinctBy { "${it[columnMap["mac"]]}-${it[columnMap["essid"]]}" }
        Log.d(TAG, "Total unique results found: ${results.size}")
        return results
    }

    @Volatile
    private var searchCancelled = false

    fun cancelSearch() {
        searchCancelled = true
    }

    fun searchNetworksByBSSIDAndFieldsPaginated(
        tableName: String,
        columnMap: Map<String, String>,
        query: String,
        filters: Set<String>,
        searchMode: SearchMode,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        searchCancelled = false
        val reverseColumnMap = columnMap.entries.associate { (k, v) -> v to k }
        val branches = mutableListOf<Triple<String, MutableList<String>, Boolean>>()
        val indexedCols = indexedColumns(tableName)

        filters.forEach { columnName ->
            val mappedColumn = columnMap[reverseColumnMap[columnName] ?: columnName]
            mappedColumn?.let { dbColumn ->
                when (reverseColumnMap[columnName]) {
                    "mac" -> {
                        if (searchMode == SearchMode.EXACT) {
                            val macFormats = generateAllMacFormats(query)
                            macFormats.forEach { format ->
                                branches.add(
                                    Triple(
                                        "UPPER($dbColumn) = UPPER(?)",
                                        mutableListOf(format),
                                        false
                                    )
                                )
                                branches.add(
                                    Triple(
                                        "REPLACE(REPLACE(UPPER($dbColumn), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')",
                                        mutableListOf(format),
                                        false
                                    )
                                )
                            }
                        } else {
                            val cleanQuery = query.replace("[^a-fA-F0-9:]".toRegex(), "")
                            if (cleanQuery.isNotEmpty()) {
                                val pattern = "%${cleanQuery.uppercase()}%"
                                branches.add(
                                    Triple(
                                        "UPPER($dbColumn) LIKE ? OR REPLACE(REPLACE(UPPER($dbColumn), ':', ''), '-', '') LIKE ?",
                                        mutableListOf(pattern, pattern),
                                        true
                                    )
                                )
                            }
                        }
                    }

                    else -> {
                        when (searchMode) {
                            SearchMode.EXACT -> {
                                val (cond, params) = caseInsensitiveEquals(dbColumn, query)
                                branches.add(Triple(cond, params.toMutableList(), false))
                            }

                            SearchMode.PREFIX -> {
                                if (dbColumn in indexedCols) {
                                    caseInsensitivePrefix(
                                        dbColumn,
                                        query
                                    ).forEach { (cond, params) ->
                                        branches.add(Triple(cond, params.toMutableList(), false))
                                    }
                                } else {
                                    branches.add(
                                        Triple(
                                            "UPPER($dbColumn) LIKE UPPER(?)",
                                            mutableListOf("$query%"),
                                            true
                                        )
                                    )
                                }
                            }

                            SearchMode.SUBSTRING -> {
                                val words = query.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                                if (words.size > 1) {
                                    branches.add(
                                        Triple(
                                            words.joinToString(" AND ") { "UPPER($dbColumn) LIKE UPPER(?)" },
                                            words.map { "%$it%" }.toMutableList(),
                                            true
                                        )
                                    )
                                } else {
                                    branches.add(
                                        Triple(
                                            "UPPER($dbColumn) LIKE UPPER(?)",
                                            mutableListOf("%${query}%"),
                                            true
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (branches.isEmpty()) return emptyList()

        val quotedTable = quoteIdentifier(tableName)
        val hasSlowBranch = branches.any { it.third }

        if (branches.size == 1 && !hasSlowBranch) {
            val (cond, params, _) = branches[0]
            val sql =
                "SELECT * FROM $quotedTable WHERE $cond ORDER BY rowid LIMIT $limit OFFSET $offset"
            return executeSearchQuery(sql, params.toTypedArray())
        }

        if (hasSlowBranch) {
            val conds = branches.map { "(${it.first})" }.toMutableList()
            val params = branches.flatMap { it.second }.toMutableList()
            return runChunkedSearch(tableName, conds, params, offset, limit)
        }

        val unionSql = branches.joinToString(" UNION ALL ") { (cond, _, _) ->
            "SELECT rowid, * FROM $quotedTable WHERE $cond"
        }
        val sql = "SELECT * FROM ($unionSql ORDER BY rowid LIMIT $limit OFFSET $offset)"
        return executeSearchQuery(sql, branches.flatMap { it.second }.toTypedArray())
    }

    private val columnIndexCache = ConcurrentHashMap<String, Set<String>>()

    private fun indexedColumns(tableName: String): Set<String> {
        columnIndexCache[tableName]?.let { return it }
        val cols = mutableSetOf<String>()
        try {
            val indexNames = mutableListOf<String>()
            _database?.rawQuery("PRAGMA index_list($tableName)", null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    indexNames.add(cursor.getString(1))
                }
            }
            indexNames.forEach { indexName ->
                _database?.rawQuery("PRAGMA index_info($indexName)", null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val colName = cursor.getString(2)
                        if (colName != null) cols.add(colName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading indexes for $tableName", e)
        }
        columnIndexCache[tableName] = cols
        return cols
    }

    private fun caseVariantParts(
        query: String,
        maxVariants: Int = MAX_PREFIX_VARIANTS
    ): Pair<String, String?> {
        var count = 1
        val prefix = StringBuilder()
        for (ch in query) {
            if (ch.isLetter()) {
                if (count * 2 <= maxVariants) {
                    prefix.append(ch)
                    count *= 2
                } else {
                    return prefix.toString() to query.substring(prefix.length)
                }
            } else {
                prefix.append(ch)
            }
        }
        return prefix.toString() to null
    }

    private fun enumerateCaseVariants(prefix: String): List<String> {
        if (prefix.isEmpty()) return listOf("")
        val alts = prefix.map { ch ->
            if (ch.isLetter()) listOf(ch.lowercaseChar(), ch.uppercaseChar()) else listOf(ch)
        }
        return alts.fold(listOf("")) { acc, options ->
            acc.flatMap { p -> options.map { p + it } }
        }
    }

    private fun caseInsensitivePrefix(
        column: String,
        query: String
    ): List<Pair<String, List<String>>> {
        val (prefix, tail) = caseVariantParts(query)
        return enumerateCaseVariants(prefix).map { variant ->
            val cond = "$column >= ? AND $column < (? || char(0))"
            if (tail == null) {
                cond to listOf(variant, variant)
            } else {
                cond + " AND UPPER(substr($column, ${prefix.length + 1})) LIKE UPPER(?)" to
                        listOf(variant, variant, "$tail%")
            }
        }
    }

    private fun caseInsensitiveEquals(column: String, query: String): Pair<String, List<String>> {
        val (prefix, tail) = caseVariantParts(query)
        val variants = enumerateCaseVariants(prefix)
        val params = variants.toMutableList()
        val cond = "(" + variants.joinToString(" OR ") { "$column = ?" } + ")"
        if (tail != null) {
            params.add(tail)
            return cond + " AND UPPER(substr($column, ${prefix.length + 1})) = UPPER(?)" to params
        }
        return cond to params
    }

    private fun getRowIdRange(tableName: String): Pair<Long, Long>? {
        return try {
            _database?.rawQuery(
                "SELECT MIN(rowid), MAX(rowid) FROM ${quoteIdentifier(tableName)}",
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                    cursor.getLong(0) to cursor.getLong(1)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read rowid range for $tableName", e)
            null
        }
    }

    private fun runChunkedSearch(
        tableName: String,
        conditions: List<String>,
        params: MutableList<String>,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        val totalNeeded = offset + limit
        val whereClause = conditions.joinToString(" OR ")
        val quotedTable = quoteIdentifier(tableName)

        val rowIdRange = getRowIdRange(tableName) ?: run {
            val fallbackSql =
                "SELECT * FROM $quotedTable WHERE $whereClause ORDER BY rowid LIMIT $limit OFFSET $offset"
            return executeSearchQuery(fallbackSql, params.toTypedArray())
        }

        val results = mutableListOf<Map<String, Any?>>()
        var chunkStart = rowIdRange.first
        val searchStart = System.currentTimeMillis()

        while (chunkStart <= rowIdRange.second && results.size < totalNeeded) {
            if (searchCancelled) {
                Log.d(TAG, "Search cancelled, aborting chunked scan at rowid=$chunkStart")
                return emptyList()
            }

            val chunkEnd = chunkStart + SEARCH_CHUNK_ROWS
            val remaining = totalNeeded - results.size
            val chunkParams = params.toMutableList().apply {
                add(chunkStart.toString())
                add(chunkEnd.toString())
                add(remaining.toString())
            }
            val chunkSql =
                "SELECT * FROM $quotedTable WHERE ($whereClause) AND rowid >= ? AND rowid < ? ORDER BY rowid LIMIT ?"

            results.addAll(executeSearchQuery(chunkSql, chunkParams.toTypedArray()))
            chunkStart = chunkEnd
        }

        Log.d(
            TAG,
            "Chunked search collected ${results.size} results in ${System.currentTimeMillis() - searchStart}ms"
        )
        return results.drop(offset).take(limit)
    }

    private fun searchByMacAllFormats(
        tableName: String,
        columnName: String,
        query: String,
        searchMode: SearchMode = SearchMode.EXACT
    ): List<Map<String, Any?>> {
        Log.d(
            TAG,
            "Processing MAC search with all formats. Original: $query, searchMode: $searchMode"
        )

        if (searchMode != SearchMode.EXACT) {
            val cleanQuery = query.replace("[^a-fA-F0-9:]".toRegex(), "")
            if (cleanQuery.isEmpty()) return emptyList()
            val sql =
                "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) LIKE ? OR REPLACE(REPLACE(UPPER($columnName), ':', ''), '-', '') LIKE ?"
            val searchPattern = "%${cleanQuery.uppercase()}%"

            Log.d(TAG, "MAC partial search - SQL: $sql")
            Log.d(TAG, "MAC partial search - Pattern: $searchPattern")

            return executeSearchQuery(sql, arrayOf(searchPattern, searchPattern))
        }

        val macFormats = generateAllMacFormats(query)
        Log.d(TAG, "Generated MAC formats: $macFormats")

        val allConditions = mutableListOf<String>()
        val allParams = mutableListOf<String>()

        macFormats.forEach { format ->
            allConditions.add("UPPER($columnName) = UPPER(?)")
            allParams.add(format)

            allConditions.add("REPLACE(REPLACE(UPPER($columnName), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')")
            allParams.add(format)
        }

        val sql = "SELECT DISTINCT * FROM $tableName WHERE ${allConditions.joinToString(" OR ")}"

        Log.d(TAG, "MAC exact search - SQL: $sql")
        Log.d(TAG, "MAC exact search - Params count: ${allParams.size}")

        return executeSearchQuery(sql, allParams.toTypedArray())
    }

    private fun generateAllMacFormats(input: String): List<String> {
        val cleanInput = input.replace("[^a-fA-F0-9]".toRegex(), "").uppercase()
        val formats = mutableSetOf<String>()

        formats.add(input.trim())

        if (cleanInput.isNotEmpty()) {
            formats.add(cleanInput)

            if (cleanInput.length == 12) {
                formats.add(cleanInput.lowercase())
                formats.add(cleanInput.replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(cleanInput.replace("(.{2})".toRegex(), "$1-").dropLast(1))
                formats.add(cleanInput.lowercase().replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(cleanInput.lowercase().replace("(.{2})".toRegex(), "$1-").dropLast(1))

                try {
                    val decimal = cleanInput.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert $cleanInput to decimal")
                }
            }
        }

        if (input.matches("[0-9]+".toRegex())) {
            try {
                val decimal = input.toLong()
                val hex = String.format("%012X", decimal)
                formats.add(hex)
                formats.add(hex.lowercase())
                formats.add(hex.replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(hex.replace("(.{2})".toRegex(), "$1-").dropLast(1))
                formats.add(hex.lowercase().replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(hex.lowercase().replace("(.{2})".toRegex(), "$1-").dropLast(1))
            } catch (e: NumberFormatException) {
                Log.d(TAG, "Could not convert decimal $input to hex")
            }
        }

        return formats.filter { it.isNotEmpty() }.distinct()
    }

    private fun searchByField(
        tableName: String,
        columnName: String,
        query: String,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val (sql, param) = when (searchMode) {
            SearchMode.EXACT -> "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) = UPPER(?)" to query
            SearchMode.PREFIX -> "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) LIKE UPPER(?)" to "${query}%"
            SearchMode.SUBSTRING -> "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) LIKE UPPER(?)" to "%${query}%"
        }

        Log.d(TAG, "Field search - SQL: $sql")
        Log.d(TAG, "Field search - Param: $param")

        return executeSearchQuery(sql, arrayOf(param))
    }

    private fun quoteIdentifier(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    private fun executeSearchQuery(sql: String, params: Array<String>): List<Map<String, Any?>> {
        return try {
            databaseLock.lock()
            try {
                _database?.rawQuery(sql, params)?.use { cursor ->
                    Log.d(TAG, "Cursor obtained with ${cursor.count} rows")
                    buildList {
                        while (cursor.moveToNext()) {
                            val result = cursorToMap(cursor)
                            add(result)
                        }
                    }
                } ?: emptyList()
            } finally {
                databaseLock.unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing query: $sql", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "SQLiteCustomHelper"
        private const val SEARCH_CHUNK_ROWS = 1_000_000L
        private const val MAX_PREFIX_VARIANTS = 256
    }

    private object MacAddressUtils {
        fun normalizeMAC(mac: String): String? {
            return when {
                mac.matches("\\d+".toRegex()) -> {
                    try {
                        String.format("%012X", mac.toLong())
                            .replace("(.{2})".toRegex(), "$1:").dropLast(1)
                            .lowercase()
                    } catch (_: NumberFormatException) {
                        null
                    }
                }

                mac.matches("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})".toRegex()) -> {
                    mac.replace("-", ":").lowercase()
                }

                mac.matches("[0-9A-Fa-f]{12}".toRegex()) -> {
                    mac.replace("(.{2})".toRegex(), "$1:").dropLast(1).lowercase()
                }

                else -> null
            }
        }

        fun macToDecimal(mac: String): Long? {
            return try {
                val normalizedMac = normalizeMAC(mac) ?: return null
                normalizedMac.replace(":", "").toLong(16)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    private fun openDatabaseFromDirectPath(): SQLiteDatabase {
        return try {
            SQLiteDatabase.openDatabase(
                directPath!!,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                SafeDatabaseErrorHandler()
            )
        } catch (e: Exception) {
            Log.w(
                "SQLiteCustomHelper",
                "Failed to open database using direct path: $directPath. Falling back to URI method.",
                e
            )
            openDatabaseFromUri()
        }
    }

    private fun openDatabaseFromUri(): SQLiteDatabase {
        val tempFile = copyUriToTempFileWithRetry(dbUri)
        return SQLiteDatabase.openDatabase(
            tempFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            SafeDatabaseErrorHandler()
        )
    }

    private fun copyUriToTempFileWithRetry(uri: Uri, maxRetries: Int = 3): File {
        var lastException: Exception? = null
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val effectiveRetries = if (isMainThread) 1 else maxRetries

        if (isMainThread) {
            Log.w(
                "SQLiteCustomHelper",
                "copyUriToTempFileWithRetry called on main thread — suppressing retries to avoid blocking UI"
            )
        }

        repeat(effectiveRetries) { attempt ->
            try {
                val fileName = getFileNameFromUri(uri)
                val tempFile =
                    CompatibilityHelper.createTempFileWithFallback(context, fileName, ".sqlite")
                        ?: throw IllegalStateException("Cannot create temp file")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer =
                            ByteArray(if (CompatibilityHelper.isLowMemoryDevice()) 4096 else 8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                if (CompatibilityHelper.isFileAccessible(tempFile)) {
                    return tempFile
                } else {
                    tempFile.delete()
                    throw IllegalStateException("Copied file is not accessible")
                }
            } catch (e: Exception) {
                lastException = e
                Log.w("SQLiteCustomHelper", "Attempt ${attempt + 1} failed", e)
                if (!isMainThread && attempt < effectiveRetries - 1) {
                    Thread.sleep(1000)
                }
            }
        }

        throw IllegalArgumentException(
            "Failed to copy URI to temp file after $effectiveRetries attempts",
            lastException
        )
    }

    private fun copyUriToTempFile(uri: Uri): File {
        return copyUriToTempFileWithRetry(uri)
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
        return if (name.endsWith(".sqlite", ignoreCase = true)) name else "$name.sqlite"
    }

    override fun onCreate(db: SQLiteDatabase?) {
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    }

    fun getTableNames(): List<String> {
        return _database?.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            ?.use { cursor ->
                val tableNames = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    tableNames.add(cursor.getString(0))
                }
                tableNames
            } ?: emptyList()
    }

    @SuppressLint("Range")
    fun getColumnNames(tableName: String): List<String> {
        return _database?.rawQuery("PRAGMA table_info($tableName)", null)?.use { cursor ->
            val columnNames = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columnNames.add(cursor.getString(cursor.getColumnIndex("name")))
            }
            columnNames
        } ?: emptyList()
    }

    fun getSampleValues(tableName: String, column: String, limit: Int = 25): List<String> {
        val quotedTable = quoteIdentifier(tableName)
        val quotedColumn = quoteIdentifier(column)
        val sql = "SELECT $quotedColumn FROM $quotedTable LIMIT $limit"
        databaseLock.lock()
        return try {
            _database?.rawQuery(sql, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            when (cursor.getType(0)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(0).toString()
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(0).toString()
                                Cursor.FIELD_TYPE_NULL -> ""
                                else -> cursor.getString(0) ?: ""
                            }
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error sampling column $column from $tableName", e)
            emptyList()
        } finally {
            databaseLock.unlock()
        }
    }

    private val tableSizeCache = mutableMapOf<String, Long>()

    fun getColumnFillRatio(tableName: String, column: String): Double {
        val quotedTable = quoteIdentifier(tableName)
        val quotedColumn = quoteIdentifier(column)
        databaseLock.lock()
        return try {
            val total = tableSizeCache.getOrPut(tableName) {
                _database?.rawQuery("SELECT COUNT(*) FROM $quotedTable", null)?.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                } ?: 0L
            }
            if (total == 0L) return 0.0
            val filled = _database?.rawQuery(
                "SELECT COUNT(*) FROM $quotedTable WHERE $quotedColumn IS NOT NULL AND TRIM(CAST($quotedColumn AS TEXT)) != ''",
                null
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
            filled.toDouble() / total
        } catch (e: Exception) {
            Log.e(TAG, "Error computing fill ratio for $column in $tableName", e)
            0.0
        } finally {
            databaseLock.unlock()
        }
    }

    suspend fun searchNetworksByESSIDsAsync(
        tableName: String,
        columnMap: Map<String, String>,
        essids: List<String>
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val validEssids = essids.filter { it.isNotBlank() }
            if (validEssids.isEmpty()) return@withContext emptyList()

            val essidColumn = columnMap["essid"] ?: return@withContext emptyList()
            val chunkedEssids = validEssids.chunked(500)

            val indexLevel = getCustomIndexLevel()
            val hasEssidIndex = runCatching {
                databaseLock.lock()
                try {
                    _database?.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql LIKE '%$essidColumn%'",
                        arrayOf(tableName)
                    )?.use { cursor ->
                        cursor.count > 0
                    } ?: false
                } finally {
                    databaseLock.unlock()
                }
            }.getOrNull() ?: false

            Log.d(
                TAG,
                "ESSID search - Has index on $essidColumn: $hasEssidIndex, Index level: $indexLevel"
            )

            databaseLock.lock()
            try {
                chunkedEssids.flatMap { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    val query = if (hasEssidIndex && indexLevel != "NONE") {
                        "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders)"
                    } else {
                        "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders)"
                    }

                    _database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(cursorToMap(cursor))
                            }
                        }
                    } ?: emptyList()
                }
            } finally {
                databaseLock.unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching networks by ESSID", e)
            emptyList()
        }
    }

    private fun cursorToMap(cursor: Cursor): Map<String, Any?> {
        return buildMap {
            for (i in 0 until cursor.columnCount) {
                val columnName = cursor.getColumnName(i)
                val value = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                    else -> cursor.getString(i)
                }
                put(columnName, value)
            }
        }
    }

    fun searchNetworksByAdvancedQuery(
        tableName: String,
        columnMap: Map<String, String>,
        advancedQuery: AdvancedSearchQuery,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        val macColumn = columnMap["mac"] ?: "bssid"
        val essidColumn = columnMap["essid"] ?: "essid"
        val passwordColumn = columnMap["wifi_pass"] ?: "wifi_pass"
        val wpsPinColumn = columnMap["wps_pin"] ?: "wps_pin"

        if (advancedQuery.bssid.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.bssid)) {
                val processedBssid = advancedQuery.convertWildcards(advancedQuery.bssid)
                conditions.add("$macColumn LIKE ?")
                params.add(processedBssid)
            } else {
                val macFormats = generateAllMacFormats(advancedQuery.bssid)
                val macConditions = mutableListOf<String>()
                macFormats.forEach { format ->
                    macConditions.add("UPPER($macColumn) = UPPER(?)")
                    params.add(format)
                }
                if (macConditions.isNotEmpty()) {
                    conditions.add("(${macConditions.joinToString(" OR ")})")
                }
            }
        }

        if (advancedQuery.essid.isNotBlank()) {
            val processedEssid = if (advancedQuery.containsWildcards(advancedQuery.essid)) {
                advancedQuery.convertWildcards(advancedQuery.essid)
            } else {
                "%${advancedQuery.essid}%"
            }

            if (advancedQuery.caseSensitive) {
                conditions.add("$essidColumn LIKE ? COLLATE BINARY")
            } else {
                conditions.add("UPPER($essidColumn) LIKE UPPER(?)")
            }
            params.add(processedEssid)
        }

        if (advancedQuery.password.isNotBlank()) {
            val processedPassword = if (advancedQuery.containsWildcards(advancedQuery.password)) {
                advancedQuery.convertWildcards(advancedQuery.password)
            } else {
                "%${advancedQuery.password}%"
            }

            if (advancedQuery.caseSensitive) {
                conditions.add("$passwordColumn LIKE ? COLLATE BINARY")
            } else {
                conditions.add("UPPER($passwordColumn) LIKE UPPER(?)")
            }
            params.add(processedPassword)
        }

        if (advancedQuery.wpsPin.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.wpsPin)) {
                val processedWpsPin = advancedQuery.convertWildcards(advancedQuery.wpsPin)
                conditions.add("$wpsPinColumn LIKE ?")
                params.add(processedWpsPin)
            } else {
                conditions.add("$wpsPinColumn = ?")
                params.add(advancedQuery.wpsPin)
            }
        }

        if (conditions.isEmpty()) return emptyList()

        val sql =
            "SELECT * FROM ${quoteIdentifier(tableName)} WHERE ${conditions.joinToString(" AND ")} ORDER BY rowid LIMIT $limit OFFSET $offset"

        return executeSearchQuery(sql, params.toTypedArray())
    }

    fun getCachedDbPath(): String? {
        return _database?.path
    }

    fun clearCache() {
        resultsCache.clear()
    }

    override fun close() {
        clearCache()
        _database?.close()
        _database = null
        super.close()
    }
}