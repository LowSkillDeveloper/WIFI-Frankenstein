package com.lsd.wififrankenstein.ui.localnetwork

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class ScanMode { NATIVE, CHROOT }

data class LocalNetworkState(
    val isScanning: Boolean = false,
    val devices: List<LocalDevice> = emptyList(),
    val subnet: String = "",
    val gateway: String = "",
    val selectedInterface: String = "wlan0",
    val availableInterfaces: List<String> = listOf("wlan0"),
    val error: String? = null,
    val scannedCount: Int = 0,
    val totalFound: Int = 0,
    val phase: String = "",
    val consoleLines: List<String> = emptyList(),
    val scanMode: ScanMode = ScanMode.NATIVE,
    val hasRoot: Boolean = false,
    val chrootAvailable: Boolean = false,
    val routerScanAvailable: Boolean = false
)

class LocalNetworkViewModel(application: Application) : AndroidViewModel(application) {

    private val chrootManager = ChrootManager.get(application)
    private val chrootScanner = LocalNetworkScanner(chrootManager)
    private val nativeScanner = NativeLocalNetworkScanner(application)
    private val nativeArpSpoof = NativeArpSpoof(application)
    var deviceAdapter: LocalDeviceAdapter? = null

    private val _state = MutableLiveData(LocalNetworkState())
    val state: LiveData<LocalNetworkState> = _state

    private var quickScanJob: Job? = null
    private var detailedScanJob: Job? = null
    private var cutJobs = mutableMapOf<String, Job>()

    private val mutableDevices =
        java.util.Collections.synchronizedList(mutableListOf<LocalDevice>())
    private val mutableConsole = java.util.Collections.synchronizedList(mutableListOf<String>())

    init {
        viewModelScope.launch {
            val ifaces = nativeScanner.getWlanInterfaces()
            val chrootType = chrootManager.getChrootType()
            val hasRoot = chrootType is com.lsd.wififrankenstein.util.ChrootType.Root ||
                    chrootType is com.lsd.wififrankenstein.util.ChrootType.RootMissing ||
                    chrootType is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot
            val chrootInstalled = chrootType is com.lsd.wififrankenstein.util.ChrootType.Root
            val routerScanAvailable = chrootInstalled ||
                    chrootType is com.lsd.wififrankenstein.util.ChrootType.RootMissing ||
                    chrootType is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot ||
                    chrootType is com.lsd.wififrankenstein.util.ChrootType.Rootless

            _state.postValue(
                _state.value?.copy(
                    availableInterfaces = ifaces,
                    hasRoot = hasRoot,
                    chrootAvailable = chrootInstalled,
                    routerScanAvailable = routerScanAvailable,
                    scanMode = ScanMode.NATIVE
                )
            )
        }
    }

    fun setInterface(name: String) {
        _state.postValue(_state.value?.copy(selectedInterface = name))
    }

    fun setScanMode(mode: ScanMode) {
        _state.postValue(_state.value?.copy(scanMode = mode))
    }

