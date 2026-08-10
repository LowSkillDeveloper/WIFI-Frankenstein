package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import androidx.core.net.toUri
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeMetadataDbHelper
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class PaginationHelper(
    private val context: Context,
    private val query: String,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>,
    private val filters: Set<FilterType>,
    private val searchMode: SearchMode
) {
    private val wifiHelpers = ConcurrentHashMap<String, SQLite3WiFiHelper>()
    private val customHelpers = ConcurrentHashMap<String, SQLiteCustomHelper>()
    private val sourceCache = ConcurrentHashMap<String, List<SearchResult>>()

    companion object {
        private const val TAG = "PaginationHelper"
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
        val dbResults = perSourceResults.flatten().toMutableList()

        val syntheticResults = processSyntheticSources(prefixLimit)
        dbResults.addAll(syntheticResults)

        val blockSizes = perSourceResults.map { it.size } + syntheticResults.size
        val hasMore = blockSizes.any { it >= prefixLimit }

        val page = dbResults.distinctBy { resultKey(it) }.drop(offset).take(limit)

        Log.d(TAG, "Total results for page: ${page.size}")
        PageSlice(page, hasMore)
    }

    private suspend fun processSyntheticSources(limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val hasLocalAppDbItem = dbList.any {
            selectedSources.contains(it.id) && it.dbType == DbType.LOCAL_APP_DB
        }

        if (selectedSources.contains(context.getString(R.string.source_inapp_database)) && !hasLocalAppDbItem) {
            try {
                LocalAppDbHelper(context).use { localHelper ->
                    val localMatches = localHelper.searchRecordsWithFiltersOptimized(
                        query,
                        filters.contains(FilterType.ESSID),
                        filters.contains(FilterType.BSSID),
                        filters.contains(FilterType.PASSWORD),
                        filters.contains(FilterType.WPS_PIN)
                    )
                    results.addAll(localMatches.map { wifi ->
                        SearchResult(
                            ssid = wifi.wifiName ?: "",
                            bssid = wifi.macAddress ?: "",
                            password = wifi.wifiPassword,
                            wpsPin = null,
                            source = context.getString(R.string.source_inapp_database)
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local app DB search failed", e)
            }
        }

        if (selectedSources.contains(context.getString(R.string.handshake_storage))) {
            try {
                HandshakeMetadataDbHelper(context).use { metadataDb ->
                    val handshakes = metadataDb.getAll()
                    val q = query.lowercase()
                    val filtered = handshakes.filter { hs ->
                        (hs.essid?.lowercase()?.contains(q) == true) ||
                                (hs.bssid?.lowercase()?.contains(q) == true)
                    }
                    results.addAll(filtered.map { hs ->
                        SearchResult(
                            ssid = hs.essid ?: "",
                            bssid = hs.bssid ?: "",
                            password = null,
                            wpsPin = null,
                            source = context.getString(R.string.handshake_storage)
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handshake storage search failed", e)
            }
        }

        return results.take(limit)
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
        Log.d(TAG, "Closing PaginationHelper")
        cancelSearch()
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

    fun cancelSearch() {
        wifiHelpers.values.forEach { it.cancelSearch() }
        customHelpers.values.forEach { it.cancelSearch() }
    }

    private suspend fun process3WiFiDatabase(
        dbItem: DbItem,
        offset: Int,
        limit: Int
    ): List<SearchResult> {
        Log.d(TAG, "=== Starting process3WiFiDatabase ===")
        Log.d(TAG, "DbItem: ${dbItem.path}, Offset: $offset, Limit: $limit")

        return try {
            val helper = getWifiHelper(dbItem)
            val db = helper.database
            if (db == null) {
                Log.e(TAG, "Database is null")
                return emptyList()
            }

            Log.d(TAG, "Database opened successfully")

            val searchTable = DatabaseTypeUtils.getMainTableName(db)
            Log.d(TAG, "Search table: $searchTable")

            var searchFields = filters.mapNotNull { filter ->
                when (filter) {
                    FilterType.BSSID -> "BSSID"
                    FilterType.ESSID -> "ESSID"
                    FilterType.PASSWORD -> "WiFiKey"
                    FilterType.WPS_PIN -> "WPSPIN"
                }
            }.toSet()

            if (searchFields.isEmpty()) {
                Log.d(TAG, "No search filters selected, defaulting to ESSID+BSSID")
                searchFields = setOf("ESSID", "BSSID")
            }

            Log.d(TAG, "Search fields: $searchFields")

            val dbResults = helper.searchNetworksByBSSIDAndFieldsPaginated(
                query, searchFields, searchMode, offset, limit
            )

            Log.d(TAG, "Raw database results: ${dbResults.size} items")

            val searchResults = dbResults.map { result ->
                val rawBssid = result["BSSID"] as? Long
                Log.d(TAG, "Processing result: rawBssid=$rawBssid, ESSID=${result["ESSID"]}")

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

            Log.d(TAG, "Final search results: ${searchResults.size} items")
            searchResults
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

            var searchFields = filters.mapNotNull { filter ->
                when (filter) {
                    FilterType.BSSID -> columnMap["mac"]
                    FilterType.ESSID -> columnMap["essid"]
                    FilterType.PASSWORD -> columnMap["wifi_pass"]
                    FilterType.WPS_PIN -> columnMap["wps_pin"]
                }
            }.toSet()

            if (searchFields.isEmpty()) {
                val essidField = columnMap["essid"]
                val macField = columnMap["mac"]
                Log.d(
                    TAG,
                    "No search filters selected, defaulting to: essid=$essidField, mac=$macField"
                )
                searchFields = setOfNotNull(essidField, macField)
            }

            val dbResults = helper.searchNetworksByBSSIDAndFieldsPaginated(
                tableName, columnMap, query, searchFields, searchMode, offset, limit
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
            val searchFields = filters.mapNotNull { filter ->
                when (filter) {
                    FilterType.BSSID -> "mac"
                    FilterType.ESSID -> "name"
                    FilterType.PASSWORD -> "password"
                    FilterType.WPS_PIN -> "wps"
                }
            }.toSet()

            LocalAppDbHelper(context).use { localDbHelper ->
                val dbResults = localDbHelper.searchRecordsWithFiltersPaginated(
                    query, searchFields, offset, limit, searchMode
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing local database", e)
            emptyList()
        }
    }

    private fun isValidBssid(query: String): Boolean {
        return query.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$"))
    }

    private fun resultKey(result: SearchResult): String =
        "${result.source}|${result.bssid}|${result.ssid}|${result.password}|${result.wpsPin}|${result.latitude}|${result.longitude}"

    private fun Any?.toDoubleCompat(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    private suspend fun processApiDatabase(dbItem: DbItem, limit: Int): List<SearchResult> {
        return try {
            if (!filters.contains(FilterType.BSSID)) {
                return emptyList()
            }

            if (!isValidBssid(query)) {
                return emptyList()
            }

            val apiHelper = API3WiFiHelper(context, dbItem.path, dbItem.apiKey ?: "000000000000")
            val bssids = listOf(query)
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