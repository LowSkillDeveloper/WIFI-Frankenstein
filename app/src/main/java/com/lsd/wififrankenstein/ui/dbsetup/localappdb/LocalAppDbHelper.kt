package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.database.sqlite.transaction
import com.lsd.wififrankenstein.ui.databasefinder.AdvancedSearchQuery
import com.lsd.wififrankenstein.ui.databasefinder.SearchMode
import com.lsd.wififrankenstein.ui.wifimap.ClusteredMapPoint
import com.lsd.wififrankenstein.util.CompatibilityHelper
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.QuadkeyUtils
import java.io.File
import java.io.FileOutputStream

class LocalAppDbHelper(
    private val context: Context,
    databaseName: String = DATABASE_NAME,
    private val personalDatabaseName: String = PersonalMapDbHelper.DATABASE_NAME
) :
    SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    init {

        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        const val DATABASE_NAME = "local_wifi_database.db"
        const val DATABASE_VERSION = 9

        const val TABLE_NAME = "wifi_networks"
        const val COLUMN_ID = "id"
        const val COLUMN_WIFI_NAME = "wifiname"
        const val COLUMN_MAC_ADDRESS = "macaddress"
        const val COLUMN_WIFI_PASSWORD = "wifipassword"
        const val COLUMN_WPS_CODE = "wpscode"
        const val COLUMN_ADMIN_PANEL = "adminpanel"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
        const val COLUMN_QUADKEY = "quadkey"

        const val TABLE_PERSONAL_MAP = "personal_wifi_map"
        const val KEY_PERSONAL_MIGRATED = "personal_map_migration_v9_done"
        const val COLUMN_PERSONAL_TIMESTAMP = "timestamp"
        const val COLUMN_PERSONAL_LEVEL = "level"
        const val COLUMN_PERSONAL_ACCURACY = "accuracy"
        const val COLUMN_PERSONAL_RELIABLE = "is_reliable"
        const val COLUMN_PERSONAL_COUNT = "measure_count"
        const val COLUMN_PERSONAL_WEIGHT_SUM = "weight_sum"
        const val COLUMN_PERSONAL_FIRST_SEEN = "first_seen"

    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableSQL = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WIFI_NAME TEXT,
                $COLUMN_MAC_ADDRESS TEXT,
                $COLUMN_WIFI_PASSWORD TEXT,
                $COLUMN_WPS_CODE TEXT,
                $COLUMN_ADMIN_PANEL TEXT,
                $COLUMN_LATITUDE REAL,
                $COLUMN_LONGITUDE REAL,
                $COLUMN_QUADKEY INTEGER
            )
        """.trimIndent()
        db.execSQL(createTableSQL)

        db.execSQL("CREATE INDEX idx_wifi_network_quadkey ON $TABLE_NAME ($COLUMN_QUADKEY)")
        db.execSQL("CREATE INDEX idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
        db.execSQL("CREATE INDEX idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_QUADKEY INTEGER")
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Column $COLUMN_QUADKEY upgrade error: ${e.message}")
            }
            try {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_quadkey ON $TABLE_NAME ($COLUMN_QUADKEY)")
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Error creating quadkey index during upgrade: ${e.message}")
            }

            val updates = mutableListOf<Pair<Long, Long>>()
            db.rawQuery(
                "SELECT $COLUMN_ID, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_NAME",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val lat = cursor.getDouble(1)
                    val lon = cursor.getDouble(2)
                    if (lat != 0.0 && lon != 0.0) {
                        val quadkey = QuadkeyUtils.latLonToQuadkey(lat, lon)
                        updates.add(Pair(id, quadkey))
                    }
                }
            }

            if (updates.isNotEmpty()) {
                db.transaction {
                    updates.chunked(1000).forEach { batch ->
                        batch.forEach { (id, quadkey) ->
                            db.execSQL(
                                "UPDATE $TABLE_NAME SET $COLUMN_QUADKEY = ? WHERE $COLUMN_ID = ?",
                                arrayOf(quadkey.toString(), id.toString())
                            )
                        }
                    }
                }
            }
        }
        if (oldVersion < 3) {
            val createPersonalMapSQL = """
                CREATE TABLE IF NOT EXISTS $TABLE_PERSONAL_MAP (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_WIFI_NAME TEXT,
                    $COLUMN_MAC_ADDRESS TEXT,
                    $COLUMN_LATITUDE REAL,
                    $COLUMN_LONGITUDE REAL,
                    $COLUMN_PERSONAL_TIMESTAMP INTEGER,
                    $COLUMN_PERSONAL_ACCURACY REAL,
                    $COLUMN_PERSONAL_RELIABLE INTEGER
                )
            """.trimIndent()
            db.execSQL(createPersonalMapSQL)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_mac ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS)")
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PERSONAL_MAP ADD COLUMN $COLUMN_PERSONAL_LEVEL INTEGER DEFAULT -100")
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Column $COLUMN_PERSONAL_LEVEL upgrade error: ${e.message}")
            }
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PERSONAL_MAP ADD COLUMN $COLUMN_QUADKEY INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_quadkey ON $TABLE_PERSONAL_MAP ($COLUMN_QUADKEY)")

                val updates = mutableListOf<Pair<Long, Long>>()
                db.rawQuery("SELECT $COLUMN_ID, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_PERSONAL_MAP", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val lat = cursor.getDouble(1)
                        val lon = cursor.getDouble(2)
                        if (lat != 0.0 && lon != 0.0) {
                            updates.add(Pair(id, QuadkeyUtils.latLonToQuadkey(lat, lon)))
                        }
                    }
                }

                if (updates.isNotEmpty()) {
                    db.transaction {
                        updates.chunked(1000).forEach { batch ->
                            batch.forEach { (id, quadkey) ->
                                db.execSQL("UPDATE $TABLE_PERSONAL_MAP SET $COLUMN_QUADKEY = ? WHERE $COLUMN_ID = ?",
                                    arrayOf(quadkey.toString(), id.toString()))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Version 5 upgrade error: ${e.message}")
            }
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PERSONAL_MAP ADD COLUMN $COLUMN_PERSONAL_COUNT INTEGER DEFAULT 1")
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Version 6 upgrade error: ${e.message}")
            }
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE $TABLE_PERSONAL_MAP ADD COLUMN $COLUMN_PERSONAL_WEIGHT_SUM REAL DEFAULT 0")
            } catch (e: Exception) {
                Log.w("LocalAppDbHelper", "Version 7 upgrade error: ${e.message}")
            }
        }
        if (oldVersion < 8) {
            migratePersonalMapToV8(db)
        }
        if (oldVersion < 9) {
            migratePersonalToSeparateDb(db)
        }
    }

    private fun migratePersonalToSeparateDb(db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val alreadyDone = prefs.getBoolean(KEY_PERSONAL_MIGRATED, false)
        try {
            val exists = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(TABLE_PERSONAL_MAP)
            ).use { it.moveToFirst() }
            if (!exists) {
                prefs.edit().putBoolean(KEY_PERSONAL_MIGRATED, true).apply()
                return
            }

            if (!alreadyDone) {

                val personal = PersonalMapDbHelper(context, personalDatabaseName)
                try {
                    personal.importFromLegacy(db)
                } finally {
                    personal.close()
                }

                prefs.edit().putBoolean(KEY_PERSONAL_MIGRATED, true).commit()
            }

            db.execSQL("DROP TABLE IF EXISTS $TABLE_PERSONAL_MAP")
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "v9 personal map split migration failed", e)
        }
    }

    private fun migratePersonalMapToV8(db: SQLiteDatabase) {
        val newTable = "${TABLE_PERSONAL_MAP}_new"
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $newTable (
                    $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COLUMN_WIFI_NAME TEXT,
                    $COLUMN_MAC_ADDRESS TEXT,
                    $COLUMN_LATITUDE REAL,
                    $COLUMN_LONGITUDE REAL,
                    $COLUMN_PERSONAL_TIMESTAMP INTEGER,
                    $COLUMN_PERSONAL_LEVEL INTEGER DEFAULT -100,
                    $COLUMN_PERSONAL_ACCURACY REAL,
                    $COLUMN_PERSONAL_RELIABLE INTEGER DEFAULT 0,
                    $COLUMN_PERSONAL_COUNT INTEGER DEFAULT 1,
                    $COLUMN_PERSONAL_WEIGHT_SUM REAL DEFAULT 0,
                    $COLUMN_PERSONAL_FIRST_SEEN INTEGER,
                    $COLUMN_QUADKEY INTEGER
                )
                """.trimIndent()
            )

            val aggregates = LinkedHashMap<String, PersonalAggregate>()
            val columns = "SELECT $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                    "$COLUMN_LONGITUDE, $COLUMN_PERSONAL_TIMESTAMP, $COLUMN_PERSONAL_LEVEL, " +
                    "$COLUMN_PERSONAL_ACCURACY, $COLUMN_PERSONAL_RELIABLE, $COLUMN_PERSONAL_COUNT, " +
                    "$COLUMN_PERSONAL_WEIGHT_SUM FROM $TABLE_PERSONAL_MAP"
            db.rawQuery(columns, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val rawMac = cursor.getString(1) ?: continue
                    if (rawMac.isBlank()) continue
                    val mac = normalizeMac(rawMac)
                    val lat = cursor.getDouble(2)
                    val lon = cursor.getDouble(3)
                    if (lat == 0.0 && lon == 0.0) continue

                    val timestamp = cursor.getLong(4)
                    val level = cursor.getInt(5)
                    val accuracy = cursor.getFloat(6)
                    val reliable = cursor.getInt(7) == 1
                    val count = cursor.getInt(8).coerceAtLeast(1)
                    val weightSum = cursor.getDouble(9)

                    val agg = aggregates.getOrPut(mac) {
                        PersonalAggregate(
                            wifiName = cursor.getString(0).orEmpty(),
                            firstSeen = timestamp
                        )
                    }
                    val w = if (weightSum > 0) weightSum else count.toDouble()
                    agg.latSum += lat * w
                    agg.lonSum += lon * w
                    agg.weightSum += w
                    agg.measureCount += count
                    agg.lastSeen = maxOf(agg.lastSeen, timestamp)
                    agg.firstSeen = if (agg.firstSeen == 0L) timestamp else minOf(agg.firstSeen, timestamp)
                    agg.bestLevel = if (agg.bestLevel == Int.MIN_VALUE) level else maxOf(agg.bestLevel, level)
                    if (accuracy > 0f && (agg.bestAccuracy <= 0f || accuracy < agg.bestAccuracy)) {
                        agg.bestAccuracy = accuracy
                    }
                    agg.reliable = agg.reliable || reliable
                    if (agg.wifiName.isBlank()) agg.wifiName = cursor.getString(0).orEmpty()
                }
            }

            db.transaction {
                aggregates.forEach { (mac, agg) ->
                    val lat = if (agg.weightSum > 0) agg.latSum / agg.weightSum else 0.0
                    val lon = if (agg.weightSum > 0) agg.lonSum / agg.weightSum else 0.0
                    db.execSQL(
                        "INSERT INTO $newTable ($COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, " +
                                "$COLUMN_LONGITUDE, $COLUMN_PERSONAL_TIMESTAMP, $COLUMN_PERSONAL_LEVEL, " +
                                "$COLUMN_PERSONAL_ACCURACY, $COLUMN_PERSONAL_RELIABLE, $COLUMN_PERSONAL_COUNT, " +
                                "$COLUMN_PERSONAL_WEIGHT_SUM, $COLUMN_PERSONAL_FIRST_SEEN, $COLUMN_QUADKEY) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            agg.wifiName, mac, lat, lon, agg.lastSeen, agg.bestLevel,
                            agg.bestAccuracy, if (agg.reliable) 1 else 0, agg.measureCount,
                            agg.weightSum, agg.firstSeen,
                            QuadkeyUtils.latLonToQuadkey(lat, lon)
                        )
                    )
                }

                db.execSQL("DROP TABLE $TABLE_PERSONAL_MAP")
                db.execSQL("ALTER TABLE $newTable RENAME TO $TABLE_PERSONAL_MAP")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_mac ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_quadkey ON $TABLE_PERSONAL_MAP ($COLUMN_QUADKEY)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_coords ON $TABLE_PERSONAL_MAP ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_personal_map_time ON $TABLE_PERSONAL_MAP ($COLUMN_PERSONAL_TIMESTAMP)")
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Version 8 personal map migration failed", e)
        }
    }

    private class PersonalAggregate(
        var wifiName: String,
        var firstSeen: Long
    ) {
        var latSum: Double = 0.0
        var lonSum: Double = 0.0
        var weightSum: Double = 0.0
        var measureCount: Int = 0
        var lastSeen: Long = 0L
        var bestLevel: Int = Int.MIN_VALUE
        var bestAccuracy: Float = 0f
        var reliable: Boolean = false
    }

    private fun normalizeMac(mac: String): String =
        MacAddressUtils.formatToColonSeparated(mac) ?: mac.trim().uppercase()

    fun syncLocationsFromPersonalMap(personalDbPath: String): Int {
        val db = writableDatabase
        var updatedCount = 0
        try {
            db.execSQL("ATTACH DATABASE ? AS personaldb", arrayOf(personalDbPath))
            db.transaction {

                val matchMac = "REPLACE(REPLACE(UPPER(p.macaddress), ':', ''), '-', '') = " +
                        "REPLACE(REPLACE(UPPER($TABLE_NAME.$COLUMN_MAC_ADDRESS), ':', ''), '-', '')"
                val reliable = "p.latitude != 0 AND p.longitude != 0 AND p.is_reliable = 1"
                val differs = "(COALESCE(p.latitude, 0.0) != COALESCE($TABLE_NAME.$COLUMN_LATITUDE, 0.0) " +
                        "OR COALESCE(p.longitude, 0.0) != COALESCE($TABLE_NAME.$COLUMN_LONGITUDE, 0.0))"

                val newestRow = "SELECT p2.rowid FROM personaldb.$TABLE_PERSONAL_MAP p2 " +
                        "WHERE p2.wifiname = $TABLE_NAME.$COLUMN_WIFI_NAME " +
                        "AND REPLACE(REPLACE(UPPER(p2.macaddress), ':', ''), '-', '') = " +
                        "REPLACE(REPLACE(UPPER($TABLE_NAME.$COLUMN_MAC_ADDRESS), ':', ''), '-', '') " +
                        "AND p2.latitude != 0 AND p2.longitude != 0 AND p2.is_reliable = 1 " +
                        "ORDER BY p2.timestamp DESC LIMIT 1"
                val query = """
                    UPDATE $TABLE_NAME
                    SET $COLUMN_LATITUDE = (SELECT p.latitude FROM personaldb.$TABLE_PERSONAL_MAP p WHERE p.rowid = ($newestRow)),
                        $COLUMN_LONGITUDE = (SELECT p.longitude FROM personaldb.$TABLE_PERSONAL_MAP p WHERE p.rowid = ($newestRow)),
                        $COLUMN_QUADKEY = (SELECT p.quadkey FROM personaldb.$TABLE_PERSONAL_MAP p WHERE p.rowid = ($newestRow))
                    WHERE EXISTS (SELECT 1 FROM personaldb.$TABLE_PERSONAL_MAP p WHERE p.wifiname = $TABLE_NAME.$COLUMN_WIFI_NAME AND $matchMac AND $reliable AND $differs)
                """.trimIndent()
                db.execSQL(query)
                db.rawQuery("SELECT changes()", null).use { cursor ->
                    if (cursor.moveToFirst()) updatedCount = cursor.getInt(0)
                }
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Failed to sync locations from personal DB", e)
        } finally {
            try {
                db.execSQL("DETACH DATABASE personaldb")
            } catch (_: Exception) {
            }
        }
        return updatedCount
    }

    fun findKnownLocations(normalizedMacs: Set<String>): Map<String, Pair<Double, Double>> {
        if (normalizedMacs.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Pair<Double, Double>>()
        normalizedMacs.toList().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT UPPER($COLUMN_MAC_ADDRESS), $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_NAME WHERE UPPER($COLUMN_MAC_ADDRESS) IN ($placeholders) AND $COLUMN_LATITUDE != 0"
            readableDatabase.rawQuery(query, chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getString(0)] = Pair(cursor.getDouble(1), cursor.getDouble(2))
                }
            }
        }
        return result
    }

    fun filterExistingMacDecimals(decimals: List<Long>): Set<Long> {
        if (decimals.isEmpty()) return emptySet()
        val result = mutableSetOf<Long>()
        decimals.distinct().chunked(400).forEach { chunk ->
            val conditions = chunk.joinToString(" OR ") {
                "REPLACE(REPLACE(UPPER($COLUMN_MAC_ADDRESS), ':', ''), '-', '') = ?"
            }
            val args = chunk.map { String.format("%012X", it) }.toTypedArray()
            readableDatabase.rawQuery(
                "SELECT REPLACE(REPLACE(UPPER($COLUMN_MAC_ADDRESS), ':', ''), '-', '') FROM $TABLE_NAME WHERE $conditions",
                args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.toLongOrNull(16)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    fun syncLocationsFromExternalWifiLoc(externalDbPath: String): Int {
        val db = writableDatabase
        var updatedCount = 0

        try {
            val externalDb = SQLiteDatabase.openDatabase(externalDbPath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = externalDb.query("access_points", arrayOf("ssid", "bssid", "latitude", "longitude"), null, null, null, null, null)

            db.transaction {
                while (cursor.moveToNext()) {
                    val ssid = cursor.getString(0)
                    val bssid = cursor.getString(1)
                    val lat = cursor.getDouble(2)
                    val lon = cursor.getDouble(3)
                    val quadkey = QuadkeyUtils.latLonToQuadkey(lat, lon)

                    val values = ContentValues().apply {
                        put(COLUMN_LATITUDE, lat)
                        put(COLUMN_LONGITUDE, lon)
                        put(COLUMN_QUADKEY, quadkey)
                    }

                    val affected = db.update(
                        TABLE_NAME,
                        values,
                        "$COLUMN_WIFI_NAME = ? AND $COLUMN_MAC_ADDRESS = ?",
                        arrayOf(ssid, bssid)
                    )
                    updatedCount += affected
                }
            }
            cursor.close()
            externalDb.close()
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error during direct sync from external DB", e)
            throw e
        }

        return updatedCount
    }

    private fun hasIndex(indexName: String): Boolean {
        return try {
            readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(indexName)
            ).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (e: Exception) {
            Log.w("LocalAppDbHelper", "Error checking index $indexName: ${e.message}")
            false
        }
    }

    private fun safeHasIndex(indexName: String): Boolean {
        return try {
            readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(indexName)
            ).use { cursor ->
                val hasIndex = cursor.moveToFirst()
                Log.d("LocalAppDbHelper", "Index check: $indexName = $hasIndex")
                hasIndex
            }
        } catch (e: Exception) {
            Log.w("LocalAppDbHelper", "Error checking index $indexName: ${e.message}")
            false
        }
    }

    fun getIndexLevel(): String {
        return try {
            val hasPasswordIndex = safeHasIndex("idx_wifi_network_password")
            val hasWpsIndex = safeHasIndex("idx_wifi_network_wps")
            val hasMacIndex = safeHasIndex("idx_wifi_network_mac")
            val hasNameIndex = safeHasIndex("idx_wifi_network_name")

            Log.d(
                "LocalAppDbHelper",
                "Index status - name: $hasNameIndex, mac: $hasMacIndex, password: $hasPasswordIndex, wps: $hasWpsIndex"
            )

            when {
                hasPasswordIndex && hasWpsIndex && hasMacIndex && hasNameIndex -> "FULL"
                hasMacIndex && hasNameIndex -> "BASIC"
                else -> "NONE"
            }
        } catch (e: Exception) {
            Log.w("LocalAppDbHelper", "Error determining index level: ${e.message}")
            "NONE"
        }
    }

    fun importRecordsWithStats(records: List<WifiNetwork>, importType: String): ImportStats {
        var inserted = 0
        var duplicates = 0

        writableDatabase.transaction {
            records.forEach { record ->
                val existing = readableDatabase.query(
                    TABLE_NAME,
                    arrayOf(COLUMN_ID),
                    "$COLUMN_WIFI_NAME = ? AND $COLUMN_MAC_ADDRESS = ?",
                    arrayOf(record.wifiName, record.macAddress),
                    null, null, null
                ).use { it.count > 0 }

                if (existing) {
                    duplicates++
                } else {
                    val values = ContentValues().apply {
                        put(COLUMN_WIFI_NAME, record.wifiName)
                        put(COLUMN_MAC_ADDRESS, record.macAddress)
                        put(COLUMN_WIFI_PASSWORD, record.wifiPassword)
                        put(COLUMN_WPS_CODE, record.wpsCode)
                        put(COLUMN_ADMIN_PANEL, record.adminPanel)
                        put(COLUMN_LATITUDE, record.latitude)
                        put(COLUMN_LONGITUDE, record.longitude)
                        put(COLUMN_QUADKEY, computeQuadkey(record.latitude, record.longitude))
                    }
                    if (insert(TABLE_NAME, null, values) != -1L) {
                        inserted++
                    }
                }
            }
        }

        return ImportStats(records.size, inserted, duplicates)
    }

    fun getPointsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = Int.MAX_VALUE
    ): List<WifiNetwork> {
        val limitClause = if (limit != Int.MAX_VALUE) " LIMIT $limit" else ""

        val query = "SELECT * FROM $TABLE_NAME " +
                "WHERE $COLUMN_LATITUDE BETWEEN ? AND ? " +
                "AND $COLUMN_LONGITUDE BETWEEN ? AND ?$limitClause"

        return readableDatabase.rawQuery(
            query,
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
        ).use { cursor ->
            val networks = mutableListOf<WifiNetwork>()
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)
            val macIdx = cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)
            val passwordIdx = cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)
            val wpsIdx = cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)
            val adminIdx = cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)

            var count = 0
            while (cursor.moveToNext() && count < limit) {
                networks.add(
                    WifiNetwork(
                        id = cursor.getLong(idIdx),
                        wifiName = cursor.getString(nameIdx) ?: "",
                        macAddress = cursor.getString(macIdx) ?: "",
                        wifiPassword = cursor.getString(passwordIdx),
                        wpsCode = cursor.getString(wpsIdx),
                        adminPanel = cursor.getString(adminIdx),
                        latitude = cursor.getDouble(latIdx),
                        longitude = cursor.getDouble(lonIdx)
                    )
                )
                count++
            }
            networks
        }
    }

    fun getAllRecords(): List<WifiNetwork> {
        val records = mutableListOf<WifiNetwork>()
        readableDatabase.query(TABLE_NAME, null, null, null, null, null, null).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)
            val macIdx = cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)
            val passwordIdx = cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)
            val wpsIdx = cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)
            val adminIdx = cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)
            val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)
            val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)

            while (cursor.moveToNext()) {
                records.add(
                    WifiNetwork(
                        id = cursor.getLong(idIdx),
                        wifiName = cursor.getString(nameIdx) ?: "",
                        macAddress = cursor.getString(macIdx) ?: "",
                        wifiPassword = cursor.getString(passwordIdx),
                        wpsCode = cursor.getString(wpsIdx),
                        adminPanel = cursor.getString(adminIdx),
                        latitude = cursor.getDouble(latIdx),
                        longitude = cursor.getDouble(lonIdx)
                    )
                )
            }
        }
        return records
    }

    fun searchRecordsOptimized(
        query: String,
        searchFields: Set<String>,
        limit: Int = 100
    ): List<WifiNetwork> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if ("name" in searchFields) {
            conditions.add("$COLUMN_WIFI_NAME LIKE ?")
            args.add("%$query%")
        }

        if ("mac" in searchFields) {
            conditions.add("$COLUMN_MAC_ADDRESS LIKE ?")
            args.add("%$query%")
        }

        if ("password" in searchFields) {
            conditions.add("$COLUMN_WIFI_PASSWORD LIKE ?")
            args.add("%$query%")
        }

        if ("wps" in searchFields) {
            conditions.add("$COLUMN_WPS_CODE LIKE ?")
            args.add("%$query%")
        }

        if (conditions.isEmpty()) {
            return emptyList()
        }

        val whereClause = conditions.joinToString(" OR ")
        val sql = "SELECT * FROM $TABLE_NAME WHERE $whereClause LIMIT $limit"

        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val results = mutableListOf<WifiNetwork>()
            while (cursor.moveToNext()) {
                results.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)) ?: "",
                        macAddress = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_MAC_ADDRESS
                            )
                        ) ?: "",
                        wifiPassword = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_WIFI_PASSWORD
                            )
                        ),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_ADMIN_PANEL
                            )
                        ),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
            results
        }
    }

    fun importRecords(records: List<WifiNetwork>) {
        writableDatabase.transaction {
            records.forEach { record ->
                val values = ContentValues().apply {
                    put(COLUMN_WIFI_NAME, record.wifiName)
                    put(COLUMN_MAC_ADDRESS, record.macAddress)
                    put(COLUMN_WIFI_PASSWORD, record.wifiPassword)
                    put(COLUMN_WPS_CODE, record.wpsCode)
                    put(COLUMN_ADMIN_PANEL, record.adminPanel)
                    put(COLUMN_LATITUDE, record.latitude)
                    put(COLUMN_LONGITUDE, record.longitude)
                    put(COLUMN_QUADKEY, computeQuadkey(record.latitude, record.longitude))
                }
                insert(TABLE_NAME, null, values)
            }
        }
    }

    private fun computeQuadkey(latitude: Double?, longitude: Double?): Long? {
        return if (latitude != null && longitude != null && latitude != 0.0 && longitude != 0.0) {
            QuadkeyUtils.latLonToQuadkey(latitude, longitude)
        } else {
            null
        }
    }

    fun searchRecordsByEssids(essids: List<String>): List<WifiNetwork> {
        val results = mutableListOf<WifiNetwork>()
        val validEssids = essids.filter { it.isNotBlank() }
        if (validEssids.isEmpty()) return results

        val chunkedEssids = validEssids.chunked(500)

        chunkedEssids.forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME IN ($placeholders)"

            readableDatabase.rawQuery(query, chunk.toTypedArray()).use { cursor ->
                results.addAll(buildWifiNetworkList(cursor))
            }
        }

        return results
    }

    fun searchRecordsWithFilters(
        query: String,
        filterByName: Boolean,
        filterByMac: Boolean,
        filterByPassword: Boolean,
        filterByWps: Boolean
    ): List<WifiNetwork> {
        val results = mutableListOf<WifiNetwork>()
        val searchQuery = "%$query%"

        val whereClauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (filterByName) {
            whereClauses.add("$COLUMN_WIFI_NAME LIKE ?")
            selectionArgs.add(searchQuery)
        }
        if (filterByMac) {
            val cleanMac = query.replace(Regex("[^a-fA-F0-9:]"), "").uppercase()
            whereClauses.add(
                "UPPER($COLUMN_MAC_ADDRESS) LIKE ? OR " +
                        "REPLACE(REPLACE(UPPER($COLUMN_MAC_ADDRESS), ':', ''), '-', '') LIKE ?"
            )
            selectionArgs.add("%$cleanMac%")
            selectionArgs.add("%$cleanMac%")
        }
        if (filterByPassword) {
            whereClauses.add("$COLUMN_WIFI_PASSWORD LIKE ?")
            selectionArgs.add(searchQuery)
        }
        if (filterByWps) {
            whereClauses.add("$COLUMN_WPS_CODE LIKE ?")
            selectionArgs.add(searchQuery)
        }

        if (whereClauses.isEmpty()) {
            return results
        }

        val selection = whereClauses.joinToString(" OR ")

        readableDatabase.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs.toTypedArray(),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)) ?: "",
                        macAddress = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_MAC_ADDRESS
                            )
                        ) ?: "",
                        wifiPassword = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_WIFI_PASSWORD
                            )
                        ),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_ADMIN_PANEL
                            )
                        ),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
        }

        return results
    }

    fun clearDatabase() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    fun getRecords(lastId: Long, limit: Int): List<WifiNetwork> {
        val records = mutableListOf<WifiNetwork>()
        readableDatabase.query(
            TABLE_NAME,
            null,
            "$COLUMN_ID > ?",
            arrayOf(lastId.toString()),
            null,
            null,
            "$COLUMN_ID ASC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)) ?: "",
                        macAddress = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_MAC_ADDRESS
                            )
                        ) ?: "",
                        wifiPassword = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_WIFI_PASSWORD
                            )
                        ),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(
                            cursor.getColumnIndexOrThrow(
                                COLUMN_ADMIN_PANEL
                            )
                        ),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
        }
        return records
    }

    fun deleteRecord(id: Long) {
        writableDatabase.delete(TABLE_NAME, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    fun addRecord(wifiNetwork: WifiNetwork): Long {
        val values = ContentValues().apply {
            put(COLUMN_WIFI_NAME, wifiNetwork.wifiName)
            put(COLUMN_MAC_ADDRESS, wifiNetwork.macAddress)
            put(COLUMN_WIFI_PASSWORD, wifiNetwork.wifiPassword)
            put(COLUMN_WPS_CODE, wifiNetwork.wpsCode)
            put(COLUMN_ADMIN_PANEL, wifiNetwork.adminPanel)
            put(COLUMN_LATITUDE, wifiNetwork.latitude)
            put(COLUMN_LONGITUDE, wifiNetwork.longitude)
            put(COLUMN_QUADKEY, computeQuadkey(wifiNetwork.latitude, wifiNetwork.longitude))
        }
        return writableDatabase.insert(TABLE_NAME, null, values)
    }

    fun updateRecord(wifiNetwork: WifiNetwork) {
        val values = ContentValues().apply {
            put(COLUMN_WIFI_NAME, wifiNetwork.wifiName)
            put(COLUMN_MAC_ADDRESS, wifiNetwork.macAddress)
            put(COLUMN_WIFI_PASSWORD, wifiNetwork.wifiPassword)
            put(COLUMN_WPS_CODE, wifiNetwork.wpsCode)
            put(COLUMN_ADMIN_PANEL, wifiNetwork.adminPanel)
            put(COLUMN_LATITUDE, wifiNetwork.latitude)
            put(COLUMN_LONGITUDE, wifiNetwork.longitude)
            put(COLUMN_QUADKEY, computeQuadkey(wifiNetwork.latitude, wifiNetwork.longitude))
        }
        writableDatabase.update(
            TABLE_NAME,
            values,
            "$COLUMN_ID = ?",
            arrayOf(wifiNetwork.id.toString())
        )
    }

    fun getRecordsCount(): Int {
        return readableDatabase.query(TABLE_NAME, arrayOf("COUNT(*)"), null, null, null, null, null)
            .use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
    }

    fun searchRecordsWithFiltersOptimized(
        query: String,
        filterByName: Boolean,
        filterByMac: Boolean,
        filterByPassword: Boolean,
        filterByWps: Boolean
    ): List<WifiNetwork> {
        val allResults = mutableSetOf<WifiNetwork>()

        if (filterByName) {
            allResults.addAll(searchByName(query))
        }

        if (filterByMac) {
            allResults.addAll(searchByMacAllFormats(query))
        }

        if (filterByPassword) {
            allResults.addAll(searchByPassword(query))
        }

        if (filterByWps) {
            allResults.addAll(searchByWps(query))
        }

        return allResults.distinctBy { "${it.macAddress}-${it.wifiName}" }
    }

    private fun searchByName(query: String): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME LIKE ?"

        return readableDatabase.rawQuery(sql, arrayOf("%$query%")).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun searchByMacAllFormats(query: String): List<WifiNetwork> {
        val macFormats = generateAllMacFormats(query)
        val results = mutableListOf<WifiNetwork>()

        macFormats.forEach { format ->
            val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_MAC_ADDRESS = ?"

            readableDatabase.rawQuery(sql, arrayOf(format)).use { cursor ->
                results.addAll(buildWifiNetworkList(cursor))
            }
        }

        val fallbackSql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_MAC_ADDRESS LIKE ?"
        if (query.isNotBlank()) {
            readableDatabase.rawQuery(fallbackSql, arrayOf("%$query%")).use { cursor ->
                results.addAll(buildWifiNetworkList(cursor))
            }
        }

        return results.distinctBy { it.macAddress }
    }

    private fun searchByPassword(query: String): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_PASSWORD LIKE ?"

        return readableDatabase.rawQuery(sql, arrayOf("%$query%")).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun searchByWps(query: String): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WPS_CODE = ?"

        return readableDatabase.rawQuery(sql, arrayOf(query)).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun generateAllMacFormats(input: String): List<String> {
        val cleanInput = input.replace("[^a-fA-F0-9]".toRegex(), "").uppercase()
        val formats = mutableSetOf<String>()

        formats.add(input.trim())

        if (cleanInput.isNotEmpty()) {
            formats.add(cleanInput)
            formats.add(cleanInput.lowercase())

            if (cleanInput.length == 12) {
                formats.add(cleanInput.replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(cleanInput.replace("(.{2})".toRegex(), "$1-").dropLast(1))
                formats.add(cleanInput.lowercase().replace("(.{2})".toRegex(), "$1:").dropLast(1))
                formats.add(cleanInput.lowercase().replace("(.{2})".toRegex(), "$1-").dropLast(1))

                try {
                    val decimal = cleanInput.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {

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

            }
        }

        return formats.filter { it.isNotEmpty() }.distinct()
    }

    private fun buildWifiNetworkList(cursor: Cursor): List<WifiNetwork> {
        return buildList {
            val idIdx = cursor.getColumnIndex(COLUMN_ID)
            val nameIdx = cursor.getColumnIndex(COLUMN_WIFI_NAME)
            val macIdx = cursor.getColumnIndex(COLUMN_MAC_ADDRESS)
            val passIdx = cursor.getColumnIndex(COLUMN_WIFI_PASSWORD)
            val wpsIdx = cursor.getColumnIndex(COLUMN_WPS_CODE)
            val adminIdx = cursor.getColumnIndex(COLUMN_ADMIN_PANEL)
            val latIdx = cursor.getColumnIndex(COLUMN_LATITUDE)
            val lonIdx = cursor.getColumnIndex(COLUMN_LONGITUDE)
            val quadkeyIdx = cursor.getColumnIndex(COLUMN_QUADKEY)

            while (cursor.moveToNext()) {
                add(
                    WifiNetwork(
                        id = if (idIdx >= 0) cursor.getLong(idIdx) else 0,
                        wifiName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else "",
                        macAddress = if (macIdx >= 0) cursor.getString(macIdx) ?: "" else "",
                        wifiPassword = if (passIdx >= 0) cursor.getString(passIdx) else null,
                        wpsCode = if (wpsIdx >= 0) cursor.getString(wpsIdx) else null,
                        adminPanel = if (adminIdx >= 0) cursor.getString(adminIdx) else null,
                        latitude = if (latIdx >= 0) cursor.getDouble(latIdx) else null,
                        longitude = if (lonIdx >= 0) cursor.getDouble(lonIdx) else null,
                        quadkey = if (quadkeyIdx >= 0) cursor.getLong(quadkeyIdx) else null
                    )
                )
            }
        }
    }

    fun searchRecordsWithFiltersPaginated(
        query: String,
        searchFields: Set<String>,
        offset: Int,
        limit: Int,
        searchMode: SearchMode = SearchMode.PREFIX
    ): List<WifiNetwork> {
        debugIndexes()

        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        if ("name" in searchFields) {
            addFieldCondition(conditions, params, COLUMN_WIFI_NAME, query, searchMode)
        }

        if ("mac" in searchFields) {
            addMacCondition(conditions, params, query, searchMode)
        }

        if ("password" in searchFields) {
            addFieldCondition(conditions, params, COLUMN_WIFI_PASSWORD, query, searchMode)
        }

        if ("wps" in searchFields) {
            conditions.add("$COLUMN_WPS_CODE = ?")
            params.add(query)
        }

        if (conditions.isEmpty()) return emptyList()

        val sql =
            "SELECT * FROM $TABLE_NAME WHERE ${conditions.joinToString(" OR ")} ORDER BY rowid LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, params.toTypedArray()).use { cursor ->
            buildWifiNetworkList(cursor)
        }.distinctBy { "${it.macAddress}-${it.wifiName}" }
    }

    private fun addFieldCondition(
        conditions: MutableList<String>,
        params: MutableList<String>,
        column: String,
        query: String,
        searchMode: SearchMode
    ) {
        when (searchMode) {
            SearchMode.EXACT -> {
                conditions.add("$column = ?")
                params.add(query)
            }

            SearchMode.PREFIX -> {
                conditions.add("$column LIKE ?")
                params.add("${query}%")
            }

            SearchMode.SUBSTRING -> {
                val words = query.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (words.size > 1) {
                    conditions.add(words.joinToString(" AND ") { "$column LIKE ?" })
                    words.forEach { params.add("%$it%") }
                } else {
                    conditions.add("$column LIKE ?")
                    params.add("%${query}%")
                }
            }
        }
    }

    private fun addMacCondition(
        conditions: MutableList<String>,
        params: MutableList<String>,
        query: String,
        searchMode: SearchMode
    ) {
        if (searchMode == SearchMode.EXACT) {
            val macFormats = generateAllMacFormats(query)
            macFormats.forEach { format ->
                conditions.add("$COLUMN_MAC_ADDRESS = ?")
                params.add(format)
            }
            conditions.add("$COLUMN_MAC_ADDRESS LIKE ?")
            params.add("%$query%")
        } else {
            val cleanQuery = query.replace("[^a-fA-F0-9:]".toRegex(), "")
            if (cleanQuery.isNotEmpty()) {
                conditions.add("UPPER($COLUMN_MAC_ADDRESS) LIKE ? OR REPLACE(REPLACE(UPPER($COLUMN_MAC_ADDRESS), ':', ''), '-', '') LIKE ?")
                val searchPattern = "%${cleanQuery.uppercase()}%"
                params.add(searchPattern)
                params.add(searchPattern)
            }
        }
    }

    fun debugIndexes() {
        try {
            readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?",
                arrayOf(TABLE_NAME)
            ).use { cursor ->
                Log.d("LocalAppDbHelper", "Available indexes:")
                while (cursor.moveToNext()) {
                    Log.d("LocalAppDbHelper", "  - ${cursor.getString(0)}")
                }
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error getting indexes", e)
        }
    }

    fun searchRecords(query: String): List<WifiNetwork> {
        val results = mutableListOf<WifiNetwork>()
        val searchQuery = "%$query%"
        val decimalMac = convertMacToDecimal(query)

        val selection = if (decimalMac != null) {
            "$COLUMN_WIFI_NAME LIKE ? OR $COLUMN_MAC_ADDRESS LIKE ? OR $COLUMN_MAC_ADDRESS = ?"
        } else {
            "$COLUMN_WIFI_NAME LIKE ? OR $COLUMN_MAC_ADDRESS LIKE ?"
        }
        val selectionArgs = if (decimalMac != null) {
            arrayOf(searchQuery, searchQuery, decimalMac)
        } else {
            arrayOf(searchQuery, searchQuery)
        }

        readableDatabase.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null,
            null,
            null
        ).use { cursor ->
            results.addAll(buildWifiNetworkList(cursor))
        }

        return results
    }

    fun optimizeForBulkInsert() {
        try {
            writableDatabase.apply {
                execSQL("PRAGMA synchronous = OFF")
                execSQL("PRAGMA journal_mode = MEMORY")
                execSQL("PRAGMA cache_size = 50000")
                execSQL("PRAGMA temp_store = MEMORY")
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error optimizing for bulk insert", e)
        }
    }

    fun restoreNormalSettings() {
        try {
            writableDatabase.apply {
                execSQL("PRAGMA synchronous = NORMAL")
                execSQL("PRAGMA journal_mode = WAL")
                execSQL("PRAGMA cache_size = 10000")
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error restoring normal settings", e)
        }
    }

    fun temporaryDropIndexes() {
        try {
            writableDatabase.apply {
                execSQL("DROP INDEX IF EXISTS idx_wifi_network_name")
                execSQL("DROP INDEX IF EXISTS idx_wifi_network_mac")
                execSQL("DROP INDEX IF EXISTS idx_wifi_network_coords")
                execSQL("DROP INDEX IF EXISTS idx_wifi_network_password")
                execSQL("DROP INDEX IF EXISTS idx_wifi_network_wps")
            }
            Log.d("LocalAppDbHelper", "Indexes dropped for bulk insert (quadkey preserved)")
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error dropping indexes", e)
        }
    }

    fun recreateIndexes() {
        try {
            writableDatabase.apply {
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_quadkey ON $TABLE_NAME ($COLUMN_QUADKEY)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON $TABLE_NAME ($COLUMN_WIFI_PASSWORD COLLATE NOCASE)")
                execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON $TABLE_NAME ($COLUMN_WPS_CODE)")
            }
            Log.d("LocalAppDbHelper", "Indexes recreated after bulk insert")
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error recreating indexes", e)
        }
    }

    data class ImportStats(
        val totalProcessed: Int,
        val inserted: Int,
        val duplicates: Int
    )

    fun getAllExistingKeys(): MutableSet<String> {
        val existingKeys = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery(
                "SELECT $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS FROM $TABLE_NAME",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: ""
                    val mac = cursor.getString(1) ?: ""
                    existingKeys.add("$name|$mac")
                }
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error getting existing keys", e)
        }
        return existingKeys
    }

    fun bulkInsertOptimizedWithDuplicateCheck(
        networks: List<WifiNetwork>,
        existingKeys: Set<String>
    ): Pair<Int, Int> {
        var insertedCount = 0
        var duplicateCount = 0

        val uniqueNetworks = networks.filter { network ->
            val key = "${network.wifiName}|${network.macAddress}"
            if (existingKeys.contains(key)) {
                duplicateCount++
                false
            } else {
                true
            }
        }

        if (uniqueNetworks.isNotEmpty()) {
            insertedCount = bulkInsertBatch(uniqueNetworks)
        }

        return Pair(insertedCount, duplicateCount)
    }

    fun bulkInsertBatch(networks: List<WifiNetwork>): Int {
        var inserted = 0
        val chunkSize = CompatibilityHelper.getRecommendedChunkSize()

        try {
            writableDatabase.transaction {
                networks.chunked(chunkSize).forEach { batch ->
                    batch.forEach { network ->
                        try {
                            val values = ContentValues().apply {
                                put(COLUMN_WIFI_NAME, network.wifiName)
                                put(COLUMN_MAC_ADDRESS, network.macAddress)
                                put(COLUMN_WIFI_PASSWORD, network.wifiPassword)
                                put(COLUMN_WPS_CODE, network.wpsCode)
                                put(COLUMN_ADMIN_PANEL, network.adminPanel)
                                put(COLUMN_LATITUDE, network.latitude)
                                put(COLUMN_LONGITUDE, network.longitude)
                                put(
                                    COLUMN_QUADKEY,
                                    computeQuadkey(network.latitude, network.longitude)
                                )
                            }

                            val result = insert(TABLE_NAME, null, values)
                            if (result != -1L) {
                                inserted++
                            }
                        } catch (e: Exception) {
                            Log.e("LocalAppDbHelper", "Error inserting record", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error in bulk insert batch", e)
        }

        return inserted
    }

    fun bulkInsertOptimized(
        networks: List<WifiNetwork>,
        checkDuplicates: Boolean = false
    ): Pair<Int, Int> {
        return if (checkDuplicates) {
            val existingKeys = getAllExistingKeys()
            bulkInsertOptimizedWithDuplicateCheck(networks, existingKeys)
        } else {
            val inserted = bulkInsertBatch(networks)
            Pair(inserted, 0)
        }
    }

    private fun convertMacToDecimal(mac: String): String? {
        return try {
            mac.replace(":", "").replace("-", "").toLong(16).toString()
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun enableIndexing(level: String = "BASIC") {
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_quadkey ON $TABLE_NAME ($COLUMN_QUADKEY)")

        if (level == "FULL") {
            writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON $TABLE_NAME ($COLUMN_WIFI_PASSWORD COLLATE NOCASE)")
            writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON $TABLE_NAME ($COLUMN_WPS_CODE)")
        }
    }

    fun disableIndexing() {
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_name")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_mac")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_coords")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_quadkey")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_password")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_wps")
    }

    fun hasIndexes(): Boolean {
        return readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name IN (?, ?, ?, ?)",
            arrayOf(
                TABLE_NAME,
                "idx_wifi_network_mac",
                "idx_wifi_network_name",
                "idx_wifi_network_coords",
                "idx_wifi_network_quadkey"
            )
        ).use { it.count >= 4 }
    }

    fun optimizeDatabase() {
        writableDatabase.execSQL("VACUUM")
    }

    fun removeDuplicates() {
        writableDatabase.execSQL(
            """
            DELETE FROM $TABLE_NAME
            WHERE $COLUMN_ID NOT IN (
                SELECT MIN($COLUMN_ID)
                FROM $TABLE_NAME
                GROUP BY $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_PASSWORD, $COLUMN_WPS_CODE, $COLUMN_ADMIN_PANEL, $COLUMN_LATITUDE, $COLUMN_LONGITUDE
            )
        """.trimIndent()
        )
    }

    fun restoreDatabaseFromUri(uri: Uri) {
        val currentDbPath = context.getDatabasePath(DATABASE_NAME).absolutePath
        val currentDbFile = File(currentDbPath)
        val backupFile = File("$currentDbPath.bak")

        try {
            close()

            File("$currentDbPath-wal").takeIf { it.exists() }?.delete()
            File("$currentDbPath-shm").takeIf { it.exists() }?.delete()

            if (currentDbFile.exists()) {
                if (backupFile.exists()) backupFile.delete()
                currentDbFile.copyTo(backupFile, overwrite = true)
                currentDbFile.delete()
            }

            val restored = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(currentDbFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                true
            } ?: false

            if (restored) {
                backupFile.delete()
                Log.d("LocalAppDbHelper", "Database restored successfully from $uri")
            } else if (backupFile.exists()) {
                backupFile.copyTo(currentDbFile, overwrite = true)
                backupFile.delete()
                Log.e("LocalAppDbHelper", "Restore failed: input stream is null, rolled back")
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error restoring database: ${e.message}", e)
            if (backupFile.exists()) {
                try {
                    if (currentDbFile.exists()) currentDbFile.delete()
                    backupFile.copyTo(currentDbFile, overwrite = true)
                    backupFile.delete()
                    Log.d("LocalAppDbHelper", "Rolled back to previous database after error")
                } catch (rollback: Exception) {
                    Log.e("LocalAppDbHelper", "Rollback also failed: ${rollback.message}", rollback)
                }
            }
        }
    }

    suspend fun getClusteredPointsByTileRange(
        tileX1: Int,
        tileY1: Int,
        tileX2: Int,
        tileY2: Int,
        zoom: Int,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint> {
        val maxZoom = 23.0
        val isHighZoom = zoom >= maxZoom - 1
        val effectiveScatterMode = scatterMode || isHighZoom
        val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
        val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)

        val latNorth = QuadkeyUtils.tileXYToLat(tileY1, zoom)
        val latSouth = QuadkeyUtils.tileXYToLat(tileY2 + 1, zoom)
        val lonWest = QuadkeyUtils.tileXYToLon(tileX1, zoom)
        val lonEast = QuadkeyUtils.tileXYToLon(tileX2 + 1, zoom)

        val db = readableDatabase
        val points = mutableListOf<ClusteredMapPoint>()

        if (effectiveScatterMode) {
            val scatterLimit = getZoomBasedLimit(zoom.toDouble())
            val query =
                "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_WIFI_NAME FROM $TABLE_NAME WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? LIMIT ?"
            val args = arrayOf(
                latSouth.toString(), latNorth.toString(),
                lonWest.toString(), lonEast.toString(),
                scatterLimit.toString()
            )

            db.rawQuery(query, args)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val macIdx = cursor.getColumnIndex("macaddress")
                    val latIdx = cursor.getColumnIndex("latitude")
                    val lonIdx = cursor.getColumnIndex("longitude")

                    if (macIdx >= 0 && latIdx >= 0 && lonIdx >= 0) {
                        val ssidIdx = cursor.getColumnIndex(COLUMN_WIFI_NAME)
                        do {
                            val macStr = cursor.getString(macIdx)
                            val mac = macToDecimal(macStr) ?: continue
                            val lat = cursor.getDouble(latIdx)
                            val lon = cursor.getDouble(lonIdx)
                            val ssid = if (ssidIdx >= 0) cursor.getString(ssidIdx) else null
                            points.add(ClusteredMapPoint(mac, lat, lon, 1, false, ssid))
                        } while (cursor.moveToNext())
                    }
                }
            }
        } else {
            val divisor = 1L shl mask
            val clusterLimit = getZoomBasedLimit(zoom.toDouble())
            val query =
                "SELECT MIN($COLUMN_MAC_ADDRESS) as BSSID, AVG($COLUMN_LATITUDE) as avg_lat, AVG($COLUMN_LONGITUDE) as avg_lon, COUNT(*) as count, MIN($COLUMN_WIFI_NAME) as ESSID FROM $TABLE_NAME WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? GROUP BY (CAST($COLUMN_QUADKEY / $divisor AS INTEGER)) LIMIT $clusterLimit"
            val args = arrayOf(
                latSouth.toString(), latNorth.toString(),
                lonWest.toString(), lonEast.toString()
            )

            db.rawQuery(query, args)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val bssidIdx = cursor.getColumnIndex("BSSID")
                    val latIdx = cursor.getColumnIndex("avg_lat")
                    val lonIdx = cursor.getColumnIndex("avg_lon")
                    val countIdx = cursor.getColumnIndex("count")
                    val ssidIdx = cursor.getColumnIndex("ESSID")

                    if (bssidIdx >= 0 && latIdx >= 0 && lonIdx >= 0 && countIdx >= 0) {
                        do {
                            val macStr = cursor.getString(bssidIdx)
                            val mac = macToDecimal(macStr) ?: continue
                            val lat = cursor.getDouble(latIdx)
                            val lon = cursor.getDouble(lonIdx)
                            val count = cursor.getInt(countIdx)
                            val ssid = if (ssidIdx >= 0) cursor.getString(ssidIdx) else null
                            points.add(ClusteredMapPoint(mac, lat, lon, count, count > 1, ssid))
                        } while (cursor.moveToNext())
                    }
                }
            }
        }

        return points
    }

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
            else -> 100000
        }
    }

    private fun macToDecimal(mac: String?): Long? {
        if (mac == null || mac.isBlank()) return null
        return try {
            when {
                mac.contains(":") || mac.contains("-") -> mac.replace(":", "").replace("-", "")
                    .toLong(16)

                mac.toLongOrNull() != null -> mac.toLong()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun searchRecordsWithAdvancedQuery(
        advancedQuery: AdvancedSearchQuery,
        offset: Int,
        limit: Int
    ): List<WifiNetwork> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        if (advancedQuery.bssid.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.bssid)) {
                val processedBssid = advancedQuery.convertWildcards(advancedQuery.bssid)
                conditions.add("$COLUMN_MAC_ADDRESS LIKE ?")
                params.add(processedBssid)
            } else {
                val macFormats = generateAllMacFormats(advancedQuery.bssid)
                val macConditions = mutableListOf<String>()
                macFormats.forEach { format ->
                    macConditions.add("$COLUMN_MAC_ADDRESS = ?")
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
                conditions.add("$COLUMN_WIFI_NAME LIKE ? COLLATE BINARY")
            } else {
                conditions.add("LOWER($COLUMN_WIFI_NAME) LIKE LOWER(?)")
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
                conditions.add("$COLUMN_WIFI_PASSWORD LIKE ? COLLATE BINARY")
            } else {
                conditions.add("LOWER($COLUMN_WIFI_PASSWORD) LIKE LOWER(?)")
            }
            params.add(processedPassword)
        }

        if (advancedQuery.wpsPin.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.wpsPin)) {
                val processedWpsPin = advancedQuery.convertWildcards(advancedQuery.wpsPin)
                conditions.add("$COLUMN_WPS_CODE LIKE ?")
                params.add(processedWpsPin)
            } else {
                conditions.add("$COLUMN_WPS_CODE = ?")
                params.add(advancedQuery.wpsPin)
            }
        }

        if (conditions.isEmpty()) return emptyList()

        val sql =
            "SELECT * FROM $TABLE_NAME WHERE ${conditions.joinToString(" AND ")} ORDER BY rowid LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, params.toTypedArray()).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }
}
