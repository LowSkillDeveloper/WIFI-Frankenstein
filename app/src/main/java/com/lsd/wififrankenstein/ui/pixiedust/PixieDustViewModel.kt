package com.lsd.wififrankenstein.ui.pixiedust

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.service.ChrootAttackService
import com.lsd.wififrankenstein.service.ChrootAttackType
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NativeWifiHelper
import com.lsd.wififrankenstein.util.PixieDustDbHelper
import com.lsd.wififrankenstein.util.PixieDustResult
import com.lsd.wififrankenstein.util.PixiePinProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PixieDustViewModel(application: Application) : AndroidViewModel(application) {

    private val iwWifiManager = IwWifiManager(application)
    private val chrootManager = ChrootManager.get(application)
    private val nativeWifiHelper = NativeWifiHelper(application)
    private val tag = "PixieDustVM"

    private val _interfaces = MutableLiveData<List<IwInterface>>()
    val interfaces: LiveData<List<IwInterface>> = _interfaces

    private val _networks = MutableLiveData<List<IwWifiNetwork>>()
    val networks: LiveData<List<IwWifiNetwork>> = _networks

    private val _selectedNetwork = MutableLiveData<IwWifiNetwork?>()
    val selectedNetwork: LiveData<IwWifiNetwork?> = _selectedNetwork

    private val _consoleLines = MutableLiveData<List<String>>()
    val consoleLines: LiveData<List<String>> = _consoleLines

    private val consoleLineBuffer = mutableListOf<String>()

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _needsLocationPermission = MutableLiveData(false)
    val needsLocationPermission: LiveData<Boolean> = _needsLocationPermission

    private val _isAttackRunning = MutableLiveData(false)
    val isAttackRunning: LiveData<Boolean> = _isAttackRunning

    private val _attackResult = MutableLiveData<PixieDustResult?>()
    val attackResult: LiveData<PixieDustResult?> = _attackResult

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    private val _scanMode = MutableLiveData(IwWifiManager.MODE_UNKNOWN)
    val scanMode: LiveData<String> = _scanMode

    private val _attackMode = MutableLiveData(IwWifiManager.MODE_UNKNOWN)
    val attackMode: LiveData<String> = _attackMode

    private val _pixieDone = MutableLiveData(false)
    val pixieDone: LiveData<Boolean> = _pixieDone

    private var scanInterface: String = "wlan0"
    private var attackInterface: String = "wlan0"

    private val broadcastManager by lazy {
        LocalBroadcastManager.getInstance(getApplication())
    }

    private val attackReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra(ChrootAttackService.EXTRA_ATTACK_TYPE)
            if (type != null && type != ChrootAttackType.PIXIE_DUST.name) return
            if (_isAttackRunning.value != true) return
            when (intent.action) {
                ChrootAttackService.BROADCAST_PROGRESS -> {
                    val text =
                        intent.getStringExtra(ChrootAttackService.EXTRA_PROGRESS_TEXT) ?: return
                    if (!prevDetailedLogging && text.startsWith("[stderr]")) return
                    addConsoleLine(text)
                    if (text == "PIXIE_DONE") {
                        _pixieDone.postValue(true)
                    }
                    if (text.contains("[-] Error: wrong PIN code") && !wrongPinShown) {
                        wrongPinShown = true
                        addConsoleLine("")
                        addConsoleLine("[INFO] Generated PIN didn\'t match — this is normal")
                        addConsoleLine("[INFO] The attack collected enough data for computation")
                        addConsoleLine("[INFO] Please wait for computation to finish...")
                        addConsoleLine("")
                    }
                }

                ChrootAttackService.BROADCAST_COMPLETE -> {
                    val pin = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_PIN)
                    val psk = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_PSK)
                    val success =
                        intent.getBooleanExtra(ChrootAttackService.EXTRA_RESULT_SUCCESS, false)
                    val raw = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_RAW) ?: ""
                    val reason = intent.getStringExtra(ChrootAttackService.EXTRA_RESULT_REASON)
                    val result = PixieDustResult(pin, psk, success, raw, reason)
                    _attackResult.postValue(result)
                    _isAttackRunning.postValue(false)
                    if (success && pin != null) {
                        addConsoleLine("[+] Attack SUCCESS! PIN: $pin")
                        savePixieResult(result)
                    } else {
                        addConsoleLine("[-] Attack FAILED")
                        reason?.let { addConsoleLine("[-] Reason: $it") }
                    }
                }

                ChrootAttackService.BROADCAST_ERROR -> {
                    val error = intent.getStringExtra(ChrootAttackService.EXTRA_ERROR_MESSAGE)
                        ?: "Unknown error"
                    addConsoleLine("[-] Error: $error")
                    _attackResult.postValue(
                        PixieDustResult(null, null, false, "ERROR: $error", reason = error)
                    )
                    _isAttackRunning.postValue(false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PixieDustVM"
        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val PIXIE_PREFS = "pixie_prefs"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
        private const val KEY_USE_NATIVE = "use_native_pixie"
        private const val MODE_POLL_INTERVAL_MS = 3000L
        private const val MAX_CONSOLE_LINES = 2000
    }

    private fun isNativeMode(): Boolean {
        return getApplication<Application>()
            .getSharedPreferences(PIXIE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_NATIVE, false)
    }

    init {
        loadInterfaces()
        registerAttackReceiver()
    }

    private fun registerAttackReceiver() {
        try {
            broadcastManager.unregisterReceiver(attackReceiver)
        } catch (_: Exception) {
        }
        val filter = IntentFilter().apply {
            addAction(ChrootAttackService.BROADCAST_PROGRESS)
            addAction(ChrootAttackService.BROADCAST_COMPLETE)
            addAction(ChrootAttackService.BROADCAST_ERROR)
        }
        broadcastManager.registerReceiver(attackReceiver, filter)
    }

    private fun unregisterAttackReceiver() {
        try {
            broadcastManager.unregisterReceiver(attackReceiver)
        } catch (_: Exception) {
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearAttackResult() {
        _attackResult.value = null
    }

    fun clearNeedsLocationPermission() {
        _needsLocationPermission.value = false
    }

    fun clearConsole() {
        synchronized(consoleLineBuffer) { consoleLineBuffer.clear() }
        _consoleLines.value = emptyList()
    }

    fun addConsoleLine(line: String) {
        synchronized(consoleLineBuffer) {
            consoleLineBuffer.add(line)
            if (consoleLineBuffer.size > MAX_CONSOLE_LINES) {
                val overflow = consoleLineBuffer.size - MAX_CONSOLE_LINES
                consoleLineBuffer.subList(0, overflow).clear()
            }
            _consoleLines.postValue(consoleLineBuffer.toList())
        }
    }

    fun loadInterfaces() {
        viewModelScope.launch {
            try {
                val ifaces = if (isNativeMode()) {
                    nativeWifiHelper.ensureReady()
                    nativeWifiHelper.getAvailableInterfaces()
                } else {
                    iwWifiManager.getAvailableInterfaces()
                }
                _interfaces.postValue(ifaces)
            } catch (e: Exception) {
                Log.e(tag, "Failed to load interfaces", e)
                _interfaces.postValue(listOf(IwInterface("wlan0")))
            }
        }
    }

    fun setScanInterface(iface: String) {
        scanInterface = iface
    }

    fun setAttackInterface(iface: String) {
        attackInterface = iface
    }

    fun checkScanMode(iface: String) {
        viewModelScope.launch {
            try {
                val mode = if (isNativeMode()) {
                    nativeWifiHelper.getInterfaceMode(iface)
                } else {
                    iwWifiManager.getInterfaceMode(iface)
                }
                _scanMode.postValue(mode)
            } catch (e: Exception) {
                Log.e(tag, "Failed to check scan mode", e)
                _scanMode.postValue(IwWifiManager.MODE_UNKNOWN)
            }
        }
    }

    fun checkAttackMode(iface: String) {
        viewModelScope.launch {
            try {
                val mode = if (isNativeMode()) {
                    nativeWifiHelper.getInterfaceMode(iface)
                } else {
                    iwWifiManager.getInterfaceMode(iface)
                }
                _attackMode.postValue(mode)
            } catch (e: Exception) {
                Log.e(tag, "Failed to check attack mode", e)
                _attackMode.postValue(IwWifiManager.MODE_UNKNOWN)
            }
        }
    }

    fun setInterfaceMode(iface: String, mode: String) {
        viewModelScope.launch {
            try {
                addConsoleLine("[*] Switching $iface to $mode mode...")
                val success = if (isNativeMode()) {
                    nativeWifiHelper.setInterfaceMode(iface, mode)
                } else {
                    iwWifiManager.setInterfaceMode(iface, mode)
                }
                if (success) {
                    addConsoleLine("[+] $iface switched to $mode mode")
                    checkScanMode(scanInterface)
                    checkAttackMode(attackInterface)
                    _toastMessage.postValue("Interface switched to $mode mode")
                } else {
                    addConsoleLine("[-] Failed to switch $iface to $mode mode")
                    _toastMessage.postValue("Failed to switch interface")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to set interface mode", e)
                addConsoleLine("[-] Error: ${e.message}")
            }
        }
    }

    fun scanNetworks(iface: String) {
        if (_isScanning.value == true) {
            addConsoleLine("[-] Scan already in progress")
            return
        }
        _isScanning.value = true
        _statusText.value = getApplication<Application>().getString(
            com.lsd.wififrankenstein.R.string.pixiedust_scanning
        )

        viewModelScope.launch {
            try {
                val nets = scanWithFallback(iface)
                _networks.postValue(nets)
                _statusText.value = if (nets.isEmpty()) {
                    getApplication<Application>().getString(
                        com.lsd.wififrankenstein.R.string.pixiedust_no_networks
                    )
                } else {
                    "${nets.size} networks found"
                }
            } catch (e: SecurityException) {
                Log.e(tag, "Scan failed: location permission required", e)
                _needsLocationPermission.postValue(true)
                _statusText.value = getApplication<Application>().getString(
                    com.lsd.wififrankenstein.R.string.location_permission_required
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Scan failed", e)
                _statusText.value = "Scan failed: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    private suspend fun scanWithFallback(iface: String): List<IwWifiNetwork> {
        val isNative = isNativeMode()


        if (!isNative) {
            val chrootType = chrootManager.getChrootType()
            if (chrootType is ChrootType.Root) {
                addConsoleLine("[*] Scanning for networks on $iface (chroot)...")
                try {
                    val chrootNets = withTimeout(30_000L) {
                        chrootManager.resetMountFailedCooldown()
                        iwWifiManager.mountChroot()
                        iwWifiManager.scanWifiNetworks(iface)
                    }
                    if (chrootNets.isNotEmpty()) {
                        addConsoleLine("[+] Scan complete: ${chrootNets.size} networks found (chroot)")
                        return chrootNets
                    }
                    addConsoleLine("[-] Chroot scan returned no networks, falling back")
                } catch (e: TimeoutCancellationException) {
                    Log.w(tag, "Chroot scan timed out, falling back")
                    addConsoleLine("[-] Chroot scan timed out, falling back")
                } catch (e: Exception) {
                    Log.w(tag, "Chroot scan failed, falling back", e)
                    addConsoleLine("[-] Chroot scan failed, falling back")
                }
            }
        }


        addConsoleLine("[*] Scanning for networks on $iface (in-app iw)...")
        try {
            val iwNets = withTimeout(30_000L) {
                nativeWifiHelper.ensureReady()
                nativeWifiHelper.scanWifiNetworks(iface)
            }
            if (iwNets.isNotEmpty()) {
                addConsoleLine("[+] Scan complete: ${iwNets.size} networks found (in-app iw)")
                return iwNets
            }
            addConsoleLine("[-] In-app iw scan returned no networks, falling back")
        } catch (e: TimeoutCancellationException) {
            Log.w(tag, "In-app iw scan timed out, falling back")
            addConsoleLine("[-] In-app iw scan timed out, falling back")
        } catch (e: Exception) {
            Log.w(tag, "In-app iw scan failed, falling back", e)
            addConsoleLine("[-] In-app iw scan failed, falling back")
        }


        addConsoleLine("[*] Scanning via Android system WiFi...")
        val systemNets = iwWifiManager.scanWifiNetworksNative()
        if (systemNets.isNotEmpty()) {
            addConsoleLine("[+] Scan complete: ${systemNets.size} networks found (system)")
        }
        return systemNets
    }

    fun selectNetwork(network: IwWifiNetwork) {
        _selectedNetwork.value = network
    }

    private var modePollJob: Job? = null

    private var wrongPinShown = false
    private var prevDetailedLogging = false

    fun startModePolling() {
        modePollJob?.cancel()
        modePollJob = viewModelScope.launch {
            while (true) {
                try {
                    checkScanMode(scanInterface)
                    checkAttackMode(attackInterface)
                } catch (_: Exception) {
                }
                delay(MODE_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopModePolling() {
        modePollJob?.cancel()
        modePollJob = null
    }

    fun startAttack(
        bssid: String,
        iface: String,
        disableWifi: Boolean,
        usePinGenerator: Boolean,
        useNative: Boolean,
        dbSetupViewModel: DbSetupViewModel?
    ) {
        if (_isAttackRunning.value == true) {
            addConsoleLine("[-] Attack already in progress")
            return
        }

        _isAttackRunning.value = true
        _attackResult.value = null
        _pixieDone.value = false
        wrongPinShown = false
        clearConsole()

        addConsoleLine("[*] Starting PixieDust attack on $bssid via $iface")

        val prefs = getApplication<Application>()
            .getSharedPreferences("pixie_prefs", Context.MODE_PRIVATE)
        prevDetailedLogging = prefs.getBoolean("detailed_logging", false)

        viewModelScope.launch {
            try {
                val targetSsid = _selectedNetwork.value?.ssid
                if (useNative) {
                    ChrootAttackService.startPixieDust(
                        getApplication(),
                        bssid = bssid,
                        iface = iface,
                        pin = null,
                        disableWifi = disableWifi,
                        useNative = true,
                        ssid = targetSsid,
                        freq = _selectedNetwork.value?.frequency
                            ?.filter { it.isDigit() }
                            ?.toIntOrNull()
                    )
                } else {
                    chrootManager.resetMountFailedCooldown()
                    iwWifiManager.mountChroot()

                    var customPin: String? = null
                    if (usePinGenerator) {
                        try {
                            val provider = PixiePinProvider(getApplication())
                            val dbItems = dbSetupViewModel?.dbList?.value
                            val bestScored = provider.getBestScoredPin(bssid, dbItems)
                            val highQualitySources = setOf(
                                "suggested", "local_db", "wps_db", "neighbor",
                                "3wifi_database", "custom_database", "neighbor_3wifi"
                            )
                            if (bestScored != null && bestScored.source in highQualitySources) {
                                customPin = bestScored.pin
                                addConsoleLine("[*] Using WPS PIN Generator: $customPin")
                            } else if (bestScored != null) {
                                addConsoleLine("[*] WPS PIN Generator: only algorithmic PINs found, attacking without -p")
                            } else {
                                addConsoleLine("[*] WPS PIN Generator: no PIN found, using default")
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "PIN generation failed", e)
                            addConsoleLine("[!] PIN generator error: ${e.message}")
                        }
                    }

                    ChrootAttackService.startPixieDust(
                        getApplication(),
                        bssid = bssid,
                        iface = iface,
                        pin = customPin,
                        disableWifi = disableWifi,
                        useNative = false
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start attack service", e)
                addConsoleLine("[-] Error: ${e.message}")
                _isAttackRunning.postValue(false)
            }
        }
    }

    fun stopAttack() {
        addConsoleLine("[-] Attack cancelled by user")
        ChrootAttackService.cancelAttack(getApplication())
        _isAttackRunning.value = false
        _attackResult.postValue(
            PixieDustResult(
                null,
                null,
                false,
                "CANCELLED: Attack cancelled by user"
            )
        )
    }

    private fun savePixieResult(result: PixieDustResult) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val net = _selectedNetwork.value
                if (net != null) {
                    val dbHelper = PixieDustDbHelper(getApplication())
                    try {
                        dbHelper.insertResult(
                            bssid = net.bssid,
                            essid = net.ssid,
                            wpsPin = result.wpsPin ?: "",
                            wpaPsk = result.wpaPsk ?: "",
                            latitude = null,
                            longitude = null,
                            timestamp = System.currentTimeMillis()
                        )
                    } finally {
                        dbHelper.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save result to PixieDustDb", e)
            }
            val net = _selectedNetwork.value
            addToLocalDatabase(
                essid = net?.ssid ?: "",
                bssid = net?.bssid ?: "",
                wpsPin = result.wpsPin ?: "",
                wpaPsk = result.wpaPsk
            )
        }
    }

    private fun addToLocalDatabase(
        essid: String,
        bssid: String,
        wpsPin: String,
        wpaPsk: String?
    ) {
        try {
            val appContext = getApplication<Application>()
            val dbHelper = LocalAppDbHelper(appContext)
            try {
                dbHelper.addRecord(
                    WifiNetwork(
                        id = 0,
                        wifiName = essid,
                        macAddress = bssid,
                        wifiPassword = wpaPsk,
                        wpsCode = wpsPin,
                        adminPanel = null,
                        latitude = null,
                        longitude = null
                    )
                )
                Log.d(tag, "Result added to local app database")
            } finally {
                dbHelper.close()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to add result to local app database", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterAttackReceiver()
    }
}
