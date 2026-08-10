package com.lsd.wififrankenstein.ui.localnetwork

import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import jcifs.netbios.NbtAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.regex.Pattern

class LocalNetworkScanner(private val chrootManager: ChrootManager) {

    suspend fun detectSubnet(wlanInterface: String = "wlan0"): SubnetInfo? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Detecting subnet for interface: $wlanInterface")

                var localIp = ""
                var prefixLength = 24
                var gateway = ""

                val nif = NetworkInterface.getByName(wlanInterface)
                if (nif != null) {
                    for (addr in nif.interfaceAddresses) {
                        if (addr.address is Inet4Address) {
                            localIp = addr.address.hostAddress ?: ""
                            prefixLength = addr.networkPrefixLength.toInt()
                            break
                        }
                    }
                }

                if (localIp.isEmpty()) {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        for (addr in iface.interfaceAddresses) {
                            if (addr.address is Inet4Address && !addr.address.isLoopbackAddress) {
                                localIp = addr.address.hostAddress ?: ""
                                prefixLength = addr.networkPrefixLength.toInt()
                                break
                            }
                        }
                        if (localIp.isNotEmpty()) break
                    }
                }

                gateway = parseGatewayFromProc(wlanInterface)

                if (localIp.isEmpty()) {
                    Log.e(TAG, "Cannot detect subnet — no local IP found")
                    return@withContext null
                }

                val base = localIp.substringBeforeLast(".", "")
                val subnet = "$base.0/$prefixLength"
                val cidr = "$base.0/$prefixLength"

                if (gateway.isEmpty()) {
                    gateway = "$base.1"
                }

                Log.d(TAG, "Detected subnet: $subnet, gateway: $gateway, localIp: $localIp")
                SubnetInfo(
                    gateway = gateway,
                    subnet = subnet,
                    cidr = cidr,
                    wlanInterface = wlanInterface,
                    localIp = localIp
                )
            } catch (e: Exception) {
                Log.e(TAG, "Subnet detection failed", e)
                null
            }
        }

    private fun parseGatewayFromProc(iface: String): String {
        return try {
            val br = BufferedReader(FileReader("/proc/net/route"))
            br.use { reader ->
                reader.readLine()
                for (line in reader.lines().toArray().filterIsInstance<String>()) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[0] == iface && parts[1] == "00000000") {
                        val gwHex = parts[2].padStart(8, '0')
                        val bytes = gwHex.chunked(2).map { it.toInt(16) }
                        if (bytes.size == 4) {
                            return "${bytes[3]}.${bytes[2]}.${bytes[1]}.${bytes[0]}"
                        }
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }


    suspend fun pingSweep(
        subnet: String,
        onProgress: (ScanProgress) -> Unit = {}
    ): List<LocalDevice> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting ping sweep on $subnet")
                onProgress(ScanProgress(phase = "ping_sweep", line = "Scanning $subnet..."))

                val nmapFlags = "-sP -n"
                val cmd = "nmap $subnet $nmapFlags --stats-every 2s && echo __SCAN_DONE__"

                val (out, _) = chrootManager.executeInChrootWithRoot(
                    command = cmd,
                    sessionTimeout = 120_000,
                    onOutput = { line ->
                        if (!line.contains("route_dst_netlink") && !line.startsWith("[stderr]")) {
                            onProgress(ScanProgress(phase = "ping_sweep", line = line))
                        }
                    }
                )
                val stdout = out

                Log.d(TAG, "Ping sweep complete: ${stdout.size} lines")
                parsePingSweepOutput(stdout, onProgress, hostCountForCidr(subnet))
            } catch (e: Exception) {
                Log.e(TAG, "Ping sweep failed", e)
                onProgress(ScanProgress(phase = "error", line = "Ping sweep failed: ${e.message}"))
                emptyList()
            }
        }
    }

    private fun parsePingSweepOutput(
        lines: List<String>,
        onProgress: (ScanProgress) -> Unit = {},
        totalHosts: Int = 0
    ): List<LocalDevice> {
        val devices = mutableListOf<LocalDevice>()
        var currentDevice: LocalDevice? = null
        val macPattern = Pattern.compile("(([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})")

        var hostCount = 0
        for (line in lines) {
            when {
                line.startsWith("Nmap scan report for ") -> {
                    currentDevice?.let { if (it.isAlive) devices.add(it) }
                    val ip = line.removePrefix("Nmap scan report for ").trim()
                    currentDevice = LocalDevice(ip = ip, isAlive = true)
                    hostCount++
                    onProgress(
                        ScanProgress(
                            phase = "parsing",
                            current = hostCount,
                            total = totalHosts,
                            line = "Found: $ip"
                        )
                    )
                }

                line.contains("Host is up") && currentDevice != null -> {
                    val timeMatch = Regex("latency ([0-9.]+)").find(line)
                    val time = timeMatch?.groupValues?.get(1)?.toFloatOrNull()?.let {
                        (it * 1000).toLong()
                    } ?: 0L
                    currentDevice = currentDevice.copy(responseTimeMs = time)
                }

                line.contains("MAC Address:") && currentDevice != null -> {
                    val macMatcher = macPattern.matcher(line)
                    if (macMatcher.find()) {
                        val mac = macMatcher.group(1)?.uppercase() ?: ""
                        val vendor = line.substringAfter("MAC Address: ")
                            .substringAfter("$mac ")
                            .replace("(", "")
                            .replace(")", "")
                            .trim()
                        currentDevice = currentDevice.copy(mac = mac, vendor = vendor)
                    }
                }
            }
        }
        currentDevice?.let { if (it.isAlive) devices.add(it) }

        for (device in devices) {
            try {
                val addr = InetAddress.getByName(device.ip)
                val host = addr.hostName ?: ""
                if (host.isNotEmpty() && host != device.ip) {
                    val idx = devices.indexOf(device)
                    devices[idx] = device.copy(hostname = host)
                }
            } catch (e: Exception) {
                Log.w("LocalNetworkScanner", "Failed to resolve hostname for ${device.ip}", e)
            }
        }

        Log.d(TAG, "Parsed ${devices.size} devices from ping sweep")
        return devices
    }

    private fun hostCountForCidr(subnet: String): Int {
        val prefix = subnet.substringAfter("/").toIntOrNull() ?: 24
        val hostBits = 32 - prefix
        val hosts = (1L shl hostBits) - 2
        return if (hosts > Int.MAX_VALUE) Int.MAX_VALUE else hosts.toInt().coerceAtLeast(1)
    }

    suspend fun scanDevicePorts(
        device: LocalDevice,
        fastScan: Boolean = true,
        onProgress: (ScanProgress) -> Unit = {}
    ): LocalDevice {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Scanning ports on ${device.ip} (fast=$fastScan)")
                onProgress(ScanProgress(phase = "port_scan", line = "Scanning ${device.ip}..."))

                val scanFlags =
                    if (fastScan) "-F --top 100 -n -Pn -O --max-os-tries 1" else "-n -Pn -O --max-os-tries 1"
                val cmd = "nmap ${device.ip} $scanFlags && echo __SCAN_DONE__"
                val (out, _) = chrootManager.executeInChrootWithRoot(
                    command = cmd,
                    sessionTimeout = 120_000,
                    onOutput = { line ->
                        if (!line.contains("route_dst_netlink") && !line.startsWith("[stderr]")) {
                            onProgress(ScanProgress(phase = "port_scan", line = line))
                        }
                    }
                )
                val stdout = out

                Log.d(TAG, "Port scan complete for ${device.ip}: ${stdout.size} lines")
                val result = parsePortScanOutput(device, stdout)
                result.copy(netbiosName = resolveNetbiosName(device.ip))
            } catch (e: Exception) {
                Log.e(TAG, "Port scan failed for ${device.ip}", e)
                onProgress(
                    ScanProgress(
                        phase = "error",
                        line = "Port scan failed for ${device.ip}: ${e.message}"
                    )
                )
                device
            }
        }
    }

    private fun parsePortScanOutput(baseDevice: LocalDevice, lines: List<String>): LocalDevice {
        val ports = mutableListOf<Int>()
        var os = baseDevice.os
        var osFamily = ""
        var osCpe = ""
        var deviceType = ""
        var networkDistance = ""
        var mac = baseDevice.mac
        var vendor = baseDevice.vendor
        var responseTimeMs = baseDevice.responseTimeMs
        val macPattern = Pattern.compile("(([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})")
        val latencyPattern = Pattern.compile("Host is up \\(([0-9.]+)s latency\\)")

        for (line in lines) {
            when {
                line.contains("/tcp") && line.contains("open") -> {
                    val portStr = line.substringBefore("/")
                    portStr.toIntOrNull()?.let { ports.add(it) }
                }

                line.contains("MAC Address:") -> {
                    val macMatcher = macPattern.matcher(line)
                    if (macMatcher.find()) {
                        mac = macMatcher.group(1)?.uppercase() ?: ""
                        vendor = line.substringAfter("MAC Address: ")
                            .substringAfter("$mac ")
                            .replace("(", "")
                            .replace(")", "")
                            .trim()
                    }
                }

                line.contains("Device type:") -> {
                    deviceType = line.substringAfter("Device type:").trim()
                }

                line.contains("Running:") -> {
                    osFamily = line.substringAfter("Running:").trim()
                }

                line.contains("OS CPE:") -> {
                    osCpe = line.substringAfter("OS CPE:").trim()
                }

                line.contains("OS details:") -> {
                    os = line.substringAfter("OS details:").trim()
                }

                line.contains("Aggressive OS guesses:") -> {
                    val guess = line.substringAfter(": ").substringBefore(",").trim()
                    os = if (os.isNotEmpty()) "$os; $guess" else guess
                }

                line.contains("Network Distance:") -> {
                    networkDistance = line.substringAfter("Network Distance:").trim()
                }

                line.contains("Host is up") && line.contains("latency") -> {
                    val latMatcher = latencyPattern.matcher(line)
                    if (latMatcher.find()) {
                        val secs = latMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                        responseTimeMs = (secs * 1000).toLong()
                    }
                }
            }
        }

        val sortedPorts = ports.sorted()
        val osType = guessOsByString(os, sortedPorts, vendor)

        return baseDevice.copy(
            mac = mac,
            vendor = vendor,
            openPorts = sortedPorts,
            os = os,
            osType = osType,
            osFamily = osFamily,
            osCpe = osCpe,
            deviceType = deviceType,
            networkDistance = networkDistance,
            responseTimeMs = responseTimeMs
        )
    }

    private fun guessOsByString(os: String, ports: List<Int>, vendor: String = ""): OSType {

        val lower = os.lowercase()
        val nmapGuess = when {
            lower.contains("android") || ports.contains(5555) -> OSType.ANDROID
            lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad") || ports.contains(
                62078
            ) -> OSType.IOS

            lower.contains("windows") || ports.any { it in 135..139 } || ports.contains(445) || ports.contains(
                3389
            ) || ports.contains(5357) -> OSType.WINDOWS

            lower.contains("apple") || lower.contains("mac os") || lower.contains("darwin") -> OSType.MACOS
            lower.contains("linux") || lower.contains("unix") -> OSType.LINUX
            ports.contains(9100) || ports.contains(515) -> OSType.PRINTER
            ports.contains(554) || ports.contains(37777) -> OSType.CAMERA
            lower.contains("printer") || lower.contains("print") -> OSType.PRINTER
            lower.contains("camera") || lower.contains("dvr") || lower.contains("ip cam") -> OSType.CAMERA
            lower.contains("embedded") || lower.contains("lwip")
                    || lower.contains("gosund") || lower.contains("tuya")
                    || lower.contains("smart") || lower.contains("iot") -> OSType.EMBEDDED

            else -> null
        }


        val ven = vendor.lowercase()
        val vendorGuess = when {
            ven.contains("apple") -> OSType.MACOS
            ven.contains("microsoft") -> OSType.WINDOWS
            ven.contains("hikvision") || ven.contains("dahua") -> OSType.CAMERA
            ven.contains("tuya") || ven.contains("espressif") || ven.contains("texas instruments")
                    || ven.contains("qualcomm") || ven.contains("broadcom") -> OSType.EMBEDDED

            else -> null
        }

        return vendorGuess ?: nmapGuess ?: OSType.UNKNOWN
    }

    suspend fun getWlanInterfaces(): List<String> = withContext(Dispatchers.IO) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val wlans = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.contains("wlan")) {
                    wlans.add(iface.name)
                }
            }
            wlans.reversed()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list interfaces", e)
            listOf("wlan0")
        }
    }

    private fun resolveNetbiosName(ip: String): String {
        return try {
            val nbts = NbtAddress.getAllByAddress(ip)
            val name = nbts.lastOrNull()?.hostName ?: ""
            if (name.isNotEmpty() && name != ip) name else ""
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "LocalNetworkScanner"
    }
}
