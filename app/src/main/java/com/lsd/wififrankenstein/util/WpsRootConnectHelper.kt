package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.WpsMethodSelector.Companion.NULL_PIN_IDENTIFIER
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException

class WpsRootConnectHelper(
    private val context: Context,
    private val callbacks: WpsConnectCallbacks
) {

    companion object {
        private const val TAG = "WpsRootConnectHelper"
        private const val CONFIG_FILE = "wps_connect.conf"
        private const val CONNECTION_TIMEOUT = 60000L
        private const val WPS_REMOVED_API_LEVEL = 29
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binaryDir = context.filesDir.absolutePath
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var supplicantProcess: Process? = null
    private var supplicantOutput: BufferedReader? = null
    private var connectionJob: Job? = null
    private var supplicantReaderJobs: List<Job> = emptyList()
    private var originalWifiState = false

    interface WpsConnectCallbacks {
        fun onConnectionProgress(message: String)
        fun onConnectionSuccess(ssid: String)
        fun onConnectionFailed(error: String)
        fun onLogEntry(message: String)
        fun onWpsResult(pin: String?, psk: String?) {}
    }

    fun checkRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            val result = shell.isRoot && shell.isAlive
            Log.d(
                TAG,
                "checkRootAccess: isRoot=${shell.isRoot} isAlive=${shell.isAlive} result=$result"
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "checkRootAccess: root check failed", e)
            false
        }
    }

    private fun isSystemWpsAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT < WPS_REMOVED_API_LEVEL) {
            try {
                val wpsInfo = WpsInfo()
                wpsInfo.setup = WpsInfo.PBC
                Log.d(
                    TAG,
                    "isSystemWpsAvailable: true (sdk=${Build.VERSION.SDK_INT} < $WPS_REMOVED_API_LEVEL)"
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "isSystemWpsAvailable: WPS not available", e)
                false
            } catch (e: NoSuchMethodError) {
                Log.w(TAG, "isSystemWpsAvailable: WPS methods not found", e)
                false
            }
        } else {
            Log.d(
                TAG,
                "isSystemWpsAvailable: false (sdk=${Build.VERSION.SDK_INT} >= $WPS_REMOVED_API_LEVEL)"
            )
            false
        }
    }

    fun checkBinaryFiles(): Boolean {
        return try {
            val arch =
                if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.VERSION.SDK_INT >= 24) "" else "-32"

            val requiredBinaries = listOf(
                "wpa_supplicant$arch",
                "wpa_cli-32"
            )

            val requiredLibraries = if (arch.isEmpty()) {
                listOf(
                    "libnl-3.so",
                    "libnl-genl-3.so",
                    "libnl-route-3.so"
                )
            } else {
                listOf(
                    "libnl-3.so-32",
                    "libnl-genl-3.so-32",
                    "libnl-route-3.so-32"
                )
            }

            val allFiles = requiredBinaries + requiredLibraries

            val missing = allFiles.filter { fileName ->
                val file = java.io.File(binaryDir, fileName)
                !(file.exists() && file.length() > 0 && file.canRead())
            }

            val result = missing.isEmpty()
            Log.d(
                TAG,
                "checkBinaryFiles: arch='$arch' result=$result missing=$missing dir='$binaryDir'"
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "checkBinaryFiles: error checking binary files", e)
            false
        }
    }

    fun copyBinariesFromAssets() {
        scope.launch {
            try {
                callbacks.onConnectionProgress(context.getString(R.string.wps_root_copying_binaries))
                Log.d(TAG, "copyBinariesFromAssets: start")

                val arch =
                    if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.VERSION.SDK_INT >= 24) "" else "-32"
                val binaries = listOf(
                    "wpa_supplicant$arch",
                    "wpa_cli-32"
                )

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
                        "libnl-route-3.so-32"
                    )
                }

                Log.d(
                    TAG,
                    "copyBinariesFromAssets: arch='$arch' binaries=$binaries libraries=$libraries"
                )

                binaries.forEach { fileName ->
                    val copied = copyAssetToInternalStorage(fileName, fileName)
                    Log.d(TAG, "copyBinariesFromAssets: copy '$fileName' copied=$copied")
                    if (copied) {
                        val chmod = Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                        Log.d(
                            TAG,
                            "copyBinariesFromAssets: chmod '$fileName' success=${chmod.isSuccess}"
                        )
                    }
                }

                libraries.forEach { libName ->
                    val copied = copyAssetToInternalStorage(libName, libName)
                    Log.d(TAG, "copyBinariesFromAssets: copy '$libName' copied=$copied")
                    val chmod = Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                    Log.d(
                        TAG,
                        "copyBinariesFromAssets: chmod '$libName' success=${chmod.isSuccess}"
                    )
                }

                if (arch.isNotEmpty()) {
                    createLibrarySymlinks()
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_binaries_ready))
                Log.d(TAG, "copyBinariesFromAssets: done")

            } catch (e: Exception) {
                Log.e(TAG, "copyBinariesFromAssets: error copying binaries", e)
                callbacks.onConnectionFailed(context.getString(R.string.wps_root_error_copying_binaries))
            }
        }
    }

    private fun createLibrarySymlinks() {
        val symlinkConfigs = listOf(
            Pair("libnl-3.so-32", "libnl-3.so"),
            Pair("libnl-genl-3.so-32", "libnl-genl-3.so")
        )

        Log.d(
            TAG,
            "createLibrarySymlinks: creating ${symlinkConfigs.size} symlinks in '$binaryDir'"
        )
        symlinkConfigs.forEach { (sourceFile, linkName) ->
            createSafeSymlink(sourceFile, linkName)
        }
    }

    private fun createSafeSymlink(sourceFile: String, linkName: String) {
        try {
            val sourcePath = "$binaryDir/$sourceFile"
            val linkPath = "$binaryDir/$linkName"

            val sourceExists =
                Shell.cmd("test -f $sourcePath && echo 'EXISTS' || echo 'MISSING'").exec()
            if (sourceExists.out.contains("MISSING")) {
                Log.w(TAG, "createSafeSymlink: source file missing for symlink: $sourceFile")
                return
            }

            val rm = Shell.cmd("rm -f $linkPath").exec()
            val ln = Shell.cmd("cd $binaryDir && ln -sf $sourceFile $linkName").exec()
            Log.d(
                TAG,
                "createSafeSymlink: $linkName -> $sourceFile rmSuccess=${rm.isSuccess} lnSuccess=${ln.isSuccess} " +
                        "lnErr=${ln.err.joinToString("|")}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "createSafeSymlink: error creating symlink $linkName: ${e.message}", e)
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

    fun connectToNetworkWps(
        network: ScanResult,
        wpsPin: String? = null,
        interfaceName: String = "wlan0"
    ) {
        if (connectionJob?.isActive == true) {
            Log.w(TAG, "connectToNetworkWps: connection already in progress, ignoring new request")
            return
        }

        Log.d(
            TAG,
            "connectToNetworkWps: entry ssid='${network.SSID}' bssid='${network.BSSID}' " +
                    "wpsPin='${wpsPin.orEmpty()}' interfaceName='$interfaceName' sdk=${Build.VERSION.SDK_INT} " +
                    "systemWpsAvailable=${isSystemWpsAvailable()}"
        )

        connectionJob = scope.launch {
            val startTime = System.currentTimeMillis()
            try {
                callbacks.onLogEntry(
                    context.getString(
                        R.string.wps_root_starting_connection,
                        network.SSID
                    )
                )

                if (!checkRootAccess()) {
                    Log.e(TAG, "connectToNetworkWps: root access not available, aborting")
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_no_root))
                    return@launch
                }

                originalWifiState = wifiManager.isWifiEnabled
                Log.d(TAG, "connectToNetworkWps: originalWifiState=$originalWifiState")

                var success = false

                if (isSystemWpsAvailable()) {
                    callbacks.onConnectionProgress(context.getString(R.string.wps_root_trying_system_wps))
                    success = trySystemWpsConnection(network, wpsPin)
                    Log.d(TAG, "connectToNetworkWps: method[system WPS API] success=$success")
                } else {
                    Log.d(
                        TAG,
                        "connectToNetworkWps: system WPS API not available on this build, skipping"
                    )
                }

                if (!success) {
                    callbacks.onConnectionProgress(context.getString(R.string.wps_root_trying_existing_supplicant))
                    success = tryExistingSupplicantConnection(network, wpsPin)
                    Log.d(TAG, "connectToNetworkWps: method[existing supplicant] success=$success")
                }

                if (!success) {
                    callbacks.onConnectionProgress(context.getString(R.string.wps_root_using_custom_supplicant))
                    success = useCustomSupplicantMethod(network, wpsPin, interfaceName)
                    Log.d(TAG, "connectToNetworkWps: method[custom supplicant] success=$success")
                }

                Log.d(
                    TAG,
                    "connectToNetworkWps: final result success=$success elapsed=${System.currentTimeMillis() - startTime}ms"
                )

                if (success) {
                    callbacks.onConnectionSuccess(network.SSID)
                } else {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_connection_failed))
                }

            } catch (e: Exception) {
                Log.e(TAG, "connectToNetworkWps: WPS connection failed", e)
                callbacks.onConnectionFailed(
                    context.getString(
                        R.string.wps_root_connection_error,
                        e.message ?: "Unknown"
                    )
                )
            } finally {
                try {
                    stopOurProcesses()
                    restoreSystemWifi()
                } catch (e: Exception) {
                    Log.e(TAG, "connectToNetworkWps: error in cleanup", e)
                } finally {
                    connectionJob = null
                }
            }
        }
    }

    private suspend fun trySystemWpsConnection(network: ScanResult, wpsPin: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= WPS_REMOVED_API_LEVEL) {
                    Log.d(
                        TAG,
                        "trySystemWpsConnection: skipping, sdk=${Build.VERSION.SDK_INT} >= $WPS_REMOVED_API_LEVEL"
                    )
                    return@withContext false
                }

                val wpsConfig = WpsInfo().apply {
                    if (wpsPin != null) {
                        setup = WpsInfo.KEYPAD
                        pin = wpsPin
                    } else {
                        setup = WpsInfo.PBC
                    }
                    BSSID = network.BSSID
                }

                Log.d(
                    TAG,
                    "trySystemWpsConnection: startWps setup=${wpsConfig.setup} " +
                            "(KEYPAD=${WpsInfo.KEYPAD}, PBC=${WpsInfo.PBC}) " +
                            "pin='${wpsConfig.pin.orEmpty()}' bssid='${wpsConfig.BSSID}'"
                )

                var connectionResult = false

                wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        Log.d(
                            TAG,
                            "trySystemWpsConnection: WpsCallback.onStarted pin='${pin.orEmpty()}'"
                        )
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_started))
                    }

                    override fun onSucceeded() {
                        Log.d(TAG, "trySystemWpsConnection: WpsCallback.onSucceeded")
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_succeeded))
                        connectionResult = true
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "trySystemWpsConnection: WpsCallback.onFailed reason=$reason")
                        callbacks.onLogEntry(
                            context.getString(
                                R.string.wps_root_system_wps_failed,
                                reason
                            )
                        )
                        connectionResult = false
                    }
                })

                Log.d(
                    TAG,
                    "trySystemWpsConnection: waiting ${CONNECTION_TIMEOUT}ms for WPS callback"
                )
                delay(CONNECTION_TIMEOUT)
                Log.d(TAG, "trySystemWpsConnection: return connectionResult=$connectionResult")
                connectionResult
            } catch (e: Exception) {
                Log.e(TAG, "trySystemWpsConnection: system WPS failed", e)
                false
            }
        }
    }

    private suspend fun tryExistingSupplicantConnection(
        network: ScanResult,
        wpsPin: String?
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val controlPath = findExistingControlSocket()
                if (controlPath == null) {
                    Log.d(TAG, "tryExistingSupplicantConnection: no existing control socket found")
                    callbacks.onLogEntry(context.getString(R.string.wps_root_no_existing_supplicant))
                    return@withContext false
                }

                Log.d(
                    TAG,
                    "tryExistingSupplicantConnection: found control socket at '$controlPath' " +
                            "bssid='${network.BSSID}' wpsPin='${wpsPin.orEmpty()}'"
                )
                callbacks.onLogEntry(
                    context.getString(
                        R.string.wps_root_found_existing_supplicant,
                        controlPath
                    )
                )

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                if (!checkBinaryFiles()) {
                    Log.d(
                        TAG,
                        "tryExistingSupplicantConnection: binaries missing, copying from assets"
                    )
                    copyBinariesFromAssets()
                    delay(2000)
                    if (!checkBinaryFiles()) {
                        Log.e(
                            TAG,
                            "tryExistingSupplicantConnection: binaries still missing after copy"
                        )
                        return@withContext false
                    }
                }

                val command = if (wpsPin != null) {
                    when {
                        wpsPin == NULL_PIN_IDENTIFIER -> {
                            "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -p$controlPath wps_pin ${network.BSSID}"
                        }

                        wpsPin.isEmpty() -> {
                            "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -p$controlPath wps_pin ${network.BSSID} \"\""
                        }

                        else -> {
                            "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -p$controlPath wps_pin ${network.BSSID} $wpsPin"
                        }
                    }
                } else {
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -p$controlPath wps_pbc ${network.BSSID}"
                }

                Log.d(TAG, "tryExistingSupplicantConnection: executing command: $command")
                val result = Shell.cmd(command).exec()
                Log.d(
                    TAG,
                    "tryExistingSupplicantConnection: result success=${result.isSuccess} " +
                            "out=${result.out.joinToString("|")} err=${result.err.joinToString("|")}"
                )
                if (result.isSuccess) {
                    callbacks.onLogEntry(context.getString(R.string.wps_root_existing_supplicant_command_sent))
                    Log.d(
                        TAG,
                        "tryExistingSupplicantConnection: command accepted, waiting ${CONNECTION_TIMEOUT / 2}ms"
                    )
                    delay(CONNECTION_TIMEOUT / 2)
                    return@withContext true
                } else {
                    callbacks.onLogEntry(context.getString(R.string.wps_root_existing_supplicant_failed))
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "tryExistingSupplicantConnection: existing supplicant connection failed",
                    e
                )
                false
            }
        }
    }

    private fun findExistingControlSocket(): String? {
        val possiblePaths = listOf(
            "/data/misc/wifi/wpa_supplicant",
            "/data/system/wpa_supplicant",
            "/var/run/wpa_supplicant",
            "/data/vendor/wifi/wpa",
            "/data/misc/wifi/sockets"
        )

        return possiblePaths.find { path ->
            try {
                val result = Shell.cmd("test -S $path/wlan0 && echo 'EXISTS'").exec()
                val found = result.out.contains("EXISTS")
                Log.d(TAG, "findExistingControlSocket: path='$path/wlan0' found=$found")
                found
            } catch (e: Exception) {
                Log.w(TAG, "findExistingControlSocket: socket check failed for $path/wlan0", e)
                false
            }
        }
    }

    private suspend fun useCustomSupplicantMethod(
        network: ScanResult,
        wpsPin: String?,
        interfaceName: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(
                    TAG,
                    "useCustomSupplicantMethod: entry ssid='${network.SSID}' bssid='${network.BSSID}' " +
                            "wpsPin='${wpsPin.orEmpty()}' interfaceName='$interfaceName'"
                )

                if (!checkBinaryFiles()) {
                    Log.d(TAG, "useCustomSupplicantMethod: binaries missing, copying from assets")
                    copyBinariesFromAssets()
                    delay(3000)

                    if (!checkBinaryFiles()) {
                        Log.e(
                            TAG,
                            "useCustomSupplicantMethod: binaries missing after copy, aborting"
                        )
                        callbacks.onConnectionFailed(context.getString(R.string.wps_root_binaries_missing))
                        return@withContext false
                    }
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_preparing))

                Log.d(TAG, "useCustomSupplicantMethod: stopping system WiFi")
                gentlyStopSystemWifi()
                delay(3000)

                val socketDir = getSocketDirectory()
                Log.d(TAG, "useCustomSupplicantMethod: socketDir='$socketDir'")
                if (!startSupplicant(socketDir, interfaceName)) {
                    Log.e(TAG, "useCustomSupplicantMethod: failed to start custom supplicant")
                    restoreSystemWifi()
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_supplicant_failed))
                    return@withContext false
                }

                delay(3000)

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_connecting))

                val mode = if (wpsPin != null) "PIN(pin='${wpsPin}')" else "PBC"
                Log.d(
                    TAG,
                    "useCustomSupplicantMethod: starting $mode connection to '${network.BSSID}'"
                )
                val success = if (wpsPin != null) {
                    performWpsPinConnection(network, socketDir, wpsPin)
                } else {
                    performWpsPbcConnection(network, socketDir)
                }

                Log.d(TAG, "useCustomSupplicantMethod: connection success=$success, cleaning up")

                var psk: String? = null
                if (success) {
                    Log.d(TAG, "useCustomSupplicantMethod: extracting PSK from custom supplicant")
                    val pskHelper = WpsPskConnectHelper(context)
                    psk = pskHelper.extractPskFromSupplicant(
                        socketDir = socketDir,
                        interfaceName = interfaceName,
                        configFile = CONFIG_FILE
                    ) { msg -> callbacks.onLogEntry(msg) }
                }

                stopOurProcesses()
                restoreSystemWifi()

                var connected = success
                if (success && psk != null) {
                    Log.d(TAG, "useCustomSupplicantMethod: attempting PSK-based Android connect")
                    connected = WpsPskConnectHelper(context).connectWithPsk(
                        network,
                        psk!!
                    ) { msg -> callbacks.onLogEntry(msg) }
                }

                callbacks.onWpsResult(wpsPin, psk)
                connected
            } catch (e: Exception) {
                Log.e(TAG, "useCustomSupplicantMethod: custom supplicant method failed", e)
                false
            }
        }
    }

    private suspend fun gentlyStopSystemWifi() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_gently_stopping_wifi))
                Log.d(
                    TAG,
                    "gentlyStopSystemWifi: start, isWifiEnabled=${wifiManager.isWifiEnabled}"
                )

                wifiManager.isWifiEnabled = false
                delay(3000)
                Log.d(
                    TAG,
                    "gentlyStopSystemWifi: wifi disabled, isWifiEnabled=${wifiManager.isWifiEnabled}"
                )

                val gentleCommands = listOf(
                    "am force-stop com.android.settings",
                    "killall wpa_supplicant",
                    "pkill -f wpa_supplicant"
                )

                gentleCommands.forEach { command ->
                    try {
                        val result = Shell.cmd(command).exec()
                        Log.d(
                            TAG,
                            "gentlyStopSystemWifi: '$command' success=${result.isSuccess} " +
                                    "err=${result.err.joinToString("|")}"
                        )
                        delay(1000)
                    } catch (e: Exception) {
                        Log.w(TAG, "gentlyStopSystemWifi: gentle command failed: $command", e)
                    }
                }

                delay(2000)

            } catch (e: Exception) {
                Log.w(TAG, "gentlyStopSystemWifi: error gently stopping WiFi", e)
            }
        }
    }

    private suspend fun restoreSystemWifi() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_restoring_wifi))
                Log.d(TAG, "restoreSystemWifi: restoring to originalWifiState=$originalWifiState")

                delay(2000)
                wifiManager.isWifiEnabled = originalWifiState
                delay(3000)
                Log.d(TAG, "restoreSystemWifi: done, isWifiEnabled=${wifiManager.isWifiEnabled}")

            } catch (e: Exception) {
                Log.w(TAG, "restoreSystemWifi: error restoring system WiFi", e)
            }
        }
    }

    private fun getSocketDirectory(): String {
        val dir = if (Build.VERSION.SDK_INT >= 28) {
            "/data/vendor/wifi/wpa/wififrankenstein/"
        } else {
            "/data/misc/wifi/wififrankenstein/"
        }
        Log.d(TAG, "getSocketDirectory: sdk=${Build.VERSION.SDK_INT} dir='$dir'")
        return dir
    }

    private suspend fun startSupplicant(socketDir: String, interfaceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_starting_supplicant))
                Log.d(TAG, "startSupplicant: socketDir='$socketDir' interfaceName='$interfaceName'")

                val arch =
                    if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.VERSION.SDK_INT >= 24) "" else "-32"

                val rm = Shell.cmd("rm -rf $socketDir").exec()
                val mkdir = Shell.cmd("mkdir -p $socketDir").exec()
                val chmod = Shell.cmd("chmod 777 $socketDir").exec()
                Log.d(
                    TAG,
                    "startSupplicant: prep rm=${rm.isSuccess} mkdir=${mkdir.isSuccess} chmod=${chmod.isSuccess}"
                )

                val configPath = createWpsConfig()

                val command = """
                cd $binaryDir && \
                export LD_LIBRARY_PATH=$binaryDir && \
                ./wpa_supplicant$arch -dd -K -Dnl80211,wext -i $interfaceName -c$configPath -O$socketDir
            """.trimIndent()

                Log.d(TAG, "startSupplicant: launching command: ${command.replace('\n', ' ')}")

                supplicantProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val localProcess = supplicantProcess!!
                supplicantOutput = BufferedReader(InputStreamReader(localProcess.inputStream))
                val stderrReader = BufferedReader(InputStreamReader(localProcess.errorStream))

                val readerJobs = listOf<Job>(
                    scope.launch {
                        try {
                            var line: String?
                            while (supplicantOutput?.readLine().also { line = it } != null) {
                                line?.let { parseLine(it) }
                            }
                        } catch (e: InterruptedIOException) {
                            Log.d(TAG, "startSupplicant: supplicant output reading interrupted")
                        } catch (e: Exception) {
                            Log.e(TAG, "startSupplicant: error reading supplicant output", e)
                        } finally {
                            try {
                                stderrReader.close()
                            } catch (e: Exception) {
                                Log.w(TAG, "startSupplicant: failed to close stderrReader", e)
                            }
                        }
                    },
                    scope.launch {
                        try {
                            stderrReader.use { stderr ->
                                var line: String?
                                while (stderr.readLine().also { line = it } != null) {
                                    Log.d(TAG, "startSupplicant: supplicant stderr: $line")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "startSupplicant: error reading supplicant stderr", e)
                        }
                    }
                )
                supplicantReaderJobs = readerJobs

                val socketFile = "$socketDir/$interfaceName"
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 15000) {
                    if (Shell.cmd("test -S $socketFile").exec().isSuccess) {
                        callbacks.onLogEntry(context.getString(R.string.wps_root_supplicant_started))
                        Log.d(
                            TAG,
                            "startSupplicant: socket '$socketFile' created after ${System.currentTimeMillis() - startTime}ms"
                        )
                        return@withContext true
                    }
                    delay(500)
                }

                Log.w(TAG, "startSupplicant: socket timeout after 15000ms — destroying process")
                supplicantProcess?.destroy()
                supplicantProcess = null
                false
            } catch (e: Exception) {
                Log.e(TAG, "startSupplicant: failed to start supplicant", e)
                supplicantProcess?.destroy()
                supplicantProcess = null
                false
            }
        }
    }

    private suspend fun createWpsConfig(): String {
        return withContext(Dispatchers.IO) {
            val configContent = """
                ctrl_interface_group=wifi
                update_config=1
                ap_scan=1
            """.trimIndent()

            val configPath = "$binaryDir/$CONFIG_FILE"

            try {
                context.openFileOutput(CONFIG_FILE, Context.MODE_PRIVATE).use { output ->
                    output.write(configContent.toByteArray())
                }
                val chmod = Shell.cmd("chmod 644 $configPath").exec()
                Log.d(
                    TAG,
                    "createWpsConfig: configPath='$configPath' chmod=${chmod.isSuccess} content:\n$configContent"
                )
                configPath
            } catch (e: Exception) {
                Log.e(TAG, "createWpsConfig: error creating config", e)
                configPath
            }
        }
    }

    private suspend fun performWpsPbcConnection(network: ScanResult, socketDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/wlan0"

                callbacks.onLogEntry(
                    context.getString(
                        R.string.wps_root_starting_pbc,
                        network.BSSID
                    )
                )
                Log.d(
                    TAG,
                    "performWpsPbcConnection: bssid='${network.BSSID}' socketPath='$socketPath'"
                )

                val command =
                    "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -g$socketPath wps_pbc ${network.BSSID}"
                Log.d(TAG, "performWpsPbcConnection: executing command: $command")

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val stdoutThread = Thread { process.inputStream.use { it.readBytes() } }
                val stderrThread = Thread { process.errorStream.use { it.readBytes() } }
                stdoutThread.start()
                stderrThread.start()
                val exitCode = process.waitFor()
                stdoutThread.join(3000)
                stderrThread.join(3000)

                Log.d(TAG, "performWpsPbcConnection: exitCode=$exitCode")
                if (exitCode == 0) {
                    waitForConnection()
                } else {
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "performWpsPbcConnection: WPS PBC failed", e)
                false
            }
        }
    }

    private suspend fun performWpsPinConnection(
        network: ScanResult,
        socketDir: String,
        pin: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/wlan0"

                callbacks.onLogEntry(
                    if (pin.isEmpty()) {
                        context.getString(R.string.wps_root_starting_empty_pin, network.BSSID)
                    } else {
                        context.getString(R.string.wps_root_starting_pin, network.BSSID, pin)
                    }
                )

                val command = when {
                    pin == NULL_PIN_IDENTIFIER -> {
                        "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -g$socketPath wps_pin ${network.BSSID}"
                    }

                    pin.isEmpty() -> {
                        "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -g$socketPath wps_pin ${network.BSSID} \"\""
                    }

                    else -> {
                        "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli-32 -g$socketPath wps_pin ${network.BSSID} $pin"
                    }
                }

                val pinType = when {
                    pin == NULL_PIN_IDENTIFIER -> "NULL_PIN"
                    pin.isEmpty() -> "EMPTY"
                    else -> "REAL"
                }
                Log.d(
                    TAG,
                    "performWpsPinConnection: bssid='${network.BSSID}' socketPath='$socketPath' " +
                            "pinType=$pinType pin='$pin' command=$command"
                )

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val stdoutThread = Thread { process.inputStream.use { it.readBytes() } }
                val stderrThread = Thread { process.errorStream.use { it.readBytes() } }
                stdoutThread.start()
                stderrThread.start()
                val exitCode = process.waitFor()
                stdoutThread.join(3000)
                stderrThread.join(3000)

                Log.d(TAG, "performWpsPinConnection: exitCode=$exitCode")
                if (exitCode == 0) {
                    waitForConnection()
                } else {
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "performWpsPinConnection: WPS PIN failed", e)
                false
            }
        }
    }

    private suspend fun waitForConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "waitForConnection: waiting ${CONNECTION_TIMEOUT}ms")
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CONNECTION_TIMEOUT) {
                delay(1000)
            }
            Log.d(TAG, "waitForConnection: wait complete, returning true")
            true
        }
    }

    private fun parseLine(line: String) {
        Log.d(TAG, "WPS: $line")

        when {
            line.contains("WPS-SUCCESS") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_success))
            }

            line.contains("WPS-FAIL") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_failed))
            }

            line.contains("WPS-TIMEOUT") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_timeout))
            }

            line.contains("CTRL-EVENT-CONNECTED") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_connected))
            }

            line.contains("CTRL-EVENT-DISCONNECTED") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_disconnected))
            }
        }
    }

    private suspend fun stopOurProcesses() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "stopOurProcesses: start")

            supplicantReaderJobs.forEach { it.cancel() }
            supplicantReaderJobs = emptyList()

            try {
                supplicantOutput?.close()
            } catch (e: Exception) {
                Log.w(TAG, "stopOurProcesses: error closing supplicant output", e)
            }

            try {
                supplicantProcess?.destroy()
                supplicantProcess = null
            } catch (e: Exception) {
                Log.w(TAG, "stopOurProcesses: error destroying supplicant process", e)
            }

            supplicantOutput = null

            val killCommands = listOf(
                "pkill -9 -f $binaryDir",
                "rm -rf /data/vendor/wifi/wpa/wififrankenstein/",
                "rm -rf /data/misc/wifi/wififrankenstein/"
            )

            killCommands.forEach { command ->
                try {
                    val result = Shell.cmd(command).exec()
                    Log.d(TAG, "stopOurProcesses: '$command' success=${result.isSuccess}")
                    delay(200)
                } catch (e: Exception) {
                    Log.w(TAG, "stopOurProcesses: error executing kill command: $command", e)
                }
            }
        }
    }

    fun stopConnection() {
        try {
            Log.d(TAG, "stopConnection: cancelling connection job")
            connectionJob?.cancel()
            scope.launch {
                stopOurProcesses()
                restoreSystemWifi()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopConnection: error stopping connection", e)
        }
    }

    fun cleanup() {
        try {
            stopConnection()
            scope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun isConnecting(): Boolean = connectionJob?.isActive == true

    fun onDestroy() {
        cleanup()
    }
}