package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.*
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class PaginationHelper(
    private val context: Context,
    private val query: String,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>,
    private val filters: Set<Int>,
    private val searchWholeWords: Boolean
) {
    companion object {
        private const val TAG = "PaginationHelper"
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
            SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val db = helper.database ?: return emptyList()

                val indexLevel = DatabaseIndices.determineIndexLevel(db)
                val dbType = DatabaseTypeUtils.determineDbType(db)
                val searchTable = when (dbType) {
                    DatabaseTypeUtils.WiFiDbType.TYPE_NETS -> "nets"
                    DatabaseTypeUtils.WiFiDbType.TYPE_BASE -> "base"
                    else -> return emptyList()
                }

                val searchFields = filters.mapNotNull { filter ->
                    when(filter) {
                        R.string.filter_bssid -> "BSSID"
                        R.string.filter_essid -> "ESSID"
                        R.string.filter_wifi_password -> "WiFiKey"
                        R.string.filter_wps_pin -> "WPSPIN"
                        else -> null
                    }
                }.toSet()

                val dbResults = helper.searchNetworksByBSSIDAndFieldsPaginated(
                    query, searchFields, searchWholeWords, offset, limit
                )

                dbResults.map { result ->
                    val rawBssid = result["BSSID"] as? Long
                    SearchResult(
                        ssid = result["ESSID"] as? String ?: "",
                        bssid = rawBssid?.toString() ?: "",
                        password = result["WiFiKey"] as? String,
                        wpsPin = result["WPSPIN"]?.toString(),
                        source = dbItem.path,
                        latitude = result["latitude"] as? Double ?: (result["latitude"] as? String)?.toDoubleOrNull() ?: (result["lat"] as? Double) ?: (result["lat"] as? String)?.toDoubleOrNull(),
                        longitude = result["longitude"] as? Double ?: (result["longitude"] as? String)?.toDoubleOrNull() ?: (result["lon"] as? Double) ?: (result["lon"] as? String)?.toDoubleOrNull(),
                        rawBssid = rawBssid
                    )
                }
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

            SQLiteCustomHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                val searchFields = filters.mapNotNull { filter ->
                    when(filter) {
                        R.string.filter_bssid -> columnMap["mac"]
                        R.string.filter_essid -> columnMap["essid"]
                        R.string.filter_wifi_password -> columnMap["wifi_pass"]
                        R.string.filter_wps_pin -> columnMap["wps_pin"]
                        else -> null
                    }
                }.toSet()

                val dbResults = helper.searchNetworksByBSSIDAndFieldsPaginated(
                    tableName, columnMap, query, searchFields, searchWholeWords, offset, limit
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
                        source = dbItem.path,
                        latitude = result[latField] as? Double ?: (result[latField] as? String)?.toDoubleOrNull(),
                        longitude = result[lonField] as? Double ?: (result[lonField] as? String)?.toDoubleOrNull()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing custom database", e)
            emptyList()
        }
    }

    private suspend fun processLocalDatabase(offset: Int, limit: Int): List<SearchResult> {
        return try {
            val searchFields = filters.mapNotNull { filter ->
                when(filter) {
                    R.string.filter_bssid -> "mac"
                    R.string.filter_essid -> "name"
                    R.string.filter_wifi_password -> "password"
                    R.string.filter_wps_pin -> "wps"
                    else -> null
                }
            }.toSet()

            val localDbHelper = LocalAppDbHelper(context)
            val dbResults = localDbHelper.searchRecordsWithFiltersPaginated(
                query, searchFields, offset, limit
            )

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
            if (!filters.contains(R.string.filter_bssid)) {
                return emptyList()
            }

            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
            val bssids = listOf(query)
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
                        latitude = network["latitude"] as? Double ?: (network["latitude"] as? String)?.toDoubleOrNull() ?: (network["lat"] as? Double) ?: (network["lat"] as? String)?.toDoubleOrNull(),
                        longitude = network["longitude"] as? Double ?: (network["longitude"] as? String)?.toDoubleOrNull() ?: (network["lon"] as? Double) ?: (network["lon"] as? String)?.toDoubleOrNull()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing API database", e)
            emptyList()
        }
    }
}