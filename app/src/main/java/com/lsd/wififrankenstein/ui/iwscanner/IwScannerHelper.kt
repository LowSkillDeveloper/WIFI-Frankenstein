package com.lsd.wififrankenstein.ui.iwscanner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.WifiInterfaceManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IwScannerHelper(private val context: Context) {

    companion object {
        private const val TAG = "IwScannerHelper"
    }

    private val wifiInterfaceManager = WifiInterfaceManager(context)

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
            Log.d(TAG, "Checking root access...")
            val shell = Shell.getShell()
            val hasRoot = shell.isRoot && shell.isAlive
            Log.d(TAG, "Root access check: isRoot=${shell.isRoot}, isAlive=${shell.isAlive}, result=$hasRoot")
            hasRoot
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    suspend fun copyBinariesFromAssets(): Boolean = wifiInterfaceManager.copyIwBinariesFromAssets()

    suspend fun getAvailableInterfaces(): List<IwInterface> = wifiInterfaceManager.getAvailableInterfaces()

    suspend fun getLinkInfo(interfaceName: String = "wlan0"): IwLinkInfo = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev $interfaceName link"
            Log.d(TAG, "Executing command: $command")

            val result = Shell.cmd(command).exec()
            Log.d(TAG, "Command exit code: ${result.code}")
            Log.d(TAG, "Command success: ${result.isSuccess}")
            Log.d(TAG, "Command output lines: ${result.out.size}")

            result.out.forEachIndexed { index, line ->
                Log.d(TAG, "OUT[$index]: $line")
            }

            if (result.err.isNotEmpty()) {
                Log.w(TAG, "Command stderr lines: ${result.err.size}")
                result.err.forEachIndexed { index, line ->
                    Log.w(TAG, "ERR[$index]: $line")
                }
            }

            if (result.isSuccess && result.out.isNotEmpty()) {
                val linkInfo = parseLinkInfo(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed link info: connected=${linkInfo.connected}, ssid=${linkInfo.ssid}, bssid=${linkInfo.bssid}")
                linkInfo
            } else {
                Log.w(TAG, "Command failed or no output, returning empty link info")
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
            Log.d(TAG, "Executing command: $command")

            val result = Shell.cmd(command).exec()
            Log.d(TAG, "Command exit code: ${result.code}")
            Log.d(TAG, "Command success: ${result.isSuccess}")
            Log.d(TAG, "Command output lines: ${result.out.size}")

            result.out.forEachIndexed { index, line ->
                Log.d(TAG, "OUT[$index]: $line")
            }

            if (result.err.isNotEmpty()) {
                Log.w(TAG, "Command stderr lines: ${result.err.size}")
                result.err.forEachIndexed { index, line ->
                    Log.w(TAG, "ERR[$index]: $line")
                }
            }

            if (result.isSuccess && result.out.isNotEmpty()) {
                val deviceInfo = parseDeviceInfo(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed device info: wiphy=${deviceInfo.wiphy}, bands=${deviceInfo.bands.size}")
                deviceInfo
            } else {
                Log.w(TAG, "Command failed or no output, returning empty device info")
                IwDeviceInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device info", e)
            IwDeviceInfo()
        }
    }

    @SuppressLint("LogTagMismatch")
    suspend fun scanNetworks(interfaceName: String = "wlan0"): List<IwNetworkInfo> = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev $interfaceName scan"
            Log.d(TAG, "Executing command: $command")

            val result = Shell.cmd(command).exec()
            Log.d(TAG, "Command exit code: ${result.code}")
            Log.d(TAG, "Command success: ${result.isSuccess}")
            Log.d(TAG, "Command output lines: ${result.out.size}")
            Log.d(TAG, "Command output total chars: ${result.out.joinToString("\n").length}")

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                result.out.forEachIndexed { index, line ->
                    Log.v(TAG, "OUT[$index]: $line")
                }
            } else {
                Log.d(TAG, "First 10 output lines:")
                result.out.take(10).forEachIndexed { index, line ->
                    Log.d(TAG, "OUT[$index]: $line")
                }
                if (result.out.size > 10) {
                    Log.d(TAG, "... and ${result.out.size - 10} more lines (enable VERBOSE logging to see all)")
                }
            }

            if (result.err.isNotEmpty()) {
                Log.w(TAG, "Command stderr lines: ${result.err.size}")
                result.err.forEachIndexed { index, line ->
                    Log.w(TAG, "ERR[$index]: $line")
                }
            }

            if (result.isSuccess && result.out.isNotEmpty()) {
                val networks = parseScanResults(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed ${networks.size} networks")
                networks.forEachIndexed { index, network ->
                    Log.d(TAG, "Network[$index]: SSID='${network.ssid}' BSSID=${network.bssid} Signal=${network.signal} Freq=${network.frequency}")
                }
                networks
            } else {
                Log.w(TAG, "Command failed or no output, returning empty list")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning networks", e)
            emptyList()
        }
    }

    private fun parseLinkInfo(output: String): IwLinkInfo {
        Log.d(TAG, "Parsing link info, input length: ${output.length} chars")

        val lines = output.lines()
        var connected = false
        var ssid = ""
        var bssid = ""
        var frequency = ""
        var signal = ""
        var txBitrate = ""

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            Log.v(TAG, "Link parse line $index: $trimmed")

            when {
                trimmed.startsWith("Connected to") -> {
                    connected = true
                    val bssidMatch = "Connected to ([0-9a-fA-F:]{17})".toRegex().find(trimmed)
                    bssid = bssidMatch?.groupValues?.get(1) ?: ""
                    Log.d(TAG, "Found connection: connected=$connected, bssid=$bssid")
                }
                trimmed.startsWith("SSID:") -> {
                    ssid = trimmed.substring(5).trim()
                    Log.d(TAG, "Found SSID: $ssid")
                }
                trimmed.startsWith("freq:") -> {
                    frequency = trimmed.substring(5).trim()
                    Log.d(TAG, "Found frequency: $frequency")
                }
                trimmed.startsWith("signal:") -> {
                    signal = trimmed.substring(7).trim()
                    Log.d(TAG, "Found signal: $signal")
                }
                trimmed.startsWith("tx bitrate:") -> {
                    txBitrate = trimmed.substring(11).trim()
                    Log.d(TAG, "Found tx bitrate: $txBitrate")
                }
                trimmed == "Not connected." -> {
                    connected = false
                    Log.d(TAG, "Found not connected status")
                }
            }
        }

        val linkInfo = IwLinkInfo(connected, ssid, bssid, frequency, signal, txBitrate)
        Log.d(TAG, "Parsed link info: $linkInfo")
        return linkInfo
    }

    private fun parseDeviceInfo(output: String): IwDeviceInfo {
        Log.d(TAG, "Parsing device info, input length: ${output.length} chars")

        val lines = output.lines()
        var wiphy = ""
        val bands = mutableListOf<IwBand>()
        var currentBand: IwBand? = null
        var inBand = false
        var inFrequencies = false
        var inBitrates = false
        var inCommands = false
        var inCiphers = false
        var inInterfaceModes = false

        var wiphyIndex = ""
        var maxScanSSIDs = ""
        var maxScanIEsLength = ""
        var maxSchedScanSSIDs = ""
        var maxMatchSets = ""
        var retryShortLimit = ""
        var retryLongLimit = ""
        var coverageClass = ""
        var supportsTDLS = false
        val supportedCiphers = mutableListOf<String>()
        var availableAntennas = ""
        val supportedInterfaceModes = mutableListOf<String>()
        val supportedCommands = mutableListOf<String>()
        val supportedTxFrameTypes = mutableListOf<String>()
        val supportedRxFrameTypes = mutableListOf<String>()
        val supportedExtendedFeatures = mutableListOf<String>()
        val htCapabilityOverrides = mutableListOf<String>()
        var maxScanPlans = ""
        var maxScanPlanInterval = ""
        var maxScanPlanIterations = ""

        var currentBandCapabilities = IwBandCapabilities()
        val currentFrequencies = mutableListOf<IwFrequency>()
        val currentBitrates = mutableListOf<IwBitrate>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            when {
                trimmed.startsWith("Wiphy") -> {
                    wiphy = trimmed
                    Log.d(TAG, "Found wiphy: $wiphy")
                }

                trimmed.startsWith("wiphy index:") -> {
                    wiphyIndex = trimmed.substringAfter(":").trim()
                }

                trimmed.startsWith("max # scan SSIDs:") -> {
                    inBand = false
                    currentBand?.let {
                        bands.add(it.copy(
                            capabilities = currentBandCapabilities,
                            frequencies = currentFrequencies.toList(),
                            bitrates = currentBitrates.toList()
                        ))
                    }
                    maxScanSSIDs = trimmed.substringAfter(":").trim()
                }

                trimmed.startsWith("max scan IEs length:") -> {
                    maxScanIEsLength = trimmed.substringAfter(":").trim()
                }

                trimmed.contains("supports T-DLS") -> {
                    supportsTDLS = true
                }

                trimmed == "Supported Ciphers:" -> {
                    inCiphers = true
                    inCommands = false
                    inInterfaceModes = false
                }

                trimmed.startsWith("Available Antennas:") -> {
                    inCiphers = false
                    availableAntennas = trimmed.substringAfter(":").trim()
                }

                trimmed == "Supported interface modes:" -> {
                    inInterfaceModes = true
                    inCiphers = false
                    inCommands = false
                }

                trimmed.startsWith("Band") && trimmed.contains(":") -> {
                    currentBand?.let {
                        bands.add(it.copy(
                            capabilities = currentBandCapabilities,
                            frequencies = currentFrequencies.toList(),
                            bitrates = currentBitrates.toList()
                        ))
                    }

                    currentBand = IwBand(bandNumber = trimmed)
                    currentBandCapabilities = IwBandCapabilities()
                    currentFrequencies.clear()
                    currentBitrates.clear()
                    inBand = true
                    inFrequencies = false
                    inBitrates = false
                    inInterfaceModes = false
                    inCiphers = false
                    inCommands = false
                }

                inBand && trimmed.startsWith("Capabilities:") -> {
                    val capValue = trimmed.substringAfter(":").trim()
                    val capDetails = mutableListOf<String>()

                    var i = index + 1
                    while (i < lines.size && (lines[i].startsWith("\t\t\t") || lines[i].startsWith("                "))) {
                        capDetails.add(lines[i].trim())
                        i++
                    }

                    currentBandCapabilities = currentBandCapabilities.copy(
                        value = capValue,
                        htSupport = capDetails
                    )
                }

                inBand && trimmed.startsWith("Maximum RX AMPDU length") -> {
                    currentBandCapabilities = currentBandCapabilities.copy(
                        maxAmpduLength = trimmed
                    )
                }

                inBand && trimmed.startsWith("Minimum RX AMPDU time spacing:") -> {
                    currentBandCapabilities = currentBandCapabilities.copy(
                        minAmpduTimeSpacing = trimmed
                    )
                }

                inBand && (trimmed.startsWith("HT TX/RX MCS rate indexes") || trimmed.startsWith("HT RX MCS rate indexes")) -> {
                    currentBandCapabilities = currentBandCapabilities.copy(
                        htMcsRateIndexes = trimmed
                    )
                }

                inBand && trimmed == "Frequencies:" -> {
                    inFrequencies = true
                    inBitrates = false
                }

                inBand && trimmed == "Bitrates (non-HT):" -> {
                    inFrequencies = false
                    inBitrates = true
                }

                inBand && inFrequencies && trimmed.startsWith("*") -> {
                    val freqPattern = "\\* ([0-9.]+) MHz \\[([0-9]+)\\] \\(([^)]+)\\)(.*)".toRegex()
                    val match = freqPattern.find(trimmed)
                    if (match != null) {
                        val freq = match.groupValues[1]
                        val channel = match.groupValues[2]
                        val power = match.groupValues[3]
                        val flags = match.groupValues[4].trim().split(" ").filter { it.isNotBlank() }

                        currentFrequencies.add(IwFrequency(freq, channel, power, flags))
                    }
                }

                inBand && inBitrates && trimmed.startsWith("*") -> {
                    val ratePattern = "\\* ([0-9.]+) Mbps(.*)".toRegex()
                    val match = ratePattern.find(trimmed)
                    if (match != null) {
                        val rate = match.groupValues[1]
                        val flags = match.groupValues[2].trim()
                            .replace("(", "").replace(")", "")
                            .split(",").map { it.trim() }.filter { it.isNotBlank() }

                        currentBitrates.add(IwBitrate(rate, flags))
                    }
                }

                trimmed == "Supported commands:" -> {
                    inCommands = true
                    inBand = false
                    inInterfaceModes = false
                    inCiphers = false
                }

                inCiphers && trimmed.startsWith("*") -> {
                    supportedCiphers.add(trimmed.substring(1).trim())
                }

                inInterfaceModes && trimmed.startsWith("*") -> {
                    supportedInterfaceModes.add(trimmed.substring(1).trim())
                }

                inCommands && trimmed.startsWith("*") -> {
                    supportedCommands.add(trimmed.substring(1).trim())
                }
            }
        }

        currentBand?.let {
            bands.add(it.copy(
                capabilities = currentBandCapabilities,
                frequencies = currentFrequencies.toList(),
                bitrates = currentBitrates.toList()
            ))
        }

        val capabilities = IwCapabilities(
            wiphyIndex = wiphyIndex,
            maxScanSSIDs = maxScanSSIDs,
            maxScanIEsLength = maxScanIEsLength,
            maxSchedScanSSIDs = maxSchedScanSSIDs,
            maxMatchSets = maxMatchSets,
            retryShortLimit = retryShortLimit,
            retryLongLimit = retryLongLimit,
            coverageClass = coverageClass,
            supportsTDLS = supportsTDLS,
            supportedCiphers = supportedCiphers,
            availableAntennas = availableAntennas,
            supportedInterfaceModes = supportedInterfaceModes,
            supportedCommands = supportedCommands,
            supportedTxFrameTypes = supportedTxFrameTypes,
            supportedRxFrameTypes = supportedRxFrameTypes,
            supportedExtendedFeatures = supportedExtendedFeatures,
            htCapabilityOverrides = htCapabilityOverrides,
            maxScanPlans = maxScanPlans,
            maxScanPlanInterval = maxScanPlanInterval,
            maxScanPlanIterations = maxScanPlanIterations
        )

        val deviceInfo = IwDeviceInfo(wiphy, bands, capabilities)
        Log.d(TAG, "Parsed device info: wiphy=$wiphy, bands=${bands.size}, capabilities parsed")
        return deviceInfo
    }

    private fun parseScanResults(output: String): List<IwNetworkInfo> {
        Log.d(TAG, "Starting scan results parsing, input length: ${output.length} chars")

        val networks = mutableListOf<IwNetworkInfo>()
        val bssBlocks = output.split("BSS ").drop(1)

        Log.d(TAG, "Found ${bssBlocks.size} BSS blocks to parse")

        bssBlocks.forEachIndexed { index, block ->
            try {
                Log.d(TAG, "Parsing BSS block $index, length: ${block.length} chars")
                val network = parseBssBlock(block)
                if (network.bssid.isNotEmpty()) {
                    networks.add(network)
                    Log.d(TAG, "Successfully parsed network: SSID='${network.ssid}', BSSID=${network.bssid}")
                } else {
                    Log.w(TAG, "BSS block $index produced empty BSSID, skipping")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing BSS block $index", e)
            }
        }

        val sortedNetworks = networks.sortedByDescending { parseSignalStrength(it.signal) }
        Log.d(TAG, "Parsing completed: ${sortedNetworks.size} networks, sorted by signal strength")

        return sortedNetworks
    }

    private fun parseBssBlock(block: String): IwNetworkInfo {
        val lines = block.lines()
        Log.v(TAG, "Parsing BSS block with ${lines.size} lines")

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

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            when {
                line.startsWith("BSS") || (bssid.isEmpty() && trimmed.contains("(on wlan0)")) -> {
                    val bssidMatch = "([0-9a-fA-F:]{17})".toRegex().find(trimmed)
                    bssid = bssidMatch?.groupValues?.get(1) ?: ""
                    isAssociated = trimmed.contains("-- associated")
                    Log.v(TAG, "Line $lineIndex: Found BSSID=$bssid, associated=$isAssociated")
                }
                trimmed.startsWith("SSID:") -> {
                    ssid = trimmed.substring(5).trim()
                    Log.v(TAG, "Line $lineIndex: Found SSID='$ssid'")
                }
                trimmed.startsWith("freq:") -> {
                    frequency = trimmed.substring(5).trim()
                    channel = getChannelFromFrequency(frequency)
                    Log.v(TAG, "Line $lineIndex: Found frequency=$frequency, channel=$channel")
                }
                trimmed.startsWith("signal:") -> {
                    signal = trimmed.substring(7).trim()
                    Log.v(TAG, "Line $lineIndex: Found signal=$signal")
                }
                trimmed.startsWith("capability:") -> {
                    capability = trimmed.substring(11).trim()
                    Log.v(TAG, "Line $lineIndex: Found capability=$capability")
                }
                trimmed.startsWith("last seen:") -> {
                    lastSeen = trimmed.substring(10).trim()
                    Log.v(TAG, "Line $lineIndex: Found lastSeen=$lastSeen")
                }
                trimmed.startsWith("Supported rates:") -> {
                    val ratesString = trimmed.substring(16).trim()
                    supportedRates.addAll(ratesString.split(" ").filter { it.isNotBlank() })
                    Log.v(TAG, "Line $lineIndex: Found ${supportedRates.size} supported rates")
                }
                trimmed.startsWith("Extended supported rates:") -> {
                    val ratesString = trimmed.substring(25).trim()
                    extendedRates.addAll(ratesString.split(" ").filter { it.isNotBlank() })
                    Log.v(TAG, "Line $lineIndex: Found ${extendedRates.size} extended rates")
                }
                trimmed.startsWith("Country:") -> {
                    country = trimmed.substring(8).trim()
                    Log.v(TAG, "Line $lineIndex: Found country=$country")
                }
                trimmed.startsWith("WPA:") -> {
                    wpa = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found WPA info, ${wpa.lines().size} lines")
                }
                trimmed.startsWith("RSN:") -> {
                    rsn = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found RSN info, ${rsn.lines().size} lines")
                }
                trimmed.startsWith("WPS:") -> {
                    wps = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found WPS info, ${wps.lines().size} lines")
                }
                trimmed.startsWith("HT capabilities:") -> {
                    htCapabilities = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found HT capabilities, ${htCapabilities.lines().size} lines")
                }
                trimmed.startsWith("VHT capabilities:") -> {
                    vhtCapabilities = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found VHT capabilities, ${vhtCapabilities.lines().size} lines")
                }
                trimmed.startsWith("HE capabilities:") -> {
                    heCapabilities = extractMultiLineInfo(lines, lineIndex)
                    Log.v(TAG, "Line $lineIndex: Found HE capabilities, ${heCapabilities.lines().size} lines")
                }
            }
        }

        val networkInfo = IwNetworkInfo(
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

        Log.d(TAG, "Parsed BSS: BSSID=$bssid, SSID='$ssid', Freq=$frequency, Signal=$signal")
        return networkInfo
    }

    private fun extractMultiLineInfo(lines: List<String>, startIndex: Int): String {
        val result = mutableListOf<String>()
        result.add(lines[startIndex].trim())

        Log.v(TAG, "Extracting multi-line info starting from line $startIndex: ${lines[startIndex].trim()}")

        for (i in (startIndex + 1) until lines.size) {
            val line = lines[i]
            if (line.startsWith("        ") || line.startsWith("\t\t")) {
                result.add(line.trim())
                Log.v(TAG, "Added continuation line $i: ${line.trim()}")
            } else if (line.trim().isNotEmpty() && !line.startsWith(" ")) {
                Log.v(TAG, "Multi-line info ended at line $i")
                break
            }
        }

        val extractedInfo = result.joinToString("\n")
        Log.v(TAG, "Extracted ${result.size} lines of multi-line info")
        return extractedInfo
    }

    private fun getChannelFromFrequency(freq: String): String {
        return try {
            val frequency = freq.toIntOrNull()
            if (frequency == null) {
                Log.v(TAG, "Cannot parse frequency: '$freq'")
                return ""
            }

            val channel = when {
                frequency in 2412..2484 -> ((frequency - 2412) / 5 + 1).toString()
                frequency in 5000..5999 -> ((frequency - 5000) / 5).toString()
                else -> {
                    Log.v(TAG, "Unknown frequency range: $frequency")
                    ""
                }
            }
            Log.v(TAG, "Converted frequency $frequency MHz to channel $channel")
            channel
        } catch (e: Exception) {
            Log.w(TAG, "Error converting frequency '$freq' to channel", e)
            ""
        }
    }

    private fun parseSignalStrength(signal: String): Int {
        return try {
            val dbmMatch = "(-?\\d+)".toRegex().find(signal)
            val strength = dbmMatch?.groupValues?.get(1)?.toInt() ?: -100
            Log.v(TAG, "Parsed signal strength from '$signal' = $strength dBm")
            strength
        } catch (e: Exception) {
            Log.v(TAG, "Error parsing signal strength from '$signal'", e)
            -100
        }
    }
}