package com.lsd.wififrankenstein.util

import android.util.Log
import java.io.File

data class ParsedHandshake(
    val bssid: String,
    val clientMac: String? = null,
    val essid: String? = null,
    val anonce: String? = null,
    val snonce: String? = null,
    val keymic: String? = null,
    val eapol: String? = null,
    val messagePair: Int = 0,
    val keyver: Int = 2,
    val pmkid: String? = null,
    val hasBeacon: Boolean = false,
    val channel: Int = 0,
    val rssi: Int = 0
) {
    fun to22000Line(): String {
        val essidHex = essid?.toByteArray()?.joinToString("") { "%02x".format(it) } ?: ""
        val macApHex = bssid.replace(":", "").lowercase()
        val macStaHex = (clientMac ?: "00:00:00:00:00:00").replace(":", "").lowercase()
        if (pmkid != null && anonce == null) {
            return "WPA*01*$pmkid*$macApHex*$macStaHex*$essidHex***01"
        }
        return "WPA*02*${keymic ?: "00".repeat(16)}*$macApHex*$macStaHex*$essidHex*${anonce ?: ""}*${eapol ?: ""}*${
            "%02x".format(
                messagePair
            )
        }"
    }
}

data class ApMetadata(
    val bssid: String,
    val essid: String?,
    val channel: Int?,
    val clients: List<String> = emptyList(),
    val rssi: Int? = null,
    val akm: String? = null,
    val groupCipher: String? = null,
    val pairwiseCipher: String? = null,
    val eapolM1Count: Int = 0,
    val eapolM2Count: Int = 0,
    val eapolM3Count: Int = 0,
    val eapolM4Count: Int = 0,
    val beaconCount: Int = 0,
    val assocReqCount: Int = 0,
    val authCount: Int = 0,
    val probeReqCount: Int = 0
)

class PcapParser {

    companion object {
        private const val TAG = "PcapParser"
        private const val PCAP_MAGIC = 0xa1b2c3d4L
        private const val PCAP_MAGIC_SWAPPED = 0xd4c3b2a1L
        private const val PCAP_MAGIC_NANO = 0x1a2b3c4dL
        private const val PCAP_MAGIC_NANO_SWAPPED = 0x4d3c2b1aL
        private const val PCAPNG_MAGIC = 0x0a0d0d0aL

        private const val DLT_IEEE802_11 = 105
        private const val DLT_PRISM_HEADER = 119
        private const val DLT_IEEE802_11_RADIO = 127
        private const val DLT_IEEE802_11_RADIO_AVS = 163
        private const val DLT_PPI = 192

        private const val MAX_PCAP_INCL_LEN = 1 shl 20

        private const val BLOCK_SHB = 0x0a0d0d0a
        private const val BLOCK_IDB = 0x00000001
        private const val BLOCK_EPB = 0x00000006
        private const val BLOCK_SPB = 0x00000003

        private const val TYPE_MGMT = 0
        private const val TYPE_DATA = 2
        private const val SUBTYPE_ASSOC_REQ = 0
        private const val SUBTYPE_ASSOC_RESP = 1
        private const val SUBTYPE_REASSOC_REQ = 2
        private const val SUBTYPE_REASSOC_RESP = 3
        private const val SUBTYPE_PROBE_REQ = 4
        private const val SUBTYPE_PROBE_RESP = 5
        private const val SUBTYPE_BEACON = 8
        private const val SUBTYPE_AUTH = 11
        private const val SUBTYPE_DEAUTH = 12
        private const val SUBTYPE_ACTION = 13

        private const val TAG_SSID = 0x00
        private const val TAG_CHAN = 0x03
        private const val TAG_RATES = 0x01
        private const val TAG_EXT_RATES = 0x32
        private const val TAG_RSN = 0x30
        private const val TAG_VENDOR = 0xdd
        private const val TAG_HT_CAP = 0x2d
        private const val TAG_HT_INFO = 0x3d
        private const val TAG_VHT_CAP = 0xbf
        private const val TAG_VHT_OP = 0xc0

        private const val EAPOL_KEY = 3
        private const val LLC_TYPE_EAPOL = 0x888e

        private const val WPA_KEY_INFO_ACK = 0x80
        private const val WPA_KEY_INFO_INSTALL = 0x40
        private const val WPA_KEY_INFO_SECURE = 0x200
        private const val WPA_KEY_INFO_MIC = 0x100
        private const val WPA_KEY_INFO_TYPE_MASK = 0x07

        private const val OUI_RSN = 0x000FAC
        private const val OUI_WPA = 0x0050F2

        private const val WPA_OUI_TYPE = 0x01

        private val FORMATS = setOf("cap", "pcap", "pcapng")
    }

    private class PcapRecordReader(
        private val data: ByteArray,
        private val i32: (Int) -> Int
    ) {
        private var offset = 24

        private fun isPlausibleRecord(off: Int): Boolean {
            if (off < 0 || off + 16 > data.size) return false
            val incl = i32(off + 8)
            return incl > 0 && incl <= MAX_PCAP_INCL_LEN && off + 16 + incl <= data.size
        }

        fun nextPacket(): ByteArray? {
            while (offset + 16 <= data.size) {
                val incl = i32(offset + 8)
                if (incl > 0 && incl <= MAX_PCAP_INCL_LEN && offset + 16 + incl <= data.size) {
                    val packet = data.copyOfRange(offset + 16, offset + 16 + incl)
                    val rawNext = offset + 16 + incl
                    val paddedNext = (rawNext + 3) / 4 * 4
                    offset = if (paddedNext != rawNext &&
                        !isPlausibleRecord(rawNext) && isPlausibleRecord(paddedNext)
                    ) {
                        paddedNext
                    } else {
                        rawNext
                    }
                    return packet
                }
                val padded = (offset + 3) / 4 * 4
                if (padded != offset && isPlausibleRecord(padded)) {
                    offset = padded
                    continue
                }
                offset++
            }
            return null
        }
    }

    private data class EapolMessage(
        val messageNum: Int,
        val bssid: String,
        val clientMac: String,
        val replayCounter: Long,
        val nonce: String,
        val keymic: String?,
        val eapolKeyData: ByteArray,
        val pmkid: String?,
        val keyver: Int,
        val keyDescriptor: Int
    )

    private data class RsnInfo(
        val groupCipher: String?,
        val pairwiseCipher: String?,
        val akm: String?
    )

