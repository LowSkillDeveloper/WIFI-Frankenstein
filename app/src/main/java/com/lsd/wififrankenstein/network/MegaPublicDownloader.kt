package com.lsd.wififrankenstein.network

import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaPublicDownloader(private val client: OkHttpClient) {

    suspend fun resolveFileName(megaUrl: String): String? {
        return try {
            val parsed = MegaUrlParser.parse(megaUrl)
            if (parsed == null) {
                Log.e("MegaPublicDownloader", "Failed to parse MEGA URL: $megaUrl")
                return null
            }
            val apiClient = MegaApiClient(client)
            val fileInfo = apiClient.getFileInfo(parsed.handle)
            Log.d(
                "MegaPublicDownloader",
                "MEGA file info: size=${fileInfo.size}, encryptedAttrs=${fileInfo.encryptedAttrs.size}"
            )
            val attrJson = MegaCrypto.decryptAttr(fileInfo.encryptedAttrs, parsed.aesKey)
            Log.d("MegaPublicDownloader", "Decrypted attrs: ${attrJson.decodeToString()}")
            val fileName = extractFileName(attrJson.decodeToString())
            Log.d("MegaPublicDownloader", "Extracted filename from attrs: $fileName")
            if (fileName != null) {
                fileName
            } else {

                val cdHeader = fileInfo.downloadUrl.substringBefore('?').substringAfterLast('/')
                if (cdHeader.contains('.')) {
                    val fallbackName = cdHeader.substringAfterLast('/')
                    Log.d("MegaPublicDownloader", "Fallback filename from URL path: $fallbackName")
                    fallbackName
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("MegaPublicDownloader", "Failed to resolve MEGA filename: ${e.message}", e)
            null
        }
    }

    suspend fun download(
        megaUrl: String,
        outputFile: File,
        resumeBytes: Long = 0L,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
    ): Result<File> {
        return try {
            val parsed = MegaUrlParser.parse(megaUrl)
                ?: return Result.failure(IllegalArgumentException("Invalid MEGA URL: $megaUrl"))

            val apiClient = MegaApiClient(client)
            val fileInfo = apiClient.getFileInfo(parsed.handle)

            val attrJson = MegaCrypto.decryptAttr(fileInfo.encryptedAttrs, parsed.aesKey)
            val attrString = attrJson.decodeToString()
            val fileName = extractFileName(attrString) ?: outputFile.name

            val fullOutput = if (outputFile.isDirectory) File(outputFile, fileName) else outputFile
            val finalFile = if (fullOutput.name.endsWith(".enc")) {
                File(fullOutput.parent, fullOutput.nameWithoutExtension)
            } else {
                fullOutput
            }

            if (resumeBytes == 0L) {
                finalFile.parentFile?.mkdirs()
            }

            downloadWithDecrypt(
                downloadUrl = fileInfo.downloadUrl,
                aesKey = parsed.aesKey,
                iv = parsed.iv,
                outputFile = finalFile,
                totalSize = fileInfo.size,
                resumeBytes = resumeBytes,
                onProgress = onProgress
            )

            Result.success(finalFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun downloadWithDecrypt(
        downloadUrl: String,
        aesKey: ByteArray,
        iv: ByteArray,
        outputFile: File,
        totalSize: Long,
        resumeBytes: Long,
        onProgress: (Long, Long?) -> Unit
    ) {
        val request = Request.Builder()
            .url(downloadUrl)
            .apply {
                if (resumeBytes > 0L) {
                    header("Range", "bytes=$resumeBytes-")
                }
            }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw MegaApiException("HTTP ${response.code} for download URL")
        }

        val body = response.body

        val contentLength = body.contentLength()

        val ctrIv = CryptoHelper.forwardIv16(iv, resumeBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ctrIv))

        val offsetInBlock = (resumeBytes % 16L).toInt()

        RandomAccessFile(outputFile, "rw").use { raf ->
            if (resumeBytes > 0L) {
                raf.seek(resumeBytes)
            }

            val buf = ByteArray(8192)
            var totalRead = 0L
            val stream: InputStream = body.byteStream()

            if (offsetInBlock > 0) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val discardBuf = ByteArray(16)
                val read = stream.read(discardBuf, 0, 16)
                if (read > 0) {
                    val decrypted = cipher.update(discardBuf, 0, read)
                    val writeLen = read - offsetInBlock
                    if (writeLen > 0) {
                        raf.write(decrypted, offsetInBlock, writeLen)
                        totalRead += writeLen.toLong()
                    }
                }
            }

            var bytesRead: Int
            while (stream.read(buf).also { bytesRead = it } != -1) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val decrypted = cipher.update(buf, 0, bytesRead)
                raf.write(decrypted)
                totalRead += bytesRead.toLong()
                val total =
                    if (totalSize > 0) totalSize else contentLength.takeIf { it >= 0 } ?: -1L
                onProgress(resumeBytes + totalRead, if (total > 0) total else null)
            }

            val finalBlock = cipher.doFinal()
            if (finalBlock.isNotEmpty()) {
                raf.write(finalBlock)
            }

            onProgress(resumeBytes + totalRead, resumeBytes + totalRead)
        }
    }

    private fun extractFileName(attrJson: String): String? {
        return try {
            val cleaned = attrJson.trimStart { it <= ' ' }
            val jsonStart = cleaned.indexOf('{')
            if (jsonStart >= 0) {
                val jsonStr = cleaned.substring(jsonStart)
                JSONObject(jsonStr).optString("n", "").ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private object CryptoHelper {
        fun forwardIv16(iv: ByteArray, bytes: Long): ByteArray {
            val result = ByteArray(16)
            System.arraycopy(iv, 0, result, 0, if (iv.size >= 8) 8 else iv.size)
            val counter = bytes / 16L
            val ctrBytes = ByteArray(8)
            for (i in 0..7) {
                ctrBytes[i] = (counter shr (56 - i * 8)).toByte()
            }
            System.arraycopy(ctrBytes, 0, result, 8, 8)
            return result
        }
    }
}
