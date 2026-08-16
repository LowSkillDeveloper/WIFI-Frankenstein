package com.lsd.wififrankenstein.ui.airodump

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeItem
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.CaptureFormat
import com.lsd.wififrankenstein.util.CaptureStats
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.DetectionState
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.HandshakeResult
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class CaptureState {
    IDLE, MONITORING, CAPTURING, COMPLETE
}

data class InterfaceStatus(val name: String, val mode: String, val subtitle: String? = null)

enum class CaptureResultKind { HANDSHAKE, PMKID, BOTH, PARTIAL, NONE }

data class CaptureResult(
    val kind: CaptureResultKind,
    val capFilePath: String?,
    val bssid: String?,
    val essid: String?
)

class AirodumpViewModel(application: Application) : AndroidViewModel(application) {

    private val iwWifiManager = IwWifiManager(application)
    private val captureRunner = HandshakeCaptureRunner(application)
    private val storageManager = HandshakeStorageManager(application)
    private val chrootManager = ChrootManager.get(application)
    private val captureLocationProvider = CaptureLocationProvider(application)
    private val tag = "AirodumpVM"

    private val _cleaningUp = AtomicBoolean(false)
    private val _captureStarted = AtomicBoolean(false)

    private val _interfaces = MutableLiveData<List<IwInterface>>()
    val interfaces: LiveData<List<IwInterface>> = _interfaces

    private val _networks = MutableLiveData<List<IwWifiNetwork>>()
    val networks: LiveData<List<IwWifiNetwork>> = _networks

    data class LeftoverCapture(
        val filePath: String,
        val essid: String?,
        val bssid: String?,
        val valid: Boolean
    )

    private val _leftoverCaptures = MutableLiveData<List<LeftoverCapture>?>(null)
    val leftoverCaptures: LiveData<List<LeftoverCapture>?> = _leftoverCaptures

    private val _leftoverImportRunning = MutableLiveData(false)
    val leftoverImportRunning: LiveData<Boolean> = _leftoverImportRunning

