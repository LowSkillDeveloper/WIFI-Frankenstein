package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.core.net.toUri
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "DetailLoaders"

class WiFi3DetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        try {
            val decimalBssid = if (bssid.contains(":") || bssid.contains("-")) {
                try {
                    bssid.replace(":", "").replace("-", "").toLong(16)
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "Error converting BSSID to decimal", e)
                    null
                }
            } else {
                bssid.toLongOrNull()
            }

            if (decimalBssid == null) {
                emit(mapOf("error" to "Invalid BSSID format"))
                return@flow
            }

            SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val db = helper.database
                if (db != null) {
                    val dbType = DatabaseTypeUtils.determineDbType(db)
                    val tableName = when (dbType) {
                        DatabaseTypeUtils.WiFiDbType.TYPE_NETS -> "nets"
                        DatabaseTypeUtils.WiFiDbType.TYPE_BASE -> "base"
                        else -> {
                            emit(mapOf("error" to "Unknown database type"))
                            return@flow
                        }
                    }

                    val indexLevel = DatabaseIndices.determineIndexLevel(db)
                    val query = if (indexLevel >= DatabaseIndices.IndexLevel.BASIC) {
                        "SELECT n.*, g.latitude, g.longitude FROM $tableName n INDEXED BY ${if (tableName == "nets") DatabaseIndices.NETS_BSSID else DatabaseIndices.BASE_BSSID} LEFT JOIN geo g INDEXED BY ${DatabaseIndices.GEO_BSSID} ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
                    } else {
                        "SELECT n.*, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
                    }

                    db.rawQuery(query, arrayOf(decimalBssid.toString())).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val result = cursorToMap(cursor)

                            if (result["BSSID"] is Long) {
                                result["BSSID"] = formatMacAddress(result["BSSID"] as Long)
                            }

                            emit(result)
                        } else {
                            emit(mapOf("message" to "No detailed data found"))
                        }
                    }
                } else {
                    emit(mapOf("error" to "Could not open database"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in WiFi3DetailLoader", e)
            emit(mapOf("error" to e.message))
        }
    }.flowOn(Dispatchers.IO)

    private fun formatMacAddress(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace("(.{2})".toRegex(), "$1:").dropLast(1)
    }
}

class CustomDbDetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        try {
            val tableName = dbItem.tableName
            val columnMap = dbItem.columnMap

            if (tableName == null) {
                emit(mapOf("error" to "Table name not defined"))
                return@flow
            }

            if (columnMap == null) {
                emit(mapOf("error" to "Column mapping not defined"))
                return@flow
            }

            SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val db = helper.database
                if (db != null) {
                    val cleanMac = bssid.replace("[^a-fA-F0-9]".toRegex(), "")
                    val macColumn = columnMap["mac"] ?: "bssid"

                    val query = """
                        SELECT * FROM $tableName WHERE 
                        $macColumn = ? OR 
                        UPPER($macColumn) = ? OR 
                        REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = ?
                    """.trimIndent()

                    db.rawQuery(query, arrayOf(bssid, bssid.uppercase(), cleanMac.uppercase())).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val result = cursorToMap(cursor)

                            val normalizedResult = mutableMapOf<String, Any?>()

                            columnMap["mac"]?.let { macField ->
                                normalizedResult["BSSID"] = result[macField]
                            }

                            columnMap["essid"]?.let { essidField ->
                                normalizedResult["ESSID"] = result[essidField]
                            }

                            columnMap["wifi_pass"]?.let { passField ->
                                normalizedResult["WiFiKey"] = result[passField]
                            }

                            columnMap["wps_pin"]?.let { wpsField ->
                                normalizedResult["WPSPIN"] = result[wpsField]
                            }

                            columnMap["latitude"]?.let { latField ->
                                normalizedResult["latitude"] = result[latField]
                            }

                            columnMap["longitude"]?.let { lonField ->
                                normalizedResult["longitude"] = result[lonField]
                            }

                            columnMap["security_type"]?.let { secField ->
                                normalizedResult["capabilities"] = result[secField]
                            }

                            columnMap["timestamp"]?.let { timeField ->
                                normalizedResult["time"] = result[timeField]
                            }

                            result.forEach { (key, value) ->
                                if (!normalizedResult.containsKey(key)) {
                                    normalizedResult[key] = value
                                }
                            }

                            emit(normalizedResult)
                        } else {
                            emit(mapOf("message" to "No detailed data found"))
                        }
                    }
                } else {
                    emit(mapOf("error" to "Could not open database"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in CustomDbDetailLoader", e)
            emit(mapOf("error" to e.message))
        }
    }.flowOn(Dispatchers.IO)
}

class LocalAppDetailLoader(
    private val context: Context,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        try {
            val helper = LocalAppDbHelper(context)
            val cleanMac = bssid.replace("[^a-fA-F0-9]".toRegex(), "")

            val query = """
                SELECT * FROM ${LocalAppDbHelper.TABLE_NAME} 
                WHERE ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} = ? OR 
                ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} LIKE ? OR
                REPLACE(REPLACE(${LocalAppDbHelper.COLUMN_MAC_ADDRESS}, ':', ''), '-', '') = ?
            """.trimIndent()

            helper.readableDatabase.rawQuery(query, arrayOf(bssid, "%$bssid%", cleanMac.uppercase())).use { cursor ->
                if (cursor.moveToFirst()) {
                    emit(cursorToMap(cursor))
                } else {
                    emit(mapOf("message" to "No detailed data found"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in LocalAppDetailLoader", e)
            emit(mapOf("error" to e.message))
        }
    }.flowOn(Dispatchers.IO)
}


class ApiDetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        try {
            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
            val results = apiHelper.searchNetworksByBSSIDs(listOf(bssid))

            if (results.isNotEmpty() && results.containsKey(bssid)) {
                val networkData = results[bssid]?.firstOrNull()
                if (networkData != null) {
                    emit(networkData)
                } else {
                    emit(mapOf("message" to "No detailed data found"))
                }
            } else {
                emit(mapOf("message" to "No detailed data found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ApiDetailLoader", e)
            emit(mapOf("error" to e.message))
        }
    }.flowOn(Dispatchers.IO)
}

private fun cursorToMap(cursor: Cursor): MutableMap<String, Any?> {
    return buildMap {
        for (i in 0 until cursor.columnCount) {
            val columnName = cursor.getColumnName(i)
            put(columnName, when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> "[BLOB data]"
                else -> null
            })
        }
    }.toMutableMap()
}