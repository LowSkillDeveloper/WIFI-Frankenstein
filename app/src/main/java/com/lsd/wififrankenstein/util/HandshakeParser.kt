package com.lsd.wififrankenstein.util

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.spec.KeySpec
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class HandshakeType { PMKID, EAPOL, PMKID_EAPOL }

data class HandshakeHash(
    val type: HandshakeType,
    val pmkidOrMic: String,
    val macAp: String,
    val macSta: String,
    val essid: String,
    val essidHex: String,
    val anonce: String? = null,
    val snonce: String? = null,
    val eapol: String? = null,
    val messagePair: Int? = null,
    val keyver: Int? = null,
    val originalLine: String = ""
) {
    fun to22000Line(): String {
        val essidHex = if (essidHex.isNotBlank()) essidHex else essid.toByteArray()
            .joinToString("") { "%02x".format(it) }
        return when (type) {
            HandshakeType.PMKID -> "WPA*01*$pmkidOrMic*${
                macAp.replace(":", "").lowercase()
            }*${macSta.replace(":", "").lowercase()}*$essidHex***01"

            HandshakeType.EAPOL -> "WPA*02*$pmkidOrMic*${
                macAp.replace(":", "").lowercase()
            }*${
                macSta.replace(":", "").lowercase()
            }*$essidHex*${anonce ?: ""}*${eapol ?: ""}*${messagePair?.let { "%02x".format(it) } ?: "00"}"

            HandshakeType.PMKID_EAPOL -> "WPA*03*$pmkidOrMic*${
                macAp.replace(":", "").lowercase()
            }*${
                macSta.replace(":", "").lowercase()
            }*$essidHex*${anonce ?: ""}*${eapol ?: ""}*${messagePair?.let { "%02x".format(it) } ?: "00"}"
        }
    }

    fun toPmkidLine(): String {
        val essidHex = if (essidHex.isNotBlank()) essidHex else essid.toByteArray()
            .joinToString("") { "%02x".format(it) }
        return "$pmkidOrMic*${macAp.replace(":", "").lowercase()}*${
            macSta.replace(":", "").lowercase()
        }*$essidHex"
    }

    fun to16800Line(): String {
        val essidHex = if (essidHex.isNotBlank()) essidHex else essid.toByteArray()
            .joinToString("") { "%02x".format(it) }
        return "$pmkidOrMic:${macAp.replace(":", "").lowercase()}:${
            macSta.replace(":", "").lowercase()
        }:$essidHex"
    }

    fun toHccapxBytes(): ByteArray {
        val essidBytes = essid.toByteArray().let { if (it.size > 32) it.copyOf(32) else it }
        val essidHex = essidBytes.joinToString("") { "%02x".format(it) }.padEnd(64, '0')
        val macApHex = macAp.replace(":", "").lowercase().padEnd(12, '0')
        val macStaHex = macSta.replace(":", "").lowercase().padEnd(12, '0')
        val nonceApHex = (anonce ?: "").padEnd(64, '0')
        val keymicHex = pmkidOrMic.padEnd(32, '0')
        val eapolData = eapol ?: ""

        val snonceHex = snonce?.padEnd(64, '0')
            ?: extractNonceFromEapol(eapolData)?.let {
                if (it != nonceApHex) it else "0".repeat(64)
            } ?: "0".repeat(64)

        val buffer = ByteArray(393)
        buffer[0] = 'H'.code.toByte(); buffer[1] = 'C'.code.toByte()
        buffer[2] = 'P'.code.toByte(); buffer[3] = 'X'.code.toByte()
        buffer[4] = 4; buffer[5] = 0; buffer[6] = 0; buffer[7] = 0
        buffer[8] = (messagePair ?: 0).toByte()
        buffer[9] = essidBytes.size.toByte()
        hexToBytes(essidHex).copyInto(buffer, 10)
        buffer[42] = (keyver ?: 2).toByte()
        hexToBytes(keymicHex).copyInto(buffer, 43)
        hexToBytes(macApHex).copyInto(buffer, 59)
        hexToBytes(nonceApHex).copyInto(buffer, 65)
        hexToBytes(macStaHex).copyInto(buffer, 97)
        hexToBytes(snonceHex).copyInto(buffer, 103)
        val eapolLen = (eapolData.length / 2).coerceAtMost(255)
        buffer[135] = (eapolLen and 0xFF).toByte()
        buffer[136] = ((eapolLen shr 8) and 0xFF).toByte()
        hexToBytes(eapolData.take(512)).copyInto(buffer, 137)
        return buffer
    }

    fun toHccapBytes(): ByteArray {
        val essidBytes = essid.toByteArray().let { if (it.size > 36) it.copyOf(36) else it }
        val macApBytes = hexToBytes(macAp.replace(":", "").lowercase().padEnd(12, '0'))
        val macStaBytes = hexToBytes(macSta.replace(":", "").lowercase().padEnd(12, '0'))
        val nonceApHex = (anonce ?: "").padEnd(64, '0')
        val keymicBytes = hexToBytes(pmkidOrMic.padEnd(32, '0'))
        val eapolData = eapol ?: ""

        val snonceHex = snonce?.padEnd(64, '0')
            ?: extractNonceFromEapol(eapolData)?.let {
                if (it != nonceApHex) it else "0".repeat(64)
            } ?: "0".repeat(64)

        val buffer = ByteArray(392)
        essidBytes.copyInto(buffer, 0)
        macApBytes.copyInto(buffer, 36)
        macStaBytes.copyInto(buffer, 42)
        hexToBytes(snonceHex).copyInto(buffer, 48)
        hexToBytes(nonceApHex).copyInto(buffer, 80)
        val eapolBytes = hexToBytes(eapolData)
        eapolBytes.copyInto(buffer, 112)
        val eapolSize = (eapolData.length / 2).coerceAtMost(256)
        buffer[368] = (eapolSize and 0xFF).toByte()
        buffer[369] = ((eapolSize shr 8) and 0xFF).toByte()
        buffer[370] = ((eapolSize shr 16) and 0xFF).toByte()
        buffer[371] = ((eapolSize shr 24) and 0xFF).toByte()
        val keyverVal = (keyver ?: 2)
        buffer[372] = (keyverVal and 0xFF).toByte()
        buffer[373] = ((keyverVal shr 8) and 0xFF).toByte()
        buffer[374] = ((keyverVal shr 16) and 0xFF).toByte()
        buffer[375] = ((keyverVal shr 24) and 0xFF).toByte()
        keymicBytes.copyInto(buffer, 376)
        return buffer
    }

    val isUselessPmkid: Boolean
        get() = type == HandshakeType.PMKID && pmkidOrMic.length == 32 && pmkidOrMic.all { it == '0' }

    val isFaultyPmkid: Boolean
        get() {
            if (type != HandshakeType.PMKID || pmkidOrMic.length < 8) return false
            val first4 = pmkidOrMic.take(8)
            return first4 == "00006e00"
        }

    fun verifyPassword(password: String): Boolean {
        if (password.isBlank()) return false
        if (type != HandshakeType.PMKID) return false
        if (pmkidOrMic.length != 32) return false
        return try {
            val essidBytes = essid.toByteArray(Charsets.UTF_8)
            val spec: KeySpec = PBEKeySpec(password.toCharArray(), essidBytes, 4096, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val pmk = factory.generateSecret(spec).encoded

            val macApBytes = hexToBytes(macAp.replace(":", ""))
            val macStaBytes = hexToBytes(macSta.replace(":", ""))
            val data = "PMK Name".toByteArray() + macApBytes + macStaBytes

            val hmac = Mac.getInstance("HmacSHA1")
            hmac.init(SecretKeySpec(pmk, "HmacSHA1"))
            val result = hmac.doFinal(data)
            val expectedHex = bytesToHex(result.copyOf(16))
            expectedHex.equals(pmkidOrMic, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "verifyPassword failed for ${essid}: ${e.message}")
            false
        }
    }

    fun dedupKey(): String {
        val raw = "${type.ordinal}:$pmkidOrMic:${macAp.replace(":", "").lowercase()}:${
            macSta.replace(
                ":",
                ""
            ).lowercase()
        }:$essidHex:${anonce ?: ""}:${snonce ?: ""}:${eapol ?: ""}"
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "HandshakeHash"

        fun parse22000Line(line: String): HandshakeHash? {
            val trimmed = line.trim()
            if (!trimmed.startsWith("WPA*")) return null

            val sep = if (trimmed.contains("\t")) '\t' else '*'
            val parts = trimmed.split(sep)

            val typeCode = when {
                trimmed.startsWith("WPA*01") -> 1
                trimmed.startsWith("WPA*02") -> 2
                trimmed.startsWith("WPA*03") -> 3
                else -> {
                    Log.w(TAG, "parse22000Line: unknown type prefix in: ${trimmed.take(40)}")
                    return null
                }
            }

            val type = when (typeCode) {
                1, 3 -> HandshakeType.PMKID
                2 -> HandshakeType.EAPOL
                else -> return null
            }

            val fields = when (sep) {
                '*' -> parts.drop(2)
                '\t' -> {
                    val starSplit = parts[0].split("*")
                    starSplit.drop(2) + parts.drop(1)
                }

                else -> return null
            }

            if (fields.size < 4) {
                Log.w(TAG, "parse22000Line: too few fields (${fields.size}): ${trimmed.take(60)}")
                return null
            }

            val pmkidOrMic = fields.getOrElse(0) { "" }
            val macApRaw = fields.getOrElse(1) { "" }
            val macStaRaw = fields.getOrElse(2) { "" }
            val essidHex = fields.getOrElse(3) { "" }
            val anonce = fields.getOrElse(4) { "" }
            val eapol = fields.getOrElse(5) { "" }
            val mp = fields.getOrElse(6) { "00" }

            val macAp = formatMac(macApRaw)
            val macSta = formatMac(macStaRaw)
            val essid = try {
                hexToAscii(essidHex)
            } catch (_: Exception) {
                essidHex
            }

            val messagePair = mp.toIntOrNull(16) ?: 0

            Log.d(
                TAG,
                "parse22000Line OK: $type ${macAp} -> ${macSta} essid=$essid mp=$messagePair"
            )
            return HandshakeHash(
                type = type,
                pmkidOrMic = pmkidOrMic,
                macAp = macAp,
                macSta = macSta,
                essid = essid,
                essidHex = essidHex,
                anonce = anonce.ifBlank { null },
                eapol = eapol.ifBlank { null },
                messagePair = messagePair,
                originalLine = trimmed
            )
        }

        fun parseHccapx(data: ByteArray): HandshakeHash? {
            if (data.size < 393) {
                Log.w(TAG, "parseHccapx: too short (${data.size} < 393)")
                return null
            }
            val header = data.copyOf(4).decodeToString()
            if (!header.startsWith("HCPX")) {
                Log.w(TAG, "parseHccapx: bad header '$header'")
                return null
            }

            val messagePair = data[8].toInt() and 0xFF
            val essidLen = data[9].toInt() and 0xFF
            val essidHex = bytesToHex(data.copyOfRange(10, 10 + 32))
            val keyverVal = data[42].toInt() and 0xFF
            val keymic = bytesToHex(data.copyOfRange(43, 43 + 16))
            val macApHex = bytesToHex(data.copyOfRange(59, 59 + 6))
            val nonceApHex = bytesToHex(data.copyOfRange(65, 65 + 32))
            val macStaHex = bytesToHex(data.copyOfRange(97, 97 + 6))
            val nonceStaHex = bytesToHex(data.copyOfRange(103, 103 + 32))
            val eapolLen = (data[136].toInt() and 0xFF) shl 8 or (data[135].toInt() and 0xFF)
            val eapolEnd = 137 + eapolLen.coerceAtMost(256)
            val eapol = bytesToHex(data.copyOfRange(137, eapolEnd.coerceAtMost(data.size)))

            val macAp = formatMac(macApHex)
            val macSta = formatMac(macStaHex)
            val essidBytes = data.copyOfRange(10, 10 + essidLen.coerceAtMost(32))
            val essid = try {
                essidBytes.decodeToString()
            } catch (_: Exception) {
                hexToAscii(essidHex)
            }

            return HandshakeHash(
                type = HandshakeType.EAPOL,
                pmkidOrMic = keymic,
                macAp = macAp,
                macSta = macSta,
                essid = essid,
                essidHex = essidHex,
                anonce = nonceApHex,
                snonce = nonceStaHex,
                eapol = eapol,
                messagePair = messagePair,
                keyver = keyverVal
            )
        }

        fun parseHccap(data: ByteArray): HandshakeHash? {
            if (data.size < 392) {
                Log.w(TAG, "parseHccap: too short (${data.size} < 392)")
                return null
            }

            val essidEnd = (0 until 36).firstOrNull { data[it].toInt() == 0 } ?: 36
            val essidBytes = data.copyOfRange(0, essidEnd)
            val essid = try {
                essidBytes.decodeToString()
            } catch (_: Exception) {
                ""
            }
            if (essid.isBlank()) {
                Log.w(TAG, "parseHccap: blank essid")
                return null
            }

            val macApHex = bytesToHex(data.copyOfRange(36, 42))
            val macStaHex = bytesToHex(data.copyOfRange(42, 48))
            val snonceHex = bytesToHex(data.copyOfRange(48, 80))
            val anonceHex = bytesToHex(data.copyOfRange(80, 112))

            val eapolSize = (data[368].toInt() and 0xFF) or
                    ((data[369].toInt() and 0xFF) shl 8) or
                    ((data[370].toInt() and 0xFF) shl 16) or
                    ((data[371].toInt() and 0xFF) shl 24)

            val keyverVal = (data[372].toInt() and 0xFF) or
                    ((data[373].toInt() and 0xFF) shl 8) or
                    ((data[374].toInt() and 0xFF) shl 16) or
                    ((data[375].toInt() and 0xFF) shl 24)

            val keymic = bytesToHex(data.copyOfRange(376, 392))

            val eapolFullHex = bytesToHex(data.copyOfRange(112, 368))
            val eapolLen = (eapolFullHex.length / 2).coerceAtMost(eapolSize.coerceAtMost(256))
            val eapol = eapolFullHex.take(eapolLen * 2)

            val macAp = formatMac(macApHex)
            val macSta = formatMac(macStaHex)
            val essidHex = bytesToHex(essidBytes)

            return HandshakeHash(
                type = HandshakeType.EAPOL,
                pmkidOrMic = keymic,
                macAp = macAp,
                macSta = macSta,
                essid = essid,
                essidHex = essidHex,
                anonce = anonceHex,
                snonce = snonceHex,
                eapol = eapol,
                keyver = keyverVal,
                messagePair = 0x80
            )
        }

        fun parsePmkidText(line: String): HandshakeHash? {
            val trimmed = line.trim()
            if (!trimmed.contains("*")) return null
            val parts = trimmed.split("*")
            if (parts.size < 3) {
                Log.w(TAG, "parsePmkidText: too few fields (${parts.size}): ${trimmed.take(40)}")
                return null
            }

            val pmkid =
                if (parts.size >= 3 && parts[0].length == 32 && parts[0].all { it.isDigit() || it.lowercase() in "abcdef" }) {
                    parts[0]
                } else {
                    val p = trimmed.split(":")
                    if (p.size >= 4 && p[3].length == 32) p[3] else return null
                }

            val macApRaw = parts.getOrElse(1) { "" }
            val macStaRaw = parts.getOrElse(2) { "" }
            val essidHex = parts.getOrElse(3) { "" }

            val macAp = formatMac(macApRaw)
            val macSta = formatMac(macStaRaw)
            val essid = try {
                hexToAscii(essidHex)
            } catch (_: Exception) {
                essidHex
            }

            return HandshakeHash(
                type = HandshakeType.PMKID,
                pmkidOrMic = pmkid,
                macAp = macAp,
                macSta = macSta,
                essid = essid,
                essidHex = essidHex
            )
        }

        fun parseAny(line: String): HandshakeHash? {
            return parse22000Line(line) ?: parsePmkidText(line)
        }

        fun extractAllFromText(text: String): List<HandshakeHash> {
            return text.lines()
                .mapNotNull { parseAny(it.trim()) }
                .distinctBy { it.dedupKey() }
        }

        fun extractAllFromHccap(data: ByteArray): List<HandshakeHash> {
            if (data.size < 392 || data.size % 392 != 0) return emptyList()
            return (0 until data.size step 392).mapNotNull { offset ->
                parseHccap(data.copyOfRange(offset, offset + 392))
            }.distinctBy { it.dedupKey() }
        }

        fun detectFileFormat(file: File): HandshakeFormat {
            if (!file.exists()) {
                Log.w(
                    TAG,
                    "detectFileFormat: file not found: ${file.path}"
                ); return HandshakeFormat.UNKNOWN
            }
            val bytes = try {
                file.inputStream().use { it.readBytes().take(4).toByteArray() }
            } catch (e: Exception) {
                Log.w(TAG, "detectFileFormat: read error", e); return HandshakeFormat.UNKNOWN
            }
            if (bytes.size < 4) {
                Log.w(
                    TAG,
                    "detectFileFormat: file too small: ${file.path}"
                ); return HandshakeFormat.UNKNOWN
            }
            val magicHex = bytes.joinToString("") { "%02x".format(it) }
            return when {
                bytes.contentEquals(pcapNgMagic) -> {
                    Log.d(TAG, "detectFileFormat: PCAPNG ($magicHex)")
                    HandshakeFormat.PCAPNG
                }

                bytes.contentEquals(pcapMagic) || bytes.contentEquals(pcapNanoMagic) -> {
                    Log.d(TAG, "detectFileFormat: PCAP ($magicHex)")
                    HandshakeFormat.PCAP
                }

                bytes.contentEquals(pcapBigEndianMagic) || bytes.contentEquals(pcapNanoBigEndianMagic) -> {
                    Log.d(TAG, "detectFileFormat: PCAP big-endian ($magicHex)")
                    HandshakeFormat.PCAP
                }

                bytes.contentEquals(
                    byteArrayOf(
                        'H'.code.toByte(),
                        'C'.code.toByte(),
                        'P'.code.toByte(),
                        'X'.code.toByte()
                    )
                ) -> {
                    Log.d(TAG, "detectFileFormat: HCCAPX")
                    HandshakeFormat.HCCAPX
                }

                else -> {
                    val text = try {
                        file.readText(Charsets.ISO_8859_1).take(200)
                    } catch (_: Exception) {
                        return HandshakeFormat.UNKNOWN
                    }
                    val format = when {
                        text.contains("WPA*01") || text.contains("WPA*02") || text.contains("WPA*03") -> {
                            Log.d(TAG, "detectFileFormat: 22000 text")
                            HandshakeFormat.M22000
                        }

                        text.contains("PMKID") && text.contains("*") -> {
                            Log.d(TAG, "detectFileFormat: PMKID text")
                            HandshakeFormat.PMKID
                        }

                        text.contains("HCCAPX") || file.length() == 393L -> {
                            Log.d(TAG, "detectFileFormat: HCCAPX (text fallback)")
                            HandshakeFormat.HCCAPX
                        }

                        else -> {
                            val fileLen = file.length()
                            if (fileLen > 0 && fileLen % 392 == 0L) {
                                Log.d(TAG, "detectFileFormat: HCCAP (size=${fileLen})")
                                HandshakeFormat.HCCAP
                            } else {
                                Log.w(
                                    TAG,
                                    "detectFileFormat: UNKNOWN (magic=$magicHex, textStart=${
                                        text.take(60)
                                    })"
                                )
                                HandshakeFormat.UNKNOWN
                            }
                        }
                    }
                    format
                }
            }
        }
    }
}

