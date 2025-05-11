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
        return readableDatabase.query(
            TABLE_NAME,
            null,
            "$COLUMN_LATITUDE BETWEEN ? AND ? AND $COLUMN_LONGITUDE BETWEEN ? AND ?",
            arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            null,
            null,
            null
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

        val placeholders = validEssids.joinToString(",") { "?" }
        val selection = "$COLUMN_WIFI_NAME IN ($placeholders)"

        readableDatabase.query(
            TABLE_NAME,
            null,
            selection,
            validEssids.toTypedArray(),
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
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_wifiname ON $TABLE_NAME ($COLUMN_WIFI_NAME)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_macaddress ON $TABLE_NAME ($COLUMN_MAC_ADDRESS)")
    }

    fun disableIndexing() {
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_wifiname")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_macaddress")
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

    fun hasIndexes(): Boolean {
        return readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND (name='idx_wifiname' OR name='idx_macaddress')",
            null
        ).use { it.count == 2 }
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