package com.lsd.wififrankenstein.ui.localnetwork

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.lsd.wififrankenstein.util.Log
import jcifs.netbios.NbtAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.math.min

class NativeLocalNetworkScanner(private val context: Context) {

    suspend fun detectSubnet(wlanInterface: String = "wlan0"): SubnetInfo? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Detecting subnet natively for interface: $wlanInterface")

                var localIp = ""
                var prefixLength = 24
                var gateway = ""

                val cm =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

                if (cm != null) {
                    val activeNetwork: Network? = try {
                        cm.activeNetwork
                    } catch (_: Throwable) {
                        null
                    }

                    if (activeNetwork != null) {
                        val lp = try {
                            cm.getLinkProperties(activeNetwork)
                        } catch (_: Throwable) {
                            null
                        }

                        if (lp != null) {
                            for (addr in lp.linkAddresses) {
                                if (addr.address is Inet4Address) {
                                    if (localIp.isEmpty()) {
                                        localIp = addr.address.hostAddress ?: ""
                                        prefixLength = addr.prefixLength
                                    }
                                }
                            }

                            for (route in lp.routes) {
                                if (route.hasGateway() && route.isDefaultRoute) {
                                    gateway = route.gateway?.hostAddress ?: ""
                                }
                            }

                            if (lp.interfaceName != null) {
                                Log.d(TAG, "Active network interface: ${lp.interfaceName}")
                            }
                        }
                    }
                }

                if (localIp.isEmpty()) {
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
                }

                if (gateway.isEmpty()) {
                    gateway = parseGatewayFromProc(wlanInterface)
                }

                if (localIp.isEmpty()) {
                    Log.e(TAG, "Cannot determine local IP")
                    return@withContext null
                }

                val base = localIp.substringBeforeLast(".", "")
                val subnet = "$base.0/$prefixLength"
                val cidr = "$base.0/$prefixLength"

                if (gateway.isEmpty()) {
                    gateway = "$base.1"
                }