enum class HandshakeFormat {
    PCAP, PCAPNG, HCCAPX, HCCAP, M22000, PMKID, UNKNOWN
}

private val pcapMagic = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte())
private val pcapBigEndianMagic =
    byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())
private val pcapNanoMagic = byteArrayOf(0x4d.toByte(), 0x3c.toByte(), 0x2b.toByte(), 0x1a.toByte())
private val pcapNanoBigEndianMagic =
    byteArrayOf(0x1a.toByte(), 0x2b.toByte(), 0x3c.toByte(), 0x4d.toByte())
private val pcapNgMagic = byteArrayOf(0x0a.toByte(), 0x0d.toByte(), 0x0d.toByte(), 0x0a.toByte())

private fun formatMac(mac: String): String {
    val clean = mac.replace(Regex("[^0-9A-Fa-f]"), "").padEnd(12, '0').take(12)
    return clean.lowercase().chunked(2).joinToString(":")
}

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { it.isDigit() || it.lowercase() in "abcdef" }
    return clean.chunked(2).mapNotNull {
        try {
            it.toInt(16).toByte()
        } catch (_: Exception) {
            null
        }
    }.toByteArray()
}

private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private fun hexToAscii(hex: String): String {
    val bytes = hex.chunked(2).mapNotNull {
        try {
            it.toInt(16).toByte()
        } catch (_: Exception) {
            null
        }
    }.toByteArray()
    return bytes.decodeToString()
}

