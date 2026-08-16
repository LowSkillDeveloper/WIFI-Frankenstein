package com.lsd.wififrankenstein.ui.handshakecapture

import android.content.Context
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HandshakeStorageManager(private val context: Context) {

    companion object {
        const val STORAGE_DIR = "/sdcard/WIFI-Frankenstein/handshakes-storage"
        const val CAPTURE_DIR = "/sdcard/WIFI-Frankenstein/captured"
        private const val TAG = "HandshakeStorageMgr"
        private val MAC_REGEX_COLON = Regex("([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})")
        private val MAC_REGEX_RAW = Regex("([0-9A-Fa-f]{12})")
        private val ESSID_MAC_REGEX = Regex("^(.+?)_([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\.")
    }

    private val chrootManager = ChrootManager.get(context)
    private val metadataDb = HandshakeMetadataDbHelper(context)


    private fun chrootOrShell(cmd: String): Shell.Result {
        if (ChrootCapabilities.hasChrootTools(context)) {
            val chrootR = chrootManager.executeInChroot(cmd)
            if (chrootR.isSuccess) return chrootR
        }
        return try {
            Shell.cmd(cmd).exec()
        } catch (e: Exception) {
            object : Shell.Result() {
                override fun getCode() = 1
                override fun isSuccess() = false
                override fun getOut(): MutableList<String> = mutableListOf()
                override fun getErr(): MutableList<String> = mutableListOf()
            }
        }
    }

    fun ensureStorageDir() {
        if (!chrootOrShell("mkdir -p $STORAGE_DIR").isSuccess) {
            try {
                File(storageDirHost()).mkdirs()
            } catch (_: Exception) {
            }
        }
        migrateOldJvmFiles()
    }

    private fun migrateOldJvmFiles() {
        try {
            val oldDir = java.io.File(context.filesDir, "handshakes")
            if (!oldDir.exists()) return
            val olds = oldDir.listFiles { f ->
                f.isFile && (f.extension == "cap" || f.extension == "pcap" || f.extension == "hccapx")
            } ?: return
            for (file in olds) {
                val chrootPath = "$STORAGE_DIR/${file.name}"
                val exists = chrootOrShell("test -e '$chrootPath'")
                if (!exists.isSuccess) {
                    val jvmOk = copyViaJvmBlocking(file, file.name) != null
                    if (!jvmOk) {
                        chrootOrShell("cp '${HandshakeCaptureRunner.jvmPathToChroot(file.absolutePath)}' '$chrootPath' 2>/dev/null; true")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "migrateOldJvmFiles failed", e)
        }
    }

    suspend fun moveToStorage(capFilePath: String, essid: String?, bssid: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                ensureStorageDir()

                val hostSource = File(jvmPathOf(capFilePath))
                val sourceCheck = chrootOrShell("test -e '$capFilePath' && echo EXISTS")
                val sourceExists = hostSource.exists() ||
                        (sourceCheck.isSuccess && sourceCheck.out.firstOrNull()?.trim() == "EXISTS")
                if (!sourceExists) {
                    Log.e(TAG, "moveToStorage: cap file missing: $capFilePath")
                    return@withContext null
                }

                val safeEssid = essid?.replace(Regex("[^a-zA-Z0-9._-]"), "_")?.take(32) ?: "Unknown"
                val formattedBssid = bssid?.replace(":", "") ?: "000000000000"
                var destName = "${safeEssid}_${formattedBssid}.pcap"
                var counter = 1
                while (fileExistsInStorage(destName)) {
                    destName = "${safeEssid}_${formattedBssid}_$counter.cap"
                    counter++
                }

                val chrootDest = "$STORAGE_DIR/$destName"
                if (!copyIntoStorage(hostSource, destName)) {
                    Log.e(
                        TAG,
                        "copy to $chrootDest failed (chroot + JVM)"
                    )
                    return@withContext null
                }
                Log.d(TAG, "cp OK: $capFilePath -> $chrootDest")

                chrootDest
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move to storage", e)
                null
            }
        }

    suspend fun ensureChrootCopy(chrootPath: String): String? = withContext(Dispatchers.IO) {
        try {
            val fileName = File(chrootPath).name
            val resolvedPath = if (chrootPath.startsWith("/sdcard/")) chrootPath
            else "$STORAGE_DIR/$fileName"

            val check = chrootOrShell("test -e '$resolvedPath' && echo EXISTS")
            if (check.isSuccess && check.out.firstOrNull()?.trim() == "EXISTS") {
                return@withContext resolvedPath
            }


            val jvmFile = File(storageDirHost(), fileName)
            if (jvmFile.exists()) return@withContext resolvedPath

            if (!chrootPath.startsWith("/sdcard/")) {
                Log.w(TAG, "ensureChrootCopy: file not in chroot, attempting copy: $chrootPath")
                val cp =
                    chrootOrShell("cp \"$chrootPath\" \"$resolvedPath\" 2>/dev/null && echo CP_OK")
                if (cp.isSuccess && cp.out.firstOrNull()?.trim() == "CP_OK") {
                    Log.d(TAG, "ensureChrootCopy: $chrootPath -> $resolvedPath")
                    return@withContext resolvedPath
                }
            }

            Log.w(TAG, "ensureChrootCopy: file not found at $chrootPath")
            null
        } catch (e: Exception) {
            Log.e(TAG, "ensureChrootCopy failed", e)
            null
        }
    }

    private fun storageDirHost(): String =
        STORAGE_DIR.replaceFirst("/sdcard", "/storage/emulated/0")

    private fun jvmPathOf(chrootPath: String): String =
        chrootPath.replaceFirst("/sdcard", "/storage/emulated/0")

    private fun jvmFileExists(name: String): Boolean =
        File(storageDirHost(), name).exists()

    private fun fileExistsInStorage(name: String): Boolean {
        if (jvmFileExists(name)) return true
        return try {
            chrootOrShell("test -e '$STORAGE_DIR/$name'").isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun copyIntoStorage(hostSource: File, destName: String): Boolean =
        withContext(Dispatchers.IO) {
            val chrootDest = "$STORAGE_DIR/$destName"
            try {
                val cp = chrootOrShell(
                    "cp -f '${HandshakeCaptureRunner.jvmPathToChroot(hostSource.absolutePath)}' '$chrootDest' 2>&1 && echo CP_OK"
                )
                if (cp.isSuccess && cp.out.firstOrNull()?.trim() == "CP_OK") return@withContext true
            } catch (_: Exception) {
            }
            try {
                val dest = File(storageDirHost(), destName)
                dest.parentFile?.mkdirs()
                if (hostSource.exists()) {
                    hostSource.copyTo(dest, overwrite = true)
                    return@withContext dest.exists()
                }
            } catch (_: Exception) {
            }
            false
        }

    private fun copyViaJvmBlocking(source: File, destName: String): String? {
        return try {
            val dest = File(storageDirHost(), destName)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            if (dest.exists() && dest.length() > 0) "$STORAGE_DIR/$destName" else null
        } catch (e: Exception) {
            Log.e(TAG, "copyViaJvm failed: $destName", e)
            null
        }
    }

    suspend fun copyViaJvm(source: File, destName: String): String? =
        withContext(Dispatchers.IO) {
            copyViaJvmBlocking(source, destName)
        }

    private val CAP_EXTENSIONS = setOf("cap", "pcap", "pcapng", "hccapx", "22000", "pcapdump")
    private val CAP_EXTENSIONS_GREP = "pcapdump|pcapng|pcap|hccapx|22000|cap"

    private suspend fun listStorageFileNames(): Set<String> = listFileNamesIn(STORAGE_DIR)

    private suspend fun listFileNamesIn(dir: String): Set<String> = withContext(Dispatchers.IO) {
        val names = mutableSetOf<String>()
        val lsCmd = "ls -1 '$dir' 2>/dev/null"


        if (ChrootCapabilities.hasChrootTools(context)) {
            try {
                val result =
                    chrootManager.executeInChroot("$lsCmd | grep -iE '\\.($CAP_EXTENSIONS_GREP)'")
                if (result.isSuccess && result.out.any { it.isNotBlank() }) {
                    result.out.map { it.trim() }.filter { it.isNotEmpty() }.forEach { names.add(it) }
                    if (names.isNotEmpty()) return@withContext names
                }
            } catch (_: Exception) {
            }
        }


        try {
            val result = Shell.cmd("$lsCmd").exec()
            if (result.isSuccess && result.out.any { it.isNotBlank() }) {
                result.out.map { it.trim() }.filter { it.isNotEmpty() }.filter { name ->
                    name.substringAfterLast('.').lowercase() in CAP_EXTENSIONS
                }.forEach { names.add(it) }
                if (names.isNotEmpty()) return@withContext names
            }
        } catch (_: Exception) {
        }


        try {
            val hostDir = File(dir.replaceFirst("/sdcard", "/storage/emulated/0"))
            if (hostDir.isDirectory) {
                hostDir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in CAP_EXTENSIONS
                }?.forEach { names.add(it.name) }
            }
        } catch (_: Exception) {
        }

        names
    }

    private var storageInitialized = false

    suspend fun listHandshakes(): List<HandshakeItem> = withContext(Dispatchers.IO) {
        if (!storageInitialized) {
            try {
                ensureStorageDir()
            } catch (_: Exception) {
            }
            storageInitialized = true
        }
        val result = mutableListOf<HandshakeItem>()
        val seenNames = mutableSetOf<String>()

        val metaMap = metadataDb.getAll().associateBy { it.fileName }

        for ((name, meta) in metaMap) {
            result.add(meta.copy(filePath = "$STORAGE_DIR/$name", fileExists = true))
            seenNames.add(name)
        }

        val capFiles = listStorageFileNames()
        for (name in capFiles) {
            if (name in seenNames) continue
            val (essid, bssid) = parseFileName(name)
            result.add(
                HandshakeItem(
                    filePath = "$STORAGE_DIR/$name",
                    fileName = name,
                    bssid = bssid,
                    essid = essid,
                    fileSize = 0,
                    lastModified = 0,
                    fileExists = true
                )
            )
            seenNames.add(name)
        }

        result.sortedByDescending { it.lastModified }
    }

    suspend fun checkFileExistence(items: List<HandshakeItem>): List<HandshakeItem> =
        withContext(Dispatchers.IO) {
            try {
                val onDisk = listStorageFileNames()
                items.map { item ->
                    val exists = item.fileName in onDisk
                    if (exists != item.fileExists) item.copy(fileExists = exists) else item
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkFileExistence failed", e)
                items
            }
        }

    fun saveHandshakeMetadata(item: HandshakeItem) {
        val existed = metadataDb.get(item.fileName) != null
        metadataDb.saveOrUpdate(item)
        val hashTypes = buildString {
            if (item.hashPmkid != null) append("PMKID ")
            if (!item.hash22000.isNullOrBlank()) append("EAPOL ")
        }.trim().ifBlank { "none" }
        Log.i(
            TAG,
            "Handshake ${if (existed) "UPDATED" else "ADDED"}: " +
                "file=${item.fileName} path=${item.filePath} essid=${item.essid ?: "?"} " +
                "bssid=${item.bssid ?: "?"} size=${item.formattedSize} valid=${item.isValid} " +
                "hashes=${item.handshakeCount} eapol=${item.eapolCount} pmkid=${item.pmkidCount} " +
                "type=$hashTypes keyver=${item.keyver ?: "?"} " +
                "format=${item.originalFormat ?: "?"} channel=${item.channel ?: "?"} " +
                "band=${item.band ?: "?"} akm=${item.akm ?: "?"} cipher=${item.pairwiseCipher ?: "?"}"
        )
    }

    fun listSavedBssids(): Set<String> {
        return metadataDb.getAll().mapNotNull { it.bssid?.uppercase() }.toSet()
    }

    fun updateHandshakeHash22000(fileName: String, hash: String?) {
        metadataDb.updateHash22000(fileName, hash)
    }

    fun updateHandshakeHashPmkid(fileName: String, hash: String?) {
        metadataDb.updateHashPmkid(fileName, hash)
    }

    fun updateHandshakeValid(fileName: String, isValid: Boolean?) {
        metadataDb.updateValid(fileName, isValid)
    }

    fun updateHandshakeEssid(fileName: String, essid: String?) {
        metadataDb.updateEssid(fileName, essid)
    }

    fun updateHandshakeBssid(fileName: String, bssid: String?) {
        metadataDb.updateBssid(fileName, bssid)
    }

    fun updateHandshakeCounts(
        fileName: String,
        eapolCount: Int,
        pmkidCount: Int,
        handshakeCount: Int
    ) {
        metadataDb.updateCounts(fileName, eapolCount, pmkidCount, handshakeCount)
    }

    fun updateHandshakeKeyver(fileName: String, keyver: Int?) {
        metadataDb.updateKeyver(fileName, keyver)
    }

    fun updateHandshakeOriginalFormat(fileName: String, format: String?) {
        metadataDb.updateOriginalFormat(fileName, format)
    }

    fun updateHandshakeApsInFile(fileName: String, apsInFile: String?) {
        metadataDb.updateApsInFile(fileName, apsInFile)
    }

    fun updateHandshakeCracked(fileName: String, password: String?) {
        metadataDb.updateCracked(fileName, password)
    }

    fun updateHandshakeLocation(fileName: String, latitude: Double?, longitude: Double?) {
        metadataDb.updateLocation(fileName, latitude, longitude)
    }

    fun updateHandshakeMetadata(
        fileName: String,
        clients: String? = null,
        channel: Int? = null,
        band: String? = null,
        akm: String? = null,
        groupCipher: String? = null,
        pairwiseCipher: String? = null,
        rssi: Int? = null,
        eapolM1Count: Int = 0,
        eapolM2Count: Int = 0,
        eapolM3Count: Int = 0,
        eapolM4Count: Int = 0,
        beaconCount: Int = 0,
        assocReqCount: Int = 0,
        authCount: Int = 0,
        probeReqCount: Int = 0,
        hash16800: String? = null
    ) {
        metadataDb.updateMetadata(
            fileName, clients, channel, band, akm, groupCipher, pairwiseCipher,
            rssi, eapolM1Count, eapolM2Count, eapolM3Count, eapolM4Count,
            beaconCount, assocReqCount, authCount, probeReqCount, hash16800
        )
    }

    fun getHandshakeMeta(fileName: String): HandshakeItem? {
        return metadataDb.get(fileName)?.let { item ->
            if (!item.filePath.startsWith("/")) item.copy(filePath = "$STORAGE_DIR/${item.fileName}") else item
        }
    }

    private fun parseFileName(fileName: String): Pair<String?, String?> {
        val nameWithoutExt = fileName.substringBeforeLast('.')

        val macMatchColon = MAC_REGEX_COLON.find(nameWithoutExt)
        val bssidColon = macMatchColon?.groupValues?.get(1)?.uppercase()

        val macMatchRaw = MAC_REGEX_RAW.find(nameWithoutExt)
        val bssidRaw = macMatchRaw?.groupValues?.get(1)?.uppercase()?.chunked(2)?.joinToString(":")

        val bssid = bssidColon ?: bssidRaw

        val essidMacMatch = ESSID_MAC_REGEX.find(fileName)
        val essidFromRegex =
            essidMacMatch?.groupValues?.get(1)?.replace('_', ' ')?.takeIf { it.isNotBlank() }

        return Pair(essidFromRegex, bssid)
    }

    suspend fun deleteHandshake(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = File(filePath).name
            val chrootPath = "$STORAGE_DIR/$fileName"

            try {
                val res = chrootOrShell("rm -f '$chrootPath' 2>&1")
                if (res.isSuccess) return@withContext true
            } catch (_: Exception) {
            }

            try {
                File(storageDirHost(), fileName).delete().also { deleted ->
                    if (deleted) return@withContext true
                }
            } catch (_: Exception) {
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete: $filePath", e)
            false
        }
    }

    fun deleteHandshakeMetaOnly(fileName: String) {
        metadataDb.delete(fileName)
    }

    suspend fun deleteHandshakeEntryAndFile(filePath: String): Boolean {
        val file = File(filePath)
        val fileName = file.name
        metadataDb.delete(fileName)
        return deleteHandshake(filePath)
    }

    suspend fun deleteRawChrootFile(chrootPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            try {
                val res = chrootOrShell("rm -f '$chrootPath' 2>&1")
                if (res.isSuccess) return@withContext true
            } catch (_: Exception) {
            }
            try {
                val jvmFile = File(
                    chrootPath.replaceFirst("/sdcard", "/storage/emulated/0")
                )
                if (jvmFile.delete()) return@withContext true
            } catch (_: Exception) {
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete raw file: $chrootPath", e)
            false
        }
    }

    suspend fun getHandshakeFile(filePath: String): File? = withContext(Dispatchers.IO) {
        val file = File(filePath)

        if (filePath.startsWith("/sdcard/")) {
            val fileName = file.name

            try {
                val copied =
                    chrootManager.copyFileFromChrootToFilesDir(filePath, "handshakes/$fileName")
                if (copied != null && File(copied).exists()) return@withContext File(copied)
            } catch (_: Exception) {
            }

            try {
                val dest = File(context.cacheDir, "handshakes/$fileName")
                dest.parentFile?.mkdirs()
                val r = Shell.cmd("cp '$filePath' '${dest.absolutePath}' && echo CP_OK").exec()
                if (r.isSuccess && r.out.any { it.trim() == "CP_OK" } && dest.exists()) return@withContext dest
            } catch (_: Exception) {
            }

            val jvmFile = File(storageDirHost(), fileName)
            if (jvmFile.exists()) return@withContext jvmFile
            return@withContext null
        }

        if (file.exists()) return@withContext file
        null
    }

    suspend fun detectPmkid(capFilePath: String): Boolean = withContext(Dispatchers.IO) {
        if (!ChrootCapabilities.hasChrootTools(context)) {
            Log.d(TAG, "detectPmkid: skipped (no chroot tools)")
            return@withContext false
        }
        try {
            val cmd = "hcxpcapngtool -o /dev/stdout \"$capFilePath\" 2>/dev/null"
            val result = chrootManager.executeInChroot(cmd)
            result.out.any { it.startsWith("WPA*03\t") || it.startsWith("WPA03\t") }
        } catch (e: Exception) {
            Log.e(TAG, "PMKID detection failed", e)
            false
        }
    }

    suspend fun extractPmkidHash(capFilePath: String, outputPath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!ChrootCapabilities.hasChrootTools(context)) {
                Log.d(TAG, "extractPmkidHash: skipped (no chroot tools)")
                return@withContext false
            }
            try {
                val parentDir = outputPath.substringBeforeLast("/")
                chrootManager.executeInChroot("mkdir -p $parentDir")
                val cmd = "hcxpcapngtool -o \"$outputPath\" \"$capFilePath\" 2>&1"
                val result = chrootManager.executeInChroot(cmd)
                val combined = (result.out + result.err).joinToString("\n")
                val success = combined.contains("PMKID", ignoreCase = true) ||
                        chrootManager.executeInChroot("test -s '$outputPath'").isSuccess
                Log.d(TAG, "PMKID extract: success=$success")
                success
            } catch (e: Exception) {
                Log.e(TAG, "PMKID extraction failed", e)
                false
            }
        }

    fun saveCrackedPassword(bssid: String, password: String) {
        val prefs = context.getSharedPreferences("handshake_cracked", Context.MODE_PRIVATE)
        prefs.edit().putString("cracked_$bssid", password).apply()
    }

    fun getCrackedPassword(bssid: String?): String? {
        if (bssid == null) return null
        val prefs = context.getSharedPreferences("handshake_cracked", Context.MODE_PRIVATE)
        return prefs.getString("cracked_$bssid", null)
    }

    fun updateOhcUploadStatus(
        fileName: String,
        uploaded: Boolean,
        requestId: String?,
        email: String? = null
    ) {
        metadataDb.updateOhcUploadStatus(fileName, uploaded, requestId, email)
    }

    fun updateWpaSecUploadStatus(fileName: String, uploaded: Boolean, key: String?) {
        metadataDb.updateWpaSecUploadStatus(fileName, uploaded, key)
    }

    fun updateWpaSecCheckResult(
        fileName: String,
        checked: Boolean,
        found: Boolean,
        password: String?
    ) {
        metadataDb.updateWpaSecCheckResult(fileName, checked, found, password)
    }

    fun getHandshakesNotUploadedToWpaSec(): List<HandshakeItem> {
        return metadataDb.getNotUploadedToWpaSec()
    }

    data class OrphanFile(
        val fileName: String,
        val filePath: String
    )

    suspend fun getOrphanFiles(): List<OrphanFile> = withContext(Dispatchers.IO) {
        val dbEntries = metadataDb.getAll().map { it.fileName }.toSet()
        val orphans = mutableListOf<OrphanFile>()

        val capFiles = listStorageFileNames()
        for (name in capFiles) {
            if (name !in dbEntries) {
                orphans.add(OrphanFile(name, "$STORAGE_DIR/$name"))
            }
        }
        orphans
    }

    suspend fun getRawCaptureOrphans(): List<OrphanFile> = withContext(Dispatchers.IO) {
        val orphans = mutableListOf<OrphanFile>()
        for (name in listFileNamesIn(CAPTURE_DIR)) {
            orphans.add(OrphanFile(name, "$CAPTURE_DIR/$name"))
        }
        orphans
    }
}
