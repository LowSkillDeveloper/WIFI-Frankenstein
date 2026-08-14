package com.lsd.wififrankenstein.network

import java.util.Base64
import java.util.regex.Pattern

data class MegaParsedLink(
    val handle: String,
    val rawKey: ByteArray,
    val aesKey: ByteArray,
    val iv: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MegaParsedLink) return false
        return handle == other.handle && rawKey.contentEquals(other.rawKey) &&
                aesKey.contentEquals(other.aesKey) && iv.contentEquals(other.iv)
    }

    override fun hashCode(): Int {
        var result = handle.hashCode()
        result = 31 * result + rawKey.contentHashCode()
        result = 31 * result + aesKey.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

object MegaUrlParser {

    private val FILE_LINK_PATTERN = Pattern.compile(
        "mega\\.nz/(?:file|#!)/([A-Za-z0-9_-]+)(?:#|!)([A-Za-z0-9_-]+)"
    )

    private val FOLDER_LINK_PATTERN = Pattern.compile(
        "mega\\.nz/folder/([A-Za-z0-9_-]+)(?:#|!)([A-Za-z0-9_-]+)"
    )

    fun isMegaUrl(url: String): Boolean {
        return FILE_LINK_PATTERN.matcher(url).find() || FOLDER_LINK_PATTERN.matcher(url).find()
    }

    fun isFileLink(url: String): Boolean {
        return FILE_LINK_PATTERN.matcher(url).find()
    }

    fun parse(url: String): MegaParsedLink? {
        val matcher = FILE_LINK_PATTERN.matcher(url)
        if (!matcher.find()) return null

        val handle = matcher.group(1)!!
        val keyB64 = matcher.group(2)!!

        return try {
            val rawKey = base64UrlDecode(keyB64)
            if (rawKey.size != 32) return null
            val aesKey = unmergeKey(rawKey)
            val iv = extractIv(rawKey)
            MegaParsedLink(handle, rawKey, aesKey, iv)
        } catch (_: Exception) {
            null
        }
    }

    fun isFolderLink(url: String): Boolean {
        return FOLDER_LINK_PATTERN.matcher(url).find()
    }

    fun base64UrlDecode(data: String): ByteArray {
        return Base64.getUrlDecoder().decode(data)
    }

    fun base64UrlEncode(data: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun unmergeKey(rawKey: ByteArray): ByteArray {
        val key = IntArray(4)
        val intKey = bytesToInts(rawKey, 8)
        key[0] = intKey[0] xor intKey[4]
        key[1] = intKey[1] xor intKey[5]
        key[2] = intKey[2] xor intKey[6]
        key[3] = intKey[3] xor intKey[7]
        return intsToBytes(key, 4)
    }

    private fun extractIv(rawKey: ByteArray): ByteArray {
        val intKey = bytesToInts(rawKey, 8)
        val iv = IntArray(4)
        iv[0] = intKey[4]
        iv[1] = intKey[5]
        iv[2] = 0
        iv[3] = 0
        return intsToBytes(iv, 4)
    }

    fun forwardIv(iv: ByteArray, bytesWritten: Long): ByteArray {
        val result = ByteArray(16)
        System.arraycopy(iv, 0, result, 0, if (iv.size < 8) iv.size else 8)
        val counter = bytesWritten / 16L
        for (i in 0..7) {
            result[8 + i] = (counter shr (56 - i * 8)).toByte()
        }
        return result
    }

    private fun bytesToInts(bytes: ByteArray, count: Int): IntArray {
        val ints = IntArray(count)
        for (i in 0 until count) {
            val off = i * 4
            ints[i] = ((bytes[off].toInt() and 0xFF) shl 24) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                    (bytes[off + 3].toInt() and 0xFF)
        }
        return ints
    }

    private fun intsToBytes(ints: IntArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 4)
        for (i in 0 until count) {
            val off = i * 4
            bytes[off] = (ints[i] shr 24).toByte()
            bytes[off + 1] = (ints[i] shr 16).toByte()
            bytes[off + 2] = (ints[i] shr 8).toByte()
            bytes[off + 3] = ints[i].toByte()
        }
        return bytes
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }
}
