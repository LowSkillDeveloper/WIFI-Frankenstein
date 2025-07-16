package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
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
        return readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(indexName)
        ).use { it.count > 0 }
    }

    fun getIndexLevel(): String {
        return when {
            hasIndex("idx_wifi_network_password") && hasIndex("idx_wifi_network_wps") -> "FULL"
            hasIndex("idx_wifi_network_mac") && hasIndex("idx_wifi_network_name") -> "BASIC"
            else -> "NONE"
        }
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
        val indexLevel = getIndexLevel()
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

        val queryBuilder = StringBuilder("SELECT * FROM $TABLE_NAME")

        if (indexLevel != "NONE" && searchFields.size == 1) {
            when (searchFields.first()) {
                "name" -> if (hasIndex("idx_wifi_network_name")) {
                    queryBuilder.append(" INDEXED BY idx_wifi_network_name")
                }
                "mac" -> if (hasIndex("idx_wifi_network_mac")) {
                    queryBuilder.append(" INDEXED BY idx_wifi_network_mac")
                }
                "password" -> if (indexLevel == "FULL" && hasIndex("idx_wifi_network_password")) {
                    queryBuilder.append(" INDEXED BY idx_wifi_network_password")
                }
                "wps" -> if (indexLevel == "FULL" && hasIndex("idx_wifi_network_wps")) {
                    queryBuilder.append(" INDEXED BY idx_wifi_network_wps")
                }
            }
        }

        queryBuilder.append(" WHERE $whereClause LIMIT $limit")

        return readableDatabase.rawQuery(queryBuilder.toString(), args.toTypedArray()).use { cursor ->
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

        val indexLevel = getIndexLevel()
        val hasNameIndex = hasIndex("idx_wifi_network_name")

        val chunkedEssids = validEssids.chunked(500)

        chunkedEssids.forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }

            val query = when {
                indexLevel != "NONE" && hasNameIndex -> {
                    "SELECT * FROM $TABLE_NAME INDEXED BY idx_wifi_network_name WHERE $COLUMN_WIFI_NAME IN ($placeholders) COLLATE NOCASE"
                }
                else -> {
                    "SELECT * FROM $TABLE_NAME WHERE $COLUMN_WIFI_NAME IN ($placeholders) COLLATE NOCASE"
                }
            }

            readableDatabase.rawQuery(query, chunk.toTypedArray()).use { cursor ->
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

    private fun convertMacToDecimal(mac: String): String {
        return try {
            mac.replace(":", "").toLong(16).toString()
        } catch (_: NumberFormatException) {
            ""
        }
    }

    fun enableIndexing() {
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_name ON $TABLE_NAME ($COLUMN_WIFI_NAME COLLATE NOCASE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_mac ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_coords ON $TABLE_NAME ($COLUMN_LATITUDE, $COLUMN_LONGITUDE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_password ON $TABLE_NAME ($COLUMN_WIFI_PASSWORD COLLATE NOCASE)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifi_network_wps ON $TABLE_NAME ($COLUMN_WPS_CODE)")
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