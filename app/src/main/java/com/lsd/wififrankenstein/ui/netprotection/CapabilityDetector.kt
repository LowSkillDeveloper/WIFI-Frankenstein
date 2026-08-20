package com.lsd.wififrankenstein.ui.netprotection

import android.content.Context
import android.os.Build
import com.lsd.wififrankenstein.WifiApplication
import java.io.BufferedReader
import java.io.FileReader

enum class DetectorCapability {
    FULL,
    LIMITED,
    UNAVAILABLE
}

data class DetectionResult(
    val arpCapability: DetectorCapability,
    val portScanCapability: DetectorCapability,
    val connectionMonitorCapability: DetectorCapability,
    val isRoot: Boolean,
    val androidVersion: Int
) {
    val overallLevel: DetectorCapability
        get() = when {
            arpCapability == DetectorCapability.FULL && portScanCapability == DetectorCapability.FULL -> DetectorCapability.FULL
            arpCapability != DetectorCapability.UNAVAILABLE || portScanCapability != DetectorCapability.UNAVAILABLE -> DetectorCapability.LIMITED
            else -> DetectorCapability.UNAVAILABLE
        }
}

object CapabilityDetector {

    fun detect(context: Context): DetectionResult {
        val isRoot = WifiApplication.isRootAvailable == true
        val sdk = Build.VERSION.SDK_INT

        if (isRoot) {
            return DetectionResult(
                arpCapability = DetectorCapability.FULL,
                portScanCapability = DetectorCapability.FULL,
                connectionMonitorCapability = DetectorCapability.FULL,
                isRoot = true,
                androidVersion = sdk
            )
        }

        val arpCap = detectArpAccess()
        val portCap = detectPortScanAccess()
        val connCap = detectConnectionAccess()

        return DetectionResult(
            arpCapability = arpCap,
            portScanCapability = portCap,
            connectionMonitorCapability = connCap,
            isRoot = false,
            androidVersion = sdk
        )
    }

    private fun detectArpAccess(): DetectorCapability {
        if (canReadProcArp()) return DetectorCapability.FULL
        if (canRunIpNeigh()) return DetectorCapability.FULL
        return DetectorCapability.LIMITED
    }

    private fun detectPortScanAccess(): DetectorCapability {
        if (canReadProcSnmp()) return DetectorCapability.FULL
        return DetectorCapability.LIMITED
    }

    private fun detectConnectionAccess(): DetectorCapability {
        if (canReadProcTcp()) return DetectorCapability.FULL
        return DetectorCapability.LIMITED
    }

    fun canReadProcArp(): Boolean {
        return try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                val line = reader.readLine()
                line != null && line.contains("IP address")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun canReadProcTcp(): Boolean {
        return try {
            BufferedReader(FileReader("/proc/net/tcp")).use { reader ->
                val line = reader.readLine()
                line != null && line.contains("sl")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun canReadProcSnmp(): Boolean {
        return try {
            BufferedReader(FileReader("/proc/net/snmp")).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trimStart().startsWith("Tcp:")) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun canRunIpNeigh(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.isNotEmpty() && !output.contains("Permission denied")
        } catch (_: Exception) {
            false
        }
    }
}
