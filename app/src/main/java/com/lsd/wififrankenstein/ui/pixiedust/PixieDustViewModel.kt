package com.lsd.wififrankenstein.ui.pixiedust

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.delay

class PixieDustViewModel(application: Application) : AndroidViewModel(application), PixieDustHelper.PixieDustCallbacks {

    private val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private lateinit var pixieHelper: PixieDustHelper

    private val _wpsNetworks = MutableLiveData<List<WpsNetwork>>()
    val wpsNetworks: LiveData<List<WpsNetwork>> = _wpsNetworks

    private val _attackState = MutableLiveData<PixieAttackState>(PixieAttackState.Idle)
    val attackState: LiveData<PixieAttackState> = _attackState

    private val _progressMessage = MutableLiveData<String>()
    val progressMessage: LiveData<String> = _progressMessage

    private val _isScanning = MutableLiveData<Boolean>(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _selectedNetwork = MutableLiveData<WpsNetwork?>()
    val selectedNetwork: LiveData<WpsNetwork?> = _selectedNetwork

    private val _rootAccessAvailable = MutableLiveData<Boolean>()
    val rootAccessAvailable: LiveData<Boolean> = _rootAccessAvailable

    private val _binariesReady = MutableLiveData<Boolean>()
    val binariesReady: LiveData<Boolean> = _binariesReady

    private val _logEntries = MutableLiveData<List<LogEntry>>(emptyList())
    val logEntries: LiveData<List<LogEntry>> = _logEntries

    private var wpsTimeout = 40000L
    private var extractionTimeout = 30000L
    private var computationTimeout = 300000L

    private var useAggressiveCleanup = false


    init {
        pixieHelper = PixieDustHelper(getApplication(), this)
        checkSystemRequirements()
    }

    private fun checkSystemRequirements() {
        viewModelScope.launch(Dispatchers.IO) {
            _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_checking_root))

            val hasRoot = pixieHelper.checkRootAccess()
            _rootAccessAvailable.postValue(hasRoot)

            if (hasRoot) {
                _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_checking_binaries))

                var hasBinaries = pixieHelper.checkBinaryFiles()
                _binariesReady.postValue(hasBinaries)

