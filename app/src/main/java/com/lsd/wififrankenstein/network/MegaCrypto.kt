package com.lsd.wififrankenstein.network

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaCrypto {

    private const val AES_BLOCK = 16

    fun decryptAesCbc(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun decryptAesCtr(data: ByteArray, key: ByteArray, iv: ByteArray, offset: Long): ByteArray {
        val ctrIv = buildCtrIv(iv, offset)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ctrIv))
        return cipher.doFinal(data)
    }

    fun doFinalCtr(cipher: Cipher, data: ByteArray): ByteArray {
        return cipher.doFinal(data)
    }

    fun createCtrDecrypter(key: ByteArray, iv: ByteArray, offset: Long): Cipher {
        val ctrIv = buildCtrIv(iv, offset)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ctrIv))
        return cipher
    }

    fun createCtrDecrypterForChunks(key: ByteArray, iv: ByteArray, bytesWritten: Long): Cipher {
        val forwardedIv = forwardIv(iv, bytesWritten)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(forwardedIv))
        return cipher
    }

    fun decryptAttr(at: ByteArray, key: ByteArray): ByteArray {
        val zeroIv = ByteArray(AES_BLOCK)
        val decrypted = decryptAesCbc(at, key, zeroIv)
        val nullIndex = decrypted.indexOf(0.toByte())
        return if (nullIndex >= 0) decrypted.copyOf(nullIndex) else decrypted
    }

    private fun buildCtrIv(iv: ByteArray, offset: Long): ByteArray {
        val result = ByteArray(AES_BLOCK)
        System.arraycopy(iv, 0, result, 0, 8)
        val counter = offset / AES_BLOCK
        val ctrBytes = longToBytes(counter)
        System.arraycopy(ctrBytes, 0, result, 8, 8)
        return result
    }

    internal fun forwardIv(iv: ByteArray, bytesWritten: Long): ByteArray {
        val result = ByteArray(AES_BLOCK)
        System.arraycopy(iv, 0, result, 0, 8)
        val ctr = bytesWritten / AES_BLOCK
        val ctrBytes = longToBytes(ctr)
        System.arraycopy(ctrBytes, 0, result, 8, 8)
        return result
    }

    private fun longToBytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        for (i in 0..7) {
            bytes[i] = (value shr (56 - i * 8)).toByte()
        }
        return bytes
    }
}
