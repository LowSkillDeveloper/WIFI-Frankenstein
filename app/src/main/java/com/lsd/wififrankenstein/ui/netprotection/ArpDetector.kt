package com.lsd.wififrankenstein.ui.netprotection

import android.util.Log
import com.lsd.wififrankenstein.WifiApplication
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.FileReader

class ArpDetector {

    data class ArpEntry(
        val ip: String,
        val mac: String,
        val interfaceName: String
    )

    private var knownGatewayMac: String? = null
    private var lastGateway: String? = null

    fun getGatewayMac(): String? = knownGatewayMac
    fun getLastGateway(): String? = lastGateway

    internal fun evaluateGatewayChange(previous: String?, current: String?): ArpChangeResult {
        return if (previous != null && current != null && previous != current) {
            ArpChangeResult(true, previous, current)
        } else {
            ArpChangeResult(false, null, null)
        }
    }

    fun checkForSpoof(gatewayIp: String): ArpChangeResult {
        if (gatewayIp.isEmpty()) {
            Log.w(TAG, "Gateway IP is empty, skipping check")
            return ArpChangeResult(false, null, null)
        }

        val currentMac = tryReadArpViaProc(gatewayIp)
            ?: tryReadArpViaIpNeigh(gatewayIp)
            ?: tryReadArpViaShell(gatewayIp)

        if (currentMac == null) {
            Log.w(TAG, "Could not read MAC for gateway $gatewayIp via any method")
            return ArpChangeResult(false, null, null)
        }

        val previous = knownGatewayMac
        knownGatewayMac = currentMac

        Log.d(TAG, "ARP check: gateway=$gatewayIp currentMAC=$currentMac knownMAC=$previous")

        return evaluateGatewayChange(previous, currentMac).also { result ->
            if (result.changed) {
                Log.w(TAG, "ARP SPOOF DETECTED! $gatewayIp: ${result.oldMac} -> ${result.newMac}")
            } else if (previous == null) {
                Log.i(TAG, "First ARP read: $gatewayIp -> $currentMac (baseline set)")
            }
        }
    }

    private fun tryReadArpViaProc(gatewayIp: String): String? {
        return try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.readLine()
                var result: String? = null
                var line = reader.readLine()
                while (line != null && result == null) {
                    result = parseProcArpEntry(line, gatewayIp)
                    line = reader.readLine()
                }
                result
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read /proc/net/arp: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ArpDetector"

        internal fun parseProcArpEntry(line: String, gatewayIp: String): String? {
            val parts = line.split("\\s+".toRegex())
            return if (parts.size >= 4 && parts[0] == gatewayIp &&
                parts[3] != "00:00:00:00:00:00"
            ) {
                parts[3].uppercase()
            } else {
                null
            }
        }

        internal fun parseProcArpTable(content: String): List<ArpEntry> {
            val entries = mutableListOf<ArpEntry>()
            content.lines().drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") {
                    entries.add(ArpEntry(parts[0], parts[3].uppercase(), parts.getOrNull(5) ?: ""))
                }
            }
            return entries
        }

        internal fun parseIpNeighOutput(output: String): List<ArpEntry> {
            val entries = mutableListOf<ArpEntry>()
            for (line in output.split("\n")) {
                if (line.isBlank()) continue
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 5) {
                    val ip = parts[0]
                    val mac = parts[4]
                    if (mac.length == 17 && mac.contains(":")) {
                        entries.add(ArpEntry(ip, mac.uppercase(), parts[2]))
                    }
                }
            }
            return entries
        }
    }

    private fun tryReadArpViaIpNeigh(gatewayIp: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", gatewayIp))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.contains("Permission denied") || output.isEmpty()) {
                Log.d(TAG, "ip neigh denied or empty for $gatewayIp")
                return null
            }

            val macRegex = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
            macRegex.find(output)?.value?.uppercase()
        } catch (e: Exception) {
            Log.d(TAG, "ip neigh failed for $gatewayIp: ${e.message}")
            null
        }
    }

    private fun tryReadArpViaShell(gatewayIp: String): String? {
        if (WifiApplication.isRootAvailable != true) return null
        return try {
            val result = Shell.cmd("ip neigh show $gatewayIp").exec()
            if (!result.isSuccess) return null
            val output = result.out.joinToString("\n")
            Log.d(TAG, "Shell ip neigh for $gatewayIp: $output")
            val macRegex = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
            macRegex.find(output)?.value?.uppercase()
        } catch (e: Exception) {
            Log.d(TAG, "Shell ip neigh failed: ${e.message}")
            null
        }
    }

    fun getArpTable(): List<ArpEntry> {
        val entries = mutableListOf<ArpEntry>()

        val fromProc = tryReadArpTableFromProc()
        if (fromProc.isNotEmpty()) return fromProc

        val fromShell = tryReadArpTableFromShell()
        if (fromShell.isNotEmpty()) return fromShell

        val fromIpNeigh = tryReadArpTableFromIpNeigh()
        entries.addAll(fromIpNeigh)

        return entries
    }

    private fun tryReadArpTableFromProc(): List<ArpEntry> {
        return try {
            val sb = StringBuilder()
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                reader.forEachLine { line -> sb.appendLine(line) }
            }
            parseProcArpTable(sb.toString())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryReadArpTableFromShell(): List<ArpEntry> {
        if (WifiApplication.isRootAvailable != true) return emptyList()
        return try {
            val result = Shell.cmd("ip neigh show").exec()
            if (!result.isSuccess) return emptyList()
            parseIpNeighOutput(result.out.joinToString("\n"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryReadArpTableFromIpNeigh(): List<ArpEntry> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.contains("Permission denied") || output.isEmpty()) return emptyList()
            parseIpNeighOutput(output)
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class ArpChangeResult(
        val changed: Boolean,
        val oldMac: String?,
        val newMac: String?
    )
}
