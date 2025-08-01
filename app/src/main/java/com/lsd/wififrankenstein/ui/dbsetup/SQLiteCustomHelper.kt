package com.lsd.wififrankenstein.ui.dbsetup

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class SQLiteCustomHelper(
    private val context: Context,
    private val dbUri: Uri,
    private val directPath: String?
) : SQLiteOpenHelper(context, null, null, 1) {

    val database: SQLiteDatabase? get() = _database
    private var _database: SQLiteDatabase? = null

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
                openDatabaseFromUri()
            }
        } catch (e: Exception) {
            Log.e("SQLiteCustomHelper", "Unable to open database", e)
            throw IllegalArgumentException("Unable to open database: ${e.message}")
        }
    }

    fun getRecommendedIndexLevel(tableName: String): String {
        return try {
            val recordCount = _database?.rawQuery("SELECT COUNT(*) FROM $tableName", null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L

            when {
                recordCount < 50_000 -> "NONE"
                recordCount < 500_000 -> "BASIC"
                else -> "FULL"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommended index level", e)
            "BASIC"
        }
    }

    suspend fun getPointsInBoundingBox(
        bounds: BoundingBox,
        tableName: String,
        columnMap: Map<String, String>
    ): List<Triple<Long, Double, Double>>? {
        return withContext(Dispatchers.IO) {
            try {
                val latColumn = columnMap["latitude"] ?: return@withContext null
                val lonColumn = columnMap["longitude"] ?: return@withContext null
                val macColumn = columnMap["mac"] ?: return@withContext null

                val query = getOptimalQuery(tableName, columnMap, bounds)
                if (query.isEmpty()) return@withContext null

                _database?.rawQuery(query, arrayOf(
                    bounds.latSouth.toString(),
                    bounds.latNorth.toString(),
                    bounds.lonWest.toString(),
                    bounds.lonEast.toString()
                ))?.use { cursor ->
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

    fun getCustomIndexLevel(): String {
        return try {
            val hasEssidIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND sql LIKE '%essid%'",
                null
            )?.use { it.count > 0 } ?: false

            val hasPasswordIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND sql LIKE '%password%' OR sql LIKE '%wifi_pass%'",
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
        bounds: BoundingBox
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
                    """
                SELECT $macColumn, $latColumn, $lonColumn 
                FROM $tableName 
                WHERE $latColumn >= ? AND $latColumn <= ?
                AND $lonColumn >= ? AND $lonColumn <= ?
                ORDER BY $latColumn, $lonColumn
                """
                } else {
                    """
                SELECT $macColumn, $latColumn, $lonColumn 
                FROM $tableName 
                WHERE $latColumn >= ? AND $latColumn <= ?
                AND $lonColumn >= ? AND $lonColumn <= ?
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

        val query = "SELECT * FROM $tableName WHERE ${conditions.joinToString(" OR ")}"
        Log.d(TAG, "Executing search query with ${params.size} parameters")

        val results = _database?.rawQuery(query, params.toTypedArray())?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val result = cursorToMap(cursor)
                    val dbMac = result[macColumn]?.toString()
                    if (dbMac != null) {
                        val matchingOriginalBssid = findMatchingOriginalBssid(dbMac, allMacFormats)
                        if (matchingOriginalBssid != null) {
                            put(matchingOriginalBssid, result)
                        }
                    }
                }
            }
        } ?: emptyMap()

        Log.d(TAG, "Found ${results.size} results in ${System.currentTimeMillis() - searchStartTime}ms")
        return results
    }

    private fun findMatchingOriginalBssid(dbMac: String, allMacFormats: Map<String, List<String>>): String? {
        return allMacFormats.entries.find { (_, formats) ->
            formats.any { format ->
                dbMac.equals(format, ignoreCase = true) ||
                        dbMac.replace("[^a-fA-F0-9]".toRegex(), "").equals(format.replace("[^a-fA-F0-9]".toRegex(), ""), ignoreCase = true)
            }
        }?.key
    }

    fun searchNetworksByBSSIDAndFields(
        tableName: String,
        columnMap: Map<String, String>,
        query: String,
        filters: Set<String>,
        wholeWords: Boolean
    ): List<Map<String, Any?>> {
        Log.d(TAG, """
        Starting search with:
        Query: $query
        Filters: $filters
        WholeWords: $wholeWords
        TableName: $tableName
        ColumnMap: $columnMap
    """.trimIndent())

        val reverseColumnMap = columnMap.entries.associate { (k, v) -> v to k }
        Log.d(TAG, "Reverse column map: $reverseColumnMap")

        val allResults = mutableSetOf<Map<String, Any?>>()

        filters.forEach { columnName ->
            Log.d(TAG, "Processing filter: $columnName")
            val mappedColumn = columnMap[reverseColumnMap[columnName] ?: columnName]
            Log.d(TAG, "Mapped column name: $mappedColumn")

            mappedColumn?.let { dbColumn ->
                val fieldResults = when (reverseColumnMap[columnName]) {
                    "mac" -> searchByMacAllFormats(tableName, dbColumn, query)
                    else -> searchByField(tableName, dbColumn, query, wholeWords)
                }
                allResults.addAll(fieldResults)
            }
        }

        val results = allResults.distinctBy { "${it[columnMap["mac"]]}-${it[columnMap["essid"]]}" }
        Log.d(TAG, "Total unique results found: ${results.size}")
        return results
    }

    fun searchNetworksByBSSIDAndFieldsPaginated(
        tableName: String,
        columnMap: Map<String, String>,
        query: String,
        filters: Set<String>,
        wholeWords: Boolean,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        val reverseColumnMap = columnMap.entries.associate { (k, v) -> v to k }
        val allConditions = mutableListOf<String>()
        val allParams = mutableListOf<String>()

        filters.forEach { columnName ->
            val mappedColumn = columnMap[reverseColumnMap[columnName] ?: columnName]
            mappedColumn?.let { dbColumn ->
                when (reverseColumnMap[columnName]) {
                    "mac" -> {
                        val macFormats = generateAllMacFormats(query)
                        macFormats.forEach { format ->
                            allConditions.add("UPPER($dbColumn) = UPPER(?)")
                            allParams.add(format)
                            allConditions.add("REPLACE(REPLACE(UPPER($dbColumn), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')")
                            allParams.add(format)
                        }
                        allConditions.add("$dbColumn LIKE ?")
                        allParams.add("%$query%")
                    }
                    else -> {
                        if (wholeWords) {
                            allConditions.add("UPPER($dbColumn) = UPPER(?)")
                            allParams.add(query)
                        } else {
                            allConditions.add("UPPER($dbColumn) LIKE UPPER(?)")
                            allParams.add("%$query%")
                        }
                    }
                }
            }
        }

        if (allConditions.isEmpty()) return emptyList()

        val sql = "SELECT DISTINCT * FROM $tableName WHERE ${allConditions.joinToString(" OR ")} LIMIT $limit OFFSET $offset"

        return executeSearchQuery(sql, allParams.toTypedArray())
    }

    private fun searchByMacAllFormats(tableName: String, columnName: String, query: String): List<Map<String, Any?>> {
        Log.d(TAG, "Processing MAC search with all formats. Original: $query")

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

        allConditions.add("$columnName LIKE ?")
        allParams.add("%$query%")

        val sql = "SELECT DISTINCT * FROM $tableName WHERE ${allConditions.joinToString(" OR ")}"

        Log.d(TAG, "MAC search - SQL: $sql")
        Log.d(TAG, "MAC search - Params count: ${allParams.size}")

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

    private fun searchByField(tableName: String, columnName: String, query: String, wholeWords: Boolean): List<Map<String, Any?>> {
        val sql = if (wholeWords) {
            "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) = UPPER(?)"
        } else {
            "SELECT DISTINCT * FROM $tableName WHERE UPPER($columnName) LIKE UPPER(?)"
        }

        val param = if (wholeWords) query else "%$query%"

        Log.d(TAG, "Field search - SQL: $sql")
        Log.d(TAG, "Field search - Param: $param")

        return executeSearchQuery(sql, arrayOf(param))
    }

    private fun executeSearchQuery(sql: String, params: Array<String>): List<Map<String, Any?>> {
        return try {
            _database?.rawQuery(sql, params)?.use { cursor ->
                Log.d(TAG, "Cursor obtained with ${cursor.count} rows")
                buildList {
                    while (cursor.moveToNext()) {
                        val result = cursorToMap(cursor)
                        add(result)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing query: $sql", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "SQLiteCustomHelper"
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
            SQLiteDatabase.openDatabase(directPath!!, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: Exception) {
            Log.w("SQLiteCustomHelper", "Failed to open database using direct path: $directPath. Falling back to URI method.", e)
            openDatabaseFromUri()
        }
    }

    private fun openDatabaseFromUri(): SQLiteDatabase {
        val tempFile = copyUriToTempFile(dbUri)
        return SQLiteDatabase.openDatabase(tempFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val fileName = getFileNameFromUri(uri)
        val tempFile = File(context.cacheDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
        return "$name.sqlite"
    }

    override fun onCreate(db: SQLiteDatabase?) {
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    }

    fun getTableNames(): List<String> {
        return _database?.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)?.use { cursor ->
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
            val hasEssidIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql LIKE '%$essidColumn%'",
                arrayOf(tableName)
            )?.use { cursor ->
                cursor.count > 0
            } ?: false

            Log.d(TAG, "ESSID search - Has index on $essidColumn: $hasEssidIndex, Index level: $indexLevel")

            chunkedEssids.flatMap { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val query = if (hasEssidIndex && indexLevel != "NONE") {
                    "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders) COLLATE NOCASE"
                } else {
                    "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders) COLLATE NOCASE"
                }

                _database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursorToMap(cursor))
                        }
                    }
                } ?: emptyList()
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

    fun clearCache() {
        resultsCache.clear()
    }

    override fun close() {
        clearCache()
        _database?.close()
        super.close()
    }
}