private fun extractNonceFromEapol(eapolHex: String): String? {
    if (eapolHex.length < 98) return null
    return eapolHex.substring(34, 98)
}

object HandshakeParser {
    private const val TAG = "HandshakeParser"

    fun parseFile(file: File): List<HandshakeHash> {
        Log.d(TAG, "parseFile: ${file.name} (${file.length()})")
        val format = HandshakeHash.detectFileFormat(file)
        Log.d(TAG, "  detected format: $format")
        val result = when (format) {
            HandshakeFormat.M22000, HandshakeFormat.PMKID -> {
                try {
                    val text = file.readText()
                    Log.d(TAG, "  text size: ${text.length}")
                    val hashes = HandshakeHash.extractAllFromText(text)
                    Log.d(TAG, "  parsed ${hashes.size} hashes from text")
                    hashes
                } catch (e: Exception) {
                    Log.e(TAG, "  text parse failed", e)
                    emptyList()
                }
            }

            HandshakeFormat.HCCAPX -> {
                try {
                    val data = file.readBytes()
                    Log.d(TAG, "  hccapx size: ${data.size}")
                    val hash = HandshakeHash.parseHccapx(data)
                    Log.d(TAG, "  hccapx parse ${if (hash != null) "OK" else "FAILED"}")
                    listOfNotNull(hash)
                } catch (e: Exception) {
                    Log.e(TAG, "  hccapx parse failed", e)
                    emptyList()
                }
            }

            HandshakeFormat.HCCAP -> {
                try {
                    val data = file.readBytes()
                    Log.d(TAG, "  hccap size: ${data.size}")
                    val hashes = HandshakeHash.extractAllFromHccap(data)
                    Log.d(TAG, "  parsed ${hashes.size} hashes from hccap")
                    hashes
                } catch (e: Exception) {
                    Log.e(TAG, "  hccap parse failed", e)
                    emptyList()
                }
            }

            HandshakeFormat.PCAP, HandshakeFormat.PCAPNG -> {
                try {
                    val raw = PcapParser().extractHandshakes(file)
                    Log.d(TAG, "  pcap extracted ${raw.size} raw handshakes")
                    val hashes = raw.mapNotNull { h ->
                        val line = h.to22000Line()
                        val base = HandshakeHash.parse22000Line(line)
                        if (base == null) {
                            Log.w(TAG, "  failed to convert raw handshake: $line")
                            null
                        } else {
                            var result = base
                            if (h.snonce != null) result = result.copy(snonce = h.snonce)
                            if (h.keyver != null && h.keyver != 2) result =
                                result.copy(keyver = h.keyver)
                            result
                        }
                    }.filter { h ->
                        when (h.type) {
                            HandshakeType.EAPOL -> h.anonce != null && h.eapol != null
                            HandshakeType.PMKID, HandshakeType.PMKID_EAPOL -> h.pmkidOrMic.isNotBlank()
                        }
                    }
                    Log.d(TAG, "  parsed ${hashes.size} HandshakeHash objects")
                    hashes
                } catch (e: Exception) {
                    Log.e(TAG, "  pcap parse failed", e)
                    emptyList()
                }
            }

            HandshakeFormat.UNKNOWN -> {
                Log.w(TAG, "  unknown format for ${file.name}")
                emptyList()
            }
        }
        Log.d(TAG, "parseFile result: ${result.size} hashes")
        return result
    }

