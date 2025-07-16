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
        Log.d("SQLiteCustomHelper", "Searching for ${bssids.size} BSSIDs in $tableName")

        val searchFormats = bssids.flatMap { bssid ->
            val cleanMac = bssid.replace("[^a-fA-F0-9]".toRegex(), "")
            listOf(
                bssid,
                bssid.uppercase(),
                cleanMac,
                cleanMac.uppercase()
            ).also { formats ->
                try {
                    formats.plus(cleanMac.toLong(16).toString())
                } catch (_: NumberFormatException) {}
            }
        }.distinct()

        val conditions = searchFormats.joinToString(" OR ") {
            """
        $macColumn = ? OR
        UPPER($macColumn) = UPPER(?) OR
        REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = ?
        """
        }

        val query = "SELECT * FROM $tableName WHERE $conditions"
        val params = searchFormats.flatMap { format ->
            listOf(
                format,
                format,
                format.replace("[^a-fA-F0-9]".toRegex(), "").uppercase()
            )
        }.toTypedArray()

        Log.d("SQLiteCustomHelper", "Executing search query with ${params.size} parameters")

        return _database?.rawQuery(query, params)?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val result = cursorToMap(cursor, columnMap)
                    val bssid = result["mac"]?.toString()
                    if (bssid != null) {
                        put(bssid, result)
                    }
                }
            }
        }?.also { results ->
            Log.d("SQLiteCustomHelper", "Found ${results.size} results in ${System.currentTimeMillis() - searchStartTime}ms")
        } ?: emptyMap()
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

        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        filters.forEach { columnName ->
            Log.d(TAG, "Processing filter: $columnName")
            val mappedColumn = columnMap[reverseColumnMap[columnName] ?: columnName]
            Log.d(TAG, "Mapped column name: $mappedColumn")

            mappedColumn?.let { dbColumn ->
                when (reverseColumnMap[columnName]) {
                    "mac" -> {
                        val cleanMac = query.replace("[^a-fA-F0-9]".toRegex(), "")
                        Log.d(TAG, "Processing MAC search. Original: $query, Cleaned: $cleanMac")

                        conditions.add("""
                    ($dbColumn = ? OR
                    UPPER($dbColumn) = UPPER(?) OR
                    REPLACE(REPLACE(UPPER($dbColumn), ':', ''), '-', '') = ?)
                """.trimIndent())

                        params.add(query)
                        params.add(query)
                        params.add(cleanMac.uppercase())

                        Log.d(TAG, "Added MAC condition with params: $query, $query, ${cleanMac.uppercase()}")
                    }
                    else -> {
                        conditions.add(if (wholeWords) {
                            "(UPPER($dbColumn) = UPPER(?))"
                        } else {
                            "(UPPER($dbColumn) LIKE UPPER(?))"
                        })
                        val param = if (wholeWords) query else "%$query%"
                        params.add(param)
                        Log.d(TAG, "Added regular condition for column $dbColumn with param: $param")
                    }
                }
            }
        }

        if (conditions.isEmpty()) {
            Log.d(TAG, "No conditions generated, returning empty list")
            return emptyList()
        }

        val sqlQuery = "SELECT DISTINCT * FROM $tableName WHERE ${conditions.joinToString(" OR ")}"
        Log.d(TAG, "Final SQL Query: $sqlQuery")
        Log.d(TAG, "Final params: ${params.joinToString()}")

        return try {
            _database?.rawQuery(sqlQuery, params.toTypedArray())?.use { cursor ->
                Log.d(TAG, "Cursor obtained with ${cursor.count} rows")
                buildList {
                    while (cursor.moveToNext()) {
                        val result = cursorToMap(cursor, columnMap)
                        Log.d(TAG, "Row found: $result")
                        add(result)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing query", e)
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

            val hasEssidIndex = _database?.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql LIKE '%$essidColumn%'",
                arrayOf(tableName)
            )?.use { cursor ->
                cursor.count > 0
            } ?: false

            Log.d("SQLiteCustomHelper", "ESSID search - Has index on $essidColumn: $hasEssidIndex")

            chunkedEssids.flatMap { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val query = if (hasEssidIndex) {
                    "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders)"
                } else {
                    "SELECT * FROM $tableName WHERE $essidColumn IN ($placeholders)"
                }

                _database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(cursorToMap(cursor, columnMap))
                        }
                    }
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("SQLiteCustomHelper", "Error searching networks by ESSID", e)
            emptyList()
        }
    }

    private fun cursorToMap(cursor: Cursor, columnMap: Map<String, String>): Map<String, Any?> {
        return buildMap {
            columnMap.forEach { (key, columnName) ->
                val columnIndex = cursor.getColumnIndex(columnName)
                if (columnIndex != -1) {
                    put(key, when (cursor.getType(columnIndex)) {
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(columnIndex)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(columnIndex)
                        else -> cursor.getString(columnIndex)
                    })
                }
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