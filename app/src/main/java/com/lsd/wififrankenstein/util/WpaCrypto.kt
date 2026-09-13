package com.lsd.wififrankenstein.util

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object WpaCrypto {

    private val pbkdf2Factory by lazy {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    }

    fun sha1(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(data)

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun pbkdf2Sha1(
        password: ByteArray,
        ssid: ByteArray,
        iterations: Int = 4096,
        dkLen: Int = 32
    ): ByteArray {
        val spec = PBEKeySpec(
            String(password, Charsets.UTF_8).toCharArray(),
            ssid,
            iterations,
            dkLen * 8
        )
        return pbkdf2Factory.generateSecret(spec).encoded
    }

    fun sha1Prf(key: ByteArray, label: ByteArray, data: ByteArray, outputLen: Int): ByteArray {
        val out = ByteArray(outputLen)
        val msg = ByteArray(label.size + data.size + 1)
        System.arraycopy(label, 0, msg, 0, label.size)
        System.arraycopy(data, 0, msg, label.size, data.size)
        var pos = 0
        var counter = 0
        while (pos < outputLen) {
            msg[msg.size - 1] = counter.toByte()
            val block = hmacSha1(key, msg)
            val copyLen = minOf(block.size, outputLen - pos)
            System.arraycopy(block, 0, out, pos, copyLen)
            pos += copyLen
            counter++
        }
        return out
    }

    fun sha256Prf(
        key: ByteArray,
        label: ByteArray,
        data: ByteArray,
        outputLenBits: Int
    ): ByteArray {
        val outputLenBytes = (outputLenBits + 7) / 8
        val out = ByteArray(outputLenBytes)
        val lengthBytes = ByteArray(2)
        lengthBytes[0] = (outputLenBits and 0xFF).toByte()
        lengthBytes[1] = ((outputLenBits shr 8) and 0xFF).toByte()
        val counterBytes = ByteArray(2)
        val msg = ByteArray(2 + label.size + data.size + lengthBytes.size)
        System.arraycopy(label, 0, msg, 2, label.size)
        System.arraycopy(data, 0, msg, 2 + label.size, data.size)
        System.arraycopy(lengthBytes, 0, msg, 2 + label.size + data.size, lengthBytes.size)
        var pos = 0
        var counter = 1
        while (pos < outputLenBytes) {
            counterBytes[0] = (counter and 0xFF).toByte()
            counterBytes[1] = ((counter shr 8) and 0xFF).toByte()
            msg[0] = counterBytes[0]
            msg[1] = counterBytes[1]
            val block = hmacSha256(key, msg)
            val copyLen = minOf(block.size, outputLenBytes - pos)
            System.arraycopy(block, 0, out, pos, copyLen)
            pos += copyLen
            counter++
        }
        return out
    }

    fun aes128Cmac(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        val zeroBlock = ByteArray(16)
        val L = cipher.doFinal(zeroBlock)
        val k1 = dbl(L)
        val k2 = dbl(k1)

        val n = if (data.isEmpty()) 1 else (data.size + 15) / 16
        val lastBlock: ByteArray
        val lastComplete: Boolean

        if (data.isEmpty()) {
            lastBlock = ByteArray(16)
            lastBlock[0] = 0x80.toByte()
            lastComplete = false
        } else if (data.size % 16 == 0) {
            lastBlock = data.copyOfRange(data.size - 16, data.size)
            lastComplete = true
        } else {
            lastBlock = ByteArray(16)
            val offset = (n - 1) * 16
            val remaining = data.size - offset
            System.arraycopy(data, offset, lastBlock, 0, remaining)
            lastBlock[remaining] = 0x80.toByte()
            lastComplete = false
        }

        val subkey = if (lastComplete) k1 else k2
        for (i in 0 until 16) {
            lastBlock[i] = (lastBlock[i].toInt() xor subkey[i].toInt()).toByte()
        }

        var x = ByteArray(16)
        for (i in 0 until n) {
            val block = if (i == n - 1) lastBlock else data.copyOfRange(i * 16, (i + 1) * 16)
            val y = ByteArray(16)
            for (j in 0 until 16) {
                y[j] = (x[j].toInt() xor block[j].toInt()).toByte()
            }
            x = cipher.doFinal(y)
        }
        return x
    }

    private fun dbl(block: ByteArray): ByteArray {
        val result = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val v = block[i].toInt() and 0xFF
            result[i] = ((v shl 1) or carry).toByte()
            carry = if (v and 0x80 != 0) 1 else 0
        }
        if (carry != 0) {
            result[15] = (result[15].toInt() xor 0x87).toByte()
        }
        return result
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun hexToBytes(hex: String): ByteArray {
        val cleanLen = hex.count { it.isLetterOrDigit() }
        val result = ByteArray(cleanLen / 2)
        var j = 0
        var i = 0
        while (i < hex.length) {
            if (!hex[i].isLetterOrDigit()) { i++; continue }
            val hi = hex[i].digitToInt(16) shl 4
            i++
            while (i < hex.length && !hex[i].isLetterOrDigit()) i++
            if (i < hex.length) {
                result[j++] = (hi or hex[i].digitToInt(16)).toByte()
                i++
            }
        }
        return result
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX_CHARS[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
