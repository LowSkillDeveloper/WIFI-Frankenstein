package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MapCoordinatesHelper(private val context: Context) {
    companion object {
        private const val TAG = "MapCoordinatesHelper"
    }

    suspend fun getCoordinates(bssid: String, dbItem: DbItem): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        try {
            var attempts = 0
            val maxAttempts = 3
            var result: Pair<Double?, Double?>? = null

            while (attempts < maxAttempts && result == null) {
                attempts++
                Log.d(TAG, "Attempt $attempts to get coordinates for BSSID: $bssid from: ${dbItem.path}")

                result = when (dbItem.dbType) {
                    DbType.SQLITE_FILE_3WIFI -> getCoordinatesFrom3WiFiDb(bssid, dbItem)
                    DbType.SQLITE_FILE_CUSTOM -> getCoordinatesFromCustomDb(bssid, dbItem)
                    DbType.LOCAL_APP_DB -> getCoordinatesFromLocalDb(bssid)
                    else -> null to null
                }

                if (result.first != null && result.second != null) {
                    Log.d(TAG, "Found coordinates on attempt $attempts: lat=${result.first}, lon=${result.second}")
                    break
                } else if (attempts < maxAttempts) {
                    Log.d(TAG, "No coordinates found on attempt $attempts, waiting for next attempt")
                    delay(600)
                }
            }

            if (result == null || result.first == null || result.second == null) {
                Log.d(TAG, "Failed to get coordinates after $maxAttempts attempts")
                return@withContext null to null
            }

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching coordinates for BSSID: $bssid", e)
            null to null
        }
    }

    private suspend fun getCoordinatesFrom3WiFiDb(bssid: String, dbItem: DbItem): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        try {
            val decimalBssid = if (bssid.contains(":") || bssid.contains("-")) {
                try {
                    bssid.replace(":", "").replace("-", "").toLong(16).toString()
                } catch (_: NumberFormatException) {
                    null
                }
            } else {
                bssid
            }

            if (decimalBssid == null) {
                return@withContext null to null
            }

            SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val db = helper.database
                if (db != null) {
                    val indexLevel = DatabaseIndices.determineIndexLevel(db)
                    val query = if (indexLevel >= DatabaseIndices.IndexLevel.BASIC) {
                        "SELECT latitude, longitude FROM geo INDEXED BY ${DatabaseIndices.GEO_BSSID} WHERE BSSID = ?"
                    } else {
                        "SELECT latitude, longitude FROM geo WHERE BSSID = ?"
                    }

                    db.rawQuery(query, arrayOf(decimalBssid)).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val latIndex = cursor.getColumnIndex("latitude")
                            val lonIndex = cursor.getColumnIndex("longitude")

                            val lat = if (latIndex >= 0) cursor.getDouble(latIndex) else null
                            val lon = if (lonIndex >= 0) cursor.getDouble(lonIndex) else null

                            Log.d(TAG, "3WiFi coordinates for $bssid: lat=$lat, lon=$lon (index level: $indexLevel)")
                            return@withContext lat to lon
                        }
                    }
                }
            }
            null to null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting 3WiFi coordinates", e)
            null to null
        }
    }

    private suspend fun getCoordinatesFromCustomDb(bssid: String, dbItem: DbItem): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        try {
            val tableName = dbItem.tableName ?: return@withContext null to null
            val columnMap = dbItem.columnMap ?: return@withContext null to null

            val latColumn = columnMap["latitude"] ?: "lat"
            val lonColumn = columnMap["longitude"] ?: "lon"
            val macColumn = columnMap["mac"] ?: "bssid"

            val cleanMac = bssid.replace("[^a-fA-F0-9]".toRegex(), "")

            SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val db = helper.database
                if (db != null) {
                    val query = """
                        SELECT $latColumn, $lonColumn FROM $tableName 
                        WHERE $macColumn = ? OR 
                        UPPER($macColumn) = ? OR 
                        REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = ?
                    """.trimIndent()

                    db.rawQuery(query, arrayOf(bssid, bssid.uppercase(), cleanMac.uppercase())).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val latIndex = cursor.getColumnIndex(latColumn)
                            val lonIndex = cursor.getColumnIndex(lonColumn)

                            val lat = if (latIndex >= 0) cursor.getDouble(latIndex) else null
                            val lon = if (lonIndex >= 0) cursor.getDouble(lonIndex) else null

                            Log.d(TAG, "Custom DB coordinates for $bssid: lat=$lat, lon=$lon")
                            return@withContext lat to lon
                        }
                    }
                }
            }
            null to null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Custom DB coordinates", e)
            null to null
        }
    }

    private suspend fun getCoordinatesFromLocalDb(bssid: String): Pair<Double?, Double?> = withContext(Dispatchers.IO) {
        try {
            val helper = LocalAppDbHelper(context)
            val cleanMac = bssid.replace("[^a-fA-F0-9]".toRegex(), "")

            val query = """
                SELECT ${LocalAppDbHelper.COLUMN_LATITUDE}, ${LocalAppDbHelper.COLUMN_LONGITUDE} 
                FROM ${LocalAppDbHelper.TABLE_NAME}
                WHERE ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} = ? OR 
                ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} LIKE ? OR
                REPLACE(REPLACE(${LocalAppDbHelper.COLUMN_MAC_ADDRESS}, ':', ''), '-', '') = ?
            """.trimIndent()

            helper.readableDatabase.rawQuery(query, arrayOf(bssid, "%$bssid%", cleanMac.uppercase())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val latIndex = cursor.getColumnIndex(LocalAppDbHelper.COLUMN_LATITUDE)
                    val lonIndex = cursor.getColumnIndex(LocalAppDbHelper.COLUMN_LONGITUDE)

                    val lat = if (latIndex >= 0) cursor.getDouble(latIndex) else null
                    val lon = if (lonIndex >= 0) cursor.getDouble(lonIndex) else null

                    Log.d(TAG, "Local DB coordinates for $bssid: lat=$lat, lon=$lon")

                    if (lat == 0.0 && lon == 0.0) {
                        return@withContext null to null
                    }

                    return@withContext lat to lon
                }
            }
            null to null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Local DB coordinates", e)
            null to null
        }
    }
}