    fun quickScan(interfaceName: String = "wlan0") {
        quickScanJob?.cancel()
        detailedScanJob?.cancel()
        quickScanJob = viewModelScope.launch {
            mutableConsole.clear()
            val prev = _state.value
            _state.postValue(
                LocalNetworkState(
                    isScanning = true,
                    phase = "detect_subnet",
                    selectedInterface = prev?.selectedInterface ?: "wlan0",
                    availableInterfaces = prev?.availableInterfaces ?: listOf("wlan0"),
                    scanMode = prev?.scanMode ?: ScanMode.NATIVE,
                    hasRoot = prev?.hasRoot ?: false,
                    chrootAvailable = prev?.chrootAvailable ?: false,
                    routerScanAvailable = prev?.routerScanAvailable ?: false
                )
            )
            addLine("[*] Starting local network scan...")

            try {
                val mode = _state.value?.scanMode ?: ScanMode.NATIVE
                addLine("[*] Mode: $mode")

                addLine("[*] Detecting subnet on $interfaceName...")
                val subnetInfo = if (mode == ScanMode.CHROOT) {
                    chrootScanner.detectSubnet(interfaceName)
                } else {
                    nativeScanner.detectSubnet(interfaceName)
                }

                if (subnetInfo == null) {
                    addLine("[-] Failed to detect subnet")
                    _state.postValue(
                        _state.value?.copy(
                            isScanning = false,
                            phase = "",
                            error = "Cannot detect subnet"
                        )
                    )
                    return@launch
                }

                addLine("[+] Subnet: ${subnetInfo.subnet}, Gateway: ${subnetInfo.gateway}")
                _state.postValue(
                    _state.value?.copy(
                        subnet = subnetInfo.subnet,
                        gateway = subnetInfo.gateway,
                        phase = "ping_sweep"
                    )
                )

                addLine("[*] Ping sweep on ${subnetInfo.subnet}...")
                val discoveredDevices = if (mode == ScanMode.CHROOT) {
                    chrootScanner.pingSweep(subnetInfo.subnet) { progress ->
                        handleSweepProgress(progress)
                    }
                } else {
                    nativeScanner.pingSweep(subnetInfo.subnet) { progress ->
                        handleSweepProgress(progress)
                    }
                }

                if (discoveredDevices.isEmpty()) {
                    addLine("[-] No devices found on ${subnetInfo.subnet}")
                } else {
                    addLine("[+] Found ${discoveredDevices.size} devices")
                }

                val gatewayIp = subnetInfo.gateway
                val sortedDevices = discoveredDevices
                    .map { if (it.ip == gatewayIp) it.copy(isGateway = true) else it }
                    .let { sortRouterFirst(it, gatewayIp) }

                LocalDeviceAdapter.submitData(getAdapter(), sortedDevices)
                _state.postValue(
                    _state.value?.copy(
                        devices = sortedDevices,
                        totalFound = discoveredDevices.size
                    )
                )

                synchronized(mutableDevices) {
                    mutableDevices.clear()
                    mutableDevices.addAll(sortedDevices)
                }

                addLine("[+] Scan complete — ${discoveredDevices.size} devices found")
                _state.postValue(_state.value?.copy(isScanning = false, phase = ""))
            } catch (e: Exception) {
                val msg = "Scan failed: ${e.message}"
                Log.e(TAG, msg, e)
                addLine("[-] $msg")
                _state.postValue(_state.value?.copy(isScanning = false, phase = "", error = msg))
            }
        }
    }

