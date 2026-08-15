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
        } catch (e: MegaFileUnavailableException) {
            throw e
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

            if (resumeBytes > 0L && resumeBytes >= fileInfo.size) {
                Log.w("MegaPublicDownloader", "Resume offset >= file size, file already complete")
                return Result.success(finalFile)
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
        } catch (e: CancellationException) {
            throw e
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
        var effectiveResume = resumeBytes

        val requestRangeStart = resumeBytes - (resumeBytes % 16L)

        val request = Request.Builder()
            .url(downloadUrl)
            .apply {
                if (resumeBytes > 0L) {
                    header("Range", "bytes=$requestRangeStart-")
                }
            }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            when (response.code) {
                509 -> throw MegaQuotaException("MEGA bandwidth quota exceeded (HTTP 509)")
                403, 404, 410, 451 ->
                    throw MegaFileUnavailableException("MEGA file not found or unavailable (HTTP ${response.code})")
            }
            throw MegaApiException("HTTP ${response.code} for download URL")
        }

        val body = response.body

        val contentLength = body.contentLength()

        if (resumeBytes > 0L && response.code == 200) {
            Log.w("MegaPublicDownloader", "Server ignored Range while resuming, restarting from 0")
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.setLength(0L)
            }
            effectiveResume = 0L
        }

        val offsetInBlock = (effectiveResume % 16L).toInt()
        val rangeStart = effectiveResume - offsetInBlock

        val ctrIv = CryptoHelper.forwardIv16(iv, rangeStart)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ctrIv))

        RandomAccessFile(outputFile, "rw").use { raf ->
            if (effectiveResume > 0L) {
                raf.seek(effectiveResume)
            }

            val buf = ByteArray(64 * 1024)
            var totalRead = 0L
            val stream: InputStream = body.byteStream()

            var skip = offsetInBlock

            var bytesRead: Int
            while (stream.read(buf).also { bytesRead = it } != -1) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val decrypted = cipher.update(buf, 0, bytesRead)

                var offset = 0
                var len = bytesRead
                if (skip > 0) {
                    if (len <= skip) {
                        skip -= len
                        len = 0
                    } else {
                        offset = skip
                        len -= skip
                        skip = 0
                    }
                }
                if (len > 0) {
                    raf.write(decrypted, offset, len)
                    totalRead += len.toLong()
                }

                val total =
                    if (totalSize > 0) totalSize else contentLength.takeIf { it >= 0 } ?: -1L
                onProgress(effectiveResume + totalRead, if (total > 0) total else null)
            }

            val finalBlock = cipher.doFinal()
            if (finalBlock.isNotEmpty()) {
                raf.write(finalBlock)
            }

            onProgress(effectiveResume + totalRead, effectiveResume + totalRead)
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
