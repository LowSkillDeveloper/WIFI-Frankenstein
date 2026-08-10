package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import androidx.core.net.toUri
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AdvancedPaginationHelper(
    private val context: Context,
    private val advancedQuery: AdvancedSearchQuery,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>
) {
    private val wifiHelpers = ConcurrentHashMap<String, SQLite3WiFiHelper>()
    private val customHelpers = ConcurrentHashMap<String, SQLiteCustomHelper>()
    private val sourceCache = ConcurrentHashMap<String, List<SearchResult>>()

    companion object {
        private const val TAG = "AdvancedPaginationHelper"
    }

    private fun resultKey(result: SearchResult): String =
        "${result.source}|${result.bssid}|${result.ssid}|${result.password}|${result.wpsPin}|${result.latitude}|${result.longitude}"

    private fun Any?.toDoubleCompat(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    suspend fun loadPage(offset: Int, limit: Int): PageSlice = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading page: offset=$offset, limit=$limit")

        val filteredDbList = dbList.filter { dbItem -> selectedSources.contains(dbItem.id) }
        Log.d(TAG, "Processing ${filteredDbList.size} databases")

        val prefixLimit = offset + limit

        val perSourcePairs = filteredDbList.mapIndexed { index, dbItem ->
            async<Pair<String, List<SearchResult>>> {
                Log.d(
                    TAG,
                    "Processing database ${index + 1}/${filteredDbList.size}: ${dbItem.path}"
                )

                val cached = sourceCache[dbItem.id]
                if (cached != null && cached.size >= prefixLimit) {
                    Log.d(TAG, "Using cached results for ${dbItem.id} (size=${cached.size})")
                    return@async dbItem.id to cached
                }

                Log.d(
                    TAG,
                    "DbItem: path=${dbItem.path}, dbType=${dbItem.dbType}, directPath=${dbItem.directPath}"
                )

                val results = when (dbItem.dbType) {
                    DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI ->
                        process3WiFiDatabase(dbItem, 0, prefixLimit)

                    DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM ->
                        processCustomDatabase(dbItem, 0, prefixLimit)

                    DbType.LOCAL_APP_DB -> processLocalDatabase(0, prefixLimit)
                    DbType.WIFI_API -> processApiDatabase(dbItem, prefixLimit)
                    else -> {
                        Log.d(TAG, "Unsupported db type: ${dbItem.dbType}")
                        emptyList()
                    }
                }
                dbItem.id to results
            }
        }.awaitAll()

        perSourcePairs.forEach { (id, results) -> sourceCache[id] = results }

        val perSourceResults = perSourcePairs.map { it.second }
        val blockSizes = perSourceResults.map { it.size }
        val hasMore = blockSizes.any { it >= prefixLimit }

        val page = perSourceResults.flatten().distinctBy { resultKey(it) }.drop(offset).take(limit)

        Log.d(TAG, "Total results for page: ${page.size}")
        PageSlice(page, hasMore)
    }

    private fun getWifiHelper(dbItem: DbItem): SQLite3WiFiHelper {
        return wifiHelpers.getOrPut(dbItem.id) {
            Log.d(TAG, "Creating SQLite3WiFiHelper for ${dbItem.id}")
            SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath)
        }
    }

    private fun getCustomHelper(dbItem: DbItem): SQLiteCustomHelper {
        return customHelpers.getOrPut(dbItem.id) {
            Log.d(TAG, "Creating SQLiteCustomHelper for ${dbItem.id}")
            SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath)
        }
    }

    fun close() {
        Log.d(TAG, "Closing AdvancedPaginationHelper")
        sourceCache.clear()
        wifiHelpers.values.forEach { helper ->
            try {
                helper.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing 3WiFi helper", e)
            }
        }
        wifiHelpers.clear()
        customHelpers.values.forEach { helper ->
            try {
                helper.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing custom helper", e)
            }
        }
        customHelpers.clear()
    }

    private suspend fun process3WiFiDatabase(
        dbItem: DbItem,
        offset: Int,
        limit: Int
    ): List<SearchResult> {
        return try {
            val helper = getWifiHelper(dbItem)
            val db = helper.database ?: return emptyList()
            val dbResults = helper.searchNetworksByAdvancedQuery(advancedQuery, offset, limit)

            dbResults.map { result ->
                val rawBssid = result["BSSID"] as? Long
                SearchResult(
                    ssid = result["ESSID"] as? String ?: "",
                    bssid = rawBssid?.toString() ?: "",
                    password = result["WiFiKey"] as? String,
                    wpsPin = result["WPSPIN"]?.toString(),
                    source = dbItem.id,
                    latitude = (result["latitude"] ?: result["lat"]).toDoubleCompat(),
                    longitude = (result["longitude"] ?: result["lon"]).toDoubleCompat(),
                    rawBssid = rawBssid
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing 3WiFi database", e)
            emptyList()
        }
    }

    private suspend fun processCustomDatabase(
        dbItem: DbItem,
        offset: Int,
        limit: Int
    ): List<SearchResult> {
        return try {
            val tableName = dbItem.tableName ?: return emptyList()
            val columnMap = dbItem.columnMap ?: return emptyList()

            val helper = getCustomHelper(dbItem)
            val dbResults = helper.searchNetworksByAdvancedQuery(
                tableName,
                columnMap,
                advancedQuery,
                offset,
                limit
            )

            dbResults.map { result ->
                val macField = columnMap["mac"] ?: "mac"
                val essidField = columnMap["essid"] ?: "essid"
                val passwordField = columnMap["wifi_pass"] ?: "wifi_pass"
                val wpsPinField = columnMap["wps_pin"] ?: "wps_pin"
                val latField = columnMap["latitude"] ?: "latitude"
                val lonField = columnMap["longitude"] ?: "longitude"

                SearchResult(
                    ssid = result[essidField] as? String ?: "",
                    bssid = result[macField]?.toString() ?: "",
                    password = result[passwordField] as? String,
                    wpsPin = result[wpsPinField]?.toString(),
                    source = dbItem.id,
                    latitude = result[latField].toDoubleCompat(),
                    longitude = result[lonField].toDoubleCompat()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing custom database", e)
            emptyList()
        }
    }

    private suspend fun processLocalDatabase(offset: Int, limit: Int): List<SearchResult> {
        return try {
            LocalAppDbHelper(context).use { localDbHelper ->
                val dbResults =
                    localDbHelper.searchRecordsWithAdvancedQuery(advancedQuery, offset, limit)

                dbResults.map { network ->
                    SearchResult(
                        ssid = network.wifiName,
                        bssid = network.macAddress,
                        password = network.wifiPassword,
                        wpsPin = network.wpsCode,
                        source = "local_db",
                        latitude = if (network.latitude != 0.0) network.latitude else null,
                        longitude = if (network.longitude != 0.0) network.longitude else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing local database", e)
            emptyList()
        }
    }

    private suspend fun processApiDatabase(dbItem: DbItem, limit: Int): List<SearchResult> {
        return try {
            if (advancedQuery.bssid.isBlank()) {
                return emptyList()
            }

            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
            val bssids = listOf(advancedQuery.bssid)
            val apiResults = apiHelper.searchNetworksByBSSIDs(bssids)

            apiResults.entries.flatMap { entry ->
                val networks = entry.value
                networks.take(limit).map { network ->
                    SearchResult(
                        ssid = network["essid"] as? String ?: "",
                        bssid = network["bssid"] as? String ?: "",
                        password = network["key"] as? String,
                        wpsPin = network["wps"] as? String,
                        source = dbItem.id,
                        latitude = (network["latitude"] ?: network["lat"]).toDoubleCompat(),
                        longitude = (network["longitude"] ?: network["lon"]).toDoubleCompat()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing API database", e)
            emptyList()
        }
    }
}