    fun detailedScan(interfaceName: String = "wlan0") {
        detailedScanJob?.cancel()
        quickScanJob?.cancel()
        detailedScanJob = viewModelScope.launch {
            mutableConsole.clear()
            val prev = _state.value
            _state.postValue(
                LocalNetworkState(
                    isScanning = true,
                    phase = "detect_subnet",
                    selectedInterface = prev?.selectedInterface ?: "wlan0",
                    availableInterfaces = prev?.availableInterfaces ?: listOf("wlan0"),
                    scanMode = prev?.scanMode ?: ScanMode.NATIVE,
                    hasRoot = prev?.hasRoot ?: false,
                    chrootAvailable = prev?.chrootAvailable ?: false,
                    routerScanAvailable = prev?.routerScanAvailable ?: false
                )
            )
            addLine("[*] Starting detailed scan...")
            val viewModelStartTime = System.currentTimeMillis()

            try {
                val mode = _state.value?.scanMode ?: ScanMode.NATIVE

                addLine("[*] Detecting subnet on $interfaceName...")
                val subnetInfo = if (mode == ScanMode.CHROOT) {
                    chrootScanner.detectSubnet(interfaceName)
                } else {
                    nativeScanner.detectSubnet(interfaceName)
                }

                if (subnetInfo == null) {
                    addLine("[-] Failed to detect subnet")
                    _state.postValue(
                        _state.value?.copy(
                            isScanning = false,
                            phase = "",
                            error = "Cannot detect subnet"
                        )
                    )
                    return@launch
                }

                addLine("[+] Subnet: ${subnetInfo.subnet}, Gateway: ${subnetInfo.gateway}")
                _state.postValue(
                    _state.value?.copy(
                        subnet = subnetInfo.subnet,
                        gateway = subnetInfo.gateway,
                        phase = "ping_sweep"
                    )
                )

                addLine("[*] Ping sweep on ${subnetInfo.subnet}...")
                val discoveredDevices = if (mode == ScanMode.CHROOT) {
                    chrootScanner.pingSweep(subnetInfo.subnet) { progress ->
                        handleSweepProgress(progress)
                    }
                } else {
                    nativeScanner.pingSweep(subnetInfo.subnet) { progress ->
                        handleSweepProgress(progress)
                    }
                }

                if (discoveredDevices.isEmpty()) {
                    addLine("[-] No devices found")
                    _state.postValue(_state.value?.copy(isScanning = false, phase = ""))
                    return@launch
                }

                addLine("[+] Found ${discoveredDevices.size} devices, starting parallel port scan...")

                val detailedDevices = discoveredDevices.toMutableList()
                var scannedCount = 0
                val semaphore = Semaphore(10)
                val gatewayIp = subnetInfo.gateway

                coroutineScope {
                    val deferred = discoveredDevices.mapIndexed { i, device ->
                        async {
                            semaphore.withPermit {
                                val scanned = if (mode == ScanMode.CHROOT) {
                                    chrootScanner.scanDevicePorts(
                                        device,
                                        fastScan = false
                                    ) { progress ->
                                        if (progress.line.isNotEmpty() && progress.phase == "error") {
                                            addLine("[-] ${progress.line}")
                                        }
                                    }
                                } else {
                                    nativeScanner.scanDevicePorts(
                                        device,
                                        fastScan = false
                                    ) { progress ->
                                        if (progress.line.isNotEmpty() && progress.phase == "error") {
                                            addLine("[-] ${progress.line}")
                                        }
                                    }
                                }
                                scanned to i
                            }
                        }
                    }
                    val results: List<Pair<LocalDevice, Int>> = deferred.awaitAll()
                    for ((scanned, idx) in results) {
                        detailedDevices[idx] = scanned
                        scannedCount++

                        val sorted = sortRouterFirst(detailedDevices.toList(), gatewayIp)
                        LocalDeviceAdapter.submitData(getAdapter(), sorted)
                        _state.postValue(
                            _state.value?.copy(
                                devices = sorted,
                                totalFound = discoveredDevices.size,
                                scannedCount = scannedCount,
                                phase = "port_scan"
                            )
                        )
                    }
                }

                val gatewayIdx = detailedDevices.indexOfFirst { it.ip == gatewayIp }
                if (gatewayIdx >= 0) {
                    detailedDevices[gatewayIdx] = detailedDevices[gatewayIdx].copy(isGateway = true)
                }
                val sortedDevices = sortRouterFirst(detailedDevices.toList(), gatewayIp)

                synchronized(mutableDevices) {
                    mutableDevices.clear()
                    mutableDevices.addAll(sortedDevices)
                }

                val totalPorts = sortedDevices.sumOf { it.openPorts.size }
                val totalTime = System.currentTimeMillis() - viewModelStartTime
                val summary =
                    "[+] Detailed scan complete: ${discoveredDevices.size} devices, $totalPorts open ports, ${totalTime}ms total"
                Log.d(TAG, summary)
                addLine(summary)
                _state.postValue(
                    _state.value?.copy(
                        devices = sortedDevices,
                        isScanning = false,
                        phase = "",
                        scannedCount = 0
                    )
                )
            } catch (e: Exception) {
                val msg = "Detailed scan failed: ${e.message}"
                Log.e(TAG, msg, e)
                addLine("[-] $msg")
                _state.postValue(_state.value?.copy(isScanning = false, phase = "", error = msg))
            }
        }
    }

    fun cancelScan() {
        quickScanJob?.cancel()
        detailedScanJob?.cancel()
        quickScanJob = null
        detailedScanJob = null
        _state.postValue(_state.value?.copy(isScanning = false, phase = ""))
        addLine("[-] Scan cancelled")
    }

    fun clearResults() {
        quickScanJob?.cancel()
        detailedScanJob?.cancel()
        quickScanJob = null
        detailedScanJob = null
        synchronized(mutableDevices) { mutableDevices.clear() }
        mutableConsole.clear()
        _state.postValue(
            LocalNetworkState(
                isScanning = false,
                subnet = "",
                gateway = "",
                selectedInterface = _state.value?.selectedInterface ?: "wlan0",
                availableInterfaces = _state.value?.availableInterfaces ?: listOf("wlan0"),
                scanMode = _state.value?.scanMode ?: ScanMode.NATIVE,
                hasRoot = _state.value?.hasRoot ?: false,
                chrootAvailable = _state.value?.chrootAvailable ?: false,
                routerScanAvailable = _state.value?.routerScanAvailable ?: false
            )
        )
    }

