package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ExternalIndexManager(private val context: Context) {
    private val TAG = "ExternalIndexManager"
    private val INDEX_DB_DIR = "db_indexes"

    companion object {
        private const val HASH_PREFS = "external_db_indexes"
    }

    private fun getIndexesDir(): File {
        val dir = File(context.filesDir, INDEX_DB_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
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

    fun needsIndexing(dbId: String, dbPath: String): Boolean {
        val newHash = calculateDbHash(dbPath)
        val oldHash = getDbHash(dbId)
        val indexDbFile = File(getIndexDbPath(dbId))

        val needsIndexing = oldHash != newHash || !indexDbFile.exists()
        Log.d(TAG, "Database $dbId: oldHash=$oldHash, newHash=$newHash, indexExists=${indexDbFile.exists()}, needsIndexing=$needsIndexing")
        return needsIndexing
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

            if (oldHash == newHash && File(indexDbPath).exists()) {
                Log.d(TAG, "External index database exists and hash matches, skipping")
                return@withContext true
            }

            progressCallback(0)

            val indexFile = File(indexDbPath)
            if (indexFile.exists()) {
                indexFile.delete()
                Log.d(TAG, "Deleted old index database: $indexDbPath")
            }

            val requiredSpace = File(dbPath).length() / 2
            val availableSpace = indexFile.parentFile?.usableSpace ?: 0L

            if (availableSpace < requiredSpace) {
                Log.e(TAG, "Not enough space for index creation. Required: $requiredSpace, Available: $availableSpace")
                progressCallback(100)
                return@withContext false
            }

            val indexDb = SQLiteDatabase.openOrCreateDatabase(indexDbPath, null)

            try {
                Log.d(TAG, "Attaching main database: $dbPath")
                indexDb.execSQL("ATTACH DATABASE '$dbPath' AS maindb")

                val mainDb = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                val dbType = DatabaseTypeUtils.determineDbType(mainDb)
                mainDb.close()

                Log.d(TAG, "Database type detected: $dbType")

                val macColumn = columnMap["mac"] ?: throw IllegalArgumentException(context.getString(
                    R.string.mac_column_required))

                val availableColumns = mutableMapOf<String, String>()
                availableColumns["mac"] = macColumn

                columnMap["latitude"]?.let { availableColumns["latitude"] = it }
                columnMap["longitude"]?.let { availableColumns["longitude"] = it }
                columnMap["essid"]?.let { availableColumns["essid"] = it }
                columnMap["wifi_pass"]?.let { availableColumns["password"] = it }
                columnMap["wps_pin"]?.let { availableColumns["wpspin"] = it }

                val hasGeoData = availableColumns.containsKey("latitude") && availableColumns.containsKey("longitude")
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

                val createTableSql = "CREATE TABLE indexed_data (${createTableColumns.joinToString(", ")})"
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

                progressCallback(30)

                val prefs = context.getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
                val indexLevel = prefs.getString("custom_db_index_level", "BASIC") ?: "BASIC"

                Log.d(TAG, "Creating indexes with level: $indexLevel")

                indexDb.execSQL("CREATE INDEX idx_custom_mac ON indexed_data (mac)")
                progressCallback(40)

                if (hasGeoData) {
                    indexDb.execSQL("CREATE INDEX idx_custom_latitude ON indexed_data (latitude)")
                    progressCallback(50)

                    indexDb.execSQL("CREATE INDEX idx_custom_longitude ON indexed_data (longitude)")
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
                    "BASIC" -> {
                        if (availableColumns.containsKey("essid")) {
                            Log.d(TAG, "Creating ESSID index (BASIC)")
                            indexDb.execSQL("CREATE INDEX idx_custom_essid ON indexed_data (essid COLLATE NOCASE)")
                            progressCallback(70)
                        }
                    }
                    "NONE" -> {
                        Log.d(TAG, "Skipping additional indexes (NONE level)")
                    }
                }

                indexDb.execSQL("DETACH DATABASE maindb")

                indexDb.execSQL("ANALYZE")
                progressCallback(95)

                saveDbHash(dbId, newHash)
                progressCallback(100)

                Log.d(TAG, "External indexes created successfully for $dbId with level: $indexLevel")
                return@withContext true
            } finally {
                indexDb.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating external indexes", e)
            progressCallback(100)
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

    suspend fun getPointsInBoundingBox(
        dbId: String,
        bounds: BoundingBox,
        maxPoints: Int = Int.MAX_VALUE
    ): List<Triple<Long, Double, Double>> = withContext(Dispatchers.IO) {
        try {
            val indexDbPath = getIndexDbPath(dbId)
            if (!File(indexDbPath).exists()) {
                Log.e(TAG, "Index database does not exist: $indexDbPath")
                return@withContext emptyList()
            }

            val indexDb = SQLiteDatabase.openDatabase(
                indexDbPath, null, SQLiteDatabase.OPEN_READONLY
            )

            try {
                val hasGeoColumns = indexDb.rawQuery("PRAGMA table_info(indexed_data)", null).use { cursor ->
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
                    Log.w(TAG, "No latitude/longitude columns in index database for $dbId")
                    return@withContext emptyList()
                }

                val indexLevel = getIndexLevel(dbId)

                val limitClause = if (maxPoints != Int.MAX_VALUE) " LIMIT $maxPoints" else ""

                val query = when (indexLevel) {
                    "FULL", "BASIC" -> {
                        """
                    SELECT mac, latitude, longitude 
                    FROM indexed_data 
                    INDEXED BY idx_custom_latitude
                    WHERE latitude BETWEEN ? AND ?
                    AND longitude BETWEEN ? AND ?$limitClause
                    """.trimIndent()
                    }
                    else -> {
                        """
                    SELECT mac, latitude, longitude 
                    FROM indexed_data 
                    WHERE latitude BETWEEN ? AND ?
                    AND longitude BETWEEN ? AND ?$limitClause
                    """.trimIndent()
                    }
                }

                indexDb.rawQuery(query, arrayOf(
                    bounds.latSouth.toString(),
                    bounds.latNorth.toString(),
                    bounds.lonWest.toString(),
                    bounds.lonEast.toString()
                )).use { cursor ->
                    return@withContext buildList {
                        var count = 0
                        while (cursor.moveToNext() && count < maxPoints) {
                            try {
                                val macStr = cursor.getString(0)
                                val mac = macToDecimal(macStr) ?: continue
                                val lat = cursor.getDouble(1)
                                val lon = cursor.getDouble(2)

                                add(Triple(mac, lat, lon))
                                count++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing point", e)
                                continue
                            }
                        }
                    }
                }
            } finally {
                indexDb.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying points from external index", e)
            emptyList()
        }
    }

    fun getIndexLevel(dbId: String): String {
        try {
            val indexDbPath = getIndexDbPath(dbId)
            if (!File(indexDbPath).exists()) return "NONE"

            val indexDb = SQLiteDatabase.openDatabase(
                indexDbPath, null, SQLiteDatabase.OPEN_READONLY
            )

            return try {
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
            } finally {
                indexDb.close()
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
                mac.matches("[0-9a-fA-F]{12}".toRegex()) -> mac.toLong(16)
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
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
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
    private fun convertDecimalToMac(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace("(.{2})".toRegex(), "$1:").dropLast(1)
    }
}