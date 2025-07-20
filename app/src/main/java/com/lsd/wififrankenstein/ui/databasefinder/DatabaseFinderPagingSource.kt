package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class DatabaseFinderPagingSource(
    private val context: Context,
    private val query: String,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>,
    private val filters: Set<Int>,
    private val searchWholeWords: Boolean,
) : PagingSource<Int, SearchResult>() {

    companion object {
        private const val TAG = "DatabaseFinder"
    }

    private val pageSize = 20
    private var totalResults = emptyList<SearchResult>()
    private var isInitialized = false

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting load() with params key: ${params.key}")

        try {
            if (!isInitialized) {
                totalResults = loadAllResults()
                isInitialized = true
                Log.d(TAG, "Loaded ${totalResults.size} total results in ${System.currentTimeMillis() - startTime}ms")
            }

            val position = params.key ?: 0
            val start = position * pageSize
            val end = minOf(start + pageSize, totalResults.size)

            Log.d(TAG, "Returning page position=$position, start=$start, end=$end, size=${end-start}")

            val timeTaken = System.currentTimeMillis() - startTime
            Log.d(TAG, "load() completed in ${timeTaken}ms")

            return LoadResult.Page(
                data = totalResults.subList(start, end),
                prevKey = if (position > 0) position - 1 else null,
                nextKey = if (end < totalResults.size) position + 1 else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in load(): ${e.message}", e)
            return LoadResult.Error(e)
        }
    }

    private suspend fun loadAllResults(): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting loadAllResults()")
            Log.d(TAG, "Query: $query")
            Log.d(TAG, "Search whole words: $searchWholeWords")
            Log.d(TAG, "Selected filters: $filters")

            val filteredDbList = dbList.filter { dbItem -> selectedSources.contains(dbItem.path) }
            Log.d(TAG, "Total databases to process: ${filteredDbList.size}")

            val results = filteredDbList.mapIndexed { index, dbItem ->
                async<List<SearchResult>> {
                    val dbStartTime = System.currentTimeMillis()
                    Log.d(TAG, "Processing database ${index+1}/${filteredDbList.size}:")
                    Log.d(TAG, "  Path: ${dbItem.path}")
                    Log.d(TAG, "  Type: ${dbItem.dbType}")
                    Log.d(TAG, "  Table name: ${dbItem.tableName}")
                    Log.d(TAG, "  Column map: ${dbItem.columnMap}")

                    when (dbItem.dbType) {
                        DbType.SQLITE_FILE_3WIFI -> {
                            Log.d(TAG, "Processing 3WiFi SQLite database")
                            SQLite3WiFiHelper(context, dbItem.path.toUri(), dbItem.directPath).use { helper ->
                                val db = helper.database
                                if (db != null) {
                                    val indexLevel = DatabaseIndices.determineIndexLevel(db)
                                    Log.d(TAG, "Index level: $indexLevel")

                                    val dbType = DatabaseTypeUtils.determineDbType(db)
                                    val searchTable = when (dbType) {
                                        DatabaseTypeUtils.WiFiDbType.TYPE_NETS -> "nets"
                                        DatabaseTypeUtils.WiFiDbType.TYPE_BASE -> "base"
                                        else -> {
                                            Log.e(TAG, "Unknown database type, cannot search")
                                            return@use emptyList<SearchResult>()
                                        }
                                    }

                                    Log.d(TAG, "Database type: $dbType, using table: $searchTable")

                                    val searchFields = filters.mapNotNull { filter ->
                                        when(filter) {
                                            R.string.filter_bssid -> "BSSID"
                                            R.string.filter_essid -> "ESSID"
                                            R.string.filter_wifi_password -> "WiFiKey"
                                            R.string.filter_wps_pin -> "WPSPIN"
                                            else -> null
                                        }
                                    }.toSet()

                                    Log.d(TAG, "3WiFi search fields: ${searchFields.joinToString()}")
                                    if ((searchFields.contains("WiFiKey") || searchFields.contains("WPSPIN")) &&
                                        indexLevel < DatabaseIndices.IndexLevel.FULL) {
                                        Log.w(TAG, "Search includes WiFiKey/WPSPIN but database doesn't have FULL indexes - search may be slow")
                                    }
                                    if (searchFields.contains("WiFiKey") || searchFields.contains("WPSPIN")) {
                                        if (indexLevel < DatabaseIndices.IndexLevel.FULL) {
                                            Log.w(TAG, "Database doesn't have FULL indexes, search for WiFiKey/WPSPIN may be slow")
                                        }
                                    }
                                    Log.d(TAG, "Using index level $indexLevel for optimization")

                                    val searchStartTime = System.currentTimeMillis()
                                    val dbResults = helper.searchNetworksByBSSIDAndFields(
                                        query,
                                        searchFields,
                                        searchWholeWords
                                    )
                                    Log.d(TAG, "3WiFi search completed in ${System.currentTimeMillis() - searchStartTime}ms")
                                    Log.d(TAG, "3WiFi found results: ${dbResults.size}")

                                    dbResults.map { result ->
                                        SearchResult(
                                            ssid = result["ESSID"] as? String ?: "",
                                            bssid = result["BSSID"]?.toString() ?: "",
                                            password = result["WiFiKey"] as? String,
                                            wpsPin = result["WPSPIN"]?.toString(),
                                            source = dbItem.path,
                                            latitude = result["latitude"] as? Double ?: (result["latitude"] as? String)?.toDoubleOrNull() ?: (result["lat"] as? Double) ?: (result["lat"] as? String)?.toDoubleOrNull(),
                                            longitude = result["longitude"] as? Double ?: (result["longitude"] as? String)?.toDoubleOrNull() ?: (result["lon"] as? Double) ?: (result["lon"] as? String)?.toDoubleOrNull()
                                        )
                                    }
                                } else {
                                    Log.e(TAG, "Database is null, cannot search")
                                    emptyList()
                                }
                            }
                        }
                        DbType.SQLITE_FILE_CUSTOM -> {
                            val tableName = dbItem.tableName
                            val columnMap = dbItem.columnMap
                            if (tableName != null && columnMap != null) {
                                com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper(context,
                                    dbItem.path.toUri(), dbItem.directPath).use { helper ->
                                    val searchFields = filters.mapNotNull { filter ->
                                        when(filter) {
                                            R.string.filter_bssid -> columnMap["mac"]
                                            R.string.filter_essid -> columnMap["essid"]
                                            R.string.filter_wifi_password -> columnMap["wifi_pass"]
                                            R.string.filter_wps_pin -> columnMap["wps_pin"]
                                            else -> null
                                        }
                                    }.toSet()

                                    Log.d(TAG, "Custom DB search fields using column map: ${searchFields.joinToString()}")
                                    val dbResults = helper.searchNetworksByBSSIDAndFields(
                                        tableName,
                                        columnMap,
                                        query,
                                        searchFields,
                                        searchWholeWords
                                    )
                                    Log.d(TAG, "Custom DB found results: ${dbResults.size}")
                                    Log.d(TAG, "Custom DB results: $dbResults")

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
                                        ).also {
                                            Log.d(TAG, "Mapped to SearchResult: $it")
                                        }
                                    }
                                }
                            } else {
                                Log.e(TAG, "Custom DB missing table name or column map")
                                emptyList<SearchResult>()
                            }
                        }
                        DbType.LOCAL_APP_DB -> {
                            Log.d(TAG, "Processing Local App database")
                            val searchFields = filters.mapNotNull { filter ->
                                when(filter) {
                                    R.string.filter_bssid -> "mac"
                                    R.string.filter_essid -> "name"
                                    R.string.filter_wifi_password -> "password"
                                    R.string.filter_wps_pin -> "wps"
                                    else -> null
                                }
                            }.toSet()

                            Log.d(TAG, "Local DB search fields: ${searchFields.joinToString()}")
                            val localDbHelper = LocalAppDbHelper(context)

                            val dbResults = localDbHelper.searchRecordsWithFiltersOptimized(
                                query,
                                searchFields.contains("name"),
                                searchFields.contains("mac"),
                                searchFields.contains("password"),
                                searchFields.contains("wps")
                            )

                            Log.d(TAG, "Local DB found results: ${dbResults.size}")
                            Log.d(TAG, "Local DB results: $dbResults")

                            dbResults.map { network ->
                                SearchResult(
                                    ssid = network.wifiName,
                                    bssid = network.macAddress,
                                    password = network.wifiPassword,
                                    wpsPin = network.wpsCode,
                                    source = "Local Database",
                                    latitude = if (network.latitude != 0.0) network.latitude else null,
                                    longitude = if (network.longitude != 0.0) network.longitude else null
                                ).also {
                                    Log.d(TAG, "Mapped Local DB result: $it")
                                }
                            }
                        }
                        DbType.WIFI_API -> {
                            Log.d(TAG, "Processing WiFi API")
                            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")

                            if (filters.contains(R.string.filter_bssid)) {
                                val bssids = listOf(query)
                                Log.d(TAG, "API BSSIDs to search: $bssids")

                                val apiResults = apiHelper.searchNetworksByBSSIDs(bssids)
                                Log.d(TAG, "API found results: ${apiResults.size}")
                                Log.d(TAG, "API results: $apiResults")

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
                                        ).also {
                                            Log.d(TAG, "Mapped API result: $it")
                                        }
                                    }
                                }
                            } else {
                                Log.d(TAG, "Skipping API search - BSSID filter not enabled")
                                emptyList<SearchResult>()
                            }
                        }
                        else -> {
                            Log.d(TAG, "Skipping unsupported db type: ${dbItem.dbType}")
                            emptyList<SearchResult>()
                        }
                    }.also {
                        Log.d(TAG, "Database ${index+1} processing completed in ${System.currentTimeMillis() - dbStartTime}ms")
                    }
                }
            }.awaitAll().flatten()

            Log.d(TAG, "Total results across all DBs: ${results.size}")
            results

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAllResults(): ${e.message}", e)
            e.printStackTrace()
            emptyList<SearchResult>()
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SearchResult>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}