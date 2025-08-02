package com.lsd.wififrankenstein.ui.iwscanner

import android.annotation.SuppressLint
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

    suspend fun copyBinariesFromAssets(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting iw binaries copy process")

            val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
            Log.d(TAG, "Detected architecture suffix: '$arch'")

            val binaries = listOf("iw$arch")
            Log.d(TAG, "Binaries to copy: $binaries")

            val libraries = if (arch.isEmpty()) {
                listOf(
                    "libnl-3.so",
                    "libnl-genl-3.so",
                    "libnl-route-3.so"
                )
            } else {
                listOf(
                    "libnl-3.so-32",
                    "libnl-genl-3.so-32",
                    "libnl-route-3.so"
                )
            }
            Log.d(TAG, "Libraries to copy: $libraries")

            binaries.forEach { fileName ->
                Log.d(TAG, "Copying binary: $fileName")
                if (copyAssetToInternalStorage(fileName, fileName)) {
                    val chmodResult = Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                    Log.d(TAG, "chmod 755 for $fileName: ${chmodResult.isSuccess}")
                } else {
                    Log.e(TAG, "Failed to copy binary: $fileName")
                }
            }

            libraries.forEach { libName ->
                Log.d(TAG, "Copying library: $libName")
                if (copyAssetToInternalStorage(libName, libName)) {
                    val chmodResult = Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                    Log.d(TAG, "chmod 755 for $libName: ${chmodResult.isSuccess}")
                } else {
                    Log.w(TAG, "Failed to copy library: $libName (may not exist)")
                }
            }

            if (arch.isNotEmpty()) {
                Log.d(TAG, "Creating library symlinks for 32-bit")
                createLibrarySymlinks()
            }

            Log.d(TAG, "iw binaries copy process completed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying iw binaries", e)
            false
        }
    }

    private fun createLibrarySymlinks() {
        val symlinkConfigs = listOf(
            Pair("libnl-3.so-32", "libnl-3.so"),
            Pair("libnl-genl-3.so-32", "libnl-genl-3.so")
        )

        symlinkConfigs.forEach { (sourceFile, linkName) ->
            createSafeSymlink(sourceFile, linkName)
        }
    }

    private fun createSafeSymlink(sourceFile: String, linkName: String) {
        try {
            val sourcePath = "$binaryDir/$sourceFile"
            val linkPath = "$binaryDir/$linkName"

            val sourceExists = Shell.cmd("test -f $sourcePath && echo 'EXISTS' || echo 'MISSING'").exec()
            if (sourceExists.out.contains("MISSING")) {
                Log.w(TAG, "Source file missing for symlink: $sourceFile")
                return
            }

            Shell.cmd("rm -f $linkPath").exec()

            val createResult = Shell.cmd("cd $binaryDir && ln -sf $sourceFile $linkName").exec()

            if (createResult.isSuccess) {
                val verifyResult = Shell.cmd("test -L $linkPath && test -e $linkPath && echo 'VALID' || echo 'INVALID'").exec()

                if (verifyResult.out.contains("VALID")) {
                    Log.d(TAG, "✓ Created valid symlink: $linkName -> $sourceFile")
                } else {
                    Log.e(TAG, "✗ Created invalid symlink: $linkName")
                    Shell.cmd("rm -f $linkPath").exec()
                }
            } else {
                Log.e(TAG, "✗ Failed to create symlink: $linkName -> $sourceFile")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating symlink $linkName: ${e.message}", e)
        }
    }

    private fun copyAssetToInternalStorage(assetName: String, fileName: String): Boolean {
        return try {
            Log.d(TAG, "Attempting to copy asset: $assetName -> $fileName")
            context.assets.open(assetName).use { input ->
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                    val bytes = input.copyTo(output)
                    Log.d(TAG, "Successfully copied $bytes bytes: $assetName -> $fileName")
                }
            }

            val file = java.io.File(context.filesDir, fileName)
            val fileSize = if (file.exists()) file.length() else 0
            Log.d(TAG, "File verification: $fileName exists=${file.exists()}, size=$fileSize bytes")

            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $assetName -> $fileName: ${e.message}", e)
            false
        }
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        try {
            val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./$iwBinary dev"
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
                val interfaces = parseInterfacesList(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed ${interfaces.size} interfaces: ${interfaces.map { it.name }}")
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

    private fun parseInterfacesList(output: String): List<IwInterface> {
        Log.d(TAG, "Parsing interfaces list, input length: ${output.length} chars")

        val interfaces = mutableListOf<IwInterface>()
        val lines = output.lines()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            Log.v(TAG, "Interface parse line $index: $trimmed")

            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let {
                        interfaces.add(IwInterface(it, currentType, currentAddr))
                        Log.d(TAG, "Added interface: name=$it, type=$currentType, addr=$currentAddr")
                    }
                    currentInterface = trimmed.substring(10).trim()
                    currentType = ""
                    currentAddr = ""
                    Log.d(TAG, "Started parsing interface: $currentInterface")
                }
                trimmed.startsWith("type ") -> {
                    currentType = trimmed.substring(5).trim()
                    Log.v(TAG, "Found type: $currentType")
                }
                trimmed.startsWith("addr ") -> {
                    currentAddr = trimmed.substring(5).trim()
                    Log.v(TAG, "Found addr: $currentAddr")
                }
            }
        }

        currentInterface?.let {
            interfaces.add(IwInterface(it, currentType, currentAddr))
            Log.d(TAG, "Added final interface: name=$it, type=$currentType, addr=$currentAddr")
        }

        val result = if (interfaces.isEmpty()) {
            Log.w(TAG, "No interfaces found, using default wlan0")
            listOf(IwInterface("wlan0"))
        } else {
            Log.d(TAG, "Successfully parsed ${interfaces.size} interfaces")
            interfaces
        }

        return result
    }

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