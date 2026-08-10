package com.lsd.wififrankenstein.ui.iwwifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lsd.wififrankenstein.ui.iwwifi.models.IwDeviceInfo
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwLinkInfo
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NetworkFrequencyBand
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class IwWifiManager(private val context: Context) {

    private val chrootManager = ChrootManager.get(context)
    private val iwBinary = "iw"

    var lastModeSwitchError: String? = null
        private set

    private var cachedIwList: String? = null
    private var cachedIwListTime: Long = 0

    private var cachedModes: Map<String, String>? = null
    private var cachedModesTime: Long = 0
    private var cachedInterfaces: List<IwInterface>? = null
    private var cachedInterfacesTime: Long = 0

    companion object {
        private const val TAG = "IwWifiManager"
        const val MODE_MANAGED = "managed"
        const val MODE_MONITOR = "monitor"
        const val MODE_UNKNOWN = "unknown"
        const val MODE_UNAVAILABLE = "unavailable"

        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
        private const val IW_LIST_CACHE_TTL_MS = 60_000L
        private const val MODE_CACHE_TTL_MS = 2000L
        private const val IFACE_CACHE_TTL_MS = 2000L

        private val BSSID_PATTERN =
            Regex("""([0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2})""")
        private val BSS_HEADER_PATTERN =
            Regex("BSS [0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\(on ")
    }

    fun saveSelectedInterface(interfaceName: String) {
        val prefs = context.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SCAN_IFACE, interfaceName).apply()
    }

    fun getSavedSelectedInterface(): String? {
        val prefs = context.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SCAN_IFACE, null)
    }

    fun saveCaptureInterface(interfaceName: String) {
        val prefs = context.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAPTURE_IFACE, interfaceName).apply()
    }

    fun getSavedCaptureInterface(): String? {
        val prefs = context.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CAPTURE_IFACE, null)
    }

    fun mountChroot(): Boolean {
        return chrootManager.mountChroot()
    }

    fun unmountChroot(): Boolean {
        return chrootManager.unmountChroot()
    }

    suspend fun getInterfaceMode(interfaceName: String): String = withContext(Dispatchers.IO) {
        try {
            val chrootType = chrootManager.getChrootType()
            if (chrootType !is com.lsd.wififrankenstein.util.ChrootType.Root) {
                Log.d(TAG, "Chroot not installed, returning MODE_UNKNOWN")
                return@withContext MODE_UNKNOWN
            }
            val now = System.currentTimeMillis()
            val cached = cachedModes
            if (cached != null && now - cachedModesTime < MODE_CACHE_TTL_MS) {
                val monIface = "${interfaceName}mon"
                val mode = cached[interfaceName] ?: cached[monIface]
                if (mode != null) return@withContext mode
            }
            val result = chrootManager.executeInChroot("$iwBinary dev")
            if (!result.isSuccess || result.out.isEmpty()) return@withContext MODE_UNKNOWN

            val output = result.out.joinToString("\n")
            val monIface = "${interfaceName}mon"
            val block = extractInterfaceBlock(output, interfaceName)
                ?: extractInterfaceBlock(output, monIface)
                ?: return@withContext MODE_UNKNOWN

            when {
                block.contains("type monitor", ignoreCase = true) -> MODE_MONITOR
                block.contains("type managed", ignoreCase = true) -> MODE_MANAGED
                block.contains("type IBSS", ignoreCase = true) -> "ibss"
                block.contains("type AP", ignoreCase = true) -> "ap"
                else -> MODE_UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interface mode", e)
            MODE_UNKNOWN
        }
    }

    suspend fun getAllInterfaceModes(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val chrootType = chrootManager.getChrootType()
            if (chrootType !is com.lsd.wififrankenstein.util.ChrootType.Root) {
                return@withContext emptyMap()
            }
            val result = chrootManager.executeInChroot("$iwBinary dev")
            if (!result.isSuccess || result.out.isEmpty()) return@withContext emptyMap()

            val output = result.out.joinToString("\n")
            val modes = mutableMapOf<String, String>()
            val lines = output.lines()
            var currentName: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("Interface ") -> {
                        currentName?.let { name ->
                            if (name !in modes) modes[name] = MODE_UNKNOWN
                        }
                        currentName = trimmed.substring(10).trim()
                    }

                    trimmed.startsWith("type ") && currentName != null -> {
                        val name = currentName
                        val type = trimmed.substring(5).trim()
                        modes[name] = when {
                            type.contains("monitor", ignoreCase = true) -> MODE_MONITOR
                            type.contains("managed", ignoreCase = true) -> MODE_MANAGED
                            type.contains("IBSS", ignoreCase = true) -> "ibss"
                            type.contains("AP", ignoreCase = true) -> "ap"
                            else -> MODE_UNKNOWN
                        }
                    }
                }
            }
            currentName?.let { if (it !in modes) modes[it] = MODE_UNKNOWN }
            val modeMap = modes.toMap()
            cachedModes = modeMap
            cachedModesTime = System.currentTimeMillis()
            modeMap
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all interface modes", e)
            emptyMap()
        }
    }

    private fun extractInterfaceBlock(output: String, ifaceName: String): String? {
        val lines = output.lines()
        var inBlock = false
        val sb = StringBuilder()
        for (line in lines) {
            if (line.trim().startsWith("Interface $ifaceName")) {
                inBlock = true
                sb.append(line).append('\n')
                continue
            }
            if (inBlock) {
                val trimmed = line.trim()
                if (trimmed.startsWith("Interface ") || trimmed.startsWith("phy#")) {
                    break
                }
                sb.append(line).append('\n')
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    suspend fun findMonitorInterface(baseInterface: String): String? = withContext(Dispatchers.IO) {
        try {
            val baseName = baseInterface.removeSuffix("mon")
            val monIface = "${baseName}mon"

            val now = System.currentTimeMillis()
            val cached = cachedModes
            if (cached != null && now - cachedModesTime < MODE_CACHE_TTL_MS) {
                val baseMode = cached[baseName]
                val monMode = cached[monIface]
                if (baseMode == MODE_MONITOR) return@withContext baseName
                if (monMode == MODE_MONITOR) return@withContext monIface
                if (baseMode != null && monMode != null) return@withContext null
            }

            val result = chrootManager.executeInChroot("$iwBinary dev")
            if (!result.isSuccess || result.out.isEmpty()) return@withContext null
            val output = result.out.joinToString("\n")

            val block = extractInterfaceBlock(output, baseName)
                ?.takeIf { it.contains("type monitor", ignoreCase = true) }
            if (block != null) return@withContext baseName

            val monBlock = extractInterfaceBlock(output, monIface)
                ?.takeIf { it.contains("type monitor", ignoreCase = true) }
            if (monBlock != null) return@withContext monIface

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error finding monitor interface", e)
            null
        }
    }

    private suspend fun checkInterfaceModeInternal(interfaceName: String): String {
        return try {
            val command = "$iwBinary dev $interfaceName info"
            val result = chrootManager.executeInChroot(command)

            if (result.isSuccess && result.out.isNotEmpty()) {
                val output = result.out.joinToString("\n")
                when {
                    output.contains("type monitor", ignoreCase = true) -> MODE_MONITOR
                    output.contains("type managed", ignoreCase = true) -> MODE_MANAGED
                    output.contains("type IBSS", ignoreCase = true) -> "ibss"
                    output.contains("type AP", ignoreCase = true) -> "ap"
                    else -> MODE_UNKNOWN
                }
            } else {
                MODE_UNKNOWN
            }
        } catch (e: Exception) {
            MODE_UNKNOWN
        }
    }

    suspend fun interfaceExists(interfaceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val command = "$iwBinary dev $interfaceName info"
            val result = chrootManager.executeInChroot(command)
            result.isSuccess && result.out.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun runIwList(interfaceName: String): String = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            if (cachedIwList != null && now - cachedIwListTime < IW_LIST_CACHE_TTL_MS) {
                return@withContext cachedIwList!!
            }
            val command = "$iwBinary list"
            val result = chrootManager.executeInChroot(command)
            val output = result.out.joinToString("\n")
            cachedIwList = output
            cachedIwListTime = now
            output
        } catch (e: Exception) {
            Log.e(TAG, "runIwList failed", e)
            ""
        }
    }

    private suspend fun runHostCommand(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(cmd).exec()
            (result.out + result.err).joinToString("\n").trim()
        } catch (e: Exception) {
            Log.e(TAG, "runHostCommand failed: $cmd", e)
            "Error: ${e.message}"
        }
    }

    suspend fun setInterfaceMode(
        interfaceName: String,
        mode: String,
        channel: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentMode = getInterfaceMode(interfaceName)
            if (currentMode == mode) {
                Log.d(TAG, "Interface $interfaceName already in $mode mode")
                return@withContext true
            }

            var output = ""
            when (mode) {
                MODE_MANAGED -> {

                    val isWlan0 = interfaceName.removeSuffix("mon") == "wlan0"
                    if (isWlan0) {
                        val sysfsCmd = "ip link set ${interfaceName} down; " +
                                "echo 0 > /sys/module/wlan/parameters/con_mode; " +
                                "ip link set ${interfaceName} up; svc wifi enable"
                        runHostCommand(sysfsCmd)
                        val actualMode = getInterfaceMode(interfaceName)
                        if (actualMode == MODE_MANAGED) {
                            lastModeSwitchError = null
                            Log.d(TAG, "Switched $interfaceName to managed mode via sysfs")
                            return@withContext true
                        }
                        Log.w(
                            TAG,
                            "sysfs restore failed for $interfaceName, falling back to airmon-ng"
                        )
                    }

                    val mountSys = "mountpoint -q /sys || mount -t sysfs sysfs /sys 2>/dev/null; "
                    val airmonCmd = mountSys + "airmon-ng stop $interfaceName 2>&1"
                    val result = chrootManager.executeInChroot(airmonCmd)
                    output += "\n" + (result.out + result.err).joinToString("\n")

                    val actualMode = getInterfaceMode(interfaceName)
                    if (actualMode == MODE_MANAGED) {
                        lastModeSwitchError = null
                        Log.d(TAG, "Switched $interfaceName to managed mode via airmon-ng")
                        return@withContext true
                    }

                    Log.w(
                        TAG,
                        "airmon-ng stop did not fully switch $interfaceName, trying iw set type managed..."
                    )
                    chrootManager.executeInChroot("ifconfig $interfaceName down 2>&1")
                    val iwResult = chrootManager.executeInChroot(
                        "$iwBinary dev $interfaceName set type $mode 2>&1"
                    )
                    output += "\n" + (iwResult.out + iwResult.err).joinToString("\n")
                    chrootManager.executeInChroot("ifconfig $interfaceName up 2>&1")

                    val finalMode = getInterfaceMode(interfaceName)
                    val success = finalMode == MODE_MANAGED
                    if (success) {
                        Log.d(TAG, "Switched $interfaceName to managed mode (via iw)")
                    } else {
                        Log.e(
                            TAG,
                            "Failed to switch $interfaceName to managed mode. Final mode: $finalMode"
                        )
                    }
                    return@withContext success
                }

                MODE_MONITOR -> {
                    val isWlan0 = interfaceName.removeSuffix("mon") == "wlan0"

                    if (isWlan0) {
                        val sysfsCmd = "ip link set $interfaceName down; " +
                                "echo 4 > /sys/module/wlan/parameters/con_mode; " +
                                "ip link set $interfaceName up; sleep 3"
                        output = runHostCommand(sysfsCmd)

                        val actualMode = getInterfaceMode(interfaceName)
                        if (actualMode == MODE_MONITOR) {
                            lastModeSwitchError = null
                            Log.d(TAG, "Switched $interfaceName to monitor mode via sysfs")
                            return@withContext true
                        }
                        Log.w(
                            TAG,
                            "sysfs monitor failed for $interfaceName, falling back to airmon-ng"
                        )
                    }

                    val chArg = if (!channel.isNullOrBlank()) " $channel" else ""
                    val mountSys = "mountpoint -q /sys || mount -t sysfs sysfs /sys 2>/dev/null; "
                    val cmd = mountSys + "airmon-ng start $interfaceName$chArg 2>&1"
                    val result = chrootManager.executeInChroot(cmd)
                    output = (result.out + result.err).joinToString("\n")
                    val normalizedOutput = output.replace("\\s+".toRegex(), "")

                    val actualMode = getInterfaceMode(interfaceName)
                    val success = result.isSuccess ||
                            output.contains("monitor mode vif enabled", ignoreCase = true) ||
                            output.contains("enabled", ignoreCase = true) ||
                            normalizedOutput.contains(
                                "mac80211monitormodevifenabled",
                                ignoreCase = true
                            ) ||
                            actualMode == MODE_MONITOR ||
                            findMonitorInterface(interfaceName) != null

                    if (success) {
                        lastModeSwitchError = null
                        Log.d(TAG, "Switched $interfaceName to monitor mode")
                    } else {
                        lastModeSwitchError = output.lines()
                            .firstOrNull { it.contains("ERROR", ignoreCase = true) }
                            ?: output.lines().firstOrNull { it.isNotBlank() }
                                    ?: "Unknown error"
                        Log.e(TAG, "Failed to switch to monitor mode: $output")
                        chrootManager.enableWifiOnHost()
                    }
                    return@withContext success
                }

                else -> return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting interface mode", e)
            false
        }
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val cached = cachedInterfaces
            if (cached != null && now - cachedInterfacesTime < IFACE_CACHE_TTL_MS) {
                return@withContext cached
            }

            val chrootType = chrootManager.getChrootType()
            if (chrootType !is com.lsd.wififrankenstein.util.ChrootType.Root) {
                Log.d(TAG, "Chroot not installed, using default wlan0")
                return@withContext listOf(IwInterface("wlan0"))
            }
            val command = "$iwBinary dev"
            val result = chrootManager.executeInChroot(command)

            if (result.isSuccess && result.out.isNotEmpty()) {
                val interfaces = parseInterfacesList(result.out.joinToString("\n"))
                cachedInterfaces = interfaces
                cachedInterfacesTime = now
                interfaces
            } else {
                Log.w(TAG, "Command failed or no output, using default wlan0")
                listOf(IwInterface("wlan0"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interfaces", e)
            listOf(IwInterface("wlan0"))
        }
    }

    suspend fun scanWifiNetworks(interfaceName: String): List<IwWifiNetwork> =
        withContext(Dispatchers.IO) {
            try {
                chrootManager.executeInChroot("ifconfig $interfaceName up 2>&1")

                val command = "$iwBinary dev $interfaceName scan"
                val result = chrootManager.executeInChroot(command)

                if (result.isSuccess && result.out.isNotEmpty()) {
                    val networks = parseWifiNetworks(result.out.joinToString("\n"))
                    networks
                } else {
                    Log.w(TAG, "Scan command failed or no output")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning wifi networks", e)
                emptyList()
            }
        }





    suspend fun scanWifiNetworksNative(): List<IwWifiNetwork> = withContext(Dispatchers.IO) {
        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.w(TAG, "WiFi is disabled, native scan skipped")
                return@withContext emptyList()
            }
            if (!hasLocationPermission()) {
                Log.w(TAG, "Location permission missing for native scan")
                throw SecurityException("Location permission is required for WiFi scan")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isLocationEnabled()) {
                Log.w(TAG, "Location services disabled, native scan skipped")
                throw SecurityException("Location services must be enabled for WiFi scan")
            }
            if (!wifiManager.startScan()) {
                Log.w(TAG, "startScan() returned false")
            }
            delay(2500)
            val results = wifiManager.scanResults.orEmpty()
            Log.d(TAG, "Native scan completed: ${results.size} networks")
            results.map { scanResultToIwNetwork(it) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Native scan permission denied", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning wifi networks natively", e)
            emptyList()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.isLocationEnabled ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isLocationEnabled check failed", e)
            false
        }
    }

    private fun scanResultToIwNetwork(r: ScanResult): IwWifiNetwork {
        val frequency = r.frequency
        val band = NetworkFrequencyBand.fromFrequency(frequency)
        val channelNum = NetworkFrequencyBand.getChannelNumber(frequency, band)
        val caps = r.capabilities ?: ""
        return IwWifiNetwork(
            ssid = r.SSID ?: "",
            bssid = r.BSSID ?: "",
            frequency = "$frequency MHz",
            channel = if (channelNum > 0) channelNum.toString() else "",
            signal = "${r.level} dBm",
            lastSeen = "",
            beaconInterval = "",
            capabilities = caps,
            securityType = detectSecurity(caps),
            wpsEnabled = caps.contains("[WPS]", ignoreCase = true),
            wpsLocked = false,
            band = context.getString(band.displayNameRes),
            rawData = caps
        )
    }

    private fun detectSecurity(caps: String): String {
        return when {
            caps.contains("SAE", ignoreCase = true) -> "WPA3"
            caps.contains("WPA2", ignoreCase = true) -> "WPA2-PSK"
            caps.contains("WPA", ignoreCase = true) -> "WPA-PSK"
            caps.contains("WEP", ignoreCase = true) -> "WEP"
            caps.contains("PSK", ignoreCase = true) -> "WPA-PSK"
            else -> "OPEN"
        }
    }

    suspend fun getRawScanForBssid(interfaceName: String, bssid: String): String? =
        withContext(Dispatchers.IO) {
            try {
                chrootManager.executeInChroot("ifconfig $interfaceName up 2>&1")

                val command = "$iwBinary dev $interfaceName scan"
                val result = chrootManager.executeInChroot(command)

                if (result.isSuccess && result.out.isNotEmpty()) {
                    val rawOutput = result.out.joinToString("\n")
                    val bssBlocks = rawOutput.split("\n\n").filter { it.isNotBlank() }

                    for (block in bssBlocks) {
                        if (block.contains("BSS $bssid")) {
                            return@withContext block
                        }
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting raw scan for BSSID $bssid", e)
                null
            }
        }

    suspend fun getLinkInfo(interfaceName: String): IwLinkInfo = withContext(Dispatchers.IO) {
        try {
            val command = "$iwBinary dev $interfaceName link"
            val result = chrootManager.executeInChroot(command)

            if (result.isSuccess && result.out.isNotEmpty()) {
                parseLinkInfo(result.out.joinToString("\n"))
            } else {
                IwLinkInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting link info", e)
            IwLinkInfo()
        }
    }

    suspend fun getDeviceInfo(): IwDeviceInfo = withContext(Dispatchers.IO) {
        try {
            val output = runIwList("")
            if (output.isNotEmpty()) {
                parseDeviceInfo(output)
            } else {
                IwDeviceInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            IwDeviceInfo()
        }
    }

    internal fun parseInterfacesList(output: String): List<IwInterface> {
        val interfaces = mutableListOf<IwInterface>()
        val lines = output.lines()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        lines.forEach { line ->
            val trimmed = line.trim()

            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let {
                        interfaces.add(IwInterface(it, currentType, currentAddr))
                    }
                    currentInterface = trimmed.substring(10).trim()
                    currentType = ""
                    currentAddr = ""
                }

                trimmed.startsWith("type ") -> {
                    currentType = trimmed.substring(5).trim()
                }

                trimmed.startsWith("addr ") -> {
                    currentAddr = trimmed.substring(5).trim()
                }
            }
        }

        currentInterface?.let {
            interfaces.add(IwInterface(it, currentType, currentAddr))
        }

        return if (interfaces.isEmpty()) {
            listOf(IwInterface("wlan0"))
        } else {
            interfaces
        }
    }

    private fun buildHtFeaturesString(
        rxLdpc: Boolean, ht20Ht40: Boolean, smPowerDisabled: Boolean,
        rxHt20Sgi: Boolean, rxHt40Sgi: Boolean, txStbc: Boolean,
        rxStbc1: Boolean, noRxStbc: Boolean, dssCckHt40: Boolean,
        amsduLen: String, ampduExp: String
    ): String {
        val features = mutableListOf<String>()
        if (rxLdpc) features.add("RX LDPC")
        if (ht20Ht40) features.add("HT20/HT40")
        if (smPowerDisabled) features.add("SM Power Save disabled")
        if (rxHt20Sgi) features.add("RX HT20 SGI")
        if (rxHt40Sgi) features.add("RX HT40 SGI")
        if (txStbc) features.add("TX STBC")
        if (rxStbc1) features.add("RX STBC 1-stream")
        if (noRxStbc) features.add("No RX STBC")
        if (amsduLen.isNotEmpty()) features.add("Max AMSDU length: $amsduLen bytes")
        if (dssCckHt40) features.add("DSSS/CCK HT40")
        if (features.isEmpty()) features.add("No HT features detected")
        return features.joinToString("\n\t\t")
    }

    private fun buildHePhyFeaturesString(
        suBf: Boolean, suBfme: Boolean, muBf: Boolean,
        sts80: String, sts80Plus: String,
        dims80: String, dims80Plus: String,
        ng: String, codebook: Boolean, triggeredBf: Boolean,
        triggeredCqi: Boolean, ppePresent: Boolean, maxNc: String,
        tx1024Qam: Boolean, rx1024Qam: Boolean, ldpc: Boolean
    ): String {
        val features = mutableListOf<String>()
        if (suBf) features.add("SU Beamformer")
        if (suBfme) features.add("SU Beamformee")
        if (muBf) features.add("MU Beamformer")
        if (sts80.isNotEmpty()) features.add("Beamformee STS <= 80MHz: $sts80")
        if (sts80Plus.isNotEmpty()) features.add("Beamformee STS > 80MHz: $sts80Plus")
        if (dims80.isNotEmpty()) features.add("Sounding Dimensions <= 80MHz: $dims80")
        if (dims80Plus.isNotEmpty()) features.add("Sounding Dimensions > 80MHz: $dims80Plus")
        if (ng.isNotEmpty()) features.add("Ng = $ng SU Feedback")
        if (codebook) features.add("Codebook Size SU Feedback")
        if (triggeredBf) features.add("Triggered SU Beamforming Feedback")
        if (triggeredCqi) features.add("Triggered CQI Feedback")
        if (ppePresent) features.add("PPE Threshold Present")
        if (maxNc.isNotEmpty()) features.add("Max NC: $maxNc")
        if (tx1024Qam) features.add("TX 1024-QAM")
        if (rx1024Qam) features.add("RX 1024-QAM")
        if (ldpc) features.add("LDPC Coding in Payload")
        return features.joinToString("\n\t\t")
    }

    private fun buildHeMacFeaturesString(
        htcHe: Boolean, bsr: Boolean, omControl: Boolean,
        ampduExp: String, amsduInAmpdu: Boolean, omUlMuDisable: Boolean
    ): String {
        val features = mutableListOf<String>()
        if (htcHe) features.add("+HTC HE Supported")
        if (bsr) features.add("BSR")
        if (omControl) features.add("OM Control")
        if (ampduExp.isNotEmpty()) features.add("Maximum A-MPDU Length Exponent: $ampduExp")
        if (amsduInAmpdu) features.add("A-MSDU in A-MPDU")
        if (omUlMuDisable) features.add("OM Control UL MU Data Disable RX")
        return features.joinToString("\n\t\t")
    }

    private fun buildVhtFeaturesString(featureLines: List<String>): String {
        if (featureLines.isEmpty()) return ""
        return featureLines.joinToString("\n\t\t")
    }

    internal fun parseWifiNetworks(output: String): List<IwWifiNetwork> {
        val networks = mutableListOf<IwWifiNetwork>()
        val lines = output.lines()
        val bssPattern = BSS_HEADER_PATTERN

        var currentBlock = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            val isBssHeader = bssPattern.containsMatchIn(trimmed)

            if (isBssHeader) {
                if (currentBlock.length > 0) {
                    try {
                        val network = parseNetworkBlock(currentBlock.trim().toString())
                        network?.let { networks.add(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing network block: ${e.message}")
                    }
                    currentBlock = StringBuilder()
                }
            }

            if (currentBlock.length > 0 || isBssHeader) {
                if (currentBlock.length > 0) currentBlock.append("\n")
                currentBlock.append(line)
            }
        }

        if (currentBlock.length > 0) {
            try {
                val network = parseNetworkBlock(currentBlock.trim().toString())
                network?.let { networks.add(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing network block: ${e.message}")
            }
        }

        return networks.sortedByDescending { it.signalStrength }
    }

    private class ParsedBss(
        var ssid: String = "",
        var frequency: String = "",
        var channel: String = "",
        var signal: String = "",
        var wpsEnabled: Boolean = false,
        var wpsLocked: Boolean = false,
        var capabilities: String = "",
        var lastSeen: String = "",
        var beaconInterval: String = "",
        var rsnVersion: String = "0",
        var authSuite: String = "",
        var groupCipher: String = "",
        var pairwiseCipher: String = "",
        var wpaVersion: String = "0",
        var wpaAuthSuite: String = "",
        var currentSection: String = "",
        var currentSubSection: String = "",
        var wpsVersion: String = "",
        var wpsDeviceType: String = "",
        var wpsDeviceName: String = "",
        var wpsManufacturer: String = "",
        var wpsModel: String = "",
        var wpsModelNumber: String = "",
        var wpsSerialNumber: String = "",
        var wpsUuid: String = "",
        var wpsConfigMethods: String = "",
        var wpsRfBands: String = "",
        var wpsResponseTypes: String = "",
        var dtimPeriod: String = "",
        var dtimCount: String = "",
        var associated: Boolean = false,
        var probeResponse: Boolean = false,
        var supportedRates: String = "",
        var extendedRates: String = "",
        var country: String = "",
        var countryEnv: String = "",
        var channelsAvailable: String = "",
        var powerConstraint: String = "",
        var txPower: String = "",
        var stationCount: String = "",
        var channelUtilisation: String = "",
        var admissionCapacity: String = "",
        var htCapabilities: String = "",
        var htCapabilitiesCapab: String = "",
        var htChannelWidth: String = "",
        var htSecondaryChannel: String = "",
        var htProtection: String = "",
        var htMcs: String = "",
        var htTxMcs: String = "",
        var htAmpduMaxLen: String = "",
        var htAmpduMinSpacing: String = "",
        var localAmpduMaxLen: String = "",
        var htRxLdpc: Boolean = false,
        var htHt20Ht40: Boolean = false,
        var htSmPowerSaveDisabled: Boolean = false,
        var htRxHt20Sgi: Boolean = false,
        var htRxHt40Sgi: Boolean = false,
        var htTxStbc: Boolean = false,
        var htRxStbc1Stream: Boolean = false,
        var htNoRxStbc: Boolean = false,
        var htDssCckHt40: Boolean = false,
        var htNoDssCckHt40: Boolean = false,
        var tpcTxPower: String = "",
        var environment: String = "",
        var vhtCapabilities: String = "",
        var vhtFeatureLines: MutableList<String> = mutableListOf(),
        var vhtMaxMpdu: String = "",
        var vhtSupportedChannelWidth: String = "",
        var vhtRxMcs: String = "",
        var vhtTxMcs: String = "",
        var vhtRxHighestSupported: String = "",
        var vhtTxHighestSupported: String = "",
        var vhtExtendedNss: String = "",
        var vhtOpChannelWidth: String = "",
        var vhtOpCenterFreq1: String = "",
        var vhtOpCenterFreq2: String = "",
        var vhtOpBasicMcs: String = "",
        var heOpParameters: String = "",
        var heOpDefaultPeDuration: String = "",
        var heOpTxopDurationRts: String = "",
        var heOpCoHostedBss: Boolean = false,
        var heOpErSuDisable: Boolean = false,
        var heOpBssColor: String = "",
        var heOpBasicMcsSet: String = "",
        var heOpMaxCoHostedBssid: String = "",
        var heOpVhtInfoPresent: Boolean = false,
        var heOpVhtInfo: String = "",
        var txPowerEnvelope20: String = "",
        var txPowerEnvelope40: String = "",
        var txPowerEnvelope80: String = "",
        var txPowerEnvelope160: String = "",
        var apChannelReportClass: String = "",
        var apChannelReportChannels: String = "",
        var operatingClasses: String = "",
        var currentSubIndent: Int = 0,
        var heCapabilities: String = "",
        var heMacCapabilities: String = "",
        var hePhyCapabilities: String = "",
        var heRcMcs: String = "",
        var heTcMcs: String = "",
        var hePpeThreshold: String = "",
        var hePhyHe40: Boolean = false,
        var hePhy242ToneRu: Boolean = false,
        var hePhyLdpcPayload: Boolean = false,
        var hePhyNd4Ltf32Gi: Boolean = false,
        var hePhyRxMuPpduNonAp: Boolean = false,
        var hePhySuBeamformer: Boolean = false,
        var hePhySuBeamformee: Boolean = false,
        var hePhyMuBeamformer: Boolean = false,
        var hePhyBeamformeeSts80: String = "",
        var hePhyBeamformeeSts80Plus: String = "",
        var hePhySoundingDims80: String = "",
        var hePhySoundingDims80Plus: String = "",
        var hePhyNg: String = "",
        var hePhyCodebookSu: Boolean = false,
        var hePhyTriggeredSuBf: Boolean = false,
        var hePhyTriggeredCqi: Boolean = false,
        var hePhyPpePresent: Boolean = false,
        var hePhyMaxNc: String = "",
        var hePhyTx1024Qam: Boolean = false,
        var hePhyRx1024Qam: Boolean = false,
        var hePhyTxPpdu4Ltf08Gi: Boolean = false,
        var hePhyDeviceClass: String = "",
        var hePhyStbcTx80: Boolean = false,
        var hePhyStbcRx80: Boolean = false,
        var hePhyDcmMaxConstellation: String = "",
        var hePhyFullBwUlMuMimo: Boolean = false,
        var hePhyPartialBwUlMuMimo: Boolean = false,
        var hePhyPartialBwExtendedRange: Boolean = false,
        var hePhy20In40Mhz: Boolean = false,
        var hePhyHe40He80: Boolean = false,
        var hePhyErSuPpdu4Ltf: Boolean = false,
        var hePhyErSuPpdu1Ltf: Boolean = false,
        var heMacHtcHe: Boolean = false,
        var heMacBsr: Boolean = false,
        var heMacOmControl: Boolean = false,
        var heMacMaxAmpduExp: String = "",
        var heMacAmsduInAmpdu: Boolean = false,
        var heMacOmUlMuDataDisableRx: Boolean = false,
        var heMacAckEnabledAggregation: Boolean = false,
        var heMacTwtResponder: Boolean = false,
        var heMacDynamicBaFragmentation: String = "",
        var heMacMinPayload128: Boolean = false,
        var heMacRxControlFrameMultiBss: Boolean = false,
        var heMacBqr: Boolean = false,
        var wmmParams: String = "",
        var extCapabilities: String = "",
        var wmmPresent: Boolean = false,
        var tsf: String = "",
        var rmCapabilitiesHex: String = "",
        var rmCapabilitiesList: MutableList<String> = mutableListOf(),
        var rmLinkMeasurement: Boolean = false,
        var rmNeighborReport: Boolean = false,
        var rmBeaconPassive: Boolean = false,
        var rmBeaconActive: Boolean = false,
        var rmBeaconTable: Boolean = false,
        var rmChannelLoad: Boolean = false,
        var rmStatistics: Boolean = false,
        var rmFrameMeasurement: Boolean = false,
        var rmLCI: Boolean = false,
        var rmTransmitStream: Boolean = false,
        var ftmRangeReport: Boolean = false,
        var rmCivicLocation: Boolean = false,
        var rmMeasurementPilotCap: String = "",
        var rmNonOpChannelMaxDur: String = "",
        var operatingClass: String = "",
        var extHtInfoExchange: Boolean = false,
        var extTfs: Boolean = false,
        var extWnmSleep: Boolean = false,
        var extTimBroadcast: Boolean = false,
        var extBssTransition: Boolean = false,
        var extOpModeNotification: Boolean = false,
        var extTwtResponder: Boolean = false,
        var extEcs: Boolean = false,
        var interworking: Boolean = false,
        var networkOptions: String = "",
        var networkType: String = "",
        var anqpAvailable: Boolean = false,
        var queryResponseLength: String = "",
        var txPowerEnvelope: String = "",
        var obssPassiveDwell: String = "",
        var obssActiveDwell: String = "",
        var obssScanInterval: String = "",
        var obssPassiveTotal: String = "",
        var obssActiveTotal: String = "",
        var obssChannelDelayFactor: String = "",
        var obssScanThreshold: String = "",
        var erpProtection: String = "",
        var staChannelWidth: String = "",
        var rifs: String = "",
        var nonGfPresent: String = "",
        var obssNonGfPresent: String = "",
        var dualBeacon: String = "",
        var dualCtsProtection: String = "",
        var stbcBeacon: String = "",
        var lsigTxopProtect: String = "",
        var pcoActive: String = "",
        var pcoPhase: String = ""
    )

    private fun parseNetworkBlock(block: String): IwWifiNetwork? {
        val rawLines = block.lines()
        val bssid = BSSID_PATTERN.find(block)?.groupValues?.get(1) ?: return null
        val s = ParsedBss()
        s.associated = block.contains("-- associated")
        s.probeResponse = block.contains("Information elements from Probe Response frame:")

        for (rawLine in rawLines) {
            val line = rawLine.trim()
            when {
                parseBssCoreFields(line, s) -> {}
                parseBssSecurity(line, s) -> {}
                parseBssRm(line, s) -> {}
                parseBssHt(line, s) -> {}
                parseBssExtended(line, s) -> {}
                parseBssHe(rawLine, s) -> {}
                parseBssVht(line, s) -> {}
                parseBssHeOp(line, s) -> {}
                parseBssWmm(line, s) -> {}
                line.startsWith("RSN:") || line.startsWith("WPA:") || line.startsWith("WPS:") || line.startsWith(
                    "WMM:"
                ) -> {
                    s.currentSection = when {
                        line.startsWith("RSN") -> "RSN"
                        line.startsWith("WPA") -> "WPA"
                        line.startsWith("WPS") -> {
                            s.wpsEnabled = true; "WPS"
                        }

                        line.startsWith("WMM") -> {
                            s.wmmPresent = true; "WMM"
                        }

                        else -> ""
                    }
                    s.currentSubSection = ""
                }
            }
        }

        val securityType = detectSecurityType(
            s.rsnVersion,
            s.authSuite,
            s.wpaVersion,
            s.wpaAuthSuite,
            s.groupCipher,
            s.pairwiseCipher,
            s.capabilities
        )

        return IwWifiNetwork(
            ssid = s.ssid,
            bssid = bssid,
            frequency = "${s.frequency} MHz",
            channel = s.channel,
            signal = s.signal,
            wpsEnabled = s.wpsEnabled,
            wpsLocked = s.wpsLocked,
            capabilities = s.capabilities,
            lastSeen = s.lastSeen,
            beaconInterval = s.beaconInterval,
            dtimPeriod = s.dtimPeriod,
            dtimCount = s.dtimCount,
            associated = s.associated,
            probeResponse = s.probeResponse,
            rawData = block,
            securityType = securityType,
            band = detectBand(s.frequency, s.capabilities),
            groupCipher = s.groupCipher,
            pairwiseCipher = s.pairwiseCipher,
            authSuite = s.authSuite,
            wpaVersion = s.wpaVersion,
            rsnVersion = s.rsnVersion,
            wpsVersion = s.wpsVersion,
            wpsDeviceType = s.wpsDeviceType,
            wpsDeviceName = s.wpsDeviceName,
            wpsManufacturer = s.wpsManufacturer,
            wpsModel = s.wpsModel,
            wpsModelNumber = s.wpsModelNumber,
            wpsSerialNumber = s.wpsSerialNumber,
            wpsUuid = s.wpsUuid,
            wpsConfigMethods = s.wpsConfigMethods,
            wpsRfBands = s.wpsRfBands,
            wpsResponseTypes = s.wpsResponseTypes,
            supportedRates = s.supportedRates,
            extendedRates = s.extendedRates,
            country = s.country,
            countryEnv = s.countryEnv,
            channelsAvailable = s.channelsAvailable,
            powerConstraint = s.powerConstraint,
            txPower = s.txPower,
            stationCount = s.stationCount,
            channelUtilisation = s.channelUtilisation,
            admissionCapacity = s.admissionCapacity,
            htCapabilities = s.htCapabilities,
            htCapabilitiesCapab = s.htCapabilitiesCapab,
            htChannelWidth = s.htChannelWidth,
            htSecondaryChannel = s.htSecondaryChannel,
            htProtection = s.htProtection,
            htMcs = s.htMcs,
            htTxMcs = s.htTxMcs,
            htAmpduMaxLen = s.htAmpduMaxLen,
            htAmpduMinSpacing = s.htAmpduMinSpacing,
            htRxLdpc = s.htRxLdpc,
            htHt20Ht40 = s.htHt20Ht40,
            htSmPowerSaveDisabled = s.htSmPowerSaveDisabled,
            htRxHt20Sgi = s.htRxHt20Sgi,
            htRxHt40Sgi = s.htRxHt40Sgi,
            htTxStbc = s.htTxStbc,
            htRxStbc1Stream = s.htRxStbc1Stream,
            htNoRxStbc = s.htNoRxStbc,
            htMaxAmsduLen = s.localAmpduMaxLen,
            htDssCckHt40 = s.htDssCckHt40,
            htNoDssCckHt40 = s.htNoDssCckHt40,
            htFeaturesRaw = buildHtFeaturesString(
                s.htRxLdpc, s.htHt20Ht40, s.htSmPowerSaveDisabled,
                s.htRxHt20Sgi, s.htRxHt40Sgi, s.htTxStbc,
                s.htRxStbc1Stream, s.htNoRxStbc, s.htDssCckHt40,
                s.localAmpduMaxLen, s.htAmpduMaxLen
            ),
            tpcTxPower = s.tpcTxPower,
            environment = s.environment,
            vhtCapabilities = s.vhtCapabilities,
            vhtFeaturesRaw = buildVhtFeaturesString(s.vhtFeatureLines),
            vhtMaxMpdu = s.vhtMaxMpdu,
            vhtSupportedChannelWidth = s.vhtSupportedChannelWidth,
            vhtRxMcs = s.vhtRxMcs,
            vhtTxMcs = s.vhtTxMcs,
            vhtRxHighestSupported = s.vhtRxHighestSupported,
            vhtTxHighestSupported = s.vhtTxHighestSupported,
            vhtExtendedNss = s.vhtExtendedNss,
            vhtOpChannelWidth = s.vhtOpChannelWidth,
            vhtOpCenterFreq1 = s.vhtOpCenterFreq1,
            vhtOpCenterFreq2 = s.vhtOpCenterFreq2,
            vhtOpBasicMcs = s.vhtOpBasicMcs,
            heOpParameters = s.heOpParameters,
            heOpDefaultPeDuration = s.heOpDefaultPeDuration,
            heOpTxopDurationRts = s.heOpTxopDurationRts,
            heOpCoHostedBss = s.heOpCoHostedBss,
            heOpErSuDisable = s.heOpErSuDisable,
            heOpBssColor = s.heOpBssColor,
            heOpBasicMcsSet = s.heOpBasicMcsSet,
            heOpMaxCoHostedBssid = s.heOpMaxCoHostedBssid,
            heOpVhtInfoPresent = s.heOpVhtInfoPresent,
            heOpVhtInfo = s.heOpVhtInfo,
            heCapabilities = s.heCapabilities,
            heMacCapabilities = s.heMacCapabilities,
            hePhyCapabilities = s.hePhyCapabilities,
            heRcMcs = s.heRcMcs,
            heTcMcs = s.heTcMcs,
            hePpeThreshold = s.hePpeThreshold,
            hePhyHe40 = s.hePhyHe40,
            hePhy242ToneRu = s.hePhy242ToneRu,
            hePhyLdpcPayload = s.hePhyLdpcPayload,
            hePhyNd4Ltf32Gi = s.hePhyNd4Ltf32Gi,
            hePhyRxMuPpduNonAp = s.hePhyRxMuPpduNonAp,
            hePhySuBeamformer = s.hePhySuBeamformer,
            hePhySuBeamformee = s.hePhySuBeamformee,
            hePhyMuBeamformer = s.hePhyMuBeamformer,
            hePhyBeamformeeSts80 = s.hePhyBeamformeeSts80,
            hePhyBeamformeeSts80Plus = s.hePhyBeamformeeSts80Plus,
            hePhySoundingDims80 = s.hePhySoundingDims80,
            hePhySoundingDims80Plus = s.hePhySoundingDims80Plus,
            hePhyNg = s.hePhyNg,
            hePhyCodebookSu = s.hePhyCodebookSu,
            hePhyTriggeredSuBf = s.hePhyTriggeredSuBf,
            hePhyTriggeredCqi = s.hePhyTriggeredCqi,
            hePhyPpePresent = s.hePhyPpePresent,
            hePhyMaxNc = s.hePhyMaxNc,
            hePhyTx1024Qam = s.hePhyTx1024Qam,
            hePhyRx1024Qam = s.hePhyRx1024Qam,
            hePhyTxPpdu4Ltf08Gi = s.hePhyTxPpdu4Ltf08Gi,
            hePhyDeviceClass = s.hePhyDeviceClass,
            hePhyStbcTx80 = s.hePhyStbcTx80,
            hePhyStbcRx80 = s.hePhyStbcRx80,
            hePhyDcmMaxConstellation = s.hePhyDcmMaxConstellation,
            hePhyFullBwUlMuMimo = s.hePhyFullBwUlMuMimo,
            hePhyPartialBwUlMuMimo = s.hePhyPartialBwUlMuMimo,
            hePhyPartialBwExtendedRange = s.hePhyPartialBwExtendedRange,
            hePhy20In40Mhz = s.hePhy20In40Mhz,
            hePhyHe40He80 = s.hePhyHe40He80,
            hePhyErSuPpdu4Ltf = s.hePhyErSuPpdu4Ltf,
            hePhyErSuPpdu1Ltf = s.hePhyErSuPpdu1Ltf,
            heMacHtcHe = s.heMacHtcHe,
            heMacBsr = s.heMacBsr,
            heMacOmControl = s.heMacOmControl,
            heMacMaxAmpduExp = s.heMacMaxAmpduExp,
            heMacAmsduInAmpdu = s.heMacAmsduInAmpdu,
            heMacOmUlMuDataDisableRx = s.heMacOmUlMuDataDisableRx,
            heMacAckEnabledAggregation = s.heMacAckEnabledAggregation,
            heMacTwtResponder = s.heMacTwtResponder,
            heMacDynamicBaFragmentation = s.heMacDynamicBaFragmentation,
            heMacMinPayload128 = s.heMacMinPayload128,
            heMacRxControlFrameMultiBss = s.heMacRxControlFrameMultiBss,
            heMacBqr = s.heMacBqr,
            wmmPresent = s.wmmPresent,
            wmmParams = s.wmmParams,
            extCapabilities = s.extCapabilities,
            tsf = s.tsf,
            rmCapabilities = s.rmCapabilitiesList.joinToString("\n"),
            rmCapabilitiesHex = s.rmCapabilitiesHex,
            rmLinkMeasurement = s.rmLinkMeasurement,
            rmNeighborReport = s.rmNeighborReport,
            rmBeaconPassive = s.rmBeaconPassive,
            rmBeaconActive = s.rmBeaconActive,
            rmBeaconTable = s.rmBeaconTable,
            rmChannelLoad = s.rmChannelLoad,
            rmStatistics = s.rmStatistics,
            rmFrameMeasurement = s.rmFrameMeasurement,
            rmLCI = s.rmLCI,
            rmTransmitStream = s.rmTransmitStream,
            ftmRangeReport = s.ftmRangeReport,
            rmCivicLocation = s.rmCivicLocation,
            rmMeasurementPilotCap = s.rmMeasurementPilotCap,
            rmNonOpChannelMaxDur = s.rmNonOpChannelMaxDur,
            operatingClass = s.operatingClass,
            operatingClasses = s.operatingClasses,
            apChannelReportClass = s.apChannelReportClass,
            apChannelReportChannels = s.apChannelReportChannels,
            extHtInfoExchange = s.extHtInfoExchange,
            extTfs = s.extTfs,
            extWnmSleep = s.extWnmSleep,
            extTimBroadcast = s.extTimBroadcast,
            extBssTransition = s.extBssTransition,
            extOpModeNotification = s.extOpModeNotification,
            extTwtResponder = s.extTwtResponder,
            extEcs = s.extEcs,
            interworking = s.interworking,
            networkOptions = s.networkOptions,
            networkType = s.networkType,
            anqpAvailable = s.anqpAvailable,
            queryResponseLength = s.queryResponseLength,
            txPowerEnvelope = listOfNotNull(
                s.txPowerEnvelope20, s.txPowerEnvelope40, s.txPowerEnvelope80, s.txPowerEnvelope160
            ).joinToString(", "),
            txPowerEnvelope20 = s.txPowerEnvelope20,
            txPowerEnvelope40 = s.txPowerEnvelope40,
            txPowerEnvelope80 = s.txPowerEnvelope80,
            txPowerEnvelope160 = s.txPowerEnvelope160,
            obssPassiveDwell = s.obssPassiveDwell,
            obssActiveDwell = s.obssActiveDwell,
            obssScanInterval = s.obssScanInterval,
            obssPassiveTotal = s.obssPassiveTotal,
            obssActiveTotal = s.obssActiveTotal,
            obssChannelDelayFactor = s.obssChannelDelayFactor,
            obssScanThreshold = s.obssScanThreshold,
            erpProtection = s.erpProtection,
            staChannelWidth = s.staChannelWidth,
            rifs = s.rifs,
            nonGfPresent = s.nonGfPresent,
            obssNonGfPresent = s.obssNonGfPresent,
            dualBeacon = s.dualBeacon,
            dualCtsProtection = s.dualCtsProtection,
            stbcBeacon = s.stbcBeacon,
            lsigTxopProtect = s.lsigTxopProtect,
            pcoActive = s.pcoActive,
            pcoPhase = s.pcoPhase
        )
    }

    private fun parseBssCoreFields(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("TSF: ") -> {
                s.tsf = line.substringAfter("TSF: ").trim(); true
            }

            line.startsWith("SSID: ") -> {
                val v = line.substringAfter("SSID: ").trim(); s.ssid =
                    if (v.isEmpty()) "Hidden network" else v; true
            }

            line.startsWith("freq: ") -> {
                s.frequency = line.substringAfter("freq: ").trim()
                val freq = s.frequency.toIntOrNull()
                s.channel = when {
                    freq == null -> "0"
                    freq >= 2412 && freq <= 2484 -> ((freq - 2412) / 5 + 1).toString()
                    freq >= 5170 && freq <= 5825 -> ((freq - 5000) / 5).toString()
                    freq >= 5955 && freq <= 45000 -> ((freq - 5955) / 5).toString()
                    else -> "0"
                }
                true
            }

            line.startsWith("signal: ") -> {
                s.signal = line.substringAfter("signal: ").trim(); true
            }

            line.startsWith("capability: ") -> {
                s.capabilities = line.substringAfter("capability: ").trim(); true
            }

            line.startsWith("last seen: ") -> {
                s.lastSeen = line.substringAfter("last seen: ").trim(); true
            }

            line.startsWith("beacon interval: ") -> {
                s.beaconInterval = line.substringAfter("beacon interval: ").trim(); true
            }

            line.startsWith("TIM:") -> {
                val d = Regex("""DTIM Period\s*(\d+)""").find(line); if (d != null) s.dtimPeriod =
                    d.groupValues[1];
                val c = Regex("""DTIM Count\s*(\d+)""").find(line); if (c != null) s.dtimCount =
                    c.groupValues[1]; true
            }

            line.startsWith("Supported rates: ") -> {
                s.supportedRates = line.substringAfter("Supported rates: ").trim(); true
            }

            line.startsWith("Extended supported rates: ") -> {
                s.extendedRates = line.substringAfter("Extended supported rates: ").trim(); true
            }

            line.startsWith("Country: ") -> {
                val full = line.substringAfter("Country: ").trim()
                val parts = full.split("\t").map { it.trim() }
                s.country = parts.getOrElse(0) { "" }
                if (parts.size > 1) {
                    val env = parts[1].substringAfter("Environment: ").trim(); s.environment =
                        env; s.countryEnv = env
                }
                true
            }

            line.startsWith("Channels ") -> {
                s.channelsAvailable = line.substringAfter("Channels ").trim(); true
            }

            line.startsWith("Power constraint: ") -> {
                s.powerConstraint = line.substringAfter("Power constraint: ").trim(); true
            }

            line.startsWith("TPC report: ") -> {
                val t =
                    Regex("""TX power:\s*([\d]+) dBm""").find(line); if (t != null) s.tpcTxPower =
                    t.groupValues[1] + " dBm"; true
            }

            line.startsWith("* Local Maximum Transmit Power") -> {
                val t = Regex("""For\s*([\d]+) MHz:\s*([\d]+) dBm""").find(line)
                if (t != null) {
                    val power = t.groupValues[2] + " dBm"
                    when (t.groupValues[1]) {
                        "20" -> s.txPowerEnvelope20 = power
                        "40" -> s.txPowerEnvelope40 = power
                        "80" -> s.txPowerEnvelope80 = power
                        "160" -> s.txPowerEnvelope160 = power
                    }
                }
                true
            }

            line.startsWith("BSS Load:") -> {
                s.currentSection = "BSS_LOAD"; true
            }

            line.contains("station count: ") -> {
                val sc =
                    Regex("""station count:\s*(\d+)""").find(line); if (sc != null) s.stationCount =
                    sc.groupValues[1]; true
            }

            line.contains("channel utilisation: ") -> {
                val cu =
                    Regex("""channel utilisation:\s*(\d+)/\d+""").find(line); if (cu != null) s.channelUtilisation =
                    cu.groupValues[1] + "/255"; true
            }

            line.contains("available admission capacity: ") -> {
                val ac =
                    Regex("""available admission capacity:\s*(\d+)""").find(line); if (ac != null) s.admissionCapacity =
                    ac.groupValues[1]; true
            }

            line.startsWith("ERP: ") -> {
                s.erpProtection = line.substringAfter("ERP: ").trim(); true
            }

            line.startsWith("Supported operating classes:") -> {
                s.currentSection = "OP_CLASS"; true
            }

            line.startsWith("* current operating class: ") -> {
                s.operatingClass = line.substringAfter("* current operating class: ").trim(); true
            }

            line.startsWith("* operating class: ") && s.currentSection == "OP_CLASS" -> {
                val oc = line.substringAfter("* operating class: ").trim()
                s.operatingClasses =
                    if (s.operatingClasses.isEmpty()) oc else s.operatingClasses + ", " + oc
                true
            }

            line.startsWith("AP Channel Report:") -> {
                s.currentSection = "AP_CHANNEL"; true
            }

            line.startsWith("* operating class: ") && s.currentSection == "AP_CHANNEL" -> {
                s.apChannelReportClass = line.substringAfter("* operating class: ").trim(); true
            }

            line.startsWith("* channel(s): ") && s.currentSection == "AP_CHANNEL" -> {
                s.apChannelReportChannels = line.substringAfter("* channel(s): ").trim(); true
            }

            else -> false
        }
    }

    private fun parseBssSecurity(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("RSN:") || line.startsWith("WPA:") -> {
                s.currentSection = if (line.startsWith("RSN")) "RSN" else "WPA"
                s.currentSubSection = ""
                val ver = Regex("""Version:\s*(\d+)""").find(line)
                if (ver != null) {
                    if (s.currentSection == "RSN") s.rsnVersion = ver.groupValues[1]
                    else s.wpaVersion = ver.groupValues[1]
                }
                true
            }

            s.currentSection == "RSN" && line.startsWith("* ") -> {
                when {
                    line.contains("Authentication suites: ") -> s.authSuite =
                        line.substringAfter("Authentication suites: ").trim()

                    line.contains("Group cipher: ") -> s.groupCipher =
                        line.substringAfter("Group cipher: ").trim()

                    line.contains("Pairwise ciphers: ") -> s.pairwiseCipher =
                        line.substringAfter("Pairwise ciphers: ").trim()
                }
                true
            }

            s.currentSection == "WPA" && line.startsWith("* ") -> {
                if (line.contains("Authentication suites: ")) s.wpaAuthSuite =
                    line.substringAfter("Authentication suites: ").trim()
                true
            }

            line.startsWith("WPS:") -> {
                s.currentSection = "WPS"; s.currentSubSection = ""; s.wpsEnabled = true; true
            }

            s.currentSection == "WPS" && line.contains("AP setup locked") -> {
                s.wpsLocked = true; true
            }

            s.currentSection == "WPS" && line.startsWith("* Version: ") -> {
                s.wpsVersion = line.substringAfter("* Version: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Version2: ") -> {
                s.wpsVersion = line.substringAfter("* Version2: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Response Type: ") -> {
                s.wpsResponseTypes = line.substringAfter("* Response Type: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* UUID: ") -> {
                s.wpsUuid = line.substringAfter("* UUID: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Manufacturer: ") -> {
                s.wpsManufacturer = line.substringAfter("* Manufacturer: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Model: ") && !line.startsWith("* Model Number") -> {
                s.wpsModel = line.substringAfter("* Model: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Model Number: ") -> {
                s.wpsModelNumber = line.substringAfter("* Model Number: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Serial Number: ") -> {
                s.wpsSerialNumber = line.substringAfter("* Serial Number: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Primary Device Type: ") -> {
                s.wpsDeviceType = line.substringAfter("* Primary Device Type: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Device name: ") -> {
                s.wpsDeviceName = line.substringAfter("* Device name: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* Config methods: ") -> {
                s.wpsConfigMethods = line.substringAfter("* Config methods: ").trim(); true
            }

            s.currentSection == "WPS" && line.startsWith("* RF Bands: ") -> {
                s.wpsRfBands = line.substringAfter("* RF Bands: ").trim(); true
            }

            else -> false
        }
    }

    private fun parseBssRm(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("RM enabled capabilities:") -> {
                s.currentSection = "RM"; s.currentSubSection = "CAPAB"; s.rmCapabilitiesList =
                    mutableListOf("Radio Measurement"); true
            }

            line.startsWith("Capabilities:") && s.currentSection == "RM" && s.currentSubSection == "CAPAB" -> {
                val cap = Regex("""Capabilities:\s*(0x[0-9a-fA-F]+)""").find(line)
                if (cap != null) {
                    s.rmCapabilitiesHex = cap.groupValues[1]
                    val hexVal = cap.groupValues[1].substring(2).toIntOrNull(16) ?: 0
                    s.rmLinkMeasurement = (hexVal and 0x80) != 0
                    s.rmNeighborReport = (hexVal and 0x08) != 0
                    s.rmBeaconPassive = (hexVal and 0x04) != 0
                    s.rmBeaconActive = (hexVal and 0x02) != 0
                    s.rmBeaconTable = (hexVal and 0x01) != 0
                }
                true
            }

            line.trim() == "0x0a" && s.currentSection == "RM" -> true
            line.trim() == "0x00" && s.currentSection == "RM" && s.currentSubSection == "CAPAB" -> true
            line.startsWith(" * Link Measurement") && s.currentSection == "RM" -> {
                s.rmLinkMeasurement = true; s.rmCapabilitiesList.add("Link Measurement"); true
            }

            line.startsWith(" * Neighbor Report") && s.currentSection == "RM" -> {
                s.rmNeighborReport = true; s.rmCapabilitiesList.add("Neighbor Report"); true
            }

            line.startsWith(" * Beacon Passive Measurement") && s.currentSection == "RM" -> {
                s.rmBeaconPassive =
                    true; s.rmCapabilitiesList.add("Beacon Passive Measurement"); true
            }

            line.startsWith(" * Beacon Active Measurement") && s.currentSection == "RM" -> {
                s.rmBeaconActive = true; s.rmCapabilitiesList.add("Beacon Active Measurement"); true
            }

            line.startsWith(" * Beacon Table Measurement") && s.currentSection == "RM" -> {
                s.rmBeaconTable = true; s.rmCapabilitiesList.add("Beacon Table Measurement"); true
            }

            line.startsWith(" * Channel Load") && s.currentSection == "RM" -> {
                s.rmChannelLoad = true; s.rmCapabilitiesList.add("Channel Load"); true
            }

            line.startsWith(" * Statistics Measurement") && s.currentSection == "RM" -> {
                s.rmStatistics = true; s.rmCapabilitiesList.add("Statistics Measurement"); true
            }

            line.startsWith(" * Frame Measurement") && s.currentSection == "RM" -> {
                s.rmFrameMeasurement = true; s.rmCapabilitiesList.add("Frame Measurement"); true
            }

            line.startsWith(" * LCI Measurement") && s.currentSection == "RM" -> {
                s.rmLCI = true; s.rmCapabilitiesList.add("LCI Measurement"); true
            }

            line.startsWith(" * Transmit Stream") && s.currentSection == "RM" -> {
                s.rmTransmitStream =
                    true; s.rmCapabilitiesList.add("Transmit Stream/Category"); true
            }

            line.startsWith(" * FTM Range Report") && s.currentSection == "RM" -> {
                s.ftmRangeReport = true; s.rmCapabilitiesList.add("FTM Range Report"); true
            }

            line.startsWith(" * Civic Location") && s.currentSection == "RM" -> {
                s.rmCivicLocation =
                    true; s.rmCapabilitiesList.add("Civic Location Measurement"); true
            }

            line.startsWith(" * Beacon Measurement Reporting") && s.currentSection == "RM" -> {
                s.rmCapabilitiesList.add("Beacon Measurement Reporting Conditions"); true
            }

            line.startsWith(" * AP Channel Report") && s.currentSection == "RM" -> {
                s.rmCapabilitiesList.add("AP Channel Report"); true
            }

            line.startsWith(" * Noise Histogram") && s.currentSection == "RM" -> {
                s.rmCapabilitiesList.add("Noise Histogram Measurement"); true
            }

            line.startsWith("Triggered") && s.currentSection == "RM" -> {
                s.rmCapabilitiesList.add("Triggered measurements"); true
            }

            line.startsWith("Nonoperating Channel Max Measurement Duration: ") -> {
                val dur = Regex("""(\d+)""").find(line.substringAfter("Duration: "))
                if (dur != null) s.rmNonOpChannelMaxDur = dur.groupValues[1]; true
            }

            line.startsWith("Measurement Pilot Capability: ") -> {
                val pil = Regex("""Capability:\s*(\d+)""").find(line)
                if (pil != null) s.rmMeasurementPilotCap = pil.groupValues[1]; true
            }

            else -> false
        }
    }

    private fun parseBssHt(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("HT capabilities:") -> {
                s.currentSection = "HT"; s.currentSubSection = ""; s.htCapabilities =
                    line.trim(); true
            }

            line.startsWith("Capab") && s.currentSection == "HT" && s.currentSubSection == "" -> {
                val cap = Regex("""Capabilities:\s*(0x[0-9a-fA-F]+)""").find(line)
                if (cap != null) {
                    s.htCapabilitiesCapab = cap.groupValues[1]
                    val hexVal = cap.groupValues[1].substring(2).toIntOrNull(16) ?: 0
                    s.htRxLdpc = (hexVal and 0x01) != 0
                    s.htHt20Ht40 = (hexVal and 0x02) != 0
                    s.htSmPowerSaveDisabled = (hexVal and 0x0C) == 0x0C
                    s.htRxHt20Sgi = (hexVal and 0x10) != 0
                    s.htRxHt40Sgi = (hexVal and 0x20) != 0
                    s.htTxStbc = (hexVal and 0x40) != 0
                    s.htRxStbc1Stream = (hexVal and 0x180) == 0x080
                    s.htNoRxStbc = (hexVal and 0x180) == 0
                    s.htDssCckHt40 = (hexVal and 0x2000) != 0
                    s.htNoDssCckHt40 = (hexVal and 0x2000) == 0
                }
                true
            }

            line.startsWith("Maximum RX AMPDU length") -> {
                val exp = Regex("""exponent:\s*(0x[0-9a-fA-F]+)""").find(line)
                if (exp != null) {
                    s.htAmpduMaxLen = exp.groupValues[1]; s.localAmpduMaxLen = exp.groupValues[1]
                }
                true
            }

            line.startsWith("Minimum RX AMPDU time spacing") -> {
                val spacing = Regex("""(\d+) usec""").find(line)
                if (spacing != null) s.htAmpduMinSpacing = spacing.groupValues[1] + " usec"
                if (Regex("""No restriction""").find(line) != null) s.htAmpduMinSpacing =
                    "No restriction"
                true
            }

            line.startsWith("\t\tHT RX MCS") || line.startsWith("\t\tHT TX/RX MCS") -> {
                s.htMcs = line.trim(); true
            }

            line.startsWith("\t\tHT TX MCS") -> {
                s.htTxMcs = line.trim(); true
            }

            line.startsWith("* primary channel: ") && s.currentSection == "HT" -> {
                s.channel = line.substringAfter("* primary channel: ").trim(); true
            }

            line.startsWith("* secondary channel offset: ") && s.currentSection == "HT" -> {
                s.htSecondaryChannel =
                    line.substringAfter("* secondary channel offset: ").trim(); true
            }

            line.startsWith("* STA channel width: ") && s.currentSection == "HT" -> {
                s.staChannelWidth =
                    line.substringAfter("* STA channel width: ").trim(); s.htChannelWidth =
                    s.staChannelWidth; true
            }

            line.startsWith("* RIFS: ") && s.currentSection == "HT" -> {
                s.rifs = line.substringAfter("* RIFS: ").trim(); true
            }

            line.startsWith("* HT protection: ") && s.currentSection == "HT" -> {
                s.htProtection = line.substringAfter("* HT protection: ").trim(); true
            }

            line.startsWith("* non-GF present: ") && s.currentSection == "HT" -> {
                s.nonGfPresent = line.substringAfter("* non-GF present: ").trim(); true
            }

            line.startsWith("* OBSS non-GF present: ") && s.currentSection == "HT" -> {
                s.obssNonGfPresent = line.substringAfter("* OBSS non-GF present: ").trim(); true
            }

            line.startsWith("* dual beacon: ") && s.currentSection == "HT" -> {
                s.dualBeacon = line.substringAfter("* dual beacon: ").trim(); true
            }

            line.startsWith("* dual CTS protection: ") && s.currentSection == "HT" -> {
                s.dualCtsProtection = line.substringAfter("* dual CTS protection: ").trim(); true
            }

            line.startsWith("* STBC beacon: ") && s.currentSection == "HT" -> {
                s.stbcBeacon = line.substringAfter("* STBC beacon: ").trim(); true
            }

            line.startsWith("* L-SIG TXOP Prot: ") && s.currentSection == "HT" -> {
                s.lsigTxopProtect = line.substringAfter("* L-SIG TXOP Prot: ").trim(); true
            }

            line.startsWith("* PCO active: ") && s.currentSection == "HT" -> {
                s.pcoActive = line.substringAfter("* PCO active: ").trim(); true
            }

            line.startsWith("* PCO phase: ") && s.currentSection == "HT" -> {
                s.pcoPhase = line.substringAfter("* PCO phase: ").trim(); true
            }

            line.startsWith("* passive dwell: ") && s.currentSection == "HT" -> {
                s.obssPassiveDwell = line.substringAfter("* passive dwell: ").trim(); true
            }

            line.startsWith("* active dwell: ") && s.currentSection == "HT" -> {
                s.obssActiveDwell = line.substringAfter("* active dwell: ").trim(); true
            }

            line.startsWith("* channel width trigger scan interval: ") && s.currentSection == "HT" -> {
                s.obssScanInterval =
                    line.substringAfter("* channel width trigger scan interval: ").trim(); true
            }

            line.startsWith("* scan passive total per channel: ") && s.currentSection == "HT" -> {
                s.obssPassiveTotal =
                    line.substringAfter("* scan passive total per channel: ").trim(); true
            }

            line.startsWith("* scan active total per channel: ") && s.currentSection == "HT" -> {
                s.obssActiveTotal =
                    line.substringAfter("* scan active total per channel: ").trim(); true
            }

            line.startsWith("* BSS width channel transition delay factor: ") && s.currentSection == "HT" -> {
                s.obssChannelDelayFactor =
                    line.substringAfter("* BSS width channel transition delay factor: ")
                        .trim(); true
            }

            line.startsWith("* OBSS Scan Activity Threshold: ") && s.currentSection == "HT" -> {
                s.obssScanThreshold =
                    line.substringAfter("* OBSS Scan Activity Threshold: ").trim(); true
            }

            else -> false
        }
    }

    private fun parseBssExtended(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("Extended capabilities:") -> {
                s.currentSection = "EXT"; s.currentSubSection = ""; true
            }

            s.currentSection == "EXT" && line.startsWith("* HT Information Exchange") -> {
                s.extHtInfoExchange = true; s.extCapabilities += "HT Information Exchange\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* TFS") -> {
                s.extTfs = true; s.extCapabilities += "TFS\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* WNM-Sleep Mode") -> {
                s.extWnmSleep = true; s.extCapabilities += "WNM-Sleep Mode\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* TIM Broadcast") -> {
                s.extTimBroadcast = true; s.extCapabilities += "TIM Broadcast\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* BSS Transition") -> {
                s.extBssTransition = true; s.extCapabilities += "BSS Transition\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* Operating Mode Notification") -> {
                s.extOpModeNotification =
                    true; s.extCapabilities += "Operating Mode Notification\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* TWT Responder") -> {
                s.extTwtResponder = true; s.extCapabilities += "TWT Responder\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* Extended Channel Switching") -> {
                s.extEcs = true; s.extCapabilities += "Extended Channel Switching\n"; true
            }

            s.currentSection == "EXT" && line.startsWith("* Reserved") -> {
                s.extCapabilities += line.trim() + "\n"; true
            }

            line.startsWith("802.11u Interworking:") -> {
                s.currentSection = "11U"; s.currentSubSection = ""; s.interworking = true; true
            }

            s.currentSection == "11U" && line.startsWith("Network Options: ") -> {
                s.networkOptions = line.substringAfter("Network Options: ").trim(); true
            }

            s.currentSection == "11U" && line.startsWith("Network Type: ") -> {
                s.networkType = line.substringAfter("Network Type: ").trim(); true
            }

            line.startsWith("802.11u Advertisement:") -> {
                s.currentSection = "11U_ADVD"; s.currentSubSection = ""; true
            }

            s.currentSection == "11U_ADVD" && line.startsWith("Query Response Info:") -> true
            s.currentSection == "11U_ADVD" && line.startsWith("Query Response Length Limit: ") -> {
                s.queryResponseLength =
                    line.substringAfter("* Query Response Length Limit: ").trim(); true
            }

            s.currentSection == "11U_ADVD" && line.startsWith("* Query Response Length Limit: ") -> {
                s.queryResponseLength =
                    line.substringAfter("* Query Response Length Limit: ").trim(); true
            }

            s.currentSection == "11U_ADVD" && line.startsWith("* ANQP") -> {
                s.anqpAvailable = true; true
            }

            else -> false
        }
    }

    private fun parseBssHe(rawLine: String, s: ParsedBss): Boolean {
        val line = rawLine.trim()
        return when {
            line.startsWith("HE capabilities:") -> {
                s.currentSection = "HE"; s.currentSubSection = ""; s.heCapabilities =
                    line.trim(); true
            }

            line.startsWith("HE MAC Capabilities") && s.currentSection == "HE" -> {
                s.currentSubSection = "MAC"
                s.currentSubIndent = leadingIndent(rawLine)
                s.heMacCapabilities = line.trim()
                true
            }

            line.startsWith("HE PHY Capabilities") && s.currentSection == "HE" -> {
                s.currentSubSection = "PHY"
                s.currentSubIndent = leadingIndent(rawLine)
                s.hePhyCapabilities = line.trim()
                true
            }

            s.currentSection == "HE" && s.currentSubSection == "MAC" && leadingIndent(rawLine) > s.currentSubIndent -> {
                parseHeMacFlag(line, s)
                true
            }

            s.currentSection == "HE" && s.currentSubSection == "PHY" && leadingIndent(rawLine) > s.currentSubIndent -> {
                parseHePhyFlag(line, s)
                true
            }

            line.startsWith("HE RX MCS") && s.currentSection == "HE" -> {
                s.currentSubSection = "RX_MCS"; s.heRcMcs = line.trim(); true
            }

            line.startsWith("HE TX MCS") && s.currentSection == "HE" -> {
                s.currentSubSection = "TX_MCS"; s.heTcMcs = line.trim(); true
            }

            s.currentSection == "HE" && (s.currentSubSection == "RX_MCS" || s.currentSubSection == "TX_MCS") && line.contains(
                "streams:"
            ) -> {
                if (s.currentSubSection == "RX_MCS") s.heRcMcs += "\n" + line.trim() else s.heTcMcs += "\n" + line.trim()
                true
            }

            line.startsWith("PPE Threshold") && s.currentSection == "HE" -> {
                s.currentSubSection = ""; s.hePpeThreshold = line.trim(); true
            }

            else -> false
        }
    }

    private fun parseHeMacFlag(line: String, s: ParsedBss) {
        when {
            line.startsWith("+HTC HE") -> s.heMacHtcHe = true
            line.startsWith("BSR") -> s.heMacBsr = true
            line.startsWith("OM Control") -> s.heMacOmControl = true
            line.startsWith("Maximum A-MPDU Length Exponent:") -> s.heMacMaxAmpduExp =
                line.substringAfter("Exponent: ").trim()

            line.startsWith("A-MSDU in A-MPDU") -> s.heMacAmsduInAmpdu = true
            line.startsWith("OM Control UL MU Data Disable RX") -> s.heMacOmUlMuDataDisableRx = true
            line.startsWith("Ack-Enabled Aggregation") -> s.heMacAckEnabledAggregation = true
            line.startsWith("TWT Responder") -> s.heMacTwtResponder = true
            line.startsWith("Dynamic BA") -> s.heMacDynamicBaFragmentation =
                line.substringAfter(": ").trim().ifEmpty { line.substringAfter("Level ").trim() }

            line.startsWith("Minimum Payload size") -> s.heMacMinPayload128 = true
            line.startsWith("RX Control Frame to MultiBSS") -> s.heMacRxControlFrameMultiBss = true
            line.startsWith("BQR") -> s.heMacBqr = true
        }
    }

    private fun parseHePhyFlag(line: String, s: ParsedBss) {
        when {
            line.startsWith("HE40/HE80/5GHz") -> s.hePhyHe40He80 = true
            line.startsWith("HE40/2.4GHz") -> s.hePhyHe40 = true
            line.startsWith("242 tone RUs") -> s.hePhy242ToneRu = true
            line.startsWith("Device Class:") -> s.hePhyDeviceClass =
                line.substringAfter("Device Class: ").trim()

            line.startsWith("LDPC Coding in Payload") -> s.hePhyLdpcPayload = true
            line.startsWith("NDP with 4x HE-LTF") -> s.hePhyNd4Ltf32Gi = true
            line.startsWith("Rx HE MU PPDU") -> s.hePhyRxMuPpduNonAp = true
            line.startsWith("STBC Tx <= 80MHz") -> s.hePhyStbcTx80 = true
            line.startsWith("STBC Rx <= 80MHz") -> s.hePhyStbcRx80 = true
            line.startsWith("Full Bandwidth UL MU-MIMO") -> s.hePhyFullBwUlMuMimo = true
            line.startsWith("Partial Bandwidth UL MU-MIMO") -> s.hePhyPartialBwUlMuMimo = true
            line.startsWith("DCM Max Constellation:") -> s.hePhyDcmMaxConstellation =
                line.substringAfter(": ").trim()

            line.startsWith("SU Beamformer") -> s.hePhySuBeamformer = true
            line.startsWith("SU Beamformee") -> s.hePhySuBeamformee = true
            line.startsWith("MU Beamformer") -> s.hePhyMuBeamformer = true
            line.startsWith("Beamformee STS <= 80MHz:") -> s.hePhyBeamformeeSts80 =
                line.substringAfter(": ").trim()

            line.startsWith("Beamformee STS > 80MHz:") -> s.hePhyBeamformeeSts80Plus =
                line.substringAfter(": ").trim()

            line.startsWith("Sounding Dimensions <= 80MHz:") -> s.hePhySoundingDims80 =
                line.substringAfter(": ").trim()

            line.startsWith("Sounding Dimensions > 80MHz:") -> s.hePhySoundingDims80Plus =
                line.substringAfter(": ").trim()

            line.startsWith("Ng = ") -> s.hePhyNg =
                line.substringAfter("Ng = ").trim().substringBefore(" SU")

            line.startsWith("Codebook Size SU Feedback") -> s.hePhyCodebookSu = true
            line.startsWith("Triggered SU Beamforming Feedback") -> s.hePhyTriggeredSuBf = true
            line.startsWith("Triggered CQI Feedback") -> s.hePhyTriggeredCqi = true
            line.startsWith("PPE Threshold Present") -> s.hePhyPpePresent = true
            line.startsWith("Max NC:") -> s.hePhyMaxNc = line.substringAfter(": ").trim()
            line.startsWith("TX 1024-QAM") -> s.hePhyTx1024Qam = true
            line.startsWith("RX 1024-QAM") -> s.hePhyRx1024Qam = true
            line.startsWith("Partial Bandwidth Extended Range") -> s.hePhyPartialBwExtendedRange =
                true

            line.startsWith("20MHz in 40MHz") -> s.hePhy20In40Mhz = true
            line.startsWith("HE ER SU PPDU 4x HE-LTF") -> s.hePhyErSuPpdu4Ltf = true
            line.startsWith("HE ER SU PPDU 1x HE-LTF") -> s.hePhyErSuPpdu1Ltf = true
            line.startsWith("HE SU PPDU & HE PPDU 4x HE-LTF") -> s.hePhyTxPpdu4Ltf08Gi = true
        }
    }

    private fun leadingIndent(line: String): Int {
        var count = 0
        for (ch in line) {
            if (ch == ' ' || ch == '\t') count++ else break
        }
        return count
    }

    private fun parseBssVht(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("VHT capabilities:") -> {
                s.currentSection = "VHT"; s.currentSubSection = "CAPAB"; true
            }

            line.startsWith("VHT Capabilities") && s.currentSection == "VHT" -> {
                s.vhtCapabilities = line.trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("Max MPDU length:") -> {
                s.vhtMaxMpdu = line.substringAfter("Max MPDU length: ").trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("Supported Channel Width:") -> {
                s.vhtSupportedChannelWidth =
                    line.substringAfter("Supported Channel Width: ").trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("VHT RX MCS set:") -> {
                s.currentSubSection = "RX_MCS"; s.vhtRxMcs = line.trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("VHT TX MCS set:") -> {
                s.currentSubSection = "TX_MCS"; s.vhtTxMcs = line.trim(); true
            }

            s.currentSection == "VHT" && (s.currentSubSection == "RX_MCS" || s.currentSubSection == "TX_MCS") && line.contains(
                "streams:"
            ) -> {
                if (s.currentSubSection == "RX_MCS") s.vhtRxMcs += "\n" + line.trim() else s.vhtTxMcs += "\n" + line.trim()
                true
            }

            s.currentSection == "VHT" && line.startsWith("VHT RX highest supported:") -> {
                s.vhtRxHighestSupported =
                    line.substringAfter("VHT RX highest supported: ").trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("VHT TX highest supported:") -> {
                s.vhtTxHighestSupported =
                    line.substringAfter("VHT TX highest supported: ").trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("VHT extended NSS:") -> {
                s.vhtExtendedNss = line.substringAfter("VHT extended NSS: ").trim(); true
            }

            s.currentSection == "VHT" && line.startsWith("VHT operation:") -> {
                s.currentSection = "VHT_OP"; s.currentSubSection = ""; true
            }

            s.currentSection == "VHT" && line.isNotBlank() -> {
                s.vhtFeatureLines.add(line.trim()); true
            }

            line.startsWith("VHT operation:") -> {
                s.currentSection = "VHT_OP"; s.currentSubSection = ""; true
            }

            s.currentSection == "VHT_OP" && line.startsWith("* channel width:") -> {
                s.vhtOpChannelWidth = line.substringAfter("* channel width: ").trim(); true
            }

            s.currentSection == "VHT_OP" && line.startsWith("* center freq segment 1:") -> {
                s.vhtOpCenterFreq1 = line.substringAfter("* center freq segment 1: ").trim(); true
            }

            s.currentSection == "VHT_OP" && line.startsWith("* center freq segment 2:") -> {
                s.vhtOpCenterFreq2 = line.substringAfter("* center freq segment 2: ").trim(); true
            }

            s.currentSection == "VHT_OP" && line.startsWith("* VHT basic MCS set:") -> {
                s.vhtOpBasicMcs = line.substringAfter("* VHT basic MCS set: ").trim(); true
            }

            else -> false
        }
    }

    private fun parseBssHeOp(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("HE Operation:") -> {
                s.currentSection = "HE_OP"; s.currentSubSection = ""; true
            }

            line.startsWith("HE Operation Parameters") && s.currentSection == "HE_OP" -> {
                s.currentSubSection = "PARAMS"; s.heOpParameters = line.trim(); true
            }

            s.currentSection == "HE_OP" && line.startsWith("BSS Color:") -> {
                s.currentSubSection = ""; s.heOpBssColor =
                    line.substringAfter("BSS Color: ").trim(); true
            }

            s.currentSection == "HE_OP" && line.startsWith("Basic HE-MCS NSS Set:") -> {
                s.currentSubSection = "BASIC_MCS"; s.heOpBasicMcsSet =
                    line.substringAfter("Basic HE-MCS NSS Set: ").trim(); true
            }

            s.currentSection == "HE_OP" && line.startsWith("Max Co-Hosted BSSID:") -> {
                s.currentSubSection = ""; s.heOpMaxCoHostedBssid =
                    line.substringAfter("Max Co-Hosted BSSID: ").trim(); true
            }

            s.currentSection == "HE_OP" && line.startsWith("VHT Operation Info:") -> {
                s.currentSubSection = ""; s.heOpVhtInfo =
                    line.substringAfter("VHT Operation Info: ").trim(); true
            }

            s.currentSection == "HE_OP" && s.currentSubSection == "PARAMS" && line.isNotBlank() -> {
                when {
                    line.startsWith("Default PE Duration:") -> s.heOpDefaultPeDuration =
                        line.substringAfter(": ").trim()

                    line.startsWith("TXOP Duration RTS Threshold:") -> s.heOpTxopDurationRts =
                        line.substringAfter(": ").trim()

                    line.startsWith("Co-Hosted BSS") -> s.heOpCoHostedBss = true
                    line.startsWith("ER SU Disable") -> s.heOpErSuDisable = true
                    line.startsWith("VHT Operation Information Present") -> s.heOpVhtInfoPresent =
                        true
                }
                true
            }

            s.currentSection == "HE_OP" && s.currentSubSection == "BASIC_MCS" && line.contains("streams:") -> {
                s.heOpBasicMcsSet += "\n" + line.trim()
                true
            }

            else -> false
        }
    }

    private fun parseBssWmm(line: String, s: ParsedBss): Boolean {
        return when {
            line.startsWith("WMM:") -> {
                s.currentSection = "WMM"; s.currentSubSection = ""; s.wmmPresent = true;
                val v =
                    Regex("""Parameter version\s*(\d+)""").find(line); if (v != null) s.wmmParams =
                    "Version " + v.groupValues[1]; true
            }

            (s.currentSection == "WMM" && line.startsWith("* BE:")) || (s.currentSection == "WMM" && line.startsWith(
                "* BK:"
            )) || (s.currentSection == "WMM" && line.startsWith("* VI:")) || (s.currentSection == "WMM" && line.startsWith(
                "* VO:"
            )) -> {
                s.wmmParams += "\n" + line.trim(); true
            }

            else -> false
        }
    }

    private fun detectSecurityType(
        rsnVersion: String,
        authSuite: String,
        wpaVersion: String,
        wpaAuthSuite: String,
        groupCipher: String,
        pairwiseCipher: String,
        capabilities: String
    ): String {
        if (authSuite.contains("SAE")) {
            return "WPA3-SAE"
        }
        if (rsnVersion == "2") {
            return "WPA3"
        }
        if (rsnVersion == "1" || rsnVersion != "0") {
            return "WPA2"
        }
        if (wpaVersion != "0" || wpaAuthSuite.isNotEmpty()) {
            return "WPA"
        }
        if (capabilities.contains("RSN")) {
            return "WPA2"
        }
        if (capabilities.contains("WPA3")) {
            return "WPA3"
        }
        if (capabilities.contains("WPA")) {
            return "WPA"
        }
        if (capabilities.contains("Privacy")) {
            return "WPA2"
        }
        return "OPEN"
    }

    private fun detectBand(frequency: String, capabilities: String): String {
        val freq = frequency.toIntOrNull()
        return when {
            freq != null && freq >= 5724 -> "6 GHz"
            freq != null && freq >= 4920 -> "5 GHz"
            freq != null && freq >= 2400 && freq <= 2484 -> "2.4 GHz"
            freq != null && freq >= 5000 -> "5 GHz"
            capabilities.contains("HT") -> "2.4 GHz"
            else -> ""
        }
    }

    internal fun parseLinkInfo(output: String): IwLinkInfo {
        val lines = output.lines()

        var connected = false
        var ssid = ""
        var bssid = ""
        var frequency = ""
        var txBitrate = ""
        var rxBitrate = ""

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Connected to ") -> {
                    connected = true
                    bssid = trimmed.substring(13).split(" ")[0]
                }

                trimmed.startsWith("SSID: ") -> {
                    ssid = trimmed.substring(6).trim()
                }

                trimmed.startsWith("freq: ") -> {
                    frequency = trimmed.substring(6).trim() + " MHz"
                }

                trimmed.startsWith("tx bitrate: ") -> {
                    txBitrate = trimmed.substring(12).trim()
                }

                trimmed.startsWith("rx bitrate: ") -> {
                    rxBitrate = trimmed.substring(12).trim()
                }
            }
        }

        return IwLinkInfo(
            connected = connected,
            ssid = ssid,
            bssid = bssid,
            frequency = frequency,
            txBitrate = txBitrate,
            rxBitrate = rxBitrate
        )
    }

    private fun parseDeviceInfo(output: String): IwDeviceInfo {
        val lines = output.lines()

        var wiphy = ""
        val bands = mutableListOf<String>()
        val supportedCiphers = mutableListOf<String>()
        var maxScanSSIDs = ""

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Wiphy ") -> {
                    wiphy = trimmed.substring(6).trim()
                }

                trimmed.startsWith("Band ") -> {
                    bands.add(trimmed.substring(5).trim())
                }

                trimmed.contains("CCMP") -> {
                    supportedCiphers.add("CCMP")
                }

                trimmed.contains("TKIP") -> {
                    supportedCiphers.add("TKIP")
                }

                trimmed.startsWith("max # scan SSIDs: ") -> {
                    maxScanSSIDs = trimmed.substring(18).trim()
                }
            }
        }

        return IwDeviceInfo(
            wiphy = wiphy,
            bands = bands.distinct(),
            supportedCiphers = supportedCiphers.distinct(),
            maxScanSSIDs = maxScanSSIDs
        )
    }
}
