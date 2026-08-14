package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.WpaSecClient
import com.lsd.wififrankenstein.ui.dbsetup.API3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.WiFiManagerWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var sqliteCustomHelper: SQLiteCustomHelper? = null

    val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val wifiManagerWrapper: WiFiManagerWrapper by lazy {
        WiFiManagerWrapper(application.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
    }

    private val locationManager: LocationManager by lazy {
        application.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val localAppDbHelper = LocalAppDbHelper(application)
    private val wpaSecClient = WpaSecClient(application)

    private val _searchByMac = MutableLiveData(true)
    val searchByMac: LiveData<Boolean> = _searchByMac

    private val _wifiList = MutableLiveData<List<ScanResult>>()
    val wifiList: LiveData<List<ScanResult>> = _wifiList

    private val _iwWifiList = MutableLiveData<List<IwWifiNetwork>>()
    val iwWifiList: LiveData<List<IwWifiNetwork>> = _iwWifiList

    private val _scanState = MutableLiveData<String>()
    val scanState: LiveData<String> = _scanState

    private val _databaseResults = MutableLiveData<Map<String, List<NetworkDatabaseResult>>>()
    val databaseResults: LiveData<Map<String, List<NetworkDatabaseResult>>> = _databaseResults

    private val _isChecking = MutableLiveData<Boolean>()
    val isChecking: LiveData<Boolean> = _isChecking

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _wifiEnabled = MutableLiveData<Boolean>()
    val wifiEnabled: LiveData<Boolean> = _wifiEnabled

    private val _locationEnabled = MutableLiveData<Boolean>()
    val locationEnabled: LiveData<Boolean> = _locationEnabled


    private var sqlite3WiFiHelper: SQLite3WiFiHelper? = null

    private val api3WiFiHelpers = mutableMapOf<String, API3WiFiHelper>()

    private var scanReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    private var isScanning = false
    private var scanTimeoutJob: Job? = null
    private val SCAN_TIMEOUT = 10000L

    fun setSearchType(searchByMac: Boolean) {
        _searchByMac.value = searchByMac
    }

    fun clearData() {
        _wifiList.postValue(emptyList())
        _databaseResults.postValue(emptyMap())
        clearResults()
    }

    private fun isDuplicate(
        existingResults: Map<String, List<NetworkDatabaseResult>>,
        newResult: NetworkDatabaseResult
    ): Boolean {
        val existingList =
            existingResults[newResult.network.BSSID?.lowercase(Locale.ROOT) ?: ""] ?: return false

        return existingList.any { existing ->
            existing.network.BSSID == newResult.network.BSSID &&
                    existing.databaseInfo == newResult.databaseInfo &&
                    existing.databaseName == newResult.databaseName
        }
    }

    private val databaseFileNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun formatDatabaseName(db: DbItem): String {
        val isCustom = db.dbType == DbType.SQLITE_FILE_CUSTOM ||
                db.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
        if (!isCustom) return db.path
        val fileName = databaseFileNameCache.getOrPut(db.path) {
            resolveDatabaseFileName(db.path)
        }
        return if (!db.tableName.isNullOrBlank()) "$fileName · ${db.tableName}" else fileName
    }

    private fun resolveDatabaseFileName(path: String): String {
        return try {
            val fallback = Uri.decode(path.substringAfterLast('/'))
                .substringAfterLast('/')
                .substringBefore('?')
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val displayName = runCatching {
                    getApplication<Application>().contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx =
                                cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
                }.getOrNull()
                displayName?.takeIf { it.isNotBlank() } ?: fallback
            } else {
                fallback
            }
        } catch (e: Exception) {
            path.substringAfterLast('/')
        }
    }

    private fun isWifiEnabled(): Boolean {
        return wifiManagerWrapper.wiFiEnabled()
    }

    private fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            try {
                val locationMode = Settings.Secure.getInt(
                    getApplication<Application>().contentResolver,
                    Settings.Secure.LOCATION_MODE
                )
                locationMode != Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Settings.SettingNotFoundException) {
                false
            }
        }
    }

    private fun needsLocationForWifiScan(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun startWifiScan() {
        if (!hasLocationPermission()) {
            _scanState.postValue(getApplication<Application>().getString(R.string.location_permission_required))
            return
        }

        val wifiEnabled = isWifiEnabled()
        _wifiEnabled.postValue(wifiEnabled)

        if (!wifiEnabled) {
            _scanState.postValue(getApplication<Application>().getString(R.string.wifi_disabled))
            return
        }

        if (needsLocationForWifiScan() && !isLocationEnabled()) {
            _locationEnabled.postValue(false)
            _scanState.postValue(getApplication<Application>().getString(R.string.location_services_disabled))
            return
        }

        _locationEnabled.postValue(true)

        if (isScanning) {
            _scanState.postValue(getApplication<Application>().getString(R.string.scanning_in_progress))
            return
        }

        viewModelScope.launch {
            try {
                isScanning = true
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))

                val networks = withContext(Dispatchers.IO) {
                    if (isDummyNetworkModeEnabled()) {
                        createDummyNetworks()
                    } else {
                        try {
                            if (wifiManagerWrapper.startScan()) {
                                delay(2000)
                                val results = wifiManagerWrapper.scanResults()
                                if (results.isNotEmpty()) {
                                    results.sortedByDescending { it.level }
                                } else {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        } catch (se: SecurityException) {
                            throw se
                        } catch (e: Exception) {
                            Log.e("WiFiScannerViewModel", "Error during scan", e)
                            emptyList()
                        }
                    }
                }


                when {
                    networks.isNotEmpty() -> {
                        _wifiList.postValue(networks)
                        _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
                    }

                    isDummyNetworkModeEnabled() -> {
                        _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
                    }

                    else -> {
                        _wifiList.postValue(emptyList())
                        _scanState.postValue(getApplication<Application>().getString(R.string.scanning_failed_generic))
                    }
                }

            } catch (se: SecurityException) {
                _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
            } catch (e: Exception) {
                _scanState.postValue(getApplication<Application>().getString(R.string.wifi_scan_failed_unknown))
                Log.e("WiFiScannerViewModel", "Error in startWifiScan", e)
            } finally {
                isScanning = false
            }
        }
    }

    fun startLegacyWifiScan() {
        if (!hasLocationPermission()) {
            _scanState.value =
                getApplication<Application>().getString(R.string.location_permission_required)
            return
        }

        val wifiEnabled = isWifiEnabled()
        _wifiEnabled.postValue(wifiEnabled)

        if (!wifiEnabled) {
            _scanState.postValue(getApplication<Application>().getString(R.string.wifi_disabled))
            return
        }

        if (needsLocationForWifiScan() && !isLocationEnabled()) {
            _locationEnabled.postValue(false)
            _scanState.postValue(getApplication<Application>().getString(R.string.location_services_disabled))
            return
        }

        _locationEnabled.postValue(true)

        if (isDummyNetworkModeEnabled()) {
            val dummyNetworks = createDummyNetworks()
            _wifiList.postValue(dummyNetworks)
            _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
            return
        }

        if (isScanning) {
            _scanState.postValue(getApplication<Application>().getString(R.string.scanning_in_progress))
            return
        }

        viewModelScope.launch {
            try {
                isScanning = true
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_wifi))

                registerScanReceiver()
                startScanTimeout()

                val scanStarted = withContext(Dispatchers.IO) {
                    try {
                        wifiManagerWrapper.startScan()
                    } catch (e: Exception) {
                        Log.e("WiFiScannerViewModel", "Error starting scan", e)
                        false
                    }
                }

                if (!scanStarted) {
                    _scanState.postValue(getApplication<Application>().getString(R.string.wifi_scan_failed_unknown))
                    unregisterScanReceiver()
                }
            } catch (e: SecurityException) {
                _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
                unregisterScanReceiver()
            } catch (e: Exception) {
                _scanState.postValue(getApplication<Application>().getString(R.string.wifi_scan_failed_unknown))
                Log.e("WiFiScannerViewModel", "Error in startLegacyWifiScan", e)
                unregisterScanReceiver()
            } finally {
                if (!isReceiverRegistered) {
                    isScanning = false
                }
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
                            _scanState.postValue(getApplication<Application>().getString(R.string.wifi_scan_failed_unknown))
                        }
                        unregisterScanReceiver()
                    }
                }
            }

            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getApplication<Application>().registerReceiver(
                    scanReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                getApplication<Application>().registerReceiver(scanReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun startScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_TIMEOUT)
            if (isReceiverRegistered) {
                Log.w("WiFiScannerViewModel", "Scan timed out after ${SCAN_TIMEOUT}ms")
                unregisterScanReceiver()
                _scanState.postValue(
                    getApplication<Application>().getString(R.string.wifi_scan_failed_unknown)
                )
            }
        }
    }

    private fun unregisterScanReceiver() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (isReceiverRegistered && scanReceiver != null) {
            try {
                getApplication<Application>().unregisterReceiver(scanReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w("WiFiScannerViewModel", "Receiver not registered", e)
            }
            scanReceiver = null
            isScanning = false
            isReceiverRegistered = false
        }
    }

    private fun processScanResults() {
        try {
            val results = wifiManagerWrapper.scanResults()

            if (results.isNotEmpty()) {
                val sortedResults = results.sortedByDescending { it.level }
                _wifiList.postValue(sortedResults)
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
            } else {
                _wifiList.postValue(emptyList())
                _scanState.postValue(getApplication<Application>().getString(R.string.scanning_failed_generic))
            }
        } catch (e: SecurityException) {
            _scanState.postValue(getApplication<Application>().getString(R.string.permission_denied_wifi_scan))
        } catch (e: Exception) {
            _scanState.postValue(getApplication<Application>().getString(R.string.wifi_scan_failed_unknown))
            Log.e("WiFiScannerViewModel", "Error processing scan results", e)
        } finally {
            isScanning = false
        }
    }

    fun setIwScanResults(results: List<IwWifiNetwork>) {
        if (results.isNotEmpty()) {
            _iwWifiList.postValue(results.sortedByDescending { it.signalStrength })
            _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
        } else {
            _iwWifiList.postValue(emptyList())
            _scanState.postValue(getApplication<Application>().getString(R.string.scanning_completed))
        }
        isScanning = false
    }

    override fun onCleared() {
        super.onCleared()
        isScanning = false
        unregisterScanReceiver()
    }

    private fun createDummyNetworks(): List<ScanResult> {
        return listOf(
            createDummyNetwork(
                "DummyNetwork1",
                "60:14:66:9d:8a:0c",
                "[WPA2-PSK-CCMP][ESS]",
                -10,
                2412
            ),
            createDummyNetwork(
                "DummyNetwork2",
                "00:d0:41:d0:08:8a",
                "[WPA2-PSK-CCMP][ESS]",
                -10,
                2417
            ),
            createDummyNetwork(
                "DummyNetwork3",
                "00:d0:41:d0:48:8a",
                "[WPA2-PSK-CCMP][ESS]",
                -20,
                2417
            ),
            createDummyNetwork(
                "DummyNetwork4",
                "24:bc:f8:aa:e6:e0",
                "[WPA2-PSK-CCMP][ESS]",
                -15,
                2422
            ),
            createDummyNetwork(
                "DummyNetwork5_Upper",
                "E0:19:54:14:6C:76",
                "[WPA2-PSK-CCMP][ESS]",
                -30,
                2422
            ),
            createDummyNetwork(
                "DummyNetwork5",
                "e0:19:54:14:6c:76",
                "[WPA2-PSK-CCMP][ESS]",
                -25,
                2422
            ),
            createDummyNetwork(
                "Thomson123ABC",
                "00:26:24:12:34:56",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -35,
                2412
            ),
            createDummyNetwork(
                "SpeedTouch789DEF",
                "44:32:C8:78:9A:BC",
                "[WPA2-PSK-CCMP+TKIP][WPA-PSK-CCMP+TKIP][WPS][ESS]",
                -40,
                2437
            ),
            createDummyNetwork(
                "Vodafone-123456",
                "00:1E:69:AB:CD:EF",
                "[WPA2-PSK-CCMP][ESS][802.11k][802.11r]",
                -32,
                5180
            ),
            createDummyNetwork(
                "EasyBox A1B2C3",
                "38:22:9D:12:34:56",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -38,
                2462
            ),
            createDummyNetwork(
                "VodafoneA1B2",
                "7C:4F:B5:78:9A:BC",
                "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]",
                -42,
                5240
            ),
            createDummyNetwork(
                "Belkin.123ABC",
                "08:86:3B:DE:AD:BE",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -36,
                2437
            ),
            createDummyNetwork(
                "belkin_456DEF",
                "94:10:3E:EF:12:34",
                "[WPA2-PSK-CCMP][ESS]",
                -41,
                5320
            ),
            createDummyNetwork(
                "ALICE-WLAN1A",
                "00:08:27:56:78:9A",
                "[WPA2-PSK-CCMP][ESS][802.11k][802.11r][802.11v]",
                -39,
                5180
            ),
            createDummyNetwork(
                "ALICE-WLANF3",
                "00:19:CB:BC:DE:F0",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -44,
                2412
            ),
            createDummyNetwork(
                "INFINITUMA1B2",
                "00:25:68:11:22:33",
                "[WPA2-PSK-CCMP][ESS]",
                -37,
                2437
            ),
            createDummyNetwork(
                "INFINITUM3C4D",
                "00:66:4B:44:55:66",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -43,
                5240
            ),
            createDummyNetwork(
                "InfostradaWiFi-123456",
                "00:13:C8:77:88:99",
                "[WPA2-PSK-CCMP][ESS]",
                -40,
                5180
            ),
            createDummyNetwork("CBN-A1B2", "50:7E:5D:AA:BB:CC", "[WPA2-PSK-CCMP][ESS]", -38, 2462),
            createDummyNetwork(
                "WiFi-Arnet-123456",
                "74:88:8B:DD:EE:FF",
                "[WPA2-PSK-CCMP][ESS]",
                -41,
                5320
            ),
            createDummyNetwork(
                "UPC1234567",
                "64:7C:34:12:34:56",
                "[WPA2-PSK-CCMP][ESS]",
                -39,
                5240
            ),
            createDummyNetwork(
                "UPC2345678",
                "64:7C:34:78:9A:BC",
                "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS]",
                -42,
                5745
            ),
            createDummyNetwork(
                "ASUS_WPS_Test",
                "04:92:26:11:22:33",
                "[WPA2-PSK-CCMP][WPS][ESS][802.11k][802.11r][802.11v]",
                -25,
                2462
            ),
            createDummyNetwork(
                "DLink_Test",
                "14:D6:4D:44:55:66",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -30,
                2412
            ),
            createDummyNetwork(
                "Belkin_WPS",
                "08:86:3B:77:88:99",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -35,
                2437
            ),
            createDummyNetwork(
                "Netgear_Test",
                "00:14:BF:AA:BB:CC",
                "[WPA2-PSK-CCMP][WPS][ESS]",
                -40,
                2462
            ),
            createDummyNetwork(
                "WiFi6_Test",
                "04:BF:6D:12:34:56",
                "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][802.11ax][HE][TWT][MLD]",
                -20,
                5745
            ),
            createDummyNetwork(
                "WiFi6E_Ultra",
                "0E:5D:4E:78:9A:BC",
                "[WPA3-SAE-CCMP][ESS][802.11ax][HE][160MHz][TWT][MLD][RTT][NTB]",
                -25,
                6035
            ),
            createDummyNetwork("Legacy_WEP", "10:7B:EF:DE:AD:BE", "[WEP][ESS]", -50, 2412),
            createDummyNetwork(
                "Enterprise_Net",
                "28:28:5D:EF:12:34",
                "[WPA2-EAP-CCMP][ESS][802.11k][802.11r]",
                -30,
                5180
            ),
            createDummyNetwork("OpenNetwork", "2A:28:5D:56:78:9A", "[ESS]", -35, 2437),
            createDummyNetwork(
                "HiddenAP",
                "32:B2:DC:BC:DE:F0",
                "[WPA3-SAE-CCMP][WPA2-PSK-CCMP][ESS][HIDDEN]",
                -40,
                5240
            )
        )
    }

    private fun hasLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        getApplication(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun refreshWifiList() {
        _wifiList.value?.let { wifiList ->
            _wifiList.postValue(wifiList)
        }
    }

    private fun createDummyNetwork(
        ssid: String,
        bssid: String,
        capabilities: String,
        level: Int,
        frequency: Int
    ): ScanResult {
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

    fun checkNetworksInDatabases(
        networks: List<ScanResult>,
        databases: List<DbItem>,
        checkWpaSec: Boolean = false
    ) {
        if (_isChecking.value == true) return

        viewModelScope.launch {
            _isChecking.postValue(true)
            try {
                offlineResults.clear()
                customResults.clear()
                onlineResults.clear()
                localResults.clear()

                try {
                    processLocalDatabase(networks, localResults)
                } catch (e: Exception) {
                    Log.e("WiFiScannerViewModel", "Error processing local database", e)
                    _error.postValue(
                        getApplication<Application>().getString(
                            R.string.error_searching_local_db,
                            e.message
                        )
                    )
                }

                val searchByMac = _searchByMac.value != false
                databases.forEach { db ->
                    Log.d(
                        "WiFiScannerViewModel",
                        "checkNetworksInDatabases: db=${db.type}, dbType=${db.dbType}"
                    )
                    try {
                        when (db.dbType) {
                            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                                if (searchByMac) {
                                    processOfflineDatabaseAsync(db, networks, offlineResults)
                                } else {
                                    processOfflineDatabaseByESSIDAsync(db, networks, offlineResults)
                                }
                            }

                            DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                                if (searchByMac) {
                                    processCustomDatabaseAsync(db, networks, customResults)
                                } else {
                                    processCustomDatabaseByESSIDAsync(db, networks, customResults)
                                }
                            }

                            DbType.WIFI_API -> {
                                processOnlineDatabase(db, networks, onlineResults)
                            }

                            DbType.LOCAL_APP_DB -> {
                            }

                            else -> {
                                Log.d(
                                    "WiFiScannerViewModel",
                                    "Unsupported db type in checkNetworksInDatabases: ${db.dbType}"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "WiFiScannerViewModel",
                            "Error processing database ${db.type} (${db.dbType})",
                            e
                        )
                        _error.postValue(
                            getApplication<Application>().getString(
                                R.string.error_searching_local_db,
                                e.message
                            )
                        )
                    }
                }

                if (checkWpaSec) {
                    processWpaSecCheck(networks)
                }

                updateResults()
            } catch (e: Exception) {
                Log.e("WiFiScannerViewModel", "Error in checkNetworksInDatabases", e)
                _error.postValue(
                    getApplication<Application>().getString(
                        R.string.error_searching_local_db,
                        e.message
                    )
                )
            } finally {
                _isChecking.postValue(false)
            }
        }
    }

    private suspend fun processWpaSecCheck(
        networks: List<ScanResult>
    ) {
        val targetMap = onlineResults
        Log.d(
            "WiFiScannerViewModel",
            "processWpaSecCheck: filtering ${networks.size} scanned networks, target=${targetMap.hashCode()}"
        )
        val validNetworks = networks.filter { n ->
            val bssid = n.BSSID
            val essid = n.SSID
            bssid != null && essid != null && bssid.isNotBlank() && essid.isNotBlank() && essid != "<unknown ssid>"
        }
        Log.d(
            "WiFiScannerViewModel",
            "processWpaSecCheck: ${validNetworks.size} valid networks with BSSID+ESSID"
        )
        if (validNetworks.isEmpty()) {
            Log.d("WiFiScannerViewModel", "processWpaSecCheck: no valid networks to check")
            return
        }

        Log.d(
            "WiFiScannerViewModel",
            "processWpaSecCheck: building hex queries for ${validNetworks.size} networks"
        )
        @Suppress("UNUSED")
        data class NetInfo(val bssid: String, val essid: String, val scanResult: ScanResult)

        val netInfos = validNetworks.mapNotNull { n ->
            val bssid = n.BSSID ?: return@mapNotNull null
            val essid = n.SSID ?: return@mapNotNull null
            NetInfo(bssid, essid, n)
        }
        val queries =
            netInfos.map { wpaSecClient.bssidToHex(it.bssid) to wpaSecClient.essidToHex(it.essid) }
        try {
            Log.d("WiFiScannerViewModel", "processWpaSecCheck: sending batch request to wpa-sec")
            val batchResults = wpaSecClient.checkPasswordsBatch(queries)
            val foundCount = batchResults.count { it }
            Log.d(
                "WiFiScannerViewModel",
                "processWpaSecCheck: $foundCount/${netInfos.size} networks found on wpa-sec"
            )
            for ((index, found) in batchResults.withIndex()) {
                if (found) {
                    val info = netInfos[index]
                    Log.d(
                        "WiFiScannerViewModel",
                        "processWpaSecCheck: matched — ${info.essid} (${info.bssid})"
                    )
                    val key = info.bssid.lowercase()
                    targetMap.getOrPut(key) { mutableListOf() }.add(
                        NetworkDatabaseResult(
                            network = info.scanResult,
                            databaseInfo = mapOf("isWpaSec" to true),
                            databaseName = "wpa-sec.stanev.org",
                            resultType = ResultType.DATABASE
                        )
                    )
                }
            }
            Log.d(
                "WiFiScannerViewModel",
                "processWpaSecCheck: finished, ${targetMap.size} networks have wpa-sec results"
            )
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "processWpaSecCheck: wpa-sec batch check failed", e)
            throw e
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

            val essids = networks.map {
                it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.no_ssid)
            }

            val networkInfoList =
                sqlite3WiFiHelper?.searchNetworksByESSIDsAsync(essids) ?: emptyList()

            networkInfoList.forEach { networkInfo ->
                val essid = networkInfo["ESSID"] as? String ?: return@forEach
                val matchingNetworks = networks.filter { it.SSID == essid }
                matchingNetworks.forEach { network ->
                    val newResult = NetworkDatabaseResult(network, networkInfo, db.path)
                    val bssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""
                    if (!isDuplicate(results, newResult)) {
                        results.getOrPut(bssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing database by ESSID: ${db.path}", e)
            _error.postValue(
                getApplication<Application>().getString(R.string.ws_db_error, "database", e.message)
            )
        } finally {
            sqlite3WiFiHelper?.close()
        }
    }

    private suspend fun processCustomDatabaseByESSIDAsync(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        try {
            sqliteCustomHelper =
                SQLiteCustomHelper(getApplication(), db.path.toUri(), db.directPath)
            val essids = networks.map {
                it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.no_ssid)
            }

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
                        "lat" to (networkInfo[latField] as? Double
                            ?: networkInfo[latField]?.toString()?.toDoubleOrNull()),
                        "lon" to (networkInfo[lonField] as? Double
                            ?: networkInfo[lonField]?.toString()?.toDoubleOrNull()),
                        "time" to networkInfo[timeField]?.toString(),
                        "sec" to networkInfo[secField]?.toString()
                    )

                    val newResult =
                        NetworkDatabaseResult(network, normalizedInfo, formatDatabaseName(db))
                    val bssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""
                    if (!isDuplicate(results, newResult)) {
                        results.getOrPut(bssid) { mutableListOf() }.add(newResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing custom database by ESSID: ${db.path}", e)
            _error.postValue(
                getApplication<Application>().getString(
                    R.string.ws_db_error,
                    "custom_database",
                    e.message
                )
            )
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

            val networkInfoList = withContext(Dispatchers.IO) {
                if (_searchByMac.value == true) {
                    val bssidDecimals = networks.mapNotNull { network ->
                        network.BSSID?.let {
                            com.lsd.wififrankenstein.util.MacAddressUtils.convertToDecimal(it)
                        }
                    }
                    sqlite3WiFiHelper?.searchNetworksByBSSIDDecimals(bssidDecimals)
                } else {
                    val essids = networks.map {
                        it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                            ?: getApplication<Application>().getString(R.string.no_ssid)
                    }
                    sqlite3WiFiHelper?.searchNetworksByESSIDsAsync(essids)
                } ?: emptyList()
            }

            processResults(networkInfoList, networks, results, db.path)
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing database: ${db.path}", e)
            _error.postValue(
                getApplication<Application>().getString(
                    R.string.error_database_access,
                    e.message
                )
            )
        } finally {
            sqlite3WiFiHelper?.close()
        }
    }

    private suspend fun processCustomDatabaseAsync(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        try {
            sqliteCustomHelper =
                SQLiteCustomHelper(getApplication(), db.path.toUri(), db.directPath)
            val bssids = networks.map { it.BSSID }
            Log.d("WiFiScannerViewModel", "Searching for BSSIDs in custom DB: $bssids")
            Log.d("WiFiScannerViewModel", "Using table: ${db.tableName}")
            Log.d("WiFiScannerViewModel", "Using column map: ${db.columnMap}")

            val networkInfoMap = withContext(Dispatchers.IO) {
                sqliteCustomHelper?.searchNetworksByBSSIDsAll(
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

            networkInfoMap.forEach { (dbBssid, networkInfoList) ->
                val matchingNetworks = networks.filter { network ->
                    val bssid = network.BSSID
                    bssid != null && (
                            bssid.equals(dbBssid, ignoreCase = true) ||
                                    bssid.replace(":", "")
                                        .equals(dbBssid.replace(":", ""), ignoreCase = true)
                            )
                }

                networkInfoList.forEach { networkInfo ->
                    matchingNetworks.forEach { network ->
                        val normalizedInfo = mapOf(
                            "BSSID" to dbBssid,
                            "ESSID" to networkInfo[essidField]?.toString(),
                            "WiFiKey" to networkInfo[passwordField]?.toString(),
                            "WPSPIN" to networkInfo[wpsPinField]?.toString(),
                            "lat" to (networkInfo[latField] as? Double
                                ?: networkInfo[latField]?.toString()?.toDoubleOrNull()),
                            "lon" to (networkInfo[lonField] as? Double
                                ?: networkInfo[lonField]?.toString()?.toDoubleOrNull()),
                            "time" to networkInfo[timeField]?.toString(),
                            "sec" to networkInfo[secField]?.toString()
                        )

                        val newResult =
                            NetworkDatabaseResult(network, normalizedInfo, formatDatabaseName(db))
                        val resultBssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""
                        if (!isDuplicate(results, newResult)) {
                            Log.d(
                                "WiFiScannerViewModel",
                                "Adding result for BSSID $resultBssid: $newResult"
                            )
                            results.getOrPut(resultBssid) { mutableListOf() }.add(newResult)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiFiScannerViewModel", "Error accessing custom database: ${db.path}", e)
            _error.postValue(
                getApplication<Application>().getString(
                    R.string.ws_db_error,
                    "custom_database",
                    e.message
                )
            )
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
            val bssid =
                (networkInfo["BSSID"] ?: networkInfo["mac"])?.toString()?.lowercase(Locale.ROOT)
            if (bssid != null) {
                val filteredNetworks =
                    networks.filter { it.BSSID?.lowercase(Locale.ROOT) == bssid }
                filteredNetworks.forEach { network ->
                    val normalizedInfo = mapOf(
                        "BSSID" to bssid,
                        "ESSID" to (networkInfo["ESSID"] ?: networkInfo["essid"])?.toString(),
                        "WiFiKey" to (networkInfo["WiFiKey"] ?: networkInfo["wifi_pass"]
                        ?: networkInfo["key"])?.toString(),
                        "WPSPIN" to (networkInfo["WPSPIN"] ?: networkInfo["wps_pin"]
                        ?: networkInfo["wps"])?.toString(),
                        "lat" to (networkInfo["lat"] ?: networkInfo["latitude"])?.toString()
                            ?.toDoubleOrNull(),
                        "lon" to (networkInfo["lon"] ?: networkInfo["longitude"])?.toString()
                            ?.toDoubleOrNull(),
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

    private suspend fun processLocalDatabase(
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        withContext(Dispatchers.IO) {
            val searchByMac = _searchByMac.value != false

            if (searchByMac) {
                networks.forEach { network ->
                    val bssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""
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
                val essids = networks.map {
                    it.SSID.takeIf { ssid -> ssid.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.no_ssid)
                }
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
        val bssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""

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
        _databaseResults.postValue(
            mergeResults(localResults, offlineResults, customResults, onlineResults)
        )
    }

    private suspend fun processOnlineDatabase(
        db: DbItem,
        networks: List<ScanResult>,
        results: MutableMap<String, MutableList<NetworkDatabaseResult>>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val helper = api3WiFiHelpers.getOrPut(db.id) {
                    API3WiFiHelper(
                        getApplication(),
                        db.path,
                        db.apiReadKey ?: "000000000000",
                        db.apiWriteKey
                    )
                }
                val networkInfoList =
                    helper.searchNetworksByBSSIDs(networks.mapNotNull { it.BSSID?.lowercase(Locale.ROOT) })
                Log.d(
                    "WiFiScannerViewModel",
                    "Found ${networkInfoList.size} results for BSSIDs from API"
                )

                networkInfoList.forEach { (bssid, networkInfo) ->
                    val filteredNetworks =
                        networks.filter { it.BSSID?.lowercase(Locale.ROOT) == bssid.lowercase(Locale.ROOT) }
                    filteredNetworks.forEach { network ->
                        val existingResults =
                            results[bssid]?.filter { it.databaseName == db.path } ?: emptyList()
                        if (existingResults.isEmpty()) {
                            results.getOrPut(bssid) { mutableListOf() }
                                .addAll(networkInfo.map {
                                    NetworkDatabaseResult(
                                        network,
                                        it,
                                        db.path
                                    )
                                })
                        }
                    }
                }
            } catch (e: API3WiFiHelper.API3WiFiException) {
                Log.e("WiFiScannerViewModel", "API error: ${e.errorCode}", e)
                _error.postValue(
                    getApplication<Application>().getString(
                        R.string.ws_db_error,
                        e.errorCode,
                        e.message
                    )
                )
            } catch (e: Exception) {
                Log.e("WiFiScannerViewModel", "Error accessing API: ${db.path}", e)
                _error.postValue(
                    getApplication<Application>().getString(
                        R.string.ws_db_error,
                        "unknown",
                        e.message
                    )
                )
            }
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
    }

    fun resetScanningState() {
        isScanning = false
        unregisterScanReceiver()
    }

}