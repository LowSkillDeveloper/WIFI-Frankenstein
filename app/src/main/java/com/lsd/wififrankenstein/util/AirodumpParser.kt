package com.lsd.wififrankenstein.util

enum class DetectionState { NONE, AIRODUMP, CONFIRMED }

data class CaptureStats(
    val targetBssid: String = "",
    val power: String = "--",
    val beacons: String = "0",
    val dataFrames: String = "0",
    val channel: String = "--",
    val enc: String = "--",
    val cipher: String = "--",
    val auth: String = "--",
    val essid: String = "",
    val rxq: String = "--",
    val clientCount: Int = 0,
    val bestClient: String = "",
    val bestClientPwr: String = "--",
    val bestClientRate: String = "--",
    val clients: List<AirodumpClient> = emptyList(),
    val pmkidFound: Boolean = false,
    val handshakeFound: Boolean = false,
    val pmkidState: DetectionState = DetectionState.NONE,
    val handshakeState: DetectionState = DetectionState.NONE,
    val lastUpdateMs: Long = 0L
) {
    val hasData: Boolean get() = beacons != "0" || dataFrames != "0" || clientCount > 0
    val isWpa3: Boolean get() = auth.uppercase() in setOf("SAE", "OWE")
}

data class AirodumpClient(
    val mac: String,
    val power: String = "--",
    val rate: String = "--",
    val lost: String = "0",
    val frames: String = "0",
    val probes: String = ""
)

class AirodumpParser(private val targetBssid: String) {

    private val targetBssidUpper = targetBssid.uppercase()
    private var targetBss: BssRow? = null
    private val clients = LinkedHashMap<String, ClientRow>()
    private var pmkidFound = false
    private var handshakeFound = false

    private data class BssRow(
        val bssid: String,
        var power: String,
        var rxq: String,
        var beacons: String,
        var data: String,
        var channel: String,
        var enc: String,
        var cipher: String,
        var auth: String,
        var essid: String
    )

    private data class ClientRow(
        val mac: String,
        var power: String,
        var rate: String,
        var lost: String,
        var frames: String,
        var notes: String,
        var probes: String
    ) {
        fun toClient(): AirodumpClient = AirodumpClient(mac, power, rate, lost, frames, probes)
    }

    private val macRegex = Regex("""[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}""")
    private val pmkidRegex = Regex("""PMKID found:\s+([0-9A-Fa-f:]{17})""")
    private val handshakeRegex = Regex("""WPA handshake:\s+([0-9A-Fa-f:]{17})""")

