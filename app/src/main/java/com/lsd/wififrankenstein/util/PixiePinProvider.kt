package com.lsd.wififrankenstein.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.net.toUri
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PixiePinProvider(private val context: Context) {

    data class ScoredPin(val pin: String, val score: Int, val source: String)

    suspend fun getBestPin(
        bssid: String,
        dbItems: List<DbItem>? = null
    ): String? {
        return getBestScoredPin(bssid, dbItems)?.pin
    }

    suspend fun getBestScoredPin(
        bssid: String,
        dbItems: List<DbItem>? = null
    ): ScoredPin? {
        val pins = mutableListOf<ScoredPin>()
        val generator = WpsPinGenerator()
        val normalized =
            MacAddressUtils.formatToColonSeparated(bssid)?.uppercase() ?: bssid


        generator.generateSuggestedPins(normalized).forEach {
            val normalizedPin = if (it.pin == "<empty>" || it.pin.isEmpty()) "" else it.pin
            pins.add(ScoredPin(normalizedPin, 100, "suggested"))
        }


        getPinsFromLocalDb(normalized).forEach {
            pins.add(ScoredPin(it, 90, "local_db"))
        }


        if (dbItems != null) {
            find3WiFiPins(context, normalized, dbItems).forEach {
                if (pins.none { p -> p.pin == it.pin })
                    pins.add(it)
            }
            findCustomPins(context, normalized, dbItems).forEach {
                if (pins.none { p -> p.pin == it.pin })
                    pins.add(it)
            }
        }


        generator.generateAllPins(normalized).filter { !it.isExperimental }.forEach {
            if (pins.none { p -> p.pin == it.pin }) {
                pins.add(ScoredPin(it.pin, 80, "algorithm"))
            }
        }


        getPinsFromWpsDb(normalized).forEach {
            if (pins.none { p -> p.pin == it }) {
                pins.add(ScoredPin(it, 70, "wps_db"))
            }
        }


        if (dbItems != null) {
            val neighborPins = find3WiFiNeighborPins(context, normalized, dbItems)
            neighborPins.forEach { scoredPin ->
                if (pins.none { p -> p.pin == scoredPin.pin })
                    pins.add(scoredPin)
            }
        }


        searchNeighborPinsWpsDb(normalized).forEach {
            if (pins.none { p -> p.pin == it }) {
                pins.add(ScoredPin(it, 50, "neighbor"))
            }
        }


        if (pins.isEmpty()) {
            generator.generateAllPins(normalized).filter { it.isExperimental }.forEach {
                pins.add(ScoredPin(it.pin, 30, "experimental"))
            }
        }

        val best = pins.maxByOrNull { it.score }
        Log.d(
            "PixiePinProvider",
            "Best PIN: ${best?.pin} (score=${best?.score}, source=${best?.source})"
        )
        return best
    }





    companion object {

        suspend fun find3WiFiPins(
            context: Context,
            bssid: String,
            dbItems: List<DbItem>
        ): List<ScoredPin> = withContext(Dispatchers.IO) {
            val normalized =
                MacAddressUtils.formatToColonSeparated(bssid)?.uppercase() ?: bssid
            val pins = mutableListOf<ScoredPin>()
            val p3wifiDbs = dbItems.filter {
                it.dbType == DbType.SQLITE_FILE_P3WIFI
                        || it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI
            }
            val searchFormats = MacAddressUtils.generateAllFormats(normalized)
            val decimalBssids = searchFormats.mapNotNull { format ->
                MacAddressUtils.convertToDecimal(format)?.toString()
            }.distinct()

            p3wifiDbs.forEach { dbItem ->
                try {
                    val helper = SQLite3WiFiHelper(
                        context.applicationContext,
                        dbItem.path.toUri(),
                        dbItem.directPath
                    )
                    if (decimalBssids.isNotEmpty()) {
                        val results =
                            helper.searchNetworksByBSSIDsAsync(decimalBssids)
                        results.forEach { result ->
                            val wpsPin = result["WPSPIN"]?.toString()
                            if (!wpsPin.isNullOrEmpty() && wpsPin != "0" && isValidWpsPin(
                                    wpsPin
                                )
                            ) {
                                pins.add(
                                    ScoredPin(
                                        pin = wpsPin,
                                        score = 85,
                                        source = "3wifi_database"
                                    )
                                )
                            }
                        }
                    }
                    helper.close()
                } catch (e: Exception) {
                    Log.w("PixiePinProvider", "Error in 3WiFi database search", e)
                }
            }
            pins.distinctBy { it.pin }
        }

        suspend fun findCustomPins(
            context: Context,
            bssid: String,
            dbItems: List<DbItem>
        ): List<ScoredPin> = withContext(Dispatchers.IO) {
            val normalized =
                MacAddressUtils.formatToColonSeparated(bssid)?.uppercase() ?: bssid
            val pins = mutableListOf<ScoredPin>()
            val customDbs = dbItems.filter {
                it.dbType == DbType.SQLITE_FILE_CUSTOM
                        || it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
            }
            val searchFormats = MacAddressUtils.generateAllFormats(normalized)

            customDbs.forEach { dbItem ->
                try {
                    val helper = SQLiteCustomHelper(
                        context,
                        dbItem.path.toUri(),
                        dbItem.directPath
                    )
                    val tableName = dbItem.tableName ?: return@forEach
                    val columnMap = dbItem.columnMap ?: return@forEach
                    val results =
                        helper.searchNetworksByBSSIDs(tableName, columnMap, searchFormats)
                    searchFormats.forEach { searchFormat ->
                        results[searchFormat]?.let { result ->
                            val wpsPinColumn = columnMap["wps_pin"]
                            if (wpsPinColumn != null) {
                                val wpsPin = result[wpsPinColumn]?.toString()
                                if (!wpsPin.isNullOrEmpty() && wpsPin != "0" && isValidWpsPin(
                                        wpsPin
                                    )
                                ) {
                                    pins.add(
                                        ScoredPin(
                                            pin = wpsPin,
                                            score = 85,
                                            source = "custom_database"
                                        )
                                    )
                                }
                            }
                        }
                    }
                    helper.close()
                } catch (e: Exception) {
                    Log.w("PixiePinProvider", "Error in custom database search", e)
                }
            }
            pins.distinctBy { it.pin }
        }

        suspend fun find3WiFiNeighborPins(
            context: Context,
            bssid: String,
            dbItems: List<DbItem>
        ): List<ScoredPin> = withContext(Dispatchers.IO) {
            val pins = mutableListOf<ScoredPin>()
            val p3wifiDbs = dbItems.filter {
                it.dbType == DbType.SQLITE_FILE_P3WIFI
                        || it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI
            }
            val targetDecimal =
                MacAddressUtils.convertToDecimal(bssid) ?: return@withContext pins
            val maxDistance = 1000

            p3wifiDbs.forEach { dbItem ->
                try {
                    val helper = SQLite3WiFiHelper(
                        context.applicationContext,
                        dbItem.path.toUri(),
                        dbItem.directPath
                    )
                    val targetNic = targetDecimal and 0xFFFFFF
                    val ouiBase = targetDecimal and 0xFFFFFF000000L

                    val rangeStart =
                        (kotlin.math.max(0, targetNic - maxDistance)) or ouiBase
                    val rangeEnd =
                        (kotlin.math.min(0xFFFFFF, targetNic + maxDistance)) or ouiBase

                    val tableName =
                        DatabaseTypeUtils.getMainTableName(helper.database!!)
                    val sql = """
                        SELECT BSSID, WPSPIN 
                        FROM $tableName 
                        WHERE BSSID BETWEEN ? AND ? 
                        AND BSSID != ?
                        AND WPSPIN IS NOT NULL 
                        AND WPSPIN != '0' 
                        AND WPSPIN != '1'
                        ORDER BY ABS(BSSID - ?) 
                        LIMIT 50
                    """.trimIndent()

                    helper.database?.rawQuery(
                        sql,
                        arrayOf(
                            rangeStart.toString(),
                            rangeEnd.toString(),
                            targetDecimal.toString(),
                            targetDecimal.toString()
                        )
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val neighborDecimal = cursor.getLong(0)
                            val wpsPin = cursor.getString(1)
                            if (isValidWpsPin(wpsPin)) {
                                val distance = kotlin.math.abs(
                                    (targetNic - (neighborDecimal and 0xFFFFFF)).toInt()
                                )
                                val score = when (distance) {
                                    in 1..10 -> 85
                                    in 11..100 -> 85
                                    else -> 60
                                }
                                pins.add(
                                    ScoredPin(
                                        pin = wpsPin,
                                        score = score,
                                        source = "neighbor_3wifi"
                                    )
                                )
                            }
                        }
                    }
                    helper.close()
                } catch (e: Exception) {
                    Log.w("PixiePinProvider", "Error in 3WiFi neighbor search", e)
                }
            }
            pins.distinctBy { it.pin }
        }

        private fun isValidWpsPin(pin: String): Boolean {
            return pin.length in 4..8 && pin.all { it.isDigit() }
        }
    }





    private fun getPinsFromWpsDb(bssid: String): List<String> {
        val pins = mutableListOf<String>()
        try {
            val dbFile = getFileFromAssets("wps_pin.db") ?: return pins
            val db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            )
            val macPrefix = bssid.replace(":", "").substring(0, 6).uppercase()
            val macPrefixColon = bssid.substring(0, 8).uppercase()

            val cursor = db.rawQuery(
                "SELECT pin FROM pins WHERE mac=? OR mac=?",
                arrayOf(macPrefix, macPrefixColon)
            )
            cursor.use {
                while (it.moveToNext()) {
                    val pin = it.getString(it.getColumnIndexOrThrow("pin"))
                    if (isValidWpsPin(pin)) pins.add(pin)
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.w("PixiePinProvider", "Error reading wps_pin.db", e)
        }
        return pins
    }

    private fun getPinsFromLocalDb(bssid: String): List<String> {
        val pins = mutableListOf<String>()
        try {
            val helper = LocalAppDbHelper(context)
            val formats = MacAddressUtils.generateAllFormats(bssid)
            formats.forEach { format ->
                val results = helper.searchRecordsWithFilters(
                    query = format,
                    filterByName = false,
                    filterByMac = true,
                    filterByPassword = false,
                    filterByWps = true
                )
                results.forEach { network ->
                    if (!network.wpsCode.isNullOrEmpty() && isValidWpsPin(network.wpsCode)) {
                        pins.add(network.wpsCode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("PixiePinProvider", "Error reading local database", e)
        }
        return pins.distinct()
    }

    private fun searchNeighborPinsWpsDb(bssid: String): List<String> {
        val pins = mutableListOf<String>()
        try {
            val dbFile = getFileFromAssets("wps_pin.db") ?: return pins
            val db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            )

            val neighborPrefixes = (0..0xFF).map {
                val bssidBytes = bssid.replace(":", "").uppercase()
                val prefix = bssidBytes.substring(0, 6)
                val lastByte =
                    Integer.parseInt(bssidBytes.substring(6, 8), 16)
                val neighborLast = (lastByte + 1) % 256
                prefix + neighborLast.toString(16).padStart(2, '0')
            }

            neighborPrefixes.forEach { prefix ->
                val macWithColon =
                    "${prefix.substring(0, 2)}:${prefix.substring(2, 4)}:${
                        prefix.substring(
                            4,
                            6
                        )
                    }:${prefix.substring(6, 8)}"
                val cursor = db.rawQuery(
                    "SELECT pin FROM pins WHERE mac LIKE ?",
                    arrayOf("$macWithColon%")
                )
                cursor.use {
                    while (it.moveToNext()) {
                        val pin = it.getString(it.getColumnIndexOrThrow("pin"))
                        if (isValidWpsPin(pin)) pins.add(pin)
                    }
                }
            }
            db.close()
        } catch (e: Exception) {
            Log.w("PixiePinProvider", "Error searching wps_pin.db neighbors", e)
        }
        return pins.distinct()
    }

    private fun getFileFromAssets(fileName: String): File? {
        return try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) {
                context.assets.open(fileName).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            file
        } catch (e: Exception) {
            Log.w("PixiePinProvider", "Failed to extract $fileName from assets", e)
            null
        }
    }
}
