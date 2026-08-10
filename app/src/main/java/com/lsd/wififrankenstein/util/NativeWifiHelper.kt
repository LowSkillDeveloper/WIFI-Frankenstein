package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwLinkInfo
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeWifiHelper(private val context: Context) {

    private val iwWifiManager = IwWifiManager(context)
    private val ifaceRegex = Regex("^[a-zA-Z0-9]+$")

    fun isArmArchitecture(): Boolean = NativeWifiBinaries.isArmArchitecture(context)

    private fun isValidInterface(interfaceName: String): Boolean =
        interfaceName.isNotEmpty() && ifaceRegex.matches(interfaceName)

    private fun iwCommand(args: String): String {
        val dir = NativeWifiBinaries.binaryDir(context)
        return "cd $dir && export LD_LIBRARY_PATH=$dir && ./${NativeWifiBinaries.iwAssetName()} $args"
    }

    private fun run(args: String): String {
        return try {
            val result = Shell.cmd(iwCommand(args)).exec()
            Log.d(
                TAG,
                "iw $args -> exit=${result.code} outLines=${result.out.size} errLines=${result.err.size}"
            )
            (result.out + result.err).joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "iw $args failed", e)
            ""
        }
    }

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        NativeWifiBinaries.ensure(context)
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        val output = run("dev")
        if (output.isBlank()) {
            Log.d(TAG, "iw dev returned no output")
            emptyList()
        } else {
            val ifaces = iwWifiManager.parseInterfacesList(output)
            Log.d(TAG, "iw dev -> ${ifaces.size} interfaces")
            ifaces
        }
    }

    suspend fun getInterfaceMode(interfaceName: String): String = withContext(Dispatchers.IO) {
        if (!isValidInterface(interfaceName)) return@withContext IwWifiManager.MODE_UNKNOWN
        val output = run("dev $interfaceName info")
        val mode = when {
            output.contains("type monitor", ignoreCase = true) -> IwWifiManager.MODE_MONITOR
            output.contains("type managed", ignoreCase = true) -> IwWifiManager.MODE_MANAGED
            output.contains("type IBSS", ignoreCase = true) -> "ibss"
            output.contains("type AP", ignoreCase = true) -> "ap"
            else -> IwWifiManager.MODE_UNKNOWN
        }
        Log.d(TAG, "iw dev $interfaceName info -> mode=$mode")
        mode
    }

    suspend fun scanWifiNetworks(interfaceName: String): List<IwWifiNetwork> =
        withContext(Dispatchers.IO) {
            if (!isValidInterface(interfaceName)) return@withContext emptyList()
            val up = Shell.cmd("ip link set $interfaceName up 2>/dev/null").exec()
            Log.d(TAG, "ip link set $interfaceName up -> exit=${up.code}")
            val output = run("dev $interfaceName scan")
            if (output.isBlank()) {
                Log.d(TAG, "iw scan returned no output for $interfaceName")
                emptyList()
            } else {
                val nets = iwWifiManager.parseWifiNetworks(output)
                Log.d(
                    TAG,
                    "iw scan $interfaceName -> ${nets.size} networks from ${output.lines().size} lines"
                )
                nets
            }
        }

    suspend fun getLinkInfo(interfaceName: String): IwLinkInfo = withContext(Dispatchers.IO) {
        if (!isValidInterface(interfaceName)) return@withContext IwLinkInfo()
        val output = run("dev $interfaceName link")
        if (output.isBlank()) {
            Log.d(TAG, "iw link returned no output for $interfaceName")
            IwLinkInfo()
        } else {
            iwWifiManager.parseLinkInfo(output)
        }
    }

    suspend fun getRawScanForBssid(interfaceName: String, bssid: String): String? =
        withContext(Dispatchers.IO) {
            if (!isValidInterface(interfaceName)) return@withContext null
            Shell.cmd("ip link set $interfaceName up 2>/dev/null").exec()
            val output = run("dev $interfaceName scan")
            if (output.isBlank()) return@withContext null

            val bssBlocks = output.split("\n\n").filter { it.isNotBlank() }
            for (block in bssBlocks) {
                if (block.contains("BSS $bssid")) {
                    return@withContext block
                }
            }
            null
        }

    suspend fun setInterfaceMode(interfaceName: String, mode: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isValidInterface(interfaceName)) return@withContext false
            try {
                if (mode != IwWifiManager.MODE_MANAGED && mode != IwWifiManager.MODE_MONITOR) {
                    return@withContext false
                }
                Shell.cmd("ip link set $interfaceName down 2>/dev/null").exec()
                run("dev $interfaceName set type $mode")
                Shell.cmd("ip link set $interfaceName up 2>/dev/null").exec()
                getInterfaceMode(interfaceName) == mode
            } catch (e: Exception) {
                Log.e(TAG, "setInterfaceMode failed", e)
                false
            }
        }

    companion object {
        private const val TAG = "NativeWifiHelper"
    }
}