    fun cutInternet(device: LocalDevice, gatewayIp: String, durationSeconds: Int = 0) {
        if (_state.value?.hasRoot != true) {
            addLine("[-] Cut Internet requires root access")
            return
        }

        cutJobs[device.ip]?.cancel()
        cutJobs[device.ip] = viewModelScope.launch {
            val iface = _state.value?.selectedInterface ?: "wlan0"
            addLine("[*] Cutting internet for ${device.ip}...")
            val (success, output) = nativeArpSpoof.cutInternet(
                device.ip,
                gatewayIp,
                iface,
                durationSeconds
            )
            if (success) {
                addLine("[+] $output")
                if (durationSeconds > 0) {
                    delay(durationSeconds * 1000L)
                    val (restored, _) = nativeArpSpoof.restoreInternet(device.ip, gatewayIp)
                    if (restored) addLine("[+] Internet restored for ${device.ip}")
                }
            } else {
                addLine("[-] $output")
            }
        }
    }

    fun restoreInternet(device: LocalDevice, gatewayIp: String) {
        cutJobs[device.ip]?.cancel()
        cutJobs[device.ip] = viewModelScope.launch {
            addLine("[*] Restoring internet for ${device.ip}...")
            val (success, output) = nativeArpSpoof.restoreInternet(
                device.ip,
                gatewayIp,
                _state.value?.selectedInterface ?: "wlan0"
            )
            if (success) {
                addLine("[+] Internet restored for ${device.ip}")
            } else {
                addLine("[-] Failed to restore internet for ${device.ip}: $output")
            }
        }
    }

    fun sendWakeOnLan(context: android.content.Context, device: LocalDevice) {
        viewModelScope.launch {
            addLine("[*] Sending Wake-on-LAN to ${device.ip}...")
            val (success, msg) = withContext(Dispatchers.IO) {
                WakeOnLan.send(context, device.mac)
            }
            if (success) {
                addLine("[+] $msg")
            } else {
                addLine("[-] $msg")
            }
        }
    }

    suspend fun scanDevice(ip: String): LocalDevice? {
        val device = mutableDevices.find { it.ip == ip } ?: return null
        if (device.openPorts.isNotEmpty() && device.osType != OSType.UNKNOWN) return device

        addLine("[*] Scanning ${device.ip}...")
        return withContext(Dispatchers.IO) {
            val mode = _state.value?.scanMode ?: ScanMode.NATIVE
            val scanned = if (mode == ScanMode.CHROOT) {
                chrootScanner.scanDevicePorts(device) { progress ->
                    if (progress.line.isNotEmpty() && progress.phase == "error") {
                        addLine("[-] ${progress.line}")
                    }
                }
            } else {
                nativeScanner.scanDevicePorts(device) { progress ->
                    if (progress.line.isNotEmpty() && progress.phase == "error") {
                        addLine("[-] ${progress.line}")
                    }
                }
            }
            val updatedList: List<LocalDevice>? = synchronized(mutableDevices) {
                val idx = mutableDevices.indexOfFirst { it.ip == ip }
                if (idx >= 0) {
                    mutableDevices[idx] = scanned
                    val list = mutableDevices.toList()
                    _state.postValue(_state.value?.copy(devices = list))
                    list
                } else {
                    null
                }
            }
            if (updatedList != null) {
                withContext(Dispatchers.Main) {
                    LocalDeviceAdapter.submitData(getAdapter(), updatedList)
                }
            }
            addLine(
                "[+] ${scanned.ip}: ${scanned.openPorts.size} ports open, OS: ${
                    scanned.os.take(
                        40
                    )
                }"
            )
            scanned
        }
    }

    private fun addLine(line: String) {
        mutableConsole.add(line)
        _state.postValue(_state.value?.copy(consoleLines = mutableConsole.toList()))
    }

    private fun handleSweepProgress(progress: ScanProgress) {
        if (progress.line.isNotEmpty()) addLine(progress.line)
        if (progress.total > 0) {
            _state.postValue(
                _state.value?.copy(
                    phase = "ping_sweep",
                    scannedCount = progress.current.coerceIn(0, progress.total),
                    totalFound = progress.total
                )
            )
        }
    }

    private fun sortRouterFirst(devices: List<LocalDevice>, gatewayIp: String): List<LocalDevice> =
        devices.sortedBy { it.ip != gatewayIp && !it.isGateway }

    fun getAdapter(
        onDetails: (LocalDevice) -> Unit = {}
    ) = deviceAdapter ?: run {
        deviceAdapter = LocalDeviceAdapter(onDetails)
        deviceAdapter!!
    }

    companion object {
        private const val TAG = "LocalNetworkViewModel"
    }
}