                if (!hasBinaries) {
                    _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_preparing_binaries))

                    try {
                        pixieHelper.copyBinariesFromAssets()
                        delay(5000)

                        hasBinaries = pixieHelper.checkBinaryFiles()
                        _binariesReady.postValue(hasBinaries)

                        if (hasBinaries) {
                            _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_binaries_ready))
                        } else {
                            _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_binary_files_not_available))
                        }
                    } catch (e: Exception) {
                        Log.e("PixieDustViewModel", "Error during binary setup", e)
                        _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_error_copying_binaries))
                    }
                } else {
                    _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_binaries_ready))
                }
            } else {
                _binariesReady.postValue(false)
                _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_root_not_available))
            }
        }
    }

    fun setAggressiveCleanup(enabled: Boolean) {
        useAggressiveCleanup = enabled
        pixieHelper.setAggressiveCleanup(enabled)
    }

    fun getAggressiveCleanup() = useAggressiveCleanup

    fun recheckBinaries() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasBinaries = pixieHelper.checkBinaryFiles()
            _binariesReady.postValue(hasBinaries)

            if (hasBinaries) {
                _progressMessage.postValue(getApplication<Application>().getString(R.string.pixiedust_binaries_ready))
            }
        }
    }

    fun setWpsTimeout(timeout: Long) {
        wpsTimeout = timeout
    }

    fun setExtractionTimeout(timeout: Long) {
        extractionTimeout = timeout
    }

    fun setComputationTimeout(timeout: Long) {
        computationTimeout = timeout
    }

    fun getWpsTimeout() = wpsTimeout
    fun getExtractionTimeout() = extractionTimeout
    fun getComputationTimeout() = computationTimeout

    fun scanForWpsNetworks() {
        if (_isScanning.value == true) return

        _isScanning.value = true
        _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_scanning)

        viewModelScope.launch {
            try {
                if (!wifiManager.isWifiEnabled) {
                    _progressMessage.value = "WiFi is disabled"
                    _isScanning.value = false
                    return@launch
                }

                val success = wifiManager.startScan()
                if (!success) {
                    _progressMessage.value = "Failed to start WiFi scan"
                    _isScanning.value = false
                    return@launch
                }

                delay(3000)

                val scanResults = wifiManager.scanResults
                val wpsNetworks = scanResults
                    .filter { it.capabilities.contains("WPS") }
                    .map { scanResult ->
                        WpsNetwork(
                            ssid = scanResult.SSID ?: "Unknown",
                            bssid = scanResult.BSSID,
                            capabilities = scanResult.capabilities,
                            level = scanResult.level,
                            frequency = scanResult.frequency,
                            scanResult = scanResult
                        )
                    }
                    .sortedByDescending { it.level }

                val currentNetworks = _wpsNetworks.value?.toMutableList() ?: mutableListOf()
                currentNetworks.removeAll { !it.isManual }
                currentNetworks.addAll(wpsNetworks)

                _wpsNetworks.value = currentNetworks
                _progressMessage.value = getApplication<Application>().getString(
                    R.string.pixiedust_networks_found,
                    wpsNetworks.size
                )

            } catch (e: Exception) {
                _progressMessage.value = "Scan error: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun addManualNetwork(ssid: String, bssid: String) {
        val manualNetwork = WpsNetwork(
            ssid = ssid,
            bssid = bssid.uppercase(),
            capabilities = "WPS",
            level = -50,
            frequency = 2400,
            isManual = true
        )

        val currentNetworks = _wpsNetworks.value?.toMutableList() ?: mutableListOf()
        currentNetworks.removeAll { it.bssid.equals(bssid, ignoreCase = true) }
        currentNetworks.add(0, manualNetwork)
        _wpsNetworks.value = currentNetworks

        addLogEntry(LogEntry("Manual network added: $ssid ($bssid)"))
    }

    fun selectNetwork(network: WpsNetwork) {
        _selectedNetwork.value = network
        if (_attackState.value is PixieAttackState.Completed || _attackState.value is PixieAttackState.Failed) {
            _attackState.value = PixieAttackState.Idle
        }
        addLogEntry(LogEntry("Selected network: ${network.ssid} (${network.bssid})"))
    }

    fun startPixieAttack() {
        val network = _selectedNetwork.value
        if (network == null) {
            _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_select_network)
            return
        }

        if (_rootAccessAvailable.value != true) {
            _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_root_required)
            return
        }

        if (_binariesReady.value != true) {
            _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_binaries_not_found)
            return
        }

        pixieHelper.startPixieAttack(network, wpsTimeout, extractionTimeout, computationTimeout)
    }

    fun stopAttack() {
        pixieHelper.stopAttack()
    }

    fun saveResult(result: PixieResult) {
        if (result.success && result.pin != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val prefs = context.getSharedPreferences("pixiedust_results", Context.MODE_PRIVATE)
                    with(prefs.edit()) {
                        putString("${result.network.bssid}_pin", result.pin)
                        putLong("${result.network.bssid}_timestamp", result.timestamp)
                        putString("${result.network.bssid}_ssid", result.network.ssid)
                        apply()
                    }

                    _progressMessage.postValue(
                        getApplication<Application>().getString(R.string.pixiedust_result_saved)
                    )
                    addLogEntry(LogEntry("Result saved for ${result.network.ssid}", LogColorType.INFO))
                } catch (e: Exception) {
                    _progressMessage.postValue("Failed to save result: ${e.message}")
                    addLogEntry(LogEntry("Failed to save result: ${e.message}", LogColorType.ERROR))
                }
            }
        }
    }

    fun clearLog() {
        _logEntries.value = emptyList()
    }

    private fun addLogEntry(logEntry: LogEntry) {
        val currentEntries = _logEntries.value?.toMutableList() ?: mutableListOf()
        currentEntries.add(0, logEntry)
        if (currentEntries.size > 100) {
            currentEntries.removeAt(currentEntries.size - 1)
        }
        _logEntries.value = currentEntries
    }

    override fun onStateChanged(state: PixieAttackState) {
        viewModelScope.launch(Dispatchers.Main) {
            _attackState.value = state

            val message = when (state) {
                is PixieAttackState.Idle -> {
                    recheckBinaries()
                    ""
                }
                is PixieAttackState.Scanning -> getApplication<Application>().getString(R.string.pixiedust_scanning)
                is PixieAttackState.CheckingRoot -> getApplication<Application>().getString(R.string.pixiedust_checking_root)
                is PixieAttackState.Preparing -> getApplication<Application>().getString(R.string.pixiedust_preparing)
                is PixieAttackState.ExtractingData -> getApplication<Application>().getString(R.string.pixiedust_extracting_data)
                is PixieAttackState.RunningAttack -> getApplication<Application>().getString(R.string.pixiedust_running_attack)
                is PixieAttackState.Completed -> getApplication<Application>().getString(R.string.pixiedust_attack_completed)
                is PixieAttackState.Failed -> getApplication<Application>().getString(R.string.pixiedust_attack_failed, state.error)
            }

            _progressMessage.value = message
        }
    }

    override fun onProgressUpdate(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _progressMessage.value = message
        }
    }

    override fun onAttackCompleted(result: PixieResult) {
        viewModelScope.launch(Dispatchers.Main) {
            _attackState.value = PixieAttackState.Completed(result)

            if (result.success && result.pin != null) {
                _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_pin_found, result.pin)
            } else {
                _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_pin_not_found)
            }
        }
    }

    override fun onAttackFailed(error: String, errorCode: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            _attackState.value = PixieAttackState.Failed(error, errorCode)
            _progressMessage.value = getApplication<Application>().getString(R.string.pixiedust_attack_failed, error)

            pixieHelper.forceCleanup()
        }
    }

    override fun onLogEntry(logEntry: LogEntry) {
        viewModelScope.launch(Dispatchers.Main) {
            addLogEntry(logEntry)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pixieHelper.cleanup()
    }
}