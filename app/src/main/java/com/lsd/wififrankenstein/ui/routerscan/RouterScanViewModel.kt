package com.lsd.wififrankenstein.ui.routerscan

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.RouterScanResult
import com.lsd.wififrankenstein.service.ChrootAttackService
import com.lsd.wififrankenstein.service.ChrootAttackType
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.RootlessManager
import com.lsd.wififrankenstein.util.RouterScanRunner
import com.lsd.wififrankenstein.util.RuntimeType
import com.lsd.wififrankenstein.util.SslHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RouterScanState(
    val isScanning: Boolean = false,
    val results: List<RouterScanResult> = emptyList(),
    val error: String? = null,
    val rsBinaryAvailable: Boolean = false,
    val pingCount: Int = 0,
    val successfulPingCount: Int = 0,
    val rsCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val totalToScan: Int = 0
)

data class RouterScanSettings(
    val maxThreads: Int = 10,
    val timeout: Long = 1000,
    val rsTimeout: Long = 30_000,
    val pingBeforeScan: Boolean = false,
    val saveToLocalDb: Boolean = true
)

class RouterScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableLiveData(RouterScanState())
    val state: LiveData<RouterScanState> = _state

    private val _consoleLines = MutableLiveData<List<String>>(emptyList())
    val consoleLines: LiveData<List<String>> = _consoleLines

    private val _progress = MutableLiveData<Int>(0)
    val progress: LiveData<Int> = _progress

    private val _scanComplete = MutableLiveData<Boolean>(false)
    val scanComplete: LiveData<Boolean> = _scanComplete

    private val _isUploading = MutableLiveData<Boolean>(false)
    val isUploading: LiveData<Boolean> = _isUploading

    private val _uploadResult = MutableLiveData<UploadResult?>(null)
    val uploadResult: LiveData<UploadResult?> = _uploadResult

    private val isRootlessProot by lazy {
        val cm = ChrootManager(getApplication())
        val chrootType = cm.getChrootType()
        when {
            chrootType is ChrootType.Rootless -> chrootType.rt == RuntimeType.PROOT
            chrootType is ChrootType.RootWithoutChroot || chrootType is ChrootType.RootMissing -> {
                RootlessManager(getApplication()).getRuntimeConfig()?.type == RuntimeType.PROOT
            }

            else -> false
        }
    }

    data class UploadResult(
        val success: Boolean,
        val message: String
    )

    private var currentRsBinaryAvailable = false

    private val mutableResults = mutableListOf<RouterScanResult>()
    private val mutableConsoleLines = mutableListOf<String>()

    private var currentPingCount = 0
    private var currentSuccessfulPingCount = 0
    private var currentRsCount = 0
    private var currentSuccessCount = 0
    private var currentFailureCount = 0
    private var totalToScan = 0

    private val prefs: SharedPreferences by lazy {
        application.getSharedPreferences("router_scan_prefs", Context.MODE_PRIVATE)
    }

    private val attackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val attackType = intent.getStringExtra(ChrootAttackService.EXTRA_ATTACK_TYPE)
            if (attackType != ChrootAttackType.ROUTER_SCAN.name) return

            when (intent.action) {
                ChrootAttackService.BROADCAST_PROGRESS -> {
                    val line =
                        intent.getStringExtra(ChrootAttackService.EXTRA_PROGRESS_TEXT) ?: return
                    addConsoleLine(line)
                    if (line.contains("is not responding") || line.contains("is responding")) {
                        currentPingCount++
                        if (line.contains("is responding")) {
                            currentSuccessfulPingCount++
                        }
                        _state.postValue(
                            _state.value?.copy(
                                pingCount = currentPingCount,
                                successfulPingCount = currentSuccessfulPingCount
                            )
                        )
                    }
                }

                ChrootAttackService.BROADCAST_ROUTER_RESULT -> {
                    val result = RouterScanResult(
                        ip = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_IP) ?: "",
                        port = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_PORT) ?: "80",
                        ssid = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_SSID) ?: "",
                        bssid = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_BSSID) ?: "",
                        auth = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_AUTH) ?: "",
                        sec = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_SEC) ?: "",
                        psk = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_PSK) ?: "",
                        wps = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_WPS) ?: "",
                        title = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_TITLE) ?: "",
                        serverType = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_SERVER_TYPE)
                            ?: "",
                        lanIp = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_LAN_IP) ?: "",
                        lanMask = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_LAN_MASK)
                            ?: "",
                        wanIp = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_WAN_IP) ?: "",
                        wanMask = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_WAN_MASK)
                            ?: "",
                        wanGate = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_WAN_GATE)
                            ?: "",
                        dns = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_DNS) ?: "",
                        success = intent.getBooleanExtra(
                            ChrootAttackService.EXTRA_RESULT_SUCCESS,
                            false
                        ),
                        status = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_STATUS)
                            ?: "",
                        type = intent.getIntExtra(ChrootAttackService.EXTRA_RESULT_TYPE, 0),
                        lat = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_LAT) ?: "N/A",
                        lon = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_LON) ?: "N/A",
                        scanned = intent.getBooleanExtra(
                            ChrootAttackService.EXTRA_RESULT_SCANNED,
                            false
                        ),
                        fullOutput = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_FULL_OUTPUT)
                            ?: ""
                    )

                    currentRsCount++
                    mutableResults.add(result)
                    if (result.success) currentSuccessCount++ else currentFailureCount++

                    Log.d(
                        TAG,
                        "Result: ip=${result.ip}, success=${result.success}, ssid='${result.ssid}', auth='${result.auth}'"
                    )

                    if (result.success && prefs.getBoolean("save_to_local_db", true)) {
                        saveSingleResultToLocalDb(result)
                    }

                    val progress = if (totalToScan > 0) (currentRsCount * 100) / totalToScan else 0
                    _state.postValue(
                        _state.value?.copy(
                            rsCount = currentRsCount,
                            successCount = currentSuccessCount,
                            failureCount = currentFailureCount,
                            results = mutableResults.toList()
                        )
                    )
                    _progress.postValue(progress)
                }

                ChrootAttackService.BROADCAST_COMPLETE -> {
                    _state.postValue(
                        _state.value?.copy(
                            results = mutableResults.toList(),
                            isScanning = false
                        )
                    )
                    _progress.postValue(100)
                    addConsoleLine(
                        getApplication<Application>().getString(
                            R.string.rs_scan_complete_line,
                            currentSuccessCount,
                            totalToScan
                        )
                    )
                    _scanComplete.postValue(true)
                }

                ChrootAttackService.BROADCAST_ERROR -> {
                    val errorMsg = intent.getStringExtra(ChrootAttackService.EXTRA_ERROR_MESSAGE)
                        ?: getApplication<Application>().getString(R.string.rs_unknown_error)
                    addConsoleLine(
                        getApplication<Application>().getString(R.string.rs_error_line, errorMsg)
                    )
                    _state.postValue(
                        _state.value?.copy(
                            isScanning = false,
                            error = errorMsg
                        )
                    )
                }
            }
        }
    }

    init {
        checkRsBinary()
        LocalBroadcastManager.getInstance(getApplication()).registerReceiver(
            attackReceiver,
            IntentFilter().apply {
                addAction(ChrootAttackService.BROADCAST_PROGRESS)
                addAction(ChrootAttackService.BROADCAST_ROUTER_RESULT)
                addAction(ChrootAttackService.BROADCAST_COMPLETE)
                addAction(ChrootAttackService.BROADCAST_ERROR)
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        try {
            LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(attackReceiver)
        } catch (_: Exception) {
        }
    }

    private fun getCurrentSettings(): RouterScanSettings = RouterScanSettings(
        maxThreads = prefs.getInt("max_threads", 10),
        timeout = prefs.getLong("timeout", 1000),
        rsTimeout = prefs.getLong("rs_timeout", 30_000),
        pingBeforeScan = if (isRootlessProot) false else prefs.getBoolean("ping_before_scan", false),
        saveToLocalDb = prefs.getBoolean("save_to_local_db", true)
    )

    private fun checkRsBinary() {
        viewModelScope.launch(Dispatchers.IO) {
            val runner = RouterScanRunner(getApplication())
            val available = runner.checkRsBinary()
            currentRsBinaryAvailable = available
            _state.postValue(_state.value?.copy(rsBinaryAvailable = available))
            Log.d(TAG, "rs binary available: $available")
        }
    }

    fun scanAll(ips: List<String>, ports: List<String>) {
        if (_state.value?.isScanning == true) {
            cancelScan()
        }

        if (!currentRsBinaryAvailable) {
            _state.value = _state.value?.copy(
                error = getApplication<Application>().getString(R.string.rs_binary_not_found)
            )
            return
        }

        totalToScan = ips.size * ports.size
        mutableResults.clear()
        mutableConsoleLines.clear()
        currentPingCount = 0
        currentSuccessfulPingCount = 0
        currentRsCount = 0
        currentSuccessCount = 0
        currentFailureCount = 0

        _state.value = _state.value?.copy(
            isScanning = true,
            error = null,
            results = emptyList(),
            pingCount = 0,
            successfulPingCount = 0,
            rsCount = 0,
            successCount = 0,
            failureCount = 0,
            totalToScan = totalToScan
        )
        _progress.value = 0
        _scanComplete.value = false
        addConsoleLine(
            getApplication<Application>().getString(
                R.string.rs_scanning_targets,
                totalToScan,
                getCurrentSettings().maxThreads,
                ports.size
            )
        )

        val settings = getCurrentSettings()
        ChrootAttackService.startRouterScan(
            context = getApplication(),
            ips = ArrayList(ips),
            ports = ArrayList(ports),
            maxThreads = settings.maxThreads,
            rsTimeout = settings.rsTimeout,
            pingBeforeScan = settings.pingBeforeScan
        )
    }

    fun cancelScan() {
        ChrootAttackService.cancelAttack(getApplication())
        _state.value = _state.value?.copy(isScanning = false)
        addConsoleLine(getApplication<Application>().getString(R.string.rs_scan_cancelled_line))
    }

    private fun saveSingleResultToLocalDb(result: RouterScanResult) {
        if (result.ssid.isBlank() && result.bssid.isBlank()) return
        try {
            val dbHelper = LocalAppDbHelper(getApplication())
            try {
                val db = dbHelper.writableDatabase
                val existing = db.query(
                    LocalAppDbHelper.TABLE_NAME,
                    arrayOf(LocalAppDbHelper.COLUMN_ID),
                    "${LocalAppDbHelper.COLUMN_WIFI_NAME} = ? AND ${LocalAppDbHelper.COLUMN_MAC_ADDRESS} = ?",
                    arrayOf(result.ssid, result.bssid), null, null, null
                ).use { it.count > 0 }

                if (!existing) {
                    dbHelper.addRecord(
                        WifiNetwork(
                            id = 0,
                            wifiName = result.ssid,
                            macAddress = result.bssid,
                            wifiPassword = result.psk,
                            wpsCode = result.wps,
                            adminPanel = result.title,
                            latitude = result.lat.toDoubleOrNull(),
                            longitude = result.lon.toDoubleOrNull()
                        )
                    )
                }
            } finally {
                dbHelper.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save RouterScan result to local DB", e)
        }
    }

    fun clearResults() {
        if (_state.value?.isScanning == true) {
            cancelScan()
        }
        mutableResults.clear()
        mutableConsoleLines.clear()
        currentPingCount = 0
        currentSuccessfulPingCount = 0
        currentRsCount = 0
        currentSuccessCount = 0
        currentFailureCount = 0
        totalToScan = 0
        _state.value = _state.value?.copy(
            results = emptyList(),
            error = null,
            pingCount = 0,
            successfulPingCount = 0,
            rsCount = 0,
            successCount = 0,
            failureCount = 0,
            totalToScan = 0
        )
        _consoleLines.value = emptyList()
        _progress.value = 0
        _scanComplete.value = false
    }

    fun updateSettings(
        maxThreads: Int,
        timeout: Long,
        rsTimeout: Long,
        pingBeforeScan: Boolean,
        saveToLocalDb: Boolean
    ) {
        prefs.edit().apply {
            putInt("max_threads", maxThreads)
            putLong("timeout", timeout)
            putLong("rs_timeout", rsTimeout)
            putBoolean("ping_before_scan", if (isRootlessProot) false else pingBeforeScan)
            putBoolean("save_to_local_db", saveToLocalDb)
            apply()
        }
    }

    fun getScanSettings(): RouterScanSettings = getCurrentSettings()

    fun saveInputState(ip: String, ports: String) {
        prefs.edit().apply {
            putString("last_ip", ip)
            putString("last_ports", ports)
            apply()
        }
    }

    fun addConsoleLine(line: String) {
        mutableConsoleLines.add(line)
        _consoleLines.postValue(mutableConsoleLines.toList())
    }

    fun saveState(ip: String? = null, ports: String? = null) {
        if (ip != null || ports != null) {
            prefs.edit().apply {
                if (ip != null) putString("last_ip", ip)
                if (ports != null) putString("last_ports", ports)
                apply()
            }
        }
        Log.d(TAG, "State saved")
    }

    fun restoreState(): Pair<String, String>? {
        val ip = prefs.getString("last_ip", null) ?: return null
        val ports = prefs.getString("last_ports", null) ?: return null
        Log.d(TAG, "Restored state: ip=$ip, ports=$ports")
        return ip to ports
    }

    fun getSuccessfulResults(): List<RouterScanResult> =
        mutableResults.filter { it.success && (it.ssid.isNotEmpty() || it.bssid.isNotEmpty()) }

    fun uploadTo3WiFi(results: List<RouterScanResult>, server: DbItem, comment: String) {
        if (results.isEmpty()) {
            _uploadResult.postValue(
                UploadResult(false, getApplication<Application>().getString(R.string.rs_no_results_upload))
            )
            return
        }
        viewModelScope.launch {
            _isUploading.value = true
            _uploadResult.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    performUpload(results, server, comment)
                }
                _uploadResult.value = result
            } catch (e: Exception) {
                _uploadResult.value = UploadResult(
                    false,
                    e.message ?: getApplication<Application>().getString(R.string.rs_upload_failed)
                )
            } finally {
                _isUploading.value = false
            }
        }
    }

    private fun performUpload(
        results: List<RouterScanResult>,
        server: DbItem,
        comment: String
    ): UploadResult {
        val csvData = convertTo3WiFiCsv(results)
        val serverUrl = server.path.trimEnd('/')
        val uploadUrl = "$serverUrl/3wifi.php?a=upload"

        val url = buildString {
            append(uploadUrl)
            val finalComment = if (comment.isNotBlank()) comment else "WiFi-Frankenstein RouterScan"
            append("&comment=${URLEncoder.encode(finalComment, "UTF-8")}")
            append("&checkexist=1")
            append("&done=1")
            if (!server.apiWriteKey.isNullOrBlank()) {
                append("&key=${URLEncoder.encode(server.apiWriteKey, "UTF-8")}")
            }
        }

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            SslHelper.configure(connection)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/csv")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            connection.outputStream.use { it.write(csvData.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                if (json.optBoolean("result", false)) {
                    val upload = json.optJSONObject("upload")
                    if (upload != null && upload.optBoolean("state", false)) {
                        return UploadResult(
                            true,
                            getApplication<Application>().getString(R.string.rs_uploaded, results.size)
                        )
                    } else {
                        val errors = upload?.optJSONArray("error")
                        val errorMsg = if (errors != null && errors.length() > 0) {
                            getApplication<Application>().getString(
                                R.string.rs_server_error_code,
                                errors.getInt(0)
                            )
                        } else {
                            getApplication<Application>().getString(R.string.rs_upload_rejected)
                        }
                        return UploadResult(false, errorMsg)
                    }
                } else {
                    return UploadResult(
                        false,
                        json.optString(
                            "error",
                            getApplication<Application>().getString(R.string.rs_unknown_server_error)
                        )
                    )
                }
            } else {
                return UploadResult(
                    false,
                    getApplication<Application>().getString(R.string.rs_http_error, responseCode)
                )
            }
        } catch (e: Exception) {
            return UploadResult(
                false,
                e.message ?: getApplication<Application>().getString(R.string.rs_connection_failed)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun escCsv(value: String): String {
        return if (value.contains(';') || value.contains('"') || value.contains('\n') || value.contains(
                '\r'
            )
        ) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    private fun convertTo3WiFiCsv(results: List<RouterScanResult>): String {
        return buildString {
            appendLine("IP Address;Port;;;Authorization;Server name / Realm name / Device type;Radio Off;Hidden;BSSID;ESSID;Security;Key;WPS PIN;LAN IP Address;LAN Subnet Mask;WAN IP Address;WAN Subnet Mask;WAN Gateway;Domain Name Servers")
            results.forEach { r ->
                appendLine(
                    "${escCsv(r.ip)};${escCsv(r.port)};;;${escCsv(r.auth)};${escCsv(r.title)};0;0;${
                        escCsv(
                            r.bssid
                        )
                    };${escCsv(r.ssid)};${escCsv(r.sec)};${escCsv(r.psk)};${escCsv(r.wps)};${
                        escCsv(
                            r.lanIp
                        )
                    };${escCsv(r.lanMask)};${escCsv(r.wanIp)};${escCsv(r.wanMask)};${
                        escCsv(
                            r.wanGate
                        )
                    };${escCsv(r.dns)}"
                )
            }
        }
    }

    companion object {
        private const val TAG = "RouterScanViewModel"
    }
}
