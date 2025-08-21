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

class AdvancedPaginationHelper(
    private val context: Context,
    private val advancedQuery: AdvancedSearchQuery,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>
) {
    companion object {
        private const val TAG = "AdvancedPaginationHelper"
    }

    suspend fun loadPage(offset: Int, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading page: offset=$offset, limit=$limit")

        val filteredDbList = dbList.filter { dbItem -> selectedSources.contains(dbItem.path) }
        Log.d(TAG, "Processing ${filteredDbList.size} databases")

        val results = filteredDbList.mapIndexed { index, dbItem ->
            async<List<SearchResult>> {
                Log.d(TAG, "Processing database ${index+1}/${filteredDbList.size}: ${dbItem.path}")

                when (dbItem.dbType) {
                    DbType.SQLITE_FILE_3WIFI -> process3WiFiDatabase(dbItem, offset, limit)
                    DbType.SQLITE_FILE_CUSTOM -> processCustomDatabase(dbItem, offset, limit)
                    DbType.LOCAL_APP_DB -> processLocalDatabase(offset, limit)
                    DbType.WIFI_API -> processApiDatabase(dbItem)
                    else -> {
                        Log.d(TAG, "Unsupported db type: ${dbItem.dbType}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()

        Log.d(TAG, "Total results for page: ${results.size}")
        results.take(limit)
    }

    private suspend fun process3WiFiDatabase(dbItem: DbItem, offset: Int, limit: Int): List<SearchResult> {
        return try {
            val helper = SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath)
            try {
                val db = helper.database ?: return emptyList()
                val dbResults = helper.searchNetworksByAdvancedQuery(advancedQuery, offset, limit)

                dbResults.map { result ->
                    val rawBssid = result["BSSID"] as? Long
                    SearchResult(
                        ssid = result["ESSID"] as? String ?: "",
                        bssid = rawBssid?.toString() ?: "",
                        password = result["WiFiKey"] as? String,
                        wpsPin = result["WPSPIN"]?.toString(),
                        source = dbItem.path,
                        latitude = result["latitude"] as? Double,
                        longitude = result["longitude"] as? Double,
                        rawBssid = rawBssid
                    )
                }
            } finally {
                helper.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing 3WiFi database", e)
            emptyList()
        }
    }

    private suspend fun processCustomDatabase(dbItem: DbItem, offset: Int, limit: Int): List<SearchResult> {
        return try {
            val tableName = dbItem.tableName ?: return emptyList()
            val columnMap = dbItem.columnMap ?: return emptyList()

            val helper = SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath)
            try {
                val dbResults = helper.searchNetworksByAdvancedQuery(tableName, columnMap, advancedQuery, offset, limit)

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
                        source = dbItem.path,
                        latitude = result[latField] as? Double,
                        longitude = result[lonField] as? Double
                    )
                }
            } finally {
                helper.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing custom database", e)
            emptyList()
        }
    }

    private suspend fun processLocalDatabase(offset: Int, limit: Int): List<SearchResult> {
        return try {
            val localDbHelper = LocalAppDbHelper(context)
            val dbResults = localDbHelper.searchRecordsWithAdvancedQuery(advancedQuery, offset, limit)

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
        } catch (e: Exception) {
            Log.e(TAG, "Error processing local database", e)
            emptyList()
        }
    }

    private suspend fun processApiDatabase(dbItem: DbItem): List<SearchResult> {
        return try {
            if (advancedQuery.bssid.isBlank()) {
                return emptyList()
            }

            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
            val bssids = listOf(advancedQuery.bssid)
            val apiResults = apiHelper.searchNetworksByBSSIDs(bssids)

            apiResults.entries.flatMap { entry ->
                val networks = entry.value
                networks.map { network ->
                    SearchResult(
                        ssid = network["essid"] as? String ?: "",
                        bssid = network["bssid"] as? String ?: "",
                        password = network["key"] as? String,
                        wpsPin = network["wps"] as? String,
                        source = dbItem.path,
                        latitude = network["latitude"] as? Double,
                        longitude = network["longitude"] as? Double
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing API database", e)
            emptyList()
        }
    }
}