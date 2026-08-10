package com.lsd.wififrankenstein.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class PixieDustResult(
    val wpsPin: String?,
    val wpaPsk: String?,
    val success: Boolean,
    val rawOutput: String,
    val reason: String? = null
)

class PixieDustRunner(private val context: Context) {

    private val chrootManager = ChrootManager(context)

    companion object {
        private const val TAG = "PixieDustRunner"
        private const val PIXIE_SCRIPT = "/opt/PixieWps/pixie.py"
        private val PIN_REGEX =
            Regex("(\\[\\+\\]\\s*WPS\\s*PIN:\\s*|\\[\\+\\]\\s*WPS\\s*pin:\\s*)'?([\\d]{4,8})'?")
        private val PSK_REGEX =
            Regex("(\\[\\+\\]\\s*WPA\\s*PSK:\\s*|\\[\\+\\]\\s*WPA\\s*psk:\\s*)'?([^\\s']+)'?")
        private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        @Deprecated("Use parseOutput directly")
        fun canParsePin(output: String): String? {
            val match = PIN_REGEX.find(output)
            return match?.groups?.get(2)?.value
        }

        @Deprecated("Use parseOutput directly")
        fun canParsePsk(output: String): String? {
            val match = PSK_REGEX.find(output)
            return match?.groups?.get(2)?.value?.trim('"')
        }
    }

    private var currentProcess: Process? = null
    private val processLock = Any()

