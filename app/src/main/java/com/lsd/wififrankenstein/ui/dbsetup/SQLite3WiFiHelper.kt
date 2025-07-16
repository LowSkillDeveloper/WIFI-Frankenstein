package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseOptimizer
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
                return size > 1000
            }
        }
    )

    private fun openDatabaseFromDirectPath(): SQLiteDatabase {
        return try {
            val db = SQLiteDatabase.openDatabase(directPath!!, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            DatabaseOptimizer.optimizeDatabase(db)
            db
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
            val points = ArrayList<Triple<Long, Double, Double>>(50000)

            try {
                val indexLevel = DatabaseIndices.determineIndexLevel(database!!)
                Log.d(TAG, "Index level: $indexLevel")

                val query = DatabaseIndices.getOptimalGeoQuery(indexLevel)

                Log.d(TAG, "Executing query with bounds: lat(${bounds.latSouth}-${bounds.latNorth}), lon(${bounds.lonWest}-${bounds.lonEast})")

                database?.rawQuery(query, arrayOf(
                    bounds.latSouth.toString(),
                    bounds.latNorth.toString(),
                    bounds.lonWest.toString(),
                    bounds.lonEast.toString()
                ))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val bssidIndex = cursor.getColumnIndex("BSSID")
                        val latIndex = cursor.getColumnIndex("latitude")
                        val lonIndex = cursor.getColumnIndex("longitude")

                        do {
                            val bssid = cursor.getLong(bssidIndex)
                            val lat = cursor.getDouble(latIndex)
                            val lon = cursor.getDouble(lonIndex)
                            points.add(Triple(bssid, lat, lon))

                            if (points.size % 10000 == 0) {
                                yield()
                            }
                        } while (cursor.moveToNext())
                    }
                    Log.d(TAG, "Retrieved ${points.size} points from database")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting points in bounding box", e)
            }

            points
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

        val chunkedBssids = decimalBSSIDs.chunked(100)
        Log.d(TAG, "Searching for ${decimalBSSIDs.size} BSSIDs in ${chunkedBssids.size} chunks")

        val indexLevel = DatabaseIndices.determineIndexLevel(database!!)

        val placeholders = chunkedBssids[0].joinToString(",") { "?" }
        val baseQuery = DatabaseIndices.getOptimalBssidQuery(indexLevel, tableName, true)
        val query = baseQuery.replace("(?)", "($placeholders)")

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

    private fun buildBssidQuery(bssids: List<String>, indexLevel: DatabaseIndices.IndexLevel, tableName: String): String {
        val placeholders = bssids.joinToString(",") { "?" }

        return when {
            indexLevel >= DatabaseIndices.IndexLevel.BASIC -> {
                """
    SELECT n.*, g.latitude, g.longitude
    FROM $tableName n
    LEFT JOIN geo g ON n.BSSID = g.BSSID
    WHERE n.BSSID IN ($placeholders)
    """
            }
            else -> {
                """
                SELECT n.*, g.latitude, g.longitude
                FROM $tableName n
                LEFT JOIN geo g ON n.BSSID = g.BSSID
                WHERE n.BSSID IN ($placeholders)
                """
            }
        }
    }

    private suspend fun loadGeoDataForResults(results: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (results.isEmpty()) return results

        val bssids = results.mapNotNull { it["BSSID"] as? Long }.distinct()
        if (bssids.isEmpty()) return results

        val geoData = mutableMapOf<Long, Pair<Double?, Double?>>()

        bssids.chunked(100).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = """
            SELECT BSSID, latitude, longitude
            FROM geo
            WHERE BSSID IN ($placeholders)
        """

            database?.rawQuery(query, chunk.map { it.toString() }.toTypedArray())?.use { cursor ->
                while (cursor.moveToNext()) {
                    val bssid = cursor.getLong(0)
                    val lat = if (cursor.isNull(1)) null else cursor.getDouble(1)
                    val lon = if (cursor.isNull(2)) null else cursor.getDouble(2)
                    geoData[bssid] = Pair(lat, lon)
                }
            }
        }

        return results.map { result ->
            val bssid = result["BSSID"] as? Long
            if (bssid != null && geoData.containsKey(bssid)) {
                val (lat, lon) = geoData[bssid]!!
                result.toMutableMap().apply {
                    put("latitude", lat)
                    put("longitude", lon)
                }
            } else {
                result
            }
        }
    }

    suspend fun searchNetworksByESSIDsAsync(essids: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val validEssids = essids.filter { it.isNotBlank() }
                if (validEssids.isEmpty()) return@withContext emptyList()

                val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                if (tableName == "unknown") return@withContext emptyList()

                val chunkedEssids = validEssids.chunked(50)
                val indexLevel = DatabaseIndices.determineIndexLevel(database!!)
                Log.d(TAG, "Index level for ESSID search: $indexLevel")

                chunkedEssids.flatMap { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    val baseQuery = DatabaseIndices.getOptimalEssidQuery(indexLevel, tableName, true)
                    val query = baseQuery.replace("(?)", "($placeholders)")
                    Log.d(TAG, "Using ESSID query: $query")
                    Log.d(TAG, "Searching for ${chunk.size} ESSIDs")

                    val startTime = System.currentTimeMillis()

                    val results = database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
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

                    val queryTime = System.currentTimeMillis() - startTime
                    Log.d(TAG, "ESSID query completed in ${queryTime}ms, found ${results.size} results")

                    if (results.isNotEmpty()) {
                        val geoStartTime = System.currentTimeMillis()
                        val resultsWithGeo = loadGeoDataForResults(results)
                        val geoTime = System.currentTimeMillis() - geoStartTime
                        Log.d(TAG, "Geo data loaded in ${geoTime}ms")
                        resultsWithGeo
                    } else {
                        results
                    }
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
        val indexLevel = DatabaseIndices.determineIndexLevel(database!!)
        Log.d(TAG, "Index level: $indexLevel")

        val tableName = if (getTableNames().contains("nets")) "nets" else "base"
        val args = mutableListOf<String>()

        if (filters.size == 1) {
            when (filters.first()) {
                "ESSID" -> {
                    if (indexLevel >= DatabaseIndices.IndexLevel.BASIC) {
                        val essidIndex = if (tableName == "nets") DatabaseIndices.NETS_ESSID else DatabaseIndices.BASE_ESSID
                        val sql = if (wholeWords) {
                            """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $essidIndex
                        LEFT JOIN geo g ON n.BSSID = g.BSSID
                        WHERE n.ESSID = ? COLLATE NOCASE
                        """.trimIndent()
                        } else {
                            """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $essidIndex
                        LEFT JOIN geo g ON n.BSSID = g.BSSID
                        WHERE n.ESSID LIKE ? ESCAPE '\\'
                        """.trimIndent()
                        }

                        val searchArg = if (wholeWords) query else "%${query.replace("%", "\\%").replace("_", "\\_")}%"

                        return database?.rawQuery(sql, arrayOf(searchArg))?.use { cursor ->
                            cursor.toSearchResults()
                        } ?: emptyList()
                    }
                }
                "WiFiKey" -> {
                    if (indexLevel == DatabaseIndices.IndexLevel.FULL) {
                        val wifiKeyIndex = if (tableName == "nets") DatabaseIndices.NETS_WIFIKEY else DatabaseIndices.BASE_WIFIKEY
                        val sql = if (wholeWords) {
                            """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $wifiKeyIndex
                        LEFT JOIN geo g ON n.BSSID = g.BSSID
                        WHERE n.WiFiKey = ? COLLATE NOCASE
                        """.trimIndent()
                        } else {
                            """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $wifiKeyIndex
                        LEFT JOIN geo g ON n.BSSID = g.BSSID
                        WHERE n.WiFiKey LIKE ? ESCAPE '\\'
                        """.trimIndent()
                        }

                        val searchArg = if (wholeWords) query else "%${query.replace("%", "\\%").replace("_", "\\_")}%"

                        return database?.rawQuery(sql, arrayOf(searchArg))?.use { cursor ->
                            cursor.toSearchResults()
                        } ?: emptyList()
                    }
                }
                "WPSPIN" -> {
                    if (indexLevel == DatabaseIndices.IndexLevel.FULL) {
                        val wpsPinIndex = if (tableName == "nets") DatabaseIndices.NETS_WPSPIN else DatabaseIndices.BASE_WPSPIN
                        val sql = """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $wpsPinIndex
                        LEFT JOIN geo g ON n.BSSID = g.BSSID
                        WHERE n.WPSPIN = ?
                    """.trimIndent()

                        return database?.rawQuery(sql, arrayOf(query))?.use { cursor ->
                            cursor.toSearchResults()
                        } ?: emptyList()
                    }
                }
                "BSSID" -> {
                    val macValue = if (query.contains(":") || query.contains("-")) {
                        try {
                            query.replace(":", "").replace("-", "").toLong(16).toString()
                        } catch (_: Exception) {
                            null
                        }
                    } else query.toLongOrNull()?.toString()

                    if (macValue != null && indexLevel >= DatabaseIndices.IndexLevel.BASIC) {
                        val bssidIndex = if (tableName == "nets") DatabaseIndices.NETS_BSSID else DatabaseIndices.BASE_BSSID
                        val sql = """
                        SELECT DISTINCT n.*, g.latitude, g.longitude 
                        FROM $tableName n INDEXED BY $bssidIndex
                        LEFT JOIN geo g INDEXED BY ${DatabaseIndices.GEO_BSSID} ON n.BSSID = g.BSSID
                        WHERE n.BSSID = ?
                    """.trimIndent()

                        return database?.rawQuery(sql, arrayOf(macValue))?.use { cursor ->
                            cursor.toSearchResults()
                        } ?: emptyList()
                    }
                }
            }
        }

        val sql = DatabaseIndices.getOptimalSearchQuery(indexLevel, tableName, filters, wholeWords)

        filters.forEach { field ->
            when (field) {
                "BSSID" -> {
                    val decimalBssid = if (query.contains(":") || query.contains("-")) {
                        try {
                            val decimal = query.replace(":", "").replace("-", "").toLong(16)
                            args.add(decimal.toString())
                            true
                        } catch (_: Exception) {
                            false
                        }
                    } else query.toLongOrNull() != null

                    if (!decimalBssid) {
                        args.add(if (wholeWords) query else "%$query%")
                    }
                }
                "ESSID" -> {
                    if (wholeWords) {
                        args.add(query)
                    } else {
                        args.add("%${query.replace("%", "\\%").replace("_", "\\_")}%")
                    }
                }
                "WiFiKey" -> {
                    if (wholeWords) {
                        args.add(query)
                    } else {
                        args.add("%${query.replace("%", "\\%").replace("_", "\\_")}%")
                    }
                }
                "WPSPIN" -> {
                    args.add(query)
                }
            }
        }

        Log.d(TAG, "Using query: $sql")
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
                    if (wholeWords) {
                        conditions.add("n.ESSID = ? COLLATE NOCASE")
                        args.add(query)
                    } else {
                        conditions.add("n.ESSID LIKE ? ESCAPE '\\'")
                        args.add("%${query.replace("%", "\\%").replace("_", "\\_")}%")
                    }
                }
                "WiFiKey" -> {
                    if (wholeWords) {
                        conditions.add("n.WiFiKey = ? COLLATE NOCASE")
                        args.add(query)
                    } else {
                        conditions.add("n.WiFiKey LIKE ? ESCAPE '\\'")
                        args.add("%${query.replace("%", "\\%").replace("_", "\\_")}%")
                    }
                }
                "WPSPIN" -> {
                    conditions.add("n.WPSPIN = ?")
                    args.add(query)
                }
                else -> {
                    conditions.add("$field LIKE ? ESCAPE '\\'")
                    args.add(if (wholeWords) query else "%${query.replace("%", "\\%").replace("_", "\\_")}%")
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