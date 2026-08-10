package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.PerformanceManager
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.lsd.wififrankenstein.util.TileRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ExternalIndexManager(private val context: Context) {
    private val TAG = "ExternalIndexManager"
    private val INDEX_DB_DIR = "db_indexes"

    companion object {
        private const val HASH_PREFS = "external_db_indexes"
        private const val INDEX_SCHEMA_VERSION = 1
        private val HEX_12_REGEX = Regex("[0-9a-fA-F]{12}")
        private val HEX_PAIR_REGEX = Regex("(.{2})")
    }

    private val INDEX_DB_CACHE_SIZE = 4
    private val indexDbCache = mutableMapOf<String, SQLiteDatabase>()
    private val indexDbInUse = mutableMapOf<String, Int>()
    private val cacheLock = Any()
    private val pathMutexes = mutableMapOf<String, Mutex>()
    private val pathMutexesLock = Any()

    private fun getIndexesDir(): File {
        val dir = File(context.filesDir, INDEX_DB_DIR)
        dir.mkdirs()
        return dir
    }

    private fun getIndexDbPath(dbId: String): String {
        return File(getIndexesDir(), "$dbId.index.db").absolutePath
    }

    private fun saveDbHash(dbId: String, hash: String) {
        context.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(dbId, hash)
            }
    }

    private fun getDbHash(dbId: String): String? {
        return context.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE)
            .getString(dbId, null)
    }

    private fun getPathMutex(dbPath: String): Mutex {
        synchronized(pathMutexesLock) {
            return pathMutexes.getOrPut(dbPath) { Mutex() }
        }
    }

    private suspend fun <T> withIndexDb(dbPath: String, block: suspend (SQLiteDatabase) -> T): T {
        val pathMutex = getPathMutex(dbPath)
        return pathMutex.withLock {
            val db = synchronized(cacheLock) {
                indexDbInUse[dbPath] = (indexDbInUse[dbPath] ?: 0) + 1
                indexDbCache.getOrPut(dbPath) {
                    SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                }
            }
            try {
                block(db)
            } finally {
                synchronized(cacheLock) {
                    val remaining = (indexDbInUse[dbPath] ?: 0) - 1
                    if (remaining <= 0) {
                        indexDbInUse.remove(dbPath)
                    } else {
                        indexDbInUse[dbPath] = remaining
                    }
                    evictIndexDbIfNeeded()
                }
            }
        }
    }

    private fun evictIndexDbIfNeeded() {
        if (indexDbCache.size <= INDEX_DB_CACHE_SIZE) return
        val candidates = indexDbCache.entries.toList()
        for ((path, db) in candidates) {
            if (indexDbCache.size <= INDEX_DB_CACHE_SIZE) break
            if ((indexDbInUse[path] ?: 0) > 0) continue
            db.close()
            indexDbCache.remove(path)
        }
    }

    fun close() {
        synchronized(cacheLock) {
            indexDbCache.values.forEach { it.close() }
            indexDbCache.clear()
            indexDbInUse.clear()
        }
    }

    private fun calculateDbHash(dbPath: String): String {
        val file = File(dbPath)

        return try {
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $dbPath")
                return ""
            }

            val fileSize = file.length()
            Log.d(TAG, "File size: $fileSize bytes")

            if (fileSize > 50 * 1024 * 1024) {
                Log.d(TAG, "File too large, using modified date for hash")
                return file.lastModified().toString()
            }

            val md = MessageDigest.getInstance("SHA-256")
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(8192)
            var read: Int

            inputStream.use { stream ->
                while (stream.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }

            val digest = md.digest()
            val hash = digest.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Generated hash: $hash")
            hash
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating hash", e)
            file.lastModified().toString()
        }
    }

    suspend fun needsIndexing(dbId: String, dbPath: String): Boolean = withContext(Dispatchers.IO) {
        val newHash = calculateDbHash(dbPath)
        val oldHash = getDbHash(dbId)
        val indexDbFile = File(getIndexDbPath(dbId))

        var needsIndexing = oldHash != newHash || !indexDbFile.exists()

        if (!needsIndexing && indexDbFile.exists()) {
            val version = readIndexSchemaVersion(indexDbFile.absolutePath)
            if (version < INDEX_SCHEMA_VERSION) {
                Log.d(
                    TAG,
                    "Database $dbId has stale index schema version $version < $INDEX_SCHEMA_VERSION, needs reindex"
                )
                needsIndexing = true
            }
        }

        Log.d(
            TAG,
            "Database $dbId: oldHash=$oldHash, newHash=$newHash, indexExists=${indexDbFile.exists()}, needsIndexing=$needsIndexing"
        )
        needsIndexing
    }

    private fun readIndexSchemaVersion(indexDbPath: String): Int {
        val file = File(indexDbPath)
        if (!file.exists()) return 0
        return try {
            SQLiteDatabase.openDatabase(
                indexDbPath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read schema version from $indexDbPath", e)
            0
        }
    }

    suspend fun createExternalIndexes(
        dbId: String,
        dbPath: String,
        tableName: String,
        columnMap: Map<String, String>,
        progressCallback: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting external index creation for $dbId, tableName=$tableName")
            Log.d(TAG, "Column mapping: $columnMap")

            val newHash = calculateDbHash(dbPath)
            val oldHash = getDbHash(dbId)
            val indexDbPath = getIndexDbPath(dbId)

            if (oldHash == newHash && File(indexDbPath).exists() &&
                readIndexSchemaVersion(indexDbPath) >= INDEX_SCHEMA_VERSION
            ) {
                Log.d(TAG, "External index database exists and hash matches, skipping")
                return@withContext true
            }

            progressCallback(0)

            synchronized(cacheLock) {
                indexDbCache.remove(indexDbPath)?.close()
                indexDbInUse.remove(indexDbPath)
            }

            val indexFile = File(indexDbPath)
            if (indexFile.exists()) {
                indexFile.delete()
                Log.d(TAG, "Deleted old index database: $indexDbPath")
            }

            val requiredSpace = File(dbPath).length() / 2
            val availableSpace = indexFile.parentFile?.usableSpace ?: 0L

            if (availableSpace < requiredSpace) {
                Log.e(
                    TAG,
                    "Not enough space for index creation. Required: $requiredSpace, Available: $availableSpace"
                )
                progressCallback(100)
                return@withContext false
            }

            val indexDb = SQLiteDatabase.openOrCreateDatabase(indexDbPath, null)

            try {
                Log.d(TAG, "Attaching main database: $dbPath")
                val escapedPath = dbPath.replace("'", "''")
                indexDb.execSQL("ATTACH DATABASE '$escapedPath' AS maindb")

                val mainDb = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                mainDb.close()

                val macColumn = columnMap["mac"] ?: throw IllegalArgumentException(
                    context.getString(
                        R.string.mac_column_required
                    )
                )

                val availableColumns = mutableMapOf<String, String>()
                availableColumns["mac"] = macColumn

                columnMap["latitude"]?.let { availableColumns["latitude"] = it }
                columnMap["longitude"]?.let { availableColumns["longitude"] = it }
                columnMap["essid"]?.let { availableColumns["essid"] = it }
                columnMap["wifi_pass"]?.let { availableColumns["password"] = it }
                columnMap["wps_pin"]?.let { availableColumns["wpspin"] = it }

                val hasGeoData =
                    availableColumns.containsKey("latitude") && availableColumns.containsKey("longitude")
                Log.d(TAG, "Available columns: ${availableColumns.keys}, Has geo data: $hasGeoData")

                Log.d(TAG, "Creating mirror table with indexes")

                val createTableColumns = mutableListOf("id INTEGER PRIMARY KEY", "mac TEXT")
                if (hasGeoData) {
                    createTableColumns.add("latitude REAL")
                    createTableColumns.add("longitude REAL")
                }
                if (availableColumns.containsKey("essid")) createTableColumns.add("essid TEXT")
                if (availableColumns.containsKey("password")) createTableColumns.add("password TEXT")
                if (availableColumns.containsKey("wpspin")) createTableColumns.add("wpspin TEXT")

                val createTableSql =
                    "CREATE TABLE indexed_data (${createTableColumns.joinToString(", ")})"
                Log.d(TAG, "Creating table with SQL: $createTableSql")
                indexDb.execSQL(createTableSql)

                val selectFields = mutableListOf(macColumn)
                val insertFields = mutableListOf("mac")

                if (hasGeoData) {
                    selectFields.add(availableColumns["latitude"]!!)
                    selectFields.add(availableColumns["longitude"]!!)
                    insertFields.add("latitude")
                    insertFields.add("longitude")
                }

                availableColumns["essid"]?.let { col ->
                    selectFields.add(col)
                    insertFields.add("essid")
                }
                availableColumns["password"]?.let { col ->
                    selectFields.add(col)
                    insertFields.add("password")
                }
                availableColumns["wpspin"]?.let { col ->
                    selectFields.add(col)
                    insertFields.add("wpspin")
                }

                val whereClause = if (hasGeoData) {
                    "WHERE ${availableColumns["latitude"]} IS NOT NULL AND ${availableColumns["longitude"]} IS NOT NULL"
                } else {
                    "WHERE $macColumn IS NOT NULL"
                }

                val insertSQL = """
                INSERT INTO indexed_data (${insertFields.joinToString(", ")})
                SELECT ${selectFields.joinToString(", ")}
                FROM maindb.$tableName
                $whereClause
            """.trimIndent()

                Log.d(TAG, "Insert SQL: $insertSQL")
                indexDb.execSQL(insertSQL)

                val insertedCount =
                    indexDb.rawQuery("SELECT COUNT(*) FROM indexed_data", null).use {
                        if (it.moveToFirst()) it.getLong(0) else 0L
                    }
                Log.d(TAG, "Indexed rows count for $dbId: $insertedCount")

                progressCallback(30)

                val prefs = context.getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
                val indexLevel = prefs.getString("custom_db_index_level", "BASIC") ?: "BASIC"

                Log.d(TAG, "Creating indexes with level: $indexLevel")

                when (indexLevel) {
                    "NONE" -> {
                        Log.d(TAG, "Skipping all indexes (NONE level)")
                        progressCallback(60)
                    }

                    else -> {
                        indexDb.execSQL("CREATE INDEX idx_custom_mac ON indexed_data (mac)")
                        progressCallback(40)

                        if (hasGeoData) {
                            indexDb.execSQL("CREATE INDEX idx_custom_geo ON indexed_data (latitude, longitude)")
                            progressCallback(60)
                        } else {
                            progressCallback(60)
                        }

                        when (indexLevel) {
                            "FULL" -> {
                                if (availableColumns.containsKey("essid")) {
                                    Log.d(TAG, "Creating ESSID index (FULL)")
                                    indexDb.execSQL("CREATE INDEX idx_custom_essid ON indexed_data (essid COLLATE NOCASE)")
                                    progressCallback(70)
                                }

                                if (availableColumns.containsKey("password")) {
                                    Log.d(TAG, "Creating password index (FULL)")
                                    indexDb.execSQL("CREATE INDEX idx_custom_password ON indexed_data (password COLLATE NOCASE)")
                                    progressCallback(80)
                                }

                                if (availableColumns.containsKey("wpspin")) {
                                    Log.d(TAG, "Creating WPS PIN index (FULL)")
                                    indexDb.execSQL("CREATE INDEX idx_custom_wpspin ON indexed_data (wpspin)")
                                    progressCallback(85)
                                }
                            }

                            else -> {
                                if (availableColumns.containsKey("essid")) {
                                    Log.d(TAG, "Creating ESSID index (BASIC)")
                                    indexDb.execSQL("CREATE INDEX idx_custom_essid ON indexed_data (essid COLLATE NOCASE)")
                                    progressCallback(70)
                                }
                            }
                        }
                    }
                }

                indexDb.execSQL("DETACH DATABASE maindb")

                indexDb.execSQL("ANALYZE")
                indexDb.execSQL("PRAGMA user_version = $INDEX_SCHEMA_VERSION")
                progressCallback(95)

                saveDbHash(dbId, newHash)
                progressCallback(100)

                Log.d(
                    TAG,
                    "External indexes created successfully for $dbId with level: $indexLevel"
                )
                return@withContext true
            } finally {
                indexDb.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating external indexes", e)
            progressCallback(100)
            try {
                val corruptFile = File(getIndexDbPath(dbId))
                if (corruptFile.exists()) {
                    corruptFile.delete()
                    Log.d(TAG, "Cleaned up corrupt index file: ${getIndexDbPath(dbId)}")
                }
            } catch (cleanup: Exception) {
                Log.e(TAG, "Error cleaning up corrupt index file", cleanup)
            }
            return@withContext false
        }
    }

    fun indexesExist(dbId: String): Boolean {
        val indexDbFile = File(getIndexDbPath(dbId))
        return indexDbFile.exists()
    }

    fun deleteIndexes(dbId: String): Boolean {
        try {
            val indexDbFile = File(getIndexDbPath(dbId))
            val result = if (indexDbFile.exists()) {
                indexDbFile.delete()
            } else {
                true
            }

            context.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE)
                .edit {
                    remove(dbId)
                }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting indexes", e)
            return false
        }
    }

    suspend fun getClusteredPointsInBoundingBox(
        dbId: String,
        dbPath: String,
        tableName: String,
        columnMap: Map<String, String>?,
        bounds: BoundingBox,
        zoom: Double,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint>? = withContext(Dispatchers.IO) {
        try {
            val indexDbPath = getIndexDbPath(dbId)
            if (!File(indexDbPath).exists()) {
                Log.d(TAG, "No index DB for $dbId, falling back to direct query")
                return@withContext null
            }

            return@withContext withIndexDb(indexDbPath) { indexDb ->
                try {
                    val hasGeoColumns =
                        indexDb.rawQuery("PRAGMA table_info(indexed_data)", null).use { cursor ->
                            var hasLat = false
                            var hasLon = false
                            while (cursor.moveToNext()) {
                                val colName = cursor.getString(1)
                                if (colName == "latitude") hasLat = true
                                if (colName == "longitude") hasLon = true
                            }
                            hasLat && hasLon
                        }

                    if (!hasGeoColumns) {
                        Log.w(TAG, "No geo columns in index for $dbId")
                        null
                    } else {
                        val maxZoom = 23.0
                        val isHighZoom = zoom >= maxZoom - 1
                        val effectiveScatterMode = scatterMode || isHighZoom
                        val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
                        val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)
                        val queryLimit = getZoomBasedLimit(zoom)

                        val query =
                            "SELECT mac, latitude, longitude FROM indexed_data WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? LIMIT ?"

                        val points = performClustering(
                            indexDb,
                            bounds.latSouth, bounds.latNorth,
                            bounds.lonWest, bounds.lonEast,
                            query, queryLimit,
                            effectiveScatterMode, mask
                        )
                        Log.d(TAG, "Clustered points from $dbId: ${points.size}")
                        points
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clustered query", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in clustered query for $dbId", e)
            null
        }
    }

    private suspend fun performClustering(
        indexDb: SQLiteDatabase,
        latSouth: Double, latNorth: Double,
        lonWest: Double, lonEast: Double,
        query: String, queryLimit: Int,
        effectiveScatterMode: Boolean,
        mask: Int
    ): List<ClusteredMapPoint> {
        val points = mutableListOf<ClusteredMapPoint>()
        val clusterMap = mutableMapOf<Long, ClusterAccumulator>()

        indexDb.rawQuery(
            query, arrayOf(
                latSouth.toString(),
                latNorth.toString(),
                lonWest.toString(),
                lonEast.toString(),
                queryLimit.toString()
            )
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val macStr = cursor.getString(0)
                    val mac = macToDecimal(macStr) ?: continue
                    val lat = cursor.getDouble(1)
                    val lon = cursor.getDouble(2)

                    val qk = QuadkeyUtils.latLonToQuadkey(lat, lon, 23)
                    val shiftedQk = if (mask > 0 && !effectiveScatterMode) qk ushr mask else mac

                    if (effectiveScatterMode) {
                        points.add(ClusteredMapPoint(mac, lat, lon, 1, false))
                    } else {
                        val acc = clusterMap.getOrPut(shiftedQk) { ClusterAccumulator() }
                        acc.count++
                        acc.sumLat += lat
                        acc.sumLon += lon
                        if (acc.count == 1) {
                            acc.bssid = mac
                            acc.lat = lat
                            acc.lon = lon
                        }

                        if (clusterMap.size % 1000 == 0) yield()
                    }
                } while (cursor.moveToNext())
            }
        }

        if (effectiveScatterMode) {
            return points
        }
        for ((_, acc) in clusterMap) {
            val avgLat = acc.sumLat / acc.count
            val avgLon = acc.sumLon / acc.count
            val isCluster = acc.count > 1
            points.add(ClusteredMapPoint(acc.bssid, avgLat, avgLon, acc.count, isCluster))
        }
        return points
    }

    private data class ClusterAccumulator(
        var bssid: Long = 0L,
        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var sumLat: Double = 0.0,
        var sumLon: Double = 0.0,
        var count: Int = 0
    )

    private fun getZoomBasedLimit(zoom: Double): Int {
        return when {
            zoom < 10 -> 2000
            zoom < 11 -> 4000
            zoom < 12 -> 8000
            zoom < 13 -> 15000
            zoom < 14 -> 25000
            zoom < 15 -> 40000
            zoom < 16 -> 60000
            zoom < 18 -> 80000
            else -> PerformanceManager.MAX_POINTS_PER_QUERY
        }
    }

    suspend fun getIndexLevel(dbId: String): String {
        try {
            val indexDbPath = getIndexDbPath(dbId)
            if (!File(indexDbPath).exists()) return "NONE"

            return withIndexDb(indexDbPath) { indexDb ->
                try {
                    val hasEssidIndex = indexDb.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_custom_essid'",
                        null
                    ).use { it.count > 0 }

                    val hasPasswordIndex = indexDb.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_custom_password'",
                        null
                    ).use { it.count > 0 }

                    when {
                        hasEssidIndex && hasPasswordIndex -> "FULL"
                        hasEssidIndex -> "BASIC"
                        else -> "NONE"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking index", e)
                    "NONE"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting index level", e)
            return "NONE"
        }
    }

    private fun macToDecimal(mac: String): Long? {
        return try {
            when {
                mac.contains(":") || mac.contains("-") ->
                    mac.replace(":", "").replace("-", "").toLong(16)

                mac.toLongOrNull() != null -> mac.toLong()
                mac.matches(HEX_12_REGEX) -> mac.toLong(16)
                else -> null
            }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Invalid MAC address: $mac", e)
            null
        }
    }


    suspend fun getPointInfo(
        dbPath: String,
        tableName: String,
        columnMap: Map<String, String>,
        macDecimal: Long
    ): List<Map<String, Any?>>? = withContext(Dispatchers.IO) {
        try {
            val macString = convertDecimalToMac(macDecimal)
            Log.d(TAG, "Getting info for MAC: $macString (decimal: $macDecimal)")

            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)

            try {
                val macColumn = columnMap["mac"] ?: return@withContext null

                val formats = listOf(
                    macString,
                    macString.uppercase(),
                    macString.replace(":", ""),
                    macString.replace(":", "").uppercase(),
                    macDecimal.toString()
                )

                val whereClause = formats.joinToString(" OR ") { "$macColumn = ?" }
                val query = "SELECT * FROM $tableName WHERE $whereClause"

                db.rawQuery(query, formats.toTypedArray()).use { cursor ->
                    if (cursor.count > 0) {
                        val results = mutableListOf<Map<String, Any?>>()
                        while (cursor.moveToNext()) {
                            val result = buildMap {
                                for (i in 0 until cursor.columnCount) {
                                    val columnName = cursor.getColumnName(i)
                                    val value = when (cursor.getType(i)) {
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(
                                            i
                                        )

                                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(
                                            i
                                        )

                                        else -> cursor.getString(i)
                                    }
                                    put(columnName, value)
                                }
                            }
                            results.add(result)
                        }
                        Log.d(TAG, "Found ${results.size} records for MAC: $macString")
                        return@withContext results
                    } else {
                        Log.d(TAG, "No data found for MAC: $macString")
                        return@withContext null
                    }
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting point info", e)
            null
        }
    }

    suspend fun getClusteredPointsByTileRange(
        dbId: String,
        dbPath: String,
        tableName: String,
        columnMap: Map<String, String>?,
        tileX1: Int,
        tileY1: Int,
        tileX2: Int,
        tileY2: Int,
        zoom: Int,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint>? = withContext(Dispatchers.IO) {
        try {
            val indexDbPath = getIndexDbPath(dbId)
            if (!File(indexDbPath).exists()) {
                Log.d(TAG, "[TileQuery-NoIndex] No index DB for $dbId")
                return@withContext null
            }

            val bounds = QuadkeyUtils.getTileRangeBounds(
                TileRange(tileX1, tileY1, tileX2, tileY2),
                zoom
            )
            if (bounds == null) {
                Log.w(TAG, "[TileQuery] Failed to compute bounds for $dbId")
                return@withContext null
            }

            val maxZoom = 23.0
            val isHighZoom = zoom >= maxZoom - 1
            val effectiveScatterMode = scatterMode || isHighZoom
            val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
            val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)
            val queryLimit = getZoomBasedLimit(zoom.toDouble())

            val query =
                "SELECT mac, latitude, longitude FROM indexed_data WHERE latitude >= ? AND latitude <= ? AND longitude >= ? AND longitude <= ? LIMIT ?"

            val points = withIndexDb(indexDbPath) { indexDb ->
                performClustering(
                    indexDb,
                    bounds.latSouth,
                    bounds.latNorth,
                    bounds.lonWest,
                    bounds.lonEast,
                    query,
                    queryLimit,
                    effectiveScatterMode,
                    mask
                )
            }
            if (points.isEmpty()) {
                val total = try {
                    withIndexDb(indexDbPath) { db ->
                        db.rawQuery("SELECT COUNT(*) FROM indexed_data", null).use {
                            if (it.moveToFirst()) it.getLong(0) else 0L
                        }
                    }
                } catch (e: Exception) {
                    -1L
                }
                Log.d(
                    TAG,
                    "[TileQuery] Retrieved 0 clustered points for $dbId, bounds=$bounds, indexTotalRows=$total"
                )
            } else {
                Log.d(TAG, "[TileQuery] Retrieved ${points.size} clustered points for $dbId")
            }
            points
        } catch (e: Exception) {
            Log.e(TAG, "[TileQuery] Error in tile query for $dbId", e)
            null
        }
    }

    private fun convertDecimalToMac(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace(HEX_PAIR_REGEX, "$1:").dropLast(1)
    }
}