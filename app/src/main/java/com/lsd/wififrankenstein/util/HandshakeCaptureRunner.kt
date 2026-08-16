package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Environment
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.util.ChrootCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HandshakeResult(
    val success: Boolean,
    val capFilePath: String?,
    val bssid: String?,
    val essid: String?,
    val rawOutput: String
)

enum class CaptureFormat(val airodumpArg: String, val fileSuffix: String) {
    PCAPNG("pcapng", "-01.pcapng"),
    PCAP("pcap", "-01.cap"),
    CAP("pcap", "-01.cap");

    companion object {
        val DEFAULT = PCAPNG
    }
}

class HandshakeCaptureRunner(private val context: Context) {

    private val chrootManager = ChrootManager.get(context)
    private val iwWifiManager = IwWifiManager(context)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private val TAG = "HandshakeCaptureRunner"

    private val hasChrootTools: Boolean
        get() = ChrootCapabilities.hasChrootTools(context)

    companion object {
        const val OUTPUT_BASE = "/sdcard/WIFI-Frankenstein/hs"
        private const val CAPTURE_PREFIX = "handshake"
        private const val AIRODUMP_DONE_MARKER = "AIRODUMP_DONE"
        private const val AIRODUMP_SESSION_TIMEOUT_MS = 5 * 60 * 1000L
        private val CREATED_CAPTURE_FILE_REGEX = Regex(
            """Created capture file ["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )

        fun monitorInterfaceName(iface: String): String =
            if (iface == "wlan0") "wlan0" else "${iface}mon"

        fun chrootPathToJvm(chrootPath: String): String =
            chrootPath.replaceFirst(
                "/sdcard",
                Environment.getExternalStorageDirectory().absolutePath
            )

        fun jvmPathToChroot(jvmPath: String): String =
            jvmPath.replaceFirst(Environment.getExternalStorageDirectory().absolutePath, "/sdcard")
    }

    suspend fun resolveMonitorInterface(baseIface: String): String = withContext(Dispatchers.IO) {
        val found = iwWifiManager.findMonitorInterface(baseIface)
        found ?: monitorInterfaceName(baseIface)
    }

    @Volatile
    private var captureSessionRunning = false

    @Volatile
    private var cachedCreatedFilePath: String? = null

    suspend fun disableMonitor(iface: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DISABLE MONITOR: $iface ===")
        val monIface = resolveMonitorInterface(iface)
        Log.i(TAG, "Disabling monitor: base=$iface resolvedMon=$monIface")
        val targetIface = monIface.takeIf { it != iface } ?: iface
        val success = iwWifiManager.setInterfaceMode(targetIface, IwWifiManager.MODE_MANAGED)
        Log.i(TAG, "disableMonitor: iface=$targetIface success=$success")

        if (iface != "wlan0") {
            Log.d(TAG, "Enabling host WiFi after non-wlan0 interface monitor stop")
            chrootManager.enableWifiOnHost()
        }
        success
    }

    suspend fun startCaptureAsync(
        iface: String,
        bssid: String,
        channel: String,
        outputDir: String,
        outputFormat: CaptureFormat = CaptureFormat.DEFAULT,
        onProgress: ((String) -> Unit)? = null,
        onStats: ((CaptureStats) -> Unit)? = null,
        onEvent: ((String) -> Unit)? = null
    ) {
        if (captureSessionRunning) {
            Log.w(TAG, "Capture already running")
            return
        }

        Log.d(TAG, "=== CAPTURE START (non-blocking session) ===")

        val mountOk = withContext(Dispatchers.IO) {
            runCatching { chrootManager.mountChroot() }.getOrDefault(false)
        }
        Log.d(TAG, "mountChroot() result: $mountOk")
        if (!mountOk) {
            onProgress?.invoke("[error] Chroot mount failed")
            return
        }

        withContext(Dispatchers.IO) {
            runCatching {
                chrootManager.executeInChroot("mkdir -p $outputDir")
            }
        }
        Log.d(TAG, "mkdir -p $outputDir done")

        val monIface = resolveMonitorInterface(iface)
        Log.i(TAG, "Resolved monitor iface: $monIface (base: $iface)")

        val outputFormatArg = outputFormat.airodumpArg
        val airodumpCmd =
            "printf \"\\033[8;200;512t\"; export COLUMNS=512; airodump-ng $monIface -w $outputDir/$CAPTURE_PREFIX " +
                    "--ignore-negative-one --output-format $outputFormatArg " +
                    "--bssid $bssid -c $channel ; " +
                    "echo $AIRODUMP_DONE_MARKER" + "_" + "\$?"
        Log.d(TAG, "airodump command: $airodumpCmd")

        captureSessionRunning = true
        cachedCreatedFilePath = null

        onProgress?.invoke("[*] Starting airodump-ng on $monIface (ch=$channel)")

        sessionJob = sessionScope.launch {
            val parser = AirodumpParser(bssid)
            var lastStatsEmitMs = 0L
            val statsEmitIntervalMs = 2_000L
            var lastPmkidFound = false
            var lastHandshakeFound = false

            try {
                val result = chrootManager.executePersistentSession(
                    command = airodumpCmd,
                    onOutput = { line ->
                        Log.d(TAG, "airodump: $line")
                        val cleanLine = line.removePrefix("[stderr] ")

                        val createdMatch = CREATED_CAPTURE_FILE_REGEX.find(cleanLine)
                        if (createdMatch != null) {
                            val path = createdMatch.groupValues[1]
                            cachedCreatedFilePath = path
                            Log.i(TAG, "Captured airodump output file path: $path")
                        }

                        val stats = parser.processLine(cleanLine)
                        if (stats != null) {
                            if (stats.pmkidFound && !lastPmkidFound) {
                                lastPmkidFound = true
                                onEvent?.invoke("PMKID")
                            }
                            if (stats.handshakeFound && !lastHandshakeFound) {
                                lastHandshakeFound = true
                                onEvent?.invoke("HANDSHAKE")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastStatsEmitMs >= statsEmitIntervalMs) {
                                lastStatsEmitMs = now
                                onStats?.invoke(stats)
                            }
                        }
                    },
                    sessionTimeout = AIRODUMP_SESSION_TIMEOUT_MS
                )
                val doneLine = result.stdout.firstOrNull { it.startsWith(AIRODUMP_DONE_MARKER) }
                val exitCode = doneLine?.substringAfter("_")?.toIntOrNull()
                val stderrOutput = result.stderr.filter { it.isNotBlank() }.joinToString("; ")
                Log.i(
                    TAG,
                    "Airodump session ended: stdout=${result.stdout.size} lines, stderr=${result.stderr.size} lines, marker=$doneLine, exitCode=$exitCode"
                )
                if (exitCode != null && exitCode != 0) {
                    Log.w(TAG, "airodump-ng exited with code $exitCode. Stderr: $stderrOutput")
                    onProgress?.invoke("[error] airodump-ng exited with code $exitCode${if (stderrOutput.isNotBlank()) ": $stderrOutput" else ""}")
                } else if (stderrOutput.isNotBlank()) {
                    Log.d(TAG, "airodump-ng stderr: $stderrOutput")
                }
                onProgress?.invoke("[+] Airodump session ended (exit=$exitCode)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run airodump persistent session", e)
                onProgress?.invoke("[error] Failed to start airodump: ${e.message}")
            } finally {
                captureSessionRunning = false
            }
        }
    }

    fun stopCapture() {
        Log.d(TAG, "=== STOP CAPTURE ===")
        captureSessionRunning = false
        sessionJob?.cancel()
        sessionJob = null
        runCatching { chrootManager.cancelSession() }
            .onFailure { Log.w(TAG, "cancelSession failed", it) }
        runCatching {
            val pkillRes =
                chrootManager.executeInChroot("pkill -9 -f 'airodump-ng' 2>/dev/null; pkill -9 -f 'aireplay-ng' 2>/dev/null; true")
            Log.d(TAG, "pkill airodump-ng/aireplay-ng: code=${pkillRes.code}")
        }.onFailure { Log.w(TAG, "pkill airodump-ng failed", it) }
    }

    fun isAirodumpRunning(): Boolean = captureSessionRunning

    suspend fun sendDeauth(
        iface: String,
        bssid: String,
        clientMac: String? = null,
        count: Int = 5,
        channel: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DEAUTH ONE-SHOT ===")
        val monIface = resolveMonitorInterface(iface)
        Log.i(
            TAG,
            "Deauth: base=$iface resolvedMon=$monIface bssid=$bssid count=$count client=$clientMac channel=$channel"
        )
        val clientArg = if (!clientMac.isNullOrBlank()) "-c $clientMac " else ""
        val channelSet =
            if (channel != null) "iw dev $monIface set channel $channel 2>/dev/null; " else ""
        val cmd = "${channelSet}aireplay-ng -0 $count -D -a $bssid ${clientArg}$monIface 2>&1"
        Log.d(TAG, "Deauth command: $cmd")

        val result = chrootManager.executeInChroot(cmd)
        val combined = (result.out + result.err).joinToString("\n")
        Log.d(TAG, "Deauth raw output (first 500): ${combined.take(500)}")
        val sendingMatch = Regex("""Sending\s+(\d+)""", RegexOption.IGNORE_CASE).find(combined)
        val ackCountMatch =
            Regex("""\[\s*\d+\|\s*(\d+)\s*ACKs\]""", RegexOption.IGNORE_CASE).find(combined)
        val ackCount = ackCountMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val deauthCountMatch =
            Regex("""(\d+)\s+deauth(?:entication)?\b""", RegexOption.IGNORE_CASE).find(combined)
        val success = sendingMatch != null || deauthCountMatch != null
        if (!success) {
            val waitingForBeacon = combined.contains("Waiting for beacon", ignoreCase = true)
            if (waitingForBeacon) {
                Log.w(
                    TAG,
                    "sendDeauth: stuck waiting for beacon — channel mismatch or AP out of range"
                )
            }
        }
        onProgress?.invoke(combined)
        Log.i(
            TAG,
            "sendDeauth: success=$success ackCount=$ackCount sendingMatch=${sendingMatch?.value} countMatch=${deauthCountMatch?.value}"
        )
        success
    }

    suspend fun verifyHandshake(
        capFilePath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== VERIFY HANDSHAKE ===")

        if (!hasChrootTools) {
            Log.d(TAG, "verifyHandshake: skipped (no chroot tools)")
            return@withContext false
        }

        val cowpattyCmd = "cowpatty -c -r \"$capFilePath\" 2>&1"
        val cowpattyRes = chrootManager.executeInChroot(cowpattyCmd)
        val cowpattyOut = (cowpattyRes.out + cowpattyRes.err).joinToString("\n")
        onProgress?.invoke(cowpattyOut)
        Log.d(TAG, "verifyHandshake: cowpatty=${cowpattyOut.take(300)}")

        val cowpattyValid =
            cowpattyOut.contains("Collected all necessary data", ignoreCase = true) ||
                    cowpattyOut.contains("1 handshake", ignoreCase = true) ||
                    cowpattyOut.contains("1 potential", ignoreCase = true) ||
                    Regex("""valid\s+handshake""", RegexOption.IGNORE_CASE).containsMatchIn(
                        cowpattyOut
                    )

        if (cowpattyValid) {
            Log.d(TAG, "verifyHandshake: cowpatty valid=true")
            return@withContext true
        }

        Log.d(TAG, "verifyHandshake: cowpatty invalid, trying aircrack-ng fallback")
        val aircrackCmd = "aircrack-ng \"$capFilePath\" 2>&1"
        val aircrackRes = chrootManager.executeInChroot(aircrackCmd)
        val aircrackOut = (aircrackRes.out + aircrackRes.err).joinToString("\n")
        onProgress?.invoke("[aircrack] $aircrackOut")
        Log.d(TAG, "verifyHandshake: aircrack=${aircrackOut.take(300)}")

        val aircrackValid = aircrackOut.contains("1 handshake", ignoreCase = true) ||
                aircrackOut.contains("WPA (1 handshake", ignoreCase = true) ||
                Regex("""1\s+handshake""", RegexOption.IGNORE_CASE).containsMatchIn(aircrackOut)

        Log.d(TAG, "verifyHandshake: aircrack valid=$aircrackValid")
        aircrackValid
    }

    suspend fun verifyHandshakeWithHcxpcapngtool(
        capFilePath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== VERIFY HANDSHAKE (hcxpcapngtool) ===")

        if (!hasChrootTools) {
            Log.d(TAG, "verifyHandshakeWithHcxpcapngtool: skipped (no chroot tools)")
            return@withContext false
        }

        onProgress?.invoke("[*] hcxpcapngtool: verifying handshake data...")
        val cmd = "hcxpcapngtool -o /dev/stdout \"$capFilePath\" 2>/dev/null"
        val result = chrootManager.executeInChroot(cmd)
        Log.d(TAG, "verifyHandshakeWithHcxpcapngtool: lines=${result.out.size}")

        val stdout = result.out.joinToString("\n")
        val lines = stdout.lines()
        val hasWpa01 = lines.any { it.startsWith("WPA*01\t") || it.startsWith("WPA01\t") }
        val hasWpa02 = lines.any { it.startsWith("WPA*02\t") || it.startsWith("WPA02\t") }
        val hasPmkidRecord = lines.any { it.startsWith("WPA*03\t") || it.startsWith("WPA03\t") }

        val valid = hasWpa01 || hasWpa02 || hasPmkidRecord
        val found = mutableListOf<String>()
        if (hasWpa01) found.add("EAPOL")
        if (hasWpa02) found.add("handshake")
        if (hasPmkidRecord) found.add("PMKID")
        if (valid) {
            onProgress?.invoke("[+] hcxpcapngtool: found ${found.joinToString(", ")}")
        } else {
            onProgress?.invoke("[-] hcxpcapngtool: no PMKID or handshake found")
        }
        Log.d(
            TAG,
            "verifyHandshakeWithHcxpcapngtool: valid=$valid (WPA01=$hasWpa01 WPA02=$hasWpa02 PMKID=$hasPmkidRecord)"
        )
        valid
    }

    suspend fun getHcxpcapngtoolOutput(
        capFilePath: String,
        extraArgs: String = ""
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== HCXPCAPNGTOOL OUTPUT ===")

        if (!hasChrootTools) {
            Log.d(TAG, "getHcxpcapngtoolOutput: skipped (no chroot tools)")
            return@withContext ""
        }

        val cmd = "hcxpcapngtool $extraArgs -o /dev/stdout \"$capFilePath\" 2>&1"
        val result = chrootManager.executeInChroot(cmd)
        (result.out + result.err).joinToString("\n")
    }

    suspend fun readCapBytes(chrootPath: String): ByteArray? = withContext(Dispatchers.IO) {
        val jvmPath = chrootPath.replaceFirst("/sdcard", "/storage/emulated/0")


        if (hasChrootTools) {
            try {
                val res = chrootManager.executeInChroot("base64 '$chrootPath' 2>/dev/null")
                if (res.isSuccess && res.out.any { it.isNotEmpty() }) {
                    val raw = res.out.joinToString("")
                    val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
                    if (bytes.isNotEmpty()) {
                        Log.d(
                            TAG,
                            "readCapBytes: read via chroot base64: ${jvmPath.substringAfterLast('/')} (${bytes.size}B)"
                        )
                        return@withContext bytes
                    }
                }
            } catch (_: Exception) {
            }
        }




        try {
            val res = com.topjohnwu.superuser.Shell.cmd("base64 '$jvmPath' 2>/dev/null").exec()
            if (res.isSuccess && res.out.any { it.isNotEmpty() }) {
                val raw = res.out.joinToString("")
                val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
                if (bytes.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "readCapBytes: read via shell base64: ${jvmPath.substringAfterLast('/')} (${bytes.size}B)"
                    )
                    return@withContext bytes
                }
            }
        } catch (_: Exception) {
        }


        try {
            val file = File(jvmPath)
            if (file.exists() && file.canRead()) {
                Log.d(
                    TAG,
                    "readCapBytes: read via JVM File API: ${jvmPath.substringAfterLast('/')}"
                )
                return@withContext file.readBytes()
            }
        } catch (_: Exception) {
        }

        Log.w(TAG, "readCapBytes: FAILED for $jvmPath")
        null
    }

    suspend fun readCapBytesAndParse(chrootPath: String): List<HandshakeHash> =
        withContext(Dispatchers.IO) {
            val bytes = readCapBytes(chrootPath) ?: return@withContext emptyList()
            val tmp = File(context.cacheDir, "parse_${System.nanoTime()}.cap")
            try {
                tmp.writeBytes(bytes)
                return@withContext HandshakeParser.parseFile(tmp)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

    suspend fun readCapApMetadata(chrootPath: String): Map<String, ApMetadata> =
        withContext(Dispatchers.IO) {
            val bytes = readCapBytes(chrootPath) ?: return@withContext emptyMap()
            val tmp = File(context.cacheDir, "meta_${System.nanoTime()}.cap")
            try {
                tmp.writeBytes(bytes)
                return@withContext PcapParser().extractApMetadata(tmp)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }

    suspend fun crackWithWordlist(
        capFilePath: String,
        wordlistPath: String,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== CRACK START ===")

        if (!hasChrootTools) {
            Log.d(TAG, "crackWithWordlist: skipped (no chroot tools)")
            return@withContext null
        }

        val cmd = "aircrack-ng -w \"$wordlistPath\" \"$capFilePath\" 2>&1"
        var foundPassword: String? = null

        try {
            val result = chrootManager.executeInChroot(cmd)

            for (line in result.out) {
                onProgress?.invoke(line)
                val match = KEY_FOUND_REGEX.find(line)
                if (match != null) {
                    foundPassword = match.groupValues[1]
                }
            }
            for (line in result.err) {
                onProgress?.invoke("[stderr] $line")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Crack failed", e)
        }

        Log.d(TAG, "crackWithWordlist: found=${foundPassword != null}")
        foundPassword
    }

    suspend fun crackSinglePassword(
        capFilePath: String,
        password: String,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== CRACK SINGLE PASSWORD ===")

        if (!hasChrootTools) {
            Log.d(TAG, "crackSinglePassword: skipped (no chroot tools)")
            return@withContext null
        }

        val cmd = "echo \"$password\" | aircrack-ng -w - \"$capFilePath\" 2>&1"
        var foundPassword: String? = null
        try {
            val result = chrootManager.executeInChroot(cmd)
            for (line in result.out) {
                onProgress?.invoke(line)
                val match = KEY_FOUND_REGEX.find(line)
                if (match != null) foundPassword = match.groupValues[1]
            }
            for (line in result.err) onProgress?.invoke("[stderr] $line")
        } catch (e: Exception) {
            Log.e(TAG, "Crack single failed", e)
        }
        foundPassword
    }

    suspend fun crackWithPasswords(
        capFilePath: String,
        passwords: List<String>,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== CRACK WITH PASSWORD LIST ===")

        if (!hasChrootTools) {
            Log.d(TAG, "crackWithPasswords: skipped (no chroot tools)")
            return@withContext null
        }

        val tempDirChroot = "/sdcard/WIFI-Frankenstein/temp"
        val name = "pwlist_${System.nanoTime()}.txt"
        val hostFile = File(context.cacheDir, name)
        try {
            hostFile.writeText(passwords.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "crackWithPasswords: failed to write wordlist", e)
            return@withContext null
        }

        chrootManager.executeInChroot("mkdir -p '$tempDirChroot'")
        val tempFileChroot = "$tempDirChroot/$name"
        com.topjohnwu.superuser.Shell.cmd(
            "mkdir -p '$tempDirChroot' && cp '${hostFile.absolutePath}' '$tempFileChroot'"
        ).exec()

        val cmd = "aircrack-ng -w \"$tempFileChroot\" \"$capFilePath\" 2>&1"
        var foundPassword: String? = null
        try {
            val result = chrootManager.executeInChroot(cmd)
            for (line in result.out) {
                onProgress?.invoke(line)
                val match = KEY_FOUND_REGEX.find(line)
                if (match != null) foundPassword = match.groupValues[1]
            }
            for (line in result.err) onProgress?.invoke("[stderr] $line")
        } catch (e: Exception) {
            Log.e(TAG, "Crack list failed", e)
        } finally {
            chrootManager.executeInChroot("rm -f '$tempFileChroot'")
            hostFile.delete()
        }
        foundPassword
    }

    suspend fun combineCaptures(
        capFilePaths: List<String>,
        outputPath: String,
        outputFormat: String = "22000",
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== COMBINE CAPTURES ===")

        if (!hasChrootTools) {
            Log.d(TAG, "combineCaptures: skipped (no chroot tools)")
            return@withContext false
        }

        val args = capFilePaths.joinToString(" ") { "\"$it\"" }
        val cmd = when (outputFormat) {
            "hccapx" -> "aircrack-ng -j \"${outputPath.removeSuffix(".hccapx")}\" $args 2>&1"
            else -> "hcxpcapngtool -o \"$outputPath\" $args 2>&1"
        }
        val result = chrootManager.executeInChroot(cmd)
        val combined = (result.out + result.err).joinToString("\n")
        onProgress?.invoke(combined)
        val success = chrootManager.executeInChroot("test -s '$outputPath'").isSuccess ||
                chrootManager.executeInChroot("test -f '${outputPath}.hccapx'").isSuccess
        Log.d(TAG, "combineCaptures: success=$success")
        success
    }

    suspend fun getAircrackVersion(): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== GET AIRCRACK VERSION ===")

        if (!hasChrootTools) {
            Log.d(TAG, "getAircrackVersion: skipped (no chroot tools)")
            return@withContext null
        }

        val cmd = "aircrack-ng --help 2>&1 | grep -i 'aircrack-ng' | head -1"
        val result = chrootManager.executeInChroot(cmd)
        val version = result.out.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        Log.d(TAG, "aircrack-ng version: $version")
        return@withContext version
    }

    suspend fun exportToHccapx(
        capFilePath: String,
        outputPath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== EXPORT TO HCCAPX ===")

        if (!hasChrootTools) {
            Log.d(TAG, "exportToHccapx: skipped (no chroot tools)")
            return@withContext false
        }

        chrootManager.executeInChroot("mkdir -p ${outputPath.substringBeforeLast("/")}")

        val cmd = "aircrack-ng -j \"$outputPath\" \"$capFilePath\" 2>&1"
        val result = chrootManager.executeInChroot(cmd)
        val combined = (result.out + result.err).joinToString("\n")
        val success = combined.contains("Successfully", ignoreCase = true) ||
                chrootManager.executeInChroot("test -f '${outputPath}.hccapx'").isSuccess ||
                chrootManager.executeInChroot("test -f '${outputPath}.22000'").isSuccess
        onProgress?.invoke(combined)
        Log.d(TAG, "exportToHccapx: success=$success")
        success
    }

    suspend fun exportTo22000(
        capFilePath: String,
        outputPath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== EXPORT TO 22000 ===")

        if (!hasChrootTools) {
            Log.d(TAG, "exportTo22000: skipped (no chroot tools)")
            return@withContext false
        }

        chrootManager.executeInChroot("mkdir -p ${outputPath.substringBeforeLast("/")}")
        val cmd = "hcxpcapngtool -o \"$outputPath\" \"$capFilePath\" 2>&1"
        val result = chrootManager.executeInChroot(cmd)
        val combined = (result.out + result.err).joinToString("\n")
        val success = chrootManager.executeInChroot("test -s '$outputPath'").isSuccess
        onProgress?.invoke(combined)
        Log.d(TAG, "exportTo22000: success=$success")
        success
    }

    suspend fun extractPmkidHash(
        capFilePath: String,
        outputPath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== EXTRACT PMKID HASH ===")

        if (!hasChrootTools) {
            Log.d(TAG, "extractPmkidHash: skipped (no chroot tools)")
            return@withContext false
        }

        chrootManager.executeInChroot("mkdir -p ${outputPath.substringBeforeLast("/")}")

        val cmd = "hcxpcapngtool -o \"$outputPath\" \"$capFilePath\" 2>&1"
        val result = chrootManager.executeInChroot(cmd)
        val combined = (result.out + result.err).joinToString("\n")
        val success = chrootManager.executeInChroot("test -s '$outputPath'").isSuccess
        onProgress?.invoke(combined)
        Log.d(TAG, "extractPmkidHash: success=$success, output=${combined.take(200)}")
        success
    }

    suspend fun detectPmkid(
        capFilePath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DETECT PMKID ===")

        if (!hasChrootTools) {
            Log.d(TAG, "detectPmkid: skipped (no chroot tools)")
            return@withContext false
        }

        val cmd = "hcxpcapngtool --pmkid-only -o /dev/stdout \"$capFilePath\" 2>/dev/null"
        val result = chrootManager.executeInChroot(cmd)
        val hasPmkid = result.out.any { it.startsWith("WPA*03\t") || it.startsWith("WPA03\t") }
        onProgress?.invoke(result.out.joinToString("\n"))
        Log.d(TAG, "detectPmkid: hasPmkid=$hasPmkid")
        hasPmkid
    }

    suspend fun hasPmkidViaAircrack(
        capFilePath: String,
        onProgress: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== DETECT PMKID ===")

        if (!hasChrootTools) {
            Log.d(TAG, "hasPmkidViaAircrack: skipped (no chroot tools)")
            return@withContext false
        }

        val aircrackCmd = "aircrack-ng \"$capFilePath\" 2>&1"
        val aircrackRes = chrootManager.executeInChroot(aircrackCmd)
        val aircrackOut = (aircrackRes.out + aircrackRes.err).joinToString("\n")
        onProgress?.invoke(aircrackOut)

        val hasPmkid = PMKID_AIRCRACK_REGEX.containsMatchIn(aircrackOut)

        Log.d(TAG, "hasPmkidViaAircrack: result=$hasPmkid")
        hasPmkid
    }

    fun cancel() {
        Log.d(TAG, "Cancelling handshake capture")
        stopCapture()
    }

    suspend fun forceCleanup() {
        Log.d(TAG, "Force cleanup handshake capture")
        stopCapture()
        sessionJob?.cancelAndJoin()
        sessionJob = null
        chrootManager.forceCleanup()
    }

    fun listOutputDir(dir: String): com.topjohnwu.superuser.Shell.Result {
        return chrootManager.executeInChroot("ls -la $dir 2>&1")
    }

    @Volatile
    private var cachedCapFile: String? = null

    fun getCachedCreatedFilePath(): String? = cachedCreatedFilePath

    suspend fun findCapFile(dir: String): String? {
        cachedCreatedFilePath?.let {
            Log.d(TAG, "findCapFile: using airodump-created path: $it")
            cachedCapFile = it
            return it
        }

        cachedCapFile?.let {
            if (File(chrootPathToJvm(it)).exists()) return it
            cachedCapFile = null
        }

        Log.d(TAG, "findCapFile: dir=$dir")

        val jvmDir = chrootPathToJvm(dir)
        val jvmDirFile = File(jvmDir)
        if (!jvmDirFile.exists() || !jvmDirFile.isDirectory) {
            val dirExists =
                chrootManager.executeInChroot("test -d \"$dir\" 2>/dev/null && echo EXISTS").isSuccess
            if (!dirExists) {
                Log.d(TAG, "findCapFile: output dir $dir does not exist yet")
                return null
            }
        } else {
            val cap01 = jvmDirFile.listFiles { file ->
                file.name.endsWith("-01.cap") || file.name.endsWith("-01.pcap") || file.name.endsWith(
                    "-01.pcapng"
                )
            }
            if (!cap01.isNullOrEmpty()) {
                val latest = cap01.maxByOrNull { it.lastModified() }
                val chrootPath = "$dir/${latest!!.name}"
                cachedCapFile = chrootPath
                Log.d(TAG, "findCapFile: JVM-direct found at $chrootPath")
                return chrootPath
            }
        }

        Log.d(TAG, "findCapFile: trying chroot ls fallback")
        val chrootLs =
            chrootManager.executeInChroot("ls -1 \"$dir\" 2>/dev/null | grep -E '\\-01\\.(cap|pcap|pcapng)$'")
        Log.d(TAG, "findCapFile: chroot ls out=${chrootLs.out}")

        for (line in chrootLs.out) {
            val name = line.trim()
            if (name.isEmpty()) continue
            val chrootFile = "$dir/$name"
            cachedCapFile = chrootFile
            Log.d(TAG, "findCapFile: chroot file found at $chrootFile")
            return chrootFile
        }

        Log.w(TAG, "findCapFile: no cap file in JVM or chroot for $dir")
        return null
    }

    private val HANDSHAKE_REGEX = Regex(
        """WPA\s*handshake:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""",
        RegexOption.IGNORE_CASE
    )

    private val ESSID_REGEX = Regex(
        """ESSID:\s*(.+)""",
        RegexOption.IGNORE_CASE
    )

    private val KEY_FOUND_REGEX = Regex(
        """KEY\s*FOUND!\s*\[([^\]]+)]""",
        RegexOption.IGNORE_CASE
    )

    private val PMKID_AIRCRACK_REGEX = Regex(
        """\(.*\d+\s*handshake.*PMKID|PMKID.*\d+\s*handshake|with\s*PMKID""",
        RegexOption.IGNORE_CASE
    )

    suspend fun disableHostWifi(): Boolean = withContext(Dispatchers.IO) {
        chrootManager.disableWifiOnHost()
    }

    suspend fun enableHostWifi(): Boolean = withContext(Dispatchers.IO) {
        chrootManager.enableWifiOnHost()
    }
}