    fun canParse(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext !in FORMATS) {
            Log.d(TAG, "canParse: bad extension '$ext'"); return false
        }
        val magic = try {
            val header = file.inputStream().use { it.readNBytes(4) }
            header.toInt32LE(0)
        } catch (e: Exception) {
            Log.w(TAG, "canParse: read error", e); return false
        }
        val ok =
            magic == PCAP_MAGIC.toInt() || magic == PCAP_MAGIC_SWAPPED.toInt() ||
            magic == PCAP_MAGIC_NANO.toInt() || magic == PCAP_MAGIC_NANO_SWAPPED.toInt() ||
            magic == PCAPNG_MAGIC.toInt()
        Log.d(TAG, "canParse: $file magic=0x%08x result=$ok".format(magic))
        return ok
    }

    fun extractHandshakes(file: File): List<ParsedHandshake> {
        Log.d(TAG, "extractHandshakes: ${file.name} (${file.length()}B)")
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "extractHandshakes: read failed", e); return emptyList()
        }
        return parseBytes(bytes)
    }

    fun extractApMetadata(file: File): Map<String, ApMetadata> {
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return emptyMap()
        }
        return parseBytesWithMetadata(bytes)
    }

    private fun parseBytes(data: ByteArray): List<ParsedHandshake> {
        if (data.size < 4) return emptyList()
        val magic = data.toInt32LE(0)
        return when {
            magic == PCAP_MAGIC.toInt() || magic == PCAP_MAGIC_NANO.toInt() -> parsePcap(data, false)
            magic == PCAP_MAGIC_SWAPPED.toInt() || magic == PCAP_MAGIC_NANO_SWAPPED.toInt() ->
                parsePcap(data, true)

            magic == PCAPNG_MAGIC.toInt() -> parsePcapng(data)
            else -> {
                Log.w(TAG, "  unknown magic")
                emptyList()
            }
        }
    }

    private fun parseBytesWithMetadata(data: ByteArray): Map<String, ApMetadata> {
        val ctx = ProcContext(
            records = mutableListOf(),
            eapolMessages = mutableMapOf(),
            essidMap = mutableMapOf(),
            channelMap = mutableMapOf(),
            rsnInfoMap = mutableMapOf(),
            clientsPerBssid = mutableMapOf(),
            frameCounts = mutableMapOf()
        )
        if (data.size < 4) return emptyMap()
        val magic = data.toInt32LE(0)
        val handshakes = when {
            magic == PCAP_MAGIC.toInt() || magic == PCAP_MAGIC_SWAPPED.toInt() ||
            magic == PCAP_MAGIC_NANO.toInt() || magic == PCAP_MAGIC_NANO_SWAPPED.toInt() -> {
                val swapped = magic == PCAP_MAGIC_SWAPPED.toInt() ||
                        magic == PCAP_MAGIC_NANO_SWAPPED.toInt()
                val i32 =
                    if (swapped) { o: Int -> data.toInt32BE(o) } else { o: Int -> data.toInt32LE(o) }
                val linktype = i32(20)
                val reader = PcapRecordReader(data, i32)
                while (true) {
                    val packetData = reader.nextPacket() ?: break
                    processPacket(packetData, linktype, ctx)
                }
                ctx.records
            }

            magic == PCAPNG_MAGIC.toInt() -> {
                var offset = 0;
                var linktype = DLT_IEEE802_11_RADIO
                while (offset + 8 <= data.size) {
                    val blockType = data.toInt32LE(offset);
                    val totalLen = data.toInt32LE(offset + 4)
                    if (totalLen < 12) break
                    when {
                        blockType == BLOCK_IDB && offset + 16 <= data.size -> linktype =
                            data.toInt16LE(offset + 8).toInt()

                        blockType == BLOCK_EPB && offset + 28 <= data.size -> {
                            val caplen = data.toInt32LE(offset + 20)
                            processPacket(
                                data.copyOfRange(
                                    offset + 28,
                                    offset + 28 + caplen.coerceAtMost(totalLen - 28)
                                ), linktype, ctx
                            )
                        }

                        blockType == BLOCK_SPB && offset + 16 <= data.size -> {
                            val caplen = data.toInt32LE(offset + 12)
                            processPacket(
                                data.copyOfRange(
                                    offset + 16,
                                    offset + 16 + caplen.coerceAtMost(totalLen - 16)
                                ), linktype, ctx
                            )
                        }
                    }
                    offset += totalLen; if (totalLen == 0) break
                }
                ctx.records
            }

            else -> emptyList()
        }
        pairMessages(ctx.eapolMessages, ctx.records, ctx.essidMap)

        val allBssids =
            (ctx.essidMap.keys + ctx.clientsPerBssid.keys + handshakes.map { it.bssid }).toSet()
        Log.d(TAG, "  extractApMetadata: ${allBssids.size} BSSIDs found: $allBssids")
        for (bssid in allBssids) {
            val ess = ctx.essidMap[bssid] ?: "?"
            val clients = ctx.clientsPerBssid[bssid]?.joinToString(",") ?: "none"
            val chan = ctx.channelMap[bssid]?.toString() ?: "?"
            val counts = listOf(
                "bcn=${ctx.frameCounts[bssid]?.get("beacon") ?: 0}",
                "e1=${ctx.frameCounts[bssid]?.get("eapol_1") ?: 0}",
                "e2=${ctx.frameCounts[bssid]?.get("eapol_2") ?: 0}",
                "e3=${ctx.frameCounts[bssid]?.get("eapol_3") ?: 0}",
                "e4=${ctx.frameCounts[bssid]?.get("eapol_4") ?: 0}",
                "assoc=${ctx.frameCounts[bssid]?.get("assoc_req") ?: 0}",
                "auth=${ctx.frameCounts[bssid]?.get("auth") ?: 0}"
            ).joinToString(" ")
            val rsn = ctx.rsnInfoMap[bssid]
            val rsnStr =
                if (rsn != null) " | cipher=${rsn.pairwiseCipher ?: rsn.groupCipher ?: "?"} akm=${rsn.akm ?: "?"}" else ""
            Log.d(TAG, "  AP $bssid: essid=$ess ch=$chan clients=[$clients] $counts$rsnStr")
        }
        return allBssids.associateWith { bssid ->
            ApMetadata(
                bssid = bssid,
                essid = ctx.essidMap[bssid],
                channel = ctx.channelMap[bssid],
                clients = ctx.clientsPerBssid[bssid]?.toList() ?: emptyList(),
                akm = ctx.rsnInfoMap[bssid]?.akm,
                groupCipher = ctx.rsnInfoMap[bssid]?.groupCipher,
                pairwiseCipher = ctx.rsnInfoMap[bssid]?.pairwiseCipher,
                eapolM1Count = (ctx.frameCounts[bssid]?.get("eapol_1") ?: 0),
                eapolM2Count = (ctx.frameCounts[bssid]?.get("eapol_2") ?: 0),
                eapolM3Count = (ctx.frameCounts[bssid]?.get("eapol_3") ?: 0),
                eapolM4Count = (ctx.frameCounts[bssid]?.get("eapol_4") ?: 0),
                beaconCount = (ctx.frameCounts[bssid]?.get("beacon") ?: 0),
                assocReqCount = (ctx.frameCounts[bssid]?.get("assoc_req") ?: 0),
                authCount = (ctx.frameCounts[bssid]?.get("auth") ?: 0),
                probeReqCount = (ctx.frameCounts[bssid]?.get("probe_req") ?: 0)
            )
        }
    }

    fun extractEssid(file: File): String? {
        val handshakes = extractHandshakes(file)
        val withEssid = handshakes.firstOrNull { !it.essid.isNullOrBlank() }
        return withEssid?.essid
    }

    fun extractBssid(file: File): String? {
        val handshakes = extractHandshakes(file)
        return handshakes.firstOrNull()?.bssid
    }

    fun summarize(file: File): String {
        val hs = extractHandshakes(file)
        if (hs.isEmpty()) return "No handshakes found"
        val essids = hs.mapNotNull { it.essid }.distinct()
        val bssids = hs.map { it.bssid }.distinct()
        return buildString {
            appendLine("Handshakes: ${hs.size}")
            appendLine("Unique ESSIDs: ${essids.size}")
            essids.forEach { appendLine("  $it") }
            appendLine("Unique BSSIDs: ${bssids.size}")
            appendLine("Has PMKID: ${hs.any { it.pmkid != null }}")
        }
    }

    private fun parsePcap(data: ByteArray, swapped: Boolean): List<ParsedHandshake> {
        val i32 = if (swapped) { o: Int -> data.toInt32BE(o) } else { o: Int -> data.toInt32LE(o) }
        val linktype = i32(20)
        Log.d(TAG, "  parsePcap: linktype=$linktype, size=${data.size}")
        val ctx = ProcContext(
            records = mutableListOf(),
            eapolMessages = mutableMapOf(),
            essidMap = mutableMapOf(),
            channelMap = mutableMapOf(),
            rsnInfoMap = mutableMapOf(),
            clientsPerBssid = mutableMapOf(),
            frameCounts = mutableMapOf()
        )

        var packetCount = 0
        val reader = PcapRecordReader(data, i32)
        while (true) {
            val packetData = reader.nextPacket() ?: break
            processPacket(packetData, linktype, ctx)
            packetCount++
        }
        pairMessages(ctx.eapolMessages, ctx.records, ctx.essidMap)
        val distinct = ctx.records.distinctBy { it.to22000Line() }
        Log.d(TAG, "  parsePcap: $packetCount packets, ${distinct.size} distinct")
        return distinct
    }

    private fun parsePcapng(data: ByteArray): List<ParsedHandshake> {
        Log.d(TAG, "  parsePcapng: size=${data.size}")
        val ctx = ProcContext(
            records = mutableListOf(),
            eapolMessages = mutableMapOf(),
            essidMap = mutableMapOf(),
            channelMap = mutableMapOf(),
            rsnInfoMap = mutableMapOf(),
            clientsPerBssid = mutableMapOf(),
            frameCounts = mutableMapOf()
        )
        var offset = 0
        var linktype = DLT_IEEE802_11_RADIO
        var blockCount = 0

        while (offset + 8 <= data.size) {
            val blockType = data.toInt32LE(offset)
            var totalLen = data.toInt32LE(offset + 4)
            if (totalLen < 12) break
            blockCount++
            when {
                blockType == PCAPNG_MAGIC.toInt() -> {
                    if (offset + 12 <= data.size) {
                        val bom = data.toInt32LE(offset + 8)
                        if (bom == 0x4d3c2b1a) linktype = -1
                    }
                }

                blockType == BLOCK_IDB && offset + 16 <= data.size -> {
                    linktype = data.toInt16LE(offset + 8).toInt()
                }

                blockType == BLOCK_EPB && offset + 28 <= data.size -> {
                    val caplen = data.toInt32LE(offset + 20)
                    val packetData = data.copyOfRange(
                        offset + 28,
                        offset + 28 + caplen.coerceAtMost(totalLen - 28)
                    )
                    processPacket(packetData, linktype, ctx)
                }

                blockType == BLOCK_SPB && offset + 16 <= data.size -> {
                    val caplen = data.toInt32LE(offset + 12)
                    val packetData = data.copyOfRange(
                        offset + 16,
                        offset + 16 + caplen.coerceAtMost(totalLen - 16)
                    )
                    processPacket(packetData, linktype, ctx)
                }
            }
            offset += totalLen
            if (totalLen == 0) break
        }
        pairMessages(ctx.eapolMessages, ctx.records, ctx.essidMap)
        val distinct = ctx.records.distinctBy { it.to22000Line() }
        Log.d(TAG, "  parsePcapng: $blockCount blocks, ${distinct.size} distinct")
        return distinct
    }

    private data class MacFrame(
        val frameControl: Int,
        val duration: Int,
        val addr1: String,
        val addr2: String,
        val addr3: String,
        val sequence: Int,
        val type: Int,
        val subtype: Int,
        val toDs: Boolean,
        val fromDs: Boolean,
        val prot: Boolean,
        val headerSize: Int,
        val addr4: String? = null
    )

    private fun parseMacFrame(packet: ByteArray, offset: Int): MacFrame? {
        if (offset + 24 > packet.size) return null
        val fcLow = packet[offset].toInt() and 0xFF
        val fcHigh = packet[offset + 1].toInt() and 0xFF
        val type = (fcLow shr 2) and 0x03
        val subtype = (fcLow shr 4) and 0x0F
        val toDs = (fcHigh and 0x01) != 0
        val fromDs = (fcHigh and 0x02) != 0
        val prot = (fcHigh and 0x40) != 0

        val isQos = type == TYPE_DATA && (subtype and 0x08) != 0
        var headerSize = 24
        if (toDs && fromDs) headerSize += 6
        if (isQos) headerSize += 2

        if (offset + headerSize > packet.size) return null

        val addr1 = macToString(packet, offset + 4)
        val addr2 = macToString(packet, offset + 10)
        val addr3 = macToString(packet, offset + 16)
        val seq =
            ((packet[offset + 23].toInt() and 0xFF) shl 8) or (packet[offset + 22].toInt() and 0xFF)

        var addr4: String? = null
        if (toDs && fromDs && offset + 30 <= packet.size) {
            addr4 = macToString(packet, offset + 24)
        }

        return MacFrame(
            frameControl = fcLow or (fcHigh shl 8),
            duration = ((packet[offset + 3].toInt() and 0xFF) shl 8) or (packet[offset + 2].toInt() and 0xFF),
            addr1 = addr1, addr2 = addr2, addr3 = addr3,
            sequence = seq, type = type, subtype = subtype,
            toDs = toDs, fromDs = fromDs, prot = prot,
            headerSize = headerSize, addr4 = addr4
        )
    }

    private data class ProcContext(
        val records: MutableList<ParsedHandshake>,
        val eapolMessages: MutableMap<String, MutableMap<Long, MutableList<EapolMessage>>>,
        val essidMap: MutableMap<String, String>,
        val channelMap: MutableMap<String, Int>,
        val rsnInfoMap: MutableMap<String, RsnInfo>,
        val clientsPerBssid: MutableMap<String, MutableSet<String>>,
        val frameCounts: MutableMap<String, MutableMap<String, Int>>
    )

    private fun processPacket(
        packet: ByteArray, linktype: Int, ctx: ProcContext
    ) {
        var offset = 0
        var fcsPresent = false
        var radiotapRssi: Int? = null
        var radiotapChannel: Int? = null

        when (linktype) {
            DLT_IEEE802_11_RADIO -> {
                if (offset + 8 > packet.size) {
                    Log.w(TAG, "  processPacket: radiotap header truncated (${packet.size}B)")
                    return
                }
                val radiotapLen =
                    ((packet[offset + 3].toInt() and 0xFF) shl 8) or (packet[offset + 2].toInt() and 0xFF)
                if (radiotapLen < 8 || offset + radiotapLen > packet.size) {
                    Log.w(TAG, "  processPacket: bad radiotapLen=$radiotapLen (packet=${packet.size})")
                    return
                }
                val (flags, channel, rssi) = parseRadiotap(packet, offset)
                fcsPresent = (flags and 0x10) != 0
                radiotapChannel = channel
                radiotapRssi = rssi
                offset += radiotapLen
            }

            DLT_PPI -> {
                if (offset + 8 > packet.size) {
                    Log.w(TAG, "  processPacket: PPI header truncated (${packet.size}B)")
                    return
                }
                val ppiLen =
                    ((packet[offset + 3].toInt() and 0xFF) shl 8) or (packet[offset + 2].toInt() and 0xFF)
                if (ppiLen < 8 || offset + ppiLen > packet.size) {
                    Log.w(TAG, "  processPacket: bad PPI len=$ppiLen (packet=${packet.size})")
                    return
                }
                offset += ppiLen
            }

            DLT_IEEE802_11_RADIO_AVS -> {
                if (offset + 8 > packet.size) {
                    Log.w(TAG, "  processPacket: AVS header truncated (${packet.size}B)")
                    return
                }
                val avsLen = packet.toInt32LE(offset + 4)
                if (avsLen < 8 || offset + avsLen > packet.size) {
                    Log.w(TAG, "  processPacket: bad AVS len=$avsLen (packet=${packet.size})")
                    return
                }
                offset += avsLen
            }

            DLT_PRISM_HEADER -> {
                if (offset + 8 > packet.size) {
                    Log.w(TAG, "  processPacket: Prism header truncated (${packet.size}B)")
                    return
                }
                var prismLen = packet.toInt32LE(offset + 4)
                if (prismLen < 8 || offset + prismLen > packet.size) prismLen = 144
                if (offset + prismLen > packet.size) {
                    Log.w(TAG, "  processPacket: bad Prism len=$prismLen (packet=${packet.size})")
                    return
                }
                offset += prismLen
            }
        }

        var frameEnd = packet.size
        if (fcsPresent && frameEnd >= 4) {
            frameEnd -= 4
        }

        val frame = parseMacFrame(packet, offset)
        if (frame == null) {
            Log.w(TAG, "  processPacket: failed to parse MAC frame at offset $offset")
            return
        }
        val payloadStart = offset + frame.headerSize
        val payloadLen = frameEnd - payloadStart
        if (payloadLen < 0) return

        val bssid = when {
            frame.type == TYPE_MGMT -> frame.addr3
            frame.toDs && !frame.fromDs -> frame.addr1
            !frame.toDs && frame.fromDs -> frame.addr2
            !frame.toDs && !frame.fromDs -> frame.addr3
            else -> frame.addr3
        }

        val clientMac = when {
            frame.toDs && !frame.fromDs -> frame.addr2
            !frame.toDs && frame.fromDs -> frame.addr1
            !frame.toDs && !frame.fromDs -> frame.addr2
            else -> frame.addr2
        }

        fun countFrame(subtype: String) {
            val counters = ctx.frameCounts.getOrPut(bssid) { mutableMapOf() }
            counters[subtype] = (counters[subtype] ?: 0) + 1
        }

        when (frame.type) {
            TYPE_MGMT -> {
                when (frame.subtype) {
                    SUBTYPE_BEACON, SUBTYPE_PROBE_RESP -> {
                        val fixedHdr = if (frame.subtype == SUBTYPE_BEACON) 12 else 12
                        if (payloadLen > fixedHdr) {
                            val tagsStart = payloadStart + fixedHdr
                            val tagsLen = payloadLen - fixedHdr
                            val tags = parseTaggedParams(packet, tagsStart, tagsLen)
                            val essid = tags["ssid"]
                            val chan = tags["channel"]?.toIntOrNull() ?: radiotapChannel

                            if (essid != null && essid.isNotBlank()) {
                                ctx.essidMap[bssid] = essid
                            }
                            if (chan != null && chan > 0) {
                                ctx.channelMap[bssid] = chan
                            }

                            val rsn = parseRsnFromTaggedParams(packet, tagsStart, tagsLen)
                            if (rsn != null) {
                                ctx.rsnInfoMap[bssid] = rsn
                            }

                            countFrame(if (frame.subtype == SUBTYPE_BEACON) "beacon" else "probe_resp")

                            if (!ctx.records.any { it.bssid == bssid && it.hasBeacon }) {
                                ctx.records.add(
                                    ParsedHandshake(
                                        bssid = bssid, essid = essid,
                                        channel = chan ?: 0, hasBeacon = true
                                    )
                                )
                            }
                        } else {
                            Log.w(TAG, "  beacon/probe too short: payloadLen=$payloadLen")
                        }
                    }

                    SUBTYPE_PROBE_REQ -> {
                        countFrame("probe_req")
                    }

                    SUBTYPE_ASSOC_REQ, SUBTYPE_REASSOC_REQ -> {
                        val fixedHdr = if (frame.subtype == SUBTYPE_ASSOC_REQ) 4 else 10
                        if (payloadLen > fixedHdr) {
                            val tagsStart = payloadStart + fixedHdr
                            val tagsLen = payloadLen - fixedHdr
                            val tags = parseTaggedParams(packet, tagsStart, tagsLen)
                            val essid = tags["ssid"]
                            if (essid != null && essid.isNotBlank() && !ctx.essidMap.containsKey(
                                    bssid
                                )
                            ) {
                                ctx.essidMap[bssid] = essid
                                Log.d(TAG, "  assoc req: $bssid essid=$essid")
                            }
                            val rsn = parseRsnFromTaggedParams(packet, tagsStart, tagsLen)
                            if (rsn != null && !ctx.rsnInfoMap.containsKey(bssid)) {
                                ctx.rsnInfoMap[bssid] = rsn
                            }
                            countFrame("assoc_req")
                            ctx.clientsPerBssid.getOrPut(bssid) { mutableSetOf() }.add(clientMac)
                        }
                    }

                    SUBTYPE_AUTH -> {
                        countFrame("auth")
                    }
                }
            }

            TYPE_DATA -> {
                if (payloadLen > 0) {
                    val msg = parseEapolFrame(
                        packet,
                        payloadStart,
                        payloadLen,
                        bssid,
                        frame,
                        ctx.eapolMessages
                    )
                    if (msg != null) {
                        ctx.clientsPerBssid.getOrPut(bssid) { mutableSetOf() }.add(msg.clientMac)
                        if (radiotapChannel != null) ctx.channelMap.putIfAbsent(
                            bssid,
                            radiotapChannel
                        )
                        countFrame("eapol_${msg.messageNum}")
                    }
                }
            }
        }
    }

    private fun parseEapolFrame(
        packet: ByteArray, start: Int, len: Int, bssid: String, frame: MacFrame,
        eapolMessages: MutableMap<String, MutableMap<Long, MutableList<EapolMessage>>>
    ): EapolMessage? {
        var off = start
        if (off + 8 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: packet too short for LLC ($bssid)")
            return null
        }

        val dsap = packet[off].toInt() and 0xFF
        val ssap = packet[off + 1].toInt() and 0xFF
        var matched = false
        if (dsap == 0xAA && ssap == 0xAA) {
            off += 6
            if (off + 2 > packet.size) {
                Log.w(TAG, "  parseEapolFrame: SNAP truncated"); return null
            }
            val etherType =
                ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
            off += 2
            if (etherType == LLC_TYPE_EAPOL) matched = true
        } else if (dsap == 0x88 && ssap == 0x8E) {
            off += 2
            matched = true
        }
        if (!matched) {
            Log.d(TAG, "  parseEapolFrame: not EAPOL (dsap=0x%02x ssap=0x%02x)".format(dsap, ssap))
            return null
        }

        val eapolStart = off
        if (off + 4 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated after LLC ($bssid)"); return null
        }
        val eapolVersion = packet[off].toInt() and 0xFF
        val eapolType = packet[off + 1].toInt() and 0xFF
        val eapolLen =
            ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
        off += 4
        if (eapolType != EAPOL_KEY) {
            Log.d(TAG, "  parseEapolFrame: eapolType=$eapolType != EAPOL_KEY ($bssid)")
            return null
        }

        if (off + 4 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated at key info ($bssid)"); return null
        }
        val keyDescriptor = packet[off].toInt() and 0xFF
        val keyInfo =
            ((packet[off + 1].toInt() and 0xFF) shl 8) or (packet[off + 2].toInt() and 0xFF)
        val keyLen =
            ((packet[off + 3].toInt() and 0xFF) shl 8) or (packet[off + 4].toInt() and 0xFF)
        if (keyDescriptor != 0x02 && keyDescriptor != 0xFE) {
            Log.d(
                TAG,
                "  parseEapolFrame: unsupported key descriptor 0x%02x ($bssid)".format(keyDescriptor)
            )
            return null
        }
        off += 5

        if (off + 8 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated at replay counter ($bssid)"); return null
        }
        val replayCounter = packet.toLongLE(off)
        off += 8

        if (off + 32 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated at nonce ($bssid)"); return null
        }
        val nonce = bytesToHex(packet.copyOfRange(off, off + 32))
        off += 32

        off += 16
        off += 8
        off += 8

        if (off + 16 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated at keymic ($bssid)"); return null
        }
        val keymic = bytesToHex(packet.copyOfRange(off, off + 16))
        off += 16

        if (off + 2 > packet.size) {
            Log.w(TAG, "  parseEapolFrame: truncated at WPA data len ($bssid)"); return null
        }
        val wpaDataLen =
            ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        off += 2

        val ack = (keyInfo and WPA_KEY_INFO_ACK) != 0
        val install = (keyInfo and WPA_KEY_INFO_INSTALL) != 0
        val secure = (keyInfo and WPA_KEY_INFO_SECURE) != 0
        val kdv = keyInfo and WPA_KEY_INFO_TYPE_MASK

        val messageNum = when {
            ack && !install -> 1
            !ack && !secure -> 2
            ack && install -> 3
            else -> 4
        }

        val clientMac =
            if (frame.addr2.equals(bssid, ignoreCase = true)) frame.addr1 else frame.addr2

        val pmkid = if (messageNum == 1 && off + wpaDataLen <= packet.size && wpaDataLen >= 22) {
            val data = packet.copyOfRange(off, off + wpaDataLen)
            extractPmkidFromM1Data(data)
        } else null

        val keyDataEnd = (off + wpaDataLen).coerceAtMost(packet.size)
        val fullEapolEnd = (eapolStart + 4 + eapolLen).coerceAtMost(packet.size)
        val eapolKeyData = packet.copyOfRange(eapolStart, maxOf(fullEapolEnd, keyDataEnd)).copyOf()
        val micOffInEapol = 4 + 77
        if (micOffInEapol + 16 <= eapolKeyData.size) {
            java.util.Arrays.fill(eapolKeyData, micOffInEapol, micOffInEapol + 16, 0.toByte())
        }

        val msg = EapolMessage(
            messageNum = messageNum,
            bssid = bssid,
            clientMac = clientMac,
            replayCounter = replayCounter,
            nonce = nonce,
            keymic = keymic,
            eapolKeyData = eapolKeyData,
            pmkid = pmkid,
            keyver = kdv,
            keyDescriptor = keyDescriptor
        )

        val key = "$bssid|$clientMac"
        val messages = eapolMessages.getOrPut(key) { mutableMapOf() }
        messages.getOrPut(replayCounter) { mutableListOf() }.add(msg)

        Log.d(
            TAG,
            "  EAPOL message: bssid=$bssid client=$clientMac msgNum=$messageNum replay=$replayCounter pmkid=${pmkid != null}"
        )
        return msg
    }

    private fun pairMessages(
        eapolMessages: Map<String, Map<Long, List<EapolMessage>>>,
        records: MutableList<ParsedHandshake>,
        essidMap: Map<String, String>
    ) {
        var pmkidStandalone = 0;
        var m12 = 0;
        var m34 = 0;
        var m14 = 0;
        var m23 = 0
        for ((key, replayGroups) in eapolMessages) {
            val parts = key.split("|")
            if (parts.size < 2) continue
            val bssid = parts[0]
            val clientMac = parts[1]
            val essid = essidMap[bssid]

            for ((rc, msgs) in replayGroups) {
                val m1 = msgs.firstOrNull { it.messageNum == 1 }
                val m2 = msgs.firstOrNull { it.messageNum == 2 }
                val m3 = msgs.firstOrNull { it.messageNum == 3 } ?: replayGroups[rc + 1]
                    ?.firstOrNull { it.messageNum == 3 } ?: replayGroups[rc - 1]
                    ?.firstOrNull { it.messageNum == 3 }
                val m4 = msgs.firstOrNull { it.messageNum == 4 } ?: replayGroups[rc + 1]
                    ?.firstOrNull { it.messageNum == 4 } ?: replayGroups[rc - 1]
                    ?.firstOrNull { it.messageNum == 4 }
                Log.d(
                    TAG,
                    "  replay=0x%x msg=[m1=${m1 != null} m2=${m2 != null} m3=${m3 != null} m4=${m4 != null}] $bssid <-> $clientMac".format(
                        rc
                    )
                )

                if (m1?.pmkid != null && !records.any { it.bssid == bssid && it.clientMac == clientMac && it.pmkid == m1.pmkid }) {
                    Log.d(TAG, "    → PMKID standalone: ${m1.pmkid}")
                    pmkidStandalone++
                    records.add(
                        ParsedHandshake(
                            bssid = bssid, clientMac = clientMac, essid = essid,
                            pmkid = m1.pmkid, hasBeacon = essidMap.containsKey(bssid)
                        )
                    )
                }

                if (m1 != null && m2 != null) {
                    val m2Eapol = bytesToHex(m2.eapolKeyData)
                    Log.d(
                        TAG,
                        "    → M1+M2 pair (0x80) anonce=${m1.nonce.take(16)}... eapol=${
                            m2Eapol.take(32)
                        }... keyver=${m1.keyver} pmkid=${m1.pmkid != null}"
                    )
                    m12++
                    records.add(
                        ParsedHandshake(
                            bssid = bssid, clientMac = clientMac, essid = essid,
                            anonce = m1.nonce, snonce = m2.nonce,
                            keymic = m2.keymic ?: "00".repeat(16),
                            eapol = m2Eapol, messagePair = 0x80,
                            keyver = m1.keyver, hasBeacon = essidMap.containsKey(bssid),
                            pmkid = m1.pmkid
                        )
                    )
                }

                if (m3 != null && m4 != null) {
                    val m4Eapol = bytesToHex(m4.eapolKeyData)
                    Log.d(
                        TAG,
                        "    → M3+M4 pair (0x85) anonce=${m3.nonce.take(16)}... eapol=${
                            m4Eapol.take(32)
                        }... keyver=${m3.keyver}"
                    )
                    m34++
                    records.add(
                        ParsedHandshake(
                            bssid = bssid, clientMac = clientMac, essid = essid,
                            anonce = m3.nonce, snonce = m4.nonce,
                            keymic = m4.keymic ?: "00".repeat(16),
                            eapol = m4Eapol, messagePair = 0x85,
                            keyver = m3.keyver, hasBeacon = essidMap.containsKey(bssid)
                        )
                    )
                }

                if (m1 != null && m4 != null && m2 == null) {
                    val m4Eapol = bytesToHex(m4.eapolKeyData)
                    Log.d(
                        TAG,
                        "    → M1+M4 pair (0x81) anonce=${m1.nonce.take(16)}... eapol=${
                            m4Eapol.take(32)
                        }..."
                    )
                    m14++
                    records.add(
                        ParsedHandshake(
                            bssid = bssid, clientMac = clientMac, essid = essid,
                            anonce = m1.nonce, snonce = m4.nonce,
                            keymic = m4.keymic ?: "00".repeat(16),
                            eapol = m4Eapol, messagePair = 0x81,
                            keyver = m1.keyver, hasBeacon = essidMap.containsKey(bssid)
                        )
                    )
                }

                if (m2 != null && m3 != null && m1 == null && m4 == null) {
                    val m2Eapol = bytesToHex(m2.eapolKeyData)
                    Log.d(
                        TAG,
                        "    → M2+M3 pair (0x82) anonce=${m3.nonce.take(16)}... snonce=${
                            m2.nonce.take(16)
                        }..."
                    )
                    m23++
                    records.add(
                        ParsedHandshake(
                            bssid = bssid, clientMac = clientMac, essid = essid,
                            anonce = m3.nonce, snonce = m2.nonce,
                            keymic = m2.keymic ?: "00".repeat(16),
                            eapol = m2Eapol, messagePair = 0x82,
                            keyver = m2.keyver, hasBeacon = essidMap.containsKey(bssid)
                        )
                    )
                }
            }
        }
        Log.d(
            TAG,
            "  pairing summary: PMKID=$pmkidStandalone M1+M2=$m12 M3+M4=$m34 M1+M4=$m14 M2+M3=$m23 total=${records.size}"
        )
    }

    private fun findEapolPayloadOffset(data: ByteArray): Int {
        var off = 0
        while (off + 8 < data.size) {
            val dsap = data[off].toInt() and 0xFF
            val ssap = data[off + 1].toInt() and 0xFF
            if (dsap == 0xAA && ssap == 0xAA) {
                off += 8; return off
            }
            if (dsap == 0x88 && ssap == 0x8E) {
                off += 2; return off
            }
            off++
        }
        return off
    }

    private fun extractNonce(data: ByteArray): String? {
        var off = findEapolPayloadOffset(data)
        if (off + 4 > data.size) return null
        off += 4
        off += 5
        off += 8
        return if (off + 32 <= data.size) bytesToHex(data.copyOfRange(off, off + 32)) else null
    }

    private fun extractKeymic(data: ByteArray): String? {
        val eapolOff = findEapolPayloadOffset(data)
        val micOff = eapolOff + 81
        return if (micOff + 16 <= data.size) bytesToHex(
            data.copyOfRange(
                micOff,
                micOff + 16
            )
        ) else null
    }

    private fun extractKdvFromData(data: ByteArray): Int {
        val eapolOff = findEapolPayloadOffset(data)
        if (eapolOff + 7 > data.size) return 2
        val keyInfo =
            ((data[eapolOff + 5].toInt() and 0xFF) shl 8) or (data[eapolOff + 6].toInt() and 0xFF)
        return keyInfo and 0x03
    }

    private fun extractPmkidFromM1Data(data: ByteArray): String? {
        var off = 0
        while (off + 2 < data.size) {
            val id = data[off].toInt() and 0xFF
            val len = data[off + 1].toInt() and 0xFF
            if (id == TAG_VENDOR && off + 2 + len <= data.size && len >= 20) {
                val oui0 = data[off + 2].toInt() and 0xFF
                val oui1 = data[off + 3].toInt() and 0xFF
                val oui2 = data[off + 4].toInt() and 0xFF
                val dtype = data[off + 5].toInt() and 0xFF
                val isRsnoi = oui0 == 0x00 && oui1 == 0x0F && oui2 == 0xAC
                val isGeneric = oui0 == 0x00 && oui1 == 0x00 && oui2 == 0x00
                if ((isRsnoi || isGeneric) && dtype == 4 && len >= 20) {
                    val pmkidBytes = data.copyOfRange(off + 6, off + 22)
                    if (pmkidBytes.all { it.toInt() == 0 }) return null
                    return bytesToHex(pmkidBytes)
                }
            }
            if (id == 0x30 && off + 2 + len <= data.size) {
                val rsn = data.copyOfRange(off + 2, off + 2 + len)
                var rOff = 4
                val gcip = rsn.copyOfRange(rOff, rOff + 4); rOff += 4
                val pcs =
                    ((rsn[rOff].toInt() and 0xFF) shl 8) or (rsn[rOff + 1].toInt() and 0xFF); rOff += 2
                rOff += pcs * 4
                if (rOff + 2 <= rsn.size) {
                    val aks =
                        ((rsn[rOff].toInt() and 0xFF) shl 8) or (rsn[rOff + 1].toInt() and 0xFF); rOff += 2
                    rOff += aks * 4
                    if (rOff + 4 <= rsn.size) {
                        val pmkidId =
                            ((rsn[rOff].toInt() and 0xFF) shl 8) or (rsn[rOff + 1].toInt() and 0xFF)
                        val pmkidLen =
                            ((rsn[rOff + 2].toInt() and 0xFF) shl 8) or (rsn[rOff + 3].toInt() and 0xFF)
                        if (pmkidId == 1 && pmkidLen == 16 && rOff + 4 + 16 <= rsn.size) {
                            val pmkidBytes = rsn.copyOfRange(rOff + 4, rOff + 4 + 16)
                            if (pmkidBytes.all { it.toInt() == 0 }) return null
                            return bytesToHex(pmkidBytes)
                        }
                    }
                }
            }
            off += 2 + len
        }
        return null
    }

    private fun parseRadiotap(packet: ByteArray, offset: Int): Triple<Int, Int?, Int?> {
        val present = packet.toInt32LE(offset + 4)
        var fieldOff = offset + 8
        var flags = 0
        var channel: Int? = null
        var rssi: Int? = null
        var bitIndex = 0
        var presentFlags = present.toLong() and 0xFFFFFFFFL
        while (presentFlags != 0L && fieldOff < packet.size) {
            if ((presentFlags and 1L) != 0L) {
                when (bitIndex) {
                    1 -> flags = packet[fieldOff].toInt() and 0xFF
                    3 -> {
                        if (fieldOff + 4 <= packet.size) {
                            val freq =
                                ((packet[fieldOff + 1].toInt() and 0xFF) shl 8) or (packet[fieldOff].toInt() and 0xFF)
                            channel = freqToChannel(freq)
                        }
                    }

                    5 -> {
                        if (fieldOff < packet.size) {
                            rssi = packet[fieldOff].toInt()
                        }
                    }
                }
                fieldOff += when (bitIndex) {
                    0 -> 8; 1 -> 1; 2 -> 1; 3 -> 4; 4 -> 2; 5 -> 1; 6 -> 1; 7 -> 2; 8 -> 2; else -> 1
                }
            }
            presentFlags = presentFlags shr 1
            bitIndex++
        }
        return Triple(flags, channel, rssi)
    }

    private fun freqToChannel(freq: Int): Int {
        return when {
            freq >= 5975 -> (freq - 5975) / 5 + 1
            freq >= 5000 -> (freq - 5000) / 5 + 7
            freq >= 2412 -> (freq - 2412) / 5 + 1
            else -> 0
        }
    }

    private fun parseTaggedParams(packet: ByteArray, start: Int, len: Int): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var off = start
        while (off + 1 < start + len && off + 1 < packet.size) {
            val id = packet[off].toInt() and 0xFF
            val tLen = packet[off + 1].toInt() and 0xFF
            if (tLen == 0 || off + 2 + tLen > start + len || off + 2 + tLen > packet.size) {
                off += 2; continue
            }
            when (id) {
                TAG_SSID -> {
                    val ssidBytes = packet.copyOfRange(off + 2, off + 2 + tLen)
                    val ssid = try {
                        ssidBytes.decodeToString()
                    } catch (_: Exception) {
                        ""
                    }
                    if (ssid.isNotBlank()) result["ssid"] = ssid
                }

                TAG_CHAN -> {
                    if (tLen >= 1) result["channel"] = (packet[off + 2].toInt() and 0xFF).toString()
                }
            }
            off += 2 + tLen
        }
        return result
    }

    private fun parseRsnFromTaggedParams(packet: ByteArray, start: Int, len: Int): RsnInfo? {
        var off = start
        while (off + 1 < start + len && off + 1 < packet.size) {
            val id = packet[off].toInt() and 0xFF
            val tLen = packet[off + 1].toInt() and 0xFF
            if (tLen == 0 || off + 2 + tLen > start + len || off + 2 + tLen > packet.size) {
                off += 2; continue
            }
            when (id) {
                TAG_RSN -> {
                    val rsn = packet.copyOfRange(off + 2, off + 2 + tLen)
                    val result = parseRsnIe(rsn)
                    if (result != null) return result
                }

                TAG_VENDOR -> {
                    if (tLen >= 6) {
                        val oui = (packet[off + 2].toInt() and 0xFF) shl 16 or
                                ((packet[off + 3].toInt() and 0xFF) shl 8) or
                                (packet[off + 4].toInt() and 0xFF)
                        val vType = packet[off + 5].toInt() and 0xFF
                        if (oui == OUI_WPA && vType == WPA_OUI_TYPE && tLen >= 8) {
                            val wpa = packet.copyOfRange(off + 8, off + 2 + tLen)
                            val result = parseWpaIe(wpa)
                            if (result != null) return result
                        }
                    }
                }
            }
            off += 2 + tLen
        }
        return null
    }

    private fun parseRsnIe(data: ByteArray): RsnInfo? {
        if (data.size < 10) return null
        var off = 2
        val groupCipher = cipherTypeToString(data, off); off += 4
        if (off + 2 > data.size) return null
        val pCount =
            ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF); off += 2
        val pairwiseCipher =
            if (pCount > 0 && off + 4 <= data.size) cipherTypeToString(data, off) else null
        off += pCount * 4
        if (off + 2 > data.size) return null
        val aCount =
            ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF); off += 2
        val akm = if (aCount > 0 && off + 4 <= data.size) aknTypeToString(data, off) else null
        return RsnInfo(groupCipher = groupCipher, pairwiseCipher = pairwiseCipher, akm = akm)
    }

    private fun parseWpaIe(data: ByteArray): RsnInfo? {
        if (data.size < 10) return null
        var off = 2
        val groupCipher = cipherTypeToString(data, off); off += 4
        if (off + 2 > data.size) return null
        val pCount =
            ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF); off += 2
        val pairwiseCipher =
            if (pCount > 0 && off + 4 <= data.size) cipherTypeToString(data, off) else null
        off += pCount * 4
        if (off + 2 > data.size) return null
        val aCount =
            ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF); off += 2
        val akm = if (aCount > 0 && off + 4 <= data.size) aknTypeToString(data, off) else null
        return RsnInfo(groupCipher = groupCipher, pairwiseCipher = pairwiseCipher, akm = akm)
    }

    private fun cipherTypeToString(data: ByteArray, off: Int): String? {
        if (off + 4 > data.size) return null
        val oui = ((data[off].toInt() and 0xFF) shl 16) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                (data[off + 2].toInt() and 0xFF)
        val ctype = data[off + 3].toInt() and 0xFF
        return when {
            oui == OUI_RSN || oui == OUI_WPA -> when (ctype) {
                1 -> "WEP40"; 2 -> "TKIP"; 3 -> "CCMP"; 4 -> "WEP104"
                5 -> "BIP-CMAC-128"; 6 -> "BIP-GMAC-128"; 7 -> "BIP-GMAC-256"
                8 -> "BIP-CMAC-256"; 9 -> "GCMP-128"; 10 -> "GCMP-256"
                11 -> "CCMP-256"; 12 -> "GMAC-128"; 13 -> "GMAC-256"
                else -> "CIPHER-$ctype"
            }

            else -> null
        }
    }

    private fun aknTypeToString(data: ByteArray, off: Int): String? {
        if (off + 4 > data.size) return null
        val oui = ((data[off].toInt() and 0xFF) shl 16) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                (data[off + 2].toInt() and 0xFF)
        val atype = data[off + 3].toInt() and 0xFF
        return when {
            oui != OUI_RSN && oui != OUI_WPA -> "AKM-$atype"
            atype == 1 -> "802.1X"; atype == 2 -> "PSK"; atype == 3 -> "FT-802.1X"
            atype == 4 -> "FT-PSK"; atype == 5 -> "802.1X-SHA256"; atype == 6 -> "PSK-SHA256"
            atype == 7 -> "TDLS"; atype == 8 -> "SAE"; atype == 9 -> "FT-SAE"
            atype == 10 -> "AP-PEER"; atype == 11 -> "802.1X-SUITE-B"
            atype == 12 -> "802.1X-SUITE-B-192"; atype == 13 -> "FT-802.1X-SHA384"
            atype == 14 -> "FILS-SHA256"; atype == 15 -> "FILS-SHA384"
            atype == 16 -> "FT-FILS-SHA256"; atype == 17 -> "FT-FILS-SHA384"
            atype == 18 -> "OWE"; atype == 19 -> "FT-PSK-SHA384"
            else -> "AKM-$atype"
        }
    }

    private fun macToString(data: ByteArray, offset: Int): String {
        if (offset + 6 > data.size) return "00:00:00:00:00:00"
        return (0..5).joinToString(":") { "%02x".format(data[offset + it].toInt() and 0xFF) }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun ByteArray.toInt32LE(offset: Int): Int {
        if (offset + 4 > size) return 0
        return ((this[offset + 3].toInt() and 0xFF) shl 24) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                (this[offset].toInt() and 0xFF)
    }

    private fun ByteArray.toInt32BE(offset: Int): Int {
        if (offset + 4 > size) return 0
        return ((this[offset].toInt() and 0xFF) shl 24) or
                ((this[offset + 1].toInt() and 0xFF) shl 16) or
                ((this[offset + 2].toInt() and 0xFF) shl 8) or
                (this[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.toInt16LE(offset: Int): Int {
        if (offset + 2 > size) return 0
        return ((this[offset + 1].toInt() and 0xFF) shl 8) or (this[offset].toInt() and 0xFF)
    }

    private fun ByteArray.toInt16BE(offset: Int): Int {
        if (offset + 2 > size) return 0
        return ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
    }

    private fun ByteArray.toLongLE(offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            if (offset + i >= size) break
            result = result or (((this[offset + i].toLong()) and 0xFF) shl (i * 8))
        }
        return result
    }

}
