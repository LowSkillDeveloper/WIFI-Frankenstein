package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale

class SQLite3WiFiHelper(private val context: Context, private val dbUri: Uri, private val directPath: String?) : SQLiteOpenHelper(context, null, null, 1) {
    var database: SQLiteDatabase? = null
    private var selectedFileSize: Float = 0f
    private val databaseLock = Mutex()

    private val cacheDir = File(context.cacheDir, "CacheDB").apply { mkdirs() }

    init {
        try {
            database = if (!directPath.isNullOrBlank()) {
                openDatabaseFromDirectPath()
            } else {
                openDatabaseFromUri()
            }
        } catch (e: Exception) {
            Log.e("SQLite3WiFiHelper", "Unable to open database", e)
            throw IllegalArgumentException("Unable to open database: ${e.message}")
        }
    }

    private val resultsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, Any?>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Any?>?>): Boolean {
                return size > 1000 // размер временного кэша
            }
        }
    )

    private fun openDatabaseFromDirectPath(): SQLiteDatabase {
        return try {
            SQLiteDatabase.openDatabase(directPath!!, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: Exception) {
            Log.w("SQLite3WiFiHelper", "Failed to open database using direct path: $directPath. Falling back to URI method.", e)
            openDatabaseFromUri()
        }
    }

    private fun isOriginalFileChanged(uri: Uri, cachedFile: File): Boolean {
        val originalLastModified = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow("last_modified"))
        } ?: return true

        val cachedLastModified = getCachedLastModified(cachedFile)
        return originalLastModified != cachedLastModified
    }

    private fun openDatabaseFromUri(): SQLiteDatabase {
        val cachedFile = getCachedFile(dbUri)
        return if (cachedFile != null && !isOriginalFileChanged(dbUri, cachedFile)) {
            SQLiteDatabase.openDatabase(cachedFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } else {
            val tempFile = copyUriToTempFile(dbUri)
            SQLiteDatabase.openDatabase(tempFile.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        }
    }

    fun getSelectedFileSize(): Float {
        return selectedFileSize
    }

    private fun getCachedLastModified(cachedFile: File): Long {
        val metadataFile = File(cachedFile.parentFile, "${cachedFile.name}.metadata")
        return if (metadataFile.exists()) {
            metadataFile.readText().toLongOrNull() ?: 0
        } else {
            0
        }
    }

    suspend fun loadNetworkInfo(bssidDecimal: Long): Map<String, Any?>? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting loadNetworkInfo for decimal BSSID: $bssidDecimal")
        databaseLock.withLock {
            try {
                Log.d(TAG, "Acquired database lock for BSSID: $bssidDecimal")
                val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                Log.d(TAG, "Using table: $tableName")

                if (tableName == "unknown") {
                    Log.e(TAG, "Unknown database type")
                    return@withLock null
                }

                database?.rawQuery(
                    "SELECT * FROM $tableName WHERE BSSID = ?",
                    arrayOf(bssidDecimal.toString())
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        buildMap {
                            for (i in 0 until cursor.columnCount) {
                                val columnName = cursor.getColumnName(i)
                                put(columnName, when (cursor.getType(i)) {
                                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                    Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                    else -> cursor.getString(i)
                                })
                            }
                            Log.d(TAG, "Retrieved network info with $size fields")
                        }
                    } else {
                        Log.d(TAG, "No network info found for BSSID: $bssidDecimal")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading network info for BSSID: $bssidDecimal", e)
                null
            } finally {
                Log.d(TAG, "Releasing database lock for BSSID: $bssidDecimal")
            }
        }
    }

    private val TAG = "SQLite3WiFiHelper"

    suspend fun getPointsInBoundingBox(bounds: BoundingBox): List<Triple<Long, Double, Double>> {
        Log.d(TAG, "Starting getPointsInBoundingBox with bounds: $bounds")
        return withContext(Dispatchers.IO) {
            databaseLock.withLock {
                try {
                    Log.d(TAG, "Acquired database lock")

                    val indices = DatabaseIndices.getAvailableIndices(database!!)
                    Log.d(TAG, "Available indices: $indices")

                    val geoIndex = if (indices.contains(DatabaseIndices.GEO_COORDS_BSSID)) {
                        "INDEXED BY ${DatabaseIndices.GEO_COORDS_BSSID}"
                    } else if (indices.contains(DatabaseIndices.GEO_QUADKEY_FULL)) {
                        "INDEXED BY ${DatabaseIndices.GEO_QUADKEY_FULL}"
                    } else if (indices.contains(DatabaseIndices.GEO_BSSID)) {
                        "INDEXED BY ${DatabaseIndices.GEO_BSSID}"
                    } else {
                        ""
                    }

                    val query = """
                    SELECT BSSID, latitude, longitude 
                    FROM geo $geoIndex
                    WHERE latitude BETWEEN ? AND ?
                    AND longitude BETWEEN ? AND ?
                """.trimIndent()

                    Log.d(TAG, "Executing query with bounds: lat(${bounds.latSouth}-${bounds.latNorth}), lon(${bounds.lonWest}-${bounds.lonEast})")
                    Log.d(TAG, "Using index: $geoIndex")

                    database?.rawQuery(query, arrayOf(
                        bounds.latSouth.toString(),
                        bounds.latNorth.toString(),
                        bounds.lonWest.toString(),
                        bounds.lonEast.toString()
                    ))?.use { cursor ->
                        buildList {
                            if (cursor.moveToFirst()) {
                                do {
                                    val bssid = cursor.getLong(0)
                                    val lat = cursor.getDouble(1)
                                    val lon = cursor.getDouble(2)
                                    add(Triple(bssid, lat, lon))
                                } while (cursor.moveToNext())
                            }
                            Log.d(TAG, "Retrieved $size points from database")
                        }
                    } ?: run {
                        Log.w(TAG, "Database or cursor is null")
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting points in bounding box", e)
                    emptyList()
                } finally {
                    Log.d(TAG, "Releasing database lock")
                }
            }
        }
    }

    private fun saveCachedLastModified(cachedFile: File, lastModified: Long) {
        val metadataFile = File(cachedFile.parentFile, "${cachedFile.name}.metadata")
        metadataFile.writeText(lastModified.toString())
    }

    private fun getCachedFile(uri: Uri): File? {
        val fileName = getFileNameFromUri(uri)
        val cachedFile = File(cacheDir, fileName)
        return if (cachedFile.exists()) cachedFile else null
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val fileName = getFileNameFromUri(uri)
        val tempFile = File(cacheDir, fileName)

        if (tempFile.exists()) {
            tempFile.delete()
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        val lastModified = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(cursor.getColumnIndexOrThrow("last_modified"))
        } ?: System.currentTimeMillis()
        saveCachedLastModified(tempFile, lastModified)

        selectedFileSize = tempFile.length().toFloat() / (1024 * 1024)

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
        return database?.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)?.use { cursor ->
            val tableNames = mutableListOf<String>()
            while (cursor.moveToNext()) {
                tableNames.add(cursor.getString(0))
            }
            tableNames
        } ?: emptyList()
    }

    suspend fun searchNetworksByBSSIDsAsync(bssids: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val (cachedBssids, uncachedBssids) = bssids.partition { resultsCache.containsKey(it) }

                val cachedResults = cachedBssids.mapNotNull { resultsCache[it] }

                val newResults = if (uncachedBssids.isNotEmpty()) {
                    searchUncachedBSSIDs(uncachedBssids)
                } else {
                    emptyList()
                }

                (cachedResults + newResults).distinctBy { it["BSSID"] }
            } catch (e: Exception) {
                Log.e("SQLite3WiFiHelper", "Error searching networks", e)
                emptyList()
            }
        }

    private fun searchUncachedBSSIDs(bssids: List<String>): List<Map<String, Any?>> {
        val decimalBSSIDs = bssids.mapNotNull { bssid ->
            val decimal = macToDecimal(bssid.uppercase(Locale.ROOT))
            if (decimal != -1L) decimal else null
        }

        if (decimalBSSIDs.isEmpty()) return emptyList()

        val tableName = DatabaseTypeUtils.getMainTableName(database!!)
        if (tableName == "unknown") return emptyList()

        val chunkedBssids = decimalBSSIDs.chunked(500)

        val indices = DatabaseIndices.getAvailableIndices(database!!)
        Log.d(TAG, "Available indices for BSSID search: $indices")

        val compositeIndex = if (tableName == "nets")
            DatabaseIndices.NETS_COMPOSITE
        else
            DatabaseIndices.BASE_COMPOSITE

        val query = if (indices.contains(compositeIndex)) {
            """
        SELECT n.*, g.latitude, g.longitude
        FROM $tableName n INDEXED BY $compositeIndex
        LEFT JOIN geo g ON n.BSSID = g.BSSID
        WHERE n.BSSID IN (${chunkedBssids[0].joinToString(",") { "?" }})
        """
        } else {
            buildBssidQuery(chunkedBssids[0].map { it.toString() }, indices, tableName)
        }

        Log.d(TAG, "Using query: $query")

        return chunkedBssids.flatMap { chunk ->
            database?.rawQuery(query, chunk.map { it.toString() }.toTypedArray())?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val result = mutableMapOf<String, Any?>()
                        for (i in 0 until cursor.columnCount) {
                            val columnName = cursor.getColumnName(i)
                            result[columnName] = when (cursor.getType(i)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                else -> cursor.getString(i)
                            }
                        }

                        if (result["BSSID"] is Long) {
                            val bssid = decimalToMac(result["BSSID"] as Long)
                            result["BSSID"] = bssid
                            resultsCache[bssid] = result
                        }

                        add(result)
                    }
                }
            } ?: emptyList()
        }
    }

    private fun buildBssidQuery(bssids: List<String>, indices: Set<String>, tableName: String): String {
        return when {
            indices.contains(DatabaseIndices.GEO_COORDS_BSSID) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n
            LEFT JOIN geo g INDEXED BY ${DatabaseIndices.GEO_COORDS_BSSID}
            ON n.BSSID = g.BSSID
            WHERE n.BSSID IN (${bssids.joinToString(",") { "?" }})
            """
            }
            tableName == "nets" && indices.contains(DatabaseIndices.NETS_BSSID) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_BSSID}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.BSSID IN (${bssids.joinToString(",") { "?" }})
            """
            }
            tableName == "base" && indices.contains(DatabaseIndices.BASE_BSSID) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_BSSID}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.BSSID IN (${bssids.joinToString(",") { "?" }})
            """
            }
            else -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.BSSID IN (${bssids.joinToString(",") { "?" }})
            """
            }
        }
    }

    private fun buildEssidQuery(essids: List<String>, indices: Set<String>, tableName: String): String {
        Log.d(TAG, "Building ESSID query with indices: $indices")

        return when {
            tableName == "nets" && indices.contains(DatabaseIndices.NETS_ESSID_LOWER) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_ESSID_LOWER}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE LOWER(n.ESSID) IN (${essids.joinToString(",") { "?" }})
            """
            }
            tableName == "nets" && indices.contains(DatabaseIndices.NETS_ESSID) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_ESSID}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.ESSID IN (${essids.joinToString(",") { "?" }})
            """
            }
            tableName == "base" && indices.contains(DatabaseIndices.BASE_ESSID_LOWER) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_ESSID_LOWER}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE LOWER(n.ESSID) IN (${essids.joinToString(",") { "?" }})
            """
            }
            tableName == "base" && indices.contains(DatabaseIndices.BASE_ESSID) -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_ESSID}
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.ESSID IN (${essids.joinToString(",") { "?" }})
            """
            }
            else -> {
                """
            SELECT n.*, g.latitude, g.longitude
            FROM $tableName n
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE n.ESSID IN (${essids.joinToString(",") { "?" }})
            """
            }
        }.also { query ->
            Log.d(TAG, "Generated ESSID query: $query")
        }
    }

    suspend fun searchNetworksByESSIDsAsync(essids: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val validEssids = essids.filter { it.isNotBlank() }
                if (validEssids.isEmpty()) return@withContext emptyList()

                val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                if (tableName == "unknown") return@withContext emptyList()

                val chunkedEssids = validEssids.chunked(500)
                val indices = DatabaseIndices.getAvailableIndices(database!!)
                Log.d(TAG, "Available indices for ESSID search: $indices")

                chunkedEssids.flatMap { chunk ->
                    val query = buildEssidQuery(chunk, indices, tableName)
                    Log.d(TAG, "Using optimized ESSID query: $query")

                    database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val result = mutableMapOf<String, Any?>()
                                for (i in 0 until cursor.columnCount) {
                                    val columnName = cursor.getColumnName(i)
                                    result[columnName] = when (cursor.getType(i)) {
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                        else -> cursor.getString(i)
                                    }
                                }

                                if (result["BSSID"] is Long) {
                                    result["BSSID"] = decimalToMac(result["BSSID"] as Long)
                                }
                                add(result)
                            }
                        }
                    } ?: emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error searching networks by ESSID", e)
                emptyList()
            }
        }

    fun searchNetworksByBSSIDAndFields(
        query: String,
        filters: Set<String>,
        wholeWords: Boolean
    ): List<Map<String, Any?>> {
        val indices = DatabaseIndices.getAvailableIndices(database!!)
        Log.d(TAG, "Available indices: $indices")

        val tableName = if (getTableNames().contains("nets")) "nets" else "base"
        val args = mutableListOf<String>()

        if (filters == setOf("ESSID")) {
            if (tableName == "nets") {
                if (indices.contains(DatabaseIndices.NETS_ESSID_LOWER)) {
                    Log.d(TAG, "Trying NETS_ESSID_LOWER index")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_ESSID_LOWER}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE LOWER(n.ESSID) LIKE LOWER(?)
                """.trimIndent()

                    tryQuery(sql, arrayOf(if (wholeWords) query else "%$query%"))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                }

                if (indices.contains(DatabaseIndices.NETS_ESSID)) {
                    Log.d(TAG, "Trying NETS_ESSID index")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_ESSID}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE UPPER(n.ESSID) LIKE UPPER(?)
                """.trimIndent()

                    tryQuery(sql, arrayOf(if (wholeWords) query else "%$query%"))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                }
            }
            else {
                if (indices.contains(DatabaseIndices.BASE_ESSID_LOWER)) {
                    Log.d(TAG, "Trying BASE_ESSID_LOWER index")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_ESSID_LOWER}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE LOWER(n.ESSID) LIKE LOWER(?)
                """.trimIndent()

                    tryQuery(sql, arrayOf(if (wholeWords) query else "%$query%"))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                }

                if (indices.contains(DatabaseIndices.BASE_ESSID)) {
                    Log.d(TAG, "Trying BASE_ESSID index")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_ESSID}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE UPPER(n.ESSID) LIKE UPPER(?)
                """.trimIndent()

                    tryQuery(sql, arrayOf(if (wholeWords) query else "%$query%"))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                }
            }

            Log.d(TAG, "Using fallback search without index")
            val sql = """
            SELECT DISTINCT n.*, g.latitude, g.longitude 
            FROM $tableName n
            LEFT JOIN geo g ON n.BSSID = g.BSSID
            WHERE UPPER(n.ESSID) LIKE UPPER(?)
        """.trimIndent()

            return database?.rawQuery(sql, arrayOf(if (wholeWords) query else "%$query%"))?.use { cursor ->
                cursor.toSearchResults()
            } ?: emptyList()
        }

        if (filters == setOf("BSSID")) {
            val macValue = if (query.contains(":") || query.contains("-")) {
                try {
                    query.replace(":", "").replace("-", "").toLong(16).toString()
                } catch (_: Exception) {
                    null
                }
            } else query.toLongOrNull()?.toString()

            if (macValue != null) {
                if (tableName == "nets" && indices.contains(DatabaseIndices.NETS_BSSID)) {
                    Log.d(TAG, "Using NETS_BSSID index for exact match")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.NETS_BSSID}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE n.BSSID = ?
                """.trimIndent()

                    tryQuery(sql, arrayOf(macValue))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                } else if (tableName == "base" && indices.contains(DatabaseIndices.BASE_BSSID)) {
                    Log.d(TAG, "Using BASE_BSSID index for exact match")
                    val sql = """
                    SELECT DISTINCT n.*, g.latitude, g.longitude 
                    FROM $tableName n INDEXED BY ${DatabaseIndices.BASE_BSSID}
                    LEFT JOIN geo g ON n.BSSID = g.BSSID
                    WHERE n.BSSID = ?
                """.trimIndent()

                    tryQuery(sql, arrayOf(macValue))?.use { cursor ->
                        val results = cursor.toSearchResults()
                        if (results.isNotEmpty()) {
                            return results
                        }
                    }
                }
            }
        }

        val whereConditions = buildWhereConditions(filters, query, wholeWords, args)

        val sql = """
        SELECT DISTINCT n.*, g.latitude, g.longitude
        FROM $tableName n
        LEFT JOIN geo g ON n.BSSID = g.BSSID
        WHERE $whereConditions
        ORDER BY n.BSSID
    """.trimIndent()

        Log.d(TAG, "Using fallback query: $sql")
        Log.d(TAG, "Args: ${args.joinToString()}")

        return database?.rawQuery(sql, args.toTypedArray())?.use { cursor ->
            cursor.toSearchResults()
        } ?: emptyList()
    }

    private fun buildWhereConditions(
        filters: Set<String>,
        query: String,
        wholeWords: Boolean,
        args: MutableList<String>
    ): String {
        val conditions = mutableListOf<String>()

        filters.forEach { field ->
            when (field) {
                "BSSID" -> {
                    val decimalBssid = if (query.contains(":") || query.contains("-")) {
                        try {
                            val decimal = query.replace(":", "").replace("-", "").toLong(16)
                            conditions.add("n.BSSID = ?")
                            args.add(decimal.toString())
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } else query.toLongOrNull() != null

                    if (!decimalBssid) {
                        conditions.add("CAST(n.BSSID AS TEXT) LIKE ?")
                        args.add(if (wholeWords) query else "%$query%")
                    }
                }
                "ESSID" -> {
                    conditions.add("LOWER(n.ESSID) LIKE LOWER(?)")
                    args.add(if (wholeWords) query else "%$query%")
                }
                else -> {
                    conditions.add("LOWER($field) LIKE LOWER(?)")
                    args.add(if (wholeWords) query else "%$query%")
                }
            }
        }

        return if (conditions.isEmpty()) "1=0" else conditions.joinToString(" OR ")
    }

    private fun Cursor.toSearchResults(): List<Map<String, Any?>> = buildList {
        while (moveToNext()) {
            val result = buildMap {
                for (i in 0 until columnCount) {
                    val columnName = getColumnName(i)
                    val value = when (getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> getFloat(i)
                        else -> getString(i)
                    }
                    put(columnName, value)
                }
            }.toMutableMap()

            if (result["BSSID"] is Long) {
                result["BSSID"] = decimalToMac(result["BSSID"] as Long)
            }

            add(result)
        }
    }

    private fun tryQuery(sql: String, args: Array<String>): Cursor? {
        return try {
            database?.rawQuery(sql, args)
        } catch (e: Exception) {
            Log.d(TAG, "Query failed: $sql with error: ${e.message}")
            null
        }
    }

    private fun macToDecimal(mac: String): Long {
        return try {
            Log.d(TAG, "Converting MAC to decimal: $mac")
            val result = when {
                mac.contains(":") || mac.contains("-") ->
                    mac.replace(":", "").replace("-", "").toLong(16)
                mac.toLongOrNull() != null -> mac.toLong()
                else -> throw NumberFormatException("Invalid MAC format")
            }
            Log.d(TAG, "Converted MAC $mac to decimal: $result")
            result
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Invalid MAC address: $mac", e)
            -1
        }
    }

    private fun decimalToMac(decimal: Long): String {
        Log.d(TAG, "Converting decimal to MAC: $decimal")
        val mac = String.format("%012X", decimal)
            .replace("(.{2})".toRegex(), "$1:").dropLast(1)
        Log.d(TAG, "Converted decimal $decimal to MAC: $mac")
        return mac
    }

    override fun close() {
        clearCache()
        database?.close()
        super.close()

    }

    companion object {
        fun deleteCachedDatabase(context: Context, dbUri: Uri) {
            val cacheDir = File(context.cacheDir, "CacheDB")
            val fileName = getFileNameFromUri(dbUri)
            val cachedFile = File(cacheDir, fileName)
            if (cachedFile.exists()) {
                cachedFile.delete()
                Log.d("SQLite3WiFiHelper", "Deleted cached database file: ${cachedFile.path}")
            }

            val metadataFile = File(cacheDir, "${fileName}.metadata")
            if (metadataFile.exists()) {
                metadataFile.delete()
                Log.d("SQLite3WiFiHelper", "Deleted metadata file: ${metadataFile.path}")
            }
        }

        private fun getFileNameFromUri(uri: Uri): String {
            val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
            return "$name.sqlite"
        }
    }

    fun clearCache() {
        resultsCache.clear()
    }

}