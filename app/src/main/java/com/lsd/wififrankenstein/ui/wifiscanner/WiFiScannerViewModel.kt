package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class WiFiScannerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val offlineResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
    private val onlineResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
    private val localResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
    private val customResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
    private var lastCheckedType: DbType? = null

    private var sqliteCustomHelper: SQLiteCustomHelper? = null

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val wifiManager: WifiManager by lazy {
        application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val localAppDbHelper = LocalAppDbHelper(application)

    private val _searchByMac = MutableLiveData(true)
    val searchByMac: LiveData<Boolean> = _searchByMac

    private val _wifiList = MutableLiveData<List<ScanResult>>()
    val wifiList: LiveData<List<ScanResult>> = _wifiList

    private val _scanState = MutableLiveData<String>()
    val scanState: LiveData<String> = _scanState

    private val _databaseResults = MutableLiveData<Map<String, List<NetworkDatabaseResult>>>()
    val databaseResults: LiveData<Map<String, List<NetworkDatabaseResult>>> = _databaseResults

    private val _isChecking = MutableLiveData<Boolean>()
    val isChecking: LiveData<Boolean> = _isChecking

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private var sqlite3WiFiHelper: SQLite3WiFiHelper? = null

    private val api3WiFiHelpers = mutableMapOf<String, API3WiFiHelper>()

    fun setSearchType(searchByMac: Boolean) {
        _searchByMac.value = searchByMac
    }

    fun clearData() {
        _wifiList.postValue(emptyList())
        _databaseResults.postValue(emptyMap())
    }

    private fun isDuplicate(existingResults: Map<String, List<NetworkDatabaseResult>>, newResult: NetworkDatabaseResult): Boolean {
        val existingList = existingResults[newResult.network.BSSID.lowercase(Locale.ROOT)] ?: return false

        return existingList.any { existing ->
            existing.network.BSSID == newResult.network.BSSID &&
                    existing.databaseInfo == newResult.databaseInfo &&
                    existing.databaseName == newResult.databaseName
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun startWifiScan() {
        if (hasLocationPermission()) {
            viewModelScope.launch {
                try {
                    if (prefs.getBoolean("full_cleanup", false)) {
                        clearData()
                    }

                    _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))

                    val networks = withContext(Dispatchers.IO) {
                        if (isDummyNetworkModeEnabled()) {
                            createDummyNetworks()
                        } else {
                            if (wifiManager.startScan()) wifiManager.scanResults.sortedByDescending { it.level } else null
                        }
                    }

                    if (networks != null) {
                        _wifiList.postValue(networks)
                    } else {
                        _scanState.postValue(getApplication<Application>().getString(R.string.failed_to_start_wifi_scan))
                    }

                } catch (_: SecurityException) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
                } catch (e: Exception) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.error_general, e.message))
                }
            }
        } else {
            _scanState.value = getApplication<Application>().getString(R.string.location_permission_required)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createDummyNetworks(): List<ScanResult> {
        return listOf(
            createDummyNetwork("DummyNetwork1", "60:14:66:9d:8a:0c", "[WPA2-PSK-CCMP][ESS]", -10, 2412),
            createDummyNetwork("DummyNetwork2", "00:d0:41:d0:08:8a", "[WPA2-PSK-CCMP][ESS]", -10, 2417),
            createDummyNetwork("DummyNetwork3", "00:d0:41:d0:48:8a", "[WPA2-PSK-CCMP][ESS]", -20, 2417),
            createDummyNetwork("DummyNetwork4", "24:bc:f8:aa:e6:e0", "[WPA2-PSK-CCMP][ESS]", -15, 2422),
            createDummyNetwork("DummyNetwork5_Upper", "E0:19:54:14:6C:76", "[WPA2-PSK-CCMP][ESS]", -30, 2422),
        createDummyNetwork("DummyNetwork5", "e0:19:54:14:6c:76", "[WPA2-PSK-CCMP][ESS]", -25, 2422)
        )
    }


    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshWifiList() {
        _wifiList.value?.let { wifiList ->
            _wifiList.postValue(wifiList)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createDummyNetwork(ssid: String, bssid: String, capabilities: String, level: Int, frequency: Int): ScanResult {
        return ScanResult().apply {
            SSID = ssid
            BSSID = bssid
            this.capabilities = capabilities
            this.level = level
            this.frequency = frequency
        }
    }

    private fun isDummyNetworkModeEnabled(): Boolean {
        return prefs.getBoolean("dummy_network_mode", false)
    }

    fun initializeSQLite3WiFiHelper(dbUri: Uri, directPath: String?) {
        sqlite3WiFiHelper = SQLite3WiFiHelper(getApplication(), dbUri, directPath)
    }


    fun checkNetworksInDatabases(networks: List<ScanResult>, databases: List<DbItem>) {
        if (_isChecking.value == true) return

        viewModelScope.launch {
            _isChecking.postValue(true)
            try {
                val mergeResults = prefs.getBoolean("merge_results", false)
                if (!mergeResults) {
                    when (lastCheckedType) {
                        DbType.SQLITE_FILE_3WIFI -> offlineResults.clear()
                        DbType.SQLITE_FILE_CUSTOM -> customResults.clear()
                        DbType.WIFI_API -> onlineResults.clear()
                        DbType.LOCAL_APP_DB -> localResults.clear()
                        DbType.SMARTLINK_SQLITE_FILE_3WIFI,
                        DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> offlineResults.clear()
                        null -> {}
                    }
                }

                processLocalDatabase(networks, localResults)

                val searchByMac = _searchByMac.value != false
                databases.forEach { db ->
                    when (db.dbType) {
                        DbType.SQLITE_FILE_3WIFI -> {
                            if (searchByMac) {
                                processOfflineDatabaseAsync(db, networks, offlineResults)
                            } else {
                                processOfflineDatabaseByESSIDAsync(db, networks, offlineResults)
                            }
                            lastCheckedType = DbType.SQLITE_FILE_3WIFI
                        }
                        DbType.SQLITE_FILE_CUSTOM -> {
                            if (searchByMac) {
                                processCustomDatabaseAsync(db, networks, customResults)
                            } else {
                                processCustomDatabaseByESSIDAsync(db, networks, customResults)
                            }
                            lastCheckedType = DbType.SQLITE_FILE_CUSTOM
                        }
                        DbType.WIFI_API -> {
                            processOnlineDatabase(db, networks, onlineResults)
                            lastCheckedType = DbType.WIFI_API
                        }
                        DbType.LOCAL_APP_DB -> {
                            lastCheckedType = DbType.LOCAL_APP_DB
                        }
                        else -> {}
                    }
                }

                updateResults()
            } catch (e: Exception) {
                Log.e("WiFiScannerViewModel", "Error in checkNetworksInDatabases", e)
                _error.postValue(getApplication<Application>().getString(R.string.error_searching_local_db, e.message))
            } finally {
                _isChecking.postValue(false)
            }
        }
    }

    private suspend fun processOfflineDatabaseByESSIDAsync(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        try {
            initializeSQLite3WiFiHelper(db.path.toUri(), db.directPath)
            if (sqlite3WiFiHelper == null || sqlite3WiFiHelper?.database == null) {
                Log.e("WiFiScannerViewModel", "SQLite3WiFiHelper or database is null")
                return
            }

            val essids = networks.map { it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.no_ssid) }

            val networkInfoList = sqlite3WiFiHelper?.searchNetworksByESSIDsAsync(essids) ?: emptyList()

            networkInfoList.forEach { networkInfo ->
                val essid = networkInfo["ESSID"] as? String ?: return@forEach
                val matchingNetworks = networks.filter { it.SSID == essid }
                matchingNetworks.forEach { network ->
                    val newResult = NetworkDatabaseResult(network, networkInfo, db.path)
                    val bssid = network.BSSID.lowercase(Locale.ROOT)
                    if (!isDuplicate(results, newResult)) {
                        results.getOrPut(bssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing database by ESSID: ${db.path}", e)
            _error.postValue("Error: \"database\". ${e.message}")
        }
    }

    private suspend fun processCustomDatabaseByESSIDAsync(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        try {
            sqliteCustomHelper = SQLiteCustomHelper(getApplication(), db.path.toUri(), db.directPath)
            val essids = networks.map { it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                ?: getApplication<Application>().getString(R.string.no_ssid) }

            val networkInfoList = sqliteCustomHelper?.searchNetworksByESSIDsAsync(
                tableName = db.tableName ?: return,
                columnMap = db.columnMap ?: return,
                essids = essids
            ) ?: emptyList()

            val columnMap = db.columnMap ?: return
            val macField = columnMap["mac"] ?: "mac"
            val essidField = columnMap["essid"] ?: "essid"
            val passwordField = columnMap["wifi_pass"] ?: "wifi_pass"
            val wpsPinField = columnMap["wps_pin"] ?: "wps_pin"
            val latField = columnMap["latitude"] ?: "latitude"
            val lonField = columnMap["longitude"] ?: "longitude"
            val secField = columnMap["security_type"] ?: "security_type"
            val timeField = columnMap["timestamp"] ?: "timestamp"

            networkInfoList.forEach { networkInfo ->
                val essid = networkInfo[essidField] as? String ?: return@forEach
                val matchingNetworks = networks.filter { it.SSID == essid }
                matchingNetworks.forEach { network ->
                    val normalizedInfo = mapOf(
                        "BSSID" to networkInfo[macField]?.toString(),
                        "ESSID" to essid,
                        "WiFiKey" to networkInfo[passwordField]?.toString(),
                        "WPSPIN" to networkInfo[wpsPinField]?.toString(),
                        "lat" to (networkInfo[latField] as? Double ?: networkInfo[latField]?.toString()?.toDoubleOrNull()),
                        "lon" to (networkInfo[lonField] as? Double ?: networkInfo[lonField]?.toString()?.toDoubleOrNull()),
                        "time" to networkInfo[timeField]?.toString(),
                        "sec" to networkInfo[secField]?.toString()
                    )

                    val newResult = NetworkDatabaseResult(network, normalizedInfo, db.path)
                    val bssid = network.BSSID.lowercase(Locale.ROOT)
                    if (!isDuplicate(results, newResult)) {
                        results.getOrPut(bssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing custom database by ESSID: ${db.path}", e)
            _error.postValue("Error: \"custom_database\". ${e.message}")
        } finally {
            sqliteCustomHelper?.close()
        }
    }

    private suspend fun processOfflineDatabaseAsync(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        try {
            initializeSQLite3WiFiHelper(db.path.toUri(), db.directPath)
            if (sqlite3WiFiHelper == null || sqlite3WiFiHelper?.database == null) {
                Log.e("WiFiScannerViewModel", "SQLite3WiFiHelper or database is null")
                return
            }

            val indexLevel = DatabaseIndices.determineIndexLevel(sqlite3WiFiHelper!!.database!!)
            Log.d("WiFiScannerViewModel", "Index level: $indexLevel")

            val networkInfoList = withContext(Dispatchers.IO) {
                if (_searchByMac.value == true) {
                    val bssids = networks.map { it.BSSID }
                    sqlite3WiFiHelper?.searchNetworksByBSSIDsAsync(bssids)
                } else {
                    val essids = networks.map { it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.no_ssid) }
                    sqlite3WiFiHelper?.searchNetworksByESSIDsAsync(essids)
                } ?: emptyList()
            }

            processResults(networkInfoList, networks, results, db.path)
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing database: ${db.path}", e)
            _error.postValue(getApplication<Application>().getString(R.string.error_database_access, e.message))
        }
    }


    private suspend fun processCustomDatabaseAsync(db: DbItem, networks: List<ScanResult>, results: MutableMap<String, MutableList<NetworkDatabaseResult>>) {
        try {
            sqliteCustomHelper = SQLiteCustomHelper(getApplication(), db.path.toUri(), db.directPath)
            val bssids = networks.map { it.BSSID }
            Log.d("WiFiScannerViewModel", "Searching for BSSIDs in custom DB: $bssids")
            Log.d("WiFiScannerViewModel", "Using table: ${db.tableName}")
            Log.d("WiFiScannerViewModel", "Using column map: ${db.columnMap}")

            val networkInfoMap = withContext(Dispatchers.IO) {
                sqliteCustomHelper?.searchNetworksByBSSIDs(
                    tableName = db.tableName ?: return@withContext emptyMap(),
                    columnMap = db.columnMap ?: return@withContext emptyMap(),
                    bssids = bssids
                ) ?: emptyMap()
            }

            Log.d("WiFiScannerViewModel", "Found results in custom DB: $networkInfoMap")

            val columnMap = db.columnMap ?: return
            val macField = columnMap["mac"] ?: "mac"
            val essidField = columnMap["essid"] ?: "essid"
            val passwordField = columnMap["wifi_pass"] ?: "wifi_pass"
            val wpsPinField = columnMap["wps_pin"] ?: "wps_pin"
            val latField = columnMap["latitude"] ?: "latitude"
            val lonField = columnMap["longitude"] ?: "longitude"
            val secField = columnMap["security_type"] ?: "security_type"
            val timeField = columnMap["timestamp"] ?: "timestamp"

            networkInfoMap.forEach { (dbBssid, networkInfo) ->
                val matchingNetworks = networks.filter { network ->
                    network.BSSID.equals(dbBssid, ignoreCase = true) ||
                            network.BSSID.replace(":", "").equals(dbBssid.replace(":", ""), ignoreCase = true)
                }

                matchingNetworks.forEach { network ->
                    val normalizedInfo = mapOf(
                        "BSSID" to dbBssid,
                        "ESSID" to networkInfo[essidField]?.toString(),
                        "WiFiKey" to networkInfo[passwordField]?.toString(),
                        "WPSPIN" to networkInfo[wpsPinField]?.toString(),
                        "lat" to (networkInfo[latField] as? Double ?: networkInfo[latField]?.toString()?.toDoubleOrNull()),
                        "lon" to (networkInfo[lonField] as? Double ?: networkInfo[lonField]?.toString()?.toDoubleOrNull()),
                        "time" to networkInfo[timeField]?.toString(),
                        "sec" to networkInfo[secField]?.toString()
                    )

                    val newResult = NetworkDatabaseResult(network, normalizedInfo, db.path)
                    val resultBssid = network.BSSID.lowercase(Locale.ROOT)
                    if (!isDuplicate(results, newResult)) {
                        Log.d("WiFiScannerViewModel", "Adding result for BSSID $resultBssid: $newResult")
                        results.getOrPut(resultBssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing custom database: ${db.path}", e)
            _error.postValue("Error: \"custom_database\". ${e.message}")
        } finally {
            sqliteCustomHelper?.close()
        }
    }

    private fun processResults(
        networkInfoList: List<Map<String, Any?>>,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>,
        dbPath: String
    ) {
        networkInfoList.forEach { networkInfo ->
            val bssid = (networkInfo["BSSID"] ?: networkInfo["mac"])?.toString()?.lowercase(Locale.ROOT)
            if (bssid != null) {
                val filteredNetworks = networks.filter { it.BSSID.lowercase(Locale.ROOT) == bssid }
                filteredNetworks.forEach { network ->
                    val normalizedInfo = mapOf(
                        "BSSID" to bssid,
                        "ESSID" to (networkInfo["ESSID"] ?: networkInfo["essid"])?.toString(),
                        "WiFiKey" to (networkInfo["WiFiKey"] ?: networkInfo["wifi_pass"] ?: networkInfo["key"])?.toString(),
                        "WPSPIN" to (networkInfo["WPSPIN"] ?: networkInfo["wps_pin"] ?: networkInfo["wps"])?.toString(),
                        "lat" to (networkInfo["lat"] ?: networkInfo["latitude"])?.toString()?.toDoubleOrNull(),
                        "lon" to (networkInfo["lon"] ?: networkInfo["longitude"])?.toString()?.toDoubleOrNull(),
                        "time" to (networkInfo["time"] ?: networkInfo["timestamp"])?.toString(),
                        "sec" to (networkInfo["sec"] ?: networkInfo["security_type"])?.toString()
                    )
                    val newResult = NetworkDatabaseResult(network, normalizedInfo, dbPath)
                    if (!isDuplicate(results, newResult)) {
                        results.getOrPut(bssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        }
    }

    private suspend fun processLocalDatabase(networks: List<ScanResult>, results: MutableMap<String, MutableList<NetworkDatabaseResult>>) {
        withContext(Dispatchers.IO) {
            val searchByMac = _searchByMac.value != false

            if (searchByMac) {
                networks.forEach { network ->
                    val bssid = network.BSSID.lowercase(Locale.ROOT)
                    val localNetworks = localAppDbHelper.searchRecordsWithFiltersOptimized(
                        bssid,
                        filterByName = false,
                        filterByMac = true,
                        filterByPassword = false,
                        filterByWps = false
                    )
                    processLocalResults(network, localNetworks, results)
                }
            } else {
                val essids = networks.map { it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.no_ssid) }
                val localNetworks = localAppDbHelper.searchRecordsByEssids(essids)

                networks.forEach { network ->
                    val matchingNetworks = localNetworks.filter { it.wifiName == network.SSID }
                    processLocalResults(network, matchingNetworks, results)
                }
            }
        }
    }

    private fun processLocalResults(
        network: ScanResult,
        localNetworks: List<WifiNetwork>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        val bssid = network.BSSID.lowercase(Locale.ROOT)

        localNetworks.forEach { localNetwork ->
            val networkInfo = mapOf(
                "BSSID" to localNetwork.macAddress,
                "ESSID" to localNetwork.wifiName,
                "WiFiKey" to localNetwork.wifiPassword,
                "WPSPIN" to localNetwork.wpsCode,
                "lat" to localNetwork.latitude,
                "lon" to localNetwork.longitude
            )

            val newResult = NetworkDatabaseResult(
                network,
                networkInfo,
                getApplication<Application>().getString(R.string.local_database)
            )

            if (!isDuplicate(results, newResult)) {
                results.getOrPut(bssid) { mutableListOf() }.add(newResult)
            }
        }
    }

    private fun updateResults() {
        val mergeResults = prefs.getBoolean("merge_results", false)
        val finalResults = if (mergeResults) {
            mergeResults(localResults, offlineResults, customResults, onlineResults)
        } else {
            when (lastCheckedType) {
                DbType.SQLITE_FILE_3WIFI -> offlineResults
                DbType.SQLITE_FILE_CUSTOM -> customResults
                DbType.WIFI_API -> onlineResults
                DbType.LOCAL_APP_DB -> localResults
                DbType.SMARTLINK_SQLITE_FILE_3WIFI,
                DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> offlineResults
                null -> emptyMap()
            }
        }

        _databaseResults.postValue(finalResults)
    }

    private suspend fun processOnlineDatabase(db: DbItem, networks: List<ScanResult>, results: MutableMap<String, MutableList<NetworkDatabaseResult>>) {
        try {
            val helper = api3WiFiHelpers.getOrPut(db.id) {
                API3WiFiHelper(getApplication(), db.path, db.apiKey ?: "000000000000")
            }
            val networkInfoList = helper.searchNetworksByBSSIDs(networks.map { it.BSSID.lowercase(Locale.ROOT) })
            Log.d("WiFiScannerViewModel", "Found ${networkInfoList.size} results for BSSIDs from API")

            networkInfoList.forEach { (bssid, networkInfo) ->
                val filteredNetworks = networks.filter { it.BSSID.lowercase(Locale.ROOT) == bssid.lowercase(Locale.ROOT) }
                filteredNetworks.forEach { network ->
                    val existingResults = results[bssid]?.filter { it.databaseName == db.path } ?: emptyList()
                    if (existingResults.isEmpty()) {
                        results.getOrPut(bssid) { mutableListOf() }
                            .addAll(networkInfo.map { NetworkDatabaseResult(network, it, db.path) })
                    }
                }
            }
        } catch (e: API3WiFiHelper.API3WiFiException) {
            Log.e("WiFiScannerViewModel", "API error: ${e.errorCode}", e)
            _error.postValue("Error: \"${e.errorCode}\". ${e.message}")
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing API: ${db.path}", e)
            _error.postValue("Error: \"unknown\". ${e.message}")
        }
    }

    private fun mergeResults(
        localResults: Map<String, MutableList<NetworkDatabaseResult>>,
        offlineResults: Map<String, MutableList<NetworkDatabaseResult>>,
        customResults: Map<String, MutableList<NetworkDatabaseResult>>,
        onlineResults: Map<String, MutableList<NetworkDatabaseResult>>
    ): Map<String, List<NetworkDatabaseResult>> {
        val mergedResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()

        listOf(localResults, offlineResults, customResults, onlineResults).forEach { resultMap ->
            resultMap.forEach { (bssid, results) ->
                if (mergedResults.containsKey(bssid)) {
                    mergedResults[bssid]?.addAll(results)
                } else {
                    mergedResults[bssid] = results.toMutableList()
                }
            }
        }

        return mergedResults
    }

    fun clearResults() {
        localResults.clear()
        offlineResults.clear()
        customResults.clear()
        onlineResults.clear()
        _databaseResults.postValue(emptyMap())
        lastCheckedType = null
    }
}