    fun parseText(text: String): List<HandshakeHash> {
        Log.d(TAG, "parseText: ${text.length} chars")
        val result = HandshakeHash.extractAllFromText(text)
        Log.d(TAG, "parseText result: ${result.size} hashes")
        return result
    }

    fun convert22000ToHccapx(m22000Lines: List<String>): ByteArray {
        val hashes = m22000Lines.mapNotNull { HandshakeHash.parse22000Line(it) }
        Log.d(TAG, "convert22000ToHccapx: ${hashes.size} hashes from ${m22000Lines.size} lines")
        if (hashes.isEmpty()) {
            Log.w(TAG, "convert22000ToHccapx: no valid hashes"); return ByteArray(0)
        }
        val result = hashes.first().toHccapxBytes()
        Log.d(
            TAG,
            "convert22000ToHccapx: result ${result.size}B for ${hashes.first().essid} (${hashes.first().macAp})"
        )
        return result
    }

    fun convert22000ToHccap(m22000Lines: List<String>): ByteArray {
        val hashes = m22000Lines.mapNotNull { HandshakeHash.parse22000Line(it) }
        Log.d(TAG, "convert22000ToHccap: ${hashes.size} hashes from ${m22000Lines.size} lines")
        if (hashes.isEmpty()) {
            Log.w(TAG, "convert22000ToHccap: no valid hashes"); return ByteArray(0)
        }
        val result = hashes.first().toHccapBytes()
        Log.d(
            TAG,
            "convert22000ToHccap: result ${result.size}B for ${hashes.first().essid} (${hashes.first().macAp})"
        )
        return result
    }