    suspend fun runAttack(
        bssid: String,
        interfaceName: String = "wlan0",
        disableWifiBeforeAttack: Boolean = true,
        pin: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): PixieDustResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "=== ATTACK START ===")
            Log.d(
                TAG,
                "Parameters: bssid=$bssid, interface=$interfaceName, disableWifiBeforeAttack=$disableWifiBeforeAttack, pin=$pin"
            )

            try {
                withTimeout(60_000L) {
                    if (disableWifiBeforeAttack && chrootManager.disableWifiOnHost()) {
                        Log.d(TAG, "WiFi disabled via svc on host")
                        delay(2000)
                    } else {
                        Log.d(TAG, "WiFi not disabled (already disabled or skipped)")
                    }

                    val cmd = buildPixieDpsCommand(bssid, interfaceName, pin)
                    Log.d(TAG, "Built command: $cmd")

                    Log.d(TAG, "Step 2.5: Checking chroot binaries...")
                    val binaryCheck = chrootManager.checkChrootBinaries()
                    Log.d(TAG, "Binary check result: $binaryCheck")
                    if (!binaryCheck) {
                        Log.e(TAG, "Chroot binary check failed — attack may fail")
                    }

                    Log.d(TAG, "Step 3: Executing attack in persistent chroot session...")
                    onProgress?.invoke("Starting pixieDPS attack...")

                    val result = chrootManager.executePersistentSession(cmd, onOutput = { line ->
                        Log.d(TAG, "Chroot output: $line")
                        onProgress?.invoke(line)
                    }, sessionTimeout = 120_000)

                    synchronized(processLock) {
                        currentProcess = result.process
                    }

                    Log.d(
                        TAG,
                        "Step 3 completed: stdout=${result.stdout.size} lines, stderr=${result.stderr.size} lines"
                    )
                    Log.d(TAG, "Stdout sample: ${result.stdout.take(3).joinToString(", ")}")
                    Log.d(TAG, "Stderr sample: ${result.stderr.take(3).joinToString(", ")}")

                    Log.d(TAG, "=== FULL RAW OUTPUT ===")
                    Log.d(
                        TAG,
                        "STDOUT (${result.stdout.size} lines): ${result.stdout.joinToString("\n")}"
                    )
                    Log.d(
                        TAG,
                        "STDERR (${result.stderr.size} lines): ${result.stderr.joinToString("\n")}"
                    )
                    Log.d(TAG, "=== END RAW OUTPUT ===")

                    Log.d(TAG, "Step 4: Parsing output...")
                    val combinedOutput =
                        result.stdout.joinToString("\n") + "\n" + result.stderr.joinToString("\n")
                    Log.d(TAG, "Combined output length: ${combinedOutput.length} chars")

                    val parsed = parseOutput(combinedOutput)
                    Log.d(
                        TAG,
                        "Step 4 completed: parsed pin=${parsed.wpsPin}, psk=${parsed.wpaPsk}, success=${parsed.success}"
                    )

                    Log.d(TAG, "=== ATTACK COMPLETE ===")
                    parsed
                }

            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Attack timed out after 1 minute")
                cancel()
                PixieDustResult(null, null, false, "TIMEOUT: Attack exceeded 2 minutes")

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e

            } catch (e: Exception) {
                Log.e(TAG, "Attack threw exception", e)
                PixieDustResult(null, null, false, "ERROR: ${e.message}")

            } finally {
                try {
                    Log.d(TAG, "Re-enabling WiFi via svc on host...")
                    chrootManager.enableWifiOnHost()
                    Thread.sleep(2000)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-enable WiFi on host", e)
                }

                synchronized(processLock) {
                    currentProcess = null
                }
            }
        }
    }

    private fun buildPixieDpsCommand(bssid: String, iface: String, pin: String? = null): String {
        if (bssid.isNotEmpty() && !BSSID_REGEX.matches(bssid)) {
            Log.e(TAG, "Invalid BSSID format — rejecting: $bssid")
            throw IllegalArgumentException("Invalid BSSID format: $bssid")
        }

        val pinArg = if (pin != null) " -p \"$pin\"" else ""
        val pixieCmd = "python3 -u $PIXIE_SCRIPT -i $iface --iface-down -v -K -F -b $bssid$pinArg"
        val cmd = "$pixieCmd ; echo PIXIE_DONE"
        Log.d(TAG, "pixie.py script: $PIXIE_SCRIPT")
        Log.d(TAG, "interface: $iface")
        Log.d(TAG, "bssid: $bssid")
        Log.d(TAG, "pin: ${pin ?: "default (from pixie.py)"}")
        Log.d(TAG, "Full command: $cmd")
        return cmd
    }

    suspend fun cancel() {
        Log.d(TAG, "PixieDust attack cancellation requested")
        chrootManager.cancelSession()
        chrootManager.forceCleanup()
    }

    private fun parseOutput(output: String): PixieDustResult {
        var pin: String? = null
        var psk: String? = null
        var success = false

        Log.d(TAG, "Parsing output (${output.lines().size} lines)...")

        val lines = output.split("\n")

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) continue


            val pinMatch = PIN_REGEX.find(trimmed)
            if (pinMatch != null) {
                pin = pinMatch.groups[2]?.value
                Log.d(TAG, "Line $index: Found WPS PIN: $pin")
            }


            val pskMatch = PSK_REGEX.find(trimmed)
            if (pskMatch != null) {
                psk = pskMatch.groups[2]?.value?.trim('"')
                Log.d(TAG, "Line $index: Found WPA PSK: $psk")
            }
        }


        val lastLines = output.lines().takeLast(10)
        val hasTimeout = lastLines.any { it.contains("timeout", ignoreCase = true) }
        val hasTerminated = lastLines.any { it.contains("Terminated", ignoreCase = true) }


        val hasNotEnoughData = lastLines.any {
            it.contains(
                "Not enough data to run Pixie Dust attack",
                ignoreCase = true
            )
        }

        if (pin != null && psk != null) {

            success = true
            Log.d(TAG, "PIN and PSK found — ignoring timeout/termination indicators")
        } else if (hasNotEnoughData) {
            Log.w(TAG, "PixieDust: not enough data collected (AP may not support WPS Pixie)")
            success = false
        } else if (hasTimeout || hasTerminated) {
            Log.w(TAG, "Timeout or termination detected in last lines — marking as failed")
            success = false
        } else if (pin != null) {
            success = true
        }

        Log.d(TAG, "Parse complete: pin=$pin, psk=$psk, success=$success")
        return PixieDustResult(pin, psk, success, output)
    }
}
