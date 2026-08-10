package com.lsd.wififrankenstein.util

object WpaCracker {

    private const val TAG = "WpaCracker"

    const val KEYVER_UNKNOWN = 0
    const val KEYVER_TKIP = 1
    const val KEYVER_CCMP = 2
    const val KEYVER_AES_CMAC = 3

    data class CrackerResult(
        val found: Boolean,
        val password: String? = null,
        val pmk: String? = null,
        val ptk: String? = null,
        val mic: String? = null,
        val keyver: Int = KEYVER_UNKNOWN
    )

    fun computePmk(password: String, ssid: String): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        return WpaCrypto.pbkdf2Sha1(passwordBytes, ssidBytes, 4096, 32)
    }

    fun buildPke(
        apMac: ByteArray,
        staMac: ByteArray,
        aNonce: ByteArray,
        sNonce: ByteArray
    ): ByteArray {
        val mac1: ByteArray
        val mac2: ByteArray
        if (memcmp(apMac, staMac) < 0) {
            mac1 = apMac
            mac2 = staMac
        } else {
            mac1 = staMac
            mac2 = apMac
        }

        val nonce1: ByteArray
        val nonce2: ByteArray
        if (memcmp(aNonce, sNonce) < 0) {
            nonce1 = aNonce
            nonce2 = sNonce
        } else {
            nonce1 = sNonce
            nonce2 = aNonce
        }

        val pke = ByteArray(100)
        "Pairwise key expansion\u0000".toByteArray(Charsets.US_ASCII).copyInto(pke, 0, 0, 23)
        mac1.copyInto(pke, 23)
        mac2.copyInto(pke, 29)
        nonce1.copyInto(pke, 35)
        nonce2.copyInto(pke, 67)
        return pke
    }

    fun computePtk(pmk: ByteArray, pke: ByteArray): ByteArray {
        val ptk = ByteArray(80)
        for (i in 0 until 4) {
            pke[99] = i.toByte()
            val block = WpaCrypto.hmacSha1(pmk, pke)
            System.arraycopy(block, 0, ptk, i * 20, minOf(20, ptk.size - i * 20))
        }
        return ptk
    }

    fun computePtkVer3(
        pmk: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
        aNonce: ByteArray,
        sNonce: ByteArray
    ): ByteArray {
        val mac1: ByteArray
        val mac2: ByteArray
        if (memcmp(apMac, staMac) < 0) {
            mac1 = apMac; mac2 = staMac
        } else {
            mac1 = staMac; mac2 = apMac
        }
        val nonce1: ByteArray
        val nonce2: ByteArray
        if (memcmp(aNonce, sNonce) < 0) {
            nonce1 = aNonce; nonce2 = sNonce
        } else {
            nonce1 = sNonce; nonce2 = aNonce
        }

        val data = mac1 + mac2 + nonce1 + nonce2
        val label = "Pairwise key expansion".toByteArray(Charsets.US_ASCII)
        return WpaCrypto.sha256Prf(pmk, label, data, 384)
    }

    fun extractSnonce(eapol: ByteArray): ByteArray? {
        if (eapol.size < 49) return null
        return eapol.copyOfRange(17, 17 + 32)
    }

    fun extractKeyver(eapol: ByteArray): Int {
        if (eapol.size < 7) return KEYVER_CCMP
        val keyInfo = ((eapol[5].toInt() and 0xFF) shl 8) or (eapol[6].toInt() and 0xFF)
        return keyInfo and 0x03
    }

    fun zeroMic(eapol: ByteArray): ByteArray {
        val copy = eapol.copyOf()
        val micEnd = minOf(81 + 16, copy.size)
        for (i in 81 until micEnd) {
            copy[i] = 0
        }
        return copy
    }

    fun tryPassword(password: String, hash: HandshakeHash): CrackerResult {
        try {
            if (hash.anonce.isNullOrBlank() || hash.eapol.isNullOrBlank()) {
                if (hash.type == HandshakeType.PMKID || hash.type == HandshakeType.PMKID_EAPOL) {
                    if (hash.pmkidOrMic.isBlank()) return CrackerResult(false)
                } else return CrackerResult(false)
            }
            val ssid = hash.essid
            val apMacBytes = parseMac(hash.macAp)
            val staMacBytes = parseMac(hash.macSta)
            val pmk = computePmk(password, ssid)

            if (hash.type == HandshakeType.PMKID || hash.type == HandshakeType.PMKID_EAPOL) {
                val pmkidInput =
                    "PMK Name".toByteArray(Charsets.US_ASCII) + apMacBytes + staMacBytes
                val computedPmkid = WpaCrypto.hmacSha1(pmk, pmkidInput)
                val capturedPmkid = WpaCrypto.hexToBytes(hash.pmkidOrMic)
                if (computedPmkid.size >= 16 && capturedPmkid.size >= 16 &&
                    computedPmkid.copyOfRange(0, 16).contentEquals(capturedPmkid.copyOfRange(0, 16))
                ) {
                    return CrackerResult(true, password, pmk = WpaCrypto.bytesToHex(pmk))
                }
                if (hash.type == HandshakeType.PMKID) return CrackerResult(false)
            }

            val anonceHex = hash.anonce ?: return CrackerResult(false)
            val eapolHex = hash.eapol ?: return CrackerResult(false)
            val aNonce = WpaCrypto.hexToBytes(anonceHex)
            val eapol = WpaCrypto.hexToBytes(eapolHex)
            val sNonce = extractSnonce(eapol) ?: return CrackerResult(false)
            val keyver = hash.keyver ?: extractKeyver(eapol)
            val capturedMic = WpaCrypto.hexToBytes(hash.pmkidOrMic)

            val pke = buildPke(apMacBytes, staMacBytes, aNonce, sNonce)

            val ptk: ByteArray
            val computedMic: ByteArray
            val kck: ByteArray

            when (keyver) {
                KEYVER_TKIP -> {
                    ptk = computePtk(pmk, pke)
                    kck = ptk.copyOfRange(0, 16)
                    val eapolZerod = zeroMic(eapol)
                    computedMic = WpaCrypto.hmacMd5(kck, eapolZerod)
                }

                KEYVER_AES_CMAC -> {
                    ptk = computePtkVer3(pmk, apMacBytes, staMacBytes, aNonce, sNonce)
                    kck = ptk.copyOfRange(0, 16)
                    val eapolZerod = zeroMic(eapol)
                    computedMic = WpaCrypto.aes128Cmac(kck, eapolZerod)
                }

                else -> {
                    ptk = computePtk(pmk, pke)
                    kck = ptk.copyOfRange(0, 16)
                    val eapolZerod = zeroMic(eapol)
                    computedMic = WpaCrypto.hmacSha1(kck, eapolZerod)
                }
            }

            if (computedMic.size >= 16 && capturedMic.size >= 16 &&
                computedMic.copyOfRange(0, 16).contentEquals(capturedMic.copyOfRange(0, 16))
            ) {
                return CrackerResult(
                    true, password,
                    pmk = WpaCrypto.bytesToHex(pmk),
                    ptk = WpaCrypto.bytesToHex(ptk),
                    mic = WpaCrypto.bytesToHex(computedMic),
                    keyver = keyver
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryPassword error for '$password': ${e.message}")
        }
        return CrackerResult(false)
    }

    fun tryPasswordAny(password: String, hash: HandshakeHash): Boolean {
        val result = tryPassword(password, hash)
        return result.found
    }

    private fun parseMac(mac: String): ByteArray {
        return mac.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun memcmp(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
