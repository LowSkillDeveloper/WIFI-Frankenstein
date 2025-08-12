package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.lsd.wififrankenstein.util.Log
import androidx.core.database.sqlite.transaction
import java.io.File
import java.io.FileOutputStream

class LocalAppDbHelper(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "local_wifi_database.db"
        const val DATABASE_VERSION = 1

        const val TABLE_NAME = "wifi_networks"
        const val COLUMN_ID = "id"
        const val COLUMN_WIFI_NAME = "wifiname"
        const val COLUMN_MAC_ADDRESS = "macaddress"
        const val COLUMN_WIFI_PASSWORD = "wifipassword"
        const val COLUMN_WPS_CODE = "wpscode"
        const val COLUMN_ADMIN_PANEL = "adminpanel"
        const val COLUMN_LATITUDE = "latitude"
        const val COLUMN_LONGITUDE = "longitude"
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
            false
        }
    }

    private fun safeHasIndex(indexName: String): Boolean {
        return try {
            val cursor = readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(indexName)
            )
            val hasIndex = cursor.count > 0
            cursor.close()
            Log.d("LocalAppDbHelper", "Index check: $indexName = $hasIndex")
            hasIndex
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

            Log.d("LocalAppDbHelper", "Index status - name: $hasNameIndex, mac: $hasMacIndex, password: $hasPasswordIndex, wps: $hasWpsIndex")

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
                    }
                    if (insert(TABLE_NAME, null, values) != -1L) {
                        inserted++
                    }
                }
            }
        }

        return ImportStats(records.size, inserted, duplicates)
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
                $COLUMN_LONGITUDE REAL
            )
        """.trimIndent()
        db.execSQL(createTableSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    fun getPointsInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<WifiNetwork> {
        val indexLevel = getIndexLevel()

        val query = when (indexLevel) {
            "FULL", "BASIC" -> {
                "SELECT * FROM $TABLE_NAME " +
                        "WHERE $COLUMN_LATITUDE BETWEEN ? AND ? " +
                        "AND $COLUMN_LONGITUDE BETWEEN ? AND ?"
            }
            else -> {
                "SELECT * FROM $TABLE_NAME " +
                        "WHERE $COLUMN_LATITUDE BETWEEN ? AND ? " +
                        "AND $COLUMN_LONGITUDE BETWEEN ? AND ?"
            }
        }

        return readableDatabase.rawQuery(
            query,
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString())
        ).use { cursor ->
            val networks = mutableListOf<WifiNetwork>()
            while (cursor.moveToNext()) {
                networks.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
            networks
        }
    }

    fun getAllRecords(): List<WifiNetwork> {
        val records = mutableListOf<WifiNetwork>()
        readableDatabase.query(TABLE_NAME, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
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
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
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
            try {
                records.forEach { record ->
                    val values = ContentValues().apply {
                        put(COLUMN_WIFI_NAME, record.wifiName)
                        put(COLUMN_MAC_ADDRESS, record.macAddress)
                        put(COLUMN_WIFI_PASSWORD, record.wifiPassword)
                        put(COLUMN_WPS_CODE, record.wpsCode)
                        put(COLUMN_ADMIN_PANEL, record.adminPanel)
                        put(COLUMN_LATITUDE, record.latitude)
                        put(COLUMN_LONGITUDE, record.longitude)
                    }
                    insert(TABLE_NAME, null, values)
                }
            } finally {
            }
        }
    }

    fun searchRecordsByEssids(essids: List<String>): List<WifiNetwork> {
        val results = mutableListOf<WifiNetwork>()
        val validEssids = essids.filter { it.isNotBlank() }
        if (validEssids.isEmpty()) return results

        val chunkedEssids = validEssids.chunked(500)

        chunkedEssids.forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val query = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME IN ($placeholders) COLLATE NOCASE"

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

        val macQuery = if (query.contains(":") || query.contains("-")) {
            query.replace(":", "").replace("-", "").toLongOrNull()?.toString()
        } else {
            query.toLongOrNull()?.toString()
        }

        val whereClauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (filterByName) {
            whereClauses.add("$COLUMN_WIFI_NAME LIKE ?")
            selectionArgs.add(searchQuery)
        }
        if (filterByMac) {
            if (macQuery != null) {
                whereClauses.add("($COLUMN_MAC_ADDRESS = ? OR $COLUMN_MAC_ADDRESS LIKE ?)")
                selectionArgs.add(macQuery)
                selectionArgs.add(searchQuery)
            } else {
                whereClauses.add("$COLUMN_MAC_ADDRESS LIKE ?")
                selectionArgs.add(searchQuery)
            }
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
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
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
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
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
        }
        writableDatabase.update(TABLE_NAME, values, "$COLUMN_ID = ?", arrayOf(wifiNetwork.id.toString()))
    }

    fun getRecordsCount(): Int {
        return readableDatabase.use { db ->
            db.query(TABLE_NAME, arrayOf("COUNT(*)"), null, null, null, null, null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
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
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME LIKE ? COLLATE NOCASE"

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
        readableDatabase.rawQuery(fallbackSql, arrayOf("%$query%")).use { cursor ->
            results.addAll(buildWifiNetworkList(cursor))
        }

        return results.distinctBy { it.macAddress }
    }

    private fun searchByPassword(query: String): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_PASSWORD LIKE ? COLLATE NOCASE"

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
                    // Ignore conversion errors
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
                // Ignore conversion errors
            }
        }

        return formats.filter { it.isNotEmpty() }.distinct()
    }

    private fun buildWifiNetworkList(cursor: Cursor): List<WifiNetwork> {
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
        }
    }

    fun searchRecordsWithFiltersPaginated(
        query: String,
        searchFields: Set<String>,
        offset: Int,
        limit: Int
    ): List<WifiNetwork> {
        debugIndexes()

        val allResults = mutableSetOf<WifiNetwork>()

        if ("name" in searchFields) {
            allResults.addAll(searchByNamePaginated(query, offset, limit))
        }

        if ("mac" in searchFields) {
            allResults.addAll(searchByMacAllFormatsPaginated(query, offset, limit))
        }

        if ("password" in searchFields) {
            allResults.addAll(searchByPasswordPaginated(query, offset, limit))
        }

        if ("wps" in searchFields) {
            allResults.addAll(searchByWpsPaginated(query, offset, limit))
        }

        return allResults.distinctBy { "${it.macAddress}-${it.wifiName}" }.take(limit)
    }

    private fun searchByNamePaginated(query: String, offset: Int, limit: Int): List<WifiNetwork> {
        Log.d("LocalAppDbHelper", "searchByNamePaginated - using simple query without INDEXED BY")

        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME LIKE ? COLLATE NOCASE LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, arrayOf("%$query%")).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun searchByMacAllFormatsPaginated(query: String, offset: Int, limit: Int): List<WifiNetwork> {
        val macFormats = generateAllMacFormats(query)
        val conditions = mutableListOf<String>()
        val params = mutableListOf<String>()

        macFormats.forEach { format ->
            conditions.add("$COLUMN_MAC_ADDRESS = ?")
            params.add(format)
        }
        conditions.add("$COLUMN_MAC_ADDRESS LIKE ?")
        params.add("%$query%")

        val sql = "SELECT * FROM $TABLE_NAME WHERE ${conditions.joinToString(" OR ")} LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, params.toTypedArray()).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun searchByPasswordPaginated(query: String, offset: Int, limit: Int): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_PASSWORD LIKE ? COLLATE NOCASE LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, arrayOf("%$query%")).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    private fun searchByWpsPaginated(query: String, offset: Int, limit: Int): List<WifiNetwork> {
        val sql = "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WPS_CODE = ? LIMIT $limit OFFSET $offset"

        return readableDatabase.rawQuery(sql, arrayOf(query)).use { cursor ->
            buildWifiNetworkList(cursor)
        }
    }

    fun debugIndexes() {
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?",
                arrayOf(TABLE_NAME)
            )
            Log.d("LocalAppDbHelper", "Available indexes:")
            while (cursor.moveToNext()) {
                Log.d("LocalAppDbHelper", "  - ${cursor.getString(0)}")
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error getting indexes", e)
        }
    }

    fun searchRecords(query: String): List<WifiNetwork> {
        val results = mutableListOf<WifiNetwork>()
        val searchQuery = "%$query%"
        val decimalMac = convertMacToDecimal(query)

        val selection = "$COLUMN_WIFI_NAME LIKE ? OR $COLUMN_MAC_ADDRESS LIKE ? OR $COLUMN_MAC_ADDRESS = ?"
        val selectionArgs = arrayOf(searchQuery, searchQuery, decimalMac)

        readableDatabase.query(
            TABLE_NAME,
            null,
            selection,
            selectionArgs,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    WifiNetwork(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                        wifiName = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_NAME)),
                        macAddress = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MAC_ADDRESS)),
                        wifiPassword = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WIFI_PASSWORD)),
                        wpsCode = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_WPS_CODE)),
                        adminPanel = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ADMIN_PANEL)),
                        latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LATITUDE)),
                        longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_LONGITUDE))
                    )
                )
            }
        }

        return results
    }

    fun optimizeForBulkInsert() {
        try {
            writableDatabase.apply {
                rawQuery("PRAGMA synchronous = OFF", null)?.close()
                rawQuery("PRAGMA journal_mode = MEMORY", null)?.close()
                rawQuery("PRAGMA cache_size = 50000", null)?.close()
                rawQuery("PRAGMA temp_store = MEMORY", null)?.close()
                rawQuery("PRAGMA count_changes = OFF", null)?.close()
            }
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error optimizing for bulk insert", e)
        }
    }

    fun restoreNormalSettings() {
        try {
            writableDatabase.apply {
                rawQuery("PRAGMA synchronous = NORMAL", null)?.close()
                rawQuery("PRAGMA journal_mode = WAL", null)?.close()
                rawQuery("PRAGMA cache_size = 10000", null)?.close()
                rawQuery("PRAGMA count_changes = ON", null)?.close()
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
            Log.d("LocalAppDbHelper", "Indexes dropped for bulk insert")
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

                val indexLevel = getIndexLevel()
                if (indexLevel == "FULL") {
                    execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON $TABLE_NAME ($COLUMN_WIFI_PASSWORD COLLATE NOCASE)")
                    execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON $TABLE_NAME ($COLUMN_WPS_CODE)")
                }
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
        var inserted = 0
        var duplicates = 0

        val uniqueNetworks = networks.filter { network ->
            val key = "${network.wifiName}|${network.macAddress}"
            if (existingKeys.contains(key)) {
                duplicates++
                false
            } else {
                true
            }
        }

        if (uniqueNetworks.isNotEmpty()) {
            inserted = bulkInsertBatch(uniqueNetworks)
        }

        return Pair(inserted, duplicates)
    }

    fun bulkInsertBatch(networks: List<WifiNetwork>): Int {
        var inserted = 0

        try {
            writableDatabase.transaction {
                networks.chunked(1000).forEach { batch ->
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

    fun bulkInsertOptimized(networks: List<WifiNetwork>, checkDuplicates: Boolean = false): Pair<Int, Int> {
        return if (checkDuplicates) {
            val existingKeys = getAllExistingKeys()
            bulkInsertOptimizedWithDuplicateCheck(networks, existingKeys)
        } else {
            val inserted = bulkInsertBatch(networks)
            Pair(inserted, 0)
        }
    }

    private fun convertMacToDecimal(mac: String): String {
        return try {
            mac.replace(":", "").toLong(16).toString()
        } catch (_: NumberFormatException) {
            ""
        }
    }

    fun enableIndexing(level: String = "BASIC") {
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")

        if (level == "FULL") {
            writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON $TABLE_NAME ($COLUMN_WIFI_PASSWORD COLLATE NOCASE)")
            writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON $TABLE_NAME ($COLUMN_WPS_CODE)")
        }
    }

    fun disableIndexing() {
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_name")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_mac")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_coords")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_password")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifi_network_wps")
    }

    fun hasIndexes(): Boolean {
        return readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND name IN (?, ?, ?)",
            arrayOf(TABLE_NAME, "idx_wifi_network_mac", "idx_wifi_network_name", "idx_wifi_network_coords")
        ).use { it.count >= 3 }
    }

    fun optimizeDatabase() {
        writableDatabase.execSQL("VACUUM")
    }

    fun removeDuplicates() {
        writableDatabase.execSQL("""
            DELETE FROM $TABLE_NAME
            WHERE $COLUMN_ID NOT IN (
                SELECT MIN($COLUMN_ID)
                FROM $TABLE_NAME
                GROUP BY $COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_PASSWORD, $COLUMN_WPS_CODE, $COLUMN_ADMIN_PANEL, $COLUMN_LATITUDE, $COLUMN_LONGITUDE
            )
        """.trimIndent())
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

            writableDatabase.close()

            Log.d("LocalAppDbHelper", "Database restored successfully from $uri")
        } catch (e: Exception) {
            Log.e("LocalAppDbHelper", "Error restoring database: ${e.message}", e)
        }
    }

}