package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
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
import android.content.Intent
import android.content.IntentFilter

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

    private var scanReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    fun setSearchType(searchByMac: Boolean) {
        _searchByMac.value = searchByMac
    }

    fun clearData() {
        _wifiList.postValue(emptyList())
        _databaseResults.postValue(emptyMap())
        clearResults()
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
                    _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))

                    val networks = withContext(Dispatchers.IO) {
                        if (isDummyNetworkModeEnabled()) {
                            createDummyNetworks()
                        } else {
                            if (wifiManager.startScan()) {
                                wifiManager.scanResults.sortedByDescending { it.level }
                            } else {
                                emptyList()
                            }
                        }
                    }

                    if (networks.isNotEmpty()) {
                        _wifiList.postValue(networks)
                        _scanState.postValue("Scan completed successfully")
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

    fun startLegacyWifiScan() {
        viewModelScope.launch {
            try {
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))

                if (isDummyNetworkModeEnabled()) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.failed_to_start_wifi_scan))
                    return@launch
                }

                if (!wifiManager.isWifiEnabled) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.wifi_disabled))
                    return@launch
                }

                registerScanReceiver()

                if (wifiManager.startScan()) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))
                } else {
                    _scanState.postValue(getApplication<Application>().getString(R.string.failed_to_start_wifi_scan))
                    unregisterScanReceiver()
                }
            } catch (e: SecurityException) {
                _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
                unregisterScanReceiver()
            } catch (e: Exception) {
                _scanState.postValue(getApplication<Application>().getString(R.string.error_general, e.message))
                unregisterScanReceiver()
            }
        }
    }

    private fun registerScanReceiver() {
        if (!isReceiverRegistered) {
            scanReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        } else {
                            true
                        }

                        if (success) {
                            processScanResults()
                        } else {
                            _scanState.postValue(getApplication<Application>().getString(R.string.failed_to_start_wifi_scan))
                        }
                        unregisterScanReceiver()
                    }
                }
            }

            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            getApplication<Application>().registerReceiver(scanReceiver, filter)
            isReceiverRegistered = true
        }
    }

    private fun unregisterScanReceiver() {
        if (isReceiverRegistered && scanReceiver != null) {
            try {
                getApplication<Application>().unregisterReceiver(scanReceiver)
            } catch (e: IllegalArgumentException) {
            }
            scanReceiver = null
            isReceiverRegistered = false
        }
    }

    private fun processScanResults() {
        try {
            val results = wifiManager.scanResults
            if (results != null && results.isNotEmpty()) {
                val sortedResults = results.sortedByDescending { it.level }
                _wifiList.postValue(sortedResults)
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))
            } else {
                _wifiList.postValue(emptyList())
                _scanState.postValue(getApplication<Application>().getString(R.string.no_networks_found))
            }
        } catch (e: SecurityException) {
            _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
        } catch (e: Exception) {
            _scanState.postValue(getApplication<Application>().getString(R.string.error_general, e.message))
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterScanReceiver()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createDummyNetworks(): List<ScanResult> {
        return listOf(
            // Оригинальные dummy сети из DB
            createDummyNetwork("DummyNetwork1", "60:14:66:9d:8a:0c", "[WPA2-PSK-CCMP][ESS]", -10, 2412),
            createDummyNetwork("DummyNetwork2", "00:d0:41:d0:08:8a", "[WPA2-PSK-CCMP][ESS]", -10, 2417),
            createDummyNetwork("DummyNetwork3", "00:d0:41:d0:48:8a", "[WPA2-PSK-CCMP][ESS]", -20, 2417),
            createDummyNetwork("DummyNetwork4", "24:bc:f8:aa:e6:e0", "[WPA2-PSK-CCMP][ESS]", -15, 2422),
            createDummyNetwork("DummyNetwork5_Upper", "E0:19:54:14:6C:76", "[WPA2-PSK-CCMP][ESS]", -30, 2422),
            createDummyNetwork("DummyNetwork5", "e0:19:54:14:6c:76", "[WPA2-PSK-CCMP][ESS]", -25, 2422),

            // Thomson WPA алгоритмы
            createDummyNetwork("Thomson123ABC", "00:26:24:12:34:56", "[WPA2-PSK-CCMP][WPS][ESS]", -35, 2412),
            createDummyNetwork("SpeedTouch789DEF", "44:32:C8:78:9A:BC", "[WPA2-PSK-CCMP+TKIP][WPA-PSK-CCMP+TKIP][WPS][ESS]", -40, 2437),

            // Arcadyan WPA алгоритмы
            createDummyNetwork("Vodafone-123456", "00:1E:69:AB:CD:EF", "[WPA2-PSK-CCMP][ESS][802.11k][802.11r]", -32, 5180),
            createDummyNetwork("EasyBox A1B2C3", "38:22:9D:12:34:56", "[WPA2-PSK-CCMP][WPS][ESS]", -38, 2462),
            createDummyNetwork("VodafoneA1B2", "7C:4F:B5:78:9A:BC", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]", -42, 5240),

            // Belkin WPA алгоритмы
            createDummyNetwork("Belkin.123ABC", "08:86:3B:DE:AD:BE", "[WPA2-PSK-CCMP][WPS][ESS]", -36, 2437),
            createDummyNetwork("belkin_456DEF", "94:10:3E:EF:12:34", "[WPA2-PSK-CCMP][ESS]", -41, 5320),

            // Alice Germany WPA алгоритм
            createDummyNetwork("ALICE-WLAN1A", "00:08:27:56:78:9A", "[WPA2-PSK-CCMP][ESS][802.11k][802.11r][802.11v]", -39, 5180),
            createDummyNetwork("ALICE-WLANF3", "00:19:CB:BC:DE:F0", "[WPA2-PSK-CCMP][WPS][ESS]", -44, 2412),

            // Huawei WPA алгоритм
            createDummyNetwork("INFINITUMA1B2", "00:25:68:11:22:33", "[WPA2-PSK-CCMP][ESS]", -37, 2437),
            createDummyNetwork("INFINITUM3C4D", "00:66:4B:44:55:66", "[WPA2-PSK-CCMP][WPS][ESS]", -43, 5240),

            // Infostrada WPA алгоритм
            createDummyNetwork("InfostradaWiFi-123456", "00:13:C8:77:88:99", "[WPA2-PSK-CCMP][ESS]", -40, 5180),

            // Cabovisao Sagem WPA алгоритм
            createDummyNetwork("CBN-A1B2", "50:7E:5D:AA:BB:CC", "[WPA2-PSK-CCMP][ESS]", -38, 2462),

            // Arnet Pirelli WPA алгоритм
            createDummyNetwork("WiFi-Arnet-123456", "74:88:8B:DD:EE:FF", "[WPA2-PSK-CCMP][ESS]", -41, 5320),

            // UPC WPA алгоритм
            createDummyNetwork("UPC1234567", "64:7C:34:12:34:56", "[WPA2-PSK-CCMP][ESS]", -39, 5240),
            createDummyNetwork("UPC2345678", "64:7C:34:78:9A:BC", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]", -42, 5745),

            // WPS алгоритмы - различные префиксы MAC
            createDummyNetwork("ASUS_WPS_Test", "04:92:26:11:22:33", "[WPA2-PSK-CCMP][WPS][ESS][802.11k][802.11r][802.11v]", -25, 2462),
            createDummyNetwork("DLink_Test", "14:D6:4D:44:55:66", "[WPA2-PSK-CCMP][WPS][ESS]", -30, 2412),
            createDummyNetwork("Belkin_WPS", "08:86:3B:77:88:99", "[WPA2-PSK-CCMP][WPS][ESS]", -35, 2437),
            createDummyNetwork("Netgear_Test", "00:14:BF:AA:BB:CC", "[WPA2-PSK-CCMP][WPS][ESS]", -40, 2462),

            // WiFi 6 с расширенными возможностями
            createDummyNetwork("WiFi6_Test", "04:BF:6D:12:34:56", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][802.11ax][HE][TWT][MLD]", -20, 5745),
            createDummyNetwork("WiFi6E_Ultra", "0E:5D:4E:78:9A:BC", "[WPA3-SAE-CCMP][ESS][802.11ax][HE][160MHz][TWT][MLD][RTT][NTB]", -25, 6035),

            // Различные протоколы и возможности
            createDummyNetwork("Legacy_WEP", "10:7B:EF:DE:AD:BE", "[WEP][ESS]", -50, 2412),
            createDummyNetwork("Enterprise_Net", "28:28:5D:EF:12:34", "[WPA2-EAP-CCMP][ESS][802.11k][802.11r]", -30, 5180),
            createDummyNetwork("OpenNetwork", "2A:28:5D:56:78:9A", "[ESS]", -35, 2437),
            createDummyNetwork("HiddenAP", "32:B2:DC:BC:DE:F0", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][HIDDEN]", -40, 5240),

            // Mesh и современные технологии
            createDummyNetwork("MeshNode_001", "38:17:66:12:34:56", "[WPA3-SAE-CCMP][ESS][MESH][802.11s][802.11k][802.11r][802.11v]", -28, 5180),
            createDummyNetwork("IoT_Network", "40:4A:03:78:9A:BC", "[WPA2-PSK-CCMP][ESS][IoT]", -45, 2412),

            // Специальные WPS тесты
            createDummyNetwork("Cisco_WPS", "00:1A:2B:11:22:33", "[WPA2-PSK-CCMP][WPS][ESS]", -35, 2437),
            createDummyNetwork("Broadcom_AP", "14:D6:4D:44:55:66", "[WPA2-PSK-CCMP][WPS][ESS]", -40, 2462),
            createDummyNetwork("Realtek_Test", "00:14:D1:77:88:99", "[WPA2-PSK-CCMP][WPS][ESS]", -45, 5180),

            // Livebox Arcadyan WPA алгоритм
            createDummyNetwork("Livebox-A1B2", "18:83:BF:11:22:33", "[WPA2-PSK-CCMP][ESS]", -36, 5320),
            createDummyNetwork("Livebox-C3D4", "88:03:55:44:55:66", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]", -41, 5745),

            // ASUS WPA алгоритм
            createDummyNetwork("ASUS_Router_Test", "04:D9:F5:77:88:99", "[WPA2-PSK-CCMP][ESS][802.11ax][HE]", -33, 5180),
            createDummyNetwork("RT-N12_Test", "AC:22:0B:AA:BB:CC", "[WPA2-PSK-CCMP][WPS][ESS]", -38, 2437),

            // D-Link WPA алгоритм
            createDummyNetwork("DLink-123456", "1C:7E:E5:DD:EE:FF", "[WPA2-PSK-CCMP][WPS][ESS]", -37, 2462),
            createDummyNetwork("DLink-789ABC", "84:C9:B2:12:34:56", "[WPA2-PSK-CCMP][ESS]", -42, 5240),

            // Netgear WPA алгоритм
            createDummyNetwork("NETGEAR12", "00:14:6C:78:9A:BC", "[WPA2-PSK-CCMP][WPS][ESS]", -39, 5320),
            createDummyNetwork("NETGEAR34", "C0:3F:0E:DE:AD:BE", "[WPA2-PSK-CCMP][ESS]", -44, 2412),

            // Untrusted networks
            createDummyNetwork("Public_WiFi", "50:67:F0:AA:BB:CC", "[WPA2-PSK-CCMP][ESS][UNTRUSTED]", -30, 2437),
            createDummyNetwork("Guest_Network", "5C:F4:AB:DD:EE:FF", "[WPA2-PSK-CCMP][ESS][GUEST][UNTRUSTED]", -35, 5240),

            // High-performance networks
            createDummyNetwork("Gaming_Pro", "6A:28:5D:12:34:56", "[WPA3-SAE-CCMP][ESS][802.11ax][HE][160MHz][TWT][MLD][RTT][NTB][LOW_LATENCY]", -15, 5785),
            createDummyNetwork("Enterprise_HQ", "8E:5D:4E:78:9A:BC", "[WPA3-EAP-CCMP][ESS][802.11ax][HE][320MHz][TWT][MLD][RTT][NTB][802.11k][802.11r][802.11v]", -18, 6075),

            // Ad-hoc network
            createDummyNetwork("AdHoc_Test", "AA:28:5D:BC:DE:F0", "[WPA2-PSK-CCMP][IBSS]", -50, 2412),

            // Experimental features
            createDummyNetwork("Test_Lab", "E2:43:F6:11:22:33", "[WPA3-SAE-CCMP][ESS][802.11be][EHT][320MHz][TWT][MLD][RTT][NTB][MLO][EXPERIMENTAL]", -22, 6115),

            // Various frequency bands
            createDummyNetwork("2.4GHz_Only", "EC:43:F6:44:55:66", "[WPA2-PSK-CCMP][ESS][802.11n][HT]", -40, 2472),
            createDummyNetwork("5GHz_AC", "EE:43:F6:77:88:99", "[WPA2-PSK-CCMP][ESS][802.11ac][VHT][80MHz]", -30, 5500),
            createDummyNetwork("6GHz_AX", "F2:B2:DC:AA:BB:CC", "[WPA3-SAE-CCMP][ESS][802.11ax][HE][160MHz][TWT]", -25, 6235),

            // Special security configurations
            createDummyNetwork("WPA3_Transition", "FC:F5:28:DD:EE:FF", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][TRANSITION]", -35, 5320),
            createDummyNetwork("Enhanced_Open", "FE:F5:28:12:34:56", "[ESS][OWE][ENHANCED_OPEN]", -40, 5745),

            // Multi-link operation
            createDummyNetwork("MLO_Test_2G", "4C:9E:FF:11:22:33", "[WPA3-SAE-CCMP][ESS][802.11be][EHT][MLO][MLD_ID:123]", -30, 2437),
            createDummyNetwork("MLO_Test_5G", "4C:9E:FF:11:22:34", "[WPA3-SAE-CCMP][ESS][802.11be][EHT][MLO][MLD_ID:123]", -25, 5180),
            createDummyNetwork("MLO_Test_6G", "4C:9E:FF:11:22:35", "[WPA3-SAE-CCMP][ESS][802.11be][EHT][MLO][MLD_ID:123]", -20, 6035),

            // Дополнительные Thomson варианты
            createDummyNetwork("Orange123456", "88:F7:C7:45:67:89", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]", -45, 5180),
            createDummyNetwork("BigPondABCDEF", "CC:03:FA:12:34:56", "[WPA2-PSK-CCMP][ESS]", -48, 2412),
            createDummyNetwork("Bbox-789123", "00:26:24:78:9A:BC", "[WPA2-PSK-CCMP][WPS][ESS]", -46, 5240),

            // Пустые PIN сети (Empty PIN algorithm)
            createDummyNetwork("DIR-820L", "E4:6F:13:DE:AD:BE", "[WPA2-PSK-CCMP][WPS][ESS]", -40, 2437),
            createDummyNetwork("DSL-2640U", "10:62:EB:EF:12:34", "[WPA2-PSK-CCMP][WPS][ESS]", -42, 5180),

            // ZyXEL тесты
            createDummyNetwork("ZyXEL_Test", "28:28:5D:56:78:9A", "[WPA2-PSK-CCMP][WPS][ESS]", -35, 2462),
            createDummyNetwork("Keenetic_Ultra", "40:4A:03:BC:DE:F0", "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][802.11ax][HE]", -28, 5320)
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
            timestamp = System.currentTimeMillis() * 1000
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