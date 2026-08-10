package com.lsd.wififrankenstein.ui.bettercap

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
import com.lsd.wififrankenstein.network.bettercap.BettercapAP
import com.lsd.wififrankenstein.network.bettercap.BettercapClient
import com.lsd.wififrankenstein.network.bettercap.BettercapEvent
import com.lsd.wififrankenstein.network.bettercap.CaptureMode
import com.lsd.wififrankenstein.network.bettercap.DaemonStatus
import com.lsd.wififrankenstein.network.bettercap.EventTag
import com.lsd.wififrankenstein.network.bettercap.SessionResponse
import com.lsd.wififrankenstein.service.BettercapDaemonService
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeItem
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.util.BettercapManager
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class BettercapViewModel(application: Application) : AndroidViewModel(application) {

    data class BettercapCaptureResult(
        val ap: BettercapAP,
        val isValid: Boolean,
        val eapolCount: Int = 0,
        val pmkidCount: Int = 0,
        val handshakeCount: Int = 0,
        val error: String? = null
    )

    private val client = BettercapClient()
    private val iwWifiManager = IwWifiManager(application)
    private val bettercapManager by lazy { BettercapManager(getApplication()) }
    private val broadcastManager = LocalBroadcastManager.getInstance(application)
    private val storageManager = HandshakeStorageManager(application)
    private val captureRunner = HandshakeCaptureRunner(application)
    private val tag = "BettercapVM"

    private val _wifiApList = MutableLiveData<List<BettercapAP>>(emptyList())
    val wifiApList: LiveData<List<BettercapAP>> = _wifiApList

    private val _eventLog = MutableLiveData<List<BettercapEvent>>(emptyList())
    val eventLog: LiveData<List<BettercapEvent>> = _eventLog

    private val _daemonStatus = MutableLiveData(DaemonStatus.STOPPED)
    val daemonStatus: LiveData<DaemonStatus> = _daemonStatus

    private val _selectedAp = MutableLiveData<BettercapAP?>()
    val selectedAp: LiveData<BettercapAP?> = _selectedAp

    private val _interfaces = MutableLiveData<List<IwInterface>>(emptyList())
    val interfaces: LiveData<List<IwInterface>> = _interfaces

    private val _daemonIface = MutableLiveData<String?>(null)
    val daemonIface: LiveData<String?> = _daemonIface

    private val _captureMode = MutableLiveData(CaptureMode.HOPPING)
    val captureMode: LiveData<CaptureMode> = _captureMode

    private val _selectedChannels = MutableLiveData<List<Int>>(emptyList())
    val selectedChannels: LiveData<List<Int>> = _selectedChannels

    private val _apCount = MutableLiveData(0)
    val apCount: LiveData<Int> = _apCount

    private val _handshakeCount = MutableLiveData(0)
    val handshakeCount: LiveData<Int> = _handshakeCount

    private val _sessionResults = MutableLiveData<List<BettercapCaptureResult>?>(null)
    val sessionResults: LiveData<List<BettercapCaptureResult>?> = _sessionResults

    private val _leftoverCaptures = MutableLiveData<List<BettercapCaptureResult>?>(null)
    val leftoverCaptures: LiveData<List<BettercapCaptureResult>?> = _leftoverCaptures

    private val _commandError = MutableLiveData<String?>(null)
    val commandError: LiveData<String?> = _commandError

    private val eventBuffer = mutableListOf<BettercapEvent>()
    private val maxEvents = 500
    private val eventDedupKeys = HashSet<String>()
    private val handshakeFiles = mutableMapOf<String, String>()
    private val dismissedLeftoverBssids = mutableSetOf<String>()

    private var pollingJob: Job? = null
    private var eventsPollingJob: Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(BettercapDaemonService.EXTRA_STATUS) ?: return
            Log.d(tag, "statusReceiver: received status=$status")
            _daemonStatus.postValue(
                when (status) {
                    "starting" -> DaemonStatus.STARTING
                    "running" -> DaemonStatus.RUNNING
                    "error" -> DaemonStatus.ERROR
                    "restarting" -> DaemonStatus.RESTARTING
                    "stopped" -> DaemonStatus.STOPPED
                    else -> DaemonStatus.STOPPED
                }
            )
            if (status == "running") {
                _daemonIface.postValue(intent.getStringExtra(BettercapDaemonService.EXTRA_IFACE))
            } else {
                _daemonIface.postValue(null)
            }
            when (status) {
                "running" -> {
                    Log.d(tag, "statusReceiver: daemon running, starting polling")
                    startPolling()

                    val channels = _selectedChannels.value ?: emptyList()
                    applyCaptureMode(channels)
                }

                "error" -> {
                    Log.e(tag, "statusReceiver: daemon error received")
                    stopPolling()
                }

                else -> {
                    stopPolling()
                }
            }
        }
    }

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

    fun startDaemon(iface: String) {
        val channels = _selectedChannels.value ?: emptyList()
        val channelMode = if (channels.isEmpty()) "auto" else channels.joinToString(",")
        Log.d(
            tag,
            "startDaemon: registering status receiver, starting daemon on $iface ch=$channelMode"
        )
        try {
            broadcastManager.unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        broadcastManager.registerReceiver(
            statusReceiver,
            IntentFilter(BettercapDaemonService.BROADCAST_STATUS)
        )
        BettercapDaemonService.start(getApplication(), iface, channelMode)
    }

    fun stopDaemon() {
        if (_daemonStatus.value == DaemonStatus.STOPPED) return
        stopPolling()
        val aps = _wifiApList.value?.filter { it.handshake }.orEmpty()
        viewModelScope.launch {
            if (aps.isNotEmpty() && _daemonStatus.value != DaemonStatus.ERROR) {
                val results = withContext(Dispatchers.IO) { validateCaptures(aps) }
                _sessionResults.postValue(results)
            }
            try {
                broadcastManager.unregisterReceiver(statusReceiver)
            } catch (_: Exception) {
            }
            BettercapDaemonService.stop(getApplication())
            if (_daemonStatus.value != DaemonStatus.ERROR) {
                _daemonStatus.value = DaemonStatus.STOPPED
                _daemonIface.value = null
            }
        }
    }

    fun dismissSessionResults() {
        _sessionResults.value = null
    }

    fun dismissLeftoverCaptures() {
        _leftoverCaptures.value?.forEach {
            dismissedLeftoverBssids.add(it.ap.mac.uppercase())
        }
        _leftoverCaptures.value = null
    }

    fun consumeCommandError() {
        _commandError.value = null
    }

    fun saveSelectedResults(results: List<BettercapCaptureResult>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (r in results) {
                saveHandshakeToStorage(r.ap)
            }
            _sessionResults.postValue(null)
            _leftoverCaptures.postValue(null)
            val intent = Intent("handshake_storage_refresh")
            LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
        }
    }

    private suspend fun validateCaptures(aps: List<BettercapAP>): List<BettercapCaptureResult> {
        val results = mutableListOf<BettercapCaptureResult>()
        for (ap in aps) {
            try {
                val handshakeFile = resolveHandshakePath(ap)
                val data = captureRunner.readCapBytes(handshakeFile)
                if (data == null || data.isEmpty()) {
                    results.add(BettercapCaptureResult(ap, false, error = "File not found"))
                    continue
                }

                var allHashes = mutableListOf<HandshakeHash>()
                try {
                    val parsed = captureRunner.readCapBytesAndParse(handshakeFile)
                    allHashes.addAll(parsed)
                } catch (_: Exception) {
                }
                if (ChrootCapabilities.isAvailable(getApplication())) {
                    try {
                        val raw = captureRunner.getHcxpcapngtoolOutput(handshakeFile)
                        val parsed =
                            raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                        allHashes.addAll(parsed)
                    } catch (_: Exception) {
                    }
                }
                allHashes = allHashes.distinctBy { it.dedupKey() }.toMutableList()
                val validPmkid =
                    allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
                val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
                val isValid =
                    allHashes.any { it.type == HandshakeType.EAPOL } || validPmkid.isNotEmpty()
                results.add(
                    BettercapCaptureResult(
                        ap,
                        isValid,
                        eapolCount,
                        pmkidCount,
                        allHashes.size
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "validateCapture failed: ${ap.mac}", e)
                results.add(BettercapCaptureResult(ap, false, error = e.message))
            }
        }
        return results
    }

    fun checkLeftoverBettercapCaptures() {
        if (_daemonStatus.value == DaemonStatus.RUNNING ||
            _daemonStatus.value == DaemonStatus.STARTING ||
            bettercapManager.isDaemonRunning()
        ) {
            Log.d(tag, "checkLeftoverBettercapCaptures: daemon active, skipping")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingBssids = storageManager.listSavedBssids()
                val orphans = storageManager.getRawCaptureOrphans()
                    .filter { it.fileName.endsWith(".pcap") }
                    .filter { orphan ->
                        val bssid = parseBssidFromFileName(orphan.fileName)
                        bssid?.uppercase() !in existingBssids &&
                                bssid?.uppercase() !in dismissedLeftoverBssids
                    }
                if (orphans.isEmpty()) return@launch
                val results = mutableListOf<BettercapCaptureResult>()
                for (orphan in orphans) {
                    try {
                        var allHashes = mutableListOf<HandshakeHash>()
                        try {
                            val parsed = captureRunner.readCapBytesAndParse(orphan.filePath)
                            allHashes.addAll(parsed)
                        } catch (_: Exception) {
                        }
                        if (ChrootCapabilities.isAvailable(getApplication())) {
                            try {
                                val raw = captureRunner.getHcxpcapngtoolOutput(orphan.filePath)
                                val parsed = raw.lines()
                                    .mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                                allHashes.addAll(parsed)
                            } catch (_: Exception) {
                            }
                        }
                        allHashes = allHashes.distinctBy { it.dedupKey() }.toMutableList()
                        val validPmkid =
                            allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                        val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
                        val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
                        val isValid =
                            allHashes.any { it.type == HandshakeType.EAPOL } || validPmkid.isNotEmpty()
                        val fakeAp = BettercapAP(
                            mac = parseBssidFromFileName(orphan.fileName) ?: "",
                            hostname = parseEssidFromFileName(orphan.fileName) ?: orphan.fileName,
                            channel = 0, vendor = "", handshake = true
                        )
                        results.add(
                            BettercapCaptureResult(
                                fakeAp,
                                isValid,
                                eapolCount,
                                pmkidCount,
                                allHashes.size
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
                if (results.isNotEmpty()) {
                    _leftoverCaptures.postValue(results)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun parseEssidFromFileName(fileName: String): String? {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        if (Regex("^[0-9A-Fa-f]{12}$").matches(nameWithoutExt)) return null
        val macPattern = Regex("_[0-9A-Fa-f]{12}$")
        return nameWithoutExt.replace(macPattern, "").replace('_', ' ').replace("hs ", "")
            .takeIf { it.isNotBlank() }
    }

    private fun parseBssidFromFileName(fileName: String): String? {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        val trailing = Regex("_([0-9A-Fa-f]{12})$").find(nameWithoutExt)
        val hex = trailing?.groupValues?.get(1)
            ?: Regex("^([0-9A-Fa-f]{12})$").find(nameWithoutExt)?.groupValues?.get(1)
            ?: return null
        return hex.uppercase().chunked(2).joinToString(":")
    }






    private fun bettercapPathFriendlyName(ap: BettercapAP): String {
        val bssid = ap.mac.replace(":", "")
        val cleanEssid = ap.hostname.replace(Regex("[^a-zA-Z0-9]+"), "")
        return if (cleanEssid.isEmpty()) bssid else "${cleanEssid}_$bssid"
    }

    private fun resolveHandshakePath(ap: BettercapAP): String {
        return handshakeFiles[ap.mac.lowercase()]
            ?: "/sdcard/WIFI-Frankenstein/captured/${bettercapPathFriendlyName(ap)}.pcap"
    }

    private fun parseHandshakeFile(event: BettercapEvent) {
        try {
            val obj = event.data?.jsonObject ?: return
            val apMac = obj["ap"]?.jsonPrimitive?.content?.lowercase() ?: return
            val file = obj["file"]?.jsonPrimitive?.content ?: return
            handshakeFiles[apMac] = file
        } catch (_: Exception) {
        }
    }

    private fun startPolling() {
        Log.d(
            tag,
            "startPolling: starting AP polling every 2s + events polling every 3s"
        )
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var pollCount = 0
            while (isActive) {
                try {
                    val state = withContext(Dispatchers.IO) { client.getWifiState() }
                    val aps = state.aps
                    if (pollCount % 5 == 0 || pollCount == 0) {
                        Log.d(tag, "AP poll #$pollCount: got ${aps.size} APs")
                    }
                    _wifiApList.postValue(aps)
                    _apCount.postValue(aps.size)
                    _handshakeCount.postValue(aps.count { it.handshake })
                    pollCount++
                } catch (e: Exception) {
                    if (pollCount % 5 == 0) {
                        Log.e(tag, "AP poll #$pollCount failed: ${e.message}")
                    }
                }
                delay(2_000)
            }
        }

        eventsPollingJob?.cancel()
        eventsPollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val events = withContext(Dispatchers.IO) { client.getEvents(50) }
                    for (event in events) {
                        addEvent(event)
                    }
                } catch (_: Exception) {
                }
                delay(3_000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        eventsPollingJob?.cancel()
        eventsPollingJob = null
    }

    private fun eventDedupKey(event: BettercapEvent): String {
        return "${event.tag}|${event.time}|${event.data?.toString() ?: "null"}"
    }

    private fun addEvent(event: BettercapEvent) {
        synchronized(eventBuffer) {
            val key = eventDedupKey(event)
            if (!eventDedupKeys.add(key)) return
            if (event.tag == EventTag.CLIENT_HANDSHAKE.tag) {
                parseHandshakeFile(event)
            }
            eventBuffer.add(event)
            if (eventBuffer.size > maxEvents) {
                val removed = eventBuffer.removeAt(0)
                eventDedupKeys.remove(eventDedupKey(removed))
            }
            _eventLog.postValue(eventBuffer.toList())
        }
    }

    fun clearEventLog() {
        synchronized(eventBuffer) {
            eventBuffer.clear()
            eventDedupKeys.clear()
            _eventLog.postValue(emptyList())
        }
    }

    fun selectAp(ap: BettercapAP?) {
        _selectedAp.value = ap
    }

    private suspend fun runCommand(cmd: String): SessionResponse? {
        return try {
            withContext(Dispatchers.IO) { client.executeCommand(cmd) }
        } catch (e: Exception) {
            Log.e(tag, "Command failed: $cmd", e)
            null
        }
    }

    fun executeCommand(cmd: String) {
        viewModelScope.launch {
            runCommand(cmd)
        }
    }

    private fun executeUserCommand(cmd: String) {
        viewModelScope.launch {
            val response = runCommand(cmd)
            if (response?.success == false) {
                val msg = response.msg.ifBlank { "Command failed: $cmd" }
                _commandError.postValue(msg)
                addEvent(
                    BettercapEvent(
                        tag = "user.error",
                        time = System.currentTimeMillis().toString(),
                        data = null
                    )
                )
            }
        }
    }

    fun deauthAp(bssid: String) {
        executeUserCommand("wifi.deauth $bssid")
        addEvent(
            BettercapEvent(
                tag = "user.action",
                time = System.currentTimeMillis().toString(),
                data = null
            )
        )
    }

    fun deauthClient(clientMac: String) {
        executeUserCommand("wifi.deauth $clientMac")
    }

    fun deauthAll() {
        executeUserCommand("wifi.deauth *")
    }

    fun deauthSelectedClients(apBssid: String, clientMacs: List<String>) {
        if (clientMacs.isEmpty()) {
            deauthAp(apBssid)
            return
        }
        for (mac in clientMacs) {
            if (mac.isNotBlank()) {
                executeUserCommand("wifi.deauth $mac")
            }
        }
    }

    fun assoc(bssid: String) {
        executeUserCommand("wifi.assoc $bssid")
    }

    fun assocAll() {
        executeUserCommand("wifi.assoc *")
    }

    fun setChannelsAndMode(channels: List<Int>) {
        val prev = _selectedChannels.value ?: emptyList()
        _selectedChannels.value = channels
        val mode = when {
            channels.isEmpty() -> {
                CaptureMode.HOPPING
            }

            channels.size == 1 -> {
                CaptureMode.SINGLE
            }

            else -> {
                CaptureMode.CHANNEL_SET
            }
        }
        _captureMode.value = mode


        if (prev != channels) {
            executeCommand("wifi.clear")
            synchronized(eventBuffer) {
                eventBuffer.clear()
                eventDedupKeys.clear()
                _eventLog.postValue(emptyList())
            }
            handshakeFiles.clear()
        }

        applyCaptureMode(channels)
    }

    private fun applyCaptureMode(channels: List<Int>) {
        if (_daemonStatus.value != DaemonStatus.RUNNING) return
        when {
            channels.isEmpty() -> {
                executeCommand("wifi.recon.channel clear")
            }

            channels.size == 1 -> {
                executeCommand("wifi.recon.channel ${channels.first()}")
            }

            else -> {
                executeCommand("wifi.recon.channel ${channels.joinToString(",")}")
            }
        }
    }

    fun setConfig(param: String, value: String) {
        executeCommand("set $param $value")
    }

    fun saveHandshakeToStorage(ap: BettercapAP) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val handshakeFile = resolveHandshakePath(ap)
                Log.d(tag, "Saving handshake in place from: $handshakeFile")
                val data = captureRunner.readCapBytes(handshakeFile)
                if (data != null && data.isNotEmpty()) {
                    var allHashes = mutableListOf<HandshakeHash>()
                    try {
                        val parsed = captureRunner.readCapBytesAndParse(handshakeFile)
                        allHashes.addAll(parsed)
                    } catch (_: Exception) {
                    }
                    if (ChrootCapabilities.isAvailable(getApplication())) {
                        try {
                            val raw = captureRunner.getHcxpcapngtoolOutput(handshakeFile)
                            val parsed = raw.lines()
                                .mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                            allHashes.addAll(parsed)
                        } catch (_: Exception) {
                        }
                    }
                    allHashes = allHashes.distinctBy { it.dedupKey() }.toMutableList()
                    val hash22000 = allHashes.map { it.to22000Line() }.distinct()
                        .joinToString("\n").takeIf { it.isNotBlank() }
                    val validPmkid =
                        allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                    val hashPmkid =
                        validPmkid.firstOrNull()?.pmkidOrMic?.takeIf { it.length == 32 }
                    val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
                    val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
                    val firstHash = allHashes.firstOrNull()
                    val hasValidData =
                        allHashes.any { it.type == HandshakeType.EAPOL } || validPmkid.isNotEmpty()

                    if (!hasValidData) {
                        Log.d(
                            tag,
                            "No valid handshake data in $handshakeFile — not saving to storage"
                        )
                        return@launch
                    }

                    val essid = firstHash?.essid ?: ap.hostname
                    val bssid = firstHash?.macAp?.uppercase() ?: ap.mac.uppercase()
                    val movedPath = storageManager.moveToStorage(handshakeFile, essid, bssid)
                    if (movedPath == null) {
                        Log.w(tag, "Failed to move handshake to storage: $handshakeFile")
                        return@launch
                    }
                    storageManager.deleteRawChrootFile(handshakeFile)

                    storageManager.saveHandshakeMetadata(
                        HandshakeItem(
                            filePath = movedPath,
                            fileName = File(movedPath).name,
                            bssid = bssid,
                            essid = essid,
                            fileSize = data.size.toLong(),
                            lastModified = System.currentTimeMillis(),
                            hash22000 = hash22000,
                            hashPmkid = hashPmkid,
                            originalFormat = "pcap",
                            handshakeCount = allHashes.size,
                            eapolCount = eapolCount,
                            pmkidCount = pmkidCount,
                            isValid = null
                        )
                    )
                    val intent = Intent("handshake_storage_refresh")
                    LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
                } else {
                    Log.w(tag, "No handshake data readable at $handshakeFile")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to save handshake", e)
            }
        }
    }

    override fun onCleared() {
        stopPolling()
        try {
            broadcastManager.unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
        super.onCleared()
    }
}
