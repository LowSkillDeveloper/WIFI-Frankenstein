package com.lsd.wififrankenstein.ui.settings

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.WifiApplication
import com.lsd.wififrankenstein.ui.airodump.InterfaceStatus
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UsbDeviceInfo(
    val vidPid: String,
    val deviceName: String,
    val wlanInterface: String?,
    val driverName: String? = null,
    val driverLoaded: Boolean = false,
    val supportsMonitorMode: Boolean = false
)

class WlanInterfaceManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val iwWifiManager = IwWifiManager(application)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val tag = "WlanInterfaceMgrVM"

    private val _interfaceStatuses = MutableLiveData<List<InterfaceStatus>>(emptyList())
    val interfaceStatuses: LiveData<List<InterfaceStatus>> = _interfaceStatuses

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _newUsbDeviceDetected = MutableLiveData<UsbDeviceInfo?>()
    val newUsbDeviceDetected: LiveData<UsbDeviceInfo?> = _newUsbDeviceDetected

    private val knownInterfaces = mutableMapOf<String, String>()
    private val usbDeviceMap = mutableMapOf<String, String>()
    private val unavailableCount = mutableMapOf<String, Int>()
    private var previousUsbDevices = emptySet<String>()
    private var pollingJob: Job? = null
    private var consecutiveChrootFailures = 0

    companion object {
        private const val PREFS_NAME = "wlan_interface_manager"
        private const val KEY_CUSTOM_INTERFACES = "custom_interfaces"
        private const val KEY_DISMISSED_USB_DEVICES = "dismissed_usb_devices"
        private const val POLL_INTERVAL_MS = 3000L
        private const val POLL_MAX_INTERVAL_MS = 30000L
        private const val CONSECUTIVE_FAILURES_TO_BACKOFF = 3
        private const val MAX_CONSECUTIVE_FAILURES = 10

        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
        private const val KEY_DEAUTH_IFACE = "deauth_interface"
    }

    private val handshakePrefs =
        application.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
    private val settingsPrefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getScanInterface(): String {
        return handshakePrefs.getString(KEY_SCAN_IFACE, "wlan0") ?: "wlan0"
    }

    fun setScanInterface(iface: String) {
        if (validateInterface(iface)) {
            handshakePrefs.edit().putString(KEY_SCAN_IFACE, iface).apply()
        }
    }

    fun getCaptureInterface(): String {
        return handshakePrefs.getString(KEY_CAPTURE_IFACE, "wlan0") ?: "wlan0"
    }

    fun setCaptureInterface(iface: String) {
        if (validateInterface(iface)) {
            handshakePrefs.edit().putString(KEY_CAPTURE_IFACE, iface).apply()
        }
    }

    fun getDeauthInterface(): String {
        return handshakePrefs.getString(KEY_DEAUTH_IFACE, "wlan0") ?: "wlan0"
    }

    fun setDeauthInterface(iface: String) {
        if (iface.isEmpty() || validateInterface(iface)) {
            handshakePrefs.edit().putString(KEY_DEAUTH_IFACE, iface).apply()
        }
    }

    private fun validateInterface(iface: String): Boolean {
        val availableIfaces = knownInterfaces.keys
        return availableIfaces.contains(iface)
    }

    fun getAutoScanInterfaces(): Boolean =
        settingsPrefs.getBoolean("auto_scan_interfaces", true)

    fun setAutoScanInterfaces(enabled: Boolean) {
        settingsPrefs.edit { putBoolean("auto_scan_interfaces", enabled) }
    }

    fun getExtendedPollInterval(): Boolean =
        settingsPrefs.getBoolean("extended_poll_interval", false)

    fun setExtendedPollInterval(enabled: Boolean) {
        settingsPrefs.edit { putBoolean("extended_poll_interval", enabled) }
    }

    private fun getEffectivePollIntervalMs(): Long =
        if (settingsPrefs.getBoolean("extended_poll_interval", false)) 30000L else POLL_INTERVAL_MS

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        if (!settingsPrefs.getBoolean("auto_scan_interfaces", true)) return
        viewModelScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { WifiApplication.checkRootAccess() }
            if (!hasRoot) {
                Log.d(tag, "No root access, polling disabled")
                return@launch
            }
        }
        pollingJob = viewModelScope.launch {
            var currentInterval = getEffectivePollIntervalMs()
            while (isActive) {
                pollInterfaceStatus()
                if (consecutiveChrootFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.d(
                        tag,
                        "Too many consecutive failures ($consecutiveChrootFailures), stopping polling"
                    )
                    stopPolling()
                    return@launch
                }
                delay(currentInterval)
                if (consecutiveChrootFailures >= CONSECUTIVE_FAILURES_TO_BACKOFF) {
                    currentInterval = minOf(currentInterval * 2, POLL_MAX_INTERVAL_MS)
                    Log.d(
                        tag,
                        "Polling backoff: next interval ${currentInterval}ms (${consecutiveChrootFailures} failures)"
                    )
                } else {
                    currentInterval = getEffectivePollIntervalMs()
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun pollInterfaceStatus(): Boolean {
        viewModelScope.launch {
            try {
                val (systemNames, customNames, anyAvailable) = withContext(Dispatchers.IO) {
                    val systemIfaces = iwWifiManager.getAvailableInterfaces()
                    val sysNames = systemIfaces.map { it.name }.toSet()
                    val custNames = getCustomInterfaces()

                    val allModes = iwWifiManager.getAllInterfaceModes()
                    var avail = false

                    for (name in sysNames) {
                        val mode = allModes[name] ?: IwWifiManager.MODE_UNAVAILABLE
                        knownInterfaces[name] = mode

                        if (mode == IwWifiManager.MODE_UNAVAILABLE) {
                            unavailableCount[name] = (unavailableCount[name] ?: 0) + 1
                        } else {
                            unavailableCount.remove(name)
                            avail = true
                        }
                    }

                    for (name in custNames) {
                        if (name !in sysNames) {
                            val mode = allModes[name] ?: IwWifiManager.MODE_UNAVAILABLE
                            knownInterfaces[name] = mode

                            if (mode == IwWifiManager.MODE_UNAVAILABLE) {
                                unavailableCount[name] = (unavailableCount[name] ?: 0) + 1
                            } else {
                                unavailableCount.remove(name)
                                avail = true
                            }
                        }
                    }

                    Triple(sysNames, custNames, avail)
                }

                val toRemove = unavailableCount.entries
                    .filter { it.value >= 2 }
                    .map { it.key }

                for (name in toRemove) {
                    knownInterfaces.remove(name)
                    unavailableCount.remove(name)
                }

                withContext(Dispatchers.IO) {
                    pollUsbDevices()
                }

                val statuses = mutableListOf<InterfaceStatus>()
                for ((name, mode) in knownInterfaces.entries.sortedBy { it.key }) {
                    val subtitle = usbDeviceMap.entries
                        .firstOrNull { it.value == name }?.key
                        ?.let { pid -> UsbWifiDeviceDb.devices[pid] }
                    statuses.add(InterfaceStatus(name, mode, subtitle))
                }

                val orphanedUsb = usbDeviceMap.entries
                    .filter { it.value !in knownInterfaces.keys }
                    .map { (pid, _) ->
                        val deviceName = UsbWifiDeviceDb.devices[pid] ?: pid
                        InterfaceStatus(pid, IwWifiManager.MODE_UNAVAILABLE, deviceName)
                    }
                statuses.addAll(orphanedUsb)

                _interfaceStatuses.postValue(statuses)


                if (anyAvailable) {
                    consecutiveChrootFailures = 0
                } else {
                    consecutiveChrootFailures++
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to poll interface status", e)
                consecutiveChrootFailures++
            }
        }
        return false
    }

    private suspend fun pollUsbDevices() {
        try {
            val newUsbDevices = mutableSetOf<String>()
            val devices = usbManager.deviceList
            for ((_, usbDevice) in devices) {
                val vid = String.format("%04x", usbDevice.vendorId)
                val pid = String.format("%04x", usbDevice.productId)
                val vidPid = "$vid:$pid"

                if (vidPid !in UsbWifiDeviceDb.devices) continue

                newUsbDevices.add(vidPid)

                val wlanIface = findWlanInterfaceForUsb(vid, pid)
                if (wlanIface != null) {
                    usbDeviceMap[vidPid] = wlanIface
                } else {
                    usbDeviceMap[vidPid] = vidPid
                }

                val dismissedDevices = getDismissedUsbDevices()
                if (vidPid !in previousUsbDevices && vidPid !in dismissedDevices) {
                    val deviceName = UsbWifiDeviceDb.devices[vidPid] ?: vidPid
                    val driverInfo = getDriverInfo(wlanIface)
                    _newUsbDeviceDetected.postValue(
                        UsbDeviceInfo(
                            vidPid,
                            deviceName,
                            wlanIface,
                            driverInfo.first,
                            driverInfo.second,
                            driverInfo.third
                        )
                    )
                }
            }

            previousUsbDevices = newUsbDevices
        } catch (e: Exception) {
            Log.e(tag, "Failed to poll USB devices", e)
        }
    }

    private fun findWlanInterfaceForUsb(vid: String, pid: String): String? {
        return try {
            val netDir = File("/sys/class/net")
            val ifaces = netDir.listFiles()
            if (ifaces == null) {
                Log.d(tag, "Cannot list /sys/class/net")
                return null
            }

            Log.d(tag, "Searching for USB device $vid:$pid in ${ifaces.size} interfaces")

            for (ifaceDir in ifaces) {
                val ifaceName = ifaceDir.name

                val uevent = File(ifaceDir, "device/uevent")
                if (!uevent.exists()) {
                    Log.d(tag, "No uevent for $ifaceName")
                    continue
                }

                val content = try {
                    uevent.readText()
                } catch (e: Exception) {
                    Log.d(tag, "Cannot read uevent for $ifaceName: ${e.message}")
                    continue
                }

                Log.d(tag, "Checking $ifaceName uevent content:\n$content")

                val productLine = content.lines().firstOrNull { it.startsWith("PRODUCT=") }
                    ?: continue

                val parts = productLine.removePrefix("PRODUCT=").split("/")
                if (parts.size >= 2) {
                    val ifaceVid = parts[0].toIntOrNull(16)?.toString(16)?.padStart(4, '0')
                    val ifacePid = parts[1].toIntOrNull(16)?.toString(16)?.padStart(4, '0')

                    Log.d(tag, "Interface $ifaceName has VID:PID = $ifaceVid:$ifacePid")

                    if (ifaceVid == vid && ifacePid == pid) {
                        Log.d(tag, "Found matching interface: $ifaceName for USB $vid:$pid")
                        return ifaceName
                    }
                }
            }

            Log.d(tag, "No matching interface found via uevent, trying USB bus fallback")
            findWlanInterfaceViaUsbBus(vid, pid)
        } catch (e: Exception) {
            Log.e(tag, "Failed to find wlan interface for USB", e)
            null
        }
    }

    private fun findWlanInterfaceViaUsbBus(vid: String, pid: String): String? {
        return try {
            val usbDevicesDir = File("/sys/bus/usb/devices")
            val usbDevices = usbDevicesDir.listFiles() ?: return null

            for (usbDev in usbDevices) {
                val idVendorFile = File(usbDev, "idVendor")
                val idProductFile = File(usbDev, "idProduct")

                if (!idVendorFile.exists() || !idProductFile.exists()) continue

                val deviceVid = idVendorFile.readText().trim()
                val devicePid = idProductFile.readText().trim()

                if (deviceVid == vid && devicePid == pid) {
                    Log.d(tag, "Found USB device at ${usbDev.name}")


                    val netDir = File(usbDev, "net")
                    if (netDir.exists()) {
                        val netIfaces = netDir.listFiles()
                        if (netIfaces != null && netIfaces.isNotEmpty()) {
                            val ifaceName = netIfaces[0].name
                            Log.d(tag, "Found wlan interface via USB bus: $ifaceName")
                            return ifaceName
                        }
                    }


                    val subDirs = usbDev.listFiles()
                    if (subDirs != null) {
                        for (subDir in subDirs) {
                            if (subDir.name.contains(":") && subDir.isDirectory) {
                                val subNetDir = File(subDir, "net")
                                if (subNetDir.exists()) {
                                    val netIfaces = subNetDir.listFiles()
                                    if (netIfaces != null && netIfaces.isNotEmpty()) {
                                        val ifaceName = netIfaces[0].name
                                        Log.d(
                                            tag,
                                            "Found wlan interface via USB bus subdirectory: $ifaceName"
                                        )
                                        return ifaceName
                                    }
                                }
                            }
                        }
                    }

                    val driverLink = File(usbDev, "driver")
                    if (driverLink.exists()) {
                        val driverPath = driverLink.canonicalPath
                        Log.d(tag, "USB device driver: $driverPath")
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(tag, "Failed to find wlan interface via USB bus", e)
            null
        }
    }

    fun setInterfaceMode(ifaceName: String, targetMode: String) {
        viewModelScope.launch {
            try {
                _toastMessage.postValue("Switching $ifaceName...")
                val success = withContext(Dispatchers.IO) {
                    iwWifiManager.setInterfaceMode(ifaceName, targetMode)
                }
                if (success) {
                    knownInterfaces[ifaceName] = targetMode
                    pollInterfaceStatus()
                    _toastMessage.postValue("$ifaceName → $targetMode")
                } else {
                    _toastMessage.postValue("Failed to switch $ifaceName")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to set interface mode", e)
                _toastMessage.postValue("Error switching $ifaceName")
            }
        }
    }

    fun getCustomInterfaces(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_INTERFACES, emptySet()) ?: emptySet()
    }

    fun addCustomInterface(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = getCustomInterfaces().toMutableSet()
        if (current.add(trimmed)) {
            prefs.edit { putStringSet(KEY_CUSTOM_INTERFACES, current) }
            pollInterfaceStatus()
        }
    }

    fun removeCustomInterface(name: String) {
        val current = getCustomInterfaces().toMutableSet()
        if (current.remove(name)) {
            prefs.edit { putStringSet(KEY_CUSTOM_INTERFACES, current) }
            knownInterfaces.remove(name)
            pollInterfaceStatus()
        }
    }

    fun isCustomInterface(name: String): Boolean {
        return getCustomInterfaces().contains(name)
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearNewUsbDeviceDetected() {
        _newUsbDeviceDetected.value = null
    }

    suspend fun getDriverInfo(wlanInterface: String?): Triple<String?, Boolean, Boolean> {
        if (wlanInterface == null) return Triple(null, false, false)

        return try {
            val uevent = File("/sys/class/net/$wlanInterface/device/uevent")
            val driverName = if (uevent.exists()) {
                val content = uevent.readText()
                content.lines()
                    .firstOrNull { it.startsWith("DRIVER=") }
                    ?.removePrefix("DRIVER=")
            } else null


            val driverLoaded = driverName != null && (
                    File("/sys/module/$driverName").exists() ||
                            File("/sys/bus/usb/drivers/$driverName").exists() ||
                            File("/sys/class/net/$wlanInterface/device/driver").exists()
                    )

            val supportsMonitor = checkMonitorModeSupport(wlanInterface)

            Triple(driverName, driverLoaded, supportsMonitor)
        } catch (e: Exception) {
            Log.e(tag, "Failed to get driver info", e)
            Triple(null, false, false)
        }
    }

    private suspend fun checkMonitorModeSupport(wlanInterface: String): Boolean {
        return try {
            val iwListResult = iwWifiManager.runIwList(wlanInterface)
            iwListResult.contains("monitor", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(tag, "Failed to check monitor mode support", e)
            false
        }
    }

    fun getDismissedUsbDevices(): Set<String> {
        return prefs.getStringSet(KEY_DISMISSED_USB_DEVICES, emptySet()) ?: emptySet()
    }

    fun dismissUsbDevice(vidPid: String) {
        val current = getDismissedUsbDevices().toMutableSet()
        current.add(vidPid)
        prefs.edit { putStringSet(KEY_DISMISSED_USB_DEVICES, current) }
        clearNewUsbDeviceDetected()
    }
}
