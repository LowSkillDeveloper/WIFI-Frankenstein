package com.lsd.wififrankenstein.util

import kotlin.math.floor
import java.security.MessageDigest
import java.util.zip.CRC32

class WpsPinGenerator {

    companion object {
        const val ALGO_MAC = 0
        const val ALGO_MACSN = 1
        const val ALGO_EMPTY = 2
        const val ALGO_STATIC = 3
        const val ALGO_EXPERIMENTAL = 4
    }

    data class WpsAlgorithm(
        val id: String,
        val name: String,
        val mode: Int,
        val func: (Long, String?) -> Any,
        val prefixes: List<String> = emptyList(),
        val deviceNames: List<String> = emptyList(),
        val description: String = "",
        val isExperimental: Boolean = false
    )

    data class PinResult(
        val pin: String,
        val algorithm: String,
        val mode: String,
        val isFromDatabase: Boolean = false,
        val isSuggested: Boolean = false,
        val isExperimental: Boolean = false
    )

    data class DslInit(
        val bk1: Int = 60,
        val bk2: Int = 195,
        val bx: List<Int>,
        val k1: Int = 0,
        val k2: Int = 0,
        val pin: Int = 0,
        val xor: Int = 0,
        val sub: Int = 0,
        val sk: Int = 0,
        val skv: Int = 0
    )

    private val algorithms = mutableListOf<WpsAlgorithm>()

    init {
        initAlgos()
        initExperimentalAlgos()
    }

