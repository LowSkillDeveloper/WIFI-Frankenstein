package com.lsd.wififrankenstein.ui.handshakecapture

import android.content.Context
import android.net.Uri
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.ArchiveExtractor
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HandshakeImportManager(private val context: Context) {

    private val storageManager = HandshakeStorageManager(context)
    private val chrootManager = ChrootManager.get(context)
    private val captureRunner = HandshakeCaptureRunner(context)
    private val tag = "HandshakeImportManager"

    data class ImportResult(
        val successCount: Int,
        val failCount: Int,
        val imported: List<HandshakeItem>,
        val warnings: List<String>
    )

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_temp")
        tempDir.mkdirs()
        try {
            val fileName = getFileName(uri) ?: "import_${System.currentTimeMillis()}"
            val tempFile = File(tempDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: return@withContext ImportResult(0, 0, emptyList(), listOf(context.getString(R.string.imp_cannot_open_file)))
            processFile(tempFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun importFromUrl(
        url: String,
        isMega: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "import_temp")
            tempDir.mkdirs()
            try {
                val fileName =
                    url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
                        ?: "download_${System.currentTimeMillis()}"
                val tempFile = File(tempDir, fileName)

                if (isMega) {
                    onProgress(context.getString(R.string.imp_downloading_mega))
                    val megaClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val megaDownloader =
                        com.lsd.wififrankenstein.network.MegaPublicDownloader(megaClient)
                    val resolvedName = megaDownloader.resolveFileName(url) ?: fileName
                    val resolvedFile = File(tempDir, resolvedName)
                    val result = megaDownloader.download(
                        url,
                        resolvedFile,
                        onProgress = { downloaded, total ->
                            if (total != null && total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                onProgress(context.getString(R.string.imp_mega_progress, pct))
                            }
                        })
                    result.getOrNull()?.let { processFile(it) }
                        ?: return@withContext ImportResult(
                            0,
                            0,
                            emptyList(),
                            listOf(context.getString(R.string.imp_mega_failed, result.exceptionOrNull()?.message))
                        )
                } else {
                    onProgress(context.getString(R.string.imp_downloading))
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder().url(url)
                        .addHeader("User-Agent", "WIFI-Frankenstein/1.1").build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) return@withContext ImportResult(
                        0,
                        0,
                        emptyList(),
                        listOf(context.getString(R.string.imp_http_error, response.code))
                    )
                    response.body?.bytes()?.let { tempFile.writeBytes(it) }
                    processFile(tempFile)
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }

    suspend fun importFromBytes(
        data: ByteArray,
        essid: String,
        bssid: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "bettercap_import")
        tempDir.mkdirs()
        try {
            val safeName = essid.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
            val bssidSuffix = if (bssid != null) "_${bssid.replace(":", "")}" else ""
            val fileName = "${safeName}$bssidSuffix.pcap"
            val tempFile = File(tempDir, fileName)
            tempFile.writeBytes(data)
            processFile(tempFile, essid, bssid)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    suspend fun importFromText(text: String): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_temp")
        tempDir.mkdirs()
        try {
            val parsedHashes = HandshakeParser.parseText(text)
            if (parsedHashes.isEmpty()) return@withContext ImportResult(
                0,
                0,
                emptyList(),
                listOf(context.getString(R.string.imp_no_valid_hash_lines))
            )

            val hashLines = parsedHashes.map { it.to22000Line() }.distinct()
            val dedupKeys = parsedHashes.map { it.dedupKey() }.distinct()

            val hashFile = File(tempDir, "pasted_${System.currentTimeMillis()}.22000")
            hashFile.writeText(hashLines.joinToString("\n"))

            val capFile = File(tempDir, hashFile.nameWithoutExtension + ".cap")
            val hasChroot = ChrootCapabilities.isAvailable(context)
            val chrootOutput = "/sdcard/WIFI-Frankenstein/temp/${hashFile.nameWithoutExtension}.cap"
            val warnings = mutableListOf<String>()

            if (hasChroot) {
                try {
                    chrootManager.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
                    val conv = chrootManager.executeInChroot(
                        "hcxhash2cap -o '$chrootOutput' '${
                            chrootPath(hashFile)
                        }' 2>&1"
                    )
                    if (conv.isSuccess && chrootManager.executeInChroot("test -s '$chrootOutput'").isSuccess) {
                        Shell.cmd("cp '$chrootOutput' '${capFile.absolutePath}'").exec()
                        if (capFile.exists()) {
                            warnings.add(context.getString(R.string.imp_converted_via_hcx))
                            val result = processFile(capFile)
                            return@withContext result.copy(warnings = result.warnings + warnings)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "importFromText: hcxhash2cap unavailable, falling back: ${e.message}")
                }
                warnings.add(context.getString(R.string.imp_hcx_failed))
            } else {
                warnings.add(context.getString(R.string.imp_chroot_unavailable))
            }

            warnings.add(context.getString(R.string.imp_hash_reference_only))
            val saved = copyToChrootStorage(hashFile, "Pasted")
            if (saved != null) {
                val first = parsedHashes.first()
                val essid = first.essid
                val bssid = first.macAp
                val stat = chrootManager.executeInChroot("stat -c '%s' '$saved' 2>/dev/null")
                val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                storageManager.saveHandshakeMetadata(
                    HandshakeItem(
                        filePath = saved, fileName = File(saved).name,
                        bssid = bssid, essid = essid, fileSize = fileSize,
                        lastModified = System.currentTimeMillis(),
                        hash22000 = hashLines.joinToString("\n"),
                        originalFormat = "22000",
                        handshakeCount = parsedHashes.size,
                        hashDedupMd5 = dedupKeys.firstOrNull()
                    )
                )
                ImportResult(1, 0, emptyList(), warnings)
            } else {
                ImportResult(0, 0, emptyList(), warnings + listOf(context.getString(R.string.imp_failed_save_hash)))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun processFile(
        file: File,
        essid: String? = null,
        bssid: String? = null
    ): ImportResult {
        val warnings = mutableListOf<String>()
        var success = 0
        var failed = 0
        val imported = mutableListOf<HandshakeItem>()

        if (file.extension.lowercase() in listOf("zip", "7z", "gz", "tgz")) {
            val tempDir = File(context.cacheDir, "import_extracted_${System.nanoTime()}")
            tempDir.mkdirs()
            try {
                for (f in ArchiveExtractor.extract(file, tempDir)) {
                    val result = processSingleFile(f, essid, bssid)
                    if (result != null) {
                        imported.add(result); success++
                    } else failed++
                }
            } catch (e: Exception) {
                warnings.add(context.getString(R.string.imp_archive_error, e.message))
            } finally {
                tempDir.deleteRecursively()
            }
        } else {
            val result = processSingleFile(file, essid, bssid)
            if (result != null) {
                imported.add(result); success++
            } else failed++
        }
        return ImportResult(success, failed, imported, warnings)
    }

    private suspend fun processSingleFile(
        file: File,
        essidOverride: String? = null,
        bssidOverride: String? = null
    ): HandshakeItem? {
        return try {
            var capFile = file

            val detectedFormat = HandshakeHash.detectFileFormat(file)
            val isBinaryCapture = detectedFormat in listOf(
                com.lsd.wififrankenstein.util.HandshakeFormat.PCAP,
                com.lsd.wififrankenstein.util.HandshakeFormat.PCAPNG
            )
            val isTextHash = detectedFormat in listOf(
                com.lsd.wififrankenstein.util.HandshakeFormat.M22000,
                com.lsd.wififrankenstein.util.HandshakeFormat.PMKID,
                com.lsd.wififrankenstein.util.HandshakeFormat.HCCAPX
            )

            if (isTextHash) {
                val parsed = HandshakeParser.parseFile(file)
                val hashLines = parsed.map { it.to22000Line() }.distinct()
                val chrootStored =
                    copyToChrootStorage(file, file.nameWithoutExtension) ?: return null
                val stat = chrootManager.executeInChroot("stat -c '%s' '$chrootStored' 2>/dev/null")
                val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                val first = parsed.firstOrNull()
                storageManager.saveHandshakeMetadata(
                    HandshakeItem(
                        filePath = chrootStored, fileName = File(chrootStored).name,
                        bssid = bssidOverride ?: first?.macAp,
                        essid = essidOverride ?: first?.essid ?: file.nameWithoutExtension,
                        fileSize = fileSize, lastModified = System.currentTimeMillis(),
                        hash22000 = hashLines.joinToString("\n"),
                        hashPmkid = parsed.firstOrNull { it.type == com.lsd.wififrankenstein.util.HandshakeType.PMKID }
                            ?.let {
                                if (it.pmkidOrMic.length == 32) it.pmkidOrMic else null
                            },
                        originalFormat = file.extension.lowercase(),
                        handshakeCount = parsed.size,
                        hashDedupMd5 = parsed.firstOrNull()?.dedupKey()
                    )
                )
                return storageManager.getHandshakeMeta(File(chrootStored).name)
            }

            if (file.extension.lowercase() !in listOf(
                    "cap",
                    "pcap",
                    "pcapng"
                ) && ChrootCapabilities.isAvailable(context)
            ) {
                try {
                    val chrootOut = "/sdcard/WIFI-Frankenstein/temp/${file.nameWithoutExtension}.cap"
                    chrootManager.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
                    val conv = chrootManager.executeInChroot(
                        "hcxhash2cap -o '$chrootOut' '${chrootPath(file)}' 2>&1"
                    )
                    if (conv.isSuccess && chrootManager.executeInChroot("test -s '$chrootOut'").isSuccess) {
                        capFile =
                            File(context.cacheDir, "import_converted/${file.nameWithoutExtension}.cap")
                        capFile.parentFile?.mkdirs()
                        Shell.cmd("cp '$chrootOut' '${capFile.absolutePath}'").exec()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "processSingleFile: hcxhash2cap unavailable for ${file.name}: ${e.message}")
                }
            }

            val chrootStored =
                copyToChrootStorage(capFile, file.nameWithoutExtension, bssidOverride)
                    ?: return null

            try {
                var allHashes = mutableListOf<HandshakeHash>()


                var nativeCount = 0
                try {
                    val parsed = captureRunner.readCapBytesAndParse(chrootStored)
                    nativeCount = parsed.size
                    if (parsed.isNotEmpty()) {
                        allHashes.addAll(parsed)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "processSingleFile: native parse failed", e)
                }


                var hcxCount = 0
                if (ChrootCapabilities.isAvailable(context)) {
                    try {
                        val rawOutput = captureRunner.getHcxpcapngtoolOutput(chrootStored)
                        val parsed =
                            rawOutput.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                        hcxCount = parsed.size
                        allHashes.addAll(parsed)
                    } catch (e: Exception) {
                        Log.w(tag, "processSingleFile: hcxpcapngtool failed", e)
                    }
                }

                allHashes = allHashes.distinctBy { it.dedupKey() }.toMutableList()
                Log.d(
                    tag,
                    "processSingleFile: ${file.name}: native=$nativeCount hcx=$hcxCount merged=${allHashes.size}"
                )

                val hash22000Lines = allHashes.map { it.to22000Line() }.distinct()
                val firstPmkid =
                    allHashes.firstOrNull { it.type == com.lsd.wififrankenstein.util.HandshakeType.PMKID }
                val bssid = allHashes.firstOrNull()?.macAp?.uppercase()
                val eapolCount =
                    allHashes.count { it.type == com.lsd.wififrankenstein.util.HandshakeType.EAPOL }
                val pmkidCount =
                    allHashes.count { it.type == com.lsd.wififrankenstein.util.HandshakeType.PMKID }
                val jvmFileSize = try {
                    File(chrootStored.replaceFirst("/sdcard", "/storage/emulated/0")).length()
                } catch (_: Exception) {
                    0L
                }
                val stat = chrootManager.executeInChroot("stat -c '%s' '$chrootStored' 2>/dev/null")
                val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull() ?: jvmFileSize

                val finalEssid =
                    essidOverride ?: allHashes.firstOrNull()?.essid ?: file.nameWithoutExtension
                val finalBssid = bssidOverride ?: bssid
                val hash16800 = firstPmkid?.let { HandshakeParser.convertTo16800(it) }

                storageManager.saveHandshakeMetadata(
                    HandshakeItem(
                        filePath = chrootStored, fileName = File(chrootStored).name,
                        bssid = finalBssid, essid = finalEssid,
                        fileSize = fileSize, lastModified = System.currentTimeMillis(),
                        hash22000 = hash22000Lines.joinToString("\n"),
                        hashPmkid = firstPmkid?.pmkidOrMic?.takeIf { it.length == 32 },
                        originalFormat = file.extension.lowercase(),
                        handshakeCount = allHashes.size,
                        eapolCount = eapolCount,
                        pmkidCount = pmkidCount,
                        hashDedupMd5 = allHashes.firstOrNull()?.dedupKey(),
                        hash16800 = hash16800
                    )
                )


                try {
                    val meta = captureRunner.readCapApMetadata(chrootStored)
                    val fileName = File(chrootStored).name
                    val ap = meta.values.firstOrNull()
                    if (ap != null) {
                        val clients =
                            if (ap.clients.isNotEmpty()) ap.clients.joinToString(",") else null
                        storageManager.updateHandshakeMetadata(
                            fileName,
                            clients = clients, channel = ap.channel,
                            akm = ap.akm, groupCipher = ap.groupCipher,
                            pairwiseCipher = ap.pairwiseCipher, rssi = ap.rssi,
                            eapolM1Count = ap.eapolM1Count, eapolM2Count = ap.eapolM2Count,
                            eapolM3Count = ap.eapolM3Count, eapolM4Count = ap.eapolM4Count,
                            beaconCount = ap.beaconCount, assocReqCount = ap.assocReqCount,
                            authCount = ap.authCount, probeReqCount = ap.probeReqCount,
                            hash16800 = hash16800
                        )
                    }
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                Log.w(tag, "Hash extraction failed", e)
                val finalEssid = essidOverride ?: file.nameWithoutExtension
                val finalBssid = bssidOverride
                val stat = chrootManager.executeInChroot("stat -c '%s' '$chrootStored' 2>/dev/null")
                val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                storageManager.saveHandshakeMetadata(
                    HandshakeItem(
                        filePath = chrootStored, fileName = File(chrootStored).name,
                        bssid = finalBssid, essid = finalEssid,
                        fileSize = fileSize, lastModified = System.currentTimeMillis(),
                        originalFormat = file.extension.lowercase()
                    )
                )
            }

            storageManager.getHandshakeMeta(File(chrootStored).name)
        } catch (e: Exception) {
            Log.e(tag, "Failed to import ${file.name}", e)
            null
        }
    }

    private suspend fun copyToChrootStorage(
        file: File,
        essid: String,
        bssid: String? = null
    ): String? {
        return try {
            val safeEssid = essid.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
            val bssidPart = bssid?.replace(":", "") ?: System.currentTimeMillis().toString()
            val destName = "${safeEssid}_${bssidPart}.${file.extension}"
            val chrootDest = "${HandshakeStorageManager.STORAGE_DIR}/$destName"
            storageManager.ensureStorageDir()

            val chrootSource = try {
                chrootPath(file)
            } catch (e: Exception) {
                Log.w(tag, "copyToChrootStorage: chrootPath failed: ${e.message}")
                null
            }

            if (chrootSource != null) {
                val cp =
                    chrootManager.executeInChroot("cp '$chrootSource' '$chrootDest' 2>&1 && echo CP_OK")
                if (cp.isSuccess && cp.out.firstOrNull()?.trim() == "CP_OK") {
                    Log.d(tag, "copyToChrootStorage: OK -> $chrootDest")
                    return chrootDest
                }
                Log.w(tag, "copyToChrootStorage: chroot cp failed: ${cp.out}")
            }

            val jvmSaved = storageManager.copyViaJvm(file, destName)
            if (jvmSaved != null) {
                Log.d(tag, "copyToChrootStorage: JVM OK -> $jvmSaved")
                chrootDest
            } else {
                Log.e(tag, "copyToChrootStorage failed (chroot + JVM)")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "copyToChrootStorage failed", e)
            null
        }
    }

    private fun chrootPath(file: File): String {
        val sdcard = android.os.Environment.getExternalStorageDirectory().absolutePath
        val jvmPath = file.absolutePath
        return if (jvmPath.startsWith(sdcard)) {
            jvmPath.replace(sdcard, "/sdcard")
        } else {
            val tempPath = "/sdcard/WIFI-Frankenstein/temp/${file.name}"
            val result =
                Shell.cmd("mkdir -p /sdcard/WIFI-Frankenstein/temp && cp '${file.absolutePath}' '$tempPath' && echo CP_OK")
                    .exec()
            if (!result.isSuccess || result.out.none { it.trim() == "CP_OK" }) {
                val errMsg = "chrootPath: cp from JVM to sdcard failed for ${file.name}"
                Log.e(tag, errMsg)
                throw java.io.IOException(errMsg)
            }
            tempPath
        }
    }

    private fun extractEssidFromHash(hashLines: List<String>): String? {
        for (line in hashLines) {
            val parts = line.split("*")
            if (parts.size >= 5) return parts[4]
        }
        return null
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
        }
    }
}
