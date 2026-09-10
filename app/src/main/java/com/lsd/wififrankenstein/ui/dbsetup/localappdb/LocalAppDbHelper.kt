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
import com.lsd.wififrankenstein.util.QuadkeyUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

class LocalAppDbHelper(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "local_wifi_database.db"
        const val DATABASE_VERSION = 7

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
        const val COLUMN_PERSONAL_TIMESTAMP = "timestamp"
        const val COLUMN_PERSONAL_LEVEL = "level"
        const val COLUMN_PERSONAL_ACCURACY = "accuracy"
        const val COLUMN_PERSONAL_RELIABLE = "is_reliable"
        const val COLUMN_PERSONAL_NOISE = "noise_level"
        const val COLUMN_PERSONAL_SATELLITES = "satellites"
        const val COLUMN_PERSONAL_SPEED = "speed"
        const val COLUMN_PERSONAL_COUNT = "measure_count"
        const val COLUMN_PERSONAL_WEIGHT_SUM = "weight_sum"
        // COLUMN_QUADKEY is reused for both tables
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

        val createPersonalMapSQL = """
            CREATE TABLE $TABLE_PERSONAL_MAP (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WIFI_NAME TEXT,
                $COLUMN_MAC_ADDRESS TEXT,
                $COLUMN_LATITUDE REAL,
                $COLUMN_LONGITUDE REAL,
                $COLUMN_PERSONAL_TIMESTAMP INTEGER,
                $COLUMN_PERSONAL_LEVEL INTEGER,
                $COLUMN_PERSONAL_ACCURACY REAL,
                $COLUMN_PERSONAL_RELIABLE INTEGER,
                $COLUMN_PERSONAL_NOISE REAL,
                $COLUMN_PERSONAL_SATELLITES INTEGER,
                $COLUMN_PERSONAL_SPEED REAL,
                $COLUMN_PERSONAL_COUNT INTEGER DEFAULT 1,
                $COLUMN_PERSONAL_WEIGHT_SUM REAL DEFAULT 0,
                $COLUMN_QUADKEY INTEGER
            )
        """.trimIndent()
        db.execSQL(createPersonalMapSQL)

        db.execSQL("CREATE INDEX idx_wifi_network_quadkey ON $TABLE_NAME ($COLUMN_QUADKEY)")
        db.execSQL("CREATE INDEX idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
        db.execSQL("CREATE INDEX idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
        db.execSQL("CREATE INDEX idx_personal_map_mac ON $TABLE_PERSONAL_MAP ($COLUMN_MAC_ADDRESS)")
        db.execSQL("CREATE INDEX idx_personal_map_quadkey ON $TABLE_PERSONAL_MAP ($COLUMN_QUADKEY)")
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
                    $COLUMN_PERSONAL_RELIABLE INTEGER,
                    $COLUMN_PERSONAL_NOISE REAL,
                    $COLUMN_PERSONAL_SATELLITES INTEGER,
                    $COLUMN_PERSONAL_SPEED REAL
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
                
                // Backfill quadkeys for personal map
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
    }

    // --- Personal Map Methods ---

    private fun calculateRssiWeight(rssi: Int): Double {
        // Логарифмическая модель затухания сигнала (log-distance path loss)
        // RSSI_REF — сигнал на расстоянии 1 м, PATH_LOSS_EXPONENT — коэффициент затухания (2.0-4.0)
        val rssiRef = -45.0
        val n = 2.7
        val distance = 10.0.pow((rssiRef - rssi) / (10.0 * n)).coerceIn(1.0, 500.0)
        return 1.0 / (distance * distance)
    }

    fun addPersonalRecord(network: PersonalWifiNetwork): Long {
        val db = writableDatabase
        val weight = calculateRssiWeight(network.level)
        
        // Берем текущие данные для усреднения
        val query = "SELECT $COLUMN_ID, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_PERSONAL_COUNT, $COLUMN_PERSONAL_LEVEL, $COLUMN_PERSONAL_WEIGHT_SUM FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS = ?"
        db.rawQuery(query, arrayOf(network.macAddress)).use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val oldLat = cursor.getDouble(1)
                val oldLon = cursor.getDouble(2)
                val count = cursor.getInt(3)
                val existingLevel = cursor.getInt(4)
                val oldWeightSum = cursor.getDouble(5)

                val values = createPersonalValues(network)
                
                if (network.isReliable) {
                    val newWeightSum = oldWeightSum + weight
                    // Взвешенное среднее координат: AP тяготеет к точкам с сильным сигналом
                    val newLat = (oldLat * oldWeightSum + network.latitude * weight) / newWeightSum
                    val newLon = (oldLon * oldWeightSum + network.longitude * weight) / newWeightSum
                    
                    values.put(COLUMN_LATITUDE, newLat)
                    values.put(COLUMN_LONGITUDE, newLon)
                    values.put(COLUMN_PERSONAL_COUNT, count + 1)
                    values.put(COLUMN_PERSONAL_WEIGHT_SUM, newWeightSum)
                    
                    // Обновляем quadkey для новой позиции
                    values.put(COLUMN_QUADKEY, QuadkeyUtils.latLonToQuadkey(newLat, newLon))
                } else {
                    // Если GPS плохой, координаты не трогаем
                    values.put(COLUMN_LATITUDE, oldLat)
                    values.put(COLUMN_LONGITUDE, oldLon)
                    values.put(COLUMN_PERSONAL_COUNT, count)
                    values.put(COLUMN_PERSONAL_WEIGHT_SUM, oldWeightSum)
                }
                
                // Сохраняем лучший уровень сигнала для маркера (отрицательное значение, -40 > -70)
                if (network.level > existingLevel) {
                     values.put(COLUMN_PERSONAL_LEVEL, network.level)
                } else {
                     values.put(COLUMN_PERSONAL_LEVEL, existingLevel)
                }

                db.update(TABLE_PERSONAL_MAP, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
                return id
            }
        }
        
        // Если новая точка, создаем запись с начальным весом
        val initialValues = createPersonalValues(network)
        if (network.isReliable) {
            initialValues.put(COLUMN_PERSONAL_WEIGHT_SUM, weight)
        }
        return db.insert(TABLE_PERSONAL_MAP, null, initialValues)
    }

    fun findReliableLocation(scans: List<Pair<String, Int>>): Pair<Double, Double>? {
        if (scans.isEmpty()) return null
        val bssids = scans.map { it.first }
        
        val db = readableDatabase
        val placeholders = bssids.joinToString(",") { "?" }
        
        // Ищем известные надежные точки из персональной карты
        val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS IN ($placeholders) AND $COLUMN_PERSONAL_RELIABLE = 1"
        
        val knownAps = mutableMapOf<String, Pair<Double, Double>>()
        db.rawQuery(query, bssids.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                knownAps[cursor.getString(0)] = Pair(cursor.getDouble(1), cursor.getDouble(2))
            }
        }
        
        // Если в персональной не густо, добавляем из общей базы
        if (knownAps.size < 3) {
            val queryMain = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_NAME WHERE $COLUMN_MAC_ADDRESS IN ($placeholders) AND $COLUMN_LATITUDE != 0"
            db.rawQuery(queryMain, bssids.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val mac = cursor.getString(0)
                    if (!knownAps.containsKey(mac)) {
                        knownAps[mac] = Pair(cursor.getDouble(1), cursor.getDouble(2))
                    }
                }
            }
        }

        if (knownAps.isEmpty()) return null

        // Взвешенный центроид (Weighted Centroid Localization)
        var sumWeightLat = 0.0
        var sumWeightLon = 0.0
        var sumWeight = 0.0

        for (scan in scans) {
            val coords = knownAps[scan.first] ?: continue
            val weight = calculateRssiWeight(scan.second)
            sumWeightLat += coords.first * weight
            sumWeightLon += coords.second * weight
            sumWeight += weight
        }

        return if (sumWeight > 0) {
            Pair(sumWeightLat / sumWeight, sumWeightLon / sumWeight)
        } else null
    }

    fun updatePersonalNetworkInfo(id: Long, newName: String, newPassword: String?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_WIFI_NAME, newName)
            if (newPassword != null) {
                put(COLUMN_WIFI_PASSWORD, newPassword)
            }
        }
        db.update(TABLE_PERSONAL_MAP, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    private fun createPersonalValues(network: PersonalWifiNetwork): ContentValues {
        return ContentValues().apply {
            put(COLUMN_WIFI_NAME, network.wifiName)
            put(COLUMN_MAC_ADDRESS, network.macAddress)
            put(COLUMN_LATITUDE, network.latitude)
            put(COLUMN_LONGITUDE, network.longitude)
            put(COLUMN_PERSONAL_TIMESTAMP, network.timestamp)
            put(COLUMN_PERSONAL_LEVEL, network.level)
            put(COLUMN_PERSONAL_ACCURACY, network.accuracy)
            put(COLUMN_PERSONAL_RELIABLE, if (network.isReliable) 1 else 0)
            put(COLUMN_PERSONAL_NOISE, network.noiseLevel)
            put(COLUMN_PERSONAL_SATELLITES, network.satellites)
            put(COLUMN_PERSONAL_SPEED, network.speed)
            put(COLUMN_PERSONAL_COUNT, 1)
            put(COLUMN_QUADKEY, computeQuadkey(network.latitude, network.longitude))
        }
    }

    fun getPersonalPointsInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int = 1000
    ): List<PersonalWifiNetwork> {
        val query = "SELECT * FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE BETWEEN ? AND ? AND $COLUMN_LONGITUDE BETWEEN ? AND ? LIMIT ?"
        return readableDatabase.rawQuery(query, arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString(), limit.toString()))
            .use { cursor ->
                val list = mutableListOf<PersonalWifiNetwork>()
                val idIdx = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)
                val macIdx = cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)
                val latIdx = cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)
                val lonIdx = cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE)
                val timeIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_TIMESTAMP)
                val levelIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_LEVEL)
                val accIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_ACCURACY)
                val relIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_RELIABLE)
                val noiseIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_NOISE)
                val satIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_SATELLITES)
                val speedIdx = cursor.getColumnIndexOrThrow(COLUMN_PERSONAL_SPEED)

                while (cursor.moveToNext()) {
                    list.add(PersonalWifiNetwork(
                        id = cursor.getLong(idIdx),
                        wifiName = cursor.getString(nameIdx) ?: "",
                        macAddress = cursor.getString(macIdx) ?: "",
                        latitude = cursor.getDouble(latIdx),
                        longitude = cursor.getDouble(lonIdx),
                        timestamp = cursor.getLong(timeIdx),
                        level = cursor.getInt(levelIdx),
                        accuracy = cursor.getFloat(accIdx),
                        isReliable = cursor.getInt(relIdx) == 1,
                        noiseLevel = cursor.getFloat(noiseIdx),
                        satellites = cursor.getInt(satIdx),
                        speed = cursor.getFloat(speedIdx)
                    ))
                }
                list
            }
    }

    suspend fun getClusteredPersonalPointsByTileRange(
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
            val query = "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE, $COLUMN_WIFI_NAME FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? LIMIT 5000"
            db.rawQuery(query, arrayOf(latSouth.toString(), latNorth.toString(), lonWest.toString(), lonEast.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val macStr = cursor.getString(0)
                    val mac = macToDecimal(macStr) ?: continue
                    points.add(ClusteredMapPoint(mac, cursor.getDouble(1), cursor.getDouble(2), 1, false, cursor.getString(3)))
                }
            }
        } else {
            val divisor = 1L shl mask
            val query = "SELECT MIN($COLUMN_MAC_ADDRESS), AVG($COLUMN_LATITUDE), AVG($COLUMN_LONGITUDE), COUNT(*), MIN($COLUMN_WIFI_NAME) FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? GROUP BY (CAST($COLUMN_QUADKEY / $divisor AS INTEGER)) LIMIT 2000"
            db.rawQuery(query, arrayOf(latSouth.toString(), latNorth.toString(), lonWest.toString(), lonEast.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val macStr = cursor.getString(0)
                    val mac = macToDecimal(macStr) ?: continue
                    val count = cursor.getInt(3)
                    points.add(ClusteredMapPoint(mac, cursor.getDouble(1), cursor.getDouble(2), count, count > 1, cursor.getString(4)))
                }
            }
        }
        return points
    }

    fun getPersonalRecords(): List<PersonalWifiNetwork> {
        val records = mutableListOf<PersonalWifiNetwork>()
        readableDatabase.query(TABLE_PERSONAL_MAP, null, null, null, null, null, "$COLUMN_PERSONAL_TIMESTAMP DESC").use { cursor ->
            records.addAll(buildPersonalList(cursor))
        }
        return records
    }

    fun searchPersonalRecordsByBssid(bssid: String): List<PersonalWifiNetwork> {
        val query = "SELECT * FROM $TABLE_PERSONAL_MAP WHERE $COLUMN_MAC_ADDRESS = ?"
        return readableDatabase.rawQuery(query, arrayOf(bssid)).use { cursor ->
            buildPersonalList(cursor)
        }
    }

    private fun buildPersonalList(cursor: Cursor): List<PersonalWifiNetwork> {
        val list = mutableListOf<PersonalWifiNetwork>()
        val idIdx = cursor.getColumnIndex(COLUMN_ID)
        val nameIdx = cursor.getColumnIndex(COLUMN_WIFI_NAME)
        val macIdx = cursor.getColumnIndex(COLUMN_MAC_ADDRESS)
        val latIdx = cursor.getColumnIndex(COLUMN_LATITUDE)
        val lonIdx = cursor.getColumnIndex(COLUMN_LONGITUDE)
        val timeIdx = cursor.getColumnIndex(COLUMN_PERSONAL_TIMESTAMP)
        val levelIdx = cursor.getColumnIndex(COLUMN_PERSONAL_LEVEL)
        val accIdx = cursor.getColumnIndex(COLUMN_PERSONAL_ACCURACY)
        val relIdx = cursor.getColumnIndex(COLUMN_PERSONAL_RELIABLE)
        val noiseIdx = cursor.getColumnIndex(COLUMN_PERSONAL_NOISE)
        val satIdx = cursor.getColumnIndex(COLUMN_PERSONAL_SATELLITES)
        val speedIdx = cursor.getColumnIndex(COLUMN_PERSONAL_SPEED)

        while (cursor.moveToNext()) {
            list.add(PersonalWifiNetwork(
                id = if (idIdx >= 0) cursor.getLong(idIdx) else 0,
                wifiName = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else "",
                macAddress = if (macIdx >= 0) cursor.getString(macIdx) ?: "" else "",
                latitude = if (latIdx >= 0) cursor.getDouble(latIdx) else 0.0,
                longitude = if (lonIdx >= 0) cursor.getDouble(lonIdx) else 0.0,
                timestamp = if (timeIdx >= 0) cursor.getLong(timeIdx) else 0L,
                level = if (levelIdx >= 0) cursor.getInt(levelIdx) else -100,
                accuracy = if (accIdx >= 0) cursor.getFloat(accIdx) else 0f,
                isReliable = if (relIdx >= 0) cursor.getInt(relIdx) == 1 else false,
                noiseLevel = if (noiseIdx >= 0) cursor.getFloat(noiseIdx) else 0f,
                satellites = if (satIdx >= 0) cursor.getInt(satIdx) else 0,
                speed = if (speedIdx >= 0) cursor.getFloat(speedIdx) else 0f
            ))
        }
        return list
    }

    fun clearPersonalMap() {
        writableDatabase.delete(TABLE_PERSONAL_MAP, null, null)
    }

    fun syncLocationsFromPersonalMap(): Int {
        val db = writableDatabase
        var updatedCount = 0
        db.transaction {
            val query = """
                UPDATE $TABLE_NAME 
                SET $COLUMN_LATITUDE = (SELECT $COLUMN_LATITUDE FROM $TABLE_PERSONAL_MAP p WHERE p.$COLUMN_WIFI_NAME = $TABLE_NAME.$COLUMN_WIFI_NAME AND p.$COLUMN_MAC_ADDRESS = $TABLE_NAME.$COLUMN_MAC_ADDRESS LIMIT 1),
                    $COLUMN_LONGITUDE = (SELECT $COLUMN_LONGITUDE FROM $TABLE_PERSONAL_MAP p WHERE p.$COLUMN_WIFI_NAME = $TABLE_NAME.$COLUMN_WIFI_NAME AND p.$COLUMN_MAC_ADDRESS = $TABLE_NAME.$COLUMN_MAC_ADDRESS LIMIT 1),
                    $COLUMN_QUADKEY = (SELECT $COLUMN_QUADKEY FROM $TABLE_PERSONAL_MAP p WHERE p.$COLUMN_WIFI_NAME = $TABLE_NAME.$COLUMN_WIFI_NAME AND p.$COLUMN_MAC_ADDRESS = $TABLE_NAME.$COLUMN_MAC_ADDRESS LIMIT 1)
                WHERE EXISTS (SELECT 1 FROM $TABLE_PERSONAL_MAP p WHERE p.$COLUMN_WIFI_NAME = $TABLE_NAME.$COLUMN_WIFI_NAME AND p.$COLUMN_MAC_ADDRESS = $TABLE_NAME.$COLUMN_MAC_ADDRESS)
            """.trimIndent()
            db.execSQL(query)
            
            val cursor = db.rawQuery("SELECT changes()", null)
            if (cursor.moveToFirst()) {
                updatedCount = cursor.getInt(0)
            }
            cursor.close()
        }
        return updatedCount
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

    // --- Original Methods Restored from 58KB version ---

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
        try {
            val currentDbPath = context.getDatabasePath(DATABASE_NAME).absolutePath
            val currentDbFile = File(currentDbPath)

            close()

            if (currentDbFile.exists()) {
                currentDbFile.delete()
            }

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(currentDbFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            Log.d("LocalAppDbHelper", "Database restored successfully from $uri")
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error restoring database: ${e.message}", e)
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
                "SELECT $COLUMN_MAC_ADDRESS, $COLUMN_LATITUDE, $COLUMN_LONGITUDE FROM $TABLE_NAME WHERE $COLUMN_LATITUDE >= ? AND $COLUMN_LATITUDE <= ? AND $COLUMN_LONGITUDE >= ? AND $COLUMN_LONGITUDE <= ? LIMIT ?"
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