    private fun initAlgos() {
        algorithms.clear()

        algorithms.add(WpsAlgorithm(
            id = "pin24",
            name = "24-bit PIN",
            mode = ALGO_MAC,
            func = { mac, _ -> mac and 0xFFFFFF },
            prefixes = listOf(
                "04:BF:6D", "0E:5D:4E", "10:7B:EF", "14:A9:E3", "28:28:5D", "2A:28:5D",
                "32:B2:DC", "38:17:66", "40:4A:03", "4E:5D:4E", "50:67:F0", "5C:F4:AB",
                "6A:28:5D", "8E:5D:4E", "AA:28:5D", "B0:B2:DC", "C8:6C:87", "CC:5D:4E",
                "CE:5D:4E", "EA:28:5D", "E2:43:F6", "EC:43:F6", "EE:43:F6", "F2:B2:DC",
                "FC:F5:28", "FE:F5:28", "4C:9E:FF", "00:14:D1", "D8:EB:97",
                "1C:7E:E5", "84:C9:B2", "FC:75:16", "14:D6:4D", "90:94:E4", "BC:F6:85", "C4:A8:1D",
                "00:66:4B", "08:7A:4C", "14:B9:68", "20:08:ED", "34:6B:D3", "4C:ED:DE",
                "78:6A:89", "88:E3:AB", "D4:6E:5C", "E8:CD:2D", "EC:23:3D", "EC:CB:30",
                "F4:9F:F3", "20:CF:30", "90:E6:BA", "E0:CB:4E", "D4:BF:7F:4", "F8:C0:91",
                "00:1C:DF", "00:22:75", "08:86:3B", "00:B0:0C", "08:10:75", "C8:3A:35",
                "00:22:F7", "00:1F:1F", "00:26:5B", "68:B6:CF", "78:8D:F7", "BC:14:01",
                "20:2B:C1", "30:87:30", "5C:4C:A9", "62:23:3D", "62:3C:E4", "62:3D:FF",
                "62:53:D4", "62:55:9C", "62:6B:D3", "62:7D:5E", "62:96:BF", "62:A8:E4",
                "62:B6:86", "62:C0:6F", "62:C6:1F", "62:C7:14", "62:CB:A8", "62:CD:BE",
                "62:E8:7B", "64:16:F0", "6A:1D:67", "6A:23:3D", "6A:3D:FF", "6A:53:D4",
                "6A:55:9C", "6A:6B:D3", "6A:96:BF", "6A:7D:5E", "6A:A8:E4", "6A:C0:6F",
                "6A:C6:1F", "6A:C7:14", "6A:CB:A8", "6A:CD:BE", "6A:D1:5E", "6A:D1:67",
                "72:1D:67", "72:23:3D", "72:3C:E4", "72:3D:FF", "72:53:D4", "72:55:9C",
                "72:6B:D3", "72:7D:5E", "72:96:BF", "72:A8:E4", "72:C0:6F", "72:C6:1F",
                "72:C7:14", "72:CB:A8", "72:CD:BE", "72:D1:5E", "72:E8:7B", "00:26:CE",
                "98:97:D1", "E0:41:36", "B2:46:FC", "E2:41:36", "00:E0:20",
                "5C:A3:9D", "D8:6C:E9", "DC:71:44", "80:1F:02", "E4:7C:F9",
                "00:0C:F6", "00:A0:26", "A0:F3:C1", "64:70:02", "B0:48:7A", "F8:1A:67",
                "F8:D1:11", "34:BA:9A", "B4:94:4E"
            ),
            deviceNames = listOf("ZyXEL Internet Center", "Keenetic Giga II", "Keenetic Lite III",
                "Keenetic Ultra", "RalinkAPS", "Wireless-N Multi-function AP",
                "Sitecom Wireless Gigabit Router"),
            description = "Pin = MAC[7..12]"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin28",
            name = "28-bit PIN",
            mode = ALGO_MAC,
            func = { mac, _ -> mac and 0xFFFFFFF },
            prefixes = listOf("20:0B:C7", "48:46:FB", "D4:6A:A8", "F8:4A:BF"),
            description = "Pin = MAC[6..12]"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin32",
            name = "32-bit PIN",
            mode = ALGO_MAC,
            func = { mac, _ -> mac % 0x100000000L },
            prefixes = listOf(
                "00:07:26", "D8:FE:E3", "FC:8B:97", "10:62:EB", "1C:5F:2B", "48:EE:0C",
                "80:26:89", "90:8D:78", "E8:CC:18", "2C:AB:25",
                "10:BF:48", "14:DA:E9", "30:85:A9", "50:46:5D", "54:04:A6", "C8:60:00",
                "F4:6D:04", "80:1F:02"
            ),
            deviceNames = listOf("DIR-825AC"),
            description = "Pin = MAC[5..12]"
        ))

        algorithms.add(WpsAlgorithm("pin36", "36-bit PIN", ALGO_MAC, { mac, _ -> mac % 0x1000000000L }, description = "Pin = MAC[4..12]"))
        algorithms.add(WpsAlgorithm("pin40", "40-bit PIN", ALGO_MAC, { mac, _ -> mac % 0x10000000000L }, description = "Pin = MAC[3..12]"))
        algorithms.add(WpsAlgorithm("pin44", "44-bit PIN", ALGO_MAC, { mac, _ -> mac % 0x100000000000L }, description = "Pin = MAC[2..12]"))
        algorithms.add(WpsAlgorithm("pin48", "48-bit PIN", ALGO_MAC, { mac, _ -> mac }, description = "Pin = MAC[1..12]"))

        algorithms.add(WpsAlgorithm(
            id = "pin24rh",
            name = "Reverse byte 24-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac and 0xFFFFFFL
                val macStr = zeroFill(m.toString(16), 6)
                val reversed = macStr.substring(4, 6) + macStr.substring(2, 4) + macStr.substring(0, 2)
                reversed.toLong(16)
            },
            description = "Pin = reverse_bytes(MAC[7..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin32rh",
            name = "Reverse byte 32-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac % 0x100000000L
                val macStr = zeroFill(m.toString(16), 8)
                val reversed = macStr.substring(6, 8) + macStr.substring(4, 6) +
                        macStr.substring(2, 4) + macStr.substring(0, 2)
                reversed.toLong(16)
            },
            description = "Pin = reverse_bytes(MAC[5..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin48rh",
            name = "Reverse byte 48-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val macStr = zeroFill(mac.toString(16), 12)
                val reversed = macStr.substring(10, 12) + macStr.substring(8, 10) +
                        macStr.substring(6, 8) + macStr.substring(4, 6) +
                        macStr.substring(2, 4) + macStr.substring(0, 2)
                reversed.toLong(16)
            },
            description = "Pin = reverse_bytes(MAC[1..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin24rn",
            name = "Reverse nibble 24-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac and 0xFFFFFFL
                val macStr = zeroFill(m.toString(16), 6)
                reverse(macStr).toLong(16)
            },
            description = "Pin = reverse_nibbles(MAC[7..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin32rn",
            name = "Reverse nibble 32-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac % 0x100000000L
                val macStr = zeroFill(m.toString(16), 8)
                reverse(macStr).toLong(16)
            },
            description = "Pin = reverse_nibbles(MAC[5..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin48rn",
            name = "Reverse nibble 48-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val macStr = zeroFill(mac.toString(16), 12)
                reverse(macStr).toLong(16)
            },
            description = "Pin = reverse_nibbles(MAC[1..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin24rb",
            name = "Reverse bits 24-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac and 0xFFFFFFL
                val binaryStr = zeroFill(m.toString(2), 24)
                reverse(binaryStr).toLong(2)
            },
            description = "Pin = reverse_bits(MAC[7..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin32rb",
            name = "Reverse bits 32-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac % 0x100000000L
                val binaryStr = zeroFill(m.toString(2), 32)
                reverse(binaryStr).toLong(2)
            },
            description = "Pin = reverse_bits(MAC[5..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pin48rb",
            name = "Reverse bits 48-bit",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val binaryStr = zeroFill(mac.toString(2), 48)
                reverse(binaryStr).toLong(2)
            },
            description = "Pin = reverse_bits(MAC[1..12])"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinDLink",
            name = "D-Link PIN",
            mode = ALGO_MAC,
            func = { mac, _ -> algoDLink(mac) },
            prefixes = listOf(
                "14:D6:4D", "1C:7E:E5", "28:10:7B", "84:C9:B2", "A0:AB:1B", "B8:A3:86",
                "C0:A0:BB", "CC:B2:55", "FC:75:16", "00:14:D1", "D8:EB:97"
            ),
            deviceNames = listOf("TEW-651BR"),
            description = "D-Link algorithm"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinDLink1",
            name = "D-Link PIN +1",
            mode = ALGO_MAC,
            func = { mac, _ -> algoDLink(mac + 1) },
            prefixes = listOf(
                "00:18:E7", "00:19:5B", "00:1C:F0", "00:1E:58", "00:21:91", "00:22:B0",
                "00:24:01", "00:26:5A", "14:D6:4D", "1C:7E:E5", "34:08:04", "5C:D9:98",
                "84:C9:B2", "B8:A3:86", "C8:BE:19", "C8:D3:A3", "CC:B2:55", "00:14:D1"
            ),
            deviceNames = listOf("D-Link Systems DIR-615", "Wireless N Router"),
            description = "D-Link algorithm with MAC+1"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinBelkin",
            name = "Belkin PIN",
            mode = ALGO_MACSN,
            func = { mac, ser -> algoDslMacSn(mac, ser, DslInit(bx = listOf(66, 129, 209, 10, 24, 3, 39))) },
            prefixes = listOf("08:86:3B", "94:10:3E", "B4:75:0E", "C0:56:27", "EC:1A:59"),
            deviceNames = listOf("Belkin Wireless Router(WFA)"),
            description = "Belkin algorithm"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinEasyBox",
            name = "Vodafone EasyBox",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: (mac and 0xFFFF).toString()
                algoDslMacSn(mac, serial, DslInit(bx = listOf(129, 65, 6, 10, 136, 80, 33)))
            },
            prefixes = listOf("00:26:4D", "38:22:9D", "7C:4F:B5"),
            description = "Vodafone EasyBox algorithm"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinLivebox",
            name = "Livebox Arcadyan",
            mode = ALGO_MACSN,
            func = { mac, ser -> algoDslMacSn(mac - 2, ser, DslInit(bx = listOf(129, 65, 6, 10, 136, 80, 33))) },
            prefixes = listOf(
                "18:83:BF", "48:8D:36", "4C:09:D4", "50:7E:5D", "5C:DC:96", "74:31:70",
                "84:9C:A6", "88:03:55", "9C:80:DF", "A8:D3:F7", "D0:05:2A", "D4:63:FE"
            ),
            deviceNames = listOf("Livebox Wireless Router(WFA)"),
            description = "Livebox Arcadyan algorithm"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinASUS",
            name = "ASUS PIN",
            mode = ALGO_MAC,
            func = { mac, _ -> algoAsus(mac) },
            prefixes = listOf(
                "04:92:26", "04:D9:F5", "08:60:6E", "08:62:66", "10:7B:44", "10:BF:48",
                "10:C3:7B", "14:DD:A9", "1C:87:2C", "1C:B7:2C", "2C:56:DC", "2C:FD:A1",
                "30:5A:3A", "38:2C:4A", "38:D5:47", "40:16:7E", "50:46:5D", "54:A0:50",
                "60:45:CB", "60:A4:4C", "70:4D:7B", "74:D0:2B", "78:24:AF", "88:D7:F6",
                "9C:5C:8E", "AC:22:0B", "AC:9E:17", "B0:6E:BF", "BC:EE:7B", "C8:60:00",
                "D0:17:C2", "D8:50:E6", "E0:3F:49", "F0:79:59", "F8:32:E4",
                "00:07:26", "00:08:A1", "00:17:7C", "00:1E:A6", "00:30:4F", "00:E0:4C",
                "04:8D:38", "08:10:77", "08:10:78", "08:10:79", "08:3E:5D", "10:FE:ED",
                "18:1E:78", "1C:44:19", "24:20:C7", "24:7F:20", "2C:AB:25", "30:85:A9",
                "3C:1E:04", "40:F2:01", "44:E9:DD", "48:EE:0C", "54:64:D9", "54:B8:0A",
                "58:7B:E9", "60:D1:AA", "64:51:7E", "64:D9:54", "6C:19:8F", "6C:72:20",
                "6C:FD:B9", "78:D9:9F", "7C:26:64", "80:3F:5D", "84:A4:23", "88:A6:C6",
                "8C:10:D4", "8C:88:2B", "90:4D:4A", "90:72:82", "90:F6:52", "94:FB:B2",
                "A0:1B:29", "A0:F3:C1", "A8:F7:E0", "AC:A2:13", "B8:55:10", "B8:EE:0E",
                "BC:34:00", "BC:96:80", "C8:91:F9", "D0:0E:D9", "D0:84:B0", "D8:FE:E3",
                "E4:BE:ED", "E8:94:F6", "EC:1A:59", "EC:4C:4D", "F4:28:53", "F4:3E:61",
                "F4:6B:EF", "F8:AB:05", "FC:8B:97",
                "70:62:B8", "78:54:2E", "C0:A0:BB", "C4:12:F5", "C4:A8:1D", "E8:CC:18",
                "EC:22:80", "F8:E9:03"
            ),
            deviceNames = listOf("ASUS WPS Router", "RT-N10PV2", "RT-N10U", "RT-N12", "RT-N12VP"),
            description = "ASUS algorithm"
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinAirocon",
            name = "Airocon Realtek",
            mode = ALGO_MAC,
            func = { mac, _ -> algoAirocon(mac) },
            prefixes = listOf(
                "00:07:26", "00:0B:2B", "00:0E:F4", "00:13:33", "00:17:7C", "00:1A:EF",
                "00:E0:4B", "02:10:18", "08:10:73", "08:10:77", "10:13:EE", "2C:AB:25",
                "78:8C:54", "80:3F:5D", "94:FB:B2", "BC:96:80", "F4:3E:61", "FC:8B:97"
            ),
            description = "Airocon Realtek algorithm"
        ))

        algorithms.add(WpsAlgorithm("pinInvNIC", "Inv NIC to PIN", ALGO_MAC,
            { mac, _ -> (mac and 0xFFFFFFL).inv() and 0xFFFFFFL }, description = "Pin = Inv(MAC[7..12])"))
        algorithms.add(WpsAlgorithm("pinNIC2", "NIC * 2", ALGO_MAC,
            { mac, _ -> (mac and 0xFFFFFFL) * 2 }, description = "Pin = MAC[7..12] * 2"))
        algorithms.add(WpsAlgorithm("pinNIC3", "NIC * 3", ALGO_MAC,
            { mac, _ -> (mac and 0xFFFFFFL) * 3 }, description = "Pin = MAC[7..12] * 3"))

        algorithms.add(WpsAlgorithm(
            id = "pinOUIaddNIC", name = "OUI + NIC", mode = ALGO_MAC,
            func = { mac, _ ->
                val macStr = zeroFill(mac.toString(16), 12)
                val oui = macStr.substring(0, 6).toLong(16)
                val nic = macStr.substring(6, 12).toLong(16)
                (oui + nic) % 0x1000000L
            }, description = "Pin = MAC[1..6] + MAC[7..12]"))

        algorithms.add(WpsAlgorithm(
            id = "pinOUIsubNIC", name = "OUI - NIC", mode = ALGO_MAC,
            func = { mac, _ ->
                val macStr = zeroFill(mac.toString(16), 12)
                val oui = macStr.substring(0, 6).toLong(16)
                val nic = macStr.substring(6, 12).toLong(16)
                if (nic < oui) oui - nic else (oui + 0x1000000L - nic) and 0xFFFFFFL
            }, description = "Pin = MAC[1..6] - MAC[7..12]"))

        algorithms.add(WpsAlgorithm(
            id = "pinOUIxorNIC", name = "OUI ^ NIC", mode = ALGO_MAC,
            func = { mac, _ ->
                val macStr = zeroFill(mac.toString(16), 12)
                val oui = macStr.substring(0, 6).toLong(16)
                val nic = macStr.substring(6, 12).toLong(16)
                oui xor nic
            }, description = "Pin = MAC[1..6] ^ MAC[7..12]"))

        algorithms.add(WpsAlgorithm(
            id = "pinEmpty",
            name = "Empty PIN",
            mode = ALGO_EMPTY,
            func = { _, _ -> "" },
            prefixes = listOf(
                "E4:6F:13", "EC:22:80", "58:D5:6E", "10:62:EB", "10:BE:F5", "1C:5F:2B",
                "80:26:89", "A0:AB:1B", "74:DA:DA", "9C:D6:43",
                "68:A0:F6", "0C:96:BF", "20:F3:A3", "78:F5:FD", "AC:E2:15", "C8:D1:5E",
                "00:0E:8F", "D4:21:22", "3C:98:72", "78:81:02", "78:94:B4", "D4:60:E3", "E0:60:66",
                "00:4A:77", "2C:95:7F", "64:13:6C", "74:A7:8E", "88:D2:74", "70:2E:22",
                "74:B5:7E", "78:96:82", "7C:39:53", "8C:68:C8", "D4:76:EA", "34:4D:EA",
                "38:D8:2F", "54:BE:53", "70:9F:2D", "94:A7:B7", "98:13:33", "CA:A3:66", "D0:60:8C"
            ),
            deviceNames = listOf("DIR-620", "DIR-822", "DIR-825AC", "DIR-825ACG1", "DSL-2640U",
                "3G Wireless gateway", "RV6688BCM", "S1010", "ADSL Modem/Router"),
            description = "Empty PIN"
        ))

        addStaticPins()
    }

    private fun addStaticPins() {
        algorithms.add(WpsAlgorithm("pinCisco", "Cisco", ALGO_STATIC, { _, _ -> 1234567 },
            prefixes = listOf("00:1A:2B", "00:24:8C", "00:26:18", "34:4D:EB", "70:71:BC", "E0:69:95", "E0:CB:4E", "70:54:F5"),
            deviceNames = listOf("ASUS Wireless Router", "Atheros AP"), description = "Pin = 1234567"))

        algorithms.add(WpsAlgorithm("pinBrcm1", "Broadcom 1", ALGO_STATIC, { _, _ -> 2017252 },
            prefixes = listOf("AC:F1:DF", "BC:F6:85", "C8:D3:A3", "98:8B:5D", "00:1A:A9", "14:14:4B", "EC:62:64"),
            description = "Pin = 2017252"))

        algorithms.add(WpsAlgorithm("pinBrcm2", "Broadcom 2", ALGO_STATIC, { _, _ -> 4626484 },
            prefixes = listOf("14:D6:4D", "1C:7E:E5", "28:10:7B", "84:C9:B2", "B8:A3:86", "BC:F6:85", "C8:BE:19"),
            deviceNames = listOf("BroadcomAP"), description = "Pin = 4626484"))

        algorithms.add(WpsAlgorithm("pinBrcm3", "Broadcom 3", ALGO_STATIC, { _, _ -> 7622990 },
            prefixes = listOf("14:D6:4D", "1C:7E:E5", "28:10:7B", "B8:A3:86", "BC:F6:85", "C8:BE:19", "7C:03:4C"),
            deviceNames = listOf("BroadcomAP"), description = "Pin = 7622990"))

        algorithms.add(WpsAlgorithm("pinBrcm4", "Broadcom 4", ALGO_STATIC, { _, _ -> 6232714 },
            prefixes = listOf("14:D6:4D", "1C:7E:E5", "28:10:7B", "84:C9:B2", "B8:A3:86", "BC:F6:85",
                "C8:BE:19", "C8:D3:A3", "CC:B2:55", "FC:75:16", "20:4E:7F", "4C:17:EB",
                "18:62:2C", "7C:03:D8", "D8:6C:E9"), description = "Pin = 6232714"))

        algorithms.add(WpsAlgorithm("pinBrcm5", "Broadcom 5", ALGO_STATIC, { _, _ -> 1086411 },
            prefixes = listOf("14:D6:4D", "1C:7E:E5", "28:10:7B", "84:C9:B2", "B8:A3:86", "BC:F6:85",
                "C8:BE:19", "C8:D3:A3", "CC:B2:55", "FC:75:16", "20:4E:7F", "4C:17:EB",
                "18:62:2C", "7C:03:D8", "D8:6C:E9"), description = "Pin = 1086411"))

        algorithms.add(WpsAlgorithm("pinBrcm6", "Broadcom 6", ALGO_STATIC, { _, _ -> 3195719 },
            prefixes = listOf("14:D6:4D", "1C:7E:E5", "28:10:7B", "84:C9:B2", "B8:A3:86", "BC:F6:85",
                "C8:BE:19", "C8:D3:A3", "CC:B2:55", "FC:75:16", "20:4E:7F", "4C:17:EB",
                "18:62:2C", "7C:03:D8", "D8:6C:E9"), description = "Pin = 3195719"))

        algorithms.add(WpsAlgorithm("pinAirc1", "Airocon 1", ALGO_STATIC, { _, _ -> 3043203 },
            prefixes = listOf("18:1E:78", "40:F2:01", "44:E9:DD", "D0:84:B0"), description = "Pin = 3043203"))

        algorithms.add(WpsAlgorithm("pinAirc2", "Airocon 2", ALGO_STATIC, { _, _ -> 7141225 },
            prefixes = listOf("84:A4:23", "8C:10:D4", "88:A6:C6"), description = "Pin = 7141225"))

        algorithms.add(WpsAlgorithm("pinDSL2740R", "DSL-2740R", ALGO_STATIC, { _, _ -> 6817554 },
            prefixes = listOf("00:26:5A", "1C:BD:B9", "34:08:04", "5C:D9:98", "84:C9:B2", "FC:75:16"),
            description = "Pin = 6817554"))

        algorithms.add(WpsAlgorithm("pinRealtek1", "Realtek 1", ALGO_STATIC, { _, _ -> 9566146 },
            prefixes = listOf("00:14:D1", "00:0C:42", "00:0E:E8"), description = "Pin = 9566146"))

        algorithms.add(WpsAlgorithm("pinRealtek2", "Realtek 2", ALGO_STATIC, { _, _ -> 9571911 },
            prefixes = listOf("00:72:63", "E4:BE:ED"), deviceNames = listOf("RTK_AP"), description = "Pin = 9571911"))

        algorithms.add(WpsAlgorithm("pinRealtek3", "Realtek 3", ALGO_STATIC, { _, _ -> 4856371 },
            prefixes = listOf("08:C6:B3"), description = "Pin = 4856371"))

        algorithms.add(WpsAlgorithm("pinUpvel", "Upvel", ALGO_STATIC, { _, _ -> 2085483 },
            prefixes = listOf("78:44:76", "D4:BF:7F:0", "F8:C0:91"), description = "Pin = 2085483"))

        algorithms.add(WpsAlgorithm("pinUR814AC", "UR-814AC", ALGO_STATIC, { _, _ -> 4397768 },
            prefixes = listOf("D4:BF:7F:60"), description = "Pin = 4397768"))

        algorithms.add(WpsAlgorithm("pinUR825AC", "UR-825AC", ALGO_STATIC, { _, _ -> 529417 },
            prefixes = listOf("D4:BF:7F:5"), deviceNames = listOf("UR-825N4G"), description = "Pin = 0529417"))

        algorithms.add(WpsAlgorithm("pinOnlime", "Onlime", ALGO_STATIC, { _, _ -> 9995604 },
            prefixes = listOf("D4:BF:7F", "F8:C0:91", "14:4D:67", "78:44:76", "00:14:D1"),
            deviceNames = listOf("RTK_AP", "RTK_AP_2x", "RTL8196E"), description = "Pin = 9995604"))

        algorithms.add(WpsAlgorithm("pinEdimax", "Edimax", ALGO_STATIC, { _, _ -> 3561153 },
            prefixes = listOf("80:1F:02", "00:E0:4C"), description = "Pin = 3561153"))

        algorithms.add(WpsAlgorithm("pinThomson", "Thomson", ALGO_STATIC, { _, _ -> 6795814 },
            prefixes = listOf("00:26:24", "44:32:C8", "88:F7:C7", "CC:03:FA"), description = "Pin = 6795814"))

        algorithms.add(WpsAlgorithm("pinHG532x", "HG532x", ALGO_STATIC, { _, _ -> 3425928 },
            prefixes = listOf("00:66:4B", "08:63:61", "08:7A:4C", "0C:96:BF", "14:B9:68", "20:08:ED", "24:69:A5",
                "34:6B:D3", "78:6A:89", "88:E3:AB", "9C:C1:72", "AC:E2:15", "D0:7A:B5", "CC:A2:23",
                "E8:CD:2D", "F8:01:13", "F8:3D:FF"), description = "Pin = 3425928"))

        algorithms.add(WpsAlgorithm("pinH108L", "H108L", ALGO_STATIC, { _, _ -> 9422988 },
            prefixes = listOf("4C:09:B4", "4C:AC:0A", "84:74:2A:4", "9C:D2:4B", "B0:75:D5", "C8:64:C7", "DC:02:8E", "FC:C8:97"),
            deviceNames = listOf("H108L"), description = "Pin = 9422988"))

        algorithms.add(WpsAlgorithm("pinONO", "CBN ONO", ALGO_STATIC, { _, _ -> 9575521 },
            prefixes = listOf("5C:35:3B", "DC:53:7C"), description = "Pin = 9575521"))
    }

    // Вспомогательные функции
    private fun computeChecksum(pin: Long): Int {
        var accum = 0
        var tempPin = pin
        while (tempPin > 0) {
            accum += 3 * (tempPin % 10).toInt()
            tempPin /= 10
            accum += (tempPin % 10).toInt()
            tempPin /= 10
        }
        return (10 - (accum % 10)) % 10
    }

    private fun addChecksum(pin: Long): Long {
        return pin * 10 + computeChecksum(pin)
    }

    private fun macToBytes(mac: Long): IntArray {
        return intArrayOf(
            ((mac shr 40) and 0xFF).toInt(),
            ((mac shr 32) and 0xFF).toInt(),
            ((mac shr 24) and 0xFF).toInt(),
            ((mac shr 16) and 0xFF).toInt(),
            ((mac shr 8) and 0xFF).toInt(),
            (mac and 0xFF).toInt()
        )
    }

    private fun initExperimentalAlgos() {
        algorithms.add(WpsAlgorithm(
            id = "pinNetgearSN",
            name = "Netgear SN-based [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { _, ser ->
                val serial = ser?.replace(Regex("\\D"), "") ?: ""
                val sn = serial.padStart(7, '0').takeLast(7)
                val pin7 = sn.toLongOrNull() ?: 0L
                pinChecksum(pin7 % 10000000L)
            },
            prefixes = listOf("00:14:BF", "00:18:F8", "00:1A:70", "00:24:B2", "C0:3F:0E", "E0:91:F5"),
            description = "[EXPERIMENTAL] Pin from last 7 digits of SN + checksum",
            isExperimental = true
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinArris",
            name = "Arris OUI XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val oui = (mac ushr 24) and 0xFFFFFFL
                (oui xor 0x5A5A5AL) % 10000000L
            },
            prefixes = listOf("00:1D:1A", "00:18:39", "F4:28:53", "E8:ED:05"),
            description = "[EXPERIMENTAL] Pin = (OUI ^ 0x5A5A5A) % 10^7",
            isExperimental = true
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinTPLinkXOR",
            name = "TP-Link XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val m = mac and 0xFFFFFFL
                (m xor 0x55AA55L) % 10000000L
            },
            prefixes = listOf("54:E6:FC", "F4:15:35", "00:14:78", "A4:2B:8C"),
            description = "[EXPERIMENTAL] Pin = (MAC & 0xFFFFFF) ^ 0x55AA55",
            isExperimental = true
        ))

        algorithms.add(WpsAlgorithm(
            id = "pinZyXELRev",
            name = "ZyXEL nibble-rev [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val hex = zeroFill(mac.toString(16), 12)
                val rev = reverse(hex)
                val value = rev.toLongOrNull(16) ?: 0L
                (value + 0xA00000000000L) % 10000000L
            },
            prefixes = listOf("00:19:CB", "00:23:F8", "28:28:5D", "40:4A:03"),
            description = "[EXPERIMENTAL] Pin = reverse_nibbles(MAC) + 0xA00000000000",
            isExperimental = true
        ))
        
        // ComputePIN - базовый алгоритм с контрольной суммой
        algorithms.add(WpsAlgorithm(
            id = "pinComputePIN",
            name = "ComputePIN [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val accum = ((mac shr 8) and 0xFFFFFF) * 10
                addChecksum(accum)
            },
            prefixes = listOf(),
            description = "[EXPERIMENTAL] Generic PIN computation with checksum",
            isExperimental = true
        ))

        // Arcadyan/Astoria Networks
        algorithms.add(WpsAlgorithm(
            id = "pinArcadyanAlt",
            name = "Arcadyan Alt [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                val seed = ((mac and 0xFF) + (serial.takeLast(6).toLongOrNull() ?: 0)) % 10000000
                addChecksum(seed)
            },
            prefixes = listOf("00:12:BF", "00:1A:2A", "00:1D:19", "00:23:08", "00:26:44"),
            description = "[EXPERIMENTAL] Arcadyan alternative algorithm",
            isExperimental = true
        ))

        // TRENDnet суммирование байтов
        algorithms.add(WpsAlgorithm(
            id = "pinTrendNetSum",
            name = "TRENDnet Sum [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val sum = bytes[0] + bytes[1] + bytes[2] + bytes[3] + bytes[4] + bytes[5]
                val pin = ((sum * 2454) + bytes[5] * 256 + bytes[4]) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:14:D1", "30:46:9A", "D8:EB:97"),
            description = "[EXPERIMENTAL] TRENDnet byte summation algorithm",
            isExperimental = true
        ))

        // ASUS альтернативный
        algorithms.add(WpsAlgorithm(
            id = "pinAsusAlt",
            name = "ASUS Alt [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val pin = mac.toString(16).takeLast(7).toLong(16)
                addChecksum(pin % 10000000)
            },
            prefixes = listOf("00:0C:43", "00:11:2F", "00:13:D4", "00:15:F2", "00:17:31",
                "00:1A:92", "00:1D:60", "00:1E:8C", "00:1F:C6", "00:22:15",
                "00:23:54", "00:24:8C", "00:26:18", "08:60:6E", "10:BF:48"),
            description = "[EXPERIMENTAL] ASUS alternative algorithm",
            isExperimental = true
        ))

        // Huawei простое умножение
        algorithms.add(WpsAlgorithm(
            id = "pinHuaweiMul10",
            name = "Huawei x10 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastBytes = mac and 0xFFFFFF
                val pin = (lastBytes * 10) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:0A:F5", "00:18:82", "00:1E:10", "00:25:68", "00:25:9E",
                "00:34:FE", "00:46:4B", "00:66:4B", "04:C0:6F", "04:F9:38"),
            description = "[EXPERIMENTAL] Huawei simple multiplication by 10",
            isExperimental = true
        ))

        // Netgear серийный номер * 3
        algorithms.add(WpsAlgorithm(
            id = "pinNetgearSerial3",
            name = "Netgear SN*3 [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.length >= 6) {
                    val seed = serial.takeLast(6).toLongOrNull() ?: 0
                    addChecksum((seed * 3) % 10000000)
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic xor 0x1C3A2F) % 10000000)
                }
            },
            prefixes = listOf("00:09:5B", "00:0F:B5", "00:14:6C", "00:1B:2F", "00:1E:2A",
                "00:1F:33", "00:22:3F", "00:24:B2", "00:26:F2", "00:8E:F2"),
            description = "[EXPERIMENTAL] Netgear serial * 3 algorithm",
            isExperimental = true
        ))

        // Ralink/MediaTek суммирование байтов
        algorithms.add(WpsAlgorithm(
            id = "pinRalinkSum",
            name = "Ralink Sum [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val pin = (bytes[0] + bytes[1] + bytes[2]) * 1000 +
                        (bytes[3] + bytes[4] + bytes[5]) * 10
                addChecksum((pin % 10000000).toLong())
            },
            prefixes = listOf("00:0C:43", "00:3A:98", "00:50:FC", "00:90:CC", "08:57:00"),
            description = "[EXPERIMENTAL] Ralink/MediaTek byte summation",
            isExperimental = true
        ))

        // Realtek XOR алгоритм
        algorithms.add(WpsAlgorithm(
            id = "pinRealtekXOR",
            name = "Realtek XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastThreeBytes = mac and 0xFFFFFF
                val pin = ((lastThreeBytes xor 0x2C83B5) * 7) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:E0:4C", "52:54:00"),
            description = "[EXPERIMENTAL] Realtek XOR algorithm",
            isExperimental = true
        ))

        // Linksys серийный номер в квадрате
        algorithms.add(WpsAlgorithm(
            id = "pinLinksysSquare",
            name = "Linksys Square [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.length >= 4) {
                    val lastFour = serial.takeLast(4)
                    val seed = lastFour.toLongOrNull(16) ?: 0
                    addChecksum((seed * seed) % 10000000)
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic * 8) % 10000000)
                }
            },
            prefixes = listOf("00:04:5A", "00:06:25", "00:0C:41", "00:0F:66", "00:12:17",
                "00:13:10", "00:14:BF", "00:16:B6", "00:18:39", "00:18:F8"),
            description = "[EXPERIMENTAL] Linksys serial squared algorithm",
            isExperimental = true
        ))

        // TP-Link битовые операции
        algorithms.add(WpsAlgorithm(
            id = "pinTPLinkBits",
            name = "TP-Link Bits [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastBytes = mac and 0xFFFFFF
                var pin = lastBytes
                pin = ((pin shr 16) and 0xFF) or
                        ((pin shl 8) and 0xFF0000) or
                        (pin and 0xFF00)
                pin = (pin * 10) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:0A:EB", "00:11:3B", "00:14:78", "00:19:E0", "00:1D:0F",
                "00:21:27", "00:23:CD", "00:25:86", "00:27:19", "08:57:00"),
            description = "[EXPERIMENTAL] TP-Link bit operations algorithm",
            isExperimental = true
        ))

        // Broadcom XOR алгоритм
        algorithms.add(WpsAlgorithm(
            id = "pinBroadcomXOR",
            name = "Broadcom XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val seed = (bytes[3] shl 16) or (bytes[4] shl 8) or bytes[5]
                val pin = (seed xor 0x1F3A5C) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:10:18", "00:90:4C", "14:D6:4D", "1C:7E:E5", "28:10:7B",
                "84:C9:B2", "B8:A3:86", "BC:F6:85", "C8:BE:19"),
            description = "[EXPERIMENTAL] Broadcom XOR algorithm",
            isExperimental = true
        ))

        // Buffalo сложение и умножение
        algorithms.add(WpsAlgorithm(
            id = "pinBuffaloAdd",
            name = "Buffalo Add [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = ((nic + 0x91543) * 7) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:0D:0B", "00:16:01", "00:1D:73", "00:24:A5", "4C:E6:76", "10:6F:3F"),
            description = "[EXPERIMENTAL] Buffalo addition algorithm",
            isExperimental = true
        ))

        // ZTE XOR вариант
        algorithms.add(WpsAlgorithm(
            id = "pinZTEXOR",
            name = "ZTE XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = (nic xor 0x345678) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:19:E4", "00:25:F1", "00:25:F2", "04:5F:A7", "0C:54:A5",
                "18:13:73", "30:87:30", "34:4B:50", "34:E0:CF", "4C:09:B4",
                "4C:16:F1", "54:25:EA", "68:1A:B2", "6C:8D:C1", "78:31:2B",
                "84:DB:AC", "9C:D2:4B", "A4:A1:C2", "AC:6F:BB", "C8:6C:87",
                "D0:15:4A", "D8:6C:E9", "DC:02:8E", "E0:C3:F3", "F4:6D:E2"),
            description = "[EXPERIMENTAL] ZTE XOR algorithm",
            isExperimental = true
        ))

        // Billion
        algorithms.add(WpsAlgorithm(
            id = "pinBillionXOR",
            name = "Billion XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastBytes = mac and 0xFFFFFF
                val pin = ((lastBytes xor 0x000504) * 13) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:04:ED", "00:0A:52", "00:11:F6", "00:15:C6", "00:19:70",
                "00:1A:8A", "00:1C:A2", "00:22:33", "00:25:12", "00:30:4F"),
            description = "[EXPERIMENTAL] Billion XOR algorithm",
            isExperimental = true
        ))

        // Technicolor/Thomson
        algorithms.add(WpsAlgorithm(
            id = "pinTechnicolorSN",
            name = "Technicolor SN [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.length >= 11) {
                    val part1 = serial.substring(6, 9).toIntOrNull() ?: 0
                    val part2 = serial.substring(9, 11).toIntOrNull() ?: 0
                    val pin = ((part1 * 100 + part2) * 8) % 10000000
                    addChecksum(pin.toLong())
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic + 0x6D3BE7) % 10000000)
                }
            },
            prefixes = listOf("00:03:C9", "00:08:9B", "00:0E:50", "00:14:7F", "00:17:82",
                "00:18:8D", "00:19:3E", "00:1B:E3", "00:1E:69", "00:22:43",
                "00:24:14", "00:25:53", "30:D3:2D", "50:39:55", "58:35:D9",
                "64:7C:34", "70:1A:ED", "7C:03:AB", "80:C6:AB", "88:F0:77"),
            description = "[EXPERIMENTAL] Technicolor/Thomson algorithm",
            isExperimental = true
        ))

        // Fiberhome
        algorithms.add(WpsAlgorithm(
            id = "pinFiberhomeXOR",
            name = "Fiberhome XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val seed = (bytes[3] * 256 * 256) + (bytes[4] * 256) + bytes[5]
                val pin = (seed xor 0x4F21AD) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("40:7B:1B", "48:0B:B2", "4C:E6:C0", "5C:64:8E", "78:1D:BA",
                "8C:E7:48", "CC:81:DA", "FC:4B:1C"),
            description = "[EXPERIMENTAL] Fiberhome XOR algorithm",
            isExperimental = true
        ))

        // ActionTec
        algorithms.add(WpsAlgorithm(
            id = "pinActiontecMul7",
            name = "ActionTec x7 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastThree = (mac and 0xFFFFFF).toString(16).padStart(6, '0')
                val decimal = lastThree.takeLast(5).toInt(16)
                val pin = (decimal * 7) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:18:01", "00:1A:1B", "00:1F:90", "00:24:7B", "00:26:42",
                "00:26:B8", "00:7F:28", "10:1F:74"),
            description = "[EXPERIMENTAL] ActionTec multiplication algorithm",
            isExperimental = true
        ))

        // LevelOne
        algorithms.add(WpsAlgorithm(
            id = "pinLevelOneRotate",
            name = "LevelOne Rotate [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                var pin = nic
                pin = ((pin shl 3) or (pin shr 21)) and 0xFFFFFF
                pin = (pin xor 0x7A9BC3) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:11:6B", "00:16:DB", "00:19:5D", "00:1D:E5", "00:22:26", "00:90:CC"),
            description = "[EXPERIMENTAL] LevelOne bit rotation algorithm",
            isExperimental = true
        ))

        // Sitecom
        algorithms.add(WpsAlgorithm(
            id = "pinSitecomMul4",
            name = "Sitecom x4 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val pin = ((bytes[4] * 256 + bytes[5]) * 4) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:0C:F6", "00:18:4D", "00:1E:C7", "00:21:03", "64:D1:A3"),
            description = "[EXPERIMENTAL] Sitecom multiplication algorithm",
            isExperimental = true
        ))

        // Planex
        algorithms.add(WpsAlgorithm(
            id = "pinPlanexAdd",
            name = "Planex Add [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastBytes = mac and 0xFFFFFF
                val pin = ((lastBytes + 0x191E4F) * 3) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:08:A2", "00:1B:8B", "00:22:6B", "00:24:A0", "10:6F:3F"),
            description = "[EXPERIMENTAL] Planex addition algorithm",
            isExperimental = true
        ))

        // ADB (Pirelli)
        algorithms.add(WpsAlgorithm(
            id = "pinADBPirelli",
            name = "ADB/Pirelli [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.startsWith("S") && serial.length >= 13) {
                    val k1 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                    val k2 = intArrayOf(0, 1, 4, 9, 6, 5, 6, 9, 4, 1)
                    var p = 0
                    for (i in 1..12) {
                        p += k1[serial[i].toString().toIntOrNull() ?: 0]
                    }
                    val pin = k2[p % 10] * 1000000 +
                            (serial.substring(7, 13).toIntOrNull() ?: 0) % 1000000
                    addChecksum(pin.toLong())
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic xor 0x9C8A6F) % 10000000)
                }
            },
            prefixes = listOf("00:08:27", "00:13:C8", "00:17:C2", "00:19:CB", "00:1C:A2",
                "00:1D:8B", "00:22:33", "00:23:8E", "00:25:53", "00:90:D0",
                "38:22:9D", "50:7E:5D", "74:88:8B", "84:26:15", "DC:0B:1A"),
            description = "[EXPERIMENTAL] ADB/Pirelli complex algorithm",
            isExperimental = true
        ))

        // Hitron
        algorithms.add(WpsAlgorithm(
            id = "pinHitronXOR",
            name = "Hitron XOR [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val seed = (bytes[3] shl 16) + (bytes[4] shl 8) + bytes[5]
                val pin = ((seed xor 0x2D5F7) * 7) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:1D:D1", "00:1D:D2", "00:1D:D3", "00:1D:D4", "00:1D:D5",
                "00:1D:D6", "58:23:8C", "8C:0E:14", "CC:A4:62", "E4:35:C8"),
            description = "[EXPERIMENTAL] Hitron XOR algorithm",
            isExperimental = true
        ))

        // Observa Telecom
        algorithms.add(WpsAlgorithm(
            id = "pinObservaMul11",
            name = "Observa x11 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastBytes = mac and 0xFFFFFF
                val pin = ((lastBytes xor 0x8B9A1C) * 11) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:02:CF", "00:1A:30", "00:1E:38", "00:25:36", "00:26:5B"),
            description = "[EXPERIMENTAL] Observa Telecom algorithm",
            isExperimental = true
        ))

        // SMC Networks
        algorithms.add(WpsAlgorithm(
            id = "pinSMCSum",
            name = "SMC Sum [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val sum = bytes.sum()
                val pin = ((sum * 9973) + bytes[5]) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:00:0A", "00:10:B5", "00:13:F7", "00:1B:FC", "00:22:2D",
                "00:25:84", "00:26:17", "D4:53:83"),
            description = "[EXPERIMENTAL] SMC Networks summation algorithm",
            isExperimental = true
        ))

        // Ubee (Ambit)
        algorithms.add(WpsAlgorithm(
            id = "pinUbeeMul20",
            name = "Ubee x20 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = ((nic + 0x64874) * 20) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:15:CE", "00:1A:DB", "00:26:CE", "00:7C:BF", "40:5D:82",
                "44:32:C8", "64:ED:57", "80:F5:03", "84:C9:B2", "C4:39:3A"),
            description = "[EXPERIMENTAL] Ubee/Ambit algorithm",
            isExperimental = true
        ))

        // CenturyLink
        algorithms.add(WpsAlgorithm(
            id = "pinCenturyLinkSq",
            name = "CenturyLink Square [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.length >= 8) {
                    val lastEight = serial.takeLast(8)
                    val numericPart = lastEight.filter { it.isDigit() }
                    if (numericPart.length >= 4) {
                        val seed = numericPart.takeLast(4).toInt()
                        val pin = (seed * seed) % 10000000
                        addChecksum(pin.toLong())
                    } else {
                        val nic = mac and 0xFFFFFF
                        addChecksum(nic % 10000000)
                    }
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum(nic % 10000000)
                }
            },
            prefixes = listOf("00:08:5C", "00:0A:9B", "00:19:CB", "00:26:B6", "20:76:00",
                "40:4A:03", "48:F8:B3", "54:04:A6", "64:0F:28", "80:37:73",
                "90:EF:68", "BC:9B:68", "C0:05:C2", "C8:D7:19", "F8:F5:32"),
            description = "[EXPERIMENTAL] CenturyLink square algorithm",
            isExperimental = true
        ))

        // Celeno (VSG1432)
        algorithms.add(WpsAlgorithm(
            id = "pinCelenoTable",
            name = "Celeno Table [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val table = intArrayOf(0, 5, 1, 6, 2, 7, 3, 8, 4, 9)
                var sum = 0
                var tmp = nic
                for (i in 0..5) {
                    sum += table[(tmp % 10).toInt()]
                    tmp /= 10
                }
                val pin = (nic * 7 + sum) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:22:F7", "00:24:D2", "00:A0:26", "1C:C6:3C", "28:BE:03",
                "30:0D:43", "4C:17:EB", "4C:8B:30", "60:38:E0", "64:3E:8C"),
            description = "[EXPERIMENTAL] Celeno VSG1432 table algorithm",
            isExperimental = true
        ))

        // Arris/Motorola (Cable modems)
        algorithms.add(WpsAlgorithm(
            id = "pinArrisCable",
            name = "Arris Cable [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.length >= 5) {
                    val lastFive = serial.takeLast(5)
                    if (lastFive.all { it.isDigit() }) {
                        val seed = lastFive.toInt()
                        val pin = (seed * 8) % 10000000
                        addChecksum(pin.toLong())
                    } else {
                        val nic = mac and 0xFFFFFF
                        addChecksum((nic * 10) % 10000000)
                    }
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic * 10) % 10000000)
                }
            },
            prefixes = listOf("00:15:A2", "00:15:A3", "00:15:A4", "00:15:A5", "00:1A:DB",
                "00:1D:CE", "00:1D:CF", "00:1D:D0", "00:24:95", "00:26:36",
                "08:3E:5D", "08:96:AD", "0C:F8:93", "14:5B:D1", "1C:1D:67",
                "20:3D:66", "20:E5:64", "30:60:23", "34:6B:D3", "3C:75:4A",
                "40:0D:10", "40:B7:F3", "44:32:C8", "44:96:AB", "48:D3:43",
                "50:EA:D6", "58:23:8C", "5C:57:1A", "60:19:71", "64:CE:D7",
                "6C:A6:04", "6C:B0:CE", "6C:C1:D2", "70:B1:4E", "74:EA:E8",
                "78:71:9C", "78:96:84", "7C:B2:1B", "80:F5:03", "80:96:B1",
                "84:5B:12", "84:61:A0", "84:E3:20", "8C:09:F4", "90:3E:AB",
                "90:B1:34", "94:62:69", "94:87:7C", "94:CC:B9", "98:52:B1",
                "9C:3A:AF", "A4:7A:A4", "A4:ED:4E", "A8:11:FC", "AC:3A:7A",
                "AC:B3:13", "B0:77:AC", "B4:F2:E8", "B8:E6:25", "BC:64:4B",
                "C0:05:C2", "C0:C1:C0", "C4:27:95", "C8:6C:87", "C8:A7:0A",
                "C8:B3:73", "CC:7D:37", "D0:04:B1", "D4:04:92", "D8:18:9B",
                "DC:A6:32", "E0:22:03", "E0:88:5D", "E4:35:C8", "E4:48:C7",
                "E4:5D:51", "E4:83:99", "E8:3E:FC", "E8:89:2C", "E8:ED:05",
                "EC:CB:30", "F0:F2:49", "F4:35:C6", "F8:02:78", "F8:18:97",
                "F8:7B:7A", "FC:51:A4", "FC:91:14", "FC:94:E3"),
            description = "[EXPERIMENTAL] Arris/Motorola cable modem algorithm",
            isExperimental = true
        ))

        // Alice/AGPF (Telecom Italia)
        algorithms.add(WpsAlgorithm(
            id = "pinAliceAGPF",
            name = "Alice/AGPF [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.startsWith("69") && serial.length >= 12) {
                    val sn1 = serial.substring(0, 4).toIntOrNull() ?: 0
                    val sn2 = serial.substring(4, 10).toIntOrNull() ?: 0
                    val k1 = serial[10].toString().toIntOrNull() ?: 0
                    val k2 = serial[11].toString().toIntOrNull() ?: 0
                    val q = (k1 + k2) % 8
                    val pin = (sn1 * 1000 + sn2 + q) % 10000000
                    addChecksum(pin.toLong())
                } else {
                    val nic = mac and 0xFFFFFF
                    val pin = ((nic xor 0x8B216C) * 17) % 10000000
                    addChecksum(pin)
                }
            },
            prefixes = listOf("00:00:00", "00:08:27", "00:19:CB", "00:1C:A2", "00:1D:8B",
                "00:22:33", "00:23:8E", "00:25:53", "00:27:3B", "00:38:72",
                "00:90:D0", "38:22:9D", "64:87:D7", "74:88:8B", "8C:6A:BF"),
            description = "[EXPERIMENTAL] Alice/AGPF Telecom Italia algorithm",
            isExperimental = true
        ))

        // Speedport (Deutsche Telekom)
        algorithms.add(WpsAlgorithm(
            id = "pinSpeedportXOR",
            name = "Speedport XOR [EXP]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val seed = (bytes[3].toLong() shl 24) or (bytes[4].toLong() shl 16) or (bytes[5].toLong() shl 8) or bytes[2].toLong()
                var pin = (seed xor 0x98124557L) % 10000000L
                if (pin < 1000000L) {
                    pin += 1000000L
                }
                addChecksum(pin)
            },
            prefixes = listOf("00:04:0E", "00:08:54", "00:1F:3F", "00:26:4D", "1C:C6:3C",
                "28:92:4A", "34:31:C4", "38:22:9D", "50:CC:F8", "5C:4C:A9",
                "7C:2F:80", "9C:C7:A6", "AC:61:75", "E0:92:5C"),
            description = "[EXPERIMENTAL] Deutsche Telekom Speedport algorithm",
            isExperimental = true
        ))

        // FTE/MaxCom
        algorithms.add(WpsAlgorithm(
            id = "pinFTEMaxCom",
            name = "FTE/MaxCom [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val x = ((nic and 0xF) shl 12) or ((nic and 0xFF00) shr 4) or
                        ((nic and 0xF000) shr 12)
                val pin = (x xor 0x1B8A93) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:05:C9", "00:0F:E2", "00:19:15", "00:24:FE", "04:C0:6F",
                "20:08:ED", "28:5F:DB", "34:6B:D3", "4C:54:99", "78:C4:0E"),
            description = "[EXPERIMENTAL] FTE/MaxCom bit manipulation algorithm",
            isExperimental = true
        ))

        // Sagemcom
        algorithms.add(WpsAlgorithm(
            id = "pinSagemcomRotate",
            name = "Sagemcom Rotate [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val x = nic xor 0x354A6B
                val pin = ((x shl 3) or (x shr 21)) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:19:15", "00:19:70", "00:1E:2A", "00:1E:74", "00:1F:9F",
                "00:23:48", "00:24:B2", "00:25:B3", "40:4A:03", "5C:33:8E",
                "60:E7:01", "68:15:90", "68:A3:78", "7C:03:AB", "84:97:6C",
                "84:A8:E4", "88:25:2C", "A4:B1:E9", "BC:98:89", "CC:33:BB",
                "D0:84:B0", "E8:BE:81", "F8:97:7F"),
            description = "[EXPERIMENTAL] Sagemcom bit rotation algorithm",
            isExperimental = true
        ))

        // Intelbras
        algorithms.add(WpsAlgorithm(
            id = "pinIntelbrasMul7",
            name = "Intelbras x7 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = ((nic + 0x6B39E5) * 7) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:21:97", "00:E0:4C", "1C:61:B4", "4C:5F:C2", "58:10:8C",
                "80:1F:02", "94:4A:0C", "AC:C6:62"),
            description = "[EXPERIMENTAL] Intelbras multiplication algorithm",
            isExperimental = true
        ))

        // Zhao алгоритм (для китайских роутеров)
        algorithms.add(WpsAlgorithm(
            id = "pinZhaoReverse",
            name = "Zhao Reverse [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                var pin = nic
                var temp = 0L
                while (pin > 0) {
                    temp = (temp * 10) + (pin % 10)
                    pin /= 10
                }
                pin = temp % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("04:8D:38", "30:B5:C2", "C8:3A:35", "CC:96:A0", "F8:3D:FF"),
            description = "[EXPERIMENTAL] Zhao reverse digits algorithm",
            isExperimental = true
        ))

        // Airocon Realtek (отличается от основного algoAirocon)
        algorithms.add(WpsAlgorithm(
            id = "pinAiroconSquare",
            name = "Airocon Square [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastTwoBytes = mac and 0xFFFF
                val pin = (lastTwoBytes * lastTwoBytes) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:0B:2B", "00:18:E7", "00:1A:EF", "00:1D:43"),
            description = "[EXPERIMENTAL] Airocon Realtek square algorithm",
            isExperimental = true
        ))

        // HG658c (Huawei HG658c specific)
        algorithms.add(WpsAlgorithm(
            id = "pinHG658cSpecific",
            name = "HG658c Specific [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val lastByte = bytes[5]
                val secondLastByte = bytes[4]
                val pin = (lastByte * 256 * 256) + (secondLastByte * 256 * 10) +
                        (lastByte * 10) + (secondLastByte % 10)
                addChecksum((pin % 10000000).toLong())
            },
            prefixes = listOf("10:47:80", "20:2B:C1", "28:5F:DB", "54:A5:1B", "54:B8:0A", "E8:CD:2D"),
            description = "[EXPERIMENTAL] Huawei HG658c specific algorithm",
            isExperimental = true
        ))

        // Comtrend
        algorithms.add(WpsAlgorithm(
            id = "pinComtrendMul4",
            name = "Comtrend x4 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastThreeBytes = mac and 0xFFFFFF
                val pin = ((lastThreeBytes xor 0xC83A35) * 4) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:0D:F0", "00:14:5C", "00:19:77", "00:1B:57", "00:26:8D",
                "00:30:DA", "64:3E:8C", "A0:21:B7", "C0:3E:0F", "FC:8B:97"),
            description = "[EXPERIMENTAL] Comtrend multiplication algorithm",
            isExperimental = true
        ))

        // Genexis
        algorithms.add(WpsAlgorithm(
            id = "pinGenexisMul5",
            name = "Genexis x5 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = ((nic xor 0x4E5D6F) * 5) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:1E:22", "00:1E:56", "00:23:28", "00:25:F1", "AC:E8:7B", "EC:43:EB"),
            description = "[EXPERIMENTAL] Genexis multiplication algorithm",
            isExperimental = true
        ))

        // Blink
        algorithms.add(WpsAlgorithm(
            id = "pinBlinkMul3",
            name = "Blink x3 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastFourBytes = mac and 0xFFFFFFFF
                val pin = (lastFourBytes * 3) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:14:1B", "00:1B:52", "00:22:F7", "84:C9:B2"),
            description = "[EXPERIMENTAL] Blink multiplication algorithm",
            isExperimental = true
        ))

        // FTE-xxxx series
        algorithms.add(WpsAlgorithm(
            id = "pinFTESeriesSN",
            name = "FTE Series SN [EXPERIMENTAL]",
            mode = ALGO_MACSN,
            func = { mac, ser ->
                val serial = ser ?: ""
                if (serial.startsWith("FTE") && serial.length >= 8) {
                    val numPart = serial.substring(3, 8).toIntOrNull() ?: 0
                    val pin = (numPart * 7) % 10000000
                    addChecksum(pin.toLong())
                } else {
                    val nic = mac and 0xFFFFFF
                    addChecksum((nic xor 0x345678) % 10000000)
                }
            },
            prefixes = listOf("00:26:5B", "04:C0:6F", "20:08:ED", "78:C4:0E"),
            description = "[EXPERIMENTAL] FTE series serial-based algorithm",
            isExperimental = true
        ))

        // Cameo
        algorithms.add(WpsAlgorithm(
            id = "pinCameoMul3",
            name = "Cameo x3 [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val nic = mac and 0xFFFFFF
                val pin = ((nic + 0x6B1E77) * 3) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:18:E7", "00:1E:8C", "40:F2:01", "D8:FE:E3"),
            description = "[EXPERIMENTAL] Cameo multiplication algorithm",
            isExperimental = true
        ))

        // Tenda/Netcore
        algorithms.add(WpsAlgorithm(
            id = "pinTendaNewSum",
            name = "Tenda New Sum [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastThree = mac and 0xFFFFFF
                val sum = ((lastThree shr 16) and 0xFF) +
                        ((lastThree shr 8) and 0xFF) +
                        (lastThree and 0xFF)
                val pin = (lastThree + sum) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("04:8D:38", "08:10:79", "C8:3A:35", "CC:96:A0", "D8:32:14",
                "E4:D3:32", "F4:83:CD"),
            description = "[EXPERIMENTAL] Tenda/Netcore byte summation algorithm",
            isExperimental = true
        ))

        // Cisco/Linksys E-series
        algorithms.add(WpsAlgorithm(
            id = "pinCiscoESeriesMul",
            name = "Cisco E-Series [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastThree = mac and 0xFFFFFF
                val multiplier = 10L
                var pin = lastThree
                for (i in 0..4) {
                    pin = (pin * multiplier) % 10000000
                }
                addChecksum(pin)
            },
            prefixes = listOf("00:25:9C", "68:7F:74", "C0:C1:C0", "C8:D7:19", "E0:5F:B9", "E8:40:F2"),
            description = "[EXPERIMENTAL] Cisco/Linksys E-series multiple multiplication",
            isExperimental = true
        ))

        // CBN CH6640E/CG6640E
        algorithms.add(WpsAlgorithm(
            id = "pinCBNSum",
            name = "CBN Sum [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val bytes = macToBytes(mac)
                val sum = bytes[0] + bytes[1] + bytes[2] + bytes[3] + bytes[4] + bytes[5]
                val pin = ((sum * 2767) + bytes[5] * 13) % 10000000
                addChecksum(pin.toLong())
            },
            prefixes = listOf("00:22:6B", "00:24:D1", "00:26:42", "00:26:CE", "40:0D:10",
                "64:12:25", "78:B2:13", "94:A1:A2", "C4:04:15", "D4:AB:82",
                "E0:91:53", "F8:35:DD"),
            description = "[EXPERIMENTAL] CBN CH6640E/CG6640E summation algorithm",
            isExperimental = true
        ))

        // Atheros chipset generic
        algorithms.add(WpsAlgorithm(
            id = "pinAtherosGeneric",
            name = "Atheros Generic [EXPERIMENTAL]",
            mode = ALGO_MAC,
            func = { mac, _ ->
                val lastTwoBytes = mac and 0xFFFF
                val pin = ((lastTwoBytes xor 0x1C5A) * 2476) % 10000000
                addChecksum(pin)
            },
            prefixes = listOf("00:03:7F", "00:0B:6B", "00:18:84", "00:1D:6A", "00:27:0D"),
            description = "[EXPERIMENTAL] Atheros chipset generic algorithm",
            isExperimental = true
        ))

        addExperimentalStaticPins()
    }

    private fun addExperimentalStaticPins() {
        algorithms.add(WpsAlgorithm("pinLinksysStatic", "Linksys Static [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 12345678 },
            prefixes = listOf("00:0F:66", "00:14:BF", "00:18:F8", "00:1A:70"),
            description = "[EXPERIMENTAL] Pin = 12345678", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinTrendnetStatic", "Trendnet Static [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 12345670 },
            prefixes = listOf("00:14:D1", "D8:EB:97"),
            description = "[EXPERIMENTAL] Pin = 12345670", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinTendaStatic", "Tenda Static [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 88888888 },
            prefixes = listOf("C8:3A:35", "00:1C:1B"),
            description = "[EXPERIMENTAL] Pin = 88888888", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinActiontecStatic", "Actiontec Static [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 25802711 },
            prefixes = listOf("00:1D:1A", "00:18:39"),
            description = "[EXPERIMENTAL] Pin = 25802711", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinLinksysWRT54G", "Linksys WRT54G [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 8621690 },
            prefixes = listOf("00:12:17", "00:13:10"),
            description = "[EXPERIMENTAL] Pin = 8621690", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinNetgearWGR614", "Netgear WGR614 [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 4030384 },
            prefixes = listOf("00:14:6C", "00:18:4D"),
            description = "[EXPERIMENTAL] Pin = 4030384", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinHuaweiHG520", "Huawei HG520 [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 2882813 },
            prefixes = listOf("00:1E:10", "00:25:68"),
            description = "[EXPERIMENTAL] Pin = 2882813", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinZTEZXHN", "ZTE ZXHN [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 1234567 },
            prefixes = listOf("68:1A:B2", "78:31:2B"),
            description = "[EXPERIMENTAL] Pin = 1234567", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinSitecomWLR2000", "Sitecom WLR-2000 [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 1679893 },
            prefixes = listOf("00:0C:F6", "64:D1:A3"),
            description = "[EXPERIMENTAL] Pin = 1679893", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinONOCBN", "ONO CBN [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 9575521 },
            prefixes = listOf("5C:35:3B", "E4:C1:46"),
            description = "[EXPERIMENTAL] Pin = 9575521", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinSagemcomFAST", "Sagemcom F@ST [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 8817524 },
            prefixes = listOf("68:15:90", "68:A3:78"),
            description = "[EXPERIMENTAL] Pin = 8817524", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinComtrendAR", "Comtrend AR/VR [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 1234567 },
            prefixes = listOf("00:30:DA", "64:3E:8C"),
            description = "[EXPERIMENTAL] Pin = 1234567", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinHuaweiHG532e", "Huawei HG532e [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 3425928 },
            prefixes = listOf("00:25:9E", "10:47:80"),
            description = "[EXPERIMENTAL] Pin = 3425928", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinZTEH108N", "ZTE H108N [EXPERIMENTAL]", ALGO_STATIC, { _, _ -> 1234567 },
            prefixes = listOf("F8:8E:85", "FC:C8:97"),
            description = "[EXPERIMENTAL] Pin = 1234567", isExperimental = true))

        // Тестовые PIN
        algorithms.add(WpsAlgorithm("pinTest1", "Test PIN 1 [FAKE]", ALGO_STATIC, { _, _ -> 12121212 },
            prefixes = listOf(),
            description = "[Fake] Test Pin = 12121212", isExperimental = true))

        algorithms.add(WpsAlgorithm("pinTest2", "Test PIN 2 [FAKE]", ALGO_STATIC, { _, _ -> 98989898 },
            prefixes = listOf(),
            description = "[Fake] Test Pin = 98989898", isExperimental = true))
    }

    private fun zeroFill(number: Any, width: Int): String {
        val numberStr = number.toString()
        val needed = width - numberStr.length
        return if (needed > 0) {
            "0".repeat(needed) + numberStr
        } else {
            numberStr
        }
    }

    private fun reverse(s: String): String = s.reversed()

    private fun pinChecksum(pin: Long): Long {
        var p = pin % 10000000L
        var a = 0L
        var t = p
        while (t > 0) {
            a += 3 * (t % 10)
            t /= 10
            a += t % 10
            t /= 10
        }
        return (p * 10) + ((10 - (a % 10)) % 10)
    }

    private fun genPin(mac: Long, sn: String?, algorithmIndex: Int): Any {
        val algo = algorithms[algorithmIndex]
        return if (algo.mode == ALGO_MACSN) {
            algo.func(mac, sn)
        } else {
            algo.func(mac, null)
        }
    }

    private fun algoDLink(mac: Long): Long {
        var m = mac and 0xFFFFFFL
        m = m xor 0x55AA55L
        m = m xor (((m and 0xF) shl 4) or
                ((m and 0xF) shl 8) or
                ((m and 0xF) shl 12) or
                ((m and 0xF) shl 16) or
                ((m and 0xF) shl 20))
        m %= 10000000L
        if (m < 1000000L) {
            m += ((m % 9L) * 1000000L) + 1000000L
        }
        return m
    }

    private fun algoDslMacSn(mac: Long, ser: String?, init: DslInit): Long {
        var serial = ser ?: ""
        if (serial.length < 4) {
            serial = zeroFill(serial, 4)
        }
        if (serial.length > 4) {
            serial = serial.takeLast(4)
        }

        val sn = mutableListOf<Int>()
        for (i in serial.indices) {
            val x = serial[i].digitToIntOrNull(16) ?: 0
            sn.add(x)
        }

        val nic = listOf(
            ((mac and 0xFFFFL) shr 12).toInt(),
            ((mac and 0xFFFL) shr 8).toInt(),
            ((mac and 0xFFL) shr 4).toInt(),
            (mac and 0xFL).toInt()
        )

        var k1 = init.k1 and 0xF
        var bk1 = init.bk1
        var i = 0
        while (bk1 > 0) {
            if ((bk1 and 1) != 0) {
                k1 += if (i < 4) nic[i] else sn[i - 4]
                k1 = k1 and 0xF
            }
            bk1 = bk1 shr 1
            i++
        }

        var k2 = init.k2 and 0xF
        var bk2 = init.bk2
        i = 0
        while (bk2 > 0) {
            if ((bk2 and 1) != 0) {
                k2 += if (i < 4) nic[i] else sn[i - 4]
                k2 = k2 and 0xF
            }
            bk2 = bk2 shr 1
            i++
        }

        var pin = init.pin.toLong()
        for (j in init.bx.indices) {
            var xor = init.xor and 0xF
            var bxj = init.bx[j]
            i = 0
            while (bxj > 0) {
                if ((bxj and 1) != 0) {
                    xor = xor xor when {
                        i > 4 -> sn[i - 4]
                        i > 1 -> nic[i - 1]
                        i > 0 -> k2
                        else -> k1
                    }
                }
                bxj = bxj shr 1
                i++
            }
            pin = pin shl 4
            pin = pin or xor.toLong()
        }

        return when (init.sub) {
            0 -> pin % 10000000L
            1 -> (pin % 10000000L) - (floor(pin.toDouble() / 10000000.0).toLong() *
                    when {
                        init.sk > 1 -> k2.toLong()
                        init.sk > 0 -> k1.toLong()
                        else -> init.skv.toLong()
                    })
            2 -> (pin % 10000000L) + (floor(pin.toDouble() / 10000000.0).toLong() *
                    when {
                        init.sk > 1 -> k2.toLong()
                        init.sk > 0 -> k1.toLong()
                        else -> init.skv.toLong()
                    })
            else -> pin % 10000000L
        }
    }

    private fun algoAsus(mac: Long): Long {
        val macStr = zeroFill(mac.toString(16), 12)
        val b = mutableListOf<Int>()
        for (i in 0..5) {
            b.add(macStr.substring(i * 2, (i + 1) * 2).toInt(16))
        }

        val pin = mutableListOf<Int>()
        for (i in 0..6) {
            pin.add((b[i % 6] + b[5]) % (10 - ((i + b[1] + b[2] + b[3] + b[4] + b[5]) % 7)))
        }

        return pin.joinToString("").toLong()
    }

    private fun algoAirocon(mac: Long): Long {
        val macStr = zeroFill(mac.toString(16), 12)
        val b = mutableListOf<Int>()
        for (i in 0..5) {
            b.add(macStr.substring(i * 2, (i + 1) * 2).toInt(16))
        }

        return ((b[0] + b[1]) % 10).toLong() +
                (((b[5] + b[0]) % 10) * 10).toLong() +
                (((b[4] + b[5]) % 10) * 100).toLong() +
                (((b[3] + b[4]) % 10) * 1000).toLong() +
                (((b[2] + b[3]) % 10) * 10000).toLong() +
                (((b[1] + b[2]) % 10) * 100000).toLong() +
                (((b[0] + b[1]) % 10) * 1000000).toLong()
    }

    private fun parseBssid(bssid: String): Long? {
        val mac = bssid.split("|")[0].replace(Regex("[:.-]"), "").replace(" ", "")
        return try {
            val macLong = mac.toLong(16)
            if (macLong > 0xFFFFFFFFFFFFL) null else macLong
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun parseSerialNumber(input: String): String? {
        val parts = input.split("|")
        return if (parts.size >= 3) parts[2] else null
    }

    private fun matchesPrefix(bssid: String, prefixes: List<String>): Boolean {
        val upperBssid = bssid.uppercase().replace(":", "").replace("-", "").replace(".", "")
        return prefixes.any { prefix ->
            val upperPrefix = prefix.uppercase().replace(":", "").replace("-", "").replace(".", "")
            upperBssid.startsWith(upperPrefix)
        }
    }

    fun generateAllPins(bssid: String, serialNumber: String? = null, includeExperimental: Boolean = false): List<PinResult> {
        val mac = parseBssid(bssid) ?: return emptyList()
        val serial = serialNumber ?: parseSerialNumber(bssid)
        val results = mutableListOf<PinResult>()

        algorithms.forEach { algo ->
            if (!includeExperimental && algo.isExperimental) return@forEach

            try {
                val pin = genPin(mac, serial, algorithms.indexOf(algo))
                val pinStr = when {
                    pin is String -> if (pin.isEmpty()) "<empty>" else pin
                    else -> {
                        val pinLong = pin.toString().toLong()
                        val checksummed = pinChecksum(pinLong)
                        zeroFill(checksummed, 8)
                    }
                }

                val modeStr = when (algo.mode) {
                    ALGO_STATIC -> "Static PIN"
                    ALGO_EXPERIMENTAL -> "Experimental"
                    ALGO_EMPTY -> ""
                    else -> ""
                }

                results.add(PinResult(
                    pin = pinStr,
                    algorithm = algo.name,
                    mode = modeStr,
                    isFromDatabase = false,
                    isExperimental = algo.isExperimental
                ))
            } catch (e: Exception) {
            }
        }

        return results
    }

    fun generateSuggestedPins(bssid: String, serialNumber: String? = null, includeExperimental: Boolean = false): List<PinResult> {
        val mac = parseBssid(bssid) ?: return emptyList()
        val serial = serialNumber ?: parseSerialNumber(bssid)
        val results = mutableListOf<PinResult>()

        algorithms.forEach { algo ->
            if (!includeExperimental && algo.isExperimental) return@forEach

            if (algo.prefixes.isNotEmpty() && matchesPrefix(bssid.split("|")[0], algo.prefixes)) {
                try {
                    val pin = genPin(mac, serial, algorithms.indexOf(algo))
                    val pinStr = when {
                        pin is String -> if (pin.isEmpty()) "<empty>" else pin
                        else -> {
                            val pinLong = pin.toString().toLong()
                            val checksummed = pinChecksum(pinLong)
                            zeroFill(checksummed, 8)
                        }
                    }

                    val modeStr = when (algo.mode) {
                        ALGO_STATIC -> "Static PIN"
                        ALGO_EXPERIMENTAL -> "Experimental"
                        ALGO_EMPTY -> ""
                        else -> ""
                    }

                    results.add(PinResult(
                        pin = pinStr,
                        algorithm = algo.name,
                        mode = modeStr,
                        isFromDatabase = true,
                        isSuggested = true,
                        isExperimental = algo.isExperimental
                    ))
                } catch (e: Exception) {
                }
            }
        }

        return results
    }

    fun formatBssid(bssid: String): String? {
        val mac = parseBssid(bssid) ?: return null
        val macStr = zeroFill(mac.toString(16).uppercase(), 12)
        return macStr.chunked(2).joinToString(":")
    }

    data class ParsedInput(
        val bssid: String,
        val pin: String? = null,
        val serialNumber: String? = null
    )

    fun parseInput(input: String): ParsedInput? {
        val parts = input.split("|")
        if (parts.isEmpty()) return null

        val bssid = parts[0].trim()
        if (parseBssid(bssid) == null) return null

        return ParsedInput(
            bssid = bssid,
            pin = if (parts.size >= 2 && parts[1].isNotBlank()) parts[1].trim() else null,
            serialNumber = if (parts.size >= 3 && parts[2].isNotBlank()) parts[2].trim() else null
        )
    }

    fun isValidPinChecksum(pin: String): Boolean {
        return try {
            val pinLong = pin.toLong()
            if (pinLong < 10000000L || pinLong > 99999999L) return false

            val calculatedPin = pinChecksum(pinLong / 10L)
            calculatedPin == pinLong
        } catch (e: NumberFormatException) {
            false
        }
    }

    fun analyzePins(inputs: List<String>): Map<String, Any> {
        val results = mutableMapOf<String, Any>()
        val validInputs = mutableListOf<ParsedInput>()

        inputs.forEach { input ->
            parseInput(input)?.let { parsed ->
                validInputs.add(parsed)
            }
        }

        if (validInputs.isEmpty()) {
            results["error"] = "No valid input data found"
            return results
        }

        val analysisResults = mutableListOf<Map<String, Any>>()
        validInputs.forEach { parsed ->
            val mac = parseBssid(parsed.bssid)!!
            val pinAnalysis = mutableMapOf<String, Any>()

            pinAnalysis["bssid"] = formatBssid(parsed.bssid) ?: parsed.bssid
            pinAnalysis["pin"] = parsed.pin ?: "<unknown>"
            pinAnalysis["serial"] = parsed.serialNumber ?: "<none>"

            val matchingAlgos = mutableListOf<String>()
            algorithms.forEach { algo ->
                try {
                    val generatedPin = genPin(mac, parsed.serialNumber, algorithms.indexOf(algo))
                    val finalPin = when {
                        generatedPin is String -> generatedPin
                        else -> {
                            val pinLong = generatedPin.toString().toLong()
                            val checksummed = pinChecksum(pinLong)
                            zeroFill(checksummed, 8)
                        }
                    }

                    if (parsed.pin == finalPin || (parsed.pin == "<empty>" && finalPin.isEmpty())) {
                        matchingAlgos.add(algo.name)
                    }
                } catch (e: Exception) {
                }
            }

            pinAnalysis["matching_algorithms"] = matchingAlgos
            pinAnalysis["checksum_valid"] = parsed.pin?.let { isValidPinChecksum(it) } ?: false

            analysisResults.add(pinAnalysis)
        }

        results["analysis"] = analysisResults
        results["total_analyzed"] = validInputs.size

        return results
    }
}