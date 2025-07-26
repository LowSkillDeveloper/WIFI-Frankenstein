package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class LocalDbIndexManager(private val context: Context) {
    private val TAG = "LocalDbIndexManager"

    companion object {
        private const val HASH_PREFS = "local_db_index_hash"
    }

    private fun calculateDbHash(): String {
        Log.d(TAG, "Calculating hash for local database")
        return try {
            val dbFile = context.getDatabasePath(LocalAppDbHelper.DATABASE_NAME)
            if (!dbFile.exists()) {
                Log.e(TAG, "Local database file does not exist")
                return ""
            }

            if (!dbFile.canRead()) {
                Log.e(TAG, "Cannot read local database file")
                return dbFile.lastModified().toString()
            }

            val fileSize = dbFile.length()
            Log.d(TAG, "File size: $fileSize bytes")

            if (fileSize > 50 * 1024 * 1024) {
                Log.d(TAG, "File too large, using modified date for hash")
                return dbFile.lastModified().toString()
            }

            val bytes = dbFile.readBytes()
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

    fun needsIndexing(db: SQLiteDatabase): Boolean {
        Log.d(TAG, "Checking if local database needs indexing")
        val newHash = calculateDbHash()
        val oldHash = getDbHash()

        val hasIndexes = hasIndexes(db)
        val needsIndexing = oldHash != newHash || !hasIndexes
        Log.d(TAG, "Local database: oldHash=$oldHash, newHash=$newHash, hasIndexes=$hasIndexes, needsIndexing=$needsIndexing")
        return needsIndexing
    }

    private fun saveDbHash(hash: String) {
        context.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("local_db", hash)
            .apply()
    }

    private fun getDbHash(): String? {
        return context.getSharedPreferences(HASH_PREFS, Context.MODE_PRIVATE)
            .getString("local_db", null)
    }

    private fun hasIndexes(db: SQLiteDatabase): Boolean {
        return try {
            val prefs = context.getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            val indexLevel = prefs.getString("local_db_index_level", "BASIC") ?: "BASIC"

            if (indexLevel == "NONE") return true

            val requiredIndexes = when (indexLevel) {
                "FULL" -> arrayOf(
                    "idx_wifi_network_mac",
                    "idx_wifi_network_name",
                    "idx_wifi_network_coords",
                    "idx_wifi_network_password",
                    "idx_wifi_network_wps"
                )
                else -> arrayOf(
                    "idx_wifi_network_mac",
                    "idx_wifi_network_name",
                    "idx_wifi_network_coords"
                )
            }

            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name IN (${requiredIndexes.joinToString(",") { "?" }})",
                arrayOf(LocalAppDbHelper.TABLE_NAME, *requiredIndexes)
            ).use { cursor ->
                cursor.count >= requiredIndexes.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking indexes", e)
            false
        }
    }

    suspend fun createIndexes(
        db: SQLiteDatabase,
        progressCallback: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting index creation for local database")

            val newHash = calculateDbHash()
            val oldHash = getDbHash()

            Log.d(TAG, "Hash check: oldHash=$oldHash, newHash=$newHash")

            if (oldHash == newHash && hasIndexes(db)) {
                Log.d(TAG, "Local database hasn't changed and already has indexes, skipping indexing")
                return@withContext true
            }

            progressCallback(0)

            dropIndexes(db)
            progressCallback(20)

            val prefs = context.getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
            val indexLevel = prefs.getString("local_db_index_level", "BASIC") ?: "BASIC"

            Log.d(TAG, "Creating indexes with level: $indexLevel")

            try {
                when (indexLevel) {
                    "FULL" -> {
                        Log.d(TAG, "Creating FULL indexes for local database")

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_MAC_ADDRESS})")
                        progressCallback(25)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_WIFI_NAME} COLLATE NOCASE)")
                        progressCallback(35)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_LATITUDE}, ${LocalAppDbHelper.COLUMN_LONGITUDE})")
                        progressCallback(45)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_WIFI_PASSWORD} COLLATE NOCASE)")
                        progressCallback(60)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_WPS_CODE})")
                        progressCallback(75)
                    }
                    "BASIC" -> {
                        Log.d(TAG, "Creating BASIC indexes for local database")

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_MAC_ADDRESS})")
                        progressCallback(30)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_WIFI_NAME} COLLATE NOCASE)")
                        progressCallback(50)

                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON ${LocalAppDbHelper.TABLE_NAME} (${LocalAppDbHelper.COLUMN_LATITUDE}, ${LocalAppDbHelper.COLUMN_LONGITUDE})")
                        progressCallback(70)
                    }
                    "NONE" -> {
                        Log.d(TAG, "Skipping index creation for local database (NONE level)")
                        progressCallback(70)
                    }
                }

                db.execSQL("ANALYZE")
                progressCallback(85)

                if (indexLevel != "NONE") {
                    db.execSQL("VACUUM")
                    progressCallback(95)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating indexes", e)
                return@withContext false
            }

            progressCallback(100)

            Log.d(TAG, "Saving new hash: $newHash for local database")
            saveDbHash(newHash)

            val indexesCreated = if (indexLevel == "NONE") true else hasIndexes(db)
            Log.d(TAG, "Indexes created successfully: $indexesCreated")

            indexesCreated
        } catch (e: Exception) {
            Log.e(TAG, "Error creating indexes", e)
            false
        }
    }

    private fun dropIndexes(db: SQLiteDatabase) {
        Log.d(TAG, "Dropping existing indexes for local database")
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?",
            arrayOf(LocalAppDbHelper.TABLE_NAME)
        ).use { cursor ->
            if (cursor.count > 0) {
                Log.d(TAG, "Found ${cursor.count} indexes to drop")
                while (cursor.moveToNext()) {
                    val indexName = cursor.getString(0)
                    Log.d(TAG, "Dropping index: $indexName")
                    try {
                        db.execSQL("DROP INDEX IF EXISTS $indexName")
                        Log.d(TAG, "Successfully dropped index: $indexName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dropping index $indexName", e)
                    }
                }
            } else {
                Log.d(TAG, "No indexes found to drop")
            }
        }
    }
}