    fun convert22000ToPmkidText(m22000Lines: List<String>): String {
        val lines = m22000Lines.mapNotNull { line ->
            HandshakeHash.parse22000Line(line)?.toPmkidLine()
        }
        Log.d(
            TAG,
            "convert22000ToPmkidText: ${lines.size} PMKID lines from ${m22000Lines.size} input lines"
        )
        return lines.joinToString("\n")
    }

    fun convertHccapxTo22000(data: ByteArray): String {
        val hash = HandshakeHash.parseHccapx(data)
        if (hash == null) {
            Log.w(TAG, "convertHccapxTo22000: parse failed (${data.size}B)"); return ""
        }
        val line = hash.to22000Line()
        Log.d(TAG, "convertHccapxTo22000: ${hash.essid} (${hash.macAp}) → ${line.take(60)}...")
        return line
    }

    fun convertHccapxToHccap(data: ByteArray): ByteArray {
        val hash = HandshakeHash.parseHccapx(data)
        if (hash == null) {
            Log.w(TAG, "convertHccapxToHccap: parse failed (${data.size}B)"); return ByteArray(0)
        }
        val result = hash.toHccapBytes()
        Log.d(TAG, "convertHccapxToHccap: ${hash.essid} (${hash.macAp}) → ${result.size}B")
        return result
    }

