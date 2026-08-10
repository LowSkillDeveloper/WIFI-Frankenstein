package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay

class BettercapManager(private val context: Context) {

    private val chrootManager = ChrootManager.get(context)
    private val iwWifiManager = IwWifiManager(context)

    @Volatile
    private var monitorIfaceUsed: String? = null

    companion object {
        private const val TAG = "BettercapManager"
        private const val REST_USER = "wff"
        private const val REST_PASS = "wff"
        private const val REST_PORT = 8081

        fun buildEvalString(iface: String, channelMode: String = "auto"): String {
            val handshakeFile = "/sdcard/WIFI-Frankenstein/captured/hs"
            return buildString {
                append("set api.rest.username $REST_USER; ")
                append("set api.rest.password $REST_PASS; ")
                append("set api.rest.port $REST_PORT; ")
                append("set wifi.interface $iface; ")
                append("set wifi.handshakes.file $handshakeFile; ")
                append("set wifi.handshakes.aggregate false; ")
                append("set wifi.rssi.min -90; ")
                append("set wifi.ap.ttl 300; ")
                append("set wifi.hop.period 250; ")
                append("set wifi.deauth.open true; ")
                append("set wifi.deauth.acquired false; ")
                append("set wifi.assoc.open false; ")
                append("set wifi.assoc.acquired false; ")
                if (channelMode != "auto" && channelMode.isNotEmpty()) {
                    append("wifi.recon.channel $channelMode; ")
                }
                append("wifi.recon on; ")
                append("api.rest on")
            }
        }
    }






    suspend fun startDaemon(
        iface: String,
        channelMode: String = "auto",
        onOutput: ((String) -> Unit)? = null
    ): String? {
        Log.d(TAG, "Starting bettercap daemon (iface=$iface, channel=$channelMode)")
        monitorIfaceUsed = null
        val monitorIface = ensureMonitorMode(iface, onOutput) ?: return null
        monitorIfaceUsed = monitorIface
        val evalStr = buildEvalString(monitorIface, channelMode)
        val cmd = "nice -n -10 /usr/bin/bettercap -eval \"$evalStr\" -no-history 2>&1"
        val started = chrootManager.executeDaemonSession(cmd, onOutput = onOutput)
        if (!started) {
            Log.e(TAG, "bettercap daemon session failed to start on $monitorIface")
            return null
        }
        return monitorIface
    }

    suspend fun stopDaemon() {
        Log.d(TAG, "Stopping bettercap daemon")
        try {
            Shell.cmd("killall -9 bettercap 2>/dev/null").exec()
        } catch (_: Exception) {
        }
        delay(300)
        restoreInterfaceToManaged()
        chrootManager.stopDaemonSession()
    }

    fun isDaemonRunning(): Boolean = chrootManager.isDaemonRunning()

    private suspend fun ensureMonitorMode(
        iface: String,
        onOutput: ((String) -> Unit)?
    ): String? {
        if (chrootManager.getChrootType() !is ChrootType.Root) {
            Log.w(TAG, "Chroot not Root — skipping monitor mode switch, using $iface")
            return iface
        }
        val baseName = iface.removeSuffix("mon")
        onOutput?.invoke("[*] Checking interface $baseName mode...")
        val existingMon = iwWifiManager.findMonitorInterface(baseName)
        if (existingMon != null) {
            Log.d(TAG, "Interface already in monitor mode: $existingMon")
            onOutput?.invoke("[+] Interface already in monitor mode ($existingMon)")
            return existingMon
        }
        onOutput?.invoke("[*] Switching $baseName to monitor mode...")
        val switched = iwWifiManager.setInterfaceMode(baseName, IwWifiManager.MODE_MONITOR)
        if (!switched) {
            val err = iwWifiManager.lastModeSwitchError
            Log.e(TAG, "Failed to switch $baseName to monitor mode: $err")
            onOutput?.invoke(
                "[-] Failed to switch $baseName to monitor mode: ${err ?: "unknown error"}"
            )
            return null
        }
        delay(1000)
        val monIface = iwWifiManager.findMonitorInterface(baseName) ?: baseName
        Log.d(TAG, "Bettercap will use monitor interface $monIface")
        onOutput?.invoke("[+] Bettercap will use monitor interface $monIface")
        return monIface
    }

    private suspend fun restoreInterfaceToManaged() {
        val monIface = monitorIfaceUsed ?: return
        monitorIfaceUsed = null
        if (chrootManager.getChrootType() !is ChrootType.Root) return
        try {
            Log.d(TAG, "Restoring $monIface to managed mode")
            val restored = iwWifiManager.setInterfaceMode(monIface, IwWifiManager.MODE_MANAGED)
            if (!restored) {
                Log.w(
                    TAG,
                    "Failed to restore $monIface to managed mode: ${iwWifiManager.lastModeSwitchError}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore $monIface to managed mode", e)
        }
    }
}