                Log.d(
                    TAG,
                    "Detected subnet: $subnet, gateway: $gateway, localIp: $localIp, prefix: $prefixLength"
                )
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
    ): List<LocalDevice> = withContext(Dispatchers.IO) {
        val devices = mutableListOf<LocalDevice>()
        val seenIps = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        try {
            Log.d(TAG, "Starting native ping sweep on $subnet")
            onProgress(ScanProgress(phase = "ping_sweep", line = "Scanning $subnet..."))

            val ipList = expandCidr(subnet)
            val selfIp = findSelfIp()
            val targetIps = ipList.filter { it != selfIp }
            var processedHosts = 0
            val progressLock = Any()

            onProgress(ScanProgress(phase = "ping_sweep", line = "Targets: ${targetIps.size} IPs"))

            fun addDevice(ip: String, mac: String, source: String, ttl: Int = 0) {
                val vendor = if (mac.isNotEmpty()) OuiDatabase.lookupByMac(mac) ?: "" else ""
                val cleanMac = mac.ifEmpty { "??:??:??:??:??:??" }
                val vendorStr = if (vendor.isNotEmpty()) " - $vendor" else ""
                Log.d(TAG, "Found ($source): $ip $cleanMac$vendorStr")
                onProgress(ScanProgress(phase = "parsing", line = "Found ($source): $ip"))
                devices.add(
                    LocalDevice(
                        ip = ip,
                        mac = mac,
                        vendor = vendor,
                        isAlive = true,
                        ttl = ttl
                    )
                )
            }


            val initialArp = readArpCache()
            Log.d(TAG, "Phase 1: ARP cache = ${initialArp.size} entries (${elapsed(startTime)}ms)")
            onProgress(
                ScanProgress(
                    phase = "ping_sweep",
                    line = "Initial ARP cache: ${initialArp.size} entries"
                )
            )

            for ((ip, mac, _) in initialArp) {
                if (ip.contains(':')) continue
                if (!seenIps.contains(ip) && ip != selfIp) {
                    seenIps.add(ip)
                    addDevice(ip, mac, "ARP")
                }
            }


            val remainingForPing = targetIps.filter { !seenIps.contains(it) }
            var pingAliveCount = 0
            if (remainingForPing.isNotEmpty()) {
                val pingSemaphore = Semaphore(50)
                val pingLock = Any()

                Log.d(
                    TAG,
                    "Phase 2: ICMP ping sweep on ${remainingForPing.size} hosts (${elapsed(startTime)}ms)"
                )
                onProgress(
                    ScanProgress(
                        phase = "ping_sweep",
                        line = "Pinging ${remainingForPing.size} hosts..."
                    )
                )

                coroutineScope {
                    val tasks = remainingForPing.map { ip ->
                        async {
                            pingSemaphore.withPermit {
                                val result = pingHost(ip)
                                synchronized(progressLock) {
                                    processedHosts++
                                    onProgress(
                                        ScanProgress(
                                            phase = "ping_sweep",
                                            current = processedHosts,
                                            total = targetIps.size
                                        )
                                    )
                                }
                                if (result != null) {
                                    synchronized(pingLock) { pingAliveCount++ }
                                    synchronized(devices) {
                                        if (!seenIps.contains(ip)) {
                                            seenIps.add(ip)
                                            addDevice(ip, "", "ICMP", result.ttl)
                                            val idx = devices.indexOfLast { it.ip == ip }
                                            if (idx >= 0) {
                                                devices[idx] = devices[idx].copy(
                                                    responseTimeMs = result.rtt,
                                                    ttl = result.ttl
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tasks.awaitAll()
                }

                Log.d(
                    TAG,
                    "Phase 2: ICMP ping done: $pingAliveCount alive (${elapsed(startTime)}ms)"
                )
                onProgress(
                    ScanProgress(
                        phase = "ping_sweep",
                        line = "ICMP: $pingAliveCount devices found"
                    )
                )
            }


            val remainingForTcp = targetIps.filter { !seenIps.contains(it) }
            if (remainingForTcp.isNotEmpty()) {

                if (pingAliveCount == 0 && seenIps.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Phase 3: skipped (ICMP blocked), ARP already found ${seenIps.size} devices (${
                            elapsed(startTime)
                        }ms)"
                    )
                } else {
                    val tcpSemaphore = Semaphore(50)
                    var tcpAliveCount = 0
                    val tcpLock = Any()
                    val fallbackPorts = listOf(22, 80, 443, 445, 139, 8080, 5000, 3389, 62078)

                    Log.d(
                        TAG,
                        "Phase 3: TCP fallback on ${remainingForTcp.size} hosts (${elapsed(startTime)}ms)"
                    )
                    onProgress(
                        ScanProgress(
                            phase = "ping_sweep",
                            line = "TCP probe: ${remainingForTcp.size} hosts..."
                        )
                    )

                    coroutineScope {
                        val tasks = remainingForTcp.map { ip ->
                            async {
                                tcpSemaphore.withPermit {
                                    val alive = isAnyPortOpenShortCircuit(ip, fallbackPorts, 200)
                                    synchronized(progressLock) {
                                        processedHosts++
                                        onProgress(
                                            ScanProgress(
                                                phase = "ping_sweep",
                                                current = processedHosts,
                                                total = targetIps.size
                                            )
                                        )
                                    }
                                    if (alive) {
                                        val mac = resolveMacFromArp(ip)
                                        synchronized(tcpLock) { tcpAliveCount++ }
                                        synchronized(devices) {
                                            if (!seenIps.contains(ip)) {
                                                seenIps.add(ip)
                                                addDevice(ip, mac, "TCP")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        tasks.awaitAll()
                    }

                    Log.d(
                        TAG,
                        "Phase 3: TCP fallback found $tcpAliveCount devices (${elapsed(startTime)}ms)"
                    )
                    onProgress(
                        ScanProgress(
                            phase = "ping_sweep",
                            line = "TCP: $tcpAliveCount devices found"
                        )
                    )
                }
            }


            val remainingForArp = targetIps.filter { !seenIps.contains(it) }
            if (remainingForArp.isNotEmpty()) {
                sendUdpProbes(remainingForArp)
                delay(150)
                val arpAfterUdp = readArpCache()
                val arpNewCount = arpAfterUdp.count { !seenIps.contains(it.ip) && it.ip != selfIp }
                if (arpNewCount > 0) {
                    Log.d(
                        TAG,
                        "Phase 4: UDP+ARP found $arpNewCount devices (${elapsed(startTime)}ms)"
                    )
                    for ((ip, mac, _) in arpAfterUdp) {
                        if (ip.contains(':')) continue
                        if (!seenIps.contains(ip) && ip != selfIp) {
                            seenIps.add(ip)
                            addDevice(ip, mac, "UDP+ARP")
                        }
                    }
                }
            }


            val arpEnrich = readArpCacheCached()
            if (arpEnrich.isNotEmpty()) {
                val arpMap = arpEnrich.associate { it.ip to it.mac }
                for (i in devices.indices) {
                    val d = devices[i]
                    if ((d.mac.isEmpty() || d.mac == "??:??:??:??:??:??") && arpMap.containsKey(d.ip)) {
                        val mac = arpMap[d.ip] ?: continue
                        val vendor = OuiDatabase.lookupByMac(mac) ?: ""
                        devices[i] = d.copy(mac = mac, vendor = vendor)
                    }
                }
            }


            for (device in devices.toList()) {
                try {
                    val addr = InetAddress.getByName(device.ip)
                    val host = addr.hostName ?: ""
                    if (host.isNotEmpty() && host != device.ip) {
                        val idx = devices.indexOfFirst { it.ip == device.ip }
                        if (idx >= 0) devices[idx] = device.copy(hostname = host)
                    }
                } catch (_: Exception) {
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            Log.d(
                TAG,
                "Native ping sweep complete: ${devices.size} devices found in ${totalTime}ms"
            )
            onProgress(ScanProgress(phase = "done", line = "Found ${devices.size} devices"))
        } catch (e: Exception) {
            Log.e(TAG, "Native ping sweep failed", e)
            onProgress(ScanProgress(phase = "error", line = "Ping sweep failed: ${e.message}"))
        }

        devices
    }

    private fun elapsed(startMs: Long): Long = System.currentTimeMillis() - startMs

    private data class PingResult(val alive: Boolean, val rtt: Long, val ttl: Int)

    private fun pingHost(ip: String): PingResult? {
        return try {
            val process =
                Runtime.getRuntime().exec(arrayOf("/system/bin/ping", "-c", "1", "-W", "1", ip))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(3, TimeUnit.SECONDS)

            if (process.exitValue() != 0) return null


            val rttMatch = Regex("""[tT]ime[=<\s]*([\d.]+)\s*ms""").find(output)
            val rtt = rttMatch?.groupValues?.get(1)?.toFloatOrNull()?.toLong() ?: 0L


            val ttlMatch = Regex("""[tT][tT][lL][=:\s]*(\d+)""").find(output)
            val ttl = ttlMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            Log.d(TAG, "ping $ip: rtt=${rtt}ms ttl=$ttl")
            PingResult(alive = true, rtt = rtt, ttl = ttl)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scanDevicePorts(
        device: LocalDevice,
        fastScan: Boolean = true,
        onProgress: (ScanProgress) -> Unit = {}
    ): LocalDevice = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Scanning ports on ${device.ip} (fast=$fastScan)")
            onProgress(ScanProgress(phase = "port_scan", line = "Scanning ${device.ip}..."))

            val portsToScan = if (fastScan) TOP_PORTS else MID_PORTS
            val openPorts = mutableListOf<Int>()
            val semaphore = Semaphore(20)
            var responseTime = 0L
            val responseLock = Any()

            coroutineScope {
                val tasks = portsToScan.map { port ->
                    async {
                        semaphore.withPermit {
                            val start = System.currentTimeMillis()
                            if (isTcpPortOpen(device.ip, port, 300)) {
                                synchronized(openPorts) { openPorts.add(port) }
                                val elapsed = System.currentTimeMillis() - start
                                if (elapsed > 0) {
                                    synchronized(responseLock) {
                                        if (responseTime == 0L || elapsed < responseTime) {
                                            responseTime = elapsed
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                tasks.awaitAll()
            }

            val sortedPorts = openPorts.sorted()
            val mac = if (device.mac.isNotEmpty()) device.mac else resolveMacFromArp(device.ip)
            val vendor = if (device.vendor.isNotEmpty()) device.vendor else
                (if (mac.isNotEmpty()) OuiDatabase.lookupByMac(mac) ?: "" else "")
            val osType = guessOsByPorts(sortedPorts, vendor, device.ttl)
            val netbiosName = resolveNetbiosName(device.ip)


            var bannerVersion = ""
            val bannerSemaphore = Semaphore(10)

            if (sortedPorts.isNotEmpty()) {
                coroutineScope {
                    val tasks = sortedPorts.map { port ->
                        async {
                            bannerSemaphore.withPermit {
                                val banner = grabBanner(device.ip, port)
                                if (banner.isNotEmpty()) {
                                    val version = extractVersionFromBanner(banner)
                                    synchronized(bannerVersion) {
                                        if (bannerVersion.isEmpty()) {
                                            bannerVersion = version
                                            Log.d(TAG, "Banner on ${device.ip}:$port = $banner")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    tasks.awaitAll()
                }
            }

            val osLabel = osNameFromType(osType, sortedPorts)
            val ttlLabel = if (device.ttl > 0) " TTL=${device.ttl}" else ""
            val finalOs =
                if (bannerVersion.isNotEmpty()) "$osLabel - $bannerVersion$ttlLabel" else "$osLabel$ttlLabel"

            Log.d(
                TAG,
                "Port scan complete for ${device.ip}: ${sortedPorts.size} ports open, OS: ${osType.label}"
            )
            onProgress(
                ScanProgress(
                    phase = "port_scan",
                    line = "${device.ip}: ${sortedPorts.size} ports open"
                )
            )

            device.copy(
                mac = mac,
                vendor = vendor,
                openPorts = sortedPorts,
                os = finalOs,
                osType = osType,
                responseTimeMs = responseTime,
                netbiosName = netbiosName
            )
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

    private fun guessOsByPorts(ports: List<Int>, vendor: String = "", ttl: Int = 0): OSType {
        val ven = vendor.lowercase()
        if (ven.contains("apple")) return OSType.MACOS
        if (ven.contains("microsoft")) return OSType.WINDOWS
        if (ven.contains("hikvision") || ven.contains("dahua")) return OSType.CAMERA
        if (ven.contains("mikrotik")) return OSType.ROUTER

        val portSet = ports.toSet()


        if (portSet.contains(8008) && portSet.contains(8009)) return OSType.ANDROID


        if (portSet.contains(7000) || portSet.contains(7100)) return OSType.MACOS


        if (portSet.any { it in 135..139 } || portSet.contains(445) || portSet.contains(3389) || portSet.contains(
                5357
            )) {
            return OSType.WINDOWS
        }


        if (portSet.contains(5555)) return OSType.ANDROID


        if (portSet.contains(62078)) return OSType.IOS


        if (portSet.contains(53) && portSet.contains(23)) return OSType.ROUTER


        if (portSet.contains(9100) || portSet.contains(515) || portSet.contains(631)) return OSType.PRINTER


        if (portSet.contains(554) || portSet.contains(37777)) return OSType.CAMERA


        if ((portSet.contains(80) || portSet.contains(443)) && portSet.contains(22)) return OSType.LINUX
        if (portSet.contains(22) && !portSet.contains(445) && !portSet.contains(3389)) return OSType.LINUX


        if (portSet.contains(23) && portSet.contains(80)) return OSType.EMBEDDED


        if (portSet.contains(548)) return OSType.MACOS


        if (ports.isEmpty() && ttl > 0) {
            return when {
                ttl <= 64 -> OSType.LINUX
                ttl <= 128 -> OSType.WINDOWS
                ttl >= 255 -> OSType.ROUTER
                else -> OSType.UNKNOWN
            }
        }

        return OSType.UNKNOWN
    }

    private fun osNameFromType(type: OSType, ports: List<Int>): String {
        val portStr = if (ports.isNotEmpty()) ports.take(5).joinToString(", ") else ""
        return when (type) {
            OSType.ANDROID -> "Android (port $portStr)"
            OSType.WINDOWS -> "Windows (port $portStr)"
            OSType.LINUX -> "Linux/Unix (port $portStr)"
            OSType.IOS -> "iOS (port $portStr)"
            OSType.MACOS -> "macOS (port $portStr)"
            OSType.PRINTER -> "Printer (port $portStr)"
            OSType.CAMERA -> "Camera/DVR (port $portStr)"
            OSType.ROUTER -> "Router (port $portStr)"
            OSType.EMBEDDED -> "Embedded/IoT (port $portStr)"
            else -> "Unknown"
        }
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

    private fun grabBanner(ip: String, port: Int): String {
        val httpPorts = listOf(80, 8080, 8000, 8008, 8081, 8888, 5000)
        val tlsPorts = listOf(443, 8443)

        return try {
            when (port) {
                in httpPorts -> {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(ip, port), 1500)
                        val os: OutputStream = socket.getOutputStream()
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        os.write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
                        os.flush()
                        reader.readLine()
                        var server = ""
                        for (line in reader.readLines().take(10)) {
                            if (line.startsWith("Server:", true)) {
                                server = line.substringAfter(":").trim().take(100)
                                break
                            }
                        }
                        server
                    }
                }

                in tlsPorts -> {

                    ""
                }

                else -> {

                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(ip, port), 2000)
                            socket.soTimeout = 2000
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                            var banner = StringBuilder()

                            try {
                                socket.soTimeout = 500
                                val firstLine = reader.readLine() ?: ""
                                if (firstLine.isNotBlank()) {
                                    socket.soTimeout = 2000
                                    banner.append(firstLine)

                                    for (i in 1..4) {
                                        val next = reader.readLine() ?: break
                                        if (next.isNotBlank()) banner.append("\n").append(next)
                                    }
                                }
                            } catch (_: Exception) {
                            }

                            if (banner.isEmpty()) {
                                try {
                                    socket.soTimeout = 2000
                                    val os: OutputStream = socket.getOutputStream()
                                    os.write("\r\n".toByteArray())
                                    os.flush()
                                    val firstLine = reader.readLine()
                                    if (firstLine != null && firstLine.isNotBlank()) {
                                        banner.append(firstLine)
                                        for (i in 1..4) {
                                            val next = reader.readLine() ?: break
                                            if (next.isNotBlank()) banner.append("\n").append(next)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            val result = banner.toString().take(200)
                            result.ifBlank { "" }
                        }
                    } catch (_: Exception) {
                        ""
                    }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractVersionFromBanner(banner: String): String {
        if (banner.isBlank()) return ""
        val lower = banner.lowercase()
        return when {
            lower.contains("openssh") -> "OpenSSH"
            lower.contains("apache") -> "Apache"
            lower.contains("nginx") -> "nginx"
            lower.contains("iis") -> "IIS"
            lower.contains("lighttpd") -> "lighttpd"
            lower.contains("cups") -> "CUPS"
            lower.contains("samba") -> "Samba"
            lower.contains("microsoft-http") -> "MS IIS"
            lower.contains("miniupnp") -> "MiniUPnP"
            lower.contains("upnp") -> "UPnP"
            else -> banner.take(40)
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

    private fun isTcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isAnyPortOpenShortCircuit(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firstSuccess = CompletableDeferred<Boolean>()
            coroutineScope {
                val jobs = ports.map { port ->
                    async {
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                            }
                            firstSuccess.complete(true)
                        } catch (_: Exception) {
                        }
                    }
                }
                val reachable = withTimeoutOrNull(1000L) {
                    firstSuccess.await()
                } == true
                jobs.forEach { it.cancel() }
                reachable
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun sendUdpProbes(ips: List<String>) {
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 100
                val buf = ByteArray(0)
                for (ip in ips) {
                    if (ip.contains(':')) continue
                    try {
                        val addr = InetAddress.getByName(ip)
                        val pkt = DatagramPacket(buf, 0, addr, 9)
                        sock.send(pkt)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readArpCache(): List<ArpEntry> {
        val entries = mutableListOf<ArpEntry>()


        try {
            val br = BufferedReader(FileReader("/proc/net/arp"))
            br.use { reader ->
                reader.readLine()
                for (line in reader.lines().toArray().filterIsInstance<String>()) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        val flags = parts[2]
                        val iface = if (parts.size > 6) parts[6] else ""
                        if (ip.isNotEmpty() && mac.isNotEmpty() && mac != "00:00:00:00:00:00") {
                            val isComplete = flags == "0x2"
                            entries.add(ArpEntry(ip, mac.uppercase(), iface, isComplete))
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }


        try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            for (line in reader.readLines()) {
                val parts = line.trim().split("\\s+".toRegex())

                if (parts.size >= 5 && parts.contains("lladdr")) {
                    val lladdrIdx = parts.indexOf("lladdr")
                    val ip = parts[0].trimEnd(',').trimEnd('/')
                    val mac = parts[lladdrIdx + 1].uppercase()
                    val stateIdx = parts.indexOfFirst {
                        it in listOf(
                            "REACHABLE",
                            "STALE",
                            "DELAY",
                            "PROBE",
                            "PERMANENT"
                        )
                    }
                    val state = if (stateIdx >= 0) parts[stateIdx] else ""
                    if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && state != "FAILED" && state != "INCOMPLETE") {
                        val iface =
                            if (parts.size > 1 && parts[1] == "dev" && parts.size > 2) parts[2] else ""

                        if (entries.none { it.ip == ip }) {
                            entries.add(ArpEntry(ip, mac, iface, state == "REACHABLE"))
                        }
                    }
                }
            }
            process.waitFor()
        } catch (_: Exception) {
        }

        return entries
    }

    private var cachedArp: List<ArpEntry> = emptyList()
    private var arpCacheTime: Long = 0

    private fun readArpCacheCached(): List<ArpEntry> {
        val now = System.currentTimeMillis()
        if (now - arpCacheTime > 1000L) {
            cachedArp = readArpCache()
            arpCacheTime = now
        }
        return cachedArp
    }

    private fun resolveMacFromArp(ip: String): String {
        return readArpCacheCached().firstOrNull { it.ip == ip }?.mac ?: ""
    }

    private fun findSelfIp(): String {
        return try {
            val nif = NetworkInterface.getNetworkInterfaces()
            while (nif.hasMoreElements()) {
                val iface = nif.nextElement()
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun expandCidr(cidr: String): List<String> {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return emptyList()
            val baseParts = parts[0].split(".")
            if (baseParts.size != 4) return emptyList()
            val prefix = parts[1].toIntOrNull() ?: 24


            val effectivePrefix = if (prefix < 22) 24.coerceAtMost(prefix) else prefix

            val ipInt = (baseParts[0].toInt() shl 24) or
                    (baseParts[1].toInt() shl 16) or
                    (baseParts[2].toInt() shl 8) or
                    baseParts[3].toInt()

            val mask = if (effectivePrefix == 0) 0 else (-1 shl (32 - effectivePrefix))
            val network = ipInt and mask
            val hosts = ((1L shl (32 - effectivePrefix)) - 2).toInt().coerceAtLeast(0)
            val count = min(hosts.coerceAtLeast(0), 1022)

            val result = mutableListOf<String>()
            for (i in 1..count) {
                val host = network + i
                result.add("${(host shr 24) and 0xFF}.${(host shr 16) and 0xFF}.${(host shr 8) and 0xFF}.${host and 0xFF}")
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class ArpEntry(
        val ip: String,
        val mac: String,
        val iface: String,
        val isComplete: Boolean
    )

    companion object {
        private const val TAG = "NativeLocalNetworkScanner"

        val TOP_PORTS = listOf(
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139,
            143, 443, 445, 993, 995, 1433, 1521, 1723, 3306, 3389,
            5357, 5432, 5800, 5900, 5901, 6379, 8080, 8443, 9000, 9090,
            27017, 32400, 49152
        )

        val TOP_PORTS_1000 = listOf(
            1, 3, 4, 6, 7, 9, 13, 17, 19, 20, 21, 22, 23, 24, 25, 26, 30, 32, 33, 37,
            42, 43, 49, 53, 70, 79, 80, 81, 82, 83, 84, 85, 88, 89, 90, 99, 100, 106,
            109, 110, 111, 113, 119, 125, 135, 139, 143, 144, 146, 161, 163, 179, 199,
            211, 212, 222, 254, 255, 256, 259, 264, 280, 301, 306, 311, 340, 366, 389,
            406, 407, 416, 417, 425, 427, 443, 444, 445, 458, 464, 465, 481, 497, 500,
            512, 513, 514, 515, 524, 541, 543, 544, 545, 548, 554, 555, 563, 587, 593,
            616, 617, 625, 631, 636, 646, 648, 666, 667, 668, 683, 687, 691, 700, 705,
            711, 714, 720, 722, 726, 749, 765, 777, 783, 787, 800, 801, 808, 843, 873,
            880, 888, 898, 900, 901, 902, 903, 911, 912, 981, 987, 990, 992, 993, 995,
            999, 1000, 1001, 1002, 1007, 1009, 1010, 1011, 1021, 1022, 1023, 1024, 1025,
            1026, 1027, 1028, 1029, 1030, 1033, 1034, 1035, 1036, 1037, 1038, 1039, 1040,
            1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053,
            1054, 1055, 1056, 1057, 1058, 1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066,
            1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078, 1080,
            1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093,
            1094, 1095, 1096, 1097, 1098, 1099, 1100, 1102, 1104, 1105, 1106, 1107, 1108,
            1110, 1111, 1112, 1113, 1114, 1117, 1119, 1121, 1122, 1123, 1124, 1126, 1130,
            1131, 1132, 1137, 1138, 1141, 1145, 1147, 1148, 1149, 1151, 1152, 1154, 1163,
            1164, 1165, 1166, 1169, 1174, 1175, 1183, 1185, 1186, 1187, 1192, 1198, 1199,
            1201, 1213, 1216, 1217, 1218, 1233, 1234, 1236, 1244, 1247, 1248, 1259, 1271,
            1272, 1277, 1287, 1296, 1300, 1301, 1309, 1310, 1311, 1322, 1328, 1334, 1352,
            1417, 1433, 1434, 1443, 1455, 1461, 1494, 1500, 1501, 1503, 1521, 1524, 1533,
            1556, 1580, 1583, 1594, 1600, 1641, 1658, 1666, 1687, 1688, 1700, 1717, 1718,
            1719, 1720, 1721, 1723, 1755, 1761, 1782, 1783, 1801, 1805, 1812, 1839, 1840,
            1862, 1863, 1864, 1875, 1900, 1914, 1935, 1947, 1971, 1972, 1974, 1984, 1991,
            1992, 1993, 1994, 1995, 1996, 1997, 1998, 1999, 2000, 2001, 2002, 2003, 2004,
            2005, 2006, 2007, 2008, 2009, 2010, 2013, 2020, 2021, 2022, 2030, 2033, 2034,
            2035, 2038, 2040, 2041, 2042, 2043, 2045, 2046, 2047, 2048, 2049, 2065, 2068,
            2099, 2100, 2103, 2105, 2106, 2107, 2111, 2119, 2121, 2126, 2135, 2144, 2160,
            2161, 2170, 2179, 2190, 2191, 2196, 2200, 2222, 2251, 2260, 2288, 2301, 2323,
            2366, 2381, 2382, 2383, 2393, 2394, 2399, 2401, 2492, 2500, 2522, 2525, 2557,
            2601, 2602, 2604, 2605, 2607, 2608, 2628, 2638, 2701, 2702, 2710, 2717, 2718,
            2725, 2800, 2809, 2811, 2869, 2875, 2909, 2910, 2920, 2967, 2968, 2998, 3000,
            3001, 3003, 3005, 3006, 3007, 3011, 3013, 3017, 3030, 3031, 3050, 3052, 3071,
            3077, 3128, 3168, 3211, 3221, 3260, 3261, 3268, 3269, 3283, 3300, 3301, 3306,
            3322, 3323, 3324, 3325, 3333, 3351, 3367, 3369, 3370, 3371, 3372, 3386, 3389,
            3390, 3404, 3476, 3493, 3517, 3527, 3546, 3551, 3580, 3659, 3689, 3690, 3703,
            3737, 3766, 3784, 3800, 3801, 3809, 3814, 3826, 3827, 3828, 3851, 3869, 3871,
            3878, 3880, 3889, 3905, 3914, 3918, 3920, 3945, 3971, 3986, 3995, 3998, 4000,
            4001, 4002, 4003, 4004, 4005, 4006, 4045, 4111, 4125, 4126, 4129, 4224, 4242,
            4279, 4321, 4343, 4443, 4444, 4445, 4446, 4449, 4550, 4567, 4662, 4848, 4899,
            4900, 4998, 5000, 5001, 5002, 5003, 5009, 5030, 5033, 5050, 5051, 5054, 5060,
            5061, 5080, 5087, 5100, 5101, 5102, 5120, 5190, 5200, 5214, 5221, 5222, 5225,
            5226, 5269, 5280, 5298, 5357, 5405, 5414, 5431, 5432, 5440, 5500, 5510, 5544,
            5550, 5555, 5560, 5566, 5631, 5633, 5666, 5678, 5679, 5718, 5730, 5800, 5801,
            5802, 5810, 5811, 5815, 5822, 5825, 5850, 5859, 5862, 5877, 5900, 5901, 5902,
            5903, 5904, 5906, 5907, 5910, 5911, 5915, 5922, 5925, 5950, 5952, 5959, 5960,
            5961, 5962, 5963, 5987, 5988, 5989, 5998, 5999, 6000, 6001, 6002, 6003, 6004,
            6005, 6006, 6007, 6009, 6025, 6059, 6100, 6101, 6106, 6112, 6123, 6129, 6156,
            6346, 6389, 6502, 6510, 6543, 6547, 6548, 6549, 6550, 6551, 6558, 6566, 6567,
            6580, 6582, 6583, 6600, 6660, 6661, 6662, 6663, 6664, 6665, 6666, 6667, 6668,
            6669, 6689, 6692, 6699, 6779, 6788, 6789, 6792, 6839, 6881, 6901, 6969, 7000,
            7001, 7002, 7004, 7007, 7019, 7025, 7070, 7100, 7103, 7106, 7200, 7201, 7240,
            7402, 7435, 7443, 7496, 7512, 7625, 7627, 7676, 7741, 7777, 7778, 7800, 7911,
            7920, 7921, 7937, 7938, 7999, 8000, 8001, 8002, 8007, 8008, 8009, 8010, 8011,
            8021, 8022, 8031, 8042, 8045, 8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087,
            8088, 8089, 8090, 8093, 8099, 8100, 8180, 8181, 8192, 8193, 8194, 8200, 8222,
            8254, 8290, 8291, 8292, 8300, 8333, 8383, 8400, 8402, 8443, 8500, 8600, 8649,
            8651, 8652, 8654, 8701, 8800, 8804, 8873, 8880, 8881, 8882, 8883, 8888, 8899,
            8994, 9000, 9001, 9002, 9003, 9009, 9010, 9011, 9040, 9050, 9071, 9080, 9081,
            9090, 9091, 9099, 9100, 9101, 9102, 9103, 9110, 9111, 9160, 9191, 9200, 9201,
            9207, 9220, 9290, 9415, 9418, 9485, 9500, 9502, 9503, 9535, 9575, 9593, 9594,
            9595, 9600, 9612, 9614, 9616, 9618, 9620, 9622, 9624, 9626, 9628, 9630, 9632,
            9634, 9636, 9638, 9640, 9642, 9644, 9646, 9648, 9650, 9652, 9654, 9656, 9658,
            9660, 9662, 9664, 9666, 9668, 9670, 9672, 9673, 9675, 9676, 9678, 9679, 9680,
            9681, 9682, 9684, 9685, 9686, 9690, 9691, 9692, 9693, 9694, 9695, 9696, 9697,
            9698, 9700, 9701, 9702, 9703, 9704, 9705, 9706, 9707, 9708, 9709, 9710, 9711,
            9712, 9713, 9714, 9715, 9716, 9717, 9718, 9719, 9720, 9721, 9722, 9724, 9725,
            9726, 9727, 9728, 9729, 9730, 9731, 9732, 9733, 9734, 9735, 9736, 9737, 9738,
            9739, 9740, 9741, 9742, 9743, 9744, 9745, 9746, 9747, 9748, 9749, 9750, 9751,
            9752, 9753, 9754, 9755, 9756, 9757, 9758, 9759, 9760, 9761, 9762, 9800, 9875,
            9876, 9877, 9878, 9898, 9900, 9917, 9929, 9943, 9944, 9968, 9981, 9987, 9990,
            9991, 9992, 9993, 9994, 9995, 9996, 9997, 9998, 9999, 10000, 10001, 10002,
            10003, 10004, 10005, 10006, 10007, 10008, 10009, 10010, 10012, 10024, 10025,
            10082, 10180, 10215, 10243, 10566, 10616, 10617, 10621, 10626, 10628, 10629,
            10778, 11110, 11111, 11967, 12000, 12174, 12265, 12345, 13456, 13722, 13782,
            13783, 14000, 14238, 14441, 15000, 15002, 15003, 15004, 15660, 15742, 16000,
            16001, 16012, 16016, 16018, 16080, 16113, 16992, 16993, 17877, 17988, 18040,
            18101, 18988, 19101, 19283, 19315, 19350, 19780, 19801, 19842, 20000, 20005,
            20031, 20221, 20222, 20828, 21571, 22939, 23502, 24444, 24800, 25734, 25735,
            26214, 27000, 27352, 27353, 27355, 27356, 27715, 28201, 30000, 30718, 30951,
            31038, 31337, 32768, 32769, 32770, 32771, 32772, 32773, 32774, 32775, 32776,
            32777, 32778, 32779, 32780, 32781, 32782, 32783, 32784, 32785, 33354, 33899,
            34571, 34572, 34573, 35500, 38292, 40193, 40911, 41511, 42510, 44176, 44442,
            44443, 44501, 45100, 48080, 49152, 49153, 49154, 49155, 49156, 49157, 49158,
            49159, 49160, 49161, 49163, 49165, 49167, 49175, 49176, 49400, 49999, 50000,
            50001, 50002, 50003, 50006, 50300, 50389, 50500, 50636, 50800, 51103, 51493,
            52673, 52822, 52848, 52869, 54045, 54328, 55055, 55056, 55555, 55600, 56737,
            56738, 57294, 57797, 58080, 60020, 60443, 61532, 61900, 62078, 63331, 64623,
            64680, 65000, 65129, 65389
        )

        val MID_PORTS = TOP_PORTS_1000.take(500)
    }
}