    fun convertHccapTo22000(data: ByteArray): String {
        val hash = HandshakeHash.parseHccap(data)
        if (hash == null) {
            Log.w(TAG, "convertHccapTo22000: parse failed (${data.size}B)"); return ""
        }
        val line = hash.to22000Line()
        Log.d(TAG, "convertHccapTo22000: ${hash.essid} (${hash.macAp}) → ${line.take(60)}...")
        return line
    }

    fun convertHccapToHccapx(data: ByteArray): ByteArray {
        val hash = HandshakeHash.parseHccap(data)
        if (hash == null) {
            Log.w(TAG, "convertHccapToHccapx: parse failed (${data.size}B)"); return ByteArray(0)
        }
        val result = hash.toHccapxBytes()
        Log.d(TAG, "convertHccapToHccapx: ${hash.essid} (${hash.macAp}) → ${result.size}B")
        return result
    }

    fun dedupKey(line: String): String? {
        val key = HandshakeHash.parseAny(line)?.dedupKey()
        Log.d(TAG, "dedupKey: ${line.take(40)}... → $key")
        return key
    }

    fun extractEssidFrom22000(line: String): String? {
        val essid = HandshakeHash.parse22000Line(line)?.essid
        Log.d(TAG, "extractEssidFrom22000: ${line.take(40)}... → $essid")
        return essid
    }

    fun convertTo16800(hash: HandshakeHash): String? {
        if (hash.type != HandshakeType.PMKID) {
            Log.d(TAG, "convertTo16800: not PMKID type (${hash.type}), skipping"); return null
        }
        if (hash.pmkidOrMic.length != 32) {
            Log.d(
                TAG,
                "convertTo16800: invalid PMKID length ${hash.pmkidOrMic.length}"
            ); return null
        }
        val result = hash.to16800Line()
        Log.d(TAG, "convertTo16800: ${hash.essid} → $result")
        return result
    }

    fun convertAllTo16800(hashes: List<HandshakeHash>): String {
        val lines = hashes.mapNotNull { convertTo16800(it) }.distinct()
        Log.d(TAG, "convertAllTo16800: ${lines.size} lines from ${hashes.size} hashes")
        return lines.joinToString("\n")
    }
}