    fun checkLeftoverCaptures() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val captures = scanLeftoverCaptures()
                val existingBssids = storageManager.listHandshakes()
                    .mapNotNull { it.bssid?.uppercase() }
                    .toSet()
                val newCaptures = captures.filter { c ->
                    c.valid && (c.bssid?.uppercase() !in existingBssids)
                }
                if (newCaptures.isNotEmpty()) {
                    _leftoverCaptures.postValue(newCaptures)
                }
            } catch (e: Exception) {
                Log.e(tag, "checkLeftoverCaptures failed", e)
            }
        }
    }

    fun importLeftoverCaptures() {
        val captures = _leftoverCaptures.value ?: return
        _leftoverImportRunning.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            var failed = 0
            for (cap in captures) {
                try {
                    var allHashes = mutableListOf<HandshakeHash>()
                    var essid = cap.essid
                    var bssid = cap.bssid


                    try {
                        val raw = captureRunner.getHcxpcapngtoolOutput(cap.filePath)
                        val parsed =
                            raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                        if (parsed.isNotEmpty()) {
                            allHashes.addAll(parsed)
                        }
                    } catch (_: Exception) {
                    }


                    if (allHashes.isEmpty() && !ChrootCapabilities.isAvailable(getApplication())) {
                        try {
                            val jvmPath =
                                cap.filePath.replaceFirst("/sdcard", "/storage/emulated/0")
                            val file = File(jvmPath)
                            if (file.exists()) {
                                val parsed = HandshakeParser.parseFile(file)
                                if (parsed.isNotEmpty()) {
                                    allHashes.addAll(parsed)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    if (allHashes.isEmpty()) {
                        failed++
                        continue
                    }

                    val hash22000Lines = allHashes.map { it.to22000Line() }.distinct()
                    val hash22000 = hash22000Lines.joinToString("\n").takeIf { it.isNotBlank() }
                    val hashPmkid = allHashes.firstOrNull { it.type == HandshakeType.PMKID }
                        ?.pmkidOrMic?.takeIf { it.length == 32 }
                    val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
                    val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
                    val handshakeCount = allHashes.size
                    val firstHash = allHashes.firstOrNull()
                    if (firstHash?.essid != null) essid = firstHash.essid
                    if (firstHash?.macAp != null) bssid = firstHash.macAp

                    val saved = storageManager.moveToStorage(cap.filePath, essid, bssid)
                    if (saved != null) {
                        val stat =
                            chrootManager.executeInChroot("stat -c '%s' '$saved' 2>/dev/null")
                        val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                        storageManager.saveHandshakeMetadata(
                            HandshakeItem(
                                filePath = saved,
                                fileName = File(saved).name,
                                bssid = bssid,
                                essid = essid,
                                fileSize = fileSize,
                                lastModified = System.currentTimeMillis(),
                                hash22000 = hash22000,
                                hashPmkid = hashPmkid,
                                originalFormat = File(cap.filePath).extension.lowercase(),
                                handshakeCount = handshakeCount,
                                eapolCount = eapolCount,
                                pmkidCount = pmkidCount,
                                hashDedupMd5 = allHashes.firstOrNull()?.dedupKey()
                            )
                        )
                        chrootManager.executeInChroot("rm -rf '${File(cap.filePath).parent}' 2>/dev/null; true")
                        imported++
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    Log.e(tag, "importLeftoverCapture failed: ${cap.filePath}", e)
                    failed++
                }
            }
            _leftoverImportRunning.postValue(false)
            _leftoverCaptures.postValue(null)
            withContext(Dispatchers.Main) {
                if (imported > 0) {
                    addConsoleLine(
                        getApplication<Application>().getString(
                            R.string.aird_imported,
                            imported
                        ) +
                                if (failed > 0) getApplication<Application>().getString(
                                    R.string.aird_imported_failed_suffix,
                                    failed
                                ) else ""
                    )
                    loadStorage()
                }
            }
        }
    }

    fun dismissLeftoverCaptures() {
        _leftoverCaptures.postValue(null)
    }

    private suspend fun scanLeftoverCaptures(): List<LeftoverCapture> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<LeftoverCapture>()
            val hsDir = HandshakeCaptureRunner.OUTPUT_BASE

            val lsDirs = chrootManager.executeInChroot("ls -1 '$hsDir' 2>/dev/null")
            for (dirLine in lsDirs.out) {
                val dirName = dirLine.trim()
                if (dirName.isEmpty()) continue
                val dirPath = "$hsDir/$dirName"

                val lsFiles = chrootManager.executeInChroot(
                    "ls -1 '$dirPath' 2>/dev/null | grep -E '\\.(cap|pcap|pcapng)$'"
                )
                for (fileLine in lsFiles.out) {
                    val fileName = fileLine.trim()
                    if (fileName.isEmpty()) continue
                    val filePath = "$dirPath/$fileName"

                    try {
                        val raw = captureRunner.getHcxpcapngtoolOutput(filePath)
                        val hcxLines = raw.lines()
                        val valid = hcxLines.any { line ->
                            line.startsWith("WPA*01\t") || line.startsWith("WPA*02\t") ||
                                    line.startsWith("WPA*03\t") || line.startsWith("WPA01\t") ||
                                    line.startsWith("WPA02\t") || line.startsWith("WPA03\t")
                        }
                        val firstHash = hcxLines.firstOrNull {
                            it.startsWith("WPA*01\t") || it.startsWith("WPA*02\t") || it.startsWith(
                                "WPA*03\t"
                            )
                        }
                        var essid: String? = null
                        var bssid: String? = null
                        if (firstHash != null) {
                            val parsed = HandshakeHash.parse22000Line(firstHash.trim())
                            if (parsed != null) {
                                essid = parsed.essid
                                bssid = parsed.macAp
                            }
                        }
                        result.add(LeftoverCapture(filePath, essid, bssid, valid))
                    } catch (_: Exception) {

                    }
                }
            }
            result
        }

    private val _selectedNetwork = MutableLiveData<IwWifiNetwork?>()
    val selectedNetwork: LiveData<IwWifiNetwork?> = _selectedNetwork

    private val _state = MutableLiveData(CaptureState.IDLE)
    val state: LiveData<CaptureState> = _state

    private val _consoleLines = MutableLiveData<List<String>>()
    val consoleLines: LiveData<List<String>> = _consoleLines

    private val consoleLineBuffer = mutableListOf<String>()

    private val _result = MutableLiveData<HandshakeResult?>()
    val result: LiveData<HandshakeResult?> = _result

    private val _captureResult = MutableLiveData<CaptureResult?>()
    val captureResult: LiveData<CaptureResult?> = _captureResult

    private val _verifyResult = MutableLiveData<Boolean?>()
    val verifyResult: LiveData<Boolean?> = _verifyResult

    data class HcxpcapngtoolResult(
        val valid: Boolean,
        val eapolCount: Int,
        val pmkidCount: Int,
        val packetsTotal: Int,
        val durationSec: Int,
        val essid: String,
        val bssid: String,
        val channel: Int,
        val rawOutput: String
    )

    private val _hcxpcapngtoolResult = MutableLiveData<HcxpcapngtoolResult?>(null)
    val hcxpcapngtoolResult: LiveData<HcxpcapngtoolResult?> = _hcxpcapngtoolResult

    fun clearHcxpcapngtoolResult() {
        _hcxpcapngtoolResult.value = null
    }

    private fun parseHcxpcapngtoolOutput(raw: String): HcxpcapngtoolResult {
        val lines = raw.lines()
        val eapolCount = lines.count { it.contains("EAPOL", ignoreCase = true) }
        val pmkidCount = lines.count { it.contains("PMKID", ignoreCase = true) }
        val packetsTotal = lines.firstOrNull { it.contains("packets inside", ignoreCase = true) }
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val duration = lines.firstOrNull { it.contains("duration", ignoreCase = true) }
            ?.let { Regex("""(\d+)s""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val essid = lines.firstOrNull { it.contains("ESSID", ignoreCase = true) }
            ?.substringAfter(":").orEmpty().trim()
        val channel = lines.firstOrNull {
            it.contains("BEACON", ignoreCase = true) && it.contains(
                "channel",
                ignoreCase = true
            )
        }
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val hasValidData = lines.any { line ->
            val t = line.trim()
            t.startsWith("WPA*01\t") || t.startsWith("WPA*02\t") || t.startsWith("WPA*03\t") ||
                    t.startsWith("WPA01\t") || t.startsWith("WPA02\t") || t.startsWith("WPA03\t") ||
                    t.contains("WPA*01*") || t.contains("WPA*02*") || t.contains("WPA*03*")
        }

        return HcxpcapngtoolResult(
            valid = hasValidData || eapolCount > 0,
            eapolCount = eapolCount,
            pmkidCount = pmkidCount,
            packetsTotal = packetsTotal,
            durationSec = duration,
            essid = essid,
            bssid = "",
            channel = channel,
            rawOutput = raw
        )
    }

    private val _crackResult = MutableLiveData<String?>()
    val crackResult: LiveData<String?> = _crackResult

    private val _statusText = MutableLiveData<String>()
    val statusText: LiveData<String> = _statusText

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _isSwitchingMode = MutableLiveData(false)
    val isSwitchingMode: LiveData<Boolean> = _isSwitchingMode

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    private val _isCaptureRunning = MutableLiveData(false)
    val isCaptureRunning: LiveData<Boolean> = _isCaptureRunning

    private val _interfaceMode = MutableLiveData(IwWifiManager.MODE_UNKNOWN)
    val interfaceMode: LiveData<String> = _interfaceMode

    private val _activeInterfaceName = MutableLiveData<String?>(null)
    val activeInterfaceName: LiveData<String?> = _activeInterfaceName

    private val _deauthMode = MutableLiveData(IwWifiManager.MODE_UNKNOWN)
    val deauthMode: LiveData<String> = _deauthMode

    private val _scanMode = MutableLiveData(IwWifiManager.MODE_UNKNOWN)
    val scanMode: LiveData<String> = _scanMode

    private val _storageItems = MutableLiveData<List<HandshakeItem>>(emptyList())
    val storageItems: LiveData<List<HandshakeItem>> = _storageItems

    private val _storageCrackResult = MutableLiveData<Pair<String, String>?>()
    val storageCrackResult: LiveData<Pair<String, String>?> = _storageCrackResult

    private val _storageVerifyResult = MutableLiveData<Pair<String, Boolean>?>()
    val storageVerifyResult: LiveData<Pair<String, Boolean>?> = _storageVerifyResult

    private val _interfaceStatuses = MutableLiveData<List<InterfaceStatus>>(emptyList())
    val interfaceStatuses: LiveData<List<InterfaceStatus>> = _interfaceStatuses

    private val _captureStats = MutableLiveData(CaptureStats())
    val captureStats: LiveData<CaptureStats> = _captureStats

    private val _captureFormat = MutableLiveData(CaptureFormat.DEFAULT)
    val captureFormat: LiveData<CaptureFormat> = _captureFormat

    fun setCaptureFormat(format: CaptureFormat) {
        _captureFormat.value = format
    }

    private val _captureEvents = MutableLiveData<Set<String>>(emptySet())
    val captureEvents: LiveData<Set<String>> = _captureEvents

    private val _savePromptRequest = MutableLiveData<SavePromptRequest?>(null)
    val savePromptRequest: LiveData<SavePromptRequest?> = _savePromptRequest

    private val _saveUnverifiedRequest = MutableLiveData<SavePromptRequest?>(null)
    val saveUnverifiedRequest: LiveData<SavePromptRequest?> = _saveUnverifiedRequest

    private val _captureTimerText = MutableLiveData<String>()
    val captureTimerText: LiveData<String> = _captureTimerText

    private val _captureProgress = MutableLiveData(0)
    val captureProgress: LiveData<Int> = _captureProgress

    private val _idleWarning = MutableLiveData(false)
    val idleWarning: LiveData<Boolean> = _idleWarning

    data class SavePromptRequest(
        val type: String,
        val capFilePath: String,
        val bssid: String,
        val channel: String,
        val essid: String,
        val secondsLeft: Int
    )

    private val reportedClients = mutableSetOf<String>()

    fun updateCaptureStats(stats: CaptureStats) {
        for (client in stats.clients) {
            if (reportedClients.add(client.mac)) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_new_client, client.mac, client.power, client.rate))
            }
        }
        _captureStats.postValue(stats)
    }

    fun resetCaptureStats() {
        reportedClients.clear()
        _captureStats.postValue(CaptureStats())
        _captureEvents.postValue(emptySet())
        _captureProgress.postValue(0)
        _captureTimerText.postValue(formatTimerText(0L, CAPTURE_TIMEOUT_MS))
        _idleWarning.postValue(false)
        _saveUnverifiedRequest.value = null
        hcxpcapngtoolHandshakeConfirmed = false
        unverifiedHandshakeDetected = false
    }

    fun emitCaptureEvent(event: String) {
        val current = _captureEvents.value?.toMutableSet() ?: mutableSetOf()
        current.add(event)
        _captureEvents.postValue(current)
    }

    fun clearCaptureEvents() {
        _captureEvents.postValue(emptySet())
    }

    private var captureJob: Job? = null
    private var deauthLoopJob: Job? = null
    private var pollingJob: Job? = null
    private var timerJob: Job? = null
    private var promptCountdownJob: Job? = null

    private var currentOutputDir: String? = null
    private var currentBssid: String? = null
    private var currentIface: String? = null
    private var currentDeauthIface: String? = null
    private var currentChannel: String? = null
    private var currentEssid: String? = null
    private var currentKeepHostWifi: Boolean = true
    private var currentDeauthCount: Int = 5
    private var currentAutoDeauthClients: Boolean = true
    private var currentAutoDeauthBroadcast: Boolean = true
    private var currentExcludeSelf: Boolean = false
    private var currentDeviceMac: String? = null

    @Volatile
    private var savedCapFilePath: String? = null

    @Volatile
    private var captureStartedAtMs: Long = 0L

    @Volatile
    private var timeoutFired = false

    private var handshakeDetectedAtMs: Long? = null
    private var detectedType: String? = null

    @Volatile
    private var suppressAutoPromptUntil: Long = 0L

    private val knownInterfaces = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedHandshakeResult = false

    @Volatile
    private var monitorRestoreFailed = false

    @Volatile
    private var parserHandshakeDetected = false

    @Volatile
    private var hcxpcapngtoolHandshakeConfirmed = false

    @Volatile
    private var unverifiedHandshakeDetected = false

    companion object {
        const val CAPTURE_TIMEOUT_MS = 5 * 60 * 1000L
        const val AUTO_SAVE_DELAY_MS = 10_000L
        const val POLL_INTERVAL_MS = 3_000L
        const val MAX_POLL_ATTEMPTS = 100
        const val IDLE_WARNING_MS = 30_000L
    }

    init {
        loadInterfaces()
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearCrackResult() {
        _crackResult.value = null
    }

    fun clearVerifyResult() {
        _verifyResult.value = null
    }

    fun clearResult() {
        _result.value = null
    }

    fun clearCaptureResult() {
        _captureResult.value = null
    }

    fun clearStorageCrackResult() {
        _storageCrackResult.value = null
    }

    fun clearStorageVerifyResult() {
        _storageVerifyResult.value = null
    }

    fun clearSavePrompt() {
        _savePromptRequest.value = null
    }

    fun pollInterfaceStatus() {
        pollInterfaceJob?.cancel()
        pollInterfaceJob = viewModelScope.launch {
            try {
                val currentIfaces = iwWifiManager.getAvailableInterfaces()
                val currentNames = currentIfaces.map { it.name }.toSet()

                for (iface in currentIfaces) {
                    val mode = iwWifiManager.getInterfaceMode(iface.name)
                    knownInterfaces[iface.name] = mode
                }

                val disappeared = knownInterfaces.keys.filter { it !in currentNames }
                for (name in disappeared) {
                    knownInterfaces[name] = IwWifiManager.MODE_UNAVAILABLE
                }

                val statuses = knownInterfaces.entries
                    .sortedBy { it.key }
                    .map { InterfaceStatus(it.key, it.value) }
                _interfaceStatuses.postValue(statuses)
            } catch (e: Exception) {
                Log.e(tag, "Failed to poll interface status", e)
            }
        }
    }

    private var pollInterfaceJob: Job? = null

    fun loadInterfaces() {
        viewModelScope.launch {
            try {
                val ifaces = iwWifiManager.getAvailableInterfaces()
                _interfaces.postValue(ifaces)
            } catch (e: Exception) {
                Log.e(tag, "Failed to load interfaces", e)
                _interfaces.postValue(listOf(IwInterface("wlan0")))
            }
        }
    }

    fun checkInterfaceMode(iface: String) {
        viewModelScope.launch {
            try {
                val mode = iwWifiManager.getInterfaceMode(iface)
                _interfaceMode.postValue(mode)
            } catch (e: Exception) {
                Log.e(tag, "Failed to check interface mode", e)
                _interfaceMode.postValue(IwWifiManager.MODE_UNKNOWN)
            }
        }
    }

    fun setActiveInterfaceName(name: String?) {
        _activeInterfaceName.value = name
    }

    fun checkDeauthMode(iface: String) {
        viewModelScope.launch {
            try {
                val mode = iwWifiManager.getInterfaceMode(iface)
                _deauthMode.postValue(mode)
            } catch (e: Exception) {
                Log.e(tag, "Failed to check deauth mode", e)
                _deauthMode.postValue(IwWifiManager.MODE_UNKNOWN)
            }
        }
    }

    fun checkScanMode(iface: String) {
        viewModelScope.launch {
            try {
                val mode = iwWifiManager.getInterfaceMode(iface)
                _scanMode.postValue(mode)
            } catch (e: Exception) {
                Log.e(tag, "Failed to check scan mode", e)
                _scanMode.postValue(IwWifiManager.MODE_UNKNOWN)
            }
        }
    }

    fun setInterfaceMode(iface: String, mode: String, channel: String? = null) {
        _isSwitchingMode.postValue(true)
        viewModelScope.launch {
            try {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_switching_mode, iface, mode))
                val success = iwWifiManager.setInterfaceMode(iface, mode, channel)
                if (success) {
                    _interfaceMode.postValue(mode)
                    knownInterfaces[iface] = mode
                    pollInterfaceStatus()
                    _toastMessage.postValue(
                        getApplication<Application>().getString(
                            R.string.aird_interface_switched_toast,
                            mode
                        )
                    )
                    addConsoleLine(
                        getApplication<Application>().getString(
                            R.string.aird_interface_switched,
                            mode
                        )
                    )

                    delay(500)
                    loadInterfaces()

                    val newName = when (mode) {
                        IwWifiManager.MODE_MONITOR -> {
                            val found = iwWifiManager.findMonitorInterface(iface)
                            found ?: "${iface}mon"
                        }

                        IwWifiManager.MODE_MANAGED -> {
                            if (iface.endsWith("mon")) iface.removeSuffix("mon") else iface
                        }

                        else -> iface
                    }
                    _activeInterfaceName.postValue(newName)

                    if (mode == IwWifiManager.MODE_MONITOR) {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_monitor_interface, newName))
                    }
                } else {
                    val errorDetail = iwWifiManager.lastModeSwitchError
                    val errorMsg = if (errorDetail != null) {
                        getApplication<Application>().getString(
                            R.string.aird_failed_switch_with_detail,
                            iface,
                            mode,
                            errorDetail
                        )
                    } else {
                        getApplication<Application>().getString(
                            R.string.aird_failed_switch,
                            iface,
                            mode
                        )
                    }
                    addConsoleLine("[-] $errorMsg")
                    _toastMessage.postValue(errorMsg)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to set interface mode", e)
                addConsoleLine(
                    getApplication<Application>().getString(
                        R.string.aird_error_console,
                        e.message
                    )
                )
                _toastMessage.postValue(
                    getApplication<Application>().getString(
                        R.string.aird_error_switching_mode,
                        e.message
                    )
                )
            } finally {
                _isSwitchingMode.postValue(false)
            }
        }
    }

    suspend fun getCurrentCapFilePath(): String? {
        return savedCapFilePath ?: currentOutputDir?.let { captureRunner.findCapFile(it) }
    }

    suspend fun runHcxpcapngtool(capFile: String, isPmkid: Boolean): String {
        val extraArgs = if (isPmkid) "--pmkid-only" else ""
        return captureRunner.getHcxpcapngtoolOutput(capFile, extraArgs)
    }

    fun scanNetworks(iface: String) {
        if (_isScanning.value == true) {
            addConsoleLine(getApplication<Application>().getString(R.string.aird_scan_in_progress))
            return
        }
        _isScanning.value = true
        _statusText.value = getApplication<Application>().getString(
            com.lsd.wififrankenstein.R.string.airodump_scanning
        )

        viewModelScope.launch {
            var wasMonitorBeforeScan = false
            var originalMonIface: String? = null
            try {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_checking_iface_mode))
                var currentMode = iwWifiManager.getInterfaceMode(iface)
                _interfaceMode.postValue(currentMode)

                val monIface = iwWifiManager.findMonitorInterface(iface)
                if (currentMode == IwWifiManager.MODE_MONITOR || monIface != null) {
                    wasMonitorBeforeScan = true
                    originalMonIface = monIface ?: iface
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_temporarily_managed))
                    val ifaceToStop = monIface ?: iface
                    val switched =
                        iwWifiManager.setInterfaceMode(ifaceToStop, IwWifiManager.MODE_MANAGED)
                    if (switched) {
                        delay(1000)
                        currentMode = IwWifiManager.MODE_MANAGED
                        _interfaceMode.postValue(IwWifiManager.MODE_MANAGED)
                        _activeInterfaceName.postValue(iface)
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_switched_managed))
                    } else {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_failed_managed))
                        wasMonitorBeforeScan = false
                    }
                }

                val nets = iwWifiManager.scanWifiNetworks(iface)
                _networks.postValue(nets)
                _statusText.value = if (nets.isEmpty()) {
                    getApplication<Application>().getString(
                        com.lsd.wififrankenstein.R.string.airodump_no_networks
                    )
                } else {
                    getApplication<Application>().getString(
                        com.lsd.wififrankenstein.R.string.networks_found,
                        nets.size
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Scan failed", e)
                _statusText.value = getApplication<Application>().getString(R.string.aird_scan_failed, e.message)
            } finally {
                if (wasMonitorBeforeScan && originalMonIface != null) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_restoring_monitor_after_scan, originalMonIface))
                    val ifaceToRestore =
                        if (originalMonIface != iface && originalMonIface.endsWith("mon")) {
                            originalMonIface
                        } else {
                            iface
                        }
                    var restored = false
                    for (attempt in 1..3) {
                        restored = iwWifiManager.setInterfaceMode(
                            ifaceToRestore,
                            IwWifiManager.MODE_MONITOR,
                            null
                        )
                        if (restored) break
                        delay(1000)
                    }
                    if (restored) {
                        monitorRestoreFailed = false
                        delay(500)
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_restored_monitor, ifaceToRestore))
                    } else {
                        monitorRestoreFailed = true
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_failed_restore_monitor))
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_please_switch_monitor, ifaceToRestore))
                    }
                }
                _isScanning.value = false
            }
        }
    }

    fun selectNetwork(network: IwWifiNetwork) {
        _selectedNetwork.value = network
    }

    fun startCapture(
        iface: String,
        bssid: String,
        channel: String,
        captureFormat: CaptureFormat = CaptureFormat.DEFAULT,
        essid: String? = null,
        autoDeauthClients: Boolean = true,
        autoDeauthBroadcast: Boolean = true,
        keepHostWifi: Boolean = true,
        deauthIface: String = iface,
        deauthCount: Int = 5,
        excludeSelf: Boolean = false,
        deviceMac: String? = null
    ) {
        if (!_captureStarted.compareAndSet(false, true)) {
            addConsoleLine(getApplication<Application>().getString(R.string.aird_capture_in_progress))
            return
        }
        val net = _selectedNetwork.value
        val effectiveEssid = essid?.takeIf { it.isNotBlank() } ?: net?.ssid ?: ""

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedBssid = bssid.replace(":", "")
        val outputDir = "${HandshakeCaptureRunner.OUTPUT_BASE}/${sanitizedBssid}_$timestamp"

        currentOutputDir = outputDir
        currentBssid = bssid
        currentIface = iface
        currentDeauthIface = deauthIface
        currentChannel = channel
        currentEssid = effectiveEssid
        currentKeepHostWifi = keepHostWifi
        currentDeauthCount = deauthCount.coerceAtLeast(1)
        currentAutoDeauthClients = autoDeauthClients
        currentAutoDeauthBroadcast = autoDeauthBroadcast
        currentExcludeSelf = excludeSelf
        currentDeviceMac = deviceMac
        savedCapFilePath = null
        captureStartedAtMs = System.currentTimeMillis()
        handshakeDetectedAtMs = null
        detectedType = null
        suppressAutoPromptUntil = 0L

        _savePromptRequest.value = null
        synchronized(consoleLineBuffer) { consoleLineBuffer.clear() }
        _consoleLines.value = emptyList()
        _result.value = null
        _captureResult.value = null
        _verifyResult.value = null
        _crackResult.value = null
        _isCaptureRunning.value = true
        _idleWarning.value = false
        _captureProgress.value = 0
        _captureTimerText.value = formatTimerText(0L, CAPTURE_TIMEOUT_MS)

        captureLocationProvider.start()
        addConsoleLine(getApplication<Application>().getString(R.string.aird_start_capture_debug, iface, bssid, channel, autoDeauthClients, autoDeauthBroadcast, deauthIface, currentDeauthCount, excludeSelf))
        addConsoleLine(
            "[*] ${
                getApplication<Application>().getString(
                    com.lsd.wififrankenstein.R.string.airodump_started,
                    bssid,
                    channel
                )
            }"
        )
        addConsoleLine(getApplication<Application>().getString(R.string.aird_output, outputDir))

        val isWpa3 = net?.securityType?.contains("WPA3", ignoreCase = true) == true ||
                net?.authSuite?.uppercase() in setOf("SAE", "OWE")
        if (isWpa3) {
            addConsoleLine(getApplication<Application>().getString(R.string.aird_wpa3_warning))
            addConsoleLine(getApplication<Application>().getString(R.string.aird_wpa3_eapol_impossible))
            addConsoleLine(getApplication<Application>().getString(R.string.aird_wpa3_continue))
        }

        captureJob = viewModelScope.launch {
            try {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_checking_capture_iface, iface))
                val currentMode = iwWifiManager.getInterfaceMode(iface)
                Log.d(tag, "Capture iface $iface mode: $currentMode")
                _interfaceMode.postValue(currentMode)

                val monIface = iwWifiManager.findMonitorInterface(iface)
                Log.d(tag, "Monitor iface found for capture: $monIface")
                val monExists = monIface != null

                val forceSwitch =
                    (currentMode != IwWifiManager.MODE_MONITOR && !monExists) || monitorRestoreFailed
                if (forceSwitch) {
                    monitorRestoreFailed = false
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_switching_monitor_channel, iface, channel))
                    Log.d(tag, "setInterfaceMode($iface, monitor, $channel)")
                    val switched =
                        iwWifiManager.setInterfaceMode(iface, IwWifiManager.MODE_MONITOR, channel)
                    if (switched) {
                        delay(1000)
                        _interfaceMode.postValue(IwWifiManager.MODE_MONITOR)
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_iface_switched_monitor, iface))

                        val foundMon = iwWifiManager.findMonitorInterface(iface)
                        Log.d(tag, "Monitor iface after switch: $foundMon")
                        if (foundMon != null) {
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_capture_monitor_interface, foundMon))
                            _activeInterfaceName.postValue(foundMon)
                        }
                    } else {
                        val errDetail = iwWifiManager.lastModeSwitchError
                        val errMsg =
                            if (errDetail != null) getApplication<Application>().getString(
                                R.string.aird_failed_monitor_with_detail,
                                iface,
                                errDetail
                            ) else getApplication<Application>().getString(
                                R.string.aird_failed_monitor,
                                iface
                            )
                        addConsoleLine("[-] $errMsg")
                        Log.e(tag, errMsg)
                        _state.postValue(CaptureState.COMPLETE)
                        _isCaptureRunning.postValue(false)
                        return@launch
                    }
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_already_monitor, iface))
                    if (monIface != null) {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_capture_monitor_interface, monIface))
                        _activeInterfaceName.postValue(monIface)
                    }
                }

                val actualDeauthIface = deauthIface.takeIf { it.isNotEmpty() } ?: iface
                val deauthNeedsSetup =
                    (currentAutoDeauthClients || currentAutoDeauthBroadcast) && actualDeauthIface != iface

                if (deauthNeedsSetup) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_checking_deauth_iface, actualDeauthIface))
                    val deauthMode = iwWifiManager.getInterfaceMode(actualDeauthIface)
                    Log.d(tag, "Deauth iface $actualDeauthIface mode: $deauthMode")
                    _deauthMode.postValue(deauthMode)

                    val deauthMonIface = iwWifiManager.findMonitorInterface(actualDeauthIface)
                    Log.d(tag, "Monitor iface found for deauth: $deauthMonIface")
                    val deauthMonExists = deauthMonIface != null

                    if (deauthMode != IwWifiManager.MODE_MONITOR && !deauthMonExists) {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_switching_deauth_monitor, actualDeauthIface))
                        Log.d(tag, "setInterfaceMode($actualDeauthIface, monitor, $channel)")
                        val deauthSwitched = iwWifiManager.setInterfaceMode(
                            actualDeauthIface,
                            IwWifiManager.MODE_MONITOR,
                            channel
                        )
                        if (deauthSwitched) {
                            delay(1000)
                            _deauthMode.postValue(IwWifiManager.MODE_MONITOR)
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_switched_monitor, actualDeauthIface))

                            val foundDeauthMon =
                                iwWifiManager.findMonitorInterface(actualDeauthIface)
                            if (foundDeauthMon != null) {
                                addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_monitor_interface, foundDeauthMon))
                            }
                        } else {
                            val errDetail = iwWifiManager.lastModeSwitchError
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_failed_deauth_monitor, actualDeauthIface, errDetail ?: "unknown error"))
                            Log.e(tag, "Deauth monitor switch failed: $errDetail")
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_may_fail))
                        }
                    } else {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_already_monitor, actualDeauthIface))
                        if (deauthMonIface != null) {
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_monitor_interface, deauthMonIface))
                        }
                    }
                } else if (currentAutoDeauthClients || currentAutoDeauthBroadcast) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_same_interface, iface))
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_auto_deauth_disabled))
                }

                if (!currentKeepHostWifi) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_disabling_host_wifi))
                    Log.d(tag, "Calling disableHostWifi() (keepHostWifi=false)")
                    captureRunner.disableHostWifi()
                    delay(500)
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_keep_host_wifi))
                    Log.d(tag, "Skipping disableHostWifi() (keepHostWifi=true)")
                }

                val captureIface = iwWifiManager.findMonitorInterface(iface) ?: iface
                addConsoleLine(getApplication<Application>().getString(R.string.aird_starting_airodump, captureIface, bssid, channel))
                Log.d(
                    tag,
                    "Starting capture: iface=$captureIface bssid=$bssid ch=$channel dir=$outputDir"
                )
                _state.postValue(CaptureState.CAPTURING)
                _statusText.postValue(
                    getApplication<Application>().getString(
                        com.lsd.wififrankenstein.R.string.airodump_capturing
                    )
                )

                captureRunner.startCaptureAsync(
                    iface = iface,
                    bssid = bssid,
                    channel = channel,
                    outputDir = outputDir,
                    outputFormat = captureFormat,
                    onProgress = { line ->
                        addConsoleLine(line)
                    },
                    onStats = { stats -> updateCaptureStats(stats) },
                    onEvent = { event ->
                        emitCaptureEvent(event)
                        if (event == "HANDSHAKE") {
                            parserHandshakeDetected = true
                            Log.d(
                                tag,
                                "[*] AirodumpParser detected handshake — will trigger cowpatty verify"
                            )
                        }
                    }
                )

                startCaptureTimer()

                if (currentAutoDeauthClients || currentAutoDeauthBroadcast) {
                    val deauthLoopIface = actualDeauthIface
                    val deauthCount = currentDeauthCount
                    val modeParts = mutableListOf<String>()
                    if (currentAutoDeauthClients) modeParts.add("clients")
                    if (currentAutoDeauthBroadcast) modeParts.add("broadcast")
                    if (currentExcludeSelf) modeParts.add("excludeSelf=${currentDeviceMac ?: "?"}")
                    addConsoleLine(
                        getApplication<Application>().getString(
                            R.string.aird_auto_deauth_enabled,
                            deauthLoopIface,
                            deauthCount,
                            modeParts.joinToString(" + ")
                        )
                    )
                    deauthLoopJob = launch(Dispatchers.IO) {
                        try {
                            delay(10_000)
                            var deauthAttempt = 0
                            while (isActive && captureRunner.isAirodumpRunning() && handshakeDetectedAtMs == null) {
                                val clients = captureStats.value?.clients?.toList() ?: emptyList()

                                if (currentAutoDeauthClients) {
                                    Log.d(
                                        tag,
                                        "deauth-loop: found ${clients.size} clients, sending $deauthCount packets each"
                                    )
                                    for (client in clients) {
                                        if (!isActive || !captureRunner.isAirodumpRunning()) break
                                        if (currentExcludeSelf && currentDeviceMac != null &&
                                            client.mac.equals(currentDeviceMac, ignoreCase = true)
                                        ) {
                                            Log.d(
                                                tag,
                                                "deauth-loop: skipping self device ${client.mac}"
                                            )
                                            continue
                                        }
                                        Log.d(tag, "deauth-loop: sending to client ${client.mac}")
                                        captureRunner.sendDeauth(
                                            iface = deauthLoopIface,
                                            bssid = bssid,
                                            clientMac = client.mac,
                                            count = deauthCount,
                                            channel = currentChannel
                                        ) { line ->
                                            Log.d(tag, "[deauth] $line")
                                        }
                                    }
                                }

                                if (currentAutoDeauthBroadcast && isActive && captureRunner.isAirodumpRunning()) {
                                    if (clients.isNotEmpty() || deauthAttempt == 0) {
                                        Log.d(tag, "deauth-loop: sending broadcast")
                                        captureRunner.sendDeauth(
                                            iface = deauthLoopIface,
                                            bssid = bssid,
                                            clientMac = null,
                                            count = deauthCount,
                                            channel = currentChannel
                                        ) { line ->
                                            Log.d(tag, "[deauth] $line")
                                        }
                                    }
                                }
                                deauthAttempt++
                                delay(20_000)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(tag, "deauth-loop error: ${e.message}")
                        }
                    }
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_auto_deauth_disabled))
                }

                pollingJob = launch(Dispatchers.IO) {
                    var cappedFile: String? = null
                    var attempts = 0
                    val startMs = System.currentTimeMillis()
                    var lastDataMs = startMs
                    var pmkidPmkidOnlyLogged = false
                    var pmkidDetected = false
                    var lastVerifyDataFrameCount = 0L
                    var noFileWarningFired = false

                    while (isActive && attempts < MAX_POLL_ATTEMPTS) {
                        if (!captureRunner.isAirodumpRunning()) {
                            Log.d(tag, "polling: airodump process died")
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_process_died))
                            break
                        }

                        val elapsed = System.currentTimeMillis() - startMs
                        if (elapsed >= CAPTURE_TIMEOUT_MS && !timeoutFired) {
                            timeoutFired = true
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_timeout))
                            val finalFile = cappedFile ?: captureRunner.findCapFile(outputDir)
                            val hasData = finalFile?.let { f ->
                                try {
                                    captureRunner.verifyHandshakeWithHcxpcapngtool(f) { line ->
                                        addConsoleLine(line)
                                    }
                                } catch (e: Exception) {
                                    false
                                }
                            } ?: false
                            addConsoleLine(getApplication<Application>().getString(R.string.aird_timeout_data, hasData))
                            stopCapture()
                            break
                        }

                        cappedFile = captureRunner.findCapFile(outputDir)
                        if (cappedFile == null) {
                            val elapsed = System.currentTimeMillis() - startMs
                            if (elapsed > 30_000 && !noFileWarningFired) {
                                noFileWarningFired = true
                                addConsoleLine(getApplication<Application>().getString(R.string.aird_no_file_30s))
                                addConsoleLine(getApplication<Application>().getString(R.string.aird_check_airodump_installed))
                            }
                        }
                        if (cappedFile != null) {
                            val stats = captureStats.value
                            if (stats != null && stats.hasData) {
                                lastDataMs = System.currentTimeMillis()
                                _idleWarning.postValue(false)
                            } else if (System.currentTimeMillis() - lastDataMs > IDLE_WARNING_MS) {
                                _idleWarning.postValue(true)
                            }

                            if (stats?.isWpa3 == true && !noFileWarningFired) {
                                addConsoleLine(getApplication<Application>().getString(R.string.aird_sae_owe_detected))
                                addConsoleLine(getApplication<Application>().getString(R.string.aird_eapol_no_wpa3))
                                noFileWarningFired = true
                            }

                            val currentDataFrames =
                                stats?.let { it.dataFrames.toLongOrNull() ?: 0L } ?: 0L
                            val shouldRecheck =
                                currentDataFrames != lastVerifyDataFrameCount || parserHandshakeDetected

                            if (shouldRecheck || handshakeDetectedAtMs != null) {
                                lastVerifyDataFrameCount = currentDataFrames
                            }

                            val hasHandshake = if (shouldRecheck) {
                                parserHandshakeDetected = false
                                var hcxpcapngtoolOk = false
                                try {
                                    hcxpcapngtoolOk =
                                        captureRunner.verifyHandshakeWithHcxpcapngtool(cappedFile) { line ->
                                            addConsoleLine(line)
                                        }
                                    if (hcxpcapngtoolOk) {
                                        hcxpcapngtoolHandshakeConfirmed = true
                                    }
                                } catch (e: Exception) {
                                    addConsoleLine(getApplication<Application>().getString(R.string.aird_hcx_verify_error, e.message))
                                    Log.e(tag, "verifyHandshakeWithHcxpcapngtool failed", e)
                                }
                                if (!hcxpcapngtoolOk && handshakeDetectedAtMs != null) {
                                    unverifiedHandshakeDetected = true
                                }
                                hcxpcapngtoolOk
                            } else {
                                cachedHandshakeResult
                            }
                            if (hasHandshake) {
                                cachedHandshakeResult = true
                                hcxpcapngtoolHandshakeConfirmed = true
                                unverifiedHandshakeDetected = false
                            } else if (handshakeDetectedAtMs != null && !hcxpcapngtoolHandshakeConfirmed) {
                                unverifiedHandshakeDetected = true
                            }

                            if (hcxpcapngtoolHandshakeConfirmed || unverifiedHandshakeDetected || parserHandshakeDetected) {
                                val current = _captureStats.value
                                if (current != null) {
                                    val newHandshake = when {
                                        hcxpcapngtoolHandshakeConfirmed -> DetectionState.CONFIRMED
                                        handshakeDetectedAtMs != null || parserHandshakeDetected -> DetectionState.AIRODUMP
                                        else -> DetectionState.NONE
                                    }
                                    val newPmkid = when {
                                        pmkidDetected -> DetectionState.CONFIRMED
                                        current.pmkidFound -> DetectionState.AIRODUMP
                                        else -> DetectionState.NONE
                                    }
                                    val updated = current.copy(
                                        handshakeState = DetectionState.values()[
                                            maxOf(
                                                current.handshakeState.ordinal,
                                                newHandshake.ordinal
                                            )
                                        ],
                                        pmkidState = DetectionState.values()[
                                            maxOf(current.pmkidState.ordinal, newPmkid.ordinal)
                                        ]
                                    )
                                    _captureStats.postValue(updated)
                                }
                            }

                            if (hasHandshake) {
                                val now = System.currentTimeMillis()
                                if (handshakeDetectedAtMs == null) {
                                    handshakeDetectedAtMs = now
                                    detectedType = "HANDSHAKE"
                                    addConsoleLine(getApplication<Application>().getString(R.string.aird_hs_confirmed_autosave))
                                    _savePromptRequest.postValue(
                                        SavePromptRequest(
                                            type = "HANDSHAKE",
                                            capFilePath = cappedFile,
                                            bssid = bssid,
                                            channel = channel,
                                            essid = effectiveEssid,
                                            secondsLeft = 10
                                        )
                                    )
                                } else if (now - handshakeDetectedAtMs!! >= AUTO_SAVE_DELAY_MS) {
                                    addConsoleLine(getApplication<Application>().getString(R.string.aird_auto_saving_now))
                                    autoSaveAndFinish(
                                        cappedFile,
                                        effectiveEssid,
                                        bssid,
                                        true,
                                        pmkidDetected
                                    )
                                    break
                                }
                            } else if (shouldRecheck) {
                                val hasPmkid = try {
                                    captureRunner.hasPmkidViaAircrack(cappedFile) {  }
                                } catch (e: Exception) {
                                    addConsoleLine(getApplication<Application>().getString(R.string.aird_pmkid_check_error, e.message))
                                    Log.e(tag, "hasPmkidViaAircrack failed", e)
                                    pmkidDetected
                                }
                                if (hasPmkid) {
                                    pmkidDetected = true
                                    if (!pmkidPmkidOnlyLogged) {
                                        addConsoleLine(getApplication<Application>().getString(R.string.aird_pmkid_confirmed))
                                        pmkidPmkidOnlyLogged = true
                                    }
                                }
                            }
                        }

                        attempts++
                        delay(POLL_INTERVAL_MS)
                    }

                    if (cappedFile == null) {
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_no_file_produced))
                        autoSaveAndFinish(null, effectiveEssid, bssid)
                    }
                }

                pollingJob?.join()

            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(tag, "Capture job cancelled")
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_error_console, e.message))
                Log.e(tag, "Capture failed", e)
            } finally {
                cleanupCapture()
            }
        }
    }

    private fun startCaptureTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch(Dispatchers.Default) {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startMs
                _captureTimerText.postValue(formatTimerText(elapsed, CAPTURE_TIMEOUT_MS))
                _captureProgress.postValue(
                    ((elapsed.toFloat() / CAPTURE_TIMEOUT_MS) * 100).toInt().coerceIn(0, 100)
                )
                if (elapsed >= CAPTURE_TIMEOUT_MS && !timeoutFired) {
                    timeoutFired = true
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_timer_stop))
                    stopCapture()
                }
                delay(1_000)
            }
        }
    }

    private fun formatTimerText(elapsedMs: Long, totalMs: Long): String {
        val elapsedSec = (elapsedMs / 1000).toInt().coerceAtLeast(0)
        val totalSec = (totalMs / 1000).toInt()
        val elapsedMin = elapsedSec / 60
        val elapsedSecRem = elapsedSec % 60
        val totalMin = totalSec / 60
        val totalSecRem = totalSec % 60
        return String.format(
            Locale.US,
            "%d:%02d / %d:%02d",
            elapsedMin,
            elapsedSecRem,
            totalMin,
            totalSecRem
        )
    }

    private suspend fun autoSaveAndFinish(
        capFile: String?,
        essid: String,
        bssid: String,
        hasHandshake: Boolean = false,
        hasPmkid: Boolean = false,
        skipStopCapture: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (!skipStopCapture && _isCaptureRunning.value == true && capFile != null) {
            addConsoleLine(getApplication<Application>().getString(R.string.aird_stopping_before_save))
            captureRunner.stopCapture()
            delay(300)
        }

        var verifyValid = hasHandshake
        var verifyPmkid = hasPmkid
        val finalPath: String?
        if (capFile != null) {
            if (!hasHandshake && !hasPmkid) {
                verifyValid = try {
                    captureRunner.verifyHandshakeWithHcxpcapngtool(capFile) { line ->
                        if (!line.contains("Information:") && !line.contains("https://") && line.isNotBlank())
                            addConsoleLine("[hcxpcapngtool] $line")
                    }
                } catch (e: Exception) {
                    false
                }
            }

            val shouldSave = verifyValid || verifyPmkid

            if (shouldSave) {
                val saved = storageManager.moveToStorage(capFile, essid, bssid)
                finalPath = saved ?: capFile
                if (saved != null) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_saved_storage, saved))
                    if (currentOutputDir != null) {
                        chrootManager.executeInChroot("rm -rf '${currentOutputDir}' 2>/dev/null; true")
                        addConsoleLine(getApplication<Application>().getString(R.string.aird_temp_dir_cleaned))
                    }
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_save_failed_remains, capFile))
                }
            } else {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_no_valid_data))
                finalPath = null
            }
        } else {
            finalPath = null
        }

        savedCapFilePath = finalPath

        if (finalPath != null) {
            val fileName = File(finalPath).name
            val savedFilePath = finalPath
            try {
                val statResult =
                    chrootManager.executeInChroot("stat -c '%s' '$savedFilePath' 2>/dev/null")
                val fileSize = statResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

                val raw = captureRunner.getHcxpcapngtoolOutput(finalPath)
                val hashLines = raw.lines()
                val allHashes =
                    hashLines.mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                val hash22000Lines =
                    allHashes.map { it.to22000Line() }.distinct()
                val hash22000 = hash22000Lines.joinToString("\n").takeIf { it.isNotBlank() }
                val hashPmkid = allHashes.firstOrNull { it.type == HandshakeType.PMKID }
                    ?.pmkidOrMic?.takeIf { it.length == 32 }
                val eapolCount =
                    allHashes.count { it.type == HandshakeType.EAPOL }
                val pmkidCount =
                    allHashes.count { it.type == HandshakeType.PMKID }
                val handshakeCount = allHashes.size
                val hashDedupMd5 = allHashes.firstOrNull()?.dedupKey()
                val originalFormat = File(finalPath).extension.lowercase()
                val loc = captureLocationProvider.getLastKnownLocation()
                val lat = loc?.latitude
                val lon = loc?.longitude
                val savedIsValid = hasHandshake || verifyValid
                storageManager.saveHandshakeMetadata(
                    HandshakeItem(
                        filePath = savedFilePath,
                        fileName = fileName,
                        essid = essid,
                        bssid = bssid,
                        fileSize = fileSize,
                        lastModified = System.currentTimeMillis(),
                        hash22000 = hash22000,
                        hashPmkid = hashPmkid,
                        isValid = if (savedIsValid) true else null,
                        latitude = lat,
                        longitude = lon,
                        handshakeCount = handshakeCount,
                        eapolCount = eapolCount,
                        pmkidCount = pmkidCount,
                        hashDedupMd5 = hashDedupMd5,
                        originalFormat = originalFormat
                    )
                )
                if (hash22000 != null) addConsoleLine(getApplication<Application>().getString(R.string.aird_hash_22000_extracted, handshakeCount))
                if (hashPmkid != null) addConsoleLine(getApplication<Application>().getString(R.string.aird_pmkid_extracted))
                if (lat != null && lon != null) addConsoleLine(getApplication<Application>().getString(R.string.aird_location, lat, lon))
            } catch (e: Exception) {
                Log.e(tag, "Failed to extract hash/location after save", e)
            }
        }

        captureLocationProvider.stop()

        val kind = when {
            finalPath != null && (hasHandshake || verifyValid) && (hasPmkid || verifyPmkid) -> CaptureResultKind.BOTH
            finalPath != null && (hasHandshake || verifyValid) -> CaptureResultKind.HANDSHAKE
            finalPath != null && (hasPmkid || verifyPmkid) -> CaptureResultKind.PMKID
            else -> CaptureResultKind.NONE
        }

        _captureResult.postValue(
            CaptureResult(
                kind = kind,
                capFilePath = finalPath,
                bssid = bssid,
                essid = essid
            )
        )

        _result.postValue(
            HandshakeResult(
                success = finalPath != null,
                capFilePath = finalPath,
                bssid = bssid,
                essid = essid,
                rawOutput = ""
            )
        )
        loadStorage()
    }

    fun stopCapture() {
        addConsoleLine("[!] ${getApplication<Application>().getString(com.lsd.wififrankenstein.R.string.airodump_stopped)}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val capFile = currentOutputDir?.let { captureRunner.findCapFile(it) }
                val essid = currentEssid ?: ""
                val bssid = currentBssid ?: ""

                if (capFile != null && unverifiedHandshakeDetected &&
                    !hcxpcapngtoolHandshakeConfirmed && handshakeDetectedAtMs != null
                ) {
                    val channel = currentChannel ?: ""
                    _saveUnverifiedRequest.postValue(
                        SavePromptRequest(
                            type = if (detectedType == "PMKID") "PMKID" else "HANDSHAKE",
                            capFilePath = capFile,
                            bssid = bssid,
                            channel = channel,
                            essid = essid,
                            secondsLeft = 0
                        )
                    )
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_hs_inconclusive))
                } else if (capFile != null && parserHandshakeDetected) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_parser_detected))
                    autoSaveAndFinish(capFile, essid, bssid)
                } else if (capFile != null) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_no_hs_final_verify))
                    val hcxValid = try {
                        captureRunner.verifyHandshakeWithHcxpcapngtool(capFile) {}
                    } catch (_: Exception) {
                        false
                    }
                    autoSaveAndFinish(
                        capFile, essid, bssid,
                        hasHandshake = hcxValid
                    )
                } else {
                    autoSaveAndFinish(null, essid, bssid)
                }
            } catch (e: Exception) {
                Log.e(tag, "stopCapture: save failed", e)
            } finally {
                cleanupCapture()
            }
        }
    }

    fun saveUnverified() {
        val req = _saveUnverifiedRequest.value ?: return
        _saveUnverifiedRequest.value = null
        val bssid = req.bssid
        val essid = req.essid
        val capFile = req.capFilePath
        viewModelScope.launch(Dispatchers.IO) {
            autoSaveAndFinish(
                capFile, essid, bssid, hasHandshake = detectedType == "HANDSHAKE",
                hasPmkid = detectedType == "PMKID"
            )
        }
    }

    fun discardUnverified() {
        _saveUnverifiedRequest.value = null
        addConsoleLine(getApplication<Application>().getString(R.string.aird_unverified_discarded))
        cleanupCapture()
    }

    fun saveNow() {
        if (timeoutFired) {
            addConsoleLine(getApplication<Application>().getString(R.string.aird_timeout_fired_save))
            return
        }
        promptCountdownJob?.cancel()
        val req = _savePromptRequest.value ?: return
        addConsoleLine(getApplication<Application>().getString(R.string.aird_user_save_now))
        val capFile = req.capFilePath
        val bssid = req.bssid
        val essid = req.essid
        val isHandshake = req.type == "HANDSHAKE"
        val isPmkid = req.type == "PMKID"
        _savePromptRequest.value = null
        viewModelScope.launch(Dispatchers.IO) {
            autoSaveAndFinish(capFile, essid, bssid, isHandshake, isPmkid)
        }
    }

    fun cancelAutoSave() {
        promptCountdownJob?.cancel()
        _savePromptRequest.value = null
        handshakeDetectedAtMs = null
        suppressAutoPromptUntil = System.currentTimeMillis() + 60_000
        addConsoleLine(getApplication<Application>().getString(R.string.aird_autosave_cancelled))
    }

    private fun startPromptCountdown(initialSeconds: Int) {
        promptCountdownJob?.cancel()
        promptCountdownJob = viewModelScope.launch {
            var remaining = initialSeconds
            while (isActive && remaining > 0) {
                val current = _savePromptRequest.value
                if (current == null) break
                _savePromptRequest.postValue(current.copy(secondsLeft = remaining))
                delay(1_000)
                remaining--
            }
        }
    }

    private fun cleanupCapture() {
        if (!_cleaningUp.compareAndSet(false, true)) return
        doCleanupCapture()
    }

    private fun doCleanupCapture() {
        deauthLoopJob?.cancel()
        deauthLoopJob = null
        pollingJob?.cancel()
        pollingJob = null
        captureJob?.cancel()
        captureJob = null
        promptCountdownJob?.cancel()
        promptCountdownJob = null
        timerJob?.cancel()
        timerJob = null
        _captureStarted.set(false)
        _savePromptRequest.postValue(null)
        handshakeDetectedAtMs = null
        captureStartedAtMs = 0L

        viewModelScope.launch(Dispatchers.IO) {
            Log.d(tag, "=== CLEANUP START ===")
            try {
                captureRunner.stopCapture()

                var waited = 0L
                while (captureRunner.isAirodumpRunning() && waited < 5000) {
                    delay(200)
                    waited += 200
                }
                Log.d(tag, "Airodump session stop wait elapsed: ${waited}ms")
                delay(500)

                val captureIface = currentIface
                if (captureIface != null) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_restoring_capture_iface, captureIface))
                    Log.d(tag, "Restoring capture iface: $captureIface")
                    val monIface = iwWifiManager.findMonitorInterface(captureIface)
                    val ifaceToStop = monIface ?: captureIface
                    Log.d(tag, "Stopping monitor on capture: $ifaceToStop")
                    val captureRestored = captureRunner.disableMonitor(ifaceToStop)
                    Log.d(tag, "Capture iface restore: success=$captureRestored")
                    delay(1000)

                    _activeInterfaceName.postValue(captureIface)
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_capture_iface_restored, captureIface))
                }

                val deauthIface = currentDeauthIface
                if (deauthIface != null && deauthIface != captureIface) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_restoring_deauth_iface, deauthIface))
                    Log.d(tag, "Restoring deauth iface: $deauthIface")
                    val deauthMonIface = iwWifiManager.findMonitorInterface(deauthIface)
                    val deauthIfaceToStop = deauthMonIface ?: deauthIface
                    Log.d(tag, "Stopping monitor on deauth: $deauthIfaceToStop")
                    val deauthRestored = captureRunner.disableMonitor(deauthIfaceToStop)
                    Log.d(tag, "Deauth iface restore: success=$deauthRestored")
                    delay(1000)
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_iface_restored, deauthIface))
                }

                if (!currentKeepHostWifi) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_re_enabling_wifi))
                    Log.d(tag, "Calling enableHostWifi() (keepHostWifi=false)")
                    captureRunner.enableHostWifi()
                } else {
                    Log.d(tag, "Skipping enableHostWifi() (keepHostWifi=true)")
                }

                captureRunner.forceCleanup()
                Log.d(tag, "=== CLEANUP DONE ===")
            } catch (e: Exception) {
                Log.e(tag, "Cleanup failed", e)
            } finally {
                _cleaningUp.set(false)
                _isCaptureRunning.postValue(false)
                _state.postValue(CaptureState.COMPLETE)
            }
        }
    }

    private suspend fun resolveChrootPath(capFilePath: String?): String? {
        if (capFilePath == null) return null
        if (capFilePath.startsWith("/sdcard/")) return capFilePath
        val fileName = File(capFilePath).name
        val chrootPath = "${HandshakeStorageManager.STORAGE_DIR}/$fileName"

        val check = chrootManager.executeInChroot("test -e '$chrootPath'")
        if (check.isSuccess) return chrootPath

        Log.w(tag, "resolveChrootPath: file missing in chroot, attempting ensure: $chrootPath")
        return storageManager.ensureChrootCopy(capFilePath)
    }

    fun verifyHandshake(capFilePath: String?) {
        _verifyResult.value = null
        _hcxpcapngtoolResult.value = null
        addConsoleLine(getApplication<Application>().getString(R.string.aird_verifying))

        viewModelScope.launch(Dispatchers.IO) {
            val chrootPath = resolveChrootPath(capFilePath)
            if (chrootPath == null) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_could_not_resolve, capFilePath))
                return@launch
            }
            try {
                val rawOutput = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                val result = parseHcxpcapngtoolOutput(rawOutput)
                _hcxpcapngtoolResult.postValue(result)
                _verifyResult.postValue(result.valid)
                if (result.valid) {
                    addConsoleLine("[+] ${getApplication<Application>().getString(com.lsd.wififrankenstein.R.string.airodump_valid)}")
                } else {
                    addConsoleLine("[-] ${getApplication<Application>().getString(com.lsd.wififrankenstein.R.string.airodump_invalid)}")
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_verify_error, e.message))
            }
        }
    }

    fun crackWithWordlist(capFilePath: String?, wordlistPath: String) {
        _crackResult.value = null
        addConsoleLine("[*] ${getApplication<Application>().getString(com.lsd.wififrankenstein.R.string.airodump_crack_started)}")

        viewModelScope.launch(Dispatchers.IO) {
            val chrootPath = resolveChrootPath(capFilePath)
            if (chrootPath == null) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_could_not_resolve, capFilePath))
                return@launch
            }
            try {
                val password = captureRunner.crackWithWordlist(
                    capFilePath = chrootPath,
                    wordlistPath = wordlistPath
                ) { line ->
                    addConsoleLine(line)
                }
                _crackResult.postValue(password)
                if (password != null) {
                    addConsoleLine(
                        "[+] ${
                            getApplication<Application>().getString(
                                com.lsd.wififrankenstein.R.string.airodump_key_found,
                                password
                            )
                        }"
                    )
                } else {
                    addConsoleLine("[-] ${getApplication<Application>().getString(com.lsd.wififrankenstein.R.string.airodump_key_not_found)}")
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_crack_error, e.message))
            }
        }
    }

    fun exportToHashcat(capFilePath: String?) {
        addConsoleLine(getApplication<Application>().getString(R.string.aird_exporting_hashcat))

        viewModelScope.launch(Dispatchers.IO) {
            val chrootPath = resolveChrootPath(capFilePath)
            if (chrootPath == null) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_could_not_resolve, capFilePath))
                return@launch
            }
            val outputPath = chrootPath.removeSuffix(".cap").removeSuffix(".pcap")
            try {
                val success = captureRunner.exportToHccapx(chrootPath, outputPath) { line ->
                    addConsoleLine(line)
                }
                if (success) {
                    addConsoleLine(
                        "[+] ${
                            getApplication<Application>().getString(
                                com.lsd.wififrankenstein.R.string.airodump_exported_to,
                                "${outputPath}.hccapx"
                            )
                        }"
                    )
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_export_failed))
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_export_error, e.message))
            }
        }
    }

    fun exportTo22000(capFilePath: String?) {
        addConsoleLine(getApplication<Application>().getString(R.string.aird_exporting_22000))
        viewModelScope.launch(Dispatchers.IO) {
            val chrootPath = resolveChrootPath(capFilePath)
            if (chrootPath == null) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_could_not_resolve, capFilePath))
                return@launch
            }
            val outputPath = chrootPath.removeSuffix(".cap").removeSuffix(".pcap") + ".22000"
            try {
                val cmd = "hcxpcapngtool -o \"$outputPath\" \"$chrootPath\" 2>&1"
                val res = chrootManager.executeInChroot(cmd)
                val output = (res.out + res.err).joinToString("\n")
                addConsoleLine(output)
                val success = chrootManager.executeInChroot("test -s '$outputPath'").isSuccess
                if (success) {
                    addConsoleLine(
                        "[+] ${
                            getApplication<Application>().getString(
                                com.lsd.wififrankenstein.R.string.airodump_exported_to,
                                outputPath
                            )
                        }"
                    )
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_export_failed_no_output))
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_export_error, e.message))
            }
        }
    }

    fun exportPmkidOnly(capFilePath: String?) {
        addConsoleLine(getApplication<Application>().getString(R.string.aird_extracting_pmkid))
        viewModelScope.launch(Dispatchers.IO) {
            val chrootPath = resolveChrootPath(capFilePath)
            if (chrootPath == null) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_could_not_resolve, capFilePath))
                return@launch
            }
            val outputPath = chrootPath.removeSuffix(".cap").removeSuffix(".pcap") + "_pmkid.txt"
            try {
                val cmd = "hcxpcapngtool --pmkid-only -o \"$outputPath\" \"$chrootPath\" 2>&1"
                val res = chrootManager.executeInChroot(cmd)
                val output = (res.out + res.err).joinToString("\n")
                addConsoleLine(output)
                val success = chrootManager.executeInChroot("test -s '$outputPath'").isSuccess
                if (success) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_pmkid_saved_to, outputPath))
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_no_pmkid))
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_pmkid_extract_error, e.message))
            }
        }
    }

    fun sendDeauth(
        iface: String,
        bssid: String,
        clientMac: String?,
        count: Int,
        channel: String? = null
    ) {
        val ch = channel ?: currentChannel
        addConsoleLine(getApplication<Application>().getString(R.string.aird_sending_deauth, count, bssid, ch ?: "?"))

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = captureRunner.sendDeauth(
                    iface = iface,
                    bssid = bssid,
                    clientMac = clientMac,
                    count = count,
                    channel = ch,
                    onProgress = { line ->
                        addConsoleLine("[deauth] $line")
                    }
                )
                if (success) {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_sent))
                } else {
                    addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_no_acks))
                }
            } catch (e: Exception) {
                addConsoleLine(getApplication<Application>().getString(R.string.aird_deauth_error, e.message))
            }
        }
    }

    private val MAX_CONSOLE_LINES = 500

    private val ansiControlRegex = Regex("""^\u001B\[[\d;]*[A-Za-z]""")

    private fun isUserVisibleLine(line: String): Boolean {
        if (line.isBlank()) return false
        val cleaned = line.replace(ansiControlRegex, "")
        if (cleaned.isBlank()) return false
        val alwaysShowPrefixes =
            listOf("[!] ", "[+] ", "[-] ", "[*] ", "[error] ", "[stderr] ", "[hcxpcapngtool] ")
        if (alwaysShowPrefixes.any { cleaned.startsWith(it) }) return true
        val debugPrefixes = listOf(
            "[deauth-loop]", "[chroot]", "[chroot-err]", "[polling]",
            "[verify]", "[pmkid-check]", "[storage-verify]", "[storage-crack]",
            "[storage-export]"
        )
        return debugPrefixes.none { cleaned.startsWith(it) }
    }

    fun addConsoleLine(line: String) {
        Log.d(tag, line)

        if (!isUserVisibleLine(line)) return

        val snapshot = synchronized(consoleLineBuffer) {
            consoleLineBuffer.add(line)
            while (consoleLineBuffer.size > MAX_CONSOLE_LINES) {
                consoleLineBuffer.removeAt(0)
            }
            consoleLineBuffer.toList()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            _consoleLines.value = snapshot
        } else {
            _consoleLines.postValue(snapshot)
        }
    }

    fun loadStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = storageManager.listHandshakes()
                val enriched = items.map { item ->
                    val cracked = storageManager.getCrackedPassword(item.bssid)
                    item.copy(crackedPassword = cracked)
                }
                _storageItems.postValue(enriched)
            } catch (e: Exception) {
                Log.e(tag, "Failed to load storage", e)
                _storageItems.postValue(emptyList())
            }
        }
    }

    fun deleteHandshake(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteHandshake(item.filePath)
            loadStorage()
        }
    }

    fun verifyStoredHandshake(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                    Log.e(
                        tag,
                        "verifyStoredHandshake: cannot resolve chroot path for ${item.filePath}"
                    )
                    return@launch
                }
                val valid = captureRunner.verifyHandshake(chrootPath) { line ->
                    Log.d(tag, "[storage-verify] $line")
                }
                _storageVerifyResult.postValue(item.filePath to valid)
                updateStorageItemStatus(item.filePath, isValid = valid)
            } catch (e: Exception) {
                Log.e(tag, "Verify failed for stored handshake", e)
            }
        }
    }

    fun crackStoredHandshake(item: HandshakeItem, wordlistPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(tag, "Cracking ${item.displayName}...")
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                    Log.e(
                        tag,
                        "crackStoredHandshake: cannot resolve chroot path for ${item.filePath}"
                    )
                    return@launch
                }
                val password = captureRunner.crackWithWordlist(chrootPath, wordlistPath) { line ->
                    Log.d(tag, "[storage-crack] $line")
                }
                if (password != null) {
                    item.bssid?.let { storageManager.saveCrackedPassword(it, password) }
                    _storageCrackResult.postValue(item.filePath to password)
                    Log.d(tag, "KEY FOUND: $password")
                    updateStorageItemCracked(item.filePath, password)
                }
            } catch (e: Exception) {
                Log.e(tag, "Crack failed for stored handshake", e)
            }
        }
    }

    fun exportStoredHandshake(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                    Log.e(
                        tag,
                        "exportStoredHandshake: cannot resolve chroot path for ${item.filePath}"
                    )
                    return@launch
                }
                val outputPath = chrootPath.removeSuffix(".cap").removeSuffix(".pcap")
                captureRunner.exportToHccapx(chrootPath, outputPath) { line ->
                    Log.d(tag, "[storage-export] $line")
                }
            } catch (e: Exception) {
                Log.e(tag, "Export failed for stored handshake", e)
            }
        }
    }

    private fun updateStorageItemStatus(filePath: String, isValid: Boolean) {
        val current = _storageItems.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.filePath == filePath }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isValid = isValid)
            _storageItems.postValue(current)
        }
    }

    private fun updateStorageItemCracked(filePath: String, password: String) {
        val current = _storageItems.value?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.filePath == filePath }
        if (idx >= 0) {
            current[idx] = current[idx].copy(crackedPassword = password)
            _storageItems.postValue(current)
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (captureJob?.isActive == true || _isCaptureRunning.value == true) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try {
                    captureRunner.forceCleanup()
                } catch (e: Exception) {
                    Log.e("AirodumpViewModel", "Force cleanup failed", e)
                }
            }
        }
    }
}
