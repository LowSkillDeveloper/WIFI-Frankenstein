package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.content.edit
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.io.File
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
        return try {
            val file = File(dbPath)
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

            val bytes = file.readBytes()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val hash = digest.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "Generated hash: $hash")
            hash
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating hash", e)
            ""
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

            val indexDb = SQLiteDatabase.openOrCreateDatabase(indexDbPath, null)

            try {
                Log.d(TAG, "Attaching main database: $dbPath")
                indexDb.execSQL("ATTACH DATABASE '$dbPath' AS maindb")

                val mainDb = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                val dbType = DatabaseTypeUtils.determineDbType(mainDb)
                mainDb.close()

                Log.d(TAG, "Database type detected: $dbType")

                val macColumn = columnMap["mac"] ?: throw IllegalArgumentException("MAC column not specified in column map")
                val latColumn = columnMap["latitude"] ?: throw IllegalArgumentException("Latitude column not specified in column map")
                val lonColumn = columnMap["longitude"] ?: throw IllegalArgumentException("Longitude column not specified in column map")

                Log.d(TAG, "Creating mirror table with indexes")

                indexDb.execSQL("""
                CREATE TABLE indexed_data (
                    id INTEGER PRIMARY KEY,
                    mac TEXT,
                    latitude REAL,
                    longitude REAL,
                    essid TEXT,
                    password TEXT,
                    wpspin TEXT
                )
            """.trimIndent())

                val essidCol = columnMap["essid"]
                val passCol = columnMap["wifi_pass"]
                val wpsCol = columnMap["wps_pin"]

                val selectFields = StringBuilder("$macColumn, $latColumn, $lonColumn")
                if (essidCol != null) selectFields.append(", $essidCol")
                if (passCol != null) selectFields.append(", $passCol")
                if (wpsCol != null) selectFields.append(", $wpsCol")

                Log.d(TAG, "Filling mirror table with data from main database")
                val insertSQL = """
                INSERT INTO indexed_data (mac, latitude, longitude${if (essidCol != null) ", essid" else ""}${if (passCol != null) ", password" else ""}${if (wpsCol != null) ", wpspin" else ""})
                SELECT $selectFields
                FROM maindb.$tableName
                WHERE $latColumn IS NOT NULL AND $lonColumn IS NOT NULL
            """.trimIndent()

                Log.d(TAG, "Insert SQL: $insertSQL")
                indexDb.execSQL(insertSQL)

                progressCallback(30)

                val prefs = context.getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
                val indexLevel = prefs.getString("custom_db_index_level", "BASIC") ?: "BASIC"

                Log.d(TAG, "Creating indexes with level: $indexLevel")

                indexDb.execSQL("CREATE INDEX idx_custom_mac ON indexed_data (mac)")
                progressCallback(40)

                indexDb.execSQL("CREATE INDEX idx_custom_latitude ON indexed_data (latitude)")
                progressCallback(50)

                indexDb.execSQL("CREATE INDEX idx_custom_longitude ON indexed_data (longitude)")
                progressCallback(60)

                when (indexLevel) {
                    "FULL" -> {
                        // Создаем все индексы для FULL уровня
                        if (essidCol != null) {
                            Log.d(TAG, "Creating ESSID index (FULL)")
                            indexDb.execSQL("CREATE INDEX idx_custom_essid ON indexed_data (essid COLLATE NOCASE)")
                            progressCallback(70)
                        }

                        if (passCol != null) {
                            Log.d(TAG, "Creating password index (FULL)")
                            indexDb.execSQL("CREATE INDEX idx_custom_password ON indexed_data (password COLLATE NOCASE)")
                            progressCallback(80)
                        }

                        if (wpsCol != null) {
                            Log.d(TAG, "Creating WPS PIN index (FULL)")
                            indexDb.execSQL("CREATE INDEX idx_custom_wpspin ON indexed_data (wpspin)")
                            progressCallback(85)
                        }
                    }
                    "BASIC" -> {
                        if (essidCol != null) {
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
        bounds: BoundingBox
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
                val indexLevel = getIndexLevel(dbId)

                val query = when (indexLevel) {
                    "FULL", "BASIC" -> {
                        """
                    SELECT mac, latitude, longitude 
                    FROM indexed_data 
                    INDEXED BY idx_custom_latitude
                    WHERE latitude BETWEEN ? AND ?
                    AND longitude BETWEEN ? AND ?
                    """.trimIndent()
                    }
                    else -> {
                        """
                    SELECT mac, latitude, longitude 
                    FROM indexed_data 
                    WHERE latitude BETWEEN ? AND ?
                    AND longitude BETWEEN ? AND ?
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
                        while (cursor.moveToNext()) {
                            try {
                                val macStr = cursor.getString(0)
                                val mac = macToDecimal(macStr) ?: continue
                                val lat = cursor.getDouble(1)
                                val lon = cursor.getDouble(2)

                                add(Triple(mac, lat, lon))
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
    ): Map<String, Any?>? = withContext(Dispatchers.IO) {
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

                val query = "SELECT * FROM $tableName WHERE $whereClause LIMIT 1"

                db.rawQuery(query, formats.toTypedArray()).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return@withContext buildMap {
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
                    } else {
                        Log.d(TAG, "No data found for MAC: $macString")
                        null
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