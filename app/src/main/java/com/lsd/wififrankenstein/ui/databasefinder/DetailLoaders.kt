package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import android.database.Cursor
import androidx.core.net.toUri
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TAG = "DetailLoaders"

private val CLEAN_MAC_REGEX = Regex("[^a-fA-F0-9]")
private val HEX_PAIR_REGEX = Regex("(.{2})")
private val DECIMAL_REGEX = Regex("^[0-9]+$")

class WiFi3DetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> =
        flow {
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
                    emit(mapOf("error" to context.getString(R.string.invalid_bssid_format)))
                    return@flow
                }

                val helper = SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath)
                try {
                    val db = helper.database
                    if (db != null) {
                        val tableName = DatabaseTypeUtils.getMainTableName(db)

                        val hasGeo = DatabaseTypeUtils.hasColumn(db, "geo", "latitude")
                        val hasComments = DatabaseTypeUtils.hasColumn(db, "comments", "comment")
                        val hasGeoSource = DatabaseTypeUtils.hasColumn(db, "geo", "source")
                        val hasGeoTime = DatabaseTypeUtils.hasColumn(db, "geo", "time")
                        val geoCols = buildString {
                            append("g.latitude, g.longitude")
                            if (hasGeoSource) append(", g.source as geo_source")
                            if (hasGeoTime) append(", g.time as geo_time")
                        }

                        val query = if (hasGeo && hasComments) {
                            "SELECT n.*, $geoCols, c.comment FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                        } else if (hasGeo) {
                            "SELECT n.*, $geoCols FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
                        } else if (hasComments) {
                            "SELECT n.*, c.comment FROM $tableName n LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                        } else {
                            "SELECT n.* FROM $tableName n WHERE n.BSSID = ?"
                        }

                        db.rawQuery(query, arrayOf(decimalBssid.toString())).use { cursor ->
                            if (cursor.moveToFirst()) {
                                val result = cursorToMap(cursor)

                                if (result["BSSID"] is Long) {
                                    result["BSSID"] = formatMacAddress(result["BSSID"] as Long)
                                }

                                val formatted = formatWiFi3Result(result)

                                emit(formatted)
                            } else {
                                emit(mapOf("message" to context.getString(R.string.df_no_detailed_data)))
                            }
                        }
                    } else {
                        emit(mapOf("error" to context.getString(R.string.df_could_not_open_db)))
                    }
                } finally {
                    helper.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in WiFi3DetailLoader", e)
                emit(mapOf("error" to e.message))
            }
        }.flowOn(Dispatchers.IO)

    private fun formatMacAddress(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace(HEX_PAIR_REGEX, "$1:").dropLast(1)
    }

    private fun formatWiFi3Result(result: MutableMap<String, Any?>): Map<String, Any?> {
        val formatted = mutableMapOf<String, Any?>()

        for ((key, value) in result) {
            if (value == null) continue

            when (key.lowercase()) {
                "ip", "lanip", "wanip", "lanmask", "wanmask", "wangateway", "dns1", "dns2", "dns3" -> {
                    if (value is Long) {
                        formatted[key] =
                            com.lsd.wififrankenstein.util.DbFieldFormatter.longToIp(value)
                    } else {
                        formatted[key] = value
                    }
                }

                "time" -> {
                    formatted[key] =
                        com.lsd.wififrankenstein.util.DbFieldFormatter.formatTime(value)
                }

                "source" -> {
                    formatted[key] = com.lsd.wififrankenstein.util.DbFieldFormatter.sourceLabel(
                        context,
                        value as? Int
                    )
                }

                "hidden" -> {
                    formatted[key] =
                        com.lsd.wififrankenstein.util.DbFieldFormatter.hiddenLabel(value)
                }

                "radiooff" -> {
                    formatted[key] =
                        com.lsd.wififrankenstein.util.DbFieldFormatter.radioOffLabel(value)
                }

                "nowifikey" -> {
                    formatted[key] = com.lsd.wififrankenstein.util.DbFieldFormatter.noWifiKeyLabel(
                        context,
                        value as? Int
                    )
                }

                "nobssid" -> {
                    formatted[key] = com.lsd.wififrankenstein.util.DbFieldFormatter.noBssidLabel(
                        context,
                        value as? Int
                    )
                }

                "nowps" -> {
                    formatted[key] = com.lsd.wififrankenstein.util.DbFieldFormatter.noWpsLabel(
                        context,
                        value as? Int
                    )
                }

                "iprange" -> {
                    formatted[key] = com.lsd.wififrankenstein.util.DbFieldFormatter.iprangeLabel(
                        context,
                        value as? Int
                    )
                }

                "authorization" -> {
                    formatted[key] =
                        com.lsd.wififrankenstein.util.DbFieldFormatter.authorizationLabel(value as? String)
                }

                else -> {
                    formatted[key] = value
                }
            }
        }

        return formatted
    }
}

class CustomDbDetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> =
        flow {
            try {
                val tableName = dbItem.tableName
                val columnMap = dbItem.columnMap

                if (tableName == null) {
                    emit(mapOf("error" to context.getString(R.string.df_table_not_defined)))
                    return@flow
                }

                if (columnMap == null) {
                    emit(mapOf("error" to context.getString(R.string.df_column_mapping_not_defined)))
                    return@flow
                }

                val helper = SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath)
                try {
                    val db = helper.database
                    if (db != null) {
                        val cleanMac = bssid.replace(CLEAN_MAC_REGEX, "")
                        val macColumn = columnMap["mac"] ?: "bssid"

                        val query = """
                    SELECT * FROM $tableName WHERE 
                    $macColumn = ? OR 
                    UPPER($macColumn) = ? OR 
                    REPLACE(REPLACE(UPPER($macColumn), ':', ''), '-', '') = ?
                """.trimIndent()

                        db.rawQuery(query, arrayOf(bssid, bssid.uppercase(), cleanMac.uppercase()))
                            .use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val result = cursorToMap(cursor)

                                    val mappedKeys = mutableSetOf<String>()

                                    val normalizedResult = mutableMapOf<String, Any?>()

                                    columnMap["mac"]?.let { macField ->
                                        if (result.containsKey(macField)) {
                                            normalizedResult["BSSID"] = result[macField]
                                            mappedKeys.add(macField)
                                        }
                                    }

                                    columnMap["essid"]?.let { essidField ->
                                        if (result.containsKey(essidField)) {
                                            normalizedResult["ESSID"] = result[essidField]
                                            mappedKeys.add(essidField)
                                        }
                                    }

                                    columnMap["wifi_pass"]?.let { passField ->
                                        if (result.containsKey(passField)) {
                                            normalizedResult["WiFiKey"] = result[passField]
                                            mappedKeys.add(passField)
                                        }
                                    }

                                    columnMap["wps_pin"]?.let { wpsField ->
                                        if (result.containsKey(wpsField)) {
                                            normalizedResult["WPSPIN"] = result[wpsField]
                                            mappedKeys.add(wpsField)
                                        }
                                    }

                                    columnMap["latitude"]?.let { latField ->
                                        if (result.containsKey(latField)) {
                                            normalizedResult["latitude"] = result[latField]
                                            mappedKeys.add(latField)
                                        }
                                    }

                                    columnMap["longitude"]?.let { lonField ->
                                        if (result.containsKey(lonField)) {
                                            normalizedResult["longitude"] = result[lonField]
                                            mappedKeys.add(lonField)
                                        }
                                    }

                                    columnMap["security_type"]?.let { secField ->
                                        if (result.containsKey(secField)) {
                                            normalizedResult["capabilities"] = result[secField]
                                            mappedKeys.add(secField)
                                        }
                                    }

                                    columnMap["timestamp"]?.let { timeField ->
                                        if (result.containsKey(timeField)) {
                                            normalizedResult["time"] = result[timeField]
                                            mappedKeys.add(timeField)
                                        }
                                    }

                                    columnMap["admin_panel"]?.let { adminField ->
                                        if (result.containsKey(adminField)) {
                                            normalizedResult["AdminPanel"] = result[adminField]
                                            mappedKeys.add(adminField)
                                        }
                                    }

                                    columnMap["admin_login"]?.let { loginField ->
                                        if (result.containsKey(loginField)) {
                                            val login = result[loginField]?.toString().orEmpty()
                                            val pass = columnMap["admin_pass"]
                                                ?.let { result[it]?.toString() }.orEmpty()
                                            if (login.isNotBlank() || pass.isNotBlank()) {
                                                normalizedResult["AdminPanel"] = "$login:$pass"
                                            }
                                            mappedKeys.add(loginField)
                                        }
                                    }

                                    columnMap["admin_pass"]?.let { passField ->
                                        if (result.containsKey(passField)) {
                                            mappedKeys.add(passField)
                                        }
                                    }

                                    columnMap["adminpanel"]?.let { adminField ->
                                        if (result.containsKey(adminField)) {
                                            normalizedResult["AdminPanel"] = result[adminField]
                                            mappedKeys.add(adminField)
                                        }
                                    }

                                    columnMap["routermodel"]?.let { modelField ->
                                        if (result.containsKey(modelField)) {
                                            normalizedResult["RouterModel"] = result[modelField]
                                            mappedKeys.add(modelField)
                                        }
                                    }

                                    columnMap["routerfirmware"]?.let { firmwareField ->
                                        if (result.containsKey(firmwareField)) {
                                            normalizedResult["RouterFirmware"] =
                                                result[firmwareField]
                                            mappedKeys.add(firmwareField)
                                        }
                                    }

                                    columnMap["country"]?.let { countryField ->
                                        if (result.containsKey(countryField)) {
                                            normalizedResult["Country"] = result[countryField]
                                            mappedKeys.add(countryField)
                                        }
                                    }

                                    columnMap["firstseen"]?.let { firstSeenField ->
                                        if (result.containsKey(firstSeenField)) {
                                            normalizedResult["FirstSeen"] = result[firstSeenField]
                                            mappedKeys.add(firstSeenField)
                                        }
                                    }

                                    columnMap["lastseen"]?.let { lastSeenField ->
                                        if (result.containsKey(lastSeenField)) {
                                            normalizedResult["LastSeen"] = result[lastSeenField]
                                            mappedKeys.add(lastSeenField)
                                        }
                                    }

                                    columnMap["channel"]?.let { channelField ->
                                        if (result.containsKey(channelField)) {
                                            normalizedResult["Channel"] = result[channelField]
                                            mappedKeys.add(channelField)
                                        }
                                    }

                                    columnMap["notes"]?.let { notesField ->
                                        if (result.containsKey(notesField)) {
                                            normalizedResult["Notes"] = result[notesField]
                                            mappedKeys.add(notesField)
                                        }
                                    }

                                    result.forEach { (key, value) ->
                                        if (!mappedKeys.contains(key)) {
                                            normalizedResult[key] = value
                                        }
                                    }

                                    emit(normalizedResult)
                                } else {
                                    emit(mapOf("message" to context.getString(R.string.df_no_detailed_data)))
                                }
                            }
                    } else {
                        emit(mapOf("error" to context.getString(R.string.df_could_not_open_db)))
                    }
                } finally {
                    helper.close()
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
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> =
        flow {
            try {
                val helper = LocalAppDbHelper(context)

                val macFormats = generateAllMacFormats(bssid)
                val conditions = mutableListOf<String>()
                val params = mutableListOf<String>()

                macFormats.forEach { format ->
                    conditions.add("UPPER(${LocalAppDbHelper.COLUMN_MAC_ADDRESS}) = UPPER(?)")
                    params.add(format)
                    conditions.add("REPLACE(REPLACE(UPPER(${LocalAppDbHelper.COLUMN_MAC_ADDRESS}), ':', ''), '-', '') = REPLACE(REPLACE(UPPER(?), ':', ''), '-', '')")
                    params.add(format)
                }

                val query = """
            SELECT * FROM ${LocalAppDbHelper.TABLE_NAME} 
            WHERE ${conditions.joinToString(" OR ")}
        """.trimIndent()

                helper.readableDatabase.rawQuery(query, params.toTypedArray()).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val result = cursorToMap(cursor)

                        val normalizedResult = mutableMapOf<String, Any?>()
                        normalizedResult["BSSID"] = result[LocalAppDbHelper.COLUMN_MAC_ADDRESS]
                        normalizedResult["ESSID"] = result[LocalAppDbHelper.COLUMN_WIFI_NAME]
                        normalizedResult["WiFiKey"] = result[LocalAppDbHelper.COLUMN_WIFI_PASSWORD]
                        normalizedResult["WPSPIN"] = result[LocalAppDbHelper.COLUMN_WPS_CODE]
                        normalizedResult["AdminPanel"] = result[LocalAppDbHelper.COLUMN_ADMIN_PANEL]
                        normalizedResult["latitude"] = result[LocalAppDbHelper.COLUMN_LATITUDE]
                        normalizedResult["longitude"] = result[LocalAppDbHelper.COLUMN_LONGITUDE]

                        result.forEach { (key, value) ->
                            if (!normalizedResult.containsKey(key)) {
                                normalizedResult[key] = value
                            }
                        }

                        emit(normalizedResult)
                    } else {
                        emit(mapOf("message" to context.getString(R.string.df_no_detailed_data)))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in LocalAppDetailLoader", e)
                emit(mapOf("error" to e.message))
            }
        }.flowOn(Dispatchers.IO)

    private fun generateAllMacFormats(input: String): List<String> {
        val cleanInput = input.replace(CLEAN_MAC_REGEX, "").uppercase()
        val formats = mutableSetOf<String>()

        formats.add(input.trim())

        if (cleanInput.isNotEmpty()) {
            formats.add(cleanInput)
            formats.add(cleanInput.lowercase())

            if (cleanInput.length == 12) {
                formats.add(cleanInput.replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(cleanInput.replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
                formats.add(cleanInput.lowercase().replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(cleanInput.lowercase().replace(HEX_PAIR_REGEX, "$1-").dropLast(1))

                try {
                    val decimal = cleanInput.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {

                }
            }
        }

        if (input.matches(DECIMAL_REGEX)) {
            try {
                val decimal = input.toLong()
                val hex = String.format("%012X", decimal)
                formats.add(hex)
                formats.add(hex.lowercase())
                formats.add(hex.replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(hex.replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
                formats.add(hex.lowercase().replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(hex.lowercase().replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
            } catch (e: NumberFormatException) {

            }
        }

        return formats.filter { it.isNotEmpty() }.distinct()
    }
}


class ApiDetailLoader(
    private val context: Context,
    private val dbItem: DbItem,
    private val bssid: String
) : DetailDataLoader {
    override suspend fun loadDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> =
        flow {
            try {
                val apiHelper =
                    API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
                val results = apiHelper.searchNetworksByBSSIDs(listOf(bssid))

                if (results.isNotEmpty()) {
                    val networkData = (results[bssid]
                        ?: results[bssid.lowercase()]
                        ?: results[bssid.uppercase()])?.firstOrNull()
                    if (networkData != null) {
                        emit(networkData)
                    } else {
                        emit(mapOf("message" to context.getString(R.string.df_no_detailed_data)))
                    }
                } else {
                    emit(mapOf("message" to context.getString(R.string.df_no_detailed_data)))
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
            put(
                columnName, when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> "[BLOB data]"
                    else -> null
                }
            )
        }
    }.toMutableMap()
}