    fun processLine(raw: String): CaptureStats? {
        if (raw.isBlank()) return null

        val pmkidMatch = pmkidRegex.find(raw)
        if (pmkidMatch != null && pmkidMatch.groupValues[1].uppercase() == targetBssidUpper) {
            if (!pmkidFound) {
                pmkidFound = true
                Log.i(TAG, "PMKID found for target $targetBssidUpper")
                return snapshot()
            }
            return null
        }

        val handshakeMatch = handshakeRegex.find(raw)
        if (handshakeMatch != null && handshakeMatch.groupValues[1].uppercase() == targetBssidUpper) {
            if (!handshakeFound) {
                handshakeFound = true
                Log.i(TAG, "WPA handshake found for target $targetBssidUpper")
                return snapshot()
            }
            return null
        }

        val cleaned = raw
            .replace("\u001B\\[[0-9;]*[A-Za-z]".toRegex(), "")
            .replace("\\[\\d+;\\d+H".toRegex(), "")
            .replace("[\\p{Cntrl}]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        if (cleaned.isEmpty()) return null

        val parts = cleaned.split(' ')
        if (parts.size < 11) {
            parseClientRow(parts)?.let { return snapshot() }
            return null
        }

        val bssid = parts[0]
        if (!macRegex.matches(bssid)) return null
        if (bssid.uppercase() != targetBssidUpper) return null

        val pwr = parts.getOrNull(1) ?: ""
        val rxq = parts.getOrNull(2) ?: "--"
        val beacons = parts.getOrNull(3) ?: "0"
        val dataCount = parts.getOrNull(4) ?: "0"
        val channel = parts.getOrNull(6) ?: "--"
        val enc = parts.getOrNull(8) ?: "--"
        val cipher = parts.getOrNull(9) ?: "--"
        val auth = parts.getOrNull(10) ?: "--"

        val existingBss = targetBss
        if (existingBss == null) {
            targetBss = BssRow(
                bssid.uppercase(),
                pwr,
                rxq,
                beacons,
                dataCount,
                channel,
                enc,
                cipher,
                auth,
                ""
            )
        } else {
            existingBss.power = pwr
            existingBss.rxq = rxq
            existingBss.beacons = beacons
            existingBss.data = dataCount
            existingBss.channel = channel
            existingBss.enc = enc
            existingBss.cipher = cipher
            existingBss.auth = auth
        }

        val tail = parts.drop(11)
        if (tail.isNotEmpty()) {
            val nextMacIdx = tail.indexOfFirst { macRegex.matches(it) }
            val essid = if (nextMacIdx > 0) {
                tail.subList(0, nextMacIdx).joinToString(" ")
            } else {
                tail.joinToString(" ")
            }
            if (existingBss != null) {
                existingBss.essid = essid
            } else {
                targetBss?.essid = essid
            }
        }

        return snapshot()
    }

    private fun parseClientRow(parts: List<String>): ClientRow? {
        if (parts.size < 7) return null
        val bssid = parts[0]
        if (bssid.uppercase() != targetBssidUpper) return null
        if (!macRegex.matches(parts[1])) return null

        val stationMac = parts[1]
        val pwr = parts[2]
        val tail = parts.drop(3)
        if (tail.isEmpty()) return null

        val framesRegex = Regex("""^\d+$""")
        var framesIdx = -1
        for (i in tail.indices.reversed()) {
            if (framesRegex.matches(tail[i])) {
                framesIdx = i
                break
            }
        }
        if (framesIdx < 2) return null

        val rate = tail.subList(0, framesIdx - 1).joinToString(" ")
        val lost = tail[framesIdx - 1]
        val frames = tail[framesIdx]
        val notesProbes = tail.drop(framesIdx + 1)
        val notes = notesProbes.getOrNull(0) ?: ""
        val probes = notesProbes.getOrNull(1) ?: ""

        val existing = clients[stationMac]
        if (existing == null) {
            clients[stationMac] = ClientRow(stationMac, pwr, rate, lost, frames, notes, probes)
        } else {
            existing.power = pwr
            existing.rate = rate
            existing.lost = lost
            existing.frames = frames
            existing.notes = notes
            existing.probes = probes
        }
        return existing
    }

    private fun snapshot(): CaptureStats {
        val bss = targetBss
        val best = clients.values.maxByOrNull { parseIntOrZero(it.frames) }
        return CaptureStats(
            targetBssid = targetBssidUpper,
            power = bss?.power ?: "--",
            beacons = bss?.beacons ?: "0",
            dataFrames = bss?.data ?: "0",
            channel = bss?.channel ?: "--",
            enc = bss?.enc ?: "--",
            cipher = bss?.cipher ?: "--",
            auth = bss?.auth ?: "--",
            essid = bss?.essid ?: "",
            rxq = bss?.rxq ?: "--",
            clientCount = clients.size,
            bestClient = best?.mac ?: "",
            bestClientPwr = best?.power ?: "--",
            bestClientRate = best?.rate ?: "--",
            clients = clients.values.map { it.toClient() },
            pmkidFound = pmkidFound,
            handshakeFound = handshakeFound,
            pmkidState = if (pmkidFound) DetectionState.AIRODUMP else DetectionState.NONE,
            handshakeState = if (handshakeFound) DetectionState.AIRODUMP else DetectionState.NONE,
            lastUpdateMs = System.currentTimeMillis()
        )
    }

    private fun parseIntOrZero(value: String): Int = value.toIntOrNull() ?: 0

    companion object {
        private const val TAG = "AirodumpParser"
        fun authIsWpa3(auth: String): Boolean = auth.uppercase() in setOf("SAE", "OWE")
    }
}
