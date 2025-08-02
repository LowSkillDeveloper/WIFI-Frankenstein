package com.lsd.wififrankenstein.ui.iwscanner

import android.content.Context
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class IwScannerHelper(private val context: Context) {

    companion object {
        private const val TAG = "IwScannerHelper"
    }

    private val binaryDir = context.filesDir.absolutePath
    private val iwBinary = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "iw" else "iw-32"

    interface IwScannerCallbacks {
        fun onStateChanged(state: IwScanState)
        fun onLinkInfoUpdated(linkInfo: IwLinkInfo)
        fun onDeviceInfoUpdated(deviceInfo: IwDeviceInfo)
        fun onInterfacesUpdated(interfaces: List<IwInterface>)
    }

    fun checkRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot && shell.isAlive
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    suspend fun copyBinariesFromAssets(): Boolean = withContext(Dispatchers.IO) {
        try {
            val binaries = listOf("iw", "iw-32")

            binaries.forEach { fileName ->
                if (copyAssetToInternalStorage(fileName, fileName)) {
                    Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying iw binaries", e)
            false
        }
    }

    private fun copyAssetToInternalStorage(assetName: String, fileName: String): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $assetName", e)
            false
        }
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev"
            val result = Shell.cmd(command).exec()

            if (result.isSuccess && result.out.isNotEmpty()) {
                parseInterfacesList(result.out.joinToString("\n"))
            } else {
                listOf(IwInterface("wlan0"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interfaces", e)
            listOf(IwInterface("wlan0"))
        }
    }

    private fun parseInterfacesList(output: String): List<IwInterface> {
        val interfaces = mutableListOf<IwInterface>()
        val lines = output.lines()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let { interfaces.add(IwInterface(it, currentType, currentAddr)) }
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

        currentInterface?.let { interfaces.add(IwInterface(it, currentType, currentAddr)) }

        return if (interfaces.isEmpty()) listOf(IwInterface("wlan0")) else interfaces
    }

    suspend fun getLinkInfo(interfaceName: String = "wlan0"): IwLinkInfo = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev $interfaceName link"
            val result = Shell.cmd(command).exec()

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
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary list"
            val result = Shell.cmd(command).exec()

            if (result.isSuccess && result.out.isNotEmpty()) {
                parseDeviceInfo(result.out.joinToString("\n"))
            } else {
                IwDeviceInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            IwDeviceInfo()
        }
    }

    suspend fun scanNetworks(interfaceName: String = "wlan0"): List<IwNetworkInfo> = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev $interfaceName scan"
            val result = Shell.cmd(command).exec()

            if (result.isSuccess && result.out.isNotEmpty()) {
                parseScanResults(result.out.joinToString("\n"))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning networks", e)
            emptyList()
        }
    }

    private fun parseLinkInfo(output: String): IwLinkInfo {
        val lines = output.lines()
        var connected = false
        var ssid = ""
        var bssid = ""
        var frequency = ""
        var signal = ""
        var txBitrate = ""

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Connected to") -> {
                    connected = true
                    val bssidMatch = "Connected to ([0-9a-fA-F:]{17})".toRegex().find(trimmed)
                    bssid = bssidMatch?.groupValues?.get(1) ?: ""
                }
                trimmed.startsWith("SSID:") -> {
                    ssid = trimmed.substring(5).trim()
                }
                trimmed.startsWith("freq:") -> {
                    frequency = trimmed.substring(5).trim()
                }
                trimmed.startsWith("signal:") -> {
                    signal = trimmed.substring(7).trim()
                }
                trimmed.startsWith("tx bitrate:") -> {
                    txBitrate = trimmed.substring(11).trim()
                }
                trimmed == "Not connected." -> {
                    connected = false
                }
            }
        }

        return IwLinkInfo(connected, ssid, bssid, frequency, signal, txBitrate)
    }

    private fun parseDeviceInfo(output: String): IwDeviceInfo {
        val lines = output.lines()
        var wiphy = ""
        val bands = mutableListOf<IwBand>()
        var currentBand: IwBand? = null
        var inBand = false
        var capabilities: IwCapabilities? = null

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Wiphy") -> {
                    wiphy = trimmed
                }
                trimmed.startsWith("Band") -> {
                    currentBand?.let { bands.add(it) }
                    currentBand = IwBand(bandNumber = trimmed)
                    inBand = true
                }
                trimmed.startsWith("max # scan SSIDs:") -> {
                    inBand = false
                    currentBand?.let { bands.add(it) }
                    val maxScanSSIDs = trimmed.substring(17).trim()
                    capabilities = IwCapabilities(maxScanSSIDs = maxScanSSIDs)
                }
            }
        }

        currentBand?.let { bands.add(it) }

        return IwDeviceInfo(wiphy, bands, capabilities)
    }

    private fun parseScanResults(output: String): List<IwNetworkInfo> {
        val networks = mutableListOf<IwNetworkInfo>()
        val bssBlocks = output.split("BSS ").drop(1)

        bssBlocks.forEach { block ->
            try {
                val network = parseBssBlock(block)
                if (network.bssid.isNotEmpty()) {
                    networks.add(network)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing BSS block", e)
            }
        }

        return networks.sortedByDescending { parseSignalStrength(it.signal) }
    }

    private fun parseBssBlock(block: String): IwNetworkInfo {
        val lines = block.lines()
        var bssid = ""
        var ssid = ""
        var frequency = ""
        var channel = ""
        var signal = ""
        var capability = ""
        var lastSeen = ""
        var isAssociated = false
        var wpa = ""
        var rsn = ""
        var wps = ""
        var htCapabilities = ""
        var vhtCapabilities = ""
        var heCapabilities = ""
        val supportedRates = mutableListOf<String>()
        val extendedRates = mutableListOf<String>()
        var country = ""

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                line.startsWith("BSS") || (bssid.isEmpty() && trimmed.contains("(on wlan0)")) -> {
                    val bssidMatch = "([0-9a-fA-F:]{17})".toRegex().find(trimmed)
                    bssid = bssidMatch?.groupValues?.get(1) ?: ""
                    isAssociated = trimmed.contains("-- associated")
                }
                trimmed.startsWith("SSID:") -> {
                    ssid = trimmed.substring(5).trim()
                }
                trimmed.startsWith("freq:") -> {
                    frequency = trimmed.substring(5).trim()
                    channel = getChannelFromFrequency(frequency)
                }
                trimmed.startsWith("signal:") -> {
                    signal = trimmed.substring(7).trim()
                }
                trimmed.startsWith("capability:") -> {
                    capability = trimmed.substring(11).trim()
                }
                trimmed.startsWith("last seen:") -> {
                    lastSeen = trimmed.substring(10).trim()
                }
                trimmed.startsWith("Supported rates:") -> {
                    val ratesString = trimmed.substring(16).trim()
                    supportedRates.addAll(ratesString.split(" ").filter { it.isNotBlank() })
                }
                trimmed.startsWith("Extended supported rates:") -> {
                    val ratesString = trimmed.substring(25).trim()
                    extendedRates.addAll(ratesString.split(" ").filter { it.isNotBlank() })
                }
                trimmed.startsWith("Country:") -> {
                    country = trimmed.substring(8).trim()
                }
                trimmed.startsWith("WPA:") -> {
                    wpa = extractMultiLineInfo(lines, lines.indexOf(line))
                }
                trimmed.startsWith("RSN:") -> {
                    rsn = extractMultiLineInfo(lines, lines.indexOf(line))
                }
                trimmed.startsWith("WPS:") -> {
                    wps = extractMultiLineInfo(lines, lines.indexOf(line))
                }
                trimmed.startsWith("HT capabilities:") -> {
                    htCapabilities = extractMultiLineInfo(lines, lines.indexOf(line))
                }
                trimmed.startsWith("VHT capabilities:") -> {
                    vhtCapabilities = extractMultiLineInfo(lines, lines.indexOf(line))
                }
                trimmed.startsWith("HE capabilities:") -> {
                    heCapabilities = extractMultiLineInfo(lines, lines.indexOf(line))
                }
            }
        }

        return IwNetworkInfo(
            bssid = bssid,
            ssid = ssid,
            frequency = frequency,
            channel = channel,
            signal = signal,
            capability = capability,
            lastSeen = lastSeen,
            isAssociated = isAssociated,
            security = IwSecurityInfo(wpa, rsn, wps),
            capabilities = IwCapabilitiesInfo(htCapabilities, vhtCapabilities, heCapabilities),
            supportedRates = supportedRates,
            extendedRates = extendedRates,
            country = country,
            rawData = block
        )
    }

    private fun extractMultiLineInfo(lines: List<String>, startIndex: Int): String {
        val result = mutableListOf<String>()
        result.add(lines[startIndex].trim())

        for (i in (startIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.startsWith("        ") || line.startsWith("\t\t")) {
                result.add(line.trim())
            } else if (line.trim().isNotEmpty() && !line.startsWith(" ")) {
                break
            }
        }

        return result.joinToString("\n")
    }

    private fun getChannelFromFrequency(freq: String): String {
        return try {
            val frequency = freq.toIntOrNull() ?: return ""
            when {
                frequency in 2412..2484 -> ((frequency - 2412) / 5 + 1).toString()
                frequency in 5000..5999 -> ((frequency - 5000) / 5).toString()
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseSignalStrength(signal: String): Int {
        return try {
            val dbmMatch = "(-?\\d+)".toRegex().find(signal)
            dbmMatch?.groupValues?.get(1)?.toInt() ?: -100
        } catch (e: Exception) {
            -100
        